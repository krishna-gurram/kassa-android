package app.kassa.money.data.security

import org.junit.Test
import kotlin.test.assertEquals

class KeyPolicyTest {
    @Test
    fun aUsableStoredKeyIsUsed() {
        assertEquals(KeyAction.USE_STORED, KeyPolicy.decide(databaseExists = true, keyStored = true, storedKeyUsable = true))
    }

    @Test
    fun aLostKeyOverExistingDataIsNeverReplaced() {
        // Keystore entry gone or invalid.
        assertEquals(KeyAction.CANNOT_OPEN, KeyPolicy.decide(databaseExists = true, keyStored = true, storedKeyUsable = false))
        // The saved passphrase itself is gone.
        assertEquals(KeyAction.CANNOT_OPEN, KeyPolicy.decide(databaseExists = true, keyStored = false, storedKeyUsable = false))
    }

    @Test
    fun withNoDataANewKeyIsSafe() {
        assertEquals(KeyAction.CREATE_NEW, KeyPolicy.decide(databaseExists = false, keyStored = false, storedKeyUsable = false))
        assertEquals(KeyAction.CREATE_NEW, KeyPolicy.decide(databaseExists = false, keyStored = true, storedKeyUsable = false))
    }

    @Test
    fun anUnreadableKeyOrNotADatabaseIsUnreadableData() {
        assertEquals(OpenProblem.DATA_UNREADABLE, OpenProblem.classify(KeyUnreadable("lost")))
        assertEquals(OpenProblem.DATA_UNREADABLE, OpenProblem.classify(RuntimeException("open failed", IllegalStateException("file is not a database (code 26)"))))
    }

    @Test
    fun otherFailuresAreNotCalledUnreadable() {
        assertEquals(OpenProblem.OTHER, OpenProblem.classify(IllegalStateException("Migration didn't properly handle: accounts")))
        assertEquals(OpenProblem.OTHER, OpenProblem.classify(RuntimeException()))
    }
}
