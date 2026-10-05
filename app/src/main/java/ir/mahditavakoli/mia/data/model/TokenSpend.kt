package ir.mahditavakoli.mia.data.model

import java.util.Locale

/** Who spent the tokens. Four actors, and the pipeline has no fifth. */
enum class SpendRole {
    /** The app itself: turning a spoken or typed command into intents. */
    MIA,

    /** The coding agent: implementing an issue, including every build-repair attempt. */
    TEC,

    /** The product owner: decomposing a brief, and answering `@po`. */
    PO,

    /** Quality control: the pull-request gate, and answering `@qc`. */
    QC;

    val persianLabel: String
        get() = when (this) {
            MIA -> "MIA (درک دستور)"
            TEC -> "TEC (کدنویس)"
            PO -> "PO (محصول)"
            QC -> "QC (کیفیت)"
        }
}

/**
 * One recorded spend: a commit trailer, an agent's comment footer, or one of the app's own calls.
 *
 * [costUsd] is null when nothing reported a number, which is a different thing from $0.00 — free
 * models legitimately cost nothing, and the screen must not turn "unknown" into "free".
 */
data class SpendEntry(
    val role: SpendRole,
    val model: String,
    val tokens: Int,
    val costUsd: Double? = null,
    /** Null for the app's own calls, which are not tied to a repo. */
    val projectName: String? = null,
    val issueNumber: Int? = null,
    /** Epoch millis. The app's own entries are exact; GitHub's come from its timestamps. */
    val atMillis: Long = 0L,
    /** Where to look this up — the commit or the comment it was read from. */
    val url: String? = null
)

/** One bar of the weekly chart. */
data class SpendWeek(
    /** Midnight (local) on the Saturday that starts this Persian week. */
    val startMillis: Long,
    val tokens: Int,
    val costUsd: Double
)

/** Tokens and cost for one grouping key — a model, or a role. */
data class SpendSlice(val label: String, val tokens: Int, val costUsd: Double, val calls: Int)

/** One issue and everything every actor spent on it. */
data class IssueSpend(
    val projectName: String?,
    val issueNumber: Int,
    val tokens: Int,
    val costUsd: Double,
    val url: String?
)

/**
 * Everything the spend screen shows, derived from a flat list of entries.
 *
 * [isTruncated] is the honest bit. MIA pages a bounded number of commits and comments per repo, so
 * on a busy project the totals are a floor rather than a total — the screen says "at least X" and
 * the issues list already sets that precedent.
 */
data class SpendReport(
    val entries: List<SpendEntry> = emptyList(),
    val isTruncated: Boolean = false,
    /** Repos that could not be read at all, by project name, so the screen can name them. */
    val unreadableProjects: List<String> = emptyList()
) {
    val totalTokens: Int get() = entries.sumOf { it.tokens }

    /** Only the entries that actually reported money; the rest are "not reported". */
    val totalCostUsd: Double get() = entries.sumOf { it.costUsd ?: 0.0 }

    val isEmpty: Boolean get() = entries.isEmpty()

    val byRole: List<SpendSlice>
        get() = SpendRole.entries.mapNotNull { role ->
            val slice = entries.filter { it.role == role }
            if (slice.isEmpty()) return@mapNotNull null
            SpendSlice(
                label = role.persianLabel,
                tokens = slice.sumOf { it.tokens },
                costUsd = slice.sumOf { it.costUsd ?: 0.0 },
                calls = slice.size
            )
        }.sortedByDescending { it.tokens }

    val byModel: List<SpendSlice>
        get() = entries.groupBy { it.model.ifBlank { "نامشخص" } }
            .map { (model, slice) ->
                SpendSlice(
                    label = model,
                    tokens = slice.sumOf { it.tokens },
                    costUsd = slice.sumOf { it.costUsd ?: 0.0 },
                    calls = slice.size
                )
            }
            .sortedByDescending { it.tokens }

    /** The five issues that cost the most, across every actor that worked on them. */
    val topIssues: List<IssueSpend>
        get() = entries.filter { it.issueNumber != null }
            .groupBy { it.projectName to it.issueNumber }
            .map { (key, slice) ->
                IssueSpend(
                    projectName = key.first,
                    issueNumber = key.second!!,
                    tokens = slice.sumOf { it.tokens },
                    costUsd = slice.sumOf { it.costUsd ?: 0.0 },
                    url = slice.firstNotNullOfOrNull { it.url }
                )
            }
            .sortedByDescending { it.tokens }
            .take(5)

    /**
     * The last [weeks] weeks, oldest bar first, including weeks with nothing in them.
     *
     * Empty weeks are kept on purpose: a chart that silently drops them would show two bars side by
     * side that are a month apart, which reads as continuous work.
     */
    fun weekly(now: Long, weeks: Int = 8): List<SpendWeek> {
        val currentWeekStart = weekStart(now)
        val buckets = LinkedHashMap<Long, MutableList<SpendEntry>>()
        for (index in (weeks - 1) downTo 0) {
            buckets[currentWeekStart - index * WEEK_MS] = mutableListOf()
        }
        for (entry in entries) {
            if (entry.atMillis <= 0L) continue
            buckets[weekStart(entry.atMillis)]?.add(entry)
        }
        return buckets.map { (start, slice) ->
            SpendWeek(
                startMillis = start,
                tokens = slice.sumOf { it.tokens },
                costUsd = slice.sumOf { it.costUsd ?: 0.0 }
            )
        }
    }

    /** How much of a monthly token budget this month has used, as 0f..1f (or null when unset). */
    fun budgetFraction(budgetTokens: Int, now: Long): Float? {
        if (budgetTokens <= 0) return null
        val monthStart = now - THIRTY_DAYS_MS
        val used = entries.filter { it.atMillis >= monthStart }.sumOf { it.tokens }
        return (used.toFloat() / budgetTokens).coerceAtLeast(0f)
    }

    /** Tokens spent in the last 30 days — the number the budget bar is measuring. */
    fun tokensThisMonth(now: Long): Int =
        entries.filter { it.atMillis >= now - THIRTY_DAYS_MS }.sumOf { it.tokens }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val WEEK_MS = 7 * DAY_MS
        private const val THIRTY_DAYS_MS = 30 * DAY_MS

        /**
         * Midnight UTC on the Saturday at or before [millis].
         *
         * Saturday because the Persian week starts there, and UTC because the alternative — the
         * device's own zone — would move every bar when the user travels, for a chart whose whole
         * point is comparing one week with the next. Epoch day 0 (1970-01-01) was a Thursday, so
         * the offset to the previous Saturday is 5 days.
         */
        fun weekStart(millis: Long): Long {
            val day = Math.floorDiv(millis, DAY_MS)
            val sinceSaturday = Math.floorMod(day + 5, 7L)
            return (day - sinceSaturday) * DAY_MS
        }

        /** Locale.US grouping, like every other number in the app. */
        fun groupTokens(value: Int): String = String.format(Locale.US, "%,d", value)

        /** "$0.0123", or "$0.00" for a number that is genuinely zero. */
        fun formatCost(value: Double): String = String.format(Locale.US, "$%.4f", value)
    }
}
