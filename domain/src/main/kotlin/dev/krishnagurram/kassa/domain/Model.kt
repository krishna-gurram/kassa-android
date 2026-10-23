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
    /** Last four digits of the card or account number, to tell similar accounts apart. */
    val last4: String? = null,
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

/** A new subcategory under this category: same kind, icon, color and daily setting. */
fun Category.subcategory(name: String) =
    Category(name = name, kind = kind, parentId = id, icon = icon, color = color, countsTowardDaily = countsTowardDaily)

enum class TxKind {
    EXPENSE,
    INCOME,
    TRANSFER,
    /** Money from your account to a person: lending, or paying back what you owe. */
    PERSON_OUT,
    /** Money from a person into your account: being paid back, or borrowing. */
    PERSON_IN,
    /** Cash in an investment account spent on shares. The note holds the ticker. */
    BUY,
    /** Shares sold back to cash in an investment account. */
    SELL,
}

/** Someone you split costs with or lend to. Their balance lives in [LoanEntry] rows and person transactions. */
data class Person(
    val id: Long = 0,
    val name: String,
    val archived: Boolean = false,
)

/**
 * A change to what a person owes, not tied to money moving through your accounts.
 * Positive: they owe you more (their share of something you paid). Negative: you owe them (they paid for you).
 */
data class LoanEntry(
    val id: Long = 0,
    val personId: Long,
    val amount: Long,
    val epochMillis: Long,
    val note: String = "",
    /** The expense this came from, when it came from a split. */
    val transactionId: Long? = null,
)

data class Transaction(
    val id: Long = 0,
    val kind: TxKind,
    /** Always positive. Direction comes from [kind]. */
    val amount: Long,
    /** Null only for an expense someone else paid for you ([personId] says who). */
    val accountId: Long?,
    /** Only for transfers. */
    val toAccountId: Long? = null,
    /** Expense and income only. A subcategory id when one was picked. */
    val categoryId: Long? = null,
    val epochMillis: Long,
    val note: String = "",
    /** For person transactions, who the money went to or came from. For an expense, who paid when you didn't. */
    val personId: Long? = null,
    /** When an expense was split, the part that is yours. Category totals use this. */
    val myShare: Long? = null,
    val externalId: String? = null,
) {
    init {
        require(amount >= 0) { "Amounts are stored positive, got $amount" }
        require(kind != TxKind.TRANSFER || toAccountId != null) { "A transfer needs a destination account" }
        require(kind !in PERSON_KINDS || personId != null) { "A person payment needs a person" }
        require(accountId != null || (kind == TxKind.EXPENSE && personId != null)) { "Only an expense someone else paid can skip the account" }
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
val PERSON_KINDS = setOf(TxKind.PERSON_OUT, TxKind.PERSON_IN)

fun Transaction.effectOn(accountId: Long): Long = when (kind) {
    TxKind.EXPENSE, TxKind.PERSON_OUT, TxKind.BUY -> if (this.accountId == accountId) -amount else 0
    TxKind.INCOME, TxKind.PERSON_IN, TxKind.SELL -> if (this.accountId == accountId) amount else 0
    TxKind.TRANSFER -> when (accountId) {
        this.accountId -> -amount
        toAccountId -> amount
        else -> 0
    }
}

fun balanceOf(account: Account, transactions: Iterable<Transaction>): Long =
    account.openingBalance + transactions.sumOf { it.effectOn(account.id) }

/**
 * What [personId] owes you (positive) or you owe them (negative): split shares and debts from [loans],
 * plus money you sent them, minus money they sent you.
 */
fun personBalance(personId: Long, loans: Iterable<LoanEntry>, transactions: Iterable<Transaction>): Long =
    loans.filter { it.personId == personId }.sumOf { it.amount } +
        transactions.filter { it.personId == personId }.sumOf {
            when (it.kind) {
                TxKind.PERSON_OUT -> it.amount
                TxKind.PERSON_IN -> -it.amount
                else -> 0L
            }
        }
