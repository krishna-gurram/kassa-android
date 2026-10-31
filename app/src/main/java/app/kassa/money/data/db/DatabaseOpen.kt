package app.kassa.money.data.db

import app.kassa.money.data.security.OpenProblem

/** The result of opening Kassa's database. */
sealed interface DatabaseOpen {
    data class Ready(val database: KassaDatabase) : DatabaseOpen

    /** [problem] says whether the data itself is unreadable, which only starting over or a backup can fix. */
    data class Failed(val problem: OpenProblem) : DatabaseOpen
}
