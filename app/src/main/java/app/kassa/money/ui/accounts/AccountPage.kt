package app.kassa.money.ui.accounts

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import app.kassa.money.domain.Shares
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
import app.kassa.money.domain.Account
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.Holding
import app.kassa.money.domain.LoanTerms
import app.kassa.money.domain.Money
import app.kassa.money.domain.Period
import app.kassa.money.domain.Transaction
import app.kassa.money.domain.TxKind
import app.kassa.money.domain.effectOn
import app.kassa.money.ui.common.monthYear
import app.kassa.money.ui.common.shortDate
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.common.favourColor
import app.kassa.money.ui.common.figureColor
import app.kassa.money.ui.common.balanceColor
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.GlyphBadge
import app.kassa.money.ui.common.PageTop
import app.kassa.money.ui.common.PeriodSwitcher
import app.kassa.money.ui.common.RaisedPanel
import app.kassa.money.ui.common.SectionHeader
import app.kassa.money.ui.common.TransactionRow
import app.kassa.money.ui.common.swipeShift
import app.kassa.money.ui.importer.PrimaryButton
import app.kassa.money.ui.importer.SecondaryButton
import app.kassa.money.ui.invest.HoldingSheet
import app.kassa.money.ui.loans.LoanHero
import app.kassa.money.ui.loans.LoanPageActions
import app.kassa.money.ui.loans.loanSections
import app.kassa.money.ui.loans.rememberLoanSummary
import app.kassa.money.ui.invest.PricesSheet
import app.kassa.money.ui.invest.TradeOrder
import app.kassa.money.ui.invest.percentOf
import app.kassa.money.ui.invest.TradeSheet
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import app.kassa.money.ui.today.BigMoney
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.time.temporal.ChronoUnit

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
    onBuy: (TradeOrder) -> Unit,
    onSell: (TradeOrder) -> Unit,
    /** Close and Reopen; null where closing isn't offered. */
    closing: AccountClosing? = null,
    /** The loan's terms when it has them; a loan without terms offers [onAddTerms] instead of [onPayLoan]. */
    loanTerms: LoanTerms? = null,
    onPayLoan: () -> Unit = {},
    onAddTerms: () -> Unit = {},
    onChangeTerms: () -> Unit = {},
    onEditDetails: () -> Unit = {},
    onCorrectBalance: () -> Unit = {},
    onRemoveChange: (Long) -> Unit = {},
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
        InvestmentBody(ledger, account, balance, history, after, contentPadding, top, onEditEntry, onSaveHolding, onDeleteHolding, onBuy, onSell, status = { AccountStatus(ledger, account, closing) })
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
    // A loan with terms has its own page: outstanding balance, payment schedule, terms. Its entries follow, month by month.
    val loanSummary = if (account.type == AccountType.LOAN && loanTerms != null) rememberLoanSummary(ledger, account, loanTerms, balance) else null
    var showFullSchedule by rememberSaveable(account.id) { mutableStateOf(false) }
    val periodSwitcher: @Composable () -> Unit = {
        PeriodSwitcher(
            monthYear(period.yearMonth),
            if (monthsBack == 0) "So far" else null, canGoBack, monthsBack > 0, onBack = { shift(-1) }, onForward = { shift(1) },
        )
    }

    LazyColumn(Modifier.fillMaxSize().swipeShift(shift), contentPadding = contentPadding) {
        item {
            Column(Modifier.widthIn(max = 720.dp)) {
                top()
                if (loanSummary != null && loanTerms != null) LoanHero(loanSummary, loanTerms, balance)
                else periodSwitcher()
                if (loanSummary == null) Gap(12)
                if (loanSummary == null) RaisedPanel(tone = if (debt && balance < 0) app.kassa.money.ui.common.HeroTone.ORANGE else app.kassa.money.ui.common.HeroTone.GREEN) {
                    if (account.type == AccountType.CREDIT_CARD && account.isActive) {
                        // A card without statement days can't be split into payable and outstanding yet.
                        Text("Total balance", style = KassaType.tiny, color = Kassa.colors.ink2)
                        // What's owed, never with a minus; a credit shows green.
                        BigMoney(Math.abs(balance), sizeSp = 36f, color = app.kassa.money.ui.cards.cardTint(-balance))
                        Text("Add the statement day and due day to split this into balance payable and outstanding.", style = KassaType.small, color = Kassa.colors.ink2)
                        TextButton(onClick = onEditAccount) { Text("Add statement days", color = Kassa.colors.ink) }
                    } else {
                        Text(
                            when {
                                monthsBack == 0 && debt -> "Outstanding balance"
                                monthsBack == 0 -> "Balance today"
                                else -> "Balance on ${shortDate(period.endInclusive)}"
                            },
                            style = KassaType.tiny, color = Kassa.colors.ink2,
                        )
                        if (debt) BigMoney(Math.abs(endBalance), sizeSp = 36f, color = app.kassa.money.ui.cards.cardTint(-endBalance))
                        else BigMoney(endBalance, sizeSp = 36f, color = balanceColor(account.type, endBalance))
                        TwoBoxes("In" to Money.format(moneyIn), "Out" to Money.format(moneyOut), aColor = Kassa.colors.positive)
                        if (account.type == AccountType.LOAN && monthsBack == 0) LoanActions(account, loanTerms, onAddTerms)
                    }
                }
                AccountStatus(ledger, account, closing)
                if (loanSummary == null) {
                    if (rows.isEmpty()) Text("Nothing in this month.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 24.dp))
                    Gap(8)
                }
            }
        }
        if (loanSummary != null && loanTerms != null) {
            loanSections(
                ledger, account, loanTerms, loanSummary, showFullSchedule, { showFullSchedule = it },
                LoanPageActions(onPayLoan, onChangeTerms, onEditDetails, onCorrectBalance, onRemoveChange),
            )
            item(key = "loan-activity") {
                Column(Modifier.widthIn(max = 720.dp)) {
                    SectionHeader("Activity")
                    periodSwitcher()
                    if (rows.isEmpty()) Text("Nothing in this month.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 24.dp))
                    Gap(8)
                }
            }
        }
        items(rows, key = { it.first.id }) { (t, balanceAfter) ->
            Column(Modifier.widthIn(max = 720.dp)) {
                TransactionRow(t, ledger, showDivider = t !== rows.last().first, noteOverride = "Balance ${if (debt) app.kassa.money.ui.cards.cardMoney(-balanceAfter) else Money.format(balanceAfter)}", onClick = onEditEntry, showDate = true)
            }
        }
    }
}

/** What a loan account without terms offers: Add terms. A loan with terms has the fuller page, see `LoanHero`. */
@Composable
private fun LoanActions(account: Account, terms: LoanTerms?, onAddTerms: () -> Unit) {
    if (terms != null) return
    Gap(12)
    Text("Add the rate and payment and Kassa splits each payment into principal and interest.", style = KassaType.small, color = Kassa.colors.ink2)
    Gap(8)
    PrimaryButton("Add terms", onAddTerms)
}

/**
 * One holding: ticker and shares (with the average cost when known) on the left; what it's worth and its profit on
 * the right. Opens the holding's sheet.
 */
@Composable
private fun HoldingRow(h: Holding, showDivider: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphBadge(h.ticker.take(2))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(h.ticker, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${Shares.format(h.microShares)} shares" + (h.avgCost?.let { " · avg ${Money.format(it)}" } ?: ""),
                    style = KassaType.small, color = Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Money.format(h.value), style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1)
                val profit = h.profit
                val cost = h.cost
                if (profit == null || cost == null) Text("Add average cost", style = KassaType.small, color = Kassa.colors.ink3, maxLines = 1)
                else Text(
                    Money.format(profit, showPlus = true) + if (cost > 0 && !Money.isHidden) " · ${percentOf(Math.abs(profit), cost)}" else "",
                    style = KassaType.small, maxLines = 1,
                    color = figureColor(if (profit < 0) Kassa.colors.attention else Kassa.colors.positive),
                )
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = Kassa.colors.ink3, modifier = Modifier.padding(start = 4.dp).size(20.dp))
        }
        if (showDivider) HorizontalDivider(color = Kassa.colors.line)
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
    onBuy: (TradeOrder) -> Unit,
    onSell: (TradeOrder) -> Unit,
    status: @Composable () -> Unit = {},
) {
    val holdings = ledger.holdings.filter { it.accountId == account.id }.sortedByDescending { it.value }
    val holdingsValue = holdings.sumOf { it.value }
    val value = cash + holdingsValue
    // What you put in: the starting amount, transfers in minus transfers out, and income paid in.
    // Trades move money inside the account and don't count.
    val putIn = account.openingBalance + history.filter { it.kind == TxKind.TRANSFER }.sumOf {
        when (account.id) { it.toAccountId -> it.amount; it.accountId -> -it.amount; else -> 0L }
    } + history.filter { it.kind == TxKind.INCOME && it.accountId == account.id }.sumOf { it.amount }
    val gain = value - putIn
    val newHolding = { Holding(accountId = account.id, ticker = "", microShares = 0, price = 0, priceMillis = 0) }
    var holdingOpen by remember { mutableStateOf<Holding?>(null) }
    var pricing by rememberSaveable { mutableStateOf(false) }
    var trading by rememberSaveable { mutableStateOf(false) }
    val months = after.groupBy { (t, _) -> YearMonth.from(Instant.ofEpochMilli(t.epochMillis).atZone(ledger.zone)) }.toList()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Column(Modifier.widthIn(max = 720.dp)) {
                top()
                RaisedPanel {
                    Text("Total value", style = KassaType.tiny, color = Kassa.colors.ink2)
                    BigMoney(value, sizeSp = 36f, color = figureColor(if (value < 0) Kassa.colors.attention else Kassa.colors.ink))
                    if (putIn > 0) {
                        Text(
                            if (Money.isHidden) "${Money.MASK} on what you put in"
                            else "${if (gain >= 0) "+" else "−"}${Money.format(Math.abs(gain))} (${percentOf(Math.abs(gain), putIn)}) on what you put in",
                            style = KassaType.bodyStrong, color = figureColor(if (gain >= 0) Kassa.colors.positive else Kassa.colors.attention),
                        )
                    }
                    TwoBoxes((if (putIn > 0) "You put in" else "Holdings") to Money.format(if (putIn > 0) putIn else holdingsValue), "Cash" to Money.format(cash))
                    Gap(12)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) {
                            PrimaryButton(if (holdings.isEmpty()) "Add shares you own" else "Update prices") {
                                if (holdings.isEmpty()) holdingOpen = newHolding() else pricing = true
                            }
                        }
                        SecondaryButton("Buy or sell", { trading = true }, Modifier.weight(1f))
                    }
                }
                status()
                if (holdings.isNotEmpty()) {
                    val oldest = Instant.ofEpochMilli(holdings.minOf { it.priceMillis }).atZone(ledger.zone)
                    val days = ChronoUnit.DAYS.between(oldest.toLocalDate(), ledger.today)
                    SectionHeader("Holdings", if (days > 14) "Prices $days days old" else "Prices from ${shortDate(oldest.toLocalDate())}")
                    holdings.forEachIndexed { i, h -> HoldingRow(h, showDivider = i < holdings.lastIndex) { holdingOpen = h } }
                    TextButton(onClick = { holdingOpen = newHolding() }) { Text("Add shares you own", color = Kassa.colors.ink) }
                }
                if (history.isEmpty()) Text("No money in or out yet.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 24.dp))
            }
        }
        months.forEach { (month, rows) ->
            item(key = "m$month") {
                Column(Modifier.widthIn(max = 720.dp)) { SectionHeader("Money in and out · " + monthYear(month)) }
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
        today = ledger.today, zone = ledger.zone,
        onBuy = { onBuy(it); trading = false },
        onSell = { onSell(it); trading = false },
        onDismiss = { trading = false },
    )
}
