package io.toyboxracing.gc8companion

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Direct authenticated SmartThings REST integration for a garage-door device.
 * Only the connected Android phone ever sees its credentials. Device ID and
 * capability are selected by the user, never guessed by matching a name.
 *
 * CAUTION: new SmartThings personal access tokens last 24 hours. This class
 * provides a real short-term test integration, not long-lived OAuth account
 * linking. No unofficial Meross credentials, scraping or UI automation.
 */
class SmartThingsGarage(private val context: Context) {
    data class GarageDevice(
        val id: String,
        val label: String,
        val component: String,
        val capability: String
    )

    data class Result(val ok: Boolean, val message: String)
    private val prefs = context.getSharedPreferences("gc8_smartthings_garage", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val activeRequest = AtomicBoolean(false)
    private val apiRoot = "https://api.smartthings.com/v1"
    private val alias = "gc8_smartthings_pat_v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val present = store.getKey(alias, null)
        if (present is SecretKey) return present
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
         .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
         .setKeySize(256).build())
        return generator.generateKey()
    }

    private fun saveSecret(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val bytes = cipher.iv + encrypted
        prefs.edit().putString("token",
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        ).putLong("token_saved_at", System.currentTimeMillis()).apply()
    }

    private fun loadSecret(): String? {
        val stored = prefs.getString("token", null) ?: return null
        return try {
            val data = Base64.decode(stored, Base64.NO_WRAP)
            if (data.size < 29) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    fun hasToken() = loadSecret() != null
    fun savedAt() = prefs.getLong("token_saved_at", 0L)
    fun device(): GarageDevice? {
        val id = prefs.getString("device_id", "").orEmpty()
        val component = prefs.getString("device_component", "").orEmpty()
        val capability = prefs.getString("device_capability", "").orEmpty()
        val label = prefs.getString("device_label", "").orEmpty()
        if (id.isBlank() || component.isBlank() ||
            capability !in setOf("doorControl", "garageDoorControl")) return null
        return GarageDevice(id, label, component, capability)
    }
    fun configured() = hasToken() && device() != null
    fun isArmed() = prefs.getBoolean("garage_armed", false)
    fun trustAddress(): String = prefs.getString("trusted_ble_address", "").orEmpty()

    // Explicitly opt in to physical door control from a particular BLE device.
    // A device-address match is NOT a cryptographic bond. This is personal-use
    // test security; future production should use authenticated BLE pairing.
    fun armForBluetooth(address: String) {
        require(Regex("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$").matches(address))
        prefs.edit().putString("trusted_ble_address", address)
            .putBoolean("garage_armed", true).apply()
    }
    fun disarm() { prefs.edit().putBoolean("garage_armed", false).apply() }
    fun setToken(token: String) {
        require(token.length in 20..4096 && !token.any { it.isWhitespace() })
        // Changing accounts invalidates the previously selected device.
        prefs.edit().remove("device_id").remove("device_component")
            .remove("device_capability").remove("device_label")
            .putBoolean("garage_armed", false).apply()
        saveSecret(token)
    }
    fun chooseDevice(d: GarageDevice) {
        require(d.capability in setOf("doorControl", "garageDoorControl"))
        require(d.component.isNotBlank() && d.id.isNotBlank())
        prefs.edit().putString("device_id", d.id)
            .putString("device_component", d.component)
            .putString("device_capability", d.capability)
            .putString("device_label", d.label)
            .putBoolean("garage_armed", false).apply()
    }
    fun forget() {
        prefs.edit().clear().apply()
        // The AndroidKeyStore key contains no token itself, but removing it
        // also prevents restoring a previously backed-up encrypted blob.
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        } catch (_: Exception) {}
    }

    private fun request(
        token: String, method: String, path: String, jsonBody: String? = null
    ): JSONObject {
        val url = URL(apiRoot + path)
        if (url.protocol != "https" || url.host != "api.smartthings.com") {
            throw IOException("Untrusted API host")
        }
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 7000
        conn.readTimeout = 7000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (jsonBody != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json;charset=utf-8")
                conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            // Don't log, display or export a token or raw API payload.
            if (status == 401) throw IOException("SmartThings access expired: create a new token")
            if (status == 403) throw IOException("SmartThings permission denied: check r:devices and x:devices")
            if (status == 429) throw IOException("SmartThings rate limit: retry later")
            if (status !in 200..299) throw IOException("SmartThings rejected request (HTTP $status)")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() }.ifBlank { "{}" })
        } finally {
            conn.disconnect()
        }
    }

    private fun devicePath(d: GarageDevice): String {
        fun clean(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        return "/devices/${clean(d.id)}"
    }

    fun discover(callback: (Result, List<GarageDevice>) -> Unit) {
        executor.execute {
            val result = try {
                val token = loadSecret() ?: throw IOException("Save a SmartThings token first")
                val json = request(token, "GET", "/devices?max=200")
                val devices = arrayListOf<GarageDevice>()
                val items = json.optJSONArray("items") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val id = item.optString("deviceId")
                    val name = item.optString("label").ifBlank { item.optString("name") }
                    val components = item.optJSONArray("components") ?: continue
                    for (j in 0 until components.length()) {
                        val component = components.optJSONObject(j) ?: continue
                        val componentId = component.optString("id").ifBlank { "main" }
                        val caps = component.optJSONArray("capabilities") ?: continue
                        for (k in 0 until caps.length()) {
                            val capability = caps.optJSONObject(k)?.optString("id") ?: continue
                            if (capability in setOf("doorControl", "garageDoorControl") && id.isNotBlank()) {
                                devices.add(GarageDevice(id, name, componentId, capability))
                            }
                        }
                    }
                }
                Pair(Result(true, if (devices.isEmpty()) "No compatible garage doors found in SmartThings" else "Found ${devices.size} garage device(s)"), devices)
            } catch (e: Exception) {
                Pair(Result(false, e.message ?: "SmartThings discovery failed"), emptyList())
            }
            main.post { callback(result.first, result.second) }
        }
    }

    /**
     * Read CURRENT door state on EVERY press (no stale local toggle state),
     * then issue exactly ONE open or close command. Unknown / moving states
     * fail closed; never blindly send a relay pulse.
     */
    fun toggle(callback: (Result) -> Unit) {
        if (!activeRequest.compareAndSet(false, true)) {
            main.post { callback(Result(false, "Garage command already running")) }
            return
        }
        executor.execute {
            val result = try {
                if (!isArmed()) throw IOException("Garage remote not enabled")
                val d = device() ?: throw IOException("Select a SmartThings garage")
                val token = loadSecret() ?: throw IOException("Connect SmartThings")
                // Tokens are short-lived. Treat >24h from SAVE as expired even
                // when the token was issued earlier; actual 401 is authoritative.
                val saved = savedAt()
                if (saved == 0L || System.currentTimeMillis() - saved > 24L * 60 * 60 * 1000)
                    throw IOException("SmartThings token expired (24h)")
                val state = request(token, "GET", devicePath(d) + "/status")
                val current = state.optJSONObject("components")
                    ?.optJSONObject(d.component)
                    ?.optJSONObject(d.capability)
                    ?.optJSONObject("door")
                    ?.optString("value").orEmpty().lowercase()
                val action = when (current) {
                    "closed" -> "open"
                    "open" -> "close"
                    else -> throw IOException("Garage state unavailable or moving; no command sent")
                }
                val body = JSONObject().put("commands", JSONArray().put(
                    JSONObject().put("component", d.component)
                        .put("capability", d.capability)
                        .put("command", action)
                        .put("arguments", JSONArray())
                )).toString()
                request(token, "POST", devicePath(d) + "/commands", body)
                // HTTP success only confirms SmartThings accepted the command.
                Result(true, "Garage ${action.uppercase()} command accepted")
            } catch (e: Exception) {
                Result(false, e.message ?: "Garage command failed")
            } finally {
                activeRequest.set(false)
            }
            main.post { callback(result) }
        }
    }
}
