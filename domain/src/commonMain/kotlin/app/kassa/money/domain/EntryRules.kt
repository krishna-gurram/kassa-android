package app.kassa.money.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** The inputs of the add and edit entry page, in the order the page asks for them. */
enum class EntryField { AMOUNT, CATEGORY, ACCOUNT, TO, DATE, NOTE }

/** The first thing wrong with an entry: which input to open, and the sentence to show under it. */
data class EntryProblem(val field: EntryField, val message: String)

/**
 * The rules for adding, editing and deleting an expense, income or transfer, the same on every platform. The platform's
 * repository does the writing (one transaction) and asks these first, so a refusal leaves nothing behind and the sentences are
 * the same everywhere. Plain Kotlin with JVM tests.
 *
 * Splits are covered by [SplitRules]. What it does not cover: payments to people, loan payments, trades, card-payment shortcuts and balance corrections.
 * Those entries are not [isEditable] here, so they are never rewritten by a page that doesn't know them.
 */
object EntryRules {

    /** An entry as the page builds it. [id] is null for a new one. */
    data class Draft(
        val id: Long? = null,
        val kind: TxKind,
        val amount: Long,
        val accountId: Long? = null,
        val toAccountId: Long? = null,
        val categoryId: Long? = null,
        val epochMillis: Long,
        val note: String = "",
        /** Who paid when it wasn't you (a split expense): the entry then moves none of your accounts. Null when you paid. */
        val paidBy: Long? = null,
    )

    /** The kinds the entry page adds and edits. */
    val PAGE_KINDS: Set<TxKind> = setOf(TxKind.EXPENSE, TxKind.INCOME, TxKind.TRANSFER)

    fun isPageKind(kind: TxKind): Boolean = kind in PAGE_KINDS

    /** The next required input still empty, in the order the tiles read; null when the entry has all it needs. */
    fun missing(kind: TxKind, categoryId: Long?, accountId: Long?, toAccountId: Long?, someoneElsePaid: Boolean = false): EntryField? = when (kind) {
        TxKind.TRANSFER -> when {
            accountId == null -> EntryField.ACCOUNT
            toAccountId == null || toAccountId == accountId -> EntryField.TO
            else -> null
        }
        else -> when {
            categoryId == null -> EntryField.CATEGORY
            accountId == null && !someoneElsePaid -> EntryField.ACCOUNT
            else -> null
        }
    }

    /**
     * The first problem in reading order, or null when the entry can be saved: amount, note, then what is missing. The sentences
     * are Android's ("Pick a category."). Amounts are written in the current [Money.style].
     */
    fun problem(d: Draft): EntryProblem? {
        InputRules.amountProblem(d.amount)?.let { return EntryProblem(EntryField.AMOUNT, it) }
        InputRules.noteProblem(d.note)?.let { return EntryProblem(EntryField.NOTE, it) }
        return when (missing(d.kind, d.categoryId, d.accountId, d.toAccountId, someoneElsePaid(d))) {
            EntryField.CATEGORY -> EntryProblem(EntryField.CATEGORY, "Pick a category.")
            EntryField.ACCOUNT -> EntryProblem(EntryField.ACCOUNT, "Pick an account.")
            EntryField.TO -> EntryProblem(EntryField.TO, "Pick where the money went.")
            else -> if (d.kind in PAGE_KINDS) null else EntryProblem(EntryField.AMOUNT, "That kind of entry can't be saved here.")
        }
    }

    private fun someoneElsePaid(d: Draft): Boolean = d.kind == TxKind.EXPENSE && d.paidBy != null

    /** The draft as it is stored: only a transfer has a destination, only an expense or income a category, the note cleaned. */
    fun normalized(d: Draft): Draft = d.copy(
        paidBy = if (d.kind == TxKind.EXPENSE) d.paidBy else null,
        accountId = if (d.kind == TxKind.EXPENSE && d.paidBy != null) null else d.accountId,
        toAccountId = if (d.kind == TxKind.TRANSFER) d.toAccountId else null,
        categoryId = if (d.kind == TxKind.TRANSFER) null else d.categoryId,
        note = InputRules.clean(d.note),
    )

    /**
     * The accounts a save newly touches, which must be open: an old entry on an account closed since can still be edited, as long
     * as the edit doesn't move it onto another closed one. [before] is the stored entry being edited, null for a new one.
     */
    fun newlyTouched(d: Draft, before: Draft?): List<Long> {
        val now = listOfNotNull(d.accountId, d.toAccountId).distinct()
        val old = listOfNotNull(before?.accountId, before?.toAccountId)
        return now.filter { it !in old }
    }

    /** What the person is told when they try to add to a closed account. */
    fun closedMessage(name: String): String = "$name is closed. Reopen it first to add to it."

    /** True for a new transfer into an investment account: it waits on Overview until the person says what it bought. */
    fun waitsForExplanation(kind: TxKind, toAccountType: AccountType?): Boolean =
        kind == TxKind.TRANSFER && toAccountType == AccountType.INVESTMENT

    /**
     * Whether Activity may open this entry on the entry page. A transfer into a card is a card payment and, as on Android, an ordinary
     * transfer there (the pay-card sheet adds one; the entry page edits or deletes it). An entry of a loan payment or a
     * person's payment has its own page; a split expense (and one someone else paid, which has a person and no account) is edited here, as on Android, and buys, sales and balance corrections are not expenses, income or transfers; those are
     * never rewritten by a page that doesn't know them. [toAccountType] is kept so callers need not change.
     */
    @Suppress("UNUSED_PARAMETER")
    fun isEditable(kind: TxKind, toAccountType: AccountType?, belongsToLoanPayment: Boolean, hasPerson: Boolean, isSplit: Boolean): Boolean =
        kind in PAGE_KINDS && !belongsToLoanPayment && (!hasPerson || kind == TxKind.EXPENSE)

    /**
     * The accounts the picker leaves out for [field] (ACCOUNT or TO). The destination of a transfer is never the source. A transfer
     * can't come from a card, so cards are left out of its source list, except the one already picked.
     */
    fun excludedAccounts(kind: TxKind, field: EntryField, accountId: Long?, accounts: List<Account>): Set<Long> = when {
        field == EntryField.TO -> setOfNotNull(accountId)
        kind == TxKind.TRANSFER -> accounts.filter { it.type.hasStatement && it.id != accountId }.map { it.id }.toSet()
        else -> emptySet()
    }

    /** What changes when the kind switches: the category goes (the lists differ), a card can't be the source of a transfer, and a destination only belongs to transfers. */
    fun switched(d: Draft, to: TxKind, accountIsCard: Boolean): Draft {
        if (to == d.kind) return d
        var next = d.copy(kind = to, categoryId = null)
        if (to == TxKind.TRANSFER && accountIsCard) next = next.copy(accountId = null)
        if (to != TxKind.TRANSFER) next = next.copy(toAccountId = null)
        return next
    }

    /**
     * The moment to store for an entry on [day]: when editing, the original time of day is kept; a new entry for today uses the
     * time [nowMillis], and one for another day uses noon.
     */
    fun savedMillis(day: LocalDate, zone: TimeZone, nowMillis: Long, originalMillis: Long?): Long {
        val time = Instant.fromEpochMilliseconds(originalMillis ?: nowMillis).toLocalDateTime(zone)
        return when {
            originalMillis != null -> millisAt(day, time.hour, time.minute, zone) + (originalMillis.mod(60_000L))
            day == dateOfMillis(nowMillis, zone) -> millisAt(day, time.hour, time.minute, zone) + (nowMillis.mod(60_000L))
            else -> millisAt(day, 12, 0, zone)
        }
    }
}
