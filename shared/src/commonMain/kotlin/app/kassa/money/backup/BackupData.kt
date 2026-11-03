package app.kassa.money.backup

/*
 * What a .kassa backup holds, as plain data. One class per table, with the names and types of the Android entities in
 * `app/data/db/Entities.kt` and the JSON keys of `BackupContents.kt`. A field that is nullable here is one the Android
 * writer sends only when it has a value (`opt`); every other field is required in the file. See docs/BACKUP_FORMAT.md.
 */

data class BackupGroup(val id: Long, val name: String, val sortOrder: Int)

data class BackupAccount(
    val id: Long,
    val name: String,
    val type: String,
    val groupId: Long? = null,
    val openingBalance: Long,
    val statementDay: Int? = null,
    val dueDay: Int? = null,
    val payFromId: Long? = null,
    val creditLimit: Long? = null,
    val archived: Boolean,
    val sortOrder: Int,
    val externalId: String? = null,
    val last4: String? = null,
    val maturityDay: Int? = null,
    val closedDay: Int? = null,
)

data class BackupCategory(
    val id: Long,
    val name: String,
    val kind: String,
    val parentId: Long? = null,
    val icon: String,
    val color: Int,
    val countsTowardDaily: Boolean,
    val hidden: Boolean,
    val sortOrder: Int,
    val externalId: String? = null,
)

data class BackupTransaction(
    val id: Long,
    val kind: String,
    val amount: Long,
    val accountId: Long? = null,
    val toAccountId: Long? = null,
    val categoryId: Long? = null,
    val personId: Long? = null,
    val epochMillis: Long,
    val note: String,
    val myShare: Long? = null,
    val externalId: String? = null,
)

data class BackupBudget(val categoryId: Long, val month: Int, val amount: Long)

data class BackupPerson(val id: Long, val name: String, val archived: Boolean)

/** A row of the `loans` table: money lent to or borrowed from a person. */
data class BackupLoanEntry(
    val id: Long,
    val personId: Long,
    val amount: Long,
    val epochMillis: Long,
    val note: String,
    val txId: Long? = null,
)

/** A row of the `recurring` table (a bill). */
data class BackupBill(
    val id: Long,
    val name: String,
    val categoryId: Long,
    val accountId: Long,
    val dayOfMonth: Int,
    val fixedAmount: Long? = null,
    val active: Boolean,
    val handledMonth: Int,
)

data class BackupHolding(
    val id: Long,
    val accountId: Long,
    val ticker: String,
    val microShares: Long,
    val price: Long,
    val priceMillis: Long,
    val avgCost: Long? = null,
)

data class BackupTrade(val id: Long, val transactionId: Long, val ticker: String, val microShares: Long, val price: Long)

data class BackupLoanTerms(
    val accountId: Long,
    val type: String,
    val profit: Boolean,
    val rate: Long,
    val payment: Long,
    val termMonths: Int? = null,
    val originalAmount: Long? = null,
    val startDay: Int? = null,
    val nextPaymentDay: Int,
    val dueDay: Int,
    val escrow: Long,
    val fee: Long,
    val earlyCharge: Long,
    val interestCategoryId: Long? = null,
    val payFromId: Long? = null,
    /** Required in the file the writer makes, but read as false when absent. */
    val countAllAsSpending: Boolean = false,
    val principalCategoryId: Long? = null,
)

data class BackupLoanChange(
    val id: Long,
    val accountId: Long,
    val effectiveDay: Int,
    val rate: Long? = null,
    val payment: Long? = null,
    val termMonths: Int? = null,
    val type: String? = null,
)

data class BackupLoanPayment(
    val id: Long,
    val accountId: Long,
    val day: Int,
    val amount: Long,
    val principalEntryId: Long? = null,
    val interestEntryId: Long? = null,
    val otherEntryId: Long? = null,
    val escrow: Long,
    val charge: Long,
    val edited: Boolean,
    val estimate: Long,
    val scheduled: Boolean,
    val countAllAsSpending: Boolean? = null,
    val principalCategoryId: Long? = null,
)

