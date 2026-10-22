package dev.krishnagurram.kassa.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dev.krishnagurram.kassa.ui.entry.EntryStart

enum class Tab(val label: String) { TODAY("Today"), ACTIVITY("Activity"), CARDS("Cards"), INSIGHTS("Insights"), ACCOUNTS("Accounts"), SETTINGS("Settings") }

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
    data class PersonDetail(val personId: Long) : Page
    data class CategoryDetail(val categoryId: Long) : Page
    data object Week : Page
    data object Import : Page { override val besideList: Boolean get() = false }
}

/**
 * The current tab and the pages stacked on it. Kept in a view model so folding or unfolding the phone,
 * which recreates the activity, keeps you where you were.
 */
class NavViewModel : ViewModel() {
    var tab by mutableStateOf(Tab.TODAY)
        private set
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
