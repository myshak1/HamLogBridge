package pl.hamlogbridge.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import pl.hamlogbridge.App
import pl.hamlogbridge.data.UploadStatus
import java.util.concurrent.TimeUnit

/**
 * Drains the pending upload queue. Anything that comes back Retry is left
 * PENDING and the whole job is retried with exponential backoff; Fatal results
 * are marked FAILED so the user can fix credentials and requeue by hand.
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as App
        val repo = app.repo
        val pending = repo.db.uploadDao().byStatus(UploadStatus.PENDING)
        if (pending.isEmpty()) return Result.success()

        var needsRetry = false
        for (item in pending) {
            val qso = repo.db.qsoDao().byId(item.qsoId) ?: continue
            val target = Targets.byId(item.targetId) ?: continue
            val settings = repo.settingsSnapshot()
            val cfg = settings.cfg(item.targetId)
            if (!cfg.enabled) {
                repo.markUpload(item, UploadStatus.SKIPPED, "Target disabled")
                continue
            }
            val missing = target.missingFields(cfg.params)
            if (missing.isNotEmpty()) {
                repo.markUpload(item, UploadStatus.FAILED, "Missing: " + missing.joinToString { it.label })
                continue
            }

            when (val res = target.upload(cfg.params, qso.adif, qso)) {
                is UploadResult.Ok -> repo.markUpload(item, UploadStatus.OK, res.message)
                is UploadResult.Fatal -> repo.markUpload(item, UploadStatus.FAILED, res.message)
                is UploadResult.Retry -> {
                    val attempts = item.attempts + 1
                    if (attempts >= MAX_ATTEMPTS) {
                        repo.markUpload(item, UploadStatus.FAILED, "Gave up after $attempts tries: ${res.message}")
                    } else {
                        repo.markUpload(item, UploadStatus.PENDING, res.message, attempts)
                        needsRetry = true
                    }
                }
            }
        }
        return if (needsRetry) Result.retry() else Result.success()
    }

    companion object {
        private const val MAX_ATTEMPTS = 12
        const val WORK_NAME = "qso-upload"

        fun enqueue(ctx: Context, expedited: Boolean = true) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
