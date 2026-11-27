package dev.agwidgets.ui.i18n

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/**
 * UI language selector.
 *
 * [AUTO] follows the system language; only Russian and English are shipped, so
 * every other system language resolves to English.
 */
object AppLanguage {
    const val AUTO = "auto"
    const val RU = "ru"
    const val EN = "en"

    /** Unknown, blank and legacy values fall back to [AUTO]. */
    fun normalize(raw: String?): String = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        RU -> RU
        EN -> EN
        else -> AUTO
    }

    /** Concrete [RU]/[EN] for [mode]; [AUTO] consults [systemLang]. */
    fun resolve(mode: String?, systemLang: String = Locale.getDefault().language): String =
        when (normalize(mode)) {
            RU -> RU
            EN -> EN
            else -> if (systemLang.trim().lowercase(Locale.ROOT).startsWith(RU)) RU else EN
        }
}

/** [title]. */
data class TimeStrings(
    val justNow: String,
    val minutesAgoPattern: String,
    val hoursAgoPattern: String,
    val daysAgoPattern: String,
    val resetNow: String,
    val countdownDaysPattern: String,
    val countdownHoursPattern: String,
    val countdownMinutesPattern: String,
    val quotaWindow5h: String,
    val quotaWindowWeekly: String,
    val quotaWindow5hShort: String,
    val quotaWindowWeeklyShort: String,
)

/** [title]. */
data class CommonStrings(
    val cancel: String,
    val close: String,
    val delete: String,
    val save: String,
    val configure: String,
    val reset: String,
    val back: String,
    val refresh: String,
    val next: String,
    val apply: String,
    val refreshData: String,
    val refreshing: String,
    val unknownError: String,
    val networkError: String,
)

/** [title]. */
data class BrandStrings(
    val widgets: String,
    val widgetsCaps: String,
    val antigravity: String,
    val antigravity001: String,
    val antigravityZcode001: String,
    val agw01: String,
    val agContext: String,
)

/** [title]. */
data class SerialStrings(
    val context: String,
    val limits: String,
    val access: String,
    val channel: String,
    val surface: String,
    val system: String,
    val theme: String,
    val zcode: String,
    val pcFirst: String,
    val pcSecond: String,
)

/** [title]. */
data class LabelStrings(
    val context: String,
    val limits: String,
    val zcode: String,
    val theme: String,
    val widget: String,
)

/** [title]. */
data class StatusStrings(
    val noData: String,
    val online: String,
    val quiet: String,
    val stale: String,
    val classicNoData: String,
    val classicLive: String,
    val classicQuiet: String,
    val classicStale: String,
    val loginRequired: String,
    val rateLimited: String,
    val noConnection: String,
    val checkFailed: String,
    val unknown: String,
    val notConnected: String,
    val coupon: String,
    val balance: String,
    val noCoupon: String,
    val active: String,
    val notRunning: String,
    val noDataLower: String,
    val zcodeNoData: String,
    val zcodeSignIn: String,
    val zcodeNoConnection: String,
    val zcodeCheckFailed: String,
    val zcodeNotConnected: String,
    val zcodeCouponAvailable: String,
    val zcodeNoCoupon: String,
    val zcodeBalance: String,
)

/** [title]. */
data class DashStrings(
    val oAuthOpening: String,
    val oAuthError: String,
    val oAuthOpenBrowserFailed: String,
    val oAuthFinishInBrowser: String,
    val oAuthDone: String,
    val oAuthTimeout: String,
    val refreshing: String,
    val ntfyOk: String,
    val ntfyBadJson: String,
    val ntfyError: String,
    val zcodeTokenExpired: String,
    val zcodeNoConnection: String,
    val zcodeRateLimited: String,
    val zcodeServerError: String,
    val zcodeApiChanged: String,
    val zcodeParseFailed: String,
    val zcodeUnknown: String,
    val zcodeCouponAvailable: String,
    val zcodeRemaining: String,
    val zcodeOkNoCoupons: String,
    val zcodePrefix: String,
    val zcodeOk: String,
    val zcodeRateLimitBackoff: String,
    val signInPrompt: String,
    val setNtfyTopic: String,
    val tokenRemoved: String,
    val pinUnsupported: String,
    val notificationsDisabled: String,
    val serverHttpsRequired: String,
    val topicCleared: String,
    val topicSaved: String,
    val e2eKeyReset: String,
    val tokenEmpty: String,
    val tokenSaved: String,
    val footerZcodeOnly: String,
    val footerFull: String,
    val footerContext: String,
    val footerClassicPattern: String,
    val pairingConnectTitle: String,
    val pairingReadFailedTitle: String,
    val pairingApp: String,
    val pairingServer: String,
    val pairingTopic: String,
    val pairingE2eOn: String,
    val pairingE2eOff: String,
    val connect: String,
    val pairingDone: String,
    val pairingApplyFailed: String,
    val contextChipCompact: String,
    val contextChipAttention: String,
    val contextChipOk: String,
    val contextChipWaiting: String,
    val contextWaiting: String,
    val contextNoPcData: String,
    val contextCurrentChat: String,
    val contextOfWindow: String,
    val contextPctUsed: String,
    val contextSafeUntil: String,
    val contextRiskAfter: String,
    val limitsClassicGemini5h: String,
    val limitsClassicGeminiWeekly: String,
    val limitsClassicGpt5h: String,
    val limitsClassicGptWeekly: String,
    val limitsRemainingTitle: String,
    val limitsGemini5h: String,
    val limitsGeminiWeekly: String,
    val limitsThird5h: String,
    val limitsThirdWeekly: String,
    val limitsResetIn: String,
    val zcodeRemainingOf: String,
    val zcodeRemainingOnly: String,
    val zcodeValidUntil: String,
    val zcodeWaitingLogin: String,
    val zcodeSignIn: String,
    val surfaceTitleCount: String,
    val surfaceNotAdded: String,
    val surfaceOnScreen: String,
    val surfaceDescriptionFull: String,
    val surfaceDescriptionContext: String,
    val addWidget: String,
    val headerSubtitleA11y: String,
    val scannerUnavailable: String,
)

/** [title]. */
data class OnbStrings(
    val question: String,
    val modeChangeHint: String,
    val contextRequiresPc: String,
    val zcodeOnly: String,
    val noPc: String,
    val zcodeDescription: String,
    val chooseZcode: String,
    val withPc: String,
    val pcDescriptionFull: String,
    val pcDescriptionContext: String,
    val choosePc: String,
    val footerFull: String,
    val footerContext: String,
)

/** [title]. */
data class SettingsStrings(
    val title: String,
    val themeTitle: String,
    val themeA11y: String,
    val themeA11ySelected: String,
    val themeAuto: String,
    val languageTitle: String,
    val languageAria: String,
)

/** [title]. */
data class ChannelStrings(
    val notConfigured: String,
    val description: String,
    val topicLabel: String,
    val serverLabel: String,
    val serverHint: String,
    val refreshing: String,
    val pastePairing: String,
    val resetE2eKey: String,
    val pastePairingTitle: String,
    val pairingCodeLabel: String,
    val resetE2eTitle: String,
    val resetE2eBody: String,
    val scanQr: String,
    val summaryE2e: String,
    val summaryPlain: String,
    val emptyHint: String,
    val manualShow: String,
    val manualHide: String,
)

/** [title]. */
data class AccessStrings(
    val description: String,
    val tokenLabel: String,
    val waiting: String,
    val saveToken: String,
    val clearToken: String,
    val deleteTitle: String,
    val deleteBody: String,
    val balanceClassic: String,
    val balanceClassicTotal: String,
    val untilClassic: String,
    val until: String,
)

/** [title]. */
data class DiagStrings(
    val title: String,
    val hide: String,
    val showJson: String,
    val noData: String,
    val lastError: String,
    val changeModeClassic: String,
    val changeMode: String,
)

/** [title]. */
data class WidgetConfigStrings(
    val title: String,
    val saving: String,
    val save: String,
    val presets: String,
    val blocks: String,
    val moveUp: String,
    val moveDown: String,
    val blockHeader: String,
    val optionCompactChip: String,
    val optionResetTimer: String,
    val optionBalanceBar: String,
    val style: String,
    val accent: String,
    val accentDefault: String,
    val accentHex: String,
    val opacity: String,
    val cornerRadius: String,
    val fontScale: String,
    val density: String,
    val densityCompact: String,
    val densityComfortable: String,
    val densitySpacious: String,
    val resetAction: String,
    val resetTitle: String,
    val resetBody: String,
    val exportImport: String,
    val exportJson: String,
    val jsonCopied: String,
    val importJson: String,
    val importPlaceholder: String,
    val importHint: String,
    val configLabel: String,
    val importInvalid: String,
    val presetDashboard: String,
    val presetClassic: String,
    val presetContext: String,
    val presetDetailed: String,
    val presetZcodeOnly: String,
    val presetCustom: String,
)

