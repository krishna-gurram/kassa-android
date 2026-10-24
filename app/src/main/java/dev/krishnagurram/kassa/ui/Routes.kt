package dev.krishnagurram.kassa.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.krishnagurram.kassa.domain.subcategory
import dev.krishnagurram.kassa.ui.accounts.AccountPage
import dev.krishnagurram.kassa.ui.accounts.AccountsScreen
import dev.krishnagurram.kassa.ui.accounts.EditAccountPage
import dev.krishnagurram.kassa.ui.accounts.PersonPage
import dev.krishnagurram.kassa.ui.activity.ActivityScreen
import dev.krishnagurram.kassa.ui.cards.CardPage
import dev.krishnagurram.kassa.ui.common.PageTop
import dev.krishnagurram.kassa.ui.entry.BillPage
import dev.krishnagurram.kassa.ui.entry.EntryPage
import dev.krishnagurram.kassa.ui.importer.ImportScreen
import dev.krishnagurram.kassa.ui.insights.CategoryPage
import dev.krishnagurram.kassa.ui.insights.DayPage
import dev.krishnagurram.kassa.ui.insights.InsightDetailPage
import dev.krishnagurram.kassa.ui.insights.InsightStorePage
import dev.krishnagurram.kassa.ui.insights.InsightsScreen
import dev.krishnagurram.kassa.ui.settings.SettingsScreen
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType
import dev.krishnagurram.kassa.ui.today.NotificationsPage
import dev.krishnagurram.kassa.ui.today.SpendingPage
import dev.krishnagurram.kassa.ui.today.TodayScreen
import dev.krishnagurram.kassa.ui.today.UpcomingPage
import java.time.LocalDate

/*
 * Which composable draws each tab and each page. Adding a page means a [Page] in Nav.kt and a branch here.
 */

/** The screen for the selected tab. [selected] is the page open beside it, to highlight its row. */
@Composable
fun TabContent(app: AppController, l: Ledger, padding: PaddingValues, selected: Page?) {
    when (app.nav.tab) {
        Tab.TODAY -> TodayScreen(
            l, padding,
            onOpenCard = { app.openFromList(Page.CardDetail(it)) },
            onSpending = { month, income -> app.openFromList(Page.Spending(month, income)) },
            onOpenCategory = { app.openFromList(Page.CategoryDetail(it)) },
            onUpcoming = { app.openFromList(Page.Upcoming) },
            onNotifications = { app.openFromList(Page.Notifications) },
            onAccounts = { app.nav.select(Tab.ACCOUNTS) },
            onCards = { app.nav.select(Tab.ACCOUNTS) },
        )
        Tab.ACTIVITY -> ActivityScreen(
            l, padding, onEdit = app.editFromList, onOpenDay = { app.openFromList(Page.Day(it.toEpochDay())) },
            selectedId = (selected as? Page.Entry)?.start?.editing?.id,
        )
        Tab.INSIGHTS -> InsightsScreen(l, padding, app.insights)
        Tab.ACCOUNTS -> AccountsScreen(
            l, padding,
            selectedAccount = (selected as? Page.AccountDetail)?.accountId,
            onOpenAccount = { app.openFromList(Page.AccountDetail(it)) },
            onOpenPerson = { app.openFromList(Page.PersonDetail(it)) },
            onAddAccount = { account, group, done -> app.vm.addAccount(account, group, done) },
            onAddPerson = { name, opening, done -> app.vm.addPerson(name, opening, done) },
        )
        Tab.SETTINGS -> SettingsScreen(l, app.settings, padding)
    }
}

