'use strict';

const fs = require('fs');
const path = require('path');
const { APP_DIR, STATUS_PATH } = require('./paths');
const { readAllState, readJson } = require('./storage');
const zcodeAuth = require('./zcode-auth');
const appearance = require('./appearance');
const preferences = require('./preferences');
const i18n = require('../shared/i18n.js');

const APP_ROOT = path.join(__dirname, '..', '..');
const ASSETS_DIR = path.join(APP_ROOT, 'assets');
const ZCODE_WEB_URL = 'https://zcode.z.ai/';

/** Dictionary keys for the four window form factors. */
const WINDOW_MODE_KEYS = Object.freeze({
  full: 'tray.modeFull',
  compact: 'tray.modeCompact',
  minibar: 'tray.modeMinibar',
  pill: 'tray.modePill',
});

let tray = null;

/**
 * Resolve Electron lazily: this module must stay importable under plain
 * `node --test`, where the `electron` package may be missing or only export
 * the runtime path instead of the API surface.
 */
function electronApi() {
  try {
    const electron = require('electron');
    return electron && typeof electron === 'object' ? electron : null;
  } catch {
    return null;
  }
}

/** Window helpers are Electron-bound, so they are loaded on first real use. */
function windowApi() {
  try {
    return require('./window');
  } catch {
    return null;
  }
}

/** Notification prefs are pure Node; lazy for symmetry with the other helpers. */
function notificationsApi() {
  try {
    return require('./notifications');
  } catch {
    return null;
  }
}

/** Current notification toggle; `true` when prefs cannot be read. */
function currentNotificationsEnabled() {
  const api = notificationsApi();
  if (!api || typeof api.getPreferences !== 'function') return true;
  try {
    return api.getPreferences().enabled !== false;
  } catch {
    return true;
  }
}

function toggleNotifications(checked) {
  const api = notificationsApi();
  if (api && typeof api.updatePreferences === 'function') {
    try {
      api.updatePreferences({ enabled: Boolean(checked) });
    } catch {
      // A read-only profile must not break the tray menu.
    }
  }
  refreshMenu();
  syncSettingsWindow();
}

function currentWindow() {
  const api = windowApi();
  if (!api || typeof api.getWindow !== 'function') return null;
  try {
    return api.getWindow();
  } catch {
    return null;
  }
}

/** Autostart registration; reads must use the same path/args as writes. */
function loginItemOptions() {
  const electron = electronApi();
  const app = electron && electron.app ? electron.app : null;
  return {
    path: process.execPath,
    // Dev builds need the app directory; packaged builds open themselves.
    args: app && app.isPackaged ? [] : [APP_ROOT],
  };
}

function quit() {
  const electron = electronApi();
  const app = electron && electron.app ? electron.app : null;
  if (app && typeof app.quit === 'function') app.quit();
}

function showWidget() {
  const api = windowApi();
  if (api && typeof api.showWindow === 'function') api.showWindow();
  // Rebuild the tray menu immediately so Windows shows the updated action on the next right-click.
  refreshMenu();
}

function hideWidget() {
  const api = windowApi();
  if (api && typeof api.hideWindow === 'function') api.hideWindow();
  refreshMenu();
}

function refreshMenu() {
  if (!tray || tray.isDestroyed()) return;
  const menu = buildMenu({ source: 'tray' });
  if (menu) tray.setContextMenu(menu);
}

/** ZCode counts as connected only for a verified API session. */
function isZCodeConnected() {
  const status = readJson(STATUS_PATH);
  const zcode = status && status.zcode;
  return Boolean(zcode && zcode.method === 'api' && zcode.auth_ok !== false);
}

/** Persisted form factor; `full` when appearance.json is unreadable. */
function currentPresetMode() {
  try {
    return appearance.loadAppearance().preset_mode;
  } catch {
    return 'full';
  }
}

function syncSettingsWindow() {
  const api = windowApi();
  if (!api || typeof api.pushAppearance !== 'function') return;
  try {
    const { buildSettingsSnapshot } = require('./ipc');
    if (typeof buildSettingsSnapshot === 'function') {
      api.pushAppearance(buildSettingsSnapshot());
    }
  } catch {
    // Plain Node tests load tray.js without Electron.
  }
}

function toggleAlwaysOnTop(checked) {
  const api = windowApi();
  if (api && typeof api.setAlwaysOnTop === 'function') api.setAlwaysOnTop(checked);
  // The tray owns its own Menu instance; rebuilding it keeps the checkboxes in
  // sync even when the toggle came from the popup context menu.
  refreshMenu();
  syncSettingsWindow();
}

