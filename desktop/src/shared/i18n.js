'use strict';

/**
 * Desktop UI dictionary and locale helpers (RU / EN).
 *
 * UMD, zero dependencies: the same file works as a plain `<script>` in the
 * Electron renderer (`window.AGW.i18n`), through `require()` in the Electron
 * main process, and under `node --test`.
 *
 * `lang` defaults to `'ru'` everywhere: existing renderer/unit tests call the
 * formatters without a language and pin the Russian strings, so the default is
 * part of the contract and must not change.
 */
(function (root) {
  'use strict';

  /** Selectable language modes: `auto` resolves through the system locale. */
  const LANGUAGES = ['auto', 'ru', 'en'];

  /** Contract default for every `lang` parameter. */
  const DEFAULT_LANG = 'ru';

  /**
   * Flat, dot-separated keys. `ru` and `en` must carry an identical key set and
   * an identical set of `{placeholder}` names for each key.
   */
  const STRINGS = {
    ru: {
      'app.brand': 'AG Widgets',
      'app.brandSub': 'ANTIGRAVITY · ZCODE',
      'app.title': 'AG Widgets',

      'titlebar.refresh': 'Обновить данные',
      'titlebar.refreshTooltip': 'Обновить данные (F5)',
      'titlebar.refreshing': 'Обновляю данные…',
      'titlebar.refreshFailed': 'Не удалось обновить: {message}',
      'titlebar.settings': 'Настройки',
      'titlebar.theme': 'Сменить тему',
      'titlebar.menu': 'Меню',
      'titlebar.menuTooltip': 'Меню: свернуть, поверх окон, выход',
      'titlebar.minimize': 'Свернуть в панель задач',
      'titlebar.quit': 'Закрыть AG Widgets',
      'titlebar.noDataTooltip': 'Нет данных о последнем ответе',
      'titlebar.updatedTooltip': 'Обновлено: {when} · сейчас {now}',

      'dashboard.context.serial': '01 / CONTEXT',
      'dashboard.context.heading': 'Контекст',
      'dashboard.context.tokensUnit': 'токены',
      'dashboard.context.gaugeAria': 'Заполнение контекста',
      'dashboard.context.waitingDialog': 'Ожидание диалога',
      'dashboard.context.projectPrefix': 'Проект: ',
      'dashboard.context.snippetIdle': 'Отправь запрос в Antigravity',
      'dashboard.context.compactAlert': 'Пора /compact',
      'dashboard.context.rising': 'Контекст растёт',
      'dashboard.context.noProject': 'Без проекта',
      'dashboard.context.windowUnset': 'окно не задано',
      'dashboard.context.pctOfWindow': '{pct}% из {window}',
      'dashboard.context.pctBusy': '{pct}% занято',
      'dashboard.context.pctOnly': '{pct}%',
      'dashboard.context.modelWithWindow': '{model} · {window}',
      'dashboard.context.workspaceWithAge': '{workspace} · {age}',
      'dashboard.context.zoneTooltip':
        'Порог {yellow}% и {red}% окна. После ~{yellow}% деградация на сложных задачах заметна, после ~{red}% надёжная часть окна обычно заканчивается (RULER, NoLiMa, Chroma Context Rot).',
      'dashboard.context.zoneUnknown': 'Окно модели неизвестно: задайте model_windows в config.json',

      'dashboard.zones.safeUntil': 'надёжно до {tokens}',
      'dashboard.zones.safeUntilUnknown': 'надёжно до —',
      'dashboard.zones.riskAfter': 'риск после {tokens}',
      'dashboard.zones.riskAfterUnknown': 'риск после —',
      'dashboard.zones.safe': '0–40%',
      'dashboard.zones.warn': '40–65%',
      'dashboard.zones.risk': '65%+',
      'dashboard.zones.legendOk': '0–{yellow}%',
      'dashboard.zones.legendWarn': '{yellow}–{red}%',
      'dashboard.zones.legendRisk': '{red}%+',
      'dashboard.zones.legendOkStatic': '<40%',
      'dashboard.zones.legendWarnStatic': '40–65%',
      'dashboard.zones.legendRiskStatic': '>65%',

      'dashboard.quotas.serial': '02 / LIMITS',
      'dashboard.quotas.heading': 'Лимиты · остаток',
      'dashboard.quotas.empty': 'Квоты появятся после первого /usage',
      'dashboard.quotas.error': 'Не удалось обновить лимиты',
      'dashboard.quotas.retry': 'Повторить',
      'dashboard.quotas.errorTooltip': 'Квоты не обновились: {error}',
      'dashboard.quotas.bucketGrouped': '{group} · {kind}',
      'dashboard.quotas.groupGemini': 'Gemini',
      'dashboard.quotas.groupThirdParty': 'Claude / GPT',
      'dashboard.quotas.kind5h': '5 ч',
      'dashboard.quotas.kindWeekly': '7 дн',
      'dashboard.quotas.kindFallback': 'квота',
      'dashboard.quotas.hiddenTitle': 'Скрыты: {labels}',
      'dashboard.quotas.percent': '{pct}%',

      'dashboard.zcode.serial': '03 / ACCESS',
      'dashboard.zcode.heading': 'ZCode',
      'dashboard.zcode.defaultTitle': 'ZCode',
      'dashboard.zcode.materialDisabled': 'Отключён',
      'dashboard.zcode.waitingCheck': 'Ожидание проверки',
      'dashboard.zcode.noData': 'Нет данных',
      'dashboard.zcode.retry': 'Повторить',
      'dashboard.zcode.retryTooltip': 'Проверить ZCode ещё раз',
      'dashboard.zcode.retryTooltipReason': 'Проверить ZCode ещё раз: {reason}',
      'dashboard.zcode.login': 'Войти в Z.ai',
      'dashboard.zcode.cancelLogin': 'Отменить вход',
      'dashboard.zcode.retryLogin': 'Повторить вход',
      'dashboard.zcode.loginUnavailable': 'Вход недоступен',
      'dashboard.zcode.loginFailed': 'вход не выполнен',
      'dashboard.zcode.loginSuccessRefreshing': 'Вход выполнен · обновляю баланс…',
      'dashboard.zcode.tokenSaved': 'Токен сохранён · нажмите «Обновить»',
      'dashboard.zcode.signedIn': 'Вход выполнен',
      'dashboard.zcode.statusConnected': 'Подключён',
      'dashboard.zcode.statusDisabled': 'Отключён',
      'dashboard.zcode.statusExpired': 'Токен истёк',
      'dashboard.zcode.statusOffline': 'Нет сети',
      'dashboard.zcode.statusUnverified': 'Не проверен',
      'dashboard.zcode.materialSignIn': 'Вход',
      'dashboard.zcode.pendingSubtitle': 'Открыл браузер · завершите вход в Z.ai…',
      'dashboard.zcode.pendingChip': 'Ожидание',
      'dashboard.zcode.errorSubtitle': 'Не удалось войти: {message}',
      'dashboard.zcode.errorChip': 'Ошибка',
      'dashboard.zcode.disabledSubtitle': 'Не подключён · войдите в Z.ai',
      'dashboard.zcode.disabledChip': 'Не подключён',
      'dashboard.zcode.expiredSubtitle': 'Токен истёк · войдите снова',
      'dashboard.zcode.loginRequiredChip': 'Нужен вход',
      'dashboard.zcode.couponVisibleText': 'Ваучер доступен',
      'dashboard.zcode.couponAbsentText': 'Нет активных ваучеров',
      'dashboard.zcode.couponUnknownText': 'Не удалось проверить ваучер: {reason}',
      'dashboard.zcode.couponUnknownNoData': 'Не удалось проверить ваучер: неизвестная ошибка',
      'dashboard.zcode.planTitle': 'ZCode · {plan}',
      'dashboard.zcode.materialWithPlan': '{status} · {plan}',
      'dashboard.zcode.balanceWithTotal': 'Остаток: {remaining} / {total}',
      'dashboard.zcode.balanceOnly': 'Остаток: {remaining}',
      'dashboard.zcode.endsAt': 'до {date}',
      'dashboard.zcode.couponCta': 'Ваучер 300–500M · заберите в ZCode',
      'dashboard.zcode.unverifiedWithBalance': 'Ваучер не проверен · {balance}',
      'dashboard.zcode.unverified': 'Ваучер не проверен',
      'dashboard.zcode.chipAbsent': 'Нет ваучера',
      'dashboard.zcode.chipCheckFailed': 'Проверка не удалась',
      'dashboard.zcode.reason.rateLimited': 'лимит запросов 429',
      'dashboard.zcode.reason.serverError': 'ошибка сервера',
      'dashboard.zcode.reason.apiChanged': 'формат API изменился',
      'dashboard.zcode.reason.network': 'нет связи',
      'dashboard.zcode.reason.authExpired': 'токен истёк',
      'dashboard.zcode.reason.parseFailed': 'не удалось разобрать ответ',
      'dashboard.zcode.reason.unknown': 'неизвестная ошибка',

      'dashboard.strip.aria': 'Краткая сводка',
      'dashboard.strip.refresh': 'Обновить данные',
      'dashboard.strip.settings': 'Настройки',
      'dashboard.strip.quota5h': '5 ч {pct}%',

      'dashboard.footer.brand': 'AGW',
      'dashboard.footer.brandSlash': '/01',
      'dashboard.footer.contextHealth': 'CONTEXT HEALTH',
      'dashboard.footer.legendOk': '<40%',
      'dashboard.footer.legendWarn': '40–65%',
      'dashboard.footer.legendRisk': '>65%',

      'dashboard.recent.labelWithModel': '{title} · {model}',

      'dashboard.empty.noSession': 'Нет сессии',
      'dashboard.empty.waitingAnswer': 'Ожидание ответа',
      'dashboard.empty.noActiveSession': 'Нет активной сессии',
      'dashboard.empty.selectedChat': 'Выбранный чат',
      'dashboard.empty.replyHint': 'Ответьте модели, чтобы увидеть контекст',
      'dashboard.empty.sendHint': 'Отправьте запрос в Antigravity — данные появятся здесь',
      'dashboard.empty.newChat': 'Новый чат',

      'status.unknownModel': 'Неизвестная модель',
      'status.level.green': 'Норма',
      'status.level.yellow': 'Внимание',
      'status.level.red': 'Риск',

      'time.justNow': 'только что',
      'time.minutesAgo': '{n}м назад',
      'time.hoursAgo': '{n}ч назад',
      'time.daysAgo': '{n}д назад',
      'time.resetNow': 'сброс сейчас',
      'time.countdownDays': '{d}д {h}ч',
      'time.countdownHours': '{h}ч {m}м',
      'time.countdownMinutes': '{m}м',

      'theme.auto': 'Авто',
      'theme.autoTitle': 'Системная тема',
      'theme.graphite': 'Graphite',
      'theme.paper': 'Paper',
      'theme.oled': 'OLED',
      'theme.glass': 'Glass',
      'theme.classic': 'Classic',

      'density.compact': 'Компактно',
      'density.comfortable': 'Обычно',
      'density.spacious': 'Просторно',

      'mode.full': 'Полный',
      'mode.compact': 'Компактный',
      'mode.minibar': 'Мини-бар',
      'mode.pill': 'Пилюля',
      'mode.fullSize': '400×640',
      'mode.compactSize': '330×270',
      'mode.minibarSize': '480×48',
      'mode.pillSize': '196×42',

      'preset.full': 'Полный',
      'preset.compact': 'Компактный',
      'preset.minibar': 'Мини-бар',
      'preset.pill': 'Пилюля',
      'preset.paper': 'Светлая бумага',
      'preset.classic': 'Classic',
      'preset.fullHint': 'Вертикальный дашборд с тремя блоками',
      'preset.compactHint': 'Плотная карточка без второстепенных деталей',
      'preset.minibarHint': 'Горизонтальная полоска для края экрана',
      'preset.pillHint': 'Только контекст: ● 442K · 84%',
      'preset.paperHint': 'Тёплая светлая тема',
      'preset.classicHint': 'Оригинальный вид автора, 1:1',

      'block.context': 'Контекст',
      'block.quotas': 'Лимиты',
      'block.zcode': 'ZCode',
      'block.contextRecent': 'Недавние чаты',
      'block.contextRecentCount': 'Сколько показывать',
      'block.contextThresholds': 'Пороги контекста',
      'block.quotasGemini': 'Gemini',
      'block.quotasThirdParty': 'Сторонние модели',
      'block.quotasCountdown': 'Таймер обновления',
      'block.zcodeBalanceBar': 'Полоса баланса',
      'block.recentCountAria': 'Количество недавних чатов',
      'block.moveUp': 'Поднять блок «{name}»',
      'block.moveDown': 'Опустить блок «{name}»',
      'block.showBlock': 'Показывать блок «{name}»',
      'block.toggleRecent': 'Показывать недавние чаты',
      'block.toggleThresholds': 'Показывать пороги контекста',
      'block.toggleGemini': 'Показывать квоты Gemini',
      'block.toggleThirdParty': 'Показывать сторонние модели',
      'block.toggleCountdown': 'Показывать таймер обновления',
      'block.toggleBalance': 'Показывать полосу баланса',

      'classic.variantLabel': 'Вариант Classic',
      'classic.dark': 'Тёмная',
      'classic.light': 'Светлая',

      'settings.title': 'Настройки',
      'settings.windowTitle': 'Настройки — AG Widgets',
      'settings.subtitle': 'Внешний вид и поведение виджета',
      'settings.tabsAria': 'Разделы настроек',
      'settings.tab.appearance': 'Оформление',
      'settings.tab.builder': 'Конструктор',
      'settings.tab.sync': 'Синхронизация',
      'settings.bridgeNotice': 'Настройки доступны только в приложении.',

      'settings.appearance.presetsTitle': 'Готовые пресеты',
      'settings.appearance.themeTitle': 'Тема',
      'settings.appearance.themeGroupAria': 'Тема оформления',
      'settings.appearance.accentTitle': 'Акцентный цвет',
      'settings.appearance.accentAuto': 'авто',
      'settings.appearance.accentGroupAria': 'Быстрый выбор акцента',
      'settings.appearance.accentColorAria': 'Выбрать акцентный цвет',
      'settings.appearance.accentHexAria': 'Акцентный цвет в формате HEX',
      'settings.appearance.accentReset': 'По умолчанию',
      'settings.appearance.fontTitle': 'Масштаб шрифта',
      'settings.appearance.fontResetTitle': 'Сбросить к 100%',
      'settings.appearance.fontResetAria': 'Сбросить масштаб к 100%',
      'settings.appearance.opacityTitle': 'Прозрачность окна',
      'settings.appearance.radiusTitle': 'Скругление углов',
      'settings.appearance.densityTitle': 'Плотность',
      'settings.appearance.behaviorTitle': 'Поведение окна',
      'settings.appearance.alwaysOnTop': 'Поверх всех окон',
      'settings.appearance.openAtLogin': 'Запускать при входе в Windows',

      'settings.accent.blue': 'Синий #60a5fa',
      'settings.accent.green': 'Зелёный #34d399',
      'settings.accent.purple': 'Фиолетовый #a78bfa',
      'settings.accent.orange': 'Оранжевый #f59e0b',
      'settings.accent.pink': 'Розовый #f43f5e',
      'settings.accent.sky': 'Голубой #38bdf8',

      'settings.notif.title': 'Уведомления Windows',
      'settings.notif.enabled': 'Показывать уведомления',
      'settings.notif.contextRed': 'Контекст в красной зоне (/compact)',
      'settings.notif.zcodeVoucher': 'Доступен ваучер ZCode',
      'settings.notif.quotaReset': 'Сброс 5-часовой квоты',
      'settings.notif.test': 'Проверить уведомление',
      'settings.notif.status.testing': 'Отправляем…',
      'settings.notif.status.sent': 'Уведомление отправлено.',
      'settings.notif.status.unsupported': 'Windows не поддерживает уведомления.',
      'settings.notif.status.failed': 'Не удалось отправить уведомление.',
      'settings.notif.status.bridgeOnly': 'Проверка доступна только в приложении.',

      'settings.language.title': 'Язык',
      'settings.language.aria': 'Язык интерфейса',
      'settings.language.auto': 'Авто',
      'settings.language.ru': 'Русский',
      'settings.language.en': 'English',

      'settings.builder.modeTitle': 'Форм-фактор',

      'settings.blocks.title': 'Блоки',
      'settings.blocks.hint': 'Порядок сверху вниз',

      'settings.json.title': 'Экспорт / импорт пресета (JSON)',
      'settings.json.export': 'Скопировать JSON',
      'settings.json.import': 'Применить',
      'settings.json.aria': 'JSON пресета',
      'settings.json.copied': 'JSON скопирован в буфер обмена',
      'settings.json.copyFallback': 'Скопируйте JSON из поля ниже',
      'settings.json.missing': 'Не удалось получить JSON',
      'settings.json.paste': 'Вставьте JSON пресета',
      'settings.json.applied': 'Пресет применён',
      'settings.json.noKnownFields': 'В JSON нет известных полей',
      'settings.json.invalid': 'Некорректный JSON',
      'settings.json.failed': 'Не удалось применить пресет',

      'settings.sync.channelTitle': 'Канал',
      'settings.sync.ntfyToggle': 'Синхронизация с телефоном (ntfy)',
      'settings.sync.serverLabel': 'Сервер ntfy',
      'settings.sync.save': 'Сохранить',
      'settings.sync.serverHint':
        'Допускается только адрес, начинающийся с https://, без символов @, ? и #.',
      'settings.sync.serverError':
        'Адрес должен начинаться с https:// и не содержать @, ? или #.',
      'settings.sync.serverSaved': 'Сервер ntfy сохранён.',
      'settings.sync.serverSaveFailed': 'Не удалось сохранить сервер ntfy.',
      'settings.sync.ntfyFailed': 'Не удалось изменить синхронизацию.',
      'settings.sync.pairingUnavailable': 'Синхронизация доступна только в приложении.',
      'settings.sync.pairingLoadFailed': 'Не удалось получить состояние сопряжения.',

      'settings.e2e.title': 'Шифрование',
      'settings.e2e.toggle': 'E2E-шифрование (AES-256-GCM)',
      'settings.e2e.rotate': 'Сменить ключ',
      'settings.e2e.rotateConfirm': 'Подтвердить смену ключа',
      'settings.e2e.hintOn':
        'Ключ хранится на этом устройстве и передаётся телефону через QR-код. При смене ключа телефон нужно переподключить.',
      'settings.e2e.hintShort':
        'Ключ хранится на этом устройстве и передаётся телефону только через QR-код.',
      'settings.e2e.hintOff':
        'Без E2E сообщения идут через сервер ntfy в открытом виде. Включите шифрование перед сопряжением.',
      'settings.e2e.rotated':
        'Ключ сменён. Переподключите телефон: откройте QR-код заново и отсканируйте его.',
      'settings.e2e.rotateFailed': 'Не удалось сменить ключ шифрования.',
      'settings.e2e.toggleFailed': 'Не удалось изменить E2E-шифрование.',

      'settings.qr.title': 'Подключить Android (QR-код)',
      'settings.qr.hint': 'Откройте AG Widgets на телефоне и отсканируйте код.',
      'settings.qr.reveal': 'Показать QR-код',
      'settings.qr.hide': 'Скрыть QR-код',
      'settings.qr.badgeOn': 'E2E вкл',
      'settings.qr.badgeOff': 'Без E2E',
      'settings.qr.copyUri': 'Скопировать ссылку сопряжения',
      'settings.qr.copyTopic': 'Скопировать тему',
      'settings.qr.uriCopied': 'Ссылка сопряжения скопирована.',
      'settings.qr.topicCopied': 'Тема скопирована.',
      'settings.qr.noPairingData': 'Данные сопряжения ещё не загружены.',
      'settings.qr.clipboardUnavailable': 'Буфер обмена недоступен в этом окне.',
      'settings.qr.copyFailed': 'Не удалось скопировать в буфер обмена.',
      'settings.qr.generatorUnavailable': 'QR-генератор недоступен.',
      'settings.qr.tooLong':
        'Ссылка слишком длинная для QR-кода — скопируйте её кнопкой ниже.',
      'settings.qr.buildFailed': 'Не удалось построить QR-код.',
      'settings.qr.imageAria': 'QR-код для сопряжения',
      'settings.qr.errorTooLong':
        'Текст слишком длинный для QR-кода: максимум 213 байт (версия 10, уровень M).',
      'settings.qr.errorDoesNotFit': 'Текст не помещается в выбранную версию QR-кода.',
      'settings.qr.errorEmpty': 'Пустой текст: QR-код не строится.',

      'settings.footer.note': 'Изменения применяются сразу',
      'settings.footer.reset': 'Сбросить всё',
      'settings.footer.resetConfirm': 'Точно сбросить?',

      'settings.errors.applyFailed': 'Не удалось применить настройку.',
      'settings.errors.applyRejected': 'Настройка не применилась.',
      'settings.errors.presetFailed': 'Не удалось применить пресет.',
      'settings.errors.colorFormat': 'Цвет задаётся в формате #rrggbb.',
      'settings.errors.resetFailed': 'Не удалось сбросить внешний вид.',
      'settings.errors.loadFailed': 'Не удалось загрузить настройки.',
      'settings.errors.updateFailed': 'Не удалось обновить настройки.',
      'settings.errors.subscribeFailed': 'Не удалось подписаться на изменения.',
      'settings.errors.saveFailed': 'Не удалось сохранить настройки',
      'settings.errors.expectedString': 'Ожидается JSON-строка',
      'settings.errors.emptyJson': 'Пустой JSON',
      'settings.errors.tooLarge': 'JSON больше 16 КБ',
      'settings.errors.invalidJson': 'Некорректный JSON',
      'settings.errors.rootNotObject': 'Корень JSON должен быть объектом',

      'tray.tooltip': 'AG Widgets',
      'tray.show': 'Показать виджет',
      'tray.hide': 'Свернуть в трей',
      'tray.alwaysOnTop': 'Поверх всех окон',
      'tray.openAtLogin': 'Запускать при входе',
      'tray.notifications': 'Уведомления',
      'tray.settings': 'Настройки…',
      'tray.windowMode': 'Режим окна',
      'tray.openConfigDir': 'Открыть папку конфига',
      'tray.openZcode': 'Открыть Z.ai',
      'tray.logout': 'Выйти из Z.ai',
      'tray.quit': 'Выход',
      'tray.modeFull': 'Полный',
      'tray.modeCompact': 'Компактный',
      'tray.modeMinibar': 'Мини-бар',
      'tray.modePill': 'Пилюля',

      'notif.contextRed.title': 'Пора сделать /compact',
      'notif.contextRed.body': '{model}: {tokens} ({pct}%) — контекст в красной зоне',
      'notif.couponAvailable.title': 'В ZCode доступен ваучер!',
      'notif.couponFallback.body': 'Откройте ZCode, чтобы забрать 300–500M токенов',
      'notif.quotaReset.title': 'Квота {bucket} обновилась',
      'notif.quotaAvailable.body': 'Доступно: {pct}%',
      'notif.quotaResetLimit.body': 'Лимит сброшен',
      'notif.test.title': 'AG Widgets',
      'notif.test.body': 'Тестовое уведомление: всё работает.',
      'notif.bucket.gemini5h': 'Gemini (5 ч)',
      'notif.bucket.thirdParty5h': 'Claude/GPT (5 ч)',
    },

    en: {
      'app.brand': 'AG Widgets',
      'app.brandSub': 'ANTIGRAVITY · ZCODE',
      'app.title': 'AG Widgets',

      'titlebar.refresh': 'Refresh data',
      'titlebar.refreshTooltip': 'Refresh data (F5)',
      'titlebar.refreshing': 'Refreshing…',
      'titlebar.refreshFailed': 'Refresh failed: {message}',
      'titlebar.settings': 'Settings',
      'titlebar.theme': 'Switch theme',
      'titlebar.menu': 'Menu',
      'titlebar.menuTooltip': 'Menu: minimize, always on top, quit',
      'titlebar.minimize': 'Minimize to taskbar',
      'titlebar.quit': 'Close AG Widgets',
      'titlebar.noDataTooltip': 'No data about the last reply',
      'titlebar.updatedTooltip': 'Updated: {when} · now {now}',

      'dashboard.context.serial': '01 / CONTEXT',
      'dashboard.context.heading': 'Context',
      'dashboard.context.tokensUnit': 'tokens',
      'dashboard.context.gaugeAria': 'Context usage',
      'dashboard.context.waitingDialog': 'Waiting for a dialog',
      'dashboard.context.projectPrefix': 'Project: ',
      'dashboard.context.snippetIdle': 'Send a request to Antigravity',
      'dashboard.context.compactAlert': 'Time to /compact',
      'dashboard.context.rising': 'Context is growing',
      'dashboard.context.noProject': 'No project',
      'dashboard.context.windowUnset': 'window not set',
      'dashboard.context.pctOfWindow': '{pct}% of {window}',
      'dashboard.context.pctBusy': '{pct}% used',
      'dashboard.context.pctOnly': '{pct}%',
      'dashboard.context.modelWithWindow': '{model} · {window}',
      'dashboard.context.workspaceWithAge': '{workspace} · {age}',
      'dashboard.context.zoneTooltip':
        'Thresholds {yellow}% and {red}% of the window. Beyond ~{yellow}% degradation on complex tasks becomes noticeable; beyond ~{red}% the reliable part of the window is usually exhausted (RULER, NoLiMa, Chroma Context Rot).',
      'dashboard.context.zoneUnknown': 'Model window is unknown: set model_windows in config.json',

      'dashboard.zones.safeUntil': 'safe until {tokens}',
      'dashboard.zones.safeUntilUnknown': 'safe until —',
      'dashboard.zones.riskAfter': 'risk after {tokens}',
      'dashboard.zones.riskAfterUnknown': 'risk after —',
      'dashboard.zones.safe': '0–40%',
      'dashboard.zones.warn': '40–65%',
      'dashboard.zones.risk': '65%+',
      'dashboard.zones.legendOk': '0–{yellow}%',
      'dashboard.zones.legendWarn': '{yellow}–{red}%',
      'dashboard.zones.legendRisk': '{red}%+',
      'dashboard.zones.legendOkStatic': '<40%',
      'dashboard.zones.legendWarnStatic': '40–65%',
      'dashboard.zones.legendRiskStatic': '>65%',

      'dashboard.quotas.serial': '02 / LIMITS',
      'dashboard.quotas.heading': 'Limits · remaining',
      'dashboard.quotas.empty': 'Quotas will appear after the first /usage',
      'dashboard.quotas.error': 'Could not refresh limits',
      'dashboard.quotas.retry': 'Retry',
      'dashboard.quotas.errorTooltip': 'Quotas did not refresh: {error}',
      'dashboard.quotas.bucketGrouped': '{group} · {kind}',
      'dashboard.quotas.groupGemini': 'Gemini',
      'dashboard.quotas.groupThirdParty': 'Claude / GPT',
      'dashboard.quotas.kind5h': '5h',
      'dashboard.quotas.kindWeekly': '7d',
      'dashboard.quotas.kindFallback': 'quota',
      'dashboard.quotas.hiddenTitle': 'Hidden: {labels}',
      'dashboard.quotas.percent': '{pct}%',

      'dashboard.zcode.serial': '03 / ACCESS',
      'dashboard.zcode.heading': 'ZCode',
      'dashboard.zcode.defaultTitle': 'ZCode',
      'dashboard.zcode.materialDisabled': 'Disabled',
      'dashboard.zcode.waitingCheck': 'Awaiting check',
      'dashboard.zcode.noData': 'No data',
      'dashboard.zcode.retry': 'Retry',
      'dashboard.zcode.retryTooltip': 'Check ZCode again',
      'dashboard.zcode.retryTooltipReason': 'Check ZCode again: {reason}',
      'dashboard.zcode.login': 'Sign in to Z.ai',
      'dashboard.zcode.cancelLogin': 'Cancel sign-in',
      'dashboard.zcode.retryLogin': 'Retry sign-in',
      'dashboard.zcode.loginUnavailable': 'Sign-in unavailable',
      'dashboard.zcode.loginFailed': 'sign-in failed',
      'dashboard.zcode.loginSuccessRefreshing': 'Signed in · refreshing balance…',
      'dashboard.zcode.tokenSaved': 'Token saved · press "Refresh"',
      'dashboard.zcode.signedIn': 'Signed in',
      'dashboard.zcode.statusConnected': 'Connected',
      'dashboard.zcode.statusDisabled': 'Disabled',
      'dashboard.zcode.statusExpired': 'Token expired',
      'dashboard.zcode.statusOffline': 'Offline',
      'dashboard.zcode.statusUnverified': 'Unverified',
      'dashboard.zcode.materialSignIn': 'Sign-in',
      'dashboard.zcode.pendingSubtitle': 'Browser opened · finish signing in to Z.ai…',
      'dashboard.zcode.pendingChip': 'Waiting',
      'dashboard.zcode.errorSubtitle': 'Sign-in failed: {message}',
      'dashboard.zcode.errorChip': 'Error',
      'dashboard.zcode.disabledSubtitle': 'Not connected · sign in to Z.ai',
      'dashboard.zcode.disabledChip': 'Not connected',
      'dashboard.zcode.expiredSubtitle': 'Token expired · sign in again',
      'dashboard.zcode.loginRequiredChip': 'Sign-in required',
      'dashboard.zcode.couponVisibleText': 'Voucher available',
      'dashboard.zcode.couponAbsentText': 'No active vouchers',
      'dashboard.zcode.couponUnknownText': 'Could not check the voucher: {reason}',
      'dashboard.zcode.couponUnknownNoData': 'Could not check the voucher: unknown error',
      'dashboard.zcode.planTitle': 'ZCode · {plan}',
      'dashboard.zcode.materialWithPlan': '{status} · {plan}',
      'dashboard.zcode.balanceWithTotal': 'Balance: {remaining} / {total}',
      'dashboard.zcode.balanceOnly': 'Balance: {remaining}',
      'dashboard.zcode.endsAt': 'until {date}',
      'dashboard.zcode.couponCta': 'Voucher 300–500M · claim it in ZCode',
      'dashboard.zcode.unverifiedWithBalance': 'Voucher unverified · {balance}',
      'dashboard.zcode.unverified': 'Voucher unverified',
      'dashboard.zcode.chipAbsent': 'No voucher',
      'dashboard.zcode.chipCheckFailed': 'Check failed',
      'dashboard.zcode.reason.rateLimited': 'rate limit 429',
      'dashboard.zcode.reason.serverError': 'server error',
      'dashboard.zcode.reason.apiChanged': 'API format changed',
      'dashboard.zcode.reason.network': 'no connection',
      'dashboard.zcode.reason.authExpired': 'token expired',
      'dashboard.zcode.reason.parseFailed': 'could not parse the response',
      'dashboard.zcode.reason.unknown': 'unknown error',

      'dashboard.strip.aria': 'Summary',
      'dashboard.strip.refresh': 'Refresh data',
      'dashboard.strip.settings': 'Settings',
      'dashboard.strip.quota5h': '5h {pct}%',

      'dashboard.footer.brand': 'AGW',
      'dashboard.footer.brandSlash': '/01',
      'dashboard.footer.contextHealth': 'CONTEXT HEALTH',
      'dashboard.footer.legendOk': '<40%',
      'dashboard.footer.legendWarn': '40–65%',
      'dashboard.footer.legendRisk': '>65%',

      'dashboard.recent.labelWithModel': '{title} · {model}',

      'dashboard.empty.noSession': 'No session',
      'dashboard.empty.waitingAnswer': 'Waiting for a reply',
      'dashboard.empty.noActiveSession': 'No active session',
      'dashboard.empty.selectedChat': 'Selected chat',
      'dashboard.empty.replyHint': 'Reply to the model to see the context',
      'dashboard.empty.sendHint': 'Send a request to Antigravity — data will appear here',
      'dashboard.empty.newChat': 'New chat',

      'status.unknownModel': 'Unknown model',
      'status.level.green': 'Normal',
      'status.level.yellow': 'Warning',
      'status.level.red': 'Risk',

      'time.justNow': 'just now',
      'time.minutesAgo': '{n}m ago',
      'time.hoursAgo': '{n}h ago',
      'time.daysAgo': '{n}d ago',
      'time.resetNow': 'resets now',
      'time.countdownDays': '{d}d {h}h',
      'time.countdownHours': '{h}h {m}m',
      'time.countdownMinutes': '{m}m',

      'theme.auto': 'Auto',
      'theme.autoTitle': 'System theme',
      'theme.graphite': 'Graphite',
      'theme.paper': 'Paper',
      'theme.oled': 'OLED',
      'theme.glass': 'Glass',
      'theme.classic': 'Classic',

      'density.compact': 'Compact',
      'density.comfortable': 'Normal',
      'density.spacious': 'Spacious',

      'mode.full': 'Full',
      'mode.compact': 'Compact',
      'mode.minibar': 'Mini bar',
      'mode.pill': 'Pill',
      'mode.fullSize': '400×640',
      'mode.compactSize': '330×270',
      'mode.minibarSize': '480×48',
      'mode.pillSize': '196×42',

      'preset.full': 'Full',
      'preset.compact': 'Compact',
      'preset.minibar': 'Mini bar',
      'preset.pill': 'Pill',
      'preset.paper': 'Light paper',
      'preset.classic': 'Classic',
      'preset.fullHint': 'Vertical dashboard with three blocks',
      'preset.compactHint': 'Dense card without secondary details',
      'preset.minibarHint': 'Horizontal strip for the screen edge',
      'preset.pillHint': 'Context only: ● 442K · 84%',
      'preset.paperHint': 'Warm light theme',
      'preset.classicHint': 'Original author look, 1:1',

      'block.context': 'Context',
      'block.quotas': 'Limits',
      'block.zcode': 'ZCode',
      'block.contextRecent': 'Recent chats',
      'block.contextRecentCount': 'How many to show',
      'block.contextThresholds': 'Context thresholds',
      'block.quotasGemini': 'Gemini',
      'block.quotasThirdParty': 'Third-party models',
      'block.quotasCountdown': 'Refresh timer',
      'block.zcodeBalanceBar': 'Balance bar',
      'block.recentCountAria': 'Number of recent chats',
      'block.moveUp': 'Move block "{name}" up',
      'block.moveDown': 'Move block "{name}" down',
      'block.showBlock': 'Show block "{name}"',
      'block.toggleRecent': 'Show recent chats',
      'block.toggleThresholds': 'Show context thresholds',
      'block.toggleGemini': 'Show Gemini quotas',
      'block.toggleThirdParty': 'Show third-party models',
      'block.toggleCountdown': 'Show refresh timer',
      'block.toggleBalance': 'Show balance bar',

      'classic.variantLabel': 'Classic variant',
      'classic.dark': 'Dark',
      'classic.light': 'Light',

      'settings.title': 'Settings',
      'settings.windowTitle': 'Settings — AG Widgets',
      'settings.subtitle': 'Widget appearance and behavior',
      'settings.tabsAria': 'Settings sections',
      'settings.tab.appearance': 'Appearance',
      'settings.tab.builder': 'Builder',
      'settings.tab.sync': 'Sync',
      'settings.bridgeNotice': 'Settings are available only in the app.',

      'settings.appearance.presetsTitle': 'Presets',
      'settings.appearance.themeTitle': 'Theme',
      'settings.appearance.themeGroupAria': 'Appearance theme',
      'settings.appearance.accentTitle': 'Accent color',
      'settings.appearance.accentAuto': 'auto',
      'settings.appearance.accentGroupAria': 'Quick accent picker',
      'settings.appearance.accentColorAria': 'Pick an accent color',
      'settings.appearance.accentHexAria': 'Accent color in HEX format',
      'settings.appearance.accentReset': 'Default',
      'settings.appearance.fontTitle': 'Font scale',
      'settings.appearance.fontResetTitle': 'Reset to 100%',
      'settings.appearance.fontResetAria': 'Reset scale to 100%',
      'settings.appearance.opacityTitle': 'Window opacity',
      'settings.appearance.radiusTitle': 'Corner radius',
      'settings.appearance.densityTitle': 'Density',
      'settings.appearance.behaviorTitle': 'Window behavior',
      'settings.appearance.alwaysOnTop': 'Always on top',
      'settings.appearance.openAtLogin': 'Launch at Windows sign-in',

      'settings.accent.blue': 'Blue #60a5fa',
      'settings.accent.green': 'Green #34d399',
      'settings.accent.purple': 'Purple #a78bfa',
      'settings.accent.orange': 'Orange #f59e0b',
      'settings.accent.pink': 'Pink #f43f5e',
      'settings.accent.sky': 'Sky blue #38bdf8',

      'settings.notif.title': 'Windows notifications',
      'settings.notif.enabled': 'Show notifications',
      'settings.notif.contextRed': 'Context in the red zone (/compact)',
      'settings.notif.zcodeVoucher': 'ZCode voucher available',
      'settings.notif.quotaReset': '5-hour quota reset',
      'settings.notif.test': 'Test notification',
      'settings.notif.status.testing': 'Sending…',
      'settings.notif.status.sent': 'Notification sent.',
      'settings.notif.status.unsupported': 'Windows does not support notifications.',
      'settings.notif.status.failed': 'Could not send the notification.',
      'settings.notif.status.bridgeOnly': 'Testing is available only in the app.',

      'settings.language.title': 'Language',
      'settings.language.aria': 'Interface language',
      'settings.language.auto': 'Auto',
      'settings.language.ru': 'Русский',
      'settings.language.en': 'English',

      'settings.builder.modeTitle': 'Form factor',

      'settings.blocks.title': 'Blocks',
      'settings.blocks.hint': 'Order from top to bottom',

      'settings.json.title': 'Preset export / import (JSON)',
      'settings.json.export': 'Copy JSON',
      'settings.json.import': 'Apply',
      'settings.json.aria': 'Preset JSON',
      'settings.json.copied': 'JSON copied to the clipboard',
      'settings.json.copyFallback': 'Copy the JSON from the field below',
      'settings.json.missing': 'Could not get the JSON',
      'settings.json.paste': 'Paste the preset JSON',
      'settings.json.applied': 'Preset applied',
      'settings.json.noKnownFields': 'The JSON has no known fields',
      'settings.json.invalid': 'Invalid JSON',
      'settings.json.failed': 'Could not apply the preset',

      'settings.sync.channelTitle': 'Channel',
      'settings.sync.ntfyToggle': 'Sync with phone (ntfy)',
      'settings.sync.serverLabel': 'ntfy server',
      'settings.sync.save': 'Save',
      'settings.sync.serverHint':
        'Only a URL starting with https:// is allowed, without the characters @, ? or #.',
      'settings.sync.serverError':
        'The URL must start with https:// and must not contain @, ? or #.',
      'settings.sync.serverSaved': 'ntfy server saved.',
      'settings.sync.serverSaveFailed': 'Could not save the ntfy server.',
      'settings.sync.ntfyFailed': 'Could not change sync.',
      'settings.sync.pairingUnavailable': 'Sync is available only in the app.',
      'settings.sync.pairingLoadFailed': 'Could not load the pairing state.',

      'settings.e2e.title': 'Encryption',
      'settings.e2e.toggle': 'E2E encryption (AES-256-GCM)',
      'settings.e2e.rotate': 'Rotate key',
      'settings.e2e.rotateConfirm': 'Confirm key rotation',
      'settings.e2e.hintOn':
        'The key is stored on this device and reaches the phone only through a QR code. After a key rotation the phone must be reconnected.',
      'settings.e2e.hintShort':
        'The key is stored on this device and reaches the phone only through a QR code.',
      'settings.e2e.hintOff':
        'Without E2E, messages travel through the ntfy server in plain text. Enable encryption before pairing.',
      'settings.e2e.rotated':
        'Key rotated. Reconnect the phone: open the QR code again and scan it.',
      'settings.e2e.rotateFailed': 'Could not rotate the encryption key.',
      'settings.e2e.toggleFailed': 'Could not change E2E encryption.',

      'settings.qr.title': 'Connect Android (QR code)',
      'settings.qr.hint': 'Open AG Widgets on your phone and scan the code.',
      'settings.qr.reveal': 'Show QR code',
      'settings.qr.hide': 'Hide QR code',
      'settings.qr.badgeOn': 'E2E on',
      'settings.qr.badgeOff': 'No E2E',
      'settings.qr.copyUri': 'Copy pairing link',
      'settings.qr.copyTopic': 'Copy topic',
      'settings.qr.uriCopied': 'Pairing link copied.',
      'settings.qr.topicCopied': 'Topic copied.',
      'settings.qr.noPairingData': 'Pairing data is not loaded yet.',
      'settings.qr.clipboardUnavailable': 'The clipboard is unavailable in this window.',
      'settings.qr.copyFailed': 'Could not copy to the clipboard.',
      'settings.qr.generatorUnavailable': 'The QR generator is unavailable.',
      'settings.qr.tooLong': 'The link is too long for a QR code — copy it with the button below.',
      'settings.qr.buildFailed': 'Could not build the QR code.',
      'settings.qr.imageAria': 'Pairing QR code',
      'settings.qr.errorTooLong':
        'Text is too long for a QR code: 213 bytes max (version 10, level M).',
      'settings.qr.errorDoesNotFit': 'The text does not fit the selected QR version.',
      'settings.qr.errorEmpty': 'Empty text: the QR code cannot be built.',

      'settings.footer.note': 'Changes apply immediately',
      'settings.footer.reset': 'Reset everything',
      'settings.footer.resetConfirm': 'Reset for sure?',

      'settings.errors.applyFailed': 'Could not apply the setting.',
      'settings.errors.applyRejected': 'The setting was not applied.',
      'settings.errors.presetFailed': 'Could not apply the preset.',
      'settings.errors.colorFormat': 'The color must be in #rrggbb format.',
      'settings.errors.resetFailed': 'Could not reset the appearance.',
      'settings.errors.loadFailed': 'Could not load the settings.',
      'settings.errors.updateFailed': 'Could not update the settings.',
      'settings.errors.subscribeFailed': 'Could not subscribe to changes.',
      'settings.errors.saveFailed': 'Could not save the settings',
      'settings.errors.expectedString': 'A JSON string is expected',
      'settings.errors.emptyJson': 'Empty JSON',
      'settings.errors.tooLarge': 'JSON is larger than 16 KB',
      'settings.errors.invalidJson': 'Invalid JSON',
      'settings.errors.rootNotObject': 'The JSON root must be an object',

      'tray.tooltip': 'AG Widgets',
      'tray.show': 'Show widget',
      'tray.hide': 'Minimize to tray',
      'tray.alwaysOnTop': 'Always on top',
      'tray.openAtLogin': 'Launch at sign-in',
      'tray.notifications': 'Notifications',
      'tray.settings': 'Settings…',
      'tray.windowMode': 'Window mode',
      'tray.openConfigDir': 'Open config folder',
      'tray.openZcode': 'Open Z.ai',
      'tray.logout': 'Sign out of Z.ai',
      'tray.quit': 'Quit',
      'tray.modeFull': 'Full',
      'tray.modeCompact': 'Compact',
      'tray.modeMinibar': 'Mini bar',
      'tray.modePill': 'Pill',

      'notif.contextRed.title': 'Time to run /compact',
      'notif.contextRed.body': '{model}: {tokens} ({pct}%) — context is in the red zone',
      'notif.couponAvailable.title': 'A voucher is available in ZCode!',
      'notif.couponFallback.body': 'Open ZCode to claim 300–500M tokens',
      'notif.quotaReset.title': 'Quota {bucket} refreshed',
      'notif.quotaAvailable.body': 'Available: {pct}%',
      'notif.quotaResetLimit.body': 'Limit reset',
      'notif.test.title': 'AG Widgets',
      'notif.test.body': 'Test notification: everything works.',
      'notif.bucket.gemini5h': 'Gemini (5h)',
      'notif.bucket.thirdParty5h': 'Claude/GPT (5h)',
    },
  };

  /** Strip a raw value down to `'ru'`, `'en'` or `'auto'` (unknown → auto). */
  function normalizeLanguage(raw) {
    if (typeof raw !== 'string') return 'auto';
    const value = raw.trim().toLowerCase();
    if (value === 'ru' || value === 'en' || value === 'auto') return value;
    return 'auto';
  }

  /** Turn a language mode into a concrete `'ru'` / `'en'` for the system locale. */
  function resolveLanguage(mode, systemLocale) {
    const normalized = normalizeLanguage(mode);
    if (normalized === 'ru' || normalized === 'en') return normalized;
    if (typeof systemLocale === 'string' && systemLocale.trim().toLowerCase().indexOf('ru') === 0) {
      return 'ru';
    }
    return 'en';
  }

  /** `lang` for pure helpers: unknown values fall back to the contract default. */
  function languageFor(lang) {
    if (typeof lang === 'string') {
      const value = lang.trim().toLowerCase();
      if (value === 'en') return 'en';
      if (value === 'ru') return 'ru';
    }
    return DEFAULT_LANG;
  }

  /** True when the key exists in the Russian table (the canonical key set). */
  function hasKey(key) {
    return typeof key === 'string' && Object.prototype.hasOwnProperty.call(STRINGS.ru, key);
  }

  /** All keys of a dictionary in insertion order. */
  function keys(lang = DEFAULT_LANG) {
    return Object.keys(STRINGS[languageFor(lang)]);
  }

  /** Unique `{placeholder}` names in the given text, in first-seen order. */
  function placeholders(text) {
    if (typeof text !== 'string') return [];
    const pattern = /\{([A-Za-z0-9_][A-Za-z0-9_.-]*)\}/g;
    const found = [];
    let match = pattern.exec(text);
    while (match !== null) {
      if (found.indexOf(match[1]) === -1) found.push(match[1]);
      match = pattern.exec(text);
    }
    return found;
  }

  /**
   * Translate `key` for `lang` (default `'ru'`) and substitute `{name}` from
   * `vars`. A missing key returns the key itself; a missing variable keeps its
   * placeholder untouched.
   */
  function t(key, vars, lang = DEFAULT_LANG) {
    const language = languageFor(lang);
    const table = STRINGS[language];
    if (typeof key !== 'string') return '';
    if (!Object.prototype.hasOwnProperty.call(table, key)) return key;
    const template = table[key];
    if (!vars || typeof vars !== 'object') return template;
    return template.replace(/\{([A-Za-z0-9_][A-Za-z0-9_.-]*)\}/g, function (match, name) {
      if (
        Object.prototype.hasOwnProperty.call(vars, name) &&
        vars[name] !== undefined &&
        vars[name] !== null
      ) {
        return String(vars[name]);
      }
      return match;
    });
  }

  /** Finite numeric `nowMs`, otherwise `Date.now()`; never throws. */
  function resolveNow(nowMs) {
    return typeof nowMs === 'number' && Number.isFinite(nowMs) ? nowMs : Date.now();
  }

  /** Parsed `Date` for an ISO string, or `null` for empty/invalid input. */
  function parseDate(iso) {
    if (!iso) return null;
    const date = iso instanceof Date ? iso : new Date(iso);
    return Number.isNaN(date.getTime()) ? null : date;
  }

  /** Two-digit 24-hour local clock fragment ("03", "09"). */
  function pad2(value) {
    return String(value).padStart(2, '0');
  }

  /** Local 24-hour `HH:MM`, independent of the OS locale. */
  function localClock(date) {
    return pad2(date.getHours()) + ':' + pad2(date.getMinutes());
  }

  /**
   * Relative age: `только что / 5м назад / 3ч назад / 2д назад` (RU default).
   * Rounding matches `desktop/src/renderer/format.js` byte for byte in RU.
   */
  function shortAge(iso, nowMs, lang = DEFAULT_LANG) {
    const date = parseDate(iso);
    if (!date) return '';
    const language = languageFor(lang);
    const minutes = Math.max(0, Math.round((resolveNow(nowMs) - date.getTime()) / 60000));
    if (minutes < 1) return t('time.justNow', null, language);
    if (minutes < 60) return t('time.minutesAgo', { n: minutes }, language);
    const hours = Math.round(minutes / 60);
    if (hours < 24) return t('time.hoursAgo', { n: hours }, language);
    return t('time.daysAgo', { n: Math.round(hours / 24) }, language);
  }

  /**
   * Countdown to a reset with the local clock: `4ч 15м · 18:00`.
   * A zero/epoch timestamp is suppressed; `nowMs` may be omitted.
   */
  function formatCountdown(iso, nowMs, lang = DEFAULT_LANG) {
    const target = parseDate(iso);
    if (!target || target.getTime() <= 0) return '';
    const language = languageFor(lang);
    const diffSec = Math.max(0, Math.floor((target.getTime() - resolveNow(nowMs)) / 1000));
    const clock = localClock(target);
    if (diffSec <= 0) return t('time.resetNow', null, language) + ' · ' + clock;
    const days = Math.floor(diffSec / 86400);
    const hours = Math.floor((diffSec % 86400) / 3600);
    const minutes = Math.floor((diffSec % 3600) / 60);
    let relative;
    if (days > 0) relative = t('time.countdownDays', { d: days, h: hours }, language);
    else if (hours > 0) relative = t('time.countdownHours', { h: hours, m: minutes }, language);
    else relative = t('time.countdownMinutes', { m: minutes }, language);
    return relative + ' · ' + clock;
  }

  /** Attribute → DOM property pairs rewritten by `applyDomTranslations`. */
  const DOM_TRANSLATIONS = [
    { selector: '[data-i18n]', attribute: 'data-i18n', property: 'textContent' },
    { selector: '[data-i18n-title]', attribute: 'data-i18n-title', property: 'title' },
    { selector: '[data-i18n-aria]', attribute: 'data-i18n-aria', property: 'aria-label' },
    { selector: '[data-i18n-placeholder]', attribute: 'data-i18n-placeholder', property: 'placeholder' },
  ];

  /**
   * Live-translate a document without a reload:
   * `[data-i18n]` → textContent, `[data-i18n-title]` → title,
   * `[data-i18n-aria]` → aria-label, `[data-i18n-placeholder]` → placeholder.
   * Also sets `<html lang>`. Tolerates a missing/partial `doc`.
   */
  function applyDomTranslations(doc, lang = DEFAULT_LANG) {
    const mode = normalizeLanguage(lang);
    let locale = '';
    try {
      if (doc && doc.defaultView && doc.defaultView.navigator) {
        locale = doc.defaultView.navigator.language || '';
      }
    } catch {
      locale = '';
    }
    const resolved = mode === 'ru' || mode === 'en' ? mode : resolveLanguage('auto', locale);
    if (!doc) return resolved;

    if (doc.documentElement) {
      try {
        doc.documentElement.lang = resolved;
      } catch {
        // A read-only stub document may reject the assignment.
      }
    }

    if (typeof doc.querySelectorAll !== 'function') return resolved;
    DOM_TRANSLATIONS.forEach(function (rule) {
      let nodes;
      try {
        nodes = doc.querySelectorAll(rule.selector);
      } catch {
        nodes = null;
      }
      if (!nodes || typeof nodes.length !== 'number') return;
      for (let index = 0; index < nodes.length; index += 1) {
        const node = nodes[index];
        if (!node || typeof node.getAttribute !== 'function') continue;
        let key;
        try {
          key = node.getAttribute(rule.attribute);
        } catch {
          continue;
        }
        if (typeof key !== 'string' || key.trim() === '') continue;
        const value = t(key, null, resolved);
        try {
          if (rule.property === 'textContent') node.textContent = value;
          else node.setAttribute(rule.property, value);
        } catch {
          // One unwritable node must not stop the rest of the document.
        }
      }
    });
    return resolved;
  }

  const api = {
    LANGUAGES,
    STRINGS,
    normalizeLanguage,
    resolveLanguage,
    t,
    hasKey,
    keys,
    placeholders,
    shortAge,
    formatCountdown,
    applyDomTranslations,
  };

  const AGW = (root.AGW = root.AGW || {});
  AGW.i18n = api;

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }
})(typeof window !== 'undefined' ? window : globalThis);
