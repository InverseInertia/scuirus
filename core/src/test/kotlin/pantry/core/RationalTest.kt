package pantry.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RationalTest {
    @Test fun thirdsAddBackToExactlyOne() {
        val third = r("1/3")
        assertEquals(Rational.ONE, third + third + third)
        assertTrue((Rational.ONE - third - third - third).isZero)
    }

    @Test fun parsesCommonForms() {
        assertEquals(Rational.of(5, 2), r("2.5"))
        assertEquals(Rational.of(1, 2), r(".5"))
        assertEquals(Rational.of(3, 2), r("1 1/2"))
        assertEquals(Rational.of(-1, 4), r("-1/4"))
        assertEquals(Rational.of(3, 2), r("1\u00BD"))
        assertEquals(Rational.of(2), r("2"))
        assertEquals(Rational.of(1, 3), r("2/6"))
    }

    @Test fun rejectsGarbage() {
        assertNull(Rational.parseOrNull(""))
        assertNull(Rational.parseOrNull("abc"))
        assertNull(Rational.parseOrNull("1/0"))
        assertNull(Rational.parseOrNull("1 2"))
        assertNull(Rational.parseOrNull("1/2/3"))
    }

    @Test fun storageFormIsCanonical() {
        assertEquals("1/3", r("2/6").toString())
        assertEquals("2", r("4/2").toString())
        assertEquals("-1/2", r("1/-2").toString())
        assertEquals("0", (r("1/2") - r("1/2")).toString())
    }

    @Test fun decimalStringTrimsZeros() {
        assertEquals("0.333", r("1/3").toDecimalString(3))
        assertEquals("2.5", r("5/2").toDecimalString(3))
        assertEquals("2", r("2").toDecimalString(3))
        assertEquals("0.99", r("99/100").toDecimalString(2))
    }

    @Test fun comparesWithoutRounding() {
        assertTrue(r("1/3") < r("0.34"))
        assertTrue(r("1/3") > r("0.33"))
        assertEquals(r("1/3"), r("1/3").min(r("1/2")))
    }
}
