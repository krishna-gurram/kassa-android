package app.kassa.money.data.backup

import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The synthetic demo backup (`tools/make_demo_backup.py`): Android opens it, plain or protected, and its entries add up to the figures worked out in Python. */
class BackupDemoTest {
    private fun file(name: String) = javaClass.classLoader!!.getResourceAsStream("backup-demo/$name")!!.readBytes()
    private val decode: (String) -> ByteArray = { Base64.getDecoder().decode(it) }
    private fun read(name: String, pw: String?) =
        BackupFile.open(ByteArrayInputStream(file(name)), pw?.toCharArray()) { BackupContents.readJson(it, decode) }
    private val text get() = String(file("expected.json"))
    private fun num(key: String): Long = Regex("\"$key\": (-?\\d+)").find(text)!!.groupValues[1].toLong()

    private fun check(c: BackupContents) {
        assertEquals(num("entries").toInt(), c.transactions.size)
        assertEquals(num("accounts").toInt(), c.accounts.size)
        assertEquals(num("categories").toInt(), c.categories.size)
        assertEquals("USD", c.currency)
        assertEquals(1, c.holdings.size)
        assertEquals(1, c.loanTerms.size)
        assertTrue(c.loanPayments.isNotEmpty())
        for (a in c.accounts) {
            var b = a.openingBalance
            for (t in c.transactions) {
                val up = t.kind in listOf("INCOME", "PERSON_IN", "SELL", "ADJUST_IN")
                if (t.accountId == a.id) b += if (up) t.amount else -t.amount
                if (t.kind == "TRANSFER" && t.toAccountId == a.id) b += t.amount
            }
            assertEquals(num(a.id.toString()), b, a.name)
        }
    }

    @Test fun plainDemoOpens() = check(read("demo.kassa", null))
    /** The file the iOS app wrote from this same demo (the ios-app CI job's `demo-from-ios.kassa`): Android's reader opens it and it adds up the same. */
    @Test fun aFileWrittenByTheIosAppOpens() = check(read("from-ios.kassa", null))

    @Test fun protectedDemoOpens() = check(read("demo-protected.kassa", "demo password"))
    @Test fun protectedDemoChecksPassword() {
        assertFailsWith<BackupFile.WrongPassword> { read("demo-protected.kassa", "nope") }
    }
}