data class BackupInvestmentTransfer(val id: Long, val transactionId: Long, val accountId: Long, val amount: Long, val epochMillis: Long)

/**
 * Everything a backup holds, like Android's `BackupContents`. The tables that older files lack read as empty lists.
 * [icons] maps a file name to the picture's bytes; equality compares those bytes by content.
 */
data class BackupData(
    val createdAt: Long,
    val monthStartDay: Int,
    val currency: String? = null,
    val groups: List<BackupGroup> = emptyList(),
    val accounts: List<BackupAccount> = emptyList(),
    val categories: List<BackupCategory> = emptyList(),
    val transactions: List<BackupTransaction> = emptyList(),
    val budgets: List<BackupBudget> = emptyList(),
    val people: List<BackupPerson> = emptyList(),
    val loans: List<BackupLoanEntry> = emptyList(),
    val bills: List<BackupBill> = emptyList(),
    val holdings: List<BackupHolding> = emptyList(),
    val trades: List<BackupTrade> = emptyList(),
    val loanTerms: List<BackupLoanTerms> = emptyList(),
    val loanChanges: List<BackupLoanChange> = emptyList(),
    val loanPayments: List<BackupLoanPayment> = emptyList(),
    val icons: Map<String, ByteArray> = emptyMap(),
    val accountArrangement: String? = null,
    val investmentTransfers: List<BackupInvestmentTransfer> = emptyList(),
) {
    /** One line for the confirm step before restoring; the same words as Android. */
    val summary: String
        get() = "${transactions.size} entries, ${accounts.size} accounts, ${categories.size} categories"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BackupData) return false
        if (createdAt != other.createdAt || monthStartDay != other.monthStartDay || currency != other.currency) return false
        if (accountArrangement != other.accountArrangement) return false
        if (groups != other.groups || accounts != other.accounts || categories != other.categories) return false
        if (transactions != other.transactions || budgets != other.budgets || people != other.people) return false
        if (loans != other.loans || bills != other.bills || holdings != other.holdings || trades != other.trades) return false
        if (loanTerms != other.loanTerms || loanChanges != other.loanChanges || loanPayments != other.loanPayments) return false
        if (investmentTransfers != other.investmentTransfers) return false
        if (icons.size != other.icons.size) return false
        for ((name, bytes) in icons) {
            val theirs = other.icons[name] ?: return false
            if (!bytes.contentEquals(theirs)) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var h = createdAt.hashCode()
        h = 31 * h + monthStartDay
        h = 31 * h + (currency?.hashCode() ?: 0)
        h = 31 * h + (accountArrangement?.hashCode() ?: 0)
        h = 31 * h + groups.hashCode()
        h = 31 * h + accounts.hashCode()
        h = 31 * h + categories.hashCode()
        h = 31 * h + transactions.hashCode()
        h = 31 * h + budgets.hashCode()
        h = 31 * h + people.hashCode()
        h = 31 * h + loans.hashCode()
        h = 31 * h + bills.hashCode()
        h = 31 * h + holdings.hashCode()
        h = 31 * h + trades.hashCode()
        h = 31 * h + loanTerms.hashCode()
        h = 31 * h + loanChanges.hashCode()
        h = 31 * h + loanPayments.hashCode()
        h = 31 * h + investmentTransfers.hashCode()
        var iconHash = 0
        for ((name, bytes) in icons) iconHash += name.hashCode() xor bytes.contentHashCode()
        return 31 * h + iconHash
    }
}

/** A file that can't be opened as a backup. The messages are the ones Android shows. */
sealed class BackupFormatException(message: String) : Exception(message)

/** Not JSON, not the JSON a backup holds, or missing a required field. */
class NotABackup : BackupFormatException("This file isn't a Kassa backup.")

/** Written by a newer Kassa: its fields may mean something this code doesn't know. */
class NewerVersion : BackupFormatException("This backup is from a newer version of Kassa. Update Kassa to open it.")
