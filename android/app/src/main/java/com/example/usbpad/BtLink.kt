package com.example.usbpad

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import java.util.concurrent.Executors

/**
 * Bluetooth HID mode (like Bluke): the phone registers as a Bluetooth HID keyboard + mouse
 * (BluetoothHidDevice API, Android 9+). No root, no cable, no PC software.
 * It understands the same text protocol as HidHelper / server.py:
 *   M dx dy | W d | D/U/C L|R|M | T text | K tap|down|up name | R
 */
@TargetApi(Build.VERSION_CODES.P)
@SuppressLint("MissingPermission")
class BtLink(private val ctx: Context, private val onStatus: (String, Boolean) -> Unit) {

    companion object {
        private const val ID_KBD = 1
        private const val ID_MOUSE = 2

        // Report ID 1: keyboard (8 bytes: mods, reserved, 6 keys) + LED output report
        // Report ID 2: mouse (3 buttons, x, y, wheel)
        private val DESCRIPTOR = intArrayOf(
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, ID_KBD,
            0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
            0x95, 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91, 0x02,
            0x95, 0x01, 0x75, 0x03, 0x91, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
            0xC0,
            0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, ID_MOUSE,
            0x09, 0x01, 0xA1, 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01,
            0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x05, 0x81, 0x01,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
            0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
            0xC0, 0xC0
        ).map { it.toByte() }.toByteArray()

        private const val PLAIN = "-=[]\\;'`,./"
        private const val SHIFT = "_+{}|:\"~<>?"
        private val SYM = intArrayOf(45, 46, 47, 48, 49, 51, 52, 53, 54, 55, 56)
    }

