package app.kassa.money.data.backup

import app.kassa.money.data.db.AccountEntity
import app.kassa.money.data.db.BudgetEntity
import app.kassa.money.data.db.CategoryEntity
import app.kassa.money.data.db.GroupEntity
import app.kassa.money.data.db.HoldingEntity
import app.kassa.money.data.db.InvestmentTransferEntity
import app.kassa.money.data.db.LoanEntryEntity
import app.kassa.money.data.db.LoanPaymentEntity
import app.kassa.money.data.db.LoanTermChangeEntity
import app.kassa.money.data.db.LoanTermsEntity
import app.kassa.money.data.db.PersonEntity
import app.kassa.money.data.db.RecurringEntity
import app.kassa.money.data.db.TradeEntity
import app.kassa.money.data.db.TransactionEntity
import java.io.Reader
import java.io.StringReader
import java.io.StringWriter
import java.io.Writer

/** Everything a backup holds: every table's rows, a few settings, and category photos (file name to bytes). */
data class BackupContents(
    val createdAt: Long,
    val monthStartDay: Int,
    val currency: String?,
    val groups: List<GroupEntity>,
    val accounts: List<AccountEntity>,
    val categories: List<CategoryEntity>,
    val transactions: List<TransactionEntity>,
    val budgets: List<BudgetEntity>,
    val people: List<PersonEntity>,
    val loans: List<LoanEntryEntity>,
    val bills: List<RecurringEntity>,
    val holdings: List<HoldingEntity>,
    /** Absent in backups from before trades were kept. */
    val trades: List<TradeEntity> = emptyList(),
    /** Absent in backups from before loans knew their terms: a loan account then comes back as a plain account. */
    val loanTerms: List<LoanTermsEntity> = emptyList(),
    val loanChanges: List<LoanTermChangeEntity> = emptyList(),
    val loanPayments: List<LoanPaymentEntity> = emptyList(),
    val icons: Map<String, ByteArray>,
    /** The user's account order, as [app.kassa.money.domain.AccountArrangement.encode]; absent in older backups. */
    val accountArrangement: String? = null,
    /** Investment transfers still waiting to be explained. Absent in older backups, which then restore none. */
    val investmentTransfers: List<InvestmentTransferEntity> = emptyList(),
) {
    /** One line for the confirm step before restoring. */
    val summary: String
        get() = "${transactions.size} entries, ${accounts.size} accounts, ${categories.size} categories"

    /**
     * The JSON inside a backup file, as text: for tests and small things. A backup file is written with
     * [writeJson] and read with [readJson], which stream. Field names are part of the file format: renaming one breaks
     * every existing backup, so add new fields instead.
     */
    fun toJson(encodeBytes: (ByteArray) -> String): String = StringWriter().also { writeJson(it, encodeBytes) }.toString()

    /** Writes the backup's JSON to [out] one value at a time. [out] isn't closed. */
    fun writeJson(out: Writer, encodeBytes: (ByteArray) -> String) {
        val w = BackupWriter(JsonOut(out), encodeBytes)
        w.begin(createdAt, monthStartDay, currency, accountArrangement)
        w.groups(groups)
        w.accounts(accounts)
        w.categories(categories)
        w.beginTransactions(); w.transactions(transactions); w.endTransactions()
        w.budgets(budgets)
        w.people(people)
        w.loans(loans)
        w.bills(bills)
        w.holdings(holdings)
        w.trades(trades)
        w.loanTerms(loanTerms)
        w.loanChanges(loanChanges)
        w.loanPayments(loanPayments)
        w.investmentTransfers(investmentTransfers)
        w.beginIcons(); icons.forEach { (name, bytes) -> w.icon(name, bytes) }; w.endIcons()
        w.end()
    }

    companion object {
        /** The file format's version. A file from a newer one is refused: its fields may mean something this code doesn't know. */
        const val VERSION = 1

        /** Reads the JSON of any backup Kassa has written. Throws [BackupFile.NotABackup] for anything else. */
        fun fromJson(text: String, decodeBytes: (String) -> ByteArray): BackupContents = readJson(StringReader(text), decodeBytes)

        /**
         * Reads a backup's JSON from [reader] one value at a time. Throws [BackupFile.NotABackup] for anything that isn't
         * one, and [BackupFile.NewerVersion] for one written by a newer Kassa. Keys it doesn't know are skipped.
         */
        fun readJson(reader: Reader, decodeBytes: (String) -> ByteArray): BackupContents {
            val r = JsonIn(reader)
            var version = 0
            try {
                var createdAt = 0L
                var monthStartDay = 1
                var currency: String? = null
                var arrangement: String? = null
                var groups = emptyList<GroupEntity>()
                var accounts = emptyList<AccountEntity>()
                var categories = emptyList<CategoryEntity>()
                var transactions = emptyList<TransactionEntity>()
                var budgets = emptyList<BudgetEntity>()
                var people = emptyList<PersonEntity>()
                var loans = emptyList<LoanEntryEntity>()
                var bills = emptyList<RecurringEntity>()
                var holdings = emptyList<HoldingEntity>()
                var trades = emptyList<TradeEntity>()
                var loanTerms = emptyList<LoanTermsEntity>()
                var loanChanges = emptyList<LoanTermChangeEntity>()
                var loanPayments = emptyList<LoanPaymentEntity>()
                var icons = emptyMap<String, ByteArray>()
                var transfers = emptyList<InvestmentTransferEntity>()

                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "version" -> { version = r.nextInt(); if (version > VERSION) throw BackupFile.NewerVersion() }
                        "createdAt" -> createdAt = r.nextLong()
                        "monthStartDay" -> monthStartDay = r.nextInt()
                        "currency" -> currency = r.optionalString()
                        "accountArrangement" -> arrangement = r.optionalString()
                        "groups" -> groups = r.rows { GroupEntity(long("id"), string("name"), int("sortOrder")) }
                        "accounts" -> accounts = r.rows {
                            AccountEntity(long("id"), string("name"), string("type"), longOrNull("groupId"), long("openingBalance"),
                                intOrNull("statementDay"), intOrNull("dueDay"), longOrNull("payFromId"), longOrNull("creditLimit"),
                                boolean("archived"), int("sortOrder"), stringOrNull("externalId"), stringOrNull("last4"),
                                intOrNull("maturityDay"), intOrNull("closedDay"))
                        }
                        "categories" -> categories = r.rows {
                            CategoryEntity(long("id"), string("name"), string("kind"), longOrNull("parentId"), string("icon"), int("color"),
                                boolean("countsTowardDaily"), boolean("hidden"), int("sortOrder"), stringOrNull("externalId"))
                        }
                        "transactions" -> transactions = r.rows {
                            TransactionEntity(long("id"), string("kind"), long("amount"), longOrNull("accountId"), longOrNull("toAccountId"),
                                longOrNull("categoryId"), longOrNull("personId"), long("epochMillis"), string("note"), longOrNull("myShare"), stringOrNull("externalId"))
                        }
                        "budgets" -> budgets = r.rows { BudgetEntity(long("categoryId"), int("month"), long("amount")) }
                        "people" -> people = r.rows { PersonEntity(long("id"), string("name"), boolean("archived")) }
                        "loans" -> loans = r.rows { LoanEntryEntity(long("id"), long("personId"), long("amount"), long("epochMillis"), string("note"), longOrNull("txId")) }
                        "recurring" -> bills = r.rows {
                            RecurringEntity(long("id"), string("name"), long("categoryId"), long("accountId"), int("dayOfMonth"),
                                longOrNull("fixedAmount"), boolean("active"), int("handledMonth"))
                        }
                        "holdings" -> holdings = r.rows {
                            HoldingEntity(long("id"), long("accountId"), string("ticker"), long("microShares"), long("price"), long("priceMillis"), longOrNull("avgCost"))
                        }
                        "trades" -> trades = r.rows { TradeEntity(long("id"), long("transactionId"), string("ticker"), long("microShares"), long("price")) }
                        "loanTerms" -> loanTerms = r.rows {
                            LoanTermsEntity(long("accountId"), string("type"), boolean("profit"), long("rate"), long("payment"),
                                intOrNull("termMonths"), longOrNull("originalAmount"), intOrNull("startDay"), int("nextPaymentDay"), int("dueDay"),
                                long("escrow"), long("fee"), long("earlyCharge"), longOrNull("interestCategoryId"), longOrNull("payFromId"),
                                boolean("countAllAsSpending", false), longOrNull("principalCategoryId"))
                        }
                        "loanChanges" -> loanChanges = r.rows {
                            LoanTermChangeEntity(long("id"), long("accountId"), int("effectiveDay"), longOrNull("rate"), longOrNull("payment"),
                                intOrNull("termMonths"), stringOrNull("type"))
                        }
                        "loanPayments" -> loanPayments = r.rows {
                            LoanPaymentEntity(long("id"), long("accountId"), int("day"), long("amount"), longOrNull("principalEntryId"),
                                longOrNull("interestEntryId"), longOrNull("otherEntryId"), long("escrow"), long("charge"), boolean("edited"),
                                long("estimate"), boolean("scheduled"),
                                booleanOrNull("countAllAsSpending"), longOrNull("principalCategoryId"))
                        }
                        "investmentTransfers" -> transfers = r.rows {
                            InvestmentTransferEntity(long("id"), long("transactionId"), long("accountId"), long("amount"), long("epochMillis"))
                        }
                        "icons" -> icons = r.icons(decodeBytes)
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                r.endDocument()
                return BackupContents(
                    createdAt = createdAt, monthStartDay = monthStartDay, currency = currency?.ifEmpty { null },
                    groups = groups, accounts = accounts, categories = categories, transactions = transactions, budgets = budgets,
                    people = people, loans = loans, bills = bills, holdings = holdings, trades = trades,
                    loanTerms = loanTerms, loanChanges = loanChanges, loanPayments = loanPayments, icons = icons,
                    accountArrangement = arrangement?.ifEmpty { null }, investmentTransfers = transfers,
                )
            } catch (e: JsonMalformed) {
                // A file the reader couldn't follow, from a newer Kassa, is more likely that than damaged.
                throw if (version > VERSION) BackupFile.NewerVersion() else BackupFile.NotABackup()
            } catch (e: IllegalArgumentException) {
                // Not valid base64 in an icon.
                throw BackupFile.NotABackup()
            }
        }

        /** A string value that may also be null. */
        private fun JsonIn.optionalString(): String? = if (peek() == JsonToken.NULL) { nextNull(); null } else nextString()

        private fun JsonIn.icons(decode: (String) -> ByteArray): Map<String, ByteArray> {
            if (peek() == JsonToken.NULL) { nextNull(); return emptyMap() }
            val map = LinkedHashMap<String, ByteArray>()
            beginObject()
            while (hasNext()) { val name = nextName(); map[name] = decode(nextString()) }
            endObject()
            return map
        }
    }
}

