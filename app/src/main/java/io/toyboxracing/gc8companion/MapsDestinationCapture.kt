package io.toyboxracing.gc8companion

import android.content.Context
import android.os.SystemClock

/**
 * Selected-place destination is confirmed only after a Start click and
 * a subsequent Maps navigation-screen transition. Never use the search query.
 */
object MapsDestinationCapture {
    private const val PREFS = "gc8_accessibility_diagnostics"
    private const val MAX_PENDING_MS = 120000L
    private const val MAX_ARMED_MS = 20000L
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, snapshot: String) {
        prefs(context).edit().putString("last_snapshot", snapshot.take(10000)).apply()
    }
    fun read(context: Context): String =
        prefs(context).getString("last_snapshot", null)
            ?: "No Maps accessibility capture yet."

    fun candidate(context: Context, name: String, address: String) {
        val p = prefs(context)
        val now = SystemClock.elapsedRealtime()
        val oldName = p.getString("candidate_name", "")
        val oldAddress = p.getString("candidate_address", "")
        if (name == oldName && address == oldAddress) return
        p.edit().putString("candidate_name", name)
            .putString("candidate_address", address)
            .putLong("candidate_at", now)
            .remove("armed_at").apply()
        DriveLog.record(context, "DEST_CANDIDATE", "$name | $address")
    }

    fun armStart(context: Context) {
        val p = prefs(context)
        val now = SystemClock.elapsedRealtime()
        if (now - p.getLong("candidate_at", 0L) !in 0..MAX_PENDING_MS) {
            DriveLog.record(context, "DEST_IGNORED", "Start without fresh selected place")
            return
        }
        p.edit().putLong("armed_at", now).apply()
        DriveLog.record(context, "DEST_START", "Start pressed on selected place")
    }

    fun navigationVisible(context: Context) {
        val p = prefs(context)
        val now = SystemClock.elapsedRealtime()
        val armed = p.getLong("armed_at", 0L)
        if (armed == 0L || now - armed !in 0..MAX_ARMED_MS) return
        val name = p.getString("candidate_name", "").orEmpty()
        val address = p.getString("candidate_address", "").orEmpty()
        p.edit().remove("armed_at").apply()
        if (name.isBlank() || address.isBlank()) return
        p.edit().putString("confirmed_name", name)
            .putString("confirmed_address", address).apply()
        DriveLog.record(context, "DEST_CONFIRMED", "$name | $address")
    }

    fun confirmed(context: Context): String =
        prefs(context).getString("confirmed_address", "").orEmpty()

    fun clearRoute(context: Context) {
        prefs(context).edit().remove("confirmed_name").remove("confirmed_address")
            .remove("candidate_name").remove("candidate_address")
            .remove("candidate_at").remove("armed_at").apply()
        DriveLog.record(context, "DEST_CLEARED", "Navigation notification ended")
    }
}
