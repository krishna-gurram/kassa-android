package dev.krishnagurram.kassa.domain

/**
 * Core money model. Amounts are always whole cents in a [Long]; nothing in Kassa uses floating point for money.
 * Every entity here is plain Kotlin so the rules can be unit tested without Android.
 */

/** What an account is. [isDebt] decides whether its balance adds to or subtracts from net worth. */
enum class AccountType(val isDebt: Boolean, val label: String) {
    CHECKING(false, "Checking"),
    SAVINGS(false, "Savings"),
    CASH(false, "Cash"),
    CREDIT_CARD(true, "Credit card"),
    INVESTMENT(false, "Investment"),
    LOAN(true, "Loan or mortgage"),
    PREPAID(false, "Prepaid or wallet"),
    OTHER_ASSET(false, "Other asset"),
    OTHER_DEBT(true, "Other debt");

    /** Card fields (statement and due day) only make sense for cards. */
    val hasStatement: Boolean get() = this == CREDIT_CARD
}

data class AccountGroup(
    val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
)

data class Account(
    val id: Long = 0,
    val name: String,
    val type: AccountType,
    val groupId: Long? = null,
    /** Balance before the first entry, in cents. For debts this is usually negative. */
    val openingBalance: Long = 0,
    val statementDay: Int? = null,
    val dueDay: Int? = null,
    val payFromId: Long? = null,
    val creditLimit: Long? = null,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
    /** Id in the system this came from (Money Manager uid), so a re-import never duplicates. */
    val externalId: String? = null,
)

enum class CategoryKind { EXPENSE, INCOME }

/**
 * A category icon is one of three things. It is stored as a single string, see [encode] and [decode].
 */
sealed interface CategoryIcon {
    data class Emoji(val value: String) : CategoryIcon
    /** A Material Symbols name bundled with the app, for example "fitness_center". */
    data class Symbol(val name: String) : CategoryIcon
    /** A picture the user picked, stored as a file in the app's private storage. */
    data class Image(val fileName: String) : CategoryIcon

    fun encode(): String = when (this) {
        is Emoji -> "emoji:$value"
        is Symbol -> "symbol:$name"
        is Image -> "image:$fileName"
    }

    companion object {
        val DEFAULT: CategoryIcon = Emoji("🏷️")

        fun decode(raw: String?): CategoryIcon {
            if (raw.isNullOrBlank()) return DEFAULT
            val kind = raw.substringBefore(':')
            val value = raw.substringAfter(':', "")
            if (value.isEmpty()) return DEFAULT
            return when (kind) {
                "emoji" -> Emoji(value)
                "symbol" -> Symbol(value)
                "image" -> Image(value)
                else -> DEFAULT
            }
        }
    }
}

data class Category(
    val id: Long = 0,
    val name: String,
    val kind: CategoryKind,
    val parentId: Long? = null,
    val icon: CategoryIcon = CategoryIcon.DEFAULT,
    /** ARGB color used everywhere this category appears. */
    val color: Int,
    /** Counts toward the "safe to spend today" number. Bills like mortgage usually do not. */
    val countsTowardDaily: Boolean = true,
    val hidden: Boolean = false,
    val sortOrder: Int = 0,
    val externalId: String? = null,
)

enum class TxKind { EXPENSE, INCOME, TRANSFER }

data class Transaction(
    val id: Long = 0,
    val kind: TxKind,
    /** Always positive. Direction comes from [kind]. */
    val amount: Long,
    val accountId: Long,
    /** Only for transfers. */
    val toAccountId: Long? = null,
    /** Expense and income only. A subcategory id when one was picked. */
    val categoryId: Long? = null,
    val epochMillis: Long,
    val note: String = "",
    /** When an expense was split, the part that is yours. Category totals use this. */
    val myShare: Long? = null,
    val externalId: String? = null,
) {
    init {
        require(amount >= 0) { "Amounts are stored positive, got $amount" }
        require(kind != TxKind.TRANSFER || toAccountId != null) { "A transfer needs a destination account" }
    }

    /** What this entry adds to category spending or income. */
    val categoryAmount: Long get() = myShare ?: amount
}

/** A monthly budget for one category. [month] is yyyyMM, or 0 for the default that applies to every month. */
data class Budget(
    val categoryId: Long,
    val month: Int,
    val amount: Long,
)

/** Applies one transaction to an account's running balance. */
fun Transaction.effectOn(accountId: Long): Long = when (kind) {
    TxKind.EXPENSE -> if (this.accountId == accountId) -amount else 0
    TxKind.INCOME -> if (this.accountId == accountId) amount else 0
    TxKind.TRANSFER -> when (accountId) {
        this.accountId -> -amount
        toAccountId -> amount
        else -> 0
    }
}

fun balanceOf(account: Account, transactions: Iterable<Transaction>): Long =
    account.openingBalance + transactions.sumOf { it.effectOn(account.id) }
