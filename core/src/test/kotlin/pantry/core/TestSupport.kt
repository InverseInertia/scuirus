package pantry.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock the test moves by hand. */
class TestClock(start: String = "2026-10-14T12:00:00Z") : Clock() {
    private var now: Instant = Instant.parse(start)
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
    fun advanceHours(h: Long) { now = now.plusSeconds(h * 3600) }
    fun advanceDays(d: Long) = advanceHours(d * 24)
}

fun tempDir(prefix: String = "pantry-test"): Path = Files.createTempDirectory(prefix)

fun r(s: String): Rational = Rational.parse(s)

fun ev(
    id: String, ts: String, type: EventType, lot: String? = null, product: String? = null,
    qty: String? = null, price: String? = null, bought: String? = null, expires: String? = null,
    device: String = "dev1",
) = Event(
    id = id, ts = Instant.parse(ts), device = device, type = type, lot = lot, product = product,
    qty = qty?.let { Rational.parse(it) }, price = price?.let { Rational.parse(it) },
    bought = bought?.let { java.time.LocalDate.parse(it) }, expires = expires?.let { java.time.LocalDate.parse(it) },
)

fun gitAvailable(): Boolean = try {
    ProcessBuilder("git", "--version").redirectErrorStream(true).start().waitFor() == 0
} catch (e: Exception) { false }
