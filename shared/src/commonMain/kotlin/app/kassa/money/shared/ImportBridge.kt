package app.kassa.money.shared

import app.kassa.money.backup.BackupAccount
import app.kassa.money.backup.BackupBill
import app.kassa.money.backup.BackupBudget
import app.kassa.money.backup.BackupCategory
import app.kassa.money.backup.BackupData
import app.kassa.money.backup.BackupGroup
import app.kassa.money.backup.BackupLoanEntry
import app.kassa.money.backup.BackupPerson
import app.kassa.money.backup.BackupTransaction
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.Currencies
import app.kassa.money.domain.Money
import app.kassa.money.domain.NumberText
import app.kassa.money.domain.TxKind
import app.kassa.money.domain.dateOfMillis
import app.kassa.money.domain.toEpochDay
import app.kassa.money.importer.CashewImporter
import app.kassa.money.importer.CashewInterview
import app.kassa.money.importer.CashewReader
import app.kassa.money.importer.CashewScan
import app.kassa.money.importer.CashewSql
import app.kassa.money.importer.ImportIssue
import app.kassa.money.importer.ImportPlan
import app.kassa.money.importer.LoanChoice
import app.kassa.money.importer.MoneyManagerImporter
import app.kassa.money.importer.MoneyManagerReader
import app.kassa.money.importer.MoneyManagerSql
import app.kassa.money.importer.PickedFileRules
import app.kassa.money.importer.SqlQuery
import app.kassa.money.importer.SqlResults
import kotlinx.datetime.TimeZone

/*
 * Import from another app on iOS (batch K). Swift opens the picked SQLite file read-only, runs the queries listed here into a
 * `SqlResults` (:importer), and everything else happens in Kotlin: which app wrote the file, Money Manager's plan or Cashew's
 * questions and plan, the review with its balance checks, and the data to save (`ImportSession.merged`, which mirrors Android's
 * `ImportRepository.replaceWith`). Swift only runs SQL, lays out and words the answers. Values cross as Strings, Ints, Longs,
 * Booleans and small classes; kinds and steps are upper-case names compared as text; Money text honours `hidden`.
 */

/** What opening a file gave: a [session] to ask questions of, or the [refusal] sentence to show (the file isn't one Kassa can read). */
class ImportOpened(val session: ImportSession?, val refusal: String)

/** One Cashew account for the questions. Amounts are text, ready to show. */
class ImportWalletData(
    val pk: String,
    val name: String,
    /** The account's currency code ("" for the main one). */
    val currency: String,
    /** The balance as the accounts question shows it: in the phone's currency, or plain with its code when it is another currency. */
    val shownAmount: String,
    /** The balance as a plain number with its code ("12.50 EUR"). */
    val codeAmount: String,
    /** The balance in the phone's currency's style. */
    val phoneAmount: String,
    val entries: Int,
    val entriesText: String,
    val transfers: Int,
    val transfersText: String,
    /** The `AccountType` name picked for it, or "" before. */
    val kind: String,
    /** True for an account in another currency than the phone's. */
    val other: Boolean,
    val leaveOut: Boolean,
)

class ImportLoanData(
    val pk: String,
    val name: String,
    val lent: Boolean,
    val amountText: String,
    val payments: Int,
    val paymentsText: String,
    /** A `LoanChoice` name, or "" before. */
    val choice: String,
)

/** A Cashew question: [step] is FOUND, ACCOUNTS, LOANS or CURRENCY. [currencyAccounts] has the one account a CURRENCY step asks about. */
class ImportQuestionData(
    val step: String,
    val canGoOn: Boolean,
    val accountCount: Int,
    val categories: Int,
    val subcategories: Int,
    val entries: Int,
    val transfers: Int,
    val hasRange: Boolean,
    val firstEpochDay: Int,
    val lastEpochDay: Int,
    val phoneCurrency: String,
    val accounts: List<ImportWalletData>,
    val loans: List<ImportLoanData>,
    val currencyAccounts: List<ImportWalletData>,
)

