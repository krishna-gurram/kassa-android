package app.kassa.money.data.repo

import androidx.room.withTransaction
import app.kassa.money.data.db.KassaDatabase
import app.kassa.money.data.db.toDomain
import app.kassa.money.data.db.toEntity
import app.kassa.money.domain.Account
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.Category
import app.kassa.money.domain.CountedSpending
import app.kassa.money.domain.CategoryColors
import app.kassa.money.domain.CategoryIcon
import app.kassa.money.domain.CategoryKind
import app.kassa.money.domain.InputRules
import app.kassa.money.domain.LoanCategories
import app.kassa.money.domain.LoanDates
import app.kassa.money.domain.LoanNotes
import app.kassa.money.domain.LoanPart
import app.kassa.money.domain.LoanPayment
import app.kassa.money.domain.tag
import app.kassa.money.domain.LoanTermChange
import app.kassa.money.domain.LoanTerms
import app.kassa.money.domain.Money
import app.kassa.money.domain.Transaction
import app.kassa.money.domain.TxKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Every loan's terms, term changes and recorded payments, as the screens read them. [interestPaid] is the sum of each
 * loan's linked interest entries; [escrowCategory] is the category each loan's latest escrow entry went to, which the next
 * payment's sheet starts from, so a loan keeps the category it was last given.
 */
data class LoansState(
    val terms: Map<Long, LoanTerms>,
    val changes: List<LoanTermChange>,
    val payments: List<LoanPayment>,
    val interestPaid: Map<Long, Long> = emptyMap(),
    val escrowCategory: Map<Long, Long> = emptyMap(),
) {
    fun changesFor(accountId: Long): List<LoanTermChange> = changes.filter { it.accountId == accountId }
    fun paymentsFor(accountId: Long): List<LoanPayment> = payments.filter { it.accountId == accountId }

    private val byEntry: Map<Long, Pair<LoanPayment, LoanPart>> by lazy {
        buildMap {
            payments.forEach { p ->
                listOf(p.principalEntryId to LoanPart.PRINCIPAL, p.interestEntryId to LoanPart.INTEREST, p.otherEntryId to LoanPart.OTHER)
                    .forEach { (id, part) -> if (id != null) put(id, p to part) }
            }
        }
    }

    /** Whether an entry is one of a loan payment's entries, whatever its kind. */
    fun isLoanEntry(entryId: Long): Boolean = entryId in byEntry

    /** Whether an entry is the principal of a payment that counts as spending (see [CountedSpending]). */
    fun countsAsSpending(entryId: Long): Boolean =
        byEntry[entryId]?.let { (p, part) -> part == LoanPart.PRINCIPAL && CountedSpending.counts(p, terms[p.accountId]) } == true

    /** The small tag Activity shows on a loan payment's entry ("principal", "interest", "escrow", "charge"); null for any other entry. */
    fun tagFor(entryId: Long): String? =
        byEntry[entryId]?.let { (p, part) -> p.tag(part, terms[p.accountId]?.interestWord ?: "Interest") }

    companion object {
        val EMPTY = LoansState(emptyMap(), emptyList(), emptyList())
    }
}

/** A loan payment as the payment sheet builds it, before it's saved. The parts have to add up to [amount]. */
data class LoanPaymentDraft(
    /** The id of the payment being edited; null for a new one. */
    val id: Long?,
    val accountId: Long,
    val fromId: Long?,
    /** The payment's date as an epoch day (for the schedule) and as a moment (for the entries). */
    val day: Int,
    val epochMillis: Long,
    val amount: Long,
    val principal: Long,
    val interest: Long,
    val escrow: Long,
    val charge: Long,
    /** The category for the interest part; null means the loan's own (the "Loan interest" category unless it names another). */
    val categoryId: Long?,
    /** The category for the escrow (and any early repayment charge) entry; null means the shared "Escrow" category. */
    val escrowCategoryId: Long? = null,
    val edited: Boolean,
    val estimate: Long,
    /** This payment only: null follows the loan's "count the whole payment as spending", true counts the principal, false doesn't. */
    val countAllAsSpending: Boolean? = null,
    /** Where this payment's principal counts when it counts; null means the loan's principal category. */
    val principalCategoryId: Long? = null,
)

/** A saved payment with what its entries hold, to open the payment sheet on. */
data class LoanPaymentDetail(
    val payment: LoanPayment,
    val principal: Long,
    val interest: Long,
    val fromId: Long?,
    val categoryId: Long?,
    val escrowCategoryId: Long? = null,
)

