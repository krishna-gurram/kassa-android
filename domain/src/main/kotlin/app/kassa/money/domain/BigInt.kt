package app.kassa.money.domain

/**
 * A small signed integer of any size, in plain Kotlin so the same rules run on Android and iOS. It exists for the few
 * places where a product of two amounts can pass [Long] (loan payments, share costs); it is not a general library.
 * The magnitude is little-endian 32-bit limbs with no leading zero limb, and zero has no limbs and sign 0.
 */
internal class BigInt private constructor(private val sign: Int, private val mag: IntArray) : Comparable<BigInt> {

    fun signum(): Int = sign

    operator fun unaryMinus(): BigInt = if (sign == 0) this else BigInt(-sign, mag)

    operator fun plus(other: BigInt): BigInt = when {
        sign == 0 -> other
        other.sign == 0 -> this
        sign == other.sign -> BigInt(sign, addMag(mag, other.mag))
        else -> {
            val c = compareMag(mag, other.mag)
            when {
                c == 0 -> ZERO
                c > 0 -> make(sign, subMag(mag, other.mag))
                else -> make(other.sign, subMag(other.mag, mag))
            }
        }
    }

    operator fun minus(other: BigInt): BigInt = plus(-other)

    operator fun times(other: BigInt): BigInt =
        if (sign == 0 || other.sign == 0) ZERO else make(sign * other.sign, mulMag(mag, other.mag))

    fun pow(exponent: Int): BigInt {
        require(exponent >= 0) { "Negative exponent" }
        var result = ONE
        var base = this
        var e = exponent
        while (e > 0) {
            if (e and 1 == 1) result *= base
            e = e shr 1
            if (e > 0) base *= base
        }
        return result
    }

    /** Quotient (truncated toward zero) and remainder (sign of this), like Long's `/` and `%`. Throws ArithmeticException on zero. */
    fun divRem(other: BigInt): Pair<BigInt, BigInt> {
        if (other.sign == 0) throw ArithmeticException("Division by zero")
        if (sign == 0 || compareMag(mag, other.mag) < 0) return Pair(ZERO, this)
        val (q, r) = divMag(mag, other.mag)
        return Pair(make(sign * other.sign, q), make(sign, r))
    }

    operator fun div(other: BigInt): BigInt = divRem(other).first

    operator fun rem(other: BigInt): BigInt = divRem(other).second

    /** This divided by [other], rounded to the nearest whole number with halves going away from zero (BigDecimal's HALF_UP). */
    fun divHalfUp(other: BigInt): BigInt {
        val (q, r) = divRem(other)
        if (r.sign == 0) return q
        // |2r| >= |other| means the remainder is at least half.
        val twice = compareMag(addMag(r.mag, r.mag), other.mag)
        return if (twice >= 0) q + (if (sign * other.sign > 0) ONE else MINUS_ONE) else q
    }

    override fun compareTo(other: BigInt): Int = when {
        sign != other.sign -> sign.compareTo(other.sign)
        sign == 0 -> 0
        else -> sign * compareMag(mag, other.mag)
    }

    fun fitsLong(): Boolean {
        if (mag.size < 2) return true
        if (mag.size > 2) return false
        val top = mag[1]
        if (top >= 0) return true
        // Magnitude in [2^63, 2^64): only -2^63 fits.
        return sign < 0 && top == Int.MIN_VALUE && mag[0] == 0
    }

    /** The value as a Long; throws ArithmeticException when it does not fit, like `longValueExact`. */
    fun toLongExact(): Long {
        if (!fitsLong()) throw ArithmeticException("BigInt out of Long range")
        return toLongWrapped()
    }

    /** The low 64 bits as a two's complement Long, like BigInteger's `toLong()`: silently wraps when it does not fit. */
    fun toLongWrapped(): Long {
        val m = (if (mag.size > 1) (mag[1].toLong() shl 32) else 0L) or (if (mag.isNotEmpty()) mag[0].toLong() and MASK else 0L)
        return if (sign < 0) -m else m
    }

    override fun equals(other: Any?): Boolean = other is BigInt && sign == other.sign && mag.contentEquals(other.mag)

    override fun hashCode(): Int = 31 * sign + mag.contentHashCode()

