package app.kassa.money.importer

/**
 * The queries that read a Money Manager backup, and the reader that turns their answers into a [MoneyManagerSource].
 * The queries are the ones Android's `AndroidMoneyManagerSource` runs, word for word; the platform only runs them.
 * Money Manager for iPhone saves the same data in Core Data tables (ZASSET, ZINOUTCOME...), hence [iphone].
 */
object MoneyManagerSql {

    /** True when [tables] (the names in sqlite_master) are Money Manager's Android layout. */
    fun isAndroidLayout(tables: List<String>): Boolean = has(tables, "INOUTCOME", "ASSETS", "ZCATEGORY")

    /** True when [tables] are the iPhone layout (and not the Android one). */
    fun isIphoneLayout(tables: List<String>): Boolean = !isAndroidLayout(tables) && has(tables, "ZINOUTCOME", "ZASSET", "ZASSETGROUP", "ZCATEGORY")

    private fun has(tables: List<String>, vararg wanted: String): Boolean = wanted.all { it in tables }

    /** Every query to run, in any order. A required one that fails means the file can't be read. */
    fun queries(iphone: Boolean): List<SqlQuery> {
        val list = mutableListOf<SqlQuery>()
        fun need(name: String, sql: String) { list += SqlQuery(name, sql, false) }
        fun maybe(name: String, sql: String) { list += SqlQuery(name, sql, true) }
        fun column(name: String, table: String, uid: String, col: String) =
            maybe(name, "SELECT $uid, $col FROM $table WHERE $col IS NOT NULL")
        if (iphone) {
            need("groups", "SELECT ZUID, ZASSETGROUPNAME, ZSTATUS, ZORDER FROM ZASSETGROUP WHERE COALESCE(ZISDEL, 0) = 0")
            need("assets", "SELECT ZUID, ZNICNAME, ZGROUPUID, ZORDER, ZCARD_DAYFIN, ZCARD_DAYPAY, ZZDATA FROM ZASSET")
            column("assetCurrency", "ZASSET", "ZUID", "ZCURRENCYUID")
            column("assetPayFrom", "ZASSET", "ZUID", "ZCARDASSETUID")
            column("assetDeleted", "ZASSET", "ZUID", "ZISDEL")
            need("categories", "SELECT ZUID, ZNAME, ZDOTYPE, ZPUID, ZISDEL, ZORDER FROM ZCATEGORY")
            // ZDATE is Core Data time: seconds since 2001-01-01. ZMONEY is empty on iPhone; the amount is ZAMOUNT.
            need(
                "transactions",
                """SELECT ZUID, ZDO_TYPE, ZASSETUID, ZTOASSETUID, ZCATEGORYUID, COALESCE(ZMONEY, ZAMOUNT), ZDATE, ZCONTENT, ZTXUIDTRANS
           FROM ZINOUTCOME WHERE COALESCE(ZISDEL, 0) = 0""",
            )
            column("txCurrency", "ZINOUTCOME", "ZUID", "ZCURRENCYUID")
            column("txAccountAmount", "ZINOUTCOME", "ZUID", "ZAMOUNTACCOUNT")
            need(
                "budgets",
                """SELECT b.ZTARGETUID, a.ZBUDGETPERIOD, a.ZAMOUNT, b.ZUID FROM ZBUDGET b JOIN ZBUDGETAMOUNT a ON a.ZBUDGETUID = b.ZUID
               WHERE COALESCE(a.ZISDEL, 0) = 0 AND COALESCE(b.ZISDEL, 0) = 0""",
            )
            column("budgetTotal", "ZBUDGET", "ZUID", "ZTOTALTYPEINT")
            column("budgetPeriod", "ZBUDGET", "ZUID", "ZPERIODTYPEINT")
            maybe("mainCurrencyUid", "SELECT ZUID FROM ZCURRENCY WHERE ZISMAINCURRENCY = 1 AND COALESCE(ZISDEL, 0) = 0")
            maybe("mainCurrency", "SELECT ZISO FROM ZCURRENCY WHERE ZISMAINCURRENCY = 1 AND COALESCE(ZISDEL, 0) = 0")
            maybe("n.repeating", "SELECT COUNT(*) FROM ZREPEATTRANSACTION WHERE COALESCE(ZISDEL, 0) = 0")
            maybe("n.bookmarks", "SELECT COUNT(*) FROM ZFAVTRANSACTION WHERE COALESCE(ZISDEL, 0) = 0")
            maybe("n.photos", "SELECT COUNT(*) FROM ZPHOTO WHERE COALESCE(ZISDEL, 0) = 0")
            maybe("n.installmentPlans", "SELECT COUNT(DISTINCT ZCARDDIVIDEUID) FROM ZINOUTCOME WHERE COALESCE(ZISDEL, 0) = 0 AND TRIM(COALESCE(ZCARDDIVIDEUID, '')) NOT IN ('', '0')")
            maybe("n.descriptions", "SELECT COUNT(*) FROM ZINOUTCOME WHERE COALESCE(ZISDEL, 0) = 0 AND TRIM(COALESCE(ZMEMO, '')) <> ''")
            maybe("n.tags", "SELECT COUNT(DISTINCT ZTAGUID) FROM ZTXTAG WHERE COALESCE(ZISDEL, 0) = 0")
            maybe("n.taggedEntries", "SELECT COUNT(DISTINCT ZTXUID) FROM ZTXTAG WHERE COALESCE(ZISDEL, 0) = 0")
            maybe("n.accountNotes", "SELECT COUNT(*) FROM ZASSET WHERE COALESCE(ZISDEL, 0) = 0 AND TRIM(COALESCE(ZMEMO, '')) <> ''")
            maybe("n.notificationRules", "SELECT COUNT(*) FROM ZMESSAGEMACRO2 WHERE ZUSEYN = 'Y' AND TRIM(COALESCE(ZCONASSETUID, '')) NOT IN ('', '0')")
            maybe("n.memos", "SELECT COUNT(*) FROM ZMEMO WHERE COALESCE(ZISDEL, 0) = 0")
        } else {
            need("groups", "SELECT uid, ACC_GROUP_NAME, TYPE, ORDERSEQ FROM ASSETGROUP WHERE IS_DEL IS NULL OR IS_DEL = 0")
            need("assets", "SELECT uid, NIC_NAME, groupUid, ORDERSEQ, CARD_DAY_FIN, CARD_DAY_PAY, ZDATA FROM ASSETS")
            column("assetCurrency", "ASSETS", "uid", "currencyUid")
            column("assetPayFrom", "ASSETS", "uid", "cardAssetUid")
            need("categories", "SELECT uid, NAME, TYPE, pUid, C_IS_DEL, ORDERSEQ FROM ZCATEGORY")
            need(
                "transactions",
                """SELECT uid, DO_TYPE, assetUid, toAssetUid, ctgUid, ZMONEY, ZDATE, ZCONTENT, txUidTrans
           FROM INOUTCOME WHERE IS_DEL IS NULL OR IS_DEL = 0""",
            )
            column("txCurrency", "INOUTCOME", "uid", "currencyUid")
            column("txAccountAmount", "INOUTCOME", "uid", "AMOUNT_ACCOUNT")
            need(
                "budgets",
                """SELECT b.targetUid, a.BUDGET_PERIOD, a.AMOUNT, b.uid FROM BUDGET b JOIN BUDGET_AMOUNT a ON a.budgetUid = b.uid
               WHERE COALESCE(a.IS_DEL, 0) = 0 AND COALESCE(b.IS_DEL, 0) = 0""",
            )
            column("budgetTotal", "BUDGET", "uid", "IS_TOTAL")
            column("budgetPeriod", "BUDGET", "uid", "PERIOD_TYPE")
            maybe("mainCurrencyUid", "SELECT uid FROM CURRENCY WHERE IS_MAIN_CURRENCY = 1 AND COALESCE(IS_DEL, 0) = 0")
            maybe("mainCurrency", "SELECT ISO FROM CURRENCY WHERE IS_MAIN_CURRENCY = 1 AND COALESCE(IS_DEL, 0) = 0")
            maybe("n.repeating", "SELECT COUNT(*) FROM REPEATTRANSACTION WHERE COALESCE(IS_DEL, 0) = 0")
            maybe("n.bookmarks", "SELECT COUNT(*) FROM FAVTRANSACTION WHERE COALESCE(IS_DEL, 0) = 0")
            maybe("n.photos", "SELECT COUNT(*) FROM PHOTO WHERE COALESCE(IS_DEL, 0) = 0")
            maybe("n.installmentPlans", "SELECT COUNT(DISTINCT cardDivideUid) FROM INOUTCOME WHERE COALESCE(IS_DEL, 0) = 0 AND TRIM(COALESCE(cardDivideUid, '')) NOT IN ('', '0')")
            maybe("n.descriptions", "SELECT COUNT(*) FROM INOUTCOME WHERE COALESCE(IS_DEL, 0) = 0 AND TRIM(COALESCE(ZDATA, '')) <> ''")
            maybe("n.tags", "SELECT COUNT(DISTINCT tagUid) FROM TX_TAG WHERE COALESCE(isDel, 0) = 0")
            maybe("n.taggedEntries", "SELECT COUNT(DISTINCT txUid) FROM TX_TAG WHERE COALESCE(isDel, 0) = 0")
            maybe("n.accountNotes", "SELECT COUNT(*) FROM ASSETS WHERE TRIM(COALESCE(ZDATA1, '')) <> ''")
            maybe("n.notificationRules", "SELECT COUNT(*) FROM MESSAGEMACRO2 WHERE USEYN = 'Y' AND TRIM(COALESCE(conAssetUid, '')) NOT IN ('', '0')")
            maybe("n.memos", "SELECT COUNT(*) FROM MEMO WHERE COALESCE(isDel, 0) = 0")
        }
        return list
    }
}

