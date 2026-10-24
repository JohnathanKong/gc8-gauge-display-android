package io.toyboxracing.gc8companion

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/** Experimental Google Maps notification bridge. Never invent guidance. */
class MapsNotificationListener : NotificationListenerService() {
    private var lastSent: String? = null
    @Volatile private var navigationActive = false
    private var lastNavigationKey: String? = null
    // Google Maps often reaches 0 m at both intermediate turns and the
    // destination. This is NOT proof of arrival. A prolonged silence after
    // 0 m means only that the old arrow is no longer trustworthy.
    private var zeroDistanceSince = 0L
    private var guidanceHeld = false
    private var forwardedManeuverForRoute = false
    private var lastWatchdogLogAt = 0L
    // Match only an explicit destination-side announcement from Maps.
    // Never infer left/right from the final turn or the destination address.
    private val destinationSidePattern = Regex(
        """(?i)\bdestination\s+(?:(?:is|will\s+be)\s+)?(?:on\s+)?(?:the\s+)?(left|right)\b"""
    )
    private val exitParkingPattern =
        Regex("""(?i)\bexit (?:the )?parking (?:lot|garage)\b""")

    private fun isNavigation(sbn: StatusBarNotification): Boolean {
        val title = sbn.notification.extras
            .getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = sbn.notification.extras
            .getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        // Exclude Maps review prompts and account notifications: notification
        // access can see other Maps content, not only turn-by-turn guidance.
        return title.startsWith("Starting navigation", ignoreCase = true) ||
            title.startsWith("Rerouting", ignoreCase = true) ||
            Regex("""(?i)\b(turn|head|continue|keep|bear|merge|exit)\b""")
                .containsMatchIn(title) ||
            (navigationActive && (destinationSidePattern.containsMatchIn(title) ||
                                  destinationSidePattern.containsMatchIn(text)))
    }

    private fun beginRoute() {
        navigationActive = true
        lastSent = null
        zeroDistanceSince = 0L
        guidanceHeld = false
        forwardedManeuverForRoute = false
        DriveLog.record(this, "NAV_STARTED", "Google Maps navigation notification detected")
        GaugeBleClient.startNavigation()
    }

    private fun endRoute(reason: String) {
        navigationActive = false
        lastNavigationKey = null
        lastSent = null
        zeroDistanceSince = 0L
        guidanceHeld = false
        forwardedManeuverForRoute = false
        DriveLog.record(this, "NAV_ENDED", reason)
        GaugeBleClient.clearNavigation()
    }

