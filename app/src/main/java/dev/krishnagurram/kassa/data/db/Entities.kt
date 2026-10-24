package dev.krishnagurram.kassa.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.krishnagurram.kassa.domain.Account
import dev.krishnagurram.kassa.domain.AccountGroup
import dev.krishnagurram.kassa.domain.AccountType
import dev.krishnagurram.kassa.domain.Budget
import dev.krishnagurram.kassa.domain.Category
import dev.krishnagurram.kassa.domain.CategoryIcon
import dev.krishnagurram.kassa.domain.CategoryKind
import dev.krishnagurram.kassa.domain.Holding
import dev.krishnagurram.kassa.domain.InvestmentTransfer
import dev.krishnagurram.kassa.domain.LoanEntry
import dev.krishnagurram.kassa.domain.Recurring
import dev.krishnagurram.kassa.domain.Person
import dev.krishnagurram.kassa.domain.Transaction
import dev.krishnagurram.kassa.domain.Trade
import dev.krishnagurram.kassa.domain.TxKind

/* Room rows. Enums are stored by name so the database stays readable and reorder-safe. */

@Entity(tableName = "account_groups")
data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int,
)

@Entity(tableName = "accounts", indices = [Index("groupId")])
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val groupId: Long?,
    val openingBalance: Long,
    val statementDay: Int?,
    val dueDay: Int?,
    val payFromId: Long?,
    val creditLimit: Long?,
    val archived: Boolean,
    val sortOrder: Int,
    val externalId: String?,
    val last4: String? = null,
)

@Entity(tableName = "categories", indices = [Index("parentId")])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,
    val parentId: Long?,
    val icon: String,
    val color: Int,
    val countsTowardDaily: Boolean,
    val hidden: Boolean,
    val sortOrder: Int,
    val externalId: String?,
)

@Entity(
    tableName = "transactions",
    indices = [Index("epochMillis"), Index("accountId"), Index("toAccountId"), Index("categoryId"), Index("personId")],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val amount: Long,
    val accountId: Long?,
    val toAccountId: Long?,
    val categoryId: Long?,
    val personId: Long?,
    val epochMillis: Long,
    val note: String,
    val myShare: Long?,
    val externalId: String?,
)

@Entity(tableName = "budgets", primaryKeys = ["categoryId", "month"])
data class BudgetEntity(
    val categoryId: Long,
    /** yyyyMM, or 0 for the default amount. */
    val month: Int,
    val amount: Long,
)

@Entity(tableName = "people")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val archived: Boolean,
)

@Entity(tableName = "loan_entries", indices = [Index("personId"), Index("txId")])
data class LoanEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personId: Long,
    val amount: Long,
    val epochMillis: Long,
    val note: String,
    val txId: Long?,
)

@Entity(tableName = "recurring")
data class RecurringEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val categoryId: Long,
    val accountId: Long,
    val dayOfMonth: Int,
    val fixedAmount: Long?,
    val active: Boolean,
    val handledMonth: Int,
)

/** An [InvestmentTransfer]: money moved into an investment account, waiting to be explained. */
@Entity(tableName = "investment_transfers", indices = [Index("accountId")])
data class InvestmentTransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: Long,
    val accountId: Long,
    val amount: Long,
    val epochMillis: Long,
)

@Entity(tableName = "holdings", indices = [Index("accountId")])
data class HoldingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val ticker: String,
    val microShares: Long,
    val price: Long,
    val priceMillis: Long,
    val avgCost: Long? = null,
)

/** A [Trade]: the shares and price behind a BUY or SELL entry. */
@Entity(tableName = "trades", indices = [Index("transactionId")])
data class TradeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: Long,
    val ticker: String,
    val microShares: Long,
    val price: Long,
)

/** Result row for the balance query. */
data class AccountBalance(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "balance") val balance: Long,
)

fun GroupEntity.toDomain() = AccountGroup(id, name, sortOrder)
fun AccountGroup.toEntity() = GroupEntity(id, name, sortOrder)

fun AccountEntity.toDomain() = Account(
    id = id, name = name, type = AccountType.valueOf(type), groupId = groupId, openingBalance = openingBalance,
    statementDay = statementDay, dueDay = dueDay, payFromId = payFromId, creditLimit = creditLimit,
    archived = archived, sortOrder = sortOrder, externalId = externalId, last4 = last4,
)
fun Account.toEntity() = AccountEntity(
    id, name, type.name, groupId, openingBalance, statementDay, dueDay, payFromId, creditLimit, archived, sortOrder, externalId, last4,
)

fun CategoryEntity.toDomain() = Category(
    id = id, name = name, kind = CategoryKind.valueOf(kind), parentId = parentId, icon = CategoryIcon.decode(icon),
    color = color, countsTowardDaily = countsTowardDaily, hidden = hidden, sortOrder = sortOrder, externalId = externalId,
)
fun Category.toEntity() = CategoryEntity(
    id, name, kind.name, parentId, icon.encode(), color, countsTowardDaily, hidden, sortOrder, externalId,
)

fun TransactionEntity.toDomain() = Transaction(
    id = id, kind = TxKind.valueOf(kind), amount = amount, accountId = accountId, toAccountId = toAccountId,
    categoryId = categoryId, personId = personId, epochMillis = epochMillis, note = note, myShare = myShare, externalId = externalId,
)
fun Transaction.toEntity() = TransactionEntity(
    id, kind.name, amount, accountId, toAccountId, categoryId, personId, epochMillis, note, myShare, externalId,
)

fun Budget.toEntity() = BudgetEntity(categoryId, month, amount)
fun BudgetEntity.toDomain() = Budget(categoryId, month, amount)

fun PersonEntity.toDomain() = Person(id, name, archived)
fun Person.toEntity() = PersonEntity(id, name, archived)
fun LoanEntryEntity.toDomain() = LoanEntry(id, personId, amount, epochMillis, note, txId)
fun LoanEntry.toEntity() = LoanEntryEntity(id, personId, amount, epochMillis, note, transactionId)

fun RecurringEntity.toDomain() = Recurring(id, name, categoryId, accountId, dayOfMonth, fixedAmount, active, handledMonth)
fun Recurring.toEntity() = RecurringEntity(id, name, categoryId, accountId, dayOfMonth, fixedAmount, active, handledMonth)
fun HoldingEntity.toDomain() = Holding(id, accountId, ticker, microShares, price, priceMillis, avgCost)
fun Holding.toEntity() = HoldingEntity(id, accountId, ticker, microShares, price, priceMillis, avgCost)
fun InvestmentTransferEntity.toDomain() = InvestmentTransfer(id, transactionId, accountId, amount, epochMillis)
fun InvestmentTransfer.toEntity() = InvestmentTransferEntity(id, transactionId, accountId, amount, epochMillis)
fun TradeEntity.toDomain() = Trade(id, transactionId, ticker, microShares, price)
fun Trade.toEntity() = TradeEntity(id, transactionId, ticker, microShares, price)