/** A [MoneyManagerSource] over the answers to [MoneyManagerSql.queries]. Throws IllegalStateException when a required query has no answer. */
class MoneyManagerReader(private val results: SqlResults, private val iphone: Boolean) : MoneyManagerSource {

    /** One of the "uid, value" column queries as a map; nothing when an older backup lacks the column. */
    private fun column(name: String, decimal: Boolean = false): Map<String, String> {
        val out = HashMap<String, String>()
        for (r in results.maybe(name)) {
            val key = r.str(0) ?: continue
            val value = (if (decimal) r.decimal(1) else r.str(1)) ?: continue
            out[key] = value
        }
        return out
    }

    override fun groups(): List<MmGroup> = results.need("groups").map { MmGroup(it.str(0)!!, it.str(1).orEmpty(), it.int(2) ?: 7, it.int(3) ?: 0) }

    override fun assets(): List<MmAsset> {
        val base = results.need("assets").map { MmAsset(it.str(0)!!, it.str(1).orEmpty(), it.str(2), it.int(3) ?: 0, it.int(4), it.int(5), it.str(6)) }
        val currency = column("assetCurrency")
        val payFrom = column("assetPayFrom")
        // Only the iPhone layout marks deleted accounts.
        val deleted = if (iphone) column("assetDeleted") else emptyMap()
        return base.map { a ->
            a.copy(
                currencyUid = currency[a.uid]?.takeIf { it.isNotBlank() },
                payFromUid = payFrom[a.uid]?.takeIf { it.isNotBlank() && it != "0" },
                deleted = deleted[a.uid]?.trim()?.toDoubleOrNull() == 1.0,
            )
        }
    }

