package pl.hamlogbridge.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import pl.hamlogbridge.data.AppSettings
import pl.hamlogbridge.net.BtPacketSource
import pl.hamlogbridge.ui.theme.Amber
import pl.hamlogbridge.ui.theme.Mono
import pl.hamlogbridge.ui.theme.MonoSmall
import pl.hamlogbridge.ui.theme.SignalRed
import pl.hamlogbridge.ui.theme.TextMuted
import pl.hamlogbridge.upload.Targets

@Composable
fun SetupScreen(vm: BridgeViewModel) {
    val s by vm.settings.collectAsState()
    val testResult by vm.testResult.collectAsState()
    val ctx = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Spacer(Modifier.height(10.dp)) }

        item { SectionLabel("Station") }
        item {
            PanelCard {
                Field("Callsign", s.myCall) { v -> vm.edit { it.copy(myCall = v.uppercase()) } }
                Field("Grid square", s.myGrid) { v -> vm.edit { it.copy(myGrid = v.uppercase()) } }
                Field("Operator (if different)", s.operator) { v -> vm.edit { it.copy(operator = v.uppercase()) } }
                Field("TX power in watts", s.txPowerW, numeric = true) { v -> vm.edit { it.copy(txPowerW = v) } }
                Field("Rig", s.rigName) { v -> vm.edit { it.copy(rigName = v) } }
                Field("Antenna", s.antenna) { v -> vm.edit { it.copy(antenna = v) } }
                Text(
                    "These fill in any field the radio leaves empty. The X6100 firmware usually sends only the callsign and grid.",
                    style = MonoSmall, color = TextMuted, modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        item { SectionLabel("Connection") }
        item {
            PanelCard {
                Text(
                    "How this phone receives WSJT-X datagrams from the radio.",
                    style = MonoSmall, color = TextMuted
                )
                Spacer(Modifier.height(8.dp))
                ConnectionModeRow(s.connectionMode) { mode ->
                    vm.edit { it.copy(connectionMode = mode) }
                    vm.restartIfRunning()
                }
                if (s.connectionMode != AppSettings.MODE_WIFI) {
                    Spacer(Modifier.height(12.dp))
                    BluetoothDevicePicker(vm, s)
                }
            }
        }

        item { SectionLabel("Receive") }
        item {
            PanelCard {
                Field("UDP port", s.udpPort.toString(), numeric = true) { v ->
                    v.toIntOrNull()?.takeIf { it in 1..65535 }?.let { p ->
                        vm.edit { it.copy(udpPort = p) }
                        vm.restartIfRunning()
                    }
                }
                Field(
                    "Multicast group (leave empty for unicast/broadcast)",
                    s.multicastGroup
                ) { v ->
                    vm.edit { it.copy(multicastGroup = v.trim()) }
                    vm.restartIfRunning()
                }
                Field(
                    "Relay datagrams to (host:port, comma separated)",
                    s.relayTargets
                ) { v ->
                    vm.edit { it.copy(relayTargets = v) }
                    vm.restartIfRunning()
                }
                Text(
                    "Relaying passes every packet on untouched, so GridTracker or Log4OM on a PC keeps working at the same time.",
                    style = MonoSmall, color = TextMuted, modifier = Modifier.padding(top = 6.dp)
                )
                Spacer(Modifier.height(8.dp))
                ToggleRow("Start with the phone", s.autoStart) { v -> vm.edit { it.copy(autoStart = v) } }
            }
        }

        item { SectionLabel("Loggers") }
        items(Targets.all.size) { idx ->
            val target = Targets.all[idx]
            val cfg = s.cfg(target.id)
            PanelCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.fillMaxWidth(0.75f)) {
                        Text(target.title, style = Mono, color = MaterialTheme.colorScheme.onSurface)
                        Text(target.blurb, style = MonoSmall, color = TextMuted)
                    }
                    Switch(
                        checked = cfg.enabled,
                        onCheckedChange = { vm.setTargetEnabled(target.id, it) }
                    )
                }

                if (cfg.enabled) {
                    Spacer(Modifier.height(8.dp))
                    target.fields.forEach { f ->
                        Field(
                            label = f.label + if (f.required) "" else " (optional)",
                            value = cfg.params[f.key] ?: f.default,
                            hint = f.hint,
                            secret = f.secret
                        ) { v -> vm.setTargetParam(target.id, f.key, v) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { vm.testTarget(target.id) }) { Text("Check connection") }
                        testResult?.takeIf { it.first == target.id }?.let { (_, msg) ->
                            Text(
                                msg, style = MonoSmall,
                                color = when {
                                    msg.startsWith("OK") -> MaterialTheme.colorScheme.primary
                                    msg.startsWith("Checking") -> Amber
                                    else -> SignalRed
                                }
                            )
                        }
                    }
                }
            }
        }

        item { SectionLabel("Local copy") }
        item {
            PanelCard {
                Text(
                    "Every QSO is also appended to an ADIF file on this phone, whatever the uploads do.",
                    style = MonoSmall, color = TextMuted
                )
                Spacer(Modifier.height(4.dp))
                Text(vm.adifFile().absolutePath, style = MonoSmall, color = TextMuted)
                Spacer(Modifier.height(6.dp))
                Row {
                    TextButton(onClick = {
                        val f = vm.adifFile()
                        if (!f.exists()) return@TextButton
                        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(Intent.createChooser(send, "Share ADIF"))
                    }) { Text("Share ADIF file") }
                    TextButton(onClick = { confirmClear = true }) {
                        Text("Clear log", color = SignalRed)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the in-app log?") },
            text = { Text("Removes every contact and upload record from this app. The ADIF file on disk stays.") },
            confirmButton = {
                TextButton(onClick = { vm.clearLog(); confirmClear = false }) {
                    Text("Clear", color = SignalRed)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep") } }
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    hint: String = "",
    numeric: Boolean = false,
    secret: Boolean = false,
    onChange: (String) -> Unit
) {
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (value != text) text = value }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        label = { Text(label, style = MonoSmall) },
        placeholder = { if (hint.isNotBlank()) Text(hint, style = MonoSmall, color = TextMuted) },
        singleLine = !label.contains("headers"),
        textStyle = Mono,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
            imeAction = ImeAction.Next
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    )
}

@Composable
private fun ConnectionModeRow(current: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeOption("Wi-Fi (UDP)", current == AppSettings.MODE_WIFI, Modifier.weight(1f)) {
            onSelect(AppSettings.MODE_WIFI)
        }
        ModeOption("Bluetooth", current == AppSettings.MODE_BLUETOOTH, Modifier.weight(1f)) {
            onSelect(AppSettings.MODE_BLUETOOTH)
        }
        ModeOption("Both", current == AppSettings.MODE_BOTH, Modifier.weight(1f)) {
            onSelect(AppSettings.MODE_BOTH)
        }
    }
}

@Composable
private fun ModeOption(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
    ) {
        Text(
            label, style = MonoSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
    }
}

@Composable
private fun BluetoothDevicePicker(vm: BridgeViewModel, s: AppSettings) {
    val ctx = LocalContext.current
    var showList by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    val btState by vm.btState.collectAsState()
    val btMessage by vm.btMessage.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) { permissionDenied = false; showList = true } else permissionDenied = true }

    Text("Paired Bluetooth SPP device", style = Mono, color = MaterialTheme.colorScheme.onSurface)
    Text(
        "Pairing itself happens in Android's Bluetooth settings - the radio pairs in Just Works mode. Pick it here once paired.",
        style = MonoSmall, color = TextMuted
    )
    Spacer(Modifier.height(6.dp))
    Text(
        if (s.btDeviceName.isNotBlank() || s.btDeviceAddress.isNotBlank())
            "${s.btDeviceName.ifBlank { "?" }}  ${s.btDeviceAddress}"
        else "No device selected",
        style = MonoSmall, color = TextMuted
    )
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = {
            if (Build.VERSION.SDK_INT >= 31 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                showList = true
            }
        }) { Text("Choose device") }

        when (btState) {
            BtPacketSource.State.CONNECTED -> Pill("connected", MaterialTheme.colorScheme.primary)
            BtPacketSource.State.CONNECTING -> Pill("connecting", Amber)
            BtPacketSource.State.FAILED -> Pill(btMessage ?: "failed", SignalRed)
            BtPacketSource.State.IDLE -> Unit
        }
    }
    if (permissionDenied) {
        Spacer(Modifier.height(4.dp))
        Text(
            "Bluetooth permission is required to list paired devices.",
            style = MonoSmall, color = SignalRed
        )
    }

    if (showList) {
        BondedDeviceDialog(
            onDismiss = { showList = false },
            onSelect = { device ->
                vm.edit { it.copy(btDeviceAddress = device.address, btDeviceName = device.name.orEmpty()) }
                vm.restartIfRunning()
                showList = false
            }
        )
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun BondedDeviceDialog(onDismiss: () -> Unit, onSelect: (BluetoothDevice) -> Unit) {
    val ctx = LocalContext.current
    val devices = remember {
        val bm = ctx.getSystemService(BluetoothManager::class.java)
        bm?.adapter?.bondedDevices?.toList().orEmpty()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paired devices") },
        text = {
            if (devices.isEmpty()) {
                Text(
                    "No paired devices. Pair the radio in Android's Bluetooth settings first.",
                    style = MonoSmall, color = TextMuted
                )
            } else {
                Column {
                    devices.forEach { d ->
                        Text(
                            "${d.name ?: "?"}  ${d.address}",
                            style = Mono,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(d) }
                                .padding(vertical = 10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = Mono, color = MaterialTheme.colorScheme.onSurface)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
