package com.example.usbpad

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

const val PORT = 38555
const val DISCOVERY_PORT = 38556

/**
 * Talks to server.py on the PC: through `adb reverse` (host 127.0.0.1) or, in Wi-Fi (PC) mode,
 * straight to the PC's IP on the same network (then [pin] is sent first and must match the PC's PIN).
 */
class Link(
    host: String = "127.0.0.1",
    private val pin: String = "",
    /** Called with the PC's new IP when the phone had to look for it (Wi-Fi (PC) mode). */
    private val onFound: ((String) -> Unit)? = null,
    private val onStatus: (Boolean) -> Unit
) {
    @Volatile private var curHost = host

    /** Broadcasts "USBPAD?" on the Wi-Fi; the PC server answers, so a changed PC IP is found again. */
    private fun discover(): String? {
        return try {
            DatagramSocket().use { u ->
                u.broadcast = true
                u.soTimeout = 1200
                val q = "USBPAD?".toByteArray()
                u.send(DatagramPacket(q, q.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
                val buf = ByteArray(32)
                val r = DatagramPacket(buf, buf.size)
                u.receive(r)
                if (String(r.data, 0, r.length).trim() == "USBPAD!") r.address.hostAddress else null
            }
        } catch (e: Exception) {
            null
        }
    }
    private val queue = LinkedBlockingQueue<String>()
    @Volatile private var running = true
    @Volatile var connected = false
        private set
    private var last: Boolean? = null

    private fun report(v: Boolean) {
        if (last != v) {
            last = v
            onStatus(v)
        }
    }

    fun start() {
        val t = Thread {
            while (running) {
                val s = Socket()
                try {
                    if (pin.isNotEmpty() && onFound != null) {
                        // LAN mode: if the saved IP does not answer, look for the PC on the network.
                        val ok = curHost.isNotEmpty() && try {
                            Socket().use { t -> t.connect(InetSocketAddress(curHost, PORT), 800) }
                            true
                        } catch (e: Exception) { false }
                        if (!ok) {
                            val ip = discover()
                            if (ip != null && ip != curHost) { curHost = ip; onFound.invoke(ip) }
                        }
                    }
                    if (curHost.isEmpty()) throw IOException("no host")
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(curHost, PORT), if (pin.isEmpty()) 1000 else 3000)
                    s.soTimeout = 3000
                    if (pin.isNotEmpty()) {
                        s.getOutputStream().write(("AUTH $pin\n").toByteArray())
                        s.getOutputStream().flush()
                    }
                    val reader = s.getInputStream().bufferedReader()
                    // adbd accepts the connection even when the PC server is down,
                    // so wait for the server's HELLO before calling it connected.
                    if (reader.readLine() != "HELLO") throw IOException("no hello")
                    connected = true
                    report(true)
                    queue.clear()
                    val rd = Thread {
                        try {
                            while (reader.readLine() != null) { /* pings */ }
                        } catch (e: Exception) {
                        }
                        try { s.close() } catch (e: Exception) { }
                    }
                    rd.isDaemon = true
                    rd.start()
                    val out = s.getOutputStream()
                    while (running && !s.isClosed) {
                        val line = queue.poll(500, TimeUnit.MILLISECONDS)
                        if (line != null) {
                            out.write((line + "\n").toByteArray())
                            out.flush()
                        }
                    }
                } catch (e: Exception) {
                } finally {
                    connected = false
                    try { s.close() } catch (e: Exception) { }
                }
                report(false)
                if (running) {
                    try { Thread.sleep(1000) } catch (e: InterruptedException) { }
                }
            }
        }
        t.isDaemon = true
        t.start()
    }

    fun send(line: String) {
        if (connected) queue.offer(line)
    }

    fun stop() {
        running = false
    }
}
