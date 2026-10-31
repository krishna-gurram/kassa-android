package app.kassa.money.data

import app.kassa.money.data.backup.BackupFile
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The one place that turns a failure into words for the person. Exception text is never shown: it can hold file paths, SQL,
 * names or amounts, and means nothing to someone using a money app. Known failures get their own sentence; anything else gets a
 * plain line for what was being done. The messages the repositories write for an input rule ([IllegalArgumentException],
 * "Pick a category.") are written for the person, so while saving they pass through as they are.
 *
 * Plain Kotlin, so it has JVM tests. [code] is a short, non-sensitive name for feedback (the exception's class name, never
 * its message).
 */
object Failures {
    /** What was being done when it failed. */
    enum class Doing { SAVE, WRITE_BACKUP, READ_BACKUP, RESTORE, IMPORT_FILE, IMPORT_SAVE }

    /** An exception whose message was written for the person, so it is shown as it is. */
    class UserFacing(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** [message] is for the person; [code] is for a feedback note. */
    data class Plain(val message: String, val code: String)

    const val NO_SPACE = "Not enough space on this phone. Free some space and try again."
    const val LOCATION_GONE = "Kassa can't reach that folder or file any more. Pick it again."
    const val FILE_UNREADABLE = "That file couldn't be opened. Try picking it again."
    const val FILE_DAMAGED = "The file is damaged or cut short. Back up again and pick the new file."
    const val TOO_LARGE = "This backup is too large to open on this phone."

    fun describe(e: Throwable, doing: Doing): Plain = Plain(message(e, doing), code(e))

    fun code(e: Throwable): String = e.javaClass.simpleName.ifEmpty { "Exception" }.take(40)

    fun message(e: Throwable, doing: Doing): String {
        when (e) {
            is UserFacing -> return e.message.orEmpty().ifBlank { generic(doing) }
            is BackupFile.NeedsPassword, is BackupFile.WrongPassword, is BackupFile.NewerVersion, is BackupFile.TooLarge ->
                return e.message.orEmpty()
            is BackupFile.NotABackup ->
                return if (doing == Doing.IMPORT_FILE) FILE_DAMAGED else e.message.orEmpty()
            is OutOfMemoryError -> return TOO_LARGE
        }
        if (chain(e).any { isNoSpace(it) }) return NO_SPACE
        return when (doing) {
            Doing.SAVE, Doing.IMPORT_SAVE ->
                if (e is IllegalArgumentException && !e.message.isNullOrBlank()) e.message.orEmpty() else generic(doing)
            Doing.WRITE_BACKUP -> if (chain(e).any { it is SecurityException || it is FileNotFoundException }) LOCATION_GONE else generic(doing)
            Doing.READ_BACKUP, Doing.IMPORT_FILE -> when {
                chain(e).any { it is SecurityException || it is FileNotFoundException } -> FILE_UNREADABLE
                chain(e).any { it is IOException } || e is IllegalArgumentException -> FILE_DAMAGED
                else -> generic(doing)
            }
            Doing.RESTORE -> generic(doing)
        }
    }

    private fun generic(doing: Doing) = when (doing) {
        Doing.SAVE -> "Couldn't save. Try again."
        Doing.WRITE_BACKUP -> "The backup couldn't be saved. Try again."
        Doing.READ_BACKUP -> "Couldn't open that file."
        Doing.RESTORE -> "Nothing was changed. Try again."
        Doing.IMPORT_FILE -> "Couldn't read that file."
        Doing.IMPORT_SAVE -> "Nothing was saved. Try again."
    }

    /** [e] and its causes, outermost first, at most a few deep. */
    private fun chain(e: Throwable): List<Throwable> = generateSequence(e) { it.cause?.takeIf { c -> c !== it } }.take(6).toList()

    private fun isNoSpace(e: Throwable): Boolean {
        val name = e.javaClass.name
        if (name.endsWith("SQLiteFullException") || name.endsWith("SQLiteDiskIOException")) return true
        val text = e.message ?: return false
        return text.contains("ENOSPC") || text.contains("No space left", ignoreCase = true) || text.contains("disk full", ignoreCase = true)
    }
}
