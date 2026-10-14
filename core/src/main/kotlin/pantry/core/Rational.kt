package pantry.core

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * Exact rational number. Quantities are stored as strings like "1/3" or "2.5" and
 * parsed into this type, so removing a third of an item three times lands on zero.
 */
class Rational private constructor(val num: BigInteger, val den: BigInteger) : Comparable<Rational> {

    val signum: Int get() = num.signum()
    val isZero: Boolean get() = num.signum() == 0
    val isNegative: Boolean get() = num.signum() < 0
    val isPositive: Boolean get() = num.signum() > 0

    operator fun plus(o: Rational) = of(num * o.den + o.num * den, den * o.den)
    operator fun minus(o: Rational) = of(num * o.den - o.num * den, den * o.den)
    operator fun times(o: Rational) = of(num * o.num, den * o.den)
    operator fun div(o: Rational): Rational {
        require(!o.isZero) { "division by zero" }
        return of(num * o.den, den * o.num)
    }
    operator fun unaryMinus() = Rational(num.negate(), den)

    override fun compareTo(other: Rational): Int = (num * other.den).compareTo(other.num * den)

    fun min(o: Rational) = if (this <= o) this else o
    fun max(o: Rational) = if (this >= o) this else o

    fun toDouble(): Double = BigDecimal(num).divide(BigDecimal(den), 12, RoundingMode.HALF_EVEN).toDouble()

    /** Decimal text rounded to [scale] places with trailing zeros trimmed. */
    fun toDecimalString(scale: Int = 3): String {
        val d = BigDecimal(num).divide(BigDecimal(den), scale, RoundingMode.HALF_EVEN)
        val s = d.stripTrailingZeros().toPlainString()
        return if (s == "-0") "0" else s
    }

    /** Canonical storage form: "3", "-3", or "1/3". */
    override fun toString(): String = if (den == BigInteger.ONE) num.toString() else "$num/$den"

    override fun equals(other: Any?): Boolean = other is Rational && num == other.num && den == other.den
    override fun hashCode(): Int = 31 * num.hashCode() + den.hashCode()

    companion object {
        val ZERO = Rational(BigInteger.ZERO, BigInteger.ONE)
        val ONE = Rational(BigInteger.ONE, BigInteger.ONE)

        fun of(n: Long, d: Long = 1): Rational = of(BigInteger.valueOf(n), BigInteger.valueOf(d))

        fun of(n: BigInteger, d: BigInteger): Rational {
            require(d.signum() != 0) { "zero denominator" }
            if (n.signum() == 0) return ZERO
            val g = n.gcd(d)
            var nn = n / g
            var dd = d / g
            if (dd.signum() < 0) {
                nn = nn.negate()
                dd = dd.negate()
            }
            return Rational(nn, dd)
        }

        private val unicodeFractions = mapOf(
            '\u00BD' to (1L to 2L), '\u2153' to (1L to 3L), '\u2154' to (2L to 3L),
            '\u00BC' to (1L to 4L), '\u00BE' to (3L to 4L), '\u215B' to (1L to 8L),
        )

        /**
         * Accepts "2", "-2", "0.25", ".5", "1/3", "1 1/2", and unicode fractions such as "1 1/2" or "1\u00BD".
         * Returns null when the text is not a number.
         */
        fun parseOrNull(text: String): Rational? {
            var s = text.trim()
            if (s.isEmpty()) return null
            // Replace a unicode fraction with a space-separated ascii one.
            for ((ch, frac) in unicodeFractions) {
                if (s.indexOf(ch) >= 0) s = s.replace(ch.toString(), " ${frac.first}/${frac.second}")
            }
            s = s.trim()
            val negative = s.startsWith("-")
            if (negative) s = s.substring(1).trim()
            val parts = s.split(Regex("\\s+"))
            val total = when (parts.size) {
                1 -> parseSimple(parts[0])
                2 -> {
                    val whole = parts[0].toBigIntegerOrNull()
                    val frac = parseSimple(parts[1])
                    if (whole == null || frac == null || !parts[1].contains('/')) null
                    else of(whole, BigInteger.ONE) + frac
                }
                else -> null
            } ?: return null
            return if (negative) -total else total
        }

        fun parse(text: String): Rational =
            parseOrNull(text) ?: throw IllegalArgumentException("not a quantity: \"$text\"")

        private fun parseSimple(s: String): Rational? {
            if (s.contains('/')) {
                val p = s.split('/')
                if (p.size != 2) return null
                val n = p[0].toBigIntegerOrNull() ?: return null
                val d = p[1].toBigIntegerOrNull() ?: return null
                if (d.signum() == 0) return null
                return of(n, d)
            }
            if (!Regex("^\\d*\\.?\\d+$|^\\d+\\.$").matches(s)) return null
            return try {
                val bd = BigDecimal(s)
                val scaled = bd.unscaledValue()
                val scale = bd.scale()
                if (scale >= 0) of(scaled, BigInteger.TEN.pow(scale)) else of(scaled * BigInteger.TEN.pow(-scale), BigInteger.ONE)
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}
