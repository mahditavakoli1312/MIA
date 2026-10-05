---
name: glmocr-doc-intake
description: >
  Pull requirements out of a PDF, a scan, a photographed page or a table with GLM-OCR — the
  documents a brief points at instead of restating. Use it when the request references a file
  whose contents are not in the issue text.
roles: [po, brief, qc]
requires: [ZHIPU_API_KEY]
triggers:
  - ".pdf"
  - ".docx"
  - ".xlsx"
  - "ocr"
  - "سند"
  - "مستند"
  - "پی‌دی‌اف"
  - "جدول"
  - "فاکتور"
  - "قرارداد"
  - "اسکن"
  - "فرم"
---

# Requirements that live in a document

Adapted from Z.ai's `glmocr` and `glmocr-table` (github.com/zai-org/GLM-skills), running through
`.github/scripts/glm-vision.js` instead of the bundled Python CLI.

```bash
node .github/scripts/glm-vision.js ocr <pdf|image|url>
```

Returns the page as markdown: prose as prose, **tables as markdown tables**, formulas as LaTeX.
Handwriting included. One file per call.

## Why this belongs to PO and BRIEF

A brief that says «طبق فرم پیوست» has moved its own specification into a file. Nobody downstream
can see it: TEC gets the issue body and nothing else, and QC checks criteria, not attachments. If
you do not transcribe it, it is not a requirement — it is a rumour.

So the job is not "read the document". It is **turn the document into the part of the brief that
was missing**:

1. Run the OCR and read the output whole.
2. Pull out only what the change actually depends on — the field names and their order, the
   rules, the exact Persian labels, the validation constraints, the numbers in the table.
3. Write those into `## مشخصات فنی` and `## معیارهای پذیرش` **as text**, one checkable line each.
4. Keep the OCR dump out of the issue body. Paste it in a `<details>` block if someone will need
   to audit it, and never in place of the criteria you were supposed to write from it.

## What OCR gets wrong, and what to do about it

Persian OCR is good, not perfect, and the errors are systematic: Arabic vs. Persian ی and ک,
digits switching between ۱۲۳ and 123, a merged cell read as two, a stamped or handwritten
correction over printed text.

Anything that becomes a **string in the product**, a **field name** or a **number in a rule**
gets read back against the image before you write it down. Where you are unsure, say which
reading you took under **پرسش‌های باز** with the assumption attached — a silently mis-transcribed
field name is a bug nobody can find later, because the issue and the code agree with each other
and both disagree with the document.

If the call fails, say the document could not be read and write the brief from what the issue
text does say, naming the gap. A brief that admits a hole is workable; one that invents the
missing form is not.
