---
name: mia-po-brief
description: >
  Turn a request into a brief TEC can implement correctly on the first attempt — scope,
  verifiable acceptance criteria, UI/UX guidance, and an explicit list of what is out.
  Use it whenever you are writing or sharpening the body of an issue.
roles: [po]
always: true
---

# Writing a brief a small model can implement

Your reader is TEC: an autonomous coding agent on a small free model that reads **one issue and
nothing else**. It has no memory of this conversation, no access to your reasoning, and no way to
ask you a question mid-run. Whatever is not in the issue body will not be built.

So the test of a brief is not "is this clear to a colleague". It is: **would two different
implementations of this text come out the same?**

## The five parts, in order

1. **`## شرح`** — two or three sentences. Who is better off after this change, and how. Name the
   screen, the command or the endpoint by the name it already has in this repo.
2. **`## مشخصات فنی`** — the decisions you are making *for* TEC so it does not make them badly:
   which existing file or component to extend, where a new file goes, the data shape, the
   error path. Name real paths from the file list. Never write "an appropriate place".
3. **`## راهنمای طراحی (UI/UX)`** — only when a user can see the change. Layout, the elements in
   order, wording (in Persian), which design tokens and shared components to reuse, and all four
   states: loading, empty, error-with-retry, content. Concretely enough that two implementations
   would look alike.
4. **`## معیارهای پذیرش`** — a `- [ ]` checkbox list. See the rule below.
5. **`## فایل‌های مجاز`** — the paths TEC may touch. This is enforced: a run that edits anything
   else is thrown away. List directories generously enough that the work is possible, and never
   so generously that "while I was there" becomes a merge conflict.

## The acceptance-criteria rule

Every line must be checkable by **looking at the result**, with no judgement call.

| Rejected | Why | Written properly |
|---|---|---|
| مدیریت خطا به‌درستی انجام شود | "به‌درستی" is not observable | وقتی درخواست شبکه شکست می‌خورد، `MiaError` با پیام فارسی و دکمهٔ «تلاش دوباره» نشان داده می‌شود |
| رابط کاربری تمیز باشد | taste, not a criterion | فاصله‌ها از `Spacing` و رنگ‌ها از `MaterialTheme.colorScheme` خوانده می‌شوند؛ هیچ `Color(0x…)` یا `.dp` خامی در فایل نیست |
| تست‌ها اضافه شوند | which tests, of what | یک تست واحد در `app/src/test/.../XRepositoryTest.kt` مسیر خطا را پوشش می‌دهد |

If you cannot write a line that way, you do not yet know what you are asking for — and that is
the finding, not a reason to write a vaguer line.

## Size

If the brief needs more than **two files of real work**, it is two issues. Split it, order the
pieces so prerequisites come first, and say which one to start with. A brief that is too big does
not fail loudly; TEC implements the first third of it confidently and QC has to reject a diff
that is not wrong, only incomplete.

## When the request is ambiguous

Do not stop and ask. Under **پرسش‌های باز**, write the question *and* the answer you will proceed
on if nobody replies. A question with an assumption attached keeps the work moving and still
lets a human correct you; a bare question stops the repository.

## Before you post

- [ ] Every acceptance criterion names a concrete behaviour, file, state or string.
- [ ] Nothing in the brief was invented — it is in the request, or it is an open question.
- [ ] The scope section says what is **out**, not only what is in.
- [ ] `فایل‌های مجاز` exists and is reachable from the repository's file list.
- [ ] The last line says who acts next.
