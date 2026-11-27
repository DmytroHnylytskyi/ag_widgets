package dev.agwidgets.work

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.agwidgets.data.QuotaBucket
import dev.agwidgets.notify.Notifier
import dev.agwidgets.ui.parseInstant
import dev.agwidgets.widget.LimitsWidget
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Fires locally on the phone at the scheduled Antigravity quota reset_time,
 * even when the laptop is turned off or sleeping.
 */
class ResetAlertWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val bucketId = inputData.getString(KEY_BUCKET_ID) ?: return Result.success()
        val kind = inputData.getString(KEY_KIND) ?: "other"
        val resetTime = inputData.getString(KEY_RESET_TIME) ?: return Result.success()

        Notifier.notifyScheduledReset(applicationContext, bucketId, kind, resetTime)
        LimitsWidget().updateAll(applicationContext)
        return Result.success()
    }

    companion object {
        /** Tag shared by every locally scheduled reset alert, used for bulk cancel. */
        internal const val TAG_RESET_ALERT = "agw_reset_alert"

        private const val KEY_BUCKET_ID = "bucket_id"
        private const val KEY_KIND = "kind"
        private const val KEY_RESET_TIME = "reset_time"

        private val KNOWN_BUCKET_IDS = listOf("gemini-5h", "gemini-weekly", "3p-5h", "3p-weekly")

        /**
         * Cancels every pending reset alert.
         *
         * Alerts from older releases carry no tag, so the unique work names are
         * cancelled explicitly as well.
         */
        fun cancelAllResetAlerts(context: Context) {
            val wm = WorkManager.getInstance(context.applicationContext)
            wm.cancelAllWorkByTag(TAG_RESET_ALERT)
            for (id in KNOWN_BUCKET_IDS) {
                wm.cancelUniqueWork("agw_reset_$id")
            }
        }

        fun scheduleForBuckets(context: Context, buckets: List<QuotaBucket>) {
            val wm = WorkManager.getInstance(context.applicationContext)
            val now = Instant.now()
            val presentIds = buckets.map { it.id }

            for (bucket in buckets) {
                val resetIso = bucket.resetTime
                // Only schedule an alert if quota was actually consumed (< 99%)
                val frac = bucket.remainingFraction
                val target = resetIso?.let { parseInstant(it) }
                if (resetIso == null || target == null || target.epochSecond <= 0L ||
                    (frac != null && frac >= 0.99)
                ) {
                    // Never leave a stale alert scheduled for a bucket we cannot trust.
                    wm.cancelUniqueWork("agw_reset_${bucket.id}")
                    continue
                }

                val delayMs = Duration.between(now, target).toMillis()
                if (delayMs <= 5_000L) {
                    wm.cancelUniqueWork("agw_reset_${bucket.id}")
                    continue
                }

                val data = Data.Builder()
                    .putString(KEY_BUCKET_ID, bucket.id)
                    .putString(KEY_KIND, bucket.kind)
                    .putString(KEY_RESET_TIME, resetIso)
                    .build()

                val req = OneTimeWorkRequestBuilder<ResetAlertWorker>()
                    .addTag(TAG_RESET_ALERT)
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .setInputData(data)
                    .build()

                wm.enqueueUniqueWork(
                    "agw_reset_${bucket.id}",
                    ExistingWorkPolicy.REPLACE,
                    req
                )
            }

            // Cancel alerts for known buckets that disappeared from the report.
            for (id in KNOWN_BUCKET_IDS) {
                if (id !in presentIds) {
                    wm.cancelUniqueWork("agw_reset_$id")
                }
            }
        }
    }
}
