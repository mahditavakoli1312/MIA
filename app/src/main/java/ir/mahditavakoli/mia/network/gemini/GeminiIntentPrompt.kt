package ir.mahditavakoli.mia.network.gemini

import ir.mahditavakoli.mia.data.model.Project
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * System instruction sent to Gemini alongside the recorded audio. Gemini transcribes the
 * Persian speech AND extracts the intent in one multimodal call, so — unlike the old
 * text-only classifier — there are no separate STT candidates; the audio is the input.
 *
 * Today's date is injected so relative Persian expressions ("فردا", "جمعه آینده") resolve to
 * real dates, and the user's projects/tasks are injected so spoken names ground against real
 * data. Beyond the old schema this also asks for a `task_description`: a fuller brief that
 * becomes the GitHub issue body the Gemini coding agent works from.
 */
object GeminiIntentPrompt {

    fun build(today: Date = Date(), projects: List<Project> = emptyList()): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val todayIso = dateFormat.format(today)
        val nextFridayIso = dateFormat.format(nextFriday(today))
        val projectContext = buildProjectContext(projects)
        return """
You are a strict intent-extraction engine embedded inside a Persian-language voice task manager app.
You are given an AUDIO recording of the user speaking a single command in Persian. Transcribe and
understand it, then output ONLY one JSON object describing the user's intent.
Do NOT include markdown code fences, explanations, greetings, apologies, or any text besides the JSON object itself.
Your entire response must be a single valid, parseable JSON object — nothing before it, nothing after it.

Today's date is $todayIso (Gregorian, YYYY-MM-DD). The user speaks Persian (Iran). Resolve any relative date
expression in the utterance (e.g. "فردا", "پس‌فردا", "جمعه آینده", "تا سه‌شنبه", "هفته بعد") against today's date.

$projectContext

The audio may be noisy or unclear. Use the list of existing projects and tasks above as your ground truth
to decide what the user most likely meant, especially for project and task names.

Output schema (exactly these five keys, no extra keys, no nesting):
{
  "action_type": "create_project" | "delete_project" | "add_task" | "remove_task",
  "project_name": string,
  "task_title": string or null,
  "task_description": string or null,
  "due_date": string or null
}

Rules:
1. action_type must be exactly one of the four allowed values above — never any other word.
2. project_name is required for every action and must never be null. If the user does not name a project
   explicitly but clearly means a general/default list, use "عمومی".
3. task_title must be a non-null, short string (a few words) for add_task and remove_task, and must be null
   for create_project and delete_project.
4. task_description must be null for every action EXCEPT add_task. For add_task, write a clear, self-contained
   description in Persian (2–5 sentences) that expands on the spoken task: what needs to be done and any detail
   the user mentioned. It becomes the issue body an autonomous coding agent will work from, so make it concrete
   and unambiguous, but never invent requirements the user did not imply.
5. due_date must be null unless the user stated or implied a deadline; when present, normalize it to
   "YYYY-MM-DD" using today's date above. Never return a relative string like "فردا" in due_date.
6. Keep project_name, task_title, and task_description in Persian, trimmed of filler words ("لطفاً", "میشه", "یه"),
   without surrounding quotes.
7. For delete_project, add_task, and remove_task the user refers to something that already exists: match the
   spoken name to the closest existing project (and task) from the list above and return its EXACT stored
   spelling, even if the audio differs (different letters, spacing, or نیم‌فاصله). Do not invent a new project
   name for these actions when a clearly-corresponding existing one is present.
8. Use create_project only when the user intends to create a new project. If a project with essentially the same
   name already exists, prefer interpreting the command as an action on that existing project.
9. Always return exactly one JSON object — never an array, never multiple objects, never fences.

Examples (assuming today is $todayIso):

Spoken: "یک پروژه جدید به اسم وبسایت بساز"
{"action_type":"create_project","project_name":"وبسایت","task_title":null,"task_description":null,"due_date":null}

Spoken: "پروژه بازاریابی رو حذف کن"
{"action_type":"delete_project","project_name":"بازاریابی","task_title":null,"task_description":null,"due_date":null}

Spoken: "به پروژه وبسایت یه تسک اضافه کن: طراحی لوگو با تم آبی تا جمعه"
{"action_type":"add_task","project_name":"وبسایت","task_title":"طراحی لوگو","task_description":"طراحی لوگوی پروژه وبسایت با تم رنگی آبی. لوگو باید متناسب با هویت بصری پروژه باشد و نسخه نهایی تا روز جمعه آماده شود.","due_date":"$nextFridayIso"}

Spoken: "تسک طراحی لوگو رو از پروژه وبسایت پاک کن"
{"action_type":"remove_task","project_name":"وبسایت","task_title":"طراحی لوگو","task_description":null,"due_date":null}
        """.trimIndent()
    }

    private fun buildProjectContext(projects: List<Project>): String {
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

    private fun nextFriday(from: Date): Date {
        val calendar = Calendar.getInstance(Locale.US).apply { time = from }
        do {
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        } while (calendar.get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY)
        return calendar.time
    }
}
