package app.kassa.money.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.domain.Account
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.Money
import app.kassa.money.domain.Person
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.LocalSettings
import app.kassa.money.ui.cards.CardRow
import app.kassa.money.ui.cards.cardMoney
import app.kassa.money.ui.cards.cardsByDueDate
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.RaisedPanel
import app.kassa.money.ui.common.accountIcon
import app.kassa.money.ui.importer.RaisedButton
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import app.kassa.money.ui.today.BigMoney

/** How the Accounts tab orders every section; stored in Settings as these names. */
private enum class AccountSort(val label: String) {
    NAME("Name"), BALANCE("Balance"), DUE("Due date");

    fun direction(desc: Boolean) = when (this) {
        NAME -> if (desc) "Z to A" else "A to Z"
        BALANCE -> if (desc) "Smallest first" else "Largest first"
        DUE -> if (desc) "Latest due first" else "Soonest due first"
    }

    companion object {
        fun of(stored: String) = entries.firstOrNull { it.name == stored } ?: NAME
    }
}

/** The sections' keys, which also remember which ones are open. */
private object Section {
    const val CARDS = "cards"
    const val BANKS = "Bank and cash"
    const val INVEST = "Investments"
    const val LOANS = "Loans"
    const val PEOPLE = "people"
    val all = listOf(CARDS, BANKS, INVEST, LOANS, PEOPLE)
}

private val BANK_TYPES = setOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH, AccountType.PREPAID)
private val INVEST_TYPES = setOf(AccountType.INVESTMENT, AccountType.OTHER_ASSET)
private val LOAN_TYPES = setOf(AccountType.LOAN, AccountType.OTHER_DEBT)

/**
 * Everything you own and owe on one page: net worth, then credit cards (payable and outstanding), bank and cash,
 * investments, loans, and people. Each row opens its own page.
 *
 * The add callbacks get a `done` to call once the save succeeded, so a sheet stays open on a problem.
 */
