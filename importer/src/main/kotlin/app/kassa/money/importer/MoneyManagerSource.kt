package app.kassa.money.importer

/**
 * Raw rows from a Money Manager (Realbyte) backup. The .mmbak file is a SQLite database; the Android app
 * reads it with the platform SQLite API and hands the rows over through [MoneyManagerSource], so the
 * mapping below stays plain Kotlin and is unit tested on the JVM.
 *
 * Column notes, from reading a real backup (schema user_version 19):
 *  - INOUTCOME.DO_TYPE: 0 income, 1 expense, 3 transfer out, 4 transfer in. A transfer is stored twice;
 *    the two rows share txUidTrans and the "out" row carries both accounts.
 *  - INOUTCOME.ZMONEY is decimal text, ZDATE is epoch milliseconds as text.
 *  - ZCATEGORY.TYPE: 0 income, 1 expense. pUid points at the parent; C_IS_DEL = 1 means deleted.
 *  - ASSETS.CARD_DAY_FIN is the statement day, CARD_DAY_PAY the due day, ZDATA the starting amount.
 *  - BUDGET_AMOUNT.BUDGET_PERIOD is 0 for the default amount, or yyyyMM for a one-month override.
 *  - INOUTCOME.AMOUNT_ACCOUNT is the amount in the account's own currency when the entry was made in another one.
 *  - ASSETS.cardAssetUid is the account a card is paid from. Only the iPhone layout marks deleted accounts (ZISDEL).
 *
 * Everything past the five lists has a default, so a source that doesn't know it (an older backup) still works.
 */
interface MoneyManagerSource {
    fun groups(): List<MmGroup>
    fun assets(): List<MmAsset>
    fun categories(): List<MmCategory>
    fun transactions(): List<MmTransaction>
    fun budgets(): List<MmBudget>
    /** The uid of the backup's main currency, when it records one. */
    fun mainCurrencyUid(): String? = null
    /** Counts of what Kassa doesn't bring over, for the review. */
    fun extras(): MmExtras = MmExtras()
}

/** How many of each thing the backup has that Kassa leaves in Money Manager. Deleted rows aren't counted. */
data class MmExtras(
    val repeating: Int = 0,
    val bookmarks: Int = 0,
    val photos: Int = 0,
    /** Distinct installment plans (cardDivideUid) among the entries. */
    val installmentPlans: Int = 0,
    /** Entries with a description besides their short note. */
    val descriptions: Int = 0,
    val tags: Int = 0,
    val taggedEntries: Int = 0,
    val accountNotes: Int = 0,
    val notificationRules: Int = 0,
    val memos: Int = 0,
)

data class MmGroup(val uid: String, val name: String, val type: Int, val order: Int)

data class MmAsset(
    val uid: String,
    val name: String,
    val groupUid: String?,
    val order: Int,
    val statementDay: Int?,
    val dueDay: Int?,
    val startingAmount: String?,
    val currencyUid: String? = null,
    /** The account a card is paid from (cardAssetUid). */
    val payFromUid: String? = null,
    val deleted: Boolean = false,
)

data class MmCategory(
    val uid: String,
    val name: String,
    /** 0 income, 1 expense. */
    val type: Int,
    val parentUid: String?,
    val deleted: Boolean,
    val order: Int,
)

data class MmTransaction(
    val uid: String,
    /** 0 income, 1 expense, 3 transfer out, 4 transfer in. */
    val doType: Int,
    val assetUid: String,
    val toAssetUid: String?,
    val categoryUid: String?,
    val amount: String,
    val epochMillis: Long,
    val note: String?,
    val pairKey: String?,
    val currencyUid: String? = null,
    /** The amount in the account's currency (AMOUNT_ACCOUNT), when it was recorded. */
    val accountAmount: String? = null,
)

/** One amount row of a budget: [period] is 0 for the default or yyyyMM. [periodType] 6 is monthly. */
data class MmBudget(
    val categoryUid: String,
    val period: Int,
    val amount: Double,
    val isTotal: Boolean = false,
    val periodType: Int = MmBudget.MONTHLY,
    /** The budget this amount belongs to, so a budget with several months counts once. */
    val budgetUid: String = "",
) {
    companion object {
        const val MONTHLY = 6
    }
}
