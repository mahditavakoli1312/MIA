---
name: mia-failure-triage
description: >
  Read a failed or rejected run and decide the cheapest road to finishing it — retry, narrow,
  rebrief or pause — from build logs, an empty diff, or a second QC rejection.
roles: [po, tec]
triggers:
  - "failed"
  - "failure"
  - "error:"
  - "exit code"
  - "build failed"
  - "compilation error"
  - "unresolved reference"
  - "needs-rework"
  - "agent-failed"
  - "شکست"
  - "خطا"
  - "ناتمام"
  - "دوباره"
---

# Triaging a run that produced nothing mergeable

A failed run is evidence, and most of it is in three places: the last 50 lines of the build log,
the diff (or the absence of one), and `.mia-report.md`, where TEC was asked to say what it could
not finish. Read all three before deciding. A diagnosis invented to fill the silence sends the
team down the wrong road at full speed.

## Read the failure by its shape

| What you see | What it usually means | The move |
|---|---|---|
| Runner killed, network error, tool not found, 429 | nothing is wrong with the work | **retry** |
| Compile error in files TEC just wrote | the model lost the thread mid-run; often too much at once | **narrow** |
| Diff is empty, or touches only unrelated files | the issue did not say what "done" is | **rebrief** |
| Diff is large and half-right, files outside the scope | the issue was more than one run | **narrow** |
| Second QC rejection on the same objection | the brief and the criteria disagree | **rebrief** |
| Missing credential, two incompatible requirements | only a person can unblock it | **pause** |

## The compile-error trap

An unresolved reference is not automatically "narrow". Read *which* symbol is missing:

- A symbol **TEC invented** (a helper it meant to write and did not, a component that does not
  exist in this project) → the run ran out of room. **narrow**.
- A symbol **that exists but was imported wrong**, or a signature that changed under it → a
  single-line fix. **retry** is cheaper than a decomposition.
- A symbol from **a dependency that is not in the build file** → the issue asked for something
  the project cannot do yet. That prerequisite is its own issue. **narrow**.

## Pausing is expensive — earn it

`pause` stops the repository until a human comes back, and humans come back slowly. It is right
only when **no rewriting of the issue could let the work continue**: a secret nobody has, a
product decision with two genuinely incompatible answers.

When you do pause, you owe two things and the system will not accept one without the other:

- **the question**, with two or three concrete options — not "چه کنیم؟"
- **the assumption** the team proceeds on if nobody replies, and it must be something the team
  can actually act on alone.

A pause with no assumption is not a pause, it is an abandonment — the exact failure this whole
team is built to prevent.

## Say what you actually saw

Two to four sentences, first person, Persian, naming the evidence: «لاگ می‌گوید `TaskCard`
حل نشده و TEC در گزارشش نوشته که فرصت نکرده بسازدش — پس این ایشو برای یک اجرا بزرگ بوده.»
If the log shows no cause, say that plainly and choose **retry** or **rebrief**. A confident
wrong diagnosis costs more than an honest "نمی‌دانم چرا".
