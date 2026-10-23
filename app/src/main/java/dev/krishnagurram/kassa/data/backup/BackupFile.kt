package dev.krishnagurram.kassa.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The bytes of a .kassa backup file around its JSON text.
 *
 * - No password (the default, like a Money Manager .mmbak): "KASSA0", then the gzipped JSON. Anyone with the file can read it.
 * - With a password: "KASSA1", a 16-byte salt, a 12-byte IV, then AES-256-GCM over the gzipped JSON. The key is
 *   PBKDF2-HMAC-SHA256 of the password, 210,000 iterations.
 */
object BackupFile {
    class NotABackup : Exception("This file isn't a Kassa backup.")
    class NeedsPassword : Exception("This backup has a password. Enter it, then pick the file again.")
    class WrongPassword : Exception("That password doesn't open this backup.")

    private val PLAIN = "KASSA0".toByteArray()
    private val PROTECTED = "KASSA1".toByteArray()
    private const val SALT = 16
    private const val IV = 12
    private const val ITERATIONS = 210_000

    /** Wraps [json]; a null or empty [password] means no password. */
    fun encode(json: String, password: CharArray?): ByteArray {
        val zipped = gzip(json.toByteArray(Charsets.UTF_8))
        if (password == null || password.isEmpty()) return PLAIN + zipped
        val salt = random(SALT)
        val iv = random(IV)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv)) }
        return PROTECTED + salt + iv + cipher.doFinal(zipped)
    }

    /** The JSON text inside [bytes]. Throws [NotABackup], [NeedsPassword] or [WrongPassword]. */
    fun decode(bytes: ByteArray, password: CharArray?): String {
        val zipped = when {
            bytes.startsWith(PLAIN) -> bytes.copyOfRange(PLAIN.size, bytes.size)
            bytes.startsWith(PROTECTED) && bytes.size > PROTECTED.size + SALT + IV -> {
                if (password == null || password.isEmpty()) throw NeedsPassword()
                val salt = bytes.copyOfRange(PROTECTED.size, PROTECTED.size + SALT)
                val iv = bytes.copyOfRange(PROTECTED.size + SALT, PROTECTED.size + SALT + IV)
                val body = PROTECTED.size + SALT + IV
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv)) }
                try { cipher.doFinal(bytes, body, bytes.size - body) } catch (e: AEADBadTagException) { throw WrongPassword() }
            }
            else -> throw NotABackup()
        }
        return try { gunzip(zipped).toString(Charsets.UTF_8) } catch (e: java.io.IOException) { throw NotABackup() }
    }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)

    private fun random(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }

    private fun gzip(b: ByteArray) = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(b) } }.toByteArray()

    private fun gunzip(b: ByteArray) = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
}
