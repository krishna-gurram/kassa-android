package app.kassa.money.data.backup

import java.io.StringReader
import java.io.StringWriter

/** Tests change a backup's JSON as a tree (drop a key, add one) to stand in for files from other versions of Kassa. */
internal object JsonTree {
    /** Objects become `MutableMap`, arrays `MutableList`, whole numbers `Long`, everything else as itself. */
    fun parse(text: String): Any? {
        val r = JsonIn(StringReader(text))
        return value(r).also { r.endDocument() }
    }

    private fun value(r: JsonIn): Any? = when (r.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val m = LinkedHashMap<String, Any?>()
            r.beginObject()
            while (r.hasNext()) { val k = r.nextName(); m[k] = value(r) }
            r.endObject()
            m
        }
        JsonToken.BEGIN_ARRAY -> {
            val l = ArrayList<Any?>()
            r.beginArray()
            while (r.hasNext()) l.add(value(r))
            r.endArray()
            l
        }
        JsonToken.STRING -> r.nextString()
        JsonToken.NUMBER -> r.nextLong()
        JsonToken.BOOLEAN -> r.nextBoolean()
        JsonToken.NULL -> { r.nextNull(); null }
        else -> error("Unexpected token")
    }

    fun render(v: Any?): String = StringWriter().also { write(JsonOut(it), v) }.toString()

    @Suppress("UNCHECKED_CAST")
    private fun write(o: JsonOut, v: Any?) {
        when (v) {
            is Map<*, *> -> { o.beginObject(); (v as Map<String, Any?>).forEach { (k, x) -> o.name(k); write(o, x) }; o.endObject() }
            is List<*> -> { o.beginArray(); v.forEach { write(o, it) }; o.endArray() }
            is String -> o.value(v)
            is Long -> o.value(v)
            is Int -> o.value(v)
            is Boolean -> o.value(v)
            else -> error("Can't write $v")
        }
    }

    /** The backup's JSON with [change] applied to its top-level object. */
    @Suppress("UNCHECKED_CAST")
    fun edit(json: String, change: (MutableMap<String, Any?>) -> Unit): String {
        val tree = parse(json) as MutableMap<String, Any?>
        change(tree)
        return render(tree)
    }

    /** Every row of the table [name], to change in place. */
    @Suppress("UNCHECKED_CAST")
    fun rows(root: MutableMap<String, Any?>, name: String): List<MutableMap<String, Any?>> = root[name] as List<MutableMap<String, Any?>>
}
