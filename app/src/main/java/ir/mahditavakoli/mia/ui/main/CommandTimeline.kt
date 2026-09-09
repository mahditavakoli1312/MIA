package ir.mahditavakoli.mia.ui.main

import ir.mahditavakoli.mia.data.model.IssueComment
import ir.mahditavakoli.mia.data.model.IssueLabels
import ir.mahditavakoli.mia.data.model.RepoIssue

/**
 * Every step a command goes through, from the microphone to a merged pull request.
 *
 * The first three happen inside the app and are known exactly. The rest happen on GitHub, minutes
 * later, and are *derived* from the issue's labels and comments — see [CommandTimeline.advance].
 * That is the whole point of this file: the work does not stop when the app's part is done, and a
 * status banner that goes quiet at that moment tells the user the system stopped too.
 */
enum class CommandStage {
    /** Nothing in flight. */
    NONE,

    /** Typed text is being rewritten into an explicit prompt (OpenRouter pre-processing). */
    REFINING,

    /** The prompt (or the recorded audio) is being turned into intent JSON. */
    UNDERSTANDING,

    /** Intents are being executed against Supabase/GitHub. */
    EXECUTING,

    /** The issue exists on GitHub. */
    ISSUE_CREATED,

    /** It carries `by-agent`: in TEC's queue, waiting its turn. */
    QUEUED,

    /** It carries `agent-running`: a model is editing files right now. */
    AGENT_RUNNING,

    /** The change is being compiled (and repaired, up to three attempts). */
    BUILD,

    /** A pull request is open for it. */
    PR_OPENED,

    /** The pull request was squash-merged; the issue is `done`. */
    MERGED,

    /** It stopped short — `agent-failed`, `needs-human`, or an error comment. */
    FAILED;

    /** True for the steps that happen on GitHub, after the app's own work is finished. */
    val isRemote: Boolean get() = ordinal >= ISSUE_CREATED.ordinal
}

/** How one row of the timeline is drawn. */
enum class StepState { PENDING, RUNNING, DONE, FAILED }

/**
 * One row: which step, how it is going, when it started and finished, and what it left behind.
 *
 * @param artefactUrl the thing this step produced — the issue, the workflow run, the pull request.
 *        A finished row with a URL is tappable; the others are not.
 * @param tokens what this step cost, when a number is actually known. MIA's own steps report it
 *        from the classification response; the agent's from the spend comment it posts.
 */
data class TimelineStep(
    val stage: CommandStage,
    val state: StepState,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val artefactUrl: String? = null,
    val tokens: Int? = null,
    /** Extra detail for this row, e.g. how many other issues the same command opened. */
    val note: String? = null
) {
    /** Milliseconds this step took, or has been running for, given [now]. Null before it starts. */
    fun elapsedMs(now: Long): Long? {
        val start = startedAt ?: return null
        return (endedAt ?: now) - start
    }
}

/** The issue a timeline follows on GitHub. */
data class TrackedIssue(
    val projectName: String,
    val number: Int,
    val title: String,
    val htmlUrl: String
)

/**
 * The live state of one command, from "understanding" to "merged".
 *
 * Held in the ViewModel rather than the composable so it survives rotation, and advanced by
 * [advance] from a poll of the issue. Nothing here reaches out on its own — a state object that
 * fetched would be impossible to test and would keep polling after the screen was gone.
 *
 * [issue] is null for a command that opened no issue at all (a rename, a deletion, or GitHub not
 * being configured). The timeline then simply ends at [CommandStage.EXECUTING], which is the whole
 * truth about that command.
 */
