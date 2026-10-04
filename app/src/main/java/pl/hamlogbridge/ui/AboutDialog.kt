package pl.hamlogbridge.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.hamlogbridge.data.AppSettings
import pl.hamlogbridge.ui.theme.Amber
import pl.hamlogbridge.ui.theme.Mono
import pl.hamlogbridge.ui.theme.MonoSmall
import pl.hamlogbridge.ui.theme.TextMuted
import java.net.Inet4Address
import java.net.NetworkInterface

@Composable
fun AboutDialog(settings: AppSettings, boundPort: Int?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val version = remember { versionName(ctx) }
    val localIp = remember { localIpv4() }
    val port = boundPort ?: settings.udpPort
    val address = settings.multicastGroup.ifBlank { localIp ?: "this phone's IP" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("RigLink", style = Mono, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Bridges WSJT-X-protocol UDP decodes and QSOs from your radio into online " +
                        "logbooks - a Xiegu X6100 with the FT8 UDP broadcaster, or WSJT-X/JTDX on a PC.",
                    style = MonoSmall, color = TextMuted
                )
                Spacer(Modifier.height(14.dp))
                SectionLabel("How to connect")
                AboutStep(1, "Phone and radio on the same Wi-Fi, or the radio connected to this phone's hotspot.")
                AboutStep(2, "In the radio's UDP settings, enter:")
                Spacer(Modifier.height(4.dp))
                KeyValue("address", address, valueColor = Amber)
                KeyValue("port", port.toString(), valueColor = Amber)
                Spacer(Modifier.height(6.dp))
                AboutStep(3, "Start listening on the Monitor tab, then log a QSO - it should appear within a couple of seconds.")
                Spacer(Modifier.height(14.dp))
                SectionLabel("Author")
                KeyValue("callsign", "Kris, SP2KMO")
                KeyValue("email", "sp2kmo@gmail.com")
                Spacer(Modifier.height(10.dp))
                Text("version $version", style = MonoSmall, color = TextMuted)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun AboutStep(n: Int, text: String) {
    Text(
        "$n. $text",
        style = MonoSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 4.dp)
    )
}

private fun versionName(ctx: Context): String = runCatching {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
}.getOrDefault("?")

/** Best-effort address for display only - the bridge's own socket binding is unaffected. */
private fun localIpv4(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress }
        ?.hostAddress
}.getOrNull()
