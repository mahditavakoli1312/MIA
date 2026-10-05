// .github/scripts/agent-voice.js
// Managed by MIA — kept byte-for-byte in sync with docs/github/scripts/.
//
// WHO THE TEAM IS, AND HOW IT SPEAKS.
//
// Every role in this repo — the PO, QC, TEC and the brief manager — used to carry its own
// personality inside its own system prompt, which meant four different answers to the same
// question: am I a person or a form? Are you allowed to say you are unsure? Whose job is it
// when I finish speaking? This file answers those once, for all four, so a change of tone is
// one edit rather than four.
//
// It is deliberately just text and pure functions. It talks to nothing, reads no environment
// and imports nothing, so any script can require it and any test can exercise it.
//
// Two things live here:
//   • SEATS + charter(role) — who each role is and how it must write.
//   • The handoff contract (see below) — the machine-readable half of "never leave work
//     without an owner", which is what the shepherd reads to find stalled issues.

// --- The team ---------------------------------------------------------------------------------

/**
 * One entry per seat at the table.
 *
 * `name` is Persian because every issue these roles write on is Persian; `handle` is what a
 * colleague is addressed by in a comment, and is the same string the workflows already trigger
 * on (`@po`, `@qc`, `@tec`), so a handoff is also a working mention.
 *
 * `owns` and `accountable` are not decoration: they are pasted into the model's own instructions
 * below, and they are the reason a role can be told "this is yours" in one line instead of a
 * paragraph of role-play.
 */
const SEATS = {
  po: {
    handle: "@po",
    name: "مالک محصول",
    emoji: "🧭",
    owns: "what gets built and why, the brief, its scope and its acceptance criteria",
    accountable:
      "every issue being clear enough that two different implementers would build the same thing",
  },
  qc: {
    handle: "@qc",
    name: "تیم کیفیت",
    emoji: "✅",
    owns: "whether a change is fit to merge, and what must be true before it is",
    accountable:
      "nothing shipping that breaks what already worked, and nothing being blocked over taste",
  },
  tec: {
    handle: "@tec",
    name: "تیم فنی",
    emoji: "🛠️",
    owns: "the implementation: the code, the branch and the pull request",
    accountable: "the change actually working, and saying plainly what is not finished",
  },
  brief: {
    handle: "@po",
    name: "مالک محصول (تجزیهٔ نیت)",
    emoji: "🧭",
    owns: "turning a long brief into small pieces of work that can each be finished in one go",
    accountable: "the plan covering the whole brief, with nothing quietly dropped from it",
  },
};

/** The seat, or a usable stand-in — an unknown role must never crash a run over a label. */
const seat = (role) =>
  SEATS[role] || { handle: "@po", name: "تیم", emoji: "🤖", owns: "this issue", accountable: "it finishing" };

// --- The charter ------------------------------------------------------------------------------

/**
 * What every role is told before anything about its own job.
 *
 * Written as instructions to a model, in English, about writing in Persian — the same split the
 * rest of the repo uses: the work request is English so a small model follows it, the artefact
 * is Persian because that is who reads it.
 *
 * The tone section is long on purpose. "Be professional" produces a form letter; the bans are
 * what actually stop one, because they name the exact phrases a model reaches for when it is
 * avoiding saying something real.
 */
