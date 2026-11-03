package app.kassa.money.domain

/**
 * The order the user gave their accounts: the order of the groups (an [AccountSection] name each) and, per group, the
 * order of its accounts (ids). Plain data with no Android: the Accounts tab and the Overview tiles both read it, and
 * Settings stores it as one short string ([encode] and [decode]).
 *
 * Anything it doesn't know yet (a new account, a new kind of group) goes to the end, A to Z; ids that no longer exist
 * or are closed are simply never asked for, so they're ignored.
 */
data class AccountArrangement(
    val groups: List<String> = emptyList(),
    val accounts: Map<String, List<Long>> = emptyMap(),
) {
    /** Puts [items] (each a group, named by [key] and [title]) in the arranged order, unknown ones last A to Z. */
    fun <T> orderGroups(items: List<T>, key: (T) -> String, title: (T) -> String): List<T> =
        orderBy(items, groups, key, title)

    /** Puts one group's [items] in the arranged order, unknown ones last A to Z. */
    fun <T> orderRows(groupKey: String, items: List<T>, id: (T) -> Long, name: (T) -> String): List<T> =
        orderBy(items, accounts[groupKey].orEmpty(), id, name)

    /** Every item in the order the Accounts tab shows it: group by group, row by row. */
    fun <T> flatten(items: List<T>, groupKey: (T) -> String, groupTitle: (T) -> String, id: (T) -> Long, name: (T) -> String): List<T> =
        orderGroups(items.map(groupKey).distinct(), { it }, { k -> groupTitle(items.first { groupKey(it) == k }) })
            .flatMap { g -> orderRows(g, items.filter { groupKey(it) == g }, id, name) }

    /**
     * Moves group [key] one place ([delta] -1 up, +1 down) among [within], the groups of one column in their shown
     * order. [all] is every group's shown order; groups outside [within] keep their places.
     */
    fun moveGroup(all: List<String>, within: List<String>, key: String, delta: Int): AccountArrangement {
        val moved = swap(within, key, delta) ?: return withGroups(all)
        val slots = all.indices.filter { all[it] in within }
        val next = all.toMutableList()
        slots.forEachIndexed { n, slot -> next[slot] = moved[n] }
        return withGroups(next)
    }

    /** Moves account [id] one place within its group, whose shown order is [shown]. Never leaves the group. */
    fun moveAccount(all: List<String>, groupKey: String, shown: List<Long>, id: Long, delta: Int): AccountArrangement {
        val moved = swap(shown, id, delta) ?: shown
        return copy(groups = all, accounts = accounts + (groupKey to moved))
    }

    /** Keeps what's shown now as the arrangement, so a later new group doesn't shift what you see. */
    fun withShown(all: List<String>, rows: Map<String, List<Long>>) = AccountArrangement(all, rows)

    private fun withGroups(all: List<String>) = copy(groups = all)

    /** A compact string: groups, then `|`, then `GROUP=id,id` pairs split by `;`. */
    fun encode(): String = groups.joinToString(",") + "|" + accounts.entries.joinToString(";") { (g, ids) -> g + "=" + ids.joinToString(",") }

    companion object {
        val EMPTY = AccountArrangement()

        /** Reads [encode]'s string; null, blank or damaged text gives null, and bad pieces are skipped. */
        fun decode(text: String?): AccountArrangement? {
            if (text.isNullOrBlank() || '|' !in text) return null
            val (g, a) = text.split('|', limit = 2)
            val groups = g.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val accounts = a.split(';').mapNotNull { pair ->
                val (k, v) = pair.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                val ids = v.split(',').mapNotNull { it.trim().toLongOrNull() }.distinct()
                k.trim().takeIf { it.isNotEmpty() }?.let { it to ids }
            }.toMap()
            return AccountArrangement(groups, accounts).takeIf { it != EMPTY }
        }

        private fun <K> swap(list: List<K>, k: K, delta: Int): List<K>? {
            val i = list.indexOf(k)
            val j = i + delta
            if (i < 0 || j < 0 || j >= list.size) return null
            return list.toMutableList().also { it[i] = list[j]; it[j] = k }
        }

        private fun <T, K> orderBy(items: List<T>, known: List<K>, key: (T) -> K, name: (T) -> String): List<T> {
            val rank = known.withIndex().associate { (i, k) -> k to i }
            val (placed, fresh) = items.partition { key(it) in rank }
            return placed.sortedBy { rank.getValue(key(it)) } + fresh.sortedWith(Names.order(name))
        }
    }
}
