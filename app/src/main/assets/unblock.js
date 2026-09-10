// .github/scripts/unblock.js
// Managed by MIA — kept byte-for-byte in sync with docs/github/scripts/.
//
// THE FIRST DEAD END: a child issue that waits forever for work that is already finished.
//
// decompose-brief.js writes "⛔ blocked by #12" into a child's body and labels it `blocked`.
// Nothing ever took that label off again. So a brief with three dependent issues delivered its
// first one and then stopped — no error, no failed run, just a queue that had nothing in it and
// two issues nobody was waiting on. The most expensive failures here are the quiet ones.
//
// This runs when an issue closes and asks the only question that matters: who was waiting for
// this? Every dependent whose blockers have all closed goes straight back into TEC's queue.
//
// The body is the source of truth, not the label. A human can remove `blocked` by hand, or add
// it for their own reasons; the "blocked by #n" line is what decompose-brief actually wrote and
// what the plan comment refers to. Reading the body also means a dependent with two blockers is
// released only when BOTH are done — a label can only ever say "blocked", not "by what".

const {
  postComment,
  startTecQueue,
} = require("./ai-provider.js");
const { updateLedger } = require("./ledger.js");

const repo = process.env.REPO;
const githubToken = process.env.GITHUB_TOKEN;
/** The issue that just closed. Empty on the cron sweep, which checks every blocked issue. */
const closedNumber = (process.env.CLOSED_ISSUE || "").trim();

const BLOCKED_LABEL = "blocked";
const AGENT_LABEL = "by-agent";
/** States where queueing would fight with whoever is already acting. */
const HANDS_OFF = ["needs-human", "agent-running", "needs-po", "needs-split"];

async function gh(path, { method = "GET", body } = {}) {
  const res = await fetch(`https://api.github.com/repos/${repo}${path}`, {
    method,
    headers: {
      authorization: `Bearer ${githubToken}`,
      accept: "application/vnd.github+json",
      ...(body ? { "content-type": "application/json" } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  if (!res.ok) throw new Error(`GitHub ${method} ${path} → HTTP ${res.status}`);
  return res.status === 204 ? null : res.json();
}

/** Every open issue, paged. A repo with hundreds is still one or two calls. */
async function openIssues() {
  const all = [];
  for (let page = 1; page <= 10; page += 1) {
    const batch = await gh(`/issues?state=open&per_page=100&page=${page}`);
    if (!batch || batch.length === 0) break;
    all.push(...batch.filter((i) => !i.pull_request));
    if (batch.length < 100) break;
  }
  return all;
}

/**
 * The issue numbers one body says it is blocked by.
 *
 * Matches the line decompose-brief.js writes ("⛔ blocked by #4, #7") and the plain English a
 * person would type into an issue, because both appear in real repos and a dependency a human
 * wrote by hand deserves to be honoured exactly as much as one a model wrote.
 */
function blockersOf(body) {
  const text = String(body || "");
  const numbers = new Set();
  const line = /blocked by\s+((?:#\d+[\s,،and]*)+)/gi;
  let m;
  while ((m = line.exec(text)) !== null) {
    for (const hit of m[1].match(/#\d+/g) || []) numbers.add(Number(hit.slice(1)));
  }
  return [...numbers];
}

const labelsOf = (issue) => (issue.labels || []).map((l) => (typeof l === "string" ? l : l.name));

async function main() {
  if (!repo || !githubToken) {
    console.error("REPO and GITHUB_TOKEN are required.");
    process.exit(1);
  }

  const candidates = (await openIssues()).filter((issue) => {
    const blockers = blockersOf(issue.body);
    if (blockers.length === 0) return false;
    // On the close event, only the dependents of THAT issue are interesting; the daily sweep
    // looks at everything, which is what catches closes that happened while Actions was off.
    return closedNumber ? blockers.includes(Number(closedNumber)) : true;
  });

  if (candidates.length === 0) {
    console.log(closedNumber ? `Nothing was waiting on #${closedNumber}.` : "Nothing is blocked.");
    return;
  }

  let released = 0;

  // One lookup per distinct blocker, not per dependent: five children of the same parent would
  // otherwise ask GitHub the same question five times.
  const seen = new Map();
  const stateOf = async (number) => {
    if (!seen.has(number)) {
      seen.set(
        number,
        await gh(`/issues/${number}`).catch(() => ({ state: "open", state_reason: null }))
      );
    }
    return seen.get(number);
  };

  for (const issue of candidates) {
    const labels = labelsOf(issue);
    if (HANDS_OFF.some((l) => labels.includes(l))) {
      console.log(`#${issue.number} is ${labels.join("/")} — leaving it to whoever holds it.`);
      continue;
    }

    const blockers = blockersOf(issue.body);
    const states = await Promise.all(blockers.map((n) => stateOf(n)));
    const open = blockers.filter((n, i) => states[i].state !== "closed");

    if (open.length > 0) {
      await updateLedger({
        repo,
        issueNumber: issue.number,
        token: githubToken,
        patch: {
          state: BLOCKED_LABEL,
          owner: "tec",
          next: `منتظر ${open.map((n) => `#${n}`).join("، ")}`,
        },
      });
      console.log(`#${issue.number} still waits for ${open.join(", ")}.`);
      continue;
    }

    // A dependency closed as "not planned" still unblocks — but it is said out loud, because the
    // child was written assuming that work would exist, and silently building on a promise that
    // was cancelled is how a plan quietly stops matching reality.
    const abandoned = blockers.filter((n, i) => states[i].state_reason === "not_planned");

    await gh(`/issues/${issue.number}/labels/${encodeURIComponent(BLOCKED_LABEL)}`, {
      method: "DELETE",
    }).catch(() => {});
    await gh(`/issues/${issue.number}/labels`, {
      method: "POST",
      body: { labels: [AGENT_LABEL] },
    }).catch((err) => console.error(`Could not queue #${issue.number}: ${err.message}`));

    const done = blockers.map((n) => `#${n}`).join("، ");
    await postComment({
      repo,
      issueNumber: issue.number,
      token: githubToken,
      body:
        `🧭 وابستگی‌های این کار تمام شد (${done})، پس گذاشتمش در صف TEC.` +
        (abandoned.length
          ? `\n\n⚠️ ${abandoned.map((n) => `#${n}`).join("، ")} به‌عنوان «انجام نمی‌شود» بسته شده — ` +
            "این ایشو با فرضِ وجودِ آن نوشته شده بود، پس قبل از پیاده‌سازی یک بار فرض‌هایش را نگاه کن."
          : ""),
      handoff: {
        from: "po",
        to: "tec",
        next: "این ایشو آزاد شد — شروع کن",
        issue: issue.number,
        sla: "60m",
      },
    });
    await updateLedger({
      repo,
      issueNumber: issue.number,
      token: githubToken,
      patch: { state: AGENT_LABEL, owner: "tec", next: "شروع پیاده‌سازی" },
    });
    console.log(`#${issue.number} unblocked and queued.`);
    released += 1;
  }

  // `by-agent` was applied with GITHUB_TOKEN, which starts no workflow run. Without this the
  // issue this sweep just freed would wait for the worker's timer — up to half an hour of a
  // queue that has something in it and nothing running.
  if (released > 0) await startTecQueue({ repo, token: githubToken });
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
