package ir.mahditavakoli.mia.network.openrouter

/**
 * Where a model in the picker actually runs.
 *
 * MIA started out as an OpenRouter-only app — hence the package name — but a model id alone
 * stopped being enough the moment a second endpoint was offered: the same `MiniMax-M3` is
 * reachable both through OpenRouter's free tier and through a paid MiniMax account, on
 * different hosts, with different keys, under different ids. Everything that differs between
 * the two lives here, so adding a third provider is one entry rather than a sweep.
 *
 * All three readers of this are kept in step deliberately:
 *  - the app, which picks a base URL and a key for its typed-command pipeline,
 *  - OpenCode in the TEC workflow, which addresses a model as `provider/model` and splits on
 *    the **first** `/` — so [id] must be exactly the provider name OpenCode knows, and
 *  - `ai-role-review.js`, which calls the OpenAI-compatible endpoint directly and therefore
 *    needs [chatCompletionsUrl] and [secretName] rather than an id.
 *
 * @param id the provider name OpenCode/models.dev uses, and the value written into the
 *        `AGENT_PROVIDER` default of a repo's AI-team files.
 * @param label what the model picker shows next to the model name.
 * @param secretName the Actions secret (and env var) holding this provider's API key.
 * @param apiBaseUrl Retrofit base URL for the in-app client.
 * @param chatCompletionsUrl the full OpenAI-compatible chat-completions URL, for the CI script.
 */
enum class AgentProvider(
    val id: String,
    val label: String,
    val secretName: String,
    val apiBaseUrl: String,
    val chatCompletionsUrl: String
) {
    OPENROUTER(
        id = "openrouter",
        label = "OpenRouter",
        secretName = "OPENROUTER_API_KEY",
        apiBaseUrl = "https://openrouter.ai/api/",
        chatCompletionsUrl = "https://openrouter.ai/api/v1/chat/completions"
    ),

    /**
     * MiniMax's own platform (`minimax.io`, the international host — `minimaxi.com` is the
     * mainland-China one and rejects international keys). Paid, so it is not rate limited the
     * way the free tier is, and the model is served first-party rather than through a broker.
     */
    MINIMAX(
        id = "minimax",
        label = "MiniMax",
        secretName = "MINIMAX_API_KEY",
        apiBaseUrl = "https://api.minimax.io/",
        chatCompletionsUrl = "https://api.minimax.io/v1/chat/completions"
    );

    /**
     * How OpenCode must be told to reach [modelId] on this provider. It splits on the first
     * `/`, so `openrouter/minimax/minimax-m3:free` is provider `openrouter` + model
     * `minimax/minimax-m3:free`, and `minimax/MiniMax-M3` is the first-party pair.
     */
    fun openCodeAddress(modelId: String): String = "$id/$modelId"

    companion object {
        /** The provider MIA assumes when a repo's files name none — every pre-MiniMax repo. */
        val DEFAULT = OPENROUTER

        fun byId(id: String?): AgentProvider? = entries.firstOrNull { it.id == id }
    }
}
