package app.kassa.money.data.repo

import app.kassa.money.data.db.KassaDatabase

/**
 * A closed account (see `Account.closedDay`) keeps its history but takes nothing new: no entry, transfer, card payment, loan
 * payment, trade or bill approval may land on it. Repositories call this before they write, inside their transaction, so a
 * refusal leaves nothing behind. Editing an entry that already belongs to a closed account is fine as long as the edit
 * doesn't move it onto another closed one: callers pass only the accounts the change newly touches.
 */
internal suspend fun KassaDatabase.requireOpen(vararg ids: Long?) {
    val wanted = ids.filterNotNull().distinct()
    if (wanted.isEmpty()) return
    accounts().closedAmong(wanted).firstOrNull()?.let { throw IllegalArgumentException(closedMessage(it.name)) }
}

/** What the person is told when they try to add to a closed account. */
internal fun closedMessage(name: String) = "$name is closed. Reopen it first to add to it."
