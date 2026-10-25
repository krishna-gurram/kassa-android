package app.kassa.money.domain

import java.text.Collator

/** Every list of names is A to Z in the phone's language, ignoring case and accents. */
object Names {
    fun <T> order(name: (T) -> String): Comparator<T> {
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        return Comparator { a, b -> collator.compare(name(a).trim(), name(b).trim()) }
    }
}
