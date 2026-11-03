package app.kassa.money.shared

import app.kassa.money.backup.BackupAccount
import app.kassa.money.backup.BackupBill
import app.kassa.money.backup.BackupBudget
import app.kassa.money.backup.BackupCategory
import app.kassa.money.backup.BackupData
import app.kassa.money.backup.BackupGroup
import app.kassa.money.backup.BackupHolding
import app.kassa.money.backup.BackupInvestmentTransfer
import app.kassa.money.backup.BackupLoanChange
import app.kassa.money.backup.BackupLoanEntry
import app.kassa.money.backup.BackupLoanPayment
import app.kassa.money.backup.BackupLoanTerms
import app.kassa.money.backup.BackupPerson
import app.kassa.money.backup.BackupTrade
import app.kassa.money.backup.BackupTransaction
import app.kassa.money.backup.Base64Text

/*
 * A flat, Swift-friendly view of BackupData for the iOS app's database (stage A of the iOS storage).
 *
 * Why this shape: Kotlin classes, nullable Long and Map<String, Any?> are awkward from Swift (boxed KotlinLong, NSNull inside
 * NSArray, no generics for nullable elements). So every cell crosses as a `String?` (null stays null), an Int or Boolean
 * becomes decimal text ("1" and "0" for a flag), and Swift reads or binds it by the column's kind ("int" or "text") from
 * `columnKinds`. Only plain `String`, `String?`, `Int`, `Long` and `List<String>` appear in signatures.
 *
 * Tables are named like Android's Room tables (`account_groups`, `loan_entries`, `recurring` ...) and columns like its
 * entities, in the order of Room schema 10, so Swift can build `INSERT` statements from the names alone. A new table or
 * column in Room changes this file, `BackupData`, and `ios/Kassa/Storage/Schema.swift` together (a JVM test compares the
 * column lists with Schema.swift).
 */
object BackupRows {

    /** The tables in the order they are created and filled. Pictures are separate ([iconNames]). */
    fun tableNames(): List<String> = TABLES.map { it.name }

    /** Column names of [table] in the order [cell] and the builder's `setCell` use; empty for an unknown table. */
    fun columns(table: String): List<String> = find(table)?.columns ?: emptyList()

    /** "int" or "text" for each column of [table]. A Boolean is an "int" holding 0 or 1. */
    fun columnKinds(table: String): List<String> = find(table)?.kinds ?: emptyList()

    /** Rows of [table] in [data]; 0 for an unknown table. */
    fun rowCount(data: BackupData, table: String): Int = find(table)?.size?.invoke(data) ?: 0

    /** One cell: null for a missing value (or an unknown table or position), otherwise text as described above. */
    fun cell(data: BackupData, table: String, row: Int, column: Int): String? {
        val t = find(table) ?: return null
        if (row < 0 || row >= t.size(data) || column < 0 || column >= t.columns.size) return null
        return t.read(data, row)[column]
    }

    /** File names of the category pictures, sorted. */
    fun iconNames(data: BackupData): List<String> = data.icons.keys.sorted()

    /** A picture's bytes as base64 text, or null for an unknown name. */
    fun icon(data: BackupData, name: String): String? = data.icons[name]?.let { Base64Text.encode(it) }

    internal fun find(table: String): Table? = TABLES.firstOrNull { it.name == table }

    internal class Table(
        val name: String,
        spec: String,
        val size: (BackupData) -> Int,
        val read: (BackupData, Int) -> List<String?>,
        val make: (Cells) -> Any,
    ) {
        /** [spec] is "name:i name:t ..." (i for an integer or flag, t for text). */
        val columns: List<String> = spec.split(" ").map { it.substringBefore(':') }
        val kinds: List<String> = spec.split(" ").map { if (it.substringAfter(':') == "i") "int" else "text" }
    }

    internal class Cells(private val table: String, private val names: List<String>, private val v: List<String?>) {
        private fun bad(i: Int): Nothing = throw IllegalArgumentException("Bad value for $table.${names[i]}")
        private fun flag(s: String, i: Int): Boolean = when (s) { "1" -> true; "0" -> false; else -> bad(i) }

