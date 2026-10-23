package io.toyboxracing.gc8companion

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stores only the most recent Google Maps notification in private app storage. */
object MapsCaptureStore {
    private const val PREFS = "maps_capture"
    private const val KEY = "last_notification"

    fun save(context: Context, title: String, text: String, bigText: String, subText: String, diagnostics: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val value = """
            Last captured: $stamp
            Source: Google Maps notification
            Title: ${title.take(300)}
            Text: ${text.take(600)}
            Big text: ${bigText.take(600)}
            Subtext: ${subText.take(300)}
            ${diagnostics.take(2500)}
        """.trimIndent()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value).apply()
    }

    fun setBridgeStatus(context: Context, status: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("bridge_status", status).apply()
    }

    fun read(context: Context): String =
        "Bridge: " + (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("bridge_status", "Waiting for navigation") ?: "Waiting") +
        "\\n\\n" + readNotification(context)

    private fun readNotification(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: "No Google Maps notification captured yet.\n\nCheck notification access, start turn-by-turn navigation in Google Maps, then tap Refresh."
}
