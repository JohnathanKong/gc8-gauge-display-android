package io.toyboxracing.gc8companion

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import android.widget.EditText
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.text.method.ScrollingMovementMethod

class MainActivity : Activity() {
    private val exportRequestCode = 7402
    private lateinit var output: TextView
    private lateinit var ble: GaugeBleClient
    private val updates = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        root.addView(TextView(this).apply {
            text = "GC8 Companion — Maps diagnostics"
            textSize = 23f
        })
        root.addView(TextView(this).apply {
            text = "Search for a destination or use your phone's voice assistant, then tap Start in Google Maps. Return here to inspect the most recent Google Maps notification. This data stays on your phone unless you copy it."
            textSize = 15f
        })
        val destination = EditText(this).apply {
            hint = "Destination (address or place)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
        }
        root.addView(destination)
        root.addView(Button(this).apply {
            text = "Find destination in Google Maps"
            setOnClickListener {
                val query = destination.text.toString().trim()
                if (query.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Enter a destination first.", Toast.LENGTH_SHORT).show()
                } else {
                    getSharedPreferences("gc8_navigation", MODE_PRIVATE)
                        .edit().putString("destination", query).apply()
                    val uri = Uri.parse("geo:0,0?q=" + Uri.encode(query))
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage("com.google.android.apps.maps")
                    }
                    try {
                        startActivity(intent)
                    } catch (e: android.content.ActivityNotFoundException) {
                        Toast.makeText(this@MainActivity, "Google Maps is not available.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Ask voice assistant"
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VOICE_COMMAND))
                } catch (e: android.content.ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity, "No voice assistant supports this shortcut on this phone.", Toast.LENGTH_LONG).show()
                } catch (e: SecurityException) {
                    Toast.makeText(this@MainActivity, "Voice assistant launch was blocked.", Toast.LENGTH_LONG).show()
                }
            }
        })
        val bleStatus = TextView(this).apply { text = "BLE: not connected"; textSize = 16f }
        ble = GaugeBleClient(this) { bleStatus.text = "BLE: $it" }
        root.addView(Button(this).apply {
            text = "Connect to GC8 display (BLE)"
            setOnClickListener { ble.connect() }
        })
        root.addView(bleStatus)
        root.addView(Button(this).apply {
            text = "Send test navigation to Waveshare"
            setOnClickListener { ble.sendTestNavigation() }
        })
        root.addView(Button(this).apply {
            text = "Notification access settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "Refresh captured data"
            setOnClickListener { refresh() }
        })
        root.addView(Button(this).apply {
            text = "Copy debug data"
            setOnClickListener {
                val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                manager.setPrimaryClip(ClipData.newPlainText("GC8 Maps debug", MapsCaptureStore.read(this@MainActivity)))
                Toast.makeText(this@MainActivity, "Copied. Review for private addresses before sharing.", Toast.LENGTH_LONG).show()
            }
        })
        root.addView(Button(this).apply {
            text = "Save drive diagnostics to file"
            setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TITLE, "gc8_drive_log.txt")
                    }
                    startActivityForResult(intent, exportRequestCode)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity,
                        "File picker unavailable: ${e.javaClass.simpleName}",
                        Toast.LENGTH_LONG).show()
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Clear drive diagnostics"
            setOnClickListener {
                DriveLog.clear(this@MainActivity)
                Toast.makeText(this@MainActivity, "Drive log cleared.", Toast.LENGTH_SHORT).show()
            }
        })
        output = TextView(this).apply {
            textSize = 16f
            setTextIsSelectable(true)
        }
        root.addView(ScrollView(this).apply { addView(output) })
        setContentView(root)
        refresh()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != exportRequestCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val file = DriveLog.export(this)
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: throw java.io.IOException("Cannot open destination")
            Toast.makeText(this, "Drive log saved. Attach gc8_drive_log.txt in ChatGPT.",
                Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        if (::ble.isInitialized) ble.close()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (::output.isInitialized) output.text = MapsCaptureStore.read(this)
    }
}