/**
 * Writes a backup's JSON table by table, so a caller holding the data in pieces (the repository, which reads one table at
 * a time) never needs all of it together. The sections come in the order of [BackupContents.writeJson]; a table with no
 * rows is written as an empty array. The format is the one backups have always had.
 */
internal class BackupWriter(private val o: JsonOut, private val encodeBytes: (ByteArray) -> String) {

    fun begin(createdAt: Long, monthStartDay: Int, currency: String?, accountArrangement: String?) {
        o.beginObject()
            .pair("version", BackupContents.VERSION)
            .pair("createdAt", createdAt)
            .pair("monthStartDay", monthStartDay)
            .opt("currency", currency)
            .opt("accountArrangement", accountArrangement)
    }

    fun end() { o.endObject(); o.flush() }

    private fun <T> table(name: String, items: List<T>, fill: JsonOut.(T) -> Unit) {
        o.name(name).beginArray()
        items.forEach { o.beginObject(); o.fill(it); o.endObject() }
        o.endArray()
    }

    fun groups(items: List<GroupEntity>) = table("groups", items) { pair("id", it.id).pair("name", it.name).pair("sortOrder", it.sortOrder) }

    fun accounts(items: List<AccountEntity>) = table("accounts", items) {
        pair("id", it.id).pair("name", it.name).pair("type", it.type).opt("groupId", it.groupId).pair("openingBalance", it.openingBalance)
            .opt("statementDay", it.statementDay).opt("dueDay", it.dueDay).opt("payFromId", it.payFromId).opt("creditLimit", it.creditLimit)
            .pair("archived", it.archived).pair("sortOrder", it.sortOrder).opt("externalId", it.externalId).opt("last4", it.last4)
            .opt("maturityDay", it.maturityDay).opt("closedDay", it.closedDay)
    }

