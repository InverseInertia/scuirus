package pantry.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pantry.app.PantryApp
import pantry.core.CommitInfo
import pantry.core.CommitResult
import pantry.core.ConfirmedLine
import pantry.core.DraftLine
import pantry.core.InventoryItem
import pantry.core.OcrLine
import pantry.core.OcrRows
import pantry.core.Product
import pantry.core.ProductStats
import pantry.app.data.LookupResult
import pantry.core.ProductSuggestion
import pantry.core.Rational
import pantry.core.ReceiptDraft
import pantry.core.ReceiptParser
import pantry.core.RepoStatus
import pantry.core.RevertResult
import pantry.core.ScanRemoveResult
import pantry.core.SyncResult
import pantry.core.pretty
import java.time.LocalDate

enum class ScanMode { ADD, REMOVE }

/** Shown when a scanned barcode is not in the catalog yet. [suggestion] fills in once Open Food Facts answers. */
data class NewProductPrompt(
    val barcode: String,
    val suggestion: ProductSuggestion? = null,
    val loading: Boolean = true,
    /** Set when the lookup itself failed, as opposed to the product being unknown. */
    val lookupError: String? = null,
)

/** The user's decision for one receipt line. */
data class ReceiptChoice(
    val line: DraftLine,
    val include: Boolean,
    /** Existing product, or null to create one named [newName]. */
    val productId: String?,
    val newName: String,
    val qty: Rational,
    val price: Rational?,
)

class PantryViewModel(private val app: PantryApp) : ViewModel() {

    private val container get() = app.container
    private val lock = Mutex()

