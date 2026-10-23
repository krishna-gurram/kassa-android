package dev.krishnagurram.kassa.data.backup

import dev.krishnagurram.kassa.data.db.AccountEntity
import dev.krishnagurram.kassa.data.db.BudgetEntity
import dev.krishnagurram.kassa.data.db.CategoryEntity
import dev.krishnagurram.kassa.data.db.GroupEntity
import dev.krishnagurram.kassa.data.db.HoldingEntity
import dev.krishnagurram.kassa.data.db.LoanEntryEntity
import dev.krishnagurram.kassa.data.db.PersonEntity
import dev.krishnagurram.kassa.data.db.RecurringEntity
import dev.krishnagurram.kassa.data.db.TransactionEntity
import org.junit.Test
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Synthetic data shaped like a real ledger: every table, nullable columns both set and empty. */
internal fun sampleContents() = BackupContents(
    createdAt = 1_760_000_000_000,
    monthStartDay = 16,
    currency = "INR",
    groups = listOf(GroupEntity(1, "Bank", 0)),
    accounts = listOf(
        AccountEntity(1, "Everyday checking", "CHECKING", 1, 150_000, null, null, null, null, false, 0, "mm-1", null),
        AccountEntity(2, "Travel card", "CREDIT_CARD", null, -42_000, 16, 10, 1, 500_000, false, 1, null, "1004"),
    ),
    categories = listOf(
        CategoryEntity(1, "Food", "EXPENSE", null, "emoji:🍜", -3_000_000, true, false, 0, null),
        CategoryEntity(2, "Groceries", "EXPENSE", 1, "symbol:shopping_cart", -3_000_000, true, false, 0, "mm-c2"),
    ),
    transactions = listOf(
        TransactionEntity(1, "EXPENSE", 6_000, 2, null, 2, null, 1_760_000_100_000, "Corner market", 2_000, null),
        TransactionEntity(2, "EXPENSE", 3_000, null, null, 1, 1, 1_760_000_200_000, "Dinner, Sam paid", 1_500, null),
        TransactionEntity(3, "TRANSFER", 10_000, 1, 2, null, null, 1_760_000_300_000, "", null, "mm-t3"),
    ),
    budgets = listOf(BudgetEntity(1, 0, 40_000), BudgetEntity(1, 202610, 45_000)),
    people = listOf(PersonEntity(1, "Sam", false), PersonEntity(2, "Alex", true)),
    loans = listOf(LoanEntryEntity(1, 2, 4_000, 1_760_000_100_000, "Corner market", 1), LoanEntryEntity(2, 1, -1_500, 1_760_000_200_000, "Dinner, Sam paid", 2)),
    bills = listOf(RecurringEntity(1, "Rent", 1, 1, 1, 120_000, true, 202609)),
    holdings = listOf(HoldingEntity(1, 1, "VTI", 2_500_000, 25_050, 1_760_000_000_000)),
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
