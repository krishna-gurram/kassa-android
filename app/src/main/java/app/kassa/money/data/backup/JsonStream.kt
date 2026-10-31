package app.kassa.money.data.backup

import java.io.Reader
import java.io.Writer

/*
 * A small streaming JSON writer and reader, plain Kotlin on purpose: a backup of tens of thousands of entries is written
 * and read one value at a time, so neither the file's text nor a tree of it is ever held in memory, and the same code runs
 * in JVM tests (android.util.JsonWriter and JsonReader don't). The output is ordinary JSON, the same shape the earlier
 * org.json based code wrote, and the reader accepts everything that code wrote (escaped slashes included).
 */

/** Writes JSON to [w] as values are given. Containers must be closed in order; [name] comes before each object value. */
internal class JsonOut(private val w: Writer) {
    /** One entry per open container: whether it already holds a value. */
    private val open = ArrayList<Boolean>()
    private var afterName = false

    private fun beforeValue() {
        if (afterName) { afterName = false; return }
        if (open.isNotEmpty()) {
            if (open.last()) w.write(",")
            open[open.lastIndex] = true
        }
    }

    fun beginObject(): JsonOut { beforeValue(); w.write("{"); open.add(false); return this }
    fun endObject(): JsonOut { check(open.isNotEmpty() && !afterName) { "No object to close" }; open.removeAt(open.lastIndex); w.write("}"); return this }
    fun beginArray(): JsonOut { beforeValue(); w.write("["); open.add(false); return this }
    fun endArray(): JsonOut { check(open.isNotEmpty() && !afterName) { "No array to close" }; open.removeAt(open.lastIndex); w.write("]"); return this }

    fun name(name: String): JsonOut {
        check(open.isNotEmpty() && !afterName) { "A name needs an open object" }
        if (open.last()) w.write(",")
        open[open.lastIndex] = true
        quote(name)
        w.write(":")
        afterName = true
        return this
    }

    fun value(v: String): JsonOut { beforeValue(); quote(v); return this }
    fun value(v: Long): JsonOut { beforeValue(); w.write(v.toString()); return this }
    fun value(v: Int): JsonOut { beforeValue(); w.write(v.toString()); return this }
    fun value(v: Boolean): JsonOut { beforeValue(); w.write(if (v) "true" else "false"); return this }

    /** The pair [name]: [v], or nothing at all when [v] is null (an absent key reads as null, as it always has). */
    fun opt(name: String, v: Long?): JsonOut = if (v == null) this else name(name).value(v)
    fun opt(name: String, v: Int?): JsonOut = if (v == null) this else name(name).value(v)
    fun opt(name: String, v: String?): JsonOut = if (v == null) this else name(name).value(v)
    fun opt(name: String, v: Boolean?): JsonOut = if (v == null) this else name(name).value(v)

    fun pair(name: String, v: Long): JsonOut = name(name).value(v)
    fun pair(name: String, v: Int): JsonOut = name(name).value(v)
    fun pair(name: String, v: String): JsonOut = name(name).value(v)
    fun pair(name: String, v: Boolean): JsonOut = name(name).value(v)

    fun flush() = w.flush()

    private fun quote(s: String) {
        w.write("\"")
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
                w.write(s, from, i - from)
                w.write(escape)
                from = i + 1
            }
        }
        w.write(s, from, s.length - from)
        w.write("\"")
    }
}

/** What a [JsonIn] sees next. */
internal enum class JsonToken { BEGIN_OBJECT, END_OBJECT, BEGIN_ARRAY, END_ARRAY, NAME, STRING, NUMBER, BOOLEAN, NULL, END_DOCUMENT }

/** The text isn't JSON, or isn't the JSON that was asked for. */
internal class JsonMalformed(message: String) : Exception(message)

/**
 * Reads JSON from [r] one token at a time. Strict about structure (a missing comma, a trailing comma, text after the
 * document, nesting past [MAX_DEPTH] are all [JsonMalformed]) and lenient about nothing else: unknown keys are for the
 * caller to [skipValue]. Errors from the underlying reader (a damaged gzip stream) pass through untouched.
 */
