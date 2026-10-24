package io.toyboxracing.gc8companion

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * User-controlled connectedDevice foreground service.
 *
 * A single BLE client belongs to the service, not to MainActivity.
 * MapsNotificationListener forwards its callbacks via GaugeBleClient's existing
 * activeClient; the garage path retains the existing persisted arming guard.
 * No permission or SmartThings controls are bypassed.
 */
class Gc8ConnectionService : Service() {
    companion object {
        const val ACTION_START = "io.toyboxracing.gc8companion.START_BRIDGE"
        const val ACTION_STOP = "io.toyboxracing.gc8companion.STOP_BRIDGE"
        const val ACTION_STATUS = "io.toyboxracing.gc8companion.BRIDGE_STATUS"
        const val EXTRA_STATUS = "status"
        private const val PREFS = "gc8_connection_service"
        private const val ENABLED = "enabled"
        private const val CHANNEL = "gc8_connection_service_v1"
        private const val NOTIFICATION_ID = 3410

        @Volatile var instance: Gc8ConnectionService? = null
            private set
        @Volatile var latestStatus: String = "Service stopped"
            private set

        fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(ENABLED, false)

        fun hasBlePermission(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
            (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
             context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)

        fun launch(context: Context) {
            val intent = Intent(context, Gc8ConnectionService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }

    lateinit var client: GaugeBleClient
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        client = GaugeBleClient(applicationContext) { updateStatus(it) }
        updateStatus("Service starting")
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "GC8 background connection", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent Waveshare Bluetooth connection and garage-button listening"
            setSound(null, null)
        })
    }

    private fun updateStatus(value: String) {
        latestStatus = value
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, value))
    }

    private fun ongoingNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, Gc8ConnectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("GC8 Companion running")
            .setContentText("Waveshare connection and garage button bridge")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_media_pause, "Stop service", stop
            ).build())
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !enabled(this) && intent?.action != ACTION_START) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(ENABLED, false).apply()
            updateStatus("Service stopped")
            client.close()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasBlePermission(this)) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(ENABLED, false).apply()
            updateStatus("Nearby devices permission required; open GC8 Companion")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, ongoingNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else startForeground(NOTIFICATION_ID, ongoingNotification())
        } catch (e: Exception) {
            Log.e("GC8Service", "Could not start connectedDevice foreground service", e)
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(ENABLED, false).apply()
            updateStatus("Service startup blocked: " + e.javaClass.simpleName)
            stopSelf()
            return START_NOT_STICKY
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(ENABLED, true).apply()
        client.connect()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        client.close()
        instance = null
        latestStatus = "Service stopped"
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName)
            .putExtra(EXTRA_STATUS, latestStatus))
        super.onDestroy()
    }
}
