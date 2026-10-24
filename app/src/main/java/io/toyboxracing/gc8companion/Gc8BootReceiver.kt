package io.toyboxracing.gc8companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Only resumes a service previously started by the owner and left enabled.
 * Never arms the garage button, changes a notification keyword or grants any
 * permissions. Android/OEM restrictions may prevent background boot launch.
 */
class Gc8BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Gc8ConnectionService.enabled(context)) return
        if (!Gc8ConnectionService.hasBlePermission(context)) return
        try {
            Gc8ConnectionService.launch(context)
        } catch (e: Exception) {
            Log.w("GC8Service", "Auto-resume after boot was blocked", e)
        }
    }
}