    fun categories(items: List<CategoryEntity>) = table("categories", items) {
        pair("id", it.id).pair("name", it.name).pair("kind", it.kind).opt("parentId", it.parentId).pair("icon", it.icon).pair("color", it.color)
            .pair("countsTowardDaily", it.countsTowardDaily).pair("hidden", it.hidden).pair("sortOrder", it.sortOrder).opt("externalId", it.externalId)
    }

    /** The transactions table can be long: call [beginTransactions], then [transactions] once or in pages, then [endTransactions]. */
    fun beginTransactions() { o.name("transactions").beginArray() }

    fun transactions(items: List<TransactionEntity>) {
        items.forEach {
            o.beginObject()
                .pair("id", it.id).pair("kind", it.kind).pair("amount", it.amount).opt("accountId", it.accountId).opt("toAccountId", it.toAccountId)
                .opt("categoryId", it.categoryId).opt("personId", it.personId).pair("epochMillis", it.epochMillis).pair("note", it.note)
                .opt("myShare", it.myShare).opt("externalId", it.externalId)
            o.endObject()
        }
    }

    fun endTransactions() { o.endArray() }

    fun budgets(items: List<BudgetEntity>) = table("budgets", items) { pair("categoryId", it.categoryId).pair("month", it.month).pair("amount", it.amount) }

