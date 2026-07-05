package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.data.model.ActionType
import ir.mahditavakoli.mia.data.model.VoiceCommandIntent
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the JSON contract that [GeminiVoiceIntentClassifier.parseIntents] depends on: Gemini
 * now returns an ARRAY of intents so one complex command can split into multiple issues, and each
 * add_task carries a multi-section Persian Markdown brief. The classifier's parse logic is
 * mirrored here (parse element -> array vs bare object) since it is private.
 */
class VoiceCommandIntentParsingTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Same branching as the classifier's private parseIntents. */
    private fun parseIntents(raw: String): List<VoiceCommandIntent> {
        val element = json.parseToJsonElement(raw)
        return if (element is JsonArray) {
            json.decodeFromJsonElement(ListSerializer(VoiceCommandIntent.serializer()), element)
        } else {
            listOf(json.decodeFromJsonElement(VoiceCommandIntent.serializer(), element))
        }
    }

    @Test
    fun `complex command decodes into project plus multiple task issues`() {
        val raw = """
            [
              {"action_type":"create_project","project_name":"اپ فروشگاه","task_title":null,"task_description":null,"due_date":null},
              {"action_type":"add_task","project_name":"اپ فروشگاه","task_title":"صفحه ورود","task_description":"## شرح\nصفحه ورود کاربر.\n\n## مشخصات فنی\n- اعتبارسنجی ایمیل و رمز.\n\n## راهنمای طراحی (UI/UX)\n- فرم متمرکز.","due_date":"2026-07-10"},
              {"action_type":"add_task","project_name":"اپ فروشگاه","task_title":"ورود با گوگل","task_description":"## شرح\nورود با حساب گوگل.","due_date":null}
            ]
        """.trimIndent()

        val intents = parseIntents(raw)

        assertEquals(3, intents.size)
        assertEquals(ActionType.CREATE_PROJECT, intents[0].actionType)
        assertNull(intents[0].taskDescription)

        assertEquals(ActionType.ADD_TASK, intents[1].actionType)
        assertEquals("صفحه ورود", intents[1].taskTitle)
        assertEquals("2026-07-10", intents[1].dueDate)
        // The multi-section Markdown brief must survive decoding intact.
        assertTrue(intents[1].taskDescription!!.contains("## مشخصات فنی"))
        assertTrue(intents[1].taskDescription!!.contains("## راهنمای طراحی (UI/UX)"))

        assertEquals("ورود با گوگل", intents[2].taskTitle)
        assertNull(intents[2].dueDate)
    }

    @Test
    fun `bare object response is tolerated as a single-intent list`() {
        val raw =
            """{"action_type":"create_project","project_name":"وبسایت","task_title":null,"task_description":null,"due_date":null}"""

        val intents = parseIntents(raw)

        assertEquals(1, intents.size)
        assertEquals(ActionType.CREATE_PROJECT, intents.single().actionType)
        assertEquals("وبسایت", intents.single().projectName)
    }
}
