package pantry.core

import java.time.Duration
import java.time.Instant

data class ProductStats(
    val productId: String,
    val lotCount: Int,
    val bought: Rational,
    val spent: Rational?,
    /** Total spent divided by quantity bought, over lots that have a price. */
    val averageUnitPrice: Rational?,
    /** Unit price of the most recently added priced lot. */
    val lastUnitPrice: Rational?,
    val consumed: Rational,
    val discarded: Rational,
    /** Discarded share of everything removed, 0 to 1. Null until something is removed. */
    val wasteRatio: Double?,
    /** Mean days from purchase to empty over fully used lots. Null when none are empty yet. */
    val averageDaysToUse: Double?,
    /** Quantity removed per day since the first purchase. Null until something is removed. */
    val usagePerDay: Double?,
    /** Remaining stock divided by usagePerDay. Null when usage is unknown. */
    val daysOfStock: Double?,
    val remaining: Rational,
)

object Stats {

    fun compute(snapshot: Snapshot, now: Instant): Map<String, ProductStats> =
        snapshot.lots.filter { !it.voided }.groupBy { it.productId }.mapValues { (pid, lots) ->
            val bought = lots.fold(Rational.ZERO) { a, l -> a + l.initialQty }
            val priced = lots.filter { it.price != null }
            val spent = priced.takeIf { it.isNotEmpty() }?.fold(Rational.ZERO) { a, l -> a + l.price!! }
            val pricedQty = priced.fold(Rational.ZERO) { a, l -> a + l.initialQty }
            val consumed = lots.fold(Rational.ZERO) { a, l -> a + l.consumed }
            val discarded = lots.fold(Rational.ZERO) { a, l -> a + l.discarded }
            val removed = consumed + discarded
            val remaining = lots.fold(Rational.ZERO) { a, l -> a + l.remaining }

            val emptied = lots.filter { it.depletedAt != null && it.remaining.isZero }
            val avgDays = emptied.takeIf { it.isNotEmpty() }
                ?.map { Duration.between(it.addedAt, it.depletedAt).toMinutes() / (60.0 * 24.0) }
                ?.average()

            val first = lots.minOf { it.addedAt }
            val days = (Duration.between(first, now).toMinutes() / (60.0 * 24.0)).coerceAtLeast(1.0)
            val perDay = if (removed.isPositive) removed.toDouble() / days else null

            ProductStats(
                productId = pid,
                lotCount = lots.size,
                bought = bought,
                spent = spent,
                averageUnitPrice = if (spent != null && pricedQty.isPositive) spent / pricedQty else null,
                lastUnitPrice = priced.maxByOrNull { it.addedAt }?.unitPrice,
                consumed = consumed,
                discarded = discarded,
                wasteRatio = if (removed.isPositive) (discarded / removed).toDouble() else null,
                averageDaysToUse = avgDays,
                usagePerDay = perDay,
                daysOfStock = if (perDay != null && perDay > 0) remaining.toDouble() / perDay else null,
                remaining = remaining,
            )
        }
}
