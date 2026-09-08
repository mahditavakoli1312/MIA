package ir.mahditavakoli.mia.network.openrouter

/**
 * One entry in the model picker a project card offers.
 *
 * @param id the model id exactly as [provider] wants it on the wire. The provider prefix
 *        OpenCode needs (`openrouter/`, `minimax/`) is *not* part of it — see
 *        [ir.mahditavakoli.mia.data.repository.AgentModelMigrator], which adds it per file.
 * @param provider which endpoint serves it, and therefore which API key it needs.
 * @param label what the picker shows.
 * @param note one Persian line under the label: what this model is for.
 */
data class AgentModel(
    val id: String,
    val provider: AgentProvider,
    val label: String,
    val note: String
) {
    /** `provider/model`, the form OpenCode and the `AGENT_MODEL` workflow default take. */
    val openCodeAddress: String get() = provider.openCodeAddress(id)
}

/**
 * What a repo's AI team can be pointed at, best-first.
 *
 * One hard requirement, which everything here meets: **tool calling** — without tools OpenCode
 * cannot read or write a single file, so TEC would fail on every issue no matter how strong the
 * model is. Everything on OpenRouter is additionally **free**, which is what lets MIA's CI team
 * cost nothing by default.
 *
 * The one paid entry is MiniMax M3 on MiniMax's own platform. It is the same model as the free
 * OpenRouter entry above it, but served first-party against the user's own MiniMax account, so
 * it is not sharing the free tier's 50-requests-a-day ceiling with everyone else — the reason
 * to reach for it is a repo where TEC keeps stopping at the quota wall.
 *
 * The list is deliberately short and hand-checked rather than fetched from `/api/v1/models` at
 * runtime: the picker has to work offline, and "tools + big enough context" is a judgement call
 * that a capability flag alone doesn't make.
 */
val AGENT_MODEL_CHOICES: List<AgentModel> = listOf(
    AgentModel(
        id = DEFAULT_TEXT_MODEL,
        provider = AgentProvider.OPENROUTER,
        label = "MiniMax M3 (رایگان)",
        note = "پیش‌فرض MIA — کانتکست ۱ میلیون توکن، ساخته‌شده برای کدنویسی و کار ایجنتی"
    ),
    AgentModel(
        id = MINIMAX_DIRECT_MODEL,
        provider = AgentProvider.MINIMAX,
        label = "MiniMax M3 (حساب شخصی MiniMax)",
        note = "همان مدل، اما روی حساب پولی خودتان در minimax.io — بدون سقف روزانهٔ رایگان. " +
            "کلید MiniMax را در تنظیمات وارد کنید."
    ),
    AgentModel(
        id = "thinkingmachines/inkling:free",
        provider = AgentProvider.OPENROUTER,
        label = "Inkling (رایگان)",
        note = "کانتکست ۱ میلیون توکن — گزینهٔ جایگزین وقتی MiniMax سقف نرخ خورده"
    ),
    AgentModel(
        id = "z-ai/glm-5.2:free",
        provider = AgentProvider.OPENROUTER,
        label = "GLM 5.2 (رایگان)",
        note = "مدل عمومی قوی، کانتکست ۲۵۶ هزار توکن"
    ),
    AgentModel(
        id = "cohere/north-mini-code:free",
        provider = AgentProvider.OPENROUTER,
        label = "North Mini Code (رایگان)",
        note = "مخصوص کد، کانتکست ۲۵۶ هزار توکن"
    ),
    AgentModel(
        id = "poolside/laguna-s-2.1:free",
        provider = AgentProvider.OPENROUTER,
        label = "Laguna S 2.1 (رایگان)",
        note = "مخصوص کد، کانتکست ۲۶۲ هزار توکن"
    ),
    AgentModel(
        id = "nvidia/nemotron-3-super-120b-a12b:free",
        provider = AgentProvider.OPENROUTER,
        label = "Nemotron 3 Super (رایگان)",
        note = "مدل بزرگ‌تر عمومی، کانتکست ۲۶۲ هزار توکن"
    )
)

/** The picker entry for [id], or null when a repo names a model MIA doesn't offer. */
fun agentModelOrNull(id: String?): AgentModel? = AGENT_MODEL_CHOICES.firstOrNull { it.id == id }

/**
 * The picker entry whose OpenCode address is [address] (`provider/model`), or null.
 *
 * This is the exact inverse of [AgentModel.openCodeAddress], and it has to be a table lookup
 * rather than "split on the first slash": `minimax/minimax-m3:free` is a *model id* on
 * OpenRouter, while `minimax/MiniMax-M3` is provider + model — so the prefix alone is
 * genuinely ambiguous, and only the known set resolves it.
 */
fun agentModelByOpenCodeAddress(address: String?): AgentModel? =
    AGENT_MODEL_CHOICES.firstOrNull { it.openCodeAddress == address }

/** Which provider serves [id], falling back to OpenRouter for ids MIA doesn't know. */
fun providerFor(id: String?): AgentProvider =
    agentModelOrNull(id)?.provider ?: AgentProvider.DEFAULT
