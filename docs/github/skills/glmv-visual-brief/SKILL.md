---
name: glmv-visual-brief
description: >
  Read the screenshots, mockups and screen recordings attached to an issue with GLM-V, and turn
  what is actually in them into brief text, acceptance criteria or a review finding. Use it
  whenever the request points at a picture instead of describing what it wants.
roles: [po, brief, qc, tec]
requires: [ZHIPU_API_KEY]
triggers:
  - "![" 
  - "user-images.githubusercontent"
  - "github.com/user-attachments"
  - ".png"
  - ".jpg"
  - ".jpeg"
  - "screenshot"
  - "mockup"
  - "wireframe"
  - "figma"
  - "اسکرین‌شات"
  - "اسکرین شات"
  - "عکس"
  - "تصویر"
  - "طرح"
  - "ماکاپ"
  - "پیش‌نمایش"
---

# Reading the picture instead of guessing at it

Adapted from Z.ai's `glmv-caption` and `glmv-grounding`
(github.com/zai-org/GLM-skills). The Python CLIs and their dependencies are replaced by
`.github/scripts/glm-vision.js`, which speaks the same API over Node with no install step.

An issue that says «مثل این عکس» and attaches a screenshot contains its whole specification in a
file you have not opened. Guessing at it produces a confident brief about a screen nobody
designed. Open it.

## Run it

```bash
node .github/scripts/glm-vision.js describe <url-or-path> [...] --prompt "<what you need to know>"
```

Prints `{"ok":true,"text":"…"}` on stdout. GitHub attachment URLs work directly; so do files in
the checkout. On `{"ok":false}` say so plainly and carry on from the text of the issue — a failed
call is a missing input, never a reason to invent what the image showed.

## Ask for what your seat needs

The default prompt describes the picture. That is rarely what you want.

- **PO / BRIEF** — `--prompt "فهرست کن: هر عنصر رابط کاربری، متن دقیق فارسی روی آن، ترتیب از بالا
  به پایین، و هر حالتی که در تصویر دیده می‌شود (خالی، در حال بارگذاری، خطا)."`
  Then write the brief from that list, not from the picture as a whole. Every element you name
  becomes something TEC can build and QC can check.
- **TEC** — `--prompt "رنگ‌ها را با کد هگز، اندازهٔ فونت‌ها، فاصله‌ها و سلسله‌مراتب چیدمان بده."`
  Then map each value onto a token this project already has. Do **not** hard-code what the image
  reports: the picture is the intent, `Tokens.kt` / `tokens.css` is the implementation.
- **QC** — `--prompt "در این تصویر چه چیزی با توضیح ایشو نمی‌خواند؟"` with the screenshot of the
  result. A visual diff is a finding you can point at, which is the only kind worth writing.

## Write down what you saw

Whatever you extract goes into your answer **as text**. The next role does not get the image, the
API call or your reading of it — it gets your comment. An acceptance criterion that says
«مطابق تصویر» is unimplementable and unverifiable; the same criterion written as
«دکمهٔ «ثبت» در پایین صفحه، تمام‌عرض، با فاصلهٔ ۱۶ از لبه‌ها» survives the handoff.

Say where a detail came from when the picture and the text disagree — and say which one you
followed. Two different readings of the same screenshot is exactly the ambiguity that belongs
under **پرسش‌های باز**, with the assumption you are proceeding on.
