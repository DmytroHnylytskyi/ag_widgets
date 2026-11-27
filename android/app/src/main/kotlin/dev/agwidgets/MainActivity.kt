package dev.agwidgets

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.glance.appwidget.updateAll
import dev.agwidgets.data.StatusStore
import dev.agwidgets.data.ZCodeStore
import dev.agwidgets.notify.Notifier
import dev.agwidgets.ui.AgWidgetsScreen
import dev.agwidgets.ui.i18n.AppLanguage
import dev.agwidgets.ui.i18n.LocalStrings
import dev.agwidgets.ui.i18n.stringsFor
import dev.agwidgets.ui.theme.AGWidgetsTheme
import dev.agwidgets.ui.theme.normalizeThemeMode
import dev.agwidgets.ui.theme.resolveAgTheme
import dev.agwidgets.widget.LimitsWidget
import dev.agwidgets.work.RefreshWorker

class MainActivity : ComponentActivity() {
    /**
     * `agw://pair` link waiting for the confirm dialog.
     *
     * It lives on the Activity (not inside `setContent`) so a link delivered
     * through [onNewIntent] while the Activity is already visible is not lost.
     */
    private var pendingPairingUri by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifier.ensureChannel(this)
        pendingPairingUri = extractPairingUri(intent)

        // No configured channel means no network work worth scheduling.
        val hasChannel = StatusStore.getTopic(applicationContext).isNotBlank() ||
            (BuildConfig.ZCODE_ENABLED && ZCodeStore.getToken(applicationContext).isNotBlank())
        if (hasChannel) RefreshWorker.schedulePeriodic(applicationContext)

        setContent {
            val appContext = applicationContext
            var themeMode by remember { mutableStateOf(StatusStore.getThemeMode(appContext)) }
            var languageMode by remember { mutableStateOf(StatusStore.getLanguage(appContext)) }
            val strings = stringsFor(AppLanguage.resolve(languageMode))
            val systemDark = isSystemInDarkTheme()
            val resolved = resolveAgTheme(themeMode, systemDark)

            LaunchedEffect(themeMode, resolved.isDark) {
                val barStyle = if (resolved.isDark) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
            }
            LaunchedEffect(themeMode, resolved.isDark) {
                LimitsWidget().updateAll(appContext)
            }
            // The widget and the notification channel carry their own copy, so a
            // language switch has to repaint them while the app is open.
            LaunchedEffect(languageMode) {
                LimitsWidget().updateAll(appContext)
                Notifier.ensureChannel(appContext)
            }

            CompositionLocalProvider(LocalStrings provides strings) {
                AGWidgetsTheme(themeMode = themeMode, systemDark = systemDark) {
                    AgWidgetsScreen(
                        themeMode = themeMode,
                        onSelectTheme = { mode ->
                            StatusStore.setThemeMode(appContext, mode)
                            themeMode = normalizeThemeMode(mode)
                        },
                        language = languageMode,
                        onSelectLanguage = { mode ->
                            StatusStore.setLanguage(appContext, mode)
                            languageMode = AppLanguage.normalize(mode)
                        },
                        pendingPairingUri = pendingPairingUri,
                        onConsumePairingUri = { pendingPairingUri = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingPairingUri = extractPairingUri(intent)
    }

    /**
     * Only a real `agw://pair` VIEW link is a pairing request; anything else
     * (launcher intents, a forged scheme/host, no data) is ignored.
     */
    private fun extractPairingUri(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val data = intent.data ?: return null
        if (!data.scheme.equals("agw", ignoreCase = true)) return null
        if (!data.host.equals("pair", ignoreCase = true)) return null
        return data.toString()
    }
}
