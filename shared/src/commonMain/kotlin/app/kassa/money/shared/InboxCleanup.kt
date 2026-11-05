package app.kassa.money.shared

/**
 * The files iOS copies into the app's Inbox when a file is opened from Files or the share sheet ("Open in Kassa"). Kassa reads such a
 * file once and never needs the copy again, so it is removed; this decides which files and when, and Swift only removes the paths it
 * is given. Only files directly inside a folder named `Inbox` or `<bundle id>-Inbox` are ever named, so a file the person picked
 * from anywhere else is never touched.
 */
object InboxCleanup {

    /** True when [path] is a file directly inside an `Inbox` or `...-Inbox` folder. */
    fun isInboxFile(path: String): Boolean {
        val parts = trimmed(path).split('/')
        if (parts.size < 2 || parts.last().isEmpty()) return false
        val folder = parts[parts.size - 2]
        return folder == "Inbox" || (folder.length > "-Inbox".length && folder.endsWith("-Inbox"))
    }

    /**
     * Whether the copy at [path] can go now: it is an Inbox file and no import or restore that reads it is still going on
     * ([flowActive]). A finished, cancelled or failed import all count as not going on.
     */
    fun removeAfterFlow(path: String, flowActive: Boolean): Boolean = !flowActive && isInboxFile(path)

    /**
     * What to remove at launch from [found], the files seen in the Inbox folders: every Inbox file except those in [keep] (the file
     * the app was opened with and the one being imported), so a cold start from a file still finds it.
     */
    fun staleAtLaunch(found: List<String>, keep: List<String>): List<String> {
        val kept = keep.filter { it.isNotEmpty() }.map { same(it) }.toSet()
        return found.filter { isInboxFile(it) && same(it) !in kept }
    }

    /** A path as compared: no trailing slash and no `/private` prefix (iOS writes `/var/...` and `/private/var/...` for one file). */
    private fun same(path: String): String {
        val text = trimmed(path)
        return if (text.startsWith("/private/")) text.removePrefix("/private") else text
    }

    private fun trimmed(path: String): String = path.trimEnd('/')
}
