package app.kassa.money.data

/**
 * The text kept in last-crash.txt, which the user may attach to feedback. Exception messages can hold names, notes or
 * amounts (a failed query, a bad value), so only class names and stack frames are written, never message text.
 */
object CrashReport {
    private const val MAX_CAUSES = 6
    private const val MAX_FRAMES = 60

    fun describe(error: Throwable): String {
        val out = StringBuilder()
        val seen = HashSet<Throwable>()
        var current: Throwable? = error
        var depth = 0
        while (current != null && seen.add(current) && depth < MAX_CAUSES) {
            out.append(if (depth == 0) "" else "Caused by: ").append(current.javaClass.name).append('\n')
            val frames = current.stackTrace
            frames.take(MAX_FRAMES).forEach { out.append("    at ").append(it).append('\n') }
            if (frames.size > MAX_FRAMES) out.append("    ... ").append(frames.size - MAX_FRAMES).append(" more\n")
            current = current.cause
            depth++
        }
        return out.toString()
    }
}