data class CommandTimeline(
    val steps: List<TimelineStep>,
    val issue: TrackedIssue? = null,
    /** True once nothing more will change — merged, failed, or nothing left to watch. */
    val isFinished: Boolean = false,
    /** Shown collapsed once finished; the user can expand it again. */
    val isExpanded: Boolean = true,
    /** Set when a step failed, as the one-line reason for the summary row. */
    val failureReason: String? = null
) {
    val current: TimelineStep? get() = steps.lastOrNull { it.state == StepState.RUNNING }

    val hasFailed: Boolean get() = steps.any { it.state == StepState.FAILED }

    /** What the collapsed single row says. */
    val summary: String
        get() = when {
            hasFailed -> failureReason ?: "کار نیمه‌کاره ماند"
            isFinished && issue != null -> "ایشو #${issue.number} انجام شد"
            isFinished -> "انجام شد"
            else -> current?.let { stageLabel(it.stage) } ?: "در حال کار"
        }

    /** Total tokens across every step that reported a number. */
    val totalTokens: Int? get() = steps.mapNotNull { it.tokens }.takeIf { it.isNotEmpty() }?.sum()

    fun withStep(stage: CommandStage, transform: (TimelineStep) -> TimelineStep): CommandTimeline =
        copy(steps = steps.map { if (it.stage == stage) transform(it) else it })

    /**
     * Folds one poll of the issue into a new timeline.
     *
     * The mapping is deliberately conservative: a step is only marked DONE on evidence that it
     * happened, never because a later step was seen. The one exception is the pull request, which
     * proves the build went green — GitHub has no "build finished" comment to read, and inferring
     * that from a merged PR is sound where guessing at the reverse would not be.
     *
     * @param now injected so tests are not at the mercy of the clock.
     */
    fun advance(issue: RepoIssue, comments: List<IssueComment>, now: Long): CommandTimeline {
        val labels = issue.labels
        val bodies = comments.map { it.body }
        val prUrl = bodies.firstNotNullOfOrNull { PR_URL.find(it)?.value }
        val runUrl = bodies.firstNotNullOfOrNull { RUN_URL.find(it)?.groupValues?.get(1) }
        val spendTokens = bodies.firstNotNullOfOrNull { body ->
            SPEND_TOTAL.find(body)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
        }

        val merged = IssueLabels.DONE in labels || bodies.any { "squash-merged" in it }
        val buildFailed = bodies.any { BUILD_FAILED in it }
        val scopeRejected = bodies.any { SCOPE_REJECTED in it }
        val agentRan = IssueLabels.AGENT_RUNNING in labels ||
            bodies.any { AGENT_PICKED_UP in it } || prUrl != null || merged || buildFailed
        val queued = IssueLabels.AGENT in labels || agentRan
        val failed = !merged && (
            IssueLabels.AGENT_FAILED in labels || issue.needsHuman || buildFailed || scopeRejected
            )

        var next = this
        next = next.mark(CommandStage.ISSUE_CREATED, StepState.DONE, now, this.issue?.htmlUrl)
        next = next.mark(
            CommandStage.QUEUED,
            when {
                agentRan || merged -> StepState.DONE
                queued -> StepState.RUNNING
                failed -> StepState.FAILED
                else -> StepState.PENDING
            },
            now
        )
        next = next.mark(
            CommandStage.AGENT_RUNNING,
            when {
                prUrl != null || merged || buildFailed -> StepState.DONE
                agentRan -> StepState.RUNNING
                failed -> StepState.FAILED
                else -> StepState.PENDING
            },
            now,
            runUrl,
            tokens = spendTokens
        )
        next = next.mark(
            CommandStage.BUILD,
            when {
                buildFailed -> StepState.FAILED
                // A pull request only exists because the build went green.
                prUrl != null || merged -> StepState.DONE
                agentRan -> StepState.RUNNING
                else -> StepState.PENDING
            },
            now,
            runUrl
        )
        next = next.mark(
            CommandStage.PR_OPENED,
            when {
                prUrl != null || merged -> StepState.DONE
                buildFailed || failed -> StepState.FAILED
                else -> StepState.PENDING
            },
            now,
            prUrl
        )
        next = next.mark(
            CommandStage.MERGED,
            when {
                merged -> StepState.DONE
                failed -> StepState.FAILED
                else -> StepState.PENDING
            },
            now,
            prUrl
        )

        // `needs-rework` is not a failure and not an ending: QC handed the work back and the issue
        // is in the queue again, so the timeline keeps watching rather than settling on "merged".
        val reworking = issue.needsRework
        val reason = when {
            issue.needsHuman -> "QC دو بار تغییر خواست و نتیجه نگرفت — نیاز به شما"
            scopeRejected -> "ایجنت فایل‌هایی بیرون از دامنهٔ ایشو عوض کرد"
            buildFailed -> "build سبز نشد"
            IssueLabels.AGENT_FAILED in labels -> "ایجنت کار را نیمه‌کاره گذاشت"
            else -> null
        }
        return next.copy(
            isFinished = merged || (failed && !reworking),
            failureReason = reason ?: next.failureReason
        )
    }

    /**
     * Sets one step's state, keeping the first start and the first end it was given: a poll that
     * repeats an unchanged state must not keep resetting the clock the row is showing.
     */
    private fun mark(
        stage: CommandStage,
        state: StepState,
        now: Long,
        artefactUrl: String? = null,
        tokens: Int? = null
    ): CommandTimeline = withStep(stage) { step ->
        val ending = state == StepState.DONE || state == StepState.FAILED
        step.copy(
            state = state,
            startedAt = step.startedAt ?: now.takeIf { state != StepState.PENDING },
            endedAt = if (ending) step.endedAt ?: now else null,
            artefactUrl = artefactUrl ?: step.artefactUrl,
            tokens = tokens ?: step.tokens
        )
    }

    companion object {
        /** The steps MIA runs itself, in order. A spoken command skips [CommandStage.REFINING]. */
        private val LOCAL_STAGES =
            listOf(CommandStage.REFINING, CommandStage.UNDERSTANDING, CommandStage.EXECUTING)

        /** The steps that happen on GitHub, watched by polling. */
        private val REMOTE_STAGES = listOf(
            CommandStage.ISSUE_CREATED,
            CommandStage.QUEUED,
            CommandStage.AGENT_RUNNING,
            CommandStage.BUILD,
            CommandStage.PR_OPENED,
            CommandStage.MERGED
        )

        /**
         * A timeline for a command that is just starting.
         *
         * @param refining false for a spoken command, which has no prompt to rewrite — showing a
         *        step that will never run would be a lie in the shape of a progress bar.
         */
        fun starting(now: Long, refining: Boolean): CommandTimeline {
            val stages = if (refining) LOCAL_STAGES else LOCAL_STAGES - CommandStage.REFINING
            return CommandTimeline(
                steps = stages.mapIndexed { index, stage ->
                    TimelineStep(
                        stage = stage,
                        state = if (index == 0) StepState.RUNNING else StepState.PENDING,
                        startedAt = now.takeIf { index == 0 }
                    )
                }
            )
        }

        /** Regexes over comment bodies the TEC workflow posts. Each one is quoted in that file. */
        private val PR_URL = Regex("""https://github\.com/[^\s)"]+/pull/\d+""")
        private val RUN_URL = Regex("""\[Workflow run]\((https://[^\s)]+)\)""")
        private val SPEND_TOTAL = Regex("""\|\s*\*\*Total\*\*\s*\|\s*\*\*([\d,]+)\*\*\s*\|""")
        private const val AGENT_PICKED_UP = "picked this up from the queue"
        private const val BUILD_FAILED = "still fails after"
        private const val SCOPE_REJECTED = "changed files this issue does not cover"

        /** The Persian label for one step. Kept next to the enum so the two cannot drift. */
        fun stageLabel(stage: CommandStage): String = when (stage) {
            CommandStage.NONE -> ""
            CommandStage.REFINING -> "آماده‌سازی پرامپت"
            CommandStage.UNDERSTANDING -> "درک دستور"
            CommandStage.EXECUTING -> "اجرا و ثبت"
            CommandStage.ISSUE_CREATED -> "ساخت ایشو در گیت‌هاب"
            CommandStage.QUEUED -> "در صف ایجنت"
            CommandStage.AGENT_RUNNING -> "کار ایجنت روی کد"
            CommandStage.BUILD -> "ساخت پروژه (build)"
            CommandStage.PR_OPENED -> "باز شدن Pull Request"
            CommandStage.MERGED -> "merge شد"
            CommandStage.FAILED -> "ناتمام"
        }
    }

    /**
     * The local half is finished; from here the timeline follows [issue] on GitHub.
     *
     * @param otherIssues how many more issues the same command opened. Only the first is watched —
     *        one command usually means one issue, and polling five at once would spend the shared
     *        GitHub rate limit on a decoration — so the count is shown instead of hidden.
     */
    fun handedOff(issue: TrackedIssue?, otherIssues: Int, now: Long): CommandTimeline {
        val local = steps.map { step ->
            if (step.state == StepState.RUNNING || step.state == StepState.PENDING) {
                step.copy(
                    state = StepState.DONE,
                    startedAt = step.startedAt ?: now,
                    endedAt = step.endedAt ?: now
                )
            } else {
                step
            }
        }
        if (issue == null) {
            return copy(steps = local, isFinished = true, isExpanded = false)
        }
        return copy(
            steps = local + REMOTE_STAGES.map { stage ->
                TimelineStep(
                    stage = stage,
                    state = StepState.PENDING,
                    artefactUrl = if (stage == CommandStage.ISSUE_CREATED) issue.htmlUrl else null,
                    note = if (stage == CommandStage.ISSUE_CREATED && otherIssues > 0) {
                        "و $otherIssues ایشوی دیگر (این یکی دنبال می‌شود)"
                    } else {
                        null
                    }
                )
            },
            issue = issue
        )
    }

    /** Records what MIA's own understanding step cost, once the classifier has answered. */
    fun withUnderstandingCost(tokens: Int?): CommandTimeline =
        if (tokens == null) this else withStep(CommandStage.UNDERSTANDING) { it.copy(tokens = tokens) }

    /** Moves the local half on to [stage]: everything before it is done, [stage] is running. */
    fun atLocalStage(stage: CommandStage, now: Long): CommandTimeline {
        val index = steps.indexOfFirst { it.stage == stage }
        if (index < 0) return this
        return copy(
            steps = steps.mapIndexed { i, step ->
                when {
                    i < index && step.state != StepState.FAILED -> step.copy(
                        state = StepState.DONE,
                        startedAt = step.startedAt ?: now,
                        endedAt = step.endedAt ?: now
                    )

                    i == index -> step.copy(state = StepState.RUNNING, startedAt = step.startedAt ?: now)
                    else -> step
                }
            }
        )
    }

    /** The command never reached GitHub: the running step failed and the timeline stops there. */
    fun failedLocally(reason: String, now: Long): CommandTimeline = copy(
        steps = steps.map { step ->
            if (step.state == StepState.RUNNING) {
                step.copy(state = StepState.FAILED, endedAt = now)
            } else {
                step
            }
        },
        isFinished = true,
        failureReason = reason
    )
}
