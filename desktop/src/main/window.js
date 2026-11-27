'use strict';

const path = require('path');
const { app, BrowserWindow, screen, session, nativeTheme } = require('electron');
const { windowStatePath } = require('./paths');
const { readAllState, readJson, writeJsonAtomic } = require('./storage');
const { logError } = require('./logger');
const appearance = require('./appearance');
const preferences = require('./preferences');
const i18n = require('../shared/i18n.js');

/** Settings window: two tabs fit without a horizontal scrollbar. */
const SETTINGS_WIDTH = 430;
const SETTINGS_HEIGHT = 620;
/** Gap between the widget and the Settings window. */
const SETTINGS_GAP = 12;
/** Minimum slice of the title bar that must stay on screen while dragging. */
const TITLEBAR_GRAB = 48;
/** Debounce for persisting the window size after a user resize. */
const RESIZE_SAVE_DELAY = 200;
/**
 * Window after a programmatic move during which a spurious `resize` must not
 * reach `window-state.json` (a drag at fractional DPI used to persist +1..2px).
 */
const MOVE_RESIZE_GUARD_MS = 150;
const ASSETS_DIR = path.join(__dirname, '..', '..', 'assets');

/**
 * Themes that sit on the Windows acrylic material. Every other theme is painted
 * with a solid `backgroundColor`, which is what keeps the dense themes free of
 * black acrylic artifacts on Windows 10 and with transparency effects disabled.
 */
const ACRYLIC_THEMES = new Set(['glass-dark', 'glass-light', 'classic-dark', 'classic-light']);

let mainWindow = null;
let settingsWindow = null;
let moveSaveTimer = null;
let resizeSaveTimer = null;
/** Timestamp until which `resize` events are treated as a side effect of a move. */
let ignoreResizeUntil = 0;
/**
 * Size captured for the duration of one drag. `getBounds()` comes back rounded
 * at fractional display scale, so feeding a fresh reading into every move lets
 * the error accumulate (+1..2px per animation frame at 125%/150%). The drag
 * therefore reuses the size it started with (D-01).
 */
let dragSize = null;
/** Form factor currently applied to the widget; null until the first apply. */
let currentMode = null;

/** Windows dark mode flag; never throws outside Electron. */
function systemPrefersDark() {
  try {
    return Boolean(nativeTheme && nativeTheme.shouldUseDarkColors);
  } catch {
    return false;
  }
}

/** Current snapshot of the appearance part of the settings. */
function currentAppearance() {
  const settings = appearance.loadAppearance();
  return {
    settings,
    effectiveTheme: appearance.resolveEffectiveTheme(settings, systemPrefersDark()),
  };
}

/** Whether a resolved theme uses the acrylic material. */
function isAcrylicTheme(effectiveTheme) {
  return ACRYLIC_THEMES.has(effectiveTheme);
}

/** BrowserWindow base colors: acrylic needs a fully transparent base color. */
function windowBackgroundOptions(effectiveTheme) {
  if (isAcrylicTheme(effectiveTheme)) {
    return { backgroundColor: '#00000000', backgroundMaterial: 'acrylic' };
  }
  return { backgroundColor: appearance.windowBackgroundColor(effectiveTheme) };
}

/** Clamp a desired size into the form factor limits of `mode`. */
function clampSizeToMode(mode, width, height) {
  const limits = appearance.formFactorBounds(mode);
  const toPx = (value, fallback) =>
    Number.isFinite(value) ? Math.round(value) : Math.round(fallback);
  return {
    width: Math.min(limits.maxWidth, Math.max(limits.minWidth, toPx(width, limits.width))),
    height: Math.min(limits.maxHeight, Math.max(limits.minHeight, toPx(height, limits.height))),
  };
}

/** Per-mode window sizes from `window-state.json`, revalidated on every read. */
function normalizeModeBounds(raw) {
  const source = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : {};
  const result = {};
  for (const mode of appearance.PRESET_MODES) {
    const saved = source[mode] && typeof source[mode] === 'object' ? source[mode] : {};
    result[mode] = clampSizeToMode(mode, saved.width, saved.height);
  }
  return result;
}

