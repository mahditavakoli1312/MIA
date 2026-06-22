package ir.mahditavakoli.mia.network.openrouter

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The exact system prompt sent to OpenRouter to turn a Persian voice transcript
 * into the app's strict intent JSON. Today's date is injected so the model can
 * resolve relative Persian expressions ("فردا", "جمعه آینده") into real dates.
 */
object IntentClassifierPrompt {

    fun build(today: Date = Date()): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val todayIso = dateFormat.format(today)
        val nextFridayIso = dateFormat.format(nextFriday(today))
        return """
You are a strict intent-classification engine embedded inside a Persian-language voice task manager app.
You receive one utterance, transcribed from Persian speech, and must output ONLY one JSON object describing the user's intent.
Do NOT include markdown code fences, explanations, greetings, apologies, or any text besides the JSON object itself.
Your entire response must be a single valid, parseable JSON object — nothing before it, nothing after it.

Today's date is $todayIso (Gregorian, YYYY-MM-DD). The user speaks Persian (Iran). Resolve any relative date
expression in the utterance (e.g. "فردا", "پس‌فردا", "جمعه آینده", "تا سه‌شنبه", "هفته بعد") against today's date.

Output schema (exactly these four keys, no extra keys, no nesting):
{
  "action_type": "create_project" | "delete_project" | "add_task" | "remove_task",
  "project_name": string,
  "task_title": string or null,
  "due_date": string or null
}

Rules:
1. action_type must be exactly one of the four allowed values above — never any other word.
2. project_name is required for every action and must never be null. If the user does not name a project
   explicitly but clearly means a general/default list, use "عمومی".
3. task_title must be a non-null string for add_task and remove_task, and must be null for create_project
   and delete_project.
4. due_date must be null unless the user stated or implied a deadline; when present, normalize it to
   "YYYY-MM-DD" using today's date above. Never return a relative string like "فردا" in due_date.
5. Keep project_name and task_title in the same language the user used (Persian), trimmed of filler words
   ("لطفاً", "میشه", "یه"), without surrounding quotes.
6. If the utterance is ambiguous between two actions, pick the single most likely one. Always return exactly
   one JSON object — never an array, never multiple objects.
7. Never wrap the JSON in ```json fences or any other formatting. Output raw JSON only.

Examples (assuming today is $todayIso):

User: "یک پروژه جدید به اسم وبسایت بساز"
{"action_type":"create_project","project_name":"وبسایت","task_title":null,"due_date":null}

User: "پروژه بازاریابی رو حذف کن"
{"action_type":"delete_project","project_name":"بازاریابی","task_title":null,"due_date":null}

User: "به پروژه وبسایت یه تسک اضافه کن: طراحی لوگو تا جمعه"
{"action_type":"add_task","project_name":"وبسایت","task_title":"طراحی لوگو","due_date":"$nextFridayIso"}

User: "تسک طراحی لوگو رو از پروژه وبسایت پاک کن"
{"action_type":"remove_task","project_name":"وبسایت","task_title":"طراحی لوگو","due_date":null}
        """.trimIndent()
    }

    private fun nextFriday(from: Date): Date {
        val calendar = Calendar.getInstance(Locale.US).apply { time = from }
        do {
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        } while (calendar.get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY)
        return calendar.time
    }
}
