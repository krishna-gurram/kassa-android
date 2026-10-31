package app.kassa.money.data.backup

import app.kassa.money.data.db.AccountEntity
import app.kassa.money.data.db.BudgetEntity
import app.kassa.money.data.db.CategoryEntity
import app.kassa.money.data.db.GroupEntity
import app.kassa.money.data.db.HoldingEntity
import app.kassa.money.data.db.InvestmentTransferEntity
import app.kassa.money.data.db.LoanEntryEntity
import app.kassa.money.data.db.LoanPaymentEntity
import app.kassa.money.data.db.LoanTermChangeEntity
import app.kassa.money.data.db.LoanTermsEntity
import app.kassa.money.data.db.PersonEntity
import app.kassa.money.data.db.RecurringEntity
import app.kassa.money.data.db.TradeEntity
import app.kassa.money.data.db.TransactionEntity
import org.junit.Test
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Synthetic data shaped like a real ledger: every table, nullable columns both set and empty. */
internal fun sampleContents() = BackupContents(
    createdAt = 1_760_000_000_000,
    monthStartDay = 16,
    currency = "INR",
    groups = listOf(GroupEntity(1, "Bank", 0)),
    accounts = listOf(
        AccountEntity(1, "Everyday checking", "CHECKING", 1, 150_000, null, null, null, null, false, 0, "mm-1", null),
        AccountEntity(2, "Travel card", "CREDIT_CARD", null, -42_000, 16, 10, 1, 500_000, false, 1, null, "1004"),
        AccountEntity(3, "Car loan", "LOAN", null, -2_148_000, null, null, null, null, false, 2, null, null),
        AccountEntity(4, "Term deposit", "FIXED_DEPOSIT", 1, 300_000, null, null, null, null, false, 3, null, null, maturityDay = 20_800),
        AccountEntity(5, "Old card", "CREDIT_CARD", null, 0, 16, 10, null, null, false, 4, null, "0099", closedDay = 20_000),
    ),
    categories = listOf(
        CategoryEntity(1, "Food", "EXPENSE", null, "emoji:🍜", -3_000_000, true, false, 0, null),
        CategoryEntity(2, "Groceries", "EXPENSE", 1, "symbol:shopping_cart", -3_000_000, true, false, 0, "mm-c2"),
    ),
    transactions = listOf(
        TransactionEntity(1, "EXPENSE", 6_000, 2, null, 2, null, 1_760_000_100_000, "Corner market", 2_000, null),
        TransactionEntity(2, "EXPENSE", 3_000, null, null, 1, 1, 1_760_000_200_000, "Dinner, Sam paid", 1_500, null),
        TransactionEntity(3, "TRANSFER", 10_000, 1, 2, null, null, 1_760_000_300_000, "", null, "mm-t3"),
        TransactionEntity(4, "TRANSFER", 47_260, 1, 3, null, null, 1_760_000_400_000, "Car loan principal", null, null),
        TransactionEntity(5, "EXPENSE", 10_740, 1, null, 2, null, 1_760_000_400_000, "Car loan interest", null, null),
        TransactionEntity(6, "ADJUST_IN", 2_480, 3, null, null, null, 1_760_000_500_000, "", null, null),
        TransactionEntity(7, "ADJUST_OUT", 1_000, 1, null, null, null, 1_760_000_500_000, "", null, null),
    ),
    budgets = listOf(BudgetEntity(1, 0, 40_000), BudgetEntity(1, 202610, 45_000)),
    people = listOf(PersonEntity(1, "Sam", false), PersonEntity(2, "Alex", true)),
    loans = listOf(LoanEntryEntity(1, 2, 4_000, 1_760_000_100_000, "Corner market", 1), LoanEntryEntity(2, 1, -1_500, 1_760_000_200_000, "Dinner, Sam paid", 2)),
    bills = listOf(RecurringEntity(1, "Rent", 1, 1, 1, 120_000, true, 202609)),
    holdings = listOf(HoldingEntity(1, 1, "VTI", 2_500_000, 25_050, 1_760_000_000_000, 21_333), HoldingEntity(2, 1, "SCHD", 80_000_000, 2_750, 1_760_000_000_000)),
        trades = listOf(TradeEntity(1, 3, "VTI", 2_500_000, 25_050)),
    loanTerms = listOf(
        LoanTermsEntity(3, "REDUCING", false, 61_250, 58_000, 60, 3_000_000, 20_500, 20_758, 1, 20_000, 15_000, 10_000, 2, 1, countAllAsSpending = true, principalCategoryId = 1),
    ),
    loanChanges = listOf(
        LoanTermChangeEntity(1, 3, 21_000, 72_500, null, null, null),
        LoanTermChangeEntity(2, 3, 21_400, null, 60_000, 24, "REDUCING"),
    ),
    loanPayments = listOf(
        LoanPaymentEntity(1, 3, 20_758, 78_000, 4, 5, null, 20_000, 0, true, 10_740, true, countAllAsSpending = false, principalCategoryId = 2),
        LoanPaymentEntity(2, 3, 20_800, 58_000, null, null, null, 0, 0, false, 0, true),
    ),
    icons = mapOf("photo_1.jpg" to byteArrayOf(1, 2, 3, -1)),
    accountArrangement = "CREDIT_CARDS,CASH_AND_BANK|CREDIT_CARDS=2;CASH_AND_BANK=1",
    investmentTransfers = listOf(InvestmentTransferEntity(1, 3, 1, 10_000, 1_760_000_300_000)),
)