/** [title]. */
data class WidgetPreviewStrings(
    val hiddenOne: String,
    val hiddenMany: String,
    val gemini5h: String,
    val geminiWeekly: String,
    val third5h: String,
    val thirdWeekly: String,
    val demoCountdown1: String,
    val demoCountdown2: String,
    val demoCountdown3: String,
    val demoCountdown4: String,
)

/** [title]. */
data class WidgetAtomsStrings(
    val contextUnavailable: String,
    val staleSuffix: String,
    val classicGemini5h: String,
    val classicGeminiWeekly: String,
    val classicThird5h: String,
    val classicThirdWeekly: String,
    val gemini5h: String,
    val geminiWeekly: String,
    val third5h: String,
    val thirdWeekly: String,
    val classicChat: String,
    val classicPc: String,
    val noDataPrompt: String,
    val collectError: String,
)

/** [title]. */
data class NotifStrings(
    val channelName: String,
    val channelDescriptionFull: String,
    val channelDescriptionContext: String,
    val availablePct: String,
    val limitReset: String,
    val quotaUpdated: String,
    val couponDetail: String,
    val couponTitle: String,
    val scheduledResetBody: String,
    val bucketGemini: String,
    val bucketThird: String,
    val window5h: String,
    val windowWeekly: String,
)

/** [title]. */
data class WorkStrings(
    val badJson: String,
    val ntfyNetworkError: String,
    val zcodeAuthExpired: String,
    val zcodeRateLimited: String,
    val zcodeServerError: String,
    val zcodeApiChanged: String,
    val zcodeParseFailed: String,
    val zcodeNoConnection: String,
    val zcodeNetworkError: String,
)

/** [title]. */
data class NtfyStrings(
    val noMessages: String,
    val noStatus: String,
    val encryptedNoKey: String,
    val wrongKey: String,
    val badJson: String,
    val expectedE2e: String,
)

/** [title]. */
data class E2eStrings(
    val unsupportedVersion: String,
    val unsupportedAlg: String,
    val invalidEnvelope: String,
    val invalidIv: String,
    val tooShort: String,
    val invalidKey: String,
    val decryptFailed: String,
    val emptyPairingLink: String,
    val invalidPairingLink: String,
    val schemeRequired: String,
    val pairHostRequired: String,
    val noPath: String,
    val invalidParam: String,
    val duplicateParam: String,
    val unsupportedPairingVersion: String,
    val invalidTopic: String,
    val invalidServer: String,
    val emptyKey: String,
)

/** [title]. */
data class ZCodeClientStrings(
    val initFailed: String,
    val initEmpty: String,
    val initInvalidFields: String,
    val sessionExpired: String,
    val loginRejected: String,
    val noTokenFromServer: String,
    val tokenMissing: String,
)

/** [title]. */
data class LanguageStrings(
    val auto: String,
    val ru: String,
    val en: String,
)

/**
 * Every user-visible Android string, in Russian and English.
 *
 * The JVM method descriptor caps a constructor at 255 parameter slots, so the
 * roughly 320 strings are stored in per-area data classes and exposed below as
 * flat `val` properties. `RuStrings`/`EnStrings` use named arguments at both
 * levels, so a missing or renamed string is a compile error, not a silent drift.
 */
