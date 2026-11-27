package dev.agwidgets.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import dev.agwidgets.BuildConfig
import dev.agwidgets.R
import dev.agwidgets.data.AgStatus
import dev.agwidgets.data.E2eCrypto
import dev.agwidgets.data.NtfyClient
import dev.agwidgets.data.PairingConfig
import dev.agwidgets.data.StatusStore
import dev.agwidgets.data.ZCodeClient
import dev.agwidgets.data.ZCodeOAuthPollResult
import dev.agwidgets.data.ZCodeStore
import dev.agwidgets.notify.Notifier
import dev.agwidgets.ui.components.AccessCard
import dev.agwidgets.ui.components.AgGhostButton
import dev.agwidgets.ui.components.ChannelCard
import dev.agwidgets.ui.components.ContextCard
import dev.agwidgets.ui.components.DiagnosticsCard
import dev.agwidgets.ui.components.Header
import dev.agwidgets.ui.components.HeroCard
import dev.agwidgets.ui.components.InstalledWidgetInfo
import dev.agwidgets.ui.components.SurfaceCard
import dev.agwidgets.ui.components.ZCodeDashboardCard
import dev.agwidgets.ui.components.glassFieldColors
import dev.agwidgets.ui.theme.AgDim
import dev.agwidgets.ui.theme.AgTheme
import dev.agwidgets.ui.theme.AgType
import dev.agwidgets.ui.widget.WidgetConfigActivity
import dev.agwidgets.widget.LimitsWidget
import dev.agwidgets.widget.LimitsWidgetReceiver
import dev.agwidgets.widget.WidgetConfig
import dev.agwidgets.widget.WidgetConfigCodec
import dev.agwidgets.widget.WidgetConfigStore
import dev.agwidgets.widget.WidgetPresets
import dev.agwidgets.widget.WidgetThemes
import dev.agwidgets.work.RefreshWorker
import dev.agwidgets.work.ResetAlertWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Observable OAuth progress shared between the screen and [OAuthSessionHolder]. */
internal sealed interface OAuthUiState {
    data object Idle : OAuthUiState
    data class Waiting(val message: String) : OAuthUiState
    data class Failed(val message: String) : OAuthUiState
    data class Ready(val message: String) : OAuthUiState
}

/**
 * Process-scoped OAuth polling.
 *
 * The holder is an object, not a Composable state owner, so rotating the
 * Activity or recreating it does not cancel an in-flight browser login.
 */
internal object OAuthSessionHolder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<OAuthUiState>(OAuthUiState.Idle)
    val state: StateFlow<OAuthUiState> = _state.asStateFlow()
    private var job: Job? = null

    fun start(context: Context, onToken: (String) -> Unit) {
        val appContext = context.applicationContext
        job?.cancel()
        _state.value = OAuthUiState.Waiting("Открываю вход Z.ai в браузере…")
        job = scope.launch {
            val flow = ZCodeClient.startOAuthFlow("zai").getOrElse { error ->
                fail(appContext, "Ошибка OAuth: ${error.message ?: "неизвестная ошибка"}")
                return@launch
            }
            val launch = runCatching {
                appContext.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(flow.authorizeUrl))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            if (launch.isFailure) {
                // A missing/broken browser must not leave a poll job running.
                fail(appContext, "Не удалось открыть браузер")
                return@launch
            }
            _state.value = OAuthUiState.Waiting("Завершите вход в браузере и вернитесь сюда…")
            val deadline = System.currentTimeMillis() + 300_000L
            while (System.currentTimeMillis() < deadline) {
                delay(flow.pollIntervalSec * 1000L)
                when (val poll = ZCodeClient.pollOAuthFlow(flow)) {
                    is ZCodeOAuthPollResult.Pending -> Unit
                    is ZCodeOAuthPollResult.Failed -> {
                        fail(appContext, poll.reason)
                        return@launch
                    }
                    is ZCodeOAuthPollResult.Ready -> {
                        ZCodeStore.setToken(appContext, poll.token)
                        RefreshWorker.schedulePeriodic(appContext)
                        val deviceMid = ZCodeStore.getOrCreateDeviceMid(appContext)
                        val seq = ZCodeStore.nextSeq(appContext)
                        val previous = ZCodeStore.getDirect(appContext)
                        ZCodeClient.fetchStatus(poll.token, deviceMid, seq, previous).onSuccess { fetched ->
                            ZCodeStore.saveDirect(appContext, fetched)
                        }
                        runCatching { LimitsWidget().updateAll(appContext) }
                        runCatching { onToken(poll.token) }
                        _state.value = OAuthUiState.Ready("Вход выполнен. Автономный мониторинг включён.")
                        return@launch
                    }
                }
            }
            fail(appContext, "Таймаут входа — попробуйте снова")
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = OAuthUiState.Idle
    }

    private fun fail(context: Context, message: String) {
        // Stop the poll loop immediately; Failed must not be overwritten by Idle.
        job?.cancel()
        job = null
        _state.value = OAuthUiState.Failed(message)
        StatusStore.saveError(context, message)
    }
}

