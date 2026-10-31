package app.kassa.money.data.security

/** What to do about the database passphrase, given what exists on the phone. Plain Kotlin so it has JVM tests. */
enum class KeyAction {
    /** The stored passphrase unwrapped fine. */
    USE_STORED,

    /** There is no data to protect, so a new passphrase is safe. */
    CREATE_NEW,

    /** Data exists but its passphrase is gone or can't be read. A new passphrase would never open it: stop and ask. */
    CANNOT_OPEN,
}

/** The passphrase of an existing database can't be recovered (the Keystore key was lost or no longer works). */
class KeyUnreadable(message: String, cause: Throwable? = null) : Exception(message, cause)

object KeyPolicy {
    /**
     * [databaseExists]: the database file is on the phone. [keyStored]: a wrapped passphrase is saved. [storedKeyUsable]:
     * unwrapping it with the Keystore worked. Never makes a new passphrase over an existing database, which would turn
     * a lost key into "file is not a database" on every start.
     */
    fun decide(databaseExists: Boolean, keyStored: Boolean, storedKeyUsable: Boolean): KeyAction = when {
        keyStored && storedKeyUsable -> KeyAction.USE_STORED
        databaseExists -> KeyAction.CANNOT_OPEN
        else -> KeyAction.CREATE_NEW
    }
}

/** Why the database could not be opened. */
enum class OpenProblem {
    /** The data is there but can't be read with the key this phone has: it can only be replaced. */
    DATA_UNREADABLE,

    /** Anything else (a failed upgrade, a full disk): the data may be fine, so trying again is worth offering first. */
    OTHER;

    companion object {
        /** Reads the whole cause chain: the key was lost, or SQLCipher says the file isn't a database with this key. */
        fun classify(error: Throwable): OpenProblem {
            var e: Throwable? = error
            var steps = 0
            while (e != null && steps < 10) {
                if (e is KeyUnreadable) return DATA_UNREADABLE
                if (e.message?.contains("file is not a database", ignoreCase = true) == true) return DATA_UNREADABLE
                e = e.cause
                steps++
            }
            return OTHER
        }
    }
}