        fun text(i: Int): String = v[i] ?: bad(i)
        fun textOrNull(i: Int): String? = v[i]
        fun long(i: Int): Long = v[i]?.toLongOrNull() ?: bad(i)
        fun longOrNull(i: Int): Long? { val s = v[i] ?: return null; return s.toLongOrNull() ?: bad(i) }
        fun int(i: Int): Int = v[i]?.toIntOrNull() ?: bad(i)
        fun intOrNull(i: Int): Int? { val s = v[i] ?: return null; return s.toIntOrNull() ?: bad(i) }
        fun bool(i: Int): Boolean = flag(v[i] ?: bad(i), i)
        fun boolOrNull(i: Int): Boolean? { val s = v[i] ?: return null; return flag(s, i) }
    }

    private fun n(x: Long?): String? = x?.toString()
    private fun n(x: Int?): String? = x?.toString()
    private fun f(x: Boolean?): String? = if (x == null) null else if (x) "1" else "0"

    private val TABLES: List<Table> = listOf(
        Table("account_groups", "id:i name:t sortOrder:i", { it.groups.size }, { d, r ->
            val x = d.groups[r]; listOf(n(x.id), x.name, n(x.sortOrder))
        }, { c -> BackupGroup(c.long(0), c.text(1), c.int(2)) }),

        Table(
            "accounts",
            "id:i name:t type:t groupId:i openingBalance:i statementDay:i dueDay:i payFromId:i creditLimit:i archived:i sortOrder:i " +
                "externalId:t last4:t maturityDay:i closedDay:i",
            { it.accounts.size },
            { d, r ->
                val x = d.accounts[r]
                listOf(
                    n(x.id), x.name, x.type, n(x.groupId), n(x.openingBalance), n(x.statementDay), n(x.dueDay), n(x.payFromId),
                    n(x.creditLimit), f(x.archived), n(x.sortOrder), x.externalId, x.last4, n(x.maturityDay), n(x.closedDay),
                )
            },
            { c ->
                BackupAccount(
                    id = c.long(0), name = c.text(1), type = c.text(2), groupId = c.longOrNull(3), openingBalance = c.long(4),
                    statementDay = c.intOrNull(5), dueDay = c.intOrNull(6), payFromId = c.longOrNull(7), creditLimit = c.longOrNull(8),
                    archived = c.bool(9), sortOrder = c.int(10), externalId = c.textOrNull(11), last4 = c.textOrNull(12),
                    maturityDay = c.intOrNull(13), closedDay = c.intOrNull(14),
                )
            },
        ),

        Table(
            "categories", "id:i name:t kind:t parentId:i icon:t color:i countsTowardDaily:i hidden:i sortOrder:i externalId:t",
            { it.categories.size },
            { d, r ->
                val x = d.categories[r]
                listOf(n(x.id), x.name, x.kind, n(x.parentId), x.icon, n(x.color), f(x.countsTowardDaily), f(x.hidden), n(x.sortOrder), x.externalId)
            },
            { c ->
                BackupCategory(
                    id = c.long(0), name = c.text(1), kind = c.text(2), parentId = c.longOrNull(3), icon = c.text(4), color = c.int(5),
                    countsTowardDaily = c.bool(6), hidden = c.bool(7), sortOrder = c.int(8), externalId = c.textOrNull(9),
                )
            },
        ),

        Table(
            "transactions", "id:i kind:t amount:i accountId:i toAccountId:i categoryId:i personId:i epochMillis:i note:t myShare:i externalId:t",
            { it.transactions.size },
            { d, r ->
                val x = d.transactions[r]
                listOf(
                    n(x.id), x.kind, n(x.amount), n(x.accountId), n(x.toAccountId), n(x.categoryId), n(x.personId), n(x.epochMillis),
                    x.note, n(x.myShare), x.externalId,
                )
            },
            { c ->
                BackupTransaction(
                    id = c.long(0), kind = c.text(1), amount = c.long(2), accountId = c.longOrNull(3), toAccountId = c.longOrNull(4),
                    categoryId = c.longOrNull(5), personId = c.longOrNull(6), epochMillis = c.long(7), note = c.text(8),
                    myShare = c.longOrNull(9), externalId = c.textOrNull(10),
                )
            },
        ),

        Table("budgets", "categoryId:i month:i amount:i", { it.budgets.size }, { d, r ->
            val x = d.budgets[r]; listOf(n(x.categoryId), n(x.month), n(x.amount))
        }, { c -> BackupBudget(c.long(0), c.int(1), c.long(2)) }),

        Table("people", "id:i name:t archived:i", { it.people.size }, { d, r ->
            val x = d.people[r]; listOf(n(x.id), x.name, f(x.archived))
        }, { c -> BackupPerson(c.long(0), c.text(1), c.bool(2)) }),

        Table("loan_entries", "id:i personId:i amount:i epochMillis:i note:t txId:i", { it.loans.size }, { d, r ->
            val x = d.loans[r]; listOf(n(x.id), n(x.personId), n(x.amount), n(x.epochMillis), x.note, n(x.txId))
        }, { c -> BackupLoanEntry(c.long(0), c.long(1), c.long(2), c.long(3), c.text(4), c.longOrNull(5)) }),

        Table("recurring", "id:i name:t categoryId:i accountId:i dayOfMonth:i fixedAmount:i active:i handledMonth:i", { it.bills.size }, { d, r ->
            val x = d.bills[r]
            listOf(n(x.id), x.name, n(x.categoryId), n(x.accountId), n(x.dayOfMonth), n(x.fixedAmount), f(x.active), n(x.handledMonth))
        }, { c -> BackupBill(c.long(0), c.text(1), c.long(2), c.long(3), c.int(4), c.longOrNull(5), c.bool(6), c.int(7)) }),

        Table("investment_transfers", "id:i transactionId:i accountId:i amount:i epochMillis:i", { it.investmentTransfers.size }, { d, r ->
            val x = d.investmentTransfers[r]; listOf(n(x.id), n(x.transactionId), n(x.accountId), n(x.amount), n(x.epochMillis))
        }, { c -> BackupInvestmentTransfer(c.long(0), c.long(1), c.long(2), c.long(3), c.long(4)) }),

        Table("holdings", "id:i accountId:i ticker:t microShares:i price:i priceMillis:i avgCost:i", { it.holdings.size }, { d, r ->
            val x = d.holdings[r]
            listOf(n(x.id), n(x.accountId), x.ticker, n(x.microShares), n(x.price), n(x.priceMillis), n(x.avgCost))
        }, { c -> BackupHolding(c.long(0), c.long(1), c.text(2), c.long(3), c.long(4), c.long(5), c.longOrNull(6)) }),

        Table("trades", "id:i transactionId:i ticker:t microShares:i price:i", { it.trades.size }, { d, r ->
            val x = d.trades[r]; listOf(n(x.id), n(x.transactionId), x.ticker, n(x.microShares), n(x.price))
        }, { c -> BackupTrade(c.long(0), c.long(1), c.text(2), c.long(3), c.long(4)) }),

        Table(
            "loan_terms",
            "accountId:i type:t profit:i rate:i payment:i termMonths:i originalAmount:i startDay:i nextPaymentDay:i dueDay:i escrow:i fee:i " +
                "earlyCharge:i interestCategoryId:i payFromId:i countAllAsSpending:i principalCategoryId:i",
            { it.loanTerms.size },
            { d, r ->
                val x = d.loanTerms[r]
                listOf(
                    n(x.accountId), x.type, f(x.profit), n(x.rate), n(x.payment), n(x.termMonths), n(x.originalAmount), n(x.startDay),
                    n(x.nextPaymentDay), n(x.dueDay), n(x.escrow), n(x.fee), n(x.earlyCharge), n(x.interestCategoryId), n(x.payFromId),
                    f(x.countAllAsSpending), n(x.principalCategoryId),
                )
            },
            { c ->
                BackupLoanTerms(
                    accountId = c.long(0), type = c.text(1), profit = c.bool(2), rate = c.long(3), payment = c.long(4),
                    termMonths = c.intOrNull(5), originalAmount = c.longOrNull(6), startDay = c.intOrNull(7), nextPaymentDay = c.int(8),
                    dueDay = c.int(9), escrow = c.long(10), fee = c.long(11), earlyCharge = c.long(12), interestCategoryId = c.longOrNull(13),
                    payFromId = c.longOrNull(14), countAllAsSpending = c.bool(15), principalCategoryId = c.longOrNull(16),
                )
            },
        ),

        Table("loan_term_changes", "id:i accountId:i effectiveDay:i rate:i payment:i termMonths:i type:t", { it.loanChanges.size }, { d, r ->
            val x = d.loanChanges[r]
            listOf(n(x.id), n(x.accountId), n(x.effectiveDay), n(x.rate), n(x.payment), n(x.termMonths), x.type)
        }, { c -> BackupLoanChange(c.long(0), c.long(1), c.int(2), c.longOrNull(3), c.longOrNull(4), c.intOrNull(5), c.textOrNull(6)) }),

        Table(
            "loan_payments",
            "id:i accountId:i day:i amount:i principalEntryId:i interestEntryId:i otherEntryId:i escrow:i charge:i edited:i estimate:i " +
                "scheduled:i countAllAsSpending:i principalCategoryId:i",
            { it.loanPayments.size },
            { d, r ->
                val x = d.loanPayments[r]
                listOf(
                    n(x.id), n(x.accountId), n(x.day), n(x.amount), n(x.principalEntryId), n(x.interestEntryId), n(x.otherEntryId),
                    n(x.escrow), n(x.charge), f(x.edited), n(x.estimate), f(x.scheduled), f(x.countAllAsSpending), n(x.principalCategoryId),
                )
            },
            { c ->
                BackupLoanPayment(
                    id = c.long(0), accountId = c.long(1), day = c.int(2), amount = c.long(3), principalEntryId = c.longOrNull(4),
                    interestEntryId = c.longOrNull(5), otherEntryId = c.longOrNull(6), escrow = c.long(7), charge = c.long(8),
                    edited = c.bool(9), estimate = c.long(10), scheduled = c.bool(11), countAllAsSpending = c.boolOrNull(12),
                    principalCategoryId = c.longOrNull(13),
                )
            },
        ),
    )
}

