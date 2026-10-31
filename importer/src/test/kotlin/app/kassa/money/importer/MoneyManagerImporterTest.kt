package app.kassa.money.importer

import app.kassa.money.domain.AccountType
import app.kassa.money.domain.CategoryIcon
import app.kassa.money.domain.CategoryKind
import app.kassa.money.domain.TxKind
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
            MmAsset("a-amex", "Amex Platinum", "g-card", 2, 2, 27, "0", payFromUid = "a-chk"),
            MmAsset("a-amex2", "amex plat", "g-card", 3, 1, 1, "0"),
            MmAsset("a-sw", "Friends debt", "g-card", 4, null, null, "0"),
            MmAsset("a-rh", "Robinhood", "g-inv", 5, 1, 1, "0"),
            MmAsset("a-cash", "Cash", "g-cash", 0, null, null, "40.5"),
        )
        override fun categories() = listOf(
            MmCategory("c-gro", "🛒Groceries ", 1, null, false, 1),
            MmCategory("c-gro-costco", "🛒Costco", 1, "c-gro", false, 1),
            MmCategory("c-home", "🏡 Home Standard Expenses", 1, null, false, 2),
            MmCategory("c-sw", "Short term loan/Friends", 1, null, false, 3),
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
        // No link in the backup: nothing is guessed.
        assertEquals(null, plan.accounts.first { it.name == "amex plat" }.payFromId)
        val rh = plan.accounts.first { it.name == "Robinhood" }
        assertEquals(AccountType.INVESTMENT, rh.type)
        assertEquals(null, rh.statementDay)
        assertEquals(4_050, plan.accounts.first { it.name == "Cash" }.openingBalance)
    }

    @Test
    fun reviewFlagsDuplicatesAndZeroAmounts() {
        val dupes = plan.issues.filterIsInstance<ImportIssue.PossibleDuplicateAccounts>()
        assertEquals(listOf(listOf("Amex Platinum", "amex plat")), dupes.map { it.names })
        // Nothing is recognised by name: an account that stands in for a person is converted by the user, in Settings.
        assertEquals(listOf("Friends debt"), plan.accounts.map { it.name }.filter { it.startsWith("Friends") })
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

    @Test
    fun categoriesDifferingOnlyInCapitalsAreCombined() {
        val dupes = object : MoneyManagerSource by source {
            override fun categories() = listOf(
                MmCategory("c-ph1", "Phone bill", 1, null, true, 1),
                MmCategory("c-ph2", "Phone Bill", 1, null, false, 2),
                MmCategory("c-shop", "Shopping", 1, null, false, 3),
                MmCategory("c-b1", "Beauty", 1, "c-shop", false, 1),
                MmCategory("c-b2", "beauty", 1, "c-shop", false, 2),
                MmCategory("c-pay", "Paycheck", 0, null, false, 0),
            )
            override fun transactions() = listOf(
                MmTransaction("t1", 1, "a-chk", null, "c-ph1", "50", t0, "", null),
                MmTransaction("t2", 1, "a-chk", null, "c-ph2", "55", t0 + day, "", null),
                MmTransaction("t3", 1, "a-chk", null, "c-b2", "20", t0 + day, "", null),
            )
            override fun budgets() = listOf(MmBudget("c-ph1", 0, 60.0), MmBudget("c-ph2", 0, 70.0))
        }
        val p = MoneyManagerImporter(ZoneOffset.UTC).plan(dupes)
        val phone = p.categories.filter { it.name.equals("phone bill", ignoreCase = true) }.single()
        assertTrue(!phone.hidden, "a visible duplicate keeps the merged category visible")
        assertEquals(1, p.categories.count { it.name.equals("beauty", ignoreCase = true) })
        assertEquals(2, p.transactions.count { it.categoryId == phone.id })
        assertEquals(1, p.budgets.count { it.categoryId == phone.id })
        assertTrue(p.issues.any { it is ImportIssue.MergedCategories })
    }

    @Test
    fun cardPayFromIsOnlyALinkedNonCardAccount() {
        val linked = object : MoneyManagerSource by source {
            override fun assets() = listOf(
                MmAsset("a-chk", "Everyday", "g-acc", 1, null, null, "0"),
                MmAsset("a-c1", "Card one", "g-card", 2, 5, 20, "0", payFromUid = "a-chk"),
                MmAsset("a-c2", "Card two", "g-card", 3, 5, 20, "0", payFromUid = "a-c1"),
                MmAsset("a-c3", "Card three", "g-card", 4, 5, 20, "0", payFromUid = "a-missing"),
                MmAsset("a-c4", "Card four", "g-card", 5, 5, 20, "0"),
            )
            override fun transactions() = emptyList<MmTransaction>()
        }
        val p = MoneyManagerImporter(ZoneOffset.UTC).plan(linked)
        val chk = p.accounts.first { it.name == "Everyday" }.id
        assertEquals(chk, p.accounts.first { it.name == "Card one" }.payFromId)
        listOf("Card two", "Card three", "Card four").forEach { assertEquals(null, p.accounts.first { a -> a.name == it }.payFromId, it) }
        assertEquals(3, p.issues.filterIsInstance<ImportIssue.CardsWithoutPayFrom>().single().count)
    }

    @Test
    fun deletedAccountsAreSkippedAndCounted() {
        val withDeleted = object : MoneyManagerSource by source {
            override fun assets() = listOf(
                MmAsset("a-chk", "Everyday", "g-acc", 1, null, null, "100"),
                MmAsset("a-old", "Old wallet", "g-cash", 2, null, null, "0", deleted = true),
            )
            override fun transactions() = listOf(
                MmTransaction("t1", 1, "a-chk", null, "c-gro", "10", t0, "Market", null),
                MmTransaction("t2", 1, "a-old", null, "c-gro", "7", t0, "Gone", null),
                MmTransaction("t3", 3, "a-chk", "a-old", null, "20", t0 + day, "", "p1"),
                MmTransaction("t4", 4, "a-old", "a-chk", null, "20", t0 + day, "", "p1"),
                MmTransaction("t5", 3, "a-old", "a-chk", null, "5", t0 + 2 * day, "", "p2"),
                MmTransaction("t6", 4, "a-chk", "a-old", null, "5", t0 + 2 * day, "", "p2"),
            )
        }
        val p = MoneyManagerImporter(ZoneOffset.UTC).plan(withDeleted)
        assertEquals(listOf("Everyday"), p.accounts.map { it.name })
        assertEquals(1, p.issues.filterIsInstance<ImportIssue.DeletedAccounts>().single().count)
        assertEquals(2, p.issues.filterIsInstance<ImportIssue.TransfersAsEntries>().single().count)
        assertTrue(p.issues.none { it is ImportIssue.SkippedRows }, p.issues.toString())
        assertEquals(10_000L - 1_000 - 2_000 + 500, p.reconcile.accounts.single().imported)
        assertTrue(p.reconcile.allMatch, p.reconcile.toString())
    }

    @Test
    fun unknownEntryKindsAreCountedApart() {
        val odd = object : MoneyManagerSource by source {
            override fun transactions() = listOf(
                MmTransaction("t1", 1, "a-chk", null, "c-gro", "10", t0, "", null),
                MmTransaction("t2", 7, "a-chk", null, null, "3", t0, "", null),
                MmTransaction("t3", 9, "a-chk", null, null, "4", t0, "", null),
                MmTransaction("t4", 1, "a-nowhere", null, "c-gro", "5", t0, "", null),
            )
        }
        val skipped = MoneyManagerImporter(ZoneOffset.UTC).plan(odd).issues.filterIsInstance<ImportIssue.SkippedRows>()
        assertEquals(2, skipped.single { it.reason == "Entries of a kind Kassa doesn't have" }.count)
        assertEquals(1, skipped.single { it.reason == "Rows with an unreadable amount or a missing account" }.count)
    }

    @Test
    fun entryInAnotherCurrencyUsesTheAccountAmount() {
        val fx = object : MoneyManagerSource by source {
            override fun mainCurrencyUid() = "cur-main"
            override fun assets() = listOf(
                MmAsset("a-chk", "Everyday", "g-acc", 1, null, null, "0", currencyUid = "cur-main"),
                MmAsset("a-trip", "Travel wallet", "g-cash", 2, null, null, "0", currencyUid = "cur-other"),
            )
            override fun transactions() = listOf(
                MmTransaction("t1", 1, "a-chk", null, "c-gro", "30", t0, "Abroad", null, currencyUid = "cur-other", accountAmount = "33.33"),
                MmTransaction("t2", 1, "a-chk", null, "c-gro", "12", t0, "Home", null, currencyUid = "cur-main", accountAmount = "99"),
            )
        }
        val p = MoneyManagerImporter(ZoneOffset.UTC).plan(fx)
        assertEquals(listOf(3_333L, 1_200L), p.transactions.map { it.amount })
        assertTrue(p.reconcile.allMatch, p.reconcile.toString())
        assertEquals(-4_533L, p.reconcile.accounts.first { it.name == "Everyday" }.source)
        assertEquals(listOf("Travel wallet"), p.issues.filterIsInstance<ImportIssue.OtherCurrency>().single().accounts)
    }

    @Test
    fun totalAndNonMonthlyBudgetsStayBehind() {
        val b = object : MoneyManagerSource by source {
            override fun budgets() = listOf(
                MmBudget("c-gro", 0, 400.0, budgetUid = "b1"),
                MmBudget("c-gro", 202601, 450.0, budgetUid = "b1"),
                MmBudget("c-home", 0, 2000.0, isTotal = true, budgetUid = "b2"),
                MmBudget("c-home", 202601, 2100.0, isTotal = true, budgetUid = "b2"),
                MmBudget("c-pay", 0, 90.0, periodType = 1, budgetUid = "b3"),
            )
        }
        val p = MoneyManagerImporter(ZoneOffset.UTC).plan(b)
        val gro = p.categories.first { it.name == "Groceries" }.id
        assertEquals(setOf(gro), p.budgets.map { it.categoryId }.toSet())
        assertEquals(2, p.budgets.size)
        val left = p.issues.filterIsInstance<ImportIssue.LeftBehind>().associate { it.what to it.count }
        assertEquals(1, left["Budget totals (only their category budgets come over)"])
        assertEquals(1, left["Budgets that aren't monthly"])
    }

    @Test
    fun staysBehindListsOnlyWhatTheBackupHas() {
        assertTrue(plan.issues.none { it is ImportIssue.LeftBehind }, plan.issues.toString())
        val some = object : MoneyManagerSource by source {
            override fun extras() = MmExtras(repeating = 2, installmentPlans = 1, tags = 3, taggedEntries = 8, memos = 0)
        }
        val left = MoneyManagerImporter(ZoneOffset.UTC).plan(some).issues.filterIsInstance<ImportIssue.LeftBehind>()
        assertEquals(
            listOf(
                "Repeating entries" to 2,
                "Installment plans (each month's charge still comes over)" to 1,
                "Tags, used on 8 entries" to 3,
            ),
            left.map { it.what to it.count },
        )
        val all = object : MoneyManagerSource by source {
            override fun extras() = MmExtras(1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        }
        assertEquals(9, MoneyManagerImporter(ZoneOffset.UTC).plan(all).issues.count { it is ImportIssue.LeftBehind })
    }
}
