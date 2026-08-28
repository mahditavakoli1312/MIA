package ir.mahditavakoli.mia.network.openrouter

/**
 * One entry in the model picker a project card offers.
 *
 * @param id the OpenRouter model id exactly as it goes on the wire. The `openrouter/` prefix
 *        OpenCode needs is *not* part of it — see [ir.mahditavakoli.mia.data.repository
 *        .AgentModelMigrator], which adds it per file.
 * @param label what the picker shows.
 * @param note one Persian line under the label: what this model is for.
 */
data class AgentModel(
    val id: String,
    val label: String,
    val note: String
)

/**
 * What a repo's AI team can be pointed at, best-first.
 *
 * Two hard requirements, and everything here meets both: **free** (the whole point of MIA's CI
 * team is that it costs nothing) and **tool calling** — without tools OpenCode cannot read or
 * write a single file, so TEC would fail on every issue no matter how strong the model is.
 *
 * The list is deliberately short and hand-checked rather than fetched from `/api/v1/models` at
 * runtime: the picker has to work offline, and "free + tools + big enough context" is a
 * judgement call that a capability flag alone doesn't make.
 */
val AGENT_MODEL_CHOICES: List<AgentModel> = listOf(
    AgentModel(
        id = DEFAULT_TEXT_MODEL,
        label = "MiniMax M3 (رایگان)",
        note = "پیش‌فرض MIA — کانتکست ۱ میلیون توکن، ساخته‌شده برای کدنویسی و کار ایجنتی"
    ),
    AgentModel(
        id = "thinkingmachines/inkling:free",
        label = "Inkling (رایگان)",
        note = "کانتکست ۱ میلیون توکن — گزینهٔ جایگزین وقتی MiniMax سقف نرخ خورده"
    ),
    AgentModel(
        id = "z-ai/glm-5.2:free",
        label = "GLM 5.2 (رایگان)",
        note = "مدل عمومی قوی، کانتکست ۲۵۶ هزار توکن"
    ),
    AgentModel(
        id = "cohere/north-mini-code:free",
        label = "North Mini Code (رایگان)",
        note = "مخصوص کد، کانتکست ۲۵۶ هزار توکن"
    ),
    AgentModel(
        id = "poolside/laguna-s-2.1:free",
        label = "Laguna S 2.1 (رایگان)",
        note = "مخصوص کد، کانتکست ۲۶۲ هزار توکن"
    ),
    AgentModel(
        id = "nvidia/nemotron-3-super-120b-a12b:free",
        label = "Nemotron 3 Super (رایگان)",
        note = "مدل بزرگ‌تر عمومی، کانتکست ۲۶۲ هزار توکن"
    )
)

/** The picker entry for [id], or null when a repo names a model MIA doesn't offer. */
fun agentModelOrNull(id: String?): AgentModel? = AGENT_MODEL_CHOICES.firstOrNull { it.id == id }
