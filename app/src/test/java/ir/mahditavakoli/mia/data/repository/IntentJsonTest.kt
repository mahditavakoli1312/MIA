package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.data.model.ActionType
import ir.mahditavakoli.mia.data.model.ProjectType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // project_type ----------------------------------------------------------------------------
    //
    // The field decides which AGENTS.md and which design system MIA commits into the new repo,
    // and every repo created before it existed was given the Android pair regardless. These cover
    // the three ways it arrives: named, omitted, and named as something the app does not know.

    @Test
    fun `reads the project type a classifier returned`() {
        for (type in ProjectType.entries) {
            val raw = """[{"action_type":"create_project","project_name":"پ","task_title":null,""" +
                """"task_description":null,"due_date":null,"project_type":"${type.id}"}]"""

            val intents = IntentJson.parseIntents(json, raw)

            assertEquals(type, intents.single().projectType)
        }
    }

    @Test
    fun `an omitted project type is null, not a silent default`() {
        // Which is exactly what an older prompt, or a model that ignored the key, produces. Null
        // reaches the confirmation sheet as "the user has to pick", and that is the point: a
        // default applied here would be invisible.
        val intents = IntentJson.parseIntents(json, intentArray)

        assertNull(intents.single().projectType)
    }

    @Test
    fun `an unknown project type does not lose the whole command`() {
        // A free model inventing "ios" must not cost the user the create_project that was
        // otherwise understood perfectly — nor the add_task objects sharing the array with it.
        // LenientProjectTypeSerializer turns it into the null above, which the sheet then asks
        // about. Parsed with THIS test's `json`, which is the app's own configuration: the whole
        // point is that no special Json setting is required to survive this.
        val raw = """[{"action_type":"create_project","project_name":"پ","task_title":null,""" +
            """"task_description":null,"due_date":null,"project_type":"ios"},""" +
            """{"action_type":"add_task","project_name":"پ","task_title":"صفحه ورود",""" +
            """"task_description":"...","due_date":null,"project_type":null}]"""

        val intents = IntentJson.parseIntents(json, raw)

        assertEquals(2, intents.size)
        assertEquals(ActionType.CREATE_PROJECT, intents.first().actionType)
        assertNull(intents.first().projectType)
        assertEquals("صفحه ورود", intents[1].taskTitle)
    }

    @Test
    fun `a project type in the wrong case is still understood`() {
        // Models answer "Web" and "ANDROID" as readily as the lower-case ids they were given.
        val raw = """[{"action_type":"create_project","project_name":"پ","task_title":null,""" +
            """"task_description":null,"due_date":null,"project_type":"Web"}]"""

        assertEquals(ProjectType.WEB, IntentJson.parseIntents(json, raw).single().projectType)
    }
}
