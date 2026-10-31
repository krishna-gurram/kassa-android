package app.kassa.money.data.backup

import app.kassa.money.data.db.AccountEntity
import app.kassa.money.data.db.BudgetEntity
import app.kassa.money.data.db.CategoryEntity
import app.kassa.money.data.db.GroupEntity
import app.kassa.money.data.db.HoldingEntity
import app.kassa.money.data.db.LoanEntryEntity
import app.kassa.money.data.db.LoanPaymentEntity
import app.kassa.money.data.db.LoanTermChangeEntity
import app.kassa.money.data.db.LoanTermsEntity
import app.kassa.money.data.db.PersonEntity
import app.kassa.money.data.db.RecurringEntity
import app.kassa.money.data.db.TradeEntity
import app.kassa.money.data.db.TransactionEntity
import org.json.JSONArray
import org.json.JSONObject

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
) {
    /** One line for the confirm step before restoring. */
    val summary: String
        get() = "${transactions.size} entries, ${accounts.size} accounts, ${categories.size} categories"

    /**
     * The JSON inside a backup file. Field names are part of the file format: renaming one breaks every
     * existing backup, so add new fields instead.
     */
    fun toJson(encodeBytes: (ByteArray) -> String): String = JSONObject()
        .put("version", 1)
        .put("createdAt", createdAt)
        .put("monthStartDay", monthStartDay)
        .putOpt("currency", currency)
        .putOpt("accountArrangement", accountArrangement)
        .put("groups", array(groups) { put("id", it.id).put("name", it.name).put("sortOrder", it.sortOrder) })
        .put("accounts", array(accounts) {
            put("id", it.id).put("name", it.name).put("type", it.type).putOpt("groupId", it.groupId).put("openingBalance", it.openingBalance)
                .putOpt("statementDay", it.statementDay).putOpt("dueDay", it.dueDay).putOpt("payFromId", it.payFromId).putOpt("creditLimit", it.creditLimit)
                .put("archived", it.archived).put("sortOrder", it.sortOrder).putOpt("externalId", it.externalId).putOpt("last4", it.last4)
                .putOpt("maturityDay", it.maturityDay).putOpt("closedDay", it.closedDay)
        })
        .put("categories", array(categories) {
            put("id", it.id).put("name", it.name).put("kind", it.kind).putOpt("parentId", it.parentId).put("icon", it.icon).put("color", it.color)
                .put("countsTowardDaily", it.countsTowardDaily).put("hidden", it.hidden).put("sortOrder", it.sortOrder).putOpt("externalId", it.externalId)
        })
        .put("transactions", array(transactions) {
            put("id", it.id).put("kind", it.kind).put("amount", it.amount).putOpt("accountId", it.accountId).putOpt("toAccountId", it.toAccountId)
                .putOpt("categoryId", it.categoryId).putOpt("personId", it.personId).put("epochMillis", it.epochMillis).put("note", it.note)
                .putOpt("myShare", it.myShare).putOpt("externalId", it.externalId)
        })
        .put("budgets", array(budgets) { put("categoryId", it.categoryId).put("month", it.month).put("amount", it.amount) })
        .put("people", array(people) { put("id", it.id).put("name", it.name).put("archived", it.archived) })
        .put("loans", array(loans) {
            put("id", it.id).put("personId", it.personId).put("amount", it.amount).put("epochMillis", it.epochMillis).put("note", it.note).putOpt("txId", it.txId)
        })
        .put("recurring", array(bills) {
            put("id", it.id).put("name", it.name).put("categoryId", it.categoryId).put("accountId", it.accountId).put("dayOfMonth", it.dayOfMonth)
                .putOpt("fixedAmount", it.fixedAmount).put("active", it.active).put("handledMonth", it.handledMonth)
        })
        .put("holdings", array(holdings) {
            put("id", it.id).put("accountId", it.accountId).put("ticker", it.ticker).put("microShares", it.microShares).put("price", it.price).put("priceMillis", it.priceMillis).putOpt("avgCost", it.avgCost)
        })
        .put("trades", array(trades) {
            put("id", it.id).put("transactionId", it.transactionId).put("ticker", it.ticker).put("microShares", it.microShares).put("price", it.price)
        })
        .put("loanTerms", array(loanTerms) {
            put("accountId", it.accountId).put("type", it.type).put("profit", it.profit).put("rate", it.rate).put("payment", it.payment)
                .putOpt("termMonths", it.termMonths).putOpt("originalAmount", it.originalAmount).putOpt("startDay", it.startDay)
                .put("nextPaymentDay", it.nextPaymentDay).put("dueDay", it.dueDay).put("escrow", it.escrow).put("fee", it.fee)
                .put("earlyCharge", it.earlyCharge).putOpt("interestCategoryId", it.interestCategoryId).putOpt("payFromId", it.payFromId)
                .put("countAllAsSpending", it.countAllAsSpending).putOpt("principalCategoryId", it.principalCategoryId)
        })
        .put("loanChanges", array(loanChanges) {
            put("id", it.id).put("accountId", it.accountId).put("effectiveDay", it.effectiveDay).putOpt("rate", it.rate)
                .putOpt("payment", it.payment).putOpt("termMonths", it.termMonths).putOpt("type", it.type)
        })
        .put("loanPayments", array(loanPayments) {
            put("id", it.id).put("accountId", it.accountId).put("day", it.day).put("amount", it.amount)
                .putOpt("principalEntryId", it.principalEntryId).putOpt("interestEntryId", it.interestEntryId).putOpt("otherEntryId", it.otherEntryId)
                .put("escrow", it.escrow).put("charge", it.charge).put("edited", it.edited).put("estimate", it.estimate).put("scheduled", it.scheduled)
                .putOpt("countAllAsSpending", it.countAllAsSpending).putOpt("principalCategoryId", it.principalCategoryId)
        })
        .put("icons", JSONObject().also { o -> icons.forEach { (name, bytes) -> o.put(name, encodeBytes(bytes)) } })
        .toString()

    companion object {
        /** Reads the JSON of any backup Kassa has written. Throws [BackupFile.NotABackup] for anything else. */
        fun fromJson(text: String, decodeBytes: (String) -> ByteArray): BackupContents = try {
            val j = JSONObject(text)
            BackupContents(
                createdAt = j.optLong("createdAt"),
                monthStartDay = j.optInt("monthStartDay", 1),
                currency = j.optString("currency").ifEmpty { null },
                accountArrangement = j.optString("accountArrangement").ifEmpty { null },
                groups = j.rows("groups") { GroupEntity(getLong("id"), getString("name"), getInt("sortOrder")) },
                accounts = j.rows("accounts") {
                    AccountEntity(getLong("id"), getString("name"), getString("type"), longOrNull("groupId"), getLong("openingBalance"),
                        intOrNull("statementDay"), intOrNull("dueDay"), longOrNull("payFromId"), longOrNull("creditLimit"),
                        getBoolean("archived"), getInt("sortOrder"), stringOrNull("externalId"), stringOrNull("last4"),
                        intOrNull("maturityDay"), intOrNull("closedDay"))
                },
                categories = j.rows("categories") {
                    CategoryEntity(getLong("id"), getString("name"), getString("kind"), longOrNull("parentId"), getString("icon"), getInt("color"),
                        getBoolean("countsTowardDaily"), getBoolean("hidden"), getInt("sortOrder"), stringOrNull("externalId"))
                },
                transactions = j.rows("transactions") {
                    TransactionEntity(getLong("id"), getString("kind"), getLong("amount"), longOrNull("accountId"), longOrNull("toAccountId"),
                        longOrNull("categoryId"), longOrNull("personId"), getLong("epochMillis"), getString("note"), longOrNull("myShare"), stringOrNull("externalId"))
                },
                budgets = j.rows("budgets") { BudgetEntity(getLong("categoryId"), getInt("month"), getLong("amount")) },
                people = j.rows("people") { PersonEntity(getLong("id"), getString("name"), getBoolean("archived")) },
                loans = j.rows("loans") { LoanEntryEntity(getLong("id"), getLong("personId"), getLong("amount"), getLong("epochMillis"), getString("note"), longOrNull("txId")) },
                bills = j.rows("recurring") {
                    RecurringEntity(getLong("id"), getString("name"), getLong("categoryId"), getLong("accountId"), getInt("dayOfMonth"),
                        longOrNull("fixedAmount"), getBoolean("active"), getInt("handledMonth"))
                },
                holdings = j.rows("holdings") {
                    HoldingEntity(getLong("id"), getLong("accountId"), getString("ticker"), getLong("microShares"), getLong("price"), getLong("priceMillis"), longOrNull("avgCost"))
                },
                trades = j.rows("trades") { TradeEntity(getLong("id"), getLong("transactionId"), getString("ticker"), getLong("microShares"), getLong("price")) },
                loanTerms = j.rows("loanTerms") {
                    LoanTermsEntity(getLong("accountId"), getString("type"), getBoolean("profit"), getLong("rate"), getLong("payment"),
                        intOrNull("termMonths"), longOrNull("originalAmount"), intOrNull("startDay"), getInt("nextPaymentDay"), getInt("dueDay"),
                        getLong("escrow"), getLong("fee"), getLong("earlyCharge"), longOrNull("interestCategoryId"), longOrNull("payFromId"),
                        optBoolean("countAllAsSpending", false), longOrNull("principalCategoryId"))
                },
                loanChanges = j.rows("loanChanges") {
                    LoanTermChangeEntity(getLong("id"), getLong("accountId"), getInt("effectiveDay"), longOrNull("rate"), longOrNull("payment"),
                        intOrNull("termMonths"), stringOrNull("type"))
                },
                loanPayments = j.rows("loanPayments") {
                    LoanPaymentEntity(getLong("id"), getLong("accountId"), getInt("day"), getLong("amount"), longOrNull("principalEntryId"),
                        longOrNull("interestEntryId"), longOrNull("otherEntryId"), getLong("escrow"), getLong("charge"), getBoolean("edited"),
                        getLong("estimate"), getBoolean("scheduled"),
                        booleanOrNull("countAllAsSpending"), longOrNull("principalCategoryId"))
                },
                icons = j.optJSONObject("icons")?.let { o -> o.keys().asSequence().associateWith { decodeBytes(o.getString(it)) } }.orEmpty(),
            )
        } catch (e: org.json.JSONException) {
            throw BackupFile.NotABackup()
        }

        private fun <T> array(items: List<T>, fill: JSONObject.(T) -> JSONObject): JSONArray =
            JSONArray().also { a -> items.forEach { a.put(JSONObject().fill(it)) } }

        private fun <T> JSONObject.rows(name: String, map: JSONObject.() -> T): List<T> {
            val a = optJSONArray(name) ?: return emptyList()
            return (0 until a.length()).map { a.getJSONObject(it).map() }
        }

        private fun JSONObject.longOrNull(k: String): Long? = if (has(k) && !isNull(k)) getLong(k) else null
        private fun JSONObject.intOrNull(k: String): Int? = if (has(k) && !isNull(k)) getInt(k) else null
        private fun JSONObject.booleanOrNull(k: String): Boolean? = if (has(k) && !isNull(k)) getBoolean(k) else null
        private fun JSONObject.stringOrNull(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
    }
}
