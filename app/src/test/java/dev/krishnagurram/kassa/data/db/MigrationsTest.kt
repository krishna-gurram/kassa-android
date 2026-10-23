package dev.krishnagurram.kassa.data.db

import org.junit.Test
import kotlin.test.assertEquals

class MigrationsTest {
    /** A gap in the chain would leave phones on that version unable to open the database. */
    @Test
    fun migrationsStepOneVersionAtATimeUpToTheCurrentOne() {
        ALL_MIGRATIONS.forEachIndexed { i, m ->
            assertEquals(i + 1, m.startVersion)
            assertEquals(i + 2, m.endVersion)
        }
        assertEquals(DATABASE_VERSION, ALL_MIGRATIONS.last().endVersion)
    }
}