function toggleOpenAtLogin(checked) {
  const electron = electronApi();
  const app = electron && electron.app ? electron.app : null;
  if (!app || typeof app.setLoginItemSettings !== 'function') return;
  app.setLoginItemSettings({ openAtLogin: Boolean(checked), ...loginItemOptions() });
  refreshMenu();
  syncSettingsWindow();
}

function logoutZCode() {
  Promise.resolve()
    .then(() => zcodeAuth.zcodeLogout())
    .then(() => {
      const api = windowApi();
      if (api && typeof api.pushState === 'function') api.pushState(readAllState());
      refreshMenu();
    })
    .catch(() => {});
}

/** Open (or focus) the separate Settings window. */
function openSettings() {
  const api = windowApi();
  if (api && typeof api.openSettingsWindow === 'function') api.openSettingsWindow();
}

function openConfigDir() {
  const electron = electronApi();
  const shell = electron && electron.shell ? electron.shell : null;
  if (shell && typeof shell.openPath === 'function') shell.openPath(APP_DIR);
}

function openZCodeWeb() {
  const electron = electronApi();
  const shell = electron && electron.shell ? electron.shell : null;
  if (shell && typeof shell.openExternal === 'function') shell.openExternal(ZCODE_WEB_URL);
}

/** Prefer an injected handler; fall back to the module-internal behaviour. */
function pickHandler(candidate, fallback) {
  return typeof candidate === 'function' ? candidate : fallback;
}

/** Radio submenu switching the widget between window form factors. */
function windowModeSubmenu(presetMode, handlers, lang = 'ru') {
  const active = appearance.normalizePresetMode(presetMode);
  return {
    label: i18n.t('tray.windowMode', null, lang),
    submenu: appearance.PRESET_MODES.map((id) => ({
      label: i18n.t(WINDOW_MODE_KEYS[id], null, lang),
      type: 'radio',
      checked: active === id,
      click: () => pickHandler(handlers.setWindowMode, applyWindowMode)(id),
    })),
  };
}

/**
 * Switch the window form factor from the tray without overwriting the user's
 * theme, accent or block layout. The IPC module is loaded lazily, exactly like
 * `syncSettingsWindow`, so plain Node tests can load this file.
 */
function applyWindowMode(presetMode) {
  const mode = appearance.normalizePresetMode(presetMode);
  appearance.saveAppearance({ preset_mode: mode });
  const api = windowApi();
  try {
    const { buildSettingsSnapshot } = require('./ipc');
    if (typeof buildSettingsSnapshot === 'function' && api) {
      const snapshot = buildSettingsSnapshot();
      if (typeof api.applyAppearanceToWindows === 'function') {
        api.applyAppearanceToWindows(snapshot);
      }
    }
  } catch {
    // Plain Node tests load tray.js without Electron.
  }
  refreshMenu();
}

/**
 * Pure menu template shared by the tray and the popup menu. It never touches
 * the Electron API: state is passed in and clicks go through `handlers`, so
 * the shape can be unit-tested under plain `node --test`.
 */