/** One line of the review's "stays behind" or "worth knowing" lists. [kind] names the sentence; [count] and [first] fill it (see `Wording+Import.swift`). */
class ImportIssueData(
    val kind: String,
    val count: Int,
    val countText: String,
    /** Names, a reason or "what", or a zero amount, depending on [kind]. */
    val first: String,
)

class ImportReviewData(
    val sourceName: String,
    val appName: String,
    val expenses: Int,
    val income: Int,
    val transfers: Int,
    val accounts: Int,
    val categoriesActive: Int,
    val categoriesRetired: Int,
    val budgets: Int,
    val people: Int,
    val bills: Int,
    val entries: Int,
    val allMatch: Boolean,
    val accountChecks: Int,
    val accountsBad: Int,
    /** "Name (difference)" for each account that differs, joined with ", ". */
    val accountsBadText: String,
    val monthChecks: Int,
    val monthsBad: Int,
    val firstBadMonth: String,
    val staysBehind: List<ImportIssueData>,
    val worthKnowing: List<ImportIssueData>,
    /** True when the plan brings people or bills (the replace sentence says people are kept). */
    val bringsPeopleOrBills: Boolean,
    val cardsWithoutStatement: List<String>,
    val cardsWithoutPayFrom: List<String>,
    val peopleNames: String,
)

