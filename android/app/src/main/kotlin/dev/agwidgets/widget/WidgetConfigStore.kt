package dev.agwidgets.widget

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import dev.agwidgets.data.StatusStore
import dev.agwidgets.ui.theme.ResolvedAgTheme
import dev.agwidgets.ui.theme.normalizeThemeMode
import dev.agwidgets.ui.theme.resolveAgTheme

/**
 * Per-`appWidgetId` storage for [WidgetConfig] in a dedicated
 * `SharedPreferences` file (`agw_widgets.xml`).
 *
 * A widget without a stored config is not an error: it simply renders the
 * defaults and inherits the app-wide theme, which keeps every widget placed by
 * an older build working after the update.
 */
object WidgetConfigStore {
    /** Separate prefs file so widget configs never mix with secrets in `agw.xml`. */
    const val PREFS_NAME = "agw_widgets"

    private const val KEY_PREFIX = "widget_cfg_"

    private val lock = Any()

    private fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(appWidgetId: Int): String = "$KEY_PREFIX$appWidgetId"

    /** Stored (already normalized) config, or the defaults for a fresh widget. */
    fun get(context: Context, appWidgetId: Int): WidgetConfig {
        val raw = synchronized(lock) {
            preferences(context).getString(key(appWidgetId), null)
        } ?: return WidgetConfig()
        // A corrupt entry must never take the widget down: fall back to defaults.
        return WidgetConfigCodec.importJson(raw) ?: WidgetConfig()
    }

    fun hasCustomConfig(context: Context, appWidgetId: Int): Boolean = synchronized(lock) {
        preferences(context).contains(key(appWidgetId))
    }

    fun save(context: Context, appWidgetId: Int, config: WidgetConfig) {
        val normalized = WidgetConfigCodec.normalize(config)
        val payload = WidgetConfigCodec.toJson(normalized).toString()
        synchronized(lock) {
            preferences(context).edit().putString(key(appWidgetId), payload).apply()
        }
    }

    fun delete(context: Context, appWidgetId: Int) {
        synchronized(lock) {
            preferences(context).edit().remove(key(appWidgetId)).apply()
        }
    }

    /** Ids that currently have a stored config (used by the Settings list). */
    fun listConfiguredIds(context: Context): Set<Int> = synchronized(lock) {
        preferences(context)
            .all
            .keys
            .filter { it.startsWith(KEY_PREFIX) }
            .mapNotNull { it.removePrefix(KEY_PREFIX).toIntOrNull() }
            .toSet()
    }

    /**
     * Resolve the palette for one widget: an explicit theme wins, `system`
     * inherits the app-wide mode from [StatusStore], and the result is resolved
     * against the current night mode.
     */
    fun resolveWidgetTheme(context: Context, config: WidgetConfig): ResolvedAgTheme {
        val theme = WidgetThemes.normalize(config.theme)
        val mode = if (theme == WidgetThemes.SYSTEM) {
            normalizeThemeMode(StatusStore.getThemeMode(context))
        } else {
            theme
        }
        val systemDark = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return resolveAgTheme(mode, systemDark)
    }
}
