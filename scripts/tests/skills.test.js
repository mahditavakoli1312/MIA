// The skill layer, checked the same way everything else here is: against the property that has
// no runtime symptom when it breaks.
//
// A skill that is malformed, mis-named, or attached to a role that does not exist does not throw
// — skills.js skips it, every run keeps working, and the only evidence is that one seat quietly
// stopped getting the procedure it was written for. That is indistinguishable from a model
// having a bad day, so it has to be caught here.
//
// The three properties:
//   1. every SKILL.md parses, and its frontmatter says which seat and which stack it is for;
//   2. every seat on this team — PO, QC, TEC and the brief decomposer — has at least one skill
//      that loads unconditionally, so no role is left with an empty handbook;
//   3. the router honours the budget, the stack and the `requires:` key gate — the three things
//      that decide whether a prompt still has room for the issue in it.

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const ROOT = path.join(__dirname, "..", "..");
const GITHUB = path.join(ROOT, "docs", "github");
const SHARED = path.join(GITHUB, "skills");
const skills = require(path.join(GITHUB, "scripts", "skills.js"));

/** The seats a skill may be written for. `all` is every one of them at once. */
const SEATS = ["po", "qc", "tec", "brief"];
const STACKS = ["android", "web", "plain"];

/** Where each kind of skill lives in docs/github: shared at the top, typed under its stack. */
function everySkillFile() {
  const files = [];
  for (const name of fs.readdirSync(SHARED)) {
    const file = path.join(SHARED, name, "SKILL.md");
    if (fs.existsSync(file)) files.push({ name, file, stack: null });
  }
  for (const stack of STACKS) {
    // A stack skill lives in the PROJECT's own tree, not MIA's — same side of the line as
    // AGENTS.md and the design system, because a project has to be able to rewrite it.
    const dir = path.join(GITHUB, stack, "skills");
    if (!fs.existsSync(dir)) continue;
    for (const name of fs.readdirSync(dir)) {
      const file = path.join(dir, name, "SKILL.md");
      if (fs.existsSync(file)) files.push({ name, file, stack });
    }
  }
  return files;
}

const ALL = everySkillFile();

/**
 * A throwaway repository with the skills installed where a real one has them, plus whatever file
 * makes detectStack answer `stack`. The router reads the tree, so testing it against anything
 * else would be testing a different function.
 */
function fakeRepo(stack) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "mia-skills-"));
  for (const skill of ALL) {
    if (skill.stack && skill.stack !== stack) continue;
    // Each one lands where the bootstrap actually puts it: MIA's role skills under .github/,
    // the stack skill in the project's own tree.
    const into = path.join(dir, ...(skill.stack ? ["skills"] : [".github", "skills"]), skill.name);
    fs.mkdirSync(into, { recursive: true });
    fs.copyFileSync(skill.file, path.join(into, "SKILL.md"));
  }
  if (stack === "android") fs.writeFileSync(path.join(dir, "gradlew"), "#!/bin/sh\n");
  if (stack === "web") fs.writeFileSync(path.join(dir, "package.json"), "{}\n");
  return dir;
}

test("there are skills to check", () => {
  assert.ok(ALL.length > 0, "no SKILL.md found — has docs/github/skills/ moved?");
});

