package app.kassa.money.backup

/** Standard base64 (RFC 4648, with padding) for category pictures in a backup. No library: common Kotlin has none. */
internal object Base64Text {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val REVERSE = IntArray(128) { -1 }.also { table -> for (i in ALPHABET.indices) table[ALPHABET[i].code] = i }

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            val n = (b0 shl 16) or (b1 shl 8) or b2
            sb.append(ALPHABET[(n shr 18) and 63])
            sb.append(ALPHABET[(n shr 12) and 63])
            sb.append(if (i + 1 < bytes.size) ALPHABET[(n shr 6) and 63] else '=')
            sb.append(if (i + 2 < bytes.size) ALPHABET[n and 63] else '=')
            i += 3
        }
        return sb.toString()
    }

    /** Throws [IllegalArgumentException] for a character outside the alphabet or a length no encoder produces. Padding is optional. */
    fun decode(text: String): ByteArray {
        var end = text.length
        var padding = 0
        while (end > 0 && text[end - 1] == '=' && padding < 2) { end--; padding++ }
        if (end % 4 == 1) throw IllegalArgumentException("Not base64")
        val out = ByteArray(end * 6 / 8)
        var acc = 0
        var bits = 0
        var o = 0
        for (i in 0 until end) {
            val c = text[i].code
            val v = if (c < 128) REVERSE[c] else -1
            if (v < 0) throw IllegalArgumentException("Not base64")
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[o] = ((acc shr bits) and 0xFF).toByte()
                o++
                acc = acc and ((1 shl bits) - 1)
            }
        }
        return out
    }
}
