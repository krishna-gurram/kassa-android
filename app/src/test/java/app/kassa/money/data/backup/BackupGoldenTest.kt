package app.kassa.money.data.backup

import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Files written by `tools/make_golden_backups.py` straight from docs/BACKUP_FORMAT.md, sharing no code with the Android
 * writer. Android opening them shows the spec is complete; the iOS app runs the same files through its own reader.
 */
class BackupGoldenTest {
    private fun file(name: String) = javaClass.classLoader!!.getResourceAsStream("backup-golden/$name")!!.readBytes()
    private val decode: (String) -> ByteArray = { Base64.getDecoder().decode(it) }
    private val password = "golden test password".toCharArray()

    private fun read(bytes: ByteArray, pw: CharArray?) =
        BackupFile.open(ByteArrayInputStream(bytes), pw) { BackupContents.readJson(it, decode) }

    private fun check(c: BackupContents) {
        assertEquals(1767225600000L, c.createdAt)
        assertEquals("USD", c.currency)
        assertEquals("G:cash,card", c.accountArrangement)
        assertEquals(listOf("Checking", "Card", "Car loan"), c.accounts.map { it.name })
        assertEquals(20500, c.accounts[2].closedDay)
        assertEquals(listOf("Café ☕", "Salary"), c.categories.map { it.name })
        assertEquals(listOf(450L, 99999999L, 25000L), c.transactions.map { it.amount })
        assertEquals("Latte ₹1,00,000", c.transactions[0].note)
        assertEquals(61250L, c.loanTerms.single().rate)
        assertTrue(c.icons.getValue("photo.png").isNotEmpty())
    }

    @Test fun aPlainFileFromAnotherWriterOpens() = check(read(file("plain.kassa"), null))

    @Test fun aProtectedFileFromAnotherWriterOpens() = check(read(file("protected.kassa"), password))

    @Test fun aProtectedFileFromAnotherWriterStillChecksThePassword() {
        assertFailsWith<BackupFile.NeedsPassword> { read(file("protected.kassa"), null) }
        assertFailsWith<BackupFile.WrongPassword> { read(file("protected.kassa"), "nope".toCharArray()) }
    }

    @Test fun aNewerVersionIsRefused() {
        assertFailsWith<BackupFile.NewerVersion> { read(file("newer.kassa"), null) }
    }
}