/** Form factor currently applied to the widget; disk value before the first apply. */
function activeMode() {
  if (currentMode) return currentMode;
  try {
    return currentAppearance().settings.preset_mode;
  } catch {
    return appearance.DEFAULT_APPEARANCE.preset_mode;
  }
}

function loadWindowState() {
  const saved = readJson(windowStatePath());
  const source = saved && typeof saved === 'object' && !Array.isArray(saved) ? saved : {};
  return {
    x: typeof source.x === 'number' ? source.x : undefined,
    y: typeof source.y === 'number' ? source.y : undefined,
    alwaysOnTop: typeof source.alwaysOnTop === 'boolean' ? source.alwaysOnTop : true,
    modeBounds: normalizeModeBounds(source.modeBounds),
  };
}

/** Persist window state; filesystem hiccups must never take the app down. */
function saveWindowState(extra = {}) {
  const window = getWindow();
  if (!window) return;
  try {
    const previous = loadWindowState();
    const [x, y] = window.getPosition();
    const [width, height] = window.getSize();
    const mode = activeMode();
    writeJsonAtomic(windowStatePath(), {
      ...previous,
      ...extra,
      x,
      y,
      alwaysOnTop: window.isAlwaysOnTop(),
      modeBounds: {
        ...previous.modeBounds,
        ...(extra.modeBounds || {}),
        [mode]: { width, height },
      },
    });
  } catch (error) {
    logError('window-state', error);
  }
}

function clearPendingMoveSave() {
  if (!moveSaveTimer) return;
  clearTimeout(moveSaveTimer);
  moveSaveTimer = null;
}

function clearPendingResizeSave() {
  if (!resizeSaveTimer) return;
  clearTimeout(resizeSaveTimer);
  resizeSaveTimer = null;
}

/** Persist the size 200 ms after the last resize event. */
function scheduleResizeSave() {
  clearTimeout(resizeSaveTimer);
  resizeSaveTimer = setTimeout(() => {
    resizeSaveTimer = null;
    saveWindowState();
  }, RESIZE_SAVE_DELAY);
}

/** Persist the current bounds immediately, skipping the debounced saves. */
function saveCurrentWindowBounds() {
  clearPendingMoveSave();
  clearPendingResizeSave();
  // The drag is over: the next one captures the size that is live by then.
  dragSize = null;
  saveWindowState();
}

/** Idempotent flush for `before-quit`; safe when the window is already gone. */
function flushWindowState() {
  saveCurrentWindowBounds();
}

function isPositionOnAnyDisplay(x, y, width, height) {
  if (typeof x !== 'number' || typeof y !== 'number') return false;
  return screen.getAllDisplays().some((display) => {
    const area = display.workArea;
    return (
      x + width > area.x + 40 &&
      x < area.x + area.width - 40 &&
      y + height > area.y + 40 &&
      y < area.y + area.height - 40
    );
  });
}

/** Keep a restored window fully inside a display after monitor changes. */
function clampToWorkArea(x, y, width, height) {
  const areas = screen.getAllDisplays().map((display) => display.workArea);
  const primary = screen.getPrimaryDisplay().workArea;
  const target =
    areas.find(
      (area) =>
        x + width > area.x + 40 &&
        x < area.x + area.width - 40 &&
        y + height > area.y + 40 &&
        y < area.y + area.height - 40,
    ) || primary;
  // Integer screen coordinates only: fractional DIP positions saved to
  // `window-state.json` are re-rounded by Electron on restore.
  return {
    x: Math.round(Math.max(target.x, Math.min(x, target.x + target.width - width))),
    y: Math.round(Math.max(target.y, Math.min(y, target.y + target.height - height))),
  };
}

function pushState(state) {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  mainWindow.webContents.send('agw:state', state || readAllState());
}