/**
 * App screen: a clean dashboard (context → limits → ZCode) plus a separate
 * settings surface opened from the header gear. Always rendered on top of the
 * single [AgTheme] palette, so Classic keeps the author's decor 1:1.
 *
 * v1 invariants kept on purpose: the first-run mode choice, the `BuildConfig`
 * gates for the ZCode flavor, `ZCodeStore` (never the merged status) as the
 * token source and the 429 backoff.
 */
@Composable
internal fun AgWidgetsScreen(
    themeMode: String,
    onSelectTheme: (String) -> Unit,
    pendingPairingUri: String? = null,
    onConsumePairingUri: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = AgTheme.colors

    var topic by remember { mutableStateOf(StatusStore.getTopic(context)) }
    var ntfyServer by remember { mutableStateOf(StatusStore.getNtfyServer(context)) }
    var hasE2eKey by remember { mutableStateOf(StatusStore.hasE2eKey(context)) }
    var pairingRequest by remember { mutableStateOf<String?>(null) }
    var zcodeToken by remember { mutableStateOf(if (BuildConfig.ZCODE_ENABLED) ZCodeStore.getToken(context) else "") }
    var lastStatus by remember { mutableStateOf<AgStatus?>(StatusStore.getMergedStatus(context)) }
    var lastError by remember { mutableStateOf<String?>(StatusStore.getLastError(context)) }
    var statusMessage by remember { mutableStateOf(lastError ?: "") }
    var zcodeMessage by remember { mutableStateOf("") }
    var isRefreshing by remember { mutableStateOf(false) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(StatusStore.getMode(context)) }
    var notificationsDenied by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var installedWidgets by remember { mutableStateOf<List<InstalledWidgetInfo>>(emptyList()) }
    // The paste-code dialog lives at the root so onboarding and Settings share
    // one implementation (and one fallback when the QR scanner is unavailable).
    var showPasteDialog by remember { mutableStateOf(false) }

    // A scanned agw://pair link is held here until the user confirms or closes
    // the dialog; the dialog itself is drawn above the onboarding branch.
    LaunchedEffect(pendingPairingUri) {
        if (!pendingPairingUri.isNullOrBlank()) pairingRequest = pendingPairingUri
    }

    fun dismissPairing() {
        pairingRequest = null
        onConsumePairingUri()
    }

    // Play Services owns the camera preview, so there is no permission dance
    // here: success goes straight to the confirm dialog, cancel is silent and a
    // missing scanner falls back to pasting the link by hand.
    fun handleScanQr() {
        QrScanner.scanQr(context) { result ->
            when (result) {
                is QrScanResult.Success -> pairingRequest = result.rawValue
                QrScanResult.Cancelled -> Unit
                is QrScanResult.Unavailable -> {
                    statusMessage = "Сканер QR недоступен — вставьте ссылку сопряжения вручную"
                    showPasteDialog = true
                }
            }
        }
    }

    val oauthState by OAuthSessionHolder.state.collectAsState()
    val isOAuthPending = oauthState is OAuthUiState.Waiting

    // System back returns from Settings to the dashboard before leaving the app.
    BackHandler(enabled = showSettings) { showSettings = false }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsDenied = !granted }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            notificationsDenied = !granted
            if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(30_000)
        }
    }

    // Pick up background RefreshWorker writes (and their errors) immediately
    // without clobbering transient user feedback such as "Тема сохранена".
    DisposableEffect(context) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "last_json", "direct_zcode_json" -> {
                    lastStatus = StatusStore.getMergedStatus(context)
                    nowMs = System.currentTimeMillis()
                }
                "last_error" -> {
                    lastError = StatusStore.getLastError(context)
                    if (!isRefreshing) {
                        statusMessage = lastError.orEmpty()
                    }
                }
            }
        }
        StatusStore.registerListener(context, listener)
        onDispose { StatusStore.unregisterListener(context, listener) }
    }

    LaunchedEffect(oauthState) {
        when (val state = oauthState) {
            is OAuthUiState.Waiting -> zcodeMessage = state.message
            is OAuthUiState.Failed -> {
                zcodeMessage = state.message
                StatusStore.saveError(context, state.message)
            }
            is OAuthUiState.Ready -> {
                zcodeMessage = state.message
                zcodeToken = ZCodeStore.getToken(context)
                lastStatus = StatusStore.getMergedStatus(context)
                nowMs = System.currentTimeMillis()
            }
            OAuthUiState.Idle -> Unit
        }
    }

    fun syncPeriodicSchedule() {
        val hasChannel = StatusStore.getTopic(context).isNotBlank() ||
            (BuildConfig.ZCODE_ENABLED && ZCodeStore.getToken(context).isNotBlank())
        if (hasChannel) {
            RefreshWorker.schedulePeriodic(context)
        } else {
            RefreshWorker.cancelPeriodic(context)
        }
    }

    fun refresh() {
        if (isRefreshing) return
        val prevTopic = StatusStore.getTopic(context)
        if (mode != StatusStore.MODE_ZCODE && topic.trim() != prevTopic) {
            StatusStore.setTopic(context, topic)
            // Bucket ids from the previous topic must not fire alerts for the new one.
            ResetAlertWorker.cancelAllResetAlerts(context)
        }
        if (BuildConfig.ZCODE_ENABLED && zcodeToken.isNotBlank() &&
            zcodeToken.trim() != ZCodeStore.getToken(context)
        ) {
            ZCodeStore.setToken(context, zcodeToken)
        }
        syncPeriodicSchedule()
        isRefreshing = true
        statusMessage = "Обновление..."
        scope.launch {
            val messages = mutableListOf<String>()

            if (mode != StatusStore.MODE_ZCODE && topic.isNotBlank()) {
                NtfyClient.fetchLatestMessage(
                    topic,
                    serverUrl = StatusStore.getNtfyServer(context),
                    e2eKey = StatusStore.getE2eKey(context),
                ).fold(
                    onSuccess = { jsonText ->
                        val parsed = AgStatus.fromJson(jsonText)
                        if (parsed != null) {
                            val (previous, current) = StatusStore.saveNewStatus(context, jsonText)
                            if (current != null) {
                                ResetAlertWorker.scheduleForBuckets(context, current.buckets)
                                Notifier.checkAndNotify(context, previous, current)
                            }
                            messages.add("ntfy OK")
                        } else {
                            messages.add("ntfy: неверный JSON")
                            StatusStore.saveError(context, "ntfy: неверный JSON")
                        }
                    },
                    onFailure = { error ->
                        messages.add("ntfy: ${error.message}")
                        StatusStore.saveError(context, "ntfy: ${error.message ?: "ошибка сети"}")
                    },
                )
            }

            val token = if (BuildConfig.ZCODE_ENABLED) ZCodeStore.getToken(context) else ""
            val rateLimited = token.isNotBlank() && ZCodeStore.shouldBackoffRateLimit(context)
            if (token.isNotBlank() && !rateLimited) {
                val deviceMid = ZCodeStore.getOrCreateDeviceMid(context)
                val seq = ZCodeStore.nextSeq(context)
                val previousZCode = ZCodeStore.getDirect(context)
                ZCodeClient.fetchStatus(token, deviceMid, seq, previousZCode).fold(
                    onSuccess = { zcode ->
                        val (previous, current) = ZCodeStore.saveDirect(context, zcode)
                        Notifier.checkZCodeCouponTransition(context, previous, current)
                        val remaining = zcode.remainingUnits
                        val hasBalance = remaining != null &&
                            (remaining > 0 || (zcode.totalUnits != null && zcode.totalUnits > 0))
                        zcodeMessage = when {
                            zcode.isCouponUnknown -> when (zcode.errorKind) {
                                "auth_expired" -> "Токен истёк — войдите снова"
                                "network" -> "Нет связи с zcode.z.ai"
                                "rate_limited" -> "Слишком много запросов ZCode — попробуйте позже"
                                "server_error" -> "Ошибка сервера ZCode"
                                "api_changed" -> "Схема API ZCode изменилась"
                                "parse_failed" -> "Не удалось разобрать ответ ZCode"
                                else -> "Состояние ваучера неизвестно"
                            }
                            zcode.isCouponVisible -> "Ваучер доступен! (${zcode.matched})"
                            zcode.authOk == false -> "Токен истёк — войдите снова"
                            hasBalance -> "Остаток: ${formatUnits(remaining)}"
                            else -> "ZCode OK · ваучеров пока нет"
                        }
                        if (zcode.networkError || zcode.errorKind != null) {
                            messages.add("ZCode: $zcodeMessage")
                            StatusStore.saveError(context, "ZCode: $zcodeMessage")
                        } else {
                            messages.add("ZCode OK")
                        }
                    },
                    onFailure = { error ->
                        messages.add("ZCode: ${error.message}")
                        StatusStore.saveError(context, "ZCode: ${error.message ?: "ошибка сети"}")
                    },
                )
            }

            if (rateLimited) {
                messages.add("ZCode: лимит запросов (429) — подождите")
            }
            lastStatus = StatusStore.getMergedStatus(context)
            lastError = StatusStore.getLastError(context)
            nowMs = System.currentTimeMillis()
            LimitsWidget().updateAll(context)
            statusMessage = when {
                messages.isNotEmpty() -> messages.joinToString(" · ")
                BuildConfig.ZCODE_ENABLED && mode == StatusStore.MODE_ZCODE -> "Войдите в Z.ai"
                else -> "Укажите тему ntfy"
            }
            isRefreshing = false
        }
    }

    fun login() {
        if (isOAuthPending || OAuthSessionHolder.state.value is OAuthUiState.Waiting) return
        OAuthSessionHolder.start(context) { token ->
            zcodeToken = token
            lastStatus = StatusStore.getMergedStatus(context)
            nowMs = System.currentTimeMillis()
        }
    }

    fun logout() {
        OAuthSessionHolder.cancel()
        zcodeToken = ""
        ZCodeStore.clearToken(context)
        syncPeriodicSchedule()
        zcodeMessage = "Токен удалён"
        lastStatus = StatusStore.getMergedStatus(context)
        nowMs = System.currentTimeMillis()
        scope.launch { LimitsWidget().updateAll(context) }
    }

    fun pinWidget() {
        val manager = AppWidgetManager.getInstance(context)
        if (manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(ComponentName(context, LimitsWidgetReceiver::class.java), null, null)
        } else {
            statusMessage = "Лаунчер не поддерживает автодобавление — добавьте виджет вручную"
        }
    }

    /** Widgets currently on the launcher, with their stored preset and theme labels. */
    fun loadWidgets() {
        installedWidgets = runCatching {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, LimitsWidgetReceiver::class.java)
            manager.getAppWidgetIds(component)
                .sorted()
                .map { appWidgetId ->
                    val config = WidgetConfigStore.get(context, appWidgetId)
                    InstalledWidgetInfo(
                        appWidgetId = appWidgetId,
                        presetLabel = widgetPresetLabel(config),
                        themeLabel = widgetThemeLabel(config.theme),
                    )
                }
        }.getOrDefault(emptyList())
    }

    val launchConfig = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { loadWidgets() }

    // Returning from the config Activity — or from the launcher's pin dialog —
    // re-reads the list: the preset and theme labels must match what is stored.
    val lifecycleOwner = LocalActivity.current as? LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        loadWidgets()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) loadWidgets()
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        val canFetchPc = mode != StatusStore.MODE_ZCODE && topic.isNotBlank()
        val canFetchZCode = BuildConfig.ZCODE_ENABLED && zcodeToken.isNotBlank()
        if (lastStatus == null && (canFetchPc || canFetchZCode)) refresh()
    }

    // Dashboard and Settings keep their own offset: scrolling one used to move
    // the other when going "Назад".
    val dashboardScrollState = rememberScrollState()
    val settingsScrollState = rememberScrollState()
    val scrollState = if (showSettings) settingsScrollState else dashboardScrollState
    // The pinned bar only becomes a scrim once content slides under it. At
    // scroll == 0 it is fully transparent, so the Classic grid and the Glass
    // wash reach the status bar instead of being cut by a hard seam.
    val barAlpha = (scrollState.value / 48f).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        // Glass themes get a soft atmospheric wash; nothing else is tinted.
        if (colors.isGlass) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(colors.backdropTint, Color.Transparent),
                        startY = 0f,
                        endY = size.height * 0.55f,
                    ),
                )
            }
        }

        // Neon glows and the grid are part of the author's Classic look only.
        if (colors.isClassic) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(colors.glowMagenta, Color.Transparent),
                        center = Offset(size.width * 0.10f, size.height * 0.02f),
                        radius = size.width * 0.72f,
                    ),
                    radius = size.width * 0.72f,
                    center = Offset(size.width * 0.10f, size.height * 0.02f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(colors.glowBlue, Color.Transparent),
                        center = Offset(size.width * 0.96f, size.height * 0.72f),
                        radius = size.width * 0.78f,
                    ),
                    radius = size.width * 0.78f,
                    center = Offset(size.width * 0.96f, size.height * 0.72f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(colors.glowOrange, Color.Transparent),
                        center = Offset(size.width * 0.88f, size.height * 0.08f),
                        radius = size.width * 0.42f,
                    ),
                    radius = size.width * 0.42f,
                    center = Offset(size.width * 0.88f, size.height * 0.08f),
                )
                val step = 22.dp.toPx()
                var x = 0f
                while (x < size.width) {
                    drawLine(colors.grid, Offset(x, 0f), Offset(x, size.height), 1f)
                    x += step
                }
                var y = 0f
                while (y < size.height) {
                    drawLine(colors.grid, Offset(0f, y), Offset(size.width, y), 1f)
                    y += step
                }
            }
        }

        if (mode == StatusStore.MODE_UNSET) {
            OnboardingScreen(
                zcodeAvailable = BuildConfig.ZCODE_ENABLED,
                onChoosePc = {
                    StatusStore.setMode(context, StatusStore.MODE_PC)
                    mode = StatusStore.MODE_PC
                    if (topic.isNotBlank()) refresh()
                },
                onScanQr = { handleScanQr() },
                onChooseZcode = {
                    StatusStore.setMode(context, StatusStore.MODE_ZCODE)
                    mode = StatusStore.MODE_ZCODE
                    if (zcodeToken.isNotBlank()) refresh()
                },
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // Pinned top bar: the header never scrolls away and never gets
                // cut in half by the status-bar inset.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.background.copy(alpha = barAlpha * 0.94f))
                        .statusBarsPadding()
                        .padding(horizontal = AgDim.Page, vertical = 12.dp),
                ) {
                    if (showSettings) {
                        SettingsTopBar(onBack = { showSettings = false })
                    } else {
                        Header(
                            status = lastStatus,
                            nowMs = nowMs,
                            isRefreshing = isRefreshing,
                            onRefresh = { refresh() },
                            onOpenSettings = { showSettings = true },
                        )
                    }
                }
                HorizontalDivider(
                    thickness = 1.dp,
                    color = colors.border.copy(alpha = colors.border.alpha * barAlpha),
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .navigationBarsPadding()
                        .padding(horizontal = AgDim.Page)
                        .padding(top = AgDim.CardGap, bottom = AgDim.Page),
                    verticalArrangement = Arrangement.spacedBy(AgDim.CardGap),
                ) {
                    if (!showSettings) {
                        if (notificationsDenied) {
                            Text(
                                text = "Уведомления отключены — напоминания о сбросе квоты не придут",
                                style = if (colors.isClassic) {
                                    AgType.caption.copy(fontFamily = FontFamily.Monospace)
                                } else {
                                    AgType.body
                                },
                                color = colors.amber,
                            )
                        }
                        if (mode != StatusStore.MODE_ZCODE) {
                            ContextCard(
                                context = lastStatus?.contextSummary,
                                updatedAt = lastStatus?.pcUpdatedAt ?: lastStatus?.updatedAt,
                                nowMs = nowMs,
                            )
                            HeroCard(status = lastStatus, nowMs = nowMs)
                        }
                        if (BuildConfig.ZCODE_ENABLED) {
                            ZCodeDashboardCard(
                                zcode = lastStatus?.zcode,
                                isPending = isOAuthPending,
                                message = zcodeMessage,
                                onLogin = { login() },
                                onOpenSettings = { showSettings = true },
                            )
                        }
                    } else {
                        SettingsScreen(
                            themeMode = themeMode,
                            onSelectTheme = onSelectTheme,
                        ) {
                            if (mode != StatusStore.MODE_ZCODE) {
                                ChannelCard(
                                    topic = topic,
                                    onTopicChange = { topic = it },
                                    server = ntfyServer,
                                    onServerChange = { ntfyServer = it },
                                    hasE2eKey = hasE2eKey,
                                    isRefreshing = isRefreshing,
                                    statusMessage = statusMessage,
                                    onSave = {
                                        val prevServer = StatusStore.getNtfyServer(context)
                                        val serverToSave = ntfyServer.trim().ifEmpty { E2eCrypto.DEFAULT_NTFY_SERVER }
                                        if (!StatusStore.setNtfyServer(context, serverToSave)) {
                                            statusMessage = "Сервер должен начинаться с https://"
                                        } else {
                                            val nextServer = StatusStore.getNtfyServer(context)
                                            ntfyServer = nextServer
                                            val prevTopic = StatusStore.getTopic(context)
                                            val nextTopic = topic.trim()
                                            StatusStore.setTopic(context, nextTopic)
                                            // Alerts scheduled for the old topic or server are meaningless now.
                                            if (nextTopic.isEmpty() || nextTopic != prevTopic || nextServer != prevServer) {
                                                ResetAlertWorker.cancelAllResetAlerts(context)
                                            }
                                            if (nextServer != prevServer && nextTopic == prevTopic && nextTopic.isNotEmpty()) {
                                                StatusStore.clearPcCache(context)
                                            }
                                            hasE2eKey = StatusStore.hasE2eKey(context)
                                            syncPeriodicSchedule()
                                            lastStatus = StatusStore.getMergedStatus(context)
                                            lastError = StatusStore.getLastError(context)
                                            nowMs = System.currentTimeMillis()
                                            statusMessage =
                                                if (nextTopic.isEmpty()) "Тема очищена" else "Тема сохранена"
                                            scope.launch { runCatching { LimitsWidget().updateAll(context) } }
                                        }
                                    },
                                    onRefresh = { refresh() },
                                    onScanQr = { handleScanQr() },
                                    onPasteCode = { showPasteDialog = true },
                                    onResetE2eKey = {
                                        StatusStore.setE2eKey(context, null)
                                        hasE2eKey = false
                                        ResetAlertWorker.cancelAllResetAlerts(context)
                                        statusMessage = "E2E-ключ сброшен"
                                        scope.launch { runCatching { LimitsWidget().updateAll(context) } }
                                    },
                                )
                            }
                            if (BuildConfig.ZCODE_ENABLED) {
                                AccessCard(
                                    zcode = lastStatus?.zcode,
                                    token = zcodeToken,
                                    onTokenChange = { zcodeToken = it },
                                    isPending = isOAuthPending,
                                    message = zcodeMessage,
                                    onLogin = { login() },
                                    onCancelLogin = { OAuthSessionHolder.cancel() },
                                    onSaveToken = {
                                        ZCodeStore.setToken(context, zcodeToken)
                                        syncPeriodicSchedule()
                                        zcodeMessage = if (zcodeToken.isBlank()) "Токен пуст" else "Токен сохранён"
                                        lastStatus = StatusStore.getMergedStatus(context)
                                    },
                                    onClearToken = { logout() },
                                )
                            }
                            if (mode != StatusStore.MODE_ZCODE) {
                                SurfaceCard(
                                    installedWidgets = installedWidgets,
                                    onPinWidget = { pinWidget() },
                                    onConfigureWidget = { appWidgetId ->
                                        launchConfig.launch(
                                            Intent(context, WidgetConfigActivity::class.java)
                                                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                                        )
                                    },
                                )
                            }
                            DiagnosticsCard(
                                rawJson = lastStatus?.rawJson,
                                lastError = lastError,
                                expanded = showDiagnostics,
                                onToggle = { showDiagnostics = !showDiagnostics },
                                onChangeMode = if (BuildConfig.ZCODE_ENABLED) {
                                    {
                                        StatusStore.setMode(context, StatusStore.MODE_UNSET)
                                        mode = StatusStore.MODE_UNSET
                                        showSettings = false
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }

                    val footerCopy = when {
                        mode == StatusStore.MODE_ZCODE ->
                            "Токен ZCode хранится только на телефоне"
                        BuildConfig.ZCODE_ENABLED ->
                            "В ntfy уходят числа квот, токенов, ID диалога и флаг ваучера"
                        else ->
                            "В ntfy уходят числа квот, токенов и ID диалога"
                    }
                    Text(
                        text = if (colors.isClassic) {
                            "AGW / 01 · ${footerCopy.uppercase()}"
                        } else {
                            footerCopy
                        },
                        style = if (colors.isClassic) {
                            AgType.caption.copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.8.sp, fontSize = 9.sp)
                        } else {
                            AgType.caption
                        },
                        color = if (colors.isClassic) colors.muted.copy(alpha = 0.7f) else colors.muted,
                    )
                }
            }
        }

        // Drawn above the onboarding/dashboard branch on purpose: a QR code
        // scanned on the very first run must be usable before a mode is chosen.
        if (showPasteDialog) {
            PairingPasteDialog(
                onDismiss = { showPasteDialog = false },
                onConfirm = { value ->
                    showPasteDialog = false
                    if (value.isNotBlank()) pairingRequest = value
                },
            )
        }

        pairingRequest?.let { uri ->
            PairingConfirmDialog(
                uri = uri,
                onDismiss = { dismissPairing() },
                onConfirm = { config ->
                    if (StatusStore.applyPairing(context, config)) {
                        ResetAlertWorker.cancelAllResetAlerts(context)
                        mode = StatusStore.MODE_PC
                        topic = StatusStore.getTopic(context)
                        ntfyServer = StatusStore.getNtfyServer(context)
                        hasE2eKey = StatusStore.hasE2eKey(context)
                        syncPeriodicSchedule()
                        statusMessage = "Сопряжение выполнено"
                        lastStatus = StatusStore.getMergedStatus(context)
                        lastError = StatusStore.getLastError(context)
                        nowMs = System.currentTimeMillis()
                        dismissPairing()
                        refresh()
                    } else {
                        statusMessage = "Не удалось применить код сопряжения"
                        dismissPairing()
                    }
                },
            )
        }
    }
}