function buildMenuTemplate({
  source = 'tray',
  windowVisible = true,
  alwaysOnTop = false,
  openAtLogin = false,
  notificationsEnabled = true,
  zcodeConnected = false,
  presetMode = 'full',
  lang = 'ru',
  handlers = {},
} = {}) {
  const isWindowMenu = source === 'window';
  const alwaysOnTopChecked = Boolean(alwaysOnTop);
  const openAtLoginChecked = Boolean(openAtLogin);
  const notificationsChecked = notificationsEnabled !== false;
  const template = [];

  // The popup menu is opened from the visible window itself, so restoring it
  // is meaningless there; the tray only offers the action that applies.
  if (!isWindowMenu && windowVisible !== true) {
    template.push({
      label: i18n.t('tray.show', null, lang),
      click: pickHandler(handlers.showWindow || handlers.toggleWindow, showWidget),
    });
  }
  if (isWindowMenu || windowVisible === true) {
    template.push({
      label: i18n.t('tray.hide', null, lang),
      click: pickHandler(handlers.hideWindow || handlers.toggleWindow, hideWidget),
    });
  }

  template.push(
    {
      label: i18n.t('tray.alwaysOnTop', null, lang),
      type: 'checkbox',
      checked: alwaysOnTopChecked,
      click: (item) => {
        const handler = pickHandler(handlers.toggleAlwaysOnTop, toggleAlwaysOnTop);
        const checked =
          item && typeof item.checked === 'boolean' ? item.checked : !alwaysOnTopChecked;
        return handler(checked);
      },
    },
    {
      label: i18n.t('tray.openAtLogin', null, lang),
      type: 'checkbox',
      checked: openAtLoginChecked,
      click: (item) => {
        const handler = pickHandler(handlers.toggleOpenAtLogin, toggleOpenAtLogin);
        const checked =
          item && typeof item.checked === 'boolean' ? item.checked : !openAtLoginChecked;
        return handler(checked);
      },
    },
    {
      label: i18n.t('tray.notifications', null, lang),
      type: 'checkbox',
      checked: notificationsChecked,
      click: (item) => {
        const handler = pickHandler(handlers.toggleNotifications, toggleNotifications);
        const checked =
          item && typeof item.checked === 'boolean' ? item.checked : !notificationsChecked;
        return handler(checked);
      },
    },
    {
      label: i18n.t('tray.settings', null, lang),
      click: pickHandler(handlers.openSettings, openSettings),
    },
    { type: 'separator' },
  );

  // The window-mode submenu goes strictly after this separator: the existing
  // test pins "Настройки…" immediately before it.
  template.push(windowModeSubmenu(presetMode, handlers, lang));

  template.push(
    {
      label: i18n.t('tray.openConfigDir', null, lang),
      click: pickHandler(handlers.openConfigDir, openConfigDir),
    },
    {
      label: i18n.t('tray.openZcode', null, lang),
      click: pickHandler(handlers.openZCodeWeb, openZCodeWeb),
    },
  );

  if (zcodeConnected === true) {
    template.push({
      label: i18n.t('tray.logout', null, lang),
      click: pickHandler(handlers.logout, logoutZCode),
    });
  }

  template.push(
    { type: 'separator' },
    {
      label: i18n.t('tray.quit', null, lang),
      click: pickHandler(handlers.quit, quit),
    },
  );
  return template;
}

/** Electron Menu for the current window/tray state; null outside Electron. */
function buildMenu(options = {}) {
  const source = options.source === 'window' ? 'window' : 'tray';
  const window = currentWindow();
  const electron = electronApi();
  const app = electron && electron.app ? electron.app : null;

  let openAtLogin = false;
  if (app && typeof app.getLoginItemSettings === 'function') {
    try {
      openAtLogin = Boolean(app.getLoginItemSettings(loginItemOptions()).openAtLogin);
    } catch {
      // Login item settings are unavailable before the app is ready.
    }
  }

  let alwaysOnTop = false;
  let windowVisible = false;
  if (window) {
    try {
      alwaysOnTop = Boolean(window.isAlwaysOnTop());
      windowVisible = Boolean(window.isVisible() && !window.isMinimized());
    } catch {
      // A destroyed window makes the checks throw; keep the defaults.
    }
  }

  const template = buildMenuTemplate({
    source,
    windowVisible,
    alwaysOnTop,
    openAtLogin,
    notificationsEnabled: currentNotificationsEnabled(),
    zcodeConnected: isZCodeConnected(),
    presetMode: currentPresetMode(),
    lang: preferences.resolveCurrentLanguage(),
    handlers: options.handlers || {},
  });

  if (!electron || !electron.Menu || typeof electron.Menu.buildFromTemplate !== 'function') {
    return null;
  }
  return electron.Menu.buildFromTemplate(template);
}

function createTray() {
  const electron = electronApi();
  if (!electron || typeof electron.Tray !== 'function' || !electron.nativeImage) return null;

  const iconPath = path.join(ASSETS_DIR, 'icon.ico');
  const icon = fs.existsSync(iconPath)
    ? electron.nativeImage.createFromPath(iconPath)
    : electron.nativeImage.createEmpty();
  tray = new electron.Tray(icon);
  tray.setToolTip(i18n.t('tray.tooltip', null, preferences.resolveCurrentLanguage()));

  refreshMenu();
  tray.on('click', () => {
    const window = currentWindow();
    if (window && window.isVisible() && !window.isMinimized()) hideWidget();
    else showWidget();
  });
  tray.on('right-click', () => refreshMenu());
  return tray;
}

/** Context menu shown by the widget's title-bar button and right click. */
function showContextMenu() {
  const window = currentWindow();
  if (!window) return;
  const menu = buildMenu({ source: 'window' });
  if (menu) menu.popup({ window });
}

module.exports = {
  createTray,
  showContextMenu,
  refreshMenu,
  buildMenuTemplate,
  applyWindowMode,
  loginItemOptions,
};
