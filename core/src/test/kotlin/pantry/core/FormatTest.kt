package pantry.core

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    @Test fun prettyShowsFractionsPeopleRecognise() {
        assertEquals("0", r("0").pretty())
        assertEquals("2", r("2").pretty())
        assertEquals("\u00BC", r("1/4").pretty())
        assertEquals("1\u00BD", r("3/2").pretty())
        assertEquals("2\u00BE", r("11/4").pretty())
        assertEquals("1/5", r("1/5").pretty())
        assertEquals("1 2/5", r("7/5").pretty())
        assertEquals("0.33", r("33/100").pretty())
        assertEquals("-\u00BD", r("-1/2").pretty())
    }
}
