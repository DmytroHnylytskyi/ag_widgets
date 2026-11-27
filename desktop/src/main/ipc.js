'use strict';

const { app, ipcMain, shell, nativeTheme } = require('electron');
const { APP_DIR } = require('./paths');
const { readAllState } = require('./storage');
const collector = require('./collector');
const zcodeAuth = require('./zcode-auth');
const pairing = require('./pairing');
const windowApi = require('./window');
const appearance = require('./appearance');
const notifications = require('./notifications');
const { copyTextToClipboard } = require('./clipboard');
const { logError } = require('./logger');
const { refreshMenu, showContextMenu, loginItemOptions } = require('./tray');

const ZCODE_WEB_URL = 'https://zcode.z.ai/';

/** Appearance keys that live in `appearance.json` (window flags do not). */
const APPEARANCE_KEYS = [
  'theme',
  'classic_variant',
  'custom_accent',
  'corner_radius',
  'density',
  'preset_mode',
  'blocks',
  'font_scale',
  'opacity',
];

/** Boolean notification toggles the renderer may change. */
const NOTIFICATION_KEYS = ['enabled', 'context_red', 'zcode_voucher', 'quota_5h_reset'];

/** Fixed enum of external targets; the renderer can never pass a raw path or URL. */
const EXTERNAL_ACTIONS = {
  zcode: () => shell.openExternal(ZCODE_WEB_URL),
  'config-dir': () => shell.openPath(APP_DIR),
};

/** Windows dark mode flag; never throws. */
function systemPrefersDark() {
  try {
    return Boolean(nativeTheme && nativeTheme.shouldUseDarkColors);
  } catch {
    return false;
  }
}

/**
 * Single source of truth for the settings payload shared by the widget and the
 * Settings window. Every Electron call is guarded so a half-initialised app
 * still answers with sane defaults instead of throwing.
 */
function buildSettingsSnapshot() {
  const settings = appearance.loadAppearance();
  const systemDark = systemPrefersDark();

  let alwaysOnTop = false;
  try {
    const window = windowApi.getWindow();
    alwaysOnTop = Boolean(window && window.isAlwaysOnTop());
  } catch (error) {
    logError('settings-snapshot-always-on-top', error);
  }

  let openAtLogin = false;
  try {
    openAtLogin = Boolean(app.getLoginItemSettings(loginItemOptions()).openAtLogin);
  } catch (error) {
    logError('settings-snapshot-login-item', error);
  }

  let notificationPrefs = {
    enabled: true,
    context_red: true,
    zcode_voucher: true,
    quota_5h_reset: true,
  };
  try {
    notificationPrefs = notifications.getPublicPreferences();
  } catch (error) {
    logError('settings-snapshot-notifications', error);
  }

  return {
    appearance: settings,
    effectiveTheme: appearance.resolveEffectiveTheme(settings, systemDark),
    systemDark,
    alwaysOnTop,
    openAtLogin,
    /** Notification toggles only; dedup state never leaves main. */
    notifications: notificationPrefs,
    /** Size constraints of the current form factor (used by the Settings window). */
    bounds: appearance.formFactorBounds(settings.preset_mode),
    /** Built-in presets in display order, without their patches. */
    presets: appearance.PRESET_IDS.map((id) => ({
      id,
      label: appearance.BUILT_IN_PRESETS[id].label,
      hint: appearance.BUILT_IN_PRESETS[id].hint,
    })),
  };
}

/** Reject IPC that does not originate from this app's own main window. */
function fromMainWindow(handler) {
  return (event, ...args) => {
    const window = windowApi.getWindow();
    if (!window || event.sender !== window.webContents) {
      return { ok: false, error: 'Untrusted IPC sender' };
    }
    return handler(event, ...args);
  };
}

/** Every trusted webContents: the widget and the Settings window. */
function trustedWebContents() {
  const senders = [];
  try {
    const main = windowApi.getWindow();
    if (main) senders.push(main.webContents);
  } catch {
    // A destroyed window simply contributes nothing.
  }
  try {
    const settings = typeof windowApi.getSettingsWindow === 'function'
      ? windowApi.getSettingsWindow()
      : null;
    if (settings) senders.push(settings.webContents);
  } catch {
    // Same as above for the Settings window.
  }
  return senders;
}

/** Settings channels are allowed from either of the app's own windows. */
function fromTrustedWindow(handler) {
  return (event, ...args) => {
    const senders = trustedWebContents();
    if (!senders.some((sender) => sender === event.sender)) {
      return { ok: false, error: 'Untrusted IPC sender' };
    }
    return handler(event, ...args);
  };
}

