package app.kassa.money.backup

import kotlin.math.abs
import kotlin.math.round

/*
 * A small JSON reader and writer over plain Kotlin strings, for backups on platforms without java.*. Text is UTF-16 in
 * Kotlin, so \uXXXX escapes (surrogate pairs included) simply become the characters; turning text into UTF-8 bytes is the
 * file layer's job. The reader follows Android's JsonStream.kt: strict about structure, lenient about number spelling.
 */

/** The text isn't JSON. */
internal class JsonMalformed(message: String) : Exception(message)

/**
 * Parses one JSON document into Kotlin values: null, Boolean, Long, Double (only for numbers that aren't whole or don't
 * fit a Long), String, List<Any?> and Map<String, Any?> (keys in file order). [topVersion] is the top-level "version" as
 * soon as it has been read, so a caller can tell a newer file even when the rest turns out unreadable.
 */
internal class JsonReader(private val text: String) {
    var topVersion: Long? = null
        private set
    private var pos = 0

    fun parseDocument(): Any? {
        skipWhitespace()
        val v = value(0)
        skipWhitespace()
        if (pos != text.length) fail("Text after the end of the document")
        return v
    }

    private fun fail(what: String): Nothing = throw JsonMalformed(what)

    private fun skipWhitespace() {
        while (pos < text.length) {
            val c = text[pos]
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++ else return
        }
    }

    private fun value(depth: Int): Any? {
        if (pos >= text.length) fail("Expected a value but the text ended")
        val c = text[pos]
        return when {
            c == '{' -> obj(depth + 1)
            c == '[' -> arr(depth + 1)
            c == '"' -> { pos++; string() }
            c == 't' -> { literal("true"); true }
            c == 'f' -> { literal("false"); false }
            c == 'n' -> { literal("null"); null }
            c == '-' || (c >= '0' && c <= '9') -> number()
            else -> fail("Expected a value")
        }
    }

    private fun literal(word: String) {
        if (!text.startsWith(word, pos)) fail("Not a JSON literal")
        pos += word.length
    }

    private fun obj(depth: Int): Map<String, Any?> {
        if (depth > MAX_DEPTH) fail("Nested too deeply")
        pos++ // {
        val map = LinkedHashMap<String, Any?>()
        skipWhitespace()
        if (pos < text.length && text[pos] == '}') { pos++; return map }
        while (true) {
            skipWhitespace()
            if (pos >= text.length || text[pos] != '"') fail("Expected a name")
            pos++
            val name = string()
            skipWhitespace()
            if (pos >= text.length || text[pos] != ':') fail("Expected :")
            pos++
            skipWhitespace()
            val v = value(depth)
            map[name] = v
            if (depth == 1 && name == "version") topVersion = JsonValues.wholeNumber(v)?.takeIf { it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }
            skipWhitespace()
            if (pos >= text.length) fail("The object never ends")
            val c = text[pos++]
            if (c == '}') return map
            if (c != ',') fail("Expected , or }")
        }
    }

    private fun arr(depth: Int): List<Any?> {
        if (depth > MAX_DEPTH) fail("Nested too deeply")
        pos++ // [
        val list = ArrayList<Any?>()
        skipWhitespace()
        if (pos < text.length && text[pos] == ']') { pos++; return list }
        while (true) {
            skipWhitespace()
            list.add(value(depth))
            skipWhitespace()
            if (pos >= text.length) fail("The array never ends")
            val c = text[pos++]
            if (c == ']') return list
            if (c != ',') fail("Expected , or ]")
        }
    }

