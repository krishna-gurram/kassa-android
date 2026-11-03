package app.kassa.money.shared

import app.kassa.money.backup.BackupData
import app.kassa.money.backup.BackupFormatException
import app.kassa.money.backup.BackupJson

/**
 * What the iOS app calls to read and write the JSON inside a .kassa backup (Swift sees `BackupBridge.shared`). The file
 * envelope (header, password, gzip) is Swift's `BackupFile`; this is the JSON in it. Keep it thin: the rules are in
 * `app.kassa.money.backup`.
 */
object BackupBridge {

    /** Reads a backup's JSON. Swift sees an NSError for a [BackupFormatException]: not a backup, or from a newer Kassa. */
    @Throws(BackupFormatException::class)
    fun parse(text: String): BackupData = BackupJson.parse(text)

    fun write(data: BackupData): String = BackupJson.write(data)

    /** One line for the confirm step before restoring: "N entries, N accounts, N categories". */
    fun summary(data: BackupData): String = data.summary

    /** Null when [text] is a backup this version can read, otherwise the sentence to show the person. */
    fun refusal(text: String): String? = try {
        BackupJson.parse(text)
        null
    } catch (e: BackupFormatException) {
        e.message
    }

    /**
     * A few facts of [data] as "key=value" lines, so Swift tests and diagnostics can check what was read without walking
     * the classes. Lists are joined with "|".
     */
    fun digest(data: BackupData): String = listOf(
        "createdAt=${data.createdAt}",
        "monthStartDay=${data.monthStartDay}",
        "currency=${data.currency ?: ""}",
        "arrangement=${data.accountArrangement ?: ""}",
        "accounts=${data.accounts.joinToString("|") { it.name }}",
        "closedDays=${data.accounts.mapNotNull { it.closedDay }.joinToString("|")}",
        "categories=${data.categories.joinToString("|") { it.name }}",
        "amounts=${data.transactions.joinToString("|") { it.amount.toString() }}",
        "notes=${data.transactions.joinToString("|") { it.note }}",
        "loanRates=${data.loanTerms.joinToString("|") { it.rate.toString() }}",
        "icons=${data.icons.entries.joinToString("|") { it.key + ":" + it.value.size }}",
    ).joinToString("\n")
}