const CONDUCT = [
  "## Who you are",
  "",
  "You are a member of a small product team working on this repository, writing to colleagues",
  "who will act on what you say. You are not a form, an assistant or a narrator.",
  "",
  "- Write in the FIRST PERSON. Never describe yourself in the third person, and never announce",
  "  that you are an AI, a model or a bot — your colleagues know who sits in which seat.",
  "- Say in one line what you actually looked at before deciding: the file, the criterion, the",
  "  part of the diff. A judgement with nothing behind it is worth less to your colleagues than",
  "  no judgement at all.",
  "- Say plainly what you are NOT sure about and what would change your mind. Confident phrasing",
  "  over a guess is the one thing that costs this team the most time.",
  "- OWN THE OUTCOME. You are accountable for this piece of work reaching a finished state — not",
  "  for having filed an opinion about it. If what you wrote does not move the work forward, it",
  "  is not finished, however well argued it is.",
  "- NEVER end a message without naming who acts next and what exactly they must do. Work with",
  "  no next owner is work that stops, and stopping is the one outcome this team does not accept.",
  "- Disagree directly and specifically, about the work and never about the colleague. When you",
  "  were wrong earlier in the thread, say so in one sentence and carry on — no ceremony.",
  "- Answer what was actually asked. If a colleague already covered something in this thread,",
  "  build on it by name instead of repeating it back.",
  "",
  "## How you write",
  "",
  "- Professional Persian: the register of a good colleague in a stand-up. Warm, direct,",
  "  economical. Formal enough to read well on an issue a client might open.",
  "- Short sentences. No paragraph longer than four lines. Natural spoken rhythm is welcome;",
  "  bureaucratic Persian is not.",
  "- NEVER write: «با سلام و احترام», «لازم به ذکر است», «شایان ذکر است», «پیشاپیش سپاسگزارم»,",
  "  «مستحضر باشید», or any other opening that carries no information.",
  "- No stacked apologies. One short «اشتباه از من بود» when you were wrong, then the substance.",
  "- No exclamation marks. At most ONE emoji in a whole message, and only when it is a label.",
  "- Do not switch into English mid-sentence for a word Persian already has. Technical",
  "  identifiers, file names and code stay in English, as they should.",
  "- Address a colleague by their handle (@po, @qc, @tec) when you hand work to them.",
].join("\n");

/**
 * The system-prompt preamble for one role: the shared conduct, plus the two lines that say which
 * seat this is. Callers prepend it to their own job-specific instructions.
 */
function charter(role) {
  const s = seat(role);
  return [
    `You are ${s.name} (${s.handle}) on this repository's team.`,
    `You own: ${s.owns}.`,
    `You are accountable for: ${s.accountable}.`,
    "",
    CONDUCT,
  ].join("\n");
}

// --- The handoff contract ---------------------------------------------------------------------
//
// THE ONE RULE THIS WHOLE SYSTEM RESTS ON: no message ends without naming who acts next.
//
// The charter asks a model for it in prose, which is necessary but not sufficient — prose cannot
// be checked by a workflow at 3am. So every comment any role posts also carries a machine-
// readable footer, and `postComment` refuses to post without one. That footer is what the
// shepherd reads to find an issue nobody is acting on, and it is why "the conversation was
// interrupted" is a detectable event here rather than something a human notices a week later.

/**
 * Handoff targets that legitimately end a thread. Everything else is somebody's turn, and an
 * issue sitting on a non-terminal handoff past its SLA is by definition stuck.
 */
const TERMINAL = new Set(["done", "closed"]);

