package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.data.model.ActionType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The recovery layer both classifiers lean on. Gemini is pinned to a JSON response type, but the
 * free OpenRouter model powering typed commands has no such guarantee — a fenced or chatty
 * answer must still produce the right intents rather than failing a command the model got right.
 */
class IntentJsonTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val intentArray =
        """[{"action_type":"create_project","project_name":"وبسایت","task_title":null,"task_description":null,"due_date":null}]"""

    @Test
    fun `parses a bare array`() {
        val intents = IntentJson.parseIntents(json, intentArray)
        assertEquals(1, intents.size)
        assertEquals(ActionType.CREATE_PROJECT, intents.first().actionType)
        assertEquals("وبسایت", intents.first().projectName)
    }

    @Test
    fun `parses an array wrapped in a json code fence`() {
        val intents = IntentJson.parseIntents(json, "```json\n$intentArray\n```")
        assertEquals(1, intents.size)
        assertEquals("وبسایت", intents.first().projectName)
    }

    @Test
    fun `parses an array a chatty model buried in prose`() {
        val raw = "Sure! Here is the JSON you asked for:\n$intentArray\nLet me know if you need more."
        val intents = IntentJson.parseIntents(json, raw)
        assertEquals(1, intents.size)
        assertEquals(ActionType.CREATE_PROJECT, intents.first().actionType)
    }

    @Test
    fun `tolerates a bare object instead of the documented array`() {
        val raw =
            """{"action_type":"add_task","project_name":"وبسایت","task_title":"طراحی لوگو","task_description":"## شرح\nلوگو","due_date":"2026-09-04"}"""
        val intents = IntentJson.parseIntents(json, raw)
        assertEquals(1, intents.size)
        assertEquals(ActionType.ADD_TASK, intents.first().actionType)
        assertEquals("طراحی لوگو", intents.first().taskTitle)
        assertEquals("2026-09-04", intents.first().dueDate)
    }

    @Test
    fun `keeps a bare object whose description mentions a bracket`() {
        val raw =
            """{"action_type":"add_task","project_name":"وبسایت","task_title":"نمودار","task_description":"## شرح\nآرایه [1, 2] را رسم کن","due_date":null}"""
        val intents = IntentJson.parseIntents(json, raw)
        assertEquals(1, intents.size)
        assertEquals("نمودار", intents.single().taskTitle)
    }

    @Test
    fun `keeps the whole array when a task description itself contains brackets`() {
        val raw = """[{"action_type":"add_task","project_name":"وبسایت","task_title":"نمودار","task_description":"## شرح\nآرایه [1, 2] را رسم کن","due_date":null},{"action_type":"add_task","project_name":"وبسایت","task_title":"جدول","task_description":null,"due_date":null}]"""
        val intents = IntentJson.parseIntents(json, raw)
        assertEquals(2, intents.size)
        assertEquals("جدول", intents[1].taskTitle)
    }
}
