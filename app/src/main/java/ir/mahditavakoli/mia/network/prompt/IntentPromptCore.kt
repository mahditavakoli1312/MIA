package ir.mahditavakoli.mia.network.prompt

import ir.mahditavakoli.mia.data.model.Project
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The part of the intent-extraction instruction that is identical no matter how the command
 * reached the app: the output schema, the rules, and the worked examples.
 *
 * MIA has two front doors — the mic (Gemini, multimodal, audio in) and the text field
 * (OpenRouter `minimax/minimax-m3:free`, text in) — and both must produce byte-identical intent JSON,
 * because a single [ir.mahditavakoli.mia.data.repository.IntentExecutionRepository] executes
 * whatever comes back. Keeping the schema and rules in one place is what guarantees that; only
 * the few modality-specific lines (what the input *is*, and how it is likely to be wrong) are
 * supplied by the caller.
 *
 * @see ir.mahditavakoli.mia.network.gemini.GeminiIntentPrompt
 * @see ir.mahditavakoli.mia.network.openrouter.TextIntentPrompt
 */
object IntentPromptCore {

    /**
     * @param intro what the model is being given, in the caller's own words (audio vs. text).
     * @param groundingNote how this input modality typically goes wrong, and what to do about it.
     * @param exampleLabel the word that prefixes each worked example ("Spoken" / "Command").
     */
    fun build(
        intro: String,
        groundingNote: String,
        exampleLabel: String,
        today: Date = Date(),
        projects: List<Project> = emptyList()
    ): String {
        val todayIso = isoDate(today)
        val nextFridayIso = isoDate(nextFriday(today))
        return """
$intro

Today's date is $todayIso (Gregorian, YYYY-MM-DD). The user speaks Persian (Iran). Resolve any relative date
expression in the command (e.g. "فردا", "پس‌فردا", "جمعه آینده", "تا سه‌شنبه", "هفته بعد") against today's date.

${projectContext(projects)}

$groundingNote

Output: a JSON ARRAY of one or more intent objects. Even for a single simple command, return an array holding
exactly one object. Each object has exactly these five keys, no extra keys, no nesting:
[
  {
    "action_type": "create_project" | "delete_project" | "add_task" | "remove_task",
    "project_name": string,
    "task_title": string or null,
    "task_description": string or null,
    "due_date": string or null
  }
]

Rules:
1. action_type must be exactly one of the four allowed values above — never any other word.
2. project_name is required for every object and must never be null. If the user does not name a project
   explicitly but clearly means a general/default list, use "عمومی".
3. task_title must be a non-null, short string (a few words) for add_task and remove_task, and must be null
   for create_project and delete_project.
4. task_description must be null for every action EXCEPT add_task. For add_task it must be a comprehensive,
   self-contained brief in Persian, formatted as Markdown, that an autonomous coding agent can implement from
   without further questions. Include ONLY the sections that genuinely apply to the task, keeping these exact
   headings and order:
     ## شرح
     A precise description of what must be built/done and the requirements the user stated or clearly implied.
     ## مشخصات فنی
     A bulleted list of concrete technical specifications: components, data, states, validation, edge cases,
     and acceptance criteria — grounded in what the user asked for plus standard best practices.
     ## راهنمای طراحی (UI/UX)
     A bulleted list of interface and experience guidance: layout, key elements, states (loading/empty/error),
     and interaction/accessibility notes. Omit this whole section for tasks with no user-facing surface.
   Expand the request into real detail, but never invent scope or hard requirements the user did not imply.
5. due_date must be null unless the user stated or implied a deadline; when present, normalize it to
   "YYYY-MM-DD" using today's date above. Never return a relative string like "فردا" in due_date.
6. Keep project_name, task_title, and all task_description prose in Persian, trimmed of filler words
   ("لطفاً", "میشه", "یه"), without surrounding quotes.
7. For delete_project, add_task, and remove_task the user refers to something that already exists: match the
   named project (and task) to the closest existing one from the list above and return its EXACT stored
   spelling, even if the input differs (different letters, spacing, or نیم‌فاصله). Do not invent a new project
   name for these actions when a clearly-corresponding existing one is present.
8. Use create_project only when the user intends to create a new project. If a project with essentially the same
   name already exists, prefer interpreting the command as an action on that existing project.
9. Break complex requests down. If the command describes several distinct pieces of work (multiple features,
   screens, flows, or steps), emit MULTIPLE add_task objects — one focused issue per piece, each with its own
   task_title and its own comprehensive task_description — instead of a single overloaded task. Do NOT split a
   single small task artificially. When the user asks to create the project and add work in the same breath,
   emit the create_project object FIRST, then the add_task objects, all referencing the same project_name.
10. Always return a JSON array (never a bare object, never fences), ordered so that any prerequisite
    (e.g. create_project) comes before objects that depend on it.

Examples (assuming today is $todayIso):

$exampleLabel: "یک پروژه جدید به اسم وبسایت بساز"
[{"action_type":"create_project","project_name":"وبسایت","task_title":null,"task_description":null,"due_date":null}]

$exampleLabel: "پروژه بازاریابی رو حذف کن"
[{"action_type":"delete_project","project_name":"بازاریابی","task_title":null,"task_description":null,"due_date":null}]

$exampleLabel: "به پروژه وبسایت یه تسک اضافه کن: طراحی صفحه تماس با ما با فرم و نقشه تا جمعه"
[{"action_type":"add_task","project_name":"وبسایت","task_title":"صفحه تماس با ما","task_description":"## شرح\nطراحی و پیاده‌سازی صفحه «تماس با ما» شامل یک فرم تماس و نمایش موقعیت روی نقشه.\n\n## مشخصات فنی\n- فیلدهای فرم: نام، ایمیل، پیام؛ همه الزامی با اعتبارسنجی سمت کلاینت.\n- اعتبارسنجی فرمت ایمیل و نمایش خطای درون‌خطی برای هر فیلد.\n- ارسال فرم به‌صورت غیرهمزمان با نمایش وضعیت در حال ارسال و پیام موفقیت/خطا.\n- نمایش نقشه با نشانگر روی موقعیت دفتر.\n- ریسپانسیو برای موبایل و دسکتاپ.\n\n## راهنمای طراحی (UI/UX)\n- چیدمان دوبخشی: فرم در یک سمت و نقشه در سمت دیگر (در موبایل زیر هم).\n- دکمه ارسال با وضعیت‌های عادی، غیرفعال، و در حال بارگذاری.\n- پیام‌های موفقیت و خطا به‌صورت واضح و قابل‌دسترس (aria-live).","due_date":"$nextFridayIso"}]

$exampleLabel: "برای اپ فروشگاه صفحه ورود با ایمیل و رمز، ورود با گوگل، و بازیابی رمز عبور رو بساز"
[{"action_type":"add_task","project_name":"اپ فروشگاه","task_title":"صفحه ورود با ایمیل و رمز","task_description":"## شرح\nصفحه ورود کاربر با ایمیل و رمز عبور.\n\n## مشخصات فنی\n- فیلدهای ایمیل و رمز عبور با اعتبارسنجی؛ مدیریت خطای «اطلاعات نامعتبر».\n- وضعیت در حال ورود و غیرفعال‌سازی دکمه هنگام ارسال.\n\n## راهنمای طراحی (UI/UX)\n- فرم ساده و متمرکز با دکمه ورود اصلی و لینک «رمز را فراموش کرده‌اید؟».","due_date":null},{"action_type":"add_task","project_name":"اپ فروشگاه","task_title":"ورود با گوگل","task_description":"## شرح\nافزودن گزینه ورود/ثبت‌نام با حساب گوگل (OAuth).\n\n## مشخصات فنی\n- جریان OAuth گوگل و ساخت/اتصال حساب کاربر پس از بازگشت موفق.\n- مدیریت خطای لغو یا شکست احراز هویت.\n\n## راهنمای طراحی (UI/UX)\n- دکمه استاندارد «ورود با گوگل» زیر فرم ورود با جداکننده «یا».","due_date":null},{"action_type":"add_task","project_name":"اپ فروشگاه","task_title":"بازیابی رمز عبور","task_description":"## شرح\nجریان فراموشی و بازنشانی رمز عبور از طریق ایمیل.\n\n## مشخصات فنی\n- دریافت ایمیل، ارسال لینک بازنشانی، و صفحه تعیین رمز جدید با اعتبارسنجی.\n- انقضای لینک بازنشانی و مدیریت لینک نامعتبر.\n\n## راهنمای طراحی (UI/UX)\n- پیام تأیید ارسال ایمیل و بازخورد واضح برای موفقیت یا خطا.","due_date":null}]

$exampleLabel: "تسک طراحی لوگو رو از پروژه وبسایت پاک کن"
[{"action_type":"remove_task","project_name":"وبسایت","task_title":"طراحی لوگو","task_description":null,"due_date":null}]
        """.trimIndent()
    }

    /** The user's real projects/tasks, so names ground against stored data instead of guesswork. */
    fun projectContext(projects: List<Project>): String {
        if (projects.isEmpty()) {
            return "The user currently has no projects. Any project the user names must be treated as new."
        }
        val lines = projects.joinToString("\n") { project ->
            val taskTitles = project.tasks.map { it.title }.filter { it.isNotBlank() }
            if (taskTitles.isEmpty()) {
                "- ${project.name}"
            } else {
                "- ${project.name} (tasks: ${taskTitles.joinToString(", ")})"
            }
        }
        return "The user's existing projects and their tasks (use these exact names when referring to them):\n$lines"
    }

    fun isoDate(date: Date): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)

    fun nextFriday(from: Date): Date {
        val calendar = Calendar.getInstance(Locale.US).apply { time = from }
        do {
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        } while (calendar.get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY)
        return calendar.time
    }
}
