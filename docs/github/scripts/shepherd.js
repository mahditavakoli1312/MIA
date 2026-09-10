// .github/scripts/shepherd.js
// Managed by MIA — kept byte-for-byte in sync with docs/github/scripts/.
//
// THE GUARANTEE THAT THE CONVERSATION IS NEVER INTERRUPTED.
//
// Everything else in this repo moves work forward when something happens: a comment, a label, a
// merged pull request. This one exists for when NOTHING happens — a runner killed mid-flight, a
// label event GitHub never delivered, a role that crashed before it could hand over, a
// dependency that closed while Actions was disabled. Those are the failures that used to end a
// task, because they produce no error for anyone to see. Silence looks exactly like success.
//
// So every half hour it asks one question of every open issue: is somebody acting on this, and
// are they late? The answer comes from the state table in agent-voice.js — one owner and one
// deadline per state — and where the answer is "no", the shepherd RESTORES OWNERSHIP. It does
// not do the work.
//
// WHAT IT MAY NOT DO, deliberately, and this is the important part: it never opens an issue,
// never closes one, never merges anything, never edits a brief. A watchdog that can also act
// becomes a second, unpredictable teammate whose reasoning nobody can follow — and the moment
// two things can change an issue for reasons of their own, nobody can tell what the system is
// doing any more. It re-queues, it dispatches, it names an owner, and it says so once.
//
// It is also quiet by design. A sweep that comments on every issue every thirty minutes is noise
// people learn to scroll past, which would defeat the entire point of it: one comment per issue
// per sweep at most, and none at all for an issue that is healthy.

const {
  STATES,
  stateOfLabels,
  slaMinutes,
  parseHandoff,
} = require("./agent-voice.js");
const { postComment } = require("./ai-provider.js");
const { updateLedger, readLedger } = require("./ledger.js");

const repo = process.env.REPO;
const githubToken = process.env.GITHUB_TOKEN;
const defaultBranch = process.env.DEFAULT_BRANCH || "main";
const runUrl = process.env.RUN_URL || "";
/** Reported but not acted on, for a repo that wants to watch the shepherd before trusting it. */
const dryRun = /^(1|true|yes)$/i.test(process.env.SHEPHERD_DRY_RUN || "");
const pauseTimeout = process.env.AGENT_PAUSE_TIMEOUT || "72h";

/** After this many consecutive sweeps finding the same state late, stop nudging and triage. */
const STRIKES_BEFORE_TRIAGE = 3;

/** The line an owned pause writes for the answer it will proceed on. */
const DEFAULT_ANSWER_RE = /\*\*اگر جوابی نیاید:\*\*\s*([^\n]+)/;

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

const label = (n, labels) =>
  gh(`/issues/${n}/labels`, { method: "POST", body: { labels } }).catch((err) =>
    console.error(`Could not label #${n}: ${err.message}`)
  );
const unlabel = (n, name) =>
  gh(`/issues/${n}/labels/${encodeURIComponent(name)}`, { method: "DELETE" }).catch(() => {});

const dispatch = (workflow, inputs) =>
  gh(`/actions/workflows/${workflow}/dispatches`, {
    method: "POST",
    body: { ref: defaultBranch, ...(inputs ? { inputs } : {}) },
  })
    .then(() => true)
    .catch((err) => {
      console.error(`Could not dispatch ${workflow}: ${err.message}`);
      return false;
    });

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

/** Is a run for this repo actually alive? An `agent-running` label outlives its runner. */
async function hasLiveRun() {
  const runs = await gh("/actions/runs?status=in_progress&per_page=20").catch(() => null);
  const queued = await gh("/actions/runs?status=queued&per_page=20").catch(() => null);
  const names = [...(runs?.workflow_runs || []), ...(queued?.workflow_runs || [])].map(
    (r) => r.path || r.name || ""
  );
  return names.some((n) => n.includes("agent-issue-worker"));
}

const minutesSince = (iso) => {
  const t = Date.parse(iso || "");
  return Number.isFinite(t) ? (Date.now() - t) / 60000 : Infinity;
};

/**
 * Everything the shepherd knows about one issue before deciding anything.
 *
 * The handoff is preferred for the OWNER and the labels for the STATE, which is not a
 * contradiction: the labels are what the machinery acts on, while the handoff is what the last
 * colleague actually said they expected. When they disagree, the state is the truth about where
 * the work is and the handoff is the truth about who was asked.
 */
