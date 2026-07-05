<div dir="rtl">

# مستندات کامل سیستم MIA

> **MIA** یک دستیار مدیریت وظایف مبتنی بر **فرمان صوتی فارسی** برای اندروید است.
> کاربر با میکروفون یک دستور فارسی می‌گوید، صدا مستقیماً به **Gemini** فرستاده می‌شود،
> مدل هم‌زمان گفتار را رونویسی می‌کند و «نیت» (Intent) را استخراج می‌کند، سپس اپ آن نیت را
> روی پایگاه‌دادهٔ **Supabase** اجرا می‌کند و به‌صورت آینه‌ای روی **GitHub** بازتاب می‌دهد.
> در نهایت یک **ایجنت کدنویس خودکار** (Gemini CLI روی GitHub Actions) می‌تواند وظیفه را
> به‌صورت واقعی در مخزن کد انجام دهد.

این سند نحوهٔ کار کل سیستم را از ابتدا تا انتها توضیح می‌دهد.

---

## فهرست مطالب

1. [نگاه کلی به سیستم](#۱-نگاه-کلی-به-سیستم)
2. [معماری کلی و لایه‌ها](#۲-معماری-کلی-و-لایهها)
3. [جریان اصلی داده: از صدا تا اجرا](#۳-جریان-اصلی-داده-از-صدا-تا-اجرا)
4. [ماژول‌ها و اجزای اصلی](#۴-ماژولها-و-اجزای-اصلی)
   - [احراز هویت (Auth)](#۴۱-احراز-هویت-auth)
   - [ضبط صدا (VoiceRecorder)](#۴۲-ضبط-صدا-voicerecorder)
   - [استخراج نیت با Gemini](#۴۳-استخراج-نیت-با-gemini)
   - [اجرای نیت روی Supabase](#۴۴-اجرای-نیت-روی-supabase)
   - [بازتاب روی GitHub](#۴۵-بازتاب-روی-github)
   - [ایجنت کدنویس خودکار](#۴۶-ایجنت-کدنویس-خودکار-github-actions)
5. [لایهٔ شبکه (NetworkModule)](#۵-لایهٔ-شبکه-networkmodule)
6. [امنیت و مدیریت کلیدها](#۶-امنیت-و-مدیریت-کلیدها)
7. [لایهٔ رابط کاربری (UI)](#۷-لایهٔ-رابط-کاربری-ui)
8. [مدل‌های داده](#۸-مدلهای-داده)
9. [پیکربندی و راه‌اندازی](#۹-پیکربندی-و-راهاندازی)
10. [نکات، محدودیت‌ها و بخش‌های میراثی](#۱۰-نکات-محدودیتها-و-بخشهای-میراثی)

---

## ۱. نگاه کلی به سیستم

MIA یک اپ اندرویدی نوشته‌شده با **Kotlin + Jetpack Compose** است که هستهٔ آن روی یک ایدهٔ ساده بنا شده:

> «کاربر حرف می‌زند، سیستم می‌فهمد و انجام می‌دهد.»

اما پشت این سادگی، یک زنجیرهٔ کامل از سرویس‌ها قرار دارد:

| مرحله | مسئولیت | فناوری |
|-------|---------|--------|
| ورود کاربر | احراز هویت با نام کاربری/رمز | Supabase Auth (GoTrue) |
| ضبط صدا | گرفتن صدای میکروفون به‌صورت WAV | `AudioRecord` بومی اندروید |
| فهم دستور | رونویسی + استخراج نیت در یک فراخوانی | Google Gemini (چندوجهی/Multimodal) |
| اجرای دستور | ساخت/حذف پروژه و وظیفه | Supabase REST (PostgREST) |
| بازتاب کد | هر پروژه → یک مخزن، هر وظیفه → یک Issue | GitHub REST API |
| انجام خودکار وظیفه | ویرایش واقعی کد و push | Gemini CLI روی GitHub Actions |

**نکتهٔ کلیدی معماری:** برخلاف روش سنتی (تبدیل گفتار به متن روی دستگاه، سپس ارسال *متن* به یک
دسته‌بند)، در MIA صدای خام مستقیماً به Gemini فرستاده می‌شود و مدل در **یک فراخوانی چندوجهی**
هم رونویسی و هم استخراج نیت را انجام می‌دهد. این کار دقت را بالا می‌برد و خطای انباشتهٔ رونویسی
جداگانه را حذف می‌کند.

---

## ۲. معماری کلی و لایه‌ها

پروژه از یک معماری تمیز و لایه‌ای پیروی می‌کند (بدون فریمورک DI؛ تزریق وابستگی دستی و سبک):

<div dir="ltr">

```
┌─────────────────────────────────────────────────────────────┐
│                        UI (Jetpack Compose)                  │
│   LoginScreen · MainScreen · MicFab · SettingsDialog          │
│                          ▲   │                                │
│                 StateFlow │   │ رویدادها/کلیک                  │
│                          │   ▼                                │
│                     ViewModels (AndroidViewModel)             │
│                AuthViewModel      MainViewModel                │
└───────────────────────────┬─────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────┐
│                        Repositories (data/)                  │
│  AuthRepository · ProjectRepository ·                        │
│  GeminiVoiceIntentClassifier · IntentExecutionRepository ·   │
│  GitHubRepository · RepoBootstrapper                          │
└───────────────────────────┬─────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────┐
│              Network APIs (Retrofit interfaces)              │
│  SupabaseAuthApi · SupabaseApi · GeminiApi · GitHubApi        │
│              (سیم‌کشی مرکزی: NetworkModule)                    │
└───────────────────────────┬─────────────────────────────────┘
                            │
        ┌───────────┬───────┴────────┬──────────────┐
        ▼           ▼                ▼              ▼
    Supabase     Gemini API       GitHub API    (GapGPT/OpenRouter*)
     (Auth        (رونویسی +       (مخزن‌ها،       * میراثی/بلااستفاده
    + PostgREST)   استخراج نیت)     ایشوها،
                                   ورک‌فلوها)
```

</div>

ساختار پوشه‌ها:

<div dir="ltr">

```
ir/mahditavakoli/mia/
├── MIAApplication.kt          ← نقطهٔ آغاز؛ NetworkModule.init() را صدا می‌زند
├── MainActivity.kt            ← بین LoginScreen و MainScreen سوییچ می‌کند
├── ui/
│   ├── auth/                  ← صفحهٔ ورود + AuthViewModel
│   ├── main/                  ← صفحهٔ اصلی، MicFab، دیالوگ تنظیمات، MainViewModel
│   └── theme/                 ← تم نئونی (Color/Type/Theme)
├── data/
│   ├── model/                 ← Project, Task, VoiceCommandIntent, ActionType
│   ├── repository/            ← منطق کسب‌وکار (ریپازیتوری‌ها)
│   └── session/               ← SessionManager (نگهداری توکن نشست)
├── network/
│   ├── NetworkModule.kt       ← DI دستی: کلاینت‌های Retrofit + اینترسپتورها
│   ├── supabase/              ← SupabaseApi + SupabaseAuthApi
│   ├── gemini/                ← GeminiApi + مدل‌ها + پرامپت استخراج نیت
│   ├── github/                ← GitHubApi + مدل‌ها
│   └── openrouter/            ← کلاینت GapGPT (میراثی؛ دیگر برای دسته‌بندی استفاده نمی‌شود)
├── security/
│   ├── SecretStore.kt         ← ذخیرهٔ رمزنگاری‌شدهٔ کلید Gemini
│   └── AndroidCrypto.kt       ← libsodium sealed-box برای رمز کردن Secret گیت‌هاب
└── voice/
    └── VoiceRecorder.kt       ← ضبط PCM ۱۶ کیلوهرتز و بسته‌بندی WAV
```

</div>

---

## ۳. جریان اصلی داده: از صدا تا اجرا

این مهم‌ترین بخش سیستم است. کل سفر یک دستور صوتی:

<div dir="ltr">

```
 کاربر روی دکمهٔ میکروفون می‌زند (اولین لمس)
        │
        ▼
 [VoiceRecorder.start()]  ── ضبط PCM 16kHz مونو روی یک ترد جداگانه
        │                     دامنهٔ صدا (amplitude) لحظه‌ای برای پالس دکمه به‌روز می‌شود
        ▼
 کاربر دوباره می‌زند (لمس دوم) → RecordingState.Listening ⇒ stopAndProcess()
        │
        ▼
 [VoiceRecorder.stop()]   ── PCM را در یک هدر ۴۴ بایتی WAV می‌پیچد → ByteArray
        │                     (اگر خیلی کوتاه بود، null → پیام «صدایی ضبط نشد»)
        ▼
 RecordingState.Processing
        │
        ▼
 [GeminiVoiceIntentClassifier.classify(wav, projects)]
        │   • WAV را Base64 می‌کند
        │   • پرامپت GeminiIntentPrompt + لیست پروژه‌های فعلی را ضمیمه می‌کند
        │   • یک فراخوانی چندوجهی به Gemini
        │   • خروجی: یک شیء JSON دقیق (VoiceCommandIntent)
        ▼
 [IntentExecutionRepository.execute(intent, agentHandled)]
        │   بر اساس action_type یکی از این‌ها:
        │   ├── create_project  → Supabase: ساخت پروژه  (+ GitHub: ساخت مخزن)
        │   ├── delete_project  → Supabase: حذف پروژه بر اساس نام
        │   ├── add_task        → Supabase: ساخت وظیفه  (+ GitHub: باز کردن Issue)
        │   └── remove_task     → Supabase: حذف وظیفه بر اساس عنوان
        ▼
 پیام تأیید فارسی به کاربر (Snackbar)  +  refreshProjects()  → لیست به‌روز می‌شود
```

</div>

**مثال زنده:** کاربر می‌گوید:
> «به پروژه وبسایت یه تسک اضافه کن: طراحی لوگو با تم آبی تا جمعه»

خروجی Gemini:

<div dir="ltr">

```json
{
  "action_type": "add_task",
  "project_name": "وبسایت",
  "task_title": "طراحی لوگو",
  "task_description": "طراحی لوگوی پروژه وبسایت با تم رنگی آبی. لوگو باید متناسب با هویت بصری پروژه باشد و نسخه نهایی تا روز جمعه آماده شود.",
  "due_date": "2026-07-10"
}
```

</div>

سپس:
1. یک ردیف در جدول `tasks` سوپابیس ساخته می‌شود.
2. اگر GitHub پیکربندی شده باشد، یک Issue با عنوان «طراحی لوگو» و بدنهٔ `task_description` در مخزن پروژهٔ «وبسایت» باز می‌شود.
3. اگر گزینهٔ «سپردن به ایجنت» روشن باشد، این Issue با برچسب `by-agent` باز می‌شود و بلافاصله ورک‌فلوی ایجنت را فعال می‌کند.

---

## ۴. ماژول‌ها و اجزای اصلی

### ۴.۱. احراز هویت (Auth)

**فایل‌ها:** `AuthRepository.kt`، `SupabaseAuthApi.kt`، `SessionManager.kt`، `AuthViewModel.kt`، `LoginScreen.kt`

- ورود با **نام کاربری و رمز عبور** انجام می‌شود، اما Supabase Auth حساب‌ها را با **ایمیل** می‌شناسد.
  برای پل زدن، نام کاربری به یک ایمیل ساختگی نگاشت می‌شود: `<username>@mia.app`.
  (پسوند `.local` توسط اعتبارسنج ایمیل سوپابیس رد می‌شود، بنابراین یک TLD واقعی‌نما لازم است.)
- در ثبت‌نام (`signUp`)، اگر تأیید ایمیل غیرفعال باشد، بلافاصله یک نشست برمی‌گردد و کاربر وارد می‌شود؛
  در غیر این صورت کاربر باید جداگانه وارد شود.
- **`SessionManager`** توکن دسترسی (access token) را در `SharedPreferences` معمولی ذخیره می‌کند و
  وضعیت ورود را به‌صورت یک `StateFlow<Boolean>` در معرض UI می‌گذارد. `MainActivity` به همین Flow
  گوش می‌دهد و بین `LoginScreen` و `MainScreen` سوییچ می‌کند:

<div dir="ltr">

```kotlin
val isLoggedIn by NetworkModule.sessionManager.isLoggedIn.collectAsState()
if (isLoggedIn) MainScreen(...) else LoginScreen()
```

</div>

- همین `accessToken` است که اینترسپتور REST سوپابیس آن را به‌عنوان توکن Bearer می‌فرستد و در نتیجه
  (وقتی Row Level Security فعال باشد) هر درخواست را به کاربر جاری محدود می‌کند.

### ۴.۲. ضبط صدا (VoiceRecorder)

**فایل:** `voice/VoiceRecorder.kt`

- صدا را با **`AudioRecord`** بومی به‌صورت **PCM ۱۶ کیلوهرتز مونو ۱۶ بیتی** می‌گیرد؛ این کیفیت برای
  گفتار ایده‌آل است و حجم Base64 را کوچک نگه می‌دارد.
- ضبط روی یک **ترد اختصاصی** اجرا می‌شود (چون `AudioRecord.read` مسدودکننده است).
- در هر بافر، **RMS دامنه** محاسبه و در `amplitude: StateFlow<Float>` منتشر می‌شود تا دکمهٔ میکروفون
  (`MicFab`) بتپد.
- در `stop()`، PCM خام درون یک **هدر استاندارد ۴۴ بایتی WAV** پیچیده می‌شود تا Gemini یک فایل
  `audio/wav` خودتوصیف دریافت کند. اگر کلیپ خیلی کوتاه باشد (زیر ~۰٫۲۵ ثانیه) `null` برمی‌گردد.
- مجوز `RECORD_AUDIO` توسط UI گرفته می‌شود و در `start()` دوباره بررسی می‌شود.

> **چرا به‌جای SpeechRecognizer؟** این کلاس جایگزین مسیر `SpeechRecognizer` پلتفرم شده است. حالا
> به‌جای تبدیل گفتار به متن روی دستگاه و ارسال متن، صدای خام مستقیماً به Gemini می‌رود.

### ۴.۳. استخراج نیت با Gemini

**فایل‌ها:** `GeminiVoiceIntentClassifier.kt`، `network/gemini/GeminiIntentPrompt.kt`، `GeminiApi.kt`، `GeminiModels.kt`

این قلب «هوش» سیستم است. مراحل:

1. صدای WAV به Base64 (بدون شکست خط) تبدیل می‌شود.
2. یک درخواست `GeminiRequest` ساخته می‌شود که **دو بخش (part)** دارد:
   - **بخش متنی:** پرامپت سیستمی از `GeminiIntentPrompt.build(...)`.
   - **بخش صوتی:** `inline_data` با `mime_type = "audio/wav"` و دادهٔ Base64.
3. فراخوانی به اندپوینت `v1beta/models/{model}:generateContent` (پیش‌فرض مدل: `gemini-2.5-flash`).
   کلید API از طریق هدر `x-goog-api-key` **در هر فراخوانی** فرستاده می‌شود (نه در اینترسپتور)، چون
   همان کلید زمان‌اجراست که از `SecretStore` می‌آید.
4. پاسخ باید یک شیء JSON خالص باشد و به `VoiceCommandIntent` تبدیل (deserialize) شود.

**نکات مهم پرامپت (`GeminiIntentPrompt`):**

- **تاریخ امروز** به‌صورت میلادی تزریق می‌شود تا عبارات نسبی فارسی («فردا»، «جمعه آینده»، «هفته بعد»)
  به تاریخ واقعی `YYYY-MM-DD` تبدیل شوند.
- **لیست پروژه‌ها و وظایف فعلی کاربر** به‌عنوان «حقیقت مبنا» (ground truth) تزریق می‌شود تا مدل نام‌های
  گفته‌شده را با دادهٔ واقعی تطبیق دهد (مخصوصاً وقتی صدا نویز دارد).
- خروجی دقیقاً **پنج کلید** دارد: `action_type`، `project_name`، `task_title`، `task_description`، `due_date`.
- برای `add_task` علاوه بر عنوان کوتاه، یک **`task_description` کامل و خودبسنده به فارسی** (۲ تا ۵ جمله)
  خواسته می‌شود؛ این توضیح بعداً **بدنهٔ Issue** می‌شود که ایجنت کدنویس از روی آن کار می‌کند.
- برای اکشن‌های روی چیزهای موجود (حذف/افزودن/حذف وظیفه)، مدل باید نزدیک‌ترین نام موجود را انتخاب و
  **املای دقیق ذخیره‌شده** را برگرداند (حتی اگر صدا با ی/ي، ک/ك، نیم‌فاصله یا فاصله فرق داشته باشد).

**تنظیمات تولید (`GeminiGenerationConfig`):**
- `temperature = 0.0` برای خروجی قطعی.
- `responseMimeType = "application/json"` تا مدل مجبور شود JSON خالص بدهد (بدون حصار ```json).
  با این حال کلاسیفایر یک تابع `sanitize` دفاعی هم دارد که حصارهای احتمالی را حذف می‌کند.

**نکتهٔ سریال‌سازی:** Gemini بخش‌هایی که فیلد `null` صریح دارند را رد می‌کند، بنابراین یک نمونهٔ
`Json` جداگانه با `explicitNulls = false` استفاده می‌شود تا فیلدهای بلااستفاده حذف شوند نه اینکه
`null` فرستاده شوند.

### ۴.۴. اجرای نیت روی Supabase

**فایل‌ها:** `IntentExecutionRepository.kt`، `SupabaseApi.kt`، `ProjectRepository.kt`

نیت تجزیه‌شده روی بک‌اند PostgREST سوپابیس اجرا می‌شود. جست‌وجوی پروژه‌ها **بر اساس نام** انجام
می‌شود چون مدل فقط نام می‌دهد نه شناسه (id):

| `action_type` | عملیات Supabase | اثر جانبی GitHub |
|---------------|-----------------|------------------|
| `create_project` | `POST /projects` | ساخت مخزن جدید |
| `delete_project` | یافتن پروژه، `DELETE /projects?id=eq.<uuid>` | — |
| `add_task` | یافتن پروژه، `POST /tasks` | باز کردن Issue |
| `remove_task` | یافتن پروژه و وظیفه بر اساس عنوان، `DELETE /tasks?id=eq.<uuid>` | — |

**تطبیق مقاوم نام فارسی:** اگر تطبیق دقیق نام پیدا نشد، یک مسیر جایگزین همهٔ پروژه‌ها را می‌گیرد و
نام‌ها را به‌صورت **نرمال‌شده** مقایسه می‌کند (تبدیل ي→ی و ك→ک، حذف نیم‌فاصله و همهٔ فاصله‌ها،
کوچک کردن حروف). این باعث می‌شود تفاوت‌های رایج املای فارسی مشکلی ایجاد نکنند.

**اثرات جانبی GitHub «بهترین‌تلاش» (best-effort) هستند:** اگر GitHub پیکربندی نشده باشد یا خطا بدهد،
هرگز عملیات اصلی سوپابیس شکست نمی‌خورد — فقط پیام تأیید نهایی تغییر می‌کند (مثلاً «...ولی ساخت مخزن
گیت‌هاب ناموفق بود»).

جدول‌های سوپابیس:

<div dir="ltr">

```sql
projects(id uuid pk, name text, created_at timestamptz)
tasks(id uuid pk, project_id uuid fk -> projects.id,
      title text, due_date date, is_done bool, created_at timestamptz)
```

</div>

### ۴.۵. بازتاب روی GitHub

**فایل‌ها:** `GitHubRepository.kt`، `RepoBootstrapper.kt`، `GitHubApi.kt`، `GitHubModels.kt`

نگاشت مفهومی:

- **هر پروژه → یک مخزن (repository)**
- **هر وظیفه → یک Issue در آن مخزن**

**نام مخزن به‌صورت قطعی (deterministic) از نام پروژه ساخته می‌شود** (`repoNameFor`)، بنابراین وقتی
بعداً وظیفه‌ای اضافه می‌شود می‌توان همان مخزن را بدون ذخیرهٔ نگاشت بازسازی کرد:
- نام‌های لاتین به یک slug خوانا تبدیل می‌شوند (`[^a-z0-9]+` → `-`).
- نام‌هایی که هیچ کاراکتر ASCII قابل‌استفاده ندارند (مثلاً کاملاً فارسی) به یک هش پایدار
  (`mia-project-<hash>`) تبدیل می‌شوند تا نتیجه همچنان قطعی بماند.

**راه‌اندازی مخزن (`RepoBootstrapper`)** — هنگام ساخت هر مخزن جدید این مراحل انجام می‌شود:

1. **ساخت مخزن** (ساده، یا از روی یک قالب/template اگر `MIA_TEMPLATE_REPO` تنظیم شده باشد). این تنها
   مرحله‌ای است که خطایش کل عملیات را شکست می‌دهد.
2. **بارگذاری فایل ورک‌فلو** `.github/workflows/agent-issue-worker.yml` (اگر از قالب استفاده نشده باشد).
3. **ساخت برچسب‌ها:** `by-agent` (آبی) و `done` (سبز). کد ۴۲۲ (برچسب از قبل وجود دارد) قابل‌قبول است.
4. **ذخیرهٔ کلید Gemini** به‌عنوان Secret اکشنز با نام `GEMINI_API_KEY`.

مراحل ۲ تا ۴ «بهترین‌تلاش» هستند؛ اگر هرکدام خطا بدهند، به‌جای شکست کامل، در فهرست `warnings` گزارش
می‌شوند و کاربر پیام هشدار می‌بیند (مثلاً «هشدار پیکربندی ایجنت: ...»).

**بررسی دسترسی توکن:** `verifyTokenScopes()` هدر `X-OAuth-Scopes` پاسخ را می‌خواند تا اگر توکن دسترسی
`workflow` را نداشته باشد به کاربر هشدار دهد (بدون این دسترسی، بارگذاری فایل ورک‌فلو شکست می‌خورد).
توکن‌های fine-grained این هدر را ندارند و «غیرقابل‌تشخیص» گزارش می‌شوند.

### ۴.۶. ایجنت کدنویس خودکار (GitHub Actions)

**فایل:** `app/src/main/assets/agent-issue-worker.yml`

این جالب‌ترین قسمت سیستم است: وقتی یک Issue با برچسب `by-agent` باز/برچسب‌گذاری می‌شود، یک ورک‌فلوی
GitHub Actions فعال می‌شود که **یک ایجنت کدنویس خودکار (Gemini CLI)** را اجرا می‌کند تا وظیفه را
واقعاً در کد انجام دهد. مراحل ورک‌فلو:

<div dir="ltr">

```
issue labeled "by-agent"
        │
        ▼
1. Checkout شاخهٔ پیش‌فرض
2. نصب JDK 17 و Node 20 و Gemini CLI
3. ساخت پرامپت از عنوان و بدنهٔ Issue (به‌صورت امن، در متغیر محیطی)
4. پین کردن مدل Gemini و خاموش کردن مسیریابی خودکار مدل
5. اجرای Gemini CLI با --yolo (ویرایش مستقیم فایل‌ها)
6. گِیت ساخت: ./gradlew assembleDebug
        ├── اگر ساخت شکست خورد → کامنت خطا + شکست جاب (چیزی push نمی‌شود)
        └── اگر موفق بود ↓
7. commit، push به شاخهٔ پیش‌فرض، تعویض برچسب by-agent → done،
   و کامنت تأیید با SHA کامیت
```

</div>

**نکات امنیتی و طراحی مهم در این ورک‌فلو:**

- **جلوگیری از تزریق فرمان:** عنوان و بدنهٔ Issue ورودی نامعتمد کاربر هستند، پس در **متغیرهای محیطی**
  نگه داشته می‌شوند و هرگز مستقیماً در عبارت شل/YAML درج نمی‌شوند.
- **سریال‌سازی اجراها:** با `concurrency.group: agent-worker` تضمین می‌شود دو Issue هم‌زمان روی شاخهٔ
  پیش‌فرض push نکنند.
- **گِیت ساخت:** تغییرات ایجنت فقط زمانی push می‌شوند که `./gradlew assembleDebug` موفق باشد؛ در غیر
  این صورت هیچ چیز push نمی‌شود و کاربر کامنت خطا می‌بیند.
- **ایجنت git نمی‌زند:** ایجنت فقط فایل‌ها را ویرایش می‌کند؛ commit و push را خود ورک‌فلو انجام می‌دهد.
- **پین کردن مدل:** «مسیریاب هوشمند مدل» Gemini CLI (که به‌طور پیش‌فرض روشن است) می‌تواند فلگ `--model`
  را نادیده بگیرد و به یک مدل با سهمیهٔ روزانهٔ بسیار کم مسیریابی کند. برای همین `settings.json` با
  `useModelRouter: false` نوشته می‌شود تا مدل پین‌شده واقعاً اعمال شود.
- ایجنت به `GEMINI_API_KEY` (همان Secret که MIA در مخزن ذخیره کرده) نیاز دارد تا کار کند.

---

## ۵. لایهٔ شبکه (NetworkModule)

**فایل:** `network/NetworkModule.kt`

این یک **تزریق وابستگی دستی و سبک** است (برای اپی به این اندازه نیازی به فریمورک نیست). یک `object`
تک‌نمونه که همهٔ کلاینت‌های Retrofit و اینترسپتورها را می‌سازد. با `NetworkModule.init(context)` از
`MIAApplication.onCreate()` مقداردهی می‌شود.

**کلاینت‌های Retrofit (همه به‌صورت `by lazy`):**

| کلاینت | Base URL | احراز هویت |
|--------|----------|------------|
| `supabaseAuthApi` | `<SUPABASE_URL>/auth/v1/` | فقط anon key (کاربر هنوز توکن ندارد) |
| `supabaseApi` | `<SUPABASE_URL>/rest/v1/` | توکن کاربر جاری (یا anon در حالت خروج) |
| `geminiApi` | `generativelanguage.googleapis.com` | کلید در هر فراخوانی (هدر `x-goog-api-key`) |
| `gitHubApi` | `api.github.com` | توکن گیت‌هاب (Bearer) |
| `openRouterApi` | `api.gapgpt.app` | Bearer — **میراثی، دیگر استفاده نمی‌شود** |

**اینترسپتورهای مهم:**

- **`supabaseRestInterceptor`**: اگر نشست فعال باشد `accessToken` کاربر را می‌فرستد (تا RLS هر ردیف را
  به او محدود کند)، وگرنه به anon key برمی‌گردد. هدرهای `apikey`, `Authorization`, و
  `Prefer: return=representation` را اضافه می‌کند.
- **`gitHubAuthInterceptor`**: هدرهای `Authorization`, `Accept: application/vnd.github+json`, و نسخهٔ API را می‌افزاید.
- **`loggingInterceptor`**: در دیباگ سطح `BODY`، در ریلیز `NONE`.
- **`chuckerInterceptor`**: بازرس HTTP درون‌برنامه‌ای؛ در ریلیز با نسخهٔ no-op جایگزین و کاملاً بی‌اثر می‌شود.

**نکتهٔ مقاومت:** اگر `SUPABASE_URL` تنظیم نشده باشد، به‌جای کرش کردن اپ، یک آدرس جانگهدار معتبر
(`https://supabase-not-configured.invalid`) استفاده می‌شود تا فراخوانی‌ها به‌صورت خطای شبکهٔ معمولی
(که با `runCatching` مدیریت می‌شود) شکست بخورند، نه در زمان راه‌اندازی.

---

## ۶. امنیت و مدیریت کلیدها

**فایل‌ها:** `security/SecretStore.kt`، `security/AndroidCrypto.kt`

- **`SecretStore`**: کلید Gemini که کاربر در زمان اجرا وارد می‌کند را در **`EncryptedSharedPreferences`**
  (رمزنگاری AES-256-GCM با کلید نگه‌داری‌شده در Android Keystore) ذخیره می‌کند. مقدار زمان‌اجرا بر
  پیش‌فرض زمان‌ساخت (`BuildConfig.GEMINI_API_KEY`) اولویت دارد. همچنین گزینهٔ «سپردن به ایجنت به‌صورت
  پیش‌فرض» را نگه می‌دارد.
- **`LibsodiumSecretEncryptor`**: برای ذخیرهٔ کلید Gemini به‌عنوان Secret گیت‌هاب، باید مقدار را طبق
  استاندارد API اکشنز رمز کرد: یک **libsodium sealed box** (`crypto_box_seal`) در برابر کلید عمومی
  Curve25519 مخزن، به‌صورت Base64. این کار در دستگاه انجام می‌شود، پس کلید هرگز به‌صورت متن ساده به
  گیت‌هاب نمی‌رود.

**تمایز مهم:**
- نشست کاربر (توکن Supabase) در `SharedPreferences` **معمولی** ذخیره می‌شود.
- کلید Gemini در `EncryptedSharedPreferences` **رمزنگاری‌شده** ذخیره می‌شود.

**کلیدهای زمان‌ساخت** (از `local.properties` یا متغیرهای محیطی، به `BuildConfig` تزریق می‌شوند):
`GAPGPT_API_KEY`، `GITHUB_TOKEN`، `GEMINI_API_KEY`، `SUPABASE_URL`، `SUPABASE_ANON_KEY`.

---

## ۷. لایهٔ رابط کاربری (UI)

**فایل‌ها:** `ui/main/`، `ui/auth/`، `ui/theme/`

- کاملاً با **Jetpack Compose** و **Material 3** ساخته شده و **راست‌به‌چپ (RTL)** است
  (با `CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl)`).
- **`MainActivity`** بر اساس وضعیت ورود بین `LoginScreen` و `MainScreen` سوییچ می‌کند.
- **`LoginScreen`**: فیلد نام کاربری/رمز، دکمه‌های ورود و ثبت‌نام.
- **`MainScreen`**: نوار بالا با دکمهٔ تنظیمات و خروج، لیست پروژه‌ها (`ProjectCard`)، و `MicFab` در مرکز پایین.
- **`MicFab`**: تنها عنصر برجستهٔ کنترل صوتی — در حالت استراحت **فیروزه‌ای نئون**، هنگام شنیدن **سبز نئون**
  و پالس‌زننده (بر اساس دامنهٔ RMS میکروفون)، و در حالت پردازش یک نشانگر چرخان.
- **`SettingsDialog`**: کلید API جمینای (ذخیرهٔ رمزنگاری‌شده) و سوییچ «سپردن تسک‌های جدید به ایجنت».
- **الگوی وضعیت:** ViewModelها وضعیت را با `StateFlow<UiState>` منتشر می‌کنند و پیام‌های یک‌بارمصرف
  (Snackbar) را با `Channel` می‌فرستند (تا روی چرخش صفحه دوباره نمایش داده نشوند).

**سه حالت ضبط (`RecordingState`):**
- `Idle` (آماده) → لمس، شروع ضبط.
- `Listening` (در حال شنیدن) → لمس دوم، توقف و پردازش.
- `Processing` (در حال پردازش) → لمس‌ها نادیده گرفته می‌شوند تا دستور قبلی تمام شود.

---

## ۸. مدل‌های داده

**فایل:** `data/model/`

**`VoiceCommandIntent`** — دقیقاً آینهٔ همان اسکیمای JSON که به مدل دستور داده شده:

<div dir="ltr">

```kotlin
enum class ActionType { CREATE_PROJECT, DELETE_PROJECT, ADD_TASK, REMOVE_TASK }

data class VoiceCommandIntent(
    val actionType: ActionType,      // "action_type"
    val projectName: String,         // "project_name" — همیشه لازم
    val taskTitle: String? = null,   // "task_title"
    val taskDescription: String? = null, // "task_description" — بدنهٔ Issue برای ایجنت
    val dueDate: String? = null      // "due_date" — نرمال‌شده به YYYY-MM-DD
)
```

</div>

**`Project` و `Task`** — مدل‌های خواندنی که در صفحهٔ اصلی نمایش داده می‌شوند و با ساختار جدول‌های
سوپابیس مطابقت دارند (پروژه شامل فهرست وظایفش).

---

## ۹. پیکربندی و راه‌اندازی

برای اجرای پروژه، این کلیدها باید در `local.properties` (یا متغیرهای محیطی) تنظیم شوند:

<div dir="ltr">

```properties
SUPABASE_URL=https://<your-project>.supabase.co
SUPABASE_ANON_KEY=<anon-key>
GITHUB_TOKEN=<token با دسترسی repo + workflow>
GEMINI_API_KEY=<کلید Gemini؛ اختیاری — می‌توان از تنظیمات اپ هم وارد کرد>
GAPGPT_API_KEY=<میراثی؛ برای مسیر فعلی لازم نیست>
```

</div>

**سمت Supabase:**
1. دو جدول `projects` و `tasks` را با ساختار بالا بسازید.
2. Row Level Security را فعال کنید تا هر کاربر فقط ردیف‌های خودش را ببیند.
3. در تنظیمات Auth، در صورت تمایل «Confirm email» را غیرفعال کنید تا ثبت‌نام بلافاصله وارد شود.

**سمت GitHub:**
- توکنی با دسترسی `repo` و `workflow` بسازید (بدون `workflow`، بارگذاری فایل ورک‌فلو شکست می‌خورد).

**سمت Gemini:**
- کلید API را یا در `local.properties` قرار دهید یا از دیالوگ تنظیمات اپ وارد کنید. همین کلید هم برای
  استخراج نیت و هم به‌عنوان Secret مخزن‌ها استفاده می‌شود.

**مجوزهای اندروید:** `INTERNET` و `RECORD_AUDIO`. همچنین بلوک `<queries>` در مانیفست لازم است تا
سرویس تشخیص گفتار در API 30+ قابل‌دیدن باشد (میراث مسیر قدیمی).

---

## ۱۰. نکات، محدودیت‌ها و بخش‌های میراثی

- **OpenRouter / GapGPT میراثی است:** کلاینت `openRouterApi` و پرامپت `IntentClassifierPrompt` هنوز در
  کد هستند اما دیگر برای دسته‌بندی استفاده نمی‌شوند؛ `GeminiVoiceIntentClassifier` جای آن‌ها را گرفته.
  همچنین کلاس‌های `VoiceIntentClassifier` و `SpeechRecognizerManager` حذف شده‌اند (مسیر قدیمیِ
  STT-روی-دستگاه + دسته‌بند متنی).
- **جست‌وجو بر اساس نام:** چون مدل فقط نام می‌دهد نه شناسه، تطبیق پروژه/وظیفه بر اساس نام است؛ برای
  مقاومت در برابر تفاوت‌های املای فارسی، نرمال‌سازی سمت کلاینت انجام می‌شود.
- **اثرات جانبی GitHub بهترین‌تلاش‌اند:** شکست GitHub هرگز عملیات سوپابیس را خراب نمی‌کند.
- **بدون فریمورک DI:** همه‌چیز از طریق `NetworkModule` (یک `object` سراسری) سیم‌کشی می‌شود.
- **بازرس شبکه:** در بیلد دیباگ، **Chucker** همهٔ درخواست/پاسخ‌ها را نشان می‌دهد؛ در ریلیز کاملاً بی‌اثر است.
- **حداقل SDK:** ۲۴؛ **هدف SDK:** ۳۶.

---

<div align="center">

**MIA** — صحبت کن، بفهم، انجام بده.

</div>

</div>
