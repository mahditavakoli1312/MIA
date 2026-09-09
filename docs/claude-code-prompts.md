<div dir="rtl">
set true for llow GitHub Actions to create and approve pull requests
# پرامپت‌های آمادهٔ Claude Code

> هر آیتم [نقشهٔ راه](roadmap.md) اینجا یک **پرامپت کپی‌کردنی** دارد. متن داخل بلوک را عیناً در
> Claude Code (در ریشهٔ همین مخزن) پیست کنید. پرامپت‌ها انگلیسی‌اند چون دستورِ کار به مدل‌اند،
> نه سند فارسی پروژه.

**سه قاعده برای همهٔ پرامپت‌ها:**

1. **یکی‌یکی.** هر پرامپت یک تغییر مستقل است. دو تا را با هم پیست نکنید.
2. **اول نقشه.** اگر تغییر بزرگ است، اول `plan mode` (کلید <kbd>Shift</kbd>+<kbd>Tab</kbd>) و بعد اجرا.
3. **قانون همیشگی** — این بلوک را به هر پرامپتی که فایل‌های ایجنت را لمس می‌کند بچسبانید:

<div dir="ltr">

```
House rules for this repo:
- app/src/main/assets/ and docs/github/ hold the SAME files and must stay byte-for-byte
  identical. Edit one, then copy it to the other, and verify with `diff`.
- Docs in docs/ are Persian and RTL (<div dir="rtl">), with LTR code blocks. Match that style.
- Comments explain WHY, not what. Match the density and voice of the surrounding code.
- Never commit or push unless I ask.
```

</div>

---

## فهرست

