<div dir="rtl">

# حساب‌وکتاب توکن در MIA

> **هر تسک، دفتر هزینهٔ خودش را دارد.**
>
> از لحظه‌ای که کاربر یک دستور صوتی می‌گوید تا لحظه‌ای که ایجنت TEC کد را merge می‌کند، هر
> فراخوانی مدل روی همان ایشوی تسک ثبت می‌شود: در **بدنهٔ ایشو**، در **کامنت ایجنت**، و در
> **پیام کامیت**. بنابراین می‌توان پرسید «این تسک چقدر توکن برد؟» و جواب را دقیقاً کنار خودِ کار دید.

---

## فهرست

1. [چرا این کار لازم است](#۱-چرا-این-کار-لازم-است)
2. [نمای کلی: دفتر هزینهٔ یک تسک](#۲-نمای-کلی-دفتر-هزینهٔ-یک-تسک)
3. [مرحلهٔ ۱ — خودِ MIA (Gemini)](#۳-مرحلهٔ-۱--خودِ-mia-gemini)
4. [مرحلهٔ ۲ — ایجنت TEC (کدنویس)](#۴-مرحلهٔ-۲--ایجنت-tec-کدنویس)
5. [مرحلهٔ ۳ — ایجنت‌های مشاور PO / QC](#۵-مرحلهٔ-۳--ایجنتهای-مشاور-po--qc)
6. [اعداد از کجا می‌آیند](#۶-اعداد-از-کجا-میآیند)
7. [نمونهٔ خروجی‌ها](#۷-نمونهٔ-خروجیها)
8. [جمع‌زدن هزینه در سطح پروژه](#۸-جمعزدن-هزینه-در-سطح-پروژه)
9. [پیکربندی](#۹-پیکربندی)
10. [محدودیت‌ها و تخریب تمیز](#۱۰-محدودیتها-و-تخریب-تمیز)
11. [نقشهٔ کد](#۱۱-نقشهٔ-کد)

---

## ۱. چرا این کار لازم است

MIA روی **مدل‌های رایگان** کار می‌کند و مدل‌های رایگان OpenRouter سقف دارند (حدود ۲۰۰ درخواست در
روز و ۲۰ در دقیقه). وقتی این سقف می‌سوزد، سؤال بعدی همیشه یکی است: **کدام تسک آن را سوزاند؟**

بدون ثبت مصرف، جواب این سؤال‌ها ممکن نیست:

- کدام ایشو گران‌ترین بود و چرا؟
- آیا شرح تسکی که Gemini تولید کرد آن‌قدر بلند بود که پرامپت TEC را سنگین کند؟
- آیا مشاورهٔ `@po` و `@qc` ارزش توکنی که مصرف کرده را داشت؟
- سقف امروز کجا رفت؟

پس مصرف هر فراخوانی مدل، همان‌جا که کار انجام شده ثبت می‌شود: **روی ایشو**.

---

## ۲. نمای کلی: دفتر هزینهٔ یک تسک

سه بازیگر روی یک تسک توکن مصرف می‌کنند و هر سه در همان ایشو گزارش می‌دهند:

<div dir="ltr">

```
User speaks a command
        │
        ▼
┌─ 1. MIA app ─────────────────── Gemini (voice → intent) ──────────────────────┐
│   usageMetadata → TokenUsage                                                  │
│   ├─ Snackbar:   «🧾 1,444 توکن برای درک این دستور مصرف شد»                     │
│   └─ Issue body: 🧾 MIA voice intent · gemini-2.5-flash · 1,444 tokens …       │
└───────────────────────────────────────────────────────────────────────────────┘
        │  issue opened, labeled "by-agent"
        ▼
┌─ 2. TEC agent ───────────────── OpenCode + free OpenRouter model ─────────────┐
│   opencode run --format json → per-message token counts                       │
│   ├─ Issue comment:  💸 Token spend for #12  (table)                          │
│   ├─ PR body:        same report                                              │
│   └─ Commit trailer: Token-Spend: 41,083 tokens ($0.0000) via …               │
└───────────────────────────────────────────────────────────────────────────────┘
        │  someone asks for advice
        ▼
┌─ 3. PO / QC ─────────────────── direct OpenRouter call ──────────────────────┐
│   response.usage → per-role <sub> line + total footer on the reply            │
└───────────────────────────────────────────────────────────────────────────────┘
```

</div>

نتیجه: خواندن ترد کامنت‌های یک ایشو از بالا به پایین، همان دفتر هزینهٔ آن تسک است.

---

## ۳. مرحلهٔ ۱ — خودِ MIA (Gemini)

MIA برای تبدیل صدا به نیت، **یک** فراخوانی چندوجهی به Gemini می‌زند. پاسخ Gemini یک بخش
`usageMetadata` دارد که دقیقاً می‌گوید آن فراخوانی چند توکن بوده:

<div dir="ltr">

```json
{
  "candidates": [ … ],
  "usageMetadata": {
    "promptTokenCount": 1203,
    "candidatesTokenCount": 241,
    "thoughtsTokenCount": 0,
    "totalTokenCount": 1444
  }
}
```

</div>

این اعداد در `GeminiUsageMetadata` دیکود می‌شوند و به `TokenUsage` — شکل واحد و مستقل از
سرویس‌دهنده — تبدیل می‌شوند:

| فیلد `TokenUsage` | Gemini | OpenRouter |
|---|---|---|
| `promptTokens` | `promptTokenCount` | `prompt_tokens` |
| `outputTokens` | `candidatesTokenCount` | `completion_tokens` |
| `reasoningTokens` | `thoughtsTokenCount` | `completion_tokens_details.reasoning_tokens` |
| `totalTokens` | `totalTokenCount` | `total_tokens` |

سپس دو جا نوشته می‌شود:

1. **اسنک‌بار فارسی** در اپ — بلافاصله بعد از اجرای دستور.
2. **پای بدنهٔ ایشو** روی گیت‌هاب، بعد از یک خط جداکننده (`---`) تا با «بریفِ» ایجنت قاطی نشود.

### نکتهٔ مهم: یک دستور، چند ایشو

یک دستور صوتی می‌تواند به چند نیت تفکیک شود (مثلاً «پروژه بساز و سه تسک اضافه کن»). در آن حالت
**یک** فراخوانی Gemini هزینهٔ همهٔ آن ایشوها را داده است. پس MIA همان عدد را روی هر ایشو تکرار
نمی‌کند تا سه برابر شمرده شود؛ بلکه صادقانه می‌نویسد که این هزینه **مشترک** است:

<div dir="ltr">

> 🧾 **MIA voice intent** · `gemini-2.5-flash` · 1,444 tokens (prompt 1,203 + output 241) — one
> voice command that opened 3 issues, so this spend is shared between them.

</div>

تعداد اشتراک = تعداد نیت‌های `add_task` در همان دسته (`IntentExecutionRepository.executeAll`).

---

## ۴. مرحلهٔ ۲ — ایجنت TEC (کدنویس)

TEC گران‌ترین بازیگر است: OpenCode یک ایجنت واقعی است که فایل‌ها را می‌خواند و ویرایش می‌کند، پس
یک ایشو می‌تواند ده‌ها فراخوانی مدل داشته باشد و هر فراخوانی کل تاریخچهٔ گفت‌وگو را دوباره به
پرامپت می‌برد.

ورک‌فلو (`agent-issue-worker.yml`) این مراحل را اضافه می‌کند:

<div dir="ltr">

```
Read OpenRouter credits (before)   ← GET /api/v1/key  → data.usage
        ▼
Run OpenCode CLI                   ← --format json | tee opencode-output.log
        ▼
Read OpenRouter credits (after)    ← GET /api/v1/key  → data.usage
        ▼
Report token spend                 ← node .github/scripts/token-usage.js
        │   ├─ posts the issue comment
        │   ├─ writes token-report.md   → used in the PR body
        │   └─ writes token-trailer.txt → used in the commit message
        ▼
(quota / error / build-gate steps that may fail the job)
```

</div>

**ترتیب مراحل عمدی است.** گزارش مصرف **قبل از** هر مرحله‌ای که می‌تواند جاب را شکست بدهد اجرا
می‌شود؛ چون وقتی TEC به سقف رایگان می‌خورد یا build می‌شکند، دانستن هزینه از همیشه مهم‌تر است.

سه جایی که TEC هزینه را ثبت می‌کند:

| کجا | چه چیزی | چرا |
|---|---|---|
| **کامنت ایشو** | جدول کامل توکن + هزینهٔ دلاری | جای طبیعی برای دیدن هزینهٔ یک تسک |
| **بدنهٔ PR** | همان گزارش | هزینه کنار خودِ تغییرات دیده شود |
| **پیام کامیت** | تریلر `Token-Spend:` | ماشین‌خوان و دائمی؛ با `git log` قابل جمع‌زدن |

تریلر هم به کامیت شاخه و هم (با `gh pr merge --body`) به کامیتِ squash روی شاخهٔ پیش‌فرض می‌رود، پس
بعد از پاک شدن شاخه هم باقی می‌ماند.

### یک اصلاح جانبی لازم

نصب TEC با `npm install -g opencode-ai` همیشه **آخرین** نسخهٔ CLI را می‌آورد و OpenCode نام فلگِ
تأیید خودکار را عوض کرده است: `--auto` حالا `--dangerously-skip-permissions` است. ورک‌فلو دیگر
اسم فلگ را حدس نمی‌زند، بلکه از `opencode run --help` می‌پرسد این نسخه چه چیزی را می‌شناسد:

<div dir="ltr">

```bash
help="$(opencode run --help 2>&1 || true)"
approve=""
case "$help" in
  *--dangerously-skip-permissions*) approve="--dangerously-skip-permissions" ;;
  *--auto*)                         approve="--auto" ;;
esac
format=""
case "$help" in *--format*) format="--format json" ;; esac
```

</div>

بدون این اصلاح، نسخهٔ جدید OpenCode فلگ `--auto` را رد می‌کرد و TEC عملاً هیچ کاری انجام
نمی‌داد — یعنی گزارش توکن هم هرگز عددی برای گزارش‌دادن نداشت.

---

## ۵. مرحلهٔ ۳ — ایجنت‌های مشاور PO / QC

اسکریپت `ai-role-review.js` مستقیماً با API خودِ OpenRouter حرف می‌زند، و OpenRouter **همیشه** یک
شیء `usage` (توکن + هزینهٔ واقعیِ کسر‌شده) در پاسخ برمی‌گرداند. پس هیچ پارامتر اضافه‌ای لازم نیست
(پارامترهای قدیمی `usage: {include: true}` و `stream_options` منسوخ شده‌اند و بی‌اثرند).

هر نقش زیر پاسخ خودش یک خط کوچک می‌گیرد، و پایین کامنت یک جمع کل نوشته می‌شود.

---

## ۶. اعداد از کجا می‌آیند

برای TEC دو منبع مستقل داریم و اسکریپت `token-usage.js` هر دو را می‌خواند:

### الف) جریان رویدادهای JSON خودِ OpenCode — منبع توکن

با `--format json`، هر پیام دستیار در جریان رویدادها شمارش توکن خودش را همراه دارد:

<div dir="ltr">

```json
{
  "id": "msg_a",
  "role": "assistant",
  "cost": 0,
  "tokens": { "total": 36133, "input": 136, "output": 239, "reasoning": 174,
              "cache": { "read": 35584, "write": 0 } },
  "modelID": "openai/gpt-oss-120b:free",
  "providerID": "openrouter"
}
```

</div>

نکات پیاده‌سازی:

- **تحمل شکل (shape-tolerant):** پوشش رویدادها بین نسخه‌های OpenCode عوض می‌شود، پس اسکریپت
  به نام رویداد کار ندارد؛ کل JSON را می‌پیماید و هر شیئی که `tokens.input`/`tokens.output` (یا
  شکل OpenAI یعنی `usage.prompt_tokens`) داشته باشد را برمی‌دارد.
- **حذف تکراری‌ها:** یک پیام چند بار به‌روزرسانی می‌شود؛ کلید `id` نگه داشته می‌شود و **بزرگ‌ترین**
  عدد برنده است (شمارش‌ها فقط رشد می‌کنند).
- **جمعِ همهٔ فراخوانی‌ها:** توکن ورودی هر فراخوانی شامل کل تاریخچه است؛ این تکرار واقعی است و
  واقعاً محاسبه شده، پس جمع زدن درست است.
- `total = input + output + reasoning + cache.read + cache.write`.

### ب) شمارندهٔ اعتبار OpenRouter — منبع هزینهٔ دلاری

ورک‌فلو قبل و بعد از اجرا `GET /api/v1/key` را می‌خواند و اختلاف `data.usage` را می‌دهد. این عدد
**همان چیزی است که OpenRouter واقعاً کسر کرده** — حتی وقتی هیچ توکنی قابل استخراج نباشد. روی مدل
رایگان این اختلاف قانوناً صفر است.

اولویت: اگر خودِ پیام‌ها هزینه گزارش کرده باشند از آن استفاده می‌شود، وگرنه اختلاف اعتبار.

---

## ۷. نمونهٔ خروجی‌ها

**بدنهٔ ایشو (پای صفحه):**

<div dir="ltr">

> ---
> 🧾 **MIA voice intent** · `gemini-2.5-flash` · 1,444 tokens (prompt 1,203 + output 241)

</div>

**کامنت TEC:**

<div dir="ltr">

> ### 💸 Token spend for #12
>
> | | tokens |
> | --- | ---: |
> | Prompt (input) | 4,136 |
> | Cached input (read) | 35,684 |
> | Cache write | 50 |
> | Output | 1,039 |
> | Thinking | 174 |
> | **Total** | **41,083** |
>
> Model `openai/gpt-oss-120b:free` · 2 model calls · cost **$0.00** (free model)
>
> [Workflow run](https://github.com/…)

</div>

**وقتی سقف رایگان خورده:** تیتر عوض می‌شود تا معلوم باشد این هزینهٔ یک تلاش ناتمام است —
`### 💸 Token spend for #12 — stopped at the free-tier limit`

**پیام کامیت:**

<div dir="ltr">

```
tec: resolve #12 — صفحه ورود

Token-Spend: 41,083 tokens ($0.0000) via openrouter/openai/gpt-oss-120b:free
```

</div>

**کامنت PO/QC:**

<div dir="ltr">

> ### 🧭 Product Owner (PO)
> …پاسخ نقش…
>
> <sub>🧾 1,203 tokens (prompt 950 + output 253)</sub>
>
> ---
>
> 🧾 **Spend for this reply** — 2,406 tokens · $0.00 (free model) · `openai/gpt-oss-120b:free`

</div>

---

## ۸. جمع‌زدن هزینه در سطح پروژه

چون تریلر کامیت ماشین‌خوان است، جمع کل یک مخزن یک دستور است:

<div dir="ltr">

```bash
# every recorded spend, newest first
git log --pretty='%h %s%n  %(trailers:key=Token-Spend,valueonly)'

# total tokens the TEC agent has spent on this repo
git log --pretty='%(trailers:key=Token-Spend,valueonly)' \
  | grep -oE '^[0-9,]+' | tr -d ',' \
  | awk '{ sum += $1 } END { print sum, "tokens" }'
```

</div>

و برای دیدن هزینهٔ یک تسک خاص (همهٔ سه بازیگر روی یک ایشو):

<div dir="ltr">

```bash
gh issue view 12 --comments | grep -E '🧾|💸|Total'
```

</div>

---

## ۹. پیکربندی

**هیچ Secret یا Variable جدیدی لازم نیست.** حساب‌وکتاب توکن با همان کلیدهای موجود کار می‌کند:

| نام | نقش در گزارش مصرف |
|---|---|
| `OPENROUTER_API_KEY` | همان کلید TEC/PO/QC؛ برای خواندن شمارندهٔ اعتبار هم استفاده می‌شود |
| `AGENT_MODEL` | فقط برای برچسبِ نام مدل در گزارش |
| کلید Gemini (تنظیمات اپ) | مصرف MIA از پاسخ همان فراخوانی خوانده می‌شود |

تنها چیزی که به مخزن اضافه می‌شود، فایل ششمِ bootstrap است:
`.github/scripts/token-usage.js` (در `NetworkModule.BOOTSTRAP_ASSETS`).

---

## ۱۰. محدودیت‌ها و تخریب تمیز

**هیچ‌کدام از این‌ها جاب را شکست نمی‌دهد.** یک PR سالم نباید به‌خاطر نبودِ آمار قرمز شود؛ پس هر
مسیر خطا فقط گزارش کوتاه‌تری می‌دهد:

| وضعیت | رفتار |
|---|---|
| نسخهٔ OpenCode فلگ `--format` را نداشته باشد | فلگ اضافه نمی‌شود؛ گزارش به اختلاف اعتبار تکیه می‌کند |
| هیچ توکنی قابل استخراج نباشد | کامنت می‌گوید «per-token breakdown unavailable» و فقط هزینه را می‌دهد |
| مخزن قدیمی باشد و `token-usage.js` نداشته باشد | مرحله بی‌صدا رد می‌شود (`if [ -f … ]`) |
| فراخوانی `/api/v1/key` شکست بخورد | هزینه «cost not reported» می‌شود، توکن‌ها سر جایشان می‌مانند |
| خودِ اسکریپت خطا بدهد | خطا لاگ می‌شود، `|| true` جلوی شکست جاب را می‌گیرد |
| Gemini `usageMetadata` نفرستد | `TokenUsage` تهی می‌شود، ایشو بی‌پاورقی باز می‌شود |

**دو محدودیت که باید بدانید:**

1. **اختلاف اعتبار مطلق نیست.** اگر همان کلید OpenRouter هم‌زمان جای دیگری استفاده شود، اختلاف
   می‌تواند مصرف غیرِ این ایشو را هم شامل شود. `concurrency.group: agent-worker` اجراهای TEC را
   روی یک مخزن سریال می‌کند، ولی چند مخزن با یک کلید مشترک می‌توانند هم‌پوشانی کنند. عدد در صفر
   کلیپ می‌شود تا هرگز منفی نشود.
2. **توکن‌های کش‌خوانده شده در جمع کل می‌آیند** (همان‌طور که خودِ OpenCode می‌شمارد). این‌ها
   ارزان‌ترند ولی صفر نیستند، و در جدول جداگانه نشان داده می‌شوند تا با ورودی تازه اشتباه نشوند.

---

## ۱۱. نقشهٔ کد

<div dir="ltr">

| File | Role |
|---|---|
| `data/model/TokenUsage.kt` | Provider-neutral usage record + the issue footer / Persian summary |
| `network/gemini/GeminiModels.kt` | `usageMetadata` on the Gemini response |
| `data/repository/GeminiVoiceIntentClassifier.kt` | Returns `Classification(intents, usage)` |
| `data/repository/IntentExecutionRepository.kt` | Threads usage through, computes the shared-by count |
| `data/repository/GitHubRepository.kt` | Appends the footer to the issue body |
| `ui/main/MainViewModel.kt` | Adds the spend line to the confirmation snackbar |
| `docs/github/scripts/token-usage.js` | Parses the OpenCode stream, posts TEC's spend comment |
| `docs/github/scripts/ai-role-review.js` | Per-role + total spend on PO/QC replies |
| `docs/github/workflows/agent-issue-worker.yml` | Credit snapshots, JSON stream, report step, commit trailer |
| `app/src/test/…/TokenUsageTest.kt` | Footer/total/locale contract |

</div>

> یادآوری: فایل‌های زیر `docs/github/` و `app/src/main/assets/` باید **بایت‌به‌بایت** یکسان بمانند
> — نسخهٔ assets همان چیزی است که اپ در زمان اجرا به مخزن‌های تازه آپلود می‌کند.

---

<div align="center">

**MIA** — هر تسک، هزینهٔ خودش را گزارش می‌دهد.

</div>

</div>
