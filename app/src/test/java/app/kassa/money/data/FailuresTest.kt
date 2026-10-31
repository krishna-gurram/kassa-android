package app.kassa.money.data

import app.kassa.money.data.Failures.Doing
import app.kassa.money.data.backup.BackupFile
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FailuresTest {
    private val secret = "/data/user/0/app.kassa.money/files/Salary 4200 at Chase.db: SQL error near \"INSERT\""

    private fun clean(e: Throwable, doing: Doing): String {
        val m = Failures.message(e, doing)
        assertFalse(m.contains("/data"), m)
        assertFalse(m.contains("Salary"), m)
        assertFalse(m.contains("SQL"), m)
        assertFalse(m.contains("Exception"), m)
        return m
    }

    @Test
    fun anyUnknownFailureIsAPlainLineForWhatWasBeingDone() {
        val e = IllegalStateException(secret)
        assertEquals("Couldn't save. Try again.", clean(e, Doing.SAVE))
        assertEquals("The backup couldn't be saved. Try again.", clean(e, Doing.WRITE_BACKUP))
        assertEquals("Couldn't open that file.", clean(e, Doing.READ_BACKUP))
        assertEquals("Nothing was changed. Try again.", clean(e, Doing.RESTORE))
        assertEquals("Couldn't read that file.", clean(e, Doing.IMPORT_FILE))
        assertEquals("Nothing was saved. Try again.", clean(e, Doing.IMPORT_SAVE))
    }

    @Test
    fun inputRuleMessagesPassThroughOnlyWhileSaving() {
        val rule = IllegalArgumentException("Pick a category.")
        assertEquals("Pick a category.", Failures.message(rule, Doing.SAVE))
        assertEquals("Pick a category.", Failures.message(rule, Doing.IMPORT_SAVE))
        // Elsewhere an IllegalArgumentException is a decoding problem, not something written for the person.
        assertEquals("Nothing was changed. Try again.", Failures.message(IllegalArgumentException(secret), Doing.RESTORE))
        assertEquals(Failures.FILE_DAMAGED, Failures.message(IllegalArgumentException("bad base-64"), Doing.READ_BACKUP))
        assertEquals("Couldn't save. Try again.", Failures.message(IllegalArgumentException(""), Doing.SAVE))
        assertEquals("Couldn't save. Try again.", Failures.message(IllegalArgumentException(null as String?), Doing.SAVE))
    }

    @Test
    fun backupFailuresHaveTheirOwnWords() {
        assertEquals("That password doesn't open this backup.", Failures.message(BackupFile.WrongPassword(), Doing.READ_BACKUP))
        assertEquals("This backup has a password. Enter it, then pick the file again.", Failures.message(BackupFile.NeedsPassword(), Doing.READ_BACKUP))
        assertEquals("This backup is from a newer version of Kassa. Update Kassa to open it.", Failures.message(BackupFile.NewerVersion(), Doing.RESTORE))
        assertEquals("This file isn't a Kassa backup.", Failures.message(BackupFile.NotABackup(), Doing.READ_BACKUP))
        assertEquals(Failures.FILE_DAMAGED, Failures.message(BackupFile.NotABackup(), Doing.IMPORT_FILE))
        assertEquals(Failures.TOO_LARGE, Failures.message(BackupFile.TooLarge(), Doing.READ_BACKUP))
        assertEquals(Failures.TOO_LARGE, Failures.message(OutOfMemoryError(secret), Doing.READ_BACKUP))
    }

    @Test
    fun fullStorageIsRecognisedWhereverItSurfaces() {
        val full = IOException("write failed: ENOSPC (No space left on device)")
        for (doing in Doing.entries) assertEquals(Failures.NO_SPACE, Failures.message(full, doing))
        assertEquals(Failures.NO_SPACE, Failures.message(RuntimeException("wrapper", IOException("No space left on device")), Doing.WRITE_BACKUP))
        class SQLiteFullException(m: String) : RuntimeException(m)
        assertEquals(Failures.NO_SPACE, Failures.message(SQLiteFullException("database or disk is full"), Doing.SAVE))
    }

    @Test
    fun aFolderOrFileThatIsGoneIsSaidPlainly() {
        assertEquals(Failures.LOCATION_GONE, Failures.message(SecurityException(secret), Doing.WRITE_BACKUP))
        assertEquals(Failures.LOCATION_GONE, Failures.message(FileNotFoundException(secret), Doing.WRITE_BACKUP))
        assertEquals(Failures.FILE_UNREADABLE, Failures.message(FileNotFoundException(secret), Doing.READ_BACKUP))
        assertEquals(Failures.FILE_UNREADABLE, Failures.message(SecurityException(secret), Doing.IMPORT_FILE))
        assertEquals(Failures.FILE_DAMAGED, Failures.message(IOException(secret), Doing.READ_BACKUP))
    }

    @Test
    fun messagesWrittenForThePersonAreShownAsTheyAre() {
        assertEquals("Pick the folder again.", Failures.message(Failures.UserFacing("Pick the folder again."), Doing.WRITE_BACKUP))
        assertEquals("The backup couldn't be saved. Try again.", Failures.message(Failures.UserFacing(""), Doing.WRITE_BACKUP))
    }

    @Test
    fun theCodeNamesTheClassAndNeverTheMessage() {
        val plain = Failures.describe(IllegalStateException(secret), Doing.SAVE)
        assertEquals("IllegalStateException", plain.code)
        assertTrue(!plain.code.contains("Salary"))
        assertEquals("Exception", Failures.code(object : Exception(secret) {}))
    }
}
