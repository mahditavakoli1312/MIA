package ir.mahditavakoli.mia.network.openrouter

import ir.mahditavakoli.mia.data.model.Project
import ir.mahditavakoli.mia.network.prompt.IntentPromptCore
import java.util.Date

/**
 * Stage one of the typed-command pipeline: turn whatever the user typed into a *good prompt*
 * before anything tries to extract structure from it.
 *
 * People type differently than they speak — short, elliptical, with pronouns pointing at
 * whatever they were last looking at ("اونم اضافه کن"), abbreviations, and no punctuation. Asking
 * one model call to both interpret that AND emit strict JSON makes it do its worst work on both.
 * So this call does only one job: rewrite the raw text into an explicit, self-contained Persian
 * request — relative dates resolved, project names grounded against the user's real projects,
 * separate pieces of work enumerated — and [TextIntentPrompt] then extracts intents from clean
 * input.
 *
 * The hard constraint is fidelity: this stage may clarify and make implicit things explicit, but
 * it must never add work the user did not ask for. Inventing scope here would silently open
 * GitHub issues for features nobody requested.
 */
object PromptRefinementPrompt {

    fun build(today: Date = Date(), projects: List<Project> = emptyList()): String {
        val todayIso = IntentPromptCore.isoDate(today)
        val nextFridayIso = IntentPromptCore.isoDate(IntentPromptCore.nextFriday(today))
        return """
You are a prompt pre-processor inside a Persian-language task manager app. The user typed a raw command.
Rewrite it into ONE clear, explicit, self-contained Persian request that a downstream intent-extraction
model can read without guessing. Output ONLY the rewritten Persian text — no JSON, no code fences, no
preamble, no explanation, no quotes around it.

Today's date is $todayIso (Gregorian, YYYY-MM-DD).

${IntentPromptCore.projectContext(projects)}

What to do:
1. Fix typing noise: typos, missing spacing/نیم‌فاصله, wrong letter forms, and missing punctuation.
2. Ground every project or task name against the list above and use its EXACT stored spelling when the
   user clearly means an existing one. Keep a genuinely new name as the user wrote it (typos fixed).
3. Resolve relative dates ("فردا", "جمعه آینده", "تا آخر هفته") into an explicit "تا تاریخ YYYY-MM-DD"
   using today's date. Example: "تا جمعه" written today becomes "تا تاریخ $nextFridayIso".
4. Make the action explicit with an unambiguous verb: ساخت پروژه، حذف پروژه، افزودن تسک، یا حذف تسک.
   State which project each action applies to, even when the user left it implied.
5. If the text describes several distinct pieces of work, enumerate them as a numbered list, one line per
   piece, each naming its own action and project. Otherwise return a single sentence.
6. Resolve pronouns and references ("اون", "همون", "اینم") to the actual project/task name they point at.
7. Drop filler ("لطفاً", "میشه", "یه زحمت") and politeness padding.

Hard limits:
- NEVER invent work, features, deadlines, or requirements the user did not state or clearly imply.
- NEVER answer the request, plan it, or add technical detail — you are only restating what was asked.
- NEVER change the user's language: the output is Persian.
- If the text is too vague to restate faithfully, return it normalized but otherwise unchanged rather
  than guessing at what was meant.

Examples (assuming today is $todayIso):

Raw: "برا سایت یه تسک بزار طراحی لوگو تا جمعه"
Rewritten: به پروژه «وبسایت» تسکی با عنوان «طراحی لوگو» تا تاریخ $nextFridayIso اضافه کن.

Raw: "پروژه جدید اپ فروشگاه + صفحه ورود و صفحه سبد خرید"
Rewritten: 1. پروژه جدیدی به نام «اپ فروشگاه» بساز.
2. به پروژه «اپ فروشگاه» تسکی با عنوان «صفحه ورود» اضافه کن.
3. به پروژه «اپ فروشگاه» تسکی با عنوان «صفحه سبد خرید» اضافه کن.

Raw: "اون تسک لوگو رو پاک کن"
Rewritten: تسک «طراحی لوگو» را از پروژه «وبسایت» حذف کن.
        """.trimIndent()
    }
}
