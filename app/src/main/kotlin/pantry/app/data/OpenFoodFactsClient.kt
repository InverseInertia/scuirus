package pantry.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pantry.app.BuildConfig
import pantry.core.OpenFoodFacts
import pantry.core.ProductSuggestion
import java.net.HttpURLConnection
import java.net.URL

/** Outcome of a barcode lookup. [Failed] carries a reason the UI can show, so a network problem is not mistaken for an unknown product. */
sealed interface LookupResult {
    data class Found(val suggestion: ProductSuggestion) : LookupResult
    data object NotFound : LookupResult
    data class Failed(val reason: String) : LookupResult
}

/**
 * Looks up barcodes at Open Food Facts. Open Food Facts asks API users to identify themselves, so the
 * contact address comes from the `off.contact` build setting (see app/build.gradle.kts), not from source.
 */
class OpenFoodFactsClient(private val userAgent: String = "Scuirus/0.1 (self-hosted; ${BuildConfig.OFF_CONTACT})") {

    /** UPC-A codes are 12 digits but Open Food Facts files them as 13, so a miss is retried with a leading zero. */
    suspend fun lookup(barcode: String): LookupResult {
        val digits = barcode.filter { it.isDigit() }
        val first = fetch(digits)
        if (first != LookupResult.NotFound || digits.length !in 8..12) return first
        return fetch(digits.padStart(13, '0'))
    }

    private suspend fun fetch(barcode: String): LookupResult = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(OpenFoodFacts.url(barcode)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                // An unknown product comes back as 404 with a JSON body, so only other errors count as failures.
                if (code !in 200..299 && code != 404) return@withContext LookupResult.Failed("server answered HTTP $code")
                OpenFoodFacts.parse(barcode, body)?.let { LookupResult.Found(it) } ?: LookupResult.NotFound
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            LookupResult.Failed("${e.javaClass.simpleName}: ${e.message ?: "no details"}")
        }
    }
}
