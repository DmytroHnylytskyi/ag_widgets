'use strict';

const path = require('path');
const os = require('os');

/**
 * Resolve the Electron `app` object lazily. `src/main/*.js` must stay
 * importable under `node --test`, where the `electron` package is not loaded
 * (and may not even be installed), so every access is guarded.
 */
function electronApp() {
  try {
    const electron = require('electron');
    return electron && electron.app ? electron.app : null;
  } catch {
    return null;
  }
}

/** Runtime data directory (`%LOCALAPPDATA%\ag-widgets` by default). */
function appDir() {
  const override = String(process.env.AGW_APP_DIR || '').trim();
  if (override) return path.resolve(override);
  const base = process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local');
  return path.join(base, 'ag-widgets');
}

/** Directory holding collector/, hook/, and desktop/ (or resources when packaged). */
function projectRoot() {
  const app = electronApp();
  if (app && app.isPackaged && process.resourcesPath) return process.resourcesPath;
  return path.join(__dirname, '..', '..', '..');
}

/** Electron userData directory; falls back to a local folder outside Electron. */
function userDataDir() {
  const override = String(process.env.AGW_USER_DATA_DIR || '').trim();
  if (override) return path.resolve(override);
  const app = electronApp();
  if (app) {
    try {
      return app.getPath('userData');
    } catch {
      // `getPath` can fail very early in startup; the fallback below is enough.
    }
  }
  return path.join(appDir(), 'userdata');
}

/** Antigravity annotation files; overridable so tests can use a temp folder. */
function annotationsDir() {
  const override = String(process.env.AGW_ANNOTATIONS_DIR || '').trim();
  if (override) return path.resolve(override);
  return path.join(os.homedir(), '.gemini', 'antigravity', 'annotations');
}

const ROOT_DIR = projectRoot();
const APP_DIR = appDir();
const STATE_DIR = path.join(APP_DIR, 'state');
const CONFIG_PATH = path.join(APP_DIR, 'config.json');
const SECRETS_PATH = path.join(APP_DIR, 'secrets.json');
const APPEARANCE_PATH = path.join(APP_DIR, 'appearance.json');
const CONTEXT_PATH = path.join(STATE_DIR, 'context.json');
const STATUS_PATH = path.join(STATE_DIR, 'status.json');
const ANNOTATIONS_DIR = annotationsDir();
const COLLECTOR_PATH = path.join(ROOT_DIR, 'collector', 'collector.py');
const ZCODE_AUTH_PATH = path.join(ROOT_DIR, 'collector', 'zcode_auth.py');

/** Resolved on access so the real Electron userData path is used at runtime. */
function windowStatePath() {
  return path.join(userDataDir(), 'window-state.json');
}

const paths = {
  ROOT_DIR,
  APP_DIR,
  STATE_DIR,
  CONFIG_PATH,
  SECRETS_PATH,
  APPEARANCE_PATH,
  CONTEXT_PATH,
  STATUS_PATH,
  ANNOTATIONS_DIR,
  COLLECTOR_PATH,
  ZCODE_AUTH_PATH,
  appDir,
  projectRoot,
  userDataDir,
  annotationsDir,
  windowStatePath,
};

Object.defineProperty(paths, 'WINDOW_STATE_PATH', {
  enumerable: true,
  get: windowStatePath,
});

module.exports = paths;
