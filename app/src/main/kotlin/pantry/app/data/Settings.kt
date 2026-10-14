package pantry.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/** Remote and identity settings. The access token is stored encrypted with a Keystore-backed key. */
class Settings(context: Context) {

    private val plain: SharedPreferences = context.getSharedPreferences("pantry", Context.MODE_PRIVATE)

    private val secret: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "pantry_secret",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** HTTPS URL of the central repository, for example https://git.example.com/me/pantry-data.git */
    var remoteUrl: String
        get() = plain.getString("remote_url", "") ?: ""
        set(v) = plain.edit().putString("remote_url", v.trim()).apply()

    var username: String
        get() = plain.getString("username", "") ?: ""
        set(v) = plain.edit().putString("username", v.trim()).apply()

    var token: String
        get() = secret.getString("token", "") ?: ""
        set(v) = secret.edit().putString("token", v).apply()

    /** Names this phone's event files. Generated once and never changed. */
    val deviceId: String
        get() {
            plain.getString("device_id", null)?.let { return it }
            val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
            val rnd = SecureRandom()
            val id = "phone-" + (1..6).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
            plain.edit().putString("device_id", id).apply()
            return id
        }

    val hasRemote: Boolean get() = remoteUrl.isNotBlank()
}
