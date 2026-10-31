package app.kassa.money.data.backup

import app.kassa.money.data.db.AccountEntity
import app.kassa.money.data.db.BudgetEntity
import app.kassa.money.data.db.CategoryEntity
import app.kassa.money.data.db.GroupEntity
import app.kassa.money.data.db.HoldingEntity
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
    ),
    budgets = listOf(BudgetEntity(1, 0, 40_000), BudgetEntity(1, 202610, 45_000)),
    people = listOf(PersonEntity(1, "Sam", false), PersonEntity(2, "Alex", true)),
    loans = listOf(LoanEntryEntity(1, 2, 4_000, 1_760_000_100_000, "Corner market", 1), LoanEntryEntity(2, 1, -1_500, 1_760_000_200_000, "Dinner, Sam paid", 2)),
    bills = listOf(RecurringEntity(1, "Rent", 1, 1, 1, 120_000, true, 202609)),
    holdings = listOf(HoldingEntity(1, 1, "VTI", 2_500_000, 25_050, 1_760_000_000_000, 21_333), HoldingEntity(2, 1, "SCHD", 80_000_000, 2_750, 1_760_000_000_000)),
        trades = listOf(TradeEntity(1, 3, "VTI", 2_500_000, 25_050)),
    loanTerms = listOf(
        LoanTermsEntity(3, "REDUCING", false, 61_250, 58_000, 60, 3_000_000, 20_500, 20_758, 1, 20_000, 15_000, 10_000, 2, 1),
    ),
    loanChanges = listOf(
        LoanTermChangeEntity(1, 3, 21_000, 72_500, null, null, null),
        LoanTermChangeEntity(2, 3, 21_400, null, 60_000, 24, "REDUCING"),
    ),
    loanPayments = listOf(LoanPaymentEntity(1, 3, 20_758, 78_000, 4, 5, null, 20_000, 0, true, 10_740, true)),
    icons = mapOf("photo_1.jpg" to byteArrayOf(1, 2, 3, -1)),
)

class BackupContentsTest {
    private val encode: (ByteArray) -> String = { Base64.getEncoder().encodeToString(it) }
    private val decode: (String) -> ByteArray = { Base64.getDecoder().decode(it) }

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
        val old = org.json.JSONObject(sampleContents().toJson(encode)).apply { remove("loanTerms"); remove("loanChanges"); remove("loanPayments") }
        val back = BackupContents.fromJson(old.toString(), decode)
        assertTrue(back.loanTerms.isEmpty())
        assertTrue(back.loanChanges.isEmpty())
        assertTrue(back.loanPayments.isEmpty())
        // The loan account and its entries are still there; it is a plain loan account again.
        assertEquals("LOAN", back.accounts.last().type)
        assertEquals(5, back.transactions.size)
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
}