/**
 * Loans that know their terms. The only code that writes loan terms, term changes and payments.
 *
 * A payment is stored as ordinary entries so every report, balance and Insights view keeps working: the principal part
 * is a transfer from the account it was paid from to the loan (it lowers what is owed), the interest part is an expense
 * in a category, and escrow with any early repayment charge is a second expense (a cost, but not interest). A
 * `loan_payments` row links them, so any of the entries opens the same payment and they change and delete together.
 */
class LoanRepository(
    private val db: KassaDatabase,
    private val accounts: AccountRepository,
    private val categories: CategoryRepository,
) {

    val state: Flow<LoansState> = combine(
        db.loanTerms().observeAll(), db.loanChanges().observeAll(), db.loanPayments().observeAll(), db.loanPayments().observeExtras(),
    ) { terms, changes, payments, extras ->
        LoansState(
            terms.associate { it.accountId to it.toDomain() }, changes.map { it.toDomain() }, payments.map { it.toDomain() },
            interestPaid = extras.groupBy { it.accountId }.mapValues { (_, rows) -> rows.sumOf { it.interest } },
            escrowCategory = extras.filter { it.escrowCategoryId != null }.groupBy { it.accountId }.mapValues { (_, rows) -> rows.last().escrowCategoryId!! },
        )
    }

    /**
     * Adds a new loan account with its terms, and the "Loan interest" category if there isn't one yet. Returns the
     * account's id. Subcategories are never made here; the user makes them.
     */
    suspend fun addLoan(account: Account, terms: LoanTerms, newGroupName: String?): Long = db.withTransaction {
        require(account.type == AccountType.LOAN) { "A loan is a Loan or mortgage account." }
        val id = accounts.add(account, newGroupName)
        saveTerms(terms.copy(accountId = id))
        id
    }

    /**
     * Adds terms to a loan account that is already there, in place: its entries stay, and so does its balance, apart
     * from the change [account] carries to its starting balance when the form's balance differed.
     */
    suspend fun addTerms(account: Account, terms: LoanTerms) = db.withTransaction {
        require(account.type == AccountType.LOAN && account.id != 0L) { "Terms can only be added to a loan or mortgage account." }
        accounts.update(account)
        saveTerms(terms.copy(accountId = account.id))
    }

    private suspend fun saveTerms(terms: LoanTerms) {
        terms.problem()?.let { throw IllegalArgumentException(it) }
        terms.payFromId?.let { id ->
            require(id != terms.accountId && db.accounts().all().any { it.id == id }) { "Pick another account to pay from." }
        }
        terms.interestCategoryId?.let { id -> require(db.categories().all().any { it.id == id }) { "That category isn't there any more." } }
        terms.principalCategoryId?.let { id -> require(db.categories().all().any { it.id == id && it.kind == CategoryKind.EXPENSE.name }) { "Pick an expense category for the principal." } }
        // The default category exists from the first loan on, even when this loan names another.
        interestCategory()
        // The shared Escrow category exists from the first loan that has escrow.
        if (terms.escrow > 0) escrowCategory()
        // A principal category only means something while the whole payment counts as spending.
        db.loanTerms().upsert(terms.copy(principalCategoryId = terms.principalCategoryId.takeIf { terms.countAllAsSpending }).toEntity())
    }

    /**
     * The shared "Escrow" category's id, made when it isn't there yet (and brought back when it was archived). Every
     * loan with escrow starts from it; a single payment can name any category instead, and a loan keeps the last one.
     */
    private suspend fun escrowCategory(): Long {
        val all = db.categories().all().map { it.toDomain() }
        LoanCategories.escrowCategory(all)?.let { return it.id }
        all.firstOrNull { it.parentId == null && it.kind == CategoryKind.EXPENSE && it.name.equals(LoanCategories.ESCROW_NAME, ignoreCase = true) }?.let { archived ->
            db.categories().update(archived.copy(hidden = false).toEntity())
            return archived.id
        }
        return categories.save(
            Category(
                name = LoanCategories.ESCROW_NAME, kind = CategoryKind.EXPENSE, icon = CategoryIcon.Symbol("home"),
                color = CategoryColors.next(all, CategoryKind.EXPENSE),
                // Escrow is a standing cost, like the loan itself, so it doesn't eat into what is safe to spend today.
                countsTowardDaily = false,
            )
        )
    }

    /**
     * Saves the loan's details that have no date (see `LoanDetailsForm`): the name goes to the account, the rest to the
     * terms. A new due day was already applied to the next payment date by the form. Payments recorded so far keep
     * what they hold, so a new escrow amount or charge only reaches the next ones.
     */
    suspend fun updateDetails(account: Account, terms: LoanTerms) = db.withTransaction {
        require(account.type == AccountType.LOAN && account.id != 0L) { "Details belong to a loan or mortgage account." }
        require(db.loanTerms().forAccount(account.id) != null) { "Add this loan's terms first." }
        accounts.update(account)
        saveTerms(terms.copy(accountId = account.id))
    }

    /**
     * Saves a change of terms from a date on. Throws [IllegalArgumentException] with a message to show when it changes
     * nothing, starts before the loan did, or breaks an input rule. Payments before its date keep their split, because
     * the engine reads each date's terms from the changes; nothing already recorded is touched.
     */
    suspend fun addChange(change: LoanTermChange): Long = db.withTransaction {
        val terms = db.loanTerms().forAccount(change.accountId)?.toDomain() ?: throw IllegalArgumentException("Add this loan's terms first.")
        change.problem(terms, db.loanChanges().all().map { it.toDomain() })?.let { throw IllegalArgumentException(it) }
        db.loanChanges().insert(change.copy(id = 0).toEntity())
    }

    /** Removes a change of terms: the schedule goes back to what it was before it. Recorded payments are untouched. */
    suspend fun deleteChange(id: Long) = db.loanChanges().delete(id)

    /**
     * The "Loan interest" category's id, made when it isn't there yet (and brought back when it was archived).
     * A loan's own interest category is its terms'; this is the one that stands in for none.
     */
    private suspend fun interestCategory(): Long {
        val all = db.categories().all().map { it.toDomain() }
        LoanCategories.interestCategory(all)?.let { return it.id }
        all.firstOrNull { it.parentId == null && it.kind == CategoryKind.EXPENSE && it.name.equals(LoanCategories.NAME, ignoreCase = true) }?.let { archived ->
            db.categories().update(archived.copy(hidden = false).toEntity())
            return archived.id
        }
        return categories.save(
            Category(
                name = LoanCategories.NAME, kind = CategoryKind.EXPENSE, icon = CategoryIcon.Symbol("bank"),
                color = CategoryColors.next(all, CategoryKind.EXPENSE),
                // A loan is a standing cost, like rent, so it doesn't eat into what is safe to spend today.
                countsTowardDaily = false,
            )
        )
    }

    /** The payment an entry belongs to, if it belongs to one: so the entry opens the payment sheet, never the entry page. */
    suspend fun paymentForEntry(entryId: Long): LoanPayment? = db.loanPayments().forEntry(entryId)?.toDomain()

    /** What a saved payment's entries hold, to open the sheet on; null when the payment is gone. */
    suspend fun detail(paymentId: Long): LoanPaymentDetail? {
        val p = db.loanPayments().byId(paymentId)?.toDomain() ?: return null
        val principal = p.principalEntryId?.let { db.transactions().byId(it) }
        val interest = p.interestEntryId?.let { db.transactions().byId(it) }
        val other = p.otherEntryId?.let { db.transactions().byId(it) }
        return LoanPaymentDetail(
            payment = p,
            principal = principal?.amount ?: 0,
            interest = interest?.amount ?: 0,
            fromId = principal?.accountId ?: interest?.accountId ?: other?.accountId,
            categoryId = interest?.categoryId,
            escrowCategoryId = other?.categoryId,
        )
    }

    /**
     * Records a payment, or saves an edit of one, in one database transaction. Throws [IllegalArgumentException] with a
     * message to show when the draft breaks a rule.
     *
     * A payment that covers the scheduled payment moves the loan's next payment date on by a month, and editing or
     * deleting it moves the date back, so the date follows the payments that were really made.
     */
    suspend fun recordPayment(d: LoanPaymentDraft): LoanPayment = db.withTransaction {
        val terms = db.loanTerms().forAccount(d.accountId)?.toDomain() ?: throw IllegalArgumentException("Add this loan's terms first.")
        val loan = db.accounts().all().firstOrNull { it.id == d.accountId } ?: throw IllegalArgumentException("This loan isn't there any more.")
        InputRules.amountProblem(d.amount)?.let { throw IllegalArgumentException(it) }
        require(d.principal >= 0 && d.interest >= 0 && d.escrow >= 0 && d.charge >= 0) { "Parts of a payment can't be negative." }
        val parts = d.principal + d.interest + d.escrow + d.charge
        require(parts == d.amount) { "The parts add up to ${Money.format(parts)}, not ${Money.format(d.amount)}." }
        val from = d.fromId ?: throw IllegalArgumentException("Pick the account it's paid from.")
        require(from != d.accountId && db.accounts().all().any { it.id == from }) { "Pick the account it's paid from." }
        val category = d.categoryId ?: terms.interestCategoryId ?: interestCategory()
        require(db.categories().all().any { it.id == category }) { "Pick a category for the ${terms.interestWord.lowercase()}." }
        // The principal's category only matters when this payment counts: its own choice, else the loan's.
        val counts = d.countAllAsSpending ?: terms.countAllAsSpending
        val principalCategory = d.principalCategoryId?.takeIf { counts }
        principalCategory?.let { id ->
            require(db.categories().all().any { it.id == id && it.kind == CategoryKind.EXPENSE.name }) { "Pick an expense category for the principal." }
        }
        val costs = d.escrow + d.charge
        val costCategory = if (costs > 0) (d.escrowCategoryId ?: escrowCategory()) else null
        costCategory?.let { id -> require(db.categories().all().any { it.id == id }) { "Pick a category for the ${if (d.escrow > 0) "escrow" else "charge"}." } }

        // Take the payment being edited out first, so the balance and the next date are as they were before it.
        var next = terms.nextPaymentDay
        val old = d.id?.let { db.loanPayments().byId(it)?.toDomain() }
        if (old != null) {
            if (old.scheduled) next = LoanDates.addMonths(next, -1, terms.dueDay)
            old.entryIds.forEach { db.transactions().delete(it) }
        }
        val owed = -(db.accounts().balanceOf(d.accountId) ?: 0)
        require(d.principal <= owed.coerceAtLeast(0)) { "That's more than the ${Money.format(owed.coerceAtLeast(0))} still owed." }

        val name = loan.name
        val principalId = if (d.principal > 0) db.transactions().insert(
            Transaction(kind = TxKind.TRANSFER, amount = d.principal, accountId = from, toAccountId = d.accountId, epochMillis = d.epochMillis, note = note(LoanNotes.principal(name))).toEntity()
        ) else null
        val interestId = if (d.interest > 0) db.transactions().insert(
            Transaction(kind = TxKind.EXPENSE, amount = d.interest, accountId = from, categoryId = category, epochMillis = d.epochMillis, note = note(LoanNotes.interest(name, terms.interestWord))).toEntity()
        ) else null
        val otherId = if (costs > 0) db.transactions().insert(
            Transaction(
                kind = TxKind.EXPENSE, amount = costs, accountId = from, categoryId = costCategory, epochMillis = d.epochMillis,
                note = note(LoanNotes.other(name, d.escrow, d.charge)),
            ).toEntity()
        ) else null

        val changes = db.loanChanges().all().map { it.toDomain() }
        val scheduled = d.amount >= terms.effective(changes, d.day).payment + terms.escrow
        if (scheduled) next = LoanDates.addMonths(next, 1, terms.dueDay)
        db.loanTerms().setNextPaymentDay(d.accountId, next)

        val link = LoanPayment(
            id = old?.id ?: 0, accountId = d.accountId, day = d.day, amount = d.amount,
            principalEntryId = principalId, interestEntryId = interestId, otherEntryId = otherId,
            escrow = d.escrow, charge = d.charge, edited = d.edited, estimate = d.estimate, scheduled = scheduled,
            countAllAsSpending = d.countAllAsSpending, principalCategoryId = principalCategory,
        )
        if (old != null) link.also { db.loanPayments().update(it.toEntity()) }
        else link.copy(id = db.loanPayments().insert(link.toEntity()))
    }

    /** Deletes a payment with all its entries, and moves the next payment date back when it had moved it on. */
    suspend fun deletePayment(id: Long) = db.withTransaction {
        val p = db.loanPayments().byId(id)?.toDomain() ?: return@withTransaction
        db.loanTerms().forAccount(p.accountId)?.let { t ->
            if (p.scheduled) db.loanTerms().setNextPaymentDay(p.accountId, LoanDates.addMonths(t.nextPaymentDay, -1, t.dueDay))
        }
        p.entryIds.forEach { db.transactions().delete(it) }
        db.loanPayments().delete(id)
    }

    /** Entries are kept within the note limit whatever the loan is called. */
    private fun note(text: String): String = InputRules.clean(text).take(InputRules.NOTE_MAX)
}
