package pl.hamlogbridge

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import pl.hamlogbridge.data.Repository

class App : Application() {

    lateinit var repo: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        repo = Repository(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shows that the UDP bridge is listening." }
        )
    }

    companion object {
        const val CHANNEL_ID = "bridge"
    }
}
