package dev.krishnagurram.kassa.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.krishnagurram.kassa.data.EntryDraft
import dev.krishnagurram.kassa.domain.Account
import dev.krishnagurram.kassa.domain.AccountType
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.Person
import dev.krishnagurram.kassa.domain.TxKind
import dev.krishnagurram.kassa.ui.Ledger
import dev.krishnagurram.kassa.ui.common.EntryRow
import dev.krishnagurram.kassa.ui.common.Gap
import dev.krishnagurram.kassa.ui.common.GlyphBadge
import dev.krishnagurram.kassa.ui.common.SectionHeader
import dev.krishnagurram.kassa.ui.entry.Chip
import dev.krishnagurram.kassa.ui.importer.PrimaryButton
import dev.krishnagurram.kassa.ui.importer.SecondaryButton
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable
fun AccountsScreen(
    ledger: Ledger,
    contentPadding: PaddingValues,
    onImportAgain: () -> Unit,
    onAddAccount: (Account, String?) -> Unit,
    onArchive: (Account, Boolean) -> Unit,
    onAddPerson: (String) -> Unit,
    onSave: (EntryDraft) -> Unit,
    onSaveHolding: (dev.krishnagurram.kassa.domain.Holding) -> Unit = {},
    onDeleteHolding: (dev.krishnagurram.kassa.domain.Holding) -> Unit = {},
) {
    val state = ledger.accounts
    var adding by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    var personOpen by remember { mutableStateOf<Long?>(null) }

    val active = state.accounts.filter { !it.archived }
    val personBalances = ledger.personBalances()
    val owedToYou = personBalances.values.filter { it > 0 }.sum()
    val youOwe = -personBalances.values.filter { it < 0 }.sum()
    val accountsTotal = active.sumOf { state.balances[it.id] ?: 0 }
    val holdings = ledger.assist.holdings.filter { h -> active.any { it.id == h.accountId } }
    val holdingsTotal = holdings.sumOf { it.value }
    val netWorth = accountsTotal + holdingsTotal + owedToYou - youOwe
    var holdingOpen by remember { mutableStateOf<dev.krishnagurram.kassa.domain.Holding?>(null) }
    val sections: List<Pair<String, List<Account>>> = buildList {
        state.groups.forEach { g -> active.filter { it.groupId == g.id }.takeIf { it.isNotEmpty() }?.let { add(g.name to it) } }
        active.filter { a -> a.groupId == null || state.groups.none { it.id == a.groupId } }.takeIf { it.isNotEmpty() }?.let { add("Other" to it) }
    }
    val archived = state.accounts.filter { it.archived }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val summary: @Composable () -> Unit = {
            Text("On this phone only", style = KassaType.small, color = Kassa.colors.ink2)
            Text("Accounts", style = KassaType.title, color = Kassa.colors.ink)
            Gap(18)
            Text("Net worth", style = KassaType.body, color = Kassa.colors.ink2)
            Text(Money.format(netWorth, wholeDollars = true), style = KassaType.hero.copy(fontSize = KassaType.hero.fontSize * 0.8f), color = Kassa.colors.ink)
            Gap(8)
            Kv("Accounts", Money.format(accountsTotal, wholeDollars = true))
            if (holdingsTotal > 0) Kv("Holdings", Money.format(holdingsTotal, wholeDollars = true))
            Kv("Owed to you", Money.format(owedToYou, wholeDollars = true))
            Kv("You owe people", Money.format(-youOwe, wholeDollars = true))
            Gap(14)
            SecondaryButton("Add an account", { adding = true })
        }
        val accountList: @Composable () -> Unit = {
            sections.forEach { (name, accounts) ->
                SectionHeader(name, Money.format(accounts.sumOf { state.balances[it.id] ?: 0 }, wholeDollars = true))
                accounts.forEachIndexed { i, a -> AccountRow(a, state.balances[a.id] ?: 0, last = i == accounts.lastIndex) { onArchive(a, true) } }
            }
            if (archived.isNotEmpty()) {
                TextButton(onClick = { showArchived = !showArchived }) {
                    Text(if (showArchived) "Hide archived (${archived.size})" else "Show archived (${archived.size})", color = Kassa.colors.ink2)
                }
                if (showArchived) archived.forEachIndexed { i, a -> AccountRow(a, state.balances[a.id] ?: 0, last = i == archived.lastIndex, archiveLabel = "Restore") { onArchive(a, false) } }
            }
        }
        val investList: @Composable () -> Unit = {
            val investAccounts = active.filter { it.type == AccountType.INVESTMENT }
            if (investAccounts.isNotEmpty()) {
                SectionHeader("Holdings", if (holdings.isEmpty()) "Prices you enter" else Money.format(holdingsTotal, wholeDollars = true))
                val oldest = holdings.minOfOrNull { it.priceMillis }
                if (oldest != null) {
                    val days = java.time.temporal.ChronoUnit.DAYS.between(Instant.ofEpochMilli(oldest).atZone(ledger.zone).toLocalDate(), ledger.today)
                    if (days > 14) Text("Some prices are $days days old. Tap a holding to update it.", style = KassaType.small, color = Kassa.colors.attention)
                }
                holdings.forEachIndexed { i, h ->
                    EntryRow(
                        badge = { GlyphBadge(h.ticker.take(2)) },
                        title = h.ticker,
                        subtitle = "${java.math.BigDecimal.valueOf(h.microShares).movePointLeft(6).stripTrailingZeros().toPlainString()} shares at ${Money.format(h.price)}",
                        amount = Money.format(h.value),
                        note = "Priced " + Instant.ofEpochMilli(h.priceMillis).atZone(ledger.zone).format(DateTimeFormatter.ofPattern("MMM d")),
                        showDivider = i < holdings.lastIndex,
                        modifier = Modifier.clickable { holdingOpen = h },
                    )
                }
                TextButton(onClick = { holdingOpen = dev.krishnagurram.kassa.domain.Holding(accountId = investAccounts.first().id, ticker = "", microShares = 0, price = 0, priceMillis = 0) }) {
                    Text("Add a holding", color = Kassa.colors.ink)
                }
            }
        }
        val peopleList: @Composable () -> Unit = {
            PeopleSection(ledger, personBalances, onOpen = { personOpen = it }, onAddPerson = onAddPerson)
            Gap(24)
            TextButton(onClick = onImportAgain) { Text("Import again from Money Manager", color = Kassa.colors.ink2) }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(contentPadding), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { summary(); accountList() }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { investList(); peopleList() }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
                summary(); accountList(); investList(); peopleList()
            }
        }
    }

    if (adding) AddAccountSheet(
        groups = state.groups,
        payFromOptions = active.filter { it.type in listOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH) },
        onDismiss = { adding = false },
        onSave = { account, newGroup -> onAddAccount(account, newGroup); adding = false },
    )
    holdingOpen?.let { h ->
        dev.krishnagurram.kassa.ui.invest.HoldingSheet(h, onSave = { onSaveHolding(it); holdingOpen = null }, onDelete = { onDeleteHolding(it); holdingOpen = null }, onDismiss = { holdingOpen = null })
    }
    personOpen?.let { id ->
        ledger.people.people.firstOrNull { it.id == id }?.let { p ->
            PersonSheet(p, personBalances[p.id] ?: 0, ledger, onDismiss = { personOpen = null }, onSave = { personOpen = null; onSave(it) })
        }
    }
}

