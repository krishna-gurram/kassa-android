package app.kassa.money.domain

import kotlin.concurrent.Volatile

/** Every list of names is A to Z in the phone's language, ignoring case and accents. */
object Names {
    /**
     * Where the phone's collation comes from. Android installs a java.text.Collator (primary strength) in
     * `PlatformRules.install()`; it is read on every [order] call, so a language change is picked up the next time a list is sorted.
     * The default only ignores case, which is deterministic on every platform.
     */
    @Volatile
    var comparatorProvider: () -> Comparator<String> = { DEFAULT }

    private val DEFAULT: Comparator<String> = Comparator { a, b -> a.lowercase().compareTo(b.lowercase()) }

    fun <T> order(name: (T) -> String): Comparator<T> {
        val collator = comparatorProvider()
        return Comparator { a, b -> collator.compare(name(a).trim(), name(b).trim()) }
    }
}
