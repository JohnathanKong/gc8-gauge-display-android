package io.toyboxracing.gc8companion

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Experimental only: Google Maps notification content is not a stable navigation API.
 * No persistence, network upload or Bluetooth transmission in this prototype.
 */
class MapsNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName != "com.google.android.apps.maps") return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        Log.i("GC8Maps", "Google Maps notification: title=${title.orEmpty().take(160)}, text=${text.orEmpty().take(240)}, bigText=${bigText.orEmpty().take(240)}")
    }
}