@Composable
private fun Kv(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = KassaType.body, color = Kassa.colors.ink2)
        Text(value, style = KassaType.bodyStrong, color = Kassa.colors.ink)
    }
}

@Composable
private fun PeopleSection(ledger: Ledger, balances: Map<Long, Long>, onOpen: (Long) -> Unit, onAddPerson: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val people = ledger.people.people.filter { !it.archived }
    SectionHeader("People", "Splits and loans")
    if (people.isEmpty()) Text("Add people you split costs with or lend to. Their share is tracked as money owed, not spending.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 8.dp))
    people.forEachIndexed { i, p ->
        val b = balances[p.id] ?: 0
        EntryRow(
            badge = { GlyphBadge(p.name.take(1).uppercase()) },
            title = p.name,
            subtitle = when { b > 0 -> "Owes you"; b < 0 -> "You owe"; else -> "All settled" },
            amount = Money.format(Math.abs(b)),
            amountColor = when { b > 0 -> Kassa.colors.positive; b < 0 -> Kassa.colors.attention; else -> Kassa.colors.ink2 },
            showDivider = i < people.lastIndex,
            modifier = Modifier.clickable { onOpen(p.id) },
        )
    }
    Row(Modifier.padding(top = 8.dp)) {
        OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Add a person") }, modifier = Modifier.weight(1f))
        TextButton(onClick = { if (name.isNotBlank()) { onAddPerson(name.trim()); name = "" } }) { Text("Add", color = Kassa.colors.ink) }
    }
}

/** A person's balance and history, with a way to record money moving either way. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PersonSheet(p: Person, balance: Long, ledger: Ledger, onDismiss: () -> Unit, onSave: (EntryDraft) -> Unit) {
    // Default direction settles the balance: if they owe you, they're paying you.
    var direction by remember { mutableStateOf(if (balance < 0) TxKind.PERSON_OUT else TxKind.PERSON_IN) }
    var amount by remember { mutableStateOf(if (balance != 0L) java.math.BigDecimal.valueOf(Math.abs(balance)).movePointLeft(2).toPlainString() else "") }
    val banks = ledger.accounts.accounts.filter { !it.archived && it.type in setOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH, AccountType.PREPAID) }
    var account by remember { mutableStateOf(banks.firstOrNull()?.id) }
    var error by remember { mutableStateOf<String?>(null) }
    val history = (
        ledger.people.loans.filter { it.personId == p.id }.map { Triple(it.epochMillis, it.note.ifBlank { "Split" }, it.amount) } +
            ledger.people.payments.filter { it.personId == p.id }.map { t ->
                Triple(t.epochMillis, t.note.ifBlank { if (t.kind == TxKind.PERSON_IN) "${p.name} paid you" else "You paid ${p.name}" }, if (t.kind == TxKind.PERSON_OUT) t.amount else -t.amount)
            }
        ).sortedByDescending { it.first }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Kassa.colors.raised) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
            Text(p.name, style = KassaType.section, color = Kassa.colors.ink)
            Text(when { balance > 0 -> "${p.name} owes you"; balance < 0 -> "You owe ${p.name}"; else -> "All settled" }, style = KassaType.body, color = Kassa.colors.ink2)
            Text(Money.format(Math.abs(balance)), style = KassaType.hero.copy(fontSize = KassaType.hero.fontSize * 0.7f),
                color = when { balance > 0 -> Kassa.colors.positive; balance < 0 -> Kassa.colors.attention; else -> Kassa.colors.ink })

            Text("Record money moving", style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
            FlowRow {
                Chip("${p.name} paid me", selected = direction == TxKind.PERSON_IN) { direction = TxKind.PERSON_IN }
                Chip("I paid or lent ${p.name}", selected = direction == TxKind.PERSON_OUT) { direction = TxKind.PERSON_OUT }
            }
            OutlinedTextField(amount, { amount = it }, singleLine = true, label = { Text("Amount") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            Text(if (direction == TxKind.PERSON_IN) "Into" else "From", style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            FlowRow { banks.forEach { a -> Chip(a.name, selected = account == a.id) { account = a.id } } }
            error?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention) }
            Gap(12)
            PrimaryButton("Record payment") {
                val c = Money.parseCents(amount)
                error = when { c == null || c <= 0 -> "Enter an amount."; account == null -> "Pick an account."; else -> null }
                if (error == null) onSave(EntryDraft(
                    id = null, kind = direction, amount = c!!, accountId = account, toAccountId = null, categoryId = null,
                    personId = p.id, epochMillis = System.currentTimeMillis(),
                    note = if (direction == TxKind.PERSON_IN) "${p.name} paid you" else "You paid ${p.name}", splitWith = emptyList(),
                ))
            }
            SectionHeader("History")
            if (history.isEmpty()) Text("Nothing yet.", style = KassaType.body, color = Kassa.colors.ink2)
            history.forEach { (millis, note, amt) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(note, style = KassaType.body, color = Kassa.colors.ink)
                        Text(Instant.ofEpochMilli(millis).atZone(ledger.zone).format(DateTimeFormatter.ofPattern("MMM d, yyyy")), style = KassaType.small, color = Kassa.colors.ink2)
                    }
                    Text(Money.format(amt, showPlus = true), style = KassaType.bodyStrong, color = if (amt > 0) Kassa.colors.positive else Kassa.colors.attention)
                }
                HorizontalDivider(color = Kassa.colors.line)
            }
        }
    }
}

@Composable
private fun AccountRow(a: Account, balance: Long, last: Boolean, archiveLabel: String = "Archive", onArchive: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.clickable { open = !open }) {
        val detail = buildList {
            add(a.type.label)
            if (a.statementDay != null && a.dueDay != null) add("statement ${a.statementDay}, due ${a.dueDay}")
        }.joinToString(", ")
        EntryRow(
            badge = { GlyphBadge(a.name.take(1).uppercase()) },
            title = a.name, subtitle = detail,
            amount = Money.format(balance),
            amountColor = if (balance < 0) Kassa.colors.ink2 else Kassa.colors.ink,
            showDivider = !last && !open,
        )
        if (open) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onArchive) { Text(archiveLabel, color = Kassa.colors.ink2) }
            }
            if (!last) HorizontalDivider(color = Kassa.colors.line)
        }
    }
}
