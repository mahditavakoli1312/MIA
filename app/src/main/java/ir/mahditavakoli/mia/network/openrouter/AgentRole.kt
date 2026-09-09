package ir.mahditavakoli.mia.network.openrouter

/**
 * One seat in MIA's team, and therefore one thing that can be pointed at its own model.
 *
 * Until now a project had *a* model: TEC, PO, QC and the brief manager all ran on whatever id
 * was frozen into the repo's workflow files. That is the wrong shape for how the roles actually
 * differ — reviewing a diff and splitting a brief are worth a bigger model than writing the
 * hundredth boilerplate screen, and the free tiers they share have a per-day request cap that a
 * single model burns through four times as fast. So each role now reads its own
 * `AGENT_MODEL_<suffix>` variable, falling back to the repo-wide `AGENT_MODEL` and then to a
 * literal default the app rewrites — which is what keeps repos bootstrapped before roles existed
 * working unchanged.
 *
 * [APP] is the odd one out and deliberately in the same list: it is not a repo role at all but
 * the model MIA's own typed-command pipeline runs on, on the phone. It carries no [envSuffix]
 * because there is no workflow file to write it into — it lives in
 * [ir.mahditavakoli.mia.security.SecretStore] — but a user thinking "which model does each part
 * of this system use" is thinking about it alongside the other four, so both model screens list
 * it with them rather than hiding it in a different menu.
 *
 * @param id the stable key used in preferences. Never shown, never parsed from a repo.
 * @param envSuffix the `AGENT_MODEL_<suffix>` / `AGENT_PROVIDER_<suffix>` variables this role
 *        reads in a repo's workflows, or null for a role that has no repo side.
 * @param label the role's name, as the model screens show it.
 * @param note one Persian line: what this role does, so the choice is about the job and not
 *        about an acronym.
 */
enum class AgentRole(
    val id: String,
    val envSuffix: String?,
    val label: String,
    val note: String
) {
    APP(
        id = "app",
        envSuffix = null,
        label = "اپ MIA",
        note = "فهم دستورهای متنی و صوتی داخل خود اپ — روی گوشی اجرا می‌شود، نه روی گیت‌هاب"
    ),
    TEC(
        id = "tec",
        envSuffix = "TEC",
        label = "کدنویسی و فنی (TEC)",
        note = "ایجنتی که ایشیو را واقعاً پیاده‌سازی می‌کند و PR می‌سازد — پرمصرف‌ترین نقش"
    ),
    PO(
        id = "po",
        envSuffix = "PO",
        label = "مالک محصول (PO)",
        note = "پاسخ به @po — تبدیل درخواست به دامنه، معیار پذیرش و بریف آمادهٔ اجرا"
    ),
    QC(
        id = "qc",
        envSuffix = "QC",
        label = "کنترل کیفیت (QC)",
        note = "پاسخ به @qc و بازبینی diff هر PR پیش از merge — ارزش مدل قوی‌تر را دارد"
    ),
    BRIEF(
        id = "brief",
        envSuffix = "BRIEF",
        label = "مدیر بریف (PO بریف)",
        note = "شکستن یک بریف بلند به ایشیوهای اندازهٔ TEC، به ترتیب اجرا"
    );

    /** The repo variable holding this role's model, or null for a role with no repo side. */
    val modelVariable: String? get() = envSuffix?.let { "AGENT_MODEL_$it" }

    /** The repo variable holding the provider that serves [modelVariable]. */
    val providerVariable: String? get() = envSuffix?.let { "AGENT_PROVIDER_$it" }

    companion object {
        /** The roles that live in a repo's workflow files — everything except [APP]. */
        val REPO_ROLES: List<AgentRole> = entries.filter { it.envSuffix != null }

        fun byId(id: String?): AgentRole? = entries.firstOrNull { it.id == id }
    }
}
