package dev.krishnagurram.kassa.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.krishnagurram.kassa.data.security.DatabaseKey
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** Bump with every schema change, and add the migration to [ALL_MIGRATIONS]. */
const val DATABASE_VERSION = 5

/**
 * Kassa's database. Changing an entity means bumping [version] and adding a migration in Migrations.kt;
 * CI installs the last release first and upgrades over it, so a missing or wrong migration fails the build.
 */
@Database(
    entities = [
        GroupEntity::class, AccountEntity::class, CategoryEntity::class, TransactionEntity::class, BudgetEntity::class,
        PersonEntity::class, LoanEntryEntity::class,
        RecurringEntity::class, InvestmentTransferEntity::class, HoldingEntity::class,
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

    companion object {
        /** Encrypted with SQLCipher. The key lives in the Android Keystore, see [DatabaseKey]. */
        fun open(context: Context): KassaDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(DatabaseKey.get(context))
            return Room.databaseBuilder(context, KassaDatabase::class.java, "kassa.db")
                .openHelperFactory(factory)
                .addMigrations(*ALL_MIGRATIONS)
                .build()
        }
    }
}
