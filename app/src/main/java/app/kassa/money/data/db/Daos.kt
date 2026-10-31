package app.kassa.money.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/* One DAO per table. Flows re-emit whenever their table changes; suspend functions are one-off reads and writes. */

@Dao
interface GroupDao {
    @Query("SELECT * FROM account_groups")
    suspend fun all(): List<GroupEntity>

    @Query("SELECT * FROM account_groups ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<GroupEntity>>

    @Insert
    suspend fun insert(group: GroupEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(groups: List<GroupEntity>)

    @Query("DELETE FROM account_groups")
    suspend fun deleteAll()
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts")
    suspend fun all(): List<AccountEntity>

    @Query("SELECT * FROM accounts ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<AccountEntity>>

    /**
     * Balance per account: starting amount, plus income, minus expenses, minus transfers out, plus transfers in, and a
     * balance correction up or down (kinds ADJUST_IN and ADJUST_OUT).
     * Done in SQL so it stays fast with years of history.
     */
    @Query(
        """
        SELECT a.id AS id,
          a.openingBalance
          + COALESCE((SELECT SUM(CASE WHEN t.kind IN ('INCOME', 'PERSON_IN', 'SELL', 'ADJUST_IN') THEN t.amount ELSE -t.amount END)
                      FROM transactions t WHERE t.accountId = a.id), 0)
          + COALESCE((SELECT SUM(t.amount) FROM transactions t
                      WHERE t.kind = 'TRANSFER' AND t.toAccountId = a.id), 0) AS balance
        FROM accounts a
        """
    )
    fun observeBalances(): Flow<List<AccountBalance>>

    /** One account's balance today, by the same sum as [observeBalances]; null when there is no such account. */
    @Query(
        """
        SELECT a.openingBalance
          + COALESCE((SELECT SUM(CASE WHEN t.kind IN ('INCOME', 'PERSON_IN', 'SELL', 'ADJUST_IN') THEN t.amount ELSE -t.amount END)
                      FROM transactions t WHERE t.accountId = a.id), 0)
          + COALESCE((SELECT SUM(t.amount) FROM transactions t
                      WHERE t.kind = 'TRANSFER' AND t.toAccountId = a.id), 0)
        FROM accounts a WHERE a.id = :id
        """
    )
    suspend fun balanceOf(id: Long): Long?

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM accounts")
    suspend fun maxSortOrder(): Int

    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(accounts: List<AccountEntity>)

    @Query("DELETE FROM accounts")
    suspend fun deleteAll()
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories")
    suspend fun all(): List<CategoryEntity>

    @Query("SELECT * FROM categories ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM categories")
    suspend fun maxSortOrder(): Int

    @Query("UPDATE transactions SET categoryId = :to WHERE categoryId = :from")
    suspend fun moveEntries(from: Long, to: Long)

    @Query("DELETE FROM categories")
    suspend fun deleteAll()
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions")
    suspend fun all(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE epochMillis BETWEEN :fromMillis AND :toMillis ORDER BY epochMillis DESC, id DESC")
    fun observeBetween(fromMillis: Long, toMillis: Long): Flow<List<TransactionEntity>>

    @Query("SELECT COUNT(*) FROM transactions")
    fun observeCount(): Flow<Int>

    /**
     * Every transaction whose note, category, parent category, account, destination account or person
     * matches [like] (a LIKE pattern), or whose amount equals [cents]. Newest first, capped for speed.
     */
    @Query(
        """
        SELECT t.* FROM transactions t
        LEFT JOIN categories c ON c.id = t.categoryId
        LEFT JOIN categories p ON p.id = c.parentId
        LEFT JOIN accounts a ON a.id = t.accountId
        LEFT JOIN accounts b ON b.id = t.toAccountId
        LEFT JOIN people pe ON pe.id = t.personId
        WHERE t.note LIKE :like OR c.name LIKE :like OR p.name LIKE :like OR a.name LIKE :like
           OR b.name LIKE :like OR pe.name LIKE :like OR t.amount = :cents
        ORDER BY t.epochMillis DESC, t.id DESC
        LIMIT 1000
        """
    )
    fun search(like: String, cents: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE accountId = :accountId OR toAccountId = :accountId ORDER BY epochMillis DESC, id DESC")
    fun observeForAccount(accountId: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE personId IS NOT NULL")
    fun observeWithPeople(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun byId(id: Long): TransactionEntity?

    /** Distinct notes for autocomplete, most used first. */
    @Query("SELECT note FROM transactions WHERE kind = :kind AND note != '' GROUP BY note ORDER BY COUNT(*) DESC LIMIT 400")
    suspend fun frequentNotes(kind: String): List<String>

    @Insert
    suspend fun insert(tx: TransactionEntity): Long

    @Update
    suspend fun update(tx: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(txs: List<TransactionEntity>)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}

@Dao
interface PersonDao {
    @Query("SELECT * FROM people")
    suspend fun all(): List<PersonEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(p: List<PersonEntity>)

    @Query("DELETE FROM people")
    suspend fun deleteAll()

    @Query("SELECT * FROM people ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<PersonEntity>>

    @Insert
    suspend fun insert(p: PersonEntity): Long

    @Update
    suspend fun update(p: PersonEntity)
}

@Dao
interface LoanDao {
    @Query("SELECT * FROM loan_entries")
    suspend fun all(): List<LoanEntryEntity>

    @Query("DELETE FROM loan_entries")
    suspend fun deleteAll()

    @Query("SELECT * FROM loan_entries")
    fun observeAll(): Flow<List<LoanEntryEntity>>

    @Query("SELECT * FROM loan_entries WHERE txId = :txId")
    suspend fun forTransaction(txId: Long): List<LoanEntryEntity>

    @Insert
    suspend fun insertAll(entries: List<LoanEntryEntity>)

    @Query("DELETE FROM loan_entries WHERE txId = :txId")
    suspend fun deleteForTransaction(txId: Long)

    /** Every line tied to an entry (a split share), for when all entries are replaced. Starting balances stay. */
    @Query("DELETE FROM loan_entries WHERE txId IS NOT NULL")
    suspend fun deleteTiedToEntries()

    /** A person's starting balance: their lines not tied to any entry. */
    @Query("SELECT * FROM loan_entries WHERE personId = :personId AND txId IS NULL ORDER BY epochMillis")
    suspend fun startingFor(personId: Long): List<LoanEntryEntity>

    @Query("DELETE FROM loan_entries WHERE personId = :personId AND txId IS NULL")
    suspend fun deleteStarting(personId: Long)
}

@Dao
interface RecurringDao {
    @Query("SELECT * FROM recurring")
    suspend fun all(): List<RecurringEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(r: List<RecurringEntity>)

    @Query("DELETE FROM recurring")
    suspend fun deleteAll()

    @Query("SELECT * FROM recurring ORDER BY dayOfMonth, name")
    fun observeAll(): Flow<List<RecurringEntity>>

    @Insert
    suspend fun insert(r: RecurringEntity): Long

    @Update
    suspend fun update(r: RecurringEntity)

    @Query("DELETE FROM recurring WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface InvestmentTransferDao {
    @Query("SELECT * FROM investment_transfers")
    suspend fun all(): List<InvestmentTransferEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(t: List<InvestmentTransferEntity>)

    @Query("SELECT * FROM investment_transfers ORDER BY epochMillis DESC")
    fun observeAll(): Flow<List<InvestmentTransferEntity>>

    @Insert
    suspend fun insert(t: InvestmentTransferEntity): Long

    @Query("DELETE FROM investment_transfers WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM investment_transfers")
    suspend fun deleteAll()
}

@Dao
interface HoldingDao {
    @Query("SELECT * FROM holdings")
    suspend fun all(): List<HoldingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(h: List<HoldingEntity>)

    @Query("DELETE FROM holdings")
    suspend fun deleteAll()

    @Query("SELECT * FROM holdings ORDER BY ticker")
    fun observeAll(): Flow<List<HoldingEntity>>

    @Query("SELECT * FROM holdings WHERE accountId = :accountId AND ticker = :ticker")
    suspend fun find(accountId: Long, ticker: String): HoldingEntity?

    @Query("SELECT * FROM holdings WHERE id = :id")
    suspend fun byId(id: Long): HoldingEntity?

    @Insert
    suspend fun insert(h: HoldingEntity): Long

    @Update
    suspend fun update(h: HoldingEntity)

    @Query("DELETE FROM holdings WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budgets")
    suspend fun all(): List<BudgetEntity>

    @Query("SELECT * FROM budgets")
    fun observeAll(): Flow<List<BudgetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(budgets: List<BudgetEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(budget: BudgetEntity)

    @Query("DELETE FROM budgets WHERE categoryId = :categoryId AND month = :month")
    suspend fun delete(categoryId: Long, month: Int)

    @Query("DELETE FROM budgets")
    suspend fun deleteAll()
}

@Dao
interface TradeDao {
    @Query("SELECT * FROM trades")
    suspend fun all(): List<TradeEntity>

    @Query("SELECT * FROM trades")
    fun observeAll(): Flow<List<TradeEntity>>

    @Query("SELECT * FROM trades WHERE transactionId = :transactionId")
    suspend fun forTransaction(transactionId: Long): TradeEntity?

    @Insert
    suspend fun insert(t: TradeEntity): Long

    @Insert
    suspend fun insertAll(t: List<TradeEntity>)

    @Update
    suspend fun update(t: TradeEntity)

    @Query("DELETE FROM trades WHERE transactionId = :transactionId")
    suspend fun deleteForTransaction(transactionId: Long)

    @Query("DELETE FROM trades")
    suspend fun deleteAll()
}

@Dao
interface LoanTermsDao {
    @Query("SELECT * FROM loan_terms")
    suspend fun all(): List<LoanTermsEntity>

    @Query("SELECT * FROM loan_terms")
    fun observeAll(): Flow<List<LoanTermsEntity>>

    @Query("SELECT * FROM loan_terms WHERE accountId = :accountId")
    suspend fun forAccount(accountId: Long): LoanTermsEntity?

    /** Inserts the terms, or replaces the loan's existing ones. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(terms: LoanTermsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(terms: List<LoanTermsEntity>)

    @Query("UPDATE loan_terms SET nextPaymentDay = :day WHERE accountId = :accountId")
    suspend fun setNextPaymentDay(accountId: Long, day: Int)

    @Query("DELETE FROM loan_terms")
    suspend fun deleteAll()
}

@Dao
interface LoanTermChangeDao {
    @Query("SELECT * FROM loan_term_changes")
    suspend fun all(): List<LoanTermChangeEntity>

    @Query("SELECT * FROM loan_term_changes ORDER BY effectiveDay, id")
    fun observeAll(): Flow<List<LoanTermChangeEntity>>

    @Insert
    suspend fun insert(change: LoanTermChangeEntity): Long

    @Query("DELETE FROM loan_term_changes WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(changes: List<LoanTermChangeEntity>)

    @Query("DELETE FROM loan_term_changes")
    suspend fun deleteAll()
}

@Dao
interface LoanPaymentDao {
    @Query("SELECT * FROM loan_payments")
    suspend fun all(): List<LoanPaymentEntity>

    @Query("SELECT * FROM loan_payments ORDER BY day, id")
    fun observeAll(): Flow<List<LoanPaymentEntity>>

    /**
     * What each payment's entries hold that the loan page needs: the interest paid, and the category of its escrow
     * entry. Oldest first, so the last row of a loan is its latest payment.
     */
    @Query(
        """
        SELECT p.accountId AS accountId, COALESCE(ti.amount, 0) AS interest, o.categoryId AS escrowCategoryId
        FROM loan_payments p
        LEFT JOIN transactions ti ON ti.id = p.interestEntryId
        LEFT JOIN transactions o ON o.id = p.otherEntryId
        ORDER BY p.day, p.id
        """
    )
    fun observeExtras(): Flow<List<LoanPaymentExtra>>

    @Query("SELECT * FROM loan_payments WHERE id = :id")
    suspend fun byId(id: Long): LoanPaymentEntity?

    /** The payment an entry belongs to, whichever of its entries it is. */
    @Query("SELECT * FROM loan_payments WHERE principalEntryId = :entryId OR interestEntryId = :entryId OR otherEntryId = :entryId LIMIT 1")
    suspend fun forEntry(entryId: Long): LoanPaymentEntity?

    @Insert
    suspend fun insert(payment: LoanPaymentEntity): Long

    @Update
    suspend fun update(payment: LoanPaymentEntity)

    @Query("DELETE FROM loan_payments WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(payments: List<LoanPaymentEntity>)

    @Query("DELETE FROM loan_payments")
    suspend fun deleteAll()
}
