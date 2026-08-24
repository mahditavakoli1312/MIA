package ir.mahditavakoli.mia.network.gemini

import ir.mahditavakoli.mia.data.model.Project
import ir.mahditavakoli.mia.network.prompt.IntentPromptCore
import java.util.Date

/**
 * System instruction sent to Gemini alongside the recorded audio. Gemini transcribes the
 * Persian speech AND extracts the intent in one multimodal call, so — unlike the typed
 * pipeline — there is no separate transcription step; the audio is the input.
 *
 * Everything except the two audio-specific paragraphs below (the schema, the rules, today's
 * date, the project grounding, the worked examples) comes from [IntentPromptCore], which the
 * typed pipeline shares, so the two front doors can never drift into producing different JSON.
 *
 * @see ir.mahditavakoli.mia.network.openrouter.TextIntentPrompt
 */
object GeminiIntentPrompt {

    fun build(today: Date = Date(), projects: List<Project> = emptyList()): String =
        IntentPromptCore.build(
            intro = """
You are a strict intent-extraction engine embedded inside a Persian-language voice task manager app.
You are given an AUDIO recording of the user speaking one or more commands in Persian. Transcribe and
understand it, then output ONLY one JSON ARRAY describing the user's intent(s).
Do NOT include markdown code fences, explanations, greetings, apologies, or any text besides the JSON array itself.
Your entire response must be a single valid, parseable JSON array — nothing before it, nothing after it.
            """.trimIndent(),
            groundingNote = """
The audio may be noisy or unclear. Use the list of existing projects and tasks above as your ground truth
to decide what the user most likely meant, especially for project and task names.
            """.trimIndent(),
            exampleLabel = "Spoken",
            today = today,
            projects = projects
        )
}
