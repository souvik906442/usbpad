package com.example.usbpad

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

const val PORT = 38555

/** Talks to server.py on the PC through `adb reverse tcp:38555 tcp:38555`. */
class Link(private val onStatus: (Boolean) -> Unit) {
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
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress("127.0.0.1", PORT), 1000)
                    s.soTimeout = 3000
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
