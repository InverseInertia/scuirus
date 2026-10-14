package pantry.core

import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A catalog entry. Quantities of its lots are counted in [unit], for example "carton" or "lb". */
data class Product(
    val id: String,
    val name: String,
    val barcode: String? = null,
    val category: String? = null,
    /** What one unit is called. A barcode scan removes [defaultUse] of these. */
    val unit: String = "each",
    /** Display text only, such as "946 ml". Never used in arithmetic. */
    val packSize: String? = null,
    val shelfLifeDays: Int? = null,
    val defaultUse: Rational = Rational.ONE,
    /** Where new lots go unless told otherwise: fridge, freezer, or pantry. */
    val location: String = "pantry",
) {
    fun toMap(): Map<String, String> = linkedMapOf<String, String?>(
        "id" to id,
        "name" to name,
        "barcode" to barcode,
        "category" to category,
        "unit" to unit,
        "pack_size" to packSize,
        "shelf_life_days" to shelfLifeDays?.toString(),
        "default_use" to defaultUse.toString(),
        "location" to location,
    ).filterValues { it != null }.mapValues { it.value!! }

    companion object {
        fun fromMap(m: Map<String, String>): Product {
            val id = m["id"] ?: throw CodecException("product without id")
            return Product(
                id = id,
                name = m["name"] ?: throw CodecException("product $id without name"),
                barcode = m["barcode"],
                category = m["category"],
                unit = m["unit"] ?: "each",
                packSize = m["pack_size"],
                shelfLifeDays = m["shelf_life_days"]?.toIntOrNull(),
                defaultUse = m["default_use"]?.let { Rational.parseOrNull(it) }?.takeIf { it.isPositive } ?: Rational.ONE,
                location = m["location"] ?: "pantry",
            )
        }
    }
}

enum class EventType(val code: String) {
    ADD("add"),
    CONSUME("consume"),
    DISCARD("discard"),
    ADJUST("adjust"),
    VOID("void");

    companion object {
        fun fromCode(c: String): EventType? = entries.firstOrNull { it.code == c }
    }
}

/**
 * One line in the append-only log.
 *
 * add      creates lot [lot] of [product] with [qty], optional [price] (total paid), [bought], [expires], [location].
 * consume  removes [qty]. With [lot] it targets that lot. With only [product] it takes from the
 *          earliest-expiring lots first and spans lots when needed.
 * discard  same as consume, counted as waste.
 * adjust   sets the remaining quantity of [lot] to [qty] and updates [expires], [location], [price] if present.
 * void     cancels a lot that was added by mistake, so it no longer counts in spending stats.
 */
data class Event(
    val id: String,
    val ts: Instant,
    val device: String,
    val type: EventType,
    val lot: String? = null,
    val product: String? = null,
    val qty: Rational? = null,
    val price: Rational? = null,
    val bought: LocalDate? = null,
    val expires: LocalDate? = null,
    val location: String? = null,
    val source: String? = null,
    val note: String? = null,
) {
    fun toLine(): String {
        val m = LinkedHashMap<String, String>()
        m["id"] = id
        m["ts"] = TS_FORMAT.format(ts)
        m["dev"] = device
        m["type"] = type.code
        lot?.let { m["lot"] = it }
        product?.let { m["product"] = it }
        qty?.let { m["qty"] = it.toString() }
        price?.let { m["price"] = it.toDecimalString(4) }
        bought?.let { m["bought"] = it.toString() }
        expires?.let { m["expires"] = it.toString() }
        location?.let { m["loc"] = it }
        source?.let { m["src"] = it }
        note?.let { m["note"] = it }
        return Json.write(m)
    }

    companion object {
        /** Fixed-width UTC timestamps, so sorting the text sorts the time. */
        val TS_FORMAT: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC)

        fun fromLine(line: String): Event {
            val m = Json.parseObject(line)
            fun s(k: String) = m[k] as? String
            val id = s("id") ?: throw CodecException("event without id")
            val ts = try {
                Instant.from(TS_FORMAT.parse(s("ts") ?: throw CodecException("event $id without ts")))
            } catch (e: java.time.DateTimeException) {
                throw CodecException("event $id has a bad ts")
            }
            val type = EventType.fromCode(s("type") ?: "") ?: throw CodecException("event $id has unknown type")
            fun q(k: String) = s(k)?.let { Rational.parseOrNull(it) ?: throw CodecException("event $id has bad $k") }
            fun d(k: String) = s(k)?.let {
                try { LocalDate.parse(it) } catch (e: java.time.DateTimeException) { throw CodecException("event $id has bad $k") }
            }
            return Event(
                id = id, ts = ts, device = s("dev") ?: "unknown", type = type,
                lot = s("lot"), product = s("product"),
                qty = q("qty"), price = q("price"),
                bought = d("bought"), expires = d("expires"),
                location = s("loc"), source = s("src"), note = s("note"),
            )
        }
    }
}

/** Current state of one purchased lot, derived by replaying events. */
data class Lot(
    val id: String,
    val productId: String,
    val initialQty: Rational,
    val remaining: Rational,
    val consumed: Rational,
    val discarded: Rational,
    val price: Rational?,
    val bought: LocalDate?,
    val expires: LocalDate?,
    val location: String?,
    val source: String?,
    val addedAt: Instant,
    val depletedAt: Instant?,
    val voided: Boolean,
) {
    val isActive: Boolean get() = !voided && remaining.isPositive
    val unitPrice: Rational? get() = price?.takeIf { initialQty.isPositive }?.let { it / initialQty }
}

data class Warning(val eventId: String, val message: String)

class Snapshot(
    val lots: List<Lot>,
    /** Quantity that was removed but had no stock to come from, per product id. */
    val shortfalls: Map<String, Rational>,
    val warnings: List<Warning>,
) {
    val activeLots: List<Lot> get() = lots.filter { it.isActive }

    fun lotsFor(productId: String): List<Lot> = lots.filter { it.productId == productId && !it.voided }

    fun remainingFor(productId: String): Rational =
        activeLots.filter { it.productId == productId }.fold(Rational.ZERO) { a, l -> a + l.remaining }

    fun expiringBy(date: LocalDate): List<Lot> =
        activeLots.filter { it.expires != null && !it.expires.isAfter(date) }.sortedBy { it.expires }
}

/** Short random ids. They only need to avoid collisions between a few devices. */
object Ids {
    private val rnd = SecureRandom()
    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"

    fun random(prefix: String, length: Int = 8): String {
        val sb = StringBuilder(prefix).append('_')
        repeat(length) { sb.append(ALPHABET[rnd.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }
}
