package dev.krishnagurram.kassa.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.krishnagurram.kassa.domain.Account
import dev.krishnagurram.kassa.domain.AccountType
import dev.krishnagurram.kassa.domain.Holding
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.Period
import dev.krishnagurram.kassa.domain.Transaction
import dev.krishnagurram.kassa.domain.TxKind
import dev.krishnagurram.kassa.domain.effectOn
import dev.krishnagurram.kassa.ui.Ledger
import dev.krishnagurram.kassa.ui.common.EntryRow
import dev.krishnagurram.kassa.ui.common.Gap
import dev.krishnagurram.kassa.ui.common.GlyphBadge
import dev.krishnagurram.kassa.ui.common.PageTop
import dev.krishnagurram.kassa.ui.common.PeriodSwitcher
import dev.krishnagurram.kassa.ui.common.RaisedPanel
import dev.krishnagurram.kassa.ui.common.SectionHeader
import dev.krishnagurram.kassa.ui.common.TransactionRow
import dev.krishnagurram.kassa.ui.common.swipeShift
import dev.krishnagurram.kassa.ui.importer.PrimaryButton
import dev.krishnagurram.kassa.ui.importer.SecondaryButton
import dev.krishnagurram.kassa.ui.invest.HoldingSheet
import dev.krishnagurram.kassa.ui.invest.PricesSheet
import dev.krishnagurram.kassa.ui.invest.TradeSheet
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType
import dev.krishnagurram.kassa.ui.today.BigMoney
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * One account, built like the card page: a summary with two equal boxes, then the details.
 * Bank and cash go month by month (arrows or a swipe, like Activity): the balance at the end of the month, money in
 * and out, and that month's entries with the balance after each. Investments have their own body, [InvestmentBody].
 */
