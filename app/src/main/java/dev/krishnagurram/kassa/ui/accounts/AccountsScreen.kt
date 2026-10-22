package dev.krishnagurram.kassa.ui.accounts

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.krishnagurram.kassa.domain.Transaction
import dev.krishnagurram.kassa.domain.TxKind
import dev.krishnagurram.kassa.domain.effectOn
import dev.krishnagurram.kassa.ui.Ledger
import dev.krishnagurram.kassa.ui.common.ConfirmDialog
import dev.krishnagurram.kassa.ui.common.EntryRow
import dev.krishnagurram.kassa.ui.common.Gap
import dev.krishnagurram.kassa.ui.common.GlyphBadge
import dev.krishnagurram.kassa.ui.common.PageTop
import dev.krishnagurram.kassa.ui.common.RaisedPanel
import dev.krishnagurram.kassa.ui.common.SectionHeader
import dev.krishnagurram.kassa.ui.common.TransactionRow
import dev.krishnagurram.kassa.ui.entry.Chip
import dev.krishnagurram.kassa.ui.importer.PrimaryButton
import dev.krishnagurram.kassa.ui.importer.SecondaryButton
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType
import java.time.Instant
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** Net worth split into assets and liabilities, then every account by group, holdings and people. */
@Composable
fun AccountsScreen(
    ledger: Ledger,
    contentPadding: PaddingValues,
    selectedAccount: Long?,
    onOpenAccount: (Long) -> Unit,
    onOpenPerson: (Long) -> Unit,
    onImportAgain: () -> Unit,
    onAddAccount: (Account, String?) -> Unit,
    onAddPerson: (String) -> Unit,
    onSaveHolding: (dev.krishnagurram.kassa.domain.Holding) -> Unit = {},
    onDeleteHolding: (dev.krishnagurram.kassa.domain.Holding) -> Unit = {},
) {
    val state = ledger.accounts
    var adding by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }

    val active = state.accounts.filter { !it.archived }
    val personBalances = ledger.personBalances()
    val receivable = personBalances.values.filter { it > 0 }.sum()
    val payable = -personBalances.values.filter { it < 0 }.sum()
    val holdings = ledger.assist.holdings.filter { h -> active.any { it.id == h.accountId } }
    val holdingsTotal = holdings.sumOf { it.value }
    val assets = active.filter { !it.type.isDebt }.sumOf { state.balances[it.id] ?: 0 } + holdingsTotal + receivable
    val liabilities = -active.filter { it.type.isDebt }.sumOf { state.balances[it.id] ?: 0 } + payable
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
            Gap(16)
            RaisedPanel {
                Text("Net worth", style = KassaType.tiny, color = Kassa.colors.ink2)
                Text(Money.format(assets - liabilities), style = KassaType.hero.copy(fontSize = KassaType.hero.fontSize * 0.75f), color = Kassa.colors.ink)
                HorizontalDivider(color = Kassa.colors.line, modifier = Modifier.padding(vertical = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Assets", style = KassaType.tiny, color = Kassa.colors.ink2)
                        Text(Money.format(assets), style = KassaType.stat, color = Kassa.colors.ink, maxLines = 1)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Liabilities", style = KassaType.tiny, color = Kassa.colors.ink2)
                        Text(Money.format(liabilities), style = KassaType.stat, color = Kassa.colors.ink, maxLines = 1)
                    }
                }
            }
            Gap(12)
            SecondaryButton("Add an account", { adding = true })
        }
        val accountList: @Composable () -> Unit = {
            sections.forEach { (name, accounts) ->
                SectionHeader(name, Money.format(accounts.sumOf { state.balances[it.id] ?: 0 }))
                accounts.forEachIndexed { i, a -> AccountRow(a, state.balances[a.id] ?: 0, ledger, selected = a.id == selectedAccount, last = i == accounts.lastIndex) { onOpenAccount(a.id) } }
            }
            if (archived.isNotEmpty()) {
                TextButton(onClick = { showArchived = !showArchived }) {
                    Text(if (showArchived) "Hide archived (${archived.size})" else "Show archived (${archived.size})", color = Kassa.colors.ink2)
                }
                if (showArchived) archived.forEachIndexed { i, a -> AccountRow(a, state.balances[a.id] ?: 0, ledger, selected = a.id == selectedAccount, last = i == archived.lastIndex) { onOpenAccount(a.id) } }
            }
        }
        val investList: @Composable () -> Unit = {
            val investAccounts = active.filter { it.type == AccountType.INVESTMENT }
            if (investAccounts.isNotEmpty()) {
                SectionHeader("Holdings", if (holdings.isEmpty()) "Prices you enter" else "Market value ${Money.format(holdingsTotal)}")
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
            PeopleSection(ledger, personBalances, receivable, payable, onOpen = onOpenPerson, onAddPerson = onAddPerson)
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
}

@Composable
private fun PeopleSection(ledger: Ledger, balances: Map<Long, Long>, receivable: Long, payable: Long, onOpen: (Long) -> Unit, onAddPerson: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val people = ledger.people.people.filter { !it.archived }
    SectionHeader("People", if (people.isEmpty()) "Splits and loans" else "Receivable ${Money.format(receivable, wholeDollars = true)}, payable ${Money.format(payable, wholeDollars = true)}")
    if (people.isEmpty()) Text("Add people you split costs with or lend to. Their share is tracked as a receivable, not spending.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 8.dp))
    people.forEachIndexed { i, p ->
        val b = balances[p.id] ?: 0
        EntryRow(
            badge = { GlyphBadge(p.name.take(1).uppercase()) },
            title = p.name,
            subtitle = when { b > 0 -> "Receivable, owes you"; b < 0 -> "Payable, you owe"; else -> "Settled" },
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

@Composable
private fun AccountRow(a: Account, balance: Long, ledger: Ledger, selected: Boolean, last: Boolean, onClick: () -> Unit) {
    val group = a.groupId?.let { id -> ledger.accounts.groups.firstOrNull { it.id == id }?.name }
    val detail = buildList {
        add(a.type.label)
        if (a.statementDay != null && a.dueDay != null) add("statement day ${a.statementDay}, due day ${a.dueDay}")
        if (a.archived && group != null) add(group)
    }.joinToString(", ")
    EntryRow(
        badge = { GlyphBadge(a.name.take(1).uppercase()) },
        title = a.name, subtitle = detail,
        amount = Money.format(balance),
        amountColor = if (balance < 0) Kassa.colors.ink2 else Kassa.colors.ink,
        showDivider = !last,
        modifier = Modifier.then(if (selected) Modifier.background(Kassa.colors.chip) else Modifier).clickable(onClick = onClick),
    )
}

/**
 * One account: its balance, then every entry that moved money in or out of it, newest first, by month,
 * each with the balance right after it.
 */
@Composable
fun AccountPage(
    ledger: Ledger,
    account: Account,
    history: List<Transaction>,
    contentPadding: PaddingValues,
    onBack: (() -> Unit)?,
    onEditEntry: (Transaction) -> Unit,
    onSaveAccount: (Account, String?) -> Unit,
    onArchive: (Boolean) -> Unit,
) {
    val balance = ledger.accounts.balances[account.id] ?: 0
    val holdingsValue = if (account.type == AccountType.INVESTMENT) ledger.holdingsValue(account.id) else 0L
    var editing by remember { mutableStateOf(false) }
    var confirmArchive by remember { mutableStateOf(false) }
    // Balance right after each entry: walk back from today's balance.
    val after = remember(history, balance) {
        var running = balance
        history.map { t -> (t to running).also { running -= t.effectOn(account.id) } }
    }
    val months = after.groupBy { (t, _) -> YearMonth.from(Instant.ofEpochMilli(t.epochMillis).atZone(ledger.zone)) }.toList()
    val group = account.groupId?.let { id -> ledger.accounts.groups.firstOrNull { it.id == id }?.name }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Column(Modifier.widthIn(max = 720.dp)) {
                PageTop(account.name, onBack)
                Text(listOfNotNull(account.type.label, group, if (account.archived) "archived" else null).joinToString(", "), style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(horizontal = 4.dp))
                Gap(14)
                RaisedPanel {
                    if (account.type == AccountType.INVESTMENT) {
                        Text("Total value", style = KassaType.tiny, color = Kassa.colors.ink2)
                        Text(Money.format(balance + holdingsValue), style = KassaType.hero.copy(fontSize = KassaType.hero.fontSize * 0.75f), color = Kassa.colors.ink)
                        HorizontalDivider(color = Kassa.colors.line, modifier = Modifier.padding(vertical = 12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Uninvested cash", style = KassaType.tiny, color = Kassa.colors.ink2)
                                Text(Money.format(balance), style = KassaType.stat, color = Kassa.colors.ink, maxLines = 1)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("Market value", style = KassaType.tiny, color = Kassa.colors.ink2)
                                Text(Money.format(holdingsValue), style = KassaType.stat, color = Kassa.colors.ink, maxLines = 1)
                            }
                        }
                    } else {
                        Text(if (account.type.isDebt) "Balance owed" else "Balance", style = KassaType.tiny, color = Kassa.colors.ink2)
                        Text(Money.format(if (account.type.isDebt) -balance else balance), style = KassaType.hero.copy(fontSize = KassaType.hero.fontSize * 0.75f), color = Kassa.colors.ink)
                    }
                    Gap(14)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) { SecondaryButton("Edit or adjust balance", { editing = true }) }
                    }
                    TextButton(onClick = { if (account.archived) onArchive(false) else confirmArchive = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (account.archived) "Restore account" else "Archive account", color = Kassa.colors.ink2)
                    }
                }
                if (history.isEmpty()) Text("No entries in this account yet.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 24.dp))
            }
        }
        months.forEach { (month, rows) ->
            item(key = "m$month") {
                val net = rows.sumOf { (t, _) -> t.effectOn(account.id) }
                Column(Modifier.widthIn(max = 720.dp)) {
                    SectionHeader(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), "Net change ${Money.format(net, showPlus = true)}")
                }
            }
            items(rows, key = { it.first.id }) { (t, bal) ->
                Column(Modifier.widthIn(max = 720.dp)) {
                    TransactionRow(t, ledger, showDivider = t !== rows.last().first, noteOverride = "Balance ${Money.format(bal)}", onClick = onEditEntry)
                }
            }
        }
    }

    if (editing) AddAccountSheet(
        groups = ledger.accounts.groups,
        payFromOptions = ledger.accounts.accounts.filter { !it.archived && it.id != account.id && it.type in listOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH) },
        onDismiss = { editing = false },
        onSave = { a, g -> onSaveAccount(a, g); editing = false },
        editing = account, currentBalance = balance,
    )
    if (confirmArchive) ConfirmDialog(
        title = "Archive ${account.name}?",
        body = "It leaves your lists and totals. Its entries stay in your history, and you can restore it from Accounts.",
        confirm = "Archive", onConfirm = { onArchive(true) }, onDismiss = { confirmArchive = false },
    )
}

