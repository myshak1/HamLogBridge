package pl.hamlogbridge.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import pl.hamlogbridge.wsjtx.WsjtxCodec
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * RFCOMM client for a Xiegu X6100 whose alternative firmware also emits the
 * WSJT-X UDP datagrams over Bluetooth SPP. The radio is the SPP server, the
 * phone connects out to it - see BT_ANDROID_BRIEF.md for the radio-side story.
 *
 * There's no IP layer over RFCOMM, so the radio frames each datagram with a
 * 4-byte big-endian length prefix (1..8192) ahead of the same bytes it would
 * otherwise put in a UDP packet. A framing mismatch means the stream has
 * desynced; we don't try to resync by scanning for the magic number in the
 * stream - that's guessing, and a reconnect is cheap (~1s).
 *
 * Since firmware v1.0.2 the radio's own GUI occupies RFCOMM channel 1 as CAT
 * (CI-V), published in SDP under the plain Serial Port UUID - SPP_UUID now
 * resolves to CAT, not to us. Our frames moved to channel 2, labelled in SDP
 * under the Dial-up Networking UUID purely so the phone can tell the two
 * records apart; it's not an actual DUN connection. openSocket() tries, in
 * order: SDP lookup by that UUID (works whenever Android's SDP cache is
 * fresh), a direct connect to channel 2 via the hidden createRfcommSocket(int)
 * API (SDP can be stale right after a firmware flash), then the old SPP_UUID
 * for firmware pre-v1.0.2 where that record really was our frames. The frame
 * loop below already disconnects on anything that isn't a valid WSJT-X frame,
 * which is what catches a stale last-resort connection landing on CAT instead.
 */
class BtPacketSource(
    private val adapter: BluetoothAdapter,
    private val deviceAddress: String
) {
    enum class State { IDLE, CONNECTING, CONNECTED, FAILED }

    interface Sink {
        fun onDatagram(data: ByteArray, length: Int)
        fun onState(state: State, message: String?)
    }

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var stopRequested = false
    @Volatile private var backoffMs = INITIAL_BACKOFF_MS
    private var job: Job? = null

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope, sink: Sink) {
        stop()
        stopRequested = false
        backoffMs = INITIAL_BACKOFF_MS
        job = scope.launch(Dispatchers.IO) {
            while (!stopRequested) {
                ensureActive()
                val device = adapter.bondedDevices?.firstOrNull { it.address == deviceAddress }
                if (device == null) {
                    sink.onState(State.FAILED, "Device not paired")
                } else {
                    sink.onState(State.CONNECTING, null)
                    try {
                        connectAndRead(device, sink)
                    } catch (e: IOException) {
                        if (!stopRequested) sink.onState(State.FAILED, e.message ?: "connection lost")
                    } catch (e: SecurityException) {
                        sink.onState(State.FAILED, "Missing Bluetooth permission")
                    } finally {
                        closeQuietly()
                    }
                }
                if (stopRequested) break
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
            sink.onState(State.IDLE, null)
        }
    }

    fun stop() {
        stopRequested = true
        job?.cancel()
        job = null
        closeQuietly()
    }

    /**
     * connect() and read() both block and do not observe coroutine
     * cancellation - closing the socket from stop() is the only way to
     * unblock them, so callers must not rely on ensureActive() alone here.
     */
    @SuppressLint("MissingPermission")
    private suspend fun connectAndRead(device: BluetoothDevice, sink: Sink) {
        runCatching { adapter.cancelDiscovery() }
        val s = openSocket(device)
        socket = s
        backoffMs = INITIAL_BACKOFF_MS
        sink.onState(State.CONNECTED, null)

        val input = s.inputStream
        val lenBuf = ByteArray(4)
        while (!stopRequested) {
            coroutineContext.ensureActive()
            readFully(input, lenBuf, 4)
            val len = ((lenBuf[0].toInt() and 0xFF) shl 24) or
                    ((lenBuf[1].toInt() and 0xFF) shl 16) or
                    ((lenBuf[2].toInt() and 0xFF) shl 8) or
                    (lenBuf[3].toInt() and 0xFF)
            if (len !in 1..8192) throw IOException("frame length $len out of range - stream desync")
            val payload = ByteArray(len)
            readFully(input, payload, len)
            if (!WsjtxCodec.looksLikeWsjtx(payload, len)) throw IOException("frame missing WSJT-X magic - stream desync")
            sink.onDatagram(payload, len)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openSocket(device: BluetoothDevice): BluetoothSocket {
        tryConnect { device.createRfcommSocketToServiceRecord(FRAMES_UUID) }
            ?.let { return it }
        // SDP has no (or a stale) record for FRAMES_UUID - try the hidden-API
        // fixed-channel path before falling back to pre-v1.0.2 firmware.
        tryConnect { rfcommSocketOnChannel(device, FRAMES_CHANNEL) }
            ?.let { return it }
        val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
        s.connect()
        return s
    }

    /** A half-opened socket from a failed connect() must be closed, or the stack leaks it. */
    private fun tryConnect(create: () -> BluetoothSocket): BluetoothSocket? {
        val s = create()
        return try {
            s.connect()
            s
        } catch (e: IOException) {
            runCatching { s.close() }
            null
        }
    }

    /**
     * device.createRfcommSocket(int) isn't in the public SDK - it skips the SDP
     * lookup and binds a fixed channel directly. Non-public API, used only as a
     * fallback when the proper SDP-by-UUID connect above has already failed.
     */
    private fun rfcommSocketOnChannel(device: BluetoothDevice, channel: Int): BluetoothSocket {
        val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        return m.invoke(device, channel) as BluetoothSocket
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw IOException("Bluetooth stream closed")
            off += n
        }
    }

    private fun closeQuietly() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        /** Pre-v1.0.2 firmware: our frames were the only thing on the plain SPP record. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /**
         * v1.0.2+ firmware: our frames moved to channel 2. Published under the
         * Dial-up Networking class purely as a distinct SDP label so the phone
         * can tell this record apart from the radio's own CAT (CI-V) service,
         * which now sits on channel 1 under the Serial Port UUID above.
         */
        val FRAMES_UUID: UUID = UUID.fromString("00001103-0000-1000-8000-00805F9B34FB")
        private const val FRAMES_CHANNEL = 2

        private const val INITIAL_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 30_000L
    }
}