    override fun toString(): String {
        if (sign == 0) return "0"
        val chunks = ArrayList<Int>()
        var cur = mag.copyOf()
        var len = cur.size
        while (len > 0) {
            var rem = 0L
            for (i in len - 1 downTo 0) {
                val v = (rem shl 32) or (cur[i].toLong() and MASK)
                cur[i] = (v / BILLION).toInt()
                rem = v % BILLION
            }
            chunks.add(rem.toInt())
            while (len > 0 && cur[len - 1] == 0) len--
        }
        val sb = StringBuilder()
        if (sign < 0) sb.append('-')
        sb.append(chunks[chunks.size - 1])
        for (i in chunks.size - 2 downTo 0) {
            val s = chunks[i].toString()
            repeat(9 - s.length) { sb.append('0') }
            sb.append(s)
        }
        return sb.toString()
    }

    companion object {
        private const val MASK = 0xFFFFFFFFL
        private const val BILLION = 1_000_000_000L

        val ZERO = BigInt(0, IntArray(0))
        val ONE = of(1)
        private val MINUS_ONE = of(-1)

        fun of(value: Long): BigInt {
            if (value == 0L) return ZERO
            val negative = value < 0
            // For Long.MIN_VALUE the negation is itself, and read as unsigned it is the right magnitude 2^63.
            val m = if (negative) -value else value
            val lo = (m and MASK).toInt()
            val hi = (m ushr 32).toInt()
            return BigInt(if (negative) -1 else 1, if (hi == 0) intArrayOf(lo) else intArrayOf(lo, hi))
        }

        fun of(value: Int): BigInt = of(value.toLong())

        private fun make(sign: Int, mag: IntArray): BigInt {
            var n = mag.size
            while (n > 0 && mag[n - 1] == 0) n--
            return when {
                n == 0 -> ZERO
                n == mag.size -> BigInt(sign, mag)
                else -> BigInt(sign, mag.copyOf(n))
            }
        }

        private fun compareMag(a: IntArray, b: IntArray): Int {
            if (a.size != b.size) return if (a.size < b.size) -1 else 1
            for (i in a.size - 1 downTo 0) {
                if (a[i] != b[i]) return if ((a[i].toLong() and MASK) < (b[i].toLong() and MASK)) -1 else 1
            }
            return 0
        }

        private fun addMag(a: IntArray, b: IntArray): IntArray {
            val (x, y) = if (a.size >= b.size) Pair(a, b) else Pair(b, a)
            val out = IntArray(x.size + 1)
            var carry = 0L
            for (i in x.indices) {
                val s = (x[i].toLong() and MASK) + (if (i < y.size) y[i].toLong() and MASK else 0L) + carry
                out[i] = s.toInt()
                carry = s ushr 32
            }
            out[x.size] = carry.toInt()
            return trim(out)
        }

        /** a - b where |a| >= |b|. */
        private fun subMag(a: IntArray, b: IntArray): IntArray {
            val out = IntArray(a.size)
            var borrow = 0L
            for (i in a.indices) {
                var d = (a[i].toLong() and MASK) - (if (i < b.size) b[i].toLong() and MASK else 0L) - borrow
                if (d < 0) {
                    d += 1L shl 32
                    borrow = 1
                } else {
                    borrow = 0
                }
                out[i] = d.toInt()
            }
            return trim(out)
        }

        private fun mulMag(a: IntArray, b: IntArray): IntArray {
            val out = IntArray(a.size + b.size)
            for (i in a.indices) {
                val ai = a[i].toLong() and MASK
                if (ai == 0L) continue
                var carry = 0L
                for (j in b.indices) {
                    val t = ai * (b[j].toLong() and MASK) + (out[i + j].toLong() and MASK) + carry
                    out[i + j] = t.toInt()
                    carry = t ushr 32
                }
                out[i + b.size] = carry.toInt()
            }
            return trim(out)
        }

        private fun trim(a: IntArray): IntArray {
            var n = a.size
            while (n > 0 && a[n - 1] == 0) n--
            return if (n == a.size) a else a.copyOf(n)
        }

        /** Knuth's algorithm D (TAOCP 4.3.1) on 32-bit limbs. Requires |a| >= |b| > 0. Returns quotient and remainder magnitudes. */
        private fun divMag(a: IntArray, b: IntArray): Pair<IntArray, IntArray> {
            if (b.size == 1) {
                val d = b[0].toLong() and MASK
                val q = IntArray(a.size)
                var rem = 0L
                for (i in a.size - 1 downTo 0) {
                    val v = (rem shl 32) or (a[i].toLong() and MASK)
                    q[i] = udiv(v, d).toInt()
                    rem = urem(v, d)
                }
                return Pair(trim(q), if (rem == 0L) IntArray(0) else intArrayOf(rem.toInt()))
            }
            val s = b[b.size - 1].countLeadingZeroBits()
            val v = shiftLeft(b, s, b.size)
            val n = v.size
            val u = shiftLeft(a, s, a.size + 1)
            val m = a.size - n
            val q = IntArray(m + 1)
            val vTop = v[n - 1].toLong() and MASK
            val vNext = v[n - 2].toLong() and MASK
            for (j in m downTo 0) {
                val num = ((u[j + n].toLong() and MASK) shl 32) or (u[j + n - 1].toLong() and MASK)
                var qhat = udiv(num, vTop)
                var rhat = urem(num, vTop)
                while (qhat >= (1L shl 32) || ugreater(qhat * vNext, (rhat shl 32) or (u[j + n - 2].toLong() and MASK))) {
                    qhat--
                    rhat += vTop
                    if (rhat >= (1L shl 32)) break
                }
                // Multiply and subtract.
                var borrow = 0L
                var carry = 0L
                for (i in 0 until n) {
                    val p = qhat * (v[i].toLong() and MASK) + carry
                    carry = p ushr 32
                    val sub = (u[i + j].toLong() and MASK) - (p and MASK) - borrow
                    u[i + j] = sub.toInt()
                    borrow = if (sub < 0) 1 else 0
                }
                val sub = (u[j + n].toLong() and MASK) - carry - borrow
                u[j + n] = sub.toInt()
                if (sub < 0) {
                    // qhat was one too big: add the divisor back.
                    qhat--
                    var c = 0L
                    for (i in 0 until n) {
                        val t = (u[i + j].toLong() and MASK) + (v[i].toLong() and MASK) + c
                        u[i + j] = t.toInt()
                        c = t ushr 32
                    }
                    u[j + n] = (u[j + n] + c.toInt())
                }
                q[j] = qhat.toInt()
            }
            val rem = shiftRight(u, s, n)
            return Pair(trim(q), trim(rem))
        }

        private fun shiftLeft(a: IntArray, s: Int, outSize: Int): IntArray {
            val out = IntArray(if (outSize > a.size) outSize else a.size)
            if (s == 0) {
                a.copyInto(out)
                return out
            }
            var carry = 0
            for (i in a.indices) {
                out[i] = (a[i] shl s) or carry
                carry = a[i] ushr (32 - s)
            }
            if (out.size > a.size) out[a.size] = carry
            return out
        }

        private fun shiftRight(a: IntArray, s: Int, size: Int): IntArray {
            val out = IntArray(size)
            if (s == 0) {
                a.copyInto(out, 0, 0, size)
                return out
            }
            for (i in 0 until size) {
                val hi = if (i + 1 < a.size) a[i + 1] else 0
                out[i] = (a[i] ushr s) or (hi shl (32 - s))
            }
            return out
        }

        // Unsigned 64-bit helpers (kotlin's ULong would also do; these keep the hot loop on plain Longs).
        private fun udiv(a: Long, b: Long): Long = a.toULong().div(b.toULong()).toLong()
        private fun urem(a: Long, b: Long): Long = a.toULong().rem(b.toULong()).toLong()
        private fun ugreater(a: Long, b: Long): Boolean = a.toULong() > b.toULong()
    }
}

/** Exact products and quotients of amounts that would overflow a Long on the way. */
internal object Wide {

    /**
     * `a * b / c` rounded to the nearest whole number, halves away from zero (BigDecimal's HALF_UP), computed exactly.
     * Throws ArithmeticException when [c] is zero or the result does not fit a Long.
     */
    fun mulDivHalfUp(a: Long, b: Long, c: Long): Long =
        (BigInt.of(a) * BigInt.of(b)).divHalfUp(BigInt.of(c)).toLongExact()
}
