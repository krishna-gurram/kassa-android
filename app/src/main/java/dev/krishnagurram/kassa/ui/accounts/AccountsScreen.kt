package dev.krishnagurram.kassa.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.ui.unit.sp
import dev.krishnagurram.kassa.ui.common.accountIcon
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
import dev.krishnagurram.kassa.ui.common.PeriodSwitcher
import dev.krishnagurram.kassa.ui.common.swipeShift
import dev.krishnagurram.kassa.ui.entry.Chip
import dev.krishnagurram.kassa.ui.importer.PrimaryButton
import dev.krishnagurram.kassa.ui.importer.SecondaryButton
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType
import java.time.Instant
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Everything you own and owe on one page, laid out like the old Cards tab: net worth, then credit cards (payable and
 * outstanding, by due date), bank and cash, investments, loans, and people. Each row opens its own page.
 */
@Composable
fun AccountsScreen(
    ledger: Ledger,
    contentPadding: PaddingValues,
    selectedAccount: Long?,
    onOpenAccount: (Long) -> Unit,
    onOpenPerson: (Long) -> Unit,
    onImportAgain: () -> Unit,
    onAddAccount: (Account, String?) -> Unit,
    onAddPerson: (String, Long) -> Unit,
) {
    val state = ledger.accounts
    var adding by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    var addingPerson by remember { mutableStateOf(false) }
    // Sections start folded; which ones are open survives folding the phone.
    var openKeys by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val open = openKeys.split(',').filter { it.isNotEmpty() }.toSet()
    val toggle: (String) -> Unit = { k -> openKeys = (if (k in open) open - k else open + k).joinToString(",") }

    val active = state.accounts.filter { !it.archived }
    val personBalances = ledger.personBalances()
    val receivable = personBalances.values.filter { it > 0 }.sum()
    val payable = -personBalances.values.filter { it < 0 }.sum()
    fun valueOf(a: Account): Long = (state.balances[a.id] ?: 0) + if (a.type == AccountType.INVESTMENT) ledger.holdingsValue(a.id) else 0L
    val assets = active.filter { !it.type.isDebt }.sumOf { valueOf(it) } + receivable
    val liabilities = -active.filter { it.type.isDebt }.sumOf { state.balances[it.id] ?: 0 } + payable

    val cards = dev.krishnagurram.kassa.ui.cards.cardsByDueDate(ledger)
    val cardIds = cards.map { it.account.id }.toSet()
    val unsetCards = active.filter { it.type == AccountType.CREDIT_CARD && it.id !in cardIds }
    val banks = active.filter { it.type in setOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH, AccountType.PREPAID) }
    val invest = active.filter { it.type == AccountType.INVESTMENT || it.type == AccountType.OTHER_ASSET }
    val loans = active.filter { it.type == AccountType.LOAN || it.type == AccountType.OTHER_DEBT }
    val people = ledger.people.people.filter { !it.archived }
    val archived = state.accounts.filter { it.archived }
    val archivedPeople = ledger.people.people.filter { it.archived }

    val allKeys = listOf("cards", "Bank and cash", "Investments", "Loans", "people")
    val allOpen = allKeys.all { it in open }
    val hero: @Composable () -> Unit = {
        RaisedPanel {
            Text("Net worth", style = KassaType.tiny, color = Kassa.colors.ink2)
            dev.krishnagurram.kassa.ui.today.BigMoney(assets - liabilities, sizeSp = 36f)
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
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { openKeys = if (allOpen) "" else allKeys.joinToString(",") }) {
                Text(if (allOpen) "Collapse all" else "Expand all", style = KassaType.bodyStrong, color = Kassa.colors.ink)
            }
        }
    }
    val cardSection: @Composable () -> Unit = {
        if (cards.isNotEmpty() || unsetCards.isNotEmpty()) {
            // The cards' real balances, the same figure Liabilities adds up: an overpaid card's credit counts too.
            val owed = -(cards.map { it.account } + unsetCards).sumOf { state.balances[it.id] ?: 0 }
            GroupHead("Credit cards", if (owed < 0) Money.format(-owed, showPlus = true) else "−" + Money.format(owed), "cards" in open, { toggle("cards") },
                listOf("Payable" to dev.krishnagurram.kassa.ui.cards.cardMoney(cards.sumOf { it.snapshot.balancePayable }), "Outstanding" to Money.format(cards.sumOf { it.snapshot.outstanding })))
            if ("cards" in open) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                cards.forEach { c -> dev.krishnagurram.kassa.ui.cards.CardRow(c, ledger.today, selected = c.account.id == selectedAccount) { onOpenAccount(c.account.id) } }
                unsetCards.forEach { a -> OneLineRow(IconTile(accountIcon(a.type)), a.name, a.last4, Money.format(-(state.balances[a.id] ?: 0)), selected = a.id == selectedAccount) { onOpenAccount(a.id) } }
            }
        }
    }
    val accountGroup: @Composable (String, List<Account>, Boolean) -> Unit = { title, list, debt ->
        if (list.isNotEmpty()) {
            GroupHead(title, Money.format(list.sumOf { if (debt) -(state.balances[it.id] ?: 0) else valueOf(it) }), title in open, { toggle(title) })
            if (title in open) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                list.forEach { a -> OneLineRow(IconTile(accountIcon(a.type)), a.name, a.last4, Money.format(if (debt) -(state.balances[a.id] ?: 0) else valueOf(a)), selected = a.id == selectedAccount) { onOpenAccount(a.id) } }
            }
        }
    }
    val peopleSection: @Composable () -> Unit = {
        GroupHead("Loans and splits", Money.format(receivable - payable), "people" in open, { toggle("people") }, if (people.isEmpty()) null else listOf("Owed to you" to Money.format(receivable), "You owe" to Money.format(payable)))
        if ("people" in open) {
            if (people.isEmpty()) Text("Add people you split costs with or lend to.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                people.forEach { p ->
                    val b = personBalances[p.id] ?: 0
                    OneLineRow(
                        { LetterTile(p.name.take(1).uppercase()) }, p.name, null, Money.format(Math.abs(b)),
                        suffix = when { b > 0 -> "owes you"; b < 0 -> "you owe"; else -> "settled" },
                        amountColor = if (b > 0) Kassa.colors.positive else Kassa.colors.ink,
                    ) { onOpenPerson(p.id) }
                }
            }
        }
    }
    val footer: @Composable () -> Unit = {
        Gap(20)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            dev.krishnagurram.kassa.ui.importer.RaisedButton("Add an account", { adding = true }, Modifier.weight(1f))
            dev.krishnagurram.kassa.ui.importer.RaisedButton("Add a person", { addingPerson = true }, Modifier.weight(1f))
        }
        val archivedCount = archived.size + archivedPeople.size
        if (archivedCount > 0) {
            TextButton(onClick = { showArchived = !showArchived }) {
                Text(if (showArchived) "Hide archived ($archivedCount)" else "Show archived ($archivedCount)", color = Kassa.colors.ink2)
            }
            if (showArchived) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                archived.forEach { a -> OneLineRow(IconTile(accountIcon(a.type)), a.name, a.last4, Money.format(state.balances[a.id] ?: 0), selected = a.id == selectedAccount) { onOpenAccount(a.id) } }
                archivedPeople.forEach { p -> OneLineRow({ LetterTile(p.name.take(1).uppercase()) }, p.name, null, Money.format(Math.abs(personBalances[p.id] ?: 0))) { onOpenPerson(p.id) } }
            }
        }
        TextButton(onClick = onImportAgain) { Text("Import again from Money Manager", color = Kassa.colors.ink2) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val title: @Composable () -> Unit = { Text("Accounts", style = KassaType.title, color = Kassa.colors.ink, modifier = Modifier.padding(bottom = 12.dp)) }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(contentPadding), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    title(); hero(); accountGroup("Bank and cash", banks, false); accountGroup("Investments", invest, false); accountGroup("Loans", loans, true); peopleSection(); footer()
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { cardSection() }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
                title(); hero(); cardSection(); accountGroup("Bank and cash", banks, false); accountGroup("Investments", invest, false); accountGroup("Loans", loans, true); peopleSection(); footer()
            }
        }
    }

    if (adding) AddAccountSheet(
        groups = state.groups,
        payFromOptions = active.filter { it.type in listOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH) },
        onDismiss = { adding = false },
        onSave = { account, newGroup -> onAddAccount(account, newGroup); adding = false },
    )
    if (addingPerson) AddPersonSheet(onDismiss = { addingPerson = false }) { name, opening -> onAddPerson(name, opening); addingPerson = false }
}

