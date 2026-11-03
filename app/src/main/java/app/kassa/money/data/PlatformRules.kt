package app.kassa.money.data

import app.kassa.money.domain.Names
import app.kassa.money.domain.Repeats
import java.text.Collator
import java.text.DateFormatSymbols
import java.util.Locale

/** Hands the phone's own collation and month names to the plain Kotlin rules in `domain/`. Called first in `KassaApplication.onCreate`. */
object PlatformRules {
    fun install() {
        // Built on every sort, as before, so a language change is picked up at once.
        Names.comparatorProvider = {
            val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
            Comparator<String> { a, b -> collator.compare(a, b) }
        }
        // Read once, the first time a note is checked (Repeats keeps it).
        Repeats.monthWords = {
            listOf(Locale.ENGLISH, Locale.getDefault()).flatMap { l ->
                val s = DateFormatSymbols.getInstance(l)
                (s.months.toList() + s.shortMonths.toList())
            }.map { it.lowercase(Locale.ROOT).trimEnd('.') }.filter { it.isNotEmpty() }.toSet() + "sept"
        }
    }
}
