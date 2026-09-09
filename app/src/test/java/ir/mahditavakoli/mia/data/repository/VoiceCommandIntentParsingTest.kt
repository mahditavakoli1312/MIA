package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.data.model.ActionType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JSON contract both front doors must satisfy: the model returns an ARRAY of intents so one
 * command can split into multiple issues, and each add_task carries a multi-section Persian
 * Markdown brief. [IntentJsonTest] covers recovering that array from a messy answer; this covers
 * what the array itself has to decode into.
 */
class VoiceCommandIntentParsingTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun parseIntents(raw: String) = IntentJson.parseIntents(json, raw)

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

    /**
     * The task-state actions the prompt was extended with. They are the actions that can close
     * the loop on a task, so a wire name that drifts from [ActionType]'s @SerialName would leave
     * every "ببندش" command failing to parse — with no compiler error to catch it.
     */
    @Test
    fun `the task-state actions decode from their snake_case wire names`() {
        val raw = """
            [
              {"action_type":"complete_task","project_name":"وبسایت","task_title":"طراحی لوگو","task_description":null,"due_date":null},
              {"action_type":"reopen_task","project_name":"وبسایت","task_title":"طراحی لوگو","task_description":null,"due_date":null},
              {"action_type":"set_due_date","project_name":"وبسایت","task_title":"طراحی لوگو","task_description":null,"due_date":"2026-03-06"}
            ]
        """.trimIndent()

        val intents = parseIntents(raw)

        assertEquals(
            listOf(ActionType.COMPLETE_TASK, ActionType.REOPEN_TASK, ActionType.SET_DUE_DATE),
            intents.map { it.actionType }
        )
        // All three name a task; none of them carries a brief — that is add_task's alone.
        assertTrue(intents.all { it.taskTitle == "طراحی لوگو" })
        assertTrue(intents.all { it.taskDescription == null })
        assertEquals("2026-03-06", intents[2].dueDate)
    }
}
