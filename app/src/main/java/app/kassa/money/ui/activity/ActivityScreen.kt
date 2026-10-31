package app.kassa.money.ui.activity

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import kotlinx.coroutines.flow.debounce
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kassa.money.KassaApplication
import app.kassa.money.domain.Account
import app.kassa.money.domain.Category
import app.kassa.money.domain.CountedSpending
import app.kassa.money.domain.Money
import app.kassa.money.domain.QueryWindows
import app.kassa.money.domain.Transaction
import app.kassa.money.domain.TxKind
import app.kassa.money.ui.common.CategoryBadge
import app.kassa.money.ui.common.EntryRow
import app.kassa.money.ui.common.GlyphBadge
import app.kassa.money.ui.common.Stat
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import app.kassa.money.ui.common.swipeShift
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

enum class ActivityFilter { ALL, EXPENSE, INCOME }

data class ActivityUi(
    val month: YearMonth,
    val isCurrentMonth: Boolean,
    val query: String,
    val filter: ActivityFilter,
    /** This month's entries by day, after the filter. Empty while searching. */
    val days: List<Pair<LocalDate, List<Transaction>>>,
    val spent: Long,
    val income: Long,
    /** Every entry this month, whatever the filter or search, for the breakdown beside the list. */
    val monthEntries: List<Transaction>,
    /** Search results across every month, grouped by month. */
    val results: List<Pair<YearMonth, List<Transaction>>>,
    val resultCount: Int,
    val resultSpent: Long,
    val categories: Map<Long, Category>,
    val accounts: Map<Long, Account>,
)