/** Rename a person, or archive them once you're square. Their history stays either way. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EditPersonSheet(p: Person, balance: Long, ledger: Ledger, onDismiss: () -> Unit, onSave: (Person) -> Unit) {
    var name by remember { mutableStateOf(p.name) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmArchive by remember { mutableStateOf(false) }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Kassa.colors.raised) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Edit person", style = KassaType.section, color = Kassa.colors.ink)
            Gap(12)
            OutlinedTextField(name, { name = it; error = null }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention) }
            Gap(16)
            PrimaryButton("Save") {
                val n = name.trim()
                error = when {
                    n.isEmpty() -> "Enter a name."
                    ledger.people.people.any { it.id != p.id && it.name.equals(n, ignoreCase = true) } -> "$n is already in your people."
                    else -> null
                }
                if (error == null) onSave(p.copy(name = n))
            }
            Gap(8)
            if (p.archived) SecondaryButton("Restore", { onSave(p.copy(name = name.trim().ifEmpty { p.name }, archived = false)) })
            else SecondaryButton("Archive", { confirmArchive = true })
        }
    }
    if (confirmArchive) ConfirmDialog(
        title = "Archive ${p.name}?",
        body = if (balance != 0L) "${Money.format(Math.abs(balance))} is still open between you. ${p.name} leaves the people list and the pickers; the history and balance stay." else "${p.name} leaves the people list and the pickers. The history stays.",
        confirm = "Archive", onConfirm = { onSave(p.copy(archived = true)) }, onDismiss = { confirmArchive = false },
    )
}

/** A new person: their name and, if there's already money between you, who owes whom. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddPersonSheet(onDismiss: () -> Unit, onAdd: (String, Long) -> Unit) {
    var name by remember { mutableStateOf("") }
    var theyOwe by remember { mutableStateOf(true) }
    var amount by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Kassa.colors.raised) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Add a person", style = KassaType.section, color = Kassa.colors.ink)
            Gap(12)
            OutlinedTextField(name, { name = it; error = null }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            Gap(12)
            Text("Already owed (optional)", style = KassaType.tiny, color = Kassa.colors.ink2)
            FlowRow(Modifier.padding(top = 6.dp)) {
                Chip("They owe me", selected = theyOwe) { theyOwe = true }
                Chip("I owe them", selected = !theyOwe) { theyOwe = false }
            }
            OutlinedTextField(amount, { amount = it; error = null }, singleLine = true, label = { Text("Amount") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention) }
            Gap(16)
            PrimaryButton("Add person") {
                val c = if (amount.isBlank()) 0L else Money.parseCents(amount)
                error = when { name.isBlank() -> "Enter a name."; c == null || c < 0 -> "Enter an amount, or leave it empty."; else -> null }
                if (error == null) onAdd(name.trim(), if (theyOwe) c!! else -c!!)
            }
        }
    }
}

/** A section title with its total. Tap to open or fold it; the two key figures show while it's open. */
@Composable
private fun GroupHead(title: String, total: String, open: Boolean, onToggle: () -> Unit, figures: List<Pair<String, String>>? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onToggle).padding(vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Outlined.ChevronRight, if (open) "Fold" else "Open", tint = Kassa.colors.ink3,
            modifier = Modifier.size(22.dp).rotate(if (open) 90f else 0f),
        )
        Text(title, style = KassaType.section, color = Kassa.colors.ink, modifier = Modifier.weight(1f).padding(start = 4.dp))
        Text(total, style = KassaType.bodyStrong, color = if (total.startsWith("+")) Kassa.colors.positive else Kassa.colors.ink)
    }
    if (open && figures != null) Row(Modifier.padding(start = 26.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        figures.forEach { (l, v) ->
            Row {
                Text("$l ", style = KassaType.small, color = Kassa.colors.ink2)
                Text(v, style = KassaType.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = if (v.startsWith("+")) Kassa.colors.positive else Kassa.colors.ink)
            }
        }
    }
    if (open) Gap(10)
}

