package pantry.core

import java.time.LocalDate
import java.time.format.DateTimeParseException

/** One fragment of recognized text with its position. ML Kit returns these as lines with bounding boxes. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerY: Double get() = (top + bottom) / 2.0
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

object OcrRows {
    /**
     * Receipts print item names on the left and prices on the right, and OCR often returns them as
     * separate lines. This groups fragments whose vertical centers fall within half a line height
     * and joins each group left to right.
     */
    fun merge(lines: List<OcrLine>): List<String> {
        val sorted = lines.filter { it.text.isNotBlank() }.sortedBy { it.centerY }
        val rows = ArrayList<MutableList<OcrLine>>()
        for (l in sorted) {
            val row = rows.lastOrNull()
            val rowCenter = row?.map { it.centerY }?.average()
            val rowHeight = row?.map { it.height }?.average()
            if (row != null && rowCenter != null && rowHeight != null &&
                kotlin.math.abs(l.centerY - rowCenter) <= maxOf(rowHeight, l.height.toDouble()) * 0.5
            ) row.add(l) else rows.add(mutableListOf(l))
        }
        return rows.map { r -> r.sortedBy { it.left }.joinToString(" ") { it.text.trim() } }
    }
}

data class DraftLine(
    val raw: String,
    val name: String,
    val qty: Rational,
    /** Line total after discounts, or null if no price was read. */
    val price: Rational?,
    /** Unit text when the receipt gave a weight, such as "lb" or "kg". */
    val unit: String? = null,
)

data class ReceiptDraft(val store: String?, val date: LocalDate?, val lines: List<DraftLine>, val rawText: String)

object ReceiptParser {

