'use strict';

const fs = require('fs');
const path = require('path');
const { APP_DIR, STATUS_PATH } = require('./paths');
const { readAllState, readJson } = require('./storage');
const zcodeAuth = require('./zcode-auth');

const APP_ROOT = path.join(__dirname, '..', '..');
const ASSETS_DIR = path.join(APP_ROOT, 'assets');
const ZCODE_WEB_URL = 'https://zcode.z.ai/';

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

function toggleAlwaysOnTop(checked) {
  const api = windowApi();
  if (api && typeof api.setAlwaysOnTop === 'function') api.setAlwaysOnTop(checked);
  // The tray owns its own Menu instance; rebuilding it keeps the checkboxes in
  // sync even when the toggle came from the popup context menu.
  refreshMenu();
}

function toggleOpenAtLogin(checked) {
  const electron = electronApi();
  const app = electron && electron.app ? electron.app : null;
  if (!app || typeof app.setLoginItemSettings !== 'function') return;
  app.setLoginItemSettings({ openAtLogin: Boolean(checked), ...loginItemOptions() });
  refreshMenu();
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
  zcodeConnected = false,
  handlers = {},
} = {}) {
  const isWindowMenu = source === 'window';
  const alwaysOnTopChecked = Boolean(alwaysOnTop);
  const openAtLoginChecked = Boolean(openAtLogin);
  const template = [];

  // The popup menu is opened from the visible window itself, so restoring it
  // is meaningless there; the tray only offers the action that applies.
  if (!isWindowMenu && windowVisible !== true) {
    template.push({
      label: 'Показать виджет',
      click: pickHandler(handlers.showWindow || handlers.toggleWindow, showWidget),
    });
  }
  if (isWindowMenu || windowVisible === true) {
    template.push({
      label: 'Свернуть в трей',
      click: pickHandler(handlers.hideWindow || handlers.toggleWindow, hideWidget),
    });
  }

  template.push(
    {
      label: 'Поверх всех окон',
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
      label: 'Запускать при входе',
      type: 'checkbox',
      checked: openAtLoginChecked,
      click: (item) => {
        const handler = pickHandler(handlers.toggleOpenAtLogin, toggleOpenAtLogin);
        const checked =
          item && typeof item.checked === 'boolean' ? item.checked : !openAtLoginChecked;
        return handler(checked);
      },
    },
    { type: 'separator' },
    { label: 'Открыть папку конфига', click: pickHandler(handlers.openConfigDir, openConfigDir) },
    { label: 'Открыть Z.ai', click: pickHandler(handlers.openZCodeWeb, openZCodeWeb) },
  );

  if (zcodeConnected === true) {
    template.push({
      label: 'Выйти из Z.ai',
      click: pickHandler(handlers.logout, logoutZCode),
    });
  }

  template.push({ type: 'separator' }, { label: 'Выход', click: pickHandler(handlers.quit, quit) });
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
    zcodeConnected: isZCodeConnected(),
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
  tray.setToolTip('AG Widgets');

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

module.exports = { createTray, showContextMenu, refreshMenu, buildMenuTemplate };
