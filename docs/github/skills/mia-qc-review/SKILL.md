---
name: mia-qc-review
description: >
  Judge a pull request against the issue's acceptance criteria — what to read first in a diff,
  the failure modes of a small coding model, and how to write a rework note that can be acted
  on without another round. Use it on every review and every test checklist.
roles: [qc]
always: true
---

# Reviewing work from an autonomous agent

The author of this diff is TEC: a small model that read one issue and had no one to ask. Assume
the failure modes of a hurried junior — not of a careless one. The code usually compiles and
usually does something reasonable. What it does badly is **stop early** and **drift sideways**.

## Read the diff in this order

1. **The file list, before any code.** Files that had no business changing are the cheapest
   finding there is, and the one a line-by-line read finds last. Anything under `.github/`, a
   reformatted file the issue never mentioned, a renamed symbol nobody asked for, a lockfile that
   moved: each is a rejection on its own.
2. **Each acceptance criterion, against the code.** One row per criterion, in the issue's order,
   with the file and line that satisfies it. A criterion you cannot point at is not met — "it
   looks like it probably handles that" is how a half-finished feature merges.
3. **The states.** For anything a user sees: loading, empty, error-with-retry, content. A screen
   with only the happy path is unfinished, even when every criterion has a checkmark.
4. **The seams.** What called the changed code before, and does it still work? A changed function
   signature, a new required field, a different return shape, an altered default.

## What you are actually looking for

- **Swallowed failures** — `catch {}`, a `?: return`, a `.getOrNull()` whose null is never
  handled. The user is left with a blank screen and no idea why.
- **Hard-coded values that this project has a token for** — a colour, a size, a spacing number, a
  user-visible string outside the resources file. These are not style points here; they are what
  makes the tenth screen look like a different product.
- **Scope creep**, including the well-intentioned kind: a helper "while I was in there", a
  dependency added when something already in the project does the job, a refactor the issue did
  not ask for.
- **Work that is claimed and not done** — a TODO, a stub returning a constant, a comment saying
  what would happen "in a real implementation". Read `.mia-report.md`: TEC is asked to say what it
  did not finish, and it usually does.

## The verdict

Approve when every criterion is met and the diff contains nothing else. Two rules about rejecting:

- **Never reject over taste.** A shape you would have written differently, which meets every
  criterion and breaks no rule of this repository, is merged. Blocking on preference costs a full
  review round and teaches the next run nothing.
- **A rework note is a work order, not a critique.** Number the objections. For each: what is
  wrong, which file and line, and what specifically must be true instead. TEC will read it
  literally, so "بهترش کن" produces another rejected round. `در `TaskScreen.kt:42` حالت خالی وجود
  ندارد؛ وقتی `items` خالی است `MiaEmptyState` با متن «هنوز تسکی نیست» نشان داده شود.` does not.

Everything you did not object to is accepted. Say so — otherwise the next round re-implements
things that were already fine.

## The edge cases that get forgotten here

Empty and very long Persian text · RTL layout and a mixed LTR block (code, numbers, English) ·
zero / one / many items · a slow or offline network · rotation and process death ·
a missing permission or an unset key · a first run with no data at all.

## Before you post

- [ ] Every acceptance criterion has a row, a verdict and a file reference.
- [ ] Every objection names a file, a line and the concrete thing that must be true.
- [ ] Nothing is blocked on preference.
- [ ] The last line says who acts next, and on what.
