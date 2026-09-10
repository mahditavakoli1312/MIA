<!-- Managed by MIA — do not edit by hand (kept byte-for-byte in sync with docs/github/). -->

# AGENTS.md — conventions for this repository

Read this before you change anything. It is injected into every agent prompt, so it is short on
purpose: it answers the questions you would otherwise guess at.

## Stack

**This repository has no stack yet, and that is deliberate.** It was created for something with no
user interface to theme — a CLI, a library, a package, a bot, a set of scripts, an API-only
backend — and MIA does not know which, so it has not guessed.

The first issue that needs a language and a layout is the issue that chooses them. When you are
that issue:

- Pick the obvious tool for the job the issue describes, and **say in the PR body why**.
- Follow that ecosystem's ordinary conventions rather than inventing your own. A Python package
  looks like a Python package; a Node CLI looks like a Node CLI.
- Add the build, test and lint commands to the "Build and test" section below **in the same pull
  request**, replacing the placeholder. Every agent after you reads this file and needs to know
  how to run things.

Once a stack exists, **it is fixed**. Do not introduce a second language, a second package
manager, or a second test runner because it suited one issue.

## Build and test — the exact commands

<!-- Replace this block the moment this repo has a build. Until then it is honest, not a bug. -->

```
# Not chosen yet — see "Stack" above.
# Fill these in when the tooling lands, e.g.:
#   make build / make test
#   npm ci && npm run build && npm test
#   uv sync && uv run pytest
```

If a command is not listed here, it does not exist yet. Do not assume `make`, `npm` or `./gradlew`
is available — check first.

## Where each kind of file lives

```
src/ | lib/ | cmd/      ← the source, in whatever layout the chosen ecosystem expects
tests/                  ← tests, mirroring the source tree
docs/                   ← anything a reader needs that is longer than the README
README.md               ← what this is, how to install it, how to run it
.github/                ← MIA's AI-team workflows. Do not edit unless an issue names a path here.
```

New file goes in the folder its neighbours are already in. Do not invent a layout — and once the
first few files exist, that layout is the one.

## Hard rules

1. **The public interface is the product.** Whatever a user of this repo touches — CLI flags, an
   exported function, an HTTP route — is what must stay stable, be documented, and change
   deliberately. Everything behind it is yours to refactor.
2. **Errors are for the person reading them.** Say what failed, what was expected, and what to do
   next. Never swallow an exception, and never print a stack trace as the entire user-facing
   error.
3. **Exit codes and streams mean something** (for anything with a command line): `0` only on
   success, results on stdout, diagnostics on stderr. A tool that prints its progress to stdout
   cannot be piped.
4. **No new dependency** unless the change is impossible without it. Say why in the PR body. The
   standard library is usually enough, and every dependency is one more thing that breaks later.
5. **Secrets never land in the repo.** No API keys, tokens or URLs with credentials in source, in
   tests, or in committed files. They come from environment variables or Actions secrets, and the
   README says which.
6. **Stay inside the issue.** Do not reformat files you did not need to change, do not rename
   things nobody asked you to rename, and never touch `.github/` unless the issue names a path
   there. A small diff that builds beats a large one that does not.
7. **Test the logic you add.** Every new function with a branch in it gets a test next to the
   existing ones. If there is no test setup yet, adding one is part of the first issue that needs
   it.
8. **The README stays true.** If your change alters how this is installed, configured or run, the
   README changes in the same pull request.
9. **Comments explain WHY, not what.** Match the density and voice of the file you are editing.

## If this project grows a user interface

It should not do that quietly. This repo was set up without a design system on purpose, and a
half-invented one is worse than none. Say so on the issue first — a project that has become an app
or a site is better served by MIA creating it as one, or by the design system being added
deliberately in its own pull request, than by one screen inventing its own colours.

## Issue and PR shape

Issues carry `## شرح`, `## مشخصات فنی`, `## راهنمای طراحی (UI/UX)` and
`## معیارهای پذیرش` (a checkbox list). Implement every acceptance criterion, and nothing that is
not in one. The PR body says what changed and why, and names any dependency you added.