internal class JsonIn(private val r: Reader) {
    private val buffer = CharArray(8192)
    private var length = 0
    private var position = 0

    /** Per open container (and the document): what is expected next. */
    private val stack = ArrayList<Int>().apply { add(EMPTY_DOCUMENT) }

    private var peeked: JsonToken? = null
    /** The text of the string, name or number just peeked. */
    private var text = ""
    private var flag = false

    private fun read(): Int {
        if (position == length) {
            length = r.read(buffer, 0, buffer.size)
            position = 0
            if (length <= 0) { length = 0; return -1 }
        }
        return buffer[position++].code
    }

    private fun nonWhitespace(): Int {
        while (true) {
            val c = read()
            if (c != ' '.code && c != '\n'.code && c != '\r'.code && c != '\t'.code) return c
        }
    }

    private fun fail(what: String): Nothing = throw JsonMalformed(what)

    fun peek(): JsonToken = peeked ?: doPeek().also { peeked = it }

    private fun doPeek(): JsonToken {
        when (stack.last()) {
            EMPTY_DOCUMENT -> return valueStart(nonWhitespace())
            NONEMPTY_DOCUMENT -> {
                if (nonWhitespace() != -1) fail("Text after the end of the document")
                return JsonToken.END_DOCUMENT
            }
            EMPTY_ARRAY -> {
                val c = nonWhitespace()
                return if (c == ']'.code) JsonToken.END_ARRAY else valueStart(c)
            }
            NONEMPTY_ARRAY -> return when (val c = nonWhitespace()) {
                ']'.code -> JsonToken.END_ARRAY
                ','.code -> valueStart(nonWhitespace())
                else -> fail("Expected , or ] but found ${describe(c)}")
            }
            EMPTY_OBJECT -> return when (val c = nonWhitespace()) {
                '}'.code -> JsonToken.END_OBJECT
                '"'.code -> nameStart()
                else -> fail("Expected a name or } but found ${describe(c)}")
            }
            NONEMPTY_OBJECT -> return when (val c = nonWhitespace()) {
                '}'.code -> JsonToken.END_OBJECT
                ','.code -> if (nonWhitespace() == '"'.code) nameStart() else fail("Expected a name after the comma")
                else -> fail("Expected , or } but found ${describe(c)}")
            }
            else -> { // a name was read: its value comes after the colon
                val c = nonWhitespace()
                if (c != ':'.code) fail("Expected : but found ${describe(c)}")
                return valueStart(nonWhitespace())
            }
        }
    }

    private fun describe(c: Int) = if (c == -1) "the end of the text" else "'${c.toChar()}'"

    private fun nameStart(): JsonToken { text = string(); return JsonToken.NAME }

    private fun valueStart(c: Int): JsonToken = when {
        c == '{'.code -> JsonToken.BEGIN_OBJECT
        c == '['.code -> JsonToken.BEGIN_ARRAY
        c == '"'.code -> { text = string(); JsonToken.STRING }
        c == 't'.code -> { literal("rue"); flag = true; JsonToken.BOOLEAN }
        c == 'f'.code -> { literal("alse"); flag = false; JsonToken.BOOLEAN }
        c == 'n'.code -> { literal("ull"); JsonToken.NULL }
        c == '-'.code || (c >= '0'.code && c <= '9'.code) -> { text = number(c); JsonToken.NUMBER }
        else -> fail("Expected a value but found ${describe(c)}")
    }

    private fun literal(rest: String) {
        for (ch in rest) if (read() != ch.code) fail("Not a JSON literal")
    }

    private fun number(first: Int): String {
        val b = StringBuilder().append(first.toChar())
        while (true) {
            val c = read()
            val part = c in '0'.code..'9'.code || c == '.'.code || c == '-'.code || c == '+'.code || c == 'e'.code || c == 'E'.code
            if (!part) {
                // Not part of the number: put it back.
                if (c != -1) position--
                val token = b.toString()
                if (token.toLongOrNull() == null && token.toDoubleOrNull() == null) fail("Not a number: $token")
                return token
            }
            b.append(c.toChar())
            if (b.length > 40) fail("A number that long isn't one")
        }
    }

