'use strict';

const path = require('path');
const { app, BrowserWindow, screen, session, nativeTheme } = require('electron');
const { windowStatePath } = require('./paths');
const { readAllState, readJson, writeJsonAtomic } = require('./storage');
const { logError } = require('./logger');
const appearance = require('./appearance');

const WINDOW_WIDTH = 400;
const WINDOW_HEIGHT = 640;
const SETTINGS_WIDTH = 390;
const SETTINGS_HEIGHT = 540;
/** Gap between the widget and the Settings window. */
const SETTINGS_GAP = 12;
/** Minimum slice of the title bar that must stay on screen while dragging. */
const TITLEBAR_GRAB = 48;
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

function loadWindowState() {
  const saved = readJson(windowStatePath());
  if (!saved) return { x: undefined, y: undefined, alwaysOnTop: true };
  return {
    x: typeof saved.x === 'number' ? saved.x : undefined,
    y: typeof saved.y === 'number' ? saved.y : undefined,
    alwaysOnTop: typeof saved.alwaysOnTop === 'boolean' ? saved.alwaysOnTop : true,
  };
}

/** Persist window state; filesystem hiccups must never take the app down. */
function saveWindowState(extra = {}) {
  const window = getWindow();
  if (!window) return;
  try {
    const previous = loadWindowState();
    const [x, y] = window.getPosition();
    writeJsonAtomic(windowStatePath(), {
      ...previous,
      ...extra,
      x,
      y,
      alwaysOnTop: window.isAlwaysOnTop(),
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

/** Persist the current bounds immediately, skipping the `moved` debounce. */
function saveCurrentWindowBounds() {
  clearPendingMoveSave();
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
  return {
    x: Math.max(target.x, Math.min(x, target.x + target.width - width)),
    y: Math.max(target.y, Math.min(y, target.y + target.height - height)),
  };
}

function pushState(state) {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  mainWindow.webContents.send('agw:state', state || readAllState());
}

function createWindow() {
  const saved = loadWindowState();
  const { settings: currentSettings, effectiveTheme } = currentAppearance();
  const display = screen.getPrimaryDisplay().workArea;
  const defaultX = display.x + display.width - WINDOW_WIDTH - 24;
  const defaultY = display.y + 24;
  const position = isPositionOnAnyDisplay(saved.x, saved.y, WINDOW_WIDTH, WINDOW_HEIGHT)
    ? clampToWorkArea(saved.x, saved.y, WINDOW_WIDTH, WINDOW_HEIGHT)
    : { x: defaultX, y: defaultY };

  mainWindow = new BrowserWindow({
    width: WINDOW_WIDTH,
    height: WINDOW_HEIGHT,
    x: position.x,
    y: position.y,
    frame: false,
    title: 'AG Widgets',
    resizable: false,
    minimizable: true,
    maximizable: false,
    fullscreenable: false,
    icon: path.join(ASSETS_DIR, 'icon-256.png'),
    alwaysOnTop: saved.alwaysOnTop,
    hasShadow: true,
    // The glass themes sit on the Windows acrylic material (transparent base
    // color + material); the dense themes (graphite/paper/oled) use their own
    // solid base color so a black acrylic frame can never show through.
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

  // The widget is a fixed-size surface: dragging or a DPI change must not resize it.
  mainWindow.setMinimumSize(WINDOW_WIDTH, WINDOW_HEIGHT);
  mainWindow.setMaximumSize(WINDOW_WIDTH, WINDOW_HEIGHT);
  let sizeGuard = false;
  const enforceSize = () => {
    if (!mainWindow || mainWindow.isDestroyed() || sizeGuard) return;
    const [width, height] = mainWindow.getSize();
    if (width === WINDOW_WIDTH && height === WINDOW_HEIGHT) return;
    sizeGuard = true;
    mainWindow.setSize(WINDOW_WIDTH, WINDOW_HEIGHT, false);
    setTimeout(() => {
      sizeGuard = false;
    }, 60);
  };
  mainWindow.on('resize', enforceSize);
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
  const bounds = mainBounds || {
    x: area.x + area.width - WINDOW_WIDTH - 24,
    y: area.y + 24,
    width: WINDOW_WIDTH,
    height: WINDOW_HEIGHT,
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
    title: 'Настройки — AG Widgets',
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
  const next = {
    x: Math.round(bounds.x + dx),
    y: Math.round(bounds.y + dy),
    width: WINDOW_WIDTH,
    height: WINDOW_HEIGHT,
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
  // window the top edge is never pushed off screen.
  next.x = Math.max(left - WINDOW_WIDTH + TITLEBAR_GRAB, Math.min(next.x, right - TITLEBAR_GRAB));
  next.y = Math.max(top, Math.min(next.y, bottom - TITLEBAR_GRAB));
  window.setBounds(next, false);
}

module.exports = {
  createWindow,
  getWindow,
  openSettingsWindow,
  getSettingsWindow,
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
