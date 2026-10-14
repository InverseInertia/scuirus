package pantry.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReceiptTest {
    private val sample = """
        HANNAFORD SUPERMARKET
        10/12/2026 5:42 PM
        ORG BNLS CHKN BRST 12.34 F
        2.50 lb @ 4.94/lb
        BANANAS 1.29
        GREEK YOGURT 6.98
        2 @ 3.49
        COUPON -1.00
        4 x CANNED BEANS 3.96
        084321 BUTTER SALTED 4.50 T
        SUBTOTAL 29.07
        TAX 0.00
        TOTAL 29.07
        VISA 29.07
    """.trimIndent().lines()

    @Test fun mergesSplitColumnsIntoRows() {
        val frags = listOf(
            OcrLine("MILK", 10, 100, 80, 120),
            OcrLine("3.99", 300, 102, 350, 122),
            OcrLine("EGGS", 10, 140, 70, 160),
            OcrLine("4.50", 300, 139, 350, 159),
            OcrLine("  ", 10, 200, 20, 210),
        )
        assertEquals(listOf("MILK 3.99", "EGGS 4.50"), OcrRows.merge(frags))
    }

    @Test fun parsesItemsQuantitiesWeightsAndDiscounts() {
        val d = ReceiptParser.parse(sample)
        assertEquals("HANNAFORD SUPERMARKET", d.store)
        assertEquals(LocalDate.parse("2026-10-12"), d.date)
        assertEquals(5, d.lines.size)

        val chicken = d.lines[0]
        assertEquals("ORG BNLS CHKN BRST", chicken.name)
        assertEquals(r("2.5"), chicken.qty)
        assertEquals("lb", chicken.unit)
        assertEquals(r("12.34"), chicken.price)

        assertEquals(r("1.29"), d.lines[1].price)

        val yogurt = d.lines[2]
        assertEquals(r("2"), yogurt.qty)
        assertEquals(r("5.98"), yogurt.price)

        val beans = d.lines[3]
        assertEquals("CANNED BEANS", beans.name)
        assertEquals(r("4"), beans.qty)

        assertEquals("BUTTER SALTED", d.lines[4].name)
        assertTrue(d.lines.none { it.name.contains("TOTAL") || it.name.contains("VISA") })
    }

    @Test fun missingDateFallsBackToHint() {
        val d = ReceiptParser.parse(listOf("CORNER STORE", "MILK 3.99"), dateHint = LocalDate.parse("2026-01-02"))
        assertEquals(LocalDate.parse("2026-01-02"), d.date)
        assertEquals(1, d.lines.size)
    }

    @Test fun matcherUsesAliasesFirstThenFuzzyNames() {
        val chicken = Product("p1", "Chicken breast", unit = "lb")
        val yogurt = Product("p2", "Greek yogurt")
        val bananas = Product("p3", "Bananas")
        val all = listOf(chicken, yogurt, bananas)

        val fuzzy = ReceiptMatcher.suggest("ORG BNLS CHKN BRST", all, emptyMap())
        assertEquals("p1", fuzzy.first().product.id)
        assertEquals("p3", ReceiptMatcher.suggest("BANANAS", all, emptyMap()).first().product.id)

        val aliased = ReceiptMatcher.suggest("GRK YGT", all, mapOf(ReceiptMatcher.normalize("GRK YGT") to "p2"))
        assertEquals("p2", aliased.single().product.id)
        assertTrue(aliased.single().fromAlias)

        assertTrue(ReceiptMatcher.suggest("PAPER TOWELS", all, emptyMap()).isEmpty())
    }

    @Test fun openFoodFactsReplyParses() {
        val body = """{"code":"3017620422003","status":1,"product":{"product_name":"Hazelnut spread","brands":"Nutella,Ferrero",
            "quantity":"400 g","categories_tags":["en:spreads","en:sweet-spreads"]}}"""
        val s = OpenFoodFacts.parse("3017620422003", body)!!
        assertEquals("Nutella Hazelnut spread", s.name)
        assertEquals("400 g", s.packSize)
        assertEquals("sweet spreads", s.category)
        assertNull(OpenFoodFacts.parse("1", """{"status":0,"status_verbose":"product not found"}"""))
        assertNull(OpenFoodFacts.parse("1", "<html>oops</html>"))
        assertTrue(OpenFoodFacts.url("0123-456").endsWith("fields=code,product_name,brands,quantity,categories_tags"))
        assertTrue(OpenFoodFacts.url("0123-456").contains("/0123456.json"))
    }
}
