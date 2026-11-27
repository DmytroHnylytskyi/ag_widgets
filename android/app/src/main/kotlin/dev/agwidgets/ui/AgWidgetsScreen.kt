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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import dev.agwidgets.BuildConfig
import dev.agwidgets.data.AgStatus
import dev.agwidgets.data.NtfyClient
import dev.agwidgets.data.StatusStore
import dev.agwidgets.data.ZCodeClient
import dev.agwidgets.data.ZCodeOAuthPollResult
import dev.agwidgets.data.ZCodeStore
import dev.agwidgets.notify.Notifier
import dev.agwidgets.ui.components.AccessCard
import dev.agwidgets.ui.components.ChannelCard
import dev.agwidgets.ui.components.DiagnosticsCard
import dev.agwidgets.ui.components.Header
import dev.agwidgets.ui.components.HeroCard
import dev.agwidgets.ui.components.SurfaceCard
import dev.agwidgets.ui.theme.AgTheme
import dev.agwidgets.widget.LimitsWidget
import dev.agwidgets.widget.LimitsWidgetReceiver
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

/** App screen: configuration cards plus the live quota and context dashboard. */
@Composable
internal fun AgWidgetsScreen(darkTheme: Boolean, onToggleTheme: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = AgTheme.colors

    var topic by remember { mutableStateOf(StatusStore.getTopic(context)) }
    var zcodeToken by remember { mutableStateOf(if (BuildConfig.ZCODE_ENABLED) ZCodeStore.getToken(context) else "") }
    var lastStatus by remember { mutableStateOf<AgStatus?>(StatusStore.getMergedStatus(context)) }
    var lastError by remember { mutableStateOf<String?>(StatusStore.getLastError(context)) }
    var statusMessage by remember { mutableStateOf(lastError ?: "") }
    var zcodeMessage by remember {
        mutableStateOf(if (zcodeToken.isNotBlank()) "Автономная проверка ZCode активна" else "Не подключено")
    }
    var isRefreshing by remember { mutableStateOf(false) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(StatusStore.getMode(context)) }
    var notificationsDenied by remember { mutableStateOf(false) }

    val oauthState by OAuthSessionHolder.state.collectAsState()
    val isOAuthPending = oauthState is OAuthUiState.Waiting

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
                NtfyClient.fetchLatestMessage(topic).fold(
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
                                "network" -> "Нет связи с ZCode"
                                "rate_limited" -> "ZCode временно ограничил запросы (429) — подождите несколько минут"
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

    LaunchedEffect(Unit) {
        val canFetchPc = mode != StatusStore.MODE_ZCODE && topic.isNotBlank()
        val canFetchZCode = BuildConfig.ZCODE_ENABLED && zcodeToken.isNotBlank()
        if (lastStatus == null && (canFetchPc || canFetchZCode)) refresh()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AgTheme.colors.background),
    ) {
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

        if (mode == StatusStore.MODE_UNSET) {
            OnboardingScreen(
                zcodeAvailable = BuildConfig.ZCODE_ENABLED,
                onChoosePc = {
                    StatusStore.setMode(context, StatusStore.MODE_PC)
                    mode = StatusStore.MODE_PC
                    if (topic.isNotBlank()) refresh()
                },
                onChooseZcode = {
                    StatusStore.setMode(context, StatusStore.MODE_ZCODE)
                    mode = StatusStore.MODE_ZCODE
                    if (zcodeToken.isNotBlank()) refresh()
                },
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Header(
                    status = lastStatus,
                    nowMs = nowMs,
                    darkTheme = darkTheme,
                    onToggleTheme = onToggleTheme,
                )
                if (notificationsDenied) {
                    Text(
                        text = "Уведомления отключены — напоминания о сбросе квоты не придут",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        letterSpacing = 0.4.sp,
                        color = AgTheme.colors.amber,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                if (mode != StatusStore.MODE_ZCODE) {
                    HeroCard(
                        status = lastStatus,
                        nowMs = nowMs,
                        isRefreshing = isRefreshing,
                        onRefresh = { refresh() },
                    )
                    ChannelCard(
                        topic = topic,
                        onTopicChange = { topic = it },
                        isRefreshing = isRefreshing,
                        statusMessage = statusMessage,
                        onSave = {
                            val prevTopic = StatusStore.getTopic(context)
                            val nextTopic = topic.trim()
                            StatusStore.setTopic(context, nextTopic)
                            // Alerts scheduled for the old topic are meaningless now.
                            if (nextTopic.isEmpty() || nextTopic != prevTopic) {
                                ResetAlertWorker.cancelAllResetAlerts(context)
                            }
                            syncPeriodicSchedule()
                            lastStatus = StatusStore.getMergedStatus(context)
                            lastError = StatusStore.getLastError(context)
                            nowMs = System.currentTimeMillis()
                            statusMessage = if (nextTopic.isEmpty()) "Тема очищена" else "Тема сохранена"
                            scope.launch { runCatching { LimitsWidget().updateAll(context) } }
                        },
                        onRefresh = { refresh() },
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
                    SurfaceCard(onPinWidget = { pinWidget() })
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
                        }
                    } else {
                        null
                    },
                )
                Text(
                    text = when {
                        mode == StatusStore.MODE_ZCODE ->
                            "AGW / 01 · ТОКЕН ZCODE ХРАНИТСЯ ТОЛЬКО НА ТЕЛЕФОНЕ"
                        BuildConfig.ZCODE_ENABLED ->
                            "AGW / 01 · В NTFY УХОДЯТ ЧИСЛА КВОТ, ТОКЕНОВ, ID ДИАЛОГА И ФЛАГ ВАУЧЕРА"
                        else ->
                            "AGW / 01 · В NTFY УХОДЯТ ЧИСЛА КВОТ, ТОКЕНОВ И ID ДИАЛОГА"
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    letterSpacing = 0.8.sp,
                    color = AgTheme.colors.muted.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}
