import Foundation

/// The tables and indexes of the Kassa database on iOS.
///
/// THE ANDROID ROOM SCHEMA IS THE SOURCE OF TRUTH. `Room schema 10` (app/schemas/app.kassa.money.data.db.KassaDatabase/10.json)
/// is copied here statement for statement (its `createSql` with the table name filled in; `room_master_table` left out), so
/// columns, types, nullability, defaults and index names are the same on both platforms. A schema bump on Android
/// (`DATABASE_VERSION`) must change this file, `version` below, `BackupRows.kt` and `docs/BACKUP_FORMAT.md` in the same commit.
/// `settings` and `icons` are Swift-only: they hold what a backup carries outside the Room tables (see `LedgerStore`).
enum Schema {

    /// `PRAGMA user_version` of a database made by this code; Room's `DATABASE_VERSION`.
    static let version = 10

    /// The Room tables, in the order of `BackupRows.tableNames()`. Rows are deleted in reverse and inserted in this order.
    static let dataTables: [String] = [
        "account_groups",
        "accounts",
        "categories",
        "transactions",
        "budgets",
        "people",
        "loan_entries",
        "recurring",
        "investment_transfers",
        "holdings",
        "trades",
        "loan_terms",
        "loan_term_changes",
        "loan_payments",
    ]

