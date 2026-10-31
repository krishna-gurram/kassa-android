package app.kassa.money.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The database passphrase is 32 random bytes. It is stored only in wrapped form: encrypted with an AES key that
 * lives inside the Android Keystore and never leaves it. Uninstalling the app destroys the key, so a copied
 * database file is unreadable anywhere else. That is why Kassa has its own export for backups.
 */
object DatabaseKey {
    private const val ALIAS = "kassa_db_wrap"
    private const val PREFS = "kassa_key"
    private const val WRAPPED = "wrapped"
    private const val IV = "iv"

    /**
     * The passphrase for the database. [databaseExists] says whether a database file is already on the phone: a missing
     * or unreadable key is only replaced when there is nothing to lose ([KeyPolicy]); otherwise this throws
     * [KeyUnreadable] and the app shows its "can't open its data" page instead of making a key that can't open the data.
     */
    fun get(context: Context, databaseExists: Boolean): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wrapped = prefs.getString(WRAPPED, null)
        val iv = prefs.getString(IV, null)
        val stored = wrapped != null && iv != null
        var key: ByteArray? = null
        var cause: Throwable? = null
        if (stored) {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, wrapKey(create = false), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
                key = cipher.doFinal(Base64.decode(wrapped, Base64.NO_WRAP))
            } catch (e: Exception) {
                cause = e
            }
        }
        return when (KeyPolicy.decide(databaseExists, stored, key != null)) {
            KeyAction.USE_STORED -> key!!
            KeyAction.CANNOT_OPEN -> throw KeyUnreadable("The database key can't be read.", cause)
            KeyAction.CREATE_NEW -> createNew(context)
        }
    }

    /** A fresh passphrase and a fresh Keystore key, replacing any stale ones. Only called with no database on the phone. */
    private fun createNew(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey(create = true))
        prefs.edit()
            .putString(WRAPPED, Base64.encodeToString(cipher.doFinal(key), Base64.NO_WRAP))
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()
        return key
    }

    /** Forgets the passphrase and its Keystore key. Only for "Start over", which erases the database as well. */
    fun delete(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    /** The Keystore key that wraps the passphrase. Without [create], a missing key is an error, not a reason to make one. */
    private fun wrapKey(create: Boolean): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        if (!create) throw KeyUnreadable("The Keystore key is gone.")
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}