/** What Activity's screen state is made from besides the data: the month, the search, the filter, and today. */
private data class ActivityInputs(val month: YearMonth, val query: String, val filter: ActivityFilter, val day: app.kassa.money.data.DayClock.Day)

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class ActivityViewModel(app: Application) : AndroidViewModel(app) {
    private val entries = (app as KassaApplication).entries
    private val categories = (app as KassaApplication).categories.categories
    private val accounts = (app as KassaApplication).accounts.state
    /** Today and the time zone, kept current: the month on screen moves on with the calendar and follows a zone change. */
    private val clock = (app as KassaApplication).clock.day
    private val month = MutableStateFlow(YearMonth.from(clock.value.today))
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(ActivityFilter.ALL)

    init {
        // Someone looking at the current month when midnight passes moves on to the new one; someone who paged back stays put.
        viewModelScope.launch {
            var before = clock.value.today
            clock.collect { d ->
                month.value = QueryWindows.followMonth(month.value, before, d.today)
                before = d.today
            }
        }
    }

    private val txs = combine(month, clock) { m, d -> m to d.zone }.distinctUntilChanged().flatMapLatest { (m, zone) ->
        val range = QueryWindows.monthRange(m, zone)
        entries.between(range.first, range.last)
    }

    /** Searching reads the whole database, not just the month on screen. */
    private val found = query.debounce(150).flatMapLatest { q ->
        if (q.isBlank()) kotlinx.coroutines.flow.flowOf(emptyList()) else entries.search(q)
    }

    /**
     * The month's entries and the search hits as spending sees them: a loan principal that counts is an expense in its
     * category (see `CountedSpending`), so the totals, the Spending filter and the rows all agree.
     */
    private val counted = combine(txs, found, (app as KassaApplication).loans.state, categories) { list, hits, loans, cats ->
        CountedSpending.apply(list, loans.payments, loans.terms, cats) to CountedSpending.apply(hits, loans.payments, loans.terms, cats)
    }

    val ui: StateFlow<ActivityUi?> = combine(
        combine(month, query, filter, clock) { m, q, f, d -> ActivityInputs(m, q, f, d) },
        counted, categories, accounts,
    ) { (m, q, f, day), (list, hits), cats, accts ->
        val zone = day.zone
        val catMap = cats.associateBy { it.id }
        val acctMap = accts.accounts.associateBy { it.id }
        val shown = list.filter {
            when (f) {
                ActivityFilter.ALL -> true
                ActivityFilter.EXPENSE -> it.kind == TxKind.EXPENSE
                ActivityFilter.INCOME -> it.kind == TxKind.INCOME
            }
        }
        val searching = q.isNotBlank()
        ActivityUi(
            month = m,
            isCurrentMonth = m == YearMonth.from(day.today),
            query = q,
            filter = f,
            days = if (searching) emptyList() else shown.groupBy { Instant.ofEpochMilli(it.epochMillis).atZone(zone).toLocalDate() }.toList(),
            spent = list.filter { it.kind == TxKind.EXPENSE }.sumOf { it.categoryAmount },
            income = list.filter { it.kind == TxKind.INCOME }.sumOf { it.amount },
            monthEntries = list,
            results = hits.groupBy { YearMonth.from(Instant.ofEpochMilli(it.epochMillis).atZone(zone)) }.toList(),
            resultCount = hits.size,
            resultSpent = hits.filter { it.kind == TxKind.EXPENSE }.sumOf { it.categoryAmount },
            categories = catMap,
            accounts = acctMap,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun shift(by: Long) { month.value = month.value.plusMonths(by) }
    fun search(q: String) { query.value = q }
    fun toggle(f: ActivityFilter) { filter.value = if (filter.value == f) ActivityFilter.ALL else f }
}

/**
 * The month's entries under a fixed header: month, spending and income (each also a filter), and a search
 * that covers every transaction ever recorded. Only the list scrolls.
 */
@Composable
fun ActivityScreen(
    ledger: app.kassa.money.ui.Ledger,
    contentPadding: PaddingValues,
    onEdit: (Transaction) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    selectedId: Long? = null,
    vm: ActivityViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsState()
    val s = ui ?: return
    val today = ledger.today
    val searching = s.query.isNotBlank()
    val layout = androidx.compose.ui.platform.LocalLayoutDirection.current

    Column(
        Modifier.fillMaxSize().padding(
            start = contentPadding.calculateStartPadding(layout), end = contentPadding.calculateEndPadding(layout),
            top = contentPadding.calculateTopPadding(),
        ),
    ) {
        Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            app.kassa.money.ui.common.TabTop("Activity")
            if (!searching) {
                app.kassa.money.ui.common.PeriodSwitcher(
                    "${s.month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${s.month.year}", null,
                    canBack = true, canForward = !s.isCurrentMonth, onBack = { vm.shift(-1) }, onForward = { vm.shift(1) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterTile("Spending", Money.format(s.spent), Kassa.colors.ink, s.filter == ActivityFilter.EXPENSE, Modifier.weight(1f)) { vm.toggle(ActivityFilter.EXPENSE) }
                    FilterTile("Income", Money.format(s.income), Kassa.colors.positive, s.filter == ActivityFilter.INCOME, Modifier.weight(1f)) { vm.toggle(ActivityFilter.INCOME) }
                }
            }
            SearchField(s.query, vm::search)
            if (searching) Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    withStyleBold { append(if (s.resultCount == 1) "1 result" else "${s.resultCount} results") }
                    append(" in all months")
                    if (s.resultSpent > 0) append(" · ${Money.format(s.resultSpent)} spent")
                },
                style = KassaType.small, color = Kassa.colors.ink2,
            )
        }

        LazyColumn(Modifier.fillMaxSize().then(if (searching) Modifier else Modifier.swipeShift { d -> if (d < 0 || !s.isCurrentMonth) vm.shift(d.toLong()) }), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())) {
            if (searching) {
                if (s.resultCount == 0) item {
                    Text("No transactions match “${s.query.trim()}”.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 32.dp))
                }
                s.results.forEach { (m, entries) ->
                    item(key = "m$m") {
                        DayHeader("${m.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${m.year}", "${entries.size}")
                    }
                    items(entries, key = { "r${it.id}" }) { t ->
                        app.kassa.money.ui.common.TransactionRow(
                            t, ledger, showDivider = t !== entries.last(), modifier = Modifier.widthIn(max = 720.dp),
                            noteOverride = Instant.ofEpochMilli(t.epochMillis).atZone(ledger.zone).format(DateTimeFormatter.ofPattern("MMM d, yyyy")),
                            onClick = onEdit,
                        )
                    }
                }
            } else {
                if (s.filter != ActivityFilter.ALL) item {
                    Row(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (s.filter == ActivityFilter.EXPENSE) "Spending only" else "Income only", style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.weight(1f))
                        Text("Show all", style = KassaType.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Kassa.colors.ink,
                            modifier = Modifier.clickable { vm.toggle(s.filter) }.padding(4.dp))
                    }
                }
                if (s.days.isEmpty()) item {
                    Text("No transactions this month.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 32.dp))
                }
                s.days.forEach { (day, entries) ->
                    item(key = "h$day") {
                        val spent = entries.filter { it.kind == TxKind.EXPENSE }.sumOf { it.categoryAmount }
                        DayHeader(dayLabel(day, today), (if (spent > 0) Money.format(spent) else "") + "  \u203a") { onOpenDay(day) }
                    }
                    items(entries, key = { it.id }) { t ->
                        val sel = t.id == selectedId
                        app.kassa.money.ui.common.TransactionRow(
                            t, ledger, showDivider = t !== entries.last() && !sel,
                            modifier = Modifier.widthIn(max = 720.dp).then(if (sel) Modifier.clip(RoundedCornerShape(12.dp)).background(Kassa.colors.chip).padding(horizontal = 8.dp) else Modifier),
                            onClick = onEdit,
                        )
                    }
                }
            }
        }
    }
}

