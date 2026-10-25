package app.kassa.money.ui.invest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.domain.Holding
import app.kassa.money.domain.InputRules
import app.kassa.money.domain.InvestmentTransfer
import app.kassa.money.domain.Money
import app.kassa.money.domain.Shares
import app.kassa.money.domain.Trade
import app.kassa.money.domain.Transaction
import app.kassa.money.domain.TxKind
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.common.AmountField
import app.kassa.money.ui.common.ConfirmDialog
import app.kassa.money.ui.common.DateRow
import app.kassa.money.ui.common.momentOn
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.entry.Chip
import app.kassa.money.ui.importer.PrimaryButton
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * Sheets for investment accounts. Kassa never looks prices up: every price here is typed by you.
 * Shares are parsed with [Shares] and prices with [Money]; the repository checks both again on save.
 */

/** A buy or sale ready to save: what, how much, at what price, and when. */
data class TradeOrder(val ticker: String, val microShares: Long, val price: Long, val at: Long)

/**
 * A trade or holding as typed: ticker, shares and price, with each field's problem. [savedTicker] is the ticker
 * already stored, which passes as it is even if it predates the ticker rules.
 */
private class TradeInput(ticker: String = "", price: String = "", private val savedTicker: String? = null) {
    var ticker by mutableStateOf(ticker)
    var shares by mutableStateOf("")
    var price by mutableStateOf(price)
    var tickerProblem by mutableStateOf<String?>(null)
    var sharesProblem by mutableStateOf<String?>(null)
    var priceProblem by mutableStateOf<String?>(null)

    val microShares get() = Shares.parse(shares)
    val priceCents get() = Money.parseCents(price)
    /** The trade's total once shares and price are both typed. */
    val total get() = microShares?.let { s -> priceCents?.let { p -> Shares.cost(s, p) } }

    /** Checks every field; true when the trade can be saved. */
    fun check(): Boolean {
        tickerProblem = if (ticker == savedTicker) null else InputRules.tickerProblem(ticker)
        sharesProblem = Shares.problem(microShares)
        priceProblem = InputRules.amountProblem(priceCents)
        return tickerProblem == null && sharesProblem == null && priceProblem == null
    }

    fun pick(h: Holding) { ticker = h.ticker; price = Money.toInput(h.price); tickerProblem = null; priceProblem = null }

    val cleanTicker get() = ticker.trim().uppercase()
}

@Composable
private fun TickerField(input: TradeInput, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
        input.ticker, { input.ticker = InputRules.typed(it, InputRules.TICKER_MAX).uppercase().trim(); input.tickerProblem = null },
        singleLine = true, label = { Text("Ticker") }, isError = input.tickerProblem != null,
        supportingText = input.tickerProblem?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = modifier,
    )
}

@Composable
private fun SharesField(value: String, onChange: (String) -> Unit, problem: String?, modifier: Modifier) {
    OutlinedTextField(
        value, { onChange(Shares.typed(it)) }, singleLine = true, label = { Text("Shares") }, isError = problem != null,
        supportingText = problem?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier,
    )
}

/** Shares and price side by side. */
@Composable
private fun SharesAndPrice(input: TradeInput, priceLabel: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SharesField(input.shares, { input.shares = it; input.sharesProblem = null }, input.sharesProblem, Modifier.weight(1f))
        AmountField(input.price, { input.price = it; input.priceProblem = null }, priceLabel, Modifier.weight(1f), problem = input.priceProblem)
    }
}