    private val priceAtEnd = Regex("""(-?\d{1,4}[.,]\d{2})\s*-?\s*[A-Za-z]{0,2}\s*$""")
    private val datePatterns = listOf(
        Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""") to "ymd",
        Regex("""\b(\d{1,2})/(\d{1,2})/(\d{4})\b""") to "mdy4",
        Regex("""\b(\d{1,2})/(\d{1,2})/(\d{2})\b""") to "mdy2",
    )
    private val skipWords = listOf(
        "subtotal", "sub total", "total", "tax", "change", "balance", "visa", "mastercard", "debit", "credit",
        "cash", "card", "amex", "savings", "you saved", "points", "thank you", "items sold", "item count",
        "tender", "auth", "approval", "ref #", "terminal", "survey", "member", "loyalty", "reward",
    )
    private val weightLine = Regex("""(?i)^\s*(\d+(?:\.\d+)?)\s*(lb|lbs|kg|oz|g)\s*@\s*\$?\s*\d+(?:\.\d+)?\s*(?:/|per)?\s*(lb|lbs|kg|oz|g)?.*$""")
    private val multiLine = Regex("""(?i)^\s*(\d+)\s*(?:@|x|\*)\s*\$?\s*(\d+[.,]\d{2}).*$""")
    private val qtyPrefix = Regex("""(?i)^\s*(\d{1,2})\s*(?:x|@)\s+(.*)$""")
    private val leadingCode = Regex("""^\s*\d{4,14}\s+""")

    fun parse(rows: List<String>, dateHint: LocalDate? = null): ReceiptDraft {
        val raw = rows.joinToString("\n")
        val store = rows.firstOrNull { r -> r.any { it.isLetter() } && !looksLikeNoise(r) }?.trim()
        var date: LocalDate? = null
        for (r in rows) {
            date = findDate(r)
            if (date != null) break
        }
        date = date ?: dateHint

        val out = ArrayList<DraftLine>()
        for (row in rows) {
            val line = row.trim()
            if (line.isEmpty()) continue
            val lower = line.lowercase()

            // Weight detail line modifies the previous item: "1.42 lb @ 2.99/lb"
            val w = weightLine.matchEntire(line)
            if (w != null && out.isNotEmpty()) {
                val prev = out.removeAt(out.lastIndex)
                val q = Rational.parseOrNull(w.groupValues[1]) ?: Rational.ONE
                out.add(prev.copy(qty = q, unit = w.groupValues[2].lowercase().removeSuffix("s").let { if (it == "lb") "lb" else it }))
                continue
            }
            // "3 @ 1.50" detail line sets quantity on the previous item
            val m = multiLine.matchEntire(line)
            if (m != null && out.isNotEmpty()) {
                val prev = out.removeAt(out.lastIndex)
                out.add(prev.copy(qty = Rational.of(m.groupValues[1].toLong())))
                continue
            }
            if (skipWords.any { lower.contains(it) }) continue

            val pm = priceAtEnd.find(line) ?: continue
            val price = Rational.parseOrNull(pm.groupValues[1].replace(',', '.')) ?: continue
            var name = line.substring(0, pm.range.first).trim()

            // A negative amount is a discount on the line above.
            if (price.isNegative) {
                if (out.isNotEmpty()) {
                    val prev = out.removeAt(out.lastIndex)
                    out.add(prev.copy(price = (prev.price ?: Rational.ZERO) + price))
                }
                continue
            }
            if (name.isEmpty() || name.none { it.isLetter() }) continue

            var qty = Rational.ONE
            qtyPrefix.matchEntire(name)?.let { q ->
                qty = Rational.of(q.groupValues[1].toLong())
                name = q.groupValues[2]
            }
            name = name.replace(leadingCode, "").trim()
            if (name.isEmpty()) continue
            out.add(DraftLine(raw = line, name = name, qty = qty, price = price))
        }
        return ReceiptDraft(store, date, out, raw)
    }

    private fun looksLikeNoise(r: String) = r.trim().length < 3 || skipWords.any { r.lowercase().contains(it) }

    private fun findDate(row: String): LocalDate? {
        for ((re, kind) in datePatterns) {
            val m = re.find(row) ?: continue
            try {
                val g = m.groupValues
                return when (kind) {
                    "ymd" -> LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt())
                    "mdy4" -> LocalDate.of(g[3].toInt(), g[1].toInt(), g[2].toInt())
                    else -> LocalDate.of(2000 + g[3].toInt(), g[1].toInt(), g[2].toInt())
                }
            } catch (e: DateTimeParseException) {
                continue
            } catch (e: java.time.DateTimeException) {
                continue
            }
        }
        return null
    }
}

data class MatchSuggestion(val product: Product, val score: Double, val fromAlias: Boolean)

/** Suggests catalog products for receipt text. The user always confirms. */
object ReceiptMatcher {

    private val abbreviations = mapOf(
        "org" to "organic", "orgnc" to "organic", "bnls" to "boneless", "sknls" to "skinless", "chkn" to "chicken",
        "brst" to "breast", "bf" to "beef", "grnd" to "ground", "whl" to "whole", "wht" to "white", "wh" to "white",
        "blk" to "black", "grn" to "green", "ylw" to "yellow", "rd" to "red", "chs" to "cheese", "chse" to "cheese",
        "yog" to "yogurt", "yogrt" to "yogurt", "bnna" to "banana", "bananas" to "banana", "tom" to "tomato", "tomatoes" to "tomato",
        "pot" to "potato", "potatoes" to "potato", "bread" to "bread", "brd" to "bread", "mlk" to "milk", "btr" to "butter",
        "ch" to "cheese", "veg" to "vegetable", "fz" to "frozen", "frz" to "frozen", "gal" to "gallon", "pk" to "pack",
    )

    fun normalize(text: String): String =
        tokens(text).joinToString(" ")

    private fun tokens(text: String): List<String> =
        text.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(' ')
            .filter { it.isNotBlank() && !it.all { c -> c.isDigit() } }
            .map { abbreviations[it] ?: it }

    fun suggest(
        rawText: String,
        products: List<Product>,
        aliases: Map<String, String>,
        limit: Int = 3,
    ): List<MatchSuggestion> {
        val norm = normalize(rawText)
        aliases[norm]?.let { id ->
            products.firstOrNull { it.id == id }?.let { return listOf(MatchSuggestion(it, 1.0, true)) }
        }
        val query = tokens(rawText).toSet()
        if (query.isEmpty()) return emptyList()
        return products.map { p ->
            val pt = tokens(p.name).toSet()
            val inter = query.intersect(pt).size.toDouble()
            val union = query.union(pt).size.toDouble()
            // Weigh coverage of the product name more than plain overlap, since receipts drop words.
            val coverage = if (pt.isEmpty()) 0.0 else inter / pt.size
            MatchSuggestion(p, 0.5 * (inter / union) + 0.5 * coverage, false)
        }.filter { it.score >= 0.34 }.sortedByDescending { it.score }.take(limit)
    }
}
