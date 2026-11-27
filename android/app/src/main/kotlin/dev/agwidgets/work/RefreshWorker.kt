package dev.agwidgets.work

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.agwidgets.BuildConfig
import dev.agwidgets.data.AgStatus
import dev.agwidgets.data.NtfyClient
import dev.agwidgets.data.StatusStore
import dev.agwidgets.data.ZCodeClient
import dev.agwidgets.data.ZCodeStatus
import dev.agwidgets.data.ZCodeStore
import dev.agwidgets.notify.Notifier
import dev.agwidgets.widget.LimitsWidget
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.TimeUnit

class RefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val anyNetworkFailure = performRefresh(applicationContext)
        return if (anyNetworkFailure && runAttemptCount < 2) Result.retry() else Result.success()
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "agw_periodic_refresh"
        private const val IMMEDIATE_WORK_NAME = "agw_immediate_refresh"
        internal val RETRYABLE_ZCODE_ERRORS = setOf("network", "server_error")

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }

        fun runNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(PERIODIC_WORK_NAME)
        }
    }
}

/**
 * One complete refresh pass: polls ntfy and the ZCode API concurrently, persists
 * the results and repaints the widget. Kept independent of WorkManager so the
 * widget's refresh button can run exactly the same work on the spot.
 *
 * @return `true` when at least one source failed in a retryable way.
 */
internal suspend fun performRefresh(
    context: Context,
    timeoutMs: Int = 15_000,
    updateWidget: Boolean = true,
): Boolean {
    val appContext = context.applicationContext
    val (ntfyNetworkFailure, zcodeNetworkFailure) = coroutineScope {
        val ntfy = async { refreshNtfy(appContext, timeoutMs) }
        val zcode = async { refreshZCode(appContext, timeoutMs) }
        ntfy.await() to zcode.await()
    }

    if (updateWidget) {
        LimitsWidget().updateAll(appContext)
    }
    return ntfyNetworkFailure || zcodeNetworkFailure
}

/** Polls the ntfy channel (Antigravity quotas + PC context summary). */
private suspend fun refreshNtfy(context: Context, timeoutMs: Int): Boolean {
    val s = StatusStore.resolveStrings(context)
    // ZCode-only mode has no PC to poll; an empty topic already skips the request.
    val mode = StatusStore.getMode(context)
    val topic = if (mode == StatusStore.MODE_ZCODE) "" else StatusStore.getTopic(context)
    if (topic.isBlank()) return false

    val res = NtfyClient.fetchLatestMessage(
        topic,
        timeoutMs,
        StatusStore.getNtfyServer(context),
        StatusStore.getE2eKey(context),
        s,
    )
    return res.fold(
        onSuccess = { jsonText ->
            val parsed = AgStatus.fromJson(jsonText)
            if (parsed != null) {
                val (prev, curr) = StatusStore.saveNewStatus(context, jsonText)
                if (curr != null) {
                    ResetAlertWorker.scheduleForBuckets(context, curr.buckets)
                    Notifier.checkAndNotify(context, prev, curr)
                }
            } else {
                StatusStore.saveError(context, s.workBadJson)
            }
            false
        },
        onFailure = { err ->
            StatusStore.saveError(context, err.message ?: s.workNtfyNetworkError)
            true
        }
    )
}

/** Direct ZCode API check (works autonomously on phone even when laptop is off). */
private suspend fun refreshZCode(context: Context, timeoutMs: Int): Boolean {
    if (!BuildConfig.ZCODE_ENABLED) return false
    val zcodeToken = ZCodeStore.getToken(context)
    if (zcodeToken.isBlank()) return false
    // After a 429 the platform asked us to wait; polling again re-arms the ban.
    if (ZCodeStore.shouldBackoffRateLimit(context)) return false

    val s = StatusStore.resolveStrings(context)
    val deviceMid = ZCodeStore.getOrCreateDeviceMid(context)
    val seq = ZCodeStore.nextSeq(context)
    val previous = ZCodeStore.getDirect(context)
    return ZCodeClient.fetchStatus(zcodeToken, deviceMid, seq, previous, timeoutMs)
        .fold(
            onSuccess = { zStatus ->
                val (prevZ, currZ) = ZCodeStore.saveDirect(context, zStatus)
                Notifier.checkZCodeCouponTransition(context, prevZ, currZ)
                if (zStatus.networkError || zStatus.errorKind != null) {
                    val reason = when (zStatus.errorKind) {
                        "auth_expired" -> s.workZcodeAuthExpired
                        "rate_limited" -> s.workZcodeRateLimited
                        "server_error" -> s.workZcodeServerError
                        "api_changed" -> s.workZcodeApiChanged
                        "parse_failed" -> s.workZcodeParseFailed
                        else -> s.workZcodeNoConnection
                    }
                    StatusStore.saveError(context, reason)
                    zStatus.networkError ||
                        zStatus.errorKind in RefreshWorker.RETRYABLE_ZCODE_ERRORS
                } else {
                    false
                }
            },
            onFailure = { err ->
                val nowIso = java.time.Instant.now().toString()
                val fallback = previous
                    ?: ZCodeStatus(running = true, couponVisible = false, method = "api")
                val failed = fallback.copy(
                    running = true,
                    method = "api",
                    authOk = null,
                    networkError = true,
                    couponVisible = false,
                    couponState = ZCodeStatus.COUPON_UNKNOWN,
                    errorKind = "network",
                    checkedAt = nowIso,
                )
                ZCodeStore.saveDirect(context, failed)
                StatusStore.saveError(context, err.message ?: s.workZcodeNetworkError)
                true
            }
        )
}
