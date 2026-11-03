package app.kassa.money.backup

/**
 * The JSON inside a .kassa backup, read and written exactly as Android's `BackupContents.readJson` and `BackupWriter` do
 * (docs/BACKUP_FORMAT.md): fields in the same order, optional ones left out when null, unknown keys skipped, missing tables
 * empty, a missing required field or malformed JSON is [NotABackup], and a version above [FILE_VERSION] is [NewerVersion]
 * (also when the rest of the text can't be read, if the version came first).
 */
object BackupJson {
    /** The file format's version. A file from a newer one is refused. */
    const val FILE_VERSION = 1

    /** Reads a backup's JSON. Throws only [BackupFormatException]s. */
    fun parse(text: String): BackupData {
        val reader = JsonReader(text)
        val doc = try {
            reader.parseDocument()
        } catch (e: JsonMalformed) {
            throw if ((reader.topVersion ?: 0L) > FILE_VERSION) NewerVersion() else NotABackup()
        }
        val top = (doc as? Map<*, *>) ?: throw NotABackup()
        try {
            return build(top)
        } catch (e: BackupFormatException) {
            throw e
        } catch (e: Exception) {
            // Not valid base64 in an icon, or anything else this reader didn't foresee: not a backup, never a crash.
            throw NotABackup()
        }
    }

    private fun bad(): Nothing = throw NotABackup()