/** Your holdings in this account as chips; picking one fills its ticker and last price. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HoldingChips(holdings: List<Holding>, input: TradeInput) {
    if (holdings.isNotEmpty()) FlowRow(Modifier.padding(top = 8.dp)) {
        holdings.forEach { h -> Chip(h.ticker, selected = input.cleanTicker == h.ticker, small = true) { input.pick(h) } }
    }
}

/** After moving money to an investment account: was it left as cash, or spent on shares? */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllocateSheet(
    p: InvestmentTransfer,
    ledger: Ledger,
    onCash: () -> Unit,
    /** The user already added what it bought; just stop asking. */
    onRecorded: () -> Unit,
    onBuy: (TradeOrder) -> Unit,
    onDismiss: () -> Unit,
) {
    var buying by remember { mutableStateOf(false) }
    val input = remember { TradeInput() }
    // Most buys happen the day the money arrives, so the date starts there.
    var date by remember { mutableStateOf(Instant.ofEpochMilli(p.epochMillis).atZone(ledger.zone).toLocalDate()) }
    val existing = ledger.holdings.filter { it.accountId == p.accountId }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Kassa.colors.raised) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
            Text("Where did ${Money.format(p.amount)} go?", style = KassaType.section, color = Kassa.colors.ink)
            Text("Your answer keeps net worth accurate.", style = KassaType.body, color = Kassa.colors.ink2)
            if (!buying) {
                Option("It's uninvested cash", "Stays as cash in the brokerage account", onCash)
                Option("I bought something", "Add the ticker, shares and price") { buying = true }
                Option("Already recorded", "You added the holding yourself. Nothing changes.", onRecorded)
                Option("Decide later", "Stays on Overview until you answer", onDismiss)
                return@Column
            }
            HoldingChips(existing, input)
            TickerField(input, Modifier.fillMaxWidth().padding(top = 8.dp))
            SharesAndPrice(input, "Price each")
            val cost = input.total
            Text(
                when {
                    cost == null -> "Anything left over stays as uninvested cash."
                    cost > p.amount -> "That costs ${Money.format(cost)}, more than the ${Money.format(p.amount)} you moved."
                    else -> "${Money.format(cost)} invested, ${Money.format(p.amount - cost)} stays as uninvested cash."
                },
                style = KassaType.small, color = if (cost != null && cost > p.amount) Kassa.colors.attention else Kassa.colors.ink2,
                modifier = Modifier.padding(top = 8.dp),
            )
            Gap(12)
            Text("Date", style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
            DateRow(date, ledger.today) { date = it }
            Gap(8)
            PrimaryButton("Add to holdings") {
                if (input.check()) onBuy(TradeOrder(input.cleanTicker, input.microShares!!, input.priceCents!!, momentOn(date, ledger.today, ledger.zone)))
            }
            TextButton(onClick = { buying = false }) { Text("Back", color = Kassa.colors.ink2) }
        }
    }
}

/**
 * A holding's sheet. For a holding you have, the only thing to type is today's price: shares and average cost change
 * only through trades, so they always match your cash. The one exception is a holding with no average cost yet
 * (brought over from another app), which asks once what you paid.
 *
 * A new holding ([Holding.id] 0) is "shares you already own": ticker, shares and what you paid, with no cash moving.
 * Its price starts at what you paid.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoldingSheet(
    holding: Holding,
    onSave: (Holding) -> Unit,
    onDelete: (Holding) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Kassa.colors.raised) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp).verticalScroll(rememberScrollState())) {
            if (holding.id == 0L) NewHolding(holding, onSave) else ExistingHolding(holding, onSave, onDelete)
        }
    }
}

/** Shares you already own: ticker, shares and what you paid. */
@Composable
private fun NewHolding(holding: Holding, onSave: (Holding) -> Unit) {
    val input = remember { TradeInput() }
    Text("Add shares you own", style = KassaType.section, color = Kassa.colors.ink)
    TickerField(input, Modifier.fillMaxWidth().padding(top = 8.dp))
    SharesField(input.shares, { input.shares = it; input.sharesProblem = null }, input.sharesProblem, Modifier.fillMaxWidth())
    AmountField(input.price, { input.price = it; input.priceProblem = null }, "Price you paid per share", Modifier.fillMaxWidth(), problem = input.priceProblem)
    Text("Your broker's \"Average cost\".", style = KassaType.small, color = Kassa.colors.ink2)
    Gap(12)
    PrimaryButton("Add shares") {
        if (input.check()) {
            val paid = input.priceCents!!
            onSave(holding.copy(ticker = input.cleanTicker, microShares = input.microShares!!, price = paid, priceMillis = System.currentTimeMillis(), avgCost = paid))
        }
    }
}

