package io.toyboxracing.gc8companion

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import java.util.UUID

/** Connects only to the GC8 service UUID; no audio profile interference. */
class GaugeBleClient(private val activity: Activity, private val report: (String) -> Unit) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("a8e31c40-6b8f-4b67-a4f0-4a0b75000001")
        val WRITE_UUID: UUID = UUID.fromString("a8e31c40-6b8f-4b67-a4f0-4a0b75000002")
    }
    private val handler = Handler(Looper.getMainLooper())
    private val manager = activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    @Volatile private var writable: BluetoothGattCharacteristic? = null
    @Volatile private var writePending = false
    private fun status(value: String) = activity.runOnUiThread { report(value) }

    fun connect() {
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
    fun sendTestNavigation() {
        val connection = gatt
        val characteristic = writable
        if (connection == null || characteristic == null) {
            status("Connect to GC8 display before sending.")
            return
        }
        if (writePending) {
            status("Previous test write is still pending.")
            return
        }
        val payload = "GC8|1|RIGHT|250 m|Main St|Apex Motoring|DEMO:14.7".toByteArray(Charsets.US_ASCII)
        writePending = true
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                val result = connection.writeCharacteristic(
                    characteristic, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
                if (result != BluetoothStatusCodes.SUCCESS) {
                    writePending = false
                    status("BLE write could not start (code $result).")
                } else status("Sending test navigation + DEMO AFR 14.7…")
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
                } else status("Sending test navigation…")
            }
        } catch (_: SecurityException) {
            writePending = false
            status("Nearby devices permission missing.")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, statusCode: Int) {
            writePending = false
            if (characteristic.uuid == WRITE_UUID) {
                status(if (statusCode == BluetoothGatt.GATT_SUCCESS)
                    "Test navigation + DEMO AFR accepted by BLE GATT; check Waveshare screen."
                    else "Test navigation write failed (status $statusCode).")
            }
        }
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            if (statusCode == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                status("Connected; discovering services…")
                try {
                    if (!g.requestMtu(185)) g.discoverServices()
                } catch (_: SecurityException) { status("Permission missing.") }
            } else {
                writable = null
                writePending = false
                status("Disconnected (status $statusCode)")
                try { g.close() } catch (_: SecurityException) {}
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, statusCode: Int) {
            status("BLE MTU: $mtu; discovering services…")
            try { g.discoverServices() } catch (_: SecurityException) { status("Permission missing.") }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            writable = g.getService(SERVICE_UUID)?.getCharacteristic(WRITE_UUID)
            status(if (writable != null) "GC8 BLE connected; write characteristic ready." else "Connected but navigation characteristic missing.")
        }
    }
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { manager.adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: SecurityException) {}
    }
    fun close() {
        stopScan()
        try { gatt?.disconnect(); gatt?.close() } catch (_: SecurityException) {}
        gatt = null
    }
}
