package app.kassa.money.data.backup

import org.junit.Test
import java.io.StringReader
import java.io.StringWriter
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonStreamTest {
    private fun write(block: JsonOut.() -> Unit): String = StringWriter().also { JsonOut(it).block() }.toString()

    private fun malformed(text: String) = assertFailsWith<JsonMalformed>(text) {
        val r = JsonIn(StringReader(text))
        fun any() {
            when (r.peek()) {
                JsonToken.BEGIN_OBJECT -> { r.beginObject(); while (r.hasNext()) { r.nextName(); any() }; r.endObject() }
                JsonToken.BEGIN_ARRAY -> { r.beginArray(); while (r.hasNext()) any(); r.endArray() }
                else -> r.skipValue()
            }
        }
        any()
        r.endDocument()
    }

    @Test
    fun theWriterMakesPlainJson() {
        val text = write {
            beginObject().pair("a", 1).pair("b", "x\"y").opt("c", null as Long?).opt("d", true)
                .name("list").beginArray().value(1).value("two").beginObject().pair("k", false).endObject().beginArray().endArray().endArray()
                .name("empty").beginObject().endObject().endObject()
        }
        assertEquals("""{"a":1,"b":"x\"y","d":true,"list":[1,"two",{"k":false},[]],"empty":{}}""", text)
    }

    @Test
    fun theWriterRefusesWhatWouldNotBeJson() {
        assertFailsWith<IllegalStateException> { write { name("x") } }
        assertFailsWith<IllegalStateException> { write { beginObject().name("x").endObject() } }
        assertFailsWith<IllegalStateException> { write { endArray() } }
    }

    @Test
    fun theReaderReadsWhatTheWriterWrote() {
        val text = write {
            beginObject().pair("n", -42L).pair("s", "tab\there").pair("t", true).name("a").beginArray().value(1L).value(2L).endArray().endObject()
        }
        val r = JsonIn(StringReader(text))
        r.beginObject()
        assertEquals("n", r.nextName()); assertEquals(-42L, r.nextLong())
        assertEquals("s", r.nextName()); assertEquals("tab\there", r.nextString())
        assertEquals("t", r.nextName()); assertEquals(true, r.nextBoolean())
        assertEquals("a", r.nextName()); r.beginArray(); assertEquals(1, r.nextInt()); assertEquals(2, r.nextInt()); assertTrue(!r.hasNext()); r.endArray()
        assertTrue(!r.hasNext())
        r.endObject()
        r.endDocument()
    }

    @Test
    fun whitespaceAndEscapesAreAccepted() {
        val r = JsonIn(StringReader(" \n{ \"a\\/b\" :\t[ 1 , 2.0 , -3e2 , null , \"\\u0041\\u00e9\" ] }\n"))
        r.beginObject()
        assertEquals("a/b", r.nextName())
        r.beginArray()
        assertEquals(1L, r.nextLong()); assertEquals(2L, r.nextLong()); assertEquals(-300L, r.nextLong())
        r.nextNull()
        assertEquals("Aé", r.nextString())
        r.endArray(); r.endObject(); r.endDocument()
    }

    @Test
    fun aRowKeepsItsScalarsAndDropsNestedValues() {
        val row = JsonIn(StringReader("""{"id":5,"name":"x","ok":true,"none":null,"deep":{"a":[1,{"b":2}]},"big":"7"}""")).readRow()
        assertEquals(5L, row.long("id"))
        assertEquals("x", row.string("name"))
        assertEquals(true, row.boolean("ok"))
        assertEquals(null, row.longOrNull("none"))
        assertEquals(null, row.stringOrNull("deep"))
        assertEquals(7L, row.long("big"))
        assertEquals(false, row.boolean("missing", false))
        assertFailsWith<BackupFile.NotABackup> { row.long("missing") }
        assertFailsWith<BackupFile.NotABackup> { row.long("name") }
        assertFailsWith<BackupFile.NotABackup> { row.boolean("id") }
    }

    @Test
    fun brokenJsonIsMalformedNotACrash() {
        for (bad in listOf(
            "", "{", "[", "{\"a\"}", "{\"a\":}", "{\"a\":1,}", "[1,]", "[1 2]", "{\"a\":1 \"b\":2}", "{a:1}", "\"unterminated", "tru", "nul",
            "{} {}", "[] x", "\"bad \\q escape\"", "\"bad \\u12 escape\"", "-", "[1e]x", "{\"a\":[}",
        )) malformed(bad)
    }

    @Test
    fun deeplyNestedTextIsRefusedBeforeItCanOverflowAnything() {
        malformed("[".repeat(10_000) + "]".repeat(10_000))
    }

    @Test
    fun aVeryLongStringStillReads() {
        val s = "ab\"c".repeat(2_000_000)
        val text = write { value(s) }
        assertEquals(s, JsonIn(StringReader(text)).nextString())
    }

    @Test
    fun numbersOutOfRangeAreRefused() {
        assertFailsWith<JsonMalformed> { JsonIn(StringReader("3000000000")).nextInt() }
        assertFailsWith<JsonMalformed> { JsonIn(StringReader("1.5")).nextLong() }
        assertEquals(3_000_000_000L, JsonIn(StringReader("3000000000")).nextLong())
        assertEquals(Long.MAX_VALUE, JsonIn(StringReader(Long.MAX_VALUE.toString())).nextLong())
        assertEquals(Long.MIN_VALUE, JsonIn(StringReader(Long.MIN_VALUE.toString())).nextLong())
    }
}
