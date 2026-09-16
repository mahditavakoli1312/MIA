// .github/scripts/skills.js
// Managed by MIA — kept byte-for-byte in sync with docs/github/scripts/.
//
// The skill layer. A skill is one folder under `.github/skills/<name>/SKILL.md`: a piece of
// procedural knowledge a role needs only sometimes — how to decompose a brief, how to judge a
// diff, how to lay out a Compose screen, how to read a screenshot somebody pasted into an issue.
// The format is the one Z.ai publishes its GLM skills in (github.com/zai-org/GLM-skills):
// YAML frontmatter naming the skill and when to reach for it, then the procedure in markdown.
// Anything written for OpenCode, Claude Code or AutoClaw drops into `.github/skills/` unchanged.
//
// ## Why a router instead of one long AGENTS.md
//
// AGENTS.md is injected into every prompt, which is exactly why it is capped at ~120 lines: the
// roles here run on a small free model, and every line of standing instruction is a line the
// issue itself does not get. Skills are the other half of that trade. They hold the long,
// specific procedures — the ones that are decisive on the issue they apply to and pure noise on
// the other nine — and this file decides which ones this role, on this project, for this issue,
// actually needs.
//
// So the prompt gets two tiers, the way a person uses a handbook:
//
//   1. **The index** — one line per available skill, always present. It costs almost nothing and
//      it is what makes the second tier possible: a model cannot ask for a skill it has never
//      heard of. A role may name one ("مهارت mia-android-compose را باز کن") and the next run
//      loads it in full.
//   2. **The body** — the whole procedure, for the few skills that matched. A skill marked
//      `always: true` is loaded for its roles unconditionally (a role's own craft); every other
//      one waits for a trigger word in the issue, the comment or the diff.
//
// The budget below is a hard stop, not a suggestion. Injecting four skills because four matched
// is how the issue text ends up truncated, and an agent that has read everything about Compose
// and nothing about what it was asked to build is worse off than one that read neither.
//
// ## Who calls it
//
// Every script role goes through `askAI` in ai-provider.js, which calls `skillsFor` once, in one
// place, for the same reason the team charter lives there: it is the only line PO, QC, BRIEF,
// the re-scoper and the triage all pass through. TEC is the exception — it is an OpenCode run
// driven from bash, not a `fetch` — so this file is also a CLI:
//
//     node .github/scripts/skills.js --role tec --text-file "$RUNNER_TEMP/issue-body.txt"
//
// Set the repo variable AGENT_SKILLS=false to turn the whole layer off without removing files.

const fs = require("fs");
const path = require("path");

/**
 * Where skills live, in the order they are read.
 *
 * `.github/skills/` is MIA's: the role skills every project gets, refreshed whenever the team
 * files are updated. `skills/` is the project's own, outside the tree MIA claims — exactly like
 * AGENTS.md and the design system. The bootstrap seeds it with the one skill for this project's
 * stack and never touches it again, so a repository can rewrite that skill, or add its own, and
 * keep the change.
 */
const SKILL_DIRS = [path.join(".github", "skills"), "skills"];

/**
 * How much of the prompt the loaded skill bodies may take, in characters.
 *
 * ~9k is about a fifth of what a 256k-context free model can hold and roughly the size of the
 * AGENTS.md cap plus one long procedure — deliberately of the same order as the repo conventions
 * rather than larger than them. AGENT_SKILL_BUDGET raises it on a repo running a bigger model.
 */
const DEFAULT_BUDGET = 9000;

/** No single skill may eat the whole budget; a matched second one still has to fit. */
const PER_SKILL_CAP = 5000;

// --- Reading a SKILL.md ------------------------------------------------------------------------