    private val prefs = ctx.getSharedPreferences("usbpad", Context.MODE_PRIVATE)
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    // Every callback and every send runs on this single thread, so the key state needs no locks.
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r).also { it.isDaemon = true } }

    @Volatile private var hid: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    @Volatile var registered = false
        private set
    @Volatile var started = false
        private set

    private var mods = 0
    private var buttons = 0
    private val keys = IntArray(6)

    val connected: Boolean get() = host != null

    // ---------- lifecycle ----------

    /** Registers the HID app. Returns false if Bluetooth is missing/off. */
    fun start(): Boolean {
        val a = adapter
        if (a == null) { onStatus("This phone has no Bluetooth", false); return false }
        if (!a.isEnabled) { onStatus("Turn Bluetooth ON first", false); return false }
        if (started) return true
        started = true
        onStatus("Registering Bluetooth HID ...", false)
        val ok = try {
            a.getProfileProxy(ctx, serviceListener, BluetoothProfile.HID_DEVICE)
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            started = false
            onStatus("Bluetooth HID profile not available on this phone", false)
        }
        return ok
    }

    fun stop() {
        if (!started) return
        started = false
        val h = hid
        val d = host
        exec.execute {
            try {
                releaseAllNow()
                if (d != null) h?.disconnect(d)
                h?.unregisterApp()
            } catch (e: Exception) {
            }
            try { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h) } catch (e: Exception) { }
        }
        hid = null
        host = null
        registered = false
        onStatus("○ Bluetooth HID off", false)
    }

    /** Paired devices, so the user can pick the PC to connect to. */
    fun bondedDevices(): List<BluetoothDevice> =
        try { adapter?.bondedDevices?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }

    fun connectTo(d: BluetoothDevice) {
        val h = hid
        if (h == null || !registered) { onStatus("HID not ready yet, wait a second", false); return }
        prefs.edit().putString("bt_host", d.address).apply()
        onStatus("Connecting to ${d.name ?: d.address} ...", false)
        exec.execute { try { h.connect(d) } catch (e: Exception) { } }
    }

    private fun hostName(d: BluetoothDevice) = try { d.name ?: d.address } catch (e: Exception) { d.address }

    private fun reportConnected() =
        onStatus("● Bluetooth connected: ${host?.let { hostName(it) }}", true)

    private fun reportWaiting() =
        onStatus("○ Bluetooth ready. Tap \"Pair\", then on the PC add the phone (Settings > Bluetooth).", false)

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            if (!started) {
                try { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy) } catch (e: Exception) { }
                return
            }
            val h = proxy as BluetoothHidDevice
            hid = h
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "UsbPad", "Phone keyboard + touchpad", "UsbPad",
                BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR
            )
            val ok = try { h.registerApp(sdp, null, null, exec, callback) } catch (e: Exception) { false }
            if (!ok) onStatus("registerApp rejected (this ROM may not support Bluetooth HID)", false)
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hid = null
            host = null
            registered = false
            if (started) onStatus("Bluetooth HID service lost", false)
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@BtLink.registered = registered
            if (!registered) {
                if (started) onStatus("Bluetooth HID registration lost", false)
                return
            }
            val h = hid ?: return
            // Reconnect to the last PC (or the one Android says is already plugged).
            val target = pluggedDevice ?: prefs.getString("bt_host", null)?.let { addr ->
                bondedDevices().firstOrNull { it.address == addr }
            }
            if (target != null) {
                onStatus("Reconnecting to ${hostName(target)} ...", false)
                try { h.connect(target) } catch (e: Exception) { }
            } else {
                reportWaiting()
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    prefs.edit().putString("bt_host", device.address).apply()
                    reportConnected()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host?.address == device.address) host = null
                    mods = 0; buttons = 0; keys.fill(0)
                    if (started) reportWaiting()
                }
                else -> { }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val h = hid ?: return
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT) {
                val size = if (id.toInt() == ID_KBD) 8 else 4
                try { h.replyReport(device, type, id, ByteArray(size)) } catch (e: Exception) { }
            } else {
                try { h.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ) } catch (e: Exception) { }
            }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            // Host keyboard LEDs (Caps Lock etc.): accept and ignore.
            try { hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS) } catch (e: Exception) { }
        }

        override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) { }
        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) { }
        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            if (host?.address == device.address) host = null
            if (started) reportWaiting()
        }
    }

    // ---------- low level ----------

    private fun report(id: Int, data: ByteArray) {
        val h = hid ?: return
        val d = host ?: return
        try { h.sendReport(d, id, data) } catch (e: Exception) { }
    }

    private fun sendKbd() {
        val r = ByteArray(8)
        r[0] = mods.toByte()
        for (i in 0 until 6) r[2 + i] = keys[i].toByte()
        report(ID_KBD, r)
    }

    private fun sendMouse(dx: Int, dy: Int, wheel: Int) {
        report(ID_MOUSE, byteArrayOf(buttons.toByte(), dx.toByte(), dy.toByte(), wheel.toByte()))
    }

    private fun addKey(u: Int) {
        if (keys.contains(u)) return
        val i = keys.indexOf(0)
        if (i >= 0) keys[i] = u
    }

    private fun removeKey(u: Int) {
        for (i in 0 until 6) if (keys[i] == u) keys[i] = 0
    }

    private fun releaseAllNow() {
        mods = 0; buttons = 0; keys.fill(0)
        sendKbd()
        sendMouse(0, 0, 0)
    }

    // ---------- command protocol ----------

    /** Same lines as HidLink/Link. Safe to call from the UI thread. */
    fun send(line: String) {
        if (host == null) return
        exec.execute { try { handle(line) } catch (e: Exception) { } }
    }

    private fun handle(line: String) {
        val sp = line.indexOf(' ')
        val cmd = if (sp < 0) line else line.substring(0, sp)
        val arg = if (sp < 0) "" else line.substring(sp + 1)
        when (cmd) {
            "M" -> {
                val p = arg.trim().split(Regex("\\s+"))
                move(p[0].toInt(), p[1].toInt())
            }
            "W" -> wheel(arg.trim().toInt())
            "D" -> setButton(arg.trim(), true)
            "U" -> setButton(arg.trim(), false)
            "C" -> { setButton(arg.trim(), true); setButton(arg.trim(), false) }
            "T" -> typeText(arg)
            "X" -> for (h in arg.trim().split(Regex("\\s+"))) altHex(h)
            "K" -> {
                val s2 = arg.indexOf(' ')
                keyAction(arg.substring(0, s2), arg.substring(s2 + 1).trim())
            }
            "R" -> releaseAllNow()
        }
    }

    private fun move(dx0: Int, dy0: Int) {
        var dx = dx0
        var dy = dy0
        while (dx != 0 || dy != 0) {
            val sx = dx.coerceIn(-127, 127)
            val sy = dy.coerceIn(-127, 127)
            sendMouse(sx, sy, 0)
            dx -= sx
            dy -= sy
        }
    }

    private fun wheel(delta: Int) {
        if (delta == 0) return
        var steps = delta / 120
        if (steps == 0) steps = if (delta > 0) 1 else -1
        while (steps != 0) {
            val s = steps.coerceIn(-127, 127)
            sendMouse(0, 0, s)
            steps -= s
        }
    }

    private fun setButton(name: String, down: Boolean) {
        val bit = when (name) { "L" -> 1; "R" -> 2; "M" -> 4; else -> 0 }
        if (bit == 0) return
        buttons = if (down) buttons or bit else buttons and bit.inv()
        sendMouse(0, 0, 0)
    }

    /** usage id, +0x100 if shift is needed; -1 if unsupported (HID sends US-layout keys only). */
    private fun usageFor(cp: Int): Int {
        if (cp in 'a'.code..'z'.code) return cp - 'a'.code + 4
        if (cp in 'A'.code..'Z'.code) return (cp - 'A'.code + 4) or 0x100
        if (cp in '1'.code..'9'.code) return cp - '1'.code + 30
        if (cp == '0'.code) return 39
        if (cp == ' '.code) return 44
        if (cp == '\n'.code) return 40
        if (cp == '\t'.code) return 43
        var i = "!@#$%^&*()".indexOf(cp.toChar())
        if (i >= 0) return (30 + i) or 0x100
        i = PLAIN.indexOf(cp.toChar())
        if (i >= 0) return SYM[i]
        i = SHIFT.indexOf(cp.toChar())
        if (i >= 0) return SYM[i] or 0x100
        return -1
    }

    private fun tap(usage: Int, shift: Boolean) {
        val saved = mods
        if (shift) mods = mods or 2
        addKey(usage); sendKbd()
        removeKey(usage)
        mods = saved
        sendKbd()
    }

    private fun typeText(s: String) {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            val u = usageFor(cp)
            if (u < 0) continue
            tap(u and 0xFF, (u and 0x100) != 0)
        }
    }

    private fun hexUsage(c: Char): Int = when (c) {
        in '1'..'9' -> 30 + (c - '1')
        '0' -> 39
        in 'a'..'f' -> 4 + (c - 'a')
        else -> 0
    }

    /**
     * Types one Unicode char on Windows with the hex Alt code: hold Alt, keypad +, hex digits, release Alt.
     * Needs HKCU\Control Panel\Input Method\EnableHexNumpad = "1" on the PC (then sign out/in).
     */
    private fun altHex(hex: String) {
        if (hex.isEmpty()) return
        val saved = mods
        mods = saved or 4; sendKbd()
        addKey(0x57); sendKbd(); removeKey(0x57); sendKbd()
        for (ch in hex.lowercase()) {
            val u = hexUsage(ch)
            if (u == 0) continue
            addKey(u); sendKbd(); removeKey(u); sendKbd()
        }
        mods = saved; sendKbd()
    }

    private fun modBit(n: String) = when (n) { "ctrl" -> 1; "shift" -> 2; "alt" -> 4; "win" -> 8; else -> 0 }

    private fun namedKey(n: String): Int = when (n) {
        "enter" -> 40; "esc" -> 41; "backspace" -> 42; "tab" -> 43; "space" -> 44
        "delete" -> 76; "home" -> 74; "end" -> 77; "pgup" -> 75; "pgdn" -> 78
        "right" -> 79; "left" -> 80; "down" -> 81; "up" -> 82
        else -> {
            val f = if (n.length >= 2 && n[0] == 'f') n.substring(1).toIntOrNull() else null
            if (f != null && f in 1..12) 57 + f else 0
        }
    }

    private fun keyAction(action: String, name: String) {
        val mb = modBit(name)
        val usage = if (mb != 0) 0 else namedKey(name)
        if (mb == 0 && usage == 0) return
        when (action) {
            "tap" -> {
                if (mb != 0) { mods = mods or mb; sendKbd(); mods = mods and mb.inv(); sendKbd() }
                else { addKey(usage); sendKbd(); removeKey(usage); sendKbd() }
            }
            "down" -> { if (mb != 0) mods = mods or mb else addKey(usage); sendKbd() }
            "up" -> { if (mb != 0) mods = mods and mb.inv() else removeKey(usage); sendKbd() }
        }
    }
}