class BackupContentsTest {
    private val encode: (ByteArray) -> String = { Base64.getEncoder().encodeToString(it) }
    private val decode: (String) -> ByteArray = { Base64.getDecoder().decode(it) }

    @Test
    fun waitingInvestmentTransfersSurviveAndOlderBackupsReadNone() {
        val back = BackupContents.fromJson(sampleContents().toJson(encode), decode)
        assertEquals(sampleContents().investmentTransfers, back.investmentTransfers)
        val old = JsonTree.edit(sampleContents().toJson(encode)) { it.remove("investmentTransfers") }
        assertTrue(BackupContents.fromJson(old, decode).investmentTransfers.isEmpty())
    }

    @Test
    fun aBackupWithoutAnArrangementReadsItAsAbsent() {
        val json = sampleContents().copy(accountArrangement = null).toJson(encode)
        assertEquals(null, BackupContents.fromJson(json, decode).accountArrangement)
    }

    @Test
    fun everyTableSurvivesTheJsonRoundTrip() {
        val original = sampleContents()
        val back = BackupContents.fromJson(original.toJson(encode), decode)
        // Byte arrays compare by identity in a data class, so icons are checked on their own.
        assertEquals(original.copy(icons = emptyMap()), back.copy(icons = emptyMap()))
        assertEquals(original.icons.keys, back.icons.keys)
        assertContentEquals(original.icons.getValue("photo_1.jpg"), back.icons.getValue("photo_1.jpg"))
    }

    @Test
    fun loansWithNothingOptionalSetRoundTripToo() {
        val bare = sampleContents().copy(
            loanTerms = listOf(LoanTermsEntity(3, "INTEREST_ONLY", true, 0, 0, null, null, null, 20_758, 31, 0, 0, 0, null, null)),
            loanChanges = listOf(LoanTermChangeEntity(1, 3, 21_000, null, null, null, null)),
            loanPayments = listOf(LoanPaymentEntity(1, 3, 20_758, 100, null, null, null, 0, 0, false, 0, false)),
        )
        val back = BackupContents.fromJson(bare.toJson(encode), decode)
        assertEquals(bare.loanTerms, back.loanTerms)
        assertEquals(bare.loanChanges, back.loanChanges)
        assertEquals(bare.loanPayments, back.loanPayments)
    }

    @Test
    fun aBackupFromBeforeLoansKnewTheirTermsStillReads() {
        val old = JsonTree.edit(sampleContents().toJson(encode)) { it.remove("loanTerms"); it.remove("loanChanges"); it.remove("loanPayments") }
        val back = BackupContents.fromJson(old, decode)
        assertTrue(back.loanTerms.isEmpty())
        assertTrue(back.loanChanges.isEmpty())
        assertTrue(back.loanPayments.isEmpty())
        // The loan account and its entries are still there; it is a plain loan account again.
        assertEquals("LOAN", back.accounts.first { it.id == 3L }.type)
        assertEquals(7, back.transactions.size)
    }

    @Test
    fun maturityAndClosedDaysSurviveAndOlderBackupsReadThemAsNull() {
        val back = BackupContents.fromJson(sampleContents().toJson(encode), decode)
        assertEquals(20_800, back.accounts.first { it.id == 4L }.maturityDay)
        assertEquals(20_000, back.accounts.first { it.id == 5L }.closedDay)
        // A backup made before schema 9 has neither key on any account.
        val old = JsonTree.edit(sampleContents().toJson(encode)) { root ->
            JsonTree.rows(root, "accounts").forEach { it.remove("maturityDay"); it.remove("closedDay") }
        }
        val older = BackupContents.fromJson(old, decode)
        assertTrue(older.accounts.all { it.maturityDay == null && it.closedDay == null })
        assertEquals(sampleContents().accounts.size, older.accounts.size)
    }

