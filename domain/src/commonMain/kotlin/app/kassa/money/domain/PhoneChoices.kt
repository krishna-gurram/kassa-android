package app.kassa.money.domain

/** The theme choice in Settings: follow the phone, or fix light or dark. Stored by name. */
enum class ThemeChoice {
    SYSTEM, LIGHT, DARK;

    companion object {
        /** The choice a stored name means; anything unknown follows the phone. */
        fun of(stored: String?): ThemeChoice = entries.firstOrNull { it.name == stored } ?: SYSTEM
    }
}

/** The tab Kassa shows on a fresh start. Settings isn't offered: it is never where you would want to land. Stored by name. */
enum class HomeTab {
    TODAY, ACTIVITY, INSIGHTS, ACCOUNTS;

    companion object {
        /** The tab a stored name means; anything unknown (or Settings) is Overview. */
        fun of(stored: String?): HomeTab = entries.firstOrNull { it.name == stored } ?: TODAY
    }
}

/** The day a budget month starts on, 1 to 28 so every month has it. */
object BudgetMonthStart {
    const val FIRST_DAY = 1
    const val LAST_DAY = 28

    /** The quick picks Settings offers beside a typed day. */
    val QUICK_DAYS: List<Int> = listOf(1, 5, 10, 15, 20, 25)

    fun clamp(day: Int): Int = day.coerceIn(FIRST_DAY, LAST_DAY)

    /** Whether [typed] (digits a person entered) names a valid day. */
    fun isValid(typed: String): Boolean = typed.toIntOrNull()?.let { it in FIRST_DAY..LAST_DAY } ?: false
}

/**
 * Settings > Data > Account to person: an account that stood in for money between you and someone (another app had no idea of
 * people) becomes a person carrying the same balance, and the account is archived with its history. The person picks the
 * account; nothing is matched by name.
 */
object AccountToPerson {

    /** Open plain balances only: cards, loans and investments have their own pages, holdings and terms. */
    fun isConvertible(account: Account): Boolean =
        account.isActive && account.type != AccountType.CREDIT_CARD && account.type != AccountType.LOAN && account.type != AccountType.INVESTMENT

    /** The note of the line that carries the balance over. */
    fun note(accountName: String): String = "Balance from $accountName"

    /** The sentence under the name field when [name] can't be the person's, else null. [taken] are the existing people. */
    fun nameProblem(name: String, taken: List<String>): String? =
        InputRules.nameProblem(name, "person", taken = { n -> taken.any { it.equals(n, ignoreCase = true) } })

    fun cleanName(name: String): String = InputRules.clean(name)
}
