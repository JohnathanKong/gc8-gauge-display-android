package io.toyboxracing.gc8companion

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Observes Google Maps only. Never clicks or controls Maps, never treats a
 * search query as a selected destination, and never changes maneuver parsing.
 */
class MapsDestinationAccessibility : AccessibilityService() {
    private var previousFingerprint = ""
    private var lastRecordedAt = 0L
    private var homeVisible = false
    private var navigationVisible = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != "com.google.android.apps.maps") return

        val isStartClick = if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val clicked = (event.text.joinToString(" ") + " " +
                (event.contentDescription?.toString() ?: "")).trim()
            clicked.equals("Start", ignoreCase = true) ||
                clicked.startsWith("Start ", ignoreCase = true)
        } else {
            false
        }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && !isStartClick) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        // A click may be delivered after Maps has already transitioned to
        // navigation (as seen in the 2026-10-23 logs). Inspect the root for
        // this same click, not only subsequent content-change events.
        if (isStartClick) MapsDestinationCapture.armStart(this)

        val root = rootInActiveWindow ?: return
        val lines = mutableListOf<String>()
        try {
            fun traverse(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 16 || lines.size >= 100) return
                val value = node.text?.toString()?.trim().orEmpty()
                val description = node.contentDescription?.toString()?.trim().orEmpty()
                val id = node.viewIdResourceName.orEmpty().take(100)
                if (value.isNotBlank() || description.isNotBlank()) {
                    val entry = "depth=$depth id=$id text=${value.take(160)} desc=${description.take(160)}"
                    if (entry !in lines) lines.add(entry)
                }
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    try { traverse(child, depth + 1) } finally { child.recycle() }
                }
            }
            traverse(root, 0)
        } finally {
            root.recycle()
        }
        if (lines.isEmpty()) return

        // The Maps home screen, unlike a place details sheet, includes both
        // the search omnibox and Explore tab. That is our reliable reset cue.
        val onHome = lines.any {
            it.contains("id=com.google.android.apps.maps:id/search_omnibox_text_box") &&
                it.contains("Search here")
        } && lines.any { it.contains("id=com.google.android.apps.maps:id/explore_tab_strip_button") }
        if (onHome && !homeVisible) {
            MapsDestinationCapture.clearRoute(this, "Google Maps home screen")
            DriveLog.record(this, "DEST_HOME", "Maps home screen entered")
        }
        homeVisible = onHome

        // There are TWO Google Maps selected-place layouts on this Fold:
        // 1. Expanded details: "Directions to NAME, STREET" + "Address: ..."
        // 2. Compact place sheet: "View Street view imagery for NAME",
        //    a matching NAME text/description, and "Driving mode: ..."
        // In the second layout Maps does NOT expose a full street address,
        // even though Start navigation is available. Never use search text.
        val descriptions = lines.map { it.substringAfter(" desc=").trim() }
        val address = descriptions.firstOrNull { it.startsWith("Address: ", ignoreCase = true) }
            ?.substringAfter(": ")?.trim()
        val direction = descriptions.firstOrNull { it.startsWith("Directions to ", ignoreCase = true) }
            ?.substringAfter("Directions to ")?.trim()

        var selectedName: String? = null
        var selectedAddress = ""
        if (!onHome && !address.isNullOrBlank() && !direction.isNullOrBlank()) {
            val street = address.substringBefore(",").trim()
            val suffix = ", $street"
            if (direction.contains(suffix)) {
                val name = direction.substringBefore(suffix).trim()
                if (name.isNotBlank()) {
                    selectedName = name
                    selectedAddress = address
                }
            }
        }

        if (!onHome && selectedName == null) {
            val prefix = "View Street view imagery for "
            val streetViewName = descriptions.firstOrNull {
                it.startsWith(prefix, ignoreCase = true)
            }?.substring(prefix.length)?.trim().orEmpty()
            val drivingEstimatePresent = descriptions.any {
                it.startsWith("Driving mode:", ignoreCase = true)
            }
            // Street View thumbnail belongs to the *selected* place sheet.
            // Demand corroboration by an exact, separately exposed place title
            // and a driving estimate, not a query/list-result/nearby suggestion.
            val titleMatches = streetViewName.isNotBlank() && lines.any { line ->
                val text = line.substringAfter(" text=", "").substringBefore(" desc=").trim()
                val description = line.substringAfter(" desc=", "").trim()
                text == streetViewName && description == streetViewName
            }
            if (titleMatches && drivingEstimatePresent) {
                selectedName = streetViewName
            }
        }

        if (!selectedName.isNullOrBlank()) {
            MapsDestinationCapture.candidate(this, selectedName, selectedAddress)
        }

        // Samsung's Google Maps navigation UI does not always expose the
        // stepcue textbox. Its arrival-time + Close-navigation controls were
        // both present in the actual failure log. Require paired markers so
        // a route preview cannot accidentally confirm a destination.
        val hasStepCue = lines.any { it.contains("navigation_stepcuefirstline_textbox") }
        val hasNavigationTime = lines.any { it.contains("navigation_time_remaining_label") }
        val hasCloseNavigation = lines.any { it.contains("desc=Close navigation") }
        val hasNavigationToolbar = lines.any { it.contains("nav_search_fab_button") } &&
            lines.any { it.contains("nav_overview_fab") }
        val onNavigation = hasStepCue ||
            (hasNavigationTime && hasCloseNavigation) || hasNavigationToolbar

        if (onNavigation && !navigationVisible) {
            DriveLog.record(this, "DEST_NAV_UI",
                "Navigation chrome detected; stepCue=$hasStepCue time=$hasNavigationTime " +
                    "close=$hasCloseNavigation toolbar=$hasNavigationToolbar")
        }
        navigationVisible = onNavigation
        if (onNavigation) MapsDestinationCapture.navigationVisible(this)

        val fingerprint = lines.joinToString("\n")
        val now = android.os.SystemClock.elapsedRealtime()
        if (fingerprint == previousFingerprint || now - lastRecordedAt < 1000L) return
        previousFingerprint = fingerprint
        lastRecordedAt = now
        DriveLog.record(this, "MAPS_ACCESSIBILITY",
            "event=${event.eventType} nodes=${lines.size} " + lines.take(8).joinToString(" | "))
        MapsDestinationCapture.save(this, fingerprint)
    }

    override fun onInterrupt() {
        DriveLog.record(this, "MAPS_ACCESSIBILITY", "interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        DriveLog.record(this, "MAPS_ACCESSIBILITY", "service enabled")
    }
}