    @Test
    fun theSpendingSwitchSurvivesAndOlderBackupsReadItsDefaults() {
        val back = BackupContents.fromJson(sampleContents().toJson(encode), decode)
        assertEquals(true, back.loanTerms.single().countAllAsSpending)
        assertEquals(1L, back.loanTerms.single().principalCategoryId)
        assertEquals(false, back.loanPayments.first { it.id == 1L }.countAllAsSpending)
        assertEquals(2L, back.loanPayments.first { it.id == 1L }.principalCategoryId)
        // A payment that follows its loan stays null rather than becoming false.
        assertEquals(null, back.loanPayments.first { it.id == 2L }.countAllAsSpending)
        // A backup made before schema 10 has none of these keys: the loan doesn't count, payments follow it.
        val old = JsonTree.edit(sampleContents().toJson(encode)) { root ->
            for (name in listOf("loanTerms", "loanPayments")) JsonTree.rows(root, name).forEach { it.remove("countAllAsSpending"); it.remove("principalCategoryId") }
        }
        val older = BackupContents.fromJson(old, decode)
        assertEquals(false, older.loanTerms.single().countAllAsSpending)
        assertEquals(null, older.loanTerms.single().principalCategoryId)
        assertTrue(older.loanPayments.all { it.countAllAsSpending == null && it.principalCategoryId == null })
    }

    @Test
    fun aFileRoundTripKeepsEverything() {
        val json = sampleContents().toJson(encode)
        val bytes = BackupFile.encode(json, "a long password".toCharArray())
        assertEquals(json, BackupFile.decode(bytes, "a long password".toCharArray()))
    }

    @Test
    fun jsonThatIsNotABackupIsRefused() {
        assertFailsWith<BackupFile.NotABackup> { BackupContents.fromJson("""{"accounts":[{"id":"x"}]}""", decode) }
    }

    @Test
    fun theFileSaysItsVersionAndANewerOneIsRefused() {
        val json = sampleContents().toJson(encode)
        assertEquals(1, (JsonTree.parse(json) as Map<*, *>)["version"].let { (it as Long).toInt() })
        assertEquals(BackupContents.VERSION, 1)
        val newer = JsonTree.edit(json) { it["version"] = 2L }
        val e = assertFailsWith<BackupFile.NewerVersion> { BackupContents.fromJson(newer, decode) }
        assertEquals("This backup is from a newer version of Kassa. Update Kassa to open it.", e.message)
        // The version comes first in every file Kassa writes, so a newer file is refused before anything after it is read.
        val garbledAfter = """{"version":9,"transactions":[{"id":"x"""
        assertFailsWith<BackupFile.NewerVersion> { BackupContents.fromJson(garbledAfter, decode) }
    }

    @Test
    fun theCurrentAndEarlierVersionsAndAFileWithoutOneStillRead() {
        val json = sampleContents().toJson(encode)
        assertEquals(sampleContents().transactions, BackupContents.fromJson(json, decode).transactions)
        val none = JsonTree.edit(json) { it.remove("version") }
        assertEquals(sampleContents().transactions, BackupContents.fromJson(none, decode).transactions)
        val zero = JsonTree.edit(json) { it["version"] = 0L }
        assertEquals(sampleContents().transactions, BackupContents.fromJson(zero, decode).transactions)
    }

    @Test
    fun keysFromALaterKassaAreSkippedWhileTheVersionStillMatches() {
        val json = JsonTree.edit(sampleContents().toJson(encode)) { root ->
            root["somethingNew"] = mapOf("nested" to listOf(1L, 2L, mapOf("deep" to true)))
            JsonTree.rows(root, "accounts").forEach { it["colour"] = "teal" }
        }
        val back = BackupContents.fromJson(json, decode)
        assertEquals(sampleContents().accounts, back.accounts)
    }

    @Test
    fun aFileWrittenByTheEarlierOrgJsonCodeStillReads() {
        // org.json escapes slashes, writes keys in any order, and puts no space anywhere.
        val old = """{"icons":{"a\/b.jpg":"AQID"},"transactions":[{"note":"Fish \/ chips \u00e9 \ud83c\udf5c","amount":120,"kind":"EXPENSE","epochMillis":5,"id":1,"categoryId":2,"accountId":3}],""" +
            """"version":1,"createdAt":7,"monthStartDay":5,"accounts":[],"categories":[],"budgets":[],"people":[],"loans":[],"recurring":[],"holdings":[],"groups":[]}"""
        val back = BackupContents.fromJson(old, decode)
        assertEquals("Fish / chips \u00e9 \ud83c\udf5c", back.transactions.single().note)
        assertEquals(setOf("a/b.jpg"), back.icons.keys)
        assertContentEquals(byteArrayOf(1, 2, 3), back.icons.getValue("a/b.jpg"))
        assertEquals(5, back.monthStartDay)
        assertEquals(7L, back.createdAt)
        assertEquals(null, back.currency)
    }

