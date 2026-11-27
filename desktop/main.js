'use strict';

const { app, nativeTheme } = require('electron');
const { readAllState, startWatchers } = require('./src/main/storage');
const { maybePublishActiveChat } = require('./src/main/collector');
const { logError } = require('./src/main/logger');
const zcodeAuth = require('./src/main/zcode-auth');
const { registerIpc, buildSettingsSnapshot } = require('./src/main/ipc');
const { createTray } = require('./src/main/tray');
const windowApi = require('./src/main/window');

process.on('uncaughtException', (error) => logError('uncaughtException', error));
process.on('unhandledRejection', (reason) => logError('unhandledRejection', reason));

let stopWatching = null;
let shuttingDown = false;

app.setAppUserModelId('dev.agwidgets');

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', windowApi.showWindow);

  // A Windows dark/light switch changes `system` and `glass`; re-apply the
  // resolved theme to both windows and broadcast the fresh snapshot.
  nativeTheme.on('updated', () => {
    try {
      windowApi.applyAppearanceToWindows(buildSettingsSnapshot());
    } catch (error) {
      logError('nativeTheme', error);
    }
  });

  app.on('before-quit', () => {
    if (shuttingDown) return;
    shuttingDown = true;

    if (typeof stopWatching === 'function') {
      try {
        stopWatching();
      } catch (error) {
        logError('stopWatchers', error);
      }
    }
    try {
      zcodeAuth.cancelZCodeLogin();
    } catch (error) {
      logError('cancelZCodeLogin', error);
    }
    try {
      windowApi.flushWindowState();
    } catch (error) {
      logError('flushWindowState', error);
    }
    try {
      windowApi.closeSettingsWindow();
    } catch (error) {
      logError('closeSettingsWindow', error);
    }
  });

  // Closing the widget window quits the app (close-to-quit); the tray menu and
  // the minimize button keep it alive. `closed` in `window.js` also tears down
  // the Settings window, so this fires right after a real close.
  app.on('window-all-closed', () => app.quit());

  app.whenReady().then(() => {
    registerIpc();
    windowApi.createWindow();
    createTray();

    const refreshRenderer = () => windowApi.pushState(readAllState());
    stopWatching = startWatchers(
      refreshRenderer,
      () => maybePublishActiveChat(refreshRenderer),
      {
        // Skip the 5s polling while the widget is hidden in the tray or minimized.
        isWindowActive: () => {
          const window = windowApi.getWindow();
          return Boolean(window && window.isVisible() && !window.isMinimized());
        },
      },
    );
  });
}