data class AgStrings(
    val time: TimeStrings,
    val common: CommonStrings,
    val brand: BrandStrings,
    val serial: SerialStrings,
    val labels: LabelStrings,
    val status: StatusStrings,
    val dash: DashStrings,
    val onb: OnbStrings,
    val settings: SettingsStrings,
    val channel: ChannelStrings,
    val access: AccessStrings,
    val diag: DiagStrings,
    val widgetConfig: WidgetConfigStrings,
    val widgetPreview: WidgetPreviewStrings,
    val widgetAtoms: WidgetAtomsStrings,
    val notif: NotifStrings,
    val work: WorkStrings,
    val ntfy: NtfyStrings,
    val e2e: E2eStrings,
    val zcodeClient: ZCodeClientStrings,
    val language: LanguageStrings,
) {
    // time
    val justNow: String get() = time.justNow
    val minutesAgoPattern: String get() = time.minutesAgoPattern
    val hoursAgoPattern: String get() = time.hoursAgoPattern
    val daysAgoPattern: String get() = time.daysAgoPattern
    val resetNow: String get() = time.resetNow
    val countdownDaysPattern: String get() = time.countdownDaysPattern
    val countdownHoursPattern: String get() = time.countdownHoursPattern
    val countdownMinutesPattern: String get() = time.countdownMinutesPattern
    val quotaWindow5h: String get() = time.quotaWindow5h
    val quotaWindowWeekly: String get() = time.quotaWindowWeekly
    val quotaWindow5hShort: String get() = time.quotaWindow5hShort
    val quotaWindowWeeklyShort: String get() = time.quotaWindowWeeklyShort

    // common actions
    val commonCancel: String get() = common.cancel
    val commonClose: String get() = common.close
    val commonDelete: String get() = common.delete
    val commonSave: String get() = common.save
    val commonConfigure: String get() = common.configure
    val commonReset: String get() = common.reset
    val commonBack: String get() = common.back
    val commonRefresh: String get() = common.refresh
    val commonNext: String get() = common.next
    val commonApply: String get() = common.apply
    val commonRefreshData: String get() = common.refreshData
    val commonRefreshing: String get() = common.refreshing
    val commonUnknownError: String get() = common.unknownError
    val commonNetworkError: String get() = common.networkError

    // brand and techno labels
    val brandWidgets: String get() = brand.widgets
    val brandWidgetsCaps: String get() = brand.widgetsCaps
    val brandAntigravity: String get() = brand.antigravity
    val brandAntigravity001: String get() = brand.antigravity001
    val brandAntigravityZcode001: String get() = brand.antigravityZcode001
    val brandAgw01: String get() = brand.agw01
    val brandAgContext: String get() = brand.agContext

    // Classic card serials
    val serialContext: String get() = serial.context
    val serialLimits: String get() = serial.limits
    val serialAccess: String get() = serial.access
    val serialChannel: String get() = serial.channel
    val serialSurface: String get() = serial.surface
    val serialSystem: String get() = serial.system
    val serialTheme: String get() = serial.theme
    val serialZcode: String get() = serial.zcode
    val serialPcFirst: String get() = serial.pcFirst
    val serialPcSecond: String get() = serial.pcSecond

    // shared short labels
    val labelContext: String get() = labels.context
    val labelLimits: String get() = labels.limits
    val labelZcode: String get() = labels.zcode
    val labelTheme: String get() = labels.theme
    val labelWidget: String get() = labels.widget

    // status chips and ZCode vocabulary
    val statusNoData: String get() = status.noData
    val statusOnline: String get() = status.online
    val statusQuiet: String get() = status.quiet
    val statusStale: String get() = status.stale
    val statusClassicNoData: String get() = status.classicNoData
    val statusClassicLive: String get() = status.classicLive
    val statusClassicQuiet: String get() = status.classicQuiet
    val statusClassicStale: String get() = status.classicStale
    val statusLoginRequired: String get() = status.loginRequired
    val statusRateLimited: String get() = status.rateLimited
    val statusNoConnection: String get() = status.noConnection
    val statusCheckFailed: String get() = status.checkFailed
    val statusUnknown: String get() = status.unknown
    val statusNotConnected: String get() = status.notConnected
    val statusCoupon: String get() = status.coupon
    val statusBalance: String get() = status.balance
    val statusNoCoupon: String get() = status.noCoupon
    val statusActive: String get() = status.active
    val statusNotRunning: String get() = status.notRunning
    val statusNoDataLower: String get() = status.noDataLower
    val statusZcodeNoData: String get() = status.zcodeNoData
    val statusZcodeSignIn: String get() = status.zcodeSignIn
    val statusZcodeNoConnection: String get() = status.zcodeNoConnection
    val statusZcodeCheckFailed: String get() = status.zcodeCheckFailed
    val statusZcodeNotConnected: String get() = status.zcodeNotConnected
    val statusZcodeCouponAvailable: String get() = status.zcodeCouponAvailable
    val statusZcodeNoCoupon: String get() = status.zcodeNoCoupon
    val statusZcodeBalance: String get() = status.zcodeBalance

    // dashboard and Compose cards
    val dashOAuthOpening: String get() = dash.oAuthOpening
    val dashOAuthError: String get() = dash.oAuthError
    val dashOAuthOpenBrowserFailed: String get() = dash.oAuthOpenBrowserFailed
    val dashOAuthFinishInBrowser: String get() = dash.oAuthFinishInBrowser
    val dashOAuthDone: String get() = dash.oAuthDone
    val dashOAuthTimeout: String get() = dash.oAuthTimeout
    val dashRefreshing: String get() = dash.refreshing
    val dashNtfyOk: String get() = dash.ntfyOk
    val dashNtfyBadJson: String get() = dash.ntfyBadJson
    val dashNtfyError: String get() = dash.ntfyError
    val dashZcodeTokenExpired: String get() = dash.zcodeTokenExpired
    val dashZcodeNoConnection: String get() = dash.zcodeNoConnection
    val dashZcodeRateLimited: String get() = dash.zcodeRateLimited
    val dashZcodeServerError: String get() = dash.zcodeServerError
    val dashZcodeApiChanged: String get() = dash.zcodeApiChanged
    val dashZcodeParseFailed: String get() = dash.zcodeParseFailed
    val dashZcodeUnknown: String get() = dash.zcodeUnknown
    val dashZcodeCouponAvailable: String get() = dash.zcodeCouponAvailable
    val dashZcodeRemaining: String get() = dash.zcodeRemaining
    val dashZcodeOkNoCoupons: String get() = dash.zcodeOkNoCoupons
    val dashZcodePrefix: String get() = dash.zcodePrefix
    val dashZcodeOk: String get() = dash.zcodeOk
    val dashZcodeRateLimitBackoff: String get() = dash.zcodeRateLimitBackoff
    val dashSignInPrompt: String get() = dash.signInPrompt
    val dashSetNtfyTopic: String get() = dash.setNtfyTopic
    val dashTokenRemoved: String get() = dash.tokenRemoved
    val dashPinUnsupported: String get() = dash.pinUnsupported
    val dashNotificationsDisabled: String get() = dash.notificationsDisabled
    val dashServerHttpsRequired: String get() = dash.serverHttpsRequired
    val dashTopicCleared: String get() = dash.topicCleared
    val dashTopicSaved: String get() = dash.topicSaved
    val dashE2eKeyReset: String get() = dash.e2eKeyReset
    val dashScannerUnavailable: String get() = dash.scannerUnavailable
    val dashTokenEmpty: String get() = dash.tokenEmpty
    val dashTokenSaved: String get() = dash.tokenSaved
    val dashFooterZcodeOnly: String get() = dash.footerZcodeOnly
    val dashFooterFull: String get() = dash.footerFull
    val dashFooterContext: String get() = dash.footerContext
    val dashFooterClassicPattern: String get() = dash.footerClassicPattern
    val dashPairingConnectTitle: String get() = dash.pairingConnectTitle
    val dashPairingReadFailedTitle: String get() = dash.pairingReadFailedTitle
    val dashPairingApp: String get() = dash.pairingApp
    val dashPairingServer: String get() = dash.pairingServer
    val dashPairingTopic: String get() = dash.pairingTopic
    val dashPairingE2eOn: String get() = dash.pairingE2eOn
    val dashPairingE2eOff: String get() = dash.pairingE2eOff
    val dashConnect: String get() = dash.connect
    val dashPairingDone: String get() = dash.pairingDone
    val dashPairingApplyFailed: String get() = dash.pairingApplyFailed
    val dashContextChipCompact: String get() = dash.contextChipCompact
    val dashContextChipAttention: String get() = dash.contextChipAttention
    val dashContextChipOk: String get() = dash.contextChipOk
    val dashContextChipWaiting: String get() = dash.contextChipWaiting
    val dashContextWaiting: String get() = dash.contextWaiting
    val dashContextNoPcData: String get() = dash.contextNoPcData
    val dashContextCurrentChat: String get() = dash.contextCurrentChat
    val dashContextOfWindow: String get() = dash.contextOfWindow
    val dashContextPctUsed: String get() = dash.contextPctUsed
    val dashContextSafeUntil: String get() = dash.contextSafeUntil
    val dashContextRiskAfter: String get() = dash.contextRiskAfter
    val dashLimitsClassicGemini5h: String get() = dash.limitsClassicGemini5h
    val dashLimitsClassicGeminiWeekly: String get() = dash.limitsClassicGeminiWeekly
    val dashLimitsClassicGpt5h: String get() = dash.limitsClassicGpt5h
    val dashLimitsClassicGptWeekly: String get() = dash.limitsClassicGptWeekly
    val dashLimitsRemainingTitle: String get() = dash.limitsRemainingTitle
    val dashLimitsGemini5h: String get() = dash.limitsGemini5h
    val dashLimitsGeminiWeekly: String get() = dash.limitsGeminiWeekly
    val dashLimitsThird5h: String get() = dash.limitsThird5h
    val dashLimitsThirdWeekly: String get() = dash.limitsThirdWeekly
    val dashLimitsResetIn: String get() = dash.limitsResetIn
    val dashZcodeRemainingOf: String get() = dash.zcodeRemainingOf
    val dashZcodeRemainingOnly: String get() = dash.zcodeRemainingOnly
    val dashZcodeValidUntil: String get() = dash.zcodeValidUntil
    val dashZcodeWaitingLogin: String get() = dash.zcodeWaitingLogin
    val dashZcodeSignIn: String get() = dash.zcodeSignIn
    val dashSurfaceTitleCount: String get() = dash.surfaceTitleCount
    val dashSurfaceNotAdded: String get() = dash.surfaceNotAdded
    val dashSurfaceOnScreen: String get() = dash.surfaceOnScreen
    val dashSurfaceDescriptionFull: String get() = dash.surfaceDescriptionFull
    val dashSurfaceDescriptionContext: String get() = dash.surfaceDescriptionContext
    val dashAddWidget: String get() = dash.addWidget
    val dashHeaderSubtitleA11y: String get() = dash.headerSubtitleA11y

    // onboarding
    val onbQuestion: String get() = onb.question
    val onbModeChangeHint: String get() = onb.modeChangeHint
    val onbContextRequiresPc: String get() = onb.contextRequiresPc
    val onbZcodeOnly: String get() = onb.zcodeOnly
    val onbNoPc: String get() = onb.noPc
    val onbZcodeDescription: String get() = onb.zcodeDescription
    val onbChooseZcode: String get() = onb.chooseZcode
    val onbWithPc: String get() = onb.withPc
    val onbPcDescriptionFull: String get() = onb.pcDescriptionFull
    val onbPcDescriptionContext: String get() = onb.pcDescriptionContext
    val onbChoosePc: String get() = onb.choosePc
    val onbFooterFull: String get() = onb.footerFull
    val onbFooterContext: String get() = onb.footerContext

    // settings screen
    val setTitle: String get() = settings.title
    val setThemeTitle: String get() = settings.themeTitle
    val setThemeA11y: String get() = settings.themeA11y
    val setThemeA11ySelected: String get() = settings.themeA11ySelected
    val setThemeAuto: String get() = settings.themeAuto
    val setLanguageTitle: String get() = settings.languageTitle
    val setLanguageAria: String get() = settings.languageAria

    // ntfy channel card
    val chNotConfigured: String get() = channel.notConfigured
    val chDescription: String get() = channel.description
    val chTopicLabel: String get() = channel.topicLabel
    val chServerLabel: String get() = channel.serverLabel
    val chServerHint: String get() = channel.serverHint
    val chRefreshing: String get() = channel.refreshing
    val chPastePairing: String get() = channel.pastePairing
    val chResetE2eKey: String get() = channel.resetE2eKey
    val chPastePairingTitle: String get() = channel.pastePairingTitle
    val chPairingCodeLabel: String get() = channel.pairingCodeLabel
    val chResetE2eTitle: String get() = channel.resetE2eTitle
    val chResetE2eBody: String get() = channel.resetE2eBody
    val chScanQr: String get() = channel.scanQr
    val chSummaryE2e: String get() = channel.summaryE2e
    val chSummaryPlain: String get() = channel.summaryPlain
    val chEmptyHint: String get() = channel.emptyHint
    val chManualShow: String get() = channel.manualShow
    val chManualHide: String get() = channel.manualHide

    // ZCode access card
    val accDescription: String get() = access.description
    val accTokenLabel: String get() = access.tokenLabel
    val accWaiting: String get() = access.waiting
    val accSaveToken: String get() = access.saveToken
    val accClearToken: String get() = access.clearToken
    val accDeleteTitle: String get() = access.deleteTitle
    val accDeleteBody: String get() = access.deleteBody
    val accBalanceClassic: String get() = access.balanceClassic
    val accBalanceClassicTotal: String get() = access.balanceClassicTotal
    val accUntilClassic: String get() = access.untilClassic
    val accUntil: String get() = access.until

    // diagnostics
    val diagTitle: String get() = diag.title
    val diagHide: String get() = diag.hide
    val diagShowJson: String get() = diag.showJson
    val diagNoData: String get() = diag.noData
    val diagLastError: String get() = diag.lastError
    val diagChangeModeClassic: String get() = diag.changeModeClassic
    val diagChangeMode: String get() = diag.changeMode

    // widget builder
    val wconfTitle: String get() = widgetConfig.title
    val wconfSaving: String get() = widgetConfig.saving
    val wconfSave: String get() = widgetConfig.save
    val wconfPresets: String get() = widgetConfig.presets
    val wconfBlocks: String get() = widgetConfig.blocks
    val wconfMoveUp: String get() = widgetConfig.moveUp
    val wconfMoveDown: String get() = widgetConfig.moveDown
    val wconfBlockHeader: String get() = widgetConfig.blockHeader
    val wconfOptionCompactChip: String get() = widgetConfig.optionCompactChip
    val wconfOptionResetTimer: String get() = widgetConfig.optionResetTimer
    val wconfOptionBalanceBar: String get() = widgetConfig.optionBalanceBar
    val wconfStyle: String get() = widgetConfig.style
    val wconfAccent: String get() = widgetConfig.accent
    val wconfAccentDefault: String get() = widgetConfig.accentDefault
    val wconfAccentHex: String get() = widgetConfig.accentHex
    val wconfOpacity: String get() = widgetConfig.opacity
    val wconfCornerRadius: String get() = widgetConfig.cornerRadius
    val wconfFontScale: String get() = widgetConfig.fontScale
    val wconfDensity: String get() = widgetConfig.density
    val wconfDensityCompact: String get() = widgetConfig.densityCompact
    val wconfDensityComfortable: String get() = widgetConfig.densityComfortable
    val wconfDensitySpacious: String get() = widgetConfig.densitySpacious
    val wconfResetAction: String get() = widgetConfig.resetAction
    val wconfResetTitle: String get() = widgetConfig.resetTitle
    val wconfResetBody: String get() = widgetConfig.resetBody
    val wconfExportImport: String get() = widgetConfig.exportImport
    val wconfExportJson: String get() = widgetConfig.exportJson
    val wconfJsonCopied: String get() = widgetConfig.jsonCopied
    val wconfImportJson: String get() = widgetConfig.importJson
    val wconfImportPlaceholder: String get() = widgetConfig.importPlaceholder
    val wconfImportHint: String get() = widgetConfig.importHint
    val wconfConfigLabel: String get() = widgetConfig.configLabel
    val wconfImportInvalid: String get() = widgetConfig.importInvalid
    val wconfPresetDashboard: String get() = widgetConfig.presetDashboard
    val wconfPresetClassic: String get() = widgetConfig.presetClassic
    val wconfPresetContext: String get() = widgetConfig.presetContext
    val wconfPresetDetailed: String get() = widgetConfig.presetDetailed
    val wconfPresetZcodeOnly: String get() = widgetConfig.presetZcodeOnly
    val wconfPresetCustom: String get() = widgetConfig.presetCustom

    // widget live preview
    val wprevHiddenOne: String get() = widgetPreview.hiddenOne
    val wprevHiddenMany: String get() = widgetPreview.hiddenMany
    val wprevGemini5h: String get() = widgetPreview.gemini5h
    val wprevGeminiWeekly: String get() = widgetPreview.geminiWeekly
    val wprevThird5h: String get() = widgetPreview.third5h
    val wprevThirdWeekly: String get() = widgetPreview.thirdWeekly
    val wprevDemoCountdown1: String get() = widgetPreview.demoCountdown1
    val wprevDemoCountdown2: String get() = widgetPreview.demoCountdown2
    val wprevDemoCountdown3: String get() = widgetPreview.demoCountdown3
    val wprevDemoCountdown4: String get() = widgetPreview.demoCountdown4

    // Glance widget atoms
    val watomsContextUnavailable: String get() = widgetAtoms.contextUnavailable
    val watomsStaleSuffix: String get() = widgetAtoms.staleSuffix
    val watomsClassicGemini5h: String get() = widgetAtoms.classicGemini5h
    val watomsClassicGeminiWeekly: String get() = widgetAtoms.classicGeminiWeekly
    val watomsClassicThird5h: String get() = widgetAtoms.classicThird5h
    val watomsClassicThirdWeekly: String get() = widgetAtoms.classicThirdWeekly
    val watomsGemini5h: String get() = widgetAtoms.gemini5h
    val watomsGeminiWeekly: String get() = widgetAtoms.geminiWeekly
    val watomsThird5h: String get() = widgetAtoms.third5h
    val watomsThirdWeekly: String get() = widgetAtoms.thirdWeekly
    val watomsClassicChat: String get() = widgetAtoms.classicChat
    val watomsClassicPc: String get() = widgetAtoms.classicPc
    val watomsNoDataPrompt: String get() = widgetAtoms.noDataPrompt
    val watomsCollectError: String get() = widgetAtoms.collectError

    // notifications
    val notifChannelName: String get() = notif.channelName
    val notifChannelDescriptionFull: String get() = notif.channelDescriptionFull
    val notifChannelDescriptionContext: String get() = notif.channelDescriptionContext
    val notifAvailablePct: String get() = notif.availablePct
    val notifLimitReset: String get() = notif.limitReset
    val notifQuotaUpdated: String get() = notif.quotaUpdated
    val notifCouponDetail: String get() = notif.couponDetail
    val notifCouponTitle: String get() = notif.couponTitle
    val notifScheduledResetBody: String get() = notif.scheduledResetBody
    val notifBucketGemini: String get() = notif.bucketGemini
    val notifBucketThird: String get() = notif.bucketThird
    val notifWindow5h: String get() = notif.window5h
    val notifWindowWeekly: String get() = notif.windowWeekly

    // RefreshWorker errors
    val workBadJson: String get() = work.badJson
    val workNtfyNetworkError: String get() = work.ntfyNetworkError
    val workZcodeAuthExpired: String get() = work.zcodeAuthExpired
    val workZcodeRateLimited: String get() = work.zcodeRateLimited
    val workZcodeServerError: String get() = work.zcodeServerError
    val workZcodeApiChanged: String get() = work.zcodeApiChanged
    val workZcodeParseFailed: String get() = work.zcodeParseFailed
    val workZcodeNoConnection: String get() = work.zcodeNoConnection
    val workZcodeNetworkError: String get() = work.zcodeNetworkError

    // ntfy client errors
    val ntfyNoMessages: String get() = ntfy.noMessages
    val ntfyNoStatus: String get() = ntfy.noStatus
    val ntfyEncryptedNoKey: String get() = ntfy.encryptedNoKey
    val ntfyWrongKey: String get() = ntfy.wrongKey
    val ntfyBadJson: String get() = ntfy.badJson
    val ntfyExpectedE2e: String get() = ntfy.expectedE2e

    // E2E and pairing errors
    val e2eUnsupportedVersion: String get() = e2e.unsupportedVersion
    val e2eUnsupportedAlg: String get() = e2e.unsupportedAlg
    val e2eInvalidEnvelope: String get() = e2e.invalidEnvelope
    val e2eInvalidIv: String get() = e2e.invalidIv
    val e2eTooShort: String get() = e2e.tooShort
    val e2eInvalidKey: String get() = e2e.invalidKey
    val e2eDecryptFailed: String get() = e2e.decryptFailed
    val e2eEmptyPairingLink: String get() = e2e.emptyPairingLink
    val e2eInvalidPairingLink: String get() = e2e.invalidPairingLink
    val e2eSchemeRequired: String get() = e2e.schemeRequired
    val e2ePairHostRequired: String get() = e2e.pairHostRequired
    val e2eNoPath: String get() = e2e.noPath
    val e2eInvalidParam: String get() = e2e.invalidParam
    val e2eDuplicateParam: String get() = e2e.duplicateParam
    val e2eUnsupportedPairingVersion: String get() = e2e.unsupportedPairingVersion
    val e2eInvalidTopic: String get() = e2e.invalidTopic
    val e2eInvalidServer: String get() = e2e.invalidServer
    val e2eEmptyKey: String get() = e2e.emptyKey

    // ZCode OAuth client errors
    val zcInitFailed: String get() = zcodeClient.initFailed
    val zcInitEmpty: String get() = zcodeClient.initEmpty
    val zcInitInvalidFields: String get() = zcodeClient.initInvalidFields
    val zcSessionExpired: String get() = zcodeClient.sessionExpired
    val zcLoginRejected: String get() = zcodeClient.loginRejected
    val zcNoTokenFromServer: String get() = zcodeClient.noTokenFromServer
    val zcTokenMissing: String get() = zcodeClient.tokenMissing

    // language names
    val languageAuto: String get() = language.auto
    val languageRu: String get() = language.ru
    val languageEn: String get() = language.en

    /** Replaces every `{name}` in [pattern] with the matching argument. */
    fun fill(pattern: String, vararg args: Pair<String, Any>): String {
        var result = pattern
        for ((key, value) in args) {
            result = result.replace("{$key}", value.toString())
        }
        return result
    }

    fun updatedLabel(ageMinutes: Long): String = when {
        ageMinutes < 1 -> justNow
        ageMinutes < 60 -> fill(minutesAgoPattern, "n" to ageMinutes)
        ageMinutes < 24 * 60 -> fill(hoursAgoPattern, "n" to ageMinutes / 60)
        else -> fill(daysAgoPattern, "n" to ageMinutes / (24 * 60))
    }

    fun countdown(days: Long, hours: Long, minutes: Long): String = when {
        days > 0 -> fill(countdownDaysPattern, "d" to days, "h" to hours)
        hours > 0 -> fill(countdownHoursPattern, "h" to hours, "m" to minutes)
        else -> fill(countdownMinutesPattern, "m" to minutes)
    }
}

