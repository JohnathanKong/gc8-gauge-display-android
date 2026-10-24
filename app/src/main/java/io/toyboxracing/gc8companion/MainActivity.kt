package io.toyboxracing.gc8companion

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Native, dependency-light presentation of the EXISTING companion functions.
 *
 * Four pages: Home (BLE), Navigation (Google Maps notifications), Garage
 * (opt-in routine bridge), Logs (existing local diagnostics). No new device
 * controls, search, ECU readings, SmartThings/Meross API or background service.
 */
class MainActivity : Activity() {
    private val exportRequestCode = 7402
    private val chooseBootAppRequestCode = 7403
    private val bg = Color.rgb(5, 12, 23)
    private val panel = Color.rgb(13, 29, 50)
    private val text = Color.rgb(240, 247, 255)
    private val muted = Color.rgb(166, 190, 220)
    private val blue = Color.rgb(39, 154, 255)
    private val cyan = Color.rgb(84, 213, 255)
    private val green = Color.rgb(66, 216, 156)
    private val border = Color.rgb(37, 76, 115)

    // The foreground service exclusively owns the BLE client. The Activity is UI only.
    private val ble: GaugeBleClient? get() = Gc8ConnectionService.instance?.client
    private val garagePrefs by lazy { GarageRoutineTrigger(this) }
    private var pendingServiceStart = false
    private val bridgeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            lastBleMessage = intent?.getStringExtra(Gc8ConnectionService.EXTRA_STATUS)
                ?: Gc8ConnectionService.latestStatus
            updateLiveStatuses()
        }
    }
    private lateinit var page: LinearLayout
    private lateinit var scroller: ScrollView
    private lateinit var titleText: TextView
    private lateinit var navbar: LinearLayout
    private var currentTab = 0
    private var lastBleMessage = "Not connected"
    private var bleStateText: TextView? = null
    private var garageStateText: TextView? = null
    private var logPreview: TextView? = null
    private var logSummary: TextView? = null
    private var garageInputDraft: String? = null

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + 0.5f).toInt()

    private fun label(value: String, size: Float = 15f, color: Int = text, bold: Boolean = false): TextView =
        TextView(this).apply {
            setText(value)
            setTextColor(color)
            textSize = size
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif",
                if (bold) Typeface.BOLD else Typeface.NORMAL)
            includeFontPadding = true
            setLineSpacing(dp(2).toFloat(), 1f)
        }

    private fun shape(colors: IntArray, radius: Int = 18, edge: Int = border): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), edge)
        }

    private fun stack(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun put(parent: LinearLayout, child: View, gap: Int = 12) {
        parent.addView(child, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(gap) })
    }

    private fun card(): LinearLayout = stack().apply {
        background = shape(intArrayOf(Color.rgb(18, 38, 64), panel, Color.rgb(8, 20, 36)))
        setPadding(dp(16), dp(16), dp(16), dp(16))
        elevation = dp(2).toFloat()
    }

    private fun action(name: String, primary: Boolean = true, block: () -> Unit): TextView =
        label(name, 15f, if (primary) Color.WHITE else cyan, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = if (primary) shape(
                intArrayOf(Color.rgb(34, 169, 255), Color.rgb(6, 97, 236)),
                14, Color.rgb(99, 207, 255)
            ) else shape(
                intArrayOf(Color.rgb(13, 43, 77), Color.rgb(7, 28, 51)),
                14, Color.rgb(35, 135, 223)
            )
            isClickable = true
            isFocusable = true
            elevation = dp(2).toFloat()
            setOnClickListener { block() }
        }

    private fun heading(name: String, description: String, symbol: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val icon = FrameLayout(this@MainActivity).apply {
                background = shape(intArrayOf(Color.rgb(16, 65, 111), Color.rgb(4, 24, 47)), 12, blue)
                addView(Glyph(this@MainActivity, symbol, cyan), FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
            }
            addView(icon, LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(13) })
            val column = stack()
            put(column, label(name, 17f, text, true), 3)
            put(column, label(description, 13f, muted), 0)
            addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Preserve legacy app preference cleanup without requiring Accessibility.
        getSharedPreferences("gc8_accessibility_diagnostics", MODE_PRIVATE)
            .edit().clear().apply()
        getSharedPreferences("gc8_navigation", MODE_PRIVATE)
            .edit().remove("destination").apply()

        // Target SDK 35+ draws edge-to-edge. The root manages safe insets.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        lastBleMessage = Gc8ConnectionService.latestStatus

        val root = stack().apply { setBackgroundColor(bg) }
        // Keep top controls below the clock/camera cutout and bottom tabs above
        // the phone's navigation buttons. Also protect inputs from the keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, maxOf(safe.bottom, keyboard.bottom))
            insets
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(15), dp(18), dp(11))
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.gc8_companion_bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "GC8 Companion"
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        titleText = label("GC8 Companion", 20f, text, true)
        header.addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Glyph(this, "settings", muted).apply {
            contentDescription = "Diagnostics and tools"
            isClickable = true
            isFocusable = true
            setOnClickListener { showTab(3) }
        }, LinearLayout.LayoutParams(dp(33), dp(33)))
        root.addView(header)

        scroller = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isVerticalScrollBarEnabled = false
        }
        page = stack().apply { setPadding(dp(18), dp(5), dp(18), dp(22)) }
        scroller.addView(page)
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))

        navbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = shape(intArrayOf(Color.rgb(13, 30, 53), Color.rgb(5, 15, 29)), 0,
                Color.rgb(16, 49, 85))
            setPadding(dp(8), dp(4), dp(8), dp(5))
        }
        root.addView(navbar, LinearLayout.LayoutParams(-1, dp(72)))
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        currentTab = savedInstanceState?.getInt("tab") ?: 0
        showTab(currentTab)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", currentTab)
        super.onSaveInstanceState(outState)
    }

    private fun showTab(tab: Int) {
        currentTab = tab.coerceIn(0, 3)
        bleStateText = null
        garageStateText = null
        logPreview = null
        logSummary = null
        page.removeAllViews()
        titleText.text = arrayOf("GC8 Companion", "Navigation", "Garage", "Diagnostics")[currentTab]
        when (currentTab) {
            0 -> home()
            1 -> navigation()
            2 -> garage()
            3 -> logs()
        }
        buildTabs()
        scroller.scrollTo(0, 0)
    }

    private fun buildTabs() {
        navbar.removeAllViews()
        val names = arrayOf("Home", "Navigation", "Garage", "Logs")
        val symbols = arrayOf("home", "nav", "garage", "logs")
        for (i in names.indices) {
            val active = i == currentTab
            val tile = stack().apply {
                gravity = Gravity.CENTER
                setPadding(dp(1), dp(6), dp(1), dp(5))
                background = if (active) shape(
                    intArrayOf(Color.rgb(12, 79, 151), Color.rgb(8, 35, 78)),
                    16, Color.rgb(22, 106, 214)
                ) else null
                isClickable = true
                isFocusable = true
                contentDescription = names[i] + " tab"
                setOnClickListener { if (currentTab != i) showTab(i) }
            }
            tile.addView(Glyph(this, symbols[i], if (active) cyan else muted),
                LinearLayout.LayoutParams(dp(25), dp(25)))
            val textView = label(names[i], 11f, if (active) cyan else muted, active).apply {
                gravity = Gravity.CENTER
            }
            tile.addView(textView)
            navbar.addView(tile, LinearLayout.LayoutParams(0, -1, 1f).apply {
                leftMargin = dp(3); rightMargin = dp(3)
            })
        }
    }

    private fun hero(top: String, main: String, bottom: String) {
        val frame = FrameLayout(this).apply {
            background = shape(intArrayOf(Color.rgb(9, 30, 56), Color.rgb(4, 17, 31)), 22, border)
            clipToOutline = true
        }
        frame.addView(NightRoad(this), FrameLayout.LayoutParams(-1, -1))
        val content = stack().apply {
            gravity = Gravity.BOTTOM
            setPadding(dp(19), dp(12), dp(19), dp(19))
        }
        put(content, label(top, 12f, cyan, true).apply { letterSpacing = 0.2f }, 9)
        put(content, label(main, 29f, text, true).apply {
            setShadowLayer(dp(5).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
        }, 6)
        put(content, label(bottom, 13f, Color.rgb(213, 229, 251)), 0)
        frame.addView(content, FrameLayout.LayoutParams(-1, -1))
        page.addView(frame, LinearLayout.LayoutParams(-1, dp(180)).apply {
            bottomMargin = dp(16)
        })
    }

    private fun note(value: String) {
        put(page, label(value, 13f, muted).apply { setPadding(dp(4), dp(0), dp(4), dp(0)) }, 15)
    }

    private fun isConnected(): Boolean = ble?.connectedDisplayAddress() != null

    private fun startBridge() {
        if (!Gc8ConnectionService.hasBlePermission(this)) {
            if (Build.VERSION.SDK_INT >= 31) {
                pendingServiceStart = true
                requestPermissions(
                    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
                    7301
                )
            }
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingServiceStart = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7302)
            return
        }
        launchBridgeNow()
    }

    private fun launchBridgeNow() {
        pendingServiceStart = false
        try {
            Gc8ConnectionService.launch(this)
            lastBleMessage = "Starting background connection…"
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFinishing && currentTab == 0) showTab(0)
            }, 450L)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot start service: " + e.javaClass.simpleName,
                Toast.LENGTH_LONG).show()
        }
    }

    private fun stopBridge() {
        pendingServiceStart = false
        if (Gc8ConnectionService.instance == null) return
        startService(Intent(this, Gc8ConnectionService::class.java)
            .setAction(Gc8ConnectionService.ACTION_STOP))
        lastBleMessage = "Service stopping"
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && currentTab == 0) showTab(0)
        }, 350L)
    }


    private fun home() {
        hero("GC8  /  COMPANION", "Made for your drive", "Display  •  Google Maps  •  Garage")
        val service = card()
        put(service, heading("Background service", "Keeps BLE connected when this screen closes", "ble"), 12)
        put(service, label(
            if (Gc8ConnectionService.instance != null) "Running — " + Gc8ConnectionService.latestStatus
            else "Stopped",
            14f, if (Gc8ConnectionService.instance != null) green else muted, true
        ), 12)
        put(service, action("Start service") { startBridge() }, 9)
        put(service, action("Stop service", false) { stopBridge() }, 10)
        put(service, label("One-time permissions stay approved. Starting remembers your choice and attempts to resume after a phone restart; Stop turns automatic restart off.",
            12f, muted), 0)
        put(page, service, 14)

        val device = card()
        put(device, heading("Waveshare display", "Bluetooth Low Energy connection", "ble"), 13)
        bleStateText = label(if (isConnected()) "Connected to Waveshare" else lastBleMessage,
            15f, if (isConnected()) green else muted, true)
        put(device, bleStateText!!, 12)
        put(device, action("Connect to Waveshare") {
            if (Gc8ConnectionService.instance == null) startBridge()
            else ble?.connect()
        }, 0)
        put(page, device, 14)

        val boot = card()
        put(boot, heading("Lower BOOT button", "Choose an installed app to open", "app"), 12)
        put(boot, label("Selected: " + BootAppLauncher.chosenLabel(this), 15f, muted, true), 11)
        put(boot, action("Choose an app") { pickBootApp() }, 9)
        put(boot, action("Test selected app", false) {
            val result = BootAppLauncher.onPhysicalPress(this)
            if (!result.handled) {
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            }
        }, 9)
        put(boot, action("Clear button assignment", false) {
            BootAppLauncher.clear(this)
            showTab(0)
        }, 10)
        put(boot, label(
            "Short-press BOOT after the Waveshare is fully running. " +
            "When GC8 Companion is visible, it can open the chosen app directly. " +
            "With the phone locked or the app in the background, tap the GC8 notification to open it. " +
            "Selecting Google Home opens that app; it cannot guarantee instant voice listening.",
            12f, muted
        ), 0)
        put(page, boot, 14)

        val nav = card()
        put(nav, heading("Google Maps", "Turn instructions from phone notifications", "nav"), 12)
        put(nav, label(if (listenerAllowed()) "Notification access granted" else "Notification access not enabled",
            14f, if (listenerAllowed()) green else muted), 12)
        put(nav, action("Navigation settings", false) { showTab(1) }, 0)
        put(page, nav, 14)

        val garageCard = card()
        put(garageCard, heading("Garage bridge", "Samsung Modes and Routines integration", "garage"), 12)
        put(garageCard, label(garageStatusLabel(), 14f, muted), 12)
        put(garageCard, action("Garage settings", false) { showTab(2) }, 0)
        put(page, garageCard, 13)
        note("GC8 Companion displays real connection and permission status. The garage notification is a request, not confirmation that the door moved.")
    }

    private fun navigation() {
        hero("NAVIGATION", "Google Maps", "Turn guidance through notification access")
        val access = card()
        put(access, heading("Notification access", "Read Google Maps turn notifications", "bell"), 13)
        put(access, label(if (listenerAllowed()) "Granted" else "Not enabled", 17f,
            if (listenerAllowed()) green else muted, true), 12)
        put(access, action("Open notification access settings") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }, 0)
        put(page, access, 14)

        val instructions = card()
        put(instructions, heading("Navigation bridge", "Routes begin in Google Maps", "nav"), 11)
        put(instructions, label("Start navigation in Google Maps. GC8 Companion passes supported turn notifications to the connected Waveshare; it does not provide its own map or destination search.",
            14f, muted), 12)
        put(instructions, action("Open Google Maps") { openMaps() }, 10)
        put(instructions, action("Send test navigation to Waveshare", false) {
            if (ble == null) Toast.makeText(this, "Start service first.", Toast.LENGTH_SHORT).show()
            else ble?.sendTestNavigation()
        }, 0)
        put(page, instructions, 14)

        val latest = card()
        put(latest, heading("Latest captured notification", "Diagnostic snapshot, not live turn data", "logs"), 12)
        put(latest, label(MapsCaptureStore.read(this).take(720), 12.5f, muted).apply {
            setTextIsSelectable(true)
        }, 12)
        put(latest, action("Full capture and logs", false) { showTab(3) }, 0)
        put(page, latest)
    }

    private fun garage() {
        hero("GARAGE  /  ROUTINE BRIDGE", "Garage setup", "Waveshare button  →  Samsung notification")
        val state = card()
        put(state, heading("Garage button", "Opt-in physical PWR trigger", "garage"), 12)
        garageStateText = label(garageStatusLabel(), 15f, muted, true)
        put(state, garageStateText!!, 12)
        put(state, label("A short upper PWR press posts an Android notification. Samsung Modes and Routines must route that notification to your SmartThings virtual switch.",
            13f, muted), 0)
        put(page, state, 14)

        val notifications = card()
        put(notifications, heading("GC8 notification permission", "Required to post the garage trigger", "bell"), 11)
        put(notifications, label(if (garagePostingAllowed()) "Allowed" else "Not allowed",
            15f, if (garagePostingAllowed()) green else muted, true), 12)
        put(notifications, action("Allow GC8 notifications", false) { manageGaragePermission() }, 0)
        put(page, notifications, 14)

        val trigger = card()
        put(trigger, heading("Notification trigger text", "Match the Samsung routine's IF keyword", "logs"), 12)
        val field = EditText(this).apply {
            hint = "Notification keyword"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setTextColor(this@MainActivity.text)
            setHintTextColor(muted)
            textSize = 16f
            background = shape(intArrayOf(Color.rgb(9, 28, 49), Color.rgb(6, 21, 38)), 12, border)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setText(garageInputDraft ?: garagePrefs.notificationTriggerText())
        }
        put(trigger, field, 6)
        put(trigger, label("This is notification text, not a SmartThings device picker. Disable the garage button before changing it.",
            12f, muted), 12)
        put(trigger, action("Save trigger text", false) {
            val result = garagePrefs.setNotificationTriggerText(field.text.toString())
            if (result.sent) {
                garageInputDraft = garagePrefs.notificationTriggerText()
                field.setText(garageInputDraft)
            }
            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            updateLiveStatuses()
        }, 0)
        put(page, trigger, 14)

        val enable = card()
        put(enable, heading("Button authorization", "Only the connected Waveshare can request", "ble"), 12)
        put(enable, action("Enable garage button") {
            AlertDialog.Builder(this)
                .setTitle("Enable garage-door button?")
                .setMessage("Only enable after checking the Samsung routine. A short upper PWR tap may ACTUATE the garage door. Keep the door and entrance in view during the first live test. BLE address matching is not cryptographic pairing.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Enable") { _, _ ->
                    val result = ble?.armGarageRoutine()
                        ?: "Start service and connect the Waveshare first."
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                    garageInputDraft = field.text.toString()
                    showTab(2)
                }.show()
        }, 9)
        put(enable, action("Disable garage button", false) {
            val result = garagePrefs.disarm().message
            Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
            garageInputDraft = field.text.toString()
            showTab(2)
        }, 0)
        put(page, enable, 13)
        note("This app does not know the garage's open/closed state or directly contact SmartThings or Meross. Your existing SmartThings routines handle door state and switch reset.")
    }

    private fun logs() {
        hero("DIAGNOSTICS", "Drive logs", "Existing local tools and captured notifications")
        val save = card()
        put(save, heading("Drive diagnostics", "Save event history to a local file", "logs"), 11)
        logSummary = label(DriveLog.summary(this), 13f, muted)
        put(save, logSummary!!, 12)
        put(save, action("Save drive diagnostics to file") { exportDiagnostics() }, 10)
        put(save, action("Clear drive diagnostics", false) {
            AlertDialog.Builder(this)
                .setTitle("Clear drive diagnostics?")
                .setMessage("This deletes the local event journal. Saved export files are not affected.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    DriveLog.clear(this)
                    updateCapture()
                    Toast.makeText(this, "Drive log cleared.", Toast.LENGTH_SHORT).show()
                }.show()
        }, 0)
        put(page, save, 14)

        val capture = card()
        put(capture, heading("Google Maps capture", "Latest notification and bridge diagnostic text", "nav"), 12)
        logPreview = label(MapsCaptureStore.read(this), 12.5f, muted).apply {
            setTextIsSelectable(true)
        }
        put(capture, logPreview!!, 12)
        put(capture, action("Refresh captured data", false) { updateCapture() }, 10)
        put(capture, action("Copy debug data", false) {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("GC8 Maps debug", MapsCaptureStore.read(this)))
            Toast.makeText(this, "Copied. Review private addresses before sharing.", Toast.LENGTH_LONG).show()
        }, 0)
        put(page, capture, 14)

        val tools = card()
        put(tools, heading("Other existing tools", "Phone settings and shortcuts", "settings"), 12)
        put(tools, action("Notification access settings", false) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }, 0)
        put(page, tools, 12)
        note("Diagnostic records stay on this phone until you explicitly export or copy them.")
    }

    private fun garageStatusLabel(): String {
        val permission = if (garagePrefs.canPost()) "notifications allowed" else "notifications blocked"
        return "Garage button: " +
            if (garagePrefs.isArmed()) "ENABLED ($permission)"
            else "DISABLED ($permission)"
    }

    private fun listenerAllowed(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabled?.split(':')?.any {
            android.content.ComponentName.unflattenFromString(it)?.packageName == packageName
        } == true
    }

    private fun garagePostingAllowed(): Boolean {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        return manager.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }

    private fun manageGaragePermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7302)
        } else {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            })
        }
    }

    private fun openMaps() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")).apply {
                setPackage("com.google.android.apps.maps")
            })
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Google Maps is not available.", Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "Unable to launch Google Maps.", Toast.LENGTH_LONG).show()
        }
    }

    private fun pickBootApp() {
        // Ask Android to pick a MAIN/LAUNCHER activity, not a package name
        // typed by the user. This avoids app hardcoding and does not launch
        // the selected app during configuration.
        try {
            val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val picker = Intent(Intent.ACTION_PICK_ACTIVITY).apply {
                putExtra(Intent.EXTRA_INTENT, launchable)
                putExtra(Intent.EXTRA_TITLE, "BOOT button: choose an app")
            }
            startActivityForResult(picker, chooseBootAppRequestCode)
        } catch (e: Exception) {
            Toast.makeText(this, "App picker unavailable on this phone.", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportDiagnostics() {
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "gc8_drive_log.txt")
            }, exportRequestCode)
        } catch (_: Exception) {
            Toast.makeText(this, "File picker unavailable.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Android Activity result hook retained for the existing file export workflow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == chooseBootAppRequestCode) {
            // The system PICK_ACTIVITY result is an Intent identifying the
            // actual launcher component chosen by the owner.
            val chosen = data?.component
                ?: data?.resolveActivity(packageManager)
            if (chosen == null) {
                Toast.makeText(this, "No launchable app selected.", Toast.LENGTH_LONG).show()
                return
            }
            val label = try {
                packageManager.getActivityInfo(chosen, 0).loadLabel(packageManager).toString()
            } catch (_: Exception) {
                chosen.packageName
            }
            BootAppLauncher.save(this, chosen, label)
            Toast.makeText(this, "BOOT assigned to " + label, Toast.LENGTH_SHORT).show()
            if (currentTab == 0) showTab(0)
            return
        }
        if (requestCode != exportRequestCode) return
        val uri = data?.data ?: return
        try {
            val file = DriveLog.export(this)
            contentResolver.openOutputStream(uri, "w")?.use { destination ->
                file.inputStream().use { input -> input.copyTo(destination) }
            } ?: throw java.io.IOException("Cannot open destination")
            Toast.makeText(this, "Drive log saved. Attach gc8_drive_log.txt in ChatGPT.",
                Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateCapture() {
        logPreview?.text = MapsCaptureStore.read(this)
        logSummary?.text = DriveLog.summary(this)
    }

    private fun updateLiveStatuses() {
        bleStateText?.apply {
            text = if (isConnected()) "Connected to Waveshare" else lastBleMessage
            setTextColor(if (isConnected()) green else muted)
        }
        garageStateText?.text = garageStatusLabel()
    }

    override fun onResume() {
        super.onResume()
        BootAppLauncher.setActivityVisible(this)
        ContextCompat.registerReceiver(this, bridgeReceiver,
            IntentFilter(Gc8ConnectionService.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
        lastBleMessage = Gc8ConnectionService.latestStatus
        if (::page.isInitialized) showTab(currentTab)
    }

    override fun onPause() {
        BootAppLauncher.setActivityHidden(this)
        unregisterReceiver(bridgeReceiver)
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (pendingServiceStart && requestCode == 7301) {
            if (Gc8ConnectionService.hasBlePermission(this)) startBridge()
            else {
                pendingServiceStart = false
                Toast.makeText(this, "Nearby devices permission is needed to connect.",
                    Toast.LENGTH_LONG).show()
            }
        } else if (pendingServiceStart && requestCode == 7302) {
            // Android may deny notification permission. The service can still
            // forward navigation, but the garage routine will remain blocked.
            launchBridgeNow()
        }
        if ((requestCode == 7301 || requestCode == 7302) && ::page.isInitialized) {
            showTab(currentTab)
        }
    }

    override fun onDestroy() {
        // No BLE close here: foreground service owns the connection.
        super.onDestroy()
    }

    /** Decorative procedural night-road artwork; not a live navigation map. */
    private class NightRoad(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ridge = Path()
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            p.shader = LinearGradient(0f, 0f, w, h,
                intArrayOf(Color.rgb(5, 24, 49), Color.rgb(10, 55, 103), Color.rgb(3, 13, 29)),
                null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, p)
            p.shader = RadialGradient(w * 0.75f, h * 0.36f, w * 0.58f,
                intArrayOf(0x806BA9FA.toInt(), 0x193469AD, Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, p)
            p.shader = null
            p.color = Color.rgb(6, 23, 44)
            ridge.reset()
            ridge.moveTo(0f, h * 0.67f)
            ridge.lineTo(0f, h * 0.42f)
            ridge.lineTo(w * 0.13f, h * 0.54f)
            ridge.lineTo(w * 0.27f, h * 0.37f)
            ridge.lineTo(w * 0.45f, h * 0.57f)
            ridge.lineTo(w * 0.61f, h * 0.35f)
            ridge.lineTo(w * 0.75f, h * 0.55f)
            ridge.lineTo(w, h * 0.31f)
            ridge.lineTo(w, h)
            ridge.lineTo(0f, h)
            ridge.close()
            canvas.drawPath(ridge, p)
            p.color = Color.rgb(3, 12, 25)
            ridge.reset()
            ridge.moveTo(0f, h * 0.71f)
            ridge.lineTo(w * 0.20f, h * 0.58f)
            ridge.lineTo(w * 0.34f, h * 0.68f)
            ridge.lineTo(w * 0.56f, h * 0.48f)
            ridge.lineTo(w * 0.82f, h * 0.62f)
            ridge.lineTo(w, h * 0.47f)
            ridge.lineTo(w, h)
            ridge.lineTo(0f, h)
            ridge.close()
            canvas.drawPath(ridge, p)
            // Curving road ribbon, fading into the lower right of the artwork.
            ridge.reset()
            ridge.moveTo(w * 0.75f, h * 0.56f)
            ridge.cubicTo(w * 0.54f, h * 0.78f, w * 0.88f, h * 0.78f, w * 0.45f, h * 1.03f)
            ridge.lineTo(w * 0.84f, h * 1.03f)
            ridge.cubicTo(w * 1.10f, h * 0.79f, w * 0.70f, h * 0.73f, w * 0.79f, h * 0.56f)
            ridge.close()
            p.color = Color.rgb(18, 41, 65)
            canvas.drawPath(ridge, p)
            p.color = 0x7743B8FF
            p.strokeWidth = 2.4f * resources.displayMetrics.density
            p.style = Paint.Style.STROKE
            ridge.reset()
            ridge.moveTo(w * 0.75f, h * 0.56f)
            ridge.cubicTo(w * 0.54f, h * 0.78f, w * 0.88f, h * 0.78f, w * 0.45f, h * 1.03f)
            canvas.drawPath(ridge, p)
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(0f, h * 0.35f, 0f, h,
                intArrayOf(0x00000000, 0x66040E1D, 0xF906101F.toInt()),
                null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, p)
            p.shader = null
        }
    }

    /** Small custom line icons avoid adding dependencies or emoji-style glyphs. */
    private class Glyph(context: Context, private val symbol: String, private val tint: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private fun line(canvas: Canvas, a: Float, b: Float, c: Float, d: Float) =
            canvas.drawLine(a, b, c, d, paint)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val size = minOf(width, height).toFloat()
            if (size <= 0f) return
            canvas.save()
            canvas.translate((width - size) * 0.5f, (height - size) * 0.5f)
            canvas.scale(size / 24f, size / 24f)
            paint.color = tint
            paint.strokeWidth = 1.8f
            paint.style = Paint.Style.STROKE
            val path = Path()
            when (symbol) {
                "home" -> {
                    path.moveTo(3f, 11f); path.lineTo(12f, 3.5f); path.lineTo(21f, 11f)
                    canvas.drawPath(path, paint)
                    line(canvas, 5f, 10f, 5f, 21f)
                    line(canvas, 19f, 10f, 19f, 21f)
                    line(canvas, 5f, 21f, 19f, 21f)
                    line(canvas, 10f, 21f, 10f, 14f)
                    line(canvas, 14f, 21f, 14f, 14f)
                    line(canvas, 10f, 14f, 14f, 14f)
                }
                "nav" -> {
                    path.moveTo(21f, 3f); path.lineTo(3f, 11f)
                    path.lineTo(11f, 13f); path.lineTo(13f, 21f); path.close()
                    canvas.drawPath(path, paint)
                    line(canvas, 11f, 13f, 21f, 3f)
                }
                "garage" -> {
                    path.moveTo(3f, 9f); path.lineTo(12f, 3f); path.lineTo(21f, 9f)
                    canvas.drawPath(path, paint)
                    canvas.drawRect(5f, 9f, 19f, 21f, paint)
                    line(canvas, 8f, 14f, 16f, 14f)
                    line(canvas, 8f, 17f, 16f, 17f)
                    line(canvas, 8f, 20f, 16f, 20f)
                }
                "logs" -> {
                    canvas.drawRoundRect(5f, 3f, 19f, 21f, 2f, 2f, paint)
                    line(canvas, 8f, 9f, 16f, 9f)
                    line(canvas, 8f, 13f, 16f, 13f)
                    line(canvas, 8f, 17f, 14f, 17f)
                }
                "app" -> {
                    canvas.drawRoundRect(3f, 3f, 10.5f, 10.5f, 1.8f, 1.8f, paint)
                    canvas.drawRoundRect(13.5f, 3f, 21f, 10.5f, 1.8f, 1.8f, paint)
                    canvas.drawRoundRect(3f, 13.5f, 10.5f, 21f, 1.8f, 1.8f, paint)
                    canvas.drawRoundRect(13.5f, 13.5f, 21f, 21f, 1.8f, 1.8f, paint)
                }
                "ble" -> {
                    line(canvas, 12f, 2f, 12f, 22f)
                    line(canvas, 12f, 2f, 19f, 8f)
                    line(canvas, 19f, 8f, 5f, 18f)
                    line(canvas, 5f, 6f, 19f, 16f)
                    line(canvas, 19f, 16f, 12f, 22f)
                }
                "bell" -> {
                    path.moveTo(5f, 17f); path.lineTo(7f, 14f); path.lineTo(7f, 9f)
                    path.cubicTo(7f, 2f, 17f, 2f, 17f, 9f)
                    path.lineTo(17f, 14f); path.lineTo(19f, 17f); path.close()
                    canvas.drawPath(path, paint)
                    canvas.drawArc(10f, 17f, 14f, 21f, 5f, 170f, false, paint)
                }
                else -> {
                    canvas.drawCircle(12f, 12f, 7.5f, paint)
                    canvas.drawCircle(12f, 12f, 2.5f, paint)
                    for (i in 0..7) {
                        val angle = i * Math.PI / 4.0
                        val x = kotlin.math.cos(angle).toFloat()
                        val y = kotlin.math.sin(angle).toFloat()
                        line(canvas, 12f + x * 8f, 12f + y * 8f,
                            12f + x * 10.5f, 12f + y * 10.5f)
                    }
                }
            }
            canvas.restore()
        }
    }
}