/** A holding you have: its figures, and today's price to type. */
@Composable
private fun ExistingHolding(holding: Holding, onSave: (Holding) -> Unit, onDelete: (Holding) -> Unit) {
    var price by remember { mutableStateOf(Money.toInput(holding.price)) }
    var priceProblem by remember { mutableStateOf<String?>(null) }
    // Asked only while unknown; once saved it's a figure like the rest.
    val askPaid = holding.avgCost == null
    var paid by remember { mutableStateOf("") }
    var paidProblem by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val preview = holding.copy(
        price = Money.parseCents(price) ?: holding.price,
        avgCost = holding.avgCost ?: Money.parseCents(paid),
    )

    Text(holding.ticker, style = KassaType.section, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(Kassa.colors.ground).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Figure("Shares", Shares.format(holding.microShares))
        holding.avgCost?.let { Figure("Average cost", Money.format(it)) }
        Figure("Worth", Money.format(preview.value))
        preview.profit?.let { p ->
            val cost = preview.cost ?: 0L
            Figure(
                "Profit", Money.format(p, showPlus = true) + if (cost > 0) " · ${percentOf(Math.abs(p), cost)}" else "",
                if (p < 0) Kassa.colors.attention else Kassa.colors.positive,
            )
        }
    }
    AmountField(price, { price = it; priceProblem = null }, "Price today", Modifier.fillMaxWidth().padding(top = 8.dp), problem = priceProblem)
    if (askPaid) {
        AmountField(paid, { paid = it; paidProblem = null }, "Price you paid per share", Modifier.fillMaxWidth(), problem = paidProblem, placeholder = "Optional")
        Text("Your broker's \"Average cost\".", style = KassaType.small, color = Kassa.colors.ink2)
    }
    Gap(12)
    PrimaryButton(if (askPaid) "Save" else "Save price") {
        val cents = Money.parseCents(price)
        priceProblem = InputRules.amountProblem(cents)
        val paidCents = if (paid.isBlank()) null else Money.parseCents(paid)
        paidProblem = if (paid.isBlank()) null else InputRules.amountProblem(paidCents)
        if (priceProblem == null && paidProblem == null) {
            onSave(holding.copy(
                price = cents!!,
                // The price date says how fresh the price is, so it only moves when the price does.
                priceMillis = if (cents != holding.price) System.currentTimeMillis() else holding.priceMillis,
                avgCost = holding.avgCost ?: paidCents,
            ))
        }
    }
    TextButton(onClick = { confirmRemove = true }, modifier = Modifier.fillMaxWidth()) { Text("Remove holding", color = Kassa.colors.attention) }
    if (confirmRemove) ConfirmDialog(
        title = "Remove ${holding.ticker}?",
        body = "It comes off your holdings and net worth. No cash moves; trades stay as they are.",
        confirm = "Remove", onConfirm = { onDelete(holding) }, onDismiss = { confirmRemove = false },
    )
}

/** One labelled figure in a holding's summary. */
@Composable
private fun Figure(label: String, value: String, color: androidx.compose.ui.graphics.Color = Kassa.colors.ink) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.weight(1f))
        Text(value, style = KassaType.bodyStrong, color = color, maxLines = 1)
    }
}

/** [part] as a percent of [whole] with one decimal, like "17.2%". */
internal fun percentOf(part: Long, whole: Long): String =
    java.math.BigDecimal.valueOf(part).multiply(java.math.BigDecimal.valueOf(100))
        .divide(java.math.BigDecimal.valueOf(whole), 1, java.math.RoundingMode.HALF_UP).toPlainString() + "%"


@Composable
private fun Option(title: String, sub: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(shape).border(1.dp, Kassa.colors.line, shape).clickable(onClick = onClick).padding(16.dp)) {
        Text(title, style = KassaType.bodyStrong, color = Kassa.colors.ink)
        Text(sub, style = KassaType.small, color = Kassa.colors.ink2)
    }
}

