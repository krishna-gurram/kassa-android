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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
          + COALESCE((SELECT SUM(CASE WHEN t.kind IN ('INCOME', 'PERSON_IN') THEN t.amount ELSE -t.amount END)
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
    @Query("SELECT * FROM transactions WHERE epochMillis BETWEEN :fromMillis AND :toMillis ORDER BY epochMillis DESC, id DESC")
    fun observeBetween(fromMillis: Long, toMillis: Long): Flow<List<TransactionEntity>>

    @Query("SELECT COUNT(*) FROM transactions")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM transactions WHERE personId = :personId ORDER BY epochMillis DESC")
    fun observeForPerson(personId: Long): Flow<List<TransactionEntity>>

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
    fun observeAll(): Flow<List<LoanEntryEntity>>

    @Query("SELECT * FROM loan_entries WHERE txId = :txId")
    suspend fun forTransaction(txId: Long): List<LoanEntryEntity>

    @Insert
    suspend fun insertAll(entries: List<LoanEntryEntity>)

    @Query("DELETE FROM loan_entries WHERE txId = :txId")
    suspend fun deleteForTransaction(txId: Long)
}

@Dao
interface BudgetDao {
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

@Database(
    entities = [
        GroupEntity::class, AccountEntity::class, CategoryEntity::class, TransactionEntity::class, BudgetEntity::class,
        PersonEntity::class, LoanEntryEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class KassaDatabase : RoomDatabase() {
    abstract fun groups(): GroupDao
    abstract fun accounts(): AccountDao
    abstract fun categories(): CategoryDao
    abstract fun transactions(): TransactionDao
    abstract fun budgets(): BudgetDao
    abstract fun people(): PersonDao
    abstract fun loans(): LoanDao

    companion object {
        /** Encrypted with SQLCipher. The key lives in the Android Keystore, see [DatabaseKey]. */
        fun open(context: Context): KassaDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(DatabaseKey.get(context))
            return Room.databaseBuilder(context, KassaDatabase::class.java, "kassa.db")
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2)
                .build()
        }
    }
}

/**
 * v1 to v2: people and loan entries, and transactions gain a person and an optional account
 * (an expense someone else paid for you moves none of your accounts). SQLite can't relax NOT NULL in place,
 * so the transactions table is rebuilt and every row copied across.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `transactions_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, " +
                "`amount` INTEGER NOT NULL, `accountId` INTEGER, `toAccountId` INTEGER, `categoryId` INTEGER, `personId` INTEGER, " +
                "`epochMillis` INTEGER NOT NULL, `note` TEXT NOT NULL, `myShare` INTEGER, `externalId` TEXT)"
        )
        db.execSQL(
            "INSERT INTO `transactions_new` (`id`, `kind`, `amount`, `accountId`, `toAccountId`, `categoryId`, `epochMillis`, `note`, `myShare`, `externalId`) " +
                "SELECT `id`, `kind`, `amount`, `accountId`, `toAccountId`, `categoryId`, `epochMillis`, `note`, `myShare`, `externalId` FROM `transactions`"
        )
        db.execSQL("DROP TABLE `transactions`")
        db.execSQL("ALTER TABLE `transactions_new` RENAME TO `transactions`")
        for (col in listOf("epochMillis", "accountId", "toAccountId", "categoryId", "personId")) {
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_$col` ON `transactions` (`$col`)")
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS `people` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `archived` INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `loan_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `personId` INTEGER NOT NULL, " +
                "`amount` INTEGER NOT NULL, `epochMillis` INTEGER NOT NULL, `note` TEXT NOT NULL, `txId` INTEGER)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_loan_entries_personId` ON `loan_entries` (`personId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_loan_entries_txId` ON `loan_entries` (`txId`)")
    }
}