for (const { name, file, stack } of ALL) {
  const { meta, body } = skills.parseFrontmatter(fs.readFileSync(file, "utf8"));
  const rel = path.relative(ROOT, file);

  test(`${rel}: the frontmatter names the skill and says when to use it`, () => {
    assert.ok(meta.name, "no `name:` — skills.js would fall back to the directory name");
    assert.equal(
      meta.name,
      name,
      "the `name:` and the directory disagree; the index would advertise a skill nobody can ask for"
    );
    const description = String(meta.description || "").trim();
    assert.ok(
      description.length >= 40,
      "`description:` is the ONLY thing a role sees for a skill that is not loaded — it has to " +
        "say what the skill does and when to reach for it"
    );
  });

  test(`${rel}: every role it claims is a seat on this team`, () => {
    const roles = Array.isArray(meta.roles) ? meta.roles : String(meta.roles || "").split(",");
    const named = roles.map((r) => String(r).trim().toLowerCase()).filter(Boolean);
    assert.ok(named.length > 0, "no `roles:` — no seat would ever be offered this skill");
    for (const role of named) {
      assert.ok(
        role === "all" || SEATS.includes(role),
        `"${role}" is not a seat (${SEATS.join(", ")}) — this skill would reach nobody`
      );
    }
  });

  test(`${rel}: a typed skill declares the stack it lives under`, () => {
    if (!stack) return;
    const declared = (Array.isArray(meta.stacks) ? meta.stacks : [meta.stacks])
      .map((s) => String(s || "").trim().toLowerCase())
      .filter(Boolean);
    assert.deepEqual(
      declared,
      [stack],
      `it is bootstrapped only into ${stack} repos, so \`stacks: [${stack}]\` has to say so — ` +
        "otherwise a project that grows into another stack keeps being handed it"
    );
  });

  test(`${rel}: the body fits in the prompt`, () => {
    assert.ok(body.length > 200, "an empty skill costs prompt room and teaches nothing");
    assert.ok(
      body.length <= skills.PER_SKILL_CAP,
      `${body.length} characters — over the ${skills.PER_SKILL_CAP} cap, so it would be ` +
        "truncated mid-sentence in every prompt that loads it"
    );
  });
}

test("every seat has at least one skill that always loads", () => {
  for (const stack of STACKS) {
    const repo = fakeRepo(stack);
    for (const seat of SEATS) {
      const { loaded } = skills.selectSkills({ role: seat, text: "", root: repo, env: {} });
      assert.ok(
        loaded.length > 0,
        `on a ${stack} project, ${seat} gets no skill at all — that seat has an empty handbook`
      );
    }
  }
});

test("a role is never handed another role's skills", () => {
  const repo = fakeRepo("android");
  const { available } = skills.selectSkills({ role: "qc", text: "", root: repo, env: {} });
  for (const skill of available) {
    assert.ok(
      skill.roles.includes("qc") || skill.roles.includes("all"),
      `${skill.name} was offered to QC but is written for ${skill.roles.join("/")}`
    );
  }
});

test("a stack only ever sees its own stack skill", () => {
  for (const stack of STACKS) {
    const repo = fakeRepo(stack);
    const { available } = skills.selectSkills({ role: "tec", text: "", root: repo, env: {} });
    for (const skill of available) {
      if (skill.stacks.length === 0) continue;
      assert.ok(
        skill.stacks.includes(stack),
        `a ${stack} project was offered ${skill.name}, which is for ${skill.stacks.join("/")}`
      );
    }
  }
});

test("the stack is read off the tree, not guessed", () => {
  assert.equal(skills.detectStack(fakeRepo("android")), "android");
  assert.equal(skills.detectStack(fakeRepo("web")), "web");
  assert.equal(skills.detectStack(fakeRepo("plain")), "plain");
});

test("a skill whose API key is unset is not offered at all", () => {
  const repo = fakeRepo("android");
  const withoutKey = skills.selectSkills({ role: "po", text: "اسکرین‌شات", root: repo, env: {} });
  const withKey = skills.selectSkills({
    role: "po",
    text: "اسکرین‌شات",
    root: repo,
    env: { ZHIPU_API_KEY: "test-key" },
  });
  const gated = ALL.map((s) => s.name).filter((name) => name.startsWith("glm"));
  assert.ok(gated.length > 0, "no key-gated skill to check — has the GLM set been removed?");

  for (const name of gated) {
    assert.ok(
      !withoutKey.available.some((s) => s.name === name),
      `${name} was offered with no ZHIPU_API_KEY — the role would spend a turn on a call that ` +
        "cannot succeed and then have to explain a missing secret on the issue"
    );
  }
  assert.ok(
    withKey.available.some((s) => s.name === "glmv-visual-brief"),
    "with a key configured, the visual skill has to come back"
  );
});

