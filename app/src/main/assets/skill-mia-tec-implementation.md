---
name: mia-tec-implementation
description: >
  How to take one issue from a cold checkout to a mergeable diff — orient, plan, implement,
  verify — and the specific ways an autonomous run fails. Use it on every issue you implement.
roles: [tec]
always: true
---

# Implementing one issue, start to finish

You get one run. There is nobody to ask, and the next person to read this code is a reviewer who
will reject it over things you could have checked yourself.

## 1. Orient before you type

Before the first edit, know three things:

- **Where this kind of file already lives.** Read the file list. A new screen goes where the
  other screens are, named the way they are named. Never invent a layout.
- **What already does most of this.** Grep for the nearest existing feature and read it whole. A
  change shaped like its neighbours is reviewed in one round; a correct change shaped differently
  is reviewed in three.
- **What you are allowed to touch.** If the issue has `## فایل‌های مجاز`, that list is enforced by
  the workflow — editing anything outside it throws the entire run away uncommitted. A new file
  inside a listed directory is fine.

## 2. Plan out loud, in a few lines

Name every file you will create or change and what goes in each. This is not ceremony: writing
the list is what surfaces the file you were about to forget — the strings resource, the
navigation entry, the test.

## 3. Implement the smallest change that fully satisfies the issue

- **Finished work only.** No TODO, no placeholder, no stub that returns a constant, no
  "in a real implementation this would…". If you genuinely cannot finish something, implement
  everything else properly and say plainly at the end what is missing.
- **Reuse beats invention** every time — an existing component, helper, style or pattern.
- **No new dependency** unless the change is impossible without it and nothing in the project
  can do the job.
- **Do not tidy.** Reformatting, renaming and refactoring that the issue did not ask for turn a
  reviewable diff into an unreviewable one, even when every line of it is an improvement.

## 4. Verify before you stop

Re-read every file you touched, top to bottom, against the code around it: imports that exist,
names that match, types that line up, nullability, and the build file. Then walk the issue's
acceptance criteria one by one and point at the line that satisfies each. A criterion you cannot
point at is not done.

If the project has a build command, the change has to survive it. A diff that does not compile is
not a partial success — it is a failed run that costs the team a full cycle.

## The four ways an autonomous run usually fails

1. **Stopping at the happy path.** Loading, empty, error-with-retry and content are four states,
   not one plus three excuses.
2. **Swallowing errors.** An empty `catch` leaves the user staring at a blank screen. Turn every
   failure into something the UI can show and the user can retry.
3. **Drifting.** One unrelated "improvement" in the diff is enough to get the whole thing sent
   back. Anything the issue did not ask for belongs in another issue — say so at the end instead.
4. **Claiming more than you checked.** "کامپایل شد ولی روی دستگاه امتحان نشده" is a useful
   sentence. A confident claim that turns out to be wrong costs a review round and the next
   person's trust.

## Coming back from a QC rejection

The working tree already contains your previous attempt. Read it before you edit anything. Fix
exactly what the rework note lists and nothing else — everything QC did not object to has already
been accepted, and changing it buys another round.

## The report

Your last action is `.mia-report.md` in the repository root, in Persian, first person, four short
parts with no headings: what you changed and why that shape, what you deliberately did not do,
what you are unsure about, and what you could not finish. The workflow removes it before
committing, so it never appears in your diff — write it honestly.