function createWindow() {
  const saved = loadWindowState();
  const { settings: currentSettings, effectiveTheme } = currentAppearance();
  const mode = currentSettings.preset_mode;
  const limits = appearance.formFactorBounds(mode);
  const stored = saved.modeBounds[mode];
  const size = clampSizeToMode(
    mode,
    stored ? stored.width : limits.width,
    stored ? stored.height : limits.height,
  );
  const display = screen.getPrimaryDisplay().workArea;
  const defaultPosition = clampToWorkArea(
    display.x + display.width - size.width - 24,
    display.y + 24,
    size.width,
    size.height,
  );
  const position = isPositionOnAnyDisplay(saved.x, saved.y, size.width, size.height)
    ? clampToWorkArea(saved.x, saved.y, size.width, size.height)
    : defaultPosition;

  mainWindow = new BrowserWindow({
    width: size.width,
    height: size.height,
    x: position.x,
    y: position.y,
    frame: false,
    title: 'AG Widgets',
    resizable: true,
    minimizable: true,
    maximizable: false,
    fullscreenable: false,
    icon: path.join(ASSETS_DIR, 'icon-256.png'),
    alwaysOnTop: saved.alwaysOnTop,
    hasShadow: true,
    // The glass/classic themes sit on the Windows acrylic material (transparent
    // base color + material); the dense themes (graphite/paper/oled) use their
    // own solid base color so a black acrylic frame can never show through.
    transparent: false,
    ...windowBackgroundOptions(effectiveTheme),
    show: false,
    webPreferences: {
      preload: path.join(__dirname, '..', '..', 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });

  if (saved.alwaysOnTop) mainWindow.setAlwaysOnTop(true, 'floating');

  // Size limits and the restored size always follow the saved form factor.
  applyFormFactor(mode);

  // Window opacity is part of the appearance settings; Electron may refuse a
  // negative or unsupported value on some platforms, so it never propagates.
  try {
    mainWindow.setOpacity(currentSettings.opacity);
  } catch (error) {
    logError('setOpacity', error);
  }

  // Hardening: the widget never navigates away, opens no child windows and
  // grants no permissions to web content.
  mainWindow.webContents.on('will-navigate', (event) => event.preventDefault());
  mainWindow.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  session.defaultSession.setPermissionRequestHandler((_webContents, _permission, callback) =>
    callback(false),
  );
  session.defaultSession.setPermissionCheckHandler(() => false);

  // The widget is resizable, but only inside its current form factor. The OS
  // already blocks min/max violations; a forced size (DPI change, programmatic
  // resize) is clamped back here, and every resize is persisted debounced.
  let sizeGuard = false;
  const enforceFormFactor = () => {
    if (sizeGuard) return;
    const window = getWindow();
    if (!window || window.isMinimized()) return;
    const mode = activeMode();
    const limits = appearance.formFactorBounds(mode);
    const [width, height] = window.getSize();
    if (width <= 0 || height <= 0) return;
    if (
      width >= limits.minWidth &&
      width <= limits.maxWidth &&
      height >= limits.minHeight &&
      height <= limits.maxHeight
    ) {
      return;
    }
    const target = clampSizeToMode(mode, width, height);
    sizeGuard = true;
    window.setSize(target.width, target.height, false);
    setTimeout(() => {
      sizeGuard = false;
    }, 60);
  };
  mainWindow.on('resize', () => {
    enforceFormFactor();
    if (Date.now() < ignoreResizeUntil) return;
    // A real user resize (or a form-factor change) makes the cached drag size
    // stale, so the next drag re-reads it once.
    dragSize = null;
    scheduleResizeSave();
  });
  // `resized` fires once the user lets go of the frame; both paths share the
  // same 200 ms debounce so only the final size reaches the disk.
  mainWindow.on('resized', () => {
    if (Date.now() < ignoreResizeUntil) return;
    dragSize = null;
    scheduleResizeSave();
  });
  mainWindow.on('maximize', () => mainWindow.unmaximize());
  mainWindow.on('enter-full-screen', () => mainWindow.setFullScreen(false));

  mainWindow.once('ready-to-show', () => {
    mainWindow.show();
  });

  // Re-read state the moment the widget becomes visible again (tray restore).
  mainWindow.on('show', () => pushState(readAllState()));

  mainWindow.on('moved', () => {
    clearTimeout(moveSaveTimer);
    moveSaveTimer = setTimeout(() => {
      moveSaveTimer = null;
      saveWindowState();
    }, 250);
  });

  // Close-to-quit: closing the window (× / Alt+F4 / OS close) destroys it and
  // `window-all-closed` quits the app. The Settings window belongs to the
  // widget, so it is destroyed here too; otherwise the process would linger.
  // The minimize button and the tray menu keep the widget alive instead.
  mainWindow.on('closed', () => {
    clearPendingMoveSave();
    clearPendingResizeSave();
    dragSize = null;
    mainWindow = null;
    closeSettingsWindow();
  });

  // A frozen renderer must not leave an unusable frame forever: give it 15s to
  // recover before quitting, and cancel the timer on `responsive`.
  let unresponsiveTimer = null;
  mainWindow.on('unresponsive', () => {
    if (unresponsiveTimer) return;
    unresponsiveTimer = setTimeout(() => {
      logError('unresponsive', new Error('Renderer unresponsive for 15s'));
      app.quit();
    }, 15000);
  });
  mainWindow.on('responsive', () => {
    clearTimeout(unresponsiveTimer);
    unresponsiveTimer = null;
  });
  mainWindow.webContents.on('render-process-gone', (_event, details) => {
    const reason = details && details.reason ? details.reason : 'unknown';
    logError('render-process-gone', new Error(`Renderer process gone: ${reason}`));
    app.quit();
  });

  mainWindow.loadFile(path.join(__dirname, '..', '..', 'index.html'));
  return mainWindow;
}

/**
 * Place the Settings window next to the widget on its current display: to the
 * right when it fits the display work area, otherwise to the left, clamped.
 */
function settingsWindowPosition() {
  const main = getWindow();
  const mainBounds = main ? main.getBounds() : null;
  const display = mainBounds
    ? screen.getDisplayMatching(mainBounds)
    : screen.getPrimaryDisplay();
  const area = display.workArea;
  const full = appearance.formFactorBounds('full');
  const bounds = mainBounds || {
    x: area.x + area.width - full.width - 24,
    y: area.y + 24,
    width: full.width,
    height: full.height,
  };
  const rightX = bounds.x + bounds.width + SETTINGS_GAP;
  const leftX = bounds.x - SETTINGS_WIDTH - SETTINGS_GAP;
  const fitsRight = rightX + SETTINGS_WIDTH <= area.x + area.width;
  const preferredX = fitsRight ? rightX : leftX;
  return {
    x: Math.round(Math.max(area.x, Math.min(preferredX, area.x + area.width - SETTINGS_WIDTH))),
    y: Math.round(Math.max(area.y, Math.min(bounds.y, area.y + area.height - SETTINGS_HEIGHT))),
  };
}

/**
 * Open the separate Settings window next to the widget. A second call focuses
 * the existing window instead of creating a duplicate.
 */
function openSettingsWindow() {
  const existing = getSettingsWindow();
  if (existing) {
    if (existing.isMinimized()) existing.restore();
    existing.show();
    existing.focus();
    return existing;
  }

  const { effectiveTheme } = currentAppearance();
  const position = settingsWindowPosition();

  settingsWindow = new BrowserWindow({
    width: SETTINGS_WIDTH,
    height: SETTINGS_HEIGHT,
    x: position.x,
    y: position.y,
    // Native frame on purpose: the Settings window is a regular utility window.
    // The title follows the interface language (RU by default).
    title: i18n.t('settings.windowTitle', null, preferences.resolveCurrentLanguage()),
    resizable: false,
    maximizable: false,
    fullscreenable: false,
    autoHideMenuBar: true,
    icon: path.join(ASSETS_DIR, 'icon-256.png'),
    backgroundColor: appearance.windowBackgroundColor(effectiveTheme),
    show: false,
    webPreferences: {
      preload: path.join(__dirname, '..', '..', 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });

  settingsWindow.webContents.on('will-navigate', (event) => event.preventDefault());
  settingsWindow.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));

  settingsWindow.once('ready-to-show', () => {
    const window = getSettingsWindow();
    if (window) window.show();
  });

  settingsWindow.on('closed', () => {
    settingsWindow = null;
  });

  settingsWindow.loadFile(path.join(__dirname, '..', '..', 'settings.html'));
  return settingsWindow;
}

function getSettingsWindow() {
  return settingsWindow && !settingsWindow.isDestroyed() ? settingsWindow : null;
}

/**
 * Retitle the Settings window for `lang` without recreating it. Called when
 * the interface language changes; a missing window is a no-op.
 */
function setSettingsWindowTitle(lang) {
  const window = getSettingsWindow();
  if (!window) return;
  const normalized = i18n.normalizeLanguage(lang);
  const resolved =
    normalized === 'ru' || normalized === 'en'
      ? normalized
      : preferences.resolveCurrentLanguage();
  try {
    window.setTitle(i18n.t('settings.windowTitle', null, resolved));
  } catch (error) {
    logError('setSettingsWindowTitle', error);
  }
}

/** Destroy the Settings window; safe when it is already closed. */
function closeSettingsWindow() {
  const window = getSettingsWindow();
  if (!window) return;
  try {
    window.destroy();
  } catch (error) {
    logError('closeSettingsWindow', error);
  }
  settingsWindow = null;
}

/** Broadcast the appearance snapshot to every live window. */
function pushAppearance(snapshot) {
  const payload = snapshot || null;
  for (const window of [getWindow(), getSettingsWindow()]) {
    if (!window) continue;
    try {
      window.webContents.send('agw:appearance', payload);
    } catch (error) {
      logError('pushAppearance', error);
    }
  }
}

/**
 * Switch the widget to `presetMode`: apply the OS size limits of that form
 * factor, restore its stored size (or the default one) and nudge the window so
 * it stays fully inside the work area. Returns true when the mode changed.
 */
function applyFormFactor(presetMode) {
  const mode = appearance.normalizePresetMode(presetMode);
  const changed = currentMode !== mode;
  if (changed) clearPendingResizeSave();
  currentMode = mode;

  const window = getWindow();
  if (!window) return false;

  const limits = appearance.formFactorBounds(mode);
  window.setMinimumSize(limits.minWidth, limits.minHeight);
  window.setMaximumSize(limits.maxWidth, limits.maxHeight);
  if (!changed) return false;

  const stored = loadWindowState().modeBounds[mode];
  const target = clampSizeToMode(
    mode,
    stored ? stored.width : limits.width,
    stored ? stored.height : limits.height,
  );
  const bounds = window.getBounds();
  window.setSize(target.width, target.height, false);
  // The form factor owns the size now; a drag must re-read it.
  dragSize = null;
  const position = clampToWorkArea(bounds.x, bounds.y, target.width, target.height);
  if (position.x !== bounds.x || position.y !== bounds.y) {
    window.setPosition(position.x, position.y, false);
  }
  return true;
}

/** Switch the widget's acrylic material; older Electron builds ignore it. */
function applyBackgroundMaterial(window, effectiveTheme) {
  if (typeof window.setBackgroundMaterial !== 'function') return;
  try {
    window.setBackgroundMaterial(isAcrylicTheme(effectiveTheme) ? 'acrylic' : 'none');
  } catch (error) {
    logError('setBackgroundMaterial', error);
  }
}

/** Re-apply opacity/background to both windows and broadcast the snapshot. */
function applyAppearanceToWindows(snapshot) {
  if (!snapshot) return;
  const effectiveTheme = snapshot.effectiveTheme;
  const backgroundColor = isAcrylicTheme(effectiveTheme)
    ? '#00000000'
    : appearance.windowBackgroundColor(effectiveTheme);
  const opacity = snapshot.appearance ? snapshot.appearance.opacity : undefined;

  if (snapshot.appearance) applyFormFactor(snapshot.appearance.preset_mode);

  const main = getWindow();
  if (main) {
    if (typeof opacity === 'number') {
      try {
        main.setOpacity(opacity);
      } catch (error) {
        logError('setOpacity', error);
      }
    }
    applyBackgroundMaterial(main, effectiveTheme);
    try {
      main.setBackgroundColor(backgroundColor);
    } catch (error) {
      logError('setBackgroundColor', error);
    }
  }

  const settings = getSettingsWindow();
  if (settings) {
    // The Settings window has a native frame and is always an opaque surface.
    try {
      settings.setBackgroundColor(appearance.windowBackgroundColor(effectiveTheme));
    } catch (error) {
      logError('setBackgroundColor', error);
    }
  }

  pushAppearance(snapshot);
}

function getWindow() {
  return mainWindow && !mainWindow.isDestroyed() ? mainWindow : null;
}

function showWindow() {
  const window = getWindow();
  if (!window) return;
  if (window.isMinimized()) window.restore();
  window.show();
  window.focus();
}

function hideWindow() {
  const window = getWindow();
  if (window) window.hide();
}

function minimizeWindow() {
  const window = getWindow();
  if (window) window.minimize();
}

function setAlwaysOnTop(value) {
  const window = getWindow();
  if (!window) return;
  window.setAlwaysOnTop(Boolean(value), 'floating');
  saveWindowState({ alwaysOnTop: Boolean(value) });
}

function moveWindow(dx, dy) {
  const window = getWindow();
  if (!window) return;
  const bounds = window.getBounds();
  // Freeze the size for the whole drag. `minibar`/`pill` must not grow back to
  // the `full` layout while being moved, and a fresh `getBounds()` reading on
  // every tick is exactly what let the fractional-DPI rounding accumulate: at
  // 125%/150% each frame added 1-2px to both dimensions (electron#10862).
  if (!dragSize) dragSize = { width: bounds.width, height: bounds.height };
  const next = {
    x: Math.round(bounds.x + dx),
    y: Math.round(bounds.y + dy),
  };

  // Clamp against the union of every display's work area instead of snapping to
  // the current display, so the widget can cross monitors freely.
  const areas = screen.getAllDisplays().map((display) => display.workArea);
  if (!areas.length) return;
  const left = Math.min(...areas.map((area) => area.x));
  const top = Math.min(...areas.map((area) => area.y));
  const right = Math.max(...areas.map((area) => area.x + area.width));
  const bottom = Math.max(...areas.map((area) => area.y + area.height));

  // Keep TITLEBAR_GRAB pixels of the title bar visible horizontally and the
  // whole title strip below the desktop top edge; on displays shorter than the
  // window the top edge is never pushed off screen. The frozen size is used
  // here too, so the clamp matches what the OS is about to be told.
  next.x = Math.round(
    Math.max(left - dragSize.width + TITLEBAR_GRAB, Math.min(next.x, right - TITLEBAR_GRAB)),
  );
  next.y = Math.round(Math.max(top, Math.min(next.y, bottom - TITLEBAR_GRAB)));

  // The cached size (not a fresh reading) is the only width/height the OS sees.
  // A stray `resize` from the move is kept out of the saved state by the guard.
  ignoreResizeUntil = Date.now() + MOVE_RESIZE_GUARD_MS;
  window.setBounds(
    { x: next.x, y: next.y, width: dragSize.width, height: dragSize.height },
    false,
  );
}

module.exports = {
  createWindow,
  getWindow,
  openSettingsWindow,
  getSettingsWindow,
  setSettingsWindowTitle,
  closeSettingsWindow,
  applyAppearanceToWindows,
  pushAppearance,
  showWindow,
  hideWindow,
  minimizeWindow,
  setAlwaysOnTop,
  moveWindow,
  pushState,
  saveWindowState,
  saveCurrentWindowBounds,
  flushWindowState,
};
