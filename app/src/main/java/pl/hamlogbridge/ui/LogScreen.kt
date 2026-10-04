package pl.hamlogbridge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.hamlogbridge.data.QsoEntity
import pl.hamlogbridge.data.UploadEntity
import pl.hamlogbridge.data.UploadStatus
import pl.hamlogbridge.ui.theme.Amber
import pl.hamlogbridge.ui.theme.Mono
import pl.hamlogbridge.ui.theme.MonoSmall
import pl.hamlogbridge.ui.theme.SignalRed
import pl.hamlogbridge.ui.theme.TextMuted
import pl.hamlogbridge.upload.Targets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogScreen(vm: BridgeViewModel) {
    val qsos by vm.qsos.collectAsState()
    val uploads by vm.uploads.collectAsState()
    val byQso = remember(uploads) { uploads.groupBy { it.qsoId } }
    var expanded by remember { mutableStateOf<Long?>(null) }

    val failed = uploads.count { it.status == UploadStatus.FAILED }
    val pending = uploads.count { it.status == UploadStatus.PENDING }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Spacer(Modifier.height(10.dp)) }
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    SectionLabel("Contacts")
                    Text("${qsos.size} logged", style = Mono, color = TextMuted)
                }
                Row {
                    if (pending > 0) Pill("$pending queued", Amber)
                    if (failed > 0) {
                        Spacer(Modifier.padding(3.dp))
                        TextButton(onClick = { vm.retryFailed() }) { Text("Retry $failed") }
                    }
                }
            }
        }

        if (qsos.isEmpty()) {
            item {
                PanelCard {
                    Text(
                        "No contacts yet. Every QSO the radio logs over UDP lands here and goes out to the loggers you enabled in Setup.",
                        style = MonoSmall, color = TextMuted
                    )
                }
            }
        }

        items(qsos, key = { it.id }) { qso ->
            QsoCard(
                qso = qso,
                uploads = byQso[qso.id].orEmpty(),
                expanded = expanded == qso.id,
                onToggle = { expanded = if (expanded == qso.id) null else qso.id },
                onResend = { vm.resend(qso.id) }
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QsoCard(
    qso: QsoEntity,
    uploads: List<UploadEntity>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onResend: () -> Unit
) {
    PanelCard(Modifier.clickable { onToggle() }) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    qso.call,
                    style = Mono.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    listOfNotNull(
                        qso.band, qso.mode, qso.grid?.takeIf { it.isNotBlank() }
                    ).joinToString("  "),
                    style = MonoSmall, color = TextMuted
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stamp.format(Instant.ofEpochMilli(qso.timeOnEpoch)), style = MonoSmall, color = TextMuted)
                Text(
                    "sent ${qso.rstSent.orEmpty()}  rcvd ${qso.rstRcvd.orEmpty()}",
                    style = MonoSmall, color = TextMuted
                )
            }
        }

        if (uploads.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                uploads.forEach { u ->
                    val colour = when (u.status) {
                        UploadStatus.OK -> MaterialTheme.colorScheme.primary
                        UploadStatus.PENDING -> Amber
                        UploadStatus.FAILED -> SignalRed
                        else -> TextMuted
                    }
                    Pill(Targets.titleOf(u.targetId).substringBefore(" /"), colour)
                }
            }
        }

        if (expanded) {
            Spacer(Modifier.height(10.dp))
            uploads.forEach { u ->
                KeyValue(
                    Targets.titleOf(u.targetId),
                    (u.lastMessage ?: u.status).take(46),
                    when (u.status) {
                        UploadStatus.OK -> MaterialTheme.colorScheme.primary
                        UploadStatus.FAILED -> SignalRed
                        UploadStatus.PENDING -> Amber
                        else -> TextMuted
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(qso.adif.trim(), style = MonoSmall, color = TextMuted)
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onResend) { Text("Send again to enabled loggers") }
        }
    }
}