- [موج ۱ — بستن حلقه](#موج-۱--بستن-حلقه)
- [موج ۲ — کیفیت ایجنت](#موج-۲--کیفیت-ایجنت)
- [موج ۳ — خط تولید PO/QC](#موج-۳--خط-تولید-poqc)
- [موج ۴ — دیده‌شدن و UI/UX](#موج-۴--دیدهشدن-و-uiux)
- [قالب ساخت پرامپت جدید](#قالب-ساخت-پرامپت-جدید)

---

## موج ۱ — بستن حلقه

### ۱.۱. کنش‌های تازهٔ نیت (تسک را می‌شود بست)

> **وضعیت: ✅ انجام شد.** `ActionType` سه مقدار تازه دارد (`complete_task`، `reopen_task`،
> `set_due_date`)، `IntentPromptCore` هر سه را با قاعده و مثال فارسی پوشش می‌دهد،
> `SupabaseApi.updateTaskById` ستون‌های `is_done`/`due_date` را PATCH می‌کند، و
> `GitHubRepository.setIssueStateForTask` ایشوی متناظر را (با تطبیق عنوان روی `PersianText.fold`)
> می‌بندد یا باز می‌کند — best-effort، طوری که خطای گیت‌هاب هرگز نوشتن Supabase را شکست ندهد.
> تست‌ها: `IntentExecutionRepositoryTest` و `VoiceCommandIntentParsingTest`.

امروز `ActionType` فقط چهار مقدار دارد و ستون `is_done` هیچ‌وقت `true` نمی‌شود.

<div dir="ltr">

```
Add three new intent actions to MIA end to end: complete_task, reopen_task and set_due_date.

Read first: data/model/VoiceCommandIntent.kt, network/prompt/IntentPromptCore.kt,
data/repository/IntentExecutionRepository.kt, network/supabase/SupabaseApi.kt,
docs/supabase/schema.sql.

Requirements:
- Add the three values to ActionType with their snake_case @SerialName.
- Extend the shared JSON schema and rules in IntentPromptCore so both front doors (Gemini
  audio, OpenRouter text) can emit them. Keep the schema identical for both — that is the
  whole point of the file. Add one worked Persian example per new action.
- complete_task / reopen_task PATCH tasks.is_done by project + title, matching the existing
  name-normalisation used by remove_task (do not add id lookups the model can't produce).
- set_due_date PATCHes tasks.due_date.
- When GitHub is configured, complete_task also closes the matching issue, and reopen_task
  reopens it — best effort, exactly like the existing GitHub side effects: a GitHub failure
  must never fail the Supabase write.
- Persian confirmation messages in the same voice as the existing ones.
- Add unit tests next to the existing ones in data/repository/ for parsing each new action
  and for the "task not found" path.

Do not touch the UI in this change.
```

</div>

**تمام‌شده وقتی:** «تسک طراحی لوگو رو ببند» تسک را در Supabase می‌بندد و ایشوی گیت‌هاب را close می‌کند.

---

### ۱.۲. تأیید پیش از اجرا (جلوگیری از حذف فاجعه‌بار)

> **وضعیت: ✅ انجام شد.** `IntentConfirmationSheet` یک ModalBottomSheet است که بین دسته‌بندی و
> `IntentExecutionRepository` می‌ایستد: هر نیت یک ردیف با آیکن کنش، نام پروژه، و فیلدهای
> قابل‌ویرایش عنوان و مهلت. ردیف‌های `delete_project`/`remove_task` با رنگ error و چک‌باکس
> جداگانه می‌آیند و دکمهٔ «انجام بده» تا تیک‌خوردن همهٔ آن‌ها غیرفعال است. سوییچ
> «تأیید قبل از اجرا» در `SettingsDialog` (پیش‌فرض روشن) فقط دستورهای غیرمخرب را رد می‌کند.
> حالت در `MainUiState.IntentConfirmation` داخل ViewModel نگه داشته می‌شود، پس چرخش صفحه
> ویرایش‌های نیمه‌تمام را از بین نمی‌برد. تست: `IntentConfirmationTest`.

<div dir="ltr">

```
Add an "intent confirmation" step between understanding a command and executing it.

Read first: ui/main/MainViewModel.kt, ui/main/MainUiState.kt, ui/main/MainScreen.kt,
data/repository/IntentExecutionRepository.kt.

Behaviour:
- After classification returns intents and BEFORE IntentExecutionRepository runs, show a
  Material 3 ModalBottomSheet listing every intent as one row: action icon, project name,
  task title, due date.
- Each row is editable in place (title and due date at minimum).
- Rows for delete_project / remove_task use the error colour and carry a separate explicit
  confirmation — a checkbox or a swipe, never just the main button.
- Two actions: "انجام بده" executes the (possibly edited) list, "اصلاح می‌کنم" dismisses and
  puts the original text back in the command field.
- Add a switch in SettingsDialog: "تأیید قبل از اجرا" (default on). When it is off, only
  destructive intents are confirmed — never skip those.
- The sheet survives rotation (state in the ViewModel, not the composable).

Follow the existing state pattern exactly: StateFlow<UiState> for state, Channel for one-shot
messages. RTL is already set at the root, don't re-apply it.
```

</div>

---

### ۱.۳. همگام‌سازی وضعیت از گیت‌هاب به Supabase

> **وضعیت: ✅ انجام شد.** `IssueTaskSync` روی همان یک خواندنِ `state=all` که شمارنده‌های کارت
> را می‌سازد سوار می‌شود — بدون هیچ درخواست شبکهٔ اضافه. تطبیق ایشو↔تسک با
> `PersianText.fold` روی عنوان انجام می‌شود، همهٔ تسک‌های بسته‌شدنی در یک PATCH با فیلتر
> `in.(…)` می‌روند، و قاعدهٔ سخت این است که **فقط می‌بندد**: ایشوی باز هرگز تسکی را که کاربر
> در اپ بسته دوباره باز نمی‌کند. خطا کاملاً بی‌صدا است (نتیجه دور ریخته می‌شود) و لیست پروژه‌ها
> در حافظه به‌روز می‌شود تا `refreshProjects` دوباره صدا نخورد و حلقه نسازد.
> تست: `IssueTaskSyncTest`.

<div dir="ltr">

```
Close the loop: when an issue is closed on GitHub, the matching Supabase task should show as
done in the app.

Read first: data/repository/GitHubRepository.kt (issuesFor), ui/main/MainViewModel.kt
(the per-project issue counts), data/repository/ProjectRepository.kt.

Requirements:
- The app already reads every issue of a project once, with state=all, to build the card
  counts. Reuse that single read — do not add a second network call.
- Match issue → task by normalised title (text/PersianText.kt already has the normaliser).
- When an issue is closed and its task is not done, PATCH the task to is_done = true. Never
  the reverse: an open issue must not reopen a task a human closed in the app.
- Do it in one batched pass per project, and only for projects whose repo actually resolved.
- A GitHub or Supabase failure here is silent (log only) — this is a convenience sync, it
  must never break the project list.
- Unit-test the matching and the "only closes, never reopens" rule.
```

</div>

---

### ۱.۴. اعلان وقتی ایجنت کارش تمام شد

> **وضعیت: ✅ انجام شد.** `AgentCompletionWorker` (WorkManager، دوره‌ای ۱۵ دقیقه، مقیّد به شبکه)
> برای هر پروژه ایشوهای به‌روزشده از آخرین بازدید را با `GitHubRepository.issuesUpdatedSince`
> می‌خواند و آن‌هایی را که برچسب `done` گرفته‌اند یا با PR مرج‌شده بسته شده‌اند «تمام‌شده»
> می‌شمارد — با این قید که `agent-failed` هرگز به‌عنوان کار انجام‌شده اعلام نمی‌شود.
> برای هر مورد یک نوتیفیکیشن با عنوان ایشو و متن «TEC این کار را انجام داد» ارسال می‌شود که
> با لمس، اپ را روی صفحهٔ همان ایشو باز می‌کند (`IssueDeepLink` → `MiaApp`، اکتیویتی
> `singleTop`). واترمارک و شماره‌های اعلام‌شده per-repo در `AgentWatchStore`
> (SharedPreferences) نگه داشته می‌شوند؛ اولین برخورد با یک مخزن فقط واترمارک را ثبت می‌کند
> تا تاریخچه یک‌جا اعلام نشود. مجوز `POST_NOTIFICATIONS` دقیقاً وقتی گرفته می‌شود که کاربر
> اولین کار را به ایجنت می‌سپارد، و ردّ آن بی‌صدا تحمل می‌شود. بدون توکن گیت‌هاب هیچ‌چیز
> زمان‌بندی نمی‌شود. وابستگی تازه: `androidx.work:work-runtime-ktx`.
> تست: `AgentCompletionWorkerTest`.

<div dir="ltr">

```
Notify the user on their phone when the TEC agent finishes an issue.

Read first: MIAApplication.kt, data/repository/GitHubRepository.kt, ui/issues/.

Requirements:
- A WorkManager periodic worker (15 min, network-constrained) that, for every project with a
  repo, lists issues updated since the last check and finds ones that went to the `done`
  label or were closed by a merged PR.
- Post one notification per newly finished issue: title = the issue title, body = "TEC این
  کار را انجام داد", tap opens the app on that issue's detail screen.
- Persist the last-checked timestamp per repo (SharedPreferences is fine — SecretStore shows
  the storage style used here).
- Ask for POST_NOTIFICATIONS at the right moment on Android 13+, and degrade silently when
  it is refused.
- Nothing runs when no GitHub token is configured.
```

</div>

---

## موج ۲ — کیفیت ایجنت

### ۲.۱. حلقهٔ ترمیم build (بیشترین بازده کل سیستم)

> **وضعیت: ✅ انجام شد.** یک تک‌فراخوانی OpenCode به اسکریپت `run-agent.sh` بیرون کشیده شد
> (گام «Prepare the agent runner») تا هم پاس اول و هم هر تلاش ترمیم از آن استفاده کنند: کلید
> کدام provider، فلگ‌های همین بیلد از CLI، و فِیل‌اوور به کلید یدکی همه یک‌جا می‌مانند و خروجی
> همیشه به همان یک لاگ **append** می‌شود تا توکنِ هر تلاش به گزارش خرج برسد، نه فقط آخری.
> گام build تا `MAX_ATTEMPTS=3` بیلد می‌زند؛ در هر شکست فقط خطاهای کامپایلر
> (`^e: `، `error:`، `FAILED`، `Unresolved reference`، `* What went wrong`) تا ۶۰۰۰ کاراکتر
> استخراج و با پرامپت «فقط همین خطاها را درست کن» برگردانده می‌شوند. تسلیم‌شدن پس از تلاش سوم:
> کامنت با ۳۰ خط اول خطا و تعداد تلاش‌ها، و `exit 1` (برچسب `agent-failed` را همان گام
> `always()` «Release the queue slot» می‌زند، پس دوبار برچسب نمی‌خورد). موفقیت با N>1 در بدنهٔ
> PR اعلام می‌شود («Green after N build attempts»). قرارداد وضعیت (`ok/nokey/quota/error`) دست
> نخورد — فقط `nokey` از کد خروجی ۳ اسکریپت می‌آید — و برخوردن به سهمیه در میانهٔ ترمیم مثل
> قبل سبز خارج می‌شود با همان کامنت. YAML و همهٔ بلوک‌های `run` (به‌علاوهٔ خودِ `run-agent.sh`)
> نحو-چک شده‌اند و کپی `docs/github/workflows/` بایت‌به‌بایت یکسان است.

<div dir="ltr">

```
Give the TEC agent a repair loop, so a failed build is fixed instead of abandoned.

File: app/src/main/assets/agent-issue-worker.yml (then copy to docs/github/workflows/).

Today the "Verify build (Gradle)" step runs ./gradlew assembleDebug once and, on failure,
comments and fails the job. Replace it with up to 3 attempts:
- Attempt 1 is the build as it is today.
- On failure, extract ONLY the compiler errors from the log (lines matching ^e:, "error:",
  "FAILED", "Unresolved reference"), cap them at ~6000 characters, and run OpenCode again
  with a repair prompt: "Your previous change does not compile. Fix ONLY these errors. Do not
  add features, do not touch files unrelated to the errors." then rebuild.
- Give up after the 3rd failure: comment on the issue with the first ~30 lines of the error,
  say how many attempts were made, label the issue agent-failed, and fail the job.
- On success after N>1 attempts, say so in the PR body ("green after N attempts") so the cost
  of the repair is visible.
- Each repair attempt spends tokens: it must flow into the existing token-usage.js report,
  not bypass it.
- Respect the existing status contract (ok/nokey/quota/error) and the existing label cycle
  (by-agent → agent-running → done/agent-failed). Quota exhaustion mid-repair still exits
  green with the existing comment.

Keep every comment in the workflow in the same explanatory voice as the rest of the file.
Validate the YAML and shell-check the changed run blocks before finishing.
```

</div>

---

### ۲.۲. `AGENTS.md` در هر مخزن (حافظهٔ معماری برای مدل ضعیف)

> **وضعیت: ✅ انجام شد.** `app/src/main/assets/AGENTS.md` (۸۹ خط) به‌عنوان هشتمین asset اضافه شد و
> در `BOOTSTRAP_ASSETS` به **ریشهٔ** مخزن نگاشت می‌شود، نه `.github/` — چون گام «Build the prompt»
> ورک‌فلوی TEC همان‌جا دنبالش می‌گردد. محتوا برای مدلی نوشته شده که مخزن را هرگز ندیده: استک
> (Compose/M3، Retrofit + kotlinx.serialization، coroutines، فارسی و RTL)، سه دستور دقیق
> (`assembleDebug`، `testDebugUnitTest`، `lint`)، درختِ «هر نوع فایل کجا می‌نشیند»، ده قاعدهٔ سخت
> (فقط توکن‌های تم، بدون رشتهٔ سخت‌کدشده، یک ViewModel برای هر صفحه، چهار حالت برای هر صفحه،
> بدون وابستگی تازه بی‌دلیل، دست‌نزدن به `.github/`) و دستور پختِ هشت‌مرحله‌ای «افزودن صفحهٔ جدید».
> کپی `docs/github/AGENTS.md` بایت‌به‌بایت یکسان است، KDoc روی `BOOTSTRAP_ASSETS` به‌روز شد، و
> `docs/github/README.md` هم درخت فایل‌ها و هم شمارش («هفت» ← «هشت») را به‌روز دارد.

<div dir="ltr">

```
Add an eighth bootstrap asset: AGENTS.md, the repo conventions file the TEC prompt already
looks for.

Read first: network/NetworkModule.kt (BOOTSTRAP_ASSETS), data/repository/RepoBootstrapper.kt,
app/src/main/assets/agent-issue-worker.yml (the "Build the prompt" step reads AGENTS.md).

Requirements:
- New asset app/src/main/assets/AGENTS.md, copied to docs/github/AGENTS.md, uploaded to the
  repo root (not .github/) by the bootstrapper.
- Content, written for a small model that has never seen the repo: the stack, the exact build
  and test commands, where each kind of file lives, hard rules (design tokens only, no
  hard-coded strings/colours, one ViewModel per screen, Persian + RTL, no new dependency
  without a reason), and a numbered "how to add a new screen" recipe.
- Keep it under ~120 lines: it is injected into every agent prompt and long files crowd out
  the issue itself.
- Register the asset, update the doc comment on BOOTSTRAP_ASSETS, and update the file list and
  the file count in docs/github/README.md.
```

</div>

---

### ۲.۳. گاردِ دامنهٔ فایل‌ها

> **وضعیت: ✅ انجام شد.** گام «Guard the file scope» بین اجرای ایجنت و دروازهٔ build می‌نشیند و
> فهرست تغییرات را با `git add -A -N` + `git diff --name-only` می‌گیرد (پس فایل‌های تازه هم دیده
> می‌شوند) و بعد ایندکس را با `git reset` سر جایش برمی‌گرداند. سه قاعده به‌ترتیب سختی:
> (۱) `.github/` ممنوع است مگر بدنهٔ ایشو صریحاً مسیری از آن را نام ببرد — ورک‌فلویی که دارد
> همین تغییر را نمره می‌دهد نباید توسط خودِ تغییر بازنویسی شود؛ (۲) اگر ایشو بخش
> «فایل‌های مجاز»/«Files to touch» داشته باشد، همان یک allowlist است و هر چیز بیرونش رد می‌شود
> (فایل تازه داخل دایرکتوری مجاز، مجاز است)؛ (۳) بدون آن بخش چیزی برای سنجیدن نیست، پس بیش از
> ۱۵ فایل فقط یک کامنت هشدار با فهرست فایل‌ها می‌گیرد و کار ادامه می‌یابد. رد‌شدن یک توقف پاک
> است: `git reset` + `git checkout -- .` + `git clean -fd`، کامنت روی ایشو با نام فایلِ بیرون از
> دامنه و دلیلش، و شکست جاب — دقیقاً مثل دروازهٔ build، خودِ گام هرگز non-zero خارج نمی‌شود تا
> گزارش توکن اجرا شود، و برچسب `agent-failed` را همان گام `always()` می‌زند. یک قاعدهٔ تازه هم به
> «Hard rules» پرامپت اضافه شد تا مدل از وجود allowlist باخبر باشد. منطق گارد با یک هارنِس محلی
> روی ۸ سناریو (بی‌allowlist، `.github` مجاز/غیرمجاز، دیفِ پهن، فایل تازه در دایرکتوری مجاز،
> نقض allowlist، allowlist دایرکتوری‌ای) آزمایش شد.

<div dir="ltr">

```
Stop the TEC agent from committing changes to files the issue never mentioned.

File: app/src/main/assets/agent-issue-worker.yml (then copy to docs/github/workflows/).

- After the agent runs and before the build gate, check `git diff --name-only`.
- Always reject changes under .github/ unless the issue body explicitly names a path there.
- When the issue body contains a "Files to touch" or "فایل‌های مجاز" section, treat the paths
  listed in it as an allowlist and reject anything outside it (a new file inside an allowed
  directory is allowed).
- When the issue has no such section, don't block — but if more than 15 files changed, comment
  a warning on the issue with the file list and carry on.
- A rejection is a clean stop: revert the working tree, comment on the issue explaining which
  file was out of scope and why, label it agent-failed, exit non-zero.
```

</div>

---

### ۲.۴. نردبان مدل

> **وضعیت: ✅ انجام شد.** سه گام قبلی (اجرای ایجنت، گاردِ دامنه، دروازهٔ build) به سه اسکریپت در
> `$RUNNER_TEMP` تبدیل شدند — `run-agent.sh`، `scope-guard.sh`، `build-gate.sh` — چون یک گام
> اکشنز فقط یک بار اجرا می‌شود و نردبان باید هر سه را برای هر رونگ صدا بزند. گام تازهٔ
> «Run the model ladder» روی `AGENT_MODEL_LADDER` (فهرست با کاما) حلقه می‌زند؛ تنظیم‌نشده یعنی
> نردبانی یک‌پله‌ای با `AGENT_MODEL`، یعنی دقیقاً رفتار قبلی. **رونگ فقط با تسلیم‌شدن حلقهٔ ترمیم
> خرج می‌شود**: ۴۲۹ (که کار کلید یدکی است و دست‌نخورده ماند)، اجرای بی‌تغییر، و دیفِ بیرون از
> دامنه همه نردبان را همان‌جا متوقف می‌کنند. بین رونگ‌ها
> `git reset` + `git checkout -- .` + `git clean -fd` درخت کار را به شاخهٔ پیش‌فرض برمی‌گرداند.
> هر رونگ لاگ و نمونه‌های اعتبار خودش را دارد و با همان `token-usage.js` یک گزارش جدا — با نام
> مدل خودش و یک خط `RUNG_NOTE` («rung 2/3: …») — روی ایشو می‌گذارد؛ آخرین گزارش، که برای اجرای
> موفق همان رونگِ برنده است، به بدنهٔ PR و تریلر کامیت می‌رسد. بدنهٔ PR صریحاً می‌گوید
> «Produced by \`model\` — rung N of M». وضعیت‌های خروجی گام
> (`ok/nokey/quota/error/scope/nochanges/build_failed`) گام‌های پیامد را می‌گردانند و خودِ گام
> هرگز non-zero خارج نمی‌شود، تا حساب توکن از دست نرود. مستندسازی: بخش ۷ و نمودار بخش ۳ در
> `docs/github/README.md` و بخش ۴ در `docs/token-usage.md`. نردبان با یک هارنِس محلی روی ۸
> سناریو (تک‌مدل سبز، ارتقا به مدل دوم، تمام‌شدن نردبان، ۴۲۹ در اجرا و در ترمیم، بی‌تغییر،
> رد دامنه، بی‌کلید) شبیه‌سازی و تأیید شد.

<div dir="ltr">

```
Make TEC escalate to another model when the first one produces work that doesn't build.

File: app/src/main/assets/agent-issue-worker.yml (then copy to docs/github/workflows/).

- New optional repo variable AGENT_MODEL_LADDER: a comma-separated list of model ids, tried in
  order. When it is unset, behaviour is exactly what it is today (AGENT_MODEL only).
- A rung is spent when the repair loop gives up — not on a 429 (that is the existing key
  fallback and must stay untouched) and not on a clean "no changes" run.
- Between rungs, reset the working tree so each model starts from the default branch.
- Every rung reports its own token spend on the issue, labelled with the model that produced
  it, so the issue thread shows what each model cost and whether it worked.
- The PR body names the model that actually produced the merged change.
- Document the variable in docs/github/README.md section 7 and in docs/token-usage.md.
```

</div>

---

### ۲.۵. جلوگیری از واگرایی assets ↔ docs

> **وضعیت: ✅ انجام شد.** `scripts/check-assets-sync.sh` فهرست جفت‌ها را با awk/sed از
> `NetworkModule.BOOTSTRAP_ASSETS` استخراج می‌کند — نه یک فهرست دستیِ تکراری، که خودش همان چیزی
> است که فراموش می‌شود — و مسیر مستند را از مسیر مخزن می‌سازد
> (`.github/scripts/x.js` ← `docs/github/scripts/x.js`، و فایل ریشه‌ای مثل `AGENTS.md`). برای هر
> جفت: نبودِ asset، نبودِ کپی، و واگرایی هر سه با پیام روشن و ۴۰ خط اول `diff -u` گزارش می‌شوند.
> جهت مخالف هم چک می‌شود: فایلی در `docs/github/` که هیچ assetی claimش نکند یعنی ثبت جا افتاده
> (`README.md` و `migrate-agent-model.sh` استثنای مستند شده‌اند). این repo ورک‌فلوی خودش را نداشت،
> پس `.github/workflows/assets-sync.yml` اضافه شد که روی هر push و PR همین اسکریپت را اجرا می‌کند.
> اسکریپت با سه سناریوی منفی (واگرایی، کپیِ گم‌شده، فایل ثبت‌نشده) و یک مثبت آزمایش شد — و در همان
> اجرای اول یک واگرایی واقعی پیدا کرد: `docs/github/migrate-agent-model.sh` که جفت مدیریت‌شده
> نیست و اکنون استثنا شده. بخش ۹ در `docs/github/README.md` به‌روز شد.

<div dir="ltr">

```
The repo says app/src/main/assets/ and docs/github/ must stay byte-for-byte identical, but
nothing enforces it. Add a CI job to this repo (not to the bootstrapped ones) that diffs every
managed pair and fails with a clear message naming the file that drifted. Wire it into the
existing workflow if this repo has one, otherwise add .github/workflows/assets-sync.yml.
Derive the pair list from NetworkModule.BOOTSTRAP_ASSETS so adding an asset can't be forgotten.
```

</div>

---

## موج ۳ — خط تولید PO/QC

### ۳.۱. سند نیت → تجزیه به ایشوهای کوچک توسط PO

> **وضعیت: ✅ انجام شد.**
>
> **سمت مخزن:** منطق مشترک provider/کلید از `ai-role-review.js` به `ai-provider.js` بیرون کشیده
> شد (`resolveProvider`، `askAI` با فِیل‌اوور ۴۲۹/۴۰۲ و پرچم `.quota`، `postComment`،
> `missingKeyMessage`) و هر دو اسکریپت نقش آن را `require` می‌کنند — کپی‌کردن منطق ممنوع بود چون
> یک مخزنِ MiniMax باید برای همهٔ نقش‌ها MiniMax بماند. `decompose-brief.js` + `decompose-brief.yml`
> روی برچسب `brief` فعال می‌شوند، بریف را با `AGENTS.md` و `git ls-files` به مدل می‌دهند و JSON
> سخت‌گیرانه می‌خواهند (`epic` + `issues[]` با `title`/`body`/`size`/`depends_on` به‌صورت اندیس
> ۱-پایه). اعتبارسنجی **قبل از** باز شدن اولین ایشو انجام می‌شود، بعد در دو پاس: اول همهٔ ایشوها
> بی‌برچسب باز می‌شوند، سپس خطِ `blocked by #n` و برچسب‌ها — فقط ایشوهای بی‌بلوکِ S/M برچسب
> `by-agent` می‌گیرند (`L` یعنی باز هم بریف است). `brief-planned` رسیدِ اجراست و اجرای دوم را
> متوقف می‌کند (به‌علاوهٔ `concurrency` per-issue برای رویدادهای `opened`+`labeled`). هر شکست
> بریف را **باز** می‌گذارد: بی‌کلید، سهمیه (سبز، فقط باید صبر کرد) یا JSON بد
> (`brief-failed` + پاسخ خام مدل در `<details>`). گزارش خرج توکن مثل هر نقش دیگر، پای همان کامنت
> نقشه. با یک هارنِس محلی (fetch جعلی) روی ۷ سناریو آزمایش شد: happy path، JSON غیرقابل‌تجزیه،
> وابستگی نامعتبر، بدنهٔ جاافتاده، بریفِ از قبل تجزیه‌شده، دیوارِ سهمیه، و بی‌کلید.
>
> **سمت اپ:** `GeminiTranscriber` رونویسی خام (بدون استخراج نیت) را از همان `GeminiApi` می‌گیرد،
> `NewBriefViewModel` + `NewBriefScreen` صفحهٔ «نیت جدید» را می‌سازند — عنوان، شرح چندخطی و
> «معیار موفقیت» اختیاری، هر کدام با میکروفن مخصوص خودش که همان `VoiceRecorder` صفحهٔ اصلی را
> دوباره استفاده می‌کند و متن را به فیلد **اضافه** می‌کند (نه جایگزین). `GitHubRepository.createBrief`
> **یک** ایشو با تنها برچسب `brief` باز می‌کند. مدل: `IssueLabels` (یک خانه برای نام برچسب‌ها)،
> `BriefStatus`، `RepoIssue.isBrief/briefStatus`، `IssueList.briefs/ordinary` و `BriefCounts` —
> شمارش «باز/بسته» دیگر بریف‌ها را نمی‌شمارد، پس کارت پروژه یک چیپ جدا («۲ نیت در انتظار تجزیه»،
> با رنگ error وقتی تجزیه شکست خورده) و صفحهٔ ایشوها یک تبِ سومِ «نیت‌ها» دارد.
> `RepoBootstrapper.LABELS` چهار برچسب تازه را می‌سازد. تست: `BriefTest` (۵ تست).

<div dir="ltr">

```
Add the missing middle stage of MIA: a long-form brief that the PO agent decomposes into
small, TEC-sized issues.

Two halves.

App side:
- A "نیت جدید" screen: title, a multi-line description (voice dictation reuses the existing
  VoiceRecorder), and an optional "معیار موفقیت" field.
- Submitting opens ONE issue labelled `brief` (not `by-agent`), body = the raw brief.
- The project card shows briefs separately from ordinary issues, with their decomposition
  status.

Repo side — a new workflow asset, decompose-brief.yml + scripts/decompose-brief.js:
- Trigger: an issue labelled `brief`.
- Calls the repo's AI-team model (reuse the provider table and key-fallback logic already in
  scripts/ai-role-review.js — factor the shared part out rather than copying it) with the
  brief plus the repo's AGENTS.md and file list.
- Asks for strict JSON: { epic, issues: [ { title, body, size: "S"|"M"|"L", depends_on: [] } ] }
  where each body follows MIA's issue template (## شرح, ## مشخصات فنی, ## راهنمای طراحی (UI/UX),
  ## معیارهای پذیرش as a checkbox list) and each issue is at most ~2 files of work.
- Opens one issue per item, writes "blocked by #n" for dependencies, and labels only the
  unblocked size-S/M ones `by-agent` so the TEC queue picks them up in order.
- Comments the plan back on the brief issue as a checklist linking each child issue, and
  reports its token spend the same way every other role does.
- Degrades cleanly: no key, quota, or unparseable JSON → a comment explaining what to do, and
  the brief stays open. Never open issues from a half-parsed response.

Register the new assets in NetworkModule.BOOTSTRAP_ASSETS and document them in
docs/github/README.md.
```

</div>

---

### ۳.۲. QC به‌عنوان دروازهٔ PR

> **وضعیت: ✅ انجام شد.** `qc-review.js` + `qc-review.yml` (روی `ai-provider.js` مشترک) diff را —
> تا ۴۰ هزار کاراکتر از `gh pr diff` — در برابر معیارهای پذیرش همان ایشو و `AGENTS.md` می‌سنجند و
> JSON سخت‌گیرانه می‌خواهند (`verdict` + `criteria[]` + `blocking[]`). شماره‌گذاری معیارها **در
> اسکریپت** از بخش `## معیارهای پذیرش` استخراج می‌شود، نه در مدل، پس جدولِ پست‌شده سطر‌به‌سطر با
> چک‌باکس‌های ایشو منطبق است. سه پیامد به‌صورت برچسب روی PR: `qc-approved` (merge)،
> `needs-rework` (merge نه، PR باز می‌ماند) و `qc-skipped` (merge).
>
> **دروازه fail-open است:** بی‌کلید، سهمیه، JSON بد و حتی «rework بدون blocking» همه
> `qc-skipped` می‌شوند — یک مدل رایگانِ محدودشده نباید merge کل مخزن را قفل کند.
>
> **یک انحراف عمدی از متن پرامپت، با دلیل:** PRی که با `GITHUB_TOKEN` باز شود رویداد
> `pull_request` را فعال نمی‌کند (قاعدهٔ ضدحلقهٔ گیت‌هاب)، پس دروازه‌ای که منتظر آن ورک‌فلو بماند
> هرگز جواب نمی‌گرفت. راه‌حل: گام «Open a PR» همان اسکریپت را **درجا** صدا می‌زند و حکم را از
> `QC_VERDICT_FILE` می‌خواند — بدون polling، همه‌چیز در لاگ یک ران. `qc-review.yml` باقی ماند
> چون برای push انسانی روی شاخهٔ `tec/issue-*` رویداد `synchronize` واقعاً می‌آید و بازبینی
> دوباره انجام می‌شود.
>
> **مسیر rework:** یادداشت‌های `blocking` هم روی PR و هم با نشانگر `<!-- qc-rework -->` روی ایشو
> کامنت می‌شوند؛ ایشو `needs-rework` + `by-agent` می‌گیرد و گام «Build the prompt» **آخرین** دسته
> را زیر سرفصل «Your previous attempt was rejected by QC» به پرامپت اضافه می‌کند. سقف سخت دو دور،
> شمرده از همان کامنت‌های نشانگردار (پس با برچسب‌زدن دوباره ریست نمی‌شود)؛ دورِ سوم `needs-human`
> می‌گیرد و جاب `queue` با یک فیلتر `jq` هر ایشوی `needs-human` را برای همیشه از صف بیرون
> می‌گذارد. چون برچسبِ `by-agent` با `GITHUB_TOKEN` ران تازه نمی‌سازد، یک تریگر
> `schedule: */30` هم اضافه شد تا دورِ rework بدون دخالت انسان درو شود. «Release the queue slot»
> برای rework فقط `agent-running` را برمی‌دارد — یک تغییرِ برگشته شکست TEC نیست.
>
> سمت اپ: `IssueLabels.NEEDS_REWORK/NEEDS_HUMAN` + `RepoIssue.needsRework/needsHuman` و یک خط
> وضعیت در ردیف‌های صفحهٔ ایشوها، و چهار برچسب تازه در `RepoBootstrapper.LABELS`.
> با هارنِس محلی روی ۶ سناریو آزمایش شد: approve، rework دور اول، عبور از سقف (`needs-human`)،
> rework با `blocking` خالی، پاسخ غیرقابل‌تجزیه، و دیوارِ سهمیه — به‌علاوهٔ بازبینی چشمی جدول و
> کامنت‌های تولیدشده. مستندسازی: بخش ۴.۶ و نمودار بخش ۳ در `docs/github/README.md`.

<div dir="ltr">

```
Make QC a gate on TEC's pull requests instead of an on-demand commenter.

New workflow asset qc-review.yml (+ reuse scripts/ai-role-review.js's provider/key logic):
- Trigger: pull_request opened by the agent (head branch matching tec/issue-*).
- Context for the model: the PR diff (`gh pr diff`, truncated to ~40k characters), the linked
  issue's acceptance criteria, and AGENTS.md.
- Ask for strict JSON: { verdict: "approve"|"rework", criteria: [{id, met: bool, note}],
  blocking: [string] }.
- approve → post the per-criterion table as a PR review comment and let the existing merge
  step proceed.
- rework → post the blocking list, label the issue `needs-rework`, and do NOT merge.
- The TEC workflow gains a rework path: a `needs-rework` label re-queues the issue with the QC
  notes appended to the prompt. Hard cap of 2 rework rounds, then label `needs-human` and stop
  — two agents must never be able to loop until the daily quota is gone.
- Everything degrades green: no key, quota, or unparseable JSON → comment "QC could not
  review" and fall back to today's behaviour rather than blocking the merge forever.
```

</div>

---

## موج ۴ — دیده‌شدن و UI/UX

### ۴.۱. تایم‌لاین زنده در اپ

> **وضعیت: ✅ انجام شد.** `CommandStage` از `MainUiState` به فایل تازهٔ `ui/main/CommandTimeline.kt`
> منتقل و با نیمهٔ گیت‌هاب کامل شد (`ISSUE_CREATED`، `QUEUED`، `AGENT_RUNNING`، `BUILD`،
> `PR_OPENED`، `MERGED`، `FAILED`). `CommandTimeline` یک ساختار خالص و تست‌پذیر است: `advance()`
> یک poll از ایشو (برچسب‌ها + کامنت‌ها) را به حالت ردیف‌ها تبدیل می‌کند و **هیچ‌جا خودش شبکه
> نمی‌زند**. نگاشت محافظه‌کارانه است — هر ردیف فقط با شاهدِ خودش DONE می‌شود، با یک استثنای
> مستند: وجود PR ثابت می‌کند build سبز شده، چون گیت‌هاب کامنتِ «build تمام شد» ندارد.
> `needs-rework` پایان نیست (تایم‌لاین به تماشا ادامه می‌دهد) ولی `needs-human` هست.
> هزینهٔ توکنِ ایجنت از جدول `**Total**` در کامنت خرج، و لینک ران از `[Workflow run](…)` خوانده
> می‌شود؛ هزینهٔ درک دستور از `TokenUsage` خودِ اپ می‌آید.
>
> `CommandTimelineCard` هر گام را یک ردیف می‌کشد: نشانگر حالت (تیک / حلقهٔ نئونی با pulse
> بی‌نهایت / خالی / ضربدر)، برچسب فارسی، زمان سپری‌شده (`Locale.US`)، و توکن وقتی معلوم است.
> ردیفِ تمام‌شده‌ای که artefact دارد قابل‌لمس است و ایشو/ران/PR را در مرورگر باز می‌کند
> (`LocalUriHandler`). با تمام‌شدن، کارت به یک ردیف خلاصه جمع می‌شود و دکمهٔ بستن می‌گیرد؛ شکست با
> رنگ error و دکمهٔ «تلاش دوباره» که ایشو را با `redoIssue` دوباره در صف می‌گذارد و تماشا را از سر
> می‌گیرد.
>
> `IntentExecutionRepository.executeAll` حالا `Result<Outcome>` برمی‌گرداند (پیام + فهرست
> `OpenedIssue`) تا تایم‌لاین شمارهٔ ایشو را از دادهٔ ساخت‌یافته بگیرد، نه با پارس‌کردن یک جملهٔ
> فارسی. فقط ایشوی `by-agent` دنبال می‌شود (ایشوی بی‌ایجنت هیچ‌وقت حرکت نمی‌کند) و اگر یک دستور
> چند ایشو ساخت، اولی دنبال می‌شود و بقیه در یادداشت همان ردیف شمرده می‌شوند — pollکردن پنج ایشو
> برای یک تزئین، سهمیهٔ مشترک گیت‌هاب را خرج می‌کند.
>
> Polling هر ۲۰ ثانیه و **فقط وقتی صفحه دیده می‌شود**: یک `LifecycleEventObserver` روی
> `ON_START/ON_STOP` به‌علاوهٔ `onDispose` (رفتن به صفحهٔ ایشوها هم متوقفش می‌کند)، و با
> merge/شکست خودش تمام می‌شود. ساعتِ ردیف‌ها یک تیکِ یک‌ثانیه‌ای است که فقط تا پایان کار می‌زند.
> `StatusBanner` حالا تنها یک حالت دارد: «در حال شنیدن...» — تنها چیزی که تایم‌لاین نمی‌تواند
> نشان بدهد، چون دستور هنوز وجود ندارد. تست: `CommandTimelineTest` (۱۴ تست، شامل تثبیت متنِ
> کامنت‌های ورک‌فلو به‌عنوان قرارداد).

<div dir="ltr">

```
Replace the three-line StatusBanner with a live vertical timeline that keeps going after the
issue is created.

Read first: ui/main/MainScreen.kt (StatusBanner), ui/main/MainUiState.kt (CommandStage),
ui/main/MainViewModel.kt, data/repository/GitHubRepository.kt.

- Extend CommandStage past EXECUTING with the GitHub half: ISSUE_CREATED, QUEUED,
  AGENT_RUNNING, BUILD, PR_OPENED, MERGED, FAILED.
- Each step is a row: state icon (done / in-progress with a subtle pulse / pending), Persian
  label, elapsed time, and — when known — the token cost of that step.
- A finished step is tappable and opens its artefact (issue, workflow run, PR) in the browser.
- The GitHub half is driven by polling the issue's labels and comments (by-agent →
  agent-running → done/agent-failed, which the worker already maintains) every ~20s while the
  screen is visible, and not at all when it is not.
- Collapses to a single summary row once everything is finished.
- Fully RTL, uses the existing neon accent for the active step, and shows the failed state in
  the error colour with a "تلاش دوباره" action that re-queues the issue.
```

</div>

---

### ۴.۲. رندر Markdown در صفحهٔ ایشو

> **وضعیت: ✅ انجام شد.** `ui/issues/Markdown.kt` یک پارسر خالص است (بدون هیچ وابستگی تازه) با دو
> تابع تست‌پذیر: `parseMarkdown` (بلوک‌ها) و `parseInline` (رانِ درون‌خطی). پشتیبانی دقیقاً همان
> فهرست خواسته‌شده است: سرفصل، **پرکاربرد**/*کج*، `کد درون‌خطی`، بلوک کد fenced، لیست گلوله‌ای و
> شماره‌دار (با نگه‌داشتن شمارهٔ نوشته‌شده و عمق تورفتگی)، چک‌باکس، جدول GFM، لینک، بلوک‌کوت و خط
> افقی. **قاعدهٔ حاکم: هیچ‌چیز پنهان نمی‌شود** — fenceِ بسته‌نشده، `|` بدون خط جداکنندهٔ جدول،
> `*` بازنشده، و `[label](با فاصله)` همه به‌صورت متن عینی برمی‌گردند؛ نیمه‌پارس‌کردن همان چیزی بود
> که انتخاب «متن ساده» برای اجتنابش انجام شده بود.
>
> `MarkdownText.kt` رندر Compose است: پاراگراف‌ها `TextDirection.Content` می‌گیرند (پس جملهٔ فارسی
> RTL و جملهٔ انگلیسی LTR می‌چیند، فی‌پاراگراف)، و بلوک کد و جدول با
> `CompositionLocalProvider(LocalLayoutDirection provides Ltr)` صریحاً LTR می‌شوند و درون خودشان
> افقی اسکرول می‌خورند (`softWrap = false` برای کد — شکستن سطر کد یعنی دو دستور خواندن). لینک‌ها با
> `withLink(LinkAnnotation.Url)` کار می‌کنند (Compose 1.7 در همین BOM)، چک‌باکس‌ها عمداً فقط
> خواندنی‌اند، و ستون‌های جدول `widthIn` می‌گیرند نه `weight` — جدول‌های ایجنت‌ها یک ستون باریکِ
> تیک و یک ستون پهنِ متن دارند و وزن مساوی متن را نواری می‌کرد. پارس روی `remember(markdown)`
> کش می‌شود.
>
> بدنهٔ ایشو و **همهٔ کامنت‌ها** (گزارش توکن، نقشهٔ PO، جدول حکم QC) از این مسیر رندر می‌شوند و
> دکمهٔ «باز کردن در گیت‌هاب» در نوار بالا سر جای خودش ماند. تست: `MarkdownTest` (۱۷ تست، شامل یک
> round-trip روی یک کامنت واقعی گزارش توکن و چهار تست برای متنی که Markdown **نیست**).

<div dir="ltr">

```
IssueDetailScreen deliberately renders issue bodies as plain text. That made sense before the
agents started posting tables and code blocks; now the token reports and PO/QC answers are
unreadable. Add a small, safe Markdown renderer.

- Support exactly: headings, bold/italic, inline code, fenced code blocks (monospace,
  horizontally scrollable), bullet and numbered lists, checkbox lists, tables, links,
  blockquotes and horizontal rules. Everything else renders as its literal text — never hide
  content you can't parse, which is the reason plain text was chosen in the first place.
- Pure Compose, no new dependency, in ui/issues/Markdown.kt with unit tests for the parser.
- LTR content (code, tables, English) must not be mangled by the RTL root: give those blocks
  their own direction.
- Keep the "باز کردن در گیت‌هاب" button for anything the renderer doesn't cover.
```

</div>

---

### ۴.۳. Design System برای مخزن‌های تولیدشده

> **وضعیت: ✅ انجام شد.** چهار asset تازه که در `app/src/main/java/mia/design/` هر مخزن جدید
> می‌نشینند: `Tokens.kt` (`Spacing` شش‌پله روی گرید ۴dp، `Radius`، `Elevation`، `Sizes` شامل
> `touchTarget`، `Motion` با سه مدت و یک easing)، `MiaTheme.kt` (همان پالت نئونیِ تیره‌محورِ
> `ui/theme` خودِ اپ، تایپ‌اسکیل با line-height بازتر چون فارسی به آن نیاز دارد، و **RTL فقط
> همین‌جا**)، `MiaComponents.kt` (`MiaScaffold`، `MiaCard`، `MiaButton` با سه نقش
> primary/secondary/danger، `MiaTextField`، `MiaEmptyState`، `MiaError`، `MiaSkeleton`،
> `MiaLoading`، `MiaChip`، `MiaRow`) و `ExampleScreen.kt` (یک صفحهٔ کامل و پرکامنت با هر چهار
> حالت و چهار `@Preview`).
>
> **دو تصمیم که لازم بودند:** (۱) پکیج `mia.design` **ثابت** است، چون MIA در لحظهٔ ساخت مخزن
> نمی‌داند آن پروژه بعداً چه پکیجی خواهد داشت و پکیج یک فایل Kotlin هم لازم نیست با
> `applicationId` یکی باشد؛ (۲) `AppFontFamily` عمداً `FontFamily.Default` است، چون ارجاع به
> `R.font.vazirmatn_regular` در مخزنی که هنوز فونتی ندارد بیلد را می‌شکند — رفتن روی Vazirmatn
> تغییر همان یک خط است (نسخهٔ کامنت‌شده بالای آن آماده).
>
> `AGENTS.md` قاعدهٔ شمارهٔ ۱ را گرفت («صفحه‌ها سرهم می‌شوند، نه طراحی») و دستور پختِ «صفحهٔ جدید»
> حالا با «اول `ExampleScreen.kt` را بخوان» شروع می‌شود. `ci.yml` یک گام lint گرفت که هر
> `*Screen.kt` را برای `Color(0x…)`، رنگ‌های نام‌بردهٔ Compose و `.dp`/`.sp` خالی grep می‌کند
> (regex با `[^A-Za-z0-9_.]` قبل از رقم، تا `Spacing.md` و `Sizes.iconMd` مثبتِ کاذب نشوند)، ماژول
> design را معاف می‌کند و در صورت تخلف با شمارهٔ خط و اشاره به `Tokens.kt` بیلد را می‌شکند.
> `ExampleScreen.kt` خودش از این lint رد می‌شود.
>
> اعتبارسنجی: هر چهار فایل موقتاً داخل همین اپ کامپایل شدند (`compileDebugKotlin --rerun-tasks`،
> ۲۱ کلاس تولید شد) و بعد برداشته شدند؛ گام lint با یک مخزن آزمایشی روی هر دو حالتِ پاک و متخلف
> اجرا شد. مستندسازی: بخش ۴.۷ و درخت فایل‌ها و شمارش («سیزده» ← «هفده») در `docs/github/README.md`.

<div dir="ltr">

```
Weak models write inconsistent UI when they design; they write good UI when they assemble
pre-made parts. Ship them the parts.

Add a bootstrap asset set: a ui/design/ module dropped into every new Android repo, holding
Tokens.kt (spacing, radius, elevation, motion), MiaTheme.kt (colours + Vazirmatn typography +
RTL default), and MiaScaffold / MiaCard / MiaButton (primary, secondary, danger) /
MiaTextField / MiaEmptyState / MiaError / MiaSkeleton, plus a fully commented ExampleScreen.kt
showing all four states of a screen.

Then:
- AGENTS.md gets the hard rule: screens use these components and tokens only — no raw
  Color(0x..), no bare .dp, no bare Material components.
- ci.yml gets a lint step that greps *Screen.kt for raw colours and dp values and fails with a
  message pointing at ui/design.
- docs/github/README.md documents the module and the rule.

Take the visual language from this app's own ui/theme (dark-first, neon accents) so generated
projects look like MIA made them.
```

</div>

---

### ۴.۴. صفحهٔ هزینه

> **وضعیت: ✅ انجام شد.** صفحهٔ «هزینهٔ توکن» از آیکن نمودار در نوار بالای صفحهٔ اصلی باز می‌شود
> (`MiaApp` یک حالت saveable پنجم گرفت، پس چرخش صفحه و مرگ پروسه آن را نگه می‌دارد و Back
> برمی‌گردد؛ نوتیفیکیشنِ ایجنت هم آن را کنار می‌زند و مستقیم به ایشو می‌رود).
>
> **منبع اعداد سه‌تاست و هیچ‌کدام پایگاه‌داده نیست:** تریلرهای `Token-Spend:` از commits API
> (سهم TEC — تنها ردی که بعد از پاک‌شدن شاخه و کهنه‌شدن لاگ‌ها می‌ماند)، پاورقی کامنت‌های PO/QC از
> **یک** فراخوانی repo-wide به‌جای یک درخواست برای هر ایشو، و `LocalSpendStore` برای سهم خودِ MIA.
> کامنتِ `### 💸 Token spend` خودِ TEC عمداً خوانده **نمی‌شود** — همان هزینهٔ تریلر است و خواندن هر
> دو، هر اجرای ایجنت را دو برابر می‌شمرد. `SpendParsing` یک شیء خالص و تست‌شده است، پس رشته‌هایی که
> `.github/scripts/` می‌نویسد یک **قرارداد** است: عوض‌شدن یک پاورقی تستی را می‌شکند، نه صفحه‌ای را
> در سکوت صفر می‌کند.
>
> **صداقت، همان قاعدهٔ صفحهٔ ایشوها:** MIA سه صفحه از هر کدام را می‌خواند، و اگر ته تاریخچه نرسیده
> باشد تیتر «جمع کل» به «جمع کل (دست‌کم)» عوض می‌شود با توضیح چرا. هزینهٔ گزارش‌نشده (`costUsd = null`)
> هرگز به `$0.00` تبدیل نمی‌شود — «رایگان» و «نمی‌دانیم» دو چیزند — و مخزنی که خوانده نشد با نام در
> کارت جمع می‌آید نه اینکه بی‌صدا از قلم بیفتد؛ یک پروژهٔ خراب نباید عدد هفت‌تای دیگر را پنهان کند.
>
> صفحه شامل نمودار میله‌ای هشت هفتهٔ اخیر (هفته از **شنبه** و در UTC، چون مرزی که با سفر کاربر جابه‌جا
> شود نموداری را که کارش مقایسهٔ هفته‌هاست بی‌معنا می‌کند؛ هفتهٔ خالی حذف نمی‌شود تا دو میله که یک ماه
> فاصله دارند کنار هم «کار پیوسته» به نظر نرسند)، تفکیک به نقش و به مدل با نوار سهم، پنج ایشوی
> گران‌ترین (جمع همهٔ بازیگران روی یک ایشو، و کلیک روی آن گیت‌هاب را باز می‌کند) و جمع کل است.
> نمودار با چند `Box` کشیده شده، نه با کتابخانه — هشت مستطیل و یک ماکسیمم ارزش یک وابستگی برای
> همیشه نگه‌داشتن را ندارد. همه‌چیز RTL، و رقم‌ها با `Locale.US` گروه‌بندی می‌شوند مثل بقیهٔ اپ.
>
> بودجهٔ ماهانه در `SecretStore.monthlyTokenBudget` می‌نشیند و **به توکن است نه دلار**: مدل‌های رایگان
> `$0.00` صورت‌حساب می‌دهند و سهمیهٔ Gemini اصلاً per-call گزارش نمی‌شود، پس بودجهٔ دلاری دقیقاً وقتی
> صفر می‌ماند که سقف روزانه دارد می‌سوزد. نوار پیشرفت از **۸۰٪** به رنگ error می‌رود نه از ۱۰۰٪ —
> هشداری که بعد از خرج‌شدن بیاید هشدار نیست — و کسر بالای ۱۰۰٪ کلیپ نمی‌شود تا «کمی رد شده» و
> «سه برابر رد شده» یک شکل نباشند.
>
> **یک انحراف عمدی از متن پرامپت:** فیلد بودجه روی همین صفحه است، نه در `SettingsDialog`. عدد فقط
> در کنار نواری که می‌راند معنا دارد، و گذاشتنش در دو جا یعنی دو جا برای فراموش‌کردن.
>
> سه اصلاح که در همین گذر لازم شد: `LocalSpendStore.record` هیچ‌جا صدا زده نمی‌شد (پس سهم خودِ MIA
> همیشه صفر بود) و حالا در `handleClassification` ثبت می‌شود — یعنی وقتی توکن خرج **شده**، حتی اگر
> کاربر بعد شیت تأیید را ببندد؛ `SpendRepository` به‌جای خودِ `LocalSpendStore` یک provider می‌گیرد تا
> مثل بقیهٔ کلاس‌های این لایه بدون `Context` تست شود؛ و `FakeGitHubApi` دو متد تازهٔ API را
> پیاده‌سازی نکرده بود، پس کل source-set تست کامپایل نمی‌شد.
> تست‌ها: `SpendParsingTest` (۱۴)، `SpendReportTest` (۱۴)، `SpendRepositoryTest` (۷).

<div dir="ltr">

```
Aggregate the token accounting that is already scattered across issue comments into one screen.

Read first: docs/token-usage.md, data/model/TokenUsage.kt, data/repository/GitHubRepository.kt.

- New screen reachable from the top bar: weekly spend bar chart, a breakdown by model and by
  role (MIA / TEC / PO / QC), the five most expensive issues, and a total.
- Source: the `Token-Spend:` commit trailers (one `git log`-shaped read via the commits API)
  plus the app's own local usage. Say plainly when a number is "at least X" because pagination
  truncated it — the issues list already has that honesty and this screen must match it.
- A monthly budget in settings, with a progress bar that turns to the error colour past 80%.
- Loading / empty / error+retry states, RTL, Locale.US digit grouping like the rest of the app.
```

</div>

---

## قالب ساخت پرامپت جدید

هر پرامپت خوبی که برای این مخزن نوشتید همین پنج بخش را دارد. مدل ضعیف روی گیت‌هاب هم دقیقاً
با همین ساختار بهترین کارش را می‌کند:

<div dir="ltr">

```
<one sentence: the outcome, in user terms>

Read first: <the 3-5 files that already answer most of the questions>

Requirements:
- <one behaviour per line, each objectively checkable>
- <the failure paths: what happens on error, empty, offline, no key>

Constraints:
- <what must NOT change>
- <the existing pattern to follow, named by file>

Done when: <the observable end state>
```

</div>

سه چیزی که بیشترین تفاوت را می‌سازند: **«Read first»** (مدل حدس نمی‌زند)، **مسیرهای شکست**
(وگرنه فقط happy path ساخته می‌شود)، و **«what must NOT change»** (وگرنه diff غول‌آسا می‌شود).

---

<div align="center">

[نقشهٔ راه](roadmap.md) · [مستندات سیستم](README.md) · [تیم ایجنت](github/README.md)

</div>

</div>
