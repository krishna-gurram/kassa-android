package dev.krishnagurram.kassa.ui.activity

import android.app.Application
import androidx.compose.foundation.background
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
import dev.krishnagurram.kassa.KassaApplication
import dev.krishnagurram.kassa.domain.Account
import dev.krishnagurram.kassa.domain.Category
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.Transaction
import dev.krishnagurram.kassa.domain.TxKind
import dev.krishnagurram.kassa.ui.common.CategoryBadge
import dev.krishnagurram.kassa.ui.common.EntryRow
import dev.krishnagurram.kassa.ui.common.GlyphBadge
import dev.krishnagurram.kassa.ui.common.Stat
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

data class ActivityUi(
    val month: YearMonth,
    val isCurrentMonth: Boolean,
    val query: String,
    val days: List<Pair<LocalDate, List<Transaction>>>,
    val spent: Long,
    val income: Long,
    val categories: Map<Long, Category>,
    val accounts: Map<Long, Account>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as KassaApplication).repository
    private val zone = ZoneId.systemDefault()
    private val month = MutableStateFlow(YearMonth.now(zone))
    private val query = MutableStateFlow("")

    private val txs = month.flatMapLatest { m ->
        val from = m.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = m.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        repo.transactionsBetween(from, to)
    }

    val ui: StateFlow<ActivityUi?> = combine(month, query, txs, repo.categories, repo.accounts) { m, q, list, cats, accts ->
        val catMap = cats.associateBy { it.id }
        val acctMap = accts.accounts.associateBy { it.id }
        val needle = q.trim().lowercase()
        val filtered = if (needle.isEmpty()) list else list.filter { t ->
            listOfNotNull(
                t.note, t.categoryId?.let { catMap[it]?.name }, t.categoryId?.let { catMap[it]?.parentId?.let { p -> catMap[p]?.name } },
                acctMap[t.accountId]?.name, t.toAccountId?.let { acctMap[it]?.name },
            ).any { it.lowercase().contains(needle) }
        }
        ActivityUi(
            month = m,
            isCurrentMonth = m == YearMonth.now(zone),
            query = q,
            days = filtered.groupBy { Instant.ofEpochMilli(it.epochMillis).atZone(zone).toLocalDate() }.toList(),
            spent = list.filter { it.kind == TxKind.EXPENSE }.sumOf { it.categoryAmount },
            income = list.filter { it.kind == TxKind.INCOME }.sumOf { it.amount },
            categories = catMap,
            accounts = acctMap,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun shift(by: Long) { month.value = month.value.plusMonths(by) }
    fun search(q: String) { query.value = q }
}

@Composable
fun ActivityScreen(contentPadding: PaddingValues, vm: ActivityViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val s = ui ?: return
    val today = LocalDate.now()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Column(Modifier.widthIn(max = 720.dp)) {
                Text("${s.month.atDay(1).format(DateTimeFormatter.ofPattern("MMM d"))} to ${s.month.atEndOfMonth().format(DateTimeFormatter.ofPattern("MMM d"))}", style = KassaType.small, color = Kassa.colors.ink2)
                Text("Activity", style = KassaType.title, color = Kassa.colors.ink)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.shift(-1) }) { Icon(Chevron(left = true), "Previous month", tint = Kassa.colors.ink) }
                    Text(
                        "${s.month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${s.month.year}",
                        style = KassaType.bodyStrong, color = Kassa.colors.ink,
                        modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    IconButton(onClick = { vm.shift(1) }, enabled = !s.isCurrentMonth) {
                        Icon(Chevron(left = false), "Next month", tint = if (s.isCurrentMonth) Kassa.colors.ink3 else Kassa.colors.ink)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat("Spent", Money.format(s.spent, wholeDollars = true), modifier = Modifier.weight(1f))
                    Stat("Income", Money.format(s.income, wholeDollars = true), Kassa.colors.positive, Modifier.weight(1f))
                    Stat("Net", Money.format(s.income - s.spent, wholeDollars = true, showPlus = true), modifier = Modifier.weight(1f))
                }
                TextField(
                    value = s.query, onValueChange = vm::search, singleLine = true,
                    placeholder = { Text("Search notes, categories, accounts", style = KassaType.body) },
                    textStyle = KassaType.body,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Kassa.colors.chip, unfocusedContainerColor = Kassa.colors.chip,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
            }
        }
        if (s.days.isEmpty()) item {
            Text(
                if (s.query.isBlank()) "Nothing logged this month yet." else "Nothing matches “${s.query}” this month.",
                style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 32.dp),
            )
        }
        s.days.forEach { (day, entries) ->
            item(key = "h$day") {
                val spent = entries.filter { it.kind == TxKind.EXPENSE }.sumOf { it.categoryAmount }
                Column(Modifier.widthIn(max = 720.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(dayLabel(day, today), style = KassaType.tiny, color = Kassa.colors.ink2)
                        if (spent > 0) Text("${Money.format(spent)} spent", style = KassaType.tiny, color = Kassa.colors.ink2)
                    }
                    HorizontalDivider(color = Kassa.colors.line)
                }
            }
            items(entries, key = { it.id }) { t -> TxRow(t, s, Modifier.widthIn(max = 720.dp), last = t === entries.last()) }
        }
    }
}

@Composable
private fun TxRow(t: Transaction, s: ActivityUi, modifier: Modifier, last: Boolean) {
    val acct = s.accounts[t.accountId]?.name ?: "Unknown account"
    when (t.kind) {
        TxKind.TRANSFER -> EntryRow(
            badge = { GlyphBadge("⇄") },
            title = "To ${s.accounts[t.toAccountId]?.name ?: "Unknown account"}",
            subtitle = "From $acct",
            amount = Money.format(t.amount), amountColor = Kassa.colors.ink2,
            note = if (s.accounts[t.toAccountId]?.type?.hasStatement == true) "Card payment" else "Transfer",
            showDivider = !last, modifier = modifier,
        )
        else -> {
            val cat = t.categoryId?.let { s.categories[it] }
            val parent = cat?.parentId?.let { s.categories[it] }
            EntryRow(
                badge = { CategoryBadge(parent ?: cat) },
                title = t.note.ifBlank { cat?.name ?: "Uncategorized" },
                subtitle = listOfNotNull(cat?.name ?: "Uncategorized", t.myShare?.takeIf { it != t.amount }?.let { "your share ${Money.format(it)}" }).joinToString(", "),
                amount = if (t.kind == TxKind.INCOME) Money.format(t.amount, showPlus = true) else Money.format(t.amount),
                amountColor = if (t.kind == TxKind.INCOME) Kassa.colors.positive else Kassa.colors.ink,
                note = acct, showDivider = !last, modifier = modifier,
            )
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
