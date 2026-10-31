package app.kassa.money.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.outlined.ArrowDownward
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.domain.Account
import app.kassa.money.domain.AccountArrangement
import app.kassa.money.domain.AccountLine
import app.kassa.money.domain.AccountSection
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.AccountsOverview
import app.kassa.money.domain.LoanTerms
import app.kassa.money.domain.Money
import app.kassa.money.domain.Names
import app.kassa.money.domain.Person
import app.kassa.money.ui.CardSnapshotFor
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.LocalSettings
import app.kassa.money.ui.cards.cardMoney
import app.kassa.money.ui.cards.cardTint
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.TabTop
import app.kassa.money.ui.common.balanceColor
import app.kassa.money.ui.common.favourColor
import app.kassa.money.ui.common.figureColor
import app.kassa.money.ui.importer.RaisedButton
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType

/** How the Accounts tab orders every group; stored in Settings as these names. */
private enum class AccountSort(val label: String) {
    NAME("Name"), BALANCE("Balance"), DUE("Due date"), CUSTOM("Custom");

    fun direction(desc: Boolean) = when (this) {
        NAME -> if (desc) "Z to A" else "A to Z"
        BALANCE -> if (desc) "Smallest first" else "Largest first"
        DUE -> if (desc) "Latest due first" else "Soonest due first"
        CUSTOM -> "Your order"
    }

    companion object {
        fun of(stored: String) = entries.firstOrNull { it.name == stored } ?: NAME
    }
}

/** The keys of the groups that aren't an [AccountSection]; folding is remembered by these and the sections' names. */
private object Fold {
    const val PEOPLE = "people"
    const val CLOSED = "closed"
}

/** From this width the groups sit in two columns: what you have on the left, what you owe on the right. */
private val TWO_COLUMNS = 600.dp

/**
 * What you have, what you owe, and when. A strip of Assets, Liabilities and Net worth, then a foldable card for each
 * kind of account you have: Cash and bank, Investments, Other assets, Credit cards, Loans, Other debts, then People and
 * a folded Closed group. Rows show the account's main figure and, for cards, loans and fixed deposits, what to pay and
 * when. Nothing is calculated beyond sums: no rates, trends or forecasts. The grouping and totals are
 * [AccountsOverview]'s; this only lays them out. Each row opens its own page.
 *
 * The add callbacks get a `done` to call once the save succeeded, so a sheet stays open on a problem.
 */
