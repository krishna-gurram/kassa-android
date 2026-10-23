package dev.krishnagurram.kassa.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
    onAddPerson: (String) -> Unit,
) {
    val state = ledger.accounts
    var adding by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    var newPerson by remember { mutableStateOf("") }

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
    }
    val cardSection: @Composable () -> Unit = {
        if (cards.isNotEmpty() || unsetCards.isNotEmpty()) {
            val owed = cards.sumOf { it.snapshot.totalBalance } - unsetCards.sumOf { state.balances[it.id] ?: 0 }
            GroupHead("Credit cards", "−" + Money.format(owed), listOf("Payable" to Money.format(cards.sumOf { it.snapshot.payable }), "Outstanding" to Money.format(cards.sumOf { it.snapshot.outstanding })))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                cards.forEach { c -> dev.krishnagurram.kassa.ui.cards.CardRow(c, ledger.today, selected = c.account.id == selectedAccount) { onOpenAccount(c.account.id) } }
                unsetCards.forEach { a -> OneLineRow(IconTile(accountIcon(a.type)), a.name, a.last4, Money.format(-(state.balances[a.id] ?: 0)), selected = a.id == selectedAccount) { onOpenAccount(a.id) } }
            }
        }
    }
    val accountGroup: @Composable (String, List<Account>, Boolean) -> Unit = { title, list, debt ->
        if (list.isNotEmpty()) {
            GroupHead(title, Money.format(list.sumOf { if (debt) -(state.balances[it.id] ?: 0) else valueOf(it) }))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                list.forEach { a -> OneLineRow(IconTile(accountIcon(a.type)), a.name, a.last4, Money.format(if (debt) -(state.balances[a.id] ?: 0) else valueOf(a)), selected = a.id == selectedAccount) { onOpenAccount(a.id) } }
            }
        }
    }
    val peopleSection: @Composable () -> Unit = {
        GroupHead("Loans and splits", Money.format(receivable - payable), if (people.isEmpty()) null else listOf("Owed to you" to Money.format(receivable), "You owe" to Money.format(payable)))
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
        Row(Modifier.padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(newPerson, { newPerson = it }, singleLine = true, label = { Text("Add a person") }, modifier = Modifier.weight(1f))
            TextButton(onClick = { if (newPerson.isNotBlank()) { onAddPerson(newPerson.trim()); newPerson = "" } }) { Text("Add", color = Kassa.colors.ink) }
        }
    }
    val footer: @Composable () -> Unit = {
        Gap(16)
        SecondaryButton("Add an account", { adding = true })
        if (archived.isNotEmpty()) {
            TextButton(onClick = { showArchived = !showArchived }) {
                Text(if (showArchived) "Hide archived (${archived.size})" else "Show archived (${archived.size})", color = Kassa.colors.ink2)
            }
            if (showArchived) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                archived.forEach { a -> OneLineRow(IconTile(accountIcon(a.type)), a.name, a.last4, Money.format(state.balances[a.id] ?: 0), selected = a.id == selectedAccount) { onOpenAccount(a.id) } }
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
}

/** A section title with its total, and optionally two key figures under it. */
@Composable
private fun GroupHead(title: String, total: String, figures: List<Pair<String, String>>? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
        Text(title, style = KassaType.section, color = Kassa.colors.ink, modifier = Modifier.weight(1f))
        Text(total, style = KassaType.bodyStrong, color = Kassa.colors.ink)
    }
    if (figures != null) Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        figures.forEach { (l, v) ->
            Row {
                Text("$l ", style = KassaType.small, color = Kassa.colors.ink2)
                Text(v, style = KassaType.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Kassa.colors.ink)
            }
        }
    }
    Gap(10)
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
 * One account, built like the card page: a summary with two equal boxes, then the details. Bank accounts show money
 * in and out (this month and six months of bars); investments show holdings and cash. Then every entry, by month,
 * with the balance right after each.
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
) {
    val balance = ledger.accounts.balances[account.id] ?: 0
    val holdings = ledger.assist.holdings.filter { it.accountId == account.id }
    val holdingsValue = holdings.sumOf { it.value }
    var holdingOpen by remember { mutableStateOf<dev.krishnagurram.kassa.domain.Holding?>(null) }
    // Balance right after each entry: walk back from today's balance.
    val after = remember(history, balance) {
        var running = balance
        history.map { t -> (t to running).also { running -= t.effectOn(account.id) } }
    }
    val months = after.groupBy { (t, _) -> YearMonth.from(Instant.ofEpochMilli(t.epochMillis).atZone(ledger.zone)) }.toList()
    val isInvest = account.type == AccountType.INVESTMENT
    val periods = (5 downTo 0).map { dev.krishnagurram.kassa.domain.Period.startingIn(ledger.period.yearMonth.minusMonths(it.toLong()), ledger.monthStartDay) }
    val inOut = remember(history, ledger.today) {
        periods.map { p ->
            val inP = history.filter { Instant.ofEpochMilli(it.epochMillis).atZone(ledger.zone).toLocalDate() in p }
            inP.sumOf { maxOf(0L, it.effectOn(account.id)) } to inP.sumOf { maxOf(0L, -it.effectOn(account.id)) }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Column(Modifier.widthIn(max = 720.dp)) {
                PageTop(account.name + (account.last4?.takeIf { it.isNotBlank() }?.let { "  ··$it" } ?: ""), onBack) {
                    TextButton(onClick = onEditAccount) { Text("Edit", style = KassaType.bodyStrong, color = Kassa.colors.ink) }
                }
                RaisedPanel {
                    when {
                        isInvest -> {
                            Text("Total value", style = KassaType.tiny, color = Kassa.colors.ink2)
                            dev.krishnagurram.kassa.ui.today.BigMoney(balance + holdingsValue, sizeSp = 36f)
                            TwoBoxes("Holdings" to Money.format(holdingsValue), "Cash" to Money.format(balance))
                            Gap(12)
                            PrimaryButton(if (holdings.isEmpty()) "Add a holding" else "Add or update a holding") {
                                holdingOpen = dev.krishnagurram.kassa.domain.Holding(accountId = account.id, ticker = "", microShares = 0, price = 0, priceMillis = 0)
                            }
                        }
                        account.type == AccountType.CREDIT_CARD -> {
                            Text("Total balance", style = KassaType.tiny, color = Kassa.colors.ink2)
                            dev.krishnagurram.kassa.ui.today.BigMoney(-balance, sizeSp = 36f)
                            Text("Add the statement day and due day to split this into balance payable and outstanding.", style = KassaType.small, color = Kassa.colors.ink2)
                            TextButton(onClick = onEditAccount) { Text("Add statement days", color = Kassa.colors.ink) }
                        }
                        else -> {
                            Text(if (account.type.isDebt) "Balance owed" else "${account.type.label} balance", style = KassaType.tiny, color = Kassa.colors.ink2)
                            dev.krishnagurram.kassa.ui.today.BigMoney(if (account.type.isDebt) -balance else balance, sizeSp = 36f)
                            val (i, o) = inOut.last()
                            TwoBoxes("In this month" to Money.format(i), "Out this month" to Money.format(o), aColor = Kassa.colors.positive)
                        }
                    }
                }
                if (isInvest) {
                    if (holdings.isNotEmpty()) {
                        val oldest = holdings.minOf { it.priceMillis }
                        val days = java.time.temporal.ChronoUnit.DAYS.between(Instant.ofEpochMilli(oldest).atZone(ledger.zone).toLocalDate(), ledger.today)
                        SectionHeader("Holdings", if (days > 14) "Prices $days days old" else "Prices from ${Instant.ofEpochMilli(oldest).atZone(ledger.zone).format(DateTimeFormatter.ofPattern("MMM d"))}")
                        holdings.sortedByDescending { it.value }.forEachIndexed { i, h ->
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
                    }
                } else if (account.type != AccountType.CREDIT_CARD && history.isNotEmpty()) {
                    SectionHeader("In and out")
                    val max = inOut.maxOf { maxOf(it.first, it.second) }.coerceAtLeast(1)
                    Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        periods.forEachIndexed { idx, p ->
                            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                                    val (i, o) = inOut[idx]
                                    androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxHeight((i.toFloat() / max).coerceIn(0.02f, 1f)).clip(RoundedCornerShape(4.dp)).background(Kassa.colors.positive.copy(alpha = 0.8f)))
                                    androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxHeight((o.toFloat() / max).coerceIn(0.02f, 1f)).clip(RoundedCornerShape(4.dp)).background(Kassa.colors.ink.copy(alpha = 0.75f)))
                                }
                                Text(p.yearMonth.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()), style = KassaType.tiny, color = if (idx == periods.lastIndex) Kassa.colors.ink else Kassa.colors.ink2, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("■ In", style = KassaType.tiny, color = Kassa.colors.positive)
                        Text("■ Out", style = KassaType.tiny, color = Kassa.colors.ink2)
                    }
                }
                if (history.isEmpty()) Text("No entries in this account yet.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 24.dp))
            }
        }
        months.forEach { (month, rows) ->
            item(key = "m$month") {
                val net = rows.sumOf { (t, _) -> t.effectOn(account.id) }
                Column(Modifier.widthIn(max = 720.dp)) {
                    SectionHeader(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), "Net ${Money.format(net, showPlus = true)}")
                }
            }
            items(rows, key = { it.first.id }) { (t, bal) ->
                Column(Modifier.widthIn(max = 720.dp)) {
                    TransactionRow(t, ledger, showDivider = t !== rows.last().first, noteOverride = "Balance ${Money.format(bal)}", onClick = onEditEntry, showDate = true)
                }
            }
        }
    }
    holdingOpen?.let { h ->
        dev.krishnagurram.kassa.ui.invest.HoldingSheet(h, onSave = { onSaveHolding(it); holdingOpen = null }, onDelete = { onDeleteHolding(it); holdingOpen = null }, onDismiss = { holdingOpen = null })
    }
}

