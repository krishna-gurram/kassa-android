package app.kassa.money.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportTest {
    @Test
    fun keepsClassNamesAndFramesButNeverMessages() {
        val cause = IllegalStateException("account Maria Savings 98765.43")
        val error = RuntimeException("note: zebra rooftop flat 7", cause)
        val text = CrashReport.describe(error)
        assertTrue(text.startsWith("java.lang.RuntimeException\n"))
        assertTrue(text.contains("Caused by: java.lang.IllegalStateException\n"))
        assertTrue(text.contains("at app.kassa.money.data.CrashReportTest"))
        assertFalse(text.contains("zebra"))
        assertFalse(text.contains("Maria"))
        assertFalse(text.contains("98765"))
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
