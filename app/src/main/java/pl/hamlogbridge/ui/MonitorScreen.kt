package pl.hamlogbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pl.hamlogbridge.data.AppSettings
import pl.hamlogbridge.data.DecodeRow
import pl.hamlogbridge.net.BtPacketSource
import pl.hamlogbridge.ui.theme.Amber
import pl.hamlogbridge.ui.theme.MonoBig
import pl.hamlogbridge.ui.theme.MonoSmall
import pl.hamlogbridge.ui.theme.SignalRed
import pl.hamlogbridge.ui.theme.TextMuted
import java.util.Locale

@Composable
fun MonitorScreen(vm: BridgeViewModel) {
    val running by vm.running.collectAsState()
    val port by vm.boundPort.collectAsState()
    val packets by vm.packetCount.collectAsState()
    val lastPacket by vm.lastPacketAt.collectAsState()
    val error by vm.lastError.collectAsState()
    val status by vm.rigStatus.collectAsState()
    val decodes by vm.decodes.collectAsState()
    val settings by vm.settings.collectAsState()
    val trace by vm.trace.collectAsState()
    val btState by vm.btState.collectAsState()
    val btMessage by vm.btMessage.collectAsState()
    var lookupCall by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Spacer(Modifier.height(10.dp))

        // Docked: stays put while the decode feed below scrolls.
        PanelCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        formatHz(status?.dialFrequencyHz ?: 0L),
                        style = MonoBig,
                        color = if (running) MaterialTheme.colorScheme.primary else TextMuted
                    )
                    Text("MHz dial", style = MonoSmall, color = TextMuted)
                }
                Column(horizontalAlignment = Alignment.End) {
                    when {
                        error != null -> Pill("error", SignalRed)
                        status?.transmitting == true -> Pill("transmitting", Amber)
                        running -> Pill(
                            when {
                                settings.connectionMode == AppSettings.MODE_BLUETOOTH ->
                                    if (btState == BtPacketSource.State.CONNECTED) "BT connected" else "waiting for BT"
                                port != null -> "listening :$port"
                                else -> "starting"
                            },
                            MaterialTheme.colorScheme.primary
                        )
                        else -> Pill("stopped", TextMuted)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "$packets packets - ${ageText(lastPacket)}",
                        style = MonoSmall, color = TextMuted
                    )
                    if (settings.connectionMode == AppSettings.MODE_BOTH) {
                        Spacer(Modifier.height(6.dp))
                        Pill(
                            "BT " + when (btState) {
                                BtPacketSource.State.CONNECTED -> "connected"
                                BtPacketSource.State.CONNECTING -> "connecting"
                                BtPacketSource.State.FAILED -> btMessage ?: "failed"
                                BtPacketSource.State.IDLE -> "idle"
                            },
                            when (btState) {
                                BtPacketSource.State.CONNECTED -> MaterialTheme.colorScheme.primary
                                BtPacketSource.State.CONNECTING -> Amber
                                BtPacketSource.State.FAILED -> SignalRed
                                BtPacketSource.State.IDLE -> TextMuted
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            KeyValue("source", status?.programId ?: "waiting for first packet")
            KeyValue("mode", status?.mode?.ifBlank { "-" } ?: "-")
            KeyValue(
                "de", listOfNotNull(
                    status?.deCall?.takeIf { it.isNotBlank() },
                    status?.deGrid?.takeIf { it.isNotBlank() }
                ).joinToString(" ").ifBlank { settings.myCall.ifBlank { "-" } }
            )
            KeyValue("working", status?.dxCall?.ifBlank { "-" } ?: "-")

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MonoSmall, color = SignalRed)
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.toggle() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.primary,
                    contentColor = if (running) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onPrimary
                )
            ) { Text(if (running) "Stop listening" else "Start listening") }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SectionLabel("Band activity")
            Text("${decodes.size} decodes", style = MonoSmall, color = TextMuted)
        }

        // Only this feed scrolls with the finger - the panel above and the
        // trace below stay where they are.
        Box(Modifier.weight(1f)) {
            if (decodes.isEmpty()) {
                PanelCard {
                    Text(
                        when {
                            !running -> "Start listening, then send a decode from the radio."
                            settings.connectionMode == AppSettings.MODE_BLUETOOTH ->
                                "Nothing decoded yet. Waiting for the paired radio over Bluetooth."
                            else ->
                                "Nothing decoded yet. Point the radio's UDP output at this phone's IP on port ${port ?: settings.udpPort}."
                        },
                        style = MonoSmall, color = TextMuted
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    item { DecodeHeader() }
                    items(decodes) { row ->
                        DecodeLine(row, settings.myCall) { call -> lookupCall = call }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        SectionLabel("Event trace")
        LazyColumn(Modifier.fillMaxWidth().height(120.dp)) {
            items(trace.take(30)) { line ->
                Text(
                    line, style = MonoSmall, color = TextMuted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
    }

    lookupCall?.let { call -> QrzLookupDialog(call = call, onDismiss = { lookupCall = null }) }
}

@Composable
private fun DecodeHeader() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("UTC", style = MonoSmall, color = TextMuted, modifier = Modifier.width(68.dp))
        Text("dB", style = MonoSmall, color = TextMuted, modifier = Modifier.width(68.dp))
        Text("DT", style = MonoSmall, color = TextMuted, modifier = Modifier.width(40.dp))
        Text("Hz", style = MonoSmall, color = TextMuted, modifier = Modifier.width(44.dp))
        Text("message", style = MonoSmall, color = TextMuted, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DecodeLine(d: DecodeRow, myCall: String, onTap: (String) -> Unit) {
    val cq = d.text.trimStart().startsWith("CQ")
    val mine = containsCallsign(d.text, myCall)
    val messageColor = when {
        mine -> MaterialTheme.colorScheme.secondary
        cq -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val rowBackground = when {
        mine -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f)
        cq -> MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
        else -> Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(rowBackground)
            .clickable(enabled = true) { extractDxCall(d.text, myCall)?.let(onTap) }
            .padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            d.utc, style = MonoSmall, color = TextMuted, maxLines = 1, softWrap = false,
            modifier = Modifier.width(68.dp)
        )
        Row(Modifier.width(68.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                String.format(Locale.ROOT, "%+3d", d.snr),
                style = MonoSmall, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, softWrap = false,
                modifier = Modifier.width(30.dp)
            )
            SnrBar(d.snr)
        }
        Text(
            String.format(Locale.ROOT, "%+.1f", d.dt),
            style = MonoSmall, color = TextMuted, maxLines = 1, softWrap = false,
            modifier = Modifier.width(40.dp)
        )
        Text(
            "${d.dfHz}", style = MonoSmall, color = TextMuted, maxLines = 1, softWrap = false,
            modifier = Modifier.width(44.dp)
        )
        Text(
            d.text,
            style = MonoSmall,
            fontWeight = if (mine || cq) FontWeight.Bold else FontWeight.Normal,
            color = messageColor,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
    Box(
        Modifier.fillMaxWidth().height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    )
}
