package app.kassa.money.importer

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/** The queries that read a Cashew backup (every column of each table, so versions that lack newer columns still read), and the reader. */
object CashewSql {

    private val TABLES = listOf("wallets", "categories", "transactions", "objectives", "budgets", "category_budget_limits")

    /** Tables and columns every Cashew backup Kassa reads has; older backups that lack them are refused. */
    private val REQUIRED = mapOf(
        "wallets" to listOf("wallet_pk", "name"),
        "categories" to listOf("category_pk", "name", "income"),
        "transactions" to listOf("transaction_pk", "amount", "category_fk", "wallet_fk", "date_created", "paid"),
    )

    const val OLD_VERSION = "This Cashew backup is from a version older than Kassa can read. Open it in an up-to-date Cashew, back up again, and pick the new file."

    /** The first three are required; a failed optional one reads as an empty table. */
    fun queries(): List<SqlQuery> =
        TABLES.map { SqlQuery(it, "SELECT * FROM \"$it\"", it !in REQUIRED) } +
            SqlQuery("settings", "SELECT settings_j_s_o_n FROM app_settings LIMIT 1", true)

    /** True when [tables] have Cashew's tables, whatever its version. */
    fun looksLikeCashew(tables: List<String>): Boolean = listOf("wallets", "transactions", "categories").all { it in tables }

    /** Null when [results] hold what a Cashew backup needs, otherwise the sentence that says what to do. */
    fun refusal(results: SqlResults): String? {
        for ((table, columns) in REQUIRED) {
            val t = results.table(table) ?: return OLD_VERSION
            val have = t.columns.map { it.lowercase() }.toSet()
            if (!have.containsAll(columns)) return OLD_VERSION
        }
        return null
    }
}

/** A [CashewSource] over the answers to [CashewSql.queries]. Columns are looked up by name; a missing one gives null or its default. */
class CashewReader(private val results: SqlResults) : CashewSource {

    private fun rows(table: String): List<SqlRow> = results.maybe(table)

    override fun wallets(): List<CwWallet> = rows("wallets").mapNotNull { c ->
        CwWallet(pk = c.str("wallet_pk") ?: return@mapNotNull null, name = c.str("name").orEmpty(), currency = c.str("currency")?.takeIf { it.isNotBlank() }, order = c.int("order") ?: 0)
    }

    override fun categories(): List<CwCategory> = rows("categories").mapNotNull { c ->
        CwCategory(
            pk = c.str("category_pk") ?: return@mapNotNull null, name = c.str("name").orEmpty(), colour = c.str("colour"),
            iconName = c.str("icon_name"), emoji = c.str("emoji_icon_name"), order = c.int("order") ?: 0,
            income = c.bool("income") ?: false, mainCategoryPk = c.str("main_category_pk")?.takeIf { it.isNotBlank() },
        )
    }

    override fun transactions(): List<CwTransaction> = rows("transactions").mapNotNull { c ->
        CwTransaction(
            pk = c.str("transaction_pk") ?: return@mapNotNull null,
            pairedPk = c.str("paired_transaction_fk")?.takeIf { it.isNotBlank() },
            title = c.str("name").orEmpty(), amount = c.decimalText("amount"), note = c.str("note").orEmpty(),
            categoryPk = c.str("category_fk"), subCategoryPk = c.str("sub_category_fk")?.takeIf { it.isNotBlank() },
            walletPk = c.str("wallet_fk"), epochMillis = c.millis("date_created", ::isoMillis),
            income = c.bool("income") ?: false, type = c.int("type"), paid = c.bool("paid") ?: true,
            skipPaid = c.bool("skip_paid") ?: false, reoccurrence = c.int("reoccurrence"), periodLength = c.int("period_length"),
            objectivePk = c.str("objective_fk")?.takeIf { it.isNotBlank() },
            objectiveLoanPk = c.str("objective_loan_fk")?.takeIf { it.isNotBlank() },
        )
    }

    override fun objectives(): List<CwObjective> = rows("objectives").mapNotNull { c ->
        CwObjective(
            pk = c.str("objective_pk") ?: return@mapNotNull null, type = c.int("type") ?: 0, name = c.str("name").orEmpty(),
            amount = c.decimalText("amount"), income = c.bool("income") ?: false, epochMillis = c.millis("date_created", ::isoMillis),
            archived = c.bool("archived") ?: false,
        )
    }

    override fun budgets(): List<CwBudget> = rows("budgets").mapNotNull { c ->
        CwBudget(
            pk = c.str("budget_pk") ?: return@mapNotNull null, name = c.str("name").orEmpty(), amount = c.decimalText("amount"),
            reoccurrence = c.int("reoccurrence"), periodLength = c.int("period_length") ?: 1, archived = c.bool("archived") ?: false,
            absoluteLimits = c.bool("is_absolute_spending_limit") ?: true, order = c.int("order") ?: 0,
        )
    }

    override fun categoryLimits(): List<CwCategoryLimit> = rows("category_budget_limits").mapNotNull { c ->
        CwCategoryLimit(categoryPk = c.str("category_fk") ?: return@mapNotNull null, budgetPk = c.str("budget_fk") ?: return@mapNotNull null, amount = c.decimalText("amount"))
    }

    override fun primaryWalletPk(): String? {
        val json = results.maybe("settings").firstOrNull()?.let { if (it.isNull(0)) null else it.str(0) } ?: return null
        return Regex("\"selectedWalletPk\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)
    }

    private fun isoMillis(text: String): Long = try {
        LocalDateTime.parse(text.trim().replace(' ', 'T')).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
    } catch (e: Exception) {
        0L
    }
}
