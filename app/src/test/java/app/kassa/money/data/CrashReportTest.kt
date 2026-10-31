package app.kassa.money.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportTest {
    @Test
    fun keepsClassNamesAndFramesButNeverMessages() {
        val cause = IllegalStateException("account Maria Savings 1234.56")
        val error = RuntimeException("note: rent for flat 7", cause)
        val text = CrashReport.describe(error)
        assertTrue(text.startsWith("java.lang.RuntimeException\n"))
        assertTrue(text.contains("Caused by: java.lang.IllegalStateException\n"))
        assertTrue(text.contains("at app.kassa.money.data.CrashReportTest"))
        assertFalse(text.contains("rent"))
        assertFalse(text.contains("Maria"))
        assertFalse(text.contains("1234"))
    }

    @Test
    fun aCauseThatPointsBackDoesNotLoop() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        val text = CrashReport.describe(a)
        assertTrue(text.contains("Caused by: java.lang.RuntimeException"))
    }
}