private fun IconTile(icon: androidx.compose.ui.graphics.vector.ImageVector): @Composable () -> Unit = {
    androidx.compose.foundation.layout.Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Kassa.colors.ground),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) { androidx.compose.material3.Icon(icon, null, tint = Kassa.colors.ink2, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun LetterTile(letter: String) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Kassa.colors.ground),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) { Text(letter, style = KassaType.bodyStrong.copy(fontSize = 17.sp), color = Kassa.colors.ink2) }
}

/** A raised row like a card row, on one line: tile, name (and last 4), amount. */
@Composable
private fun OneLineRow(
    tile: @Composable () -> Unit, name: String, last4: String?, amount: String,
    selected: Boolean = false, suffix: String? = null, amountColor: androidx.compose.ui.graphics.Color = Kassa.colors.ink, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Kassa.colors.raised)
            .then(if (selected) Modifier.border(1.5.dp, Kassa.colors.ink, shape) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        tile()
        Row(Modifier.weight(1f).padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(name, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            last4?.takeIf { it.isNotBlank() }?.let { Text("  ··$it", style = KassaType.small, color = Kassa.colors.ink3, maxLines = 1) }
            suffix?.let { Text("  $it", style = KassaType.tiny, color = Kassa.colors.ink2, maxLines = 1) }
        }
        Text(amount, style = KassaType.bodyStrong.copy(fontSize = 17.sp), color = amountColor, maxLines = 1)
    }
}

