package app.kassa.money.importer

/**
 * What the Cashew importer reads from a Cashew backup. Cashew (an open-source budgeting app) saves its backup as a
 * SQLite database; the platform side opens the file and hands over these rows, so the mapping itself stays plain
 * Kotlin with no Android code. Only the file layout is used here; none of Cashew's code.
 *
 * Amounts are plain decimal text in the account's own currency, negative for money leaving the account. Columns a
 * given Cashew version doesn't have arrive as null or their default.
 */
interface CashewSource {
    fun wallets(): List<CwWallet>
    fun categories(): List<CwCategory>
    fun transactions(): List<CwTransaction>
    fun objectives(): List<CwObjective>
    fun budgets(): List<CwBudget>
    fun categoryLimits(): List<CwCategoryLimit>
    /** The account Cashew treats as primary (its currency is the backup's main one), when the backup says. */
    fun primaryWalletPk(): String?
}

data class CwWallet(
    val pk: String,
    val name: String,
    /** Lower-case code like "usd"; null means the primary currency. */
    val currency: String?,
    val order: Int,
)

data class CwCategory(
    val pk: String,
    val name: String,
    /** "0xffRRGGBB", or null when Cashew picks one from the theme. */
    val colour: String?,
    /** A bundled picture like "cutlery.png". */
    val iconName: String?,
    val emoji: String?,
    val order: Int,
    val income: Boolean,
    /** Set for a subcategory: its main category. */
    val mainCategoryPk: String?,
)

data class CwTransaction(
    val pk: String,
    /** The other half of a transfer between accounts. */
    val pairedPk: String?,
    val title: String,
    val amount: String,
    val note: String,
    val categoryPk: String?,
    val subCategoryPk: String?,
    val walletPk: String?,
    val epochMillis: Long,
    val income: Boolean,
    /** Cashew's special type: 0 upcoming, 1 subscription, 2 repetitive, 3 lent, 4 borrowed; null for an ordinary entry. */
    val type: Int?,
    /** False for an upcoming entry that hasn't happened yet. */
    val paid: Boolean,
    /** True for an occurrence the user skipped. */
    val skipPaid: Boolean,
    /** 0 custom, 1 daily, 2 weekly, 3 monthly, 4 yearly. */
    val reoccurrence: Int?,
    val periodLength: Int?,
    val objectivePk: String?,
    val objectiveLoanPk: String?,
)

data class CwObjective(
    val pk: String,
    /** 0 goal, 1 loan. */
    val type: Int,
    val name: String,
    val amount: String,
    /** For a loan: true when you lent the money, false when you borrowed it. */
    val income: Boolean,
    val epochMillis: Long,
    val archived: Boolean,
)

data class CwBudget(
    val pk: String,
    val name: String,
    val amount: String,
    /** 0 custom, 1 daily, 2 weekly, 3 monthly, 4 yearly. */
    val reoccurrence: Int?,
    val periodLength: Int,
    val archived: Boolean,
    /** False when its category limits are percentages of [amount]. */
    val absoluteLimits: Boolean,
    val order: Int,
)

data class CwCategoryLimit(val categoryPk: String, val budgetPk: String, val amount: String)

/** The file isn't a Cashew backup Kassa can read; [message] says why and what to do. */
class NotReadableBackup(message: String) : Exception(message)