/**
 * Builds a [BackupData] from cells, the other way round from [BackupRows]: Swift reads its database row by row, calls
 * [setCell] for every column of the row in order (a null for a missing value), then [endRow]. Nothing is checked against
 * the rest of the file (ids that point nowhere, duplicates): that is the database's and the importer's job.
 */
class BackupBuilder(
    private val createdAt: Long,
    private val monthStartDay: Int,
    currency: String?,
    accountArrangement: String?,
) {
    private val currency: String? = currency?.takeIf { it.isNotEmpty() }
    private val accountArrangement: String? = accountArrangement?.takeIf { it.isNotEmpty() }
    private val rows = HashMap<String, MutableList<Any>>()
    private val pending = HashMap<String, Array<String?>>()
    private val isSet = HashMap<String, BooleanArray>()
    private val icons = LinkedHashMap<String, ByteArray>()

    /** Sets column [column] (0-based, in [BackupRows.columns] order) of the row being built for [table]. */
    @Throws(IllegalArgumentException::class)
    fun setCell(table: String, column: Int, value: String?) {
        val t = BackupRows.find(table) ?: throw IllegalArgumentException("Unknown table")
        if (column < 0 || column >= t.columns.size) throw IllegalArgumentException("Unknown column")
        pending.getOrPut(table) { arrayOfNulls(t.columns.size) }[column] = value
        isSet.getOrPut(table) { BooleanArray(t.columns.size) }[column] = true
    }

    /** Finishes the row of [table]: every column must have been set since the last row, and each value must fit its column. */
    @Throws(IllegalArgumentException::class)
    fun endRow(table: String) {
        val t = BackupRows.find(table) ?: throw IllegalArgumentException("Unknown table")
        val cells = pending[table]
        val flags = isSet[table]
        if (cells == null || flags == null || flags.any { !it }) throw IllegalArgumentException("Row of $table is incomplete")
        val row = t.make(BackupRows.Cells(table, t.columns, cells.toList()))
        rows.getOrPut(table) { ArrayList() }.add(row)
        pending.remove(table)
        isSet.remove(table)
    }

    /** Adds a category picture; [base64] is standard base64 text. */
    @Throws(IllegalArgumentException::class)
    fun addIcon(name: String, base64: String) {
        icons[name] = Base64Text.decode(base64)
    }

    private inline fun <reified T> list(table: String): List<T> = (rows[table] ?: emptyList<Any>()).map { it as T }

    fun build(): BackupData = BackupData(
        createdAt = createdAt,
        monthStartDay = monthStartDay,
        currency = currency,
        groups = list("account_groups"),
        accounts = list("accounts"),
        categories = list("categories"),
        transactions = list("transactions"),
        budgets = list("budgets"),
        people = list("people"),
        loans = list("loan_entries"),
        bills = list("recurring"),
        holdings = list("holdings"),
        trades = list("trades"),
        loanTerms = list("loan_terms"),
        loanChanges = list("loan_term_changes"),
        loanPayments = list("loan_payments"),
        icons = LinkedHashMap(icons),
        accountArrangement = accountArrangement,
        investmentTransfers = list("investment_transfers"),
    )
}
