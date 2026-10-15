package pantry.core

data class ProductSuggestion(
    val barcode: String,
    val name: String,
    val brand: String?,
    /** Package size as printed, for example "946 ml". */
    val packSize: String?,
    val category: String?,
)

/**
 * Parser for the Open Food Facts product endpoint. Open Food Facts asks API users to send a
 * descriptive User-Agent that names the app and a contact.
 */
object OpenFoodFacts {

    fun url(barcode: String): String =
        "https://world.openfoodfacts.org/api/v2/product/${barcode.filter { it.isDigit() }}.json" +
            "?fields=code,product_name,product_name_en,generic_name,brands,quantity,categories_tags"

    /** Returns null when the barcode is unknown or the reply has no usable name. */
    fun parse(barcode: String, body: String): ProductSuggestion? {
        val root = try { Json.parseObject(body) } catch (e: CodecException) { return null }
        val status = (root["status"] as? Double)?.toInt()
        @Suppress("UNCHECKED_CAST")
        val p = root["product"] as? Map<String, Any?> ?: return null
        if (status != null && status != 1) return null

        val name = listOf("product_name", "product_name_en", "generic_name")
            .firstNotNullOfOrNull { k -> (p[k] as? String)?.trim()?.takeIf { it.isNotEmpty() } }.orEmpty()
        if (name.isEmpty()) return null
        val brand = (p["brands"] as? String)?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val qty = (p["quantity"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val category = (p["categories_tags"] as? List<*>)?.lastOrNull()?.toString()
            ?.substringAfter(':')?.replace('-', ' ')?.takeIf { it.isNotEmpty() }
        val display = if (brand != null && !name.contains(brand, ignoreCase = true)) "$brand $name" else name
        return ProductSuggestion(barcode, display, brand, qty, category)
    }
}
