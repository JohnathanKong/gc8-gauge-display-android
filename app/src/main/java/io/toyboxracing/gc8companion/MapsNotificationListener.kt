package io.toyboxracing.gc8companion

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/** Experimental Google Maps notification bridge. Never invent guidance. */
class MapsNotificationListener : NotificationListenerService() {
    private var lastSent: String? = null
    private var navigationActive = false
    private var lastNavigationKey: String? = null

    private fun isNavigation(sbn: StatusBarNotification): Boolean {
        val title = sbn.notification.extras
            .getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        // Exclude Maps review prompts and account notifications: notification
        // access can see other Maps content, not only turn-by-turn guidance.
        return title.startsWith("Starting navigation", ignoreCase = true) ||
            title.startsWith("Rerouting", ignoreCase = true) ||
            Regex("""(?i)\b(turn|head|continue|keep|bear|merge|exit)\b""")
                .containsMatchIn(title)
    }

    private fun beginRoute() {
        navigationActive = true
        lastSent = null
        DriveLog.record(this, "NAV_STARTED", "Google Maps navigation notification detected")
        GaugeBleClient.startNavigation()
    }

    private fun endRoute(reason: String) {
        navigationActive = false
        lastNavigationKey = null
        lastSent = null
        DriveLog.record(this, "NAV_ENDED", reason)
        GaugeBleClient.clearNavigation()
    }

    private var lastNotificationAt = 0L
    private val watchdog = android.os.Handler(android.os.Looper.getMainLooper())
    private val watchdogTask = object : Runnable {
        override fun run() {
            if (lastNotificationAt > 0L) {
                val age = android.os.SystemClock.elapsedRealtime() - lastNotificationAt
                if (age >= 30000L) {
                    DriveLog.record(this@MapsNotificationListener, "WATCHDOG", "No Maps update for ${age / 1000}s")
                    MapsCaptureStore.setBridgeStatus(this@MapsNotificationListener, "STALE: no Maps event for ${age / 1000}s")
                }
            }
            watchdog.postDelayed(this, 30000L)
        }
    }
    override fun onCreate() {
        super.onCreate()
        instance = this
        watchdog.postDelayed(watchdogTask, 30000L)
        DriveLog.record(this, "LISTENER", "created")
    }
    override fun onDestroy() {
        if (instance === this) instance = null
        watchdog.removeCallbacks(watchdogTask)
        DriveLog.record(this, "LISTENER", "destroyed")
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeNotifications?.filter { it.packageName == MAPS_PACKAGE }?.forEach { capture(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName == MAPS_PACKAGE) capture(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName != MAPS_PACKAGE) return
        val wasNavigation = isNavigation(sbn) || sbn.key == lastNavigationKey
        // A Maps review prompt can remain after navigation ends. Count only
        // actual navigation notifications, not all com.google.android.apps.maps.
        val stillNavigating = activeNotifications?.any {
            it.packageName == MAPS_PACKAGE && isNavigation(it)
        } ?: false
        DriveLog.record(this, "MAPS_REMOVED",
            "wasNavigation=$wasNavigation stillNavigating=$stillNavigating")
        if (navigationActive && wasNavigation && !stillNavigating) {
            endRoute("Last navigation notification removed")
            MapsCaptureStore.setBridgeStatus(this, "Google Maps navigation ended")
        }
    }

    private fun capture(sbn: StatusBarNotification) {
        val title = sbn.notification.extras
            .getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        if (!isNavigation(sbn)) return
        val starting = title.startsWith("Starting navigation", ignoreCase = true)
        if (starting || !navigationActive) beginRoute()
        lastNavigationKey = sbn.key
        lastNotificationAt = android.os.SystemClock.elapsedRealtime()
        val extras = sbn.notification.extras
        fun field(key: String): String = extras.getCharSequence(key)?.toString().orEmpty()
        // title was read above to classify the navigation notification.
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
        DriveLog.record(this, "MAPS_NOTIFICATION", "title=$title text=$text big=$bigText sub=$subText " +
            samsungKeys.joinToString(" ") { key -> "$key=${field(key)}" })

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
        val reason = when {
            maneuver == null -> "Waiting: unsupported or missing maneuver"
            distance == null -> "Waiting: no positive distance in notification"
            street.isNullOrBlank() -> "Waiting: street not found in instruction"
            else -> "Parsed: $maneuver | $distance | $street"
        }
        MapsCaptureStore.setBridgeStatus(this, reason)
        DriveLog.record(this, "PARSE", reason)
        if (maneuver != null && distance != null && !street.isNullOrBlank()) {
            val fingerprint = "$maneuver|$distance|$street"
            if (fingerprint != lastSent) {
                lastSent = fingerprint
                DriveLog.record(this, "FORWARD", fingerprint)
                GaugeBleClient.forwardMapsNavigation(maneuver, distance, street)
            }
        }
        Log.i("GC8Maps", "Maps navigation notification received: $reason")
    }

    companion object {
        private const val MAPS_PACKAGE = "com.google.android.apps.maps"
        @Volatile private var instance: MapsNotificationListener? = null
        fun isRouteActive(): Boolean = instance?.navigationActive == true
    }
}
