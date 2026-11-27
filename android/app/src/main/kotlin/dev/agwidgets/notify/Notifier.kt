package dev.agwidgets.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.agwidgets.BuildConfig
import dev.agwidgets.MainActivity
import dev.agwidgets.R
import dev.agwidgets.data.AgStatus
import dev.agwidgets.data.QuotaBucket
import dev.agwidgets.data.StatusStore
import dev.agwidgets.data.ZCodeStatus
import dev.agwidgets.ui.i18n.AgStrings
import dev.agwidgets.ui.parseInstant
import kotlin.math.roundToInt

object Notifier {
    private const val CHANNEL_ID = "agw_limits"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val s = StatusStore.resolveStrings(context)
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                s.notifChannelName,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = if (BuildConfig.ZCODE_ENABLED) {
                    s.notifChannelDescriptionFull
                } else {
                    s.notifChannelDescriptionContext
                }
            }
            mgr.createNotificationChannel(channel)
        }
    }

    fun checkAndNotify(context: Context, prev: AgStatus?, curr: AgStatus?) {
        if (prev == null || curr == null) return
        if (!canPostNotifications(context)) return
        ensureChannel(context)
        val s = StatusStore.resolveStrings(context)

        for (currBucket in curr.buckets) {
            val prevBucket = prev.buckets.firstOrNull { it.id == currBucket.id } ?: continue
            if (shouldNotifyReset(prevBucket, currBucket)) {
                val currFrac = currBucket.remainingFraction
                // Key on the time before the reset so a future timestamp is never marked as notified.
                val resetKey = validResetKey(prevBucket.resetTime)
                    ?: validResetKey(currBucket.resetTime)
                    ?: "changed"
                if (StatusStore.isResetNotified(context, currBucket.id, resetKey)) continue
                StatusStore.markResetNotified(context, currBucket.id, resetKey)
                val pct = currFrac?.takeIf { it.isFinite() }
                    ?.let { s.fill(s.notifAvailablePct, "pct" to (it * 100).roundToInt()) }
                    ?: s.notifLimitReset
                sendNotification(
                    context,
                    notifIdForBucket(currBucket.id),
                    s.fill(
                        s.notifQuotaUpdated,
                        "bucket" to bucketDisplayName(currBucket.id, currBucket.kind, s),
                    ),
                    pct
                )
            }
        }

        if (BuildConfig.ZCODE_ENABLED) checkZCodeCouponTransition(context, prev.zcode, curr.zcode)
    }

    fun checkZCodeCouponTransition(context: Context, prev: ZCodeStatus?, curr: ZCodeStatus?) {
        if (!BuildConfig.ZCODE_ENABLED) return
        if (curr == null) return
        if (curr.couponState != ZCodeStatus.COUPON_VISIBLE || !curr.couponVisible) return
        if (curr.networkError || curr.authOk == false) return
        if (!canPostNotifications(context)) return
        ensureChannel(context)
        val s = StatusStore.resolveStrings(context)

        val key = "${curr.planId}|${curr.matched}"
        if (StatusStore.getLastNotifiedCoupon(context) == key) return
        StatusStore.markCouponNotified(context, key)
        val detail = curr.matched.takeIf { it.isNotBlank() } ?: s.notifCouponDetail
        sendNotification(context, 103, s.notifCouponTitle, detail)
    }

    fun notifyScheduledReset(context: Context, bucketId: String, kind: String, resetTime: String) {
        if (!canPostNotifications(context)) return
        if (StatusStore.isResetNotified(context, bucketId, resetTime)) return
        ensureChannel(context)
        val s = StatusStore.resolveStrings(context)
        StatusStore.markResetNotified(context, bucketId, resetTime)
        val label = bucketDisplayName(bucketId, kind, s)
        sendNotification(
            context,
            notifIdForBucket(bucketId),
            s.fill(s.notifQuotaUpdated, "bucket" to label),
            s.notifScheduledResetBody
        )
    }

    /**
     * Pure quota-reset predicate: fires only when the bucket was actually consumed
     * (`< 99%`) and then either jumped back up (`>= +20%`, including resets where
     * `agy /usage` switches `reset_time` back to the epoch-zero sentinel) or its
     * real reset window moved forward while remaining quota increased.
     */
    internal fun shouldNotifyReset(prev: QuotaBucket, curr: QuotaBucket): Boolean {
        val prevFrac = prev.remainingFraction
        if (prevFrac == null || !prevFrac.isFinite() || prevFrac >= 0.99) return false
        val currFrac = curr.remainingFraction?.takeIf { it.isFinite() }
        val grew = currFrac != null && (currFrac - prevFrac) >= 0.20
        if (grew) return true

        val prevReset = parseInstant(prev.resetTime)?.takeIf { it.epochSecond > 0L }
        val currReset = parseInstant(curr.resetTime)?.takeIf { it.epochSecond > 0L }
        val resetAdvanced = prevReset != null && currReset != null && currReset.isAfter(prevReset)
        val quotaDidNotDrop = currFrac == null || currFrac > prevFrac + 0.01
        return resetAdvanced && quotaDidNotDrop
    }

    private fun validResetKey(iso: String?): String? {
        val instant = parseInstant(iso) ?: return null
        return if (instant.epochSecond > 0L) iso else null
    }

    private fun bucketDisplayName(bucketId: String, kind: String, s: AgStrings): String {
        val low = bucketId.lowercase()
        val group = when {
            low.startsWith("gemini-") -> s.notifBucketGemini
            low.startsWith("3p-") -> s.notifBucketThird
            else -> ""
        }
        val window = when (kind) {
            "5h" -> s.notifWindow5h
            "weekly" -> s.notifWindowWeekly
            else -> ""
        }
        return "$group$window".trim().ifEmpty { bucketId }
    }

    private fun notifIdForBucket(bucketId: String): Int =
        200 + (bucketId.hashCode() and 0x7FFF) % 500

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun sendNotification(context: Context, id: Int, title: String, text: String) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val intent = Intent(context, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(context, 0, intent, flags)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF22D3EE.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission revoked concurrently
        }
    }
}
