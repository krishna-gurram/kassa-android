package app.kassa.money.domain

/** The fields of the add person form that can have a problem. */
enum class PersonField { NAME, AMOUNT }

/**
 * The add person form, as typed: a name and, if money is already open between you, who owes whom. Both platforms check and read
 * it the same way ([InputRules] limits), and the repository checks again before writing.
 */
data class PersonForm(
    val name: String = "",
    /** True when they owe you (the default); false when you owe them. */
    val theyOwe: Boolean = true,
    val amount: String = "",
) {
    private val cents: Long? get() = if (amount.isBlank()) 0L else Money.parseCents(amount)

    /** Each field's problem; empty when the form can be saved. [takenNames] are the other people's names. */
    fun problems(takenNames: Collection<String>): Map<PersonField, String> = buildMap {
        InputRules.nameProblem(name, "person", taken = { n -> takenNames.any { it.equals(n, ignoreCase = true) } })?.let { put(PersonField.NAME, it) }
        InputRules.amountProblem(cents, zeroAllowed = true)?.let { put(PersonField.AMOUNT, it) }
    }

    /** What is owed already, positive when they owe you. Call only when [problems] is empty. */
    fun opening(): Long {
        val typed = checkNotNull(cents) { "opening called with an invalid amount" }
        return if (theyOwe) typed else -typed
    }

    val cleanName: String get() = InputRules.clean(name)

    companion object {
        /** The note of the line that holds what was owed already; the same text Android's `PeopleRepository` writes. */
        const val STARTING_NOTE = "Starting balance"
    }
}
