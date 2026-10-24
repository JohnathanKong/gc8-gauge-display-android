package io.toyboxracing.gc8companion

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Samsung Modes and Routines bridge. A GC8 PWR tap produces exactly ONE new
 * Android notification. The user configures the Galaxy routine IF notification
 * (GC8 Companion + keyword) THEN SmartThings GC8 Garage Trigger switch action.
 *
 * We never call Meross or SmartThings directly, infer door state, or claim the
 * door actually moved. SmartThings is responsible for state-based automation.
 */
class GarageRoutineTrigger(context: Context) {
    companion object {
        const val KEYWORD = "GC8_GARAGE_TRIGGER"
        private const val CHANNEL_ID = "gc8_garage_routine_v1"
        private const val PREFS = "gc8_garage_routine"
        private const val COOLDOWN_MS = 6000L
    }

    data class Result(val sent: Boolean, val message: String)
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val notifications = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val handler = Handler(Looper.getMainLooper())

    fun isArmed(): Boolean = prefs.getBoolean("enabled", false)
    fun trustedAddress(): String = prefs.getString("gc8_ble_address", "").orEmpty()
    fun canPost(): Boolean = notifications.areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 ||
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun arm(address: String): Result {
        if (!Regex("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$").matches(address))
            return Result(false, "Connect the Waveshare over Bluetooth first.")
        if (!canPost())
            return Result(false, "Enable GC8 Companion notification permission first.")
        prefs.edit().putString("gc8_ble_address", address).putBoolean("enabled", true).apply()
        return Result(true, "GC8 garage routine bridge enabled for this Waveshare.")
    }

    fun disarm(): Result {
        prefs.edit().putBoolean("enabled", false).apply()
        return Result(true, "GC8 garage routine bridge disabled.")
    }

    fun request(address: String?): Result {
        // Fail closed on unpaired/untrusted BLE peripherals, unarmed app, or
        // missing Android notification permission. The address restriction is
        // NOT cryptographic security: BLE addresses can be spoofed.
        if (!isArmed()) return Result(false, "Garage routine is not enabled.")
        if (address.isNullOrBlank() ||
            !address.equals(trustedAddress(), ignoreCase = true))
            return Result(false, "Unrecognized Waveshare Bluetooth device.")
        if (!canPost()) return Result(false, "GC8 Companion notifications are disabled.")

        // Source/receiver both run on the Android main handler, avoiding
        // concurrent touches to shared preferences and Bluetooth UI state.
        val now = android.os.SystemClock.elapsedRealtime()
        val last = prefs.getLong("last_emit_elapsed", -COOLDOWN_MS)
        if (now - last < COOLDOWN_MS)
            return Result(false, "Duplicate button press ignored.")

        val channel = NotificationChannel(
            CHANNEL_ID, "Garage routine triggers", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Only notifications from a physical GC8 garage-button press"
            enableVibration(false)
            setSound(null, null)
        }
        notifications.createNotificationChannel(channel)
        // A new ID is essential. Updating the same posted notification might
        // not count as a new "Notification received" in Galaxy routines.
        val id = (prefs.getInt("sequence", 0) + 1).let { if (it <= 0) 1 else it }
        val notification = Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(KEYWORD)
            .setContentText("GC8 Waveshare garage button pressed")
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build()
        return try {
            notifications.notify(id, notification)
            prefs.edit().putInt("sequence", id).putLong("last_emit_elapsed", now).apply()
            // Keep notification briefly so Galaxy Routines can observe it.
            // Cancellation itself is not a second "received" event.
            handler.postDelayed({ notifications.cancel(id) }, 8000L)
            Result(true, "Garage routine notification posted")
        } catch (_: SecurityException) {
            Result(false, "Notification permission was denied.")
        } catch (_: Exception) {
            Result(false, "Android failed to post garage notification.")
        }
    }
}