async function inspect(issue) {
  const labels = (issue.labels || []).map((l) => (typeof l === "string" ? l : l.name));
  const comments = await gh(`/issues/${issue.number}/comments?per_page=100`).catch(() => []);
  const bodies = (comments || []).map((c) => c.body || "");
  // The last comment that CARRIES a handoff, not the last comment: a token-spend receipt or a
  // human's "thanks" must not read as the team going silent.
  let handoff = null;
  for (let i = bodies.length - 1; i >= 0 && !handoff; i -= 1) handoff = parseHandoff(bodies[i]);

  const state = stateOfLabels(labels) || (handoff ? null : null);
  const spec = state ? STATES[state] : null;
  const owner = handoff ? handoff.to : spec ? spec.owner : null;
  // Movement is the later of "the last time a colleague spoke" and "the last time GitHub saw a
  // change", so a label flipped without a comment still counts as activity.
  const idle = Math.min(
    minutesSince(handoff && handoff.at ? handoff.at : null),
    minutesSince(issue.updated_at)
  );
  const defaultAnswer = (() => {
    for (let i = bodies.length - 1; i >= 0; i -= 1) {
      const m = DEFAULT_ANSWER_RE.exec(bodies[i]);
      if (m) return m[1].trim();
    }
    return "";
  })();

  return { labels, state, spec, owner, idle, handoff, defaultAnswer };
}

/** One comment per issue per sweep, and only when something was actually done about it. */
async function nudge(issue, body, handoff) {
  if (dryRun) {
    console.log(`[dry run] #${issue.number}: ${body.split("\n")[0]}`);
    return;
  }
  await postComment({
    repo,
    issueNumber: issue.number,
    token: githubToken,
    body,
    handoff,
  }).catch((err) => console.error(`Could not comment on #${issue.number}: ${err.message}`));
}

// --- What to do about each kind of lateness ----------------------------------------------------

async function reviveDeadRun(issue, live) {
  if (live) {
    console.log(`#${issue.number} is agent-running and a worker run is alive — leaving it.`);
    return false;
  }
  if (!dryRun) {
    await unlabel(issue.number, "agent-running");
    await label(issue.number, ["by-agent"]);
  }
  await nudge(
    issue,
    "🛠️ اجرایی که این ایشو را برداشته بود تمام نشد — نه merge شد، نه خطایی گزارش کرد؛ یعنی " +
      "runner وسط کار مرد. برچسب «در حال کار» را برداشتم و دوباره گذاشتمش در صف. هرچه روی شاخه " +
      "نوشته شده سر جایش است.",
    { from: "tec", to: "tec", next: "این ایشو را از نو بردار", issue: issue.number, sla: "60m" }
  );
  return true;
}

async function applyPauseDefault(issue, info) {
  const answer =
    info.defaultAnswer ||
    "با محتاطانه‌ترین برداشت از همان چیزی که در ایشو نوشته شده ادامه می‌دهیم";
  if (!dryRun) {
    await unlabel(issue.number, "needs-human");
    await label(issue.number, ["by-agent"]);
  }
  await nudge(
    issue,
    `🧭 ${pauseTimeout} از پرسشم گذشت و جوابی نیامد، پس همان‌طور که گفته بودم با فرضِ پیش‌فرض ادامه ` +
      `می‌دهیم: **${answer}**\n\n` +
      "اگر این فرض درست نیست، همین حالا یک کامنت بگذارید — کار برمی‌گردد روی همان تصمیم. یک تیم " +
      "که تا ابد پشت سکوت می‌ایستد کار را تمام نکرده، فقط متوقفش کرده.",
    { from: "po", to: "tec", next: `با این فرض ادامه بده: ${answer}`, issue: issue.number, sla: "60m" }
  );
  return true;
}

async function adoptOrphan(issue, info) {
  // No state at all: nothing has claimed this issue since it was opened. That is not a stall to
  // report, it is an issue nobody ever picked up — so the PO gets it, which is where a request
  // with no plan belongs.
  if (!dryRun) await label(issue.number, ["needs-po", "by-agent"]);
  await nudge(
    issue,
    "🧭 این ایشو باز است و هیچ‌کس رویش کار نمی‌کرد. برمی‌دارمش: اول بریفش را قابل‌اجرا می‌کنم، بعد " +
      "می‌رود در صف TEC.",
    { from: "po", to: "po", next: "بریف را قابل‌اجرا کن و بگذارش در صف", issue: issue.number, sla: "60m" }
  );
  return true;
}

async function escalate(issue, info, strikes) {
  const ok = await (dryRun
    ? Promise.resolve(true)
    : dispatch("agent-issue-worker.yml", null));
  await nudge(
    issue,
    `⏱️ این ایشو ${Math.round(info.idle)} دقیقه در حالت \`${info.state}\` بی‌حرکت مانده، که از ` +
      `مهلت \`${info.spec.sla}\` گذشته است. ` +
      (ok
        ? "صف را دوباره راه انداختم."
        : "نتوانستم صف را راه بیندازم؛ در سوییپ بعدی دوباره امتحان می‌کنم.") +
      (strikes >= 2 ? " اگر دفعهٔ بعد هم همین‌جا مانده باشد، می‌فرستمش برای تریاژ." : ""),
    {
      from: "po",
      to: info.owner || info.spec.owner,
      next: info.handoff ? info.handoff.next : "این کار را جلو ببر",
      issue: issue.number,
      sla: info.spec.sla,
    }
  );
  return true;
}