    var ready by mutableStateOf(false); private set
    var startupError by mutableStateOf<String?>(null); private set
    var items by mutableStateOf<List<InventoryItem>>(emptyList()); private set
    var products by mutableStateOf<List<Product>>(emptyList()); private set
    var stats by mutableStateOf<Map<String, ProductStats>>(emptyMap()); private set
    var status by mutableStateOf(RepoStatus(emptyList(), null)); private set
    var suggestedMessage by mutableStateOf(""); private set
    var history by mutableStateOf<List<CommitInfo>>(emptyList()); private set
    var dataWarnings by mutableStateOf<List<String>>(emptyList()); private set
    var snackbar by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false); private set

    var scanMode by mutableStateOf(ScanMode.REMOVE)
    var tossMode by mutableStateOf(false)
    var newProductPrompt by mutableStateOf<NewProductPrompt?>(null); private set
    var receiptDraft by mutableStateOf<ReceiptDraft?>(null)

    private var lastScan: Pair<String, Long> = "" to 0L

    init {
        viewModelScope.launch {
            val err = work { container.start() }
            startupError = err
            ready = err == null
            if (ready) refresh()
        }
    }

    private suspend fun <T> work(block: () -> T): T = lock.withLock { withContext(Dispatchers.IO) { block() } }

    /** Reloads every piece of derived state. Cheap enough to call after each change. */
    suspend fun refresh() {
        data class Snap(
            val items: List<InventoryItem>, val products: List<Product>, val stats: Map<String, ProductStats>,
            val status: RepoStatus, val message: String, val warnings: List<String>,
        )

        val s = work {
            val svc = container.service
            val wf = container.workflow
            Snap(
                svc.inventory(), svc.products, svc.stats(), wf.status(), wf.suggestedMessage(),
                svc.loadProblems + svc.snapshot.warnings.map { it.message } +
                    svc.snapshot.shortfalls.map { (pid, q) ->
                        "Removed ${q.pretty()} more ${svc.product(pid)?.name ?: pid} than was recorded in stock."
                    },
            )
        }
        items = s.items; products = s.products; stats = s.stats
        status = s.status; suggestedMessage = s.message; dataWarnings = s.warnings
    }

    private fun launchOp(message: ((Any?) -> String?)? = null, op: () -> Any?) {
        viewModelScope.launch {
            try {
                val r = work(op)
                message?.invoke(r)?.let { snackbar = it }
            } catch (e: Exception) {
                snackbar = e.message ?: "Something went wrong"
            }
            refresh()
        }
    }

    // Scanning

    fun onBarcode(code: String) {
        val now = System.currentTimeMillis()
        if (newProductPrompt != null) return
        if (code == lastScan.first && now - lastScan.second < 2500) return
        lastScan = code to now

        viewModelScope.launch {
            try {
                if (scanMode == ScanMode.REMOVE) {
                    val res = work { container.service.scanRemove(code) }
                    snackbar = when (res) {
                        is ScanRemoveResult.Removed ->
                            "Used ${res.removed.pretty()} ${res.product.unit} ${res.product.name}, ${res.remainingAfter.pretty()} left"
                        is ScanRemoveResult.OutOfStock -> "${res.product.name} is not in stock"
                        is ScanRemoveResult.UnknownBarcode -> "Barcode $code is not in the catalog"
                    }
                } else {
                    val added = work { container.service.scanAdd(code) }
                    if (added != null) {
                        snackbar = "Added 1 ${added.first.unit} ${added.first.name}"
                    } else {
                        newProductPrompt = NewProductPrompt(code)
                        newProductPrompt = when (val r = container.lookup.lookup(code)) {
                            is LookupResult.Found -> NewProductPrompt(code, r.suggestion, loading = false)
                            is LookupResult.NotFound -> NewProductPrompt(code, loading = false)
                            is LookupResult.Failed -> NewProductPrompt(code, loading = false, lookupError = r.reason)
                        }
                    }
                }
            } catch (e: Exception) {
                snackbar = e.message ?: "Scan failed"
            }
            refresh()
        }
    }

    fun dismissNewProduct() { newProductPrompt = null }

    fun confirmNewProduct(
        barcode: String, name: String, unit: String, location: String, packSize: String?,
        shelfLifeDays: Int?, qty: Rational,
    ) {
        newProductPrompt = null
        launchOp {
            val p = container.service.newProduct(name, barcode, unit.ifBlank { "each" }, location, packSize = packSize, shelfLifeDays = shelfLifeDays)
            if (scanMode == ScanMode.ADD) container.service.addLot(p.id, qty, source = "scan")
            null
        }
    }

    // Quick removal. The toss switch turns any removal into waste.

    fun removeFromProduct(productId: String, qty: Rational?) = launchOp({ r -> r as String? }) {
        val svc = container.service
        val have = svc.snapshot.remainingFor(productId)
        val out = svc.removeFromProduct(productId, qty ?: have, discard = tossMode)
        if (out.removed.isPositive) null else "Nothing in stock to remove"
    }

    fun removeFromLot(lotId: String, qty: Rational?) = launchOp({ r -> r as String? }) {
        val svc = container.service
        val out = if (qty == null) svc.removeWholeLot(lotId, tossMode) else svc.removeFromLot(lotId, qty, tossMode)
        if (out.removed.isPositive) null else "Nothing left in that lot"
    }

    // Manual entry and editing

    fun addManual(productId: String, qty: Rational, price: Rational?, expires: LocalDate?, location: String?) =
        launchOp({ "Added" }) { container.service.addLot(productId, qty, price, expires = expires, location = location, source = "manual") }

    fun createProductAndAdd(name: String, unit: String, location: String, shelfLifeDays: Int?, qty: Rational, price: Rational?, expires: LocalDate?) =
        launchOp({ "Added" }) {
            val p = container.service.newProduct(name, unit = unit.ifBlank { "each" }, location = location, shelfLifeDays = shelfLifeDays)
            container.service.addLot(p.id, qty, price, expires = expires, source = "manual")
        }

    fun saveProduct(p: Product) = launchOp { container.service.saveProduct(p) }

    fun adjustLot(lotId: String, remaining: Rational?, expires: LocalDate?, location: String?, price: Rational?) =
        launchOp { container.service.adjustLot(lotId, remaining, expires, location, price) }

    fun voidLot(lotId: String) = launchOp({ "Lot removed from records" }) { container.service.voidLot(lotId) }

    // Receipts

    fun onReceiptText(lines: List<OcrLine>) {
        val rows = OcrRows.merge(lines)
        receiptDraft = ReceiptParser.parse(rows, LocalDate.now())
        if (receiptDraft?.lines.isNullOrEmpty()) snackbar = "No line items found. Try a flatter, brighter photo."
    }

    fun suggestionsFor(raw: String) =
        pantry.core.ReceiptMatcher.suggest(raw, products, container.service.aliases)

    fun confirmReceipt(storeName: String?, date: LocalDate, rawText: String, choices: List<ReceiptChoice>) {
        receiptDraft = null
        launchOp({ r -> r as String? }) {
            val svc = container.service
            val confirmed = ArrayList<ConfirmedLine>()
            for (c in choices.filter { it.include }) {
                val pid = c.productId ?: svc.newProduct(c.newName.ifBlank { c.line.name }, unit = c.line.unit ?: "each").id
                confirmed += ConfirmedLine(pid, c.qty, c.price, rawText = c.line.name)
            }
            if (confirmed.isEmpty()) "Nothing selected" else {
                svc.commitReceipt(storeName, date, rawText, confirmed)
                "Added ${confirmed.size} items from the receipt"
            }
        }
    }

    // Git

    fun commit(message: String) = launchOp({ r ->
        when (r) {
            is CommitResult.Committed -> "Committed"
            else -> "Nothing to commit"
        }
    }) { container.workflow.commit(message) }

    fun discard() = launchOp({ "Changes discarded" }) {
        container.workflow.discard()
        container.service.load()
    }

    fun sync() {
        if (busy) return
        busy = true
        viewModelScope.launch {
            val r = try { work { container.workflow.sync() } } catch (e: Exception) { SyncResult.Failed(e.message ?: "sync failed") }
            snackbar = when (r) {
                is SyncResult.Synced -> "Synced: pulled ${r.pulledCommits}, pushed ${r.pushedCommits}"
                is SyncResult.Blocked -> "Commit your ${r.dirtyPaths.size} changed files first"
                is SyncResult.Conflict -> "Conflict in ${r.paths.joinToString()}. Nothing was changed."
                is SyncResult.Failed -> "Sync failed: ${r.message}"
            }
            if (r is SyncResult.Synced) work { container.service.load() }
            busy = false
            refresh()
            loadHistory()
        }
    }

    fun loadHistory() {
        viewModelScope.launch { history = work { container.workflow.history(60) } }
    }

    fun revert(sha: String) {
        viewModelScope.launch {
            val r = work {
                val res = container.workflow.revert(sha)
                if (res is RevertResult.Reverted) container.service.load()
                res
            }
            snackbar = when (r) {
                is RevertResult.Reverted -> "Reverted. The undo is a new commit."
                is RevertResult.Conflict -> "Cannot revert cleanly: later changes touch the same files."
                is RevertResult.Failed -> r.message
            }
            refresh()
            loadHistory()
        }
    }

    // Settings

    fun saveSettings(url: String, user: String, token: String) {
        container.settings.remoteUrl = url
        container.settings.username = user
        if (token.isNotBlank()) container.settings.token = token
        viewModelScope.launch {
            val err = work { container.start() }
            startupError = err
            ready = err == null
            snackbar = err ?: "Settings saved"
            if (ready) { refresh(); loadHistory() }
        }
    }
}
