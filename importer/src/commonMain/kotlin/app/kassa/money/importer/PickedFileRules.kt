package app.kassa.money.importer

/**
 * What kind of file the person picked on the Import page, worked out from what is inside it rather than its name. These are the
 * rules of Android's `PickedFile.identify`, without the files: the platform reads the first 16 bytes and the table names and asks.
 * Kinds are plain text so Swift compares them as strings.
 */
object PickedFileRules {

    const val KIND_EMPTY = "EMPTY"
    const val KIND_KASSA = "KASSA"
    const val KIND_KASSA_PROTECTED = "KASSA_PROTECTED"
    const val KIND_SQLITE = "SQLITE"
    const val KIND_TEXT = "TEXT"
    const val KIND_OTHER = "OTHER"

    const val KIND_MONEY_MANAGER = "MONEY_MANAGER"
    const val KIND_CASHEW = "CASHEW"
    const val KIND_UNKNOWN = "UNKNOWN"

    private const val SUPPORTED = "Kassa reads its own backups (.kassa), Money Manager backups (.mmbak) and Cashew backups (.sql)."

    const val DAMAGED = "The file is damaged or cut short. Back up again and pick the new file."
    const val NOT_MONEY_MANAGER = "This file isn't a Money Manager backup. In Money Manager, use Backup to save a .mmbak file, then pick that file."

    /**
     * What the first bytes say. [head] is the first 16 bytes of the file with each byte as the character of the same number (Latin-1),
     * zero-padded to 16 when the file is shorter; [size] is the file's length in bytes.
     */
    fun kindOfHead(head: String, size: Long): String {
        if (size == 0L) return KIND_EMPTY
        val padded = head.take(16).padEnd(16, '\u0000')
        if (padded.startsWith("KASSA0")) return KIND_KASSA
        if (padded.startsWith("KASSA1")) return KIND_KASSA_PROTECTED
        if (padded.startsWith("SQLite format 3")) return KIND_SQLITE
        // Tab, line breaks, printable ASCII and anything with the top bit set count as text (as Android's signed-byte test does).
        val text = padded.all { val c = it.code; c == 9 || c == 10 || c == 13 || c in 32..126 || c in 128..255 }
        return if (text) KIND_TEXT else KIND_OTHER
    }

    /** The sentence for a file that is none of the kinds Kassa reads ([KIND_EMPTY], [KIND_TEXT], [KIND_OTHER], [KIND_UNKNOWN]); "" for the others. */
    fun refusal(kind: String): String = when (kind) {
        KIND_EMPTY -> "The file is empty. Back up again and pick the new file."
        KIND_TEXT -> "This looks like a spreadsheet or text file. Kassa can't import those yet. $SUPPORTED"
        KIND_OTHER -> "This isn't a backup Kassa can read. $SUPPORTED"
        KIND_UNKNOWN -> "This isn't a backup Kassa can read. $SUPPORTED"
        else -> ""
    }

    /** Which app's SQLite file this is, from its table names: [KIND_MONEY_MANAGER], [KIND_CASHEW] or [KIND_UNKNOWN]. */
    fun kindOfTables(tables: List<String>): String = when {
        MoneyManagerSql.isAndroidLayout(tables) || MoneyManagerSql.isIphoneLayout(tables) -> KIND_MONEY_MANAGER
        CashewSql.looksLikeCashew(tables) -> KIND_CASHEW
        else -> KIND_UNKNOWN
    }
}
