package pantry.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StoreFilesTest {
    @Test fun catalogKeepsLeadingZerosAndOddTextOneProductPerLine() {
        val dir = tempDir()
        val store = PantryStore(dir, "dev1")
        store.init()
        val products = listOf(
            Product("p_b", "No", barcode = "012345678905", unit = "carton", defaultUse = r("1/4"), shelfLifeDays = 7),
            Product("p_a", "Bob's \"best\" beans: #1", barcode = "000123"),
        )
        store.saveProducts(products)
        val lines = Files.readAllLines(dir.resolve("catalog.jsonl"))
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"p_a\"") && lines[1].contains("\"p_b\""))
        assertEquals(products.sortedBy { it.id }, store.loadProducts())
        assertEquals("012345678905", store.loadProducts().first { it.id == "p_b" }.barcode)
    }

    @Test fun handEditedCatalogToleratesBlankLinesAndRejectsNumbers() {
        val dir = tempDir()
        val store = PantryStore(dir, "dev1")
        store.init()
        Files.writeString(dir.resolve("catalog.jsonl"), "{\"id\":\"p1\",\"name\":\"Rice\"}\n\n{\"id\":\"p2\",\"name\":\"Oats\",\"barcode\":\"0123\"}\n")
        assertEquals(listOf("Rice", "Oats"), store.loadProducts().map { it.name })

        Files.writeString(dir.resolve("catalog.jsonl"), "{\"id\":\"p1\",\"name\":\"Rice\"}\n{\"id\":\"p2\",\"name\":\"Oats\",\"barcode\":0123}\n")
        assertFailsWith<CodecException> { store.loadProducts() }
        Files.writeString(dir.resolve("catalog.jsonl"), "{\"id\":\"p1\",\"name\":\"Rice\"}\n{\"id\":\"p2\",\"name\":\"Oats\",\"barcode\":123}\n")
        val e = assertFailsWith<CodecException> { store.loadProducts() }
        assertTrue(e.message!!.contains("catalog.jsonl") && e.message!!.contains("line 2"))
    }

    @Test fun aValidButUnreadableCatalogNeverGetsSilentlyShortened() {
        val dir = tempDir()
        val store = PantryStore(dir, "dev1")
        store.init()
        Files.writeString(dir.resolve("catalog.jsonl"), "{\"id\":\"p1\",\"name\":\"Rice\"}\nnot json\n")
        assertFailsWith<CodecException> { store.loadProducts() }
        assertTrue(Files.readString(dir.resolve("catalog.jsonl")).contains("not json"))
    }

    @Test fun aliasesRoundTripSorted() {
        val dir = tempDir()
        val store = PantryStore(dir, "dev1")
        store.init()
        store.saveAliases(mapOf("zucchini" to "p2", "apple" to "p1"))
        assertEquals(listOf("apple", "zucchini"), Files.readAllLines(dir.resolve("aliases.jsonl")).map { Json.parseObject(it)["text"] })
        assertEquals(mapOf("apple" to "p1", "zucchini" to "p2"), store.loadAliases())
    }
}
