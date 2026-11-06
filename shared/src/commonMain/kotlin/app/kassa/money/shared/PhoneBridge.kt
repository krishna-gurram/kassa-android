package app.kassa.money.shared

import app.kassa.money.domain.AccountArrangement
import app.kassa.money.domain.AccountSort
import app.kassa.money.domain.AccountToPerson
import app.kassa.money.domain.BudgetMonthStart
import app.kassa.money.domain.HomeTab
import app.kassa.money.domain.PinRules
import app.kassa.money.domain.ReorderMath
import app.kassa.money.domain.ThemeChoice

/**
 * Accounts > Order and Arrange for the iOS screens. The arrangement travels as `AccountArrangement.encode()` text (the shown order,
 * `AccountsData.order`), so Swift holds no rule of moving: a drag or a VoiceOver action gives the position and gets the new text back.
 * Positions are of the groups (or of one group's accounts) as the screen lists them.
 */
object AccountOrderBridge {

    private fun read(shown: String): AccountArrangement = AccountArrangement.decode(shown) ?: AccountArrangement.EMPTY

    /** The groups of a shown order (`AccountsData.order`), in their order. */
    fun groupKeys(shown: String): List<String> = read(shown).groups

    /** The account ids of group [groupKey] in a shown order. */
    fun accountIds(shown: String, groupKey: String): List<Long> = read(shown).accounts[groupKey].orEmpty()

    /** The sort names in the order the sheet lists them. */
    fun sortNames(): List<String> = AccountSort.entries.map { it.name }

    /** The sort a stored name means (unknown ones are NAME). */
    fun sortOf(stored: String): String = AccountSort.of(stored).name

    /** Group [key] one place up ([delta] -1) or down (+1); at an end nothing changes. */
    fun moveGroup(shown: String, key: String, delta: Int): String {
        val a = read(shown)
        return a.moveGroup(a.groups, a.groups, key, delta).encode()
    }

    /** Account [id] one place up or down inside group [groupKey]; never leaves the group. */
    fun moveAccount(shown: String, groupKey: String, id: Long, delta: Int): String {
        val a = read(shown)
        return a.moveAccount(a.groups, groupKey, a.accounts[groupKey].orEmpty(), id, delta).encode()
    }

    /** A drop of the group at [from] before position [destination] (SwiftUI's `onMove`: counted before the group is lifted). */
    fun dropGroup(shown: String, from: Int, destination: Int): String {
        val a = read(shown)
        val moved = ReorderMath.move(a.groups, from, ReorderMath.landing(from, destination))
        return AccountArrangement().withShown(moved, a.accounts).encode()
    }

    /** A drop of an account of group [groupKey] from [from] before [destination], inside that group. */
    fun dropAccount(shown: String, groupKey: String, from: Int, destination: Int): String {
        val a = read(shown)
        val rows = a.accounts[groupKey].orEmpty()
        val moved = ReorderMath.move(rows, from, ReorderMath.landing(from, destination))
        return AccountArrangement().withShown(a.groups, a.accounts + (groupKey to moved)).encode()
    }
}

/** Settings choices that are only names and numbers: theme, the tab Kassa opens on, the budget month's first day. */
object PhoneChoicesBridge {

    /** The theme a stored name means: SYSTEM, LIGHT or DARK (unknown is SYSTEM). */
    fun theme(stored: String): String = ThemeChoice.of(stored).name

    fun themes(): List<String> = ThemeChoice.entries.map { it.name }

    /** The tab a stored name means: TODAY (Overview), ACTIVITY, INSIGHTS or ACCOUNTS (unknown is TODAY). */
    fun homeTab(stored: String): String = HomeTab.of(stored).name

    fun homeTabs(): List<String> = HomeTab.entries.map { it.name }

    /** [day] kept to 1 to 28. */
    fun clampMonthStart(day: Int): Int = BudgetMonthStart.clamp(day)

    /** Whether [typed] names a day from 1 to 28. */
    fun monthStartValid(typed: String): Boolean = BudgetMonthStart.isValid(typed)

    /** How many Overview tiles can be pinned. */
    fun pinLimit(): Int = PinRules.MAX_TILES

    fun quickMonthDays(): List<Int> = BudgetMonthStart.QUICK_DAYS

    /** The note of the line that carries an account's balance over to the person it becomes. */
    fun convertNote(accountName: String): String = AccountToPerson.note(accountName)

    fun convertName(name: String): String = AccountToPerson.cleanName(name)
}
