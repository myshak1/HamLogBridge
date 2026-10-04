package pl.hamlogbridge.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Listens for WSJT-X datagrams. Binding to 0.0.0.0 catches unicast *and*
 * subnet broadcast, which is what most radios/firmwares send. A multicast
 * group can be joined as well (WSJT-X itself supports 224.0.0.1 style groups);
 * the caller is responsible for holding a MulticastLock in that case.
 */
class UdpListener(
    private val port: Int,
    private val multicastGroup: String? = null,
    private val bufferSize: Int = 8192,
    // Joining a multicast group with a null NetworkInterface lets the OS pick
    // one, which on a phone with Wi-Fi *and* mobile data active can silently
    // pick the wrong interface (or none) — packets never arrive and nothing
    // throws. Pass the Wi-Fi interface explicitly to avoid that.
    private val networkInterface: NetworkInterface? = null
) {
    interface Sink {
        fun onDatagram(data: ByteArray, length: Int, from: InetSocketAddress)
        fun onError(t: Throwable)
        fun onBound(port: Int)
    }

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var stopRequested = false
    private var job: Job? = null

    fun start(scope: CoroutineScope, sink: Sink) {
        stop()
        stopRequested = false
        job = scope.launch(Dispatchers.IO) {
            try {
                val group = multicastGroup?.takeIf { it.isNotBlank() }
                // InetSocketAddress(port) resolves a wildcard address via
                // InetAddress.anyLocalAddress() internally; on some devices that
                // resolution comes back bad and checkPort() then rejects with
                // "port out of range:-1" even though `port` itself is fine.
                // Binding to an explicit 0.0.0.0 sidesteps that helper.
                val wildcard = InetSocketAddress(InetAddress.getByName("0.0.0.0"), port)
                val s: DatagramSocket = if (group != null) {
                    MulticastSocket(null).apply {
                        reuseAddress = true
                        bind(wildcard)
                        val groupAddr = InetAddress.getByName(group)
                        // Prefer the modern, explicit-interface join — it registers
                        // multicast membership more reliably across Android versions
                        // and OEM builds than the legacy overload below. On some
                        // devices, though, building the InetSocketAddress it needs
                        // (a multicast-range address + this exact port) throws "port
                        // out of range:-1" for a demonstrably valid port —
                        // reproducible, deterministic, unrelated to the actual
                        // values passed in. When that happens, fall back to the
                        // legacy joinGroup(InetAddress), which needs no such object
                        // (group membership isn't port-scoped) and so sidesteps the
                        // bug, using setNetworkInterface() to still pin the
                        // interface explicitly.
                        try {
                            joinGroup(InetSocketAddress(groupAddr, port), networkInterface)
                        } catch (e: IllegalArgumentException) {
                            networkInterface?.let { setNetworkInterface(it) }
                            @Suppress("DEPRECATION")
                            joinGroup(groupAddr)
                        }
                    }
                } else {
                    DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        bind(wildcard)
                    }
                }
                s.soTimeout = 1000
                socket = s
                sink.onBound(port)

                val buf = ByteArray(bufferSize)
                while (true) {
                    ensureActive()
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val copy = p.data.copyOf(p.length)
                    sink.onDatagram(copy, p.length, InetSocketAddress(p.address, p.port))
                }
            } catch (t: Throwable) {
                if (!stopRequested) {
                    Log.e("UdpListener", "listener died", t)
                    sink.onError(t)
                }
            } finally {
                closeQuietly()
            }
        }
    }

    fun stop() {
        stopRequested = true
        job?.cancel()
        job = null
        closeQuietly()
    }

    private fun closeQuietly() {
        runCatching {
            val s = socket
            if (s is MulticastSocket && multicastGroup != null) {
                runCatching {
                    @Suppress("DEPRECATION")
                    s.leaveGroup(InetAddress.getByName(multicastGroup))
                }
            }
            s?.close()
        }
        socket = null
    }
}
