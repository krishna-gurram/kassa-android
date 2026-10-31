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

    @Test
    fun aChangedProtectedFileIsNeverAcceptedAsComplete() {
        val bytes = BackupFile.encode(json, "correct horse".toCharArray())
        // Any one changed byte after the header (salt, iv, ciphertext or tag) fails the tag check.
        for (i in listOf(6, 20, 30, bytes.size / 2, bytes.size - 1)) {
            val changed = bytes.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFailsWith<BackupFile.WrongPassword>("byte $i") { BackupFile.decode(changed, "correct horse".toCharArray()) }
        }
        // A file cut short loses its tag.
        assertFailsWith<BackupFile.WrongPassword> { BackupFile.decode(bytes.copyOf(bytes.size - 5), "correct horse".toCharArray()) }
        assertFailsWith<BackupFile.NotABackup> { BackupFile.decode(bytes.copyOf(20), "correct horse".toCharArray()) }
    }

    @Test
    fun aPlainFileThatIsCutShortIsNotABackup() {
        val bytes = BackupFile.encode(json.repeat(50), password = null)
        assertFailsWith<BackupFile.NotABackup> { BackupFile.decode(bytes.copyOf(bytes.size - 6), password = null) }
        assertFailsWith<BackupFile.NotABackup> { BackupFile.decode(bytes.copyOf(8), password = null) }
        assertFailsWith<BackupFile.NotABackup> { BackupFile.decode(ByteArray(0), password = null) }
    }

    @Test
    fun theUnpackedSizeIsCappedWithAPlainMessage() {
        val big = "x".repeat(100_000)
        val bytes = BackupFile.encode("\"$big\"", password = null)
        val e = assertFailsWith<BackupFile.TooLarge> { BackupFile.open(java.io.ByteArrayInputStream(bytes), null, maxBytes = 10_000) { it.readText() } }
        assertEquals("This backup is too large to open on this phone.", e.message)
        // Right at the cap is fine; one byte over is not.
        val exact = BackupFile.encode("y".repeat(1_000), password = null)
        assertEquals(1_000, BackupFile.open(java.io.ByteArrayInputStream(exact), null, maxBytes = 1_000) { it.readText() }.length)
        assertFailsWith<BackupFile.TooLarge> { BackupFile.open(java.io.ByteArrayInputStream(exact), null, maxBytes = 999) { it.readText() } }
        assertEquals(256L * 1024 * 1024, BackupFile.MAX_JSON_BYTES)
    }

    @Test
    fun aGzipBombIsStoppedWhileUnpacking() {
        // 40 MB of zeros packs to about 40 KB; reading stops at the cap without ever holding the rest.
        val sink = java.io.ByteArrayOutputStream()
        BackupFile.sink(sink, null).use { s -> val chunk = "0".repeat(1 shl 20); repeat(40) { s.writer.write(chunk) } }
        assertTrue(sink.size() < 200_000)
        var read = 0L
        assertFailsWith<BackupFile.TooLarge> {
            BackupFile.open(java.io.ByteArrayInputStream(sink.toByteArray()), null, maxBytes = 5L * 1024 * 1024) { r ->
                val buf = CharArray(8192)
                while (true) { val n = r.read(buf); if (n < 0) break; read += n }
            }
        }
        assertTrue(read <= 5L * 1024 * 1024 + 8192)
    }

    @Test
    fun theStreamLeavesTheDestinationOpenForItsOwner() {
        var closed = false
        val out = object : java.io.ByteArrayOutputStream() { override fun close() { closed = true; super.close() } }
        BackupFile.sink(out, "pw".toCharArray()).use { it.writer.write(json) }
        assertTrue(!closed)
        assertEquals(json, BackupFile.decode(out.toByteArray(), "pw".toCharArray()))
    }

    @Test
    fun anEmptyPasswordMeansNoPasswordWhenWriting() {
        val bytes = BackupFile.encode(json, CharArray(0))
        assertEquals(BackupFile.Kind.Plain, BackupFile.kindOf(bytes.copyOf(16)))
    }
}