/** The state of one import: the file read, the questions (Cashew) and the plan. Create it with [ImportBridge]. */
class ImportSession internal constructor(
    val kind: String,
    private val currency: String,
    private val detected: String,
    private val zone: TimeZone,
    private val moneyManager: MoneyManagerReader?,
    private val cashew: CashewReader?,
    private val interview: CashewInterview?,
    private var plan: ImportPlan?,
) {

    /** The backup's own currency code when Kassa lists it, else "". */
    fun detectedCurrency(): String = detected

    /** True once the review can be shown. */
    fun hasReview(): Boolean = plan != null

    /** The step of the questions, or REVIEW once the plan is made. */
    fun step(): String = if (plan != null) "REVIEW" else interview?.step?.name ?: "REVIEW"

    fun setAccountType(pk: String, type: String) {
        val t = AccountType.entries.firstOrNull { it.name == type } ?: return
        interview?.setAccountType(pk, t)
    }

    fun setLoan(pk: String, choice: String) {
        val c = LoanChoice.entries.firstOrNull { it.name == choice } ?: return
        interview?.setLoan(pk, c)
    }

    fun setLeaveOut(pk: String, leaveOut: Boolean) {
        interview?.setLeaveOut(pk, leaveOut)
    }

    /**
     * Moves to the next question. When the last one is answered the plan is made and [hasReview] turns true. Throws when planning fails
     * (Swift shows the plain "couldn't read that file" sentence).
     */
    @Throws(Exception::class)
    fun goOn() {
        val i = interview ?: return
        if (!i.canGoOn()) return
        val reader = cashew ?: return
        if (i.next()) plan = i.plan(reader, CashewImporter(zone))
    }

    /** Back one question; true when that leaves the first one (the caller returns to picking a file). */
    fun goBack(): Boolean {
        plan = null
        return interview?.back() ?: true
    }

    /** The current question, with amounts written for [hidden]. */
    @Throws(Exception::class)
    fun question(hidden: Boolean): ImportQuestionData {
        val i = interview ?: throw IllegalStateException("No questions for this file")
        return withMoneyHidden(currency, hidden) {
            val scan = i.scan
            val other = i.otherCurrency.map { it.pk }.toSet()
            fun wallet(w: CashewScan.Wallet): ImportWalletData {
                val isOther = w.pk in other
                val code = w.currency.orEmpty()
                val plain = NumberText.decimal(w.balance, 2, 2) + " " + code
                return ImportWalletData(
                    pk = w.pk, name = w.name, currency = code,
                    shownAmount = if (isOther) plain else Money.format(w.balance), codeAmount = plain, phoneAmount = Money.format(w.balance),
                    entries = w.entries, entriesText = count(w.entries), transfers = w.transfers, transfersText = count(w.transfers),
                    kind = i.choices.accountTypes[w.pk]?.name.orEmpty(), other = isOther, leaveOut = w.pk in i.choices.leaveOut,
                )
            }
            val wallets = scan.accounts.map { wallet(it) }
            val asking = if (i.step == CashewInterview.Step.CURRENCY) listOfNotNull(i.otherCurrency.getOrNull(i.currencyIndex)).map { wallet(it) } else emptyList()
            ImportQuestionData(
                step = i.step.name, canGoOn = i.canGoOn(), accountCount = scan.accounts.size, categories = scan.categories,
                subcategories = scan.subcategories, entries = scan.entries, transfers = scan.transfers,
                hasRange = scan.firstMillis != null && scan.lastMillis != null,
                firstEpochDay = scan.firstMillis?.let { dateOfMillis(it, zone).toEpochDay().toInt() } ?: 0,
                lastEpochDay = scan.lastMillis?.let { dateOfMillis(it, zone).toEpochDay().toInt() } ?: 0,
                phoneCurrency = currency, accounts = wallets,
                loans = scan.loans.map { l ->
                    ImportLoanData(
                        pk = l.pk, name = l.name, lent = l.lent, amountText = Money.format(l.amount), payments = l.payments,
                        paymentsText = count(l.payments), choice = i.choices.loans[l.pk]?.name.orEmpty(),
                    )
                },
                currencyAccounts = asking,
            )
        }
    }

    /** The review of the plan, with amounts written for [hidden]. Throws when there is no plan yet. */
    @Throws(Exception::class)
    fun review(hidden: Boolean): ImportReviewData {
        val p = plan ?: throw IllegalStateException("No plan yet")
        return withMoneyHidden(currency, hidden) {
            val r = p.reconcile
            val byKind = p.transactions.groupingBy { it.kind }.eachCount()
            val badAccounts = r.accounts.filter { !it.matches }
            val badMonths = r.months.filter { !it.matches }
            val (left, other) = p.issues.partition { it is ImportIssue.LeftBehind }
            ImportReviewData(
                sourceName = p.sourceName, appName = p.appName,
                expenses = byKind[TxKind.EXPENSE] ?: 0, income = byKind[TxKind.INCOME] ?: 0, transfers = byKind[TxKind.TRANSFER] ?: 0,
                accounts = p.accounts.size, categoriesActive = p.categories.count { !it.hidden }, categoriesRetired = p.categories.count { it.hidden },
                budgets = p.budgets.size, people = p.people.size, bills = p.bills.size, entries = p.transactions.size,
                allMatch = r.allMatch, accountChecks = r.accounts.size, accountsBad = badAccounts.size,
                accountsBadText = badAccounts.joinToString { "${it.name} (${Money.format(it.imported - it.source)})" },
                monthChecks = r.months.size, monthsBad = badMonths.size, firstBadMonth = badMonths.firstOrNull()?.month?.toString().orEmpty(),
                staysBehind = left.map { issue(it) }, worthKnowing = other.map { issue(it) },
                bringsPeopleOrBills = p.people.isNotEmpty() || p.bills.isNotEmpty(),
                cardsWithoutStatement = p.accounts.filter { it.type == AccountType.CREDIT_CARD && it.statementDay == null }.map { it.name },
                cardsWithoutPayFrom = p.accounts.filter { it.type == AccountType.CREDIT_CARD && it.payFromId == null }.map { it.name },
                peopleNames = p.people.joinToString { it.name },
            )
        }
    }

    private fun issue(i: ImportIssue): ImportIssueData = when (i) {
        is ImportIssue.PossibleDuplicateAccounts -> ImportIssueData("DUPLICATE_ACCOUNTS", 0, "", i.names.joinToString(" and "))
        is ImportIssue.MismatchedTransfers -> ImportIssueData("MISMATCHED_TRANSFERS", i.count, count(i.count), "")
        is ImportIssue.UnpairedTransfers -> ImportIssueData("UNPAIRED_TRANSFERS", i.count, count(i.count), "")
        is ImportIssue.ZeroAmountEntries -> ImportIssueData("ZERO_AMOUNT", i.count, count(i.count), Money.format(0))
        is ImportIssue.SkippedRows -> ImportIssueData("SKIPPED_ROWS", i.count, count(i.count), i.reason)
        is ImportIssue.MergedCategories -> ImportIssueData("MERGED_CATEGORIES", 0, "", i.names.joinToString())
        is ImportIssue.LeftBehind -> ImportIssueData("LEFT_BEHIND", i.count, count(i.count), i.what)
        is ImportIssue.RefundsAsIncome -> ImportIssueData("REFUNDS", i.count, count(i.count), "")
        is ImportIssue.BalanceCorrections -> ImportIssueData("BALANCE_CORRECTIONS", i.count, count(i.count), "")
        is ImportIssue.TransfersAsEntries -> ImportIssueData("TRANSFERS_AS_ENTRIES", i.count, count(i.count), "")
        is ImportIssue.OtherCurrency -> ImportIssueData("OTHER_CURRENCY", 0, "", i.accounts.joinToString())
        is ImportIssue.DeletedAccounts -> ImportIssueData("DELETED_ACCOUNTS", i.count, count(i.count), "")
        is ImportIssue.CardsWithoutPayFrom -> ImportIssueData("CARDS_NO_PAY_FROM", i.count, count(i.count), "")
    }

    /**
     * What Kassa holds after this import over [existing], as Android's `ImportRepository.replaceWith` leaves it: the plan's groups,
     * accounts, categories, entries and budgets replace everything, and so does everything that points at the old accounts and
     * entries (holdings, trades, waiting investment transfers, loan terms, changes and payments, bills, split shares). People already
     * in Kassa stay with their starting balances; the plan's people join them (one with the same name is reused). The month start,
     * currency and category pictures stay; the account arrangement is dropped (it names old accounts).
     */
    @Throws(Exception::class)
    fun merged(existing: BackupData): BackupData {
        val p = plan ?: throw IllegalStateException("No plan yet")
        return ImportMerge.merge(existing, p)
    }
}

