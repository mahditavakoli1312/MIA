package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.data.model.Project
import ir.mahditavakoli.mia.data.model.TokenUsage
import ir.mahditavakoli.mia.network.openrouter.AgentProvider
import ir.mahditavakoli.mia.network.openrouter.ChatCompleter
import ir.mahditavakoli.mia.network.openrouter.ChatCompletionRequest
import ir.mahditavakoli.mia.network.openrouter.ChatCompletionResponse
import ir.mahditavakoli.mia.network.openrouter.ChatMessage
import ir.mahditavakoli.mia.network.openrouter.DEFAULT_TEXT_MODEL
import ir.mahditavakoli.mia.network.openrouter.OpenRouterUsage
import ir.mahditavakoli.mia.network.openrouter.PromptRefinementPrompt
import ir.mahditavakoli.mia.network.openrouter.Reasoning
import ir.mahditavakoli.mia.network.openrouter.ResponseFormat
import ir.mahditavakoli.mia.network.openrouter.TextIntentPrompt
import ir.mahditavakoli.mia.text.PersianText
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/**
 * The typed-command counterpart to [GeminiVoiceIntentClassifier]: text in, the same intent
 * array out, but running on a chat model instead of Gemini — OpenRouter's free
 * `minimax/minimax-m3:free` by default, or whatever the user picked in Settings, including
 * `MiniMax-M3` on their own MiniMax account. Which host answers is [provider]'s business; this
 * class only ever sees an OpenAI-compatible request and reply.
 *
 * Three stages, deliberately separated because each is bad at the others' job:
 *  1. [PersianText.normalize] — free, instant, on-device: Persian letter forms, ASCII digits,
 *     no diacritics/tatweel, sane spacing. The model never sees keyboard noise.
 *  2. **Prompt pre-processing** ([PromptRefinementPrompt]) — one model call that rewrites the
 *     raw text into an explicit, self-contained Persian request: pronouns resolved, project
 *     names grounded against real data, relative dates turned into ISO dates, several pieces of
 *     work enumerated. This is what makes short, elliptical typing work as well as speech.
 *  3. **Intent extraction** ([TextIntentPrompt]) — a second call that only has to emit strict
 *     JSON, from input that is already unambiguous.
 *
 * Both calls run at [Reasoning.MAX] — the highest thinking effort — because both are
 * hard for a model that answers off the cuff: stage 2 has to guess which of the user's projects
 * an elliptical Persian sentence meant, and stage 3 has to hold a whole multi-task brief in
 * strict JSON. The tokens are free on this model, and thinking is what buys the accuracy.
 *
 * Both calls are billed, so their usage is summed into one [TokenUsage] and reported as a single
 * spend for the command — matching how the voice path reports its one multimodal call.
 *
 * Both also fail over: when a key comes back rate limited (429) or out of credit (402), the call
 * is retried on the spare key from [fallbackApiKeyProvider], since MIA runs on free-tier keys
 * whose daily quota routinely runs out mid-day.
 */