@Composable
fun AccountsScreen(
    ledger: Ledger,
    contentPadding: PaddingValues,
    selectedAccount: Long?,
    folded: Set<String>,
    onFolded: (Set<String>) -> Unit,
    onOpenAccount: (Long) -> Unit,
    onOpenPerson: (Long) -> Unit,
    onAddAccount: (Account, String?, () -> Unit) -> Unit,
    onAddLoan: (Account, LoanTerms, String?, () -> Unit) -> Unit,
    onAddPerson: (String, Long, () -> Unit) -> Unit,
) {
    val settings = LocalSettings.current
    val sort = AccountSort.of(settings.accountSort.collectAsState().value)
    val desc by settings.accountSortDesc.collectAsState()
    val state = ledger.accounts
    var addingAccount by remember { mutableStateOf(false) }
    var addingPerson by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    var arranging by rememberSaveable { mutableStateOf(false) }
    val savedArrangement by settings.accountArrangement.collectAsState()
    val arrangement = savedArrangement.takeIf { sort == AccountSort.CUSTOM }
    val toggle: (String) -> Unit = { k -> onFolded(if (k in folded) folded - k else folded + k) }

    // An investment account is worth its cash plus its holdings.
    val valueOf: (Account) -> Long = { (state.balances[it.id] ?: 0L) + if (it.type == AccountType.INVESTMENT) ledger.holdingsValue(it.id) else 0L }
    val cards = ledger.cards().associateBy { it.account.id }
    val today = ledger.today

    // Due date orders what has one coming (cards with something payable, loans with terms); the rest follow A to Z.
    val dueDay: (Account) -> Int? = { a ->
        when {
            a.type.hasStatement -> cards[a.id]?.snapshot?.takeIf { it.payable > 0 }?.due?.accountDay()
            a.type == AccountType.LOAN -> ledger.loans.terms[a.id]?.nextPaymentDay
            else -> null
        }
    }
    val byName = Names.order<AccountLine> { it.account.name }
    val order: Comparator<AccountLine> = when (sort) {
        AccountSort.NAME -> byName
        AccountSort.BALANCE -> compareByDescending<AccountLine> { Math.abs(it.shown) }.then(byName)
        AccountSort.DUE -> compareBy<AccountLine, Int?>(nullsLast<Int>()) { dueDay(it.account) }.then(byName)
        AccountSort.CUSTOM -> byName
    }.let { if (desc && sort != AccountSort.CUSTOM) it.reversed() else it }

    val personBalances = ledger.personBalances()
    val owed = ledger.openPersonBalances().values
    val receivable = owed.filter { it > 0 }.sum()
    val payable = -owed.filter { it < 0 }.sum()
    val overview = AccountsOverview.of(state.accounts, valueOf, receivable, payable, order, arrangement)
    val people = ledger.people.people.filter { !it.archived }.sortedWith(Names.order { it.name })
    val archived = state.accounts.filter { it.archived }.sortedWith(Names.order { it.name })
    val archivedPeople = ledger.people.people.filter { it.archived }

    // Arranging starts from what is shown now, so a first move keeps every other group and row where it is.
    val shownGroups = overview.groups.map { it.section.name }
    val shownRows = overview.groups.associate { g -> g.section.name to g.lines.map { it.account.id } }
    val moveGroup: (String, List<String>, Int) -> Unit = { key, within, d ->
        settings.setAccountArrangement(AccountArrangement(shownGroups, shownRows).moveGroup(shownGroups, within, key, d))
    }
    val moveAccount: (String, Long, Int) -> Unit = { key, id, d ->
        settings.setAccountArrangement(AccountArrangement(shownGroups, shownRows).moveAccount(shownGroups, key, shownRows.getValue(key), id, d))
    }

    // [within] is the groups of the column this one sits in, so a group moves only among its own column.
    val groupCard: @Composable (AccountSection, List<AccountLine>, Long, List<String>) -> Unit = { section, lines, total, within ->
        val key = section.name
        val debt = section.isDebt
        val at = within.indexOf(key)
        GroupCard(
            section.title, if (debt) cardMoney(total) else Money.format(total),
            if (debt) cardTint(total) else figureColor(if (total < 0) Kassa.colors.attention else Kassa.colors.ink),
            open = key !in folded, onToggle = { toggle(key) },
            trailing = if (arranging) ({
                MoveButtons(section.title, canUp = at > 0, canDown = at in 0 until within.lastIndex,
                    onUp = { moveGroup(key, within, -1) }, onDown = { moveGroup(key, within, 1) })
            }) else null,
        ) {
            lines.forEachIndexed { i, line ->
                HorizontalDivider(color = Kassa.colors.line)
                val id = line.account.id
                AccountRow(
                    line, ledger, cards, selected = id == selectedAccount, arranging = arranging,
                    mover = { MoveButtons(line.account.name, canUp = i > 0, canDown = i < lines.lastIndex,
                        onUp = { moveAccount(key, id, -1) }, onDown = { moveAccount(key, id, 1) }) },
                ) { onOpenAccount(id) }
            }
        }
    }
    val peopleCard: @Composable () -> Unit = {
        GroupCard(
            "People", Money.format(receivable - payable), favourColor(receivable - payable),
            open = Fold.PEOPLE !in folded, onToggle = { toggle(Fold.PEOPLE) },
        ) {
            if (people.isNotEmpty()) {
                HorizontalDivider(color = Kassa.colors.line)
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Receivable ${Money.format(receivable)}", style = KassaType.small, color = Kassa.colors.ink2, maxLines = 1)
                    Text("Payable ${Money.format(payable)}", style = KassaType.small, color = Kassa.colors.ink2, maxLines = 1)
                }
            } else {
                Text("Add people you split costs with or lend to.", style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(12.dp))
            }
            people.forEach { p ->
                HorizontalDivider(color = Kassa.colors.line)
                PersonRow(p, personBalances[p.id] ?: 0L) { onOpenPerson(p.id) }
            }
        }
    }
    val closedCard: @Composable () -> Unit = {
        if (overview.closed.isNotEmpty()) GroupCard(
            "Closed · ${overview.closed.size}", null, Kassa.colors.ink,
            open = Fold.CLOSED !in folded, onToggle = { toggle(Fold.CLOSED) },
        ) {
            overview.closed.forEach { a ->
                HorizontalDivider(color = Kassa.colors.line)
                OneLineRow(
                    a.name, a.last4, "", selected = a.id == selectedAccount, grouped = true,
                    note = "Kept in your history", dueNote = a.closedDay?.let { "Closed ${accountDate(it)}" },
                ) { onOpenAccount(a.id) }
            }
        }
    }
    val strip: @Composable (Boolean) -> Unit = { large ->
        val net = overview.netWorth
        FigureStrip(
            listOf(
                Triple("Assets", Money.format(overview.assets), Kassa.colors.ink),
                Triple("Liabilities", Money.format(overview.liabilities), Kassa.colors.ink),
                // Below zero it's orange with its minus sign, like every figure that's against you; hidden, plain.
                Triple("Net worth", Money.format(net), figureColor(if (net < 0) Kassa.colors.attention else Kassa.colors.ink)),
            ),
            large,
        )
    }
    val sortBar: @Composable () -> Unit = {
        val keys = overview.groups.map { it.section.name } + Fold.PEOPLE + (if (overview.closed.isNotEmpty()) listOf(Fold.CLOSED) else emptyList())
        val allFolded = keys.all { it in folded }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SortHeader(sort, desc, onSort = { settings.setAccountSort(it.name) }, onFlip = { settings.setAccountSortDesc(!desc) })
            TextButton(onClick = { arranging = !arranging }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (arranging) "Done" else "Arrange", style = KassaType.bodyStrong, color = Kassa.colors.ink)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onFolded(if (allFolded) emptySet() else keys.toSet()) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (allFolded) "Expand all" else "Collapse all", style = KassaType.bodyStrong, color = Kassa.colors.ink2)
            }
        }
        if (arranging) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Move groups and accounts with the arrows.", style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.weight(1f))
            TextButton(onClick = { settings.resetAccountArrangement() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Reset to A to Z", style = KassaType.bodyStrong, color = Kassa.colors.ink2)
            }
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
            TextButton(onClick = { showArchived = !showArchived }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (showArchived) "Hide archived ($archivedCount)" else "Show archived ($archivedCount)", color = Kassa.colors.ink2)
            }
            if (showArchived) Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Kassa.colors.raised)) {
                archived.forEachIndexed { i, a ->
                    if (i > 0) HorizontalDivider(color = Kassa.colors.line)
                    AccountRow(AccountLine(a, if (a.type.isDebt) -valueOf(a) else valueOf(a)), ledger, cards, selected = a.id == selectedAccount) { onOpenAccount(a.id) }
                }
                archivedPeople.forEachIndexed { i, p ->
                    if (archived.isNotEmpty() || i > 0) HorizontalDivider(color = Kassa.colors.line)
                    PersonRow(p, personBalances[p.id] ?: 0L) { onOpenPerson(p.id) }
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val two = maxWidth >= TWO_COLUMNS
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = if (two) 1100.dp else 720.dp)) {
            TabTop("Accounts")
            strip(two)
            sortBar()
            Gap(4)
            if (two) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        overview.assetGroups.map { it.section.name }.let { within -> overview.assetGroups.forEach { groupCard(it.section, it.lines, it.total, within) } }
                        peopleCard()
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        overview.liabilityGroups.map { it.section.name }.let { within -> overview.liabilityGroups.forEach { groupCard(it.section, it.lines, it.total, within) } }
                        if (overview.liabilityGroups.isEmpty()) Text(
                            "Cards and loans show here when you add one.", style = KassaType.small, color = Kassa.colors.ink2,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                Gap(10)
                closedCard()
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    overview.groups.forEach { groupCard(it.section, it.lines, it.total, shownGroups) }
                    peopleCard()
                    closedCard()
                }
            }
            footer()
        }
    }

    if (addingAccount) AddAccountSheet(
        groups = state.groups,
        payFromOptions = state.accounts.filter { it.isActive && it.type in listOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH) },
        takenNames = state.accounts.map { it.name },
        categories = ledger.categories,
        today = today,
        onDismiss = { addingAccount = false },
        onSave = { account, newGroup -> onAddAccount(account, newGroup) { addingAccount = false } },
        onSaveLoan = { account, terms, newGroup -> onAddLoan(account, terms, newGroup) { addingAccount = false } },
    )
    if (addingPerson) AddPersonSheet(ledger.people.people, onDismiss = { addingPerson = false }) { name, opening, done -> onAddPerson(name, opening, done) }
}

