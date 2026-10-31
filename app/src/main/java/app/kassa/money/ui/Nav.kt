package app.kassa.money.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import app.kassa.money.ui.entry.EntryStart

/** Cards live in Accounts now; Add sits in the middle of the bar, after Activity. */
enum class Tab(val label: String) { TODAY("Overview"), ACTIVITY("Activity"), INSIGHTS("Insights"), ACCOUNTS("Accounts"), SETTINGS("Settings") }

/**
 * A page opened on top of a tab, with a back button. Quick actions (pay a card, record a payment, update a price)
 * stay bottom sheets; anything you browse or fill in at length is a page.
 */
sealed interface Page {
    /** On the unfolded screen, pages open beside the list they came from. Entry and import take the screen. */
    val besideList: Boolean get() = true

    /** Full screen everywhere: half a screen is too tight for the amount, pickers and number pad together. */
    data class Entry(val start: EntryStart) : Page { override val besideList: Boolean get() = false }
    data class CardDetail(val accountId: Long) : Page
    data class AccountDetail(val accountId: Long) : Page
    /** Edit an account or card: name, last 4 digits, statement days, balance and details. */
    data class EditAccount(val accountId: Long) : Page
    /** Change a loan's rate, payment, term or interest type from a date: a form, so it gets its own page. */
    data class ChangeTerms(val accountId: Long) : Page
    data class PersonDetail(val personId: Long) : Page
    data class CategoryDetail(val categoryId: Long, val monthsBack: Int = 0) : Page
    /** The full view behind an Insights tile or "All" link. */
    data class Insight(val page: app.kassa.money.ui.insights.InsightPage) : Page
    /** One day's entries, from any day in the app: the week strip, Activity's day headers, the spending page. */
    data class Day(val epochDay: Long) : Page
    /** Every entry at one store (the entry's note), from Top stores or Repeating. */
    data class Store(val name: String) : Page
    /** Where the spending went, or the income came from: this week, or this budget month. */
    data class Spending(val month: Boolean, val income: Boolean = false) : Page
    /** Card payments and bills in the next 30 days. */
    data object Upcoming : Page
    /** Bank alerts, bills and investment transfers waiting to be confirmed. */
    data object Notifications : Page
    /** The account tiles pinned on Overview. */
    data object PinAccounts : Page
    data object Import : Page { override val besideList: Boolean get() = false }
    /** Add or edit a recurring bill; built like the entry page, so it takes the full screen too. */
    data class Bill(val bill: app.kassa.money.domain.Recurring?) : Page { override val besideList: Boolean get() = false }
}

/**
 * The current tab and the pages stacked on it. Kept in a view model so folding or unfolding the phone,
 * which recreates the activity, keeps you where you were.
 */
class NavViewModel : ViewModel() {
    var tab by mutableStateOf(Tab.TODAY)
        private set
    private var started = false

    /** Picks the opening tab once per launch; folding the phone or returning to the app keeps the current one. */
    fun start(t: Tab) { if (!started) { started = true; if (stack.isEmpty()) tab = t } }
    /**
     * The Accounts groups that are folded: an AccountSection name, "people" or "closed". Closed starts folded. Kept here
     * with the other navigation state, so rotating or folding the phone, or visiting another tab, leaves them as they were.
     */
    var foldedGroups by mutableStateOf(setOf("closed"))
        private set

    fun setFolded(keys: Set<String>) { foldedGroups = keys }

    // The sheets that float over everything (pay a card or loan, record a correction and so on). Kept here, not in
    // AppController, so folding or rotating the phone doesn't close the one that's open; AppController reads and writes them.
    /** The card whose pay sheet is open. */
    var paying by mutableStateOf<Long?>(null)
    /** The loan whose payment sheet is open to record a new payment. */
    var payingLoan by mutableStateOf<Long?>(null)
    /** The loan account that terms are being added to. */
    var addingTermsTo by mutableStateOf<Long?>(null)
    /** The loan whose details are being edited. */
    var editingLoanDetails by mutableStateOf<Long?>(null)
    /** The balance correction sheet that is open. */
    var correcting by mutableStateOf<Correcting?>(null)
    /** The recorded loan payment whose sheet is open. */
    var editingLoanPayment by mutableStateOf<app.kassa.money.data.repo.LoanPaymentDetail?>(null)
    /** The investment transfer being explained. */
    var allocating by mutableStateOf<app.kassa.money.domain.InvestmentTransfer?>(null)
    /** The buy or sale whose sheet is open. */
    var editingTrade by mutableStateOf<app.kassa.money.domain.Transaction?>(null)
    /** The payment to or from a person whose sheet is open. */
    var editingPayment by mutableStateOf<app.kassa.money.domain.Transaction?>(null)

    /**
     * A Settings page asked for from outside Settings (the "Backup needs attention" notification asks for the backup page).
     * Settings opens it and clears this. Kept here so it survives the tab being recreated before it is read.
     */
    var settingsRequest by mutableStateOf<String?>(null)

    val stack = mutableStateListOf<Page>()
    val top: Page? get() = stack.lastOrNull()

    fun select(t: Tab) { tab = t; stack.clear() }

    fun push(p: Page) { stack.add(p) }

    /** Opens [p] from a list. When it shows beside the list, it replaces whatever was there instead of stacking. */
    fun open(p: Page, besideList: Boolean) {
        if (besideList && p.besideList) stack.clear()
        stack.add(p)
    }

    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
}