class OxTextIntentClassifier(
    private val api: ChatCompleter,
    private val json: Json,
    /** Supplies the runtime API key for [provider] (Settings override, else BuildConfig default). */
    private val apiKeyProvider: () -> String?,
    /**
     * Supplies the spare key, or null when none is configured (MiniMax has no notion of one).
     * Never used until the primary one answers with a limit — see [KeyRing].
     */
    private val fallbackApiKeyProvider: () -> String? = { null },
    private val model: String = DEFAULT_TEXT_MODEL,
    /** Which service [api] talks to — used only for accurate, actionable error messages. */
    private val provider: AgentProvider = AgentProvider.OPENROUTER,
    /**
     * How hard the model may think, sent on both calls. [Reasoning.MAX] is the top setting;
     * a caller pointing [model] at a non-reasoning model should pass null.
     */
    private val reasoning: Reasoning? = Reasoning.MAX
) {

    /**
     * @param onRefined called with the refined prompt the moment stage 2 lands, so the UI can
     *        show what the command was understood as while stage 3 is still running.
     */
    suspend fun classify(
        rawText: String,
        projects: List<Project> = emptyList(),
        onRefined: (String) -> Unit = {}
    ): Result<CommandClassification> = runCatching {
        val keys = KeyRing(listOfNotNull(apiKeyProvider(), fallbackApiKeyProvider()))
        check(keys.isNotEmpty) { "کلید ${provider.label} تنظیم نشده است؛ آن را در تنظیمات وارد کنید" }
        val normalized = PersianText.normalize(rawText)
        require(normalized.isNotBlank()) { "متنی برای پردازش وارد نشده است" }

        val refinement = complete(
            keys = keys,
            system = PromptRefinementPrompt.build(projects = projects),
            user = normalized,
            asJson = false
        )
        // A refusal, an empty answer, or a model that echoed nothing useful must not silently
        // drop the user's command — fall back to their own (normalized) words.
        val refinedPrompt = refinement.content.takeIf { it.isNotBlank() } ?: normalized
        onRefined(refinedPrompt)

        val extraction = complete(
            keys = keys,
            system = TextIntentPrompt.build(projects = projects),
            user = refinedPrompt,
            asJson = true
        )

        CommandClassification(
            intents = IntentJson.parseIntents(json, extraction.content)
                .ifEmpty { error("هیچ دستوری از متن استخراج نشد") },
            usage = sumUsage(refinement.usage, extraction.usage),
            refinedPrompt = refinedPrompt
        )
    }

    /** One chat completion, with the answer text and the usage it was billed for. */
    private data class Completion(val content: String, val usage: OpenRouterUsage?)

    private suspend fun complete(
        keys: KeyRing,
        system: String,
        user: String,
        asJson: Boolean
    ): Completion {
        val response = send(
            request = ChatCompletionRequest(
                model = model,
                messages = listOf(ChatMessage.system(system), ChatMessage.user(user)),
                reasoning = reasoning,
                // Only the extraction step asks for structured output; a model that ignores
                // the hint is still handled by IntentJson's fence/prose stripping.
                responseFormat = if (asJson) ResponseFormat() else null
            ),
            keys = keys
        )
        // MiniMax can answer HTTP 200 with the real failure in `base_resp`; OpenRouter never
        // sets the field, so a null one is simply "no envelope, nothing to check".
        val baseResp = response.baseResp
        check(baseResp == null || baseResp.statusCode == 0) {
            "خطای ${provider.label} (${baseResp?.statusCode}): ${baseResp?.statusMsg}"
        }
        val choice = response.choices.firstOrNull()
            ?: error("پاسخ خالی از ${provider.label} دریافت شد")
        // A truncated answer would parse as broken JSON with a confusing message, so name the
        // real cause: the model ran out of output budget mid-sentence. MIA no longer sends a
        // max_tokens of its own, so reaching this means the model's own ceiling was hit — with
        // full-effort thinking taking its share of it — and the only remedy left is a smaller
        // command.
        check(choice.finishReason != "length") {
            "پاسخ مدل ناقص ماند (طولانی‌تر از حد مجاز)؛ دستور را کوتاه‌تر بنویسید یا آن را به چند دستور بشکنید"
        }
        return Completion(content = choice.message.content.trim(), usage = response.usage)
    }

    /**
     * Both calls together, as one number: the user made one request and cares what that request
     * cost, not how many round trips the pipeline needed.
     */
    private fun sumUsage(vararg usages: OpenRouterUsage?): TokenUsage? {
        val reported = usages.filterNotNull().ifEmpty { return null }
        val prompt = reported.sumOf { it.promptTokens }
        val completion = reported.sumOf { it.completionTokens }
        val reasoningTokens = reported.sumOf { it.reasoningTokens }
        return TokenUsage(
            model = model,
            promptTokens = prompt,
            // OpenRouter counts reasoning tokens inside completion_tokens; TokenUsage reports
            // them separately and sums the two, so subtract them out here to avoid double-count.
            outputTokens = (completion - reasoningTokens).coerceAtLeast(0),
            reasoningTokens = reasoningTokens,
            // Prefer the provider's own totals, falling back to the sum when it reported none.
            totalTokens = reported.sumOf { it.totalTokens }.takeIf { it > 0 }
                ?: (prompt + completion)
        )
    }

    /**
     * One call, retried down [keys] for as long as the answer is "you are out of quota".
     *
     * A spent key is the one failure worth retrying: the request itself is fine. Anything else
     * (bad key, unknown model, server fault) would fail identically on the spare, so it is
     * surfaced immediately rather than burning the fallback's quota too.
     */
    private suspend fun send(
        request: ChatCompletionRequest,
        keys: KeyRing
    ): ChatCompletionResponse {
        while (true) {
            try {
                return api.complete(bearerToken = "Bearer ${keys.current}", request = request)
            } catch (e: HttpException) {
                if (isOutOfQuota(e.code()) && keys.advance()) continue
                throw IllegalStateException(describeHttpError(e), e)
            }
        }
    }

    /**
     * The keys this command may spend, in order: the primary first, then the spare. Blank and
     * duplicate entries are dropped, so a missing or identical fallback simply means one key.
     *
     * It is shared by both calls of a single command on purpose — once the primary is known to
     * be spent, the intent-extraction call starts on the spare instead of paying for another
     * 429 first.
     */
    private class KeyRing(candidates: List<String>) {
        private val keys = candidates.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        private var index = 0

        val isNotEmpty: Boolean get() = keys.isNotEmpty()
        val current: String get() = keys[index]

        /** Moves to the next key. False when this was already the last one. */
        fun advance(): Boolean = (index + 1 < keys.size).also { if (it) index++ }
    }

    /** 429 = rate limited for now, 402 = credit exhausted. Both mean "try the other key". */
    private fun isOutOfQuota(code: Int): Boolean = code == 429 || code == 402

    private fun describeHttpError(e: HttpException): String = when (e.code()) {
        401 -> "کلید ${provider.label} نامعتبر یا باطل شده است؛ کلید معتبر را در تنظیمات وارد کنید"
        402 -> "اعتبار حساب ${provider.label} کافی نیست؛ حساب را شارژ کنید یا مدل دیگری انتخاب کنید"
        403 -> "دسترسی به مدل «$model» با این کلید مجاز نیست"
        404 -> "مدل «$model» در ${provider.label} پیدا نشد؛ نام مدل را بررسی کنید"
        429 -> "سهمیه ${provider.label} پر شده است؛ کمی بعد دوباره تلاش کنید"
        else -> "خطای ${provider.label} (HTTP ${e.code()})"
    }
}
