package pl.hamlogbridge.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.hamlogbridge.App
import pl.hamlogbridge.data.AppSettings
import pl.hamlogbridge.data.QsoEntity
import pl.hamlogbridge.data.TargetCfg
import pl.hamlogbridge.data.UploadEntity
import pl.hamlogbridge.service.BridgeService
import pl.hamlogbridge.upload.Targets
import pl.hamlogbridge.upload.UploadResult

class BridgeViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as App).repo

    val settings = repo.settings.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val decodes = repo.decodes
    val rigStatus = repo.rigStatus
    val running = repo.serviceRunning
    val boundPort = repo.boundPort
    val packetCount = repo.packetCount
    val lastPacketAt = repo.lastPacketAt
    val lastError = repo.lastError
    val trace = repo.trace
    val btState = repo.btState
    val btMessage = repo.btMessage

    val qsos = repo.db.qsoDao().recent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<QsoEntity>())

    val uploads = repo.db.uploadDao().recent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<UploadEntity>())

    val testResult = MutableStateFlow<Pair<String, String>?>(null)

    fun start() = BridgeService.start(getApplication())
    fun stop() = BridgeService.stop(getApplication())

    fun toggle() {
        if (running.value) stop() else start()
    }

    /** Port and multicast changes need a socket rebind. */
    fun restartIfRunning() {
        if (running.value) {
            stop()
            viewModelScope.launch {
                kotlinx.coroutines.delay(400)
                start()
            }
        }
    }

    fun edit(block: (AppSettings) -> AppSettings) = viewModelScope.launch {
        repo.settings.update(block)
    }

    fun setTargetEnabled(id: String, enabled: Boolean) = edit { s ->
        val cfg = s.cfg(id)
        s.copy(targets = s.targets + (id to cfg.copy(enabled = enabled)))
    }

    fun setTargetParam(id: String, key: String, value: String) = edit { s ->
        val cfg = s.cfg(id)
        s.copy(targets = s.targets + (id to cfg.copy(params = cfg.params + (key to value))))
    }

    fun testTarget(id: String) = viewModelScope.launch {
        val target = Targets.byId(id) ?: return@launch
        val cfg = settings.value.cfg(id)
        val missing = target.missingFields(cfg.params)
        if (missing.isNotEmpty()) {
            testResult.value = id to "Fill in: ${missing.joinToString { it.label }}"
            return@launch
        }
        testResult.value = id to "Checking..."
        val res = withContext(Dispatchers.IO) { target.test(cfg.params) }
        testResult.value = id to when (res) {
            is UploadResult.Ok -> "OK - ${res.message}"
            is UploadResult.Retry -> "Cannot reach it - ${res.message}"
            is UploadResult.Fatal -> "Rejected - ${res.message}"
        }
    }

    fun retryFailed() = viewModelScope.launch { repo.retryFailed() }
    fun resend(qsoId: Long) = viewModelScope.launch { repo.resend(qsoId) }
    fun clearLog() = viewModelScope.launch {
        repo.db.uploadDao().deleteAll()
        repo.db.qsoDao().deleteAll()
    }

    fun adifFile() = repo.exportFile()

    fun defaultTargetCfg(id: String): TargetCfg = settings.value.cfg(id)
}