val RuStrings: AgStrings = AgStrings(
    time = TimeStrings(
        justNow = "только что",
        minutesAgoPattern = "{n} мин назад",
        hoursAgoPattern = "{n} ч назад",
        daysAgoPattern = "{n} д назад",
        resetNow = "сброс сейчас",
        countdownDaysPattern = "{d}д {h}ч",
        countdownHoursPattern = "{h}ч {m}м",
        countdownMinutesPattern = "{m}м",
        quotaWindow5h = "5 ч",
        quotaWindowWeekly = "7 дн",
        quotaWindow5hShort = "5ч",
        quotaWindowWeeklyShort = "7д",
    ),
    common = CommonStrings(
        cancel = "Отмена",
        close = "Закрыть",
        delete = "Удалить",
        save = "Сохранить",
        configure = "Настроить",
        reset = "Сбросить",
        back = "Назад",
        refresh = "Обновить",
        next = "Далее",
        apply = "Применить",
        refreshData = "Обновить данные",
        refreshing = "Обновление идёт",
        unknownError = "неизвестная ошибка",
        networkError = "ошибка сети",
    ),
    brand = BrandStrings(
        widgets = "AG Widgets",
        widgetsCaps = "AG WIDGETS",
        antigravity = "Antigravity",
        antigravity001 = "ANTIGRAVITY · 001",
        antigravityZcode001 = "ANTIGRAVITY · ZCODE · 001",
        agw01 = "AGW / 01",
        agContext = "AG Context",
    ),
    serial = SerialStrings(
        context = "01 / CONTEXT",
        limits = "02 / LIMITS",
        access = "03 / ACCESS",
        channel = "02 / CHANNEL",
        surface = "04 / SURFACE",
        system = "05 / SYSTEM",
        theme = "01 / THEME",
        zcode = "01 / ZCODE",
        pcFirst = "01 / PC",
        pcSecond = "02 / PC",
    ),
    labels = LabelStrings(
        context = "Контекст",
        limits = "Лимиты",
        zcode = "ZCode",
        theme = "Тема",
        widget = "Виджет",
    ),
    status = StatusStrings(
        noData = "Нет данных",
        online = "Онлайн",
        quiet = "Тихо",
        stale = "Устарело",
        classicNoData = "НЕТ ДАННЫХ",
        classicLive = "LIVE",
        classicQuiet = "ТИХО",
        classicStale = "СТАРО",
        loginRequired = "Нужен вход",
        rateLimited = "Лимит запросов",
        noConnection = "Нет связи",
        checkFailed = "Ошибка проверки",
        unknown = "Неизвестно",
        notConnected = "Не подключён",
        coupon = "Ваучер",
        balance = "Баланс",
        noCoupon = "Нет ваучера",
        active = "Активен",
        notRunning = "Не запущен",
        noDataLower = "нет данных",
        zcodeNoData = "ZCode: нет данных",
        zcodeSignIn = "ZCode: нужен вход",
        zcodeNoConnection = "ZCode: нет связи",
        zcodeCheckFailed = "ZCode: ошибка проверки",
        zcodeNotConnected = "ZCode: не подключён",
        zcodeCouponAvailable = "ZCode: ваучер доступен",
        zcodeNoCoupon = "ZCode: нет ваучера",
        zcodeBalance = "{plan} {amount}",
    ),
    dash = DashStrings(
        oAuthOpening = "Открываю вход Z.ai в браузере…",
        oAuthError = "Ошибка OAuth: {error}",
        oAuthOpenBrowserFailed = "Не удалось открыть браузер",
        oAuthFinishInBrowser = "Завершите вход в браузере и вернитесь сюда…",
        oAuthDone = "Вход выполнен. Автономный мониторинг включён.",
        oAuthTimeout = "Таймаут входа — попробуйте снова",
        refreshing = "Обновление...",
        ntfyOk = "ntfy OK",
        ntfyBadJson = "ntfy: неверный JSON",
        ntfyError = "ntfy: {error}",
        zcodeTokenExpired = "Токен истёк — войдите снова",
        zcodeNoConnection = "Нет связи с zcode.z.ai",
        zcodeRateLimited = "Слишком много запросов ZCode — попробуйте позже",
        zcodeServerError = "Ошибка сервера ZCode",
        zcodeApiChanged = "Схема API ZCode изменилась",
        zcodeParseFailed = "Не удалось разобрать ответ ZCode",
        zcodeUnknown = "Состояние ваучера неизвестно",
        zcodeCouponAvailable = "Ваучер доступен! ({matched})",
        zcodeRemaining = "Остаток: {amount}",
        zcodeOkNoCoupons = "ZCode OK · ваучеров пока нет",
        zcodePrefix = "ZCode: {message}",
        zcodeOk = "ZCode OK",
        zcodeRateLimitBackoff = "ZCode: лимит запросов (429) — подождите",
        signInPrompt = "Войдите в Z.ai",
        setNtfyTopic = "Укажите тему ntfy",
        tokenRemoved = "Токен удалён",
        pinUnsupported = "Лаунчер не поддерживает автодобавление — добавьте виджет вручную",
        notificationsDisabled = "Уведомления отключены — напоминания о сбросе квоты не придут",
        serverHttpsRequired = "Сервер должен начинаться с https://",
        topicCleared = "Тема очищена",
        topicSaved = "Тема сохранена",
        e2eKeyReset = "E2E-ключ сброшен",
        scannerUnavailable = "Сканер QR недоступен — вставьте ссылку сопряжения вручную",
        tokenEmpty = "Токен пуст",
        tokenSaved = "Токен сохранён",
        footerZcodeOnly = "Токен ZCode хранится только на телефоне",
        footerFull = "В ntfy уходят числа квот, токенов, ID диалога и флаг ваучера",
        footerContext = "В ntfy уходят числа квот, токенов и ID диалога",
        footerClassicPattern = "AGW / 01 · {text}",
        pairingConnectTitle = "Подключить телефон",
        pairingReadFailedTitle = "Не удалось прочитать код сопряжения",
        pairingApp = "Приложение: {app}",
        pairingServer = "Сервер: {server}",
        pairingTopic = "Тема: {topic}",
        pairingE2eOn = "E2E: AES-256-GCM",
        pairingE2eOff = "E2E: выкл",
        connect = "Подключить",
        pairingDone = "Сопряжение выполнено",
        pairingApplyFailed = "Не удалось применить код сопряжения",
        contextChipCompact = "Пора /compact",
        contextChipAttention = "Внимание",
        contextChipOk = "Ок",
        contextChipWaiting = "Ожидание",
        contextWaiting = "Текущий чат · ожидание ответа",
        contextNoPcData = "Нет данных с ПК — настройте канал ntfy в настройках",
        contextCurrentChat = "Текущий чат",
        contextOfWindow = "из {window}",
        contextPctUsed = "{pct}% занято",
        contextSafeUntil = "Надёжно до {tokens} ({pct}%)",
        contextRiskAfter = "Риск после {tokens} ({pct}%)",
        limitsClassicGemini5h = "GEMINI 5Ч",
        limitsClassicGeminiWeekly = "GEMINI НЕД",
        limitsClassicGpt5h = "GPT 5Ч",
        limitsClassicGptWeekly = "GPT НЕД",
        limitsRemainingTitle = "Лимиты · остаток",
        limitsGemini5h = "Gemini · 5 ч",
        limitsGeminiWeekly = "Gemini · 7 дн",
        limitsThird5h = "Claude / GPT · 5 ч",
        limitsThirdWeekly = "Claude / GPT · 7 дн",
        limitsResetIn = "сброс через {time}",
        zcodeRemainingOf = "Осталось {remaining} из {total}",
        zcodeRemainingOnly = "Осталось {remaining}",
        zcodeValidUntil = "Действует до {date}",
        zcodeWaitingLogin = "Ожидание входа…",
        zcodeSignIn = "Войти в Z.ai",
        surfaceTitleCount = "Виджет · {count}",
        surfaceNotAdded = "Не добавлен",
        surfaceOnScreen = "На экране",
        surfaceDescriptionFull = "Шкалы 5 ч и недели, обратный отсчёт, статус ZCode и контекст ПК. Уведомления о сбросе приходят без компьютера.",
        surfaceDescriptionContext = "Шкалы 5 ч и недели, обратный отсчёт и контекст ПК. Уведомления о сбросе приходят без компьютера.",
        addWidget = "Добавить виджет на экран",
        headerSubtitleA11y = "Antigravity, ZCode, сборка 001",
    ),
    onb = OnbStrings(
        question = "Как вы будете пользоваться?",
        modeChangeHint = "Режим можно сменить позже в разделе «Диагностика».",
        contextRequiresPc = "AG Context работает вместе с коллектором на компьютере.",
        zcodeOnly = "Только ZCode",
        noPc = "Без ПК",
        zcodeDescription = "Ваучеры на 300–500M токенов и баланс. Компьютер не нужен: приложение само проверяет ваучеры.",
        chooseZcode = "Выбрать ZCode",
        withPc = "С компьютером",
        pcDescriptionFull = "Контекст открытого чата Antigravity, лимиты 5 ч и недели и ZCode. Нужен компьютер с установленным коллектором.",
        pcDescriptionContext = "Контекст открытого чата Antigravity и лимиты 5 ч и недели. Нужен компьютер с установленным коллектором.",
        choosePc = "Выбрать ПК",
        footerFull = "AG Widgets · Antigravity + ZCode",
        footerContext = "AG Context · только Antigravity",
    ),
    settings = SettingsStrings(
        title = "Настройки",
        themeTitle = "Тема оформления",
        themeA11y = "Тема {theme}",
        themeA11ySelected = "Тема {theme}, выбрана",
        themeAuto = "Авто",
        languageTitle = "Язык",
        languageAria = "Язык интерфейса",
    ),
    channel = ChannelStrings(
        notConfigured = "Не настроен",
        description = "Квоты 5 ч и недели, контекст ПК и статус ZCode приходят с компьютера через приватный ntfy-канал.",
        topicLabel = "Тема ntfy",
        serverLabel = "Сервер ntfy",
        serverHint = "Только https://. Пустое значение вернёт https://ntfy.sh",
        refreshing = "Обновляю…",
        pastePairing = "Вставить код сопряжения",
        resetE2eKey = "Сбросить E2E-ключ",
        pastePairingTitle = "Вставить код сопряжения",
        pairingCodeLabel = "Код agw://pair?...",
        resetE2eTitle = "Сбросить E2E-ключ?",
        resetE2eBody = "Телефон перестанет расшифровывать сообщения, пока вы не отсканируете новый QR-код.",
        scanQr = "Сканировать QR-код с ПК",
        summaryE2e = "Канал: {topic} · E2E включён",
        summaryPlain = "Канал: {topic} · без E2E",
        emptyHint = "Откройте Настройки → Синхронизация на ПК и отсканируйте QR-код.",
        manualShow = "Настроить вручную",
        manualHide = "Скрыть ручные настройки",
    ),
    access = AccessStrings(
        description = "Прямой опрос zcode.z.ai каждые 15 минут: ваучеры на 300–500M токенов и баланс приходят даже при выключенном компьютере.",
        tokenLabel = "Или вставьте JWT-токен вручную",
        waiting = "Ожидание…",
        saveToken = "Сохранить токен",
        clearToken = "Очистить токен",
        deleteTitle = "Удалить токен ZCode?",
        deleteBody = "Автономная проверка ваучеров и баланса остановится до следующего входа.",
        balanceClassic = "ОСТАТОК {remaining}",
        balanceClassicTotal = "ОСТАТОК {remaining} / {total}",
        untilClassic = "ДО {date}",
        until = "до {date}",
    ),
    diag = DiagStrings(
        title = "Диагностика",
        hide = "Скрыть",
        showJson = "Показать JSON",
        noData = "Данных пока нет — нажмите «Обновить»",
        lastError = "Последняя ошибка: {error}",
        changeModeClassic = "СМЕНИТЬ РЕЖИМ",
        changeMode = "Сменить режим",
    ),
    widgetConfig = WidgetConfigStrings(
        title = "Настройка виджета",
        saving = "Сохранение…",
        save = "Сохранить виджет",
        presets = "Пресеты",
        blocks = "Блоки",
        moveUp = "Переместить «{title}» вверх",
        moveDown = "Переместить «{title}» вниз",
        blockHeader = "Шапка",
        optionCompactChip = "Чип /compact",
        optionResetTimer = "Таймер сброса",
        optionBalanceBar = "Полоса баланса",
        style = "Стиль",
        accent = "Акцент",
        accentDefault = "Акцент по умолчанию",
        accentHex = "Акцент {hex}",
        opacity = "Прозрачность",
        cornerRadius = "Скругление",
        fontScale = "Масштаб текста",
        density = "Плотность",
        densityCompact = "Компакт",
        densityComfortable = "Обычно",
        densitySpacious = "Простор",
        resetAction = "Сбросить настройки",
        resetTitle = "Сбросить настройки?",
        resetBody = "Тема, плотность и включённые блоки вернутся к 4×2 дашборду. Виджет на экране не удаляется.",
        exportImport = "Экспорт и импорт",
        exportJson = "Экспорт JSON",
        jsonCopied = "JSON скопирован",
        importJson = "Импорт JSON",
        importPlaceholder = "{\"theme\": \"graphite\", \"opacity\": 0.95, …}",
        importHint = "Например: {\"theme\": \"paper\", \"opacity\": 0.95, \"density\": \"compact\"}",
        configLabel = "Конфигурация",
        importInvalid = "Некорректный JSON — конфигурация не изменена",
        presetDashboard = "Дашборд 4×2",
        presetClassic = "Classic 4×2",
        presetContext = "Контекст 4×1",
        presetDetailed = "Подробный 4×3",
        presetZcodeOnly = "Только ZCode 2×1",
        presetCustom = "Свой",
    ),
    widgetPreview = WidgetPreviewStrings(
        hiddenOne = "+{count} блок не помещается в этой сетке",
        hiddenMany = "+{count} блока не помещается в этой сетке",
        gemini5h = "Gemini 5 ч",
        geminiWeekly = "Gemini 7 дн",
        third5h = "Claude / GPT 5 ч",
        thirdWeekly = "Claude / GPT 7 дн",
        demoCountdown1 = "2ч 40м",
        demoCountdown2 = "4д 2ч",
        demoCountdown3 = "1ч 15м",
        demoCountdown4 = "3д 8ч",
    ),
    widgetAtoms = WidgetAtomsStrings(
        contextUnavailable = "Контекст недоступен",
        staleSuffix = " · устарело",
        classicGemini5h = "GEMINI 5H",
        classicGeminiWeekly = "GEMINI WK",
        classicThird5h = "CLAUDE/GPT 5H",
        classicThirdWeekly = "CLAUDE/GPT WK",
        gemini5h = "Gemini 5 ч",
        geminiWeekly = "Gemini 7 дн",
        third5h = "Claude / GPT 5 ч",
        thirdWeekly = "Claude / GPT 7 дн",
        classicChat = "ЧАТ",
        classicPc = "ПК",
        noDataPrompt = "Нет данных — откройте AG Widgets",
        collectError = "Ошибка сбора: {error}",
    ),
    notif = NotifStrings(
        channelName = "Лимиты",
        channelDescriptionFull = "Уведомления об обновлении квот Antigravity и ваучерах ZCode",
        channelDescriptionContext = "Уведомления об обновлении квот Antigravity",
        availablePct = "Доступно: {pct}%",
        limitReset = "Лимит сброшен",
        quotaUpdated = "Квота {bucket} обновилась",
        couponDetail = "Откройте ZCode, чтобы забрать 300–500M токенов",
        couponTitle = "В ZCode доступен ваучер!",
        scheduledResetBody = "Наступило запланированное время сброса лимита",
        bucketGemini = "Gemini ",
        bucketThird = "Claude/GPT ",
        window5h = "(5h)",
        windowWeekly = "(неделя)",
    ),
    work = WorkStrings(
        badJson = "Неверный JSON в сообщении ntfy",
        ntfyNetworkError = "Ошибка сети ntfy",
        zcodeAuthExpired = "ZCode: токен истёк",
        zcodeRateLimited = "ZCode: лимит запросов (429)",
        zcodeServerError = "ZCode: ошибка сервера",
        zcodeApiChanged = "ZCode: схема API изменилась",
        zcodeParseFailed = "ZCode: не удалось разобрать ответ",
        zcodeNoConnection = "ZCode: нет связи",
        zcodeNetworkError = "Ошибка сети ZCode",
    ),
    ntfy = NtfyStrings(
        noMessages = "В теме пока нет сообщений",
        noStatus = "Нет сообщений статуса в теме ntfy",
        encryptedNoKey = "Сообщение зашифровано — отсканируйте QR-код с ПК",
        wrongKey = "Неверный E2E-ключ — отсканируйте новый QR с ПК",
        badJson = "Неверный JSON в сообщении ntfy",
        expectedE2e = "Ожидалось зашифрованное сообщение (E2E)",
    ),
    e2e = E2eStrings(
        unsupportedVersion = "Неподдерживаемая версия E2E-сообщения",
        unsupportedAlg = "Неподдерживаемый алгоритм E2E-сообщения",
        invalidEnvelope = "Некорректный E2E-конверт",
        invalidIv = "Некорректная длина IV E2E-сообщения",
        tooShort = "Слишком короткое E2E-сообщение",
        invalidKey = "Некорректный E2E-ключ",
        decryptFailed = "Не удалось расшифровать E2E-сообщение",
        emptyPairingLink = "Пустая ссылка сопряжения",
        invalidPairingLink = "Некорректная ссылка сопряжения",
        schemeRequired = "Ссылка должна использовать схему agw",
        pairHostRequired = "Ссылка должна указывать на agw://pair",
        noPath = "Ссылка не должна содержать путь",
        invalidParam = "Некорректный параметр ссылки",
        duplicateParam = "Повторяющийся параметр {key}",
        unsupportedPairingVersion = "Неподдерживаемая версия ссылки сопряжения",
        invalidTopic = "Некорректный топик ntfy",
        invalidServer = "Некорректный сервер ntfy",
        emptyKey = "Пустой E2E-ключ",
    ),
    zcodeClient = ZCodeClientStrings(
        initFailed = "Ошибка init OAuth (HTTP {code})",
        initEmpty = "Пустой ответ init",
        initInvalidFields = "Некорректные поля OAuth init",
        sessionExpired = "Сессия входа истекла (HTTP {code})",
        loginRejected = "Вход отклонён",
        noTokenFromServer = "Сервер не вернул токен",
        tokenMissing = "Токен ZCode не задан",
    ),
    language = LanguageStrings(
        auto = "Авто",
        ru = "Русский",
        en = "English",
    ),
)