    private fun build(top: Map<*, *>): BackupData {
        var createdAt = 0L
        var monthStartDay = 1
        var currency: String? = null
        var arrangement: String? = null
        var groups = emptyList<BackupGroup>()
        var accounts = emptyList<BackupAccount>()
        var categories = emptyList<BackupCategory>()
        var transactions = emptyList<BackupTransaction>()
        var budgets = emptyList<BackupBudget>()
        var people = emptyList<BackupPerson>()
        var loans = emptyList<BackupLoanEntry>()
        var bills = emptyList<BackupBill>()
        var holdings = emptyList<BackupHolding>()
        var trades = emptyList<BackupTrade>()
        var loanTerms = emptyList<BackupLoanTerms>()
        var loanChanges = emptyList<BackupLoanChange>()
        var loanPayments = emptyList<BackupLoanPayment>()
        var icons = emptyMap<String, ByteArray>()
        var transfers = emptyList<BackupInvestmentTransfer>()

        for ((key, value) in top) {
            when (key) {
                "version" -> {
                    val version = JsonValues.wholeNumber(value) ?: bad()
                    if (version < Int.MIN_VALUE || version > Int.MAX_VALUE) bad()
                    if (version > FILE_VERSION) throw NewerVersion()
                }
                "createdAt" -> createdAt = JsonValues.wholeNumber(value) ?: bad()
                "monthStartDay" -> monthStartDay = (JsonValues.wholeNumber(value) ?: bad()).let { if (it < Int.MIN_VALUE || it > Int.MAX_VALUE) bad() else it.toInt() }
                "currency" -> currency = optionalString(value)
                "accountArrangement" -> arrangement = optionalString(value)
                "groups" -> groups = rows(value) { BackupGroup(long("id"), string("name"), int("sortOrder")) }
                "accounts" -> accounts = rows(value) {
                    BackupAccount(long("id"), string("name"), string("type"), longOrNull("groupId"), long("openingBalance"),
                        statementDay = intOrNull("statementDay"), dueDay = intOrNull("dueDay"), payFromId = longOrNull("payFromId"),
                        creditLimit = longOrNull("creditLimit"), archived = boolean("archived"), sortOrder = int("sortOrder"),
                        externalId = stringOrNull("externalId"), last4 = stringOrNull("last4"),
                        maturityDay = intOrNull("maturityDay"), closedDay = intOrNull("closedDay"))
                }
                "categories" -> categories = rows(value) {
                    BackupCategory(long("id"), string("name"), string("kind"), longOrNull("parentId"), string("icon"), int("color"),
                        boolean("countsTowardDaily"), boolean("hidden"), int("sortOrder"), stringOrNull("externalId"))
                }
                "transactions" -> transactions = rows(value) {
                    BackupTransaction(long("id"), string("kind"), long("amount"), longOrNull("accountId"), longOrNull("toAccountId"),
                        longOrNull("categoryId"), longOrNull("personId"), long("epochMillis"), string("note"), longOrNull("myShare"),
                        stringOrNull("externalId"))
                }
                "budgets" -> budgets = rows(value) { BackupBudget(long("categoryId"), int("month"), long("amount")) }
                "people" -> people = rows(value) { BackupPerson(long("id"), string("name"), boolean("archived")) }
                "loans" -> loans = rows(value) {
                    BackupLoanEntry(long("id"), long("personId"), long("amount"), long("epochMillis"), string("note"), longOrNull("txId"))
                }
                "recurring" -> bills = rows(value) {
                    BackupBill(long("id"), string("name"), long("categoryId"), long("accountId"), int("dayOfMonth"),
                        longOrNull("fixedAmount"), boolean("active"), int("handledMonth"))
                }
                "holdings" -> holdings = rows(value) {
                    BackupHolding(long("id"), long("accountId"), string("ticker"), long("microShares"), long("price"), long("priceMillis"),
                        longOrNull("avgCost"))
                }
                "trades" -> trades = rows(value) {
                    BackupTrade(long("id"), long("transactionId"), string("ticker"), long("microShares"), long("price"))
                }
                "loanTerms" -> loanTerms = rows(value) {
                    BackupLoanTerms(long("accountId"), string("type"), boolean("profit"), long("rate"), long("payment"),
                        intOrNull("termMonths"), longOrNull("originalAmount"), intOrNull("startDay"), int("nextPaymentDay"), int("dueDay"),
                        long("escrow"), long("fee"), long("earlyCharge"), longOrNull("interestCategoryId"), longOrNull("payFromId"),
                        boolean("countAllAsSpending", false), longOrNull("principalCategoryId"))
                }
                "loanChanges" -> loanChanges = rows(value) {
                    BackupLoanChange(long("id"), long("accountId"), int("effectiveDay"), longOrNull("rate"), longOrNull("payment"),
                        intOrNull("termMonths"), stringOrNull("type"))
                }
                "loanPayments" -> loanPayments = rows(value) {
                    BackupLoanPayment(long("id"), long("accountId"), int("day"), long("amount"), longOrNull("principalEntryId"),
                        longOrNull("interestEntryId"), longOrNull("otherEntryId"), long("escrow"), long("charge"), boolean("edited"),
                        long("estimate"), boolean("scheduled"), booleanOrNull("countAllAsSpending"), longOrNull("principalCategoryId"))
                }
                "investmentTransfers" -> transfers = rows(value) {
                    BackupInvestmentTransfer(long("id"), long("transactionId"), long("accountId"), long("amount"), long("epochMillis"))
                }
                "icons" -> icons = iconsOf(value)
                else -> Unit // a key this reader doesn't know: skipped
            }
        }
        return BackupData(
            createdAt = createdAt, monthStartDay = monthStartDay, currency = currency?.ifEmpty { null },
            groups = groups, accounts = accounts, categories = categories, transactions = transactions, budgets = budgets,
            people = people, loans = loans, bills = bills, holdings = holdings, trades = trades,
            loanTerms = loanTerms, loanChanges = loanChanges, loanPayments = loanPayments, icons = icons,
            accountArrangement = arrangement?.ifEmpty { null }, investmentTransfers = transfers,
        )
    }

    /** A string value that may also be null; a number reads as its text. */
    private fun optionalString(v: Any?): String? = when (v) {
        null -> null
        is String -> v
        is Long, is Double -> v.toString()
        else -> bad()
    }