/**
 * One account in a group. The main figure is what you have, or for a card, loan or other debt what you owe (a credit
 * on a card is a green +). A card adds what to pay and when it's due; a loan with terms, its payment and next date; a
 * fixed deposit, when it matures. Orange is for something to pay and for a payment due today or overdue.
 */
@Composable
private fun AccountRow(
    line: AccountLine, ledger: Ledger, cards: Map<Long, CardSnapshotFor>, selected: Boolean,
    arranging: Boolean = false, mover: (@Composable () -> Unit)? = null, onClick: () -> Unit,
) {
    val a = line.account
    val debt = a.type.isDebt
    var note: String? = null
    var noteColor = Kassa.colors.ink2
    var dueNote: String? = null
    var dueColor = Kassa.colors.ink2
    val todayDay = ledger.today.accountDay()
    when (a.type) {
        AccountType.CREDIT_CARD -> {
            // A card without statement days has nothing to split yet, so it shows only its total.
            val s = cards[a.id]?.snapshot
            if (s != null && s.payable > 0) {
                note = "Pay ${Money.format(s.payable)}"
                noteColor = Kassa.colors.attention
                dueNote = "Due ${accountShortDate(s.due)}"
                if (AccountsOverview.dueNeedsAttention(s.due.accountDay(), todayDay)) dueColor = Kassa.colors.attention
            } else if (s != null) {
                note = "Nothing to pay"
            }
        }
        AccountType.LOAN -> {
            // A loan without terms shows only what's outstanding; so does one that's paid off.
            val t = ledger.loans.terms[a.id]
            if (t != null && line.shown > 0) {
                note = "Pay ${Money.format(t.scheduledTotal)}"
                dueNote = "Due ${accountShortDate(dateOfAccountDay(t.nextPaymentDay))}"
                if (AccountsOverview.dueNeedsAttention(t.nextPaymentDay, todayDay)) {
                    noteColor = Kassa.colors.attention
                    dueColor = Kassa.colors.attention
                }
            }
        }
        AccountType.FIXED_DEPOSIT -> {
            val matures = a.maturityDay
            if (matures != null) dueNote = "Matures ${accountShortDate(dateOfAccountDay(matures))}"
        }
        else -> Unit
    }
    OneLineRow(
        a.name, a.last4, if (debt) cardMoney(line.shown) else Money.format(line.shown), selected = selected, grouped = true,
        amountColor = if (debt) cardTint(line.shown) else balanceColor(a.type, line.shown),
        note = note, noteColor = noteColor, dueNote = dueNote, dueColor = dueColor,
        enabled = !arranging, trailing = if (arranging) mover else null, onClick = onClick,
    )
}

