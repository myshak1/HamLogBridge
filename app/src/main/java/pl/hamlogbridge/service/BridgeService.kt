package pl.hamlogbridge.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.hamlogbridge.App
import pl.hamlogbridge.MainActivity
import pl.hamlogbridge.R
import pl.hamlogbridge.data.AppSettings
import pl.hamlogbridge.net.BtPacketSource
import pl.hamlogbridge.net.PacketDeduper
import pl.hamlogbridge.net.UdpListener
import pl.hamlogbridge.net.UdpRelay
import pl.hamlogbridge.wsjtx.WsjtxCodec
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * Foreground service that owns the WSJT-X datagram sources - UDP, Bluetooth
 * SPP, or both, per AppSettings.connectionMode. Android aggressively parks
 * Wi-Fi and the CPU when the screen is off, so we hold a Wi-Fi lock, a
 * multicast lock (needed for broadcast reception on many vendors) and a
 * partial wake lock regardless of which source is active - Bluetooth needs
 * the CPU awake just as much as the socket does.
 */
class BridgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listener: UdpListener? = null
    private var btSource: BtPacketSource? = null
    private val relay = UdpRelay()
    private var deduper: PacketDeduper? = null

    // Guards against a double "Start" (e.g. a fast double-tap before the UI
    // reflects serviceRunning=true) launching duplicate sources.
    @Volatile private var starting = false

    // Sources report their final state (e.g. BT IDLE) from their own threads
    // while onDestroy is tearing them down. Without this flag that late
    // callback re-posts the ongoing notification after the service is gone,
    // leaving an orphan "BT idle" entry nothing will ever remove.
    @Volatile private var destroyed = false

    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val repo get() = (application as App).repo

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Also clears an orphaned notification left by an older build.
            cancelNotification()
            stopSelf()
            return START_NOT_STICKY
        }
        if (starting || listener != null || btSource != null) return START_STICKY
        starting = true
        startForeground(NOTIF_ID, buildNotification("Starting..."))
        acquireLocks()
        scope.launch { startListening() }
        return START_STICKY
    }

    private suspend fun startListening() {
        listener?.stop(); listener = null
        btSource?.stop(); btSource = null
        val s = repo.settingsSnapshot()
        relay.configure(s.relayTargets)
        deduper = if (s.connectionMode == AppSettings.MODE_BOTH) PacketDeduper() else null

        val useWifi = s.connectionMode != AppSettings.MODE_BLUETOOTH
        val useBt = s.connectionMode != AppSettings.MODE_WIFI

        if (useWifi) startUdp(s) else repo.boundPort.value = null
        if (useBt) startBt(s) else {
            repo.btState.value = BtPacketSource.State.IDLE
            repo.btMessage.value = null
        }

        starting = false
        repo.serviceRunning.value = true
        repo.lastError.value = null
        repo.note("Bridge starting (${s.connectionMode})")
        updateNotification(statusText())
    }

    private fun startUdp(s: AppSettings) {
        val group = s.multicastGroup.takeIf { it.isNotBlank() }
        val l = UdpListener(s.udpPort, group, networkInterface = group?.let { wifiNetworkInterface() })
        listener = l
        l.start(scope, object : UdpListener.Sink {
            override fun onBound(port: Int) {
                repo.boundPort.value = port
                repo.note("Listening on UDP $port")
                updateNotification(statusText())
            }

            // The receive loop only reaches here on an unexpected failure (a
            // deliberate stop() suppresses this callback) - e.g. a transient
            // bind/receive error around a Wi-Fi roam or DHCP renewal. Retry
            // instead of leaving the socket dead until the user manually
            // stops and starts the service again. Only the UDP side is
            // restarted - Bluetooth, if active, is unaffected.
            override fun onError(t: Throwable) {
                listener = null
                repo.boundPort.value = null
                repo.lastError.value = t.message ?: t.toString()
                repo.note("Socket error: ${t.message} - retrying in 3s")
                updateNotification(statusText())
                scope.launch {
                    delay(3000)
                    startUdp(s)
                }
            }

            override fun onDatagram(data: ByteArray, length: Int, from: InetSocketAddress) {
                handleIncoming(data, length, from.hostString)
            }
        })
    }

    private fun startBt(s: AppSettings) {
        val addr = s.btDeviceAddress
        if (addr.isBlank()) {
            repo.btState.value = BtPacketSource.State.FAILED
            repo.btMessage.value = "No Bluetooth device selected"
            return
        }
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            repo.btState.value = BtPacketSource.State.FAILED
            repo.btMessage.value = "Bluetooth permission not granted"
            repo.note("Bluetooth permission missing")
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            repo.btState.value = BtPacketSource.State.FAILED
            repo.btMessage.value = "Bluetooth is off"
            return
        }

        val bt = BtPacketSource(adapter, addr)
        btSource = bt
        bt.start(scope, object : BtPacketSource.Sink {
            override fun onState(state: BtPacketSource.State, message: String?) {
                repo.btState.value = state
                repo.btMessage.value = message
                when (state) {
                    BtPacketSource.State.CONNECTED ->
                        repo.note("Bluetooth connected to ${s.btDeviceName.ifBlank { addr }}")
                    BtPacketSource.State.FAILED -> message?.let { repo.note("Bluetooth: $it") }
                    else -> Unit
                }
                updateNotification(statusText())
            }

            override fun onDatagram(data: ByteArray, length: Int) {
                handleIncoming(data, length, "BT ${s.btDeviceName.ifBlank { addr }}")
            }
        })
    }

    private fun handleIncoming(data: ByteArray, length: Int, sourceLabel: String) {
        if (deduper?.isDuplicate(data, length) == true) return
        relay.send(data, length)
        scope.launch {
            try {
                val env = WsjtxCodec.decode(data, length)
                if (env == null) {
                    repo.note("Ignored ${length}B from $sourceLabel (not WSJT-X)")
                    return@launch
                }
                repo.onPacket(env.type, sourceLabel)
                repo.handle(env.message)
            } catch (e: Exception) {
                repo.note("Decode failed from $sourceLabel: ${e.message}")
            }
        }
    }

    private fun statusText(): String {
        val parts = mutableListOf<String>()
        if (listener != null) repo.boundPort.value?.let { parts += "UDP $it" }
        if (btSource != null) parts += "BT ${repo.btState.value.name.lowercase()}"
        return parts.joinToString(" / ").ifBlank { "Starting..." }
    }

    /**
     * The Wi-Fi network's interface, for multicast joins that must not be left to OS guesswork.
     *
     * ConnectivityManager only reports TRANSPORT_WIFI when the phone is a Wi-Fi *client*. When
     * the phone is instead the hotspot (radio connected directly to it, no router), the AP
     * interface never shows up in cm.allNetworks — there's no "Network" object for it — so this
     * would silently return null and the multicast join falls back to OS interface guessing,
     * which on a phone with mobile data also active tends to pick the cellular interface and
     * never see the radio's packets at all. Fall back to scanning local interfaces directly:
     * the cellular interface is point-to-point (PPP-style), Wi-Fi/AP is not, so filtering that
     * out reliably finds the right interface in both client and hotspot mode.
     */
    private fun wifiNetworkInterface(): NetworkInterface? = runCatching {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiNetwork = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        val ifaceName = wifiNetwork?.let { cm.getLinkProperties(it)?.interfaceName }
        ifaceName?.let { NetworkInterface.getByName(it) }
    }.getOrNull() ?: runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence().firstOrNull { iface ->
            iface.isUp && !iface.isLoopback && !iface.isPointToPoint && iface.supportsMulticast() &&
                iface.inetAddresses.asSequence().any { !it.isLoopbackAddress && it is java.net.Inet4Address }
        }
    }.getOrNull()

    private fun acquireLocks() {
        releaseLocks()
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("hamlogbridge").apply {
            setReferenceCounted(false); acquire()
        }
        wifiLock = wifi.createWifiLock(
            if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "hamlogbridge"
        ).apply { setReferenceCounted(false); acquire() }

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hamlogbridge:udp").apply {
            setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseLocks() {
        runCatching { multicastLock?.release() }
        runCatching { wifiLock?.release() }
        runCatching { wakeLock?.release() }
        multicastLock = null; wifiLock = null; wakeLock = null
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle("HamLog Bridge")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        if (destroyed) return
        runCatching {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun cancelNotification() {
        runCatching { getSystemService(android.app.NotificationManager::class.java).cancel(NOTIF_ID) }
    }

    override fun onDestroy() {
        destroyed = true
        val l = listener; listener = null
        val bt = btSource; btSource = null
        l?.stop()
        bt?.stop()
        relay.close()
        releaseLocks()
        repo.serviceRunning.value = false
        repo.boundPort.value = null
        repo.btState.value = BtPacketSource.State.IDLE
        repo.btMessage.value = null
        repo.note("Bridge stopped")
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Belt and braces: a source callback already past the `destroyed`
        // check may have re-posted the notification a moment ago.
        cancelNotification()
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 42
        const val ACTION_STOP = "pl.hamlogbridge.STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, BridgeService::class.java))
        }
    }
}