    /** The rest of a string whose opening quote was read. */
    private fun string(): String {
        val b = StringBuilder()
        while (true) {
            val c = read()
            when {
                c == -1 -> fail("A string that never ends")
                c == '"'.code -> return b.toString()
                c == '\\'.code -> when (val e = read()) {
                    '"'.code, '\\'.code, '/'.code -> b.append(e.toChar())
                    'b'.code -> b.append('\b')
                    'f'.code -> b.append('\u000C')
                    'n'.code -> b.append('\n')
                    'r'.code -> b.append('\r')
                    't'.code -> b.append('\t')
                    'u'.code -> {
                        var code = 0
                        repeat(4) {
                            val h = Character.digit(read(), 16)
                            if (h < 0) fail("Not a \\u escape")
                            code = code * 16 + h
                        }
                        b.append(code.toChar())
                    }
                    else -> fail("Not an escape: \\${if (e == -1) "" else e.toChar().toString()}")
                }
                else -> b.append(c.toChar())
            }
        }
    }

    /** Counts the value that was just consumed in its container. */
    private fun valueRead() {
        stack[stack.lastIndex] = when (stack.last()) {
            EMPTY_DOCUMENT -> NONEMPTY_DOCUMENT
            EMPTY_ARRAY, NONEMPTY_ARRAY -> NONEMPTY_ARRAY
            else -> NONEMPTY_OBJECT // the value that follows a name
        }
    }

    private fun expect(t: JsonToken): Unit { if (peek() != t) fail("Expected $t but found ${peek()}") }

    fun beginObject() { expect(JsonToken.BEGIN_OBJECT); begin(EMPTY_OBJECT) }
    fun beginArray() { expect(JsonToken.BEGIN_ARRAY); begin(EMPTY_ARRAY) }

    private fun begin(state: Int) {
        if (stack.size > MAX_DEPTH) fail("Nested too deeply")
        valueRead()
        stack.add(state)
        peeked = null
    }

    fun endObject() { expect(JsonToken.END_OBJECT); stack.removeAt(stack.lastIndex); peeked = null }
    fun endArray() { expect(JsonToken.END_ARRAY); stack.removeAt(stack.lastIndex); peeked = null }

    /** The document has been read to its end, with nothing after it. */
    fun endDocument() { expect(JsonToken.END_DOCUMENT) }

    /** True while the current object or array has another name or value. */
    fun hasNext(): Boolean = peek().let { it != JsonToken.END_OBJECT && it != JsonToken.END_ARRAY && it != JsonToken.END_DOCUMENT }

    fun nextName(): String {
        expect(JsonToken.NAME)
        stack[stack.lastIndex] = DANGLING_NAME
        peeked = null
        return text
    }

    /** A string; a number is read as its text. */
    fun nextString(): String {
        val t = peek()
        if (t != JsonToken.STRING && t != JsonToken.NUMBER) fail("Expected a string but found $t")
        valueRead(); peeked = null
        return text
    }

    /** A whole number; a number in a string is accepted, as the earlier org.json code did. */
    fun nextLong(): Long {
        val t = peek()
        if (t != JsonToken.STRING && t != JsonToken.NUMBER) fail("Expected a number but found $t")
        val v = text.trim().toLongOrNull() ?: text.trim().toDoubleOrNull()?.takeIf { it == Math.rint(it) && Math.abs(it) < 9.0e18 }?.toLong()
            ?: fail("Not a whole number")
        valueRead(); peeked = null
        return v
    }

