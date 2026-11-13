package com.example.usbpad

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Full-screen landscape gamepad. Uses the Bluetooth link that MainActivity / BtService already keep running. */
class GamepadActivity : Activity() {
    private lateinit var status: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun bar(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.setBackgroundColor(0xFF3A3A3A.toInt())
        b.setOnClickListener { onClick() }
        return b
    }

    private fun hideBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    private fun show(text: String, ok: Boolean) {
        status.text = text
        status.setTextColor(if (ok) 0xFF66BB6A.toInt() else 0xFFBBBBBB.toInt())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)

        status = TextView(this)
        status.textSize = 13f
        status.gravity = Gravity.CENTER_VERTICAL
        status.setPadding(dp(10), 0, dp(10), 0)
        status.maxLines = 2

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.setBackgroundColor(0xFF111111.toInt())
        fun add(v: View, w: Int, weight: Float = 0f) {
            val lp = LinearLayout.LayoutParams(w, dp(44), weight)
            lp.setMargins(dp(3), dp(3), dp(3), dp(3))
            top.addView(v, lp)
        }
        add(bar("Close") { finish() }, ViewGroup.LayoutParams.WRAP_CONTENT)
        add(status, 0, 1f)
        add(bar("◀ Back") { BtHolder.link?.send("N back") }, ViewGroup.LayoutParams.WRAP_CONTENT)
        add(bar("● Home") { BtHolder.link?.send("N home") }, ViewGroup.LayoutParams.WRAP_CONTENT)
        add(bar("▣ Recents") { BtHolder.link?.send("N recents") }, ViewGroup.LayoutParams.WRAP_CONTENT)

        val pad = GamepadView(this) { line -> BtHolder.link?.send(line) }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xFF262628.toInt())
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(pad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        hideBars()
        BtHolder.listener = { t, ok -> runOnUiThread { show(t, ok) } }
        val last = BtHolder.lastText
        if (BtHolder.link == null || last.isEmpty()) {
            show("Not connected. Close, choose Mode: Bluetooth and connect to the PC first.", false)
        } else {
            show(last, BtHolder.lastOk)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    override fun onPause() {
        super.onPause()
        // let go of every button and stick
        BtHolder.link?.send("G 0 8 128 128 128 128 0 0")
        BtHolder.listener = null
    }
}