    /** A table: null or an array of objects. */
    private fun <T> rows(v: Any?, map: Row.() -> T): List<T> {
        if (v == null) return emptyList()
        if (v !is List<*>) bad()
        val list = ArrayList<T>(v.size)
        for (item in v) {
            if (item !is Map<*, *>) bad()
            list.add(Row(item).map())
        }
        return list
    }

    private fun iconsOf(v: Any?): Map<String, ByteArray> {
        if (v == null) return emptyMap()
        if (v !is Map<*, *>) bad()
        val map = LinkedHashMap<String, ByteArray>()
        for ((name, encoded) in v) {
            val text = when (encoded) {
                is String -> encoded
                is Long, is Double -> encoded.toString()
                else -> bad()
            }
            map[name as String] = Base64Text.decode(text)
        }
        return map
    }

    /**
     * One row of a table: its keys and scalar values. A missing key or a value of the wrong kind in a required field is
     * [NotABackup]; an optional field that is absent or null is null. Nested objects and arrays count as absent.
     */
    private class Row(private val values: Map<*, *>) {
        private fun get(k: String): Any? = when (val v = values[k]) {
            is List<*>, is Map<*, *> -> null
            else -> v
        }

        fun longOrNull(k: String): Long? = when (val v = get(k)) {
            null -> null
            is Long -> v
            is Double -> JsonValues.wholeDouble(v) ?: bad()
            is String -> v.trim().toLongOrNull() ?: bad()
            else -> bad()
        }

        fun long(k: String): Long = longOrNull(k) ?: bad()

        fun intOrNull(k: String): Int? = longOrNull(k)?.let { if (it < Int.MIN_VALUE || it > Int.MAX_VALUE) bad() else it.toInt() }

        fun int(k: String): Int = intOrNull(k) ?: bad()

        fun stringOrNull(k: String): String? = when (val v = get(k)) {
            null -> null
            is String -> v
            is Long, is Double -> v.toString()
            else -> bad()
        }

        fun string(k: String): String = stringOrNull(k) ?: bad()

        fun booleanOrNull(k: String): Boolean? = when (val v = get(k)) {
            null -> null
            is Boolean -> v
            "true" -> true
            "false" -> false
            else -> bad()
        }

        fun boolean(k: String): Boolean = booleanOrNull(k) ?: bad()

        fun boolean(k: String, default: Boolean): Boolean = booleanOrNull(k) ?: default
    }

