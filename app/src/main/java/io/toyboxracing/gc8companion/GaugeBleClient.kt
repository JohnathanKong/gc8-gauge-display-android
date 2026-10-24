package io.toyboxracing.gc8companion

import android.Manifest
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
class GaugeBleClient(private val activity: Context, private val report: (String) -> Unit) {
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
        fun forwardMapsInfo(title: String, hint: String) {
            activeClient?.sendNavigationInfo(title, hint)
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    init { activeClient = this }
    private val routineBridge = GarageRoutineTrigger(activity)
    private val manager = activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val blePrefs = activity.getSharedPreferences("gc8_ble", Context.MODE_PRIVATE)
    private val knownAddressKey = "verified_gatt_device_address"
    // Reuse the previously verified, bonded GC8 identity when Android's HID
    // host holds the link and the peripheral stops advertising the GC8 UUID.
    private var tryBondedFirst = true
    private var bondedAttempt = false
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
        packet?.startsWith("GC8|1|STRAIGHT|") == true ||
        packet?.startsWith("GC8|1|INFO|") == true -> 3
        packet == "GC8|1|HOLD" -> 2
        packet == "GC8|1|START" -> 1
        else -> 0
    }
    private var autoReconnect = false
    private var retryCount = 0
    private var connecting = false
    private val retryTask = Runnable { if (autoReconnect) connect() }
    private fun retry(afterMs: Long = 20000L) {
        if (!autoReconnect) return
        handler.removeCallbacks(retryTask)
        handler.postDelayed(retryTask, afterMs)
    }
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
        handler.post { report(value) }
    }

    fun connect() {
        autoReconnect = true
        if (scanning || connecting || writable != null) return
        handler.removeCallbacks(retryTask)
        handler.removeCallbacks(clockSync)
        handler.postDelayed(clockSync, 60_000L)
        if (Build.VERSION.SDK_INT >= 31 &&
            (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
             activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) {
            status("Nearby devices permission required. Grant it in GC8 Companion.")
            retry(30000L)
            return
        }
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            status("Enable Bluetooth; GC8 will reconnect automatically.")
            retry(30000L)
            return
        }
        if (tryBondedFirst) {
            tryBondedFirst = false
            val saved = blePrefs.getString(knownAddressKey, null)
            if (saved != null && BluetoothAdapter.checkBluetoothAddress(saved)) {
                try {
                    val device = adapter.getRemoteDevice(saved)
                    if (device.bondState == BluetoothDevice.BOND_BONDED) {
                        status("Reconnecting through paired GC8 device…")
                        if (startGattConnection(device, true)) return
                    }
                } catch (e: Exception) {
                    DriveLog.record(activity, "BLE_BONDED_FALLBACK", e.javaClass.simpleName)
                }
            }
        }
        startScan(adapter)
    }

    private fun startScan(adapter: BluetoothAdapter) {
        val scanner = adapter.bluetoothLeScanner ?: run {
            status("BLE scanner unavailable.")
            retry(30000L)
            return
        }
        scanning = true
        status("Scanning for GC8 display…")
        try {
            val filters = mutableListOf(
                ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
            )
            val saved = blePrefs.getString(knownAddressKey, null)
            if (saved != null && BluetoothAdapter.checkBluetoothAddress(saved))
                filters.add(ScanFilter.Builder().setDeviceAddress(saved).build())
            scanner.startScan(
                filters,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), scanCallback
            )
            handler.postDelayed({
                if (scanning) {
                    stopScan()
                    // No advertising packet does not prove out-of-range:
                    // Android may already hold the HID connection.
                    status("No GC8 advertisement; trying paired device again.")
                    tryBondedFirst = true
                    retry(20000L)
                }
            }, 12000)
        } catch (e: Exception) {
            scanning = false
            status("Bluetooth scan unavailable: " + e.javaClass.simpleName)
            retry(30000L)
        }
    }

    private fun startGattConnection(device: BluetoothDevice, useAutoConnect: Boolean): Boolean {
        connecting = true
        bondedAttempt = useAutoConnect
        try {
            gatt?.close()
            val connection = device.connectGatt(
                activity, useAutoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE
            )
            gatt = connection
            if (connection == null) {
                connecting = false
                return false
            }
            handler.postDelayed({
                if (autoReconnect && connecting && gatt === connection) {
                    connecting = false
                    try { connection.disconnect(); connection.close() } catch (_: SecurityException) {}
                    gatt = null
                    status(if (useAutoConnect) "Paired connection timed out; scanning…"
                           else "Waveshare connection timed out; retrying.")
                    retry(if (useAutoConnect) 1000L else 5000L)
                }
            }, if (useAutoConnect) 12000L else 15000L)
            return true
        } catch (e: Exception) {
            connecting = false
            gatt = null
            status("Connect failed: ${e.javaClass.simpleName}")
            return false
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            stopScan()
            bondedAttempt = false
            status("Found GC8 service/device; connecting…")
            if (!startGattConnection(result.device, false)) retry()
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            status("Scan failed: $errorCode")
            tryBondedFirst = true
            retry()
        }
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

    // Text-only guidance from Google Maps when it provides no bearing/distance,
    // e.g. "Exit the parking lot" or "Destination on the right".
    // This is NOT confirmation that the driver has arrived.
    private fun sendNavigationInfo(title: String, hint: String) {
        handler.post {
            fun safe(s: String, max: Int): String =
                s.replace("|", " ").filter { it.code in 32..126 }.trim().take(max)
            val heading = safe(title, 31)
            if (heading.isEmpty()) return@post
            val detail = safe(hint, 38)
            sendPayload("GC8|1|INFO|$heading|$detail", false)
        }
    }

    fun connectedDisplayAddress(): String? =
        if (writable == null) null
        else try { gatt?.device?.address } catch (_: SecurityException) { null }

    fun garageBridgeStatus(): String {
        val permission = if (routineBridge.canPost()) "notifications allowed"
                         else "notifications blocked"
        val armed = if (routineBridge.isArmed()) "ENABLED" else "DISABLED"
        return "Garage button: $armed ($permission)"
    }

    fun armGarageRoutine(): String {
        val address = connectedDisplayAddress()
            ?: return "Connect the Waveshare first, then enable garage button."
        return routineBridge.arm(address).message
    }

    fun disarmGarageRoutine(): String = routineBridge.disarm().message

    fun garageNotificationTriggerText(): String = routineBridge.notificationTriggerText()
    fun setGarageNotificationTriggerText(value: String): GarageRoutineTrigger.Result =
        routineBridge.setNotificationTriggerText(value)

    fun sendTestNavigation() {
        sendPayload("GC8|1|RIGHT|250 m|TEST_ROAD|TEST_DESTINATION|DEMO:14.7", true)
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
        if (!isTest && message == lastSent && message != "GC8|1|START" &&
            !message.startsWith("GC8|1|GARAGE|RESULT|")) {
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

        val bleAddress = try { gatt?.device?.address } catch (_: SecurityException) { null }
        val result = routineBridge.request(bleAddress)
        DriveLog.record(activity, "GARAGE_ROUTINE",
            if (result.sent) "Notification posted" else "Blocked: ${result.message}")
        status(result.message)
        // A successful RESULT means Android POSTED the keyword notification.
        // It does not prove Modes & Routines ran, SmartThings acted, or that
        // the door reached a new position.
        sendPayload(
            if (result.sent) "GC8|1|GARAGE|RESULT|SENT"
            else if (!routineBridge.isArmed()) "GC8|1|GARAGE|RESULT|NOT_READY"
            else "GC8|1|GARAGE|RESULT|FAILED",
            false
        )
    }

    private fun handleGarageEvent(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
                                  bytes: ByteArray) {
        if (g !== gatt || characteristic.uuid != GARAGE_EVENT_UUID) return
        when (bytes.toString(Charsets.US_ASCII)) {
            "GC8|1|GARAGE|PRESS" -> {
                DriveLog.record(activity, "GARAGE_EVENT", "Upper PWR over BLE")
                garageButtonPressed()
            }
            else -> DriveLog.record(activity, "BUTTON_IGNORED", "Unknown BLE button event")
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
                        finished?.startsWith("GC8|1|GARAGE|RESULT|") == true -> "Garage signal feedback"
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
            if (g !== gatt || !autoReconnect) {
                try { g.close() } catch (_: SecurityException) {}
                return
            }
            connecting = false
            if (statusCode == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                retryCount = 0
                handler.removeCallbacks(retryTask)
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
                val hadBondedAttempt = bondedAttempt
                bondedAttempt = false
                if (hadBondedAttempt)
                    DriveLog.record(activity, "BLE_BONDED_FALLBACK", "Paired GATT failed; scanning")
                try { g.close() } catch (_: SecurityException) {}
                if (g === gatt) gatt = null
                if (!hadBondedAttempt) tryBondedFirst = true
                if (autoReconnect) {
                    retryCount = (retryCount + 1).coerceAtMost(6)
                    retry(if (hadBondedAttempt) 1000L else if (retryCount < 2) 5000L else 20000L)
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
            if (writable == null) {
                status("Missing GC8 BLE service; reconnecting.")
                try { g.disconnect() } catch (_: SecurityException) {}
                return
            }
            tryBondedFirst = true
            // Pin identity only AFTER verifying the private GC8 GATT service.
            try {
                val addr = g.device.address
                if (BluetoothAdapter.checkBluetoothAddress(addr))
                    blePrefs.edit().putString(knownAddressKey, addr).apply()
            } catch (_: SecurityException) {}
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
        connecting = false
        handler.removeCallbacks(retryTask)
        handler.removeCallbacks(clockSync)
        if (activeClient === this) activeClient = null
        stopScan()
        try { gatt?.disconnect(); gatt?.close() } catch (_: SecurityException) {}
        gatt = null
        writable = null
        garageEvents = null
        latestQueued = null
        linkInitialized = false
    }
}
