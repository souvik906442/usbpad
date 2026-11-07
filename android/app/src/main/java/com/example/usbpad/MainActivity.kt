package com.example.usbpad

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var link: HidLink
    private var adbLink: Link? = null
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
        b.setBackgroundColor(if (on) 0xFF2E7D32.toInt() else 0xFF3A3A3A.toInt())
        return b
    }

    private fun row(h: Int, vararg views: View): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            val lp = LinearLayout.LayoutParams(0, dp(h), 1f)
            lp.setMargins(dp(2), dp(2), dp(2), dp(2))
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
        if (mode == "adb") adbLink?.send(line) else link.send(line)
    }

    private fun showAdbStatus(ok: Boolean) {
        status.text = if (ok) "● ADB connected" else "○ ADB: waiting for PC (run server.py, USB or Wireless debugging ON)"
        status.setTextColor(if (ok) 0xFF66BB6A.toInt() else 0xFFBBBBBB.toInt())
    }

    private fun setMode(m: String) {
        mode = m
        prefs.edit().putString("mode", m).apply()
        val adb = m == "adb"
        modeBtn.text = if (adb) "Mode: ADB" else "Mode: HID (root)"
        onBtn.visibility = if (adb) View.GONE else View.VISIBLE
        offBtn.visibility = if (adb) View.GONE else View.VISIBLE
        if (adb) {
            link.stop()
            adbLink?.stop()
            val l = Link { ok -> runOnUiThread { if (mode == "adb") showAdbStatus(ok) } }
            adbLink = l
            l.start()
            showAdbStatus(false)
        } else {
            adbLink?.stop()
            adbLink = null
            status.text = "○ HID off. Tap HID ON, then plug the cable into the PC."
            status.setTextColor(0xFFBBBBBB.toInt())
        }
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
        prefs = getSharedPreferences("usbpad", Context.MODE_PRIVATE)
        mode = prefs.getString("mode", "hid") ?: "hid"

        status = TextView(this)
        status.textSize = 12f
        status.setPadding(dp(4), dp(2), dp(4), dp(6))
        status.text = "○ HID off. Tap HID ON, then plug the cable into the PC."
        status.setTextColor(0xFFBBBBBB.toInt())

        link = HidLink(this) { text, ok -> runOnUiThread { if (mode == "hid") showStatus(text, ok) } }

        val pad = TouchpadView(this) { sendCmd(it) }
        keyView = KeyCaptureView(this) { sendCmd(it) }

        onBtn = styled(Button(this))
        modeBtn = styled(Button(this))
        modeBtn.setOnClickListener { setMode(if (mode == "adb") "hid" else "adb") }
        onBtn.text = "HID ON"
        onBtn.setOnClickListener { runSetup("on") }
        offBtn = styled(Button(this))
        offBtn.text = "HID OFF"
        offBtn.setOnClickListener { runSetup("off") }

        val kbBtn = styled(Button(this))
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

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xFF111111.toInt())
        root.setPadding(dp(8), dp(8), dp(8), dp(8))

        root.addView(status)
        root.addView(row(44, modeBtn, onBtn, offBtn))
        root.addView(pad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(row(56, holdButton("Left", "L"), holdButton("Middle", "M"), holdButton("Right", "R"), kbBtn))
        root.addView(row(48, modButton("Ctrl", "ctrl"), modButton("Alt", "alt"), modButton("Shift", "shift"), modButton("Win", "win")))
        root.addView(row(48, keyButton("Esc", "esc"), keyButton("Tab", "tab"), keyButton("Del", "delete", true), keyButton("⌫", "backspace", true), keyButton("Enter", "enter")))
        root.addView(row(48, keyButton("←", "left", true), keyButton("↑", "up", true), keyButton("↓", "down", true), keyButton("→", "right", true)))
        root.addView(keyView, LinearLayout.LayoutParams(1, 1))
        setContentView(root)
        setMode(mode)
    }

    override fun onPause() {
        super.onPause()
        releaseAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        link.stop()
        adbLink?.stop()
    }
}