/** Two equal boxes inside a summary panel, like the card page. */
@Composable
private fun TwoBoxes(a: Pair<String, String>, b: Pair<String, String>, aColor: androidx.compose.ui.graphics.Color = Kassa.colors.ink, bColor: androidx.compose.ui.graphics.Color = Kassa.colors.ink) {
    Row(Modifier.padding(top = 12.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(Triple(a.first, a.second, aColor), Triple(b.first, b.second, bColor)).forEach { (l, v, c) ->
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(Kassa.colors.ground).padding(12.dp)) {
                Text(l, style = KassaType.tiny, color = Kassa.colors.ink2)
                Text(v, style = KassaType.stat, color = c, maxLines = 1)
            }
        }
    }
}

/**
 * One account, built like the card page: a summary with two equal boxes, then the details.
 * Bank and cash go month by month (arrows or a swipe, like Activity): the balance at the end of the month, money in
 * and out, and that month's entries with the balance after each. Investments show the value, what you put in and
 * the gain, holdings, and buttons to update every price at once or record a buy or sale.
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
    onSaveHolding: (dev.krishnagurram.kassa.domain.Holding) -> Unit,
    onDeleteHolding: (dev.krishnagurram.kassa.domain.Holding) -> Unit,
    onBuy: (String, Long, Long) -> Unit,
    onSell: (String, Long, Long) -> Unit,
) {
    val balance = ledger.accounts.balances[account.id] ?: 0
    val isInvest = account.type == AccountType.INVESTMENT
    // Balance right after each entry: walk back from today's balance.
    val after = remember(history, balance) {
        var running = balance
        history.map { t -> (t to running).also { running -= t.effectOn(account.id) } }
    }
    val title = account.name + (account.last4?.takeIf { it.isNotBlank() }?.let { "  ··$it" } ?: "")
    val top: @Composable () -> Unit = {
        PageTop(title, onBack) { TextButton(onClick = onEditAccount) { Text("Edit", style = KassaType.bodyStrong, color = Kassa.colors.ink) } }
    }
    if (isInvest) {
        InvestmentBody(ledger, account, balance, history, after, contentPadding, top, onEditEntry, onSaveHolding, onDeleteHolding, onBuy, onSell)
        return
    }

    var back by androidx.compose.runtime.saveable.rememberSaveable(account.id) { androidx.compose.runtime.mutableIntStateOf(0) }
    val p = dev.krishnagurram.kassa.domain.Period.startingIn(ledger.period.yearMonth.minusMonths(back.toLong()), ledger.monthStartDay)
    fun dateOf(t: Transaction) = Instant.ofEpochMilli(t.epochMillis).atZone(ledger.zone).toLocalDate()
    val earliest = history.minOfOrNull { it.epochMillis }?.let { dev.krishnagurram.kassa.domain.Period.containing(Instant.ofEpochMilli(it).atZone(ledger.zone).toLocalDate(), ledger.monthStartDay).start }
    val canBack = earliest != null && p.start.isAfter(earliest)
    val rows = after.filter { (t, _) -> dateOf(t) in p }
    val endBalance = balance - history.filter { dateOf(it).isAfter(p.endInclusive) }.sumOf { it.effectOn(account.id) }
    val moneyIn = rows.sumOf { (t, _) -> maxOf(0L, t.effectOn(account.id)) }
    val moneyOut = rows.sumOf { (t, _) -> maxOf(0L, -t.effectOn(account.id)) }
    val debt = account.type.isDebt
    val shift: (Int) -> Unit = { d -> if (d < 0 && canBack) back++ else if (d > 0 && back > 0) back-- }

    LazyColumn(Modifier.fillMaxSize().swipeShift(shift), contentPadding = contentPadding) {
        item {
            Column(Modifier.widthIn(max = 720.dp)) {
                top()
                PeriodSwitcher(
                    "${p.yearMonth.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())} ${p.yearMonth.year}",
                    if (back == 0) "So far" else null, canBack, back > 0, onBack = { shift(-1) }, onForward = { shift(1) },
                )
                Gap(12)
                RaisedPanel {
                    if (account.type == AccountType.CREDIT_CARD) {
                        Text("Total balance", style = KassaType.tiny, color = Kassa.colors.ink2)
                        dev.krishnagurram.kassa.ui.today.BigMoney(-balance, sizeSp = 36f)
                        Text("Add the statement day and due day to split this into balance payable and outstanding.", style = KassaType.small, color = Kassa.colors.ink2)
                        TextButton(onClick = onEditAccount) { Text("Add statement days", color = Kassa.colors.ink) }
                    } else {
                        Text(
                            when { back == 0 && debt -> "Balance owed"; back == 0 -> "Balance today"; else -> "Balance on ${p.endInclusive.format(DateTimeFormatter.ofPattern("MMM d"))}" },
                            style = KassaType.tiny, color = Kassa.colors.ink2,
                        )
                        dev.krishnagurram.kassa.ui.today.BigMoney(if (debt) -endBalance else endBalance, sizeSp = 36f)
                        TwoBoxes("In" to Money.format(moneyIn), "Out" to Money.format(moneyOut), aColor = Kassa.colors.positive)
                    }
                }
                if (rows.isEmpty()) Text("Nothing in this month.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 24.dp))
                Gap(8)
            }
        }
        items(rows, key = { it.first.id }) { (t, bal) ->
            Column(Modifier.widthIn(max = 720.dp)) {
                TransactionRow(t, ledger, showDivider = t !== rows.last().first, noteOverride = "Balance ${Money.format(bal)}", onClick = onEditEntry, showDate = true)
            }
        }
    }
}

@Composable
private fun InvestmentBody(
    ledger: Ledger, account: Account, cash: Long, history: List<Transaction>, after: List<Pair<Transaction, Long>>,
    contentPadding: PaddingValues, top: @Composable () -> Unit, onEditEntry: (Transaction) -> Unit,
    onSaveHolding: (dev.krishnagurram.kassa.domain.Holding) -> Unit, onDeleteHolding: (dev.krishnagurram.kassa.domain.Holding) -> Unit,
    onBuy: (String, Long, Long) -> Unit, onSell: (String, Long, Long) -> Unit,
) {
    val holdings = ledger.assist.holdings.filter { it.accountId == account.id }.sortedByDescending { it.value }
    val holdingsValue = holdings.sumOf { it.value }
    val value = cash + holdingsValue
    // What you put in: transfers into the account minus transfers out. Trades move money inside it and don't count.
    val putIn = history.filter { it.kind == TxKind.TRANSFER }.sumOf { if (it.toAccountId == account.id) it.amount else if (it.accountId == account.id) -it.amount else 0L } +
        history.filter { it.kind == TxKind.INCOME && it.accountId == account.id }.sumOf { it.amount }
    val gain = value - putIn
    var holdingOpen by remember { mutableStateOf<dev.krishnagurram.kassa.domain.Holding?>(null) }
    var pricing by remember { mutableStateOf(false) }
    var trading by remember { mutableStateOf(false) }
    val months = after.groupBy { (t, _) -> YearMonth.from(Instant.ofEpochMilli(t.epochMillis).atZone(ledger.zone)) }.toList()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Column(Modifier.widthIn(max = 720.dp)) {
                top()
                RaisedPanel {
                    Text("Total value", style = KassaType.tiny, color = Kassa.colors.ink2)
                    dev.krishnagurram.kassa.ui.today.BigMoney(value, sizeSp = 36f)
                    if (putIn > 0) Text(
                        "${if (gain >= 0) "+" else "−"}${Money.format(Math.abs(gain))} (${java.math.BigDecimal.valueOf(gain * 1000 / putIn).movePointLeft(1).toPlainString()}%) on what you put in",
                        style = KassaType.bodyStrong, color = if (gain >= 0) Kassa.colors.positive else Kassa.colors.attention,
                    )
                    TwoBoxes((if (putIn > 0) "You put in" else "Holdings") to Money.format(if (putIn > 0) putIn else holdingsValue), "Cash" to Money.format(cash))
                    Gap(12)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                            PrimaryButton(if (holdings.isEmpty()) "Add a holding" else "Update prices") {
                                if (holdings.isEmpty()) holdingOpen = dev.krishnagurram.kassa.domain.Holding(accountId = account.id, ticker = "", microShares = 0, price = 0, priceMillis = 0) else pricing = true
                            }
                        }
                        SecondaryButton("Buy or sell", { trading = true }, Modifier.weight(1f))
                    }
                }
                if (holdings.isNotEmpty()) {
                    val oldest = holdings.minOf { it.priceMillis }
                    val days = java.time.temporal.ChronoUnit.DAYS.between(Instant.ofEpochMilli(oldest).atZone(ledger.zone).toLocalDate(), ledger.today)
                    SectionHeader("Holdings", if (days > 14) "Prices $days days old" else "Prices from ${Instant.ofEpochMilli(oldest).atZone(ledger.zone).format(DateTimeFormatter.ofPattern("MMM d"))}")
                    holdings.forEachIndexed { i, h ->
                        EntryRow(
                            badge = { GlyphBadge(h.ticker.take(2)) },
                            title = h.ticker,
                            subtitle = "${java.math.BigDecimal.valueOf(h.microShares).movePointLeft(6).stripTrailingZeros().toPlainString()} shares · ${Money.format(h.price)}",
                            amount = Money.format(h.value),
                            note = if (holdingsValue > 0) "${Math.round(h.value * 100.0 / holdingsValue)}%" else null,
                            showDivider = i < holdings.lastIndex,
                            modifier = Modifier.clickable { holdingOpen = h },
                        )
                    }
                    TextButton(onClick = { holdingOpen = dev.krishnagurram.kassa.domain.Holding(accountId = account.id, ticker = "", microShares = 0, price = 0, priceMillis = 0) }) { Text("Add a holding", color = Kassa.colors.ink) }
                }
                if (history.isEmpty()) Text("No money in or out yet.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 24.dp))
            }
        }
        months.forEach { (month, rows) ->
            item(key = "m$month") {
                Column(Modifier.widthIn(max = 720.dp)) { SectionHeader("Money in and out · " + month.format(DateTimeFormatter.ofPattern("MMMM yyyy"))) }
            }
            items(rows, key = { it.first.id }) { (t, bal) ->
                Column(Modifier.widthIn(max = 720.dp)) {
                    TransactionRow(t, ledger, showDivider = t !== rows.last().first, noteOverride = "Cash ${Money.format(bal)}", onClick = onEditEntry, showDate = true)
                }
            }
        }
    }
    holdingOpen?.let { h ->
        dev.krishnagurram.kassa.ui.invest.HoldingSheet(h, onSave = { onSaveHolding(it); holdingOpen = null }, onDelete = { onDeleteHolding(it); holdingOpen = null }, onDismiss = { holdingOpen = null })
    }
    if (pricing) dev.krishnagurram.kassa.ui.invest.PricesSheet(holdings, onSave = { list -> list.forEach(onSaveHolding); pricing = false }, onDismiss = { pricing = false })
    if (trading) dev.krishnagurram.kassa.ui.invest.TradeSheet(holdings, cash,
        onBuy = { tk, sh, pr -> onBuy(tk, sh, pr); trading = false }, onSell = { tk, sh, pr -> onSell(tk, sh, pr); trading = false }, onDismiss = { trading = false })
}

/**
 * One person, Splitwise style: what's owed and since when, then two things you can do. Record a payment covers money
 * either way (they paid you, you paid or lent them). Split a transaction takes an expense you already added and
 * moves part of it onto them.
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PersonPage(
    p: Person, balance: Long, ledger: Ledger, contentPadding: PaddingValues, onBack: (() -> Unit)?,
    onSave: (EntryDraft) -> Unit, onSplit: (Long, Long) -> Unit, onOpenEntry: (Long) -> Unit,
    onUpdate: (Person) -> Unit,
) {
    var editing by remember(p.id) { mutableStateOf(false) }
    val history = (
        ledger.people.loans.filter { it.personId == p.id }.map { Quad(it.epochMillis, it.note.ifBlank { "Split" }, it.amount, it.transactionId) } +
            ledger.people.payments.filter { it.personId == p.id }.map { t ->
                Quad(t.epochMillis, t.note.ifBlank { if (t.kind == TxKind.PERSON_IN) "${p.name} paid you" else "You paid ${p.name}" }, if (t.kind == TxKind.PERSON_OUT) t.amount else -t.amount, t.id)
            }
        ).sortedByDescending { it.millis }
    var recording by remember(p.id) { mutableStateOf(false) }
    var splitting by remember(p.id) { mutableStateOf(false) }
    // When the current balance started: the last time it moved away from zero.
    val since = remember(history) {
        var running = 0L; var start: Long? = null
        history.sortedBy { it.millis }.forEach { h -> if (running == 0L && h.amount != 0L) start = h.millis; running += h.amount; if (running == 0L) start = null }
        start
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        if (editing) EditPersonSheet(p, balance, ledger, onDismiss = { editing = false }) { onUpdate(it); editing = false }
        PageTop(p.name + if (p.archived) " (archived)" else "", onBack) {
            TextButton(onClick = { editing = true }) { Text("Edit", color = Kassa.colors.ink, style = KassaType.bodyStrong) }
        }
        RaisedPanel {
            Text(when { balance > 0 -> "${p.name} owes you"; balance < 0 -> "You owe ${p.name}"; else -> "Settled" }, style = KassaType.tiny, color = Kassa.colors.ink2)
            dev.krishnagurram.kassa.ui.today.BigMoney(Math.abs(balance), sizeSp = 36f, color = if (balance > 0) Kassa.colors.positive else Kassa.colors.ink)
            if (balance != 0L) since?.let { Text("Since ${Instant.ofEpochMilli(it).atZone(ledger.zone).format(DateTimeFormatter.ofPattern("MMM d"))}", style = KassaType.bodyStrong, color = Kassa.colors.ink2) }
            Gap(14)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { PrimaryButton("Record a payment") { recording = true } }
                SecondaryButton("Split a transaction", { splitting = true }, Modifier.weight(1f))
            }
        }
        SectionHeader("History")
        if (history.isEmpty()) Text("Nothing yet.", style = KassaType.body, color = Kassa.colors.ink2)
        history.forEach { h ->
            Row(
                Modifier.fillMaxWidth().then(if (h.txId != null) Modifier.clickable { onOpenEntry(h.txId) } else Modifier).padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(h.note, style = KassaType.body, color = Kassa.colors.ink)
                    Text(Instant.ofEpochMilli(h.millis).atZone(ledger.zone).format(DateTimeFormatter.ofPattern("MMM d, yyyy")), style = KassaType.small, color = Kassa.colors.ink2)
                }
                Text(Money.format(h.amount, showPlus = true), style = KassaType.bodyStrong, color = if (h.amount > 0) Kassa.colors.positive else Kassa.colors.ink)
            }
            HorizontalDivider(color = Kassa.colors.line)
        }
        Gap(24)
    }
    if (recording) PaymentSheet(p, balance, ledger, onDismiss = { recording = false }) { onSave(it); recording = false }
    if (splitting) SplitSheet(p, ledger, onDismiss = { splitting = false }) { tx, share -> onSplit(tx, share); splitting = false }
}

private data class Quad(val millis: Long, val note: String, val amount: Long, val txId: Long?)

/** Money between you and one person, either way. Starts on settling the balance. */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PaymentSheet(p: Person, balance: Long, ledger: Ledger, onDismiss: () -> Unit, onSave: (EntryDraft) -> Unit) {
    var direction by remember(p.id, balance) { mutableStateOf(if (balance < 0) TxKind.PERSON_OUT else TxKind.PERSON_IN) }
    var amount by remember(p.id, balance) { mutableStateOf(if (balance != 0L) java.math.BigDecimal.valueOf(Math.abs(balance)).movePointLeft(2).toPlainString() else "") }
    val banks = ledger.accounts.accounts.filter { !it.archived && it.type in setOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH, AccountType.PREPAID, AccountType.CREDIT_CARD) }
    var account by remember { mutableStateOf(banks.firstOrNull { it.type == AccountType.CHECKING }?.id ?: banks.firstOrNull()?.id) }
    var error by remember { mutableStateOf<String?>(null) }
    var date by remember { mutableStateOf(ledger.today) }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Kassa.colors.raised) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text("Record a payment", style = KassaType.section, color = Kassa.colors.ink)
            FlowRow(Modifier.padding(top = 10.dp)) {
                Chip("${p.name} paid me", selected = direction == TxKind.PERSON_IN) { direction = TxKind.PERSON_IN }
                Chip("I paid or lent ${p.name}", selected = direction == TxKind.PERSON_OUT) { direction = TxKind.PERSON_OUT }
            }
            OutlinedTextField(amount, { amount = it; error = null }, singleLine = true, label = { Text("Amount") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            Text(if (direction == TxKind.PERSON_IN) "Into" else "From", style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            FlowRow { banks.forEach { a -> Chip(a.name, selected = account == a.id) { account = a.id } } }
            Text("Date", style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            FlowRow {
                Chip("Today", selected = date == ledger.today, small = true) { date = ledger.today }
                Chip("Yesterday", selected = date == ledger.today.minusDays(1), small = true) { date = ledger.today.minusDays(1) }
                Chip("2 days ago", selected = date == ledger.today.minusDays(2), small = true) { date = ledger.today.minusDays(2) }
            }
            error?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention) }
            Gap(12)
            PrimaryButton("Save payment") {
                val c = Money.parseCents(amount)
                error = when { c == null || c <= 0 -> "Enter an amount."; account == null -> "Pick an account."; else -> null }
                if (error == null) onSave(EntryDraft(
                    id = null, kind = direction, amount = c!!, accountId = account, toAccountId = null, categoryId = null,
                    personId = p.id,
                    epochMillis = date.atTime(if (date == ledger.today) java.time.LocalTime.now(ledger.zone) else java.time.LocalTime.NOON).atZone(ledger.zone).toInstant().toEpochMilli(),
                    note = if (direction == TxKind.PERSON_IN) "${p.name} paid you" else "You paid ${p.name}", splitWith = emptyList(),
                ))
            }
        }
    }
}

