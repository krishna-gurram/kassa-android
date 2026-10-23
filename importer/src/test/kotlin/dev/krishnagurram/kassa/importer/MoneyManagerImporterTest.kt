package dev.krishnagurram.kassa.importer

import dev.krishnagurram.kassa.domain.AccountType
import dev.krishnagurram.kassa.domain.CategoryIcon
import dev.krishnagurram.kassa.domain.CategoryKind
import dev.krishnagurram.kassa.domain.TxKind
import org.junit.Test
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Synthetic backup shaped like a real Money Manager export. No real data lives in this repo. */
class MoneyManagerImporterTest {

    private val day = 86_400_000L
    private val t0 = 1_767_225_600_000L // 2026-01-01T00:00Z

    private val source = object : MoneyManagerSource {
        override fun groups() = listOf(
            MmGroup("g-cash", "Cash", 11, 0),
            MmGroup("g-acc", "Accounts", 1, 1),
            MmGroup("g-card", "Credit Card", 2, 2),
            MmGroup("g-inv", "Investments", 8, 3),
        )
        override fun assets() = listOf(
            MmAsset("a-chk", "Chase checking", "g-acc", 1, null, null, "1000"),
            MmAsset("a-amex", "Amex Platinum", "g-card", 2, 2, 27, "0"),
            MmAsset("a-amex2", "amex plat", "g-card", 3, 1, 1, "0"),
            MmAsset("a-sw", "Splitwise debt", "g-card", 4, null, null, "0"),
            MmAsset("a-rh", "Robinhood", "g-inv", 5, 1, 1, "0"),
            MmAsset("a-cash", "Cash", "g-cash", 0, null, null, "40.5"),
        )
        override fun categories() = listOf(
            MmCategory("c-gro", "🛒Groceries ", 1, null, false, 1),
            MmCategory("c-gro-costco", "🛒Costco", 1, "c-gro", false, 1),
            MmCategory("c-home", "🏡 Home Standard Expenses", 1, null, false, 2),
            MmCategory("c-sw", "Short term loan/Splitwise", 1, null, false, 3),
            MmCategory("c-old", "Old stuff", 1, null, true, 4),
            MmCategory("c-pay", "Paycheck", 0, null, false, 0),
        )
        override fun transactions() = listOf(
            MmTransaction("t1", 0, "a-chk", null, "c-pay", "5000", t0, "Paycheck", null),
            MmTransaction("t2", 1, "a-amex", null, "c-gro-costco", "186.42", t0 + day, "Costco", null),
            MmTransaction("t3", 1, "a-amex", null, "c-sw", "40", t0 + day, "Dinner", null),
            MmTransaction("t4", 3, "a-chk", "a-amex", null, "186.42", t0 + 2 * day, "", "p1"),
            MmTransaction("t5", 4, "a-amex", "a-chk", null, "186.42", t0 + 2 * day, "", "p1"),
            MmTransaction("t6", 3, "a-chk", "a-rh", null, "500", t0 + 3 * day, "", "p2"),
            MmTransaction("t7", 4, "a-rh", "a-chk", null, "500", t0 + 3 * day, "", "p2"),
            MmTransaction("t8", 1, "a-cash", null, "c-home", "0", t0 + 4 * day, "", null),
        )
        override fun budgets() = listOf(MmBudget("c-gro", 0, 850.0), MmBudget("c-gro", 202601, 900.0))
    }

    private val plan = MoneyManagerImporter(ZoneOffset.UTC).plan(source)

    @Test
    fun transfersAreImportedOnceFromTheOutHalf() {
        val transfers = plan.transactions.filter { it.kind == TxKind.TRANSFER }
        assertEquals(2, transfers.size)
        val chk = plan.accounts.first { it.name == "Chase checking" }.id
        val amex = plan.accounts.first { it.name == "Amex Platinum" }.id
        assertTrue(transfers.any { it.accountId == chk && it.toAccountId == amex && it.amount == 18_642L })
    }

    @Test
    fun reconcileMatchesWhenNothingIsLost() {
        assertTrue(plan.reconcile.allMatch, plan.reconcile.toString())
        val chk = plan.reconcile.accounts.first { it.name == "Chase checking" }
        assertEquals(100_000 + 500_000 - 18_642 - 50_000, chk.imported)
    }

    @Test
    fun categoriesKeepHierarchyIconsAndBillFlag() {
        val gro = plan.categories.first { it.name == "Groceries" }
        val costco = plan.categories.first { it.name == "Costco" }
        assertEquals(CategoryIcon.Emoji("🛒"), gro.icon)
        assertEquals(gro.id, costco.parentId)
        assertEquals(gro.color, costco.color)
        assertTrue(gro.countsTowardDaily)
        assertTrue(!plan.categories.first { it.name == "Home Standard Expenses" }.countsTowardDaily)
        assertTrue(plan.categories.first { it.name == "Old stuff" }.hidden)
        assertEquals(CategoryKind.INCOME, plan.categories.first { it.name == "Paycheck" }.kind)
    }

    @Test
    fun accountsGetTypesCardDaysAndPayFrom() {
        val amex = plan.accounts.first { it.name == "Amex Platinum" }
        assertEquals(AccountType.CREDIT_CARD, amex.type)
        assertEquals(2, amex.statementDay)
        assertEquals(27, amex.dueDay)
        assertEquals(plan.accounts.first { it.name == "Chase checking" }.id, amex.payFromId)
        val rh = plan.accounts.first { it.name == "Robinhood" }
        assertEquals(AccountType.INVESTMENT, rh.type)
        assertEquals(null, rh.statementDay)
        assertEquals(4_050, plan.accounts.first { it.name == "Cash" }.openingBalance)
    }

    @Test
    fun reviewFlagsDuplicatesSplitwiseAndZeroAmounts() {
        val dupes = plan.issues.filterIsInstance<ImportIssue.PossibleDuplicateAccounts>()
        assertEquals(listOf(listOf("Amex Platinum", "amex plat")), dupes.map { it.names })
        val sw = plan.issues.filterIsInstance<ImportIssue.SplitwiseRows>().single()
        assertEquals(listOf("Splitwise debt"), sw.accountNames)
        assertEquals(1, sw.entries)
        assertEquals(1, plan.issues.filterIsInstance<ImportIssue.ZeroAmountEntries>().single().count)
    }

    @Test
    fun budgetsKeepDefaultAndMonthOverride() {
        val gro = plan.categories.first { it.name == "Groceries" }.id
        assertEquals(setOf(0 to 85_000L, 202601 to 90_000L), plan.budgets.filter { it.categoryId == gro }.map { it.month to it.amount }.toSet())
    }

    @Test
    fun splitsLeadingEmojiFromName() {
        assertEquals(CategoryIcon.Emoji("🧘🏼") to "Gym", MoneyManagerImporter.splitEmoji("🧘🏼 Gym"))
        assertEquals(CategoryIcon.DEFAULT to "Amazon", MoneyManagerImporter.splitEmoji("Amazon "))
        assertEquals(30, MoneyManagerImporter.splitEmoji("🛒" + "g".repeat(50)).second.length)
    }

    @Test
    fun namesAreTidiedCappedAndMadeUnique() {
        assertEquals("Chase Sapphire", MoneyManagerImporter.fit("  Chase\n  Sapphire ", 40))
        assertEquals(40, MoneyManagerImporter.fit("x".repeat(90), 40).length)
        assertEquals(listOf("Cash", "cash 2", "Wallet", "Cash 3"), MoneyManagerImporter.uniqueNames(listOf("Cash", "cash", "Wallet", "Cash")))
    }
}