    /// Everything that is created in a new database: the Room statements, then the Swift-only tables.
    static let statements: [String] = [
        "CREATE TABLE IF NOT EXISTS `account_groups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `accounts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `groupId` INTEGER, `openingBalance` INTEGER NOT NULL, `statementDay` INTEGER, `dueDay` INTEGER, `payFromId` INTEGER, `creditLimit` INTEGER, `archived` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `externalId` TEXT, `last4` TEXT, `maturityDay` INTEGER, `closedDay` INTEGER)",
        "CREATE INDEX IF NOT EXISTS `index_accounts_groupId` ON `accounts` (`groupId`)",
        "CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `parentId` INTEGER, `icon` TEXT NOT NULL, `color` INTEGER NOT NULL, `countsTowardDaily` INTEGER NOT NULL, `hidden` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `externalId` TEXT)",
        "CREATE INDEX IF NOT EXISTS `index_categories_parentId` ON `categories` (`parentId`)",
        "CREATE TABLE IF NOT EXISTS `transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `amount` INTEGER NOT NULL, `accountId` INTEGER, `toAccountId` INTEGER, `categoryId` INTEGER, `personId` INTEGER, `epochMillis` INTEGER NOT NULL, `note` TEXT NOT NULL, `myShare` INTEGER, `externalId` TEXT)",
        "CREATE INDEX IF NOT EXISTS `index_transactions_epochMillis` ON `transactions` (`epochMillis`)",
        "CREATE INDEX IF NOT EXISTS `index_transactions_accountId` ON `transactions` (`accountId`)",
        "CREATE INDEX IF NOT EXISTS `index_transactions_toAccountId` ON `transactions` (`toAccountId`)",
        "CREATE INDEX IF NOT EXISTS `index_transactions_categoryId` ON `transactions` (`categoryId`)",
        "CREATE INDEX IF NOT EXISTS `index_transactions_personId` ON `transactions` (`personId`)",
        "CREATE TABLE IF NOT EXISTS `budgets` (`categoryId` INTEGER NOT NULL, `month` INTEGER NOT NULL, `amount` INTEGER NOT NULL, PRIMARY KEY(`categoryId`, `month`))",
        "CREATE TABLE IF NOT EXISTS `people` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `archived` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `loan_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `personId` INTEGER NOT NULL, `amount` INTEGER NOT NULL, `epochMillis` INTEGER NOT NULL, `note` TEXT NOT NULL, `txId` INTEGER)",
        "CREATE INDEX IF NOT EXISTS `index_loan_entries_personId` ON `loan_entries` (`personId`)",
        "CREATE INDEX IF NOT EXISTS `index_loan_entries_txId` ON `loan_entries` (`txId`)",
        "CREATE TABLE IF NOT EXISTS `recurring` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `categoryId` INTEGER NOT NULL, `accountId` INTEGER NOT NULL, `dayOfMonth` INTEGER NOT NULL, `fixedAmount` INTEGER, `active` INTEGER NOT NULL, `handledMonth` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `investment_transfers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `transactionId` INTEGER NOT NULL, `accountId` INTEGER NOT NULL, `amount` INTEGER NOT NULL, `epochMillis` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_investment_transfers_accountId` ON `investment_transfers` (`accountId`)",
        "CREATE TABLE IF NOT EXISTS `holdings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `accountId` INTEGER NOT NULL, `ticker` TEXT NOT NULL, `microShares` INTEGER NOT NULL, `price` INTEGER NOT NULL, `priceMillis` INTEGER NOT NULL, `avgCost` INTEGER)",
        "CREATE INDEX IF NOT EXISTS `index_holdings_accountId` ON `holdings` (`accountId`)",
        "CREATE TABLE IF NOT EXISTS `trades` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `transactionId` INTEGER NOT NULL, `ticker` TEXT NOT NULL, `microShares` INTEGER NOT NULL, `price` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_trades_transactionId` ON `trades` (`transactionId`)",
        "CREATE TABLE IF NOT EXISTS `loan_terms` (`accountId` INTEGER NOT NULL, `type` TEXT NOT NULL, `profit` INTEGER NOT NULL, `rate` INTEGER NOT NULL, `payment` INTEGER NOT NULL, `termMonths` INTEGER, `originalAmount` INTEGER, `startDay` INTEGER, `nextPaymentDay` INTEGER NOT NULL, `dueDay` INTEGER NOT NULL, `escrow` INTEGER NOT NULL, `fee` INTEGER NOT NULL, `earlyCharge` INTEGER NOT NULL, `interestCategoryId` INTEGER, `payFromId` INTEGER, `countAllAsSpending` INTEGER NOT NULL DEFAULT 0, `principalCategoryId` INTEGER, PRIMARY KEY(`accountId`))",
        "CREATE TABLE IF NOT EXISTS `loan_term_changes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `accountId` INTEGER NOT NULL, `effectiveDay` INTEGER NOT NULL, `rate` INTEGER, `payment` INTEGER, `termMonths` INTEGER, `type` TEXT)",
        "CREATE INDEX IF NOT EXISTS `index_loan_term_changes_accountId` ON `loan_term_changes` (`accountId`)",
        "CREATE TABLE IF NOT EXISTS `loan_payments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `accountId` INTEGER NOT NULL, `day` INTEGER NOT NULL, `amount` INTEGER NOT NULL, `principalEntryId` INTEGER, `interestEntryId` INTEGER, `otherEntryId` INTEGER, `escrow` INTEGER NOT NULL, `charge` INTEGER NOT NULL, `edited` INTEGER NOT NULL, `estimate` INTEGER NOT NULL, `scheduled` INTEGER NOT NULL, `countAllAsSpending` INTEGER, `principalCategoryId` INTEGER)",
        "CREATE INDEX IF NOT EXISTS `index_loan_payments_accountId` ON `loan_payments` (`accountId`)",
        "CREATE INDEX IF NOT EXISTS `index_loan_payments_principalEntryId` ON `loan_payments` (`principalEntryId`)",
        "CREATE INDEX IF NOT EXISTS `index_loan_payments_interestEntryId` ON `loan_payments` (`interestEntryId`)",
        "CREATE INDEX IF NOT EXISTS `index_loan_payments_otherEntryId` ON `loan_payments` (`otherEntryId`)",
        "CREATE TABLE IF NOT EXISTS settings (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS icons (name TEXT NOT NULL PRIMARY KEY, bytes BLOB NOT NULL)",
    ]
}
