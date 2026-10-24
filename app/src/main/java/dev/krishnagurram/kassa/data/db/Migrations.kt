package dev.krishnagurram.kassa.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/*
 * Each migration moves the database one version forward. Column definitions must match the entities exactly
 * (type, nullability, index names `index_<table>_<column>`), or Room refuses to open the upgraded database.
 */

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

/** v4: accounts can carry the last four digits of their number. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `accounts` ADD COLUMN `last4` TEXT")
    }
}

/**
 * v5: bank alert capture is gone. Its rules table is dropped, and the pending items table, which held alerts and
 * investment transfers, becomes investment_transfers. Waiting transfers carry over; waiting alerts are dropped.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `investment_transfers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`transactionId` INTEGER NOT NULL, `accountId` INTEGER NOT NULL, `amount` INTEGER NOT NULL, `epochMillis` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_investment_transfers_accountId` ON `investment_transfers` (`accountId`)")
        db.execSQL(
            "INSERT INTO `investment_transfers` (`id`, `transactionId`, `accountId`, `amount`, `epochMillis`) " +
                "SELECT `id`, `txId`, `accountId`, `amount`, `epochMillis` FROM `pending_items` " +
                "WHERE `type` = 'ALLOCATE' AND `txId` IS NOT NULL AND `accountId` IS NOT NULL"
        )
        db.execSQL("DROP TABLE IF EXISTS `pending_items`")
        db.execSQL("DROP TABLE IF EXISTS `alert_rules`")
    }
}

/** v6: holdings can carry an average cost per share, for profit and loss. Existing holdings start without one. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `holdings` ADD COLUMN `avgCost` INTEGER")
    }
}

/** Every migration, oldest first. */
val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
