package dev.krishnagurram.kassa.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import dev.krishnagurram.kassa.data.security.DatabaseKey
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Dao
interface GroupDao {
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
    @Query("SELECT * FROM accounts ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<AccountEntity>>

    /**
     * Balance per account: starting amount, plus income, minus expenses, minus transfers out, plus transfers in.
     * Done in SQL so it stays fast with years of history.
     */
    @Query(
        """
        SELECT a.id AS id,
          a.openingBalance
          + COALESCE((SELECT SUM(CASE t.kind WHEN 'INCOME' THEN t.amount ELSE -t.amount END)
                      FROM transactions t WHERE t.accountId = a.id), 0)
          + COALESCE((SELECT SUM(t.amount) FROM transactions t
                      WHERE t.kind = 'TRANSFER' AND t.toAccountId = a.id), 0) AS balance
        FROM accounts a
        """
    )
    fun observeBalances(): Flow<List<AccountBalance>>

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
    @Query("SELECT * FROM categories ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Query("DELETE FROM categories")
    suspend fun deleteAll()
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE epochMillis BETWEEN :fromMillis AND :toMillis ORDER BY epochMillis DESC, id DESC")
    fun observeBetween(fromMillis: Long, toMillis: Long): Flow<List<TransactionEntity>>

    @Query("SELECT COUNT(*) FROM transactions")
    fun observeCount(): Flow<Int>

    @Insert
    suspend fun insert(tx: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(txs: List<TransactionEntity>)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budgets")
    fun observeAll(): Flow<List<BudgetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(budgets: List<BudgetEntity>)

    @Query("DELETE FROM budgets")
    suspend fun deleteAll()
}

@Database(
    entities = [GroupEntity::class, AccountEntity::class, CategoryEntity::class, TransactionEntity::class, BudgetEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class KassaDatabase : RoomDatabase() {
    abstract fun groups(): GroupDao
    abstract fun accounts(): AccountDao
    abstract fun categories(): CategoryDao
    abstract fun transactions(): TransactionDao
    abstract fun budgets(): BudgetDao

    companion object {
        /** Encrypted with SQLCipher. The key lives in the Android Keystore, see [DatabaseKey]. */
        fun open(context: Context): KassaDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(DatabaseKey.get(context))
            return Room.databaseBuilder(context, KassaDatabase::class.java, "kassa.db")
                .openHelperFactory(factory)
                .build()
        }
    }
}
