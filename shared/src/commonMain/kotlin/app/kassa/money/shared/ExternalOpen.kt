package app.kassa.money.shared

/**
 * A file handed to the app from outside (Files, the share sheet's "Open in Kassa"). Which kind of file it is works out from what
 * is inside it (`PickedFileRules` through `ImportBridge`), exactly as for a picked file; this only decides where it goes, so the
 * routing is the same on every launch path and tested here.
 */
object ExternalOpen {

    /** What the app does with a file it is handed while in [state]: the `AppPhase` name as Swift words it. */
    enum class Route {
        /** Nothing is on the phone yet: read it into the empty store (first run). */
        IMPORT_NEW,

        /** Kassa has data: read it, and ask before replacing anything (as Settings > Import does). */
        IMPORT_REPLACE,

        /** The app is still opening: keep the file until it is ready. */
        WAIT,

        /** The data can't be opened: Kassa takes no file until that is dealt with. */
        IGNORE,
    }

    fun route(state: String): String = when (state) {
        "FIRST_RUN" -> Route.IMPORT_NEW.name
        "READY" -> Route.IMPORT_REPLACE.name
        "OPENING" -> Route.WAIT.name
        else -> Route.IGNORE.name
    }

    /** The extensions Kassa declares it can open (the Info.plist document types list the same). Lower case, no dot. */
    fun extensions(): List<String> = listOf("kassa", "mmbak", "sql")
}
