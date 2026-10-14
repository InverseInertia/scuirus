package pantry.core

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

data class InventoryItem(
    val product: Product,
    val remaining: Rational,
    /** Active lots in the order they will be used: earliest expiry first. */
    val lots: List<Lot>,
) {
    val nearestExpiry: LocalDate? get() = lots.mapNotNull { it.expires }.minOrNull()
}

sealed interface ScanRemoveResult {
    data class Removed(val product: Product, val removed: Rational, val remainingAfter: Rational) : ScanRemoveResult
    data class UnknownBarcode(val barcode: String) : ScanRemoveResult
    data class OutOfStock(val product: Product) : ScanRemoveResult
}

data class RemoveOutcome(val removed: Rational, val shortfall: Rational)

data class ConfirmedLine(
    val productId: String,
    val qty: Rational,
    val price: Rational?,
    val expires: LocalDate? = null,
    /** Text as printed on the receipt. When present, the app remembers it as an alias for the product. */
    val rawText: String? = null,
)

/**
 * The only writer of events. Each operation appends to the log, rebuilds the snapshot,
 * and records a line for the next commit message.
 */
class PantryService(
    private val store: PantryStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    private var events = ArrayList<Event>()
    private var lastTs: Instant = Instant.EPOCH
    private var aliasMap = LinkedHashMap<String, String>()

    var products: List<Product> = emptyList()
        private set
    var snapshot: Snapshot = Snapshot(emptyList(), emptyMap(), emptyList())
        private set
    /** Lines in event files that could not be read. Shown to the user rather than hidden. */
    var loadProblems: List<String> = emptyList()
        private set

    val aliases: Map<String, String> get() = aliasMap

    /** Reads everything from disk. Call at startup and after a pull. */
    fun load() {
        store.init()
        products = store.loadProducts()
        aliasMap = LinkedHashMap(store.loadAliases())
        val loaded = store.loadEvents()
        events = ArrayList(loaded.events)
        loadProblems = loaded.problems
        lastTs = events.filter { it.device == store.deviceId }.maxOfOrNull { it.ts } ?: Instant.EPOCH
        rebuild()
    }

    fun product(id: String): Product? = products.firstOrNull { it.id == id }
    fun productByBarcode(barcode: String): Product? = products.firstOrNull { it.barcode == barcode }

    fun inventory(): List<InventoryItem> {
        val byProduct = snapshot.activeLots.groupBy { it.productId }
        return byProduct.mapNotNull { (pid, lots) ->
            val p = product(pid) ?: return@mapNotNull null
            InventoryItem(p, lots.fold(Rational.ZERO) { a, l -> a + l.remaining }, lots.sortedWith(lotOrder))
        }.sortedWith(compareBy({ it.nearestExpiry == null }, { it.nearestExpiry ?: LocalDate.MAX }, { it.product.name.lowercase() }))
    }

    fun stats(now: Instant = clock.instant()): Map<String, ProductStats> = Stats.compute(snapshot, now)

    // Catalog

    fun saveProduct(p: Product): Product {
        val isNew = products.none { it.id == p.id }
        products = (products.filter { it.id != p.id } + p).sortedBy { it.id }
        store.saveProducts(products)
        store.addPending(listOf("product", p.name, if (isNew) "new" else "edited").joinToString("\t"))
        return p
    }

    fun newProduct(name: String, barcode: String? = null, unit: String = "each", location: String = "pantry",
                   category: String? = null, packSize: String? = null, shelfLifeDays: Int? = null): Product =
        saveProduct(Product(Ids.random("p", 6), name.trim(), barcode, category, unit, packSize, shelfLifeDays, Rational.ONE, location))

    // Adding stock

    fun addLot(
        productId: String,
        qty: Rational = Rational.ONE,
        price: Rational? = null,
        bought: LocalDate? = null,
        expires: LocalDate? = null,
        location: String? = null,
        source: String? = "manual",
        note: String? = null,
    ): String {
        val p = product(productId) ?: throw IllegalArgumentException("unknown product $productId")
        require(qty.isPositive) { "quantity must be positive" }
        val day = bought ?: today()
        val exp = expires ?: p.shelfLifeDays?.let { day.plusDays(it.toLong()) }
        val lotId = Ids.random("l", 8)
        append(
            Event(newId(), nextTs(), store.deviceId, EventType.ADD, lot = lotId, product = productId, qty = qty,
                price = price, bought = day, expires = exp, location = location ?: p.location, source = source, note = note),
            pending("add", p.name, "${qty.toDecimalString()} ${p.unit}"),
        )
        return lotId
    }

    /** Barcode scan on the way in. Returns null if the barcode is not in the catalog yet. */
    fun scanAdd(barcode: String, qty: Rational = Rational.ONE): Pair<Product, String>? {
        val p = productByBarcode(barcode) ?: return null
        return p to addLot(p.id, qty, source = "scan")
    }

    // Removing stock

    /** Barcode scan on the way out. Removes [qty], or the product's default use, from the earliest-expiring lots. */
    fun scanRemove(barcode: String, qty: Rational? = null): ScanRemoveResult {
        val p = productByBarcode(barcode) ?: return ScanRemoveResult.UnknownBarcode(barcode)
        val have = snapshot.remainingFor(p.id)
        if (!have.isPositive) return ScanRemoveResult.OutOfStock(p)
        val want = (qty ?: p.defaultUse)
        require(want.isPositive) { "quantity must be positive" }
        val take = want.min(have)
        removeFromProduct(p.id, take, discard = false)
        return ScanRemoveResult.Removed(p, take, have - take)
    }

    /** Removes [qty] of a product, spanning lots if needed. Capped at what is in stock. */
    fun removeFromProduct(productId: String, qty: Rational, discard: Boolean = false): RemoveOutcome {
        require(qty.isPositive) { "quantity must be positive" }
        val p = product(productId) ?: throw IllegalArgumentException("unknown product $productId")
        val have = snapshot.remainingFor(productId)
        val take = qty.min(have)
        if (!take.isPositive) return RemoveOutcome(Rational.ZERO, qty)
        append(
            Event(newId(), nextTs(), store.deviceId, if (discard) EventType.DISCARD else EventType.CONSUME,
                product = productId, qty = take, source = "manual"),
            pending(if (discard) "toss" else "use", p.name, "${take.toDecimalString()} ${p.unit}"),
        )
        return RemoveOutcome(take, qty - take)
    }

    /** Removes [qty] from one specific lot. Capped at what the lot holds. This is what the quick buttons call. */
    fun removeFromLot(lotId: String, qty: Rational, discard: Boolean = false): RemoveOutcome {
        require(qty.isPositive) { "quantity must be positive" }
        val lot = snapshot.lots.firstOrNull { it.id == lotId && !it.voided } ?: throw IllegalArgumentException("unknown lot $lotId")
        val take = qty.min(lot.remaining)
        if (!take.isPositive) return RemoveOutcome(Rational.ZERO, qty)
        val p = product(lot.productId)
        append(
            Event(newId(), nextTs(), store.deviceId, if (discard) EventType.DISCARD else EventType.CONSUME,
                lot = lotId, product = lot.productId, qty = take, source = "manual"),
            pending(if (discard) "toss" else "use", p?.name ?: lot.productId, "${take.toDecimalString()} ${p?.unit ?: ""}".trim()),
        )
        return RemoveOutcome(take, qty - take)
    }

    fun removeWholeLot(lotId: String, discard: Boolean = false): RemoveOutcome {
        val lot = snapshot.lots.firstOrNull { it.id == lotId && !it.voided } ?: throw IllegalArgumentException("unknown lot $lotId")
        return if (lot.remaining.isPositive) removeFromLot(lotId, lot.remaining, discard) else RemoveOutcome(Rational.ZERO, Rational.ZERO)
    }

    // Editing

    fun adjustLot(lotId: String, remaining: Rational? = null, expires: LocalDate? = null, location: String? = null, price: Rational? = null) {
        val lot = snapshot.lots.firstOrNull { it.id == lotId && !it.voided } ?: throw IllegalArgumentException("unknown lot $lotId")
        require(remaining == null || !remaining.isNegative) { "remaining cannot be negative" }
        val p = product(lot.productId)
        append(
            Event(newId(), nextTs(), store.deviceId, EventType.ADJUST, lot = lotId, product = lot.productId,
                qty = remaining, expires = expires, location = location, price = price, source = "manual"),
            pending("adjust", p?.name ?: lot.productId, remaining?.let { "to ${it.toDecimalString()}" } ?: ""),
        )
    }

    fun voidLot(lotId: String) {
        val lot = snapshot.lots.firstOrNull { it.id == lotId && !it.voided } ?: throw IllegalArgumentException("unknown lot $lotId")
        append(
            Event(newId(), nextTs(), store.deviceId, EventType.VOID, lot = lotId, product = lot.productId, source = "manual"),
            pending("void", product(lot.productId)?.name ?: lot.productId, ""),
        )
    }

    // Receipts

    /** Writes one add event per confirmed line, saves the OCR text, and remembers new aliases. Returns the receipt file path. */
    fun commitReceipt(storeName: String?, date: LocalDate, rawText: String, lines: List<ConfirmedLine>): String {
        val receiptId = Ids.random("r", 6)
        val path = store.saveReceipt(receiptId, date, rawText)
        store.addPending(listOf("receipt", storeName ?: "", "${lines.size} lines").joinToString("\t"))
        var aliasesChanged = false
        for (l in lines) {
            addLot(l.productId, l.qty, l.price, bought = date, expires = l.expires, source = "receipt:$receiptId")
            val raw = l.rawText?.let { ReceiptMatcher.normalize(it) }
            if (raw != null && raw.isNotBlank() && aliasMap[raw] != l.productId) {
                aliasMap[raw] = l.productId
                aliasesChanged = true
            }
        }
        if (aliasesChanged) store.saveAliases(aliasMap)
        return path
    }

    // Internals

    private fun today(): LocalDate = clock.instant().atZone(ZoneOffset.UTC).toLocalDate()

    private fun newId() = Ids.random("e", 10)

    /** Strictly increasing per device, so a later operation never sorts before an earlier one. */
    private fun nextTs(): Instant {
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
        lastTs = if (now.isAfter(lastTs)) now else lastTs.plusMillis(1)
        return lastTs
    }

    private fun pending(kind: String, label: String, detail: String) = listOf(kind, label, detail).joinToString("\t")

    private fun append(e: Event, pendingLine: String) {
        store.append(e)
        store.addPending(pendingLine)
        events.add(e)
        rebuild()
    }

    private fun rebuild() {
        snapshot = Replay.run(events)
    }

    private val lotOrder = compareBy<Lot>({ it.expires == null }, { it.expires ?: LocalDate.MAX }, { it.bought ?: LocalDate.MAX }, { it.id })
}