    fun nextInt(): Int {
        val v = nextLong()
        if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) fail("Number out of range")
        return v.toInt()
    }

    fun nextBoolean(): Boolean {
        val t = peek()
        val v = when {
            t == JsonToken.BOOLEAN -> flag
            t == JsonToken.STRING && text == "true" -> true
            t == JsonToken.STRING && text == "false" -> false
            else -> fail("Expected true or false but found $t")
        }
        valueRead(); peeked = null
        return v
    }

    fun nextNull() { expect(JsonToken.NULL); valueRead(); peeked = null }

    /** Reads and drops the next value, whatever it is, nested containers included. */
    fun skipValue() {
        var depth = 0
        do {
            when (peek()) {
                JsonToken.BEGIN_OBJECT -> { beginObject(); depth++ }
                JsonToken.BEGIN_ARRAY -> { beginArray(); depth++ }
                JsonToken.END_OBJECT -> { endObject(); depth-- }
                JsonToken.END_ARRAY -> { endArray(); depth-- }
                JsonToken.NAME -> nextName()
                JsonToken.STRING, JsonToken.NUMBER -> nextString()
                JsonToken.BOOLEAN -> nextBoolean()
                JsonToken.NULL -> nextNull()
                JsonToken.END_DOCUMENT -> fail("The document ended inside a value")
            }
        } while (depth > 0)
    }

    /**
     * Reads one flat object into a [JsonRow]. Values that aren't strings, numbers, booleans or null (nested objects and
     * arrays, which no table has) are dropped.
     */
    fun readRow(): JsonRow {
        beginObject()
        val values = HashMap<String, Any?>()
        while (hasNext()) {
            val name = nextName()
            values[name] = when (peek()) {
                JsonToken.STRING -> nextString()
                JsonToken.NUMBER -> nextString().let { n -> n.toLongOrNull() ?: n.toDoubleOrNull() ?: fail("Not a number") }
                JsonToken.BOOLEAN -> nextBoolean()
                JsonToken.NULL -> { nextNull(); null }
                else -> { skipValue(); null }
            }
        }
        endObject()
        return JsonRow(values)
    }

    /** Reads an array of flat objects (or null) and maps each with [map]. */
    fun <T> rows(map: JsonRow.() -> T): List<T> {
        if (peek() == JsonToken.NULL) { nextNull(); return emptyList() }
        beginArray()
        val list = ArrayList<T>()
        while (hasNext()) list.add(readRow().map())
        endArray()
        return list
    }

    private companion object {
        const val EMPTY_ARRAY = 0
        const val NONEMPTY_ARRAY = 1
        const val EMPTY_OBJECT = 2
        const val NONEMPTY_OBJECT = 3
        const val DANGLING_NAME = 4
        const val EMPTY_DOCUMENT = 6
        const val NONEMPTY_DOCUMENT = 7
        const val MAX_DEPTH = 64
    }
}

/**
 * One row of a table as read: its keys and scalar values. A missing key or a value of the wrong kind in a required
 * field means the file isn't a Kassa backup ([BackupFile.NotABackup]); an optional field that is absent or null is null.
 */
internal class JsonRow(private val values: Map<String, Any?>) {
    private fun bad(): Nothing = throw BackupFile.NotABackup()

    fun longOrNull(k: String): Long? = when (val v = values[k]) {
        null -> null
        is Long -> v
        is Double -> v.takeIf { it == Math.rint(it) && Math.abs(it) < 9.0e18 }?.toLong() ?: bad()
        is String -> v.trim().toLongOrNull() ?: bad()
        else -> bad()
    }

    fun long(k: String): Long = longOrNull(k) ?: bad()

    fun intOrNull(k: String): Int? = longOrNull(k)?.let { if (it < Int.MIN_VALUE || it > Int.MAX_VALUE) bad() else it.toInt() }

    fun int(k: String): Int = intOrNull(k) ?: bad()

    fun stringOrNull(k: String): String? = when (val v = values[k]) {
        null -> null
        is String -> v
        is Long, is Double -> v.toString()
        else -> bad()
    }

    fun string(k: String): String = stringOrNull(k) ?: bad()

    fun booleanOrNull(k: String): Boolean? = when (val v = values[k]) {
        null -> null
        is Boolean -> v
        "true" -> true
        "false" -> false
        else -> bad()
    }

    fun boolean(k: String): Boolean = booleanOrNull(k) ?: bad()

    fun boolean(k: String, default: Boolean): Boolean = booleanOrNull(k) ?: default
}