/**
 * Manual entry for an `agw://pair` link: used when the phone has no Play
 * Services scanner, and by "Вставить код сопряжения" in the settings card.
 *
 * The raw text is only trimmed and forwarded; parsing happens in
 * [PairingConfirmDialog] so a typo is reported there, not here.
 */
@Composable
private fun PairingPasteDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val colors = AgTheme.colors
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(AgDim.Page),
        containerColor = colors.card,
        titleContentColor = colors.text,
        textContentColor = colors.secondary,
        title = { Text("Вставить код сопряжения", style = AgType.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AgDim.ControlGap)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 2,
                    label = { Text("Код agw://pair?...", style = AgType.caption) },
                    textStyle = AgType.body,
                    shape = RoundedCornerShape(AgDim.ControlRadius),
                    colors = glassFieldColors(colors.glow),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val value = text.trim()
                    text = ""
                    onConfirm(value)
                },
                enabled = text.isNotBlank(),
                shape = RoundedCornerShape(AgDim.ControlRadius),
                modifier = Modifier.height(AgDim.MinTouch),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.glow,
                    contentColor = colors.onGlow,
                ),
            ) {
                Text("Далее", style = AgType.body)
            }
        },
        dismissButton = {
            AgGhostButton(text = "Отмена", onClick = onDismiss)
        },
    )
}

