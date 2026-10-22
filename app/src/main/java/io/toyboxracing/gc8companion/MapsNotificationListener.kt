package io.toyboxracing.gc8companion

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/** Experimental notification data source; Google Maps is not a stable navigation API. */
class MapsNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        activeNotifications?.filter { it.packageName == MAPS_PACKAGE }?.forEach { capture(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName == MAPS_PACKAGE) capture(sbn)
    }

    private fun capture(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val diagnosticFields = extras.keySet().sorted().joinToString("\n") { key ->
            val value = try { extras.get(key) } catch (_: Exception) { null }
            "$key (${value?.javaClass?.simpleName ?: "null"})"
        }
        val notification = sbn.notification
        val diagnostics = "Extras keys/types:\n$diagnosticFields\n" +
            "Small icon present: ${notification.smallIcon != null}\n" +
            "Large icon present: ${notification.getLargeIcon() != null}\n" +
            "Category: ${notification.category.orEmpty()}"
        MapsCaptureStore.save(this, title, text, bigText, subText, diagnostics)
        Log.i("GC8Maps", "Google Maps notification captured (see app for details)")
    }

    companion object {
        private const val MAPS_PACKAGE = "com.google.android.apps.maps"
    }
}
