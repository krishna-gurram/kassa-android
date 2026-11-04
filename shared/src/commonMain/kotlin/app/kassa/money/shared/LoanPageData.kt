package app.kassa.money.shared

/** One row of a loan's payment schedule: the date and what the payment holds, as `Money.format` writes them. */
data class LoanRowData(val day: Int, val principalText: String, val interestText: String, val balanceText: String)

/** One dated change of a loan's terms. [summary] is the domain's own line ("Rate 7.25%, Payment 600.00"). */
data class LoanChangeData(val id: Long, val day: Int, val summary: String)

/**
 * A loan account's page (`LoanSummary`): what is outstanding (never with a minus), the progress when the original amount is known, the
 * payment and the payoff, interest paid and left, the schedule, the terms with their dated changes, and the month's entries by day. A
 * loan without terms has [hasTerms] false and nothing else filled; its page is the plain account page with "Add terms". Empty text
 * means "none"; 0 for a day means none. While Hide balances is on there is no bar and no percent (they would give the balance away).
 */
data class LoanPageData(
    val found: Boolean,
    val hasTerms: Boolean,
    val id: Long,
    val name: String,
    val last4: String,
    /** Epoch day it was closed, 0 while open. */
    val closedDay: Int,
    val closeProblem: String,
    /** Islamic finance: screens say "Profit" where they would say "Interest". */
    val profit: Boolean,
    val balanceText: String,
    val balanceTone: Tone,
    /** Orange while something is owed, green otherwise, Ink while hidden (a hero tint). */
    val heroTone: Tone,
    val hasProgress: Boolean,
    /** Share repaid, 0 to 100, and the same as 0 to 1000 for the bar. */
    val percent: Int,
    val permille: Int,
    val originalText: String,
    /** Something is still owed, so a payment is due. */
    val paying: Boolean,
    val paymentText: String,
    val nextPaymentDay: Int,
    /** Epoch day the loan is paid off by the schedule, 0 when it isn't within a hundred years. */
    val payoffDay: Int,
    val interestPaidText: String,
    val interestLeftText: String,
    /** The payment can't pay the loan off (shown only while something is owed and amounts are shown). */
    val doesntCover: Boolean,
    /** An `InterestType` name, the rate, and the payment in effect today. */
    val interestType: String,
    val rateText: String,
    val termPaymentText: String,
    /** With escrow the payment line reads "Principal and interest" and an escrow line follows. */
    val hasEscrow: Boolean,
    val escrowText: String,
    val termMonths: Int,
    val dueDay: Int,
    val changes: List<LoanChangeData>,
    val scheduleCount: Int,
    val rows: List<LoanRowData>,
    val year: Int,
    val month: Int,
    val isCurrentMonth: Boolean,
    val hasEarlier: Boolean,
    val entryCount: Int,
    val days: List<DayData>,
)

/** What the balance correction sheet opens on. [shownWithout] is what the account shows without the correction being edited (a debt as what is owed). */
data class CorrectionSheetData(
    val found: Boolean,
    val accountId: Long,
    val accountName: String,
    val debt: Boolean,
    val isLoan: Boolean,
    /** The entry being edited, 0 for a new correction. */
    val editingId: Long,
    val shownWithout: Long,
    val balanceInKassaText: String,
    /** The figure the account shows now, to start an edit on; 0 for a new correction. */
    val initialShown: Long,
    /** The edited correction's moment, 0 for a new one. */
    val epochMillis: Long,
)