@Composable
fun AccountsScreen(
    ledger: Ledger,
    contentPadding: PaddingValues,
    selectedAccount: Long?,
    onOpenAccount: (Long) -> Unit,
    onOpenPerson: (Long) -> Unit,
    onAddAccount: (Account, String?, () -> Unit) -> Unit,
    onAddPerson: (String, Long, () -> Unit) -> Unit,
) {
    val settings = LocalSettings.current
    val sort = AccountSort.of(settings.accountSort.collectAsState().value)
    val desc by settings.accountSortDesc.collectAsState()
    val state = ledger.accounts
    var addingAccount by remember { mutableStateOf(false) }
    var addingPerson by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    // Sections start folded; which ones are open survives folding the phone.
    var openKeys by rememberSaveable { mutableStateOf("") }
    val open = openKeys.split(',').filter { it.isNotEmpty() }.toSet()
    val toggle: (String) -> Unit = { k -> openKeys = (if (k in open) open - k else open + k).joinToString(",") }

    val balanceOf: (Account) -> Long = { state.balances[it.id] ?: 0L }
    // An investment account is worth its cash plus its holdings.
    val valueOf: (Account) -> Long = { balanceOf(it) + if (it.type == AccountType.INVESTMENT) ledger.holdingsValue(it.id) else 0L }
    fun <T> List<T>.ordered(name: (T) -> String, size: (T) -> Long): List<T> {
        val byName = compareBy<T, String>(String.CASE_INSENSITIVE_ORDER) { name(it) }
        // Due date only orders cards; everything else keeps A to Z then.
        val base = if (sort == AccountSort.BALANCE) sortedWith(compareByDescending<T> { Math.abs(size(it)) }.then(byName)) else sortedWith(byName)
        return if (desc) base.reversed() else base
    }

    val personBalances = ledger.personBalances()
    val owed = ledger.openPersonBalances().values
    val receivable = owed.filter { it > 0 }.sum()
    val payable = -owed.filter { it < 0 }.sum()
    val active = state.accounts.filter { !it.archived }
    val assets = active.filter { !it.type.isDebt }.sumOf(valueOf) + receivable
    val liabilities = -active.filter { it.type.isDebt }.sumOf(balanceOf) + payable

    val cards = if (sort == AccountSort.DUE) cardsByDueDate(ledger).let { if (desc) it.reversed() else it }
    else ledger.cards().ordered({ it.account.name }, { it.snapshot.totalBalance })
    val cardIds = cards.map { it.account.id }.toSet()
    // Cards without statement days have no snapshot; they still belong in the section.
    val unsetCards = active.filter { it.type == AccountType.CREDIT_CARD && it.id !in cardIds }.ordered({ it.name }, balanceOf)
    val banks = active.filter { it.type in BANK_TYPES }.ordered({ it.name }, valueOf)
    val invest = active.filter { it.type in INVEST_TYPES }.ordered({ it.name }, valueOf)
    val loans = active.filter { it.type in LOAN_TYPES }.ordered({ it.name }, balanceOf)
    val people = ledger.people.people.filter { !it.archived }.ordered({ it.name }, { personBalances[it.id] ?: 0L })
    val archived = state.accounts.filter { it.archived }.sortedWith(compareBy<Account, String>(String.CASE_INSENSITIVE_ORDER) { it.name })
    val archivedPeople = ledger.people.people.filter { it.archived }

    val accountRow: @Composable (Account, Long) -> Unit = { a, amount ->
        OneLineRow(iconTile(accountIcon(a.type)), a.name, a.last4, Money.format(amount), selected = a.id == selectedAccount) { onOpenAccount(a.id) }
    }
    val personRow: @Composable (Person) -> Unit = { p ->
        val b = personBalances[p.id] ?: 0
        OneLineRow(
            letterTile(p.name), p.name, null, Money.format(Math.abs(b)),
            suffix = when { b > 0 -> "owes you"; b < 0 -> "you owe"; else -> "settled" },
            amountColor = if (b > 0) Kassa.colors.positive else Kassa.colors.ink,
        ) { onOpenPerson(p.id) }
    }

    val hero: @Composable () -> Unit = {
        RaisedPanel {
            Text("Net worth", style = KassaType.tiny, color = Kassa.colors.ink2)
            BigMoney(assets - liabilities, sizeSp = 36f)
            HorizontalDivider(color = Kassa.colors.line, modifier = Modifier.padding(vertical = 12.dp))
            TwoFigures("Assets" to Money.format(assets), "Liabilities" to Money.format(liabilities))
        }
        val allOpen = Section.all.all { it in open }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            SortHeader(sort, desc, onSort = { settings.setAccountSort(it.name) }, onFlip = { settings.setAccountSortDesc(!desc) })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { openKeys = if (allOpen) "" else Section.all.joinToString(",") }) {
                Text(if (allOpen) "Collapse all" else "Expand all", style = KassaType.bodyStrong, color = Kassa.colors.ink2)
            }
        }
    }
    val cardSection: @Composable () -> Unit = {
        if (cards.isNotEmpty() || unsetCards.isNotEmpty()) {
            // The cards' real balances, the same figure Liabilities adds up: an overpaid card's credit counts too.
            val owed = -(cards.map { it.account } + unsetCards).sumOf(balanceOf)
            GroupHead(
                "Credit cards", if (owed < 0) Money.format(-owed, showPlus = true) else "−" + Money.format(owed),
                Section.CARDS in open, { toggle(Section.CARDS) },
                listOf("Payable" to cardMoney(cards.sumOf { it.snapshot.balancePayable }), "Outstanding" to Money.format(cards.sumOf { it.snapshot.outstanding })),
            )
            if (Section.CARDS in open) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                cards.forEach { c -> CardRow(c, ledger.today, selected = c.account.id == selectedAccount) { onOpenAccount(c.account.id) } }
                unsetCards.forEach { a -> accountRow(a, -balanceOf(a)) }
            }
        }
    }
    val accountSection: @Composable (String, List<Account>, Boolean) -> Unit = { key, list, debt ->
        if (list.isNotEmpty()) {
            val shown: (Account) -> Long = { if (debt) -balanceOf(it) else valueOf(it) }
            GroupHead(key, Money.format(list.sumOf(shown)), key in open, { toggle(key) })
            if (key in open) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { list.forEach { accountRow(it, shown(it)) } }
        }
    }
    val peopleSection: @Composable () -> Unit = {
        GroupHead(
            "Loans and splits", Money.format(receivable - payable), Section.PEOPLE in open, { toggle(Section.PEOPLE) },
            if (people.isEmpty()) null else listOf("Owed to you" to Money.format(receivable), "You owe" to Money.format(payable)),
        )
        if (Section.PEOPLE in open) {
            if (people.isEmpty()) Text("Add people you split costs with or lend to.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { people.forEach { personRow(it) } }
        }
    }
    val footer: @Composable () -> Unit = {
        Gap(20)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RaisedButton("Add an account", { addingAccount = true }, Modifier.weight(1f))
            RaisedButton("Add a person", { addingPerson = true }, Modifier.weight(1f))
        }
        val archivedCount = archived.size + archivedPeople.size
        if (archivedCount > 0) {
            TextButton(onClick = { showArchived = !showArchived }) {
                Text(if (showArchived) "Hide archived ($archivedCount)" else "Show archived ($archivedCount)", color = Kassa.colors.ink2)
            }
            if (showArchived) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                archived.forEach { accountRow(it, balanceOf(it)) }
                archivedPeople.forEach { personRow(it) }
            }
        }
    }
    val title: @Composable () -> Unit = { Text("Accounts", style = KassaType.title, color = Kassa.colors.ink, modifier = Modifier.padding(bottom = 12.dp)) }
    val leftColumn: @Composable () -> Unit = {
        accountSection(Section.BANKS, banks, false)
        accountSection(Section.INVEST, invest, false)
        accountSection(Section.LOANS, loans, true)
        peopleSection()
        footer()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 720.dp) {
            // Unfolded: cards get their own column beside everything else.
            Row(Modifier.fillMaxSize().padding(contentPadding), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { title(); hero(); leftColumn() }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { cardSection() }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
                title(); hero(); cardSection(); leftColumn()
            }
        }
    }

    if (addingAccount) AddAccountSheet(
        groups = state.groups,
        payFromOptions = active.filter { it.type in listOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH) },
        takenNames = state.accounts.map { it.name },
        onDismiss = { addingAccount = false },
        onSave = { account, newGroup -> onAddAccount(account, newGroup) { addingAccount = false } },
    )
    if (addingPerson) AddPersonSheet(ledger.people.people, onDismiss = { addingPerson = false }) { name, opening, done -> onAddPerson(name, opening, done) }
}

