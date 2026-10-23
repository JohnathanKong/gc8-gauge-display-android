package io.toyboxracing.gc8companion

import android.content.Context

/** Last visible Google Maps accessibility text, for manual destination validation. */
object MapsDestinationCapture {
    private const val PREFS = "gc8_accessibility_diagnostics"
    fun save(context: Context, snapshot: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("last_snapshot", snapshot.take(10000)).apply()
    }
    fun read(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("last_snapshot", null)
            ?: "No Maps accessibility capture yet. Enable GC8 Companion accessibility access and open a selected destination in Google Maps."
}