/** A person: what's between you and them, as Receivable (they owe you) or Payable (you owe them). */
@Composable
private fun PersonRow(p: Person, balance: Long, onClick: () -> Unit) {
    OneLineRow(
        p.name, null, Money.format(Math.abs(balance)), grouped = true,
        suffix = when { balance > 0 -> "owes you"; balance < 0 -> "you owe"; else -> "settled" },
        amountColor = favourColor(balance), onClick = onClick,
    )
}

/**
 * A foldable card: its title and [total] in a header 48dp tall that folds or opens it, then [content] while open. The
 * fold state is the caller's, so it survives folding the phone.
 */
@Composable
private fun GroupCard(
    title: String, total: String?, totalColor: Color, open: Boolean, onToggle: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Kassa.colors.raised)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = if (open) "Fold $title" else "Open $title", onClick = onToggle).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ChevronRight, null, tint = Kassa.colors.ink3, modifier = Modifier.size(22.dp).rotate(if (open) 90f else 0f))
            Text(title, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp))
            if (total != null) Text(total, style = KassaType.bodyStrong, color = totalColor, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 10.dp))
            trailing?.invoke()
        }
        if (open) content()
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
                // Custom is only listed while it's the order in use; arranging is what makes it.
                AccountSort.entries.filter { it != AccountSort.CUSTOM || sort == AccountSort.CUSTOM }.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label, style = KassaType.body, color = Kassa.colors.ink) },
                        leadingIcon = { Box(Modifier.size(24.dp)) { if (option == sort) Icon(Icons.Outlined.Check, null, tint = Kassa.colors.ink) } },
                        onClick = { menu = false; onSort(option) },
                    )
                }
            }
        }
        Spacer(Modifier.size(12.dp))
        if (sort != AccountSort.CUSTOM) IconButton(onClick = onFlip, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.ArrowUpward, sort.direction(desc), tint = Kassa.colors.ink, modifier = Modifier.size(20.dp).rotate(if (desc) 180f else 0f))
        }
    }
}

/** Up and down for one group or account while arranging: two 48dp buttons, each off at its end of the list. */
@Composable
private fun MoveButtons(label: String, canUp: Boolean, canDown: Boolean, onUp: () -> Unit, onDown: () -> Unit) {
    Row {
        IconButton(onClick = onUp, enabled = canUp, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.ArrowUpward, "Move $label up", tint = if (canUp) Kassa.colors.ink else Kassa.colors.ink3, modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onDown, enabled = canDown, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.ArrowDownward, "Move $label down", tint = if (canDown) Kassa.colors.ink else Kassa.colors.ink3, modifier = Modifier.size(20.dp))
        }
    }
}
