package com.example.usbpad

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var link: HidLink
    private var adbLink: Link? = null
    private var btLink: BtLink? = null
    private var mode = "hid"
    private lateinit var prefs: SharedPreferences
    private lateinit var modeBtn: Button
    private lateinit var onBtn: Button
    private lateinit var offBtn: Button
    private lateinit var status: TextView
    private lateinit var keyView: KeyCaptureView
    private val handler = Handler(Looper.getMainLooper())
    private val activeMods = mutableSetOf<String>()
    private val modButtons = mutableMapOf<String, Button>()
    private var kbVisible = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun styled(b: Button, on: Boolean = false): Button {
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.stateListAnimator = null
        b.minHeight = 0
        b.minWidth = 0
        b.includeFontPadding = false
        b.setPadding(dp(4), 0, dp(4), 0)
        b.maxLines = 1
        // long labels (e.g. "Mode: HID (root)") shrink to fit instead of wrapping and getting cut
        b.setAutoSizeTextTypeUniformWithConfiguration(9, 14, 1, TypedValue.COMPLEX_UNIT_SP)
        b.background = Glass.button(this, b.tag as? IntArray, on)
        return b
    }

    /** Tinted (coloured) glass button. */
    private fun Button.acc(c: IntArray): Button {
        tag = c
        return styled(this)
    }

    private fun row(h: Int, vararg views: View): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            val lp = LinearLayout.LayoutParams(0, dp(h), 1f)
            lp.setMargins(dp(3), dp(3), dp(3), dp(3))
            r.addView(v, lp)
        }
        return r
    }

    private fun holdButton(label: String, name: String): Button {
        val b = styled(Button(this))
        b.text = label
        b.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    styled(b, true)
                    sendCmd("D $name")
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    styled(b, false)
                    sendCmd("U $name")
                }
            }
            true
        }
        return b
    }

    private fun keyButton(label: String, name: String, repeat: Boolean = false): Button {
        val b = styled(Button(this))
        b.text = label
        var task: Runnable? = null
        b.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    styled(b, true)
                    sendCmd("K tap $name")
                    if (repeat) {
                        val r = object : Runnable {
                            override fun run() {
                                sendCmd("K tap $name")
                                handler.postDelayed(this, 60)
                            }
                        }
                        task = r
                        handler.postDelayed(r, 400)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    styled(b, false)
                    val t = task
                    if (t != null) handler.removeCallbacks(t)
                    task = null
                }
            }
            true
        }
        return b
    }

    private fun modButton(label: String, name: String): Button {
        val b = styled(Button(this))
        b.text = label
        b.setOnClickListener {
            if (activeMods.remove(name)) {
                sendCmd("K up $name")
                styled(b, false)
            } else {
                activeMods.add(name)
                sendCmd("K down $name")
                styled(b, true)
            }
        }
        modButtons[name] = b
        return b
    }

    private fun sendCmd(line: String) {
        when (mode) {
            "hid" -> link.send(line)
            "bt" -> btLink?.send(line)
            else -> adbLink?.send(line)
        }
    }

    /** Back / Home / Recents of the OTHER device (the PC / phone this one is connected to). */
    private fun nav(name: String) {
        if (mode == "bt") {
            sendCmd("N $name")
            return
        }
        // USB / ADB modes: ordinary keyboard shortcuts
        when (name) {
            "back" -> { sendCmd("K down alt"); sendCmd("K tap left"); sendCmd("K up alt") }
            "home" -> sendCmd("K tap win")
            else -> { sendCmd("K down win"); sendCmd("K tap tab"); sendCmd("K up win") }
        }
    }

    private fun showAdbStatus(ok: Boolean) {
        val how = when (mode) {
            "lan" -> "tap \"PC IP\" and enter the PIN shown by UsbPadServer on the PC (same Wi-Fi)"
            "wifi" -> "Wireless debugging, run: python server.py wifi"
            else -> "USB debugging, run: python server.py usb"
        }
        status.text = if (ok) (if (mode == "lan") "● PC connected (Wi-Fi)" else "● ADB connected") else "○ waiting for PC ($how)"
        status.setTextColor(if (ok) 0xFF66BB6A.toInt() else 0xFFBBBBBB.toInt())
    }

    private fun btSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    private fun btPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf("android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_ADVERTISE")
        else emptyArray()

    private fun btGranted() = btPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun showBtStatus(text: String, ok: Boolean) {
        status.text = text
        status.setTextColor(if (ok) 0xFF66BB6A.toInt() else 0xFFBBBBBB.toInt())
    }

    /** Creates BtLink and registers the HID profile (needs the Bluetooth permissions first). */
    private fun startBt() {
        if (!btSupported()) { showBtStatus("Bluetooth HID needs Android 9+", false); return }
        if (!btGranted()) {
            val ask = if (Build.VERSION.SDK_INT >= 33) btPermissions() + "android.permission.POST_NOTIFICATIONS" else btPermissions()
            requestPermissions(ask, 77)
            showBtStatus("Allow the Bluetooth permission ...", false)
            return
        }
        val b = BtHolder.get(this)
        btLink = b
        BtHolder.listener = { text, ok -> runOnUiThread { if (mode == "bt") showBtStatus(text, ok) } }
        try { startForegroundService(Intent(this, BtService::class.java)) } catch (e: Exception) { }
        askBatteryExemption()
        if (b.started) {
            if (BtHolder.lastText.isNotEmpty()) showBtStatus(BtHolder.lastText, BtHolder.lastOk)
        } else {
            b.start()
        }
    }

    /** Once: ask Android not to kill the app in the background (Settings > Battery > Unrestricted). */
    private fun askBatteryExemption() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) return
            if (prefs.getBoolean("asked_battery", false)) return
            prefs.edit().putBoolean("asked_battery", true).apply()
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) { }
    }

    private fun stopBt() {
        BtHolder.listener = null
        btLink = null
        try {
            startService(Intent(this, BtService::class.java).setAction(BtService.ACTION_STOP))
        } catch (e: Exception) { BtHolder.stop() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 77 && mode == "bt") {
            if (btGranted()) startBt() else showBtStatus("Bluetooth permission denied", false)
        }
    }

    /** "Pair" button: make the phone discoverable so the PC can find and pair it. */
    private fun btPair() {
        if (btLink?.started != true) { startBt(); }
        try {
            val i = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            startActivity(i)
        } catch (e: Exception) {
            showBtStatus("Cannot open discoverable dialog: " + e.message, false)
        }
    }

    /** "Devices" button: pick an already paired PC and connect to it. */
    private fun btDevices() {
        val b = btLink
        if (b == null || !b.started) { startBt(); return }
        val list = b.bondedDevices()
        if (list.isEmpty()) {
            showBtStatus("No paired devices. Tap Pair, then add the phone from the PC.", false)
            return
        }
        val names = list.map {
            try { (it.name ?: "?") + "  " + it.address } catch (e: SecurityException) { it.address }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Connect to")
            .setItems(names) { _, which -> b.connectTo(list[which]) }
            .show()
    }

    private fun setMode(m: String) {
        mode = m
        prefs.edit().putString("mode", m).apply()
        val adb = m == "usb" || m == "wifi" || m == "lan"
        val lan = m == "lan"
        keyView.native = adb
        val bt = m == "bt"
        modeBtn.text = when (m) {
            "usb" -> "Mode: ADB (USB)"
            "wifi" -> "Mode: ADB (Wi-Fi)"
            "lan" -> "Mode: Wi-Fi (PC)"
            "bt" -> "Mode: Bluetooth"
            else -> "Mode: HID (root)"
        }
        onBtn.text = if (bt) "Pair" else if (lan) "PC IP" else "HID ON"
        offBtn.text = if (bt) "Devices" else "HID OFF"
        onBtn.visibility = if (adb && !lan) View.GONE else View.VISIBLE
        offBtn.visibility = if (adb) View.GONE else View.VISIBLE
        if (!bt && (btLink != null || BtHolder.link != null)) stopBt()
        if (bt) {
            link.stop()
            adbLink?.stop()
            adbLink = null
            showBtStatus("○ Starting Bluetooth ...", false)
            startBt()
        } else if (adb) {
            link.stop()
            adbLink?.stop()
            val l = if (lan) {
                Link(
                    prefs.getString("lan_ip", "") ?: "", prefs.getString("lan_pin", "") ?: "",
                    { ip -> prefs.edit().putString("lan_ip", ip).apply() }
                ) { ok ->
                    runOnUiThread { if (mode == "lan") showAdbStatus(ok) }
                }
            } else {
                Link { ok -> runOnUiThread { if (mode == "usb" || mode == "wifi") showAdbStatus(ok) } }
            }
            adbLink = l
            if (lan && (prefs.getString("lan_pin", "").isNullOrEmpty())) {
                showAdbStatus(false)
                lanDialog()
            } else {
                l.start()
                showAdbStatus(false)
            }
        } else {
            adbLink?.stop()
            adbLink = null
            status.text = "○ HID off. Tap HID ON, then plug the cable into the PC."
            status.setTextColor(0xFFBBBBBB.toInt())
        }
    }

    /** Wi-Fi (PC) mode: ask for the PC's IP and the 6-digit PIN that UsbPadServer shows in its tray menu. */
    private fun lanDialog() {
        fun field(hint: String, value: String, number: Boolean): EditText {
            val e = EditText(this)
            e.hint = hint
            e.setText(value)
            e.setSingleLine()
            if (number) e.inputType = android.text.InputType.TYPE_CLASS_PHONE
            return e
        }
        val ip = field("PC IP (optional, found automatically)", prefs.getString("lan_ip", "") ?: "", true)
        val pin = field("PIN (6 digits)", prefs.getString("lan_pin", "") ?: "", true)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(ip)
        box.addView(pin)
        AlertDialog.Builder(this)
            .setTitle("PC on the same Wi-Fi")
            .setView(box)
            .setPositiveButton("Connect") { _, _ ->
                prefs.edit().putString("lan_ip", ip.text.toString().trim())
                    .putString("lan_pin", pin.text.toString().trim()).apply()
                if (mode == "lan") setMode("lan")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun releaseAll() {
        sendCmd("R")
        activeMods.clear()
        for ((_, b) in modButtons) styled(b, false)
    }

    private fun showStatus(text: String, ok: Boolean) {
        if (text.startsWith("S ")) {
            val p = text.split(" ")
            val sent = p.getOrElse(1) { "0" }
            val dropped = p.getOrElse(2) { "0" }
            val leds = p.getOrElse(3) { "0" }
            status.text = "● HID  sent=$sent  dropped=$dropped  PC-LED-reports=$leds"
            status.setTextColor(if (dropped == "0") 0xFF66BB6A.toInt() else 0xFFFFB74D.toInt())
        } else {
            status.text = text
            status.setTextColor(if (ok) 0xFF66BB6A.toInt() else 0xFFBBBBBB.toInt())
        }
    }

    private fun runSetup(action: String) {
        status.text = "hid.sh $action ... (may take ~10 s)"
        status.setTextColor(0xFFBBBBBB.toInt())
        val t = Thread {
            if (action == "off") {
                link.stop()
                try { Thread.sleep(800) } catch (e: Exception) { }
            }
            val out = try {
                HidSetup.run(this, action)
            } catch (e: Exception) {
                "error: " + e.message
            }
            runOnUiThread {
                status.text = out.lines().takeLast(4).joinToString("\n")
                if (action == "on" && out.contains("HID2 ON")) {
                    val kbd = out.lines().firstOrNull { it.startsWith("KBD ") }?.substring(4)?.trim()
                    val mouse = out.lines().firstOrNull { it.startsWith("MOUSE ") }?.substring(6)?.trim()
                    if (kbd != null && mouse != null) link.start(kbd, mouse)
                }
            }
        }
        t.isDaemon = true
        t.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Show the touchpad even over the lock screen, so Bluetooth keeps working while locked.
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        prefs = getSharedPreferences("usbpad", Context.MODE_PRIVATE)
        mode = prefs.getString("mode", "hid") ?: "hid"
        if (mode == "adb") mode = "usb"
        if (mode == "bt" && !btSupported()) mode = "hid"

        status = TextView(this)
        status.textSize = 12f
        status.setPadding(dp(12), dp(8), dp(12), dp(8))
        status.background = Glass.panel(this, 14f)
        status.text = "○ HID off. Tap HID ON, then plug the cable into the PC."
        status.setTextColor(0xFFBBBBBB.toInt())

        link = HidLink(this) { text, ok -> runOnUiThread { if (mode == "hid") showStatus(text, ok) } }

        val pad = TouchpadView(this) { sendCmd(it) }
        keyView = KeyCaptureView(this) { sendCmd(it) }

        onBtn = Button(this).acc(Glass.GREEN)
        modeBtn = Button(this).acc(Glass.BLUE)
        modeBtn.setOnClickListener {
            setMode(when (mode) {
                "hid" -> "usb"
                "usb" -> "wifi"
                "wifi" -> "lan"
                "lan" -> if (btSupported()) "bt" else "hid"
                else -> "hid"
            })
        }
        onBtn.text = "HID ON"
        onBtn.setOnClickListener {
            when (mode) {
                "bt" -> btPair()
                "lan" -> lanDialog()
                else -> runSetup("on")
            }
        }
        offBtn = Button(this).acc(Glass.RED)
        offBtn.text = "HID OFF"
        offBtn.setOnClickListener { if (mode == "bt") btDevices() else runSetup("off") }

        // Fn panel: F1-F12 and Home / End / PgUp / PgDn / Ins / PrtSc, toggled by the "Fn" button
        val fnPanel = LinearLayout(this)
        fnPanel.orientation = LinearLayout.VERTICAL
        fnPanel.visibility = View.GONE
        fnPanel.addView(row(40, keyButton("F1", "f1"), keyButton("F2", "f2"), keyButton("F3", "f3"),
            keyButton("F4", "f4"), keyButton("F5", "f5"), keyButton("F6", "f6")))
        fnPanel.addView(row(40, keyButton("F7", "f7"), keyButton("F8", "f8"), keyButton("F9", "f9"),
            keyButton("F10", "f10"), keyButton("F11", "f11"), keyButton("F12", "f12")))
        fnPanel.addView(row(40, keyButton("Home", "home"), keyButton("End", "end"), keyButton("PgUp", "pgup", true),
            keyButton("PgDn", "pgdn", true), keyButton("Ins", "insert"), keyButton("PrtSc", "printscreen")))
        val fnBtn = Button(this).acc(Glass.PURPLE)
        fnBtn.text = "Fn ▾"
        fnBtn.setOnClickListener {
            val show = fnPanel.visibility != View.VISIBLE
            fnPanel.visibility = if (show) View.VISIBLE else View.GONE
            fnBtn.text = if (show) "Fn ▴" else "Fn ▾"
        }
        val kbBtn = Button(this).acc(Glass.ORANGE)
        kbBtn.text = "⌨ Keyboard"
        kbBtn.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            if (kbVisible) {
                imm.hideSoftInputFromWindow(keyView.windowToken, 0)
            } else {
                keyView.requestFocus()
                imm.showSoftInput(keyView, 0)
            }
            kbVisible = !kbVisible
        }

        fun navBtn(label: String, color: IntArray = Glass.PURPLE, act: () -> Unit): Button {
            val b = Button(this).acc(color)
            b.text = label
            b.setOnClickListener { act() }
            return b
        }
        val dragBtn = navBtn("Drag: ON", Glass.ORANGE) { }
        pad.tapDrag = prefs.getBoolean("tap_drag", true)
        dragBtn.text = if (pad.tapDrag) "Drag: ON" else "Drag: OFF"
        dragBtn.setOnClickListener {
            pad.tapDrag = !pad.tapDrag
            prefs.edit().putBoolean("tap_drag", pad.tapDrag).apply()
            dragBtn.text = if (pad.tapDrag) "Drag: ON" else "Drag: OFF"
            Toast.makeText(this, if (pad.tapDrag) "Tap and drag ON: tap, tap, touch+hold, move" else "Tap and drag OFF",
                Toast.LENGTH_SHORT).show()
        }
        val padBtn = navBtn("Gamepad", Glass.PINK) {
            if (mode == "bt") startActivity(Intent(this, GamepadActivity::class.java))
            else Toast.makeText(this, "Gamepad works in Mode: Bluetooth", Toast.LENGTH_SHORT).show()
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.TRANSPARENT)
        root.setPadding(dp(8), dp(8), dp(8), dp(8))

        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(3), 0, dp(3), dp(6))
        })
        root.addView(row(44, modeBtn, onBtn, offBtn))
        root.addView(row(40, navBtn("◀ Back") { nav("back") }, navBtn("● Home") { nav("home") },
            navBtn("▣ Recents") { nav("recents") }, padBtn, dragBtn))
        root.addView(pad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            setMargins(dp(3), dp(6), dp(3), dp(6))
        })
        root.addView(fnPanel)
        root.addView(row(56, holdButton("Left", "L"), holdButton("Middle", "M"), holdButton("Right", "R"), fnBtn, kbBtn))
        root.addView(row(48, modButton("Ctrl", "ctrl"), modButton("Alt", "alt"), modButton("Shift", "shift"), modButton("Win", "win"), keyButton("Caps", "capslock")))
        root.addView(row(48, keyButton("Esc", "esc"), keyButton("Tab", "tab"), keyButton("Del", "delete", true), keyButton("⌫", "backspace", true), keyButton("Enter", "enter")))
        root.addView(row(48, keyButton("←", "left", true), keyButton("↑", "up", true), keyButton("↓", "down", true), keyButton("→", "right", true), keyButton("Num", "numlock")))
        root.addView(keyView, LinearLayout.LayoutParams(1, 1))
        window.statusBarColor = 0xFF1A1446.toInt()
        window.navigationBarColor = 0xFF0B3A8C.toInt()
        setContentView(Glass.decorate(this, root))
        setMode(mode)
    }

    override fun onResume() {
        super.onResume()
        if (mode == "bt" && btLink != null) {
            BtHolder.listener = { text, ok -> runOnUiThread { if (mode == "bt") showBtStatus(text, ok) } }
            if (BtHolder.lastText.isNotEmpty()) showBtStatus(BtHolder.lastText, BtHolder.lastOk)
        }
    }

    override fun onPause() {
        super.onPause()
        releaseAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        link.stop()
        adbLink?.stop()
        // Bluetooth keeps running in BtService; just stop updating this (dying) activity.
        BtHolder.listener = null
    }
}
