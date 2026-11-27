'use strict';

/**
 * Settings window renderer (`desktop/settings.html`).
 *
 * Talks to the main process only through `window.agWidgets`: it loads the
 * current snapshot, sends one patch per user action, and re-renders from the
 * snapshot returned by IPC. When the bridge is absent (page opened outside
 * Electron), the window degrades to a readable notice instead of throwing.
 *
 * The window has two tabs: "Оформление" (theme, presets, accent, sliders,
 * density, window flags) and "Конструктор" (form factor, block order/options,
 * JSON export/import). Patches are declarative: every control carries a
 * `data-*` key and the block list is always sent as a complete array.
 */
(function () {
  const THEMES = ['system', 'graphite', 'paper', 'oled', 'glass', 'classic'];
  const VARIANTS = ['dark', 'light'];
  const DENSITIES = ['compact', 'comfortable', 'spacious'];
  const MODES = ['full', 'compact', 'minibar', 'pill'];
  const PRESETS = ['full', 'compact', 'minibar', 'pill', 'paper', 'classic'];
  const BLOCK_IDS = ['context_hero', 'quotas_grid', 'zcode_card'];
  const BLOCK_OPTION_DEFAULTS = {
    context_hero: { show_recent: true, recent_count: 3, show_thresholds: true },
    quotas_grid: { show_gemini: true, show_3p: true, show_countdown: true },
    zcode_card: { show_balance_bar: true },
  };
  const ACCENTS = ['#60a5fa', '#34d399', '#a78bfa', '#f59e0b', '#f43f5e', '#38bdf8'];

  const DEFAULT_ACCENT = '#60a5fa';
  const DEFAULT_THEME = 'system';
  const DEFAULT_VARIANT = 'dark';
  const DEFAULT_DENSITY = 'comfortable';
  const DEFAULT_MODE = 'full';
  const DEFAULT_OPACITY = 1;
  const DEFAULT_FONT_SCALE = 1;
  const DEFAULT_CORNER_RADIUS = 12;

  const RANGE_DEBOUNCE_MS = 60;
  const FONT_STEP_PERCENT = 5;
  const OPACITY_STEP_PERCENT = 5;
  const CORNER_STEP = 2;
  const MIN_RECENT = 0;
  const MAX_RECENT = 5;
  const HEX_PATTERN = /^#[0-9a-fA-F]{6}$/;

  const el = {
    notice: byId('bridge-notice'),
    tabAppearance: byId('tab-appearance'),
    tabBuilder: byId('tab-builder'),
    tabSync: byId('tab-sync'),
    panelAppearance: byId('panel-appearance'),
    panelBuilder: byId('panel-builder'),
    panelSync: byId('panel-sync'),
    presets: queryAll('[data-preset]'),
    themes: queryAll('[data-theme-value]'),
    classicRow: byId('classic-variant'),
    variants: queryAll('[data-variant-value]'),
    accentSwatches: queryAll('.swatch[data-accent]'),
    accentColor: byId('accent-color'),
    accentHex: byId('accent-hex'),
    accentReset: byId('accent-reset'),
    accentBadge: byId('accent-value'),
    font: byId('font-scale'),
    fontBadge: byId('font-scale-value'),
    fontReset: byId('font-scale-reset'),
    opacity: byId('opacity'),
    opacityBadge: byId('opacity-value'),
    corner: byId('corner-radius'),
    cornerBadge: byId('corner-radius-value'),
    densities: queryAll('[data-density]'),
    modes: queryAll('[data-mode]'),
    blockList: byId('block-list'),
    recentCount: byId('recent-count'),
    exportBtn: byId('export-json'),
    importArea: byId('import-json'),
    importBtn: byId('import-apply'),
    jsonStatus: byId('json-status'),
    alwaysOnTop: byId('always-on-top'),
    openAtLogin: byId('open-at-login'),
    notifEnabled: byId('notif-enabled'),
    notifChildren: byId('notif-children'),
    notifContextRed: byId('notif-context-red'),
    notifZcodeVoucher: byId('notif-zcode-voucher'),
    notifQuotaReset: byId('notif-quota-reset'),
    notifTest: byId('notif-test'),
    notifStatus: byId('notif-status'),
    reset: byId('reset-appearance'),
    error: byId('settings-error'),
    syncNtfyToggle: byId('sync-ntfy-toggle'),
    syncServerInput: byId('sync-server-input'),
    syncServerSave: byId('sync-server-save'),
    syncServerError: byId('sync-server-error'),
    syncE2eToggle: byId('sync-e2e-toggle'),
    syncE2eRotate: byId('sync-e2e-rotate'),
    syncE2eHint: byId('sync-e2e-hint'),
    syncQrReveal: byId('sync-qr-reveal'),
    syncQrWrap: byId('sync-qr-wrap'),
    syncQrSvg: byId('sync-qr-svg'),
    syncQrBadge: byId('sync-qr-badge'),
    syncUri: byId('sync-uri'),
    syncCopyUri: byId('sync-copy-uri'),
    syncCopyTopic: byId('sync-copy-topic'),
    syncQrHide: byId('sync-qr-hide'),
    syncStatus: byId('sync-status'),
  };

  const bridge = window.agWidgets;
  const state = {
    appearance: null,
    blocks: [],
    alwaysOnTop: false,
    openAtLogin: false,
    notifications: null,
  };

  let queue = Promise.resolve();
  let rangeTimer = null;
  let rangePatch = null;
  let errorTimer = null;
  let tabsBound = false;
  let pairing = null;
  let pairingLoaded = false;
  let rotateArmed = false;
  let rotateTimer = null;
  let syncEventsBound = false;

  // --- small helpers -------------------------------------------------------

  function byId(id) {
    return document.getElementById(id);
  }

  function queryAll(selector) {
    return Array.prototype.slice.call(document.querySelectorAll(selector));
  }

  function guard(fn) {
    return function (event) {
      try {
        return fn(event);
      } catch (error) {
        showError('Не удалось применить настройку.');
        return undefined;
      }
    };
  }

  function hasBridge() {
    return Boolean(bridge) && typeof bridge === 'object';
  }

  function bridgeReady() {
    return hasBridge()
      && typeof bridge.getSettings === 'function'
      && typeof bridge.updateSettings === 'function'
      && typeof bridge.resetAppearance === 'function';
  }

  /** A settings snapshot always carries a normalized `appearance` object. */
  function looksLikeSnapshot(value) {
    return Boolean(value)
      && typeof value === 'object'
      && !Array.isArray(value)
      && Boolean(value.appearance)
      && typeof value.appearance === 'object'
      && !Array.isArray(value.appearance);
  }

  function num(value, fallback) {
    const numeric = typeof value === 'number' ? value : Number(value);
    return Number.isFinite(numeric) ? numeric : fallback;
  }

  function clamp(value, min, max, fallback) {
    const numeric = num(value, fallback);
    return Math.min(max, Math.max(min, numeric));
  }

  function oneOf(value, allowed, fallback) {
    return allowed.indexOf(value) === -1 ? fallback : value;
  }

  function isValidHex(value) {
    return typeof value === 'string' && HEX_PATTERN.test(value.trim());
  }

  function normalizeHex(value) {
    return isValidHex(value) ? value.trim().toLowerCase() : null;
  }

  /** Percent snapped to the slider step, clamped into [min, max]. */
  function snapPercent(value, min, max, step, fallback) {
    const numeric = clamp(value, min, max, fallback);
    return Math.min(max, Math.max(min, Math.round(numeric / step) * step));
  }

  function snapCorner(value) {
    const numeric = clamp(value, 0, 24, DEFAULT_CORNER_RADIUS);
    return Math.min(24, Math.max(0, Math.round(numeric / CORNER_STEP) * CORNER_STEP));
  }

  function clampRecent(value) {
    const numeric = num(value, BLOCK_OPTION_DEFAULTS.context_hero.recent_count);
    return Math.min(MAX_RECENT, Math.max(MIN_RECENT, Math.round(numeric)));
  }

  function cloneBlocks(blocks) {
    return (Array.isArray(blocks) ? blocks : []).map(function (block) {
      return {
        id: block.id,
        enabled: Boolean(block.enabled),
        options: Object.assign({}, block.options),
      };
    });
  }

  /** Normalize an untrusted block list against the known ids/options. */
  function normalizeBlocks(raw) {
    const source = Array.isArray(raw) ? raw : [];
    const result = [];
    const seen = {};
    source.forEach(function (entry) {
      if (!entry || typeof entry !== 'object' || Array.isArray(entry)) return;
      const id = BLOCK_IDS.indexOf(entry.id) === -1 ? null : entry.id;
      if (!id || seen[id]) return;
      seen[id] = true;
      result.push(normalizeBlock(id, entry));
    });
    BLOCK_IDS.forEach(function (id) {
      if (!seen[id]) result.push(normalizeBlock(id, null));
    });
    return result;
  }

  function normalizeBlock(id, raw) {
    const source = raw && typeof raw === 'object' ? raw : {};
    const options = source.options && typeof source.options === 'object' ? source.options : {};
    const defaults = BLOCK_OPTION_DEFAULTS[id] || {};
    const normalized = {};
    Object.keys(defaults).forEach(function (key) {
      if (key === 'recent_count') {
        normalized[key] = clampRecent(
          Object.prototype.hasOwnProperty.call(options, key) ? options[key] : defaults[key],
        );
      } else {
        normalized[key] = typeof options[key] === 'boolean' ? options[key] : defaults[key];
      }
    });
    return {
      id: id,
      enabled: typeof source.enabled === 'boolean' ? source.enabled : true,
      options: normalized,
    };
  }

  function ensureBlocks() {
    if (!state.blocks.length) state.blocks = normalizeBlocks(null);
    return state.blocks;
  }

  function allBlocksEnabled() {
    return normalizeBlocks(null);
  }

  /** Fallback presets: only used when the bridge has no `applyPreset` yet. */
  const FALLBACK_PRESETS = {
    full: {
      theme: 'system',
      preset_mode: 'full',
      density: 'comfortable',
      blocks: allBlocksEnabled(),
    },
    compact: {
      preset_mode: 'compact',
      density: 'compact',
      blocks: [
        {
          id: 'context_hero',
          enabled: true,
          options: { show_recent: false, recent_count: 3, show_thresholds: false },
        },
        {
          id: 'quotas_grid',
          enabled: true,
          options: { show_gemini: true, show_3p: true, show_countdown: true },
        },
        { id: 'zcode_card', enabled: true, options: { show_balance_bar: true } },
      ],
    },
    minibar: { preset_mode: 'minibar', density: 'compact', corner_radius: 12 },
    pill: { preset_mode: 'pill', density: 'compact', corner_radius: 20 },
    paper: { theme: 'paper', preset_mode: 'full', corner_radius: 12, custom_accent: null },
    classic: {
      theme: 'classic',
      classic_variant: 'dark',
      preset_mode: 'full',
      corner_radius: 10,
      density: 'comfortable',
      custom_accent: null,
      blocks: allBlocksEnabled(),
    },
  };

  // --- rendering -----------------------------------------------------------

  function selectTheme(theme) {
    el.themes.forEach(function (button) {
      const active = button.getAttribute('data-theme-value') === theme;
      button.classList.toggle('is-active', active);
      button.setAttribute('aria-pressed', active ? 'true' : 'false');
    });
    const isClassic = theme === 'classic';
    if (el.classicRow) {
      el.classicRow.classList.toggle('hidden', !isClassic);
      el.classicRow.setAttribute('aria-hidden', isClassic ? 'false' : 'true');
    }
  }

  function selectVariant(variant) {
    el.variants.forEach(function (button) {
      const active = button.getAttribute('data-variant-value') === variant;
      button.classList.toggle('is-active', active);
      button.setAttribute('aria-pressed', active ? 'true' : 'false');
    });
  }

  function setRange(input, badge, percent, unit) {
    const suffix = unit || '%';
    if (input) {
      input.value = String(percent);
      input.setAttribute('aria-valuetext', percent + suffix);
    }
    if (badge) badge.textContent = percent + suffix;
  }

  function setSwitch(button, on) {
    if (!button) return;
    const value = on ? 'true' : 'false';
    button.setAttribute('aria-checked', value);
    button.classList.toggle('is-on', Boolean(on));
  }

  /** Notification toggles; a stale bridge without the field keeps all three on. */
  const NOTIFICATION_DEFAULTS = {
    enabled: true,
    context_red: true,
    zcode_voucher: true,
    quota_5h_reset: true,
  };
  const NOTIF_STATUS_MS = 4000;
  let notifStatusTimer = null;

  function normalizeNotifications(raw) {
    const source = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : {};
    const next = {};
    Object.keys(NOTIFICATION_DEFAULTS).forEach(function (key) {
      next[key] = typeof source[key] === 'boolean' ? source[key] : NOTIFICATION_DEFAULTS[key];
    });
    return next;
  }

  function setNotifStatus(message, isError) {
    if (!el.notifStatus) return;
    el.notifStatus.textContent = message || '';
    el.notifStatus.classList.toggle('is-error', Boolean(isError));
    if (notifStatusTimer) clearTimeout(notifStatusTimer);
    if (!message) return;
    notifStatusTimer = setTimeout(function () {
      notifStatusTimer = null;
      if (el.notifStatus) {
        el.notifStatus.textContent = '';
        el.notifStatus.classList.remove('is-error');
      }
    }, NOTIF_STATUS_MS);
  }

  /** Paint the master switch, dim and lock the children when it is off. */
  function renderNotifications() {
    const prefs = state.notifications || normalizeNotifications(null);
    const locked = prefs.enabled === false;
    setSwitch(el.notifEnabled, prefs.enabled);
    setSwitch(el.notifContextRed, prefs.context_red);
    setSwitch(el.notifZcodeVoucher, prefs.zcode_voucher);
    setSwitch(el.notifQuotaReset, prefs.quota_5h_reset);
    if (el.notifChildren) el.notifChildren.classList.toggle('is-off', locked);
    [el.notifContextRed, el.notifZcodeVoucher, el.notifQuotaReset, el.notifTest]
      .forEach(function (node) {
        if (node) node.disabled = locked;
      });
  }

  function setNotificationFlag(key, value) {
    const next = normalizeNotifications(state.notifications);
    if (!Object.prototype.hasOwnProperty.call(next, key)) return;
    next[key] = Boolean(value);
    state.notifications = next;
    renderNotifications();
    setNotifStatus('', false);
    applyPatch({ notifications: next });
  }

  function handleTestNotification() {
    if (!el.notifTest) return;
    const prefs = normalizeNotifications(state.notifications);
    if (!prefs.enabled) return;
    if (!hasBridge() || typeof bridge.testNotification !== 'function') {
      setNotifStatus('Проверка доступна только в приложении.', true);
      return;
    }
    setNotifStatus('Отправляем…', false);
    Promise.resolve()
      .then(function () {
        return bridge.testNotification();
      })
      .then(function (result) {
        if (result && result.ok) {
          setNotifStatus('Уведомление отправлено.', false);
          return;
        }
        if (result && result.supported === false) {
          setNotifStatus('Windows не поддерживает уведомления.', true);
          return;
        }
        setNotifStatus('Не удалось отправить уведомление.', true);
      }, function () {
        setNotifStatus('Не удалось отправить уведомление.', true);
      });
  }

  function renderAccent(raw) {
    const accent = normalizeHex(raw);
    el.accentSwatches.forEach(function (button) {
      const value = normalizeHex(button.getAttribute('data-accent'));
      const active = accent !== null && value === accent;
      button.setAttribute('aria-pressed', active ? 'true' : 'false');
      button.classList.toggle('is-active', active);
    });
    if (el.accentColor) el.accentColor.value = accent || DEFAULT_ACCENT;
    if (el.accentHex) {
      el.accentHex.value = accent || '';
      el.accentHex.setAttribute('aria-invalid', 'false');
    }
    if (el.accentBadge) el.accentBadge.textContent = accent || 'авто';
  }

  function selectDensity(density) {
    el.densities.forEach(function (button) {
      const active = button.getAttribute('data-density') === density;
      button.classList.toggle('is-active', active);
      button.setAttribute('aria-pressed', active ? 'true' : 'false');
    });
  }

  function selectMode(mode) {
    el.modes.forEach(function (button) {
      const active = button.getAttribute('data-mode') === mode;
      button.classList.toggle('is-active', active);
      button.setAttribute('aria-pressed', active ? 'true' : 'false');
    });
  }

  /**
   * Preset whose patch still matches the current settings. `null` keeps every
   * tile quiet: `graphite`/`oled`/`glass` are themes, not presets (D-14).
   */
  function activePresetId(appearance) {
    const mode = oneOf(appearance.preset_mode, MODES, DEFAULT_MODE);
    const theme = oneOf(appearance.theme, THEMES, DEFAULT_THEME);
    if (mode === 'minibar' || mode === 'pill' || mode === 'compact') return mode;
    if (theme === 'classic') return 'classic';
    if (theme === 'paper') return 'paper';
    if (theme === 'system') return 'full';
    return null;
  }

  function selectPreset(id) {
    el.presets.forEach(function (button) {
      const active = Boolean(id) && button.getAttribute('data-preset') === id;
      button.classList.toggle('is-active', active);
      button.setAttribute('aria-pressed', active ? 'true' : 'false');
    });
  }

  /** Reorder the static cards and paint every switch from `state.blocks`. */
  function renderBlocks() {
    const list = el.blockList;
    if (!list) return;
    const blocks = ensureBlocks();
    const cards = Array.prototype.slice.call(list.querySelectorAll('.block-card'));
    if (!cards.length) return;

    const byBlockId = {};
    cards.forEach(function (card) {
      const id = card.getAttribute('data-block');
      if (id) byBlockId[id] = card;
    });

    blocks.forEach(function (block) {
      const card = byBlockId[block.id];
      if (card) list.appendChild(card);
    });

    blocks.forEach(function (block, index) {
      const card = byBlockId[block.id];
      if (!card) return;
      card.classList.toggle('is-off', !block.enabled);
      setSwitch(card.querySelector('[data-block-toggle]'), block.enabled);

      const up = card.querySelector('.block-up');
      const down = card.querySelector('.block-down');
      if (up) up.disabled = index === 0;
      if (down) down.disabled = index === blocks.length - 1;

      Array.prototype.forEach.call(card.querySelectorAll('[data-option]'), function (sw) {
        const key = sw.getAttribute('data-option');
        if (key && Object.prototype.hasOwnProperty.call(block.options, key)) {
          setSwitch(sw, Boolean(block.options[key]));
        }
      });

      if (block.id === 'context_hero' && el.recentCount) {
        el.recentCount.value = String(block.options.recent_count);
      }
    });
  }

  function render(snapshot) {
    if (!looksLikeSnapshot(snapshot)) return;
    const appearance = snapshot.appearance;

    state.appearance = appearance;
    state.blocks = normalizeBlocks(appearance.blocks);

    const theme = oneOf(appearance.theme, THEMES, DEFAULT_THEME);
    const variant = oneOf(appearance.classic_variant, VARIANTS, DEFAULT_VARIANT);
    const density = oneOf(appearance.density, DENSITIES, DEFAULT_DENSITY);
    const mode = oneOf(appearance.preset_mode, MODES, DEFAULT_MODE);
    const fontPercent = snapPercent(
      num(appearance.font_scale, DEFAULT_FONT_SCALE) * 100,
      85,
      140,
      FONT_STEP_PERCENT,
      100,
    );
    const opacityPercent = snapPercent(
      num(appearance.opacity, DEFAULT_OPACITY) * 100,
      60,
      100,
      OPACITY_STEP_PERCENT,
      100,
    );
    const corner = snapCorner(appearance.corner_radius);

    if (typeof snapshot.alwaysOnTop === 'boolean') {
      state.alwaysOnTop = snapshot.alwaysOnTop;
    }
    if (typeof snapshot.openAtLogin === 'boolean') {
      state.openAtLogin = snapshot.openAtLogin;
    }
    state.notifications = normalizeNotifications(snapshot.notifications);

    if (typeof snapshot.effectiveTheme === 'string' && snapshot.effectiveTheme) {
      document.documentElement.dataset.theme = snapshot.effectiveTheme;
    }
    // The accessibility scale must reach this window too (S1-036).
    document.documentElement.style.setProperty('--font-scale', String(fontPercent / 100));
    applyAccentTokens(
      typeof snapshot.effectiveTheme === 'string' && snapshot.effectiveTheme
        ? snapshot.effectiveTheme
        : document.documentElement.dataset.theme,
      normalizeHex(appearance.custom_accent),
    );

    selectTheme(theme);
    selectVariant(variant);
    selectPreset(activePresetId(appearance));
    renderAccent(appearance.custom_accent);
    setRange(el.font, el.fontBadge, fontPercent, '%');
    // At 100% the reset button has nothing to do, so it is muted (D-14).
    if (el.fontReset) el.fontReset.disabled = fontPercent === 100;
    setRange(el.opacity, el.opacityBadge, opacityPercent, '%');
    setRange(el.corner, el.cornerBadge, corner, 'px');
    selectDensity(density);
    selectMode(mode);
    renderBlocks();
    setSwitch(el.alwaysOnTop, state.alwaysOnTop);
    setSwitch(el.openAtLogin, state.openAtLogin);
    renderNotifications();
  }

  /** Build a full appearance object from the last snapshot (export fallback). */
  function appearancePayload() {
    const a = state.appearance || {};
    return {
      schema_version: 1,
      theme: oneOf(a.theme, THEMES, DEFAULT_THEME),
      classic_variant: oneOf(a.classic_variant, VARIANTS, DEFAULT_VARIANT),
      custom_accent: normalizeHex(a.custom_accent),
      opacity: clamp(a.opacity, 0.6, 1, DEFAULT_OPACITY),
      corner_radius: snapCorner(a.corner_radius),
      font_scale: clamp(a.font_scale, 0.85, 1.4, DEFAULT_FONT_SCALE),
      density: oneOf(a.density, DENSITIES, DEFAULT_DENSITY),
      preset_mode: oneOf(a.preset_mode, MODES, DEFAULT_MODE),
      blocks: cloneBlocks(ensureBlocks()),
    };
  }

  // --- status + errors -----------------------------------------------------

  function showError(message) {
    if (!el.error) return;
    el.error.textContent = message;
    el.error.classList.remove('hidden');
    if (errorTimer) clearTimeout(errorTimer);
    errorTimer = setTimeout(function () {
      errorTimer = null;
      el.error.classList.add('hidden');
    }, 4000);
  }

  function setJsonStatus(message, isError) {
    if (!el.jsonStatus) return;
    el.jsonStatus.textContent = message || '';
    el.jsonStatus.classList.toggle('is-error', Boolean(isError));
    el.jsonStatus.classList.toggle('is-ok', Boolean(message) && !isError);
    if (el.importArea) {
      el.importArea.setAttribute('aria-invalid', isError ? 'true' : 'false');
    }
  }

  /**
   * Readability tokens for a custom accent: the same maths the widget window
   * uses, so the settings preview never shows a 2:1 badge (S1-018). The accent
   * itself is set alongside them: a derived `--on-accent` without the matching
   * `--accent` painted dark text on the theme's blue fill (~2.4:1, D-03).
   */
  function applyAccentTokens(theme, accent) {
    const root = document.documentElement;
    const math = window.AGW && window.AGW.themeMath ? window.AGW.themeMath : null;
    const tokens = accent && math ? math.resolveAccentTokens(accent, theme) : null;
    if (!tokens) {
      if (root.dataset.customAccent) delete root.dataset.customAccent;
      root.style.removeProperty('--custom-accent');
      root.style.removeProperty('--accent');
      root.style.removeProperty('--on-accent');
      root.style.removeProperty('--accent-ink');
      root.style.removeProperty('--accent-bar');
      return;
    }
    root.dataset.customAccent = 'true';
    root.style.setProperty('--custom-accent', accent);
    root.style.setProperty('--accent', accent);
    root.style.setProperty('--on-accent', tokens.onAccent);
    root.style.setProperty('--accent-ink', tokens.accentInk);
    root.style.setProperty('--accent-bar', tokens.accentBar);
  }

  // --- IPC patches ---------------------------------------------------------

  /** Serialize IPC calls so a slow response can never overwrite a newer snapshot. */
  function applyPatch(patch) {
    if (!bridgeReady() || !patch || typeof patch !== 'object') return;
    queue = queue
      .then(function () {
        return bridge.updateSettings(patch);
      })
      .then(function (snapshot) {
        if (looksLikeSnapshot(snapshot)) render(snapshot);
        else if (snapshot && snapshot.ok === false) showError('Настройка не применилась.');
      })
      .catch(function () {
        showError('Не удалось применить настройку.');
      });
  }

  function queueRangePatch(patch) {
    rangePatch = Object.assign(rangePatch || {}, patch);
    if (rangeTimer) clearTimeout(rangeTimer);
    rangeTimer = setTimeout(flushRangePatch, RANGE_DEBOUNCE_MS);
  }

  function flushRangePatch() {
    if (rangeTimer) {
      clearTimeout(rangeTimer);
      rangeTimer = null;
    }
    if (!rangePatch) return;
    const patch = rangePatch;
    rangePatch = null;
    applyPatch(patch);
  }

  function sendBlocks() {
    applyPatch({ blocks: cloneBlocks(ensureBlocks()) });
  }

  // --- sync tab ------------------------------------------------------------

  const HTTPS_SERVER_PATTERN = /^https:\/\/[^\s/?#@]+(\/[^\s?#@]*)?$/i;
  const SERVER_ERROR_MESSAGE = 'Адрес должен начинаться с https:// и не содержать @, ? или #.';
  const ROTATE_CONFIRM_MS = 6000;
  const ROTATE_LABEL = 'Сменить ключ';
  const ROTATE_CONFIRM_LABEL = 'Подтвердить смену ключа';
  const E2E_HINT_ON = 'Ключ хранится на этом устройстве и передаётся телефону через QR-код. При смене ключа телефон нужно переподключить.';
  const E2E_HINT_OFF = 'Без E2E сообщения идут через сервер ntfy в открытом виде. Включите шифрование перед сопряжением.';

  let pairingRequest = null;

  function pairingBridgeReady() {
    return hasBridge()
      && typeof bridge.getPairing === 'function'
      && typeof bridge.updatePairing === 'function';
  }

  function setSyncStatus(message, isError) {
    if (!el.syncStatus) return;
    const text = message || '';
    el.syncStatus.textContent = text;
    el.syncStatus.classList.toggle('hidden', !text);
    el.syncStatus.classList.toggle('is-error', Boolean(text) && Boolean(isError));
    el.syncStatus.classList.toggle('is-ok', Boolean(text) && !isError);
  }

  function setSyncBadge(enabled) {
    if (!el.syncQrBadge) return;
    el.syncQrBadge.textContent = enabled ? 'E2E вкл' : 'Без E2E';
    el.syncQrBadge.classList.toggle('is-on', Boolean(enabled));
  }

  function updateE2eHint(enabled) {
    if (el.syncE2eHint) el.syncE2eHint.textContent = enabled ? E2E_HINT_ON : E2E_HINT_OFF;
  }

  function setServerError(message) {
    if (el.syncServerError) {
      el.syncServerError.textContent = message || '';
      el.syncServerError.classList.toggle('hidden', !message);
    }
    if (el.syncServerInput) {
      el.syncServerInput.setAttribute('aria-invalid', message ? 'true' : 'false');
    }
  }

  function isValidServerUrl(value) {
    return typeof value === 'string' && HTTPS_SERVER_PATTERN.test(value.trim());
  }

  function refreshQr() {
    if (!pairing) return;
    setSyncBadge(pairing.e2e_enabled);
    if (!el.syncQrWrap || el.syncQrWrap.classList.contains('hidden')) return;

    const generator = window.AGW && window.AGW.qr;
    if (!generator || typeof generator.renderQrSvg !== 'function') {
      setSyncStatus('QR-генератор недоступен.', true);
      return;
    }

    if (el.syncUri) el.syncUri.textContent = pairing.uri;
    try {
      const svg = generator.renderQrSvg(pairing.uri, { quietZone: 4 });
      if (el.syncQrSvg) {
        el.syncQrSvg.innerHTML = svg;
        el.syncQrSvg.classList.remove('hidden');
      }
    } catch (error) {
      if (el.syncQrSvg) {
        el.syncQrSvg.innerHTML = '';
        el.syncQrSvg.classList.add('hidden');
      }
      if (error && error.code === 'capacity') {
        setSyncStatus('Ссылка слишком длинная для QR-кода — скопируйте её кнопкой ниже.', true);
      } else {
        setSyncStatus('Не удалось построить QR-код.', true);
      }
    }
  }

  function applyPairingSnapshot(snapshot, options) {
    const forceServerInput = Boolean(options && options.forceServerInput);
    if (!snapshot || typeof snapshot !== 'object' || snapshot.ok === false) {
      const message = snapshot && typeof snapshot.error === 'string' && snapshot.error
        ? snapshot.error
        : 'Не удалось получить состояние сопряжения.';
      setSyncStatus(message, true);
      return false;
    }

    pairing = {
      topic: typeof snapshot.topic === 'string' ? snapshot.topic : '',
      server: typeof snapshot.server === 'string' ? snapshot.server : '',
      ntfy_enabled: Boolean(snapshot.ntfy_enabled),
      e2e_enabled: Boolean(snapshot.e2e_enabled),
      uri: typeof snapshot.uri === 'string' ? snapshot.uri : '',
    };
    pairingLoaded = true;

    if (el.syncNtfyToggle) el.syncNtfyToggle.checked = pairing.ntfy_enabled;
    if (el.syncE2eToggle) el.syncE2eToggle.checked = pairing.e2e_enabled;
    if (el.syncServerInput && (forceServerInput || document.activeElement !== el.syncServerInput)) {
      el.syncServerInput.value = pairing.server;
    }
    setServerError('');
    setSyncBadge(pairing.e2e_enabled);
    updateE2eHint(pairing.e2e_enabled);
    setSyncStatus('', false);
    refreshQr();
    return true;
  }

  function loadPairing() {
    if (!pairingBridgeReady()) {
      setSyncStatus('Синхронизация доступна только в приложении.', true);
      return Promise.resolve(false);
    }
    if (pairingRequest) return pairingRequest;

    pairingRequest = Promise.resolve()
      .then(function () {
        return bridge.getPairing();
      })
      .then(function (snapshot) {
        const ok = applyPairingSnapshot(snapshot);
        if (!ok) pairingLoaded = false;
        return ok;
      })
      .catch(function () {
        pairingLoaded = false;
        setSyncStatus('Не удалось получить состояние сопряжения.', true);
        return false;
      })
      .then(function (result) {
        pairingRequest = null;
        return result;
      });
    return pairingRequest;
  }

  function ensurePairingLoaded() {
    if (pairingLoaded) return;
    loadPairing();
  }

  function revealQr() {
    if (!el.syncQrWrap) return;
    el.syncQrWrap.hidden = false;
    el.syncQrWrap.classList.remove('hidden');
    if (el.syncQrReveal) el.syncQrReveal.classList.add('hidden');
    if (pairingLoaded) {
      refreshQr();
      return;
    }
    loadPairing().then(function (ok) {
      if (ok) {
        refreshQr();
        return;
      }
      hideQr();
    });
  }

  function hideQr() {
    if (el.syncQrWrap) {
      el.syncQrWrap.hidden = true;
      el.syncQrWrap.classList.add('hidden');
    }
    if (el.syncQrReveal) el.syncQrReveal.classList.remove('hidden');
    // Clear the secret from the DOM whenever the QR card is not on screen.
    if (el.syncQrSvg) el.syncQrSvg.innerHTML = '';
    if (el.syncUri) el.syncUri.textContent = '';
  }

  /**
   * Copy through the main process when the Electron bridge is present
   * (`navigator.clipboard` is rejected in the sandboxed `file://` window),
   * and fall back to the Web API when the page is opened in a plain browser.
   */
  async function writeClipboardText(text) {
    try {
      if (hasBridge() && bridge && typeof bridge.copyText === 'function') {
        const res = await bridge.copyText(text);
        // No Web fallback here: it is guaranteed to fail in this window.
        return Boolean(res && res.ok === true);
      }
    } catch (error) {
      return false;
    }
    try {
      if (!navigator.clipboard || typeof navigator.clipboard.writeText !== 'function') {
        return false;
      }
      await navigator.clipboard.writeText(text);
      return true;
    } catch (error) {
      return false;
    }
  }

  async function copyToClipboard(text, okMessage) {
    if (!text) {
      setSyncStatus('Данные сопряжения ещё не загружены.', true);
      return;
    }
    const ok = await writeClipboardText(text);
    if (ok) {
      setSyncStatus(okMessage, false);
    } else {
      setSyncStatus('Не удалось скопировать в буфер обмена.', true);
    }
  }

  function disarmRotate() {
    if (rotateTimer) {
      clearTimeout(rotateTimer);
      rotateTimer = null;
    }
    rotateArmed = false;
    if (!el.syncE2eRotate) return;
    el.syncE2eRotate.classList.remove('is-armed');
    delete el.syncE2eRotate.dataset.armed;
    el.syncE2eRotate.textContent = ROTATE_LABEL;
  }

  function armRotate() {
    if (!el.syncE2eRotate) return;
    rotateArmed = true;
    el.syncE2eRotate.dataset.armed = 'true';
    el.syncE2eRotate.classList.add('is-armed');
    el.syncE2eRotate.textContent = ROTATE_CONFIRM_LABEL;
    if (rotateTimer) clearTimeout(rotateTimer);
    rotateTimer = setTimeout(disarmRotate, ROTATE_CONFIRM_MS);
  }

  function handleRotate() {
    if (!pairingBridgeReady()) return;
    if (!rotateArmed) {
      armRotate();
      return;
    }
    disarmRotate();
    queue = queue
      .then(function () {
        return bridge.updatePairing({ e2e_action: 'rotate' });
      })
      .then(function (snapshot) {
        if (!applyPairingSnapshot(snapshot)) return;
        setSyncStatus(
          'Ключ сменён. Переподключите телефон: откройте QR-код заново и отсканируйте его.',
          false,
        );
      }, function () {
        setSyncStatus('Не удалось сменить ключ шифрования.', true);
      });
  }

  function handleNtfyToggle() {
    if (!el.syncNtfyToggle) return;
    if (!pairingBridgeReady()) {
      el.syncNtfyToggle.checked = !el.syncNtfyToggle.checked;
      return;
    }
    const desired = Boolean(el.syncNtfyToggle.checked);
    const previous = pairing ? Boolean(pairing.ntfy_enabled) : !desired;
    if (pairing) pairing.ntfy_enabled = desired;
    const revert = function (message) {
      if (el.syncNtfyToggle) el.syncNtfyToggle.checked = previous;
      if (pairing) pairing.ntfy_enabled = previous;
      if (message) setSyncStatus(message, true);
    };
    queue = queue
      .then(function () {
        return bridge.updatePairing({ ntfy_enabled: desired });
      })
      .then(function (snapshot) {
        if (!applyPairingSnapshot(snapshot)) revert();
      }, function () {
        revert('Не удалось изменить синхронизацию.');
      });
  }

  function handleE2eToggle() {
    if (!el.syncE2eToggle) return;
    if (!pairingBridgeReady()) {
      el.syncE2eToggle.checked = !el.syncE2eToggle.checked;
      return;
    }
    const desired = Boolean(el.syncE2eToggle.checked);
    const previous = pairing ? Boolean(pairing.e2e_enabled) : !desired;
    if (pairing) pairing.e2e_enabled = desired;
    const revert = function (message) {
      if (el.syncE2eToggle) el.syncE2eToggle.checked = previous;
      if (pairing) pairing.e2e_enabled = previous;
      setSyncBadge(previous);
      updateE2eHint(previous);
      if (message) setSyncStatus(message, true);
    };
    queue = queue
      .then(function () {
        return bridge.updatePairing({ e2e_action: desired ? 'enable' : 'disable' });
      })
      .then(function (snapshot) {
        if (!applyPairingSnapshot(snapshot)) revert();
      }, function () {
        revert('Не удалось изменить E2E-шифрование.');
      });
  }

  function handleServerSave() {
    if (!el.syncServerInput || !pairingBridgeReady()) return;
    const raw = el.syncServerInput.value.trim();
    const candidate = raw || 'https://ntfy.sh';
    if (!isValidServerUrl(candidate)) {
      setServerError(SERVER_ERROR_MESSAGE);
      return;
    }
    setServerError('');
    queue = queue
      .then(function () {
        return bridge.updatePairing({ ntfy_server: candidate });
      })
      .then(function (snapshot) {
        if (applyPairingSnapshot(snapshot, { forceServerInput: true })) {
          setSyncStatus('Сервер ntfy сохранён.', false);
        }
      }, function () {
        setSyncStatus('Не удалось сохранить сервер ntfy.', true);
      });
  }

  function bindSyncEvents() {
    if (syncEventsBound) return;
    syncEventsBound = true;

    if (el.syncNtfyToggle) {
      el.syncNtfyToggle.addEventListener('change', guard(handleNtfyToggle));
    }
    if (el.syncE2eToggle) {
      el.syncE2eToggle.addEventListener('change', guard(handleE2eToggle));
    }
    if (el.syncServerSave) {
      el.syncServerSave.addEventListener('click', guard(handleServerSave));
    }
    if (el.syncServerInput) {
      el.syncServerInput.addEventListener('input', guard(function () {
        setServerError('');
      }));
      el.syncServerInput.addEventListener('keydown', guard(function (event) {
        if (event.key !== 'Enter') return;
        event.preventDefault();
        handleServerSave();
      }));
    }
    if (el.syncE2eRotate) {
      el.syncE2eRotate.addEventListener('click', guard(handleRotate));
    }
    if (el.syncQrReveal) {
      el.syncQrReveal.addEventListener('click', guard(revealQr));
    }
    if (el.syncQrHide) {
      el.syncQrHide.addEventListener('click', guard(hideQr));
    }
    if (el.syncCopyUri) {
      el.syncCopyUri.addEventListener('click', guard(function () {
        copyToClipboard(pairing ? pairing.uri : '', 'Ссылка сопряжения скопирована.');
      }));
    }
    if (el.syncCopyTopic) {
      el.syncCopyTopic.addEventListener('click', guard(function () {
        copyToClipboard(pairing ? pairing.topic : '', 'Тема скопирована.');
      }));
    }

    // Any other click, any typing or Escape cancels the armed key rotation.
    document.addEventListener('click', guard(function (event) {
      if (!rotateArmed) return;
      if (el.syncE2eRotate && event.target === el.syncE2eRotate) return;
      disarmRotate();
    }), true);
    document.addEventListener('input', guard(function () {
      if (rotateArmed) disarmRotate();
    }), true);
    document.addEventListener('keydown', guard(function (event) {
      if (rotateArmed && event.key === 'Escape') disarmRotate();
    }));
  }

  // --- tabs ----------------------------------------------------------------

  function selectTab(tabId) {
    const pairs = [
      [el.tabAppearance, el.panelAppearance],
      [el.tabBuilder, el.panelBuilder],
      [el.tabSync, el.panelSync],
    ];
    pairs.forEach(function (pair) {
      const tab = pair[0];
      const panel = pair[1];
      if (!tab || !panel) return;
      const active = tab.id === tabId;
      tab.setAttribute('aria-selected', active ? 'true' : 'false');
      tab.setAttribute('tabindex', active ? '0' : '-1');
      tab.classList.toggle('is-active', active);
      panel.classList.toggle('hidden', !active);
    });

    if (tabId === 'tab-sync') {
      // Lazy: pairing state is only fetched when the tab is first opened.
      ensurePairingLoaded();
      return;
    }
    // Leaving the tab wipes the pairing secret from the DOM.
    hideQr();
  }

  function bindTabs() {
    if (tabsBound) return;
    tabsBound = true;
    const tabs = [el.tabAppearance, el.tabBuilder, el.tabSync].filter(Boolean);
    if (!tabs.length) return;

    tabs.forEach(function (tab) {
      tab.addEventListener('click', guard(function () {
        selectTab(tab.id);
      }));
      tab.addEventListener('keydown', guard(function (event) {
        const key = event.key;
        let index = tabs.indexOf(tab);
        if (key === 'ArrowRight' || key === 'ArrowDown') {
          index = (index + 1) % tabs.length;
        } else if (key === 'ArrowLeft' || key === 'ArrowUp') {
          index = (index - 1 + tabs.length) % tabs.length;
        } else if (key === 'Home') {
          index = 0;
        } else if (key === 'End') {
          index = tabs.length - 1;
        } else {
          return;
        }
        event.preventDefault();
        selectTab(tabs[index].id);
        tabs[index].focus();
      }));
    });

    selectTab(el.tabAppearance ? el.tabAppearance.id : 'tab-appearance');
  }

  // --- user actions --------------------------------------------------------

  function applyPreset(id) {
    if (PRESETS.indexOf(id) === -1) return;
    if (hasBridge() && typeof bridge.applyPreset === 'function') {
      queue = queue
        .then(function () {
          return bridge.applyPreset(id);
        })
        .then(function (result) {
          if (looksLikeSnapshot(result)) {
            render(result);
          } else if (result && result.ok && looksLikeSnapshot(result.snapshot)) {
            render(result.snapshot);
          } else if (result && result.ok === false) {
            showError(result.error || 'Не удалось применить пресет.');
          }
        })
        .catch(function () {
          showError('Не удалось применить пресет.');
        });
      return;
    }
    const preset = FALLBACK_PRESETS[id];
    if (preset) applyPatch(JSON.parse(JSON.stringify(preset)));
  }

  function applyAccent(raw) {
    const accent = normalizeHex(raw);
    renderAccent(accent);
    applyPatch({ custom_accent: accent });
  }

  function handleBlockMove(button) {
    const card = button.closest('.block-card');
    if (!card) return;
    const id = card.getAttribute('data-block');
    const blocks = ensureBlocks();
    const from = blocks.findIndex(function (block) {
      return block.id === id;
    });
    if (from === -1) return;
    const to = from + Number(button.getAttribute('data-move'));
    if (to < 0 || to >= blocks.length) return;
    const moved = blocks.splice(from, 1)[0];
    blocks.splice(to, 0, moved);
    renderBlocks();
    sendBlocks();
  }

  function handleBlockToggle(button) {
    const card = button.closest('.block-card');
    if (!card) return;
    const id = card.getAttribute('data-block');
    const block = ensureBlocks().find(function (entry) {
      return entry.id === id;
    });
    if (!block) return;
    block.enabled = !block.enabled;
    renderBlocks();
    sendBlocks();
  }

  function handleOptionToggle(button) {
    const card = button.closest('.block-card');
    if (!card) return;
    const id = card.getAttribute('data-block');
    const key = button.getAttribute('data-option');
    const block = ensureBlocks().find(function (entry) {
      return entry.id === id;
    });
    if (!block || !key || !Object.prototype.hasOwnProperty.call(block.options, key)) return;
    block.options[key] = !block.options[key];
    renderBlocks();
    sendBlocks();
  }

  function handleRecentCount() {
    if (!el.recentCount) return;
    const block = ensureBlocks().find(function (entry) {
      return entry.id === 'context_hero';
    });
    if (!block) return;
    const value = clampRecent(el.recentCount.value);
    block.options.recent_count = value;
    el.recentCount.value = String(value);
    sendBlocks();
  }

  function handleAccentHexCommit() {
    if (!el.accentHex) return;
    const raw = el.accentHex.value;
    if (!raw || !String(raw).trim()) {
      el.accentHex.setAttribute('aria-invalid', 'false');
      applyAccent(null);
      return;
    }
    if (!isValidHex(raw)) {
      el.accentHex.setAttribute('aria-invalid', 'true');
      showError('Цвет задаётся в формате #rrggbb.');
      return;
    }
    el.accentHex.setAttribute('aria-invalid', 'false');
    applyAccent(raw);
  }

  function handleFontScale() {
    if (!el.font) return;
    const percent = snapPercent(Number(el.font.value), 85, 140, FONT_STEP_PERCENT, 100);
    setRange(el.font, el.fontBadge, percent, '%');
    if (el.fontReset) el.fontReset.disabled = percent === 100;
    queueRangePatch({ font_scale: percent / 100 });
  }

  function handleOpacity() {
    if (!el.opacity) return;
    const percent = snapPercent(Number(el.opacity.value), 60, 100, OPACITY_STEP_PERCENT, 100);
    setRange(el.opacity, el.opacityBadge, percent, '%');
    queueRangePatch({ opacity: percent / 100 });
  }

  function handleCornerRadius() {
    if (!el.corner) return;
    const value = snapCorner(Number(el.corner.value));
    setRange(el.corner, el.cornerBadge, value, 'px');
    queueRangePatch({ corner_radius: value });
  }

  async function handleExport() {
    let json = null;
    const hasExport = hasBridge() && typeof bridge.exportAppearance === 'function';
    try {
      if (hasExport) {
        const result = await bridge.exportAppearance();
        if (result && result.ok && typeof result.json === 'string') json = result.json;
      }
    } catch (error) {
      json = null;
    }
    if (!json && hasExport) {
      setJsonStatus('Не удалось получить JSON', true);
      return;
    }
    if (!json) {
      try {
        json = JSON.stringify(appearancePayload(), null, 2);
      } catch (error) {
        setJsonStatus('Не удалось получить JSON', true);
        return;
      }
    }
    const ok = await writeClipboardText(json);
    if (ok) {
      setJsonStatus('JSON скопирован в буфер обмена', false);
    } else {
      if (el.importArea) el.importArea.value = json;
      setJsonStatus('Скопируйте JSON из поля ниже', false);
    }
  }

  async function handleImport() {
    const text = el.importArea ? el.importArea.value : '';
    if (!text || !String(text).trim()) {
      setJsonStatus('Вставьте JSON пресета', true);
      return;
    }
    const hasImport = hasBridge() && typeof bridge.importAppearance === 'function';
    if (hasImport) {
      try {
        const result = await bridge.importAppearance(text);
        if (result && result.ok) {
          if (result.snapshot && typeof result.snapshot === 'object') render(result.snapshot);
          setJsonStatus('Пресет применён', false);
          return;
        }
        setJsonStatus((result && result.error) || 'Некорректный JSON', true);
        return;
      } catch (error) {
        setJsonStatus('Не удалось применить пресет', true);
        return;
      }
    }
    // Fallback for an older bridge: parse and patch the known keys ourselves.
    let parsed;
    try {
      parsed = JSON.parse(text);
    } catch (error) {
      setJsonStatus('Некорректный JSON', true);
      return;
    }
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      setJsonStatus('Некорректный JSON', true);
      return;
    }
    const patch = {};
    if (THEMES.indexOf(parsed.theme) !== -1) patch.theme = parsed.theme;
    if (VARIANTS.indexOf(parsed.classic_variant) !== -1) patch.classic_variant = parsed.classic_variant;
    if (parsed.custom_accent === null || isValidHex(parsed.custom_accent)) {
      patch.custom_accent = normalizeHex(parsed.custom_accent);
    }
    if (Number.isFinite(Number(parsed.opacity))) patch.opacity = clamp(parsed.opacity, 0.6, 1, 1);
    if (Number.isFinite(Number(parsed.corner_radius))) patch.corner_radius = snapCorner(parsed.corner_radius);
    if (Number.isFinite(Number(parsed.font_scale))) {
      patch.font_scale = clamp(parsed.font_scale, 0.85, 1.4, 1);
    }
    if (DENSITIES.indexOf(parsed.density) !== -1) patch.density = parsed.density;
    if (MODES.indexOf(parsed.preset_mode) !== -1) patch.preset_mode = parsed.preset_mode;
    if (Array.isArray(parsed.blocks)) patch.blocks = normalizeBlocks(parsed.blocks);
    if (!Object.keys(patch).length) {
      setJsonStatus('В JSON нет известных полей', true);
      return;
    }
    if (patch.blocks) state.blocks = normalizeBlocks(patch.blocks);
    setJsonStatus('Пресет применён', false);
    applyPatch(patch);
  }

  /** Two-step reset: the destructive action asks once, in place, then forgets. */
  const RESET_CONFIRM_MS = 4000;
  const RESET_LABEL = 'Сбросить всё';
  const RESET_CONFIRM_LABEL = 'Точно сбросить?';
  let resetConfirmTimer = null;

  function disarmReset() {
    if (resetConfirmTimer) {
      clearTimeout(resetConfirmTimer);
      resetConfirmTimer = null;
    }
    if (!el.reset) return;
    el.reset.classList.remove('is-confirming');
    el.reset.textContent = RESET_LABEL;
  }

  function armReset() {
    if (!el.reset) return;
    el.reset.classList.add('is-confirming');
    el.reset.textContent = RESET_CONFIRM_LABEL;
    if (resetConfirmTimer) clearTimeout(resetConfirmTimer);
    resetConfirmTimer = setTimeout(disarmReset, RESET_CONFIRM_MS);
  }

  function resetAppearance() {
    if (!bridgeReady() || !el.reset) return;
    disarmReset();
    el.reset.disabled = true;
    queue = queue
      .then(function () {
        return bridge.resetAppearance();
      })
      .then(function (snapshot) {
        if (looksLikeSnapshot(snapshot)) render(snapshot);
        setJsonStatus('', false);
      })
      .catch(function () {
        showError('Не удалось сбросить внешний вид.');
      })
      .then(function () {
        el.reset.disabled = false;
      });
  }

  // --- event wiring --------------------------------------------------------

  function bindEvents() {
    el.presets.forEach(function (button) {
      button.addEventListener('click', guard(function () {
        const id = button.getAttribute('data-preset');
        if (id) applyPreset(id);
      }));
    });

    el.themes.forEach(function (button) {
      button.addEventListener('click', guard(function () {
        const theme = button.getAttribute('data-theme-value');
        if (THEMES.indexOf(theme) === -1) return;
        selectTheme(theme);
        applyPatch({ theme: theme });
      }));
    });

    el.variants.forEach(function (button) {
      button.addEventListener('click', guard(function () {
        const variant = button.getAttribute('data-variant-value');
        if (VARIANTS.indexOf(variant) === -1) return;
        selectVariant(variant);
        applyPatch({ classic_variant: variant });
      }));
    });

    el.accentSwatches.forEach(function (button) {
      button.addEventListener('click', guard(function () {
        const accent = button.getAttribute('data-accent');
        if (ACCENTS.indexOf(accent) === -1) return;
        applyAccent(accent);
      }));
    });

    if (el.accentColor) {
      el.accentColor.addEventListener('input', guard(function () {
        const value = normalizeHex(el.accentColor.value);
        if (!value) return;
        renderAccent(value);
        queueRangePatch({ custom_accent: value });
      }));
      el.accentColor.addEventListener('change', guard(flushRangePatch));
    }

    if (el.accentHex) {
      el.accentHex.addEventListener('input', guard(function () {
        const raw = String(el.accentHex.value || '').trim();
        el.accentHex.setAttribute(
          'aria-invalid',
          raw && !isValidHex(raw) ? 'true' : 'false',
        );
      }));
      el.accentHex.addEventListener('change', guard(handleAccentHexCommit));
      el.accentHex.addEventListener('keydown', guard(function (event) {
        if (event.key !== 'Enter') return;
        event.preventDefault();
        handleAccentHexCommit();
      }));
    }

    if (el.accentReset) {
      el.accentReset.addEventListener('click', guard(function () {
        applyAccent(null);
      }));
    }

    if (el.font) {
      el.font.addEventListener('input', guard(handleFontScale));
      el.font.addEventListener('change', guard(flushRangePatch));
    }

    if (el.fontReset) {
      el.fontReset.addEventListener('click', guard(function () {
        setRange(el.font, el.fontBadge, 100, '%');
        el.fontReset.disabled = true;
        applyPatch({ font_scale: 1 });
      }));
    }

    if (el.opacity) {
      el.opacity.addEventListener('input', guard(handleOpacity));
      el.opacity.addEventListener('change', guard(flushRangePatch));
    }

    if (el.corner) {
      el.corner.addEventListener('input', guard(handleCornerRadius));
      el.corner.addEventListener('change', guard(flushRangePatch));
    }

    el.densities.forEach(function (button) {
      button.addEventListener('click', guard(function () {
        const density = button.getAttribute('data-density');
        if (DENSITIES.indexOf(density) === -1) return;
        selectDensity(density);
        applyPatch({ density: density });
      }));
    });

    el.modes.forEach(function (button) {
      button.addEventListener('click', guard(function () {
        const mode = button.getAttribute('data-mode');
        if (MODES.indexOf(mode) === -1) return;
        selectMode(mode);
        applyPatch({ preset_mode: mode });
      }));
    });

    if (el.blockList) {
      el.blockList.addEventListener('click', guard(function (event) {
        const target = event.target;
        if (!target || typeof target.closest !== 'function') return;
        const move = target.closest('.block-move');
        if (move && el.blockList.contains(move)) {
          handleBlockMove(move);
          return;
        }
        const blockToggle = target.closest('[data-block-toggle]');
        if (blockToggle) {
          handleBlockToggle(blockToggle);
          return;
        }
        const optionToggle = target.closest('[data-option]');
        if (optionToggle) handleOptionToggle(optionToggle);
      }));
    }

    if (el.recentCount) {
      el.recentCount.addEventListener('change', guard(handleRecentCount));
      el.recentCount.addEventListener('keydown', guard(function (event) {
        if (event.key !== 'Enter') return;
        event.preventDefault();
        handleRecentCount();
      }));
    }

    if (el.exportBtn) el.exportBtn.addEventListener('click', function () { handleExport(); });
    if (el.importBtn) el.importBtn.addEventListener('click', function () { handleImport(); });

    if (el.alwaysOnTop) {
      el.alwaysOnTop.addEventListener('click', guard(function () {
        const next = !state.alwaysOnTop;
        state.alwaysOnTop = next;
        setSwitch(el.alwaysOnTop, next);
        applyPatch({ alwaysOnTop: next });
      }));
    }

    if (el.openAtLogin) {
      el.openAtLogin.addEventListener('click', guard(function () {
        const next = !state.openAtLogin;
        state.openAtLogin = next;
        setSwitch(el.openAtLogin, next);
        applyPatch({ openAtLogin: next });
      }));
    }

    if (el.notifEnabled) {
      el.notifEnabled.addEventListener('click', guard(function () {
        const next = normalizeNotifications(state.notifications);
        setNotificationFlag('enabled', next.enabled === false);
      }));
    }

    [
      ['context_red', el.notifContextRed],
      ['zcode_voucher', el.notifZcodeVoucher],
      ['quota_5h_reset', el.notifQuotaReset],
    ].forEach(function (pair) {
      const key = pair[0];
      const button = pair[1];
      if (!button) return;
      button.addEventListener('click', guard(function () {
        const next = normalizeNotifications(state.notifications);
        if (next.enabled === false) return;
        setNotificationFlag(key, next[key] === false);
      }));
    });

    if (el.notifTest) {
      el.notifTest.addEventListener('click', guard(handleTestNotification));
    }

    if (el.reset) {
      el.reset.addEventListener('click', guard(function () {
        if (!el.reset.classList.contains('is-confirming')) {
          armReset();
          return;
        }
        resetAppearance();
      }));
    }

    bindSyncEvents();
  }

  function showUnavailable() {
    if (el.notice) {
      el.notice.textContent = 'Настройки доступны только в приложении.';
      el.notice.classList.remove('hidden');
    }
    document.body.classList.add('is-disabled');
    // Tabs stay usable so both sections can still be read.
    Array.prototype.forEach.call(
      document.querySelectorAll('button, input, textarea'),
      function (node) {
        if (node.getAttribute('role') === 'tab') return;
        node.disabled = true;
      },
    );
  }

  // --- bootstrap -----------------------------------------------------------

  function start() {
    bindTabs();
    if (!bridgeReady()) {
      showUnavailable();
      return;
    }
    bindEvents();
    Promise.resolve()
      .then(function () {
        return bridge.getSettings();
      })
      .then(function (snapshot) {
        if (snapshot) render(snapshot);
      })
      .catch(function () {
        showError('Не удалось загрузить настройки.');
      });
    if (typeof bridge.onAppearanceUpdate === 'function') {
      try {
        bridge.onAppearanceUpdate(function (snapshot) {
          try {
            render(snapshot);
          } catch (error) {
            showError('Не удалось обновить настройки.');
          }
        });
      } catch (error) {
        showError('Не удалось подписаться на изменения.');
      }
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start, { once: true });
  } else {
    start();
  }
})();