/** A person's receivable or payable and its history, with a way to record money moving either way. */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PersonPage(p: Person, balance: Long, ledger: Ledger, contentPadding: PaddingValues, onBack: (() -> Unit)?, onSave: (EntryDraft) -> Unit) {
    // Default direction settles the balance: if they owe you, they're paying you.
    var direction by remember(p.id, balance) { mutableStateOf(if (balance < 0) TxKind.PERSON_OUT else TxKind.PERSON_IN) }
    var amount by remember(p.id, balance) { mutableStateOf(if (balance != 0L) java.math.BigDecimal.valueOf(Math.abs(balance)).movePointLeft(2).toPlainString() else "") }
    val banks = ledger.accounts.accounts.filter { !it.archived && it.type in setOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH, AccountType.PREPAID) }
    var account by remember { mutableStateOf(banks.firstOrNull()?.id) }
    var error by remember { mutableStateOf<String?>(null) }
    var date by remember { mutableStateOf(ledger.today) }
    var pickDate by remember { mutableStateOf(false) }
    val history = (
        ledger.people.loans.filter { it.personId == p.id }.map { Triple(it.epochMillis, it.note.ifBlank { "Split" }, it.amount) } +
            ledger.people.payments.filter { it.personId == p.id }.map { t ->
                Triple(t.epochMillis, t.note.ifBlank { if (t.kind == TxKind.PERSON_IN) "${p.name} paid you" else "You paid ${p.name}" }, if (t.kind == TxKind.PERSON_OUT) t.amount else -t.amount)
            }
        ).sortedByDescending { it.first }
    var recording by remember(p.id) { mutableStateOf(false) }
    val plus = history.filter { it.third > 0 }.sumOf { it.third }
    val minus = -history.filter { it.third < 0 }.sumOf { it.third }
    // When the current balance started: the last time it moved away from zero.
    val since = remember(history) {
        var running = 0L; var start: Long? = null
        history.sortedBy { it.first }.forEach { (m, _, a) -> if (running == 0L && a != 0L) start = m; running += a; if (running == 0L) start = null }
        start
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        PageTop(p.name, onBack)
        RaisedPanel {
            Text(when { balance > 0 -> "${p.name} owes you"; balance < 0 -> "You owe ${p.name}"; else -> "Settled" }, style = KassaType.tiny, color = Kassa.colors.ink2)
            dev.krishnagurram.kassa.ui.today.BigMoney(Math.abs(balance), sizeSp = 36f, color = if (balance > 0) Kassa.colors.positive else Kassa.colors.ink)
            since?.let { Text("Since ${Instant.ofEpochMilli(it).atZone(ledger.zone).format(DateTimeFormatter.ofPattern("MMM d"))}", style = KassaType.bodyStrong, color = Kassa.colors.ink2) }
            if (balance >= 0) TwoBoxes("Lent or split" to Money.format(plus), "Paid back" to Money.format(minus))
            else TwoBoxes("Borrowed" to Money.format(minus), "Paid back" to Money.format(plus))
            Gap(12)
            PrimaryButton(if (balance < 0) "I paid ${p.name}" else "${p.name} paid me") { recording = !recording }
        }
        if (recording) {
        SectionHeader("Record a payment")
        FlowRow(Modifier.padding(top = 6.dp)) {
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
            val other = date != ledger.today && date != ledger.today.minusDays(1)
            Chip(if (other) date.format(DateTimeFormatter.ofPattern("EEE, MMM d")) else "Pick a date", selected = other, small = true, dashed = !other) { pickDate = true }
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
            if (error == null) recording = false
        }
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
    if (pickDate) {
        val state = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate() }
                    pickDate = false
                }) { Text("Done", color = Kassa.colors.ink) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel", color = Kassa.colors.ink2) } },
        ) { androidx.compose.material3.DatePicker(state = state) }
    }
}
