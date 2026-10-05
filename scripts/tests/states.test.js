// The handoff invariant, checked mechanically.
//
// These tests exist because the invariant is easy to state and easy to break by accident: one
// `continue` in a loop, one label added to a workflow, one comment posted without a footer, and
// an issue can sit unowned forever without anything failing. That failure is invisible at
// runtime — it looks exactly like an idle repo — so it has to be caught here instead.

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");

const ROOT = path.join(__dirname, "..", "..");
const SCRIPTS = path.join(ROOT, "docs", "github", "scripts");
const WORKFLOWS = path.join(ROOT, "docs", "github", "workflows");

const voice = require(path.join(SCRIPTS, "agent-voice.js"));

test("every state has an owner", () => {
  for (const [name, spec] of Object.entries(voice.STATES)) {
    assert.ok(spec.owner, `state "${name}" has no owner — nobody would be chased for it`);
  }
});

test("every non-terminal state has at least one automatic exit", () => {
  for (const [name, spec] of Object.entries(voice.STATES)) {
    if (spec.terminal) continue;
    assert.ok(
      Array.isArray(spec.exits) && spec.exits.length > 0,
      `state "${name}" has no exit. A state with no way out is a dead end, which is the one ` +
        "thing this system is not allowed to have."
    );
  }
});

test("every exit names a state that exists", () => {
  const known = new Set([...Object.keys(voice.STATES), "closed"]);
  for (const [name, spec] of Object.entries(voice.STATES)) {
    for (const exit of spec.exits || []) {
      assert.ok(known.has(exit), `state "${name}" exits to "${exit}", which is not a state`);
    }
  }
});

test("a state measured by something other than a clock says what it waits on", () => {
  for (const [name, spec] of Object.entries(voice.STATES)) {
    if (spec.terminal || spec.sla !== null) continue;
    assert.ok(
      spec.waitsOn,
      `state "${name}" has no SLA and no waitsOn — nothing would ever look at it again`
    );
  }
});

test("every label the workflows create is a state somebody watches", () => {
  const labels = new Set();
  for (const file of fs.readdirSync(WORKFLOWS)) {
    const text = fs.readFileSync(path.join(WORKFLOWS, file), "utf8");
    for (const m of text.matchAll(/gh label create ([a-z][a-z-]*)/g)) labels.add(m[1]);
    for (const m of text.matchAll(/^\s*create ([a-z][a-z-]*) [0-9a-f]{6}/gm)) labels.add(m[1]);
  }
  assert.ok(labels.size > 0, "no labels were found — has the label-creation syntax changed?");
  for (const label of labels) {
    assert.ok(
      voice.STATES[label],
      `the workflows create the label "${label}", but it is not in STATES. A label with no ` +
        "state is a state nobody watches."
    );
  }
});

test("every state in the table is reachable by a label the workflows create", () => {
  const labels = new Set();
  for (const file of fs.readdirSync(WORKFLOWS)) {
    const text = fs.readFileSync(path.join(WORKFLOWS, file), "utf8");
    for (const m of text.matchAll(/gh label create ([a-z][a-z-]*)/g)) labels.add(m[1]);
    for (const m of text.matchAll(/^\s*create ([a-z][a-z-]*) [0-9a-f]{6}/gm)) labels.add(m[1]);
  }
  for (const name of Object.keys(voice.STATES)) {
    assert.ok(labels.has(name), `STATES describes "${name}" but no workflow ever creates it`);
  }
});

test("the state priority order covers every state", () => {
  for (const name of Object.keys(voice.STATES)) {
    assert.ok(
      voice.STATE_PRIORITY.includes(name),
      `"${name}" is missing from STATE_PRIORITY, so stateOfLabels() can never return it`
    );
  }
});
