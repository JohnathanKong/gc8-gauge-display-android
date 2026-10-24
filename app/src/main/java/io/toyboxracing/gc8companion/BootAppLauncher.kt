package io.toyboxracing.gc8companion

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import java.lang.ref.WeakReference

/**
 * User-selectable launcher for the physical Waveshare BOOT button.
 * The ESP32 sends a BLE event; it never chooses Android apps or bypasses
 * Android's restrictions on starting a UI from a background service.
 *
 * When Companion is visible, a BOOT press opens the chosen launch activity.
 * Otherwise it posts a tappable notification, since a foreground service
 * does not give unrestricted background activity-launch rights.
 */
object BootAppLauncher {
    private const val PREFS = "gc8_boot_app"
    private const val KEY_COMPONENT = "launch_component"
    private const val KEY_LABEL = "launch_label"
    private const val CHANNEL = "gc8_boot_app_launch_v1"
    private const val NOTICE_ID = 3430
    private const val COOLDOWN_MS = 1500L

    private var foregroundActivity: WeakReference<Activity>? = null
    private var lastEvent = 0L

    fun setActivityVisible(activity: Activity) {
        foregroundActivity = WeakReference(activity)
    }

    fun setActivityHidden(activity: Activity) {
        if (foregroundActivity?.get() === activity) foregroundActivity = null
    }

    fun chosenLabel(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LABEL, null).orEmpty().ifBlank { "Not configured" }

    private fun chosenComponent(context: Context): ComponentName? {
        val flattened = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_COMPONENT, null) ?: return null
        return ComponentName.unflattenFromString(flattened)
    }

    /** The only valid target is a launcher activity chosen by the user. */
    fun save(context: Context, component: ComponentName, label: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_COMPONENT, component.flattenToString())
            .putString(KEY_LABEL, label.take(100).ifBlank { component.packageName })
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_COMPONENT).remove(KEY_LABEL).apply()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTICE_ID)
    }

    fun chosen(context: Context): Boolean = chosenComponent(context) != null

    private fun launchIntent(component: ComponentName) = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    data class Result(val handled: Boolean, val message: String)

    fun onPhysicalPress(context: Context): Result {
        val now = SystemClock.elapsedRealtime()
        if (lastEvent > 0 && now - lastEvent < COOLDOWN_MS) {
            return Result(false, "Duplicate BOOT press ignored")
        }
        lastEvent = now

        val selected = chosenComponent(context)
            ?: return Result(false, "Select a BOOT button app in GC8 Companion")
        val intent = launchIntent(selected)
        val active = foregroundActivity?.get()
        if (active != null && !active.isFinishing && !active.isDestroyed) {
            try {
                active.startActivity(intent)
                return Result(true, "Opened " + chosenLabel(context))
            } catch (e: Exception) {
                DriveLog.record(context, "BOOT_APP_LAUNCH", "Direct launch: " + e.javaClass.simpleName)
                // Fall back to an explicit notification on this phone.
            }
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!manager.areNotificationsEnabled()) {
            return Result(false, "Enable GC8 notifications to launch apps from the background")
        }
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "BOOT button app shortcuts", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Tap to open the Android app selected for the Waveshare BOOT key"
            enableVibration(false)
            setSound(null, null)
        })
        val launch = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Waveshare BOOT: " + chosenLabel(context))
            .setContentText("Tap to open the selected app")
            .setContentIntent(launch)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        return try {
            manager.notify(NOTICE_ID, notice)
            Result(true, "Tap the GC8 notification to open " + chosenLabel(context))
        } catch (e: Exception) {
            Result(false, "Unable to show app-launch notification")
        }
    }
}