/**
 * Confirmation for a scanned/pasted `agw://pair` link.
 *
 * Nothing is persisted here: the dialog only shows what the code would change
 * and forwards the parsed [PairingConfig] to the caller.
 */
@Composable
private fun PairingConfirmDialog(
    uri: String,
    onDismiss: () -> Unit,
    onConfirm: (PairingConfig) -> Unit,
) {
    val colors = AgTheme.colors
    val parsed = remember(uri) { E2eCrypto.parsePairingUri(uri) }
    val config = parsed.getOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(AgDim.Page),
        containerColor = colors.card,
        titleContentColor = colors.text,
        textContentColor = colors.secondary,
        title = {
            Text(
                text = if (config != null) "Подключить телефон" else "Не удалось прочитать код сопряжения",
                style = AgType.title,
                color = colors.text,
            )
        },
        text = {
            if (config == null) {
                Text(
                    text = parsed.exceptionOrNull()?.message ?: "Некорректная ссылка сопряжения",
                    style = AgType.body,
                    color = colors.secondary,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(AgDim.ControlGap)) {
                    Text(
                        text = "Приложение: ${stringResource(R.string.app_name)}",
                        style = AgType.body,
                        color = colors.secondary,
                    )
                    Text(
                        text = "Сервер: ${config.server}",
                        style = AgType.body,
                        color = colors.secondary,
                    )
                    Text(
                        text = "Тема: ${E2eCrypto.maskTopic(config.topic)}",
                        style = AgType.body,
                        color = colors.secondary,
                    )
                    Text(
                        text = if (config.e2eKey != null) "E2E: AES-256-GCM" else "E2E: выкл",
                        style = AgType.body,
                        color = colors.secondary,
                    )
                }
            }
        },
        confirmButton = {
            if (config != null) {
                Button(
                    onClick = { onConfirm(config) },
                    shape = RoundedCornerShape(AgDim.ControlRadius),
                    modifier = Modifier.height(AgDim.MinTouch),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.glow,
                        contentColor = colors.onGlow,
                    ),
                ) {
                    Text("Подключить", style = AgType.body)
                }
            } else {
                AgGhostButton(text = "Закрыть", onClick = onDismiss)
            }
        },
        dismissButton = {
            if (config != null) {
                AgGhostButton(text = "Отмена", onClick = onDismiss)
            }
        },
    )
}

