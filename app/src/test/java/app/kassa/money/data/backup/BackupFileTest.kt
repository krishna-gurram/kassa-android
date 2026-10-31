package app.kassa.money.data.backup

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupFileTest {
    private val json = """{"version":1,"note":"Café ☕ and ₹1,00,000"}"""

    @Test
    fun aBackupWithoutAPasswordOpensWithoutOne() {
        val bytes = BackupFile.encode(json, password = null)
        assertTrue(bytes.copyOfRange(0, 6).contentEquals("KASSA0".toByteArray()))
        assertEquals(json, BackupFile.decode(bytes, password = null))
    }

    @Test
    fun aProtectedBackupNeedsTheRightPassword() {
        val bytes = BackupFile.encode(json, "correct horse".toCharArray())
        assertEquals(json, BackupFile.decode(bytes, "correct horse".toCharArray()))
        assertFailsWith<BackupFile.NeedsPassword> { BackupFile.decode(bytes, null) }
        assertFailsWith<BackupFile.WrongPassword> { BackupFile.decode(bytes, "wrong horse".toCharArray()) }
    }

    @Test
    fun anythingElseIsNotABackup() {
        assertFailsWith<BackupFile.NotABackup> { BackupFile.decode("hello".toByteArray(), null) }
        assertFailsWith<BackupFile.NotABackup> { BackupFile.decode("KASSA0not gzip".toByteArray(), null) }
    }

    @Test
    fun theHeaderSaysWhichKindOfBackup() {
        assertEquals(BackupFile.Kind.Plain, BackupFile.kindOf(BackupFile.encode(json, null).copyOf(16)))
        assertEquals(BackupFile.Kind.Protected, BackupFile.kindOf(BackupFile.encode(json, "pw".toCharArray()).copyOf(16)))
        assertNull(BackupFile.kindOf("SQLite format 3\u0000".toByteArray()))
        assertNull(BackupFile.kindOf("KASSA".toByteArray()))
        assertNull(BackupFile.kindOf(ByteArray(0)))
    }
}