/** Register every renderer -> main channel. */
function registerIpc() {
  ipcMain.handle('agw:get-state', fromMainWindow(() => readAllState()));

  ipcMain.handle('agw:minimize', fromMainWindow(() => windowApi.minimizeWindow()));

  ipcMain.handle(
    'agw:refresh',
    fromMainWindow(async () => {
      const result = await collector.runCollector({ force: true });
      windowApi.pushState(readAllState());
      return result;
    }),
  );

  ipcMain.handle(
    'agw:zcode-login',
    fromMainWindow(async () => {
      const result = await zcodeAuth.zcodeLogin();
      windowApi.pushState(readAllState());
      refreshMenu();
      return result;
    }),
  );
  ipcMain.handle('agw:zcode-cancel', fromMainWindow(() => zcodeAuth.cancelZCodeLogin()));
  ipcMain.handle(
    'agw:zcode-logout',
    fromMainWindow(async () => {
      const result = await zcodeAuth.zcodeLogout();
      windowApi.pushState(readAllState());
      refreshMenu();
      return result;
    }),
  );

  ipcMain.handle('agw:quit', fromMainWindow(() => app.quit()));
  ipcMain.handle('agw:menu', fromMainWindow(() => showContextMenu()));

  ipcMain.handle(
    'agw:move-window',
    fromMainWindow((_event, dx, dy) => {
      if (!Number.isFinite(dx) || !Number.isFinite(dy)) {
        return { ok: false, error: 'Invalid offset' };
      }
      windowApi.moveWindow(dx, dy);
      return { ok: true };
    }),
  );

  ipcMain.handle('agw:move-end', fromMainWindow(() => windowApi.saveCurrentWindowBounds()));

  ipcMain.handle(
    'agw:open-action',
    fromMainWindow((_event, action) => {
      const handler =
        typeof action === 'string' && Object.prototype.hasOwnProperty.call(EXTERNAL_ACTIONS, action)
          ? EXTERNAL_ACTIONS[action]
          : null;
      if (!handler) return { ok: false, error: 'Unsupported action' };
      handler();
      return { ok: true };
    }),
  );

  // --- Settings (widget + Settings window) ---------------------------------

  ipcMain.handle(
    'agw:get-settings',
    fromTrustedWindow(() => buildSettingsSnapshot()),
  );

  ipcMain.handle(
    'agw:copy-text',
    fromTrustedWindow((_event, text) => copyTextToClipboard(text)),
  );

  ipcMain.handle(
    'agw:open-settings',
    fromTrustedWindow(() => {
      windowApi.openSettingsWindow();
      return { ok: true };
    }),
  );

  ipcMain.handle(
    'agw:test-notification',
    fromTrustedWindow(() => notifications.sendTestNotification()),
  );

  ipcMain.handle(
    'agw:update-settings',
    fromTrustedWindow((_event, patch) => {
      if (!patch || typeof patch !== 'object' || Array.isArray(patch)) {
        return { ok: false, error: 'Invalid patch' };
      }

      if (APPEARANCE_KEYS.some((key) => Object.prototype.hasOwnProperty.call(patch, key))) {
        try {
          appearance.saveAppearance(patch);
        } catch (error) {
          logError('saveAppearance', error);
        }
      }

      if (typeof patch.alwaysOnTop === 'boolean') {
        windowApi.setAlwaysOnTop(patch.alwaysOnTop);
      }

      if (typeof patch.openAtLogin === 'boolean') {
        try {
          app.setLoginItemSettings({ openAtLogin: patch.openAtLogin, ...loginItemOptions() });
        } catch (error) {
          logError('setLoginItemSettings', error);
        }
      }

      if (
        patch.notifications &&
        typeof patch.notifications === 'object' &&
        !Array.isArray(patch.notifications)
      ) {
        const clean = {};
        for (const key of NOTIFICATION_KEYS) {
          if (typeof patch.notifications[key] === 'boolean') clean[key] = patch.notifications[key];
        }
        if (Object.keys(clean).length) {
          try {
            notifications.updatePreferences(clean);
          } catch (error) {
            logError('updateNotifications', error);
          }
        }
      }

      const snapshot = buildSettingsSnapshot();
      windowApi.applyAppearanceToWindows(snapshot);
      refreshMenu();
      return snapshot;
    }),
  );

  ipcMain.handle(
    'agw:reset-appearance',
    fromTrustedWindow(() => {
      try {
        appearance.resetAppearance();
      } catch (error) {
        logError('resetAppearance', error);
      }
      const snapshot = buildSettingsSnapshot();
      windowApi.applyAppearanceToWindows(snapshot);
      refreshMenu();
      return snapshot;
    }),
  );

  // --- Widget builder (presets, export/import) -----------------------------

  ipcMain.handle(
    'agw:apply-preset',
    fromTrustedWindow((_event, id) => {
      const presetId = typeof id === 'string' ? id.trim() : '';
      const next = presetId ? appearance.applyPreset(presetId) : null;
      if (!next) return { ok: false, error: 'Unknown preset' };
      const snapshot = buildSettingsSnapshot();
      windowApi.applyAppearanceToWindows(snapshot);
      refreshMenu();
      return { ok: true, snapshot };
    }),
  );

  ipcMain.handle(
    'agw:export-appearance',
    fromTrustedWindow(() => ({
      ok: true,
      json: appearance.exportAppearanceJson(appearance.loadAppearance()),
    })),
  );

  ipcMain.handle(
    'agw:import-appearance',
    fromTrustedWindow((_event, json) => {
      const result = appearance.importAppearanceJson(json);
      if (!result.ok) return result;
      try {
        appearance.saveAppearance(result.appearance);
      } catch (error) {
        logError('importAppearance', error);
        return { ok: false, error: 'Не удалось сохранить настройки', code: 'invalid' };
      }
      const snapshot = buildSettingsSnapshot();
      windowApi.applyAppearanceToWindows(snapshot);
      refreshMenu();
      return { ok: true, snapshot };
    }),
  );

  // --- Pairing (Sync tab): ntfy topic/server, E2E key, agw://pair URI ------
  // Electron never touches secrets.json: both channels delegate to
  // collector/pairing.py, which owns the DPAPI-encrypted values.

  ipcMain.handle(
    'agw:pairing-get',
    fromTrustedWindow(() => pairing.getPairingSnapshot()),
  );

  ipcMain.handle(
    'agw:pairing-update',
    fromTrustedWindow((_event, patch) => pairing.updatePairing(patch)),
  );
}

module.exports = {
  registerIpc,
  buildSettingsSnapshot,
  EXTERNAL_ACTIONS,
  copyTextToClipboard,
};