/**
 * Human label for a stored config: the first preset with the same theme,
 * radius, density and enabled blocks wins; a hand-tuned config reads "Свой".
 */
private fun widgetPresetLabel(config: WidgetConfig): String {
    val normalized = WidgetConfigCodec.normalize(config)
    val enabled = normalized.blocks.filter { it.enabled }.map { it.id }.toSet()
    val preset = WidgetPresets.all
        // A "Только ZCode" preset is not offered in the `context` flavour, so a
        // stale full-build config must not be labelled with it either.
        .filter { BuildConfig.ZCODE_ENABLED || it.id != WidgetPresets.zcodeOnly.id }
        .firstOrNull { candidate ->
            val target = WidgetConfigCodec.normalize(candidate.config)
            target.theme == normalized.theme &&
                target.cornerRadiusDp == normalized.cornerRadiusDp &&
                target.density == normalized.density &&
                target.blocks.filter { it.enabled }.map { it.id }.toSet() == enabled
        }
    return preset?.label ?: "Свой"
}

/** Human label for a [WidgetThemes] id; unknown ids fall back to the system theme. */
private fun widgetThemeLabel(theme: String): String = when (WidgetThemes.normalize(theme)) {
    WidgetThemes.GRAPHITE -> "Graphite"
    WidgetThemes.PAPER -> "Paper"
    WidgetThemes.OLED -> "OLED"
    WidgetThemes.GLASS -> "Glass"
    WidgetThemes.CLASSIC -> "Classic"
    else -> "Авто"
}