internal object ImportMerge {

    fun merge(existing: BackupData, plan: ImportPlan): BackupData {
        // The plan numbers its people from 1; map them onto people in Kassa by name, or add them after the largest id.
        var nextPerson = (existing.people.maxOfOrNull { it.id } ?: 0L) + 1
        val people = existing.people.toMutableList()
        val personIds = HashMap<Long, Long>()
        for (p in plan.people) {
            val same = existing.people.firstOrNull { it.name.equals(p.name, ignoreCase = true) }
            if (same != null) {
                personIds[p.id] = same.id
            } else {
                personIds[p.id] = nextPerson
                people += BackupPerson(nextPerson, p.name, p.archived)
                nextPerson++
            }
        }
        // Person balances not tied to an entry stay; ones tied to the old entries (split shares) go with them.
        val loans = existing.loans.filter { it.txId == null }.toMutableList()
        var nextLoan = (existing.loans.maxOfOrNull { it.id } ?: 0L) + 1
        for (l in plan.personLoans) {
            loans += BackupLoanEntry(nextLoan++, personIds.getValue(l.personId), l.amount, l.epochMillis, l.note, null)
        }
        return BackupData(
            createdAt = existing.createdAt,
            monthStartDay = existing.monthStartDay,
            currency = existing.currency,
            groups = plan.groups.map { BackupGroup(it.id, it.name, it.sortOrder) },
            accounts = plan.accounts.map {
                BackupAccount(
                    it.id, it.name, it.type.name, it.groupId, it.openingBalance, it.statementDay, it.dueDay, it.payFromId, it.creditLimit,
                    it.archived, it.sortOrder, it.externalId, it.last4, it.maturityDay, it.closedDay,
                )
            },
            categories = plan.categories.map {
                BackupCategory(it.id, it.name, it.kind.name, it.parentId, it.icon.encode(), it.color, it.countsTowardDaily, it.hidden, it.sortOrder, it.externalId)
            },
            transactions = plan.transactions.map {
                BackupTransaction(
                    it.id, it.kind.name, it.amount, it.accountId, it.toAccountId, it.categoryId, it.personId?.let { id -> personIds.getValue(id) },
                    it.epochMillis, it.note, it.myShare, it.externalId,
                )
            },
            budgets = plan.budgets.map { BackupBudget(it.categoryId, it.month, it.amount) },
            people = people,
            loans = loans,
            bills = plan.bills.mapIndexed { i, b ->
                BackupBill((i + 1).toLong(), b.name, b.categoryId, b.accountId, b.dayOfMonth, b.fixedAmount, b.active, b.handledMonth)
            },
            icons = existing.icons,
            accountArrangement = null,
        )
    }
}

