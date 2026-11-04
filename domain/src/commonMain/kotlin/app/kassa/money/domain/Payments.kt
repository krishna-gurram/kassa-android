package app.kassa.money.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.math.abs

/*
 * The rules of paying a card and of money between you and a person, the same on every platform: what the pay-card sheet offers
 * and refuses, what the card page says about the due date and which entries a statement holds, and what the person payment sheet
 * offers and refuses. Plain Kotlin with JVM tests; the platform's repository only writes (one transaction) after asking these.
 * The sentences are Android's (`cards_pick_paid_from`, "Pick an account.").
 */

/** What the pay-card sheet offers and refuses. A card payment is stored as a TRANSFER from the pay-from account to the card. */
object CardPayment {

    /** The note a card payment is stored with, as on Android. */
    const val NOTE = "Card payment"

    const val PICK_PAID_FROM = "Pick the account it's paid from."

    enum class Choice { PAYABLE, TOTAL, OTHER }

    /** One amount the sheet offers. [amount] is 0 for [Choice.OTHER] (the person types it). */
    data class Option(val choice: Choice, val amount: Long)

    /** Balance payable while something is due, the total balance while the card owes anything, and always another amount. */
    fun options(s: CardSnapshot): List<Option> = buildList {
        if (s.payable > 0) add(Option(Choice.PAYABLE, s.payable))
        if (s.totalBalance > 0) add(Option(Choice.TOTAL, s.totalBalance))
        add(Option(Choice.OTHER, 0))
    }

    /** What the sheet opens on: balance payable, else the total balance, else another amount. */
    fun defaultChoice(s: CardSnapshot): Choice = when {
        s.payable > 0 -> Choice.PAYABLE
        s.totalBalance > 0 -> Choice.TOTAL
        else -> Choice.OTHER
    }

    /** The card's own "pay from" account when it is still open; nothing else is ever guessed. */
    fun defaultPayFrom(card: Account, accounts: List<Account>): Long? =
        card.payFromId?.takeIf { id -> accounts.any { it.id == id && it.isActive } }

    /** The first problem of a payment of [amount] from [payFromId] (null for none): the input it is about and the sentence. */
    fun problem(amount: Long, payFromId: Long?): Pair<EntryField, String>? {
        InputRules.amountProblem(amount)?.let { return EntryField.AMOUNT to it }
        if (payFromId == null) return EntryField.ACCOUNT to PICK_PAID_FROM
        return null
    }
}

/** What the card page says: the due line, the colours, and which entries each statement holds. */
object CardPageRules {

    enum class Status { DUE_ON, PAID_EXTRA, PAID_IN_FULL, NOTHING_DUE, DAYS_LATE, DUE_TODAY, DUE_TOMORROW, DUE_IN_DAYS }

    enum class DueTone { NEUTRAL, ATTENTION, POSITIVE }

    /** [days] is the count a status like DAYS_LATE or DUE_IN_DAYS names (0 otherwise). */
    data class StatusLine(val status: Status, val days: Long)

    /** Attention when due within three days or late, positive once paid in full, neutral otherwise. */
    fun dueTone(s: CardSnapshot, today: LocalDate): DueTone {
        val days = s.daysUntilDue(today)
        return when {
            s.statementTotal > 0 && s.payable == 0L -> DueTone.POSITIVE
            s.payable > 0 && days <= 3 -> DueTone.ATTENTION
            else -> DueTone.NEUTRAL
        }
    }

    /** The line under Balance payable. While [hidden] it says only when it is due: "Paid in full", "extra" or "days late" would give the size away. */
    fun status(s: CardSnapshot, today: LocalDate, hidden: Boolean): StatusLine {
        val days = s.daysUntilDue(today)
        return when {
            hidden -> StatusLine(Status.DUE_ON, 0)
            s.balancePayable < 0 -> StatusLine(Status.PAID_EXTRA, 0)
            dueTone(s, today) == DueTone.POSITIVE -> StatusLine(Status.PAID_IN_FULL, 0)
            s.payable == 0L -> StatusLine(Status.NOTHING_DUE, 0)
            days < 0 -> StatusLine(Status.DAYS_LATE, -days)
            days == 0L -> StatusLine(Status.DUE_TODAY, 0)
            days == 1L -> StatusLine(Status.DUE_TOMORROW, 0)
            else -> StatusLine(Status.DUE_IN_DAYS, days)
        }
    }

    /**
     * One stretch of the statement navigator. [back] 0 is what is charged since the last statement (from the statement date up to
     * today); 1 is the last statement, and so on back. A statement runs from its opening statement date up to the day before its own:
     * the statement date starts the next one, so [to] is excluded unless [current].
     */
    data class Window(val back: Int, val from: LocalDate, val to: LocalDate) {
        val current: Boolean get() = back == 0

        fun holds(date: LocalDate): Boolean =
            !date.isBefore(from) && (if (current) !date.isAfter(to) else date.isBefore(to))
    }

    fun window(s: CardSnapshot, statementDay: Int, back: Int, today: LocalDate): Window {
        if (back <= 0) return Window(0, s.lastClose, today)
        var close = s.lastClose
        repeat(back - 1) { close = CardCycle.previousClose(statementDay, close) }
        return Window(back, CardCycle.previousClose(statementDay, close), close)
    }

