// Every path that gives up must still hand the work to somebody.
//
// These are the paths nobody exercises by hand: no API key, a rate limit, a model that answered
// with prose instead of JSON, a crash. They are also exactly the paths where an issue used to go
// quiet — the run ends, the comment says what went wrong, and no one is named to do anything
// about it. So each one is checked here for the only property that matters: control is handed on.
//
// The checks are made against the SOURCE rather than by running the scripts against a fake
// GitHub. That is a deliberate trade: a full harness would test more, and would also be a second
// implementation of the thing it is testing. What must never happen is a `postComment` call with
// no handoff, and that is visible in the text.

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");

const SCRIPTS = path.join(__dirname, "..", "..", "docs", "github", "scripts");
const read = (file) => fs.readFileSync(path.join(SCRIPTS, file), "utf8");

/** The body of one named function, brace-matched from its declaration. */
function bodyOf(source, name) {
  const start = source.search(new RegExp(`(async )?function ${name}\\s*\\(`));
  assert.notEqual(start, -1, `could not find function ${name}() — has it been renamed?`);
  // Past the parameter list first: a destructured parameter opens a brace of its own, and
  // mistaking it for the body is how this check silently stops checking anything.
  let params = 0;
  let i = source.indexOf("(", start);
  for (; i < source.length; i += 1) {
    if (source[i] === "(") params += 1;
    else if (source[i] === ")") {
      params -= 1;
      if (params === 0) break;
    }
  }
  const open = source.indexOf("{", i);
  let depth = 0;
  for (let i = open; i < source.length; i += 1) {
    if (source[i] === "{") depth += 1;
    else if (source[i] === "}") {
      depth -= 1;
      if (depth === 0) return source.slice(open, i + 1);
    }
  }
  assert.fail(`function ${name}() is not brace-balanced`);
}

// file → the functions in it that exist to give up gracefully, and must hand over anyway.
const GIVING_UP = {
  "qc-review.js": ["skip"],
  "po-rebrief.js": ["giveUp"],
  "decompose-brief.js": ["fail", "narrowFallback", "narrowInPlace"],
  "triage-failure.js": ["doRetry", "doNarrow", "doRebrief", "doPause"],
  "brief-close.js": ["auditBrief"],
  "unblock.js": ["main"],
};

for (const [file, functions] of Object.entries(GIVING_UP)) {
  for (const name of functions) {
    test(`${file}: ${name}() leaves an owner behind`, () => {
      const body = bodyOf(read(file), name);
      assert.match(
        body,
        /\bto:\s*(["'`]|HUMAN)/,
        `${name}() in ${file} can finish without naming who acts next`
      );
    });
  }
}

test("no role posts a comment without a handoff", () => {
  const files = fs.readdirSync(SCRIPTS).filter((f) => f.endsWith(".js"));
  for (const file of files) {
    // ai-provider owns the rule; ledger is a view and says so in its header.
    if (file === "ai-provider.js" || file === "ledger.js") continue;
    const source = read(file);
    for (const m of source.matchAll(/post(?:Comment|IssueComment)\(\{/g)) {
      const call = source.slice(m.index, m.index + 600);
      assert.match(
        call,
        /handoff/,
        `${file} posts a comment without a handoff at offset ${m.index}`
      );
    }
  }
});

test("the ledger deliberately carries no handoff", () => {
  // Not an oversight — the ledger reports the owner that a role already declared, and a second
  // copy that could disagree with the first would be worse than none.
  const source = read("ledger.js");
  assert.ok(!/handoff/.test(source.replace(/^\/\/.*$/gm, "")), "the ledger must not assign owners");
});

test("triage cannot end in a pause with nothing to answer", () => {
  const source = read("triage-failure.js");
  assert.match(
    bodyOf(source, "validate"),
    /pause without both a question and an assumed answer/,
    "a pause with no question and no default is the dead end triage exists to remove"
  );
});

test("brief-close never closes a brief on a failure path", () => {
  const source = read("brief-close.js");
  const audit = bodyOf(source, "auditBrief");
  // The only state: "closed" write in the file must be under the `covered` branch.
  const closes = [...audit.matchAll(/state:\s*"closed"/g)];
  assert.equal(closes.length, 1, "a brief may be closed in exactly one place");
  const covered = audit.indexOf("if (verdict.covered)");
  assert.ok(covered !== -1 && closes[0].index > covered, "and only after a clear yes");
});

test("the shepherd never opens, closes or merges anything", () => {
  const source = read("shepherd.js").replace(/^\s*\/\/.*$/gm, "");
  assert.ok(!/state:\s*"closed"/.test(source), "the shepherd must not close issues");
  assert.ok(!/gh\("\/issues",/.test(source), "the shepherd must not open issues");
  assert.ok(!/pr\/merge|\/merge\b/.test(source), "the shepherd must not merge anything");
});
