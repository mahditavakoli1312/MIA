package ir.mahditavakoli.mia.network.openrouter

import ir.mahditavakoli.mia.data.model.Project
import ir.mahditavakoli.mia.network.prompt.IntentPromptCore
import java.util.Date

/**
 * Stage two of the typed-command pipeline: the refined prompt from [PromptRefinementPrompt] in,
 * the app's strict intent JSON out — the same array of intents the voice path produces, since
 * schema, rules and examples all come from [IntentPromptCore].
 *
 * The only difference from the audio prompt is what the input is and how it tends to be wrong:
 * typed Persian misses spacing and نیم‌فاصله where speech misses whole words.
 *
 * @see ir.mahditavakoli.mia.network.gemini.GeminiIntentPrompt
 */
object TextIntentPrompt {

    fun build(today: Date = Date(), projects: List<Project> = emptyList()): String =
        IntentPromptCore.build(
            intro = """
You are a strict intent-extraction engine embedded inside a Persian-language task manager app.
You are given a TEXT command written by the user in Persian, already cleaned up by a pre-processing step.
Understand it, then output ONLY one JSON ARRAY describing the user's intent(s).
Do NOT include markdown code fences, explanations, greetings, apologies, or any text besides the JSON array itself.
Your entire response must be a single valid, parseable JSON array — nothing before it, nothing after it.
            """.trimIndent(),
            groundingNote = """
The text was typed on a phone keyboard, so it may still contain typos, missing نیم‌فاصله, or inconsistent
spacing. Use the list of existing projects and tasks above as your ground truth to decide what the user
most likely meant, especially for project and task names.
            """.trimIndent(),
            exampleLabel = "Command",
            today = today,
            projects = projects
        )
}