/** A person's receivable or payable and its history, with a way to record money moving either way. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonPage(p: Person, balance: Long, ledger: Ledger, contentPadding: PaddingValues, onBack: (() -> Unit)?, onSave: (EntryDraft) -> Unit) {
    // Default direction settles the balance: if they owe you, they're paying you.
    var direction by remember(p.id, balance) { mutableStateOf(if (balance < 0) TxKind.PERSON_OUT else TxKind.PERSON_IN) }
    var amount by remember(p.id, balance) { mutableStateOf(if (balance != 0L) java.math.BigDecimal.valueOf(Math.abs(balance)).movePointLeft(2).toPlainString() else "") }
    val banks = ledger.accounts.accounts.filter { !it.archived && it.type in setOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH, AccountType.PREPAID) }
    var account by remember { mutableStateOf(banks.firstOrNull()?.id) }
    var error by remember { mutableStateOf<String?>(null) }
    val history = (
        ledger.people.loans.filter { it.personId == p.id }.map { Triple(it.epochMillis, it.note.ifBlank { "Split" }, it.amount) } +
            ledger.people.payments.filter { it.personId == p.id }.map { t ->
                Triple(t.epochMillis, t.note.ifBlank { if (t.kind == TxKind.PERSON_IN) "${p.name} paid you" else "You paid ${p.name}" }, if (t.kind == TxKind.PERSON_OUT) t.amount else -t.amount)
            }
        ).sortedByDescending { it.first }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        PageTop(p.name, onBack)
        RaisedPanel {
            Text(when { balance > 0 -> "Receivable"; balance < 0 -> "Payable"; else -> "Settled" }, style = KassaType.tiny, color = Kassa.colors.ink2)
            Text(Money.format(Math.abs(balance)), style = KassaType.hero.copy(fontSize = KassaType.hero.fontSize * 0.75f),
                color = when { balance > 0 -> Kassa.colors.positive; balance < 0 -> Kassa.colors.attention; else -> Kassa.colors.ink })
            Text(when { balance > 0 -> "${p.name} owes you"; balance < 0 -> "You owe ${p.name}"; else -> "Nobody owes anything" }, style = KassaType.small, color = Kassa.colors.ink2)
        }
        SectionHeader("Record a payment")
        FlowRow(Modifier.padding(top = 6.dp)) {
            Chip("${p.name} paid me", selected = direction == TxKind.PERSON_IN) { direction = TxKind.PERSON_IN }
            Chip("I paid or lent ${p.name}", selected = direction == TxKind.PERSON_OUT) { direction = TxKind.PERSON_OUT }
        }
        OutlinedTextField(amount, { amount = it; error = null }, singleLine = true, label = { Text("Amount") },
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
        Gap(24)
    }
}
