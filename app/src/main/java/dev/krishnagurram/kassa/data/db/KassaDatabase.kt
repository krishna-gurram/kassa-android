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
     * Balance per account: starting amount, plus income, minus expenses, minus transfers out, plus transfers in.
     * Done in SQL so it stays fast with years of history.
     */
    @Query(
        """
        SELECT a.id AS id,
          a.openingBalance
          + COALESCE((SELECT SUM(CASE WHEN t.kind IN ('INCOME', 'PERSON_IN', 'SELL') THEN t.amount ELSE -t.amount END)
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

    @Query("SELECT * FROM transactions WHERE personId = :personId ORDER BY epochMillis DESC")
    fun observeForPerson(personId: Long): Flow<List<TransactionEntity>>

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

    @Query("SELECT categoryId FROM transactions WHERE note = :note COLLATE NOCASE AND kind = 'EXPENSE' AND categoryId IS NOT NULL ORDER BY epochMillis DESC LIMIT 1")
    suspend fun lastCategoryForNote(note: String): Long?

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
interface PendingDao {
    @Query("DELETE FROM pending_items")
    suspend fun deleteAll()

    @Query("SELECT * FROM pending_items ORDER BY epochMillis DESC")
    fun observeAll(): Flow<List<PendingEntity>>

    @Query("SELECT COUNT(*) FROM pending_items WHERE type = 'ALERT' AND amount = :amount AND source = :source AND epochMillis > :since")
    suspend fun recentDuplicates(amount: Long, source: String, since: Long): Int

    @Insert
    suspend fun insert(p: PendingEntity): Long

    @Query("DELETE FROM pending_items WHERE id = :id")
    suspend fun delete(id: Long)
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

    @Insert
    suspend fun insert(h: HoldingEntity): Long

    @Update
    suspend fun update(h: HoldingEntity)

    @Query("DELETE FROM holdings WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface AlertRuleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(r: List<AlertRuleEntity>)

    @Query("DELETE FROM alert_rules")
    suspend fun deleteAll()

    @Query("SELECT * FROM alert_rules")
    fun observeAll(): Flow<List<AlertRuleEntity>>

    @Query("SELECT * FROM alert_rules")
    suspend fun all(): List<AlertRuleEntity>

    @Insert
    suspend fun insert(r: AlertRuleEntity): Long

    @Query("DELETE FROM alert_rules WHERE id = :id")
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

@Database(
    entities = [
        GroupEntity::class, AccountEntity::class, CategoryEntity::class, TransactionEntity::class, BudgetEntity::class,
        PersonEntity::class, LoanEntryEntity::class,
        RecurringEntity::class, PendingEntity::class, HoldingEntity::class, AlertRuleEntity::class,
    ],
    version = 3,
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
    abstract fun recurring(): RecurringDao
    abstract fun pending(): PendingDao
    abstract fun holdings(): HoldingDao
    abstract fun alertRules(): AlertRuleDao

    companion object {
        /** Encrypted with SQLCipher. The key lives in the Android Keystore, see [DatabaseKey]. */
        fun open(context: Context): KassaDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(DatabaseKey.get(context))
            return Room.databaseBuilder(context, KassaDatabase::class.java, "kassa.db")
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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

/** v2 to v3: recurring bills, pending items, holdings and alert rules. New tables only; nothing existing changes. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recurring` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                "`categoryId` INTEGER NOT NULL, `accountId` INTEGER NOT NULL, `dayOfMonth` INTEGER NOT NULL, `fixedAmount` INTEGER, " +
                "`active` INTEGER NOT NULL, `handledMonth` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `pending_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, " +
                "`amount` INTEGER NOT NULL, `merchant` TEXT, `accountId` INTEGER, `categoryId` INTEGER, `epochMillis` INTEGER NOT NULL, " +
                "`source` TEXT, `isCredit` INTEGER NOT NULL, `txId` INTEGER)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `holdings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `accountId` INTEGER NOT NULL, " +
                "`ticker` TEXT NOT NULL, `microShares` INTEGER NOT NULL, `price` INTEGER NOT NULL, `priceMillis` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_holdings_accountId` ON `holdings` (`accountId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `alert_rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `packageName` TEXT NOT NULL, " +
                "`last4` TEXT, `accountId` INTEGER NOT NULL)"
        )
    }
}
