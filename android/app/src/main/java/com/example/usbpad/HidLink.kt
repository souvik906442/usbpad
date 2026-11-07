package com.example.usbpad

import android.content.Context
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Starts HidHelper as root (su + app_process) and feeds it the text protocol lines. */
class HidLink(private val ctx: Context, private val onStatus: (String, Boolean) -> Unit) {
    private val queue = LinkedBlockingQueue<String>()
    @Volatile var connected = false
        private set
    @Volatile private var running = false
    private var proc: Process? = null

    fun start(kbd: String, mouse: String) {
        if (running) return
        running = true
        queue.clear()
        val t = Thread {
            try {
                val apk = ctx.applicationInfo.sourceDir
                val cmd = "CLASSPATH=$apk app_process /system/bin com.example.usbpad.HidHelper $kbd $mouse"
                val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
                proc = p
                val reader = Thread {
                    try {
                        p.inputStream.bufferedReader().forEachLine { line ->
                            if (line.startsWith("S ")) {
                                connected = true
                                onStatus(line, true)
                            } else {
                                onStatus(line, false)
                            }
                        }
                    } catch (e: Exception) {
                    }
                    connected = false
                    onStatus("helper stopped", false)
                }
                reader.isDaemon = true
                reader.start()
                val out = p.outputStream
                while (running && p.isAlive) {
                    val line = queue.poll(300, TimeUnit.MILLISECONDS)
                    if (line != null) {
                        out.write((line + "\n").toByteArray())
                        out.flush()
                    }
                }
            } catch (e: Exception) {
                onStatus("error: " + e.message, false)
            } finally {
                connected = false
                running = false
                try { proc?.outputStream?.close() } catch (e: Exception) { }
                try { Thread.sleep(200) } catch (e: Exception) { }
                try { proc?.destroy() } catch (e: Exception) { }
            }
        }
        t.isDaemon = true
        t.start()
    }

    fun send(line: String) {
        if (connected) queue.offer(line)
    }

    /** Releases all keys/buttons, then shuts the helper down. */
    fun stop() {
        if (!running) return
        queue.offer("R")
        val t = Thread {
            try { Thread.sleep(400) } catch (e: Exception) { }
            running = false
        }
        t.isDaemon = true
        t.start()
    }
}

/** Runs assets/hid.sh as root: on | off | status. */
object HidSetup {
    fun run(ctx: Context, action: String): String {
        val f = java.io.File(ctx.filesDir, "hid.sh")
        ctx.assets.open("hid.sh").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        val p = ProcessBuilder("su", "-c", "sh " + f.absolutePath + " " + action)
            .redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return text.trim()
    }
}