/** One page. [onBack] is null for the default page beside a list, which has nothing to go back to. */
@Composable
fun PageContent(app: AppController, page: Page, l: Ledger, padding: PaddingValues, onBack: (() -> Unit)?) {
    val vm = app.vm
    val back = onBack ?: {}
    when (page) {
        is Page.Entry -> EntryPage(
            ledger = l, start = page.start, contentPadding = padding,
            loadNotes = { vm.notes(it) },
            onSave = { vm.save(it, page.start.afterSave, onDone = app::back) },
            onDelete = { vm.delete(it); app.back() },
            onAddSubcategory = { parent, name, then -> vm.saveCategory(parent.subcategory(name), null, then = then) },
            onBack = back,
        )
        is Page.Bill -> BillPage(
            ledger = l, bill = page.bill, contentPadding = padding,
            onSave = { vm.saveBill(it, onDone = app::back) },
            onDelete = { vm.deleteBill(it); app.back() },
            onBack = back,
        )
        is Page.CardDetail, is Page.AccountDetail -> AccountOrCard(app, if (page is Page.CardDetail) page.accountId else (page as Page.AccountDetail).accountId, l, padding, onBack)
        is Page.EditAccount -> {
            val a = l.accountById[page.accountId]
            if (a == null) Gone(padding, onBack)
            else key(a.id) {
                EditAccountPage(
                    l, a, padding, onBack = back,
                    onSave = { account, group -> vm.updateAccount(account, group, onDone = app::back) },
                    onArchive = { archived ->
                        vm.setArchived(a, archived)
                        app.back()
                        // An archived account's own page has nothing left to show.
                        if (archived && (app.nav.top is Page.CardDetail || app.nav.top is Page.AccountDetail)) app.back()
                    },
                )
            }
        }
        is Page.PersonDetail -> {
            val p = l.people.people.firstOrNull { it.id == page.personId }
            if (p == null) Gone(padding, onBack)
            else PersonPage(
                p, l.personBalances()[p.id] ?: 0, l, padding, onBack,
                onSave = { draft, done -> vm.save(draft, onDone = done) },
                onSplit = { txId -> app.openEntry(txId, fromPage = true, withPerson = p.id) },
                onUpdate = { person, starting, done -> vm.updatePerson(person, starting, done) },
                onOpenEntry = { id -> app.openEntry(id, fromPage = true) },
            )
        }
        is Page.CategoryDetail -> {
            val c = l.categoryById[page.categoryId]
            if (c == null) Gone(padding, onBack) else CategoryPage(l, c, padding, onBack, onEdit = app.editFromPage, load = vm::between)
        }
        is Page.Insight -> InsightDetailPage(
            l, page.page, padding, onBack,
            onOpenCategory = { app.push(Page.CategoryDetail(it.id)) }, onOpenCard = { app.push(Page.CardDetail(it)) },
            onOpenStore = { app.push(Page.Store(it)) }, onEdit = app.editFromPage,
        )
        is Page.Store -> InsightStorePage(l, page.name, padding, onBack, onEdit = app.editFromPage)
        is Page.Day -> DayPage(l, LocalDate.ofEpochDay(page.epochDay), padding, onBack, onEdit = app.editFromPage, load = vm::between)
        is Page.Spending -> SpendingPage(
            l, page.month, page.income, padding, onBack, onEdit = app.editFromPage,
            onOpenCategory = { app.push(Page.CategoryDetail(it)) }, onOpenDay = { app.push(Page.Day(it.toEpochDay())) },
        )
        Page.Upcoming -> UpcomingPage(l, padding, onBack, onOpenCard = { app.push(Page.CardDetail(it)) })
        Page.Notifications -> NotificationsPage(l, padding, onBack, app.notices)
        Page.Import -> Box(Modifier.padding(padding)) {
            ImportScreen(firstRun = false, onFinished = { app.nav.select(Tab.TODAY) }, onCancel = back)
        }
    }
}

/** A card opens its statement page; any other account its own page. */
@Composable
private fun AccountOrCard(app: AppController, accountId: Long, l: Ledger, padding: PaddingValues, onBack: (() -> Unit)?) {
    val a = l.accountById[accountId]
    if (a == null) { Gone(padding, onBack); return }
    val history by remember(a.id) { app.vm.accountHistory(a.id) }.collectAsState(initial = emptyList())
    val card = l.cards().firstOrNull { it.account.id == a.id }.takeIf { !a.archived }
    if (card != null) CardPage(
        card, l, padding, onBack, onEdit = app.editFromPage, onPay = { app.paying = a.id },
        onEditCard = { app.push(Page.EditAccount(a.id)) }, history = history,
    )
    else AccountPage(
        l, a, history, padding, onBack,
        onEditEntry = app.editFromPage,
        onEditAccount = { app.push(Page.EditAccount(a.id)) },
        onSaveHolding = { app.vm.saveHolding(it) },
        onDeleteHolding = { app.vm.deleteHolding(it.id) },
        onBuy = { o -> app.vm.buy(a.id, o.ticker, o.microShares, o.price, o.at, transferId = null) },
        onSell = { o -> app.vm.sell(a.id, o.ticker, o.microShares, o.price, o.at) },
    )
}

/** Shown when a page's account, card, person or category was removed while it was open. */
@Composable
private fun Gone(padding: PaddingValues, onBack: (() -> Unit)?) {
    Column(Modifier.padding(padding)) {
        PageTop("", onBack)
        Text("This is no longer here.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(16.dp))
    }
}
