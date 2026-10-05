---
name: mia-brief-decomposition
description: >
  Break a long brief into small, ordered, TEC-sized issues that together cover all of it —
  sizing, dependency order, and the vertical-slice rule. Use it whenever you are decomposing
  an intent or splitting an issue that is too big for one run.
roles: [brief]
always: true
---

# Decomposing a brief into TEC-sized issues

One issue is one run of a small model with no memory. The unit you are producing is **the amount
of work that can be finished, reviewed and merged in a single attempt** — not a phase of a
project plan.

## Sizing

| Size | What it is | Example |
|---|---|---|
| **S** | one file, one behaviour | add a field to a model and its serializer |
| **M** | two or three files that must change together | a screen + its ViewModel + its strings |
| **L** | the largest still allowed — say why it cannot be two | a screen whose data source does not exist yet |

Anything above L is not an issue, it is a plan. Split it again. If a piece cannot be described
without the word "and", it is usually two pieces.

## Vertical slices, not layers

The tempting split is by layer — "the data model", "the repository", "the screen". Do not. Each
of those merges a change that does nothing on its own, and if the third one is never written, the
repository is left carrying two files nobody uses.

Split so that **every issue leaves the project working and visibly better than before**:

- ✅ «فهرست تسک‌ها با حالت خالی» → ✅ «افزودن تسک» → ✅ «حذف با تأیید»
- ❌ «مدل داده» → ❌ «ریپازیتوری» → ❌ «صفحه»

The exception is a genuine prerequisite that cannot be sliced — a database table, an auth flow.
Make it the first issue and say in `depends_on` which pieces are waiting on it.

## Dependency order

`depends_on` holds positions in your own list, and it is what the unblock sweep reads to release
work when a blocker closes. Two rules:

- Only a **hard** dependency — the later issue cannot be implemented at all until the earlier one
  lands. "Nicer to do in this order" is not a dependency, it is an ordering, and the list order
  already says that.
- **No cycles, and no chains longer than they need to be.** Every issue that depends on nothing
  can start immediately; a plan where everything depends on item 1 has one worker and nine idle.

## Coverage

The brief is the contract. Before you return the plan, read it again line by line and check that
every sentence in it lands in some issue. Anything you deliberately left out gets said out loud —
"این بخش را نگرفتم چون …" — because a piece quietly dropped from a plan is the one failure mode
nobody notices until the intent is closed and the feature is half-built.

## Each issue body

Write every one as if it is the only thing its implementer will ever read: `## شرح`,
`## مشخصات فنی`, `## راهنمای طراحی (UI/UX)` when it is visible, `## معیارهای پذیرش` as a checkbox
list, and `## فایل‌های مجاز`. Do not write "مثل ایشوی قبلی" or "ادامهٔ مورد ۲" — TEC will not have
issue 2 in front of it.

## Before you return the plan

- [ ] No issue is larger than L, and every L says why it could not be two.
- [ ] Every issue leaves the repository in a working state.
- [ ] `depends_on` contains only hard prerequisites, and no cycle.
- [ ] Every sentence of the brief is covered, or its absence is stated.
- [ ] Each body stands alone, with acceptance criteria that can be checked by looking.