private inline fun androidx.compose.ui.text.AnnotatedString.Builder.withStyleBold(block: androidx.compose.ui.text.AnnotatedString.Builder.() -> Unit) {
    val i = pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
    block()
    pop(i)
}

@Composable
private fun DayHeader(left: String, right: String, onClick: (() -> Unit)? = null) {
    Column(Modifier.widthIn(max = 720.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(left, style = KassaType.tiny, color = Kassa.colors.ink2)
            Text(right, style = KassaType.tiny, color = Kassa.colors.ink2)
        }
        HorizontalDivider(color = Kassa.colors.line)
    }
}

/** A total that doubles as a filter: tap to show only these entries, tap again for all. */
@Composable
private fun FilterTile(label: String, value: String, color: Color, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.clip(shape).background(Kassa.colors.raised)
            .border(1.5.dp, if (selected) Kassa.colors.ink else Color.Transparent, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, style = KassaType.tiny, color = Kassa.colors.ink2)
        Text(value, style = KassaType.stat.copy(fontSize = KassaType.stat.fontSize * 0.9f), color = color, maxLines = 1)
    }
}

/** Looks like a search: a rounded field with a magnifier, a placeholder, and a clear button once you type. */
@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val shape = RoundedCornerShape(50)
    val active = value.isNotEmpty()
    Row(
        Modifier.fillMaxWidth().height(46.dp).clip(shape).background(Kassa.colors.raised)
            .border(1.dp, if (active) Kassa.colors.ink else Kassa.colors.line, shape).padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(androidx.compose.material.icons.Icons.Outlined.Search, null, tint = Kassa.colors.ink2, modifier = Modifier.size(20.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = value, onValueChange = { onChange(app.kassa.money.domain.InputRules.typed(it, 60)) }, singleLine = true,
            textStyle = KassaType.body.copy(color = Kassa.colors.ink),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Kassa.colors.ink),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focus.clearFocus() }),
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text("Search all transactions", style = KassaType.body, color = Kassa.colors.ink3)
                    inner()
                }
            },
        )
        // A 26dp circle inside a 48dp touch target.
        if (active) Box(
            Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable { onChange(""); focus.clearFocus() },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(26.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Kassa.colors.chip), contentAlignment = Alignment.Center) {
                Icon(androidx.compose.material.icons.Icons.Outlined.Close, "Clear search", tint = Kassa.colors.ink2, modifier = Modifier.size(14.dp))
            }
        }
    }
}

private fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
}

/** A plain chevron, drawn here so the app doesn't need the large material-icons library. */
fun Chevron(left: Boolean): ImageVector = ImageVector.Builder("chevron", 24.dp, 24.dp, 24f, 24f).path(
    stroke = androidx.compose.ui.graphics.SolidColor(Color.Black), strokeLineWidth = 1.8f,
    strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round, strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
) {
    if (left) { moveTo(15f, 6f); lineTo(9f, 12f); lineTo(15f, 18f) } else { moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f) }
}.build()

/** Unfolded, beside the list: where the shown month's spending went, with the same category lines as everywhere else. */
@Composable
fun MonthBreakdown(ledger: app.kassa.money.ui.Ledger, contentPadding: PaddingValues, onOpenCategory: (app.kassa.money.domain.Category) -> Unit, vm: ActivityViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val s = ui ?: return
    val period = app.kassa.money.domain.Period.startingIn(s.month, ledger.monthStartDay)
    val lines = app.kassa.money.ui.common.monthLines(ledger, period, s.monthEntries)
    Column(Modifier.fillMaxSize().androidx_scroll().padding(contentPadding)) {
        app.kassa.money.ui.common.SectionHeader("Where it went", s.month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()))
        app.kassa.money.ui.common.CategoryLinesAll(lines, "No spending", onOpenCategory)
    }
}

@Composable
private fun Modifier.androidx_scroll(): Modifier = this.then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))
