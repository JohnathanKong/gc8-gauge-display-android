package io.toyboxracing.gc8companion

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Read-only experiment: observe selected Google Maps place/route screens.
 * Never click, navigate, infer a destination from a search query, or alter
 * the proven notification -> BLE maneuver pipeline.
 */
class MapsDestinationAccessibility : AccessibilityService() {
    private var previousFingerprint = ""
    private var lastRecordedAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != "com.google.android.apps.maps") return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
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
        val fingerprint = lines.joinToString("\n")
        val now = android.os.SystemClock.elapsedRealtime()
        if (fingerprint == previousFingerprint || now - lastRecordedAt < 1000L) return
        previousFingerprint = fingerprint
        lastRecordedAt = now
        // Explicitly diagnostic-only: UI text is not verified destination data.
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