/** Pick an expense from the last 60 days and how much of it is theirs: half, or an exact amount. */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SplitSheet(p: Person, ledger: Ledger, onDismiss: () -> Unit, onSplit: (Long, Long) -> Unit) {
    val already = ledger.people.loans.filter { it.personId == p.id }.mapNotNull { it.transactionId }.toSet()
    val from = ledger.today.minusDays(60)
    val choices = ledger.recent.filter { it.kind == TxKind.EXPENSE && it.id !in already && !Instant.ofEpochMilli(it.epochMillis).atZone(ledger.zone).toLocalDate().isBefore(from) }
        .sortedByDescending { it.epochMillis }
    var picked by remember { mutableStateOf<Transaction?>(null) }
    var half by remember { mutableStateOf(true) }
    var exact by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Kassa.colors.raised) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Split with ${p.name}", style = KassaType.section, color = Kassa.colors.ink)
            val t = picked
            if (t == null) {
                Text("Pick an expense", style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                if (choices.isEmpty()) Text("No expenses in the last 60 days.", style = KassaType.body, color = Kassa.colors.ink2)
                LazyColumn(Modifier.fillMaxWidth().height(380.dp)) {
                    items(choices, key = { it.id }) { c -> TransactionRow(c, ledger, showDate = true, onClick = { picked = it }) }
                }
            } else {
                TransactionRow(t, ledger, showDivider = false, showDate = true, onClick = { picked = null })
                val base = t.categoryAmount
                FlowRow(Modifier.padding(top = 8.dp)) {
                    Chip("Half", selected = half) { half = true; error = null }
                    Chip("Exact amount", selected = !half) { half = false; error = null }
                }
                if (!half) OutlinedTextField(exact, { exact = it; error = null }, singleLine = true, label = { Text("${p.name}'s share") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                val share = if (half) base / 2 else Money.parseCents(exact) ?: 0L
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("${p.name} owes ${Money.format(share)}", style = KassaType.bodyStrong, color = Kassa.colors.positive)
                    Text("Your share ${Money.format(base - share)}", style = KassaType.bodyStrong, color = Kassa.colors.ink)
                }
                error?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention) }
                Gap(12)
                PrimaryButton("Split") {
                    error = if (share <= 0 || share > base) "Their share has to be more than zero and at most ${Money.format(base)}." else null
                    if (error == null) onSplit(t.id, share)
                }
            }
        }
    }
}
