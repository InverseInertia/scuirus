package pantry.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pantry.app.BuildConfig
import pantry.core.OpenFoodFacts
import pantry.core.ProductSuggestion
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks up barcodes at Open Food Facts. Open Food Facts asks API users to identify themselves, so the
 * contact address comes from the `off.contact` build setting (see app/build.gradle.kts), not from source.
 */
class OpenFoodFactsClient(private val userAgent: String = "PantryApp/0.1 (self-hosted; ${BuildConfig.OFF_CONTACT})") {

    /** Returns null when the barcode is unknown or the network is unavailable. The caller falls back to manual entry. */
    suspend fun lookup(barcode: String): ProductSuggestion? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(OpenFoodFacts.url(barcode)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json")
            }
            try {
                val body = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: return@withContext null
                OpenFoodFacts.parse(barcode, body)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }
}
