package io.toyboxracing.gc8companion

import android.content.Context
import android.os.SystemClock

/**
 * Stores a destination selected in Google Maps. A candidate is confirmed
 * only following Start and a verified in-navigation UI. The search query
 * is never a destination, and a candidate can only serve one route.
 */
object MapsDestinationCapture {
    private const val PREFS = "gc8_accessibility_diagnostics"
    private const val MAX_PENDING_MS = 120000L
    private const val MAX_ARMED_MS = 60000L
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
        val oldName = p.getString("candidate_name", "")
        val oldAddress = p.getString("candidate_address", "")
        // Maps sends multiple content events from the same place screen.
        // Do not reset the armed Start or renew freshness on each event.
        if (name == oldName && address == oldAddress) return
        p.edit().putString("candidate_name", name)
            .putString("candidate_address", address)
            .putLong("candidate_at", SystemClock.elapsedRealtime())
            .remove("armed_at").remove("confirmed_name").remove("confirmed_address")
            .apply()
        DriveLog.record(context, "DEST_CANDIDATE", "$name | $address")
        MapsNotificationListener.destinationChanged()
    }

    fun armStart(context: Context) {
        val p = prefs(context)
        val now = SystemClock.elapsedRealtime()
        val candidateAge = now - p.getLong("candidate_at", 0L)
        if (p.getLong("candidate_at", 0L) == 0L || candidateAge !in 0..MAX_PENDING_MS) {
            // An unknown new route must never inherit an earlier address.
            p.edit().remove("confirmed_name").remove("confirmed_address")
                .remove("candidate_name").remove("candidate_address")
                .remove("candidate_at").remove("armed_at").apply()
            DriveLog.record(context, "DEST_IGNORED", "Start without fresh selected place")
            MapsNotificationListener.destinationChanged()
            return
        }
        p.edit().remove("confirmed_name").remove("confirmed_address")
            .putLong("armed_at", now).apply()
        DriveLog.record(context, "DEST_START", "Start pressed on selected place")
        MapsNotificationListener.destinationChanged()
    }

    fun navigationVisible(context: Context) {
        val p = prefs(context)
        val now = SystemClock.elapsedRealtime()
        val armed = p.getLong("armed_at", 0L)
        if (armed == 0L || now - armed !in 0..MAX_ARMED_MS) return
        val name = p.getString("candidate_name", "").orEmpty()
        val address = p.getString("candidate_address", "").orEmpty()
        if (name.isBlank() || address.isBlank()) {
            p.edit().remove("armed_at").apply()
            return
        }
        // Consume the candidate. Starting a different route later requires
        // a fresh place-details capture and Start event.
        p.edit().putString("confirmed_name", name)
            .putString("confirmed_address", address)
            .remove("candidate_name").remove("candidate_address")
            .remove("candidate_at").remove("armed_at").apply()
        DriveLog.record(context, "DEST_CONFIRMED", "$name | $address")
        MapsNotificationListener.destinationChanged()
    }

    fun confirmed(context: Context): String =
        prefs(context).getString("confirmed_address", "").orEmpty()

    fun clearRoute(context: Context, reason: String = "Navigation notification ended") {
        val p = prefs(context)
        val hasData = !p.getString("confirmed_address", "").isNullOrBlank() ||
            !p.getString("candidate_address", "").isNullOrBlank() ||
            p.getLong("armed_at", 0L) != 0L
        if (!hasData) return
        p.edit().remove("confirmed_name").remove("confirmed_address")
            .remove("candidate_name").remove("candidate_address")
            .remove("candidate_at").remove("armed_at").apply()
        DriveLog.record(context, "DEST_CLEARED", reason)
        MapsNotificationListener.destinationChanged()
    }
}
