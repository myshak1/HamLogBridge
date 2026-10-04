package pl.hamlogbridge.net

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Re-sends every received datagram, byte for byte, to other hosts on the LAN.
 * This is how you keep using GridTracker / Log4OM / N1MM on a PC while the
 * phone is the one physically in range of the radio's Wi-Fi.
 *
 * Targets are written as "host:port", comma separated.
 */
class UdpRelay {
    private var socket: DatagramSocket? = null
    private var targets: List<InetSocketAddress> = emptyList()

    @Synchronized
    fun configure(spec: String) {
        targets = parse(spec)
        if (targets.isNotEmpty() && socket == null) {
            socket = runCatching { DatagramSocket().apply { broadcast = true } }.getOrNull()
        }
    }

    @Synchronized
    fun send(data: ByteArray, length: Int) {
        val s = socket ?: return
        for (t in targets) {
            runCatching {
                s.send(DatagramPacket(data, length, t.address, t.port))
            }.onFailure { Log.w("UdpRelay", "relay to $t failed: ${it.message}") }
        }
    }

    @Synchronized
    fun close() {
        runCatching { socket?.close() }
        socket = null
        targets = emptyList()
    }

    companion object {
        fun parse(spec: String): List<InetSocketAddress> =
            spec.split(',', ';', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { entry ->
                    val idx = entry.lastIndexOf(':')
                    if (idx <= 0) return@mapNotNull null
                    val host = entry.substring(0, idx).trim()
                    val port = entry.substring(idx + 1).trim().toIntOrNull() ?: return@mapNotNull null
                    runCatching { InetSocketAddress(InetAddress.getByName(host), port) }.getOrNull()
                }
    }
}