    fun people(items: List<PersonEntity>) = table("people", items) { pair("id", it.id).pair("name", it.name).pair("archived", it.archived) }

    fun loans(items: List<LoanEntryEntity>) = table("loans", items) {
        pair("id", it.id).pair("personId", it.personId).pair("amount", it.amount).pair("epochMillis", it.epochMillis).pair("note", it.note).opt("txId", it.txId)
    }

    fun bills(items: List<RecurringEntity>) = table("recurring", items) {
        pair("id", it.id).pair("name", it.name).pair("categoryId", it.categoryId).pair("accountId", it.accountId).pair("dayOfMonth", it.dayOfMonth)
            .opt("fixedAmount", it.fixedAmount).pair("active", it.active).pair("handledMonth", it.handledMonth)
    }

    fun holdings(items: List<HoldingEntity>) = table("holdings", items) {
        pair("id", it.id).pair("accountId", it.accountId).pair("ticker", it.ticker).pair("microShares", it.microShares).pair("price", it.price)
            .pair("priceMillis", it.priceMillis).opt("avgCost", it.avgCost)
    }

    fun trades(items: List<TradeEntity>) = table("trades", items) {
        pair("id", it.id).pair("transactionId", it.transactionId).pair("ticker", it.ticker).pair("microShares", it.microShares).pair("price", it.price)
    }

    fun loanTerms(items: List<LoanTermsEntity>) = table("loanTerms", items) {
        pair("accountId", it.accountId).pair("type", it.type).pair("profit", it.profit).pair("rate", it.rate).pair("payment", it.payment)
            .opt("termMonths", it.termMonths).opt("originalAmount", it.originalAmount).opt("startDay", it.startDay)
            .pair("nextPaymentDay", it.nextPaymentDay).pair("dueDay", it.dueDay).pair("escrow", it.escrow).pair("fee", it.fee)
            .pair("earlyCharge", it.earlyCharge).opt("interestCategoryId", it.interestCategoryId).opt("payFromId", it.payFromId)
            .pair("countAllAsSpending", it.countAllAsSpending).opt("principalCategoryId", it.principalCategoryId)
    }

    fun loanChanges(items: List<LoanTermChangeEntity>) = table("loanChanges", items) {
        pair("id", it.id).pair("accountId", it.accountId).pair("effectiveDay", it.effectiveDay).opt("rate", it.rate)
            .opt("payment", it.payment).opt("termMonths", it.termMonths).opt("type", it.type)
    }

    fun loanPayments(items: List<LoanPaymentEntity>) = table("loanPayments", items) {
        pair("id", it.id).pair("accountId", it.accountId).pair("day", it.day).pair("amount", it.amount)
            .opt("principalEntryId", it.principalEntryId).opt("interestEntryId", it.interestEntryId).opt("otherEntryId", it.otherEntryId)
            .pair("escrow", it.escrow).pair("charge", it.charge).pair("edited", it.edited).pair("estimate", it.estimate).pair("scheduled", it.scheduled)
            .opt("countAllAsSpending", it.countAllAsSpending).opt("principalCategoryId", it.principalCategoryId)
    }

    fun investmentTransfers(items: List<InvestmentTransferEntity>) = table("investmentTransfers", items) {
        pair("id", it.id).pair("transactionId", it.transactionId).pair("accountId", it.accountId).pair("amount", it.amount).pair("epochMillis", it.epochMillis)
    }

    /** Category photos, one at a time: call [beginIcons], [icon] for each, then [endIcons]. */
    fun beginIcons() { o.name("icons").beginObject() }

    fun icon(name: String, bytes: ByteArray) { o.pair(name, encodeBytes(bytes)) }

    fun endIcons() { o.endObject() }
}
