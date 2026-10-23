package dev.krishnagurram.kassa.ui.invest

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.krishnagurram.kassa.data.db.PendingEntity
import dev.krishnagurram.kassa.domain.Holding
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.ui.Ledger
import dev.krishnagurram.kassa.ui.common.Gap
import dev.krishnagurram.kassa.ui.entry.Chip
import dev.krishnagurram.kassa.ui.importer.PrimaryButton
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType
import java.math.BigDecimal

/** Shares typed as "1.5" become 1,500,000 millionths. */
fun parseShares(text: String): Long? = try {
    BigDecimal(text.trim()).movePointRight(6).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact().takeIf { it > 0 }
} catch (e: Exception) { null }

/** After moving money to an investment account: was it left as cash, or spent on shares? */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AllocateSheet(
    p: PendingEntity,
    ledger: Ledger,
    onCash: () -> Unit,
    onBuy: (ticker: String, microShares: Long, price: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var buying by remember { mutableStateOf(false) }
    var ticker by remember { mutableStateOf("") }
    var shares by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val existing = ledger.assist.holdings.filter { it.accountId == p.accountId }
    val cost = run {
        val s = parseShares(shares); val pr = Money.parseCents(price)
        if (s != null && pr != null) Math.round(s / 1_000_000.0 * pr) else null
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Kassa.colors.raised) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
            Text("Where did ${Money.format(p.amount)} go?", style = KassaType.section, color = Kassa.colors.ink)
            Text("Your answer keeps net worth accurate.", style = KassaType.body, color = Kassa.colors.ink2)
            if (!buying) {
                Option("It's uninvested cash", "Stays as cash in the brokerage account", onCash)
                Option("I bought something", "Add the ticker, shares and price") { buying = true }
                Option("Decide later", "Stays on Overview until you answer", onDismiss)
            } else {
                if (existing.isNotEmpty()) FlowRow(Modifier.padding(top = 12.dp)) {
                    existing.forEach { h -> Chip(h.ticker, selected = ticker.equals(h.ticker, true), small = true) { ticker = h.ticker; if (price.isBlank()) price = Money.format(h.price).removePrefix("$").replace(",", "") } }
                }
                OutlinedTextField(ticker, { ticker = it.uppercase() }, singleLine = true, label = { Text("Ticker") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Row {
                    OutlinedTextField(shares, { shares = it }, singleLine = true, label = { Text("Shares") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f).padding(top = 8.dp, end = 8.dp))
                    OutlinedTextField(price, { price = it }, singleLine = true, label = { Text("Price each") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f).padding(top = 8.dp))
                }
                Text(
                    when {
                        cost == null -> "Anything left over stays as uninvested cash."
                        cost > p.amount -> "That costs ${Money.format(cost)}, more than the ${Money.format(p.amount)} you moved."
                        else -> "${Money.format(cost)} invested, ${Money.format(p.amount - cost)} stays as uninvested cash."
                    },
                    style = KassaType.small, color = if (cost != null && cost > p.amount) Kassa.colors.attention else Kassa.colors.ink2,
                    modifier = Modifier.padding(top = 8.dp),
                )
                error?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention) }
                Gap(12)
                PrimaryButton("Add to holdings") {
                    val s = parseShares(shares); val pr = Money.parseCents(price)
                    error = when { ticker.isBlank() -> "Enter the ticker."; s == null -> "Enter how many shares."; pr == null || pr <= 0 -> "Enter the price per share."; else -> null }
                    if (error == null) onBuy(ticker.trim(), s!!, pr!!)
                }
                TextButton(onClick = { buying = false }) { Text("Back", color = Kassa.colors.ink2) }
            }
        }
    }
}

