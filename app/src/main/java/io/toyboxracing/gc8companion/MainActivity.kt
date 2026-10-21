package io.toyboxracing.gc8companion

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 64, 40, 32)
        }
        root.addView(TextView(this).apply {
            text = "GC8 Companion\nGoogle Maps notification prototype"
            textSize = 23f
        })
        root.addView(TextView(this).apply {
            text = "This first build tests whether Android exposes Google Maps navigation instructions through notifications. No route data is uploaded or sent to the gauge yet."
            textSize = 16f
        })
        root.addView(Button(this).apply {
            text = "Open notification access settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        })
        root.addView(TextView(this).apply {
            text = "Enable GC8 Companion, then start Google Maps navigation. Use Android logcat filtered by GC8Maps to inspect what the phone actually exposes. Only Google Maps notification fields are logged; do not share logs containing private destinations."
        })
        setContentView(root)
    }
}
