package io.toyboxracing.gc8companion

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.text.method.ScrollingMovementMethod

class MainActivity : Activity() {
    private lateinit var output: TextView
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
            text = "Start a route in Google Maps. Return here to inspect the most recent Google Maps notification. This data stays on your phone unless you copy it."
            textSize = 15f
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
                manager.setPrimaryClip(ClipData.newPlainText("GC8 Maps debug", MapsCaptureStore.read(this)))
                Toast.makeText(this@MainActivity, "Copied. Review for private addresses before sharing.", Toast.LENGTH_LONG).show()
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

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (::output.isInitialized) output.text = MapsCaptureStore.read(this)
    }
}
