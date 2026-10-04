package pl.hamlogbridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.hamlogbridge.ui.AboutDialog
import pl.hamlogbridge.ui.BridgeViewModel
import pl.hamlogbridge.ui.LogScreen
import pl.hamlogbridge.ui.MonitorScreen
import pl.hamlogbridge.ui.SetupScreen
import pl.hamlogbridge.ui.theme.Amber
import pl.hamlogbridge.ui.theme.HamLogBridgeTheme
import pl.hamlogbridge.ui.theme.Mono
import pl.hamlogbridge.ui.theme.MonoSmall
import pl.hamlogbridge.ui.theme.SignalRed
import pl.hamlogbridge.ui.theme.TextMuted

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askForNotifications()
        setContent {
            HamLogBridgeTheme {
                Root()
            }
        }
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/**
 * finishAffinity() alone leaves the process cached and the task in Recents, so
 * the app still shows up as "running" in system settings. Kill the process too -
 * but only after BridgeService.onDestroy has released its locks and removed the
 * notification, otherwise we race it. Pending uploads are safe: WorkManager keeps
 * them in its own database and relaunches the process when it's time to retry.
 */
private fun exitCompletely(activity: ComponentActivity, vm: BridgeViewModel) {
    val repo = (activity.application as App).repo
    vm.stop()
    activity.finishAndRemoveTask()
    val main = Handler(Looper.getMainLooper())
    val deadline = SystemClock.uptimeMillis() + 2000
    fun killWhenStopped() {
        if (repo.serviceRunning.value && SystemClock.uptimeMillis() < deadline) {
            main.postDelayed(::killWhenStopped, 100)
        } else {
            Process.killProcess(Process.myPid())
        }
    }
    // Give the task-removal animation a moment so the kill isn't visible as a crash.
    main.postDelayed(::killWhenStopped, 300)
}

private enum class Tab(val label: String) { Monitor("Monitor"), Log("Log"), Setup("Setup") }

@Composable
private fun Root(vm: BridgeViewModel = viewModel()) {
    var showSplash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(1300)
        showSplash = false
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = !showSplash,
            enter = fadeIn(tween(300))
        ) { MainScreen(vm) }

        AnimatedVisibility(
            visible = showSplash,
            exit = fadeOut(tween(300))
        ) { SplashScreen() }
    }
}

@Composable
private fun SplashScreen() {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(96.dp)
            )
            Spacer(Modifier.height(18.dp))
            Text("RigLink", style = Mono.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold), color = Amber)
            Spacer(Modifier.height(4.dp))
            Text("by SP2KMO", style = MonoSmall, color = TextMuted)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(vm: BridgeViewModel) {
    val pagerState = rememberPagerState { Tab.entries.size }
    val pagerScope = rememberCoroutineScope()
    var showAbout by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    val settings by vm.settings.collectAsState()
    val boundPort by vm.boundPort.collectAsState()
    val running by vm.running.collectAsState()
    val activity = LocalContext.current as ComponentActivity

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("RigLink", style = Mono.copy(fontWeight = FontWeight.Bold), color = Amber)
                        Text("by SP2KMO", style = MonoSmall, color = TextMuted)
                    }
                },
                actions = {
                    IconButton(onClick = { showAbout = true }) {
                        Icon(Icons.Default.Info, contentDescription = "About")
                    }
                    IconButton(onClick = { showExitConfirm = true }) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Exit")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { index, t ->
                    NavigationBarItem(
                        selected = pagerState.currentPage == index,
                        onClick = { pagerScope.launch { pagerState.animateScrollToPage(index) } },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.Monitor -> Icons.Default.GraphicEq
                                    Tab.Log -> Icons.Default.Book
                                    Tab.Setup -> Icons.Default.Tune
                                }, contentDescription = t.label
                            )
                        },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { inner ->
        // Swipeable tabs - the nav bar above stays in sync with the page in view.
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().padding(inner)) { page ->
            when (Tab.entries[page]) {
                Tab.Monitor -> MonitorScreen(vm)
                Tab.Log -> LogScreen(vm)
                Tab.Setup -> SetupScreen(vm)
            }
        }
    }

    if (showAbout) {
        AboutDialog(settings = settings, boundPort = boundPort, onDismiss = { showAbout = false })
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("Exit HamLog Bridge?") },
            text = {
                Text(
                    if (running)
                        "This stops listening for the radio and closes the app completely."
                    else
                        "Closes the app completely."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    exitCompletely(activity, vm)
                }) { Text("Exit", color = SignalRed) }
            },
            dismissButton = { TextButton(onClick = { showExitConfirm = false }) { Text("Cancel") } }
        )
    }
}
