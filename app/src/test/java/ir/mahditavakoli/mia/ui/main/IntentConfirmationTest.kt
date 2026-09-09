package ir.mahditavakoli.mia.ui.main

import ir.mahditavakoli.mia.data.model.ActionType
import ir.mahditavakoli.mia.data.model.VoiceCommandIntent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate on the confirmation sheet's primary button. It is the only thing standing between a
 * misheard word and a cascading delete, so it is tested directly rather than trusted to the
 * composable that reads it.
 */
class IntentConfirmationTest {

    private fun row(id: Int, action: ActionType, acknowledged: Boolean = false) = ConfirmableIntent(
        id = id,
        intent = VoiceCommandIntent(
            actionType = action,
            projectName = "وبسایت",
            taskTitle = "طراحی لوگو".takeIf { action != ActionType.CREATE_PROJECT }
        ),
        isAcknowledged = acknowledged
    )

    private fun confirmation(vararg rows: ConfirmableIntent, executing: Boolean = false) =
        IntentConfirmation(rows = rows.toList(), originalText = null, isExecuting = executing)

    @Test
    fun `a batch with nothing destructive can run as soon as it is shown`() {
        val state = confirmation(
            row(0, ActionType.CREATE_PROJECT),
            row(1, ActionType.ADD_TASK),
            row(2, ActionType.COMPLETE_TASK)
        )

        assertTrue(state.canExecute)
    }

    @Test
    fun `both destructive actions block the batch until each is ticked`() {
        for (action in listOf(ActionType.DELETE_PROJECT, ActionType.REMOVE_TASK)) {
            val unticked = confirmation(row(0, ActionType.ADD_TASK), row(1, action))
            assertFalse("$action should block", unticked.canExecute)

            val ticked = confirmation(row(0, ActionType.ADD_TASK), row(1, action, acknowledged = true))
            assertTrue("$action ticked should run", ticked.canExecute)
        }
    }

    @Test
    fun `one unticked destructive row is enough to block a batch of several`() {
        val state = confirmation(
            row(0, ActionType.REMOVE_TASK, acknowledged = true),
            row(1, ActionType.DELETE_PROJECT, acknowledged = false)
        )

        assertFalse(state.canExecute)
    }

    @Test
    fun `an already-running batch cannot be confirmed twice`() {
        val state = confirmation(row(0, ActionType.ADD_TASK), executing = true)

        assertFalse(state.canExecute)
    }

    @Test
    fun `an empty batch has nothing to run`() {
        assertFalse(confirmation().canExecute)
    }
}
