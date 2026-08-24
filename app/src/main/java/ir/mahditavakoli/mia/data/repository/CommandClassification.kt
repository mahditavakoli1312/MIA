package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.data.model.TokenUsage
import ir.mahditavakoli.mia.data.model.VoiceCommandIntent

/**
 * What one command — spoken or typed — turned into, and what understanding it cost.
 *
 * Both front doors return this shape so [ir.mahditavakoli.mia.ui.main.MainViewModel] runs a
 * single execution path regardless of how the command arrived.
 */
data class CommandClassification(
    /** One command can describe several distinct pieces of work; each becomes its own intent. */
    val intents: List<VoiceCommandIntent>,
    /** Null when the provider reported no usage — spend reporting then degrades to silent. */
    val usage: TokenUsage?,
    /**
     * The cleaned-up prompt the intents were actually extracted from, for the typed pipeline
     * (see [ir.mahditavakoli.mia.network.openrouter.PromptRefinementPrompt]). Null for voice,
     * where the audio itself is the input and there is nothing to show back.
     */
    val refinedPrompt: String? = null
)
