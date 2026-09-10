// .github/scripts/ledger.js
// Managed by MIA — kept byte-for-byte in sync with docs/github/scripts/.
//
// ONE COMMENT THAT ALWAYS SAYS WHERE THIS WORK STANDS.
//
// An issue that has been through two QC rounds and a PO re-scope has twenty comments on it, and
// the question a human actually arrives with — "is anyone working on this, and what are they
// waiting for?" — is answered by none of them individually. So every role edits one comment in
// place, and that comment is the summary.
//
// It is DERIVED state, and that is a deliberate constraint rather than a caveat: rounds are
// counted from QC's own marked comments, cycles from the PO's, ownership from the handoff
// footers. Anything here can be rebuilt by reading the thread, so a lost, mangled or
// hand-edited ledger costs a reader some scrolling and costs the machinery nothing.
//
// Two consequences follow from "it is a view":
//   • It carries NO handoff footer. Ownership is stated by the role that took the decision, in
//     its own message; a second copy that could disagree with the first is worse than none. The
//     ledger reports the owner, it never assigns one.
//   • Nothing here may throw. A failed ledger update is logged and swallowed — no view is worth
//     stopping the work for.

const { SEATS } = require("./agent-voice.js");

/** Finds the ledger. Also the anchor the shepherd greps for. */
const MARKER = "<!-- mia:state -->";
/** The machine half: the whole record as JSON, so a patch never has to re-parse the table. */
const DATA_OPEN = "<!-- mia:state-data ";
const DATA_CLOSE = " -->";

/** Every field the ledger knows how to show, in the order the table shows them. */
const FIELDS = [
  ["state", "وضعیت"],
  ["owner", "مالک فعلی"],
  ["next", "قدم بعدی"],
  ["reworkRound", "دور بازکاری"],
  ["poCycles", "بازتعریف PO"],
  ["attempts", "تلاش TEC"],
  ["branchOrPr", "شاخه/PR"],
  ["lastMove", "آخرین حرکت"],
];

async function gh(repo, path, token, { method = "GET", body } = {}) {
  const res = await fetch(`https://api.github.com/repos/${repo}${path}`, {
    method,
    headers: {
      authorization: `Bearer ${token}`,
      accept: "application/vnd.github+json",
      ...(body ? { "content-type": "application/json" } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  if (!res.ok) throw new Error(`GitHub ${method} ${path} → HTTP ${res.status}`);
  return res.status === 204 ? null : res.json();
}

/** The stored record inside one comment body, or null when it is not a ledger. */
function extractData(body) {
  const text = String(body || "");
  const start = text.indexOf(DATA_OPEN);
  if (start === -1) return text.includes(MARKER) ? {} : null;
  const end = text.indexOf(DATA_CLOSE, start);
  if (end === -1) return {};
  try {
    return JSON.parse(text.slice(start + DATA_OPEN.length, end));
  } catch {
    // A hand-edited or truncated payload: keep the comment, start the record again. Losing a
    // summary is survivable; refusing to write one from then on is not.
    return {};
  }
}

/**
 * The issue's ledger comment, oldest first.
 *
 * Oldest wins on purpose. Two ledgers can only exist because two roles created one in the same
 * second, and every later update should converge on one of them — picking the oldest is the only
 * choice both racers can make independently and still agree.
 */
async function findLedger({ repo, issueNumber, token }) {
  const comments = await gh(repo, `/issues/${issueNumber}/comments?per_page=100`, token);
  for (const comment of comments || []) {
    if ((comment.body || "").includes(MARKER)) {
      return { id: comment.id, data: extractData(comment.body) || {} };
    }
  }
  return null;
}

/** Reads the record without writing. Returns {} when there is no ledger yet. */
async function readLedger({ repo, issueNumber, token }) {
  try {
    const found = await findLedger({ repo, issueNumber, token });
    return found ? found.data : {};
  } catch (err) {
    console.error(`Could not read the ledger on #${issueNumber}: ${err.message}`);
    return {};
  }
}

const cell = (value) =>
  value === undefined || value === null || value === ""
    ? "—"
    : String(value).replace(/\|/g, "\\|").replace(/\n+/g, " ").slice(0, 200);

/** The owner as a colleague is addressed, not as an internal key. */
function ownerCell(owner) {
  if (!owner) return "—";
  if (owner === "human") return "شما";
  const s = SEATS[owner];
  return s ? `${s.emoji} ${s.name} (${s.handle})` : cell(owner);
}

function render(data) {
  const rows = FIELDS.map(([key, label]) => {
    const value = key === "owner" ? ownerCell(data.owner) : cell(data[key]);
    return `| ${label} | ${value} |`;
  });
  const notBefore = data.notBefore
    ? `\n\n<sub>تلاش بعدی زودتر از \`${cell(data.notBefore)}\` شروع نمی‌شود.</sub>`
    : "";
  return [
    MARKER,
    "### 📋 وضعیت این کار",
    "",
    "| | |",
    "| --- | --- |",
    ...rows,
    notBefore,
    "",
    "<sub>این کامنت یک نمای خلاصه است و در جا به‌روز می‌شود — تصمیم‌ها همان‌جایی‌اند که گرفته شدند.</sub>",
    `${DATA_OPEN}${JSON.stringify(data)}${DATA_CLOSE}`,
  ].join("\n");
}

/**
 * Merges `patch` into the issue's ledger, creating the comment the first time.
 *
 * `lastMove` is stamped here rather than by callers: a summary whose "last movement" is whatever
 * the last writer felt like claiming would be exactly as useful as no timestamp at all.
 */
async function updateLedger({ repo, issueNumber, token, patch }) {
  if (!repo || !issueNumber || !token) return null;
  try {
    const found = await findLedger({ repo, issueNumber, token });
    const data = {
      ...(found ? found.data : {}),
      ...patch,
      lastMove: new Date().toISOString().replace("T", " ").slice(0, 16) + " UTC",
    };
    const body = render(data);
    if (found) {
      await gh(repo, `/issues/comments/${found.id}`, token, { method: "PATCH", body: { body } });
    } else {
      await gh(repo, `/issues/${issueNumber}/comments`, token, { method: "POST", body: { body } });
    }
    return data;
  } catch (err) {
    console.error(`Could not update the ledger on #${issueNumber}: ${err.message}`);
    return null;
  }
}

module.exports = {
  MARKER,
  FIELDS,
  extractData,
  render,
  findLedger,
  readLedger,
  updateLedger,
};
