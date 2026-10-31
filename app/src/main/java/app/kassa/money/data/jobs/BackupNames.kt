package app.kassa.money.data.jobs

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * The names of the backups Kassa saves into the chosen folder, and which of them to keep. Kassa keeps the newest [KEEP]
 * files that carry its own naming pattern and deletes older ones; it never deletes a file that doesn't match the pattern
 * exactly (a password copy, a file the person put there, a "(1)" duplicate a file provider made). Plain Kotlin, so the rule
 * has JVM tests.
 */
object BackupNames {
    /** How many daily backups stay in the folder. */
    const val KEEP = 30

    private const val PREFIX = "Kassa_"
    private const val SUFFIX = ".kassa"
    private val STAMP = Regex("""\d{8}_\d{6}""")
    private val FORMAT = DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss").withResolverStyle(ResolverStyle.STRICT)

    /** The file name for a backup made at [now]: Kassa_yyyyMMdd_HHmmss.kassa. */
    fun nameFor(now: LocalDateTime): String = PREFIX + FORMAT.format(now) + SUFFIX

    /** True when [name] is exactly a name [nameFor] makes. */
    fun isOurs(name: String): Boolean {
        if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) return false
        val stamp = name.substring(PREFIX.length, name.length - SUFFIX.length)
        if (!STAMP.matches(stamp)) return false
        return try { LocalDateTime.parse(stamp, FORMAT); true } catch (e: java.time.format.DateTimeParseException) { false }
    }

    /**
     * Of the names in the folder, the ones to delete: Kassa's own files beyond the newest [keep]. The stamp sorts by time, so
     * newest is last in name order. Anything not matching the pattern is never in the answer.
     */
    fun toDelete(names: Collection<String>, keep: Int = KEEP): List<String> =
        names.filter(::isOurs).distinct().sortedDescending().drop(keep.coerceAtLeast(1))
}
