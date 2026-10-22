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
        val samsungKeys = listOf(
            "android.ongoingActivityNoti.chipExpandedText",
            "android.ongoingActivityNoti.nowbarPrimaryInfo",
            "android.ongoingActivityNoti.nowbarSecondaryInfo",
            "android.ongoingActivityNoti.primaryInfo",
            "android.ongoingActivityNoti.secondaryInfo",
            "android.shortCriticalText"
        )
        val samsungValues = samsungKeys.joinToString("\n") { key ->
            val value = extras.getString(key)
            "$key: ${value?.take(300) ?: "(absent)"}"
        }
        val diagnostics = "Samsung navigation strings:\n$samsungValues\n\nExtras keys/types:\n$diagnosticFields\n" +
            "Small icon present: ${notification.smallIcon != null}\n" +
            "Large icon present: ${notification.getLargeIcon() != null}\n" +
            "Category: ${notification.category.orEmpty()}"
        MapsCaptureStore.save(this, title, text, bigText, subText, diagnostics)

        val instruction = listOf(title, text, bigText).firstOrNull {
            val s = it.lowercase()
            s.contains("turn ") || s.contains("head ") || s.contains("continue ")
        }.orEmpty()
        val lower = instruction.lowercase()
        val maneuver = when {
            lower.contains("left") -> "LEFT"
            lower.contains("right") -> "RIGHT"
            lower.contains("head ") || lower.contains("continue ") -> "STRAIGHT"
            else -> null
        }
        val distanceCandidates = listOfNotNull(
            extras.getString("android.ongoingActivityNoti.primaryInfo"),
            extras.getString("android.ongoingActivityNoti.nowbarPrimaryInfo"),
            extras.getString("android.ongoingActivityNoti.chipExpandedText"),
            text
        )
        val distance = distanceCandidates.firstNotNullOfOrNull { candidate ->
            candidate.split(" ").windowed(2).firstNotNullOfOrNull { pair ->
                val number = pair[0].replace(",", ".")
                val unit = pair[1].lowercase()
                if (number.toDoubleOrNull() != null && unit in listOf("m", "km", "ft", "mi"))
                    "${pair[0]} ${pair[1]}" else null
            }
        }
        val street = listOf(" onto ", " toward ", " on ", " to ")
            .firstNotNullOfOrNull { token ->
                val index = lower.indexOf(token)
                if (index >= 0) instruction.substring(index + token.length).trim() else null
            }
        val destination = getSharedPreferences("gc8_navigation", MODE_PRIVATE)
            .getString("destination", "").orEmpty()
        if (maneuver != null && distance != null && !street.isNullOrBlank()) {
            GaugeBleClient.forwardMapsNavigation(maneuver, distance, street, destination)
        }
        Log.i("GC8Maps", "Google Maps notification captured (see app for details)")
    }

    companion object {
        private const val MAPS_PACKAGE = "com.google.android.apps.maps"
    }
}