/**
 * The frontmatter subset the skills here actually use: `key: value`, `key: [a, b]`, a `- item`
 * block, and YAML's folded `key: >` — which is how the GLM skills write their long `description`.
 *
 * Deliberately not a YAML parser. A dependency would have to be installed before any role could
 * answer, and a hand-written 40-line reader that understands four shapes is less likely to be
 * wrong than one that pretends to understand all of them. A line it cannot read is skipped, so a
 * skill file with something exotic in it still loads — it just does not get that field.
 */
function parseFrontmatter(text) {
  const match = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/.exec(text);
  if (!match) return { meta: {}, body: text.trim() };

  const meta = {};
  const lines = match[1].split(/\r?\n/);
  let key = null;
  let folded = null; // the key whose `>` block we are inside
  let list = null; // the key whose `- item` block we are inside

  for (const raw of lines) {
    const line = raw.replace(/\s+$/, "");
    if (!line) {
      if (folded) meta[folded] += "\n";
      continue;
    }
    const indented = /^\s/.test(line);

    if (folded && indented) {
      meta[folded] += (meta[folded] && !meta[folded].endsWith("\n") ? " " : "") + line.trim();
      continue;
    }
    if (list && indented && /^\s*-\s+/.test(line)) {
      meta[list].push(line.replace(/^\s*-\s+/, "").trim().replace(/^["']|["']$/g, ""));
      continue;
    }
    folded = null;
    list = null;

    const pair = /^([A-Za-z_][\w-]*):\s*(.*)$/.exec(line);
    if (!pair) continue;
    key = pair[1];
    const value = pair[2].trim();

    if (value === ">" || value === "|" || value === ">-" || value === "|-") {
      folded = key;
      meta[key] = "";
    } else if (value === "") {
      list = key;
      meta[key] = [];
    } else if (value.startsWith("[") && value.endsWith("]")) {
      meta[key] = value
        .slice(1, -1)
        .split(",")
        .map((v) => v.trim().replace(/^["']|["']$/g, ""))
        .filter(Boolean);
    } else {
      meta[key] = value.replace(/^["']|["']$/g, "");
    }
  }
  return { meta, body: (match[2] || "").trim() };
}

const asList = (value) =>
  Array.isArray(value)
    ? value.map((v) => String(v).trim()).filter(Boolean)
    : String(value == null ? "" : value)
        .split(",")
        .map((v) => v.trim())
        .filter(Boolean);

const isTrue = (value) => /^(1|true|yes|on)$/i.test(String(value == null ? "" : value).trim());

/** One skill, as the router sees it. `body` is the procedure; everything else decides whether. */
function readSkill(dir, file) {
  const { meta, body } = parseFrontmatter(fs.readFileSync(file, "utf8"));
  const name = String(meta.name || path.basename(dir)).trim();
  return {
    name,
    file,
    description: String(meta.description || "").replace(/\s+/g, " ").trim(),
    // "all" is the honest answer for a skill every seat needs (the index skill itself), and it
    // beats listing four roles that a fifth seat would then silently miss.
    roles: asList(meta.roles).map((r) => r.toLowerCase()),
    stacks: asList(meta.stacks).map((s) => s.toLowerCase()),
    triggers: asList(meta.triggers).map((t) => t.toLowerCase()),
    requires: asList(meta.requires),
    always: isTrue(meta.always),
    body,
  };
}

/**
 * Every skill installed in this checkout, sorted by name so two runs on the same issue build the
 * same prompt. A directory without a SKILL.md is somebody's notes, not a skill, and is ignored.
 */
function loadSkills(root = ".") {
  const found = new Map();
  for (const rel of SKILL_DIRS) {
    const dir = path.join(root, rel);
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      continue; // No skills of this kind installed. Normal, and not worth a warning.
    }
    for (const entry of entries) {
      if (!entry.isDirectory()) continue;
      const file = path.join(dir, entry.name, "SKILL.md");
      if (!fs.existsSync(file)) continue;
      try {
        const skill = readSkill(path.join(dir, entry.name), file);
        // A project's own `skills/<name>` deliberately shadows a bundled one of the same name:
        // that is the only way a repo can correct a MIA skill that is wrong for it, and it is
        // why the project tree is read second and wins.
        if (!found.has(skill.name) || rel === "skills") found.set(skill.name, skill);
      } catch (err) {
        console.warn(`Skipping ${file}: ${err.message}`);
      }
    }
  }
  return [...found.values()].sort((a, b) => a.name.localeCompare(b.name));
}

// --- Which project is this ----------------------------------------------------------------------

/**
 * The stack, read off the tree rather than off a variable somebody has to remember to set.
 *
 * It answers the same question `ProjectType` answers in the MIA app, and it has to answer it
 * again here because a repo does not record which of the three it was created as — and because a
 * `plain` repo whose first issue added a `package.json` is a web project now, whatever it was
 * bootstrapped as.
 */
function detectStack(root = ".") {
  const has = (...parts) => fs.existsSync(path.join(root, ...parts));
  if (has("gradlew") || has("settings.gradle.kts") || has("settings.gradle")) return "android";
  if (has("package.json") || has("index.html") || has("src", "index.html")) return "web";
  // The web design system MIA bootstraps, still there on a repo that has not scaffolded yet.
  if (has("src", "styles", "tokens.css")) return "web";
  return "plain";
}

// --- Selection ----------------------------------------------------------------------------------

/** The triggers this skill matched in the text, lower-cased on both sides. */
function matchedTriggers(skill, text) {
  const haystack = String(text || "").toLowerCase();
  if (!haystack) return [];
  const hits = skill.triggers.filter((t) => haystack.includes(t));
  // Naming the skill is always a match: it is how a role acts on the index it was given, and
  // how a human writes "with mia-web-frontend" on an issue and gets it.
  if (haystack.includes(skill.name.toLowerCase()) && !hits.includes(skill.name.toLowerCase())) {
    hits.push(skill.name.toLowerCase());
  }
  return hits;
}

/**
 * Which skills this role gets in full, and which it only hears about.
 *
 * `available` is everything installed that this role could use on this stack and has the keys
 * for; `loaded` is the subset whose bodies fit in the budget. The rest stay in the index, which
 * is not a consolation prize — it is the mechanism by which a second run can load one.
 */
function selectSkills({
  role,
  text = "",
  root = ".",
  env = process.env,
  stack = null,
  budget = null,
} = {}) {
  const seat = String(role || "").toLowerCase();
  const on = detectStack(root);
  const projectStack = stack || on;
  // An explicit 0 is a real answer — "load nothing" — so the fallback chain tests for a value
  // being GIVEN rather than for it being truthy. `budget || env…` would silently turn a caller
  // that asked for no skills into one that gets the default 9k of them.
  const asked = budget != null && budget !== "" ? budget : env.AGENT_SKILL_BUDGET;
  const parsed = Number.parseInt(asked, 10);
  const cap = Number.isFinite(parsed) && parsed >= 0 ? parsed : DEFAULT_BUDGET;

  const available = loadSkills(root).filter((skill) => {
    if (seat && skill.roles.length && !skill.roles.includes("all") && !skill.roles.includes(seat)) {
      return false;
    }
    if (skill.stacks.length && !skill.stacks.includes("all") && !skill.stacks.includes(projectStack)) {
      return false;
    }
    // A skill whose API key is not configured is not offered at all — not even in the index.
    // Telling a model about a procedure it cannot carry out buys nothing but a failed attempt
    // and a comment explaining that the repo is missing a secret nobody asked for.
    return skill.requires.every((key) => String(env[key] || "").trim().length > 0);
  });

  const scored = available
    .map((skill) => ({ skill, hits: matchedTriggers(skill, text) }))
    .filter(({ skill, hits }) => skill.always || hits.length > 0)
    // A role's own craft first, then the skill the issue points hardest at. Ties keep name
    // order, so the same issue always produces the same prompt.
    .sort((a, b) => {
      if (a.skill.always !== b.skill.always) return a.skill.always ? -1 : 1;
      if (a.hits.length !== b.hits.length) return b.hits.length - a.hits.length;
      return a.skill.name.localeCompare(b.skill.name);
    });

  const loaded = [];
  let spent = 0;
  for (const { skill, hits } of scored) {
    const body = skill.body.length > PER_SKILL_CAP
      ? `${skill.body.slice(0, PER_SKILL_CAP)}\n\n…(بریده شد — ادامه در ${skill.file})`
      : skill.body;
    if (spent + body.length > cap) continue;
    spent += body.length;
    loaded.push({ ...skill, body, hits });
  }

  return { available, loaded, stack: projectStack, budget: cap, spent };
}

// --- Rendering ------------------------------------------------------------------------------------

/**
 * The block that goes into the prompt, or "" when this role has no skills on this project.
 *
 * Returning "" rather than an empty heading matters: a section titled "Skills" with nothing
 * under it reads, to a model, as a capability it failed to find, and the next thing it does is
 * apologise for it in a comment a human has to read.
 */
function skillsFor(options = {}) {
  const env = options.env || process.env;
  if (/^(0|false|no|off)$/i.test(String(env.AGENT_SKILLS || "").trim())) return "";

  const { available, loaded } = selectSkills(options);
  if (available.length === 0) return "";

  const out = [];
  out.push("## Skills available to you");
  out.push("");
  out.push(
    "Each line is a procedure installed in this repository under `.github/skills/`. The ones " +
      "written out in full below are the ones that apply to this task — follow them as part of " +
      "your instructions, not as background reading. For any other line, say in your answer " +
      "which skill you want and why; the next run loads it in full."
  );
  out.push("");
  for (const skill of available) {
    const mark = loaded.some((l) => l.name === skill.name) ? "✔" : "·";
    out.push(`- ${mark} **${skill.name}** — ${skill.description}`);
  }

  for (const skill of loaded) {
    out.push("");
    out.push(`### Skill: ${skill.name}`);
    out.push("");
    out.push(skill.body);
  }

  out.push("");
  out.push(
    "A skill tells you HOW to do something well. It never widens WHAT you were asked to do: " +
      "where a skill and the issue disagree about scope, the issue wins."
  );
  return out.join("\n");
}

// --- CLI ------------------------------------------------------------------------------------------
//
// For TEC, whose prompt is assembled in bash by agent-issue-worker.yml rather than by a require.
// Prints the block on stdout and nothing at all when there is nothing to say, so the caller can
// append it unconditionally.

function cli(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const flag = /^--([\w-]+)$/.exec(argv[i]);
    if (flag) args[flag[1]] = argv[i + 1] && !argv[i + 1].startsWith("--") ? argv[(i += 1)] : "true";
  }
  let text = args.text || "";
  if (args["text-file"]) {
    for (const file of args["text-file"].split(",")) {
      try {
        text += `\n${fs.readFileSync(file.trim(), "utf8")}`;
      } catch {
        // A missing file is normal here — the workflow passes several and not all exist on
        // every path through it. An empty string simply matches no triggers.
      }
    }
  }
  const block = skillsFor({ role: args.role, text, root: args.root || ".", budget: args.budget });
  if (block) process.stdout.write(`\n${block}\n`);
}

if (require.main === module) {
  try {
    cli(process.argv.slice(2));
  } catch (err) {
    // The skill layer is an enhancement. A crash in it must never take a TEC run with it, so it
    // fails silent-but-visible: a warning in the log, an empty block in the prompt.
    console.warn(`::warning::skills.js could not build the skill block: ${err.message}`);
  }
}

module.exports = {
  DEFAULT_BUDGET,
  PER_SKILL_CAP,
  parseFrontmatter,
  loadSkills,
  detectStack,
  matchedTriggers,
  selectSkills,
  skillsFor,
};
