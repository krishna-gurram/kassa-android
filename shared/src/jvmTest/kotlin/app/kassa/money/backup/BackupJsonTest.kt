package app.kassa.money.backup

import app.kassa.money.shared.BackupBridge
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reads the golden files of app/src/test/resources/backup-golden (made by tools/make_golden_backups.py from
 * docs/BACKUP_FORMAT.md alone) with the code the iOS app uses. Gradle runs the tests in the module folder.
 */
class BackupJsonTest {

    private fun file(name: String): ByteArray = File("../app/src/test/resources/backup-golden/$name").readBytes()

    private fun gunzip(bytes: ByteArray): String = GZIPInputStream(ByteArrayInputStream(bytes)).readBytes().decodeToString()

    /** The golden file's JSON, opened by the spec in docs/BACKUP_FORMAT.md. */
    private fun jsonOf(name: String, password: String? = null): String {
        val bytes = file(name)
        val head = bytes.copyOfRange(0, 6).decodeToString()
        if (head == "KASSA0") return gunzip(bytes.copyOfRange(6, bytes.size))
        assertEquals("KASSA1", head)
        val salt = bytes.copyOfRange(6, 22)
        val iv = bytes.copyOfRange(22, 34)
        val rest = bytes.copyOfRange(34, bytes.size)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password!!.toCharArray(), salt, 210_000, 256)).encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }
        return gunzip(cipher.doFinal(rest))
    }

    private fun check(c: BackupData) {
        assertEquals(1767225600000L, c.createdAt)
        assertEquals(1, c.monthStartDay)
        assertEquals("USD", c.currency)
        assertEquals("G:cash,card", c.accountArrangement)
        assertEquals(listOf("Checking", "Card", "Car loan"), c.accounts.map { it.name })
        assertEquals(20500, c.accounts[2].closedDay)
        assertNull(c.accounts[0].closedDay)
        assertEquals(1L, c.accounts[0].groupId)
        assertEquals(500000L, c.accounts[1].creditLimit)
        assertEquals("0000", c.accounts[1].last4)
        assertEquals(listOf("Café ☕", "Salary"), c.categories.map { it.name })
        assertEquals(listOf(450L, 99999999L, 25000L), c.transactions.map { it.amount })
        assertEquals("Latte ₹1,00,000", c.transactions[0].note)
        assertEquals(3L, c.transactions[2].toAccountId)
        assertEquals(61250L, c.loanTerms.single().rate)
        assertFalse(c.loanTerms.single().countAllAsSpending)
        assertEquals(202601, c.budgets.single().month)
        assertEquals("Sam", c.people.single().name)
        assertTrue(c.loans.isEmpty() && c.bills.isEmpty() && c.loanPayments.isEmpty() && c.investmentTransfers.isEmpty())
        assertContentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), c.icons.getValue("photo.png"))
    }

    @Test fun aPlainGoldenFileParses() = check(BackupJson.parse(jsonOf("plain.kassa")))

    @Test fun aProtectedGoldenFileParses() = check(BackupJson.parse(jsonOf("protected.kassa", "golden test password")))

    @Test fun aNewerGoldenFileIsRefused() {
        assertFailsWith<NewerVersion> { BackupJson.parse(jsonOf("newer.kassa")) }
        assertEquals("This backup is from a newer version of Kassa. Update Kassa to open it.", BackupBridge.refusal(jsonOf("newer.kassa")))
    }

    @Test fun writingThenReadingGivesTheSameData() {
        val first = BackupJson.parse(jsonOf("plain.kassa"))
        val second = BackupJson.parse(BackupJson.write(first))
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(BackupJson.write(first), BackupJson.write(second))
    }

    @Test fun equalityLooksAtPictureBytes() {
        val a = BackupData(1, 1, icons = mapOf("p" to byteArrayOf(1, 2)))
        assertEquals(a, BackupData(1, 1, icons = mapOf("p" to byteArrayOf(1, 2))))
        assertNotEquals(a, BackupData(1, 1, icons = mapOf("p" to byteArrayOf(1, 3))))
    }

    @Test fun fieldsAreWrittenInAndroidsOrderAndNullsLeftOut() {
        val text = BackupJson.write(BackupData(5, 1, groups = listOf(BackupGroup(1, "G", 0))))
        assertEquals(
            """{"version":1,"createdAt":5,"monthStartDay":1,"groups":[{"id":1,"name":"G","sortOrder":0}],"accounts":[],"categories":[],""" +
                """"transactions":[],"budgets":[],"people":[],"loans":[],"recurring":[],"holdings":[],"trades":[],"loanTerms":[],""" +
                """"loanChanges":[],"loanPayments":[],"investmentTransfers":[],"icons":{}}""",
            text,
        )
        val account = BackupAccount(1, "A", "CASH", openingBalance = 0, archived = false, sortOrder = 0)
        val one = BackupJson.write(BackupData(5, 1, currency = "USD", accountArrangement = "G:x", accounts = listOf(account)))
        assertTrue(one.startsWith("""{"version":1,"createdAt":5,"monthStartDay":1,"currency":"USD","accountArrangement":"G:x","groups":[],"accounts":[{"id":1,"name":"A","type":"CASH","openingBalance":0,"archived":false,"sortOrder":0}]"""))
        assertFalse(one.contains("null"))
    }

    private val tricky = "Café ☕ ₹ \"quoted\" back\\slash / \u0001\u0007\u001f \b\u000C\n\r\t    😀 end"

    @Test fun unicodeAndEscapesRoundTrip() {
        val data = BackupData(
            createdAt = 1, monthStartDay = 1, currency = "INR",
            categories = listOf(BackupCategory(1, tricky, "EXPENSE", null, "x", 1, true, false, 0)),
            transactions = listOf(BackupTransaction(1, "EXPENSE", 99999999999L, 1, null, 1, null, 0, tricky)),
            icons = mapOf(tricky to byteArrayOf(0, 1, 2, 3, 4, -1, -2, 127, -128)),
        )
        val text = BackupJson.write(data)
        assertTrue(text.none { it < ' ' }, "no raw control characters")
        assertFalse(text.contains(' ') || text.contains(' '))
        assertTrue(text.contains("😀"))
        assertEquals(data, BackupJson.parse(text))
        assertEquals(tricky, BackupJson.parse(text).categories.single().name)
    }

    @Test fun escapesInTheTextAreDecoded() {
        val text = """{"version":1,"createdAt":0,"monthStartDay":1,"people":[{"id":1,"name":"a😀é\/\"\\\b\f\n\r\t","archived":false}]}"""
        assertEquals("a😀é/\"\\\b\u000C\n\r\t", BackupJson.parse(text).people.single().name)
    }

    @Test fun largeAmountsStayExact() {
        val data = BackupData(
            createdAt = Long.MAX_VALUE, monthStartDay = 1,
            transactions = listOf(
                BackupTransaction(1, "INCOME", 99999999999L, null, null, null, null, 0, ""),
                BackupTransaction(2, "INCOME", Long.MAX_VALUE, null, null, null, null, Long.MIN_VALUE, ""),
            ),
        )
        val back = BackupJson.parse(BackupJson.write(data))
        assertEquals(listOf(99999999999L, Long.MAX_VALUE), back.transactions.map { it.amount })
        assertEquals(Long.MIN_VALUE, back.transactions[1].epochMillis)
        assertEquals(Long.MAX_VALUE, back.createdAt)
    }

    @Test fun everyTableRoundTripsWithItsOptionalFields() {
        val data = BackupData(
            createdAt = 9, monthStartDay = 15, currency = "GBP", accountArrangement = "G:a",
            groups = listOf(BackupGroup(1, "g", 2)),
            accounts = listOf(BackupAccount(1, "a", "LOAN", 1, -5, 3, 4, 2, 6, true, 1, "ext", "1234", 7, 8)),
            categories = listOf(BackupCategory(1, "c", "EXPENSE", 2, "i", 3, true, true, 4, "x")),
            transactions = listOf(BackupTransaction(1, "TRANSFER", 5, 1, 2, 3, 4, 6, "n", 7, "e")),
            budgets = listOf(BackupBudget(1, 0, 300)),
            people = listOf(BackupPerson(1, "p", true)),
            loans = listOf(BackupLoanEntry(1, 2, -3, 4, "n", 5)),
            bills = listOf(BackupBill(1, "b", 2, 3, 28, 4_00, true, 202610)),
            holdings = listOf(BackupHolding(1, 2, "VTI", 1_500_000, 25_000, 99, 24_000)),
            trades = listOf(BackupTrade(1, 2, "VTI", -3, 4)),
            loanTerms = listOf(BackupLoanTerms(1, "FLAT", true, 61250, 2, 60, 3, 4, 5, 6, 7, 8, 9, 10, 11, true, 12)),
            loanChanges = listOf(BackupLoanChange(1, 2, 3, 4, 5, 6, "REDUCING")),
            loanPayments = listOf(BackupLoanPayment(1, 2, 3, 4, 5, 6, 7, 8, 9, true, 10, false, true, 11)),
            investmentTransfers = listOf(BackupInvestmentTransfer(1, 2, 3, 4, 5)),
            icons = mapOf("a.png" to byteArrayOf(1), "b.png" to ByteArray(0)),
        )
        assertEquals(data, BackupJson.parse(BackupJson.write(data)))
    }

    @Test fun missingTablesReadAsEmptyAndUnknownKeysAreSkipped() {
        val d = BackupJson.parse("""{"version":1,"createdAt":3,"future":{"a":[1,{"b":null}],"c":"d"},"groups":null,"zzz":[true,false]}""")
        assertEquals(BackupData(createdAt = 3, monthStartDay = 1), d)
        val bare = BackupJson.parse("{}")
        assertEquals(BackupData(createdAt = 0, monthStartDay = 1), bare)
        assertEquals("0 entries, 0 accounts, 0 categories", bare.summary)
    }

    @Test fun rowsIgnoreUnknownKeysAndNestedValues() {
        val d = BackupJson.parse("""{"version":1,"people":[{"id":1,"name":"x","archived":false,"extra":{"a":1},"more":[1]}]}""")
        assertEquals(listOf(BackupPerson(1, "x", false)), d.people)
    }

    @Test fun emptyCurrencyAndArrangementReadAsNull() {
        val d = BackupJson.parse("""{"version":1,"currency":"","accountArrangement":""}""")
        assertNull(d.currency)
        assertNull(d.accountArrangement)
    }

    @Test fun countAllAsSpendingDefaultsToFalse() {
        val row = """{"accountId":1,"type":"FLAT","profit":false,"rate":1,"payment":2,"nextPaymentDay":3,"dueDay":4,"escrow":0,"fee":0,"earlyCharge":0}"""
        assertFalse(BackupJson.parse("""{"version":1,"loanTerms":[$row]}""").loanTerms.single().countAllAsSpending)
    }

    private fun assertNotABackup(text: String) {
        assertFailsWith<NotABackup>(text) { BackupJson.parse(text) }
        assertEquals("This file isn't a Kassa backup.", BackupBridge.refusal(text))
    }

    @Test fun aMissingRequiredFieldIsNotABackup() {
        assertNotABackup("""{"version":1,"groups":[{"id":1,"sortOrder":0}]}""")
        assertNotABackup("""{"version":1,"groups":[{"id":1,"name":"a","sortOrder":null}]}""")
        assertNotABackup("""{"version":1,"people":[{"id":1,"name":"a"}]}""")
        assertNotABackup("""{"version":1,"budgets":[{"categoryId":1,"month":202601}]}""")
    }

    @Test fun wrongKindsAndMalformedTextAreNotABackup() {
        assertNotABackup("")
        assertNotABackup("not json")
        assertNotABackup("[]")
        assertNotABackup("""{"version":1""")
        assertNotABackup("""{"version":1,}""")
        assertNotABackup("""{"version":1} extra""")
        assertNotABackup("""{"version":1,"groups":5}""")
        assertNotABackup("""{"version":1,"groups":[5]}""")
        assertNotABackup("""{"version":1,"groups":[{"id":1.5,"name":"a","sortOrder":0}]}""")
        assertNotABackup("""{"version":1,"groups":[{"id":"x","name":"a","sortOrder":0}]}""")
        assertNotABackup("""{"version":1,"groups":[{"id":1,"name":true,"sortOrder":0}]}""")
        assertNotABackup("""{"version":1,"groups":[{"id":1,"name":"a","sortOrder":99999999999}]}""")
        assertNotABackup("""{"version":1,"people":[{"id":1,"name":"a","archived":"maybe"}]}""")
        assertNotABackup("""{"version":1,"icons":{"a":"A!"}}""")
        assertNotABackup("""{"version":1,"icons":{"a":"A"}}""")
        assertNotABackup("""{"version":1,"icons":[]}""")
        assertNotABackup("""{"version":1,"createdAt":null}""")
        assertNotABackup("""{"version":1,"name":"bad \q escape"}""")
        assertNotABackup("""{"version":1,"name":"bad \u12G4"}""")
        assertNotABackup("""{"version":1,"name":"never ends""")
    }

    @Test fun readerAcceptsWhatAndroidAccepts() {
        val d = BackupJson.parse(" \n{\"version\" : 1 ,\"createdAt\":\"42\",\"people\":[{\"id\":2.0,\"name\":\"x\",\"archived\":\"true\"}]}\r\n")
        assertEquals(42L, d.createdAt)
        assertEquals(listOf(BackupPerson(2, "x", true)), d.people)
    }

    @Test fun aNewerVersionWinsWhenSeenFirst() {
        assertFailsWith<NewerVersion> { BackupJson.parse("""{"version":2,"groups":[{"id":1}]}""") }
        assertFailsWith<NewerVersion> { BackupJson.parse("""{"version":2,"x":""") }
    }

    @Test fun aNewerVersionAfterTheDamageIsStillNotABackup() {
        assertNotABackup("""{"x":,"version":2}""")
        assertNotABackup("""{"version":"two"}""")
        assertNotABackup("""{"version":99999999999,"x":""")
    }

    @Test fun base64RoundTripsEverySizeAndByte() {
        for (n in 0..10) {
            val bytes = ByteArray(n) { (it * 37 + 5).toByte() }
            val text = Base64Text.encode(bytes)
            assertEquals(java.util.Base64.getEncoder().encodeToString(bytes), text)
            assertContentEquals(bytes, Base64Text.decode(text))
        }
        val all = ByteArray(256) { it.toByte() }
        assertContentEquals(all, Base64Text.decode(Base64Text.encode(all)))
        assertContentEquals(byteArrayOf(1, 2, 3), Base64Text.decode("AQID"))
        assertContentEquals(byteArrayOf(1, 2), Base64Text.decode("AQI"))
    }

    @Test fun theBridgeParsesWritesAndSummarises() {
        val data = BackupBridge.parse(jsonOf("plain.kassa"))
        assertEquals("3 entries, 3 accounts, 2 categories", BackupBridge.summary(data))
        assertEquals(data, BackupBridge.parse(BackupBridge.write(data)))
        assertNull(BackupBridge.refusal(jsonOf("plain.kassa")))
        val digest = BackupBridge.digest(data).split("\n")
        assertTrue("accounts=Checking|Card|Car loan" in digest)
        assertTrue("amounts=450|99999999|25000" in digest)
        assertTrue("icons=photo.png:8" in digest)
        assertFailsWith<NotABackup> { BackupBridge.parse("nope") }
    }
}
