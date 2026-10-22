package dev.krishnagurram.kassa.importer

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
 */
interface MoneyManagerSource {
    fun groups(): List<MmGroup>
    fun assets(): List<MmAsset>
    fun categories(): List<MmCategory>
    fun transactions(): List<MmTransaction>
    fun budgets(): List<MmBudget>
}

data class MmGroup(val uid: String, val name: String, val type: Int, val order: Int)

data class MmAsset(
    val uid: String,
    val name: String,
    val groupUid: String?,
    val order: Int,
    val statementDay: Int?,
    val dueDay: Int?,
    val startingAmount: String?,
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
)

data class MmBudget(val categoryUid: String, val period: Int, val amount: Double)
