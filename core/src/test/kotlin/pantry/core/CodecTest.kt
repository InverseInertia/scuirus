package pantry.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodecTest {
    @Test fun jsonRoundTripsEscapes() {
        val m = linkedMapOf<String, Any?>("a" to "line1\nline2 \"q\" \\ tab\t", "b" to "caf\u00e9 \u2603")
        assertEquals(m, Json.parseObject(Json.write(m)))
        assertEquals("a\u0001b", (Json.parse("\"a\\u0001b\"")))
    }

    @Test fun jsonParsesNestedValues() {
        val v = Json.parseObject("""{"status":1,"product":{"name":"x","tags":["a","b"],"ok":true,"none":null}}""")
        assertEquals(1.0, v["status"])
        @Suppress("UNCHECKED_CAST")
        val p = v["product"] as Map<String, Any?>
        assertEquals(listOf("a", "b"), p["tags"])
        assertEquals(true, p["ok"])
        assertNull(p["none"])
        assertFailsWith<CodecException> { Json.parse("{\"a\":}") }
    }

    @Test fun eventLineRoundTrips() {
        val e = ev("e1", "2026-10-14T12:00:00.123Z", EventType.ADD, lot = "l1", product = "p1", qty = "1/3",
            price = "4.99", bought = "2026-10-14", expires = "2026-10-20")
        val back = Event.fromLine(e.toLine())
        assertEquals(e.copy(price = back.price), back)
        assertEquals(r("4.99"), back.price)
        assertTrue(!e.toLine().contains('\n'))
    }

    @Test fun badEventLineFailsCleanly() {
        assertFailsWith<CodecException> { Event.fromLine("{\"id\":\"x\"}") }
        assertFailsWith<CodecException> { Event.fromLine("not json") }
    }
}
