package ir.mahditavakoli.mia.ui.spend

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.mahditavakoli.mia.data.model.IssueSpend
import ir.mahditavakoli.mia.data.model.SpendReport
import ir.mahditavakoli.mia.data.model.SpendSlice
import ir.mahditavakoli.mia.data.model.SpendWeek
import ir.mahditavakoli.mia.ui.issues.ErrorState
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One screen for the whole system's token spend, gathered from the records the actors left on
 * GitHub plus the app's own local ledger.
 *
 * The screen's job is not to be pretty about it — it is to be honest. Every number that could be a
 * floor rather than a total says so, a cost of "not reported" is never shown as $0.00, and a repo
 * that could not be read is named rather than quietly dropped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendScreen(
    onBack: () -> Unit,
    viewModel: SpendViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val uriHandler = LocalUriHandler.current

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("هزینهٔ توکن") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "بازگشت"
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::load, enabled = !uiState.isLoading) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "بارگذاری دوباره"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when {
                    uiState.isLoading && uiState.report.isEmpty -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.primary
                    )

                    uiState.errorMessage != null -> ErrorState(
                        message = uiState.errorMessage.orEmpty(),
                        onRetry = viewModel::load,
                        modifier = Modifier.align(Alignment.Center)
                    )

                    uiState.isEmpty -> Text(
                        text = "هنوز هیچ هزینه‌ای ثبت نشده. با اولین دستور صوتی یا اولین کارِ " +
                            "ایجنت، عددها اینجا ظاهر می‌شوند.",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    else -> Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        TotalCard(uiState)
                        Spacer(Modifier.height(12.dp))
                        BudgetCard(
                            uiState = uiState,
                            onDraftChange = viewModel::onBudgetDraftChange,
                            onSave = viewModel::onBudgetSave
                        )
                        Spacer(Modifier.height(12.dp))
                        WeeklyChart(uiState.report.weekly(uiState.now))
                        Spacer(Modifier.height(12.dp))
                        SliceCard("به تفکیک نقش", uiState.report.byRole, uiState.report.totalTokens)
                        Spacer(Modifier.height(12.dp))
                        SliceCard("به تفکیک مدل", uiState.report.byModel, uiState.report.totalTokens)
                        Spacer(Modifier.height(12.dp))
                        TopIssuesCard(uiState.report.topIssues) { url -> uriHandler.openUri(url) }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/**
 * The headline number, and the sentence that keeps it honest.
 *
 * "at least" is not a hedge here: MIA pages a bounded number of commits and comments per repo, so on
 * a busy project the total genuinely is a floor. Presenting a floor as a total would be the one
 * thing that makes the whole screen untrustworthy.
 */
@Composable
private fun TotalCard(uiState: SpendUiState) {
    val report = uiState.report
    SectionCard(title = if (report.isTruncated) "جمع کل (دست‌کم)" else "جمع کل") {
        Text(
            text = "${SpendReport.groupTokens(report.totalTokens)} توکن",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (report.totalCostUsd > 0) {
                "${SpendReport.formatCost(report.totalCostUsd)} از اعتبارِ گزارش‌شده"
            } else {
                "هزینهٔ دلاری گزارش‌شده: صفر (مدل‌های رایگان) یا گزارش‌نشده"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (report.isTruncated) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "این عدد دست‌کم همین‌قدر است: MIA فقط چند صفحهٔ آخر کامیت‌ها و کامنت‌های " +
                    "هر مخزن را می‌خواند، پس تاریخِ قدیمی‌تر در آن نیست.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (report.unreadableProjects.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "مخزن این پروژه‌ها خوانده نشد و در جمع نیست: " +
                    report.unreadableProjects.joinToString("، "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * The monthly budget: a field and a bar.
 *
 * The bar turns to the error colour past 80% rather than at 100%, because the point of a budget is
 * to be seen before it is spent. Past 100% it stays red and overflows visually — a bar that clamps
 * to full would make "just over" and "three times over" look the same.
 */
@Composable
private fun BudgetCard(
    uiState: SpendUiState,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit
) {
    SectionCard(title = "بودجهٔ ماهانه") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = uiState.budgetDraft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                label = { Text("سقف توکن در ۳۰ روز") },
                placeholder = { Text("مثلاً 2000000") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number
                )
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onSave) { Text("ذخیره") }
        }
        val fraction = uiState.budgetFraction
        Spacer(Modifier.height(8.dp))
        if (fraction == null) {
            Text(
                text = "بودجه‌ای تعیین نشده. با گذاشتن یک سقف، نوار پیشرفت و هشدار ۸۰٪ فعال می‌شود.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SectionCard
        }
        val over = fraction >= WARN_FRACTION
        val color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${SpendReport.groupTokens(uiState.tokensThisMonth)} از " +
                "${SpendReport.groupTokens(uiState.monthlyBudget)} توکن در ۳۰ روز گذشته " +
                "(${(fraction * 100).roundToInt()}٪)",
            style = MaterialTheme.typography.bodySmall,
            color = color
        )
    }
}

/**
 * Eight weeks of spend as bars, oldest first.
 *
 * Bars are drawn as plain Boxes rather than with a charting library: eight rectangles and a maximum
 * is the whole of it, and a dependency for that would be a dependency to keep updated forever.
 */
@Composable
private fun WeeklyChart(weeks: List<SpendWeek>) {
    val max = weeks.maxOfOrNull { it.tokens } ?: 0
    SectionCard(title = "هفته‌های اخیر") {
        if (max == 0) {
            Text(
                text = "در هشت هفتهٔ گذشته هزینه‌ای ثبت نشده.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SectionCard
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(140.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            weeks.forEach { week ->
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Text(
                        text = if (week.tokens == 0) "" else compactTokens(week.tokens),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(2.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            // A week with spend always gets at least a sliver, so "a little" and
                            // "nothing" are never the same picture.
                            .height((MIN_BAR + (MAX_BAR - MIN_BAR) * week.tokens / max).dp)
                            .background(
                                color = if (week.tokens == 0) {
                                    MaterialTheme.colorScheme.surfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                shape = RoundedCornerShape(4.dp)
                            )
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = weekLabel(week.startMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun SliceCard(title: String, slices: List<SpendSlice>, total: Int) {
    SectionCard(title = title) {
        if (slices.isEmpty()) {
            Text(
                text = "چیزی برای تفکیک نیست.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SectionCard
        }
        slices.forEachIndexed { index, slice ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            val share = if (total > 0) slice.tokens.toFloat() / total else 0f
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = slice.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${SpendReport.groupTokens(slice.tokens)} · ${(share * 100).roundToInt()}٪",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Spacer(Modifier.height(4.dp))
            ShareBar(share)
            if (slice.costUsd > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = SpendReport.formatCost(slice.costUsd),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ShareBar(share: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))
    ) {
        Box(
            Modifier
                .fillMaxWidth(share.coerceIn(0f, 1f))
                .height(6.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))
        )
    }
}

@Composable
private fun TopIssuesCard(issues: List<IssueSpend>, onOpen: (String) -> Unit) {
    SectionCard(title = "گران‌ترین ایشوها") {
        if (issues.isEmpty()) {
            Text(
                text = "هیچ هزینه‌ای به ایشوی مشخصی نسبت داده نشده.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SectionCard
        }
        issues.forEachIndexed { index, issue ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (issue.url != null) {
                            Modifier.clickable { onOpen(issue.url) }
                        } else {
                            Modifier
                        }
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "#${issue.issueNumber}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = issue.projectName.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${SpendReport.groupTokens(issue.tokens)} توکن",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

/** "12k" / "1.2M" — a bar label has room for three characters, not for "1,234,567". */
private fun compactTokens(tokens: Int): String = when {
    tokens >= 1_000_000 -> String.format(Locale.US, "%.1fM", tokens / 1_000_000f)
    tokens >= 1_000 -> String.format(Locale.US, "%dk", tokens / 1_000)
    else -> tokens.toString()
}

/**
 * "08-22" — month and day of the week's first day.
 *
 * Derived by arithmetic from the epoch rather than with a date formatter, for the same reason
 * `IssueFormat.kt` does its own formatting: this module has no `java.time`, and a formatter would
 * re-render the instant in the device's own zone while the bucket boundary was computed in UTC.
 */
private fun weekLabel(startMillis: Long): String {
    // Hinnant's civil_from_days, minus the year: eight bars a month or two apart need the day, and
    // a year on every label would not fit in the width one of eight columns gets.
    val days = startMillis / 86_400_000L + 719_468L
    val era = (if (days >= 0) days else days - 146_096) / 146_097
    val dayOfEra = days - era * 146_097
    val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val mp = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    return String.format(Locale.US, "%02d-%02d", month, day)
}

/** Red before the budget is gone, not when it is: a warning after the fact is not a warning. */
private const val WARN_FRACTION = 0.8f

// A week with any spend always gets at least MIN_BAR, so "a little" never looks like "nothing".
private const val MIN_BAR = 4
private const val MAX_BAR = 96
