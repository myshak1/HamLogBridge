package pl.hamlogbridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import pl.hamlogbridge.App

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as App
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (app.repo.settingsSnapshot().autoStart) BridgeService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