    /** Whether an older statement exists: the card has an entry from before this window began. */
    fun canGoBack(oldest: LocalDate?, w: Window): Boolean = oldest != null && w.from.isAfter(oldest)

    /** The entries a window holds, newest first: the card's own entries dated inside it. */
    fun entriesIn(cardId: Long, transactions: List<Transaction>, w: Window, zone: TimeZone): List<Transaction> =
        transactions
            .filter { (it.accountId == cardId || it.toAccountId == cardId) && w.holds(dateOfMillis(it.epochMillis, zone)) }
            .sortedByDescending { it.epochMillis }

    /** What was charged on the card in [list]: its expenses. */
    fun charges(cardId: Long, list: List<Transaction>): Long =
        list.filter { it.kind == TxKind.EXPENSE && it.accountId == cardId }.sumOf { it.amount }

    /** What was paid or refunded onto the card in [list]: transfers in and income. */
    fun credits(cardId: Long, list: List<Transaction>): Long =
        list.filter { it.toAccountId == cardId || (it.kind == TxKind.INCOME && it.accountId == cardId) }.sumOf { it.amount }
}

/** What the person payment sheet offers and refuses. A payment is a PERSON_IN or PERSON_OUT entry on one of your accounts. */
object PersonPayment {

    /** The accounts money to or from a person can go through. */
    val ACCOUNT_TYPES: Set<AccountType> = setOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH, AccountType.PREPAID, AccountType.CREDIT_CARD)

    const val PICK_ACCOUNT = "Pick an account."

    /** One payment as the sheet builds it. [id] is null for a new one. */
    data class Draft(
        val id: Long? = null,
        val personId: Long,
        val kind: TxKind,
        val amount: Long,
        val accountId: Long? = null,
        val epochMillis: Long,
        val note: String = "",
    )

    fun isPaymentKind(kind: TxKind): Boolean = kind in PERSON_KINDS

    /** A new payment starts on settling the balance: you pay what you owe, they pay what they owe. */
    fun defaultKind(balance: Long): TxKind = if (balance < 0) TxKind.PERSON_OUT else TxKind.PERSON_IN

    /** The amount a new payment starts with: the whole balance (0 when settled, so the field starts empty). */
    fun suggestedAmount(balance: Long): Long = abs(balance)

    fun defaultNote(kind: TxKind, personName: String): String =
        if (kind == TxKind.PERSON_IN) "$personName paid you" else "You paid $personName"

    /** A note the user wrote stays while the direction is unchanged; otherwise the generated one follows the direction. */
    fun noteFor(kind: TxKind, personName: String, editingKind: TxKind?, editingNote: String?): String =
        editingNote?.takeIf { kind == editingKind && it.isNotBlank() } ?: defaultNote(kind, personName)

    /** The first problem in reading order: amount, note, account. The sentences are Android's. */
    fun problem(d: Draft): Pair<EntryField, String>? {
        InputRules.amountProblem(d.amount)?.let { return EntryField.AMOUNT to it }
        InputRules.noteProblem(d.note)?.let { return EntryField.NOTE to it }
        if (!isPaymentKind(d.kind)) return EntryField.AMOUNT to "That kind of entry can't be saved here."
        if (d.accountId == null) return EntryField.ACCOUNT to PICK_ACCOUNT
        return null
    }

    /** The accounts the sheet offers: open ones of [ACCOUNT_TYPES], and [keep] (an edited payment's account) even when closed since. */
    fun sources(accounts: List<Account>, keep: Long? = null): List<Account> =
        accounts.filter { (it.isActive && it.type in ACCOUNT_TYPES) || (keep != null && it.id == keep) }

    /** The accounts a save newly touches, which must be open. [beforeAccountId] is the stored payment's account, null for a new one. */
    fun newlyTouched(accountId: Long?, beforeAccountId: Long?): List<Long> =
        listOfNotNull(accountId).filter { it != beforeAccountId }
}

/** What a person's page lists: splits, starting balance and payments, newest first, and since when a balance has been open. */
object PersonHistory {

    /**
     * One line of the history. [amount] is positive when it adds to what they owe you. [note] is as stored (blank when none, the
     * platform words it). [transactionId] is the entry behind it (a payment, or the expense a split came from), null for a starting
     * balance. [payment] lines only settle up.
     */
    data class Line(
        val epochMillis: Long,
        val note: String,
        val amount: Long,
        val transactionId: Long?,
        val payment: Boolean,
        val kind: TxKind?,
    )

    fun lines(personId: Long, loans: List<LoanEntry>, transactions: List<Transaction>): List<Line> {
        val splits = loans.filter { it.personId == personId }
            .map { Line(it.epochMillis, it.note, it.amount, it.transactionId, false, null) }
        val payments = transactions.filter { it.personId == personId && it.kind in PERSON_KINDS }.map { t ->
            Line(t.epochMillis, t.note, if (t.kind == TxKind.PERSON_OUT) t.amount else -t.amount, t.id, true, t.kind)
        }
        return (splits + payments).sortedByDescending { it.epochMillis }
    }

    /** When the current balance started: the last time it moved away from zero. Null when settled. */
    fun openSince(lines: List<Line>): Long? {
        var running = 0L
        var start: Long? = null
        lines.sortedBy { it.epochMillis }.forEach { line ->
            if (running == 0L && line.amount != 0L) start = line.epochMillis
            running += line.amount
            if (running == 0L) start = null
        }
        return start
    }
}