test("a trigger word is what loads a skill that is not always-on", () => {
  const repo = fakeRepo("android");
  const env = { ZHIPU_API_KEY: "test-key" };
  const quiet = skills.selectSkills({ role: "po", text: "یک دکمهٔ خروج اضافه کن", root: repo, env });
  const visual = skills.selectSkills({
    role: "po",
    text: "طبق این اسکرین‌شات بساز",
    root: repo,
    env,
  });
  assert.ok(!quiet.loaded.some((s) => s.name === "glmv-visual-brief"));
  assert.ok(visual.loaded.some((s) => s.name === "glmv-visual-brief"));
});

test("naming a skill in the text is enough to load it", () => {
  const repo = fakeRepo("web");
  const { loaded } = skills.selectSkills({
    role: "tec",
    text: "با مهارت mia-web-frontend پیش برو",
    root: repo,
    env: {},
  });
  assert.ok(loaded.some((s) => s.name === "mia-web-frontend"));
});

test("the budget is a hard stop, not a suggestion", () => {
  const repo = fakeRepo("android");
  const env = { ZHIPU_API_KEY: "test-key" };
  const text = "اسکرین‌شات pdf آیکون build failed"; // every trigger at once
  for (const budget of [0, 500, 2000, skills.DEFAULT_BUDGET]) {
    const { loaded, spent } = skills.selectSkills({ role: "tec", text, root: repo, env, budget });
    assert.ok(
      spent <= budget,
      `${spent} characters of skill body against a ${budget} budget — the issue text is what ` +
        "gets pushed out of the prompt when this is wrong"
    );
    if (budget === 0) assert.equal(loaded.length, 0);
  }
});

test("the same input always builds the same prompt", () => {
  const repo = fakeRepo("web");
  const args = { role: "tec", text: "یک صفحهٔ تنظیمات بساز", root: repo, env: {} };
  const once = skills.skillsFor(args);
  const twice = skills.skillsFor(args);
  assert.equal(once, twice, "skill selection is not deterministic — two runs would disagree");
});

test("a repository with no skills installed gets no heading at all", () => {
  const empty = fs.mkdtempSync(path.join(os.tmpdir(), "mia-noskills-"));
  assert.equal(
    skills.skillsFor({ role: "tec", text: "anything", root: empty, env: {} }),
    "",
    "an empty `## Skills` heading reads to a model as a capability it failed to find"
  );
});

test("AGENT_SKILLS=false turns the whole layer off without removing a file", () => {
  const repo = fakeRepo("android");
  assert.equal(skills.skillsFor({ role: "tec", text: "", root: repo, env: { AGENT_SKILLS: "false" } }), "");
  assert.ok(skills.skillsFor({ role: "tec", text: "", root: repo, env: {} }).length > 0);
});

test("the index lists every available skill, loaded or not", () => {
  const repo = fakeRepo("android");
  const env = { ZHIPU_API_KEY: "test-key" };
  const { available } = skills.selectSkills({ role: "po", text: "", root: repo, env });
  const block = skills.skillsFor({ role: "po", text: "", root: repo, env });
  for (const skill of available) {
    assert.ok(
      block.includes(skill.name),
      `${skill.name} is available to PO but is not in the index — a role cannot ask for a ` +
        "skill it has never been told about"
    );
  }
});

test("every skill file is registered in BOOTSTRAP_ASSETS, or the app never uploads it", () => {
  const module = fs.readFileSync(
    path.join(ROOT, "app", "src", "main", "java", "ir", "mahditavakoli", "mia", "network", "NetworkModule.kt"),
    "utf8"
  );
  for (const { name, stack } of ALL) {
    const repoPath = stack ? `skills/${name}/SKILL.md` : `.github/skills/${name}/SKILL.md`;
    assert.ok(
      module.includes(`"${repoPath}"`),
      `${name} exists in docs/github but no Bundled() entry uploads it to ${repoPath}`
    );
  }
});
