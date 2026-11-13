package com.example.usbpad

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import rikka.shizuku.Shizuku

/**
 * Back / Home / Recents of THIS phone, sent as `input keyevent` through Shizuku or root.
 * (File name kept as NavService.kt; there is no Accessibility service any more.)
 *
 * Method (button "Nav: ..." on the main screen cycles it):
 *   Auto    = Shizuku if it is running and UsbPad is allowed, otherwise root
 *   Shizuku = Shizuku only (asks for permission the first time)
 *   Root    = root only (su)
 */
object Nav {
    private const val KEY_HOME = 3
    private const val KEY_BACK = 4
    private const val KEY_RECENTS = 187
    private const val PREF = "nav_method"

    private val ui = Handler(Looper.getMainLooper())

    fun back(ctx: Context) = press(ctx, KEY_BACK)

    fun home(ctx: Context) = press(ctx, KEY_HOME)

    fun recents(ctx: Context) = press(ctx, KEY_RECENTS)

    /** 0 = Auto, 1 = Shizuku, 2 = Root */
    private fun method(ctx: Context): Int =
        ctx.getSharedPreferences("usbpad", Context.MODE_PRIVATE).getInt(PREF, 0)

    fun cycle(ctx: Context) {
        val n = (method(ctx) + 1) % 3
        ctx.getSharedPreferences("usbpad", Context.MODE_PRIVATE).edit().putInt(PREF, n).apply()
    }

    fun label(ctx: Context): String = "Nav: " + when (method(ctx)) {
        1 -> "Shizuku"
        2 -> "Root"
        else -> "Auto"
    }

    private fun toast(ctx: Context, s: String) {
        ui.post { Toast.makeText(ctx, s, Toast.LENGTH_LONG).show() }
    }

    private fun press(ctx: Context, code: Int) {
        val app = ctx.applicationContext
        val m = method(app)
        Thread {
            val ok = when (m) {
                1 -> viaShizuku(app, code, true)
                2 -> viaRoot(code)
                else -> viaShizuku(app, code, false) || viaRoot(code)
            }
            if (!ok) {
                if (m == 0) toast(app, "Needs Shizuku (running + allowed for UsbPad) or root")
                else if (m == 2) toast(app, "Root failed. Allow UsbPad in your root manager")
                if (code == KEY_HOME) {
                    // Home works even without any permission
                    ui.post {
                        try {
                            app.startActivity(
                                Intent(Intent.ACTION_MAIN)
                                    .addCategory(Intent.CATEGORY_HOME)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (e: Exception) {
                        }
                    }
                }
            }
        }.start()
    }

    /** loud = show messages and ask for the Shizuku permission; quiet is used by Auto. */
    private fun viaShizuku(ctx: Context, code: Int, loud: Boolean): Boolean {
        try {
            if (!Shizuku.pingBinder()) {
                if (loud) toast(ctx, "Shizuku is not running. Open the Shizuku app and start it")
                return false
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                if (loud) {
                    if (Shizuku.shouldShowRequestPermissionRationale()) {
                        toast(ctx, "Shizuku permission denied. In Shizuku open Authorized apps and allow UsbPad")
                    } else {
                        toast(ctx, "Allow UsbPad in the Shizuku dialog, then tap again")
                        Shizuku.requestPermission(100)
                    }
                }
                return false
            }
            // Shizuku.newProcess is private in API 13, call it by reflection (widely used way).
            val mth = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            mth.isAccessible = true
            val p = mth.invoke(null, arrayOf("input", "keyevent", code.toString()), null, null) as Process
            return p.waitFor() == 0
        } catch (e: Throwable) {
            if (loud) toast(ctx, "Shizuku error: " + e.message)
            return false
        }
    }

    private fun viaRoot(code: Int): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent $code"))
            p.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }
}
