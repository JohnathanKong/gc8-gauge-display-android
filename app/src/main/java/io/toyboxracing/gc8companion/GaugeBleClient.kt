package io.toyboxracing.gc8companion

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import java.util.UUID
import java.util.Calendar
import java.util.Locale
import android.text.format.DateFormat

/** Connects only to the GC8 service UUID; no audio profile interference. */
class GaugeBleClient(private val activity: Activity, private val report: (String) -> Unit) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("a8e31c40-6b8f-4b67-a4f0-4a0b75000001")
        val WRITE_UUID: UUID = UUID.fromString("a8e31c40-6b8f-4b67-a4f0-4a0b75000002")
        val GARAGE_EVENT_UUID: UUID = UUID.fromString("a8e31c40-6b8f-4b67-a4f0-4a0b75000003")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        @Volatile private var activeClient: GaugeBleClient? = null
        fun clearNavigation() { activeClient?.sendClearNavigation() }
        fun pauseNavigation() { activeClient?.sendHoldNavigation() }
        fun startNavigation() { activeClient?.sendStartNavigation() }
        fun forwardMapsNavigation(maneuver: String, distance: String, street: String) {
            activeClient?.sendLiveNavigation(maneuver, distance, street)
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    init { activeClient = this }
    private val manager = activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    @Volatile private var writable: BluetoothGattCharacteristic? = null
    private var garageEvents: BluetoothGattCharacteristic? = null
    private var linkInitialized = false
    private var lastGaragePressAt = 0L
    @Volatile private var writePending = false
    private var latestQueued: String? = null
    private var lastSent: String? = null
    private var lastWriteMessage: String? = null
    // One GATT write at a time. CLEAR outranks live guidance; clock sync
    // must never evict a pending route-ending command.
    private var writeSerial = 0
    private fun priority(packet: String?): Int = when {
        packet == "GC8|1|CLEAR" -> 5
        packet?.startsWith("GC8|1|GARAGE|RESULT|") == true -> 4
        packet?.startsWith("GC8|1|LEFT|") == true ||
        packet?.startsWith("GC8|1|RIGHT|") == true ||
        packet?.startsWith("GC8|1|STRAIGHT|") == true -> 3
        packet == "GC8|1|HOLD" -> 2
        packet == "GC8|1|START" -> 1
        else -> 0
    }
    private var autoReconnect = false
    private var retryCount = 0
    private val clockSync = object : Runnable {
        override fun run() {
            // Low-priority clock refresh must not displace a turn instruction
            // in the BLE single-slot queue.
            if (writable != null && !writePending && latestQueued == null) {
                sendClockTime()
            }
            if (autoReconnect) handler.postDelayed(this, 60_000L)
        }
    }

    private fun sendClockTime() {
        handler.post {
            if (writable == null || writePending || latestQueued != null) return@post
            val now = Calendar.getInstance()
            val mode = if (DateFormat.is24HourFormat(activity)) "24" else "12"
            val base = String.format(Locale.US, "GC8|1|TIME|%02d:%02d:%02d|%s",
                now.get(Calendar.HOUR_OF_DAY),
                now.get(Calendar.MINUTE),
                now.get(Calendar.SECOND), mode)
            // The top-right icon is now the Waveshare's AXP2101 battery,
            // measured on the device. The Android app sends clock only.
            sendPayload(base, false)
        }
    }

    private fun status(value: String) {
        DriveLog.record(activity, "BLE_STATUS", value)
        activity.runOnUiThread { report(value) }
    }

    fun connect() {
        autoReconnect = true
        handler.removeCallbacks(clockSync)
        handler.postDelayed(clockSync, 60_000L)
        if (Build.VERSION.SDK_INT >= 31 &&
            (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
             activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) {
            activity.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 7301)
            status("Grant Nearby devices permission, then tap Connect again.")
            return
        }
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            status("Enable Bluetooth and retry.")
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run { status("BLE scanner unavailable."); return }
        if (scanning) return
        scanning = true
        status("Scanning for GC8 display…")
        try {
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback
            )
            handler.postDelayed({
                if (scanning) {
                    stopScan()
                    status("GC8 not found; display firmware must advertise the service UUID.")
                }
            }, 12000)
        } catch (_: SecurityException) { scanning = false; status("Bluetooth permission missing.") }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            stopScan()
            status("Found GC8 service; connecting…")
            try {
                gatt?.close()
                gatt = result.device.connectGatt(activity, false, gattCallback)
            } catch (e: Exception) { status("Connect failed: ${e.javaClass.simpleName}") }
        }
        override fun onScanFailed(errorCode: Int) { scanning = false; status("Scan failed: $errorCode") }
    }
    fun sendClearNavigation() {
        handler.post { sendPayload("GC8|1|CLEAR", false) }
    }

    fun sendHoldNavigation() {
        handler.post { sendPayload("GC8|1|HOLD", false) }
    }

    // Maps notification START is independent of a positive turn distance.
    fun sendStartNavigation() {
        handler.post { sendPayload("GC8|1|START", false) }
    }

    fun sendLiveNavigation(maneuver: String, distance: String, street: String) {
        handler.post {
            fun clean(s: String, max: Int) = s.replace("|", " ").filter { it.code in 32..126 }.trim().take(max)
            if (maneuver !in listOf("LEFT", "RIGHT", "STRAIGHT")) return@post
            val dist = clean(distance, 19)
            val road = clean(street, 47)
            if (dist.isEmpty() || road.isEmpty()) return@post
            // Sixth field remains empty for compatibility with older firmware;
            // no location/address or selected-place data is transmitted.
            sendPayload("GC8|1|$maneuver|$dist|$road|", false)
        }
    }

    fun sendTestNavigation() {
        sendPayload("GC8|1|RIGHT|250 m|Main St|Apex Motoring|DEMO:14.7", true)
    }

    private fun sendPayload(message: String, isTest: Boolean) {
        val connection = gatt
        val characteristic = writable
        if (connection == null || characteristic == null) {
            status("Connect to GC8 display before sending.")
            return
        }
        if (writePending) {
            // A one-slot queue must never replace CLEAR with a TIME packet.
            // For equal-priority turns, keep the newest distance.
            val queuedPriority = priority(latestQueued)
            if (latestQueued == null || priority(message) >= queuedPriority) {
                latestQueued = message
                DriveLog.record(activity, "BLE_QUEUE", message)
            } else {
                DriveLog.record(activity, "BLE_QUEUE_SKIPPED",
                    "$message (pending higher-priority $latestQueued)")
            }
            return
        }
        if (!isTest && message == lastSent && message != "GC8|1|START") {
            DriveLog.record(activity, "BLE_DUPLICATE", message)
            return
        }
        val payload = message.toByteArray(Charsets.US_ASCII)
        if (payload.size > 100) {
            DriveLog.record(activity, "BLE_REJECT", "payload too long: ${payload.size}")
            return
        }
        DriveLog.record(activity, "BLE_SEND", message)
        writePending = true
        lastWriteMessage = message
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                val result = connection.writeCharacteristic(
                    characteristic, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
                if (result != BluetoothStatusCodes.SUCCESS) {
                    writePending = false
                    status("BLE write could not start (code $result).")
                    if (message == "GC8|1|CLEAR") {
                        // Disconnect also invalidates route display on ESP32.
                        try { connection.disconnect() } catch (_: SecurityException) {}
                    }
                } else {
                    armWriteTimeout(connection, message)
                    if (isTest) status("Sending test navigation + DEMO AFR 14.7…")
                }
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                characteristic.value = payload
                @Suppress("DEPRECATION")
                val started = connection.writeCharacteristic(characteristic)
                if (!started) {
                    writePending = false
                    status("BLE write could not start.")
                    if (message == "GC8|1|CLEAR") {
                        try { connection.disconnect() } catch (_: SecurityException) {}
                    }
                } else {
                    armWriteTimeout(connection, message)
                    if (isTest) status("Sending test navigation…")
                }
            }
        } catch (_: SecurityException) {
            writePending = false
            status("Nearby devices permission missing.")
        }
    }

    private fun armWriteTimeout(connection: BluetoothGatt, message: String) {
        // Normally GATT write callbacks arrive in ~50ms. A missing callback
        // left CLEAR behind the last TIME packet during the owner's drive.
        // Resetting the BLE connection also clears the navigation state on
        // the ESP32 peripheral (its disconnect handler), avoiding a frozen
        // last-turn display when the GATT write queue deadlocks.
        val serial = ++writeSerial
        handler.postDelayed({
            if (writePending && serial == writeSerial &&
                gatt === connection && lastWriteMessage == message) {
                DriveLog.record(activity, "BLE_WRITE_TIMEOUT", message)
                latestQueued = null
                writable = null
                garageEvents = null
                linkInitialized = false
                writePending = false
                lastSent = null
                status("BLE write timed out; resetting connection.")
                try { connection.disconnect() } catch (_: SecurityException) {}
            }
        }, 2500L)
    }

    private fun garageButtonPressed() {
        val now = SystemClock.elapsedRealtime()
        // Physical PWR debounce is handled in firmware. Android adds its own
        // lockout against duplicate BLE notifications and reconnects.
        if (now - lastGaragePressAt < 5000L) {
            DriveLog.record(activity, "GARAGE_SKIPPED", "Duplicate PWR event")
            return
        }
        lastGaragePressAt = now
        DriveLog.record(activity, "GARAGE_BUTTON", "Upper Waveshare PWR tapped")

        // Deliberately DO NOT operate a physical garage door with an
        // unconfigured/unverified vendor integration. Meross exposes
        // open/close actions, not a publicly documented blind toggle API.
        // Once the user's Meross setup is known, this is the ONLY place
        // where a verified local/secure action will be wired.
        status("Top garage button detected; Meross control not configured.")
        sendPayload("GC8|1|GARAGE|RESULT|NOT_READY", false)
    }

    private fun handleGarageEvent(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
                                  bytes: ByteArray) {
        if (g !== gatt || characteristic.uuid != GARAGE_EVENT_UUID) return
        val text = bytes.toString(Charsets.US_ASCII)
        if (text == "GC8|1|GARAGE|PRESS") {
            DriveLog.record(activity, "GARAGE_EVENT", "PRESS received over GC8 BLE")
            garageButtonPressed()
        } else {
            DriveLog.record(activity, "GARAGE_IGNORED", "Unknown BLE event")
        }
    }

    private fun afterGarageSubscription() {
        if (writable == null || linkInitialized) return
        linkInitialized = true
        // Preserve all existing START/CLEAR + clock behavior.
        if (MapsNotificationListener.isRouteActive()) sendStartNavigation()
        else sendClearNavigation()
        handler.postDelayed({ sendClockTime() }, 350L)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, statusCode: Int) {
            // Serialize queue transitions on the same main handler that sends
            // packets. Old callbacks after a forced disconnect are ignored.
            handler.post {
                if (g !== gatt || !writePending) return@post
                writePending = false
                ++writeSerial // invalidates this write's timeout
                val finished = lastWriteMessage
                DriveLog.record(activity, "BLE_WRITE_RESULT",
                    "status=$statusCode message=$finished")
                if (statusCode == BluetoothGatt.GATT_SUCCESS) lastSent = finished
                if (finished == "GC8|1|CLEAR" &&
                    statusCode != BluetoothGatt.GATT_SUCCESS) {
                    DriveLog.record(activity, "BLE_CLEAR_RECOVERY",
                        "CLEAR failed ($statusCode); disconnect to reset device guidance")
                    latestQueued = null
                    try { g.disconnect() } catch (_: SecurityException) {}
                    return@post
                }
                if (characteristic.uuid == WRITE_UUID) {
                    val next = latestQueued
                    latestQueued = null
                    if (next != null) sendPayload(next, false)
                    val kind = when {
                        finished == "GC8|1|CLEAR" -> "Navigation cleared"
                        finished == "GC8|1|HOLD" -> "Guidance paused"
                        finished == "GC8|1|START" -> "Navigation started"
                        finished?.startsWith("GC8|1|TIME|") == true -> "Phone time"
                        finished?.contains("|DEMO:") == true -> "Demo navigation"
                        else -> "Live navigation"
                    }
                    if (kind != "Phone time" || statusCode != BluetoothGatt.GATT_SUCCESS) {
                        status(if (statusCode == BluetoothGatt.GATT_SUCCESS)
                            "$kind accepted by GC8 BLE."
                            else "$kind write failed (status $statusCode).")
                    }
                }
            }
        }
        override fun onCharacteristicChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handler.post { handleGarageEvent(g, characteristic, value) }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < 33) {
                val received = characteristic.value?.clone() ?: return
                handler.post { handleGarageEvent(g, characteristic, received) }
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, descriptor: BluetoothGattDescriptor, statusCode: Int
        ) {
            if (g !== gatt || descriptor.uuid != CCCD_UUID) return
            handler.post {
                if (g !== gatt) return@post
                DriveLog.record(activity, "GARAGE_SUBSCRIBE",
                    "status=$statusCode")
                if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                    status("Garage side-button events unavailable; navigation unaffected.")
                }
                afterGarageSubscription()
            }
        }

        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            if (statusCode == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                retryCount = 0
                status("Connected; discovering services…")
                try {
                    if (!g.requestMtu(185)) g.discoverServices()
                } catch (_: SecurityException) { status("Permission missing.") }
            } else {
                writable = null
                writePending = false
                ++writeSerial
                latestQueued = null
                lastWriteMessage = null
                lastSent = null
                status("Disconnected (status $statusCode)")
                try { g.close() } catch (_: SecurityException) {}
                if (autoReconnect && retryCount < 6) {
                    retryCount++
                    handler.postDelayed({ if (autoReconnect) connect() }, 5000L)
                }
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, statusCode: Int) {
            status("BLE MTU: $mtu; discovering services…")
            try { g.discoverServices() } catch (_: SecurityException) { status("Permission missing.") }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            if (g !== gatt) return
            val service = g.getService(SERVICE_UUID)
            writable = service?.getCharacteristic(WRITE_UUID)
            garageEvents = service?.getCharacteristic(GARAGE_EVENT_UUID)
            linkInitialized = false
            status(if (writable != null) "GC8 BLE connected; write characteristic ready." else "Connected but navigation characteristic missing.")
            if (writable == null) return
            val events = garageEvents
            val descriptor = events?.getDescriptor(CCCD_UUID)
            if (events == null || descriptor == null) {
                DriveLog.record(activity, "GARAGE_SUBSCRIBE", "Old firmware; no notification characteristic")
                afterGarageSubscription()
                return
            }
            try {
                if (!g.setCharacteristicNotification(events, true)) {
                    status("Garage side-button event notification unavailable.")
                    afterGarageSubscription()
                    return
                }
                val started = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(
                        descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(descriptor)
                }
                if (!started) {
                    status("Garage side-button subscribe could not start.")
                    afterGarageSubscription()
                }
                // GATT descriptor write callback starts route and clock writes.
                // These operations must not overlap a descriptor write.
                // Failsafe for phones that never issue the CCCD callback: do
                // not strand navigation while waiting for garage events.
                handler.postDelayed({
                    if (g === gatt && !linkInitialized) {
                        DriveLog.record(activity, "GARAGE_SUBSCRIBE",
                            "No descriptor callback; resuming navigation bridge")
                        afterGarageSubscription()
                    }
                }, 2500L)
            } catch (_: SecurityException) {
                status("Nearby devices permission missing for garage button.")
                afterGarageSubscription()
            }
        }
    }
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { manager.adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: SecurityException) {}
    }
    fun close() {
        autoReconnect = false
        handler.removeCallbacks(clockSync)
        if (activeClient === this) activeClient = null
        stopScan()
        try { gatt?.disconnect(); gatt?.close() } catch (_: SecurityException) {}
        gatt = null
    }
}