    private var lastNotificationAt = 0L
    private val watchdog = android.os.Handler(android.os.Looper.getMainLooper())
    // Maps often removes/reposts its navigation notification while rerouting.
    // Confirm route loss after a short grace period to avoid flashing CLEAR.
    private val confirmRouteEnded = Runnable {
        val stillNavigating = activeNotifications?.any {
            it.packageName == MAPS_PACKAGE && isNavigation(it)
        } ?: false
        if (navigationActive && !stillNavigating) {
            endRoute("Navigation notification not restored after removal")
            MapsCaptureStore.setBridgeStatus(this, "Google Maps navigation ended")
        }
    }
    private val watchdogTask = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.elapsedRealtime()
            // Wait 15s after a legitimate Google Maps 0 m event before
            // retiring its previous arrow. Intermediate turns are normally
            // replaced by a new positive-distance step within seconds.
            // Neither HOLD nor timeout ever claims the driver has arrived.
            if (navigationActive && zeroDistanceSince > 0L &&
                !guidanceHeld && now - zeroDistanceSince >= 15000L) {
                guidanceHeld = true
                DriveLog.record(this@MapsNotificationListener, "GUIDANCE_HOLD",
                    "Maps reported 0 m, then no positive maneuver for ${(now - zeroDistanceSince) / 1000}s")
                GaugeBleClient.pauseNavigation()
                MapsCaptureStore.setBridgeStatus(this@MapsNotificationListener,
                    "Waiting for Maps: previous turn expired")
            }
            if (navigationActive && lastNotificationAt > 0L) {
                val age = now - lastNotificationAt
                if (age >= 30000L && now - lastWatchdogLogAt >= 30000L) {
                    lastWatchdogLogAt = now
                    DriveLog.record(this@MapsNotificationListener, "WATCHDOG",
                        "No Maps update for ${age / 1000}s")
                }
            }
            watchdog.postDelayed(this, 5000L)
        }
    }
    override fun onCreate() {
        super.onCreate()
        instance = this
        watchdog.postDelayed(watchdogTask, 5000L)
        DriveLog.record(this, "LISTENER", "created")
    }
    override fun onDestroy() {
        if (instance === this) instance = null
        watchdog.removeCallbacks(watchdogTask)
        watchdog.removeCallbacks(confirmRouteEnded)
        DriveLog.record(this, "LISTENER", "destroyed")
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeNotifications?.filter { it.packageName == MAPS_PACKAGE }?.forEach { capture(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName != MAPS_PACKAGE) return
        // Clear immediately if Maps *explicitly* says arrival, without
        // relying on its notification being removed. Don't mistake the
        // ETA phrase "Arrive 9:27 p.m." for arrival confirmation.
        val title = sbn.notification.extras
            .getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = sbn.notification.extras
            .getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        val explicitArrival = Regex(
            """(?i)^(?:you(?:'ve| have) arrived(?: at .*)?|arrived at (?:your )?destination.*|destination reached.*|you have reached your destination.*)$"""
        )
        if (navigationActive && (explicitArrival.matches(title) ||
                                  explicitArrival.matches(text))) {
            DriveLog.record(this, "ARRIVAL_CONFIRMED", "Explicit Google Maps arrival notification")
            endRoute("Google Maps reported arrival")
        } else {
            capture(sbn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName != MAPS_PACKAGE) return
        val wasNavigation = isNavigation(sbn) || sbn.key == lastNavigationKey
        // A Maps review prompt can remain after navigation ends. Count only
        // actual navigation notifications, not all com.google.android.apps.maps.
        val stillNavigating = activeNotifications?.any {
            it.packageName == MAPS_PACKAGE && it.key != sbn.key && isNavigation(it)
        } ?: false
        DriveLog.record(this, "MAPS_REMOVED",
            "wasNavigation=$wasNavigation stillNavigating=$stillNavigating")
        if (navigationActive && wasNavigation && !stillNavigating) {
            watchdog.removeCallbacks(confirmRouteEnded)
            watchdog.postDelayed(confirmRouteEnded, 900L)
        }
    }

    private fun capture(sbn: StatusBarNotification) {
        val title = sbn.notification.extras
            .getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        if (!isNavigation(sbn)) return
        watchdog.removeCallbacks(confirmRouteEnded)
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

        // Destination side is a separate *explicit* Maps statement, not a
        // turn or an arrival. Works even without distance or a street name.
        val side = instructions.firstNotNullOfOrNull { candidate ->
            destinationSidePattern.find(candidate)?.groupValues?.getOrNull(1)
        }
        if (side != null && navigationActive) {
            val heading = "DESTINATION ${side.uppercase()}"
            val key = "INFO|$heading"
            MapsCaptureStore.setBridgeStatus(this, heading)
            if (key != lastSent) {
                lastSent = key
                zeroDistanceSince = 0L
                guidanceHeld = false
                DriveLog.record(this, "DESTINATION_SIDE", "Maps explicitly reported $side")
                GaugeBleClient.forwardMapsInfo(heading, "FOLLOW GOOGLE MAPS")
            }
            return
        }

        val instruction = instructions.firstOrNull { candidate ->
            val s = candidate.lowercase()
            listOf("turn", "head", "continue", "keep", "bear", "exit").any { s.contains(it) }
        }.orEmpty()
        val lower = instruction.lowercase()

        // "Exit the parking lot toward Courtyard Ln" has no left/right bearing
        // and frequently reports 0 m. Show the actual instruction as text
        // instead of manufacturing a straight-ahead arrow or losing guidance.
        if (exitParkingPattern.containsMatchIn(instruction) &&
            !Regex("""(?i)\b(left|right)\b""").containsMatchIn(instruction)) {
            val toward = lower.indexOf(" toward ")
            val hint = if (toward >= 0)
                "TOWARD " + instruction.substring(toward + 8)
                    .trim().trimEnd('.', ',', ';').uppercase().take(31)
                else "FOLLOW GOOGLE MAPS"
            val key = "INFO|EXIT PARKING LOT|$hint"
            MapsCaptureStore.setBridgeStatus(this, "Exit parking lot: $hint")
            if (key != lastSent) {
                lastSent = key
                zeroDistanceSince = 0L
                guidanceHeld = false
                DriveLog.record(this, "PARKING_GUIDANCE", "Exit parking lot; $hint")
                GaugeBleClient.forwardMapsInfo("EXIT PARKING LOT", hint)
            }
            return
        }
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
        // Only explicit zero DISTANCE fields can trigger a stale-turn
        // hold. Never infer arrival from a route ETA, road name, or silence.
        val zeroDistanceRegex =
            Regex("""(?i)(?<![a-z0-9])0(?:[.,]0+)?\s*(km|mi|ft|m)\b""")
        val zeroDistance = listOf(
            field("android.ongoingActivityNoti.primaryInfo"),
            field("android.ongoingActivityNoti.nowbarPrimaryInfo"),
            field("android.ongoingActivityNoti.chipExpandedText"),
            title
        ).any { zeroDistanceRegex.containsMatchIn(it) }
        if (navigationActive && forwardedManeuverForRoute &&
            zeroDistance && zeroDistanceSince == 0L) {
            zeroDistanceSince = android.os.SystemClock.elapsedRealtime()
            DriveLog.record(this, "NEAR_ZERO", "0 m notification; awaiting next turn or route end")
        }
        val street = listOf(" onto ", " toward ", " on ", " to ")
            .firstNotNullOfOrNull { token ->
                val index = lower.indexOf(token)
                if (index >= 0) instruction.substring(index + token.length).trim() else null
            }?.trimEnd('.', ',', ';')
        // The turn direction is real even when Maps has no named road
        // (parking lots, driveways and private access lanes).
        val roadLabel = if (street.isNullOrBlank()) when (maneuver) {
            "LEFT" -> "TURN LEFT"
            "RIGHT" -> "TURN RIGHT"
            "STRAIGHT" -> "CONTINUE"
            else -> ""
        } else street.orEmpty()
        val reason = when {
            maneuver == null -> "Waiting: unsupported or missing maneuver"
            distance == null -> "Waiting: no positive distance in notification"
            else -> "Parsed: $maneuver | $distance | $roadLabel"
        }
        MapsCaptureStore.setBridgeStatus(this, reason)
        DriveLog.record(this, "PARSE", reason)
        if (maneuver != null && distance != null) {
            // Only a previously validated instruction can become stale.
            forwardedManeuverForRoute = true
            // Real fresh maneuver supersedes a near-zero provisional hold.
            zeroDistanceSince = 0L
            guidanceHeld = false
            val fingerprint = "$maneuver|$distance|$roadLabel"
            if (fingerprint != lastSent) {
                lastSent = fingerprint
                DriveLog.record(this, "FORWARD", fingerprint)
                GaugeBleClient.forwardMapsNavigation(maneuver, distance, roadLabel)
            }
        } else if (maneuver != null && distance == null && zeroDistance &&
                   instruction.isNotBlank()) {
            // Zero metres means the current step is imminent, NOT arrival.
            // No fake distance: show Maps' actual named/unnamed direction.
            val label = when (maneuver) {
                "LEFT" -> "TURN LEFT"
                "RIGHT" -> "TURN RIGHT"
                else -> "CONTINUE"
            }
            val key = "INFO|$label"
            if (key != lastSent) {
                lastSent = key
                DriveLog.record(this, "ZERO_DISTANCE_GUIDANCE", label)
                GaugeBleClient.forwardMapsInfo(label, "FOLLOW GOOGLE MAPS")
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
