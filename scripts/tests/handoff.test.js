const test = require("node:test");
const assert = require("node:assert");
const path = require("node:path");

const SCRIPTS = path.join(__dirname, "..", "..", "docs", "github", "scripts");
const voice = require(path.join(SCRIPTS, "agent-voice.js"));
const provider = require(path.join(SCRIPTS, "ai-provider.js"));

test("handoff round-trips everything it renders", () => {
  const rendered = voice.handoff({
    from: "qc",
    to: "tec",
    next: "add a retry to the error state",
    issue: 42,
    pr: 57,
    sla: "30m",
  });
  const parsed = voice.parseHandoff(`some prose\n${rendered}`);
  assert.equal(parsed.from, "qc");
  assert.equal(parsed.to, "tec");
  assert.equal(parsed.next, "add a retry to the error state");
  assert.equal(parsed.issue, "42");
  assert.equal(parsed.pr, "57");
  assert.equal(parsed.sla, "30m");
  assert.equal(parsed.terminal, false);
  assert.ok(parsed.at, "a handoff must carry when it was made, or nothing can be called late");
});

test("a handoff cannot be rendered without an owner and an action", () => {
  assert.throws(() => voice.handoff({ from: "qc", next: "do the thing" }), /to/);
  assert.throws(() => voice.handoff({ from: "qc", to: "tec" }), /next/);
});

test("text that would break the HTML comment is neutralised", () => {
  const rendered = voice.handoff({ to: "tec", next: 'fix the --dry-run <flag> in "main"' });
  // Only the payload — the closing "-->" is allowed to contain a double dash, and nothing else is.
  const payload = rendered.slice(rendered.indexOf("mia:handoff"), rendered.lastIndexOf("-->"));
  assert.ok(!payload.includes("--"), "a double dash inside would close the comment early");
  assert.ok(!/[<>]/.test(payload), "markup must not survive into the footer");
  assert.ok(!payload.split("next=")[1].includes('"main"'), "a stray quote would end the field");
  const parsed = voice.parseHandoff(rendered);
  assert.ok(parsed, "and it must still parse");
  assert.equal(parsed.to, "tec");
});

test("the last handoff in a thread wins", () => {
  const first = voice.handoff({ to: "po", next: "re-scope it" });
  const second = voice.handoff({ to: "tec", next: "implement it" });
  assert.equal(voice.parseHandoff(`${first}\n\n${second}`).to, "tec");
});

test("a terminal handoff is marked as one", () => {
  assert.equal(voice.parseHandoff(voice.handoff({ to: "done", next: "merged" })).terminal, true);
});

test("postComment refuses to post a message with no next owner", async () => {
  await assert.rejects(
    provider.postComment({ repo: "a/b", issueNumber: 1, token: "t", body: "hello" }),
    /handoff/,
    "an ownerless comment must fail loudly rather than strand an issue"
  );
});

test("postComment accepts an already-rendered footer but not arbitrary text", async () => {
  const calls = [];
  const realFetch = global.fetch;
  global.fetch = async (url, init) => {
    calls.push(JSON.parse(init.body));
    return { ok: true, status: 201, json: async () => ({}) };
  };
  try {
    await provider.postComment({
      repo: "a/b",
      issueNumber: 7,
      token: "t",
      body: "done",
      handoff: voice.handoff({ to: "tec", next: "merge" }),
    });
    assert.match(calls[0].body, /mia:handoff/);
    await assert.rejects(
      provider.postComment({ repo: "a/b", issueNumber: 7, token: "t", body: "x", handoff: "nope" }),
      /rendered/
    );
  } finally {
    global.fetch = realFetch;
  }
});

test("the charter is prepended for whichever seat is paying", async () => {
  const seen = [];
  const realFetch = global.fetch;
  global.fetch = async (url, init) => {
    seen.push(JSON.parse(init.body));
    return { ok: true, status: 200, json: async () => ({ choices: [{ message: { content: "ok" } }] }) };
  };
  try {
    const ai = provider.resolveProvider({ OPENROUTER_API_KEY: "k" }, "qc");
    await provider.askAI(ai, { system: "JOB PROMPT", user: "u" });
    const system = seen[0].messages[0].content;
    assert.match(system, /تیم کیفیت/, "the seat has to be named");
    assert.match(system, /JOB PROMPT/, "and the role's own prompt has to survive");
    assert.ok(
      system.indexOf("تیم کیفیت") < system.indexOf("JOB PROMPT"),
      "the charter comes first — it is context for the job, not an afterthought"
    );
  } finally {
    global.fetch = realFetch;
  }
});