    private fun number(): Any {
        val start = pos
        while (pos < text.length) {
            val c = text[pos]
            val part = (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E'
            if (!part) break
            pos++
            if (pos - start > 40) fail("A number that long isn't one")
        }
        val token = text.substring(start, pos)
        return token.toLongOrNull() ?: token.toDoubleOrNull() ?: fail("Not a number")
    }

    /** The rest of a string whose opening quote was read. */
    private fun string(): String {
        val b = StringBuilder()
        var from = pos
        while (true) {
            if (pos >= text.length) fail("A string that never ends")
            val c = text[pos]
            if (c == '"') {
                b.append(text.substring(from, pos))
                pos++
                return b.toString()
            }
            if (c != '\\') { pos++; continue }
            b.append(text.substring(from, pos))
            pos++
            if (pos >= text.length) fail("A string that never ends")
            when (val e = text[pos++]) {
                '"', '\\', '/' -> b.append(e)
                'b' -> b.append('\b')
                'f' -> b.append('\u000C')
                'n' -> b.append('\n')
                'r' -> b.append('\r')
                't' -> b.append('\t')
                'u' -> {
                    if (pos + 4 > text.length) fail("Not a \\u escape")
                    var code = 0
                    for (i in 0 until 4) {
                        val h = hex(text[pos + i])
                        if (h < 0) fail("Not a \\u escape")
                        code = code * 16 + h
                    }
                    pos += 4
                    b.append(code.toChar())
                }
                else -> fail("Not an escape")
            }
            from = pos
        }
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private companion object {
        const val MAX_DEPTH = 64
    }
}

internal object JsonValues {
    /** A whole number from a number or a string holding one, as Android's `nextLong` reads it; null for anything else. */
    fun wholeNumber(v: Any?): Long? = when (v) {
        is Long -> v
        is Double -> wholeDouble(v)
        is String -> v.trim().let { it.toLongOrNull() ?: it.toDoubleOrNull()?.let(::wholeDouble) }
        else -> null
    }

    fun wholeDouble(d: Double): Long? = if (d == round(d) && abs(d) < 9.0e18) d.toLong() else null
}

/** Writes JSON text to [out] as values are given. Containers must be closed in order; [name] comes before each object value. */
internal class JsonWriter(private val out: StringBuilder) {
    /** One entry per open container: whether it already holds a value. */
    private val open = ArrayList<Boolean>()
    private var afterName = false

    private fun beforeValue() {
        if (afterName) { afterName = false; return }
        if (open.isNotEmpty()) {
            if (open[open.lastIndex]) out.append(',')
            open[open.lastIndex] = true
        }
    }

    fun beginObject(): JsonWriter { beforeValue(); out.append('{'); open.add(false); return this }
    fun endObject(): JsonWriter { check(open.isNotEmpty() && !afterName); open.removeAt(open.lastIndex); out.append('}'); return this }
    fun beginArray(): JsonWriter { beforeValue(); out.append('['); open.add(false); return this }
    fun endArray(): JsonWriter { check(open.isNotEmpty() && !afterName); open.removeAt(open.lastIndex); out.append(']'); return this }

    fun name(name: String): JsonWriter {
        check(open.isNotEmpty() && !afterName)
        if (open[open.lastIndex]) out.append(',')
        open[open.lastIndex] = true
        quote(name)
        out.append(':')
        afterName = true
        return this
    }

    fun value(v: String): JsonWriter { beforeValue(); quote(v); return this }
    fun value(v: Long): JsonWriter { beforeValue(); out.append(v.toString()); return this }
    fun value(v: Int): JsonWriter { beforeValue(); out.append(v.toString()); return this }
    fun value(v: Boolean): JsonWriter { beforeValue(); out.append(if (v) "true" else "false"); return this }

    /** The pair [name]: [v], or nothing at all when [v] is null (an absent key reads as null). */
    fun opt(name: String, v: Long?): JsonWriter = if (v == null) this else name(name).value(v)
    fun opt(name: String, v: Int?): JsonWriter = if (v == null) this else name(name).value(v)
    fun opt(name: String, v: String?): JsonWriter = if (v == null) this else name(name).value(v)
    fun opt(name: String, v: Boolean?): JsonWriter = if (v == null) this else name(name).value(v)

    fun pair(name: String, v: Long): JsonWriter = name(name).value(v)
    fun pair(name: String, v: Int): JsonWriter = name(name).value(v)
    fun pair(name: String, v: String): JsonWriter = name(name).value(v)
    fun pair(name: String, v: Boolean): JsonWriter = name(name).value(v)

    private fun quote(s: String) {
        out.append('"')
        var from = 0
        for (i in s.indices) {
            val c = s[i]
            val escape = when {
                c == '"' -> "\\\""
                c == '\\' -> "\\\\"
                c == '\n' -> "\\n"
                c == '\r' -> "\\r"
                c == '\t' -> "\\t"
                c == '\b' -> "\\b"
                c == '\u000C' -> "\\f"
                c < ' ' || c == ' ' || c == ' ' -> "\\u" + c.code.toString(16).padStart(4, '0')
                else -> null
            }
            if (escape != null) {
                out.append(s.substring(from, i))
                out.append(escape)
                from = i + 1
            }
        }
        out.append(s.substring(from))
        out.append('"')
    }
}