    /** Writes a backup's JSON in the order Android writes it. */
    fun write(data: BackupData): String {
        val sb = StringBuilder()
        val o = JsonWriter(sb)
        o.beginObject()
            .pair("version", FILE_VERSION)
            .pair("createdAt", data.createdAt)
            .pair("monthStartDay", data.monthStartDay)
            .opt("currency", data.currency)
            .opt("accountArrangement", data.accountArrangement)

        table(o, "groups", data.groups) { pair("id", it.id).pair("name", it.name).pair("sortOrder", it.sortOrder) }
        table(o, "accounts", data.accounts) {
            pair("id", it.id).pair("name", it.name).pair("type", it.type).opt("groupId", it.groupId).pair("openingBalance", it.openingBalance)
                .opt("statementDay", it.statementDay).opt("dueDay", it.dueDay).opt("payFromId", it.payFromId).opt("creditLimit", it.creditLimit)
                .pair("archived", it.archived).pair("sortOrder", it.sortOrder).opt("externalId", it.externalId).opt("last4", it.last4)
                .opt("maturityDay", it.maturityDay).opt("closedDay", it.closedDay)
        }
        table(o, "categories", data.categories) {
            pair("id", it.id).pair("name", it.name).pair("kind", it.kind).opt("parentId", it.parentId).pair("icon", it.icon).pair("color", it.color)
                .pair("countsTowardDaily", it.countsTowardDaily).pair("hidden", it.hidden).pair("sortOrder", it.sortOrder).opt("externalId", it.externalId)
        }
        table(o, "transactions", data.transactions) {
            pair("id", it.id).pair("kind", it.kind).pair("amount", it.amount).opt("accountId", it.accountId).opt("toAccountId", it.toAccountId)
                .opt("categoryId", it.categoryId).opt("personId", it.personId).pair("epochMillis", it.epochMillis).pair("note", it.note)
                .opt("myShare", it.myShare).opt("externalId", it.externalId)
        }
        table(o, "budgets", data.budgets) { pair("categoryId", it.categoryId).pair("month", it.month).pair("amount", it.amount) }
        table(o, "people", data.people) { pair("id", it.id).pair("name", it.name).pair("archived", it.archived) }
        table(o, "loans", data.loans) {
            pair("id", it.id).pair("personId", it.personId).pair("amount", it.amount).pair("epochMillis", it.epochMillis).pair("note", it.note).opt("txId", it.txId)
        }
        table(o, "recurring", data.bills) {
            pair("id", it.id).pair("name", it.name).pair("categoryId", it.categoryId).pair("accountId", it.accountId).pair("dayOfMonth", it.dayOfMonth)
                .opt("fixedAmount", it.fixedAmount).pair("active", it.active).pair("handledMonth", it.handledMonth)
        }
        table(o, "holdings", data.holdings) {
            pair("id", it.id).pair("accountId", it.accountId).pair("ticker", it.ticker).pair("microShares", it.microShares).pair("price", it.price)
                .pair("priceMillis", it.priceMillis).opt("avgCost", it.avgCost)
        }
        table(o, "trades", data.trades) {
            pair("id", it.id).pair("transactionId", it.transactionId).pair("ticker", it.ticker).pair("microShares", it.microShares).pair("price", it.price)
        }
        table(o, "loanTerms", data.loanTerms) {
            pair("accountId", it.accountId).pair("type", it.type).pair("profit", it.profit).pair("rate", it.rate).pair("payment", it.payment)
                .opt("termMonths", it.termMonths).opt("originalAmount", it.originalAmount).opt("startDay", it.startDay)
                .pair("nextPaymentDay", it.nextPaymentDay).pair("dueDay", it.dueDay).pair("escrow", it.escrow).pair("fee", it.fee)
                .pair("earlyCharge", it.earlyCharge).opt("interestCategoryId", it.interestCategoryId).opt("payFromId", it.payFromId)
                .pair("countAllAsSpending", it.countAllAsSpending).opt("principalCategoryId", it.principalCategoryId)
        }
        table(o, "loanChanges", data.loanChanges) {
            pair("id", it.id).pair("accountId", it.accountId).pair("effectiveDay", it.effectiveDay).opt("rate", it.rate)
                .opt("payment", it.payment).opt("termMonths", it.termMonths).opt("type", it.type)
        }
        table(o, "loanPayments", data.loanPayments) {
            pair("id", it.id).pair("accountId", it.accountId).pair("day", it.day).pair("amount", it.amount)
                .opt("principalEntryId", it.principalEntryId).opt("interestEntryId", it.interestEntryId).opt("otherEntryId", it.otherEntryId)
                .pair("escrow", it.escrow).pair("charge", it.charge).pair("edited", it.edited).pair("estimate", it.estimate).pair("scheduled", it.scheduled)
                .opt("countAllAsSpending", it.countAllAsSpending).opt("principalCategoryId", it.principalCategoryId)
        }
        table(o, "investmentTransfers", data.investmentTransfers) {
            pair("id", it.id).pair("transactionId", it.transactionId).pair("accountId", it.accountId).pair("amount", it.amount).pair("epochMillis", it.epochMillis)
        }

        o.name("icons").beginObject()
        for ((name, bytes) in data.icons) o.pair(name, Base64Text.encode(bytes))
        o.endObject()
        o.endObject()
        return sb.toString()
    }

    private fun <T> table(o: JsonWriter, name: String, items: List<T>, fill: JsonWriter.(T) -> Unit) {
        o.name(name).beginArray()
        for (item in items) { o.beginObject(); o.fill(item); o.endObject() }
        o.endArray()
    }
}
