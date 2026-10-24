package io.toyboxracing.gc8companion

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
    private const val KEY_MODE = "launch_mode"
    private const val MODE_ASSISTANT = "assistant"
    private const val MODE_APP = "app"
    private const val MODE_DISABLED = "disabled"
    // ACTION_VOICE_ASSIST is @SystemApi, not a public SDK constant.
    // Use the documented platform action string with ACTION_ASSIST fallback.
    private const val VOICE_ASSIST_ACTION = "android.intent.action.VOICE_ASSIST"
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

    private fun mode(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Migrate silently: a previously chosen app stays selected.
        return prefs.getString(KEY_MODE, null)
            ?: if (prefs.contains(KEY_COMPONENT)) MODE_APP else MODE_ASSISTANT
    }

    fun isAssistantMode(context: Context): Boolean = mode(context) == MODE_ASSISTANT

    fun chosenLabel(context: Context): String {
        if (isAssistantMode(context)) return "Default Android assistant"
        if (mode(context) == MODE_DISABLED) return "Disabled"
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LABEL, null).orEmpty().ifBlank { "No valid app selected" }
    }

    private fun chosenComponent(context: Context): ComponentName? {
        val flattened = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_COMPONENT, null) ?: return null
        return ComponentName.unflattenFromString(flattened)
    }

    /** An app can be selected instead of the phone's default assistant. */
    fun save(context: Context, component: ComponentName, label: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, MODE_APP)
            .putString(KEY_COMPONENT, component.flattenToString())
            .putString(KEY_LABEL, label.take(100).ifBlank { component.packageName })
            .apply()
    }

    fun useDefaultAssistant(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, MODE_ASSISTANT).remove(KEY_COMPONENT)
            .remove(KEY_LABEL).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, MODE_DISABLED)
            .remove(KEY_COMPONENT).remove(KEY_LABEL).apply()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTICE_ID)
    }

    fun chosen(context: Context): Boolean = when (mode(context)) {
        MODE_ASSISTANT -> true
        MODE_APP -> chosenComponent(context) != null
        else -> false
    }

    private fun appIntent(component: ComponentName) = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    private fun voiceIntent() = Intent(VOICE_ASSIST_ACTION)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun assistIntent() = Intent(Intent.ACTION_ASSIST)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    enum class Delivery { DIRECT, NOTIFICATION, NONE }
    data class Result(val handled: Boolean, val message: String,
                      val delivery: Delivery = Delivery.NONE)

    fun onPhysicalPress(context: Context): Result {
        val now = SystemClock.elapsedRealtime()
        if (lastEvent > 0 && now - lastEvent < COOLDOWN_MS) {
            return Result(false, "Duplicate BOOT press ignored")
        }
        lastEvent = now

        val assistant = isAssistantMode(context)
        val target = if (assistant) null else chosenComponent(context)
        if (!assistant && (mode(context) == MODE_DISABLED || target == null)) {
            return Result(false, "Assign BOOT to the default assistant or an app")
        }

        val active = foregroundActivity?.get()
        if (active != null && !active.isFinishing && !active.isDestroyed) {
            // Only a visibly resumed Activity may invoke the assistant/app
            // directly. Never try to bypass Android's background limits.
            val requests = if (assistant) listOf(voiceIntent(), assistIntent())
                else listOf(appIntent(target!!))
            for (request in requests) {
                try {
                    active.startActivity(request)
                    return Result(true,
                        if (assistant) "Assistant requested" else "Opened " + chosenLabel(context),
                        Delivery.DIRECT)
                } catch (e: Exception) {
                    DriveLog.record(context, "BOOT_DIRECT",
                        "Action unavailable: " + e.javaClass.simpleName)
                }
            }
        }

        return try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!manager.areNotificationsEnabled()) {
                return Result(false, "Enable GC8 notifications for background BOOT requests")
            }
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL, "BOOT button app shortcuts", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Tap to open the app or default assistant selected for BOOT"
                enableVibration(false)
                setSound(null, null)
            })
            // A notification tap is a user-initiated activity launch.
            // ACTION_ASSIST is the public API. VOICE_ASSIST is @SystemApi
            // and cannot be guaranteed to resolve from a notification.
            val intent = if (assistant) assistIntent() else appIntent(target!!)
            val launch = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notice = Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Waveshare BOOT: " + chosenLabel(context))
                .setContentText(if (assistant) "Tap to request the phone's default assistant"
                    else "Tap to open the selected app")
                .setContentIntent(launch)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .build()
            manager.notify(NOTICE_ID, notice)
            Result(true, "Tap the GC8 notification for " + chosenLabel(context),
                Delivery.NOTIFICATION)
        } catch (e: Exception) {
            DriveLog.record(context, "BOOT_APP_ALERT",
                "Notification unavailable: " + e.javaClass.simpleName)
            Result(false, "Unable to show BOOT shortcut notification")
        }
    }
}