    override fun categories(): List<MmCategory> =
        results.need("categories").map { MmCategory(it.str(0)!!, it.str(1).orEmpty(), it.int(2) ?: 1, it.str(3), it.int(4) == 1, it.int(5) ?: 0) }

    override fun transactions(): List<MmTransaction> {
        val rows = results.need("transactions").map {
            if (iphone) MmTransaction(
                uid = it.str(0)!!, doType = it.int(1) ?: -1, assetUid = it.str(2).orEmpty(), toAssetUid = it.str(3),
                categoryUid = it.str(4), amount = it.decimal(5),
                epochMillis = if (it.isNull(6)) 0L else coreDataMillis(it.double(6)),
                note = it.str(7), pairKey = it.str(8),
            ) else MmTransaction(
                uid = it.str(0)!!, doType = it.int(1) ?: -1, assetUid = it.str(2).orEmpty(), toAssetUid = it.str(3),
                categoryUid = it.str(4), amount = it.str(5) ?: "0", epochMillis = it.str(6)?.toLongOrNull() ?: 0L,
                note = it.str(7), pairKey = it.str(8),
            )
        }
        val currency = column("txCurrency")
        val amount = column("txAccountAmount", decimal = true)
        if (currency.isEmpty() && amount.isEmpty()) return rows
        return rows.map { it.copy(currencyUid = currency[it.uid]?.takeIf { c -> c.isNotBlank() }, accountAmount = amount[it.uid]) }
    }

    override fun budgets(): List<MmBudget> {
        val rows = results.need("budgets").map { MmBudget(it.str(0).orEmpty(), it.int(1) ?: 0, it.double(2), budgetUid = it.str(3).orEmpty()) }
        val total = column("budgetTotal")
        val period = column("budgetPeriod")
        return rows.map { b ->
            b.copy(
                isTotal = total[b.budgetUid]?.trim()?.toDoubleOrNull()?.let { it != 0.0 } ?: false,
                periodType = period[b.budgetUid]?.trim()?.toDoubleOrNull()?.toInt() ?: MmBudget.MONTHLY,
            )
        }
    }

    override fun mainCurrencyUid(): String? = results.maybe("mainCurrencyUid").firstOrNull()?.str(0)

    /** The backup's main currency code (like AED), when the backup records one. */
    fun mainCurrency(): String? = results.maybe("mainCurrency").firstOrNull()?.str(0)

    private fun count(name: String): Int = results.maybe(name).firstOrNull()?.let { if (it.isNull(0)) 0 else it.long(0).toInt() } ?: 0

    override fun extras(): MmExtras = MmExtras(
        repeating = count("n.repeating"), bookmarks = count("n.bookmarks"), photos = count("n.photos"),
        installmentPlans = count("n.installmentPlans"), descriptions = count("n.descriptions"), tags = count("n.tags"),
        taggedEntries = count("n.taggedEntries"), accountNotes = count("n.accountNotes"),
        notificationRules = count("n.notificationRules"), memos = count("n.memos"),
    )

    companion object {
        /** Seconds from 1970-01-01 to 2001-01-01, where Apple's Core Data dates start. */
        private const val CORE_DATA_EPOCH = 978_307_200.0

        /** Core Data seconds as epoch milliseconds, rounded half up like Math.round. */
        fun coreDataMillis(seconds: Double): Long = kotlin.math.floor((seconds + CORE_DATA_EPOCH) * 1000 + 0.5).toLong()
    }
}
