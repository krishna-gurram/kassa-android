package app.kassa.money.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import app.kassa.money.data.security.DatabaseKey
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** Bump with every schema change, and add the migration to [ALL_MIGRATIONS]. */
const val DATABASE_VERSION = 10

/**
 * Kassa's database. Changing an entity means bumping [version] and adding a migration in Migrations.kt;
 * CI installs the last release first and upgrades over it, so a missing or wrong migration fails the build.
 */
@Database(
    entities = [
        GroupEntity::class, AccountEntity::class, CategoryEntity::class, TransactionEntity::class, BudgetEntity::class,
        PersonEntity::class, LoanEntryEntity::class,
        RecurringEntity::class, InvestmentTransferEntity::class, HoldingEntity::class, TradeEntity::class,
        LoanTermsEntity::class, LoanTermChangeEntity::class, LoanPaymentEntity::class,
    ],
    version = DATABASE_VERSION,
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
    abstract fun investmentTransfers(): InvestmentTransferDao
    abstract fun holdings(): HoldingDao
    abstract fun trades(): TradeDao
    abstract fun loanTerms(): LoanTermsDao
    abstract fun loanChanges(): LoanTermChangeDao
    abstract fun loanPayments(): LoanPaymentDao

    companion object {
        const val FILE_NAME = "kassa.db"

        /**
         * Encrypted with SQLCipher. The key lives in the Android Keystore, see [DatabaseKey]. Opens the file at once,
         * so a wrong or lost key fails here rather than on the first query somewhere in the UI.
         */
        fun open(context: Context): KassaDatabase {
            System.loadLibrary("sqlcipher")
            val exists = context.getDatabasePath(FILE_NAME).exists()
            val factory = SupportOpenHelperFactory(DatabaseKey.get(context, exists))
            val db = Room.databaseBuilder(context, KassaDatabase::class.java, FILE_NAME)
                .openHelperFactory(factory)
                .addMigrations(*ALL_MIGRATIONS)
                .build()
            try {
                db.openHelper.writableDatabase
            } catch (e: Throwable) {
                runCatching { db.close() }
                throw e
            }
            return db
        }
    }
}
