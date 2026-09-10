// .github/scripts/say.js
// Managed by MIA — kept byte-for-byte in sync with docs/github/scripts/.
//
// TEC'S MOUTH.
//
// The other three roles are node scripts, so they post through ai-provider.postComment and get
// the handoff footer and the ledger for free. TEC is a workflow, and a workflow speaks in
// `gh issue comment` — a dozen of them scattered across a thousand lines of YAML, each free to
// forget who acts next. This is that same discipline, made available to a shell.
//
//   node .github/scripts/say.js --to qc --next "بازبینی کن" --sla 60m --body "..."
//
// Options:
//   --to <seat|human|done>   who acts next (required)
//   --next <text>            what they must do (required)
//   --from <seat>            who is speaking (default: tec)
//   --sla <30m|24h>          how long before the shepherd chases it
//   --issue <n>              which issue (default: $ISSUE_NUMBER)
//   --pr <n>                 the pull request, when there is one
//   --body <text>            the message
//   --body-file <path>       …or the message from a file, for multi-line shell output
//   --state <label>          also update the ledger's state…
//   --owner <seat>           …and its owner (default: --to)
//   --branch <text>          …and its branch/PR cell
//
// It never exits non-zero on a posting failure. A run that has done the work and then dies
// because GitHub was briefly unavailable would be strictly worse than a missing comment.

const fs = require("fs");
const { postComment } = require("./ai-provider.js");
const { updateLedger } = require("./ledger.js");

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (!arg.startsWith("--")) continue;
    const key = arg.slice(2);
    const value = argv[i + 1] && !argv[i + 1].startsWith("--") ? argv[(i += 1)] : "true";
    out[key] = value;
  }
  return out;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const repo = process.env.REPO || process.env.GITHUB_REPOSITORY;
  const token = process.env.GITHUB_TOKEN || process.env.GH_TOKEN;
  const issueNumber = args.issue || process.env.ISSUE_NUMBER;

  // Non-zero on purpose: the shell wrapper reads the exit code and writes the footer itself
  // rather than letting the message disappear. A missing env var must cost a warning in the log,
  // never a comment nobody notices is absent.
  if (!repo || !token || !issueNumber) {
    console.error("say.js needs REPO, GITHUB_TOKEN and an issue number.");
    process.exitCode = 1;
    return;
  }
  if (!args.to || !args.next) {
    // Loud, because this is the invariant: a message with no next owner is the bug.
    console.error("say.js needs --to and --next: every message names who acts next.");
    process.exitCode = 1;
    return;
  }

  const body = args["body-file"]
    ? fs.readFileSync(args["body-file"], "utf8")
    : args.body || "";

  const posted = await postComment({
    repo,
    issueNumber,
    token,
    body,
    handoff: {
      from: args.from || "tec",
      to: args.to,
      next: args.next,
      issue: issueNumber,
      pr: args.pr,
      sla: args.sla,
    },
  });
  if (!posted) process.exitCode = 1;

  // The ledger only moves when the caller says the state moved. A comment that reports progress
  // without changing whose turn it is — a scope warning, say — should leave the summary alone.
  if (args.state) {
    await updateLedger({
      repo,
      issueNumber,
      token,
      patch: {
        state: args.state,
        owner: args.owner || args.to,
        next: args.next,
        ...(args.branch ? { branchOrPr: args.branch } : {}),
        ...(args.attempts ? { attempts: args.attempts } : {}),
      },
    });
  }
}

main().catch((err) => {
  console.error(`say.js could not post: ${err.message}`);
  process.exitCode = 1;
});