/** Every holding's price in one sheet, so one pass brings the account up to date. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PricesSheet(holdings: List<Holding>, onSave: (List<Holding>) -> Unit, onDismiss: () -> Unit) {
    val prices = remember { mutableStateMapOf<Long, String>().apply { holdings.forEach { put(it.id, Money.toInput(it.price)) } } }
    val problems = remember { mutableStateMapOf<Long, String>() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Kassa.colors.raised) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp).verticalScroll(rememberScrollState())) {
            Text("Update prices", style = KassaType.section, color = Kassa.colors.ink)
            holdings.forEach { h ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(h.ticker, style = KassaType.bodyStrong, color = Kassa.colors.ink)
                        Text("${Shares.format(h.microShares)} shares", style = KassaType.small, color = Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    AmountField(prices[h.id].orEmpty(), { prices[h.id] = it; problems.remove(h.id) }, "Price", Modifier.width(160.dp), problem = problems[h.id])
                }
            }
            Gap(12)
            PrimaryButton("Save prices") {
                val now = System.currentTimeMillis()
                problems.clear()
                holdings.forEach { h -> InputRules.amountProblem(Money.parseCents(prices[h.id].orEmpty()))?.let { problems[h.id] = it } }
                if (problems.isEmpty()) onSave(holdings.map { h -> h.copy(price = Money.parseCents(prices[h.id].orEmpty())!!, priceMillis = now) })
            }
        }
    }
}

/** A buy or a sale: cash moves inside the account and the holding grows or shrinks. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TradeSheet(
    holdings: List<Holding>,
    cash: Long,
    today: LocalDate,
    zone: ZoneId,
    onBuy: (TradeOrder) -> Unit,
    onSell: (TradeOrder) -> Unit,
    onDismiss: () -> Unit,
) {
    var buy by remember { mutableStateOf(true) }
    var date by remember { mutableStateOf(today) }
    val input = remember { TradeInput().apply { holdings.firstOrNull()?.let { pick(it) } } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Kassa.colors.raised) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
            Text("Buy or sell", style = KassaType.section, color = Kassa.colors.ink)
            FlowRow(Modifier.padding(top = 10.dp)) {
                Chip("Buy", selected = buy) { buy = true }
                Chip("Sell", selected = !buy) { buy = false }
            }
            HoldingChips(holdings, input)
            // A sale is always of something you hold, so it's picked from the chips.
            if (buy) TickerField(input)
            SharesAndPrice(input, "Price")
            val total = input.total ?: 0L
            Text("Date", style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
            DateRow(date, today) { date = it }
            Text(if (buy) "Costs ${Money.format(total)} of ${Money.format(cash)} cash" else "Adds ${Money.format(total)} to cash",
                style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 4.dp))
            Gap(12)
            PrimaryButton(if (buy) "Save buy" else "Save sale") {
                if (!input.check()) return@PrimaryButton
                val held = holdings.firstOrNull { it.ticker == input.cleanTicker }
                val order = TradeOrder(input.cleanTicker, input.microShares!!, input.priceCents!!, momentOn(date, today, zone))
                when {
                    buy -> onBuy(order)
                    held == null -> input.tickerProblem = "Pick a holding to sell."
                    input.microShares!! > held.microShares -> input.sharesProblem = "You hold ${Shares.format(held.microShares)}."
                    else -> onSell(order)
                }
            }
            if (!buy) input.tickerProblem?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention) }
        }
    }
}

/**
 * An existing buy or sale. When its shares were recorded ([trade]), everything can change and saving updates the
 * cash and the holding together. Older trades only kept their total, so only their date can change. Either can be
 * deleted, after asking.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradeEditSheet(
    entry: Transaction,
    trade: Trade?,
    today: LocalDate,
    zone: ZoneId,
    onSave: (TradeOrder) -> Unit,
    onMove: (Long) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val buy = entry.kind == TxKind.BUY
    val ticker = trade?.ticker ?: entry.note
    val originalDate = Instant.ofEpochMilli(entry.epochMillis).atZone(zone).toLocalDate()
    var date by remember { mutableStateOf(originalDate) }
    val input = remember {
        TradeInput(ticker, trade?.let { Money.toInput(it.price) }.orEmpty(), savedTicker = ticker)
            .apply { trade?.let { shares = Shares.format(it.microShares) } }
    }
    var confirmDelete by remember { mutableStateOf(false) }
    // An unchanged date keeps the original time of day, so the entry doesn't jump within its day.
    val at = { if (date == originalDate) entry.epochMillis else momentOn(date, today, zone) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Kassa.colors.raised) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp).verticalScroll(rememberScrollState())) {
            if (trade != null) {
                Text(if (buy) "Edit buy" else "Edit sale", style = KassaType.section, color = Kassa.colors.ink)
                TickerField(input, Modifier.fillMaxWidth().padding(top = 8.dp))
                SharesAndPrice(input, "Price")
            } else {
                Text("${if (buy) "Buy" else "Sale"} of $ticker · ${Money.format(entry.amount)}", style = KassaType.section, color = Kassa.colors.ink,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Note("This trade is from before Kassa kept shares and price, so only its date can change. To change your $ticker shares, open $ticker under Holdings.")
            }
            Text("Date", style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
            DateRow(date, today) { date = it }
            if (trade != null) {
                input.total?.let { Text("Total ${Money.format(it)}", style = KassaType.small, color = Kassa.colors.ink2) }
                Note("Saving updates the cash and your $ticker shares${if (buy) " and average cost" else ""} together.")
            }
            Gap(12)
            PrimaryButton(if (trade != null) "Save changes" else "Save date") {
                when {
                    trade == null -> onMove(at())
                    input.check() -> onSave(TradeOrder(input.cleanTicker, input.microShares!!, input.priceCents!!, at()))
                }
            }
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (buy) "Delete buy" else "Delete sale", color = Kassa.colors.attention, style = KassaType.bodyStrong)
            }
        }
    }
    if (confirmDelete) ConfirmDialog(
        title = if (buy) "Delete this buy?" else "Delete this sale?",
        body = when {
            trade != null && buy -> "${Money.format(entry.amount)} goes back to cash and ${Shares.format(trade.microShares)} $ticker shares come off. This can't be undone."
            trade != null -> "${Money.format(entry.amount)} comes out of cash and ${Shares.format(trade.microShares)} $ticker shares come back. This can't be undone."
            buy -> "${Money.format(entry.amount)} goes back to cash. Your $ticker shares stay as they are; edit $ticker to change them."
            else -> "${Money.format(entry.amount)} comes out of cash. Your $ticker shares stay as they are; edit $ticker to change them."
        },
        confirm = "Delete", onConfirm = onDelete, onDismiss = { confirmDelete = false },
    )
}

/** A short explanation in a quiet box. */
@Composable
private fun Note(text: String) {
    Text(
        text, style = KassaType.small, color = Kassa.colors.ink2,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(Kassa.colors.ground).padding(12.dp),
    )
}
