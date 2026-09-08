<div dir="rtl">
set true for Allow GitHub Actions to create and approve pull requests
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
