package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.data.model.VoiceCommandIntent
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * Turns whatever a model actually answered into the app's intent list.
 *
 * Both prompts demand a bare JSON array, and Gemini is additionally pinned to
 * `responseMimeType=application/json` — but the OpenRouter free stealth models have no such
 * guarantee, and any model may still wrap its answer in ```json fences or a stray sentence.
 * Recovering from that here is much cheaper than failing a command the model got right.
 */
object IntentJson {

    /**
     * @throws kotlinx.serialization.SerializationException if no parseable intent JSON is present.
     */
    fun parseIntents(json: Json, raw: String): List<VoiceCommandIntent> {
        val element = json.parseToJsonElement(extractJson(raw))
        // The prompt asks for an array, but tolerate a bare object too so a slightly off-spec
        // response still yields a single-intent list instead of failing outright.
        return if (element is JsonArray) {
            json.decodeFromJsonElement(ListSerializer(VoiceCommandIntent.serializer()), element)
        } else {
            listOf(json.decodeFromJsonElement(VoiceCommandIntent.serializer(), element))
        }
    }

    /**
     * The JSON value inside a model answer: code fences stripped, and any leading/trailing prose
     * cut away by slicing from the first bracket to its matching last one. Returns the trimmed
     * input unchanged when it finds no brackets, so the parse error names the real content.
     */
    fun extractJson(raw: String): String {
        val unfenced = raw.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val array = unfenced.indexOf('[')
        val obj = unfenced.indexOf('{')
        // Whichever bracket opens FIRST is the value; picking by preference instead would slice a
        // bare object apart the moment a task_description mentioned "آرایه [۱، ۲]".
        val outermostIsArray = array != -1 && (obj == -1 || array < obj)
        val sliced = if (outermostIsArray) unfenced.slice('[', ']') else unfenced.slice('{', '}')
        return sliced ?: unfenced
    }

    private fun String.slice(open: Char, close: Char): String? {
        val start = indexOf(open)
        val end = lastIndexOf(close)
        return if (start != -1 && end > start) substring(start, end + 1) else null
    }
}
