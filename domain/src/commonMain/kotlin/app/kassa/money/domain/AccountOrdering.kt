package app.kassa.money.domain

import kotlin.math.abs

/** How the Accounts tab orders every group. [CUSTOM] is the person's own arrangement ([AccountArrangement]). */
enum class AccountSort {
    NAME, BALANCE, DUE, CUSTOM;

    companion object {
        /** The sort a stored name means; anything unknown is [NAME]. */
        fun of(stored: String?): AccountSort = entries.firstOrNull { it.name == stored } ?: NAME
    }
}

/**
 * The rules of Accounts > Order, plain Kotlin so both apps share them. A sort other than [AccountSort.CUSTOM] drops the
 * arrangement; Your order needs one. A phone that has an arrangement but no stored sort (a backup carried it) shows it.
 */
object AccountOrdering {

    /** The sort in force: [stored] unless it can't hold. Your order without an arrangement is A to Z. */
    fun effective(stored: String?, arrangement: AccountArrangement?): AccountSort {
        if (stored.isNullOrBlank()) return if (arrangement != null) AccountSort.CUSTOM else AccountSort.NAME
        val sort = AccountSort.of(stored)
        return if (sort == AccountSort.CUSTOM && arrangement == null) AccountSort.NAME else sort
    }

    /** The arrangement that applies to [sort]: only Your order keeps one. */
    fun arrangementFor(sort: AccountSort, arrangement: AccountArrangement?): AccountArrangement? =
        arrangement.takeIf { sort == AccountSort.CUSTOM }

    /**
     * The order of the lines inside a group. Balance is the largest figure first; Due date puts what has a payment or
     * maturity coming first, soonest first ([dueDay] is an epoch day or null), and everything else A to Z.
     */
    fun comparator(sort: AccountSort, dueDay: (Account) -> Int?): Comparator<AccountLine> {
        val byName = Names.order<AccountLine> { it.account.name }
        return when (sort) {
            AccountSort.NAME, AccountSort.CUSTOM -> byName
            AccountSort.BALANCE -> compareByDescending<AccountLine> { abs(it.shown) }.then(byName)
            AccountSort.DUE -> compareBy<AccountLine, Int?>(nullsLast<Int>()) { dueDay(it.account) }.then(byName)
        }
    }

    /** What the tab shows now, as an arrangement: the groups in their shown order and each group's account ids. Arranging starts here. */
    fun shown(overview: AccountsOverview): AccountArrangement {
        val keys = overview.groups.map { it.section.name }
        val rows = overview.groups.associate { g -> g.section.name to g.lines.map { it.account.id } }
        return AccountArrangement().withShown(keys, rows)
    }
}

/**
 * Which accounts Overview pins as tiles. Keys are `acct:<id>` and [PEOPLE_KEY] (everyone you owe or who owes you, one tile). Until the
 * person picks (saved is null), Overview shows the first [MAX_TILES] choices; the first change saves the whole set.
 */
object PinRules {
    const val MAX_TILES = 8
    const val PEOPLE_KEY = "people"

    fun key(accountId: Long): String = "acct:$accountId"

    /** The stored text: null (never picked) stays null; blank text is an empty set (nothing pinned). */
    fun decode(text: String?): Set<String>? {
        if (text == null) return null
        return text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun encode(keys: Collection<String>): String = keys.joinToString(",")

    /** What Overview shows from the [choices] (every pinnable key in Accounts order). */
    fun shown(choices: List<String>, saved: Set<String>?): List<String> =
        if (saved == null) choices.take(MAX_TILES) else choices.filter { it in saved }

    /**
     * The set after the person ticks or unticks [key]. A tick past [MAX_TILES] changes nothing; the picks are what was shown, so
     * the first change from "nothing picked yet" saves those.
     */
    fun toggled(choices: List<String>, saved: Set<String>?, key: String): Set<String> {
        val picked = shown(choices, saved).toSet()
        return when {
            key in picked -> picked - key
            picked.size < MAX_TILES && key in choices -> picked + key
            else -> picked
        }
    }
}
