package app.kassa.money.domain

import kotlin.math.floor

/** Rounds half up (2.5 is 3, -2.5 is -2), like the JDK's Math.round, with no JDK class so it also runs on iOS. */
fun roundHalfUp(x: Double): Long {
    val down = floor(x)
    return (if (x - down >= 0.5) down + 1 else down).toLong()
}