@Composable
fun AccountPage(
    ledger: Ledger,
    account: Account,
    history: List<Transaction>,
    contentPadding: PaddingValues,
    onBack: (() -> Unit)?,
    onEditEntry: (Transaction) -> Unit,
    onEditAccount: () -> Unit,
    onSaveHolding: (Holding) -> Unit,
    onDeleteHolding: (Holding) -> Unit,
    onBuy: (String, Long, Long) -> Unit,
    onSell: (String, Long, Long) -> Unit,
) {
    val balance = ledger.accounts.balances[account.id] ?: 0
    // The balance right after each entry: walk back from today's balance.
    val after = remember(history, balance) {
        var running = balance
        history.map { t -> (t to running).also { running -= t.effectOn(account.id) } }
    }
    val title = account.name + (account.last4?.takeIf { it.isNotBlank() }?.let { "  ··$it" } ?: "")
    val top: @Composable () -> Unit = {
        PageTop(title, onBack) { TextButton(onClick = onEditAccount) { Text("Edit", style = KassaType.bodyStrong, color = Kassa.colors.ink) } }
    }
    if (account.type == AccountType.INVESTMENT) {
        InvestmentBody(ledger, account, balance, history, after, contentPadding, top, onEditEntry, onSaveHolding, onDeleteHolding, onBuy, onSell)
        return
    }

    var monthsBack by rememberSaveable(account.id) { mutableIntStateOf(0) }
    val period = Period.startingIn(ledger.period.yearMonth.minusMonths(monthsBack.toLong()), ledger.monthStartDay)
    fun dateOf(t: Transaction) = Instant.ofEpochMilli(t.epochMillis).atZone(ledger.zone).toLocalDate()
    val earliest = history.minOfOrNull { it.epochMillis }?.let { Period.containing(Instant.ofEpochMilli(it).atZone(ledger.zone).toLocalDate(), ledger.monthStartDay).start }
    val canGoBack = earliest != null && period.start.isAfter(earliest)
    val rows = after.filter { (t, _) -> dateOf(t) in period }
    val endBalance = balance - history.filter { dateOf(it).isAfter(period.endInclusive) }.sumOf { it.effectOn(account.id) }
    val moneyIn = rows.sumOf { (t, _) -> maxOf(0L, t.effectOn(account.id)) }
    val moneyOut = rows.sumOf { (t, _) -> maxOf(0L, -t.effectOn(account.id)) }
    val debt = account.type.isDebt
    val shift: (Int) -> Unit = { d -> if (d < 0 && canGoBack) monthsBack++ else if (d > 0 && monthsBack > 0) monthsBack-- }

    LazyColumn(Modifier.fillMaxSize().swipeShift(shift), contentPadding = contentPadding) {
        item {
            Column(Modifier.widthIn(max = 720.dp)) {
                top()
                PeriodSwitcher(
                    "${period.yearMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${period.yearMonth.year}",
                    if (monthsBack == 0) "So far" else null, canGoBack, monthsBack > 0, onBack = { shift(-1) }, onForward = { shift(1) },
                )
                Gap(12)
                RaisedPanel {
                    if (account.type == AccountType.CREDIT_CARD) {
                        // A card without statement days can't be split into payable and outstanding yet.
                        Text("Total balance", style = KassaType.tiny, color = Kassa.colors.ink2)
                        BigMoney(-balance, sizeSp = 36f)
                        Text("Add the statement day and due day to split this into balance payable and outstanding.", style = KassaType.small, color = Kassa.colors.ink2)
                        TextButton(onClick = onEditAccount) { Text("Add statement days", color = Kassa.colors.ink) }
                    } else {
                        Text(
                            when {
                                monthsBack == 0 && debt -> "Balance owed"
                                monthsBack == 0 -> "Balance today"
                                else -> "Balance on ${period.endInclusive.format(DateTimeFormatter.ofPattern("MMM d"))}"
                            },
                            style = KassaType.tiny, color = Kassa.colors.ink2,
                        )
                        BigMoney(if (debt) -endBalance else endBalance, sizeSp = 36f)
                        TwoBoxes("In" to Money.format(moneyIn), "Out" to Money.format(moneyOut), aColor = Kassa.colors.positive)
                    }
                }
                if (rows.isEmpty()) Text("Nothing in this month.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 24.dp))
                Gap(8)
            }
        }
        items(rows, key = { it.first.id }) { (t, balanceAfter) ->
            Column(Modifier.widthIn(max = 720.dp)) {
                TransactionRow(t, ledger, showDivider = t !== rows.last().first, noteOverride = "Balance ${Money.format(balanceAfter)}", onClick = onEditEntry, showDate = true)
            }
        }
    }
}

/** An investment account: total value, gain on what you put in, holdings, and its cash moves by month. */
@Composable
private fun InvestmentBody(
    ledger: Ledger,
    account: Account,
    cash: Long,
    history: List<Transaction>,
    after: List<Pair<Transaction, Long>>,
    contentPadding: PaddingValues,
    top: @Composable () -> Unit,
    onEditEntry: (Transaction) -> Unit,
    onSaveHolding: (Holding) -> Unit,
    onDeleteHolding: (Holding) -> Unit,
    onBuy: (String, Long, Long) -> Unit,
    onSell: (String, Long, Long) -> Unit,
) {
    val holdings = ledger.holdings.filter { it.accountId == account.id }.sortedByDescending { it.value }
    val holdingsValue = holdings.sumOf { it.value }
    val value = cash + holdingsValue
    // What you put in: transfers in minus transfers out, plus income paid in. Trades move money inside and don't count.
    val putIn = history.filter { it.kind == TxKind.TRANSFER }.sumOf {
        when (account.id) { it.toAccountId -> it.amount; it.accountId -> -it.amount; else -> 0L }
    } + history.filter { it.kind == TxKind.INCOME && it.accountId == account.id }.sumOf { it.amount }
    val gain = value - putIn
    val newHolding = { Holding(accountId = account.id, ticker = "", microShares = 0, price = 0, priceMillis = 0) }
    var holdingOpen by remember { mutableStateOf<Holding?>(null) }
    var pricing by remember { mutableStateOf(false) }
    var trading by remember { mutableStateOf(false) }
    val months = after.groupBy { (t, _) -> YearMonth.from(Instant.ofEpochMilli(t.epochMillis).atZone(ledger.zone)) }.toList()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Column(Modifier.widthIn(max = 720.dp)) {
                top()
                RaisedPanel {
                    Text("Total value", style = KassaType.tiny, color = Kassa.colors.ink2)
                    BigMoney(value, sizeSp = 36f)
                    if (putIn > 0) {
                        val percent = BigDecimal.valueOf(gain * 1000 / putIn).movePointLeft(1).toPlainString()
                        Text(
                            "${if (gain >= 0) "+" else "−"}${Money.format(Math.abs(gain))} ($percent%) on what you put in",
                            style = KassaType.bodyStrong, color = if (gain >= 0) Kassa.colors.positive else Kassa.colors.attention,
                        )
                    }
                    TwoBoxes((if (putIn > 0) "You put in" else "Holdings") to Money.format(if (putIn > 0) putIn else holdingsValue), "Cash" to Money.format(cash))
                    Gap(12)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) {
                            PrimaryButton(if (holdings.isEmpty()) "Add a holding" else "Update prices") {
                                if (holdings.isEmpty()) holdingOpen = newHolding() else pricing = true
                            }
                        }
                        SecondaryButton("Buy or sell", { trading = true }, Modifier.weight(1f))
                    }
                }
                if (holdings.isNotEmpty()) {
                    val oldest = Instant.ofEpochMilli(holdings.minOf { it.priceMillis }).atZone(ledger.zone)
                    val days = ChronoUnit.DAYS.between(oldest.toLocalDate(), ledger.today)
                    SectionHeader("Holdings", if (days > 14) "Prices $days days old" else "Prices from ${oldest.format(DateTimeFormatter.ofPattern("MMM d"))}")
                    holdings.forEachIndexed { i, h ->
                        EntryRow(
                            badge = { GlyphBadge(h.ticker.take(2)) },
                            title = h.ticker,
                            subtitle = "${BigDecimal.valueOf(h.microShares).movePointLeft(6).stripTrailingZeros().toPlainString()} shares · ${Money.format(h.price)}",
                            amount = Money.format(h.value),
                            note = if (holdingsValue > 0) "${Math.round(h.value * 100.0 / holdingsValue)}%" else null,
                            showDivider = i < holdings.lastIndex,
                            modifier = Modifier.clickable { holdingOpen = h },
                        )
                    }
                    TextButton(onClick = { holdingOpen = newHolding() }) { Text("Add a holding", color = Kassa.colors.ink) }
                }
                if (history.isEmpty()) Text("No money in or out yet.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 24.dp))
            }
        }
        months.forEach { (month, rows) ->
            item(key = "m$month") {
                Column(Modifier.widthIn(max = 720.dp)) { SectionHeader("Money in and out · " + month.format(DateTimeFormatter.ofPattern("MMMM yyyy"))) }
            }
            items(rows, key = { it.first.id }) { (t, cashAfter) ->
                Column(Modifier.widthIn(max = 720.dp)) {
                    TransactionRow(t, ledger, showDivider = t !== rows.last().first, noteOverride = "Cash ${Money.format(cashAfter)}", onClick = onEditEntry, showDate = true)
                }
            }
        }
    }

    holdingOpen?.let { h ->
        HoldingSheet(h, onSave = { onSaveHolding(it); holdingOpen = null }, onDelete = { onDeleteHolding(it); holdingOpen = null }, onDismiss = { holdingOpen = null })
    }
    if (pricing) PricesSheet(holdings, onSave = { list -> list.forEach(onSaveHolding); pricing = false }, onDismiss = { pricing = false })
    if (trading) TradeSheet(
        holdings, cash,
        onBuy = { ticker, shares, price -> onBuy(ticker, shares, price); trading = false },
        onSell = { ticker, shares, price -> onSell(ticker, shares, price); trading = false },
        onDismiss = { trading = false },
    )
}
