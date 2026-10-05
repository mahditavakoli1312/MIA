---
name: mia-plain-stack
description: >
  Choose and record a stack for a repository that does not have one yet — a CLI, a library, a
  bot or an API — and write the build and run commands the rest of the team will depend on.
  Use it on the first issue that needs a toolchain.
roles: [tec, po, qc]
stacks: [plain]
always: true
---

# The repository with no stack yet

This project was created without one, and `AGENTS.md` leaves the stack section blank on purpose.
That blank is not an oversight — it is a decision deferred to the first issue that actually needs
a toolchain, so nobody bets the repository on a framework before knowing what it is for.

If you are that issue, you are choosing for everyone who comes after you.

## Choosing

- **The smallest thing that does the job.** A CLI that parses three flags does not need a
  framework. A one-file script that works is a better answer than a scaffold that has to be
  learned.
- **Boring and installed by default** beats interesting. Whatever this project turns out to be,
  the next agent has a fresh runner, one run, and no one to ask: a stack whose setup is one
  command will work on that run, and a stack with a global toolchain and a lockfile dance may not.
- **Read the issue for what it implies.** A Telegram bot, an HTTP API, a data script and a
  library have different obvious answers, and the issue usually names one without meaning to.
- **Do not add a dependency you can avoid.** Every one is something the next run must install
  before it can start.

## Recording it — the part that is actually the deliverable

A stack that exists only in the tree is a stack the other roles cannot see. In the **same pull
request** that introduces it:

1. **Fill in the stack section of `AGENTS.md`.** Language and version, the layout (where source,
   tests and entry point live), and the hard rules of that ecosystem. This file is injected into
   every PO, QC and TEC prompt from now on — it is how the choice becomes the team's, and it is
   the only file in the repository that is allowed to say what this project is.
2. **Write the exact commands, in `AGENTS.md` and in the README's `## اجرا` section.** Install,
   build, test, run. Real and copy-pasteable, never a placeholder, and never a command you have
   not actually read out of the build file you just wrote.
3. **Say what has to exist first** — a runtime version, an environment variable, a key — and
   where it comes from. A key comes from Actions secrets or a local file that is gitignored, and
   never from the repository.

Do all three or the next issue guesses, and two issues from now the project has two stacks.

## No design system here, and that is deliberate

A plain project gets no tokens and no components, because a theme in a repository that renders
nothing is a file an agent will eventually try to use. If this project grows a user interface, it
is no longer plain: the honest move is an issue that adds a real front end, and with it the
conventions that go with one — not a stylesheet smuggled in beside a CLI.

## For PO and QC

Until the stack section of `AGENTS.md` is filled in, **no acceptance criterion may name a build
command**, because there is not one yet. The first issue's criteria are about the choice being
made and written down: the commands exist, they run, and `AGENTS.md` and the README agree with
each other and with the tree.