val EnStrings: AgStrings = AgStrings(
    time = TimeStrings(
        justNow = "just now",
        minutesAgoPattern = "{n}m ago",
        hoursAgoPattern = "{n}h ago",
        daysAgoPattern = "{n}d ago",
        resetNow = "resets now",
        countdownDaysPattern = "{d}d {h}h",
        countdownHoursPattern = "{h}h {m}m",
        countdownMinutesPattern = "{m}m",
        quotaWindow5h = "5h",
        quotaWindowWeekly = "7d",
        quotaWindow5hShort = "5h",
        quotaWindowWeeklyShort = "7d",
    ),
    common = CommonStrings(
        cancel = "Cancel",
        close = "Close",
        delete = "Delete",
        save = "Save",
        configure = "Configure",
        reset = "Reset",
        back = "Back",
        refresh = "Refresh",
        next = "Next",
        apply = "Apply",
        refreshData = "Refresh data",
        refreshing = "Refreshing",
        unknownError = "unknown error",
        networkError = "network error",
    ),
    brand = BrandStrings(
        widgets = "AG Widgets",
        widgetsCaps = "AG WIDGETS",
        antigravity = "Antigravity",
        antigravity001 = "ANTIGRAVITY · 001",
        antigravityZcode001 = "ANTIGRAVITY · ZCODE · 001",
        agw01 = "AGW / 01",
        agContext = "AG Context",
    ),
    serial = SerialStrings(
        context = "01 / CONTEXT",
        limits = "02 / LIMITS",
        access = "03 / ACCESS",
        channel = "02 / CHANNEL",
        surface = "04 / SURFACE",
        system = "05 / SYSTEM",
        theme = "01 / THEME",
        zcode = "01 / ZCODE",
        pcFirst = "01 / PC",
        pcSecond = "02 / PC",
    ),
    labels = LabelStrings(
        context = "Context",
        limits = "Limits",
        zcode = "ZCode",
        theme = "Theme",
        widget = "Widget",
    ),
    status = StatusStrings(
        noData = "No data",
        online = "Online",
        quiet = "Quiet",
        stale = "Stale",
        classicNoData = "NO DATA",
        classicLive = "LIVE",
        classicQuiet = "QUIET",
        classicStale = "STALE",
        loginRequired = "Sign-in required",
        rateLimited = "Rate limited",
        noConnection = "No connection",
        checkFailed = "Check failed",
        unknown = "Unknown",
        notConnected = "Not connected",
        coupon = "Coupon",
        balance = "Balance",
        noCoupon = "No coupon",
        active = "Active",
        notRunning = "Not running",
        noDataLower = "no data",
        zcodeNoData = "ZCode: no data",
        zcodeSignIn = "ZCode: sign-in required",
        zcodeNoConnection = "ZCode: no connection",
        zcodeCheckFailed = "ZCode: check failed",
        zcodeNotConnected = "ZCode: not connected",
        zcodeCouponAvailable = "ZCode: coupon available",
        zcodeNoCoupon = "ZCode: no coupon",
        zcodeBalance = "{plan} {amount}",
    ),
    dash = DashStrings(
        oAuthOpening = "Opening Z.ai sign-in in the browser…",
        oAuthError = "OAuth error: {error}",
        oAuthOpenBrowserFailed = "Couldn't open the browser",
        oAuthFinishInBrowser = "Finish signing in in the browser and come back here…",
        oAuthDone = "Signed in. Standalone monitoring is on.",
        oAuthTimeout = "Sign-in timed out — try again",
        refreshing = "Refreshing...",
        ntfyOk = "ntfy OK",
        ntfyBadJson = "ntfy: invalid JSON",
        ntfyError = "ntfy: {error}",
        zcodeTokenExpired = "Token expired — sign in again",
        zcodeNoConnection = "No connection to zcode.z.ai",
        zcodeRateLimited = "Too many ZCode requests — try again later",
        zcodeServerError = "ZCode server error",
        zcodeApiChanged = "The ZCode API schema has changed",
        zcodeParseFailed = "Couldn't parse the ZCode response",
        zcodeUnknown = "Coupon state is unknown",
        zcodeCouponAvailable = "Coupon available! ({matched})",
        zcodeRemaining = "Remaining: {amount}",
        zcodeOkNoCoupons = "ZCode OK · no coupons yet",
        zcodePrefix = "ZCode: {message}",
        zcodeOk = "ZCode OK",
        zcodeRateLimitBackoff = "ZCode: rate limited (429) — please wait",
        signInPrompt = "Sign in to Z.ai",
        setNtfyTopic = "Set an ntfy topic",
        tokenRemoved = "Token removed",
        pinUnsupported = "This launcher can't add widgets automatically — add one manually",
        notificationsDisabled = "Notifications are off — quota reset reminders won't arrive",
        serverHttpsRequired = "The server must start with https://",
        topicCleared = "Topic cleared",
        topicSaved = "Topic saved",
        e2eKeyReset = "E2E key reset",
        scannerUnavailable = "The QR scanner is unavailable — paste the pairing link manually",
        tokenEmpty = "Token is empty",
        tokenSaved = "Token saved",
        footerZcodeOnly = "The ZCode token is stored on this phone only",
        footerFull = "ntfy receives quota and token counts, the chat ID and the coupon flag",
        footerContext = "ntfy receives quota and token counts and the chat ID",
        footerClassicPattern = "AGW / 01 · {text}",
        pairingConnectTitle = "Connect phone",
        pairingReadFailedTitle = "Couldn't read the pairing code",
        pairingApp = "App: {app}",
        pairingServer = "Server: {server}",
        pairingTopic = "Topic: {topic}",
        pairingE2eOn = "E2E: AES-256-GCM",
        pairingE2eOff = "E2E: off",
        connect = "Connect",
        pairingDone = "Pairing complete",
        pairingApplyFailed = "Couldn't apply the pairing code",
        contextChipCompact = "Time to /compact",
        contextChipAttention = "Attention",
        contextChipOk = "OK",
        contextChipWaiting = "Waiting",
        contextWaiting = "Current chat · waiting for a reply",
        contextNoPcData = "No data from the PC — set up the ntfy channel in Settings",
        contextCurrentChat = "Current chat",
        contextOfWindow = "of {window}",
        contextPctUsed = "{pct}% used",
        contextSafeUntil = "Safe up to {tokens} ({pct}%)",
        contextRiskAfter = "Risk after {tokens} ({pct}%)",
        limitsClassicGemini5h = "GEMINI 5H",
        limitsClassicGeminiWeekly = "GEMINI WK",
        limitsClassicGpt5h = "GPT 5H",
        limitsClassicGptWeekly = "GPT WK",
        limitsRemainingTitle = "Limits · remaining",
        limitsGemini5h = "Gemini · 5h",
        limitsGeminiWeekly = "Gemini · 7d",
        limitsThird5h = "Claude / GPT · 5h",
        limitsThirdWeekly = "Claude / GPT · 7d",
        limitsResetIn = "resets in {time}",
        zcodeRemainingOf = "{remaining} of {total} left",
        zcodeRemainingOnly = "{remaining} left",
        zcodeValidUntil = "Valid until {date}",
        zcodeWaitingLogin = "Waiting for sign-in…",
        zcodeSignIn = "Sign in to Z.ai",
        surfaceTitleCount = "Widget · {count}",
        surfaceNotAdded = "Not added",
        surfaceOnScreen = "On screen",
        surfaceDescriptionFull = "5-hour and weekly bars, countdown, ZCode status and PC context. Reset notifications arrive without a computer.",
        surfaceDescriptionContext = "5-hour and weekly bars, countdown and PC context. Reset notifications arrive without a computer.",
        addWidget = "Add widget to home screen",
        headerSubtitleA11y = "Antigravity, ZCode, build 001",
    ),
    onb = OnbStrings(
        question = "How will you use the app?",
        modeChangeHint = "You can change the mode later in Diagnostics.",
        contextRequiresPc = "AG Context works together with the collector on your computer.",
        zcodeOnly = "ZCode only",
        noPc = "No PC",
        zcodeDescription = "Coupons for 300–500M tokens and your balance. No computer needed: the app checks coupons on its own.",
        chooseZcode = "Choose ZCode",
        withPc = "With a computer",
        pcDescriptionFull = "Context from the open Antigravity chat, 5-hour and weekly limits, and ZCode. Requires a computer with the collector installed.",
        pcDescriptionContext = "Context from the open Antigravity chat, and 5-hour and weekly limits. Requires a computer with the collector installed.",
        choosePc = "Choose PC",
        footerFull = "AG Widgets · Antigravity + ZCode",
        footerContext = "AG Context · Antigravity only",
    ),
    settings = SettingsStrings(
        title = "Settings",
        themeTitle = "Appearance theme",
        themeA11y = "Theme {theme}",
        themeA11ySelected = "Theme {theme}, selected",
        themeAuto = "Auto",
        languageTitle = "Language",
        languageAria = "Interface language",
    ),
    channel = ChannelStrings(
        notConfigured = "Not configured",
        description = "5-hour and weekly quotas, PC context and ZCode status arrive from the computer over a private ntfy channel.",
        topicLabel = "ntfy topic",
        serverLabel = "ntfy server",
        serverHint = "https:// only. An empty value restores https://ntfy.sh",
        refreshing = "Refreshing…",
        pastePairing = "Paste pairing code",
        resetE2eKey = "Reset E2E key",
        pastePairingTitle = "Paste pairing code",
        pairingCodeLabel = "agw://pair?... code",
        resetE2eTitle = "Reset E2E key?",
        resetE2eBody = "The phone will stop decrypting messages until you scan a new QR code.",
        scanQr = "Scan a QR code from the PC",
        summaryE2e = "Channel: {topic} · E2E on",
        summaryPlain = "Channel: {topic} · no E2E",
        emptyHint = "Open Settings → Sync on the PC and scan the QR code.",
        manualShow = "Configure manually",
        manualHide = "Hide manual settings",
    ),
    access = AccessStrings(
        description = "Direct polling of zcode.z.ai every 15 minutes: coupons for 300–500M tokens and your balance arrive even when the computer is off.",
        tokenLabel = "Or paste a JWT token manually",
        waiting = "Waiting…",
        saveToken = "Save token",
        clearToken = "Clear token",
        deleteTitle = "Delete the ZCode token?",
        deleteBody = "Standalone coupon and balance checks will stop until the next sign-in.",
        balanceClassic = "REMAINING {remaining}",
        balanceClassicTotal = "REMAINING {remaining} / {total}",
        untilClassic = "UNTIL {date}",
        until = "until {date}",
    ),
    diag = DiagStrings(
        title = "Diagnostics",
        hide = "Hide",
        showJson = "Show JSON",
        noData = "No data yet — tap Refresh",
        lastError = "Last error: {error}",
        changeModeClassic = "CHANGE MODE",
        changeMode = "Change mode",
    ),
    widgetConfig = WidgetConfigStrings(
        title = "Widget settings",
        saving = "Saving…",
        save = "Save widget",
        presets = "Presets",
        blocks = "Blocks",
        moveUp = "Move \"{title}\" up",
        moveDown = "Move \"{title}\" down",
        blockHeader = "Header",
        optionCompactChip = "/compact chip",
        optionResetTimer = "Reset timer",
        optionBalanceBar = "Balance bar",
        style = "Style",
        accent = "Accent",
        accentDefault = "Default accent",
        accentHex = "Accent {hex}",
        opacity = "Opacity",
        cornerRadius = "Corner radius",
        fontScale = "Text scale",
        density = "Density",
        densityCompact = "Compact",
        densityComfortable = "Normal",
        densitySpacious = "Spacious",
        resetAction = "Reset settings",
        resetTitle = "Reset settings?",
        resetBody = "Theme, density and enabled blocks will return to the 4×2 dashboard. The widget stays on the screen.",
        exportImport = "Export and import",
        exportJson = "Export JSON",
        jsonCopied = "JSON copied",
        importJson = "Import JSON",
        importPlaceholder = "{\"theme\": \"graphite\", \"opacity\": 0.95, …}",
        importHint = "For example: {\"theme\": \"paper\", \"opacity\": 0.95, \"density\": \"compact\"}",
        configLabel = "Configuration",
        importInvalid = "Invalid JSON — the configuration was not changed",
        presetDashboard = "Dashboard 4×2",
        presetClassic = "Classic 4×2",
        presetContext = "Context 4×1",
        presetDetailed = "Detailed 4×3",
        presetZcodeOnly = "ZCode only 2×1",
        presetCustom = "Custom",
    ),
    widgetPreview = WidgetPreviewStrings(
        hiddenOne = "+{count} block doesn't fit this grid",
        hiddenMany = "+{count} blocks don't fit this grid",
        gemini5h = "Gemini 5h",
        geminiWeekly = "Gemini 7d",
        third5h = "Claude / GPT 5h",
        thirdWeekly = "Claude / GPT 7d",
        demoCountdown1 = "2h 40m",
        demoCountdown2 = "4d 2h",
        demoCountdown3 = "1h 15m",
        demoCountdown4 = "3d 8h",
    ),
    widgetAtoms = WidgetAtomsStrings(
        contextUnavailable = "Context unavailable",
        staleSuffix = " · stale",
        classicGemini5h = "GEMINI 5H",
        classicGeminiWeekly = "GEMINI WK",
        classicThird5h = "CLAUDE/GPT 5H",
        classicThirdWeekly = "CLAUDE/GPT WK",
        gemini5h = "Gemini 5h",
        geminiWeekly = "Gemini 7d",
        third5h = "Claude / GPT 5h",
        thirdWeekly = "Claude / GPT 7d",
        classicChat = "CHAT",
        classicPc = "PC",
        noDataPrompt = "No data — open AG Widgets",
        collectError = "Collection error: {error}",
    ),
    notif = NotifStrings(
        channelName = "Limits",
        channelDescriptionFull = "Notifications about Antigravity quota updates and ZCode coupons",
        channelDescriptionContext = "Notifications about Antigravity quota updates",
        availablePct = "Available: {pct}%",
        limitReset = "Quota reset",
        quotaUpdated = "Quota {bucket} updated",
        couponDetail = "Open ZCode to claim 300–500M tokens",
        couponTitle = "A ZCode coupon is available!",
        scheduledResetBody = "The scheduled quota reset time has arrived",
        bucketGemini = "Gemini ",
        bucketThird = "Claude/GPT ",
        window5h = "(5h)",
        windowWeekly = "(weekly)",
    ),
    work = WorkStrings(
        badJson = "Invalid JSON in the ntfy message",
        ntfyNetworkError = "ntfy network error",
        zcodeAuthExpired = "ZCode: token expired",
        zcodeRateLimited = "ZCode: rate limited (429)",
        zcodeServerError = "ZCode: server error",
        zcodeApiChanged = "ZCode: API schema changed",
        zcodeParseFailed = "ZCode: couldn't parse the response",
        zcodeNoConnection = "ZCode: no connection",
        zcodeNetworkError = "ZCode network error",
    ),
    ntfy = NtfyStrings(
        noMessages = "No messages in this topic yet",
        noStatus = "No status messages in the ntfy topic",
        encryptedNoKey = "The message is encrypted — scan the QR code from your PC",
        wrongKey = "Wrong E2E key — scan a new QR code from your PC",
        badJson = "Invalid JSON in the ntfy message",
        expectedE2e = "Expected an encrypted message (E2E)",
    ),
    e2e = E2eStrings(
        unsupportedVersion = "Unsupported E2E message version",
        unsupportedAlg = "Unsupported E2E message algorithm",
        invalidEnvelope = "Invalid E2E envelope",
        invalidIv = "Invalid E2E message IV length",
        tooShort = "E2E message is too short",
        invalidKey = "Invalid E2E key",
        decryptFailed = "Couldn't decrypt the E2E message",
        emptyPairingLink = "Empty pairing link",
        invalidPairingLink = "Invalid pairing link",
        schemeRequired = "The link must use the agw scheme",
        pairHostRequired = "The link must point to agw://pair",
        noPath = "The link must not contain a path",
        invalidParam = "Invalid link parameter",
        duplicateParam = "Duplicate parameter {key}",
        unsupportedPairingVersion = "Unsupported pairing link version",
        invalidTopic = "Invalid ntfy topic",
        invalidServer = "Invalid ntfy server",
        emptyKey = "Empty E2E key",
    ),
    zcodeClient = ZCodeClientStrings(
        initFailed = "OAuth init failed (HTTP {code})",
        initEmpty = "Empty init response",
        initInvalidFields = "Invalid OAuth init fields",
        sessionExpired = "Sign-in session expired (HTTP {code})",
        loginRejected = "Sign-in rejected",
        noTokenFromServer = "The server didn't return a token",
        tokenMissing = "ZCode token is not set",
    ),
    language = LanguageStrings(
        auto = "Auto",
        ru = "Русский",
        en = "English",
    ),
)

/** "ru" -> [RuStrings]; everything else -> [EnStrings]. */
fun stringsFor(resolvedLang: String): AgStrings =
    if (resolvedLang == AppLanguage.RU) RuStrings else EnStrings

/**
 * Ambient strings; defaults to Russian so JVM tests need no provider.
 *
 * The providable type lets activities call `CompositionLocalProvider(LocalStrings provides …)`
 * directly instead of casting the read-only `CompositionLocal` every time.
 */
val LocalStrings: ProvidableCompositionLocal<AgStrings> = staticCompositionLocalOf { RuStrings }