/** Update a holding's price (you type it; Kassa never fetches prices) or its share count, or add a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoldingSheet(
    holding: Holding,
    onSave: (Holding) -> Unit,
    onDelete: (Holding) -> Unit,
    onDismiss: () -> Unit,
) {
    val isNew = holding.id == 0L
    var ticker by remember { mutableStateOf(holding.ticker) }
    var shares by remember { mutableStateOf(if (isNew) "" else BigDecimal.valueOf(holding.microShares).movePointLeft(6).stripTrailingZeros().toPlainString()) }
    var price by remember { mutableStateOf(if (isNew) "" else BigDecimal.valueOf(holding.price).movePointLeft(2).toPlainString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    if (confirmRemove) dev.krishnagurram.kassa.ui.common.ConfirmDialog(
        title = "Remove ${holding.ticker}?",
        body = "It comes off your holdings and net worth. Entries that moved money stay as they are.",
        confirm = "Remove", onConfirm = { onDelete(holding) }, onDismiss = { confirmRemove = false },
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Kassa.colors.raised) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
            Text(if (isNew) "Add a holding" else ticker, style = KassaType.section, color = Kassa.colors.ink)
            if (!isNew) Text("Type today's price to update its market value. Kassa never looks prices up.", style = KassaType.body, color = Kassa.colors.ink2)
            if (isNew) OutlinedTextField(ticker, { ticker = it.uppercase() }, singleLine = true, label = { Text("Ticker") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(price, { price = it }, singleLine = true, label = { Text("Price per share") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(shares, { shares = it }, singleLine = true, label = { Text("Shares") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            error?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention) }
            Gap(12)
            PrimaryButton(if (isNew) "Add holding" else "Save") {
                val s = parseShares(shares); val pr = Money.parseCents(price)
                error = when { ticker.isBlank() -> "Enter the ticker."; s == null -> "Enter the shares."; pr == null || pr <= 0 -> "Enter the price."; else -> null }
                if (error == null) onSave(holding.copy(ticker = ticker.trim().uppercase(), microShares = s!!, price = pr!!, priceMillis = System.currentTimeMillis()))
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

/** Every holding's price in one sheet, so one pass brings the account up to date. Kassa never looks prices up. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PricesSheet(holdings: List<Holding>, onSave: (List<Holding>) -> Unit, onDismiss: () -> Unit) {
    val prices = remember { androidx.compose.runtime.mutableStateMapOf<Long, String>().apply { holdings.forEach { put(it.id, BigDecimal.valueOf(it.price).movePointLeft(2).toPlainString()) } } }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Kassa.colors.raised) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp).verticalScroll(rememberScrollState())) {
            Text("Update prices", style = KassaType.section, color = Kassa.colors.ink)
            holdings.forEach { h ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(h.ticker, style = KassaType.bodyStrong, color = Kassa.colors.ink)
                        Text("${BigDecimal.valueOf(h.microShares).movePointLeft(6).stripTrailingZeros().toPlainString()} shares", style = KassaType.small, color = Kassa.colors.ink2)
                    }
                    OutlinedTextField(prices[h.id] ?: "", { prices[h.id] = it; error = null }, singleLine = true, label = { Text("Price") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(140.dp))
                }
            }
            error?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention) }
            Gap(12)
            PrimaryButton("Save prices") {
                val now = System.currentTimeMillis()
                val updated = holdings.map { h -> h to Money.parseCents(prices[h.id] ?: "") }
                error = if (updated.any { it.second == null || it.second!! <= 0 }) "Every price needs a number above zero." else null
                if (error == null) onSave(updated.map { (h, p) -> h.copy(price = p!!, priceMillis = now) })
            }
        }
    }
}

/** A buy or a sale: cash moves inside the account and the holding grows or shrinks. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TradeSheet(holdings: List<Holding>, cash: Long, onBuy: (String, Long, Long) -> Unit, onSell: (String, Long, Long) -> Unit, onDismiss: () -> Unit) {
    var buy by remember { mutableStateOf(true) }
    var ticker by remember { mutableStateOf(holdings.firstOrNull()?.ticker ?: "") }
    var shares by remember { mutableStateOf("") }
    var price by remember { mutableStateOf(holdings.firstOrNull()?.let { BigDecimal.valueOf(it.price).movePointLeft(2).toPlainString() } ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Kassa.colors.raised) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
            Text("Buy or sell", style = KassaType.section, color = Kassa.colors.ink)
            FlowRow(Modifier.padding(top = 10.dp)) {
                Chip("Buy", selected = buy) { buy = true }
                Chip("Sell", selected = !buy) { buy = false }
            }
            if (holdings.isNotEmpty()) FlowRow {
                holdings.forEach { h -> Chip(h.ticker, selected = ticker.equals(h.ticker, true), small = true) { ticker = h.ticker; price = BigDecimal.valueOf(h.price).movePointLeft(2).toPlainString() } }
            }
            if (buy) OutlinedTextField(ticker, { ticker = it.uppercase() }, singleLine = true, label = { Text("Ticker") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(shares, { shares = it; error = null }, singleLine = true, label = { Text("Shares") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                OutlinedTextField(price, { price = it; error = null }, singleLine = true, label = { Text("Price") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
            }
            val s = parseShares(shares); val pr = Money.parseCents(price)
            val total = if (s != null && pr != null) Math.round(s / 1_000_000.0 * pr) else 0L
            Text(if (buy) "Costs ${Money.format(total)} of ${Money.format(cash)} cash" else "Adds ${Money.format(total)} to cash", style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 8.dp))
            error?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention) }
            Gap(12)
            PrimaryButton(if (buy) "Save buy" else "Save sale") {
                val held = holdings.firstOrNull { it.ticker.equals(ticker.trim(), true) }
                error = when {
                    ticker.isBlank() -> "Pick or enter a ticker."
                    s == null || s <= 0 -> "Enter the shares."
                    pr == null || pr <= 0 -> "Enter the price."
                    !buy && held == null -> "You don't hold $ticker here."
                    !buy && s > held!!.microShares -> "You hold ${BigDecimal.valueOf(held.microShares).movePointLeft(6).stripTrailingZeros().toPlainString()} shares."
                    else -> null
                }
                if (error == null) { if (buy) onBuy(ticker.trim().uppercase(), s!!, pr!!) else onSell(ticker.trim().uppercase(), s!!, pr!!) }
            }
        }
    }
}
