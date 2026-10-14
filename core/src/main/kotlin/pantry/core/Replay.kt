package pantry.core

import java.time.Instant
import java.time.LocalDate

/**
 * Rebuilds inventory from the event log. The result depends only on the set of events,
 * not on which device wrote them or the order files were read, so two clones that have
 * pulled each other's commits always agree.
 */
object Replay {

    private class MutableLot(
        val id: String,
        val productId: String,
        val initialQty: Rational,
        var remaining: Rational,
        var consumed: Rational = Rational.ZERO,
        var discarded: Rational = Rational.ZERO,
        var price: Rational?,
        val bought: LocalDate?,
        var expires: LocalDate?,
        var location: String?,
        val source: String?,
        val addedAt: Instant,
        var depletedAt: Instant? = null,
        var voided: Boolean = false,
    ) {
        fun freeze() = Lot(
            id, productId, initialQty, remaining, consumed, discarded, price, bought, expires,
            location, source, addedAt, depletedAt, voided,
        )
    }

    /** Earliest expiry first. Lots with no expiry go last, then oldest purchase, then id for a stable tie. */
    private val fefo = compareBy<MutableLot>(
        { it.expires == null },
        { it.expires ?: LocalDate.MAX },
        { it.bought ?: LocalDate.MAX },
        { it.id },
    )

    fun run(events: Collection<Event>): Snapshot {
        val lots = LinkedHashMap<String, MutableLot>()
        val shortfalls = HashMap<String, Rational>()
        val warnings = ArrayList<Warning>()

        val ordered = events.distinctBy { it.id }.sortedWith(compareBy({ it.ts }, { it.id }))

        for (e in ordered) {
            when (e.type) {
                EventType.ADD -> add(e, lots, warnings)
                EventType.CONSUME, EventType.DISCARD -> remove(e, lots, shortfalls, warnings)
                EventType.ADJUST -> adjust(e, lots, warnings)
                EventType.VOID -> {
                    val lot = lots[e.lot]
                    if (lot == null) warnings += Warning(e.id, "void for unknown lot ${e.lot}")
                    else { lot.voided = true; lot.remaining = Rational.ZERO }
                }
            }
        }
        return Snapshot(lots.values.map { it.freeze() }, shortfalls, warnings)
    }

    private fun add(e: Event, lots: MutableMap<String, MutableLot>, warnings: MutableList<Warning>) {
        val id = e.lot
        val product = e.product
        val qty = e.qty
        if (id == null || product == null || qty == null || !qty.isPositive) {
            warnings += Warning(e.id, "add needs lot, product and a positive qty")
            return
        }
        if (lots.containsKey(id)) {
            warnings += Warning(e.id, "lot $id already exists, ignoring duplicate add")
            return
        }
        lots[id] = MutableLot(
            id = id, productId = product, initialQty = qty, remaining = qty,
            price = e.price, bought = e.bought, expires = e.expires, location = e.location,
            source = e.source, addedAt = e.ts,
        )
    }

    private fun remove(
        e: Event,
        lots: MutableMap<String, MutableLot>,
        shortfalls: MutableMap<String, Rational>,
        warnings: MutableList<Warning>,
    ) {
        val amount = e.qty
        if (amount == null || !amount.isPositive) {
            warnings += Warning(e.id, "${e.type.code} needs a positive qty")
            return
        }
        val waste = e.type == EventType.DISCARD

        if (e.lot != null) {
            val lot = lots[e.lot]
            if (lot == null) {
                warnings += Warning(e.id, "${e.type.code} for unknown lot ${e.lot}")
                e.product?.let { shortfalls.merge(it, amount) { a, b -> a + b } }
                return
            }
            val left = take(lot, amount, waste, e.ts)
            if (left.isPositive) shortfalls.merge(lot.productId, left) { a, b -> a + b }
            return
        }

        val productId = e.product
        if (productId == null) {
            warnings += Warning(e.id, "${e.type.code} needs a lot or a product")
            return
        }
        var left: Rational = amount
        val candidates = lots.values.filter { it.productId == productId && !it.voided && it.remaining.isPositive }.sortedWith(fefo)
        for (lot in candidates) {
            if (!left.isPositive) break
            left = take(lot, left, waste, e.ts)
        }
        if (left.isPositive) shortfalls.merge(productId, left) { a, b -> a + b }
    }

    /** Takes up to [amount] from [lot]. Returns the part that could not be taken. */
    private fun take(lot: MutableLot, amount: Rational, waste: Boolean, ts: Instant): Rational {
        val took = amount.min(lot.remaining)
        if (!took.isPositive) return amount
        lot.remaining = lot.remaining - took
        if (waste) lot.discarded = lot.discarded + took else lot.consumed = lot.consumed + took
        if (lot.remaining.isZero) lot.depletedAt = ts
        return amount - took
    }

    private fun adjust(e: Event, lots: MutableMap<String, MutableLot>, warnings: MutableList<Warning>) {
        val lot = lots[e.lot]
        if (lot == null) {
            warnings += Warning(e.id, "adjust for unknown lot ${e.lot}")
            return
        }
        e.qty?.let {
            if (it.isNegative) warnings += Warning(e.id, "adjust ignored a negative qty")
            else {
                lot.remaining = it
                lot.depletedAt = if (it.isZero) e.ts else null
            }
        }
        e.expires?.let { lot.expires = it }
        e.location?.let { lot.location = it }
        e.price?.let { lot.price = it }
    }
}
