// The workflow files have to PARSE. That sounds too obvious to test, and it is exactly why it
// needs one.
//
// A `run: |` step is a YAML block scalar, and a block scalar ends at the first non-empty line
// indented less than the block. A shell heredoc body must start at column 0. Put the two
// together — which is the natural way to write `while read … done <<EOF` — and the block scalar
// silently terminates in the middle of a shell script, the rest of the file is parsed as a
// mapping, and the whole workflow becomes invalid YAML.
//
// GitHub's response to that is the quietest failure in this repository: the file is ignored.
// No run is created, no error is shown on the issue, no red X appears anywhere. The workflow is
// simply listed by its PATH instead of its `name:` in the Actions sidebar, and every trigger it
// declares — the `by-agent` label, the `@tec` comment, the half-hourly drain — does nothing at
// all. That happened to agent-issue-worker.yml, and the symptom reported was "the agents are
// not working", because from the outside it is indistinguishable from a team that never ran.
//
// Anything below is cheap. The bug it prevents cost a repository a full day of doing nothing.

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");

const WORKFLOWS = path.join(__dirname, "..", "..", "docs", "github", "workflows");
const files = fs.readdirSync(WORKFLOWS).filter((f) => f.endsWith(".yml"));

/** The only keys a workflow file may start a line at column 0 with. */
const TOP_LEVEL = /^(name|run-name|on|env|defaults|concurrency|permissions|jobs):/;

test("there are workflow files to check", () => {
  assert.ok(files.length > 0, "no workflows found — has the directory moved?");
});

for (const file of files) {
  const lines = fs.readFileSync(path.join(WORKFLOWS, file), "utf8").split("\n");

  test(`${file}: nothing starts at column 0 except a top-level key`, () => {
    lines.forEach((line, i) => {
      if (line === "" || /^\s/.test(line)) return;
      if (line.startsWith("#") || TOP_LEVEL.test(line)) return;
      assert.fail(
        `${file}:${i + 1} starts at column 0: ${JSON.stringify(line.slice(0, 60))}\n` +
          "  Inside a `run: |` block this ENDS the block scalar and makes the file invalid YAML,\n" +
          "  which GitHub ignores in silence. If this is a heredoc body, write the producer to a\n" +
          "  file instead and redirect the loop from it:\n" +
          '    cmd > "${RUNNER_TEMP}/x.txt"\n' +
          '    while read -r n; do …; done < "${RUNNER_TEMP}/x.txt"'
      );
    });
  });

  test(`${file}: every block scalar has a body indented past its key`, () => {
    lines.forEach((line, i) => {
      const opener = line.match(/^(\s*)[^\s#].*:\s*[|>][-+]?\d*\s*(#.*)?$/);
      if (!opener) return;
      const keyIndent = opener[1].length;
      // The first non-empty line after the opener is what sets the block's own indentation, and
      // it is the one that has to be deeper than the key.
      for (let j = i + 1; j < lines.length; j += 1) {
        if (lines[j].trim() === "") continue;
        const indent = lines[j].length - lines[j].trimStart().length;
        assert.ok(
          indent > keyIndent,
          `${file}:${j + 1} is indented ${indent}, but the block scalar opened at line ${i + 1} ` +
            `is indented ${keyIndent}. A block scalar's body must be deeper than its key.`
        );
        return;
      }
    });
  });

  test(`${file}: has a name, so the Actions sidebar can show one`, () => {
    assert.ok(
      lines.some((l) => /^name:\s*\S/.test(l)),
      `${file} has no top-level \`name:\`. GitHub then lists it by file path — which is also what ` +
        "it does for a file it could not parse, so the two failures look identical."
    );
  });
}
