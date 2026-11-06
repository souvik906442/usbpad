package com.example.usbpad

import android.app.Activity
import android.content.Context
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
    private lateinit var link: Link
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
        b.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    styled(b, true)
                    link.send("D $name")
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    styled(b, false)
                    link.send("U $name")
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
        b.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    styled(b, true)
                    link.send("K tap $name")
                    if (repeat) {
                        val r = object : Runnable {
                            override fun run() {
                                link.send("K tap $name")
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
                link.send("K up $name")
                styled(b, false)
            } else {
                activeMods.add(name)
                link.send("K down $name")
                styled(b, true)
            }
        }
        modButtons[name] = b
        return b
    }

    private fun releaseAll() {
        link.send("R")
        activeMods.clear()
        for ((_, b) in modButtons) styled(b, false)
    }

    private fun setStatus(ok: Boolean) {
        status.text = if (ok) "● Connected" else "○ Waiting for PC (run server.py, USB debugging ON)"
        status.setTextColor(if (ok) 0xFF66BB6A.toInt() else 0xFFBBBBBB.toInt())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        status = TextView(this)
        status.textSize = 13f
        status.setPadding(dp(4), dp(2), dp(4), dp(6))
        status.text = "○ Starting..."
        status.setTextColor(0xFFBBBBBB.toInt())

        link = Link { ok -> runOnUiThread { setStatus(ok) } }

        val pad = TouchpadView(this) { link.send(it) }
        keyView = KeyCaptureView(this) { link.send(it) }

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
        root.addView(pad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(row(56, holdButton("Left", "L"), holdButton("Middle", "M"), holdButton("Right", "R"), kbBtn))
        root.addView(row(48, modButton("Ctrl", "ctrl"), modButton("Alt", "alt"), modButton("Shift", "shift"), modButton("Win", "win")))
        root.addView(row(48, keyButton("Esc", "esc"), keyButton("Tab", "tab"), keyButton("Del", "delete", true), keyButton("⌫", "backspace", true), keyButton("Enter", "enter")))
        root.addView(row(48, keyButton("←", "left", true), keyButton("↑", "up", true), keyButton("↓", "down", true), keyButton("→", "right", true)))
        root.addView(keyView, LinearLayout.LayoutParams(1, 1))
        setContentView(root)

        link.start()
    }

    override fun onPause() {
        super.onPause()
        releaseAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        link.stop()
    }
}
