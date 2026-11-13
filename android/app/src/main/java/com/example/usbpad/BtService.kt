package com.example.usbpad

import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Owns the BtLink so Bluetooth HID keeps working when the app is minimized or closed with Back. */
object BtHolder {
    @Volatile var link: BtLink? = null
    @Volatile var listener: ((String, Boolean) -> Unit)? = null
    @Volatile var lastText: String = ""
    @Volatile var lastOk: Boolean = false

    @TargetApi(Build.VERSION_CODES.P)
    fun get(ctx: Context): BtLink {
        link?.let { return it }
        val l = BtLink(ctx.applicationContext) { t, ok ->
            lastText = t
            lastOk = ok
            listener?.invoke(t, ok)
        }
        link = l
        return l
    }

    @TargetApi(Build.VERSION_CODES.P)
    fun stop() {
        link?.stop()
        link = null
    }
}

class BtService : Service() {
    companion object {
        const val ACTION_STOP = "com.example.usbpad.BT_STOP"
        private const val CHANNEL = "usbpad_bt"
        private const val NOTIF_ID = 77
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            BtHolder.stop()
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Bluetooth HID", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BtService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        @Suppress("DEPRECATION")
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("UsbPad Bluetooth")
            .setContentText("Phone is acting as a Bluetooth keyboard/touchpad")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        // Keep the CPU awake so the connection survives screen lock / Doze.
        if (wakeLock == null) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "usbpad:bt").also {
                    it.setReferenceCounted(false)
                    it.acquire()
                }
            } catch (e: Exception) { }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { wakeLock?.release() } catch (e: Exception) { }
        wakeLock = null
        BtHolder.stop()
    }
}
