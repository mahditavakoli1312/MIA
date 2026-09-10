<div dir="rtl">

# پرامپت‌های Claude Code — تیمِ انسانی و حلقه‌ای که نمی‌شکند

> **هدف طلایی:** هر گفت‌وگویی میان ایجنت‌ها باید به **اتمام کار** برسد. هیچ ایشویی حق ندارد
> بدون «مالک» و بدون «قدم بعدی» رها شود، و هیچ حالتی در سیستم نباید بن‌بست باشد.
>
> در کنارش: PO، QC، تیم فنی و مدیر بریف باید مثل **آدم** حرف بزنند و مثل آدمِ مسئول رفتار
> کنند — نه مثل یک بات که یک قالب را پر می‌کند.

این سند مکمل [`claude-code-prompts.md`](claude-code-prompts.md) است و همان قواعد را دارد:
هر بلوک انگلیسی را عیناً در Claude Code (در ریشهٔ مخزن MIA) پیست کنید، **یکی‌یکی**، به همین ترتیب.

---

> ## ✅ وضعیت کلی: هر ۱۵ پرامپت پیاده شد
>
> هر بخش زیر یک بلوک «وضعیت» دارد که می‌گوید دقیقاً چه چیزی ساخته شد و کجا. خلاصه:
>
> **فایل‌های تازه** — `agent-voice.js` (منشور، صندلی‌ها، امضای تحویل، جدول حالت‌ها)،
> `ledger.js`، `say.js`، `unblock.js`، `triage-failure.js`، `shepherd.js`، `brief-close.js`،
> `unblock-dependents.yml`، `shepherd.yml`، `scripts/check-loop-closure.sh` و سه فایل تست در
> `scripts/tests/`. همهٔ فایل‌های تیم در `BOOTSTRAP_ASSETS` ثبت و به `app/src/main/assets/`
> آینه شدند.
>
> **بازنویسی‌شده** — `ai-provider.js` (منشور در `askAI`، امضای **اجباری** در `postComment`)،
> `ai-role-review.js`، `qc-review.js`، `po-rebrief.js`، `decompose-brief.js` (حالت SPLIT)،
> `agent-issue-worker.yml`، `decompose-brief.yml`، `ai-role-review.yml`، `qc-review.yml`،
> `ci.yml`، و `.github/workflows/assets-sync.yml`.
>
> **بررسی‌های سبز:** `scripts/check-assets-sync.sh`، `scripts/check-loop-closure.sh`
> (۳۱ تست)، `node --check` روی هر اسکریپت، parse شدن هر workflow، و
> `./gradlew :app:compileDebugKotlin`. یک بررسی جداگانه هم روی همهٔ workflowها انجام شد: هر
> مرحله‌ای که یک اسکریپت node را صدا می‌زند، متغیرهای لازمش (`REPO`، `GITHUB_TOKEN`،
> `ISSUE_NUMBER`) را در دسترس دارد — همین بررسی یک باگ واقعی پیدا کرد (هشت مرحله در
> `agent-issue-worker.yml` که `say.js` را بدون `REPO`/`GITHUB_TOKEN` صدا می‌زدند و کامنتشان
> بی‌صدا گم می‌شد؛ هم متغیرها به سطح job رفتند و هم `say.sh` حالا روی **شکست** node هم به
> مسیر جایگزین می‌افتد، نه فقط روی نبودنش).
>
> آنچه **پیاده نشده** فقط آزمونِ سرتاسری روی یک مخزن واقعی است — پنج سناریوی
> [آزمون پایانی](#آزمون-پایانی--روی-یک-مخزن-آزمایشی) به مخزنی نیاز دارند که ایجنت‌هایش واقعاً
> اجرا شوند.

---

## فهرست

- [وضعیت امروز: کجا حلقه می‌شکند](#وضعیت-امروز-کجا-حلقه-میشکند)
- [قانون همیشگی این موج](#قانون-همیشگی-این-موج)
- [گروه الف — انسان‌بودن و لحن](#گروه-الف--انسانبودن-و-لحن)
- [گروه ب — مسئولیت‌پذیری](#گروه-ب--مسئولیتپذیری)
- [گروه ج — پیوستگی (هدف طلایی)](#گروه-ج--پیوستگی-هدف-طلایی)
- [گروه د — تضمین و سند](#گروه-د--تضمین-و-سند)
- [ترتیب اجرا و آزمون پایانی](#ترتیب-اجرا-و-آزمون-پایانی)

---

## وضعیت امروز: کجا حلقه می‌شکند

حلقهٔ `TEC → QC → TEC → PO → TEC` امروز کار می‌کند و عمداً fail-open است (فایل‌های
`qc-review.js` و `po-rebrief.js`). اما **هفت بن‌بست** واقعی در کد هست که کار را زمین می‌گذارند:

| # | بن‌بست | کجا |
|---|--------|-----|
| ۱ | ایشوی `blocked` وقتی بلاک‌کننده‌اش بسته شد، هیچ‌وقت آزاد نمی‌شود | `decompose-brief.js` — برچسب `blocked` می‌خورد و تمام |
| ۲ | آیتم `L` با یادداشت «نیاز به تقسیم دستی» می‌ماند؛ هیچ ایجنتی سراغش نمی‌رود | `decompose-brief.js` (`if (child.size === "L") continue`) |
| ۳ | `agent-failed` پایان راه است: «Re-queue it by hand» | `agent-issue-worker.yml` → «Release the queue slot» |
| ۴ | `needs-human` هیچ خروجی خودکاری ندارد | `qc-review.js` (سقف `AGENT_MAX_CYCLES`) |
| ۵ | بریف بعد از تمام‌شدنِ بچه‌هایش هیچ‌وقت بررسی و بسته نمی‌شود | `decompose-brief.js` — چک‌لیست دستی می‌ماند |
| ۶ | پاسخ‌های `@po` / `@qc` فقط «مشورت» است و به کار تبدیل نمی‌شود | `ai-role-review.js` |
| ۷ | ران نیمه‌مرده (timeout/cancel) صف را رها می‌کند و کسی خبردار نمی‌شود | هیچ ناظری وجود ندارد |

و از نظر لحن: هر چهار نقش، پرامپت‌های «قالب‌پرکن» دارند — بدون نام، بدون اول‌شخص، بدون
اینکه بگویند چه چیزی را نمی‌دانند یا قدم بعدی با کیست.

**قرارداد مرکزی این موج (همه‌جا به آن ارجاع می‌دهیم):**

<div dir="ltr">

```
THE HANDOFF INVARIANT
Every open issue is, at every moment, in exactly one state; every state has exactly one
owner and at least one automatic exit. No comment by any role may end without naming who
acts next and what they must do. A state with no exit is a bug, not a resting place.
```

</div>

---

## قانون همیشگی این موج

این بلوک را به **هر** پرامپت این سند بچسبانید:

<div dir="ltr">

```
House rules for this repo:
- app/src/main/assets/ and docs/github/ hold the SAME files and must stay byte-for-byte
  identical. Edit one, copy it to the other, then run `bash scripts/check-assets-sync.sh`.
- A NEW shared agent file must also be registered in BOOTSTRAP_ASSETS in
  app/src/main/java/ir/mahditavakoli/mia/network/NetworkModule.kt, or generated repos
  will never receive it and the sync check will fail.
- Docs in docs/ are Persian and RTL (<div dir="rtl">), with LTR code blocks. Match that.
- Comments explain WHY, not what. Match the density and voice of the surrounding code.
- FAIL OPEN, NEVER FAIL SILENT: every new failure path must end with the work still
  moving and a comment on the issue saying what happened. A step that errors must never
  leave an issue without an owner.
- Do not break the STRICT JSON contracts that qc-review.js, po-rebrief.js and
  decompose-brief.js depend on. Add fields; never rename or remove one.
- Never commit or push unless I ask.
```

</div>

---

## گروه الف — انسان‌بودن و لحن

### ۵.۱. منشور تیم: یک فایل، چهار آدم

> **وضعیت: ✅ انجام شد.** `docs/github/scripts/agent-voice.js` ساخته شد: `SEATS` (چهار صندلی با
> نام فارسی، دستهٔ `@po`/`@qc`/`@tec`، و «چه چیزی را مالک است» و «پاسخ‌گوی چیست»)، `seat(role)`
> که برای نقش ناشناس هم یک صندلیِ جایگزین برمی‌گرداند تا هیچ ران روی یک برچسبِ ناشناخته نشکند، و
> `charter(role)` که منشور مشترک را با دو خط هویتِ صندلی ترکیب می‌کند. منشور صریحاً اول‌شخص،
> گفتنِ «چه چیزی را دیدم»، گفتنِ عدم‌قطعیت، مالکیتِ نتیجه و «هیچ پیامی بدون مالکِ بعدی» را حکم
> می‌کند، و فهرست ممنوعه‌ها («با سلام و احترام»، «لازم به ذکر است»، علامت تعجب، بیش از یک ایموجی،
> انگلیسیِ وسط جمله) را نام می‌برد. اتصال در `askAI` انجام شد — تنها گلوگاهی که هر چهار نقش از آن
> رد می‌شوند — با `role` که پیش‌فرض از `ai.role` می‌آید، پس همهٔ فراخوان‌های موجود بدون تغییر
> منشور را می‌گیرند. فایل به `app/src/main/assets/agent-voice.js` آینه شد و در `BOOTSTRAP_ASSETS`
> ثبت شد؛ `check-assets-sync.sh` سبز است.

امروز شخصیتِ هر نقش داخل رشته‌های پراکندهٔ system prompt است. اول یک **منشور مشترک** بسازید
که هر چهار نقش از آن ارث ببرند؛ بعدش هر تغییر لحن یک‌جا اعمال می‌شود.

<div dir="ltr">

```
Create a shared "team charter" that every AI role in a generated repo inherits, so the PO,
QC, TEC and the brief manager read as four professional colleagues rather than four form
fillers.

Read first: docs/github/scripts/ai-provider.js (askAI is the single choke point every role
goes through), docs/github/scripts/ai-role-review.js, docs/github/scripts/qc-review.js,
docs/github/scripts/po-rebrief.js, docs/github/scripts/decompose-brief.js.

Create docs/github/scripts/agent-voice.js exporting:
- `charter(role)` — the shared system-prompt preamble for "po" | "qc" | "tec" | "brief".
- `SEATS` — one entry per role: a short human seat name, what that seat owns, and what it
  is accountable for. Persian-first labels, since MIA issues are Persian.

The charter text must state, in the model's own instructions:
- You are a named member of a small product team, writing to colleagues who will act on
  what you say. Write in the first person. Never refer to yourself in the third person and
  never announce that you are an AI.
- Say what you actually looked at before you decided, in one line. A judgement with no
  basis is worth less than no judgement.
- Say plainly what you are NOT sure about, and what would change your mind. Never hide
  uncertainty behind confident phrasing.
- Own the outcome: you are accountable for this issue reaching "done", not for having
  filed an opinion about it. If your answer does not move the work forward, it is not
  finished.
- Never end a message without naming who acts next and what exactly they must do.
- Disagree with a colleague directly and specifically, about the work, never about them.
  When you were wrong earlier in the thread, say so in one sentence and move on.

Tone rules (be explicit — this is the part that makes it feel human):
- Professional Persian, the register of a good colleague in a stand-up: warm, direct,
  economical. Formal enough to read well in an issue a client might see.
- Ban: corporate filler ("با سلام و احترام", "لازم به ذکر است", "پیشاپیش سپاسگزارم"),
  apology stacking, exclamation marks, more than one emoji per message, and switching
  into English mid-sentence for words Persian already has.
- Short sentences. No paragraph over four lines. Contractions of natural speech are fine.
- Address colleagues by their seat handle (@po, @qc, @tec) when you are handing work over.

Wire it in: `askAI` in ai-provider.js takes an optional `role`, and when given, prepends
`charter(role)` to the system prompt. Every existing call site passes its role. Do not
change askAI's return shape or any caller's error handling.

Mirror the new file to app/src/main/assets/agent-voice.js, register it in
BOOTSTRAP_ASSETS as `.github/scripts/agent-voice.js`, and run scripts/check-assets-sync.sh.

Done when: every role's model call carries the same charter, the sync check passes, and
`node -e "require('./docs/github/scripts/agent-voice.js').charter('qc')"` prints it.
```

</div>

**تمام‌شده وقتی:** یک فایل، منبعِ لحنِ کل تیم است و هر چهار اسکریپت از آن رد می‌شوند.

---

### ۵.۲. نقش‌های مشورتی: از «قالب» به «آدمِ صاحب‌نظر»

> **وضعیت: ✅ انجام شد.** `ADVISORY_CONDUCT` به هر دو نقش اضافه شد (یک متن مشترک، چون هر دو
> دقیقاً به یک شکل شکست می‌خورند): شروع با «برداشت من از این درخواست»، گفتنِ اینکه کدام قرائت را
> انتخاب کرده و چرا، سرفصل تازهٔ **پرسش‌های باز** که هر خطش یک پرسش **به‌علاوهٔ پاسخی است که در
> نبودِ جواب فرض می‌گیرد** — بارِ اصلی همین‌جاست: مدلی که می‌ایستد تا بپرسد، کار را خوابانده و
> مدلی که بی‌صدا حدس می‌زند، تصمیم را پنهان کرده — و خط آخرِ **قدم بعدی**. سرفصل‌های قبلی
> دست‌نخورده ماندند چون `po-rebrief.js` و `qc-review.js` رویشان حساب می‌کنند. عنوان هر بخش حالا
> از `SEATS` می‌آید (🧭 مالک محصول / ✅ تیم کیفیت) و بنرِ «🤖 AI role responses» حذف شد — اعلامِ
> ماشین بالای جوابِ دو همکار، همان لحنی است که منشور برای حذفش نوشته شده؛ فقط وقتی دو نقش
> هم‌زمان جواب بدهند یک خط می‌گوید دو نظر پشت سر هم می‌آید. مرزِ داده/دستور، تطبیق زبان و
> مسیرِ تنزلِ خطا دست‌نخورده‌اند.

<div dir="ltr">

```
Rewrite the @po and @qc personalities in docs/github/scripts/ai-role-review.js so they read
as two experienced colleagues answering a teammate, while keeping every existing heading
their downstream consumers rely on.

Read first: docs/github/scripts/ai-role-review.js, docs/github/scripts/agent-voice.js.

For both roles, add these instructions to their system prompt (the section list itself
stays exactly as it is today — po-rebrief.js and qc-review.js parse those headings):
- Open with one or two sentences in your own voice: what you understood the request to be,
  and the single thing you think matters most about it. Not a summary of the issue back at
  the reader — your reading of it.
- Where the request is ambiguous, say which reading you chose and why, instead of silently
  picking one. List genuine open questions under an explicit "پرسش‌های باز" heading, each
  with the answer you will assume if nobody replies — so silence still moves the work.
- Close with a "قدم بعدی" line naming the next actor and action, in one sentence, in your
  own words.
- When a previous comment in this thread came from another role, respond to it by name
  rather than repeating advice it already gave.

Also change the reply rendering: replace the bare "🤖 AI role responses" header with the
seat names from agent-voice.js SEATS, so a reader sees who is speaking. Keep the per-role
token footer and the spend footer exactly as they are.

Do not change: the section headings, the language-matching rule, the data-boundary
paragraph that neutralises prompt injection from issue text, or the failure path that
degrades to a warning line per role.

Done when: an @po comment reads as a person's answer with a named next step, and every
existing heading is still present.
```

</div>

---

### ۵.۳. دروازهٔ QC و بازنویسی PO: JSON بماند، آدم اضافه شود

> **وضعیت: ✅ انجام شد.** قرارداد JSON هیچ‌کدام نشکست — فقط فیلد گرفت. `qc-review.js` حالا
> `message` (دو تا چهار جمله، اول‌شخص فارسی: چه چیزی را خواندم، چه چیزی قانعم کرد، چه چیزی را
> نتوانستم از diff تأیید کنم) و `first` (کدام ایرادِ بازدارنده اول) می‌خواهد. هر دو **بردبار**
> اعتبارسنجی می‌شوند: نبودشان هرگز یک حکمِ سالم را به `skip` تبدیل نمی‌کند، وگرنه تصمیمِ merge
> دوباره بی‌صاحب می‌شد. `message` بالای جدول معیارها رندر می‌شود (هم در approve هم در rework) و
> `first` زیر فهرست بازدارنده‌ها به‌صورت «**از این‌جا شروع کن:** …» می‌آید و **همان** به امضای
> تحویل هم می‌رود — چون فهرست بازدارنده یک مجموعه است و مجموعه ترتیب ندارد، و TEC از بالا شروع
> می‌کند. SYSTEM هم این خط را گرفت: «تو کسی هستی که باید جواب این merge را بدهی» — تأیید کن حتی
> اگر خودت جور دیگری می‌نوشتی، و فقط چیزی را رد کن که می‌توانی در diff نشانش بدهی. در
> `po-rebrief.js` هم `message` اضافه شد و SYSTEM می‌خواهد PO بگوید **کدام جمله** دوپهلو بود و دو
> پیاده‌ساز چه دو برداشتی از آن می‌کردند — نه «بریف ناقص بود».

اینجا نمی‌شود قرارداد JSON را شکست — پس **یک فیلد پیام** اضافه می‌کنیم که به‌عنوان متنِ
انسانی کامنت رندر شود.

<div dir="ltr">

```
Give the QC gate and the PO re-scope a human voice without touching their JSON contracts.

Read first: docs/github/scripts/qc-review.js (SYSTEM + criteriaTable + the approve/rework
comment bodies), docs/github/scripts/po-rebrief.js (SYSTEM + the re-scope comment),
docs/github/scripts/agent-voice.js.

qc-review.js:
- Add one optional field to the JSON schema: `"message": "<two to four sentences, first
  person, Persian: what I checked, what convinced me, what I am unsure about>"`. Validation
  stays permissive for it — a missing or empty `message` must never turn a valid verdict
  into a skip.
- Render `message` as the opening paragraph of the PR comment, above the criteria table,
  in both the approve and the rework path.
- In the rework path, the blocking list stays a literal, actionable list. Add one sentence
  under it, written by QC, saying which single item it would fix first and why — TEC needs
  a priority, not just a set.
- Extend SYSTEM so the model is told: you are the person who will have to answer for this
  merge; approve when the criteria are met even if you would have written it differently,
  and reject only for things you can point at in the diff.

po-rebrief.js:
- Add `"message"` to its JSON schema the same way, and render it as the opening paragraph
  of the re-scope comment, before "What changed".
- Extend SYSTEM: you are taking responsibility for a brief that failed twice. Say what you
  believe was actually unclear — not "it was underspecified" but which sentence, and what
  two different implementers would have read it to mean.

Keep every existing rule, especially: strict JSON only, same scope, one criteria row per
numbered criterion, and every failure path ending in the loop continuing.

Done when: a QC rework comment starts with QC explaining itself in Persian and still
carries the identical table, blocking list, labels and verdict file as before.
```

</div>

---

### ۵.۴. TEC: از «ران» به «کارِ کسی»

> **وضعیت: ✅ انجام شد.** یک ابزار تازه لازم شد: `say.js` — «دهانِ TEC». سه نقش دیگر از
> `postComment` رد می‌شوند و امضای تحویل را مجانی می‌گیرند، ولی یک workflow با
> `gh issue comment` حرف می‌زند که هیچ چیزی را رد نمی‌کند؛ ده فراخوان پراکنده یعنی ده فرصت برای
> بی‌صاحب گذاشتن ایشو. حالا همهٔ کامنت‌های workflow از `${RUNNER_TEMP}/say.sh` رد می‌شوند که یا
> `say.js` را صدا می‌زند یا (روی مخزن‌های قدیمی) خودش امضا را دستی می‌نویسد — «یک جا برای تغییر»
> که پرامپت خواسته بود. **معرفی:** «این را برداشتم: «\<عنوان\>». می‌روم سراغ پیاده‌سازی…» به‌جای
> «TEC picked this up from the queue». **گزارش:** بخش تازهٔ `## How you report` در HEADER از TEC
> می‌خواهد آخرین کارش نوشتن `.mia-report.md` باشد — چه چیزی را عوض کردم و چرا این شکل، چه
> چیزی را عمداً نکردم، کجا مطمئن نیستم (QC اول همان را ببیند)، چه چیزی نیمه‌کاره ماند — با این
> قاعده که چیزی را «کار می‌کند» نگو که واقعاً اجرا نکرده‌ای. نگهبانِ دامنه فایل را در **اولین**
> دروازهٔ بعد از ایجنت برمی‌دارد و پاک می‌کند، پس نه وارد diff می‌شود، نه در فهرست مجاز حساب
> می‌شود، نه «هیچ تغییری نکرد» را خراب می‌کند (مرحلهٔ land هم برای مخزن‌هایی که این دروازه را
> ندارند دوباره پاکش می‌کند). این یادداشت **بالای** خط مکانیکیِ merge/PR منتشر می‌شود. همهٔ
> مسیرهای شکست هم اول‌شخص و صاحب‌دار شدند: کلیدِ نبود → مکثِ صاحب‌دارِ آدم، سقف سهمیه → «صبر
> است، نه شکست» و ایشو دوباره `by-agent` می‌شود، خطای ناشناخته → تریاژ (۵.۹)، شکست build →
> TEC، ردِ دامنه و «هیچ تغییری نکرد» → PO با `needs-po` (قبلاً «برای بازبینی یک آدم باز
> می‌ماند» بود، که مؤدبانه‌ترین شکلِ زمین‌گذاشتنِ کار است).

<div dir="ltr">

```
Rewrite TEC's own voice — its prompt preamble and the four comments it posts on an issue —
so the engineer seat reads as a person accountable for the change.

Read first: docs/github/workflows/agent-issue-worker.yml — the "Acknowledge the request"
step, the HEADER prompt block in "Build the prompt", and the comments in the landing step
(merged / PR-not-merged / no-PR) and the give-up path.

Changes:
- The acknowledge comment becomes one line in the first person naming what TEC is about to
  do, from the issue title, plus the run link. Not "TEC picked this up from the queue".
- Add a "## How you report" section to the HEADER prompt: at the end of your run, write a
  short Persian note for your teammates — what you changed and why you chose that shape,
  what you deliberately did NOT do and why, and anything you are unsure about that QC
  should look at first. Never claim something works that you did not verify.
- Have the workflow capture that note (the agent's final output is already collected for
  the run log) and post it as the body of the merge/PR comment, above the existing
  mechanical line about the PR and the branch.
- The give-up path must never end the conversation. Its comment says, in the first person,
  how far it got, what is on the branch, what stopped it, and hands the issue to a named
  next owner — which prompt 5.8 makes automatic.

Keep every hard rule in the prompt exactly as it is (no git commands, path allowlist, no
invented scope, the design-system section, the data boundary around the issue text).

Done when: reading one issue's timeline top to bottom sounds like a person reporting on
their own work, and no step's comment ends without a next owner.
```

</div>

---

## گروه ب — مسئولیت‌پذیری

### ۵.۵. امضای تحویل: نوشتنِ کامنتِ بی‌مالک غیرممکن شود

> **وضعیت: ✅ انجام شد.** `agent-voice.js` سه چیز تازه دارد: `handoff({from,to,next,issue,pr,sla})`
> که یک جملهٔ فارسی («@tec — …، اگر تا ۳۰ دقیقه حرکتی نبود چوپان پیگیری می‌کند») و یک کامنت
> ماشین‌خوان `<!-- mia:handoff v=1 … -->` می‌سازد، `parseHandoff()` که آخرین امضای هر متن را
> برمی‌گرداند و در برابر فیلدِ گم‌شده بردبار است، و `TERMINAL` (`done`/`closed`) به‌علاوهٔ هدفِ
> ویژهٔ `HUMAN` — که آن هم مالک است و مهلت دارد، نه بهانه‌ای برای توقف. متنِ `next` پاک‌سازی
> می‌شود (`--` و `<>`) تا کامنت HTML نشکند. در `ai-provider.js` آرگومان `handoff` برای
> `postComment` **اجباری** شد و بدون آن `throw` می‌کند؛ همین است که «همیشه بگو نفر بعد کیست» را
> از یک توصیه در پرامپت به چیزی تبدیل می‌کند که کد نمی‌تواند فراموشش کند. همهٔ فراخوان‌ها به‌روز
> شدند: QC (approve/rework/skip/سقف دوره/تحویل به PO)، PO (بازتعریف و شکستِ بازتعریف)،
> تجزیهٔ بریف (`planHandoff` — صف به TEC، نقشهٔ بی‌آیتمِ قابل‌شروع به PO، بریفِ بی‌حاصل به آدم)،
> و نقش‌های مشورتی. `token-usage.js` عمداً دست‌نخورده ماند: آن یک رسیدِ حسابداری است که همیشه
> کنار پیام واقعی TEC می‌آید، و امضای تقلبی روی آن، قاعدهٔ «امضا باید همان چیزی را بگوید که متن
> می‌گوید» را نقض می‌کرد — چوپان هم آخرین کامنتِ **دارای امضا** را می‌خواند، نه آخرین کامنت.

این ستون فقراتِ هدف طلایی است. اگر هر کامنت مالک و قدم بعدی داشته باشد، ناظرِ ۵.۹ می‌تواند
هر توقفی را پیدا کند.

<div dir="ltr">

```
Make it structurally impossible for any role in a generated repo to leave a message without
an owner and a next action.

Read first: docs/github/scripts/ai-provider.js (postComment is the single function every
role posts through), docs/github/scripts/agent-voice.js.

In agent-voice.js add:
- `handoff({ from, to, next, issue, pr, sla })` → returns the rendered handoff block: one
  human Persian sentence ("@tec — <next>. تا <sla> دیگر سراغش می‌روم.") followed by a
  machine-readable HTML comment:
  <!-- mia:handoff v=1 from=qc to=tec next="..." issue=42 pr=57 at=<ISO8601> sla=30m -->
- `parseHandoff(body)` → the object back, or null. Tolerant of missing fields.
- `TERMINAL` — the set of handoff targets that legitimately end a thread: `done`, `closed`.

In ai-provider.js, `postComment` gains a required `handoff` argument and appends the block
to every body. Calling it without one throws — this is the point: a role that forgets to
say who is next fails loudly in CI rather than quietly stalling an issue.

Update every call site in ai-role-review.js, qc-review.js, po-rebrief.js and
decompose-brief.js to pass a truthful handoff:
- QC approve → to=tec, next="merge"; QC rework → to=tec, next=the first blocking item;
  QC cycle cap → to=po; QC skip → to=tec, next="merge without review".
- PO re-scope → to=tec; PO could-not-re-scope → to=tec ("continue with the brief as it
  stands"); PO plan → to=tec for queued children, to=po for the ones needing a split.
- The advisory roles → to=tec when the answer is ready to implement, to=the human author
  when a genuine open question blocks it (with the assumed answer stated, per 5.2).

The gh CLI comments inside agent-issue-worker.yml must carry the same block. Add a tiny
shell helper in the workflow that appends it, so the YAML has one place to change.

Done when: `grep -L "mia:handoff"` over a real issue's comments returns nothing, and
removing a handoff from any call site fails `node --check` or the unit test from 5.12.
```

</div>

---

### ۵.۶. دفترِ وضعیتِ ایشو: یک کامنت زنده به‌جای اسکرول کردن

> **وضعیت: ✅ انجام شد.** `docs/github/scripts/ledger.js` ساخته شد: `updateLedger({repo,
> issueNumber, token, patch})` کامنتِ نشان‌دارِ `<!-- mia:state -->` را پیدا می‌کند (یا می‌سازد) و
> جدول فارسی «وضعیت | مالک فعلی | قدم بعدی | دور بازکاری | بازتعریف PO | تلاش TEC | شاخه/PR |
> آخرین حرکت» را بازنویسی می‌کند. رکورد به‌صورت JSON داخل `<!-- mia:state-data … -->` ذخیره
> می‌شود تا هر patch بدون بازتجزیهٔ جدول ادغام شود، و `readLedger()` همان را برای چوپان و تریاژ
> برمی‌گرداند (`notBefore` برای عقب‌نشینی نمایی در ۵.۹ همین‌جا می‌نشیند). سه قاعده رعایت شد:
> اگر دو دفتر ساخته شده باشد **قدیمی‌ترین** برنده است (تنها انتخابی که دو نویسندهٔ هم‌زمان
> مستقلاً روی آن به توافق می‌رسند)، هیچ خطایی بیرون نمی‌زند (یک نما ارزش متوقف‌کردن کار را
> ندارد)، و دفتر **امضای تحویل ندارد** — مالکیت را نقشی اعلام می‌کند که تصمیم را گرفته، و یک
> نسخهٔ دوم که بتواند با آن اختلاف پیدا کند از هیچ بدتر است. QC (هر پنج خروجی)، `po-rebrief`
> (بازتعریف و شکست) و تجزیهٔ بریف دفتر را به‌روز می‌کنند؛ TEC در ۵.۴ و ۵.۹ وصل می‌شود.

<div dir="ltr">

```
Give every agent-worked issue a single living status comment, edited in place, so a human
can see the state of the conversation without reading twenty comments.

Read first: docs/github/scripts/ai-provider.js, docs/github/scripts/agent-voice.js,
docs/github/scripts/qc-review.js (loopState() already reconstructs rounds and cycles from
marked comments — reuse that idea, do not duplicate its logic).

Add `ledger.js` (mirrored to assets, registered in BOOTSTRAP_ASSETS) exporting
`updateLedger({ repo, issueNumber, token, patch })`, which finds the comment carrying
`<!-- mia:state -->` (or creates it) and rewrites it as a small Persian table:

  وضعیت | مالک فعلی | قدم بعدی | دور بازکاری | بازتعریف PO | آخرین حرکت | شاخه/PR

Rules:
- One ledger comment per issue, always edited, never duplicated. If two exist (a race),
  keep the oldest and edit that.
- Every role updates it right after it posts its own message: QC on a verdict, PO on a
  re-scope or a plan, TEC on claim, on landing and on giving up.
- The ledger is derived state: rebuilding it from the thread must be possible, so it never
  becomes the only record of anything.
- A ledger update that fails must be logged and swallowed. It is a view, and no view is
  worth stopping the work for.

Done when: an issue that has been through QC twice and one PO re-scope shows all of it in
one comment, and there is exactly one such comment.
```

</div>

---

## گروه ج — پیوستگی (هدف طلایی)

### ۵.۷. آزادسازی خودکار وابستگی‌ها

> **وضعیت: ✅ انجام شد.** `unblock.js` + `unblock-dependents.yml` ساخته و ثبت شدند. محرک‌ها:
> بسته‌شدن هر ایشو، یک cron روزانه، و `workflow_dispatch` — cron حشو نیست، چون ایشو وقتی
> Actions خاموش است یا وسط یک قطعی هم بسته می‌شود. **متنِ بدنه مرجع است، نه برچسب:** خط
> «⛔ blocked by #4, #7» را می‌خواند (و شکل دست‌نویسِ انگلیسی‌اش را هم)، پس ایشویی با دو
> بلاک‌کننده فقط وقتی آزاد می‌شود که **هر دو** بسته شده باشند — برچسب فقط می‌تواند بگوید
> «بلاک است»، نه «به‌خاطر چه». وقتی همه بسته شدند: `blocked` برداشته می‌شود، `by-agent`
> می‌خورد، PO با لحن خودش می‌گوید کدام وابستگی نشست، و امضا به `@tec` می‌رود. اگر بلاک‌کننده‌ای
> باز مانده باشد فقط دفتر وضعیت به‌روز می‌شود («منتظر #7»). بلاک‌کننده‌ای که با «انجام نمی‌شود»
> بسته شده هم آزاد می‌کند، ولی صریحاً گفته می‌شود — بچه با فرضِ وجودِ آن نوشته شده بود. ایشوهای
> `needs-human`/`agent-running`/`needs-po`/`needs-split` دست‌نخورده می‌مانند تا با مالکِ فعلی‌شان
> نجنگیم. همان workflow بعد از آزادسازی سراغ `brief-close.js` (۵.۱۲) می‌رود.

بن‌بست شمارهٔ ۱ — و پرتکرارترین جایی که یک بریفِ چندایشویی نیمه‌کاره می‌ماند.

<div dir="ltr">

```
When an issue closes, automatically unblock and queue every issue that was waiting on it.

Read first: docs/github/scripts/decompose-brief.js (it writes "⛔ blocked by #n" into the
child body and applies the `blocked` label — that line is the machine-readable link),
docs/github/workflows/agent-issue-worker.yml (how `by-agent` starts a run).

Create .github/workflows/unblock-dependents.yml + .github/scripts/unblock.js (mirrored to
assets/docs, registered in BOOTSTRAP_ASSETS):
- Trigger: `issues: [closed]`, plus `workflow_dispatch` and a daily cron as a safety net
  for closes that happened while Actions was disabled.
- Find every OPEN issue whose body contains "blocked by #<closed>" (search the repo's
  issues; do not rely on the label alone).
- For each, recompute its remaining blockers from its own body: if every blocker is now
  closed, remove `blocked`, add `by-agent`, and post a comment in the PO's voice saying
  which dependency landed and that it is now queued — with a handoff to @tec.
- If blockers remain, update nothing but say so in the ledger (5.6).
- A dependency closed as "not planned" counts as unblocked too, but the comment must say
  so, because the child's assumptions may no longer hold.
- Never queue an issue carrying `needs-human` or `agent-running`.

Done when: closing the first child of a decomposed brief makes the second child start
within one queue drain, with no human touching a label.
```

</div>

---

### ۵.۸. آیتم `L` را PO می‌شکند، نه آدم

> **وضعیت: ✅ انجام شد.** `decompose-brief.js` حالا دو حالت دارد و هستهٔ مشترکشان
> `decomposeSource({number, title, body, depth, isBrief})` است — همه‌چیز پارامتری، چون این تابع
> **خودش را** برای بچهٔ بزرگ صدا می‌زند و یک «ایشوی جاری»ِ سراسری، باگی بود که منتظر اولین آیتم
> `L` می‌ماند. آیتم `L` دیگر `continue` نمی‌خورد: برچسب `needs-split` می‌گیرد و **در همان اجرا**
> دوباره تجزیه می‌شود؛ ایشویی که تجزیه شد با «شکستمش به #a، #b، #c» بسته می‌شود تا چک‌لیست
> نقشه ارضاشدنی بماند. حالتِ `SPLIT_ISSUE=<n>` هم اضافه شد (برای چوپان و برای دست آدم) و
> workflow با `workflow_dispatch` و برچسب `needs-split` صدایش می‌زند — برچسبی که خودِ ربات
> می‌زند هیچ اجرایی راه نمی‌اندازد (GitHub برای `GITHUB_TOKEN` رویداد نمی‌فرستد) و لازم هم ندارد،
> چون آن یکی درجا انجام شده. **کفِ بازگشت:** هر بچه `<!-- mia:split depth=n -->` را حمل می‌کند و
> در عمق ۲ به‌جای شکستن، `narrowInPlace()` ایشو را به کوچک‌ترین برشِ **به‌تنهایی مفید** بازنویسی
> می‌کند و بقیه را زیر فهرست صریح «خارج از دامنه» می‌آورد — چیزی حذف نمی‌شود، نوشته می‌شود. هر
> شکستِ split (بی‌کلیدی، سقف نرخ، JSON خراب) به `narrowFallback` می‌رسد: ایشو با بریف فعلی در
> صف TEC می‌ماند. «نیاز به تقسیم دستی» از کل خروجی حذف شد.

<div dir="ltr">

```
Stop leaving "L" items and oversized issues to a human. The PO splits them.

Read first: docs/github/scripts/decompose-brief.js (the `if (child.size === "L") continue`
branch and the plan comment that prints "نیاز به تقسیم دستی"),
docs/github/workflows/decompose-brief.yml.

Changes:
- An L child is labelled `needs-split` (create the label in the workflow like the others)
  and, in the same run, is queued for a second decomposition pass instead of being left.
- Add a `brief`-equivalent entry point: decompose-brief.js already turns one long text into
  small issues. Make it callable for an EXISTING issue (env SPLIT_ISSUE=<n>), where it
  reads that issue's body as the brief, opens children linked to it as their parent, and
  closes the parent as "split into #a #b #c" with a comment in the PO's voice.
- Guard the recursion hard: an issue produced by a split carries `<!-- mia:split depth=n -->`
  in its body; at depth 2 the PO stops splitting, keeps the issue, and instead rewrites it
  in place into the smallest implementable slice with explicit out-of-scope notes — the
  work still moves.
- A split that fails for any reason (no key, quota, unusable JSON) must leave the issue
  queued for TEC with its current brief rather than in `needs-split` limbo.

Done when: a brief that produces an L item ends with that item split and queued, and no
issue anywhere carries "نیاز به تقسیم دستی".
```

</div>

---

### ۵.۹. `agent-failed` دیگر پایان راه نیست

> **وضعیت: ✅ انجام شد.** `triage-failure.js` ساخته شد و از مرحلهٔ «Release the queue slot»
> صدا زده می‌شود. برچسب `agent-failed` می‌ماند — چون **گزارهٔ درستی دربارهٔ آن اجراست** — ولی
> معنایش عوض شد: یک رکورد، نه یک دیوار. جملهٔ «Re-queue it by hand» از YAML حذف شد. تریاژ با
> شواهد (متن ایشو، دنبالهٔ لاگ آخرین پلهٔ نردبان یا خطاهای کامپایلر، و diff هرچه نوشته شده) از
> مدل STRICT JSON می‌خواهد: `cause`، `message` (اول‌شخص فارسی) و `action` از چهار حرکت —
> `retry` (صف دوباره)، `narrow` (dispatch به تجزیهٔ PO با `split_issue`)، `rebrief`
> (`needs-po`)، `pause` (مکثِ صاحب‌دار با پرسش + پاسخ پیش‌فرض + مهلت). سه ترمز: شمارش تلاش‌ها از
> کامنت‌های نشان‌دار `<!-- mia:failed -->` **از آخرین بازتعریف به بعد** (برچسب دست‌کاری می‌شود،
> کامنت نه)، `AGENT_MAX_ATTEMPTS` (پیش‌فرض ۳) که فارغ از نظر مدل بازتعریف را تحمیل می‌کند — و
> شکست **بعد از** بازتعریف را به مکث تبدیل می‌کند — و `notBefore` نمایی (۱۵ دقیقه، ۳۰، ۶۰… تا
> سقف ۸ ساعت) در دفتر وضعیت. `pause` بدون `question` و `assume` رد می‌شود و به بازتعریف تنزل
> می‌کند، چون دقیقاً همان بن‌بستی است که این فایل برای حذفش نوشته شده. **هر** خطای خود تریاژ
> (بی‌کلیدی، سقف، JSON خراب، crash) هم به بازتعریف می‌رسد. یک تغییر لازم در `po-rebrief.js`:
> حالا `<!-- mia:failed -->` را هم به‌عنوان «ایراد تیم» می‌خواند — وگرنه ایشویی که روی **build**
> شکسته (و QC اصلاً PR ندیده) با دستِ خالی به بازتعریف می‌رسید و PO تسلیم می‌شد. شمارش دورهای QC
> دست‌نخورده است. `actions: write` به مجوزهای worker اضافه شد تا dispatch تجزیه ممکن باشد.

بن‌بست شمارهٔ ۳ — امروز صریحاً در YAML نوشته شده «Re-queue it by hand».

<div dir="ltr">

```
Turn `agent-failed` from a terminal state into a triage step with a bounded, automatic
recovery path.

Read first: docs/github/workflows/agent-issue-worker.yml — the "Release the queue slot"
step (it applies agent-failed on any run that did not merge), the build-repair loop and
the model-ladder step above it, and docs/github/scripts/qc-review.js for the pattern of
counting rounds from marked comments.

Create .github/scripts/triage-failure.js (mirrored + registered), called from the worker on
the failure path, and from the shepherd in 5.10:
- Count previous failures for this issue from marked comments (`<!-- mia:failed -->`),
  exactly the way loopState() counts rework rounds — labels can be edited, comments cannot
  be reset by relabelling.
- Give the model the issue, the last run's tail (the workflow already collects the agent
  output and build errors) and the diff if a branch exists, and ask for STRICT JSON:
  { "cause": "build" | "scope" | "brief" | "infrastructure" | "unknown",
    "message": "<first person, Persian, what I think went wrong>",
    "action": "retry" | "narrow" | "rebrief" | "pause" }
- Map the action: `retry` → re-queue `by-agent` (attempt N+1); `narrow` → rewrite the issue
  to the smallest slice that would have passed, then queue; `rebrief` → `needs-po` so
  po-rebrief.js runs on the next claim; `pause` → 5.11's owned pause, never a bare label.
- Attempt ceiling: AGENT_MAX_ATTEMPTS (default 3) failures against the same brief force
  `rebrief` regardless of what the model asked for; a failure after a re-brief forces
  `pause`. Exponential spacing between retries via a `not-before` timestamp in the ledger,
  so a broken issue cannot spin the free quota.
- Every failure of triage itself ends in `rebrief`, because a re-scope is the cheapest
  thing that has ever fixed a stuck issue here.

The worker's "Release the queue slot" step keeps applying `agent-failed` (it is a true
statement about the run) but now also calls triage, so the label is a record, not a wall.
Delete the "Re-queue it by hand" comment in the YAML and describe the new path instead.

Done when: an issue whose run fails is back in the queue (or with the PO) without a human,
and three failures in a row end in an owned pause, never in silence.
```

</div>

---

### ۵.۱۰. چوپان: ناظری که هیچ ایشویی را جا نمی‌گذارد

> **وضعیت: ✅ انجام شد.** اول جدول حالت‌ها به‌عنوان **داده** در `agent-voice.js` اعلام شد
> (`STATES`: مالک، SLA، خروج‌ها، و برای حالت‌هایی که با ساعت سنجیده نمی‌شوند `waitsOn`)، به‌علاوهٔ
> `stateOfLabels()` با ترتیب اولویت — چون یک ایشو می‌تواند هم‌زمان `by-agent` و `needs-po` باشد و
> PO اول حرکت می‌کند. بعد `shepherd.js` + `shepherd.yml` (کرون `7,37 * * * *`، جدا از drain
> خودِ worker تا با صفی که می‌خواهد بازرسی کند مسابقه ندهد). برای هر ایشوی باز: **حالت** از
> برچسب‌ها، **مالک** از آخرین کامنتِ **دارای امضا** (نه آخرین کامنت — رسیدِ توکن یا یک «ممنون»
> نباید سکوت تیم به نظر برسد)، و بی‌حرکتی از دیرترینِ `updated_at` و زمان امضا. اقدام‌ها:
> `agent-running`ِ بدون ران زنده → برچسب پاک و صف دوباره؛ `needs-human`ِ از مهلت گذشته → همان
> پاسخِ پیش‌فرضی که خودِ مکث نوشته بود اعمال می‌شود؛ `blocked` → `unblock-dependents`؛
> `needs-split`/`brief` → dispatch تجزیه؛ بقیه → راه‌اندازی دوبارهٔ صف؛ ایشوی بی‌مالک → PO
> برش می‌دارد؛ و سه سوییپِ پشت سر هم روی یک حالت → تریاژ. `notBefore` تریاژ محترم شمرده می‌شود
> تا عقب‌نشینیِ عمدی «گیر کردن» تعبیر نشود. دو محدودیت عمداً سفت‌اند و در سرِ فایل نوشته شده‌اند:
> **هیچ‌وقت** ایشو باز/بسته نمی‌کند، merge نمی‌کند و بریف را دست نمی‌زند (یک ناظر که خودش هم
> بازیگر باشد، همکارِ دومی است که کسی منطقش را دنبال نمی‌کند)، و حداکثر **یک کامنت برای هر ایشو
> در هر سوییپ** و برای ایشوی سالم **هیچ** — ناظری که هر نیم‌ساعت همه‌جا حرف بزند، همان ناظری است
> که یاد می‌گیرند نخوانندش. `SHEPHERD_DRY_RUN` هم هست تا اول تماشا کنید.

این همان ضمانتِ «گفت‌وگو قطع نمی‌شود» است.

<div dir="ltr">

```
Add the shepherd: a scheduled workflow whose only job is that no open issue is ever stuck
without someone acting on it.

Read first: docs/github/scripts/agent-voice.js (parseHandoff, TERMINAL),
docs/github/scripts/ledger.js, docs/github/workflows/agent-issue-worker.yml (labels and
their meaning), docs/github/scripts/triage-failure.js.

Create .github/workflows/shepherd.yml + .github/scripts/shepherd.js (mirrored + registered):
- Trigger: cron every 30 minutes (aligned with the worker's own drain), plus
  workflow_dispatch.
- For every OPEN issue in the repo, determine its state and owner from, in order: the
  latest handoff footer, then its labels, then its history. Then check it against that
  state's SLA:
    by-agent        → 60m   (the queue should have drained)
    agent-running   → 90m   (a run this long is dead; the runner limit is lower)
    needs-rework    → 60m
    needs-po        → 60m
    needs-split     → 60m
    blocked         → checked against its blockers, not a clock
    needs-human     → 24h   (a reminder, not a re-queue)
    no owner at all → immediate
- On a violation, act — never merely report:
    agent-running past its SLA and no live run → clear the label, re-queue as by-agent,
      and comment as the engineer seat saying the run died and it is retrying.
    by-agent past its SLA → dispatch the worker workflow.
    blocked whose blockers are all closed → hand to unblock.js (5.7).
    needs-po / needs-split past SLA → dispatch that role's workflow.
    no owner → assign one from the issue's labels and say so.
    repeated violation of the same state (3 sweeps) → triage-failure.js.
- The shepherd never opens issues, never merges, never closes anything. It only restores
  ownership and pokes the owner. Say that in the file header — this is the guard that keeps
  a watchdog from becoming a second, unpredictable actor.
- One summary comment per issue per sweep at most, and none at all when the issue is
  healthy: a shepherd that chats on every issue every 30 minutes is noise that people
  learn to ignore, which defeats it.

Done when: killing a run mid-flight (cancel it in the Actions tab) results in the issue
being picked up again automatically within one sweep, with a comment explaining why.
```

</div>

---

### ۵.۱۱. مکث‌های صاحب‌دار: `needs-human` هم خروجی دارد

> **وضعیت: ✅ انجام شد.** هر `needs-human` حالا چهار چیز را حمل می‌کند: تصمیمی که فقط آدم
> می‌تواند بگیرد (به‌شکل پرسش با دو-سه گزینهٔ مشخص)، کاری که تیم در نبودِ جواب خودش می‌کند،
> مهلت، و این جمله که «یک کامنت ساده کافی است — لازم نیست برچسبی را دست بزنید». سقف
> `AGENT_MAX_CYCLES` در `qc-review.js` بازنویسی شد (متن قبلی می‌گفت «بریف را دستی تیز کنید و
> `@tec` بزنید» — یعنی از آدم می‌خواست پروتکل را بلد باشد و تا آن موقع کار خوابیده بود) و
> `triage-failure.js` هم مکثش را با همین قرارداد می‌نویسد — و `pause`ِ بدون پرسش/پیش‌فرض را
> اصلاً قبول نمی‌کند. **بازگشت خودکار:** job تازهٔ `resume` در `ai-role-review.yml` روی هر کامنتِ
> غیرربات روی ایشوی `needs-human` اجرا می‌شود، برچسب را برمی‌دارد، `by-agent` می‌زند و با امضا
> به `@tec` تحویل می‌دهد؛ هیچ نقشی هم لازم نیست tag شود. `AGENT_PAUSE_TIMEOUT` (پیش‌فرض ۷۲
> ساعت) به هر دو workflow اضافه شد و چوپان (۵.۱۰) در سررسید همان پاسخِ پیش‌فرض را اعمال می‌کند.
> مستندات `AGENT_MAX_CYCLES` هم عوض شد: «تسلیم شو» نیست، «قبل از ادامه از یک آدم بپرس» است.

<div dir="ltr">

```
Make every pause an owned pause. Nothing in this system is allowed to be a dead end,
including the states that legitimately need a person.

Read first: docs/github/scripts/qc-review.js (the AGENT_MAX_CYCLES ceiling and the
needs-human comment), docs/github/scripts/shepherd.js, docs/github/scripts/agent-voice.js.

Changes:
- Every `needs-human` comment must contain: the one decision only a person can make,
  written as a question with the two or three concrete options; what the agents will do if
  nobody answers; and by when. Not "sharpen the issue by hand".
- Add AGENT_PAUSE_DEFAULT (default: the assumed answer stated in the comment). After
  AGENT_PAUSE_TIMEOUT (default 72h) with no human reply, the shepherd applies that default
  itself, says so in the PO's voice, and re-queues. A team that blocks forever on a silent
  stakeholder has not finished the work; it has stopped.
- A human reply on a `needs-human` issue removes the label and re-queues automatically —
  today someone has to remember to also comment `@tec`. Detect a non-bot comment on such an
  issue in ai-role-review.yml (or a small new trigger) and treat it as the answer.
- Keep AGENT_MAX_CYCLES as it is (0 = no ceiling), and update its documentation to say what
  it now means: not "give up", but "ask a person before continuing".

Done when: no state in the system, including needs-human, can be entered without an
automatic way out, and 5.13's lint proves it.
```

</div>

---

### ۵.۱۲. بستنِ بریف: جایی که کار واقعاً «تمام» می‌شود

> **وضعیت: ✅ انجام شد.** `brief-close.js` ساخته شد و از دو جا صدا زده می‌شود: بسته‌شدن هر ایشو
> (در `unblock-dependents.yml`) و سوییپ چوپان با `SWEEP_ALL`. والدِ هر ایشو از پانویسِ
> «از نیت #n» پیدا می‌شود و خطِ همان بچه در چک‌لیستِ نقشه تیک می‌خورد — کاری ظاهری که ارزشش را
> دارد، چون چک‌لیستی که هیچ‌وقت تکان نمی‌خورد به خواننده یاد می‌دهد بریف نگه‌داری نمی‌شود و بعد
> از آن هیچ بخشی‌اش را نمی‌خواند، از جمله بخشی که می‌گوید چه چیزی جا مانده. وقتی همهٔ بچه‌ها بسته
> شدند، PO **بریفِ اصلی** را کنار عنوان‌ها و معیارهای پذیرشِ تحویل‌شده می‌گذارد و STRICT JSON
> برمی‌گرداند: `covered`، `message` (سه-چهار جمله برای کسی که یک ایشو هم باز نکرده — بدون شمارهٔ
> ایشو و اسم فایل) و `gaps`. اگر پوشش کامل باشد، جمع‌بندی فارسی منتشر و نیت **بسته** می‌شود —
> این همان هدف طلایی. اگر جا مانده باشد، برای **هر** خلأ یک ایشوی `by-agent` باز می‌شود و نیت
> **باز می‌ماند** با این جملهٔ صریح که تمام نشده. بدترین شکستِ ممکن این سیستم، بسته‌شدنِ نیتی است
> که چیزی از آن ساخته نشده — پس هر مسیرِ نامطمئن (بی‌کلیدی، سقف، JSON خراب، `covered`ِ متناقض با
> `gaps`) نیت را **باز** نگه می‌دارد و از یک آدم می‌پرسد؛ بستن تنها کاری است که «بله»ی روشن
> می‌خواهد. یک نشانِ `<!-- mia:brief-audited -->` هم جلوی بازبینی دوباره را می‌گیرد.

هدف طلایی وقتی محقق است که **بریف** بسته شود، نه فقط ایشوها.

<div dir="ltr">

```
Close the loop at the top: when every child of a brief is done, the PO checks the result
against the ORIGINAL brief and either closes it or opens what is missing.

Read first: docs/github/scripts/decompose-brief.js (the plan comment with the child
checklist and the `brief-planned` receipt), docs/github/scripts/unblock.js,
docs/github/scripts/agent-voice.js.

Create .github/scripts/brief-close.js + a trigger in unblock-dependents.yml (an issue
closing is exactly the moment to check) and in shepherd.js (the daily safety net):
- Find the parent brief of the closed issue from the "از نیت #<n>" footer decompose-brief.js
  already writes. Tick that child's line in the plan checklist comment.
- When every child is closed, ask the PO's model, with the original brief text and the
  titles + acceptance criteria of every child, for STRICT JSON:
  { "covered": true|false,
    "message": "<first person, Persian: what we set out to do and what we actually
                shipped, in three or four sentences a stakeholder can read>",
    "gaps": ["<one line per thing the brief asked for that no child delivered>"] }
- covered && no gaps → post the message as the closing comment and CLOSE the brief.
- gaps → open one new child issue per gap, queue the unblocked ones, keep the brief open,
  and say plainly that the brief is not done and why. The brief closing while something it
  asked for was never built is the single worst failure this whole system can have; the
  prompt must say so.
- Any model failure → leave the brief open with a comment asking a person to confirm, with
  the checklist fully ticked so the confirmation is one glance. Never auto-close on a
  failure path.

Done when: a brief whose children all merge ends with a Persian summary a stakeholder can
read and a closed issue — or with the missing pieces already queued.
```

</div>

---

### ۵.۱۳. مشورت هم به کار ختم می‌شود

> **وضعیت: ✅ انجام شد.** جوابی که درست و کامل باشد و کسی رویش کار نکند، از نبودِ جواب
> تشخیص‌ناپذیر است — فقط گران‌تر است. حالا وقتی PO بلوکِ «بریف آمادهٔ اجرا» را نوشته باشد،
> `readyBrief()` آن را از متن بیرون می‌کشد (و بلوکِ کوتاه‌تر از ۱۲۰ نویسه را رد می‌کند: آن یک
> سرفصل و یک قول است، نه بریف)، بدنهٔ ایشو با آن جایگزین می‌شود، برچسب `by-agent` می‌خورد و متنِ
> قبلی در یک `details` می‌ماند — همان‌طور که `po-rebrief.js` می‌کند، چون بریفی که ربات بی‌صدا
> رویش می‌نویسد قابل حسابرسی نیست. حکمِ QC هم خوانده می‌شود: `آماده اجرا` → صف، و
> `نیاز به جزئیات بیشتر` → `needs-po` (قبلاً این جمله پایانِ رشته بود). `AGENT_AUTO_QUEUE=false`
> رفتار قدیمی را برمی‌گرداند و آن‌وقت امضا به **خودِ آدم** می‌رود، پس چوپان همان‌قدر پیگیرش است
> که یک صندلی. ایشوهای `needs-human`/`blocked`/`agent-running`/`needs-split` هرگز خودکار در صف
> نمی‌روند. کل این بخش best-effort است: شکستِ برچسب یا ویرایش، خودِ مشاوره را — که هزینه‌اش
> پرداخت شده — از بین نمی‌برد.

<div dir="ltr">

```
Make @po and @qc advice actionable instead of advisory-only.

Read first: docs/github/scripts/ai-role-review.js, docs/github/workflows/ai-role-review.yml.

Changes:
- When the PO's answer contains its "بریف آمادهٔ اجرا" block and the issue is not already
  queued, offer to apply it: post the reply, then (when AGENT_AUTO_QUEUE is true, the
  default) replace the issue body with that brief, label `by-agent`, and say what was done
  and how to undo it — the previous body preserved in a <details> block, exactly the way
  po-rebrief.js already does it.
- When AGENT_AUTO_QUEUE is false, the reply ends with a one-line instruction and the
  handoff targets the human author, so the shepherd tracks it as owned.
- When QC's verdict line is `آماده اجرا` and the issue carries no blockers, queue it too.
  When it is `نیاز به جزئیات بیشتر`, hand the issue to @po rather than to nobody.
- Never auto-queue an issue carrying needs-human, blocked, or agent-running.

Done when: commenting "@po" on a rough issue ends with that issue queued and implementable,
not with advice nobody acts on.
```

</div>

---

## گروه د — تضمین و سند

### ۵.۱۴. لینتِ بن‌بست: ثابت کنید هیچ حالتی بدون خروج نیست

> **وضعیت: ✅ انجام شد** (با یک انحراف عمدی از متن پرامپت، پایین‌تر). `scripts/check-loop-closure.sh`
> ساخته شد و ۳۱ تست `node:test` را اجرا می‌کند — بدون هیچ وابستگی تازه. تست‌ها: هر حالت مالک و
> **خروج** دارد و هر خروج به حالتی واقعی اشاره می‌کند؛ حالتی که با ساعت سنجیده نمی‌شود `waitsOn`
> دارد؛ هر برچسبی که workflowها می‌سازند در `STATES` هست **و برعکس**؛ `postComment` بدون امضا
> `throw` می‌کند؛ `handoff()`/`parseHandoff()` رفت‌وبرگشت کامل دارند، حتی برای متنی که کامنت
> HTML را می‌شکند؛ منشور واقعاً جلوی پرامپتِ نقش می‌نشیند؛ **هر** مسیرِ تسلیم (`skip`، `giveUp`،
> `fail`، `narrowFallback`، `narrowInPlace`، چهار حرکت تریاژ، `auditBrief`، سوییپ unblock) یک
> `to:` دارد؛ هیچ اسکریپتی `postComment` بی‌امضا صدا نمی‌زند؛ دفتر وضعیت عمداً امضا **ندارد**؛
> تریاژ نمی‌تواند در مکثِ بی‌پرسش تمام شود؛ `brief-close` فقط در **یک** جا و فقط بعد از «بله»ی
> روشن می‌بندد؛ و چوپان نمی‌تواند ایشو باز/بسته کند یا merge کند. صحت خودِ لینت آزموده شد: با
> خالی کردن `exits` حالت `needs-po`، دقیقاً همان حالت را نام می‌برد و قرمز می‌شود. به
> `.github/workflows/assets-sync.yml` (CI خودِ MIA) وصل شد، و برای مخزن‌های تولیدشده یک گام
> سبک در `docs/github/workflows/ci.yml` که جدول حالت‌های همان مخزن را بررسی می‌کند.
>
> **انحراف:** تست‌ها در `scripts/tests/` نشستند، نه `docs/github/scripts/__tests__`. دلیلش قاعدهٔ
> خانه است: `check-assets-sync.sh` هر فایلِ بی‌جفت در `docs/github/` را خطا می‌گیرد، و تست‌ها
> جزو چیزی نیستند که به مخزن‌های تولیدشده آپلود می‌شود. آوردنشان به آن‌جا یا همگام‌سازی را
> می‌شکست یا هر پروژهٔ تولیدشده را صاحب سه فایل تست می‌کرد که هیچ‌وقت اجرا نمی‌شوند.

<div dir="ltr">

```
Prove the handoff invariant mechanically, so a future change cannot reintroduce a dead end.

Read first: scripts/check-assets-sync.sh (the style: a script that fails loudly with a fix
suggestion), and every script under docs/github/scripts/.

Create scripts/check-loop-closure.sh + docs/github/scripts/__tests__ (plain node:test, no
new dependencies) that assert:
1. Every state in the state table has at least one automatic outgoing transition. Declare
   the table once, in agent-voice.js, as data — STATES with { owner, sla, exits } — and
   have the shepherd, the lint and the docs all read that one declaration.
2. Every label the workflows create appears in STATES. A label with no state is a state
   nobody watches.
3. `postComment` cannot be called without a handoff (a unit test asserting it throws).
4. parseHandoff round-trips everything handoff() renders.
5. Each fail-open path returns control: unit tests for qc-review's skip(), po-rebrief's
   giveUp(), triage's fallback and brief-close's failure path, asserting each one leaves an
   owner behind.

Wire the script into .github/workflows/ci.yml next to the assets-sync check, and into
docs/github/workflows/ci.yml for generated repos.

Done when: `bash scripts/check-loop-closure.sh` passes, and deleting one `exits` entry
makes it fail with a message naming the orphaned state.
```

</div>

---

### ۵.۱۵. سند فارسی: تیم را همان‌طور که هست توضیح بدهید

> **وضعیت: ✅ انجام شد.** در `docs/github/README.md`: جدول فایل‌ها با هفت اسکریپت و دو workflow
> تازه به‌روز شد (و «سیزده فایل» شد «بیست‌ودو فایل» — از روی خودِ `BOOTSTRAP_ASSETS`)؛ جدول
> نقش‌ها پنج سطر گرفت (شکستنِ کارِ بزرگ، تریاژ شکست، بازبینی نهایی، چوپان، آزادسازی)؛ **§۴.۸
> منشور تیم و لحن** با جدول چهار صندلی، شش حکمِ منشور، فهرست ممنوعه‌ها و یک نمونهٔ واقعیِ
> **قبل/بعد** از همان کامنت QC؛ **§۴.۹ قرارداد تحویل و حالت‌ها** با متن امضا، جدول کاملِ ۱۴
> حالت (مالک/مهلت/خروج‌ها) و یک نمودار از کلِ حلقه با یال‌های تازه؛ و زیربخش **«چرا هیچ بن‌بستی
> نداریم»** که همان هفت بن‌بستِ ابتدای این سند را کنار خروجی امروزشان می‌گذارد. §۴ دیگر
> نمی‌گوید نقش‌ها «فقط مشاوره» می‌دهند، §۴.۶ مکثِ صاحب‌دار را توضیح می‌دهد، و جدول پیکربندی چهار
> متغیر تازه گرفت (`AGENT_MAX_ATTEMPTS`، `AGENT_PAUSE_TIMEOUT`، `AGENT_AUTO_QUEUE`،
> `SHEPHERD_DRY_RUN`). `docs/token-usage.md` سه خرج‌کنندهٔ تازه را فهرست کرد و این را صریح
> نوشت که **چوپان هیچ توکنی خرج نمی‌کند** — چیزی که هر نیم‌ساعت اجرا می‌شود نباید هزینهٔ مدل
> داشته باشد. `docs/README.md` و `docs/roadmap.md` (§۶) هم آن‌جاهایی که نقش‌ها را «مشاور» و
> `needs-human` را پایان راه توصیف می‌کردند اصلاح شدند.

<div dir="ltr">

```
Update the Persian documentation to describe the team as it now behaves.

Read first: docs/github/README.md (sections ۴, ۶ and ۹), docs/README.md, docs/roadmap.md
(section ۶ — the PO/TEC/QC production line), docs/token-usage.md.

Add to docs/github/README.md:
- A new section "منشور تیم و لحن" summarising agent-voice.js: the four seats, what each
  owns, and the tone rules — with one real before/after example of a QC comment.
- A new section "قرارداد تحویل و حالت‌ها" with the state table rendered from the STATES
  declaration (states, owner, SLA, exits) and one diagram of the full loop including the
  new edges: unblock, split, triage, shepherd, brief-close.
- A short "چرا هیچ بن‌بستی نداریم" subsection listing the seven dead ends that existed
  before this wave and where each one now exits.

Update docs/README.md and docs/roadmap.md where they describe the roles as advisory-only or
describe agent-failed / needs-human as terminal. Add the new scripts and workflows to the
file tables in docs/github/README.md §۱ and to the token accounting in docs/token-usage.md
(the shepherd, triage and brief-close all spend tokens and must report it the same way).

Keep the RTL/LTR conventions and the existing voice of these documents.

Done when: someone reading docs/github/README.md alone can predict exactly what happens to
an issue whose run dies at 3am.
```

</div>

---

## ترتیب اجرا و آزمون پایانی

**ترتیب اجباری** (هر کدام روی قبلی می‌ایستد):

<div dir="ltr">

```
5.1  →  5.5  →  5.6            the charter, the handoff, the ledger  (the foundation)
5.2  →  5.3  →  5.4            the human voice of all four seats
5.7  →  5.8  →  5.9  →  5.11   the four dead ends inside the loop
5.10 →  5.12 →  5.13           the shepherd, the brief closure, actionable advice
5.14 →  5.15                   the proof and the documentation
```

</div>

۵.۱، ۵.۵ و ۵.۶ را حتماً اول اجرا کنید: بقیهٔ پرامپت‌ها به `agent-voice.js`، `handoff()` و
دفترِ وضعیت ارجاع می‌دهند.

### آزمون پایانی — روی یک مخزن آزمایشی

> **وضعیت: ⏳ باقی مانده.** این پنج سناریو به یک مخزن گیت‌هابِ واقعی نیاز دارند که ایجنت‌هایش
> اجرا شوند؛ از این‌جا قابل انجام نیست. آنچه محلی بررسی شد: نحو و بارگذاری هر اسکریپت، parse
> شدن هر workflow، همگام‌سازی assets ↔ docs، کامپایل اپ، و ۳۱ تستِ بستنِ حلقه — که همان
> **خاصیت** پشت این سناریوها را ثابت می‌کنند (هر حالت مالک و خروج دارد، هیچ نقشی بدون امضا حرف
> نمی‌زند، هیچ مسیرِ تسلیمی بی‌مالک تمام نمی‌شود)، ولی جای دیدنِ حلقه در حال چرخیدن را نمی‌گیرند.
>
> پیشنهاد برای اولین اجرا: `SHEPHERD_DRY_RUN=true` را روی مخزن آزمایشی بگذارید تا یک شبانه‌روز
> فقط تصمیم‌های چوپان را در لاگ ببینید، بعد خاموشش کنید.

سیستم وقتی «تمام» است که این پنج سناریو **بدون دخالت انسان** به کار تمام‌شده برسند:

1. **بریف چندایشویی.** یک بریف با سه ایشو که ۲ به ۱ و ۳ به ۲ وابسته است. انتظار: هر سه
   یکی‌یکی merge شوند، چک‌لیست بریف تیک بخورد، بریف با خلاصهٔ فارسی بسته شود.
2. **ردِ دوبارهٔ QC.** ایشویی که معیار پذیرشش مبهم است. انتظار: دو دور بازکاری، بازتعریف PO،
   و بعد merge — نه `needs-human`.
3. **رانِ مرده.** ران را وسط کار cancel کنید. انتظار: چوپان در سوییپ بعدی برچسب
   `agent-running` را پاک کند، ایشو دوباره در صف برود، و کامنتی به زبان مهندس دلیلش را بگوید.
4. **آیتم L.** بریفی که یک آیتم بزرگ می‌سازد. انتظار: PO خودش بشکندش، بچه‌ها در صف بروند،
   هیچ‌جا «نیاز به تقسیم دستی» نباشد.
5. **سقفِ خطا.** یک ایشو با معیار غیرممکن. انتظار: سه تلاش، یک بازتعریف، و بعد یک مکثِ
   صاحب‌دار با یک پرسشِ مشخص و یک پاسخ پیش‌فرض و مهلت — نه سکوت.

و یک آزمون کیفی: تایم‌لاین یکی از ایشوها را به یک همکار انسانی نشان بدهید. اگر نپرسید
«اینها را آدم نوشته؟»، لحن هنوز کار دارد — به ۵.۱ و ۵.۲ برگردید.

---

<div align="center">

[پرامپت‌های موج ۱ تا ۴](claude-code-prompts.md) · [نقشهٔ راه](roadmap.md) ·
[مستندات سیستم](README.md) · [تیم ایجنت](github/README.md)

</div>

</div>