    @Test
    fun awkwardTextSurvivesTheRoundTrip() {
        val notes = listOf(
            "Quote \" and backslash \\ and slash / done", "Line one\nline two\ttabbed\r", "\u0001\u001f control", "Café ₹1,00,000 \uD83C\uDF5C emoji",
            "<script>{\"a\":[1,2]}</script>", "50% off_sale", "", "\u2028\u2029",
        )
        val c = sampleContents().copy(transactions = notes.mapIndexed { i, n ->
            TransactionEntity(i + 1L, "EXPENSE", 100, 1, null, 1, null, 1_760_000_000_000, n, null, null)
        })
        val back = BackupContents.fromJson(c.toJson(encode), decode)
        assertEquals(notes, back.transactions.map { it.note })
    }

    @Test
    fun aLargeBackupStreamsThroughAFileAndComesBackWhole() {
        val many = (1..50_000).map {
            TransactionEntity(it.toLong(), "EXPENSE", it * 7L, 1, null, 2, null, 1_760_000_000_000 + it, "Entry number $it at the corner market", if (it % 3 == 0) 100L else null, null)
        }
        val c = sampleContents().copy(transactions = many)
        for (password in listOf<CharArray?>(null, "a long password".toCharArray())) {
            val bytes = java.io.ByteArrayOutputStream()
            BackupFile.sink(bytes, password).use { c.writeJson(it.writer, encode) }
            val back = BackupFile.open(java.io.ByteArrayInputStream(bytes.toByteArray()), password) { BackupContents.readJson(it, decode) }
            assertEquals(50_000, back.transactions.size)
            assertEquals(many, back.transactions)
            assertEquals(c.copy(icons = emptyMap(), transactions = emptyList()), back.copy(icons = emptyMap(), transactions = emptyList()))
        }
    }

    @Test
    fun tablesWrittenInPiecesReadTheSameAsTablesWrittenWhole() {
        val c = sampleContents()
        val out = java.io.StringWriter()
        val w = BackupWriter(JsonOut(out), encode)
        w.begin(c.createdAt, c.monthStartDay, c.currency, c.accountArrangement)
        w.groups(c.groups); w.accounts(c.accounts); w.categories(c.categories)
        w.beginTransactions(); c.transactions.chunked(2).forEach { w.transactions(it) }; w.endTransactions()
        w.budgets(c.budgets); w.people(c.people); w.loans(c.loans); w.bills(c.bills); w.holdings(c.holdings); w.trades(c.trades)
        w.loanTerms(c.loanTerms); w.loanChanges(c.loanChanges); w.loanPayments(c.loanPayments); w.investmentTransfers(c.investmentTransfers)
        w.beginIcons(); c.icons.forEach { (n, b) -> w.icon(n, b) }; w.endIcons()
        w.end()
        assertEquals(c.toJson(encode).length, out.toString().length)
        val back = BackupContents.fromJson(out.toString(), decode)
        assertEquals(c.copy(icons = emptyMap()), back.copy(icons = emptyMap()))
    }

    @Test
    fun anEmptyBackupRoundTrips() {
        val empty = BackupContents(1, 1, null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), icons = emptyMap())
        assertEquals(empty, BackupContents.fromJson(empty.toJson(encode), decode))
    }

    @Test
    fun anIconThatIsNotBase64MakesTheFileNotABackup() {
        val json = JsonTree.edit(sampleContents().toJson(encode)) { it["icons"] = mapOf("x.jpg" to "!!! not base64 !!!") }
        assertFailsWith<BackupFile.NotABackup> { BackupContents.fromJson(json, decode) }
    }

    @Test
    fun textAfterTheBackupOrADamagedBackupIsRefused() {
        val json = sampleContents().toJson(encode)
        assertFailsWith<BackupFile.NotABackup> { BackupContents.fromJson("$json garbage", decode) }
        assertFailsWith<BackupFile.NotABackup> { BackupContents.fromJson(json.dropLast(40), decode) }
        assertFailsWith<BackupFile.NotABackup> { BackupContents.fromJson("", decode) }
        assertFailsWith<BackupFile.NotABackup> { BackupContents.fromJson("[1,2]", decode) }
        assertFailsWith<BackupFile.NotABackup> { BackupContents.fromJson("not json", decode) }
    }
}
