package app.kassa.money.data.backup

import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Reader
import java.io.Writer
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The bytes of a .kassa backup file around its JSON text. Both directions stream: the JSON is written into the gzip (and
 * cipher) as it is produced, and read out of them as it is parsed, so a large backup never exists as one string or array.
 *
 * - No password (the default, like a Money Manager .mmbak): "KASSA0", then the gzipped JSON. Anyone with the file can read it.
 * - With a password: "KASSA1", a 16-byte salt, a 12-byte IV, then AES-256-GCM over the gzipped JSON. The key is
 *   PBKDF2-HMAC-SHA256 of the password, 210,000 iterations.
 */
object BackupFile {
    class NotABackup : Exception("This file isn't a Kassa backup.")
    class NeedsPassword : Exception("This backup has a password. Enter it, then pick the file again.")
    class WrongPassword : Exception("That password doesn't open this backup.")
    class NewerVersion : Exception("This backup is from a newer version of Kassa. Update Kassa to open it.")

    /** The JSON inside is bigger than any real backup can be: refused rather than filling the phone's memory. */
    class TooLarge : IOException("This backup is too large to open on this phone.")

    /** Which kind of .kassa file a header belongs to. */
    enum class Kind { Plain, Protected }

    /** The most JSON a backup may unpack to. A backup of a hundred thousand entries is about 40 MB. */
    const val MAX_JSON_BYTES = 256L * 1024 * 1024

    private val PLAIN = "KASSA0".toByteArray()
    private val PROTECTED = "KASSA1".toByteArray()
    private const val SALT = 16
    private const val IV = 12
    private const val ITERATIONS = 210_000
    private const val HEADER = 6

    /** The kind of backup [head] (the first bytes of a file) starts, or null when it isn't a .kassa file. */
    fun kindOf(head: ByteArray): Kind? = when {
        head.startsWith(PLAIN) -> Kind.Plain
        head.startsWith(PROTECTED) -> Kind.Protected
        else -> null
    }

    /* ---------- writing ---------- */

    /**
     * Where a backup's JSON is written: [writer] takes the text, and [close] finishes the file (the last gzip block, and
     * for a password the cipher's final block and tag). The stream given to [sink] stays open: closing is the caller's.
     * A null or empty password means a file anyone can open.
     */
    class Sink internal constructor(destination: OutputStream, password: CharArray?) : Closeable {
        val writer: Writer

        init {
            // Closing the chain finishes the file but leaves [destination] for its owner to close.
            val target = object : FilterOutputStream(destination) {
                override fun write(b: ByteArray, off: Int, len: Int) { destination.write(b, off, len) }
                override fun close() { flush() }
            }
            val gzipTarget: OutputStream = if (password == null || password.isEmpty()) {
                destination.write(PLAIN)
                target
            } else {
                val salt = random(SALT)
                val iv = random(IV)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv)) }
                destination.write(PROTECTED + salt + iv)
                CipherOutputStream(target, cipher)
            }
            writer = BufferedWriter(OutputStreamWriter(GZIPOutputStream(gzipTarget, 8192), Charsets.UTF_8), 8192)
        }

        override fun close() = writer.close()
    }

    /** Starts a backup file on [out]; write its JSON to [Sink.writer], then close the sink. */
    fun sink(out: OutputStream, password: CharArray?): Sink = Sink(out, password)

    /** Wraps [json]; a null or empty [password] means no password. */
    fun encode(json: String, password: CharArray?): ByteArray {
        val bytes = ByteArrayOutputStream()
        sink(bytes, password).use { it.writer.write(json) }
        return bytes.toByteArray()
    }

    /* ---------- reading ---------- */

    /**
     * Opens the file on [input] and gives its JSON text to [read] as a stream. Throws [NotABackup], [NeedsPassword],
     * [WrongPassword] (also for a protected file that has been damaged: the tag no longer matches), [TooLarge] when the JSON
     * unpacks past [maxBytes], and whatever [read] throws itself. A protected file's text is only handed over once its
     * tag has been checked, so a wrong password never parses garbage.
     */
    fun <T> open(input: InputStream, password: CharArray?, maxBytes: Long = MAX_JSON_BYTES, read: (Reader) -> T): T {
        val head = readFully(input, HEADER) ?: throw NotABackup()
        val protected = when {
            head.contentEquals(PLAIN) -> false
            head.contentEquals(PROTECTED) -> true
            else -> throw NotABackup()
        }
        try {
            val body: InputStream = if (protected) {
                val header = readFully(input, SALT + IV) ?: throw NotABackup()
                if (password == null || password.isEmpty()) throw NeedsPassword()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key(password, header.copyOfRange(0, SALT)), GCMParameterSpec(128, header.copyOfRange(SALT, SALT + IV)))
                }
                CheckedDecryptStream(input, cipher)
            } else input
            val json = BoundedInputStream(GZIPInputStream(BufferedInputStream(body, 8192), 8192), maxBytes)
            return read(InputStreamReader(json, Charsets.UTF_8))
        } catch (e: TooLarge) {
            throw e
        } catch (e: IOException) {
            // A damaged or cut-short file. For a protected one the likeliest cause is the password.
            throw if (protected) WrongPassword() else NotABackup()
        }
    }

    /** The JSON text inside [bytes]. Throws [NotABackup], [NeedsPassword] or [WrongPassword]. */
    fun decode(bytes: ByteArray, password: CharArray?): String = open(ByteArrayInputStream(bytes), password) { it.readText() }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)

    private fun readFully(input: InputStream, n: Int): ByteArray? {
        val b = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(b, got, n - got)
            if (r < 0) return null
            got += r
        }
        return b
    }

    private fun random(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }
}

/** Reads at most [limit] bytes of [source], then throws [BackupFile.TooLarge]. */
internal class BoundedInputStream(private val source: InputStream, private val limit: Long) : InputStream() {
    private var count = 0L

    override fun read(): Int {
        val b = source.read()
        if (b >= 0 && ++count > limit) throw BackupFile.TooLarge()
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = source.read(b, off, len)
        if (n > 0) {
            count += n
            if (count > limit) throw BackupFile.TooLarge()
        }
        return n
    }

    override fun close() = source.close()
}

/**
 * Decrypts AES-GCM from [source]. The whole ciphertext is decrypted and its tag checked before the first byte is handed
 * on, so nothing unauthenticated is ever parsed, whichever cipher provider the phone has. Unlike CipherInputStream, which on
 * some Android versions drops a failed tag check and simply ends the stream, a bad tag here is always an [IOException].
 * What is held is the compressed backup, a small fraction of its JSON.
 */
internal class CheckedDecryptStream(private val source: InputStream, private val cipher: Cipher) : InputStream() {
    private var plain: ByteArray? = null
    private var at = 0

    private fun load(): ByteArray {
        plain?.let { return it }
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val n = source.read(chunk)
            if (n < 0) break
            cipher.update(chunk, 0, n)?.let { out.write(it) }
        }
        try { out.write(cipher.doFinal()) } catch (e: AEADBadTagException) { throw IOException("The tag doesn't match", e) }
        return out.toByteArray().also { plain = it }
    }

    override fun read(): Int {
        val p = load()
        return if (at < p.size) p[at++].toInt() and 0xFF else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val p = load()
        if (at >= p.size) return -1
        val k = minOf(len, p.size - at)
        System.arraycopy(p, at, b, off, k)
        at += k
        return k
    }

    override fun close() = source.close()
}