private fun count(n: Int): String = NumberText.group(n.toString())

/** What the iOS import calls: telling files apart, the queries to run, and opening a file into an [ImportSession]. */
object ImportBridge {

    /** What the first 16 bytes (as Latin-1 text, zero-padded) and the size of a picked file say: EMPTY, KASSA, KASSA_PROTECTED, SQLITE, TEXT or OTHER. */
    fun kindOfHead(head: String, size: Long): String = PickedFileRules.kindOfHead(head, size)

    /** MONEY_MANAGER, CASHEW or UNKNOWN, from the table names of a SQLite file. */
    fun kindOfTables(tables: List<String>): String = PickedFileRules.kindOfTables(tables)

    /** The sentence for a file that isn't one Kassa reads (EMPTY, TEXT, OTHER or UNKNOWN); "" for a kind it does read. */
    fun refusal(kind: String): String = PickedFileRules.refusal(kind)

    /** The sentence for a file that looked like a database but could not be opened or read. */
    fun damaged(): String = PickedFileRules.DAMAGED

    /** True when the Money Manager file's tables are the iPhone layout. */
    fun isIphoneLayout(tables: List<String>): Boolean = MoneyManagerSql.isIphoneLayout(tables)

    fun moneyManagerQueries(iphone: Boolean): List<SqlQuery> = MoneyManagerSql.queries(iphone)

    fun cashewQueries(): List<SqlQuery> = CashewSql.queries()

    /**
     * Plans the import of a Money Manager backup. [currency] is the phone's (or "" for none yet); the backup's own is used when Kassa
     * lists it. Throws when a row can't be read.
     */
    @Throws(Exception::class)
    fun openMoneyManager(results: SqlResults, iphone: Boolean, zoneId: String, currency: String): ImportOpened {
        val reader = MoneyManagerReader(results, iphone)
        val zone = zoneOf(zoneId)
        val own = Currencies.forCode(reader.mainCurrency())?.code.orEmpty()
        val plan = MoneyManagerImporter(zone).plan(reader)
        return ImportOpened(ImportSession(PickedFileRules.KIND_MONEY_MANAGER, own.ifEmpty { currency }, own, zone, reader, null, null, plan), "")
    }

    /** Reads a Cashew backup and starts its questions; a backup too old to read is a refusal. Throws when a row can't be read. */
    @Throws(Exception::class)
    fun openCashew(results: SqlResults, zoneId: String, currency: String): ImportOpened {
        CashewSql.refusal(results)?.let { return ImportOpened(null, it) }
        val reader = CashewReader(results)
        val zone = zoneOf(zoneId)
        val scan = CashewImporter(zone).scan(reader)
        val own = Currencies.forCode(scan.mainCurrency)?.code.orEmpty()
        val phone = own.ifEmpty { currency }
        val interview = CashewInterview(scan, phone)
        return ImportOpened(ImportSession(PickedFileRules.KIND_CASHEW, phone, own, zone, null, reader, interview, null), "")
    }

    private fun zoneOf(id: String): TimeZone = try {
        TimeZone.of(id)
    } catch (e: Exception) {
        TimeZone.UTC
    }
}
