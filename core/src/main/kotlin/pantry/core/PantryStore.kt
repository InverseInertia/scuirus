package pantry.core

import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate

/**
 * Everything the app persists, laid out as plain files in a git working tree.
 *
 *   catalog.jsonl                     one product per line, sorted by id
 *   aliases.jsonl                     one receipt-text alias per line, sorted by text
 *   events/<device>/<yyyy-MM>.jsonl   append-only, written by one device only
 *   receipts/<yyyy-MM>/<id>.txt       raw OCR text of each scanned receipt
 *   .pantry-local/                    not tracked: pending change summaries
 *
 * Each device appends only to its own event files, so two devices never edit the same
 * event file and git has nothing to conflict on there.
 */
class PantryStore(val root: Path, val deviceId: String) {

    init {
        require(Regex("^[A-Za-z0-9_-]{1,40}$").matches(deviceId)) { "device id must be letters, digits, _ or -" }
    }

    private val catalogPath get() = root.resolve("catalog.jsonl")
    private val aliasPath get() = root.resolve("aliases.jsonl")
    private val eventsDir get() = root.resolve("events")
    private val localDir get() = root.resolve(".pantry-local")
    private val pendingPath get() = localDir.resolve("pending.log")

    fun init() {
        Files.createDirectories(root)
        Files.createDirectories(eventsDir.resolve(deviceId))
        Files.createDirectories(localDir)
        val ignore = root.resolve(".gitignore")
        if (!Files.exists(ignore)) writeText(ignore, ".pantry-local/\n")
        if (!Files.exists(catalogPath)) saveProducts(emptyList())
    }

    // Catalog

    fun loadProducts(): List<Product> {
        if (!Files.exists(catalogPath)) return emptyList()
        return readRecords(catalogPath).map { Product.fromMap(it) }
    }

    fun saveProducts(products: List<Product>) {
        val sorted = products.sortedBy { it.id }
        writeText(catalogPath, Jsonl.writeRecords(sorted.map { it.toMap() }))
    }

    // Receipt aliases

    fun loadAliases(): Map<String, String> {
        if (!Files.exists(aliasPath)) return emptyMap()
        return readRecords(aliasPath).mapNotNull { r ->
            val t = r["text"]
            val p = r["product"]
            if (t != null && p != null) t to p else null
        }.toMap()
    }

    fun saveAliases(aliases: Map<String, String>) {
        val recs = aliases.toSortedMap().map { (t, p) -> linkedMapOf("text" to t, "product" to p) }
        writeText(aliasPath, Jsonl.writeRecords(recs))
    }

    // Events

    class LoadedEvents(val events: List<Event>, val problems: List<String>)

    fun loadEvents(): LoadedEvents {
        val events = ArrayList<Event>()
        val problems = ArrayList<String>()
        if (!Files.exists(eventsDir)) return LoadedEvents(events, problems)
        Files.walk(eventsDir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jsonl") }.sorted().forEach { f ->
                readText(f).lines().forEachIndexed { i, line ->
                    if (line.isBlank()) return@forEachIndexed
                    try {
                        events += Event.fromLine(line)
                    } catch (e: CodecException) {
                        problems += "${root.relativize(f)}:${i + 1}: ${e.message}"
                    }
                }
            }
        }
        return LoadedEvents(events, problems)
    }

    fun append(e: Event) {
        require(e.device == deviceId) { "events must be written by this device" }
        val month = Event.TS_FORMAT.format(e.ts).substring(0, 7)
        val dir = eventsDir.resolve(deviceId)
        Files.createDirectories(dir)
        val file = dir.resolve("$month.jsonl")
        val needsNewline = Files.exists(file) && Files.size(file) > 0 && lastByte(file) != '\n'.code
        FileOutputStream(file.toFile(), true).use { out ->
            if (needsNewline) out.write('\n'.code)
            out.write((e.toLine() + "\n").toByteArray(StandardCharsets.UTF_8))
            out.fd.sync()
        }
    }

    private fun lastByte(p: Path): Int = RandomAccessFile(p.toFile(), "r").use {
        it.seek(it.length() - 1)
        it.read()
    }

    // Receipts

    /** Saves raw OCR text and returns the repo-relative path. */
    fun saveReceipt(id: String, date: LocalDate, text: String): String {
        val rel = "receipts/${date.toString().substring(0, 7)}/$id.txt"
        val p = root.resolve(rel)
        Files.createDirectories(p.parent)
        writeText(p, text)
        return rel
    }

    // Pending summaries, used to write commit messages

    fun addPending(entry: String) {
        Files.createDirectories(localDir)
        FileOutputStream(pendingPath.toFile(), true).use {
            it.write((entry.replace('\n', ' ') + "\n").toByteArray(StandardCharsets.UTF_8))
        }
    }

    fun readPending(): List<String> =
        if (Files.exists(pendingPath)) readText(pendingPath).lines().filter { it.isNotBlank() } else emptyList()

    fun clearPending() {
        Files.deleteIfExists(pendingPath)
    }

    // Helpers

    /** Unlike event files, a bad line here stops the load. Skipping it would drop the record the next time the file is saved. */
    private fun readRecords(p: Path): List<Map<String, String>> = try {
        Jsonl.readRecords(readText(p))
    } catch (e: CodecException) {
        throw CodecException("${root.relativize(p)} ${e.message}")
    }

    private fun readText(p: Path): String = String(Files.readAllBytes(p), StandardCharsets.UTF_8)

    private fun writeText(p: Path, text: String) {
        val tmp = p.resolveSibling(p.fileName.toString() + ".tmp")
        Files.write(tmp, text.toByteArray(StandardCharsets.UTF_8))
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