/** Two labelled figures side by side, like Assets and Liabilities. */
@Composable
private fun TwoFigures(a: Pair<String, String>, b: Pair<String, String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(a, b).forEach { (label, value) ->
            Column(Modifier.weight(1f)) {
                Text(label, style = KassaType.tiny, color = Kassa.colors.ink2)
                Text(value, style = KassaType.stat, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * The list's order, as Google Files shows it: the sort's name opens a menu, and a separate round button flips the
 * direction. Each has its own 48dp target with space between, so one can't be hit for the other.
 */
@Composable
private fun SortHeader(sort: AccountSort, desc: Boolean, onSort: (AccountSort) -> Unit, onFlip: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            TextButton(onClick = { menu = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(sort.label, style = KassaType.bodyStrong, color = Kassa.colors.ink)
            }
            DropdownMenu(menu, { menu = false }, containerColor = Kassa.colors.raised) {
                AccountSort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label, style = KassaType.body, color = Kassa.colors.ink) },
                        leadingIcon = { Box(Modifier.size(24.dp)) { if (option == sort) Icon(Icons.Outlined.Check, null, tint = Kassa.colors.ink) } },
                        onClick = { menu = false; onSort(option) },
                    )
                }
            }
        }
        Spacer(Modifier.size(12.dp))
        IconButton(onClick = onFlip, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.ArrowUpward, sort.direction(desc), tint = Kassa.colors.ink, modifier = Modifier.size(20.dp).rotate(if (desc) 180f else 0f))
        }
    }
}

/** A section title with its total. Tap to open or fold it; its key figures show while it's open. */
@Composable
private fun GroupHead(title: String, total: String, open: Boolean, onToggle: () -> Unit, figures: List<Pair<String, String>>? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onToggle).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.ChevronRight, if (open) "Fold" else "Open", tint = Kassa.colors.ink3, modifier = Modifier.size(22.dp).rotate(if (open) 90f else 0f))
        Text(title, style = KassaType.section, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp))
        Text(total, style = KassaType.bodyStrong, maxLines = 1, color = if (total.startsWith("+")) Kassa.colors.positive else Kassa.colors.ink)
    }
    if (open && figures != null) Row(Modifier.padding(start = 26.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        figures.forEach { (label, value) ->
            Row {
                Text("$label ", style = KassaType.small, color = Kassa.colors.ink2, maxLines = 1)
                Text(value, style = KassaType.small.copy(fontWeight = FontWeight.SemiBold), maxLines = 1,
                    color = if (value.startsWith("+")) Kassa.colors.positive else Kassa.colors.ink)
            }
        }
    }
    if (open) Gap(10)
}