// --- The sweep ---------------------------------------------------------------------------------

async function main() {
  if (!repo || !githubToken) {
    console.error("REPO and GITHUB_TOKEN are required.");
    process.exit(1);
  }

  const issues = await openIssues();
  const live = await hasLiveRun();
  let acted = 0;
  let healthy = 0;

  for (const issue of issues) {
    const info = await inspect(issue);

    // Nothing has claimed it at all.
    if (!info.state && !info.owner) {
      // Give a brand-new issue a moment: the label that claims it usually arrives seconds later,
      // from the app or from another workflow.
      if (info.idle < 30) continue;
      if (await adoptOrphan(issue, info)) acted += 1;
      continue;
    }

    const spec = info.spec || STATES[String(info.state)];
    if (!spec) {
      healthy += 1;
      continue;
    }
    if (spec.terminal) {
      healthy += 1;
      continue;
    }

    // States that wait on a thing rather than a clock. `blocked` is unblock.js's business, and
    // running it here is cheaper than explaining to a human why nothing happened.
    if (spec.sla === null) {
      if (info.state === "blocked" && !dryRun) {
        await dispatch("unblock-dependents.yml", null);
      }
      healthy += 1;
      continue;
    }

    const limit = slaMinutes(spec.sla);
    if (limit === null || info.idle < limit) {
      healthy += 1;
      continue;
    }

    // A retry the triage step deliberately spaced out is not a stall.
    const ledger = await readLedger({ repo, issueNumber: issue.number, token: githubToken });
    if (ledger.notBefore && Date.now() < Date.parse(String(ledger.notBefore).replace(" UTC", "Z").replace(" ", "T"))) {
      console.log(`#${issue.number} is waiting until ${ledger.notBefore} — not a stall.`);
      healthy += 1;
      continue;
    }

    const strikes = ledger.shepherdState === info.state ? (ledger.shepherdStrikes || 0) + 1 : 1;
    let didSomething = false;

    if (info.state === "agent-running") {
      didSomething = await reviveDeadRun(issue, live);
    } else if (info.state === "needs-human") {
      didSomething = await applyPauseDefault(issue, info);
    } else if (strikes >= STRIKES_BEFORE_TRIAGE) {
      // Nudging has not worked three times running. Something about this issue is wrong in a way
      // a dispatch cannot fix, so it goes to the one thing that can change the issue itself.
      if (!dryRun) await label(issue.number, ["agent-failed"]);
      await nudge(
        issue,
        `⏱️ سه سوییپ پشت سر هم این ایشو در \`${info.state}\` مانده و تکان نخورده. دیگر تلنگر ` +
          "نمی‌زنم — می‌سپارمش به تریاژ تا تصمیم بگیرد دوباره تلاش کند، بشکندش، یا بریف را بازتعریف کند.",
        { from: "po", to: "po", next: "علت گیر کردن این ایشو را تریاژ کن", issue: issue.number, sla: "60m" }
      );
      didSomething = true;
    } else if (info.state === "needs-po") {
      // The PO's re-scope runs inside TEC's own claim, so restarting the queue is what runs it.
      didSomething = await escalate(issue, info, strikes);
    } else if (info.state === "needs-split") {
      if (!dryRun) await dispatch("decompose-brief.yml", { split_issue: String(issue.number) });
      await nudge(
        issue,
        "⏱️ این ایشو منتظر شکسته‌شدن مانده بود. تجزیهٔ PO را دوباره راه انداختم.",
        { from: "po", to: "po", next: "این ایشو را بشکن", issue: issue.number, sla: "60m" }
      );
      didSomething = true;
    } else if (info.state === "brief") {
      if (!dryRun) await dispatch("decompose-brief.yml", { split_issue: "" });
      await nudge(
        issue,
        "⏱️ این نیت تجزیه نشده مانده بود. دوباره فرستادمش برای تجزیه.",
        { from: "po", to: "po", next: "این نیت را به ایشوهای کوچک بشکن", issue: issue.number, sla: "60m" }
      );
      didSomething = true;
    } else {
      didSomething = await escalate(issue, info, strikes);
    }

    if (didSomething && !dryRun) {
      await updateLedger({
        repo,
        issueNumber: issue.number,
        token: githubToken,
        patch: { shepherdState: info.state, shepherdStrikes: strikes },
      });
    }
    if (didSomething) acted += 1;
  }

  console.log(
    `Shepherd swept ${issues.length} open issue(s): ${acted} needed a push, ${healthy} were fine.` +
      (runUrl ? ` ${runUrl}` : "")
  );
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
