package pantry.core

import java.math.BigInteger

private val glyphs = mapOf(
    Rational.of(1, 2) to "\u00BD", Rational.of(1, 3) to "\u2153", Rational.of(2, 3) to "\u2154",
    Rational.of(1, 4) to "\u00BC", Rational.of(3, 4) to "\u00BE", Rational.of(1, 8) to "\u215B",
)

/**
 * Display form for quantities: "2", "1\u00BD", "\u00BC", "7/8", or a rounded decimal when the
 * denominator is larger than 8. Never used for storage.
 */
fun Rational.pretty(): String {
    if (isZero) return "0"
    val sign = if (isNegative) "-" else ""
    val n = num.abs()
    if (den == BigInteger.ONE) return sign + n.toString()
    if (den > BigInteger.valueOf(8)) return toDecimalString(2)
    val whole = n / den
    val rem = Rational.of(n % den, den)
    val frac = glyphs[rem] ?: "${rem.num}/${rem.den}"
    return when {
        whole.signum() == 0 -> sign + frac
        glyphs.containsKey(rem) -> "$sign$whole$frac"
        else -> "$sign$whole $frac"
    }
}
