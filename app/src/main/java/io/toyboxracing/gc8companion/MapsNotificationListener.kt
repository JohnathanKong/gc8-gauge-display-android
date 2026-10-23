package io.toyboxracing.gc8companion

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/** Experimental Google Maps notification bridge. Never invent guidance. */
class MapsNotificationListener : NotificationListenerService() {
    private var lastSent: String? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeNotifications?.filter { it.packageName == MAPS_PACKAGE }?.forEach { capture(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName == MAPS_PACKAGE) capture(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName != MAPS_PACKAGE) return
        val stillActive = activeNotifications?.any { it.packageName == MAPS_PACKAGE } ?: false
        if (!stillActive) {
            lastSent = null
            MapsCaptureStore.setBridgeStatus(this, "Maps notification removed; route data may be stale")
            GaugeBleClient.clearNavigation()
        }
    }

    private fun capture(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        fun field(key: String): String = extras.getCharSequence(key)?.toString().orEmpty()
        val title = field(Notification.EXTRA_TITLE)
        val text = field(Notification.EXTRA_TEXT)
        val bigText = field(Notification.EXTRA_BIG_TEXT)
        val subText = field(Notification.EXTRA_SUB_TEXT)
        val samsungKeys = listOf(
            "android.ongoingActivityNoti.chipExpandedText",
            "android.ongoingActivityNoti.nowbarPrimaryInfo",
            "android.ongoingActivityNoti.nowbarSecondaryInfo",
            "android.ongoingActivityNoti.primaryInfo",
            "android.ongoingActivityNoti.secondaryInfo",
            "android.shortCriticalText"
        )
        val diagnostics = "Samsung navigation strings:\n" +
            samsungKeys.joinToString("\n") { key -> "$key: ${field(key).take(300).ifBlank { "(absent)" }}" } +
            "\n\nExtras keys/types:\n" +
            extras.keySet().sorted().joinToString("\n") { key ->
                val value = try { extras.get(key) } catch (_: Exception) { null }
                "$key (${value?.javaClass?.simpleName ?: "null"})"
            } +
            "\nSmall icon present: ${sbn.notification.smallIcon != null}" +
            "\nLarge icon present: ${sbn.notification.getLargeIcon() != null}" +
            "\nCategory: ${sbn.notification.category.orEmpty()}"
        MapsCaptureStore.save(this, title, text, bigText, subText, diagnostics)

        val instructions = listOf(
            title, field("android.ongoingActivityNoti.nowbarSecondaryInfo"),
            field("android.ongoingActivityNoti.secondaryInfo"), bigText, text
        ).filter { it.isNotBlank() }
        val instruction = instructions.firstOrNull { candidate ->
            val s = candidate.lowercase()
            listOf("turn", "head", "continue", "keep", "bear").any { s.contains(it) }
        }.orEmpty()
        val lower = instruction.lowercase()
        val maneuver = when {
            lower.contains("left") -> "LEFT"
            lower.contains("right") -> "RIGHT"
            lower.contains("head") || lower.contains("continue") -> "STRAIGHT"
            else -> null
        }
        // Samsung Fold navigation fields have shown '0 m' in actual captures.
        // Do not treat a zero reading as trustworthy next-turn distance.
        val distanceRegex = Regex("""(?i)(?<![a-z0-9])([0-9]+(?:[.,][0-9]+)?)\s*(km|mi|ft|m)\b""")
        val distance = listOf(
            field("android.ongoingActivityNoti.primaryInfo"),
            field("android.ongoingActivityNoti.nowbarPrimaryInfo"),
            field("android.ongoingActivityNoti.chipExpandedText"),
            text, bigText, title
        ).firstNotNullOfOrNull { candidate ->
            distanceRegex.find(candidate)?.let {
                val number = it.groupValues[1].replace(",", ".").toDoubleOrNull()
                if (number != null && number > 0) it.value else null
            }
        }
        val street = listOf(" onto ", " toward ", " on ", " to ")
            .firstNotNullOfOrNull { token ->
                val index = lower.indexOf(token)
                if (index >= 0) instruction.substring(index + token.length).trim() else null
            }?.trimEnd('.', ',', ';')
        val destination = getSharedPreferences("gc8_navigation", MODE_PRIVATE)
            .getString("destination", "").orEmpty()
        val reason = when {
            maneuver == null -> "Waiting: unsupported or missing maneuver"
            distance == null -> "Waiting: no positive distance in notification"
            street.isNullOrBlank() -> "Waiting: street not found in instruction"
            else -> "Parsed: $maneuver | $distance | $street"
        }
        MapsCaptureStore.setBridgeStatus(this, reason)
        if (maneuver != null && distance != null && !street.isNullOrBlank()) {
            val fingerprint = "$maneuver|$distance|$street|$destination"
            if (fingerprint != lastSent) {
                lastSent = fingerprint
                GaugeBleClient.forwardMapsNavigation(maneuver, distance, street, destination)
            }
        }
        Log.i("GC8Maps", "Maps notification received: $reason")
    }

    companion object {
        private const val MAPS_PACKAGE = "com.google.android.apps.maps"
    }
}