/** Anything that would end the HTML comment early, or smuggle markup into a rendered page. */
const clean = (value, max = 220) =>
  String(value == null ? "" : value)
    .replace(/-{2,}/g, "–")
    .replace(/[<>"\r\n]+/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, max);

/**
 * The one target that is not a seat: a decision only a person can make.
 *
 * It is still an owner. A `human` handoff carries a deadline like any other, and the shepherd
 * chases it like any other — a question asked of a silent stakeholder is not an excuse for the
 * work to stop, it is just a slower owner.
 */
const HUMAN = "human";

/** How a target is addressed in the human sentence: a seat handle, a person, or an end state. */
function addressOf(to) {
  const key = String(to || "").trim();
  if (!key || key === HUMAN) return "";
  if (TERMINAL.has(key)) return "";
  if (SEATS[key]) return SEATS[key].handle;
  return key.startsWith("@") ? key : `@${key}`;
}

/**
 * The block appended to every comment: one Persian sentence a colleague reads, and one HTML
 * comment the machines read. Both say the same thing — that is the point. A footer that says
 * something the prose does not is how a team learns to distrust its own tooling.
 *
 * `sla` is how long this owner has before the shepherd asks about it, written the way the state
 * table writes it ("30m", "24h"). It is advisory here: the shepherd's own table is authoritative,
 * because an owner cannot be allowed to grant itself an unlimited deadline.
 */
function handoff({ from, to, next, issue, pr, sla, at } = {}) {
  const target = String(to || "").trim();
  const action = clean(next);
  if (!target) throw new Error("handoff() needs a `to`: who acts next.");
  if (!action) throw new Error("handoff() needs a `next`: what that person must do.");

  const stamp = at || new Date().toISOString();
  const fields = [
    "v=1",
    from ? `from=${clean(from, 24)}` : "",
    `to=${clean(target, 40)}`,
    `next="${action}"`,
    issue ? `issue=${clean(issue, 12)}` : "",
    pr ? `pr=${clean(pr, 12)}` : "",
    `at=${clean(stamp, 32)}`,
    sla ? `sla=${clean(sla, 8)}` : "",
  ].filter(Boolean);

  const address = addressOf(target);
  const chase = sla ? ` (اگر تا ${sla} دیگر حرکتی نبود، چوپان پیگیری می‌کند).` : ".";
  const sentence = TERMINAL.has(target)
    ? `این کار تمام شد — ${action}`
    : target === HUMAN
      ? `با شما — ${action}${chase}`
      : `${address} — ${action}${chase}`;

  return `\n\n---\n\n**قدم بعدی:** ${sentence}\n<!-- mia:handoff ${fields.join(" ")} -->`;
}

/**
 * The last handoff in a comment body, or null.
 *
 * Deliberately forgiving: a footer written by an older version of this file, or truncated by
 * GitHub's comment limit, should still yield an owner. Half a handoff is far more useful than
 * treating the issue as ownerless and waking a role that has nothing to do.
 */
function parseHandoff(body) {
  const all = String(body || "").match(/<!--\s*mia:handoff\s+([^]*?)-->/g);
  if (!all || all.length === 0) return null;
  const last = all[all.length - 1];
  const inner = last.replace(/^<!--\s*mia:handoff\s+/, "").replace(/-->$/, "");
  const out = {};
  const field = /(\w+)=(?:"([^"]*)"|(\S+))/g;
  let m;
  while ((m = field.exec(inner)) !== null) out[m[1]] = m[2] !== undefined ? m[2] : m[3];
  if (!out.to) return null;
  return {
    v: out.v || "1",
    from: out.from || null,
    to: out.to,
    next: out.next || "",
    issue: out.issue || null,
    pr: out.pr || null,
    at: out.at || null,
    sla: out.sla || null,
    terminal: TERMINAL.has(out.to),
  };
}

// --- The state table --------------------------------------------------------------------------
//
// EVERY STATE HAS AN OWNER AND AT LEAST ONE AUTOMATIC EXIT. A state with no exit is a bug.
//
// Declared here, once, as data, because three different things need to agree about it and used
// to agree only by coincidence: the shepherd (which chases whatever is late), the loop-closure
// lint (which fails the build if any state becomes a dead end), and the documentation (which
// tells a human what to expect). A table that lives in prose is a table that drifts.
//
// `sla` is how long this state may go without movement before the shepherd acts. `null` means
// time is the wrong measure — a blocked issue is late when its blockers close, not when a clock
// runs out — and those states carry `waitsOn` instead, naming what does release them.
const STATES = {
  brief: {
    owner: "brief",
    sla: "60m",
    exits: ["brief-planned", "brief-failed"],
    note: "یک نیت بلند که PO باید تجزیه‌اش کند",
  },
  "brief-planned": {
    owner: "tec",
    sla: null,
    waitsOn: "children",
    exits: ["closed"],
    note: "نقشه ساخته شده؛ با تمام‌شدن بچه‌ها بسته می‌شود (brief-close.js)",
  },
  "brief-failed": {
    owner: HUMAN,
    sla: "24h",
    exits: ["brief"],
    note: "PO نتوانست نقشه بسازد — با برچسب دوبارهٔ brief از سر گرفته می‌شود",
  },
  "by-agent": {
    owner: "tec",
    sla: "60m",
    exits: ["agent-running"],
    note: "در صف TEC",
  },
  "agent-running": {
    owner: "tec",
    sla: "90m",
    exits: ["done", "needs-rework", "agent-failed", "by-agent"],
    note: "TEC همین حالا رویش کار می‌کند",
  },
  "needs-rework": {
    owner: "tec",
    sla: "60m",
    exits: ["by-agent", "agent-running", "needs-po"],
    note: "QC ایراد گرفته؛ TEC دوباره سراغش می‌رود",
  },
  "needs-po": {
    owner: "po",
    sla: "60m",
    exits: ["by-agent"],
    note: "PO بریف را بازتعریف می‌کند",
  },
  "needs-split": {
    owner: "po",
    sla: "60m",
    exits: ["by-agent", "closed"],
    note: "برای یک اجرا بزرگ است؛ PO می‌شکندش",
  },
  blocked: {
    owner: "tec",
    sla: null,
    waitsOn: "blockers",
    exits: ["by-agent"],
    note: "منتظر ایشوهای دیگر (unblock.js آزادش می‌کند)",
  },
  "agent-failed": {
    owner: "po",
    sla: "30m",
    exits: ["by-agent", "needs-po", "needs-split", "needs-human"],
    note: "اجرا به نتیجه نرسید؛ تریاژ تصمیم بعدی را می‌گیرد",
  },
  "needs-human": {
    owner: HUMAN,
    sla: "72h",
    exits: ["by-agent"],
    note: "یک تصمیم با آدم است؛ با هر کامنت یا در سررسید با پاسخ پیش‌فرض ادامه می‌یابد",
  },
  "qc-approved": {
    owner: "tec",
    sla: "60m",
    on: "pr",
    exits: ["done"],
    note: "روی PR — QC تأیید کرده و TEC merge می‌کند",
  },
  "qc-skipped": {
    owner: "tec",
    sla: "60m",
    on: "pr",
    exits: ["done"],
    note: "روی PR — QC نتوانست بازبینی کند؛ دروازه باز است",
  },
  done: {
    owner: "done",
    sla: null,
    exits: [],
    terminal: true,
    note: "تمام شد",
  },
};

/** Minutes for an SLA written the way the table writes it. */
function slaMinutes(sla) {
  const m = /^(\d+)\s*([mhd])$/.exec(String(sla || "").trim());
  if (!m) return null;
  const n = Number(m[1]);
  return m[2] === "m" ? n : m[2] === "h" ? n * 60 : n * 1440;
}

/**
 * The state a set of labels puts an issue in, most specific first.
 *
 * Order matters and is not alphabetical: an issue can wear `by-agent` AND `needs-po` at once (QC
 * queues it and hands it to the PO in the same breath), and the PO is the one who acts first.
 */
const STATE_PRIORITY = [
  "needs-human",
  "agent-running",
  "needs-po",
  "needs-split",
  "blocked",
  "needs-rework",
  "agent-failed",
  "brief",
  "by-agent",
  "qc-approved",
  "qc-skipped",
  "brief-failed",
  "brief-planned",
  "done",
];

function stateOfLabels(labels) {
  const set = new Set(labels || []);
  for (const name of STATE_PRIORITY) if (set.has(name)) return name;
  return null;
}

module.exports = {
  SEATS,
  seat,
  charter,
  TERMINAL,
  HUMAN,
  STATES,
  STATE_PRIORITY,
  stateOfLabels,
  slaMinutes,
  handoff,
  parseHandoff,
  addressOf,
};
