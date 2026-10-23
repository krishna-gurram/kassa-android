package dev.krishnagurram.kassa.ui.invest

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
import dev.krishnagurram.kassa.domain.Holding
import dev.krishnagurram.kassa.domain.InputRules
import dev.krishnagurram.kassa.domain.InvestmentTransfer
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.Shares
import dev.krishnagurram.kassa.ui.Ledger
import dev.krishnagurram.kassa.ui.common.AmountField
import dev.krishnagurram.kassa.ui.common.ConfirmDialog
import dev.krishnagurram.kassa.ui.common.Gap
import dev.krishnagurram.kassa.ui.entry.Chip
import dev.krishnagurram.kassa.ui.importer.PrimaryButton
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType

/*
 * Sheets for investment accounts. Kassa never looks prices up: every price here is typed by you.
 * Shares are parsed with [Shares] and prices with [Money]; the repository checks both again on save.
 */

/** A trade as typed: ticker, shares and price, with each field's problem. */
private class TradeInput(ticker: String = "", price: String = "") {
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
        tickerProblem = InputRules.tickerProblem(ticker)
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
    onBuy: (ticker: String, microShares: Long, price: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var buying by remember { mutableStateOf(false) }
    val input = remember { TradeInput() }
    val existing = ledger.holdings.filter { it.accountId == p.accountId }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Kassa.colors.raised) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
            Text("Where did ${Money.format(p.amount)} go?", style = KassaType.section, color = Kassa.colors.ink)
            Text("Your answer keeps net worth accurate.", style = KassaType.body, color = Kassa.colors.ink2)
            if (!buying) {
                Option("It's uninvested cash", "Stays as cash in the brokerage account", onCash)
                Option("I bought something", "Add the ticker, shares and price") { buying = true }
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
            PrimaryButton("Add to holdings") { if (input.check()) onBuy(input.cleanTicker, input.microShares!!, input.priceCents!!) }
            TextButton(onClick = { buying = false }) { Text("Back", color = Kassa.colors.ink2) }
        }
    }
}

/** Update a holding's price or share count, or add a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoldingSheet(
    holding: Holding,
    onSave: (Holding) -> Unit,
    onDelete: (Holding) -> Unit,
    onDismiss: () -> Unit,
) {
    val isNew = holding.id == 0L
    val input = remember {
        TradeInput(holding.ticker, if (isNew) "" else Money.toInput(holding.price)).apply { if (!isNew) shares = Shares.format(holding.microShares) }
    }
    var confirmRemove by remember { mutableStateOf(false) }
    if (confirmRemove) ConfirmDialog(
        title = "Remove ${holding.ticker}?",
        body = "It comes off your holdings and net worth. Entries that moved money stay as they are.",
        confirm = "Remove", onConfirm = { onDelete(holding) }, onDismiss = { confirmRemove = false },
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Kassa.colors.raised) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
            Text(if (isNew) "Add a holding" else holding.ticker, style = KassaType.section, color = Kassa.colors.ink)
            if (!isNew) Text("Type today's price to update its market value. Kassa never looks prices up.", style = KassaType.body, color = Kassa.colors.ink2)
            if (isNew) TickerField(input, Modifier.fillMaxWidth().padding(top = 8.dp))
            SharesAndPrice(input, "Price per share")
            Gap(12)
            PrimaryButton(if (isNew) "Add holding" else "Save") {
                if (input.check()) onSave(holding.copy(ticker = input.cleanTicker, microShares = input.microShares!!, price = input.priceCents!!, priceMillis = System.currentTimeMillis()))
            }
            if (!isNew) TextButton(onClick = { confirmRemove = true }, modifier = Modifier.fillMaxWidth()) { Text("Remove holding", color = Kassa.colors.attention) }
        }
    }
}

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
fun TradeSheet(holdings: List<Holding>, cash: Long, onBuy: (String, Long, Long) -> Unit, onSell: (String, Long, Long) -> Unit, onDismiss: () -> Unit) {
    var buy by remember { mutableStateOf(true) }
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
            Text(if (buy) "Costs ${Money.format(total)} of ${Money.format(cash)} cash" else "Adds ${Money.format(total)} to cash",
                style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 8.dp))
            Gap(12)
            PrimaryButton(if (buy) "Save buy" else "Save sale") {
                if (!input.check()) return@PrimaryButton
                val held = holdings.firstOrNull { it.ticker == input.cleanTicker }
                when {
                    buy -> onBuy(input.cleanTicker, input.microShares!!, input.priceCents!!)
                    held == null -> input.tickerProblem = "Pick a holding to sell."
                    input.microShares!! > held.microShares -> input.sharesProblem = "You hold ${Shares.format(held.microShares)}."
                    else -> onSell(input.cleanTicker, input.microShares!!, input.priceCents!!)
                }
            }
            if (!buy) input.tickerProblem?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention) }
        }
    }
}
