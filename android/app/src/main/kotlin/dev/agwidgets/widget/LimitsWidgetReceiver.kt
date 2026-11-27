package dev.agwidgets.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import dev.agwidgets.BuildConfig
import dev.agwidgets.data.StatusStore
import dev.agwidgets.data.ZCodeStore
import dev.agwidgets.work.RefreshWorker

class LimitsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LimitsWidget()

    private fun hasChannel(context: Context): Boolean =
        StatusStore.getTopic(context).isNotBlank() ||
            (BuildConfig.ZCODE_ENABLED && ZCodeStore.getToken(context).isNotBlank())

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        if (hasChannel(context)) {
            RefreshWorker.schedulePeriodic(context)
            RefreshWorker.runNow(context)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        if (hasChannel(context)) {
            RefreshWorker.schedulePeriodic(context)
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // Keep background alerts alive if the user still has an active channel in the app.
        if (!hasChannel(context)) {
            RefreshWorker.cancelPeriodic(context)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        // Drop per-widget configs together with the widget instances themselves.
        appWidgetIds.forEach { WidgetConfigStore.delete(context, it) }
    }
}
