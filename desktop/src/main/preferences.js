'use strict';

/**
 * User-facing desktop preferences that are not part of `appearance.json`.
 *
 * Only the interface language lives here for now: appearance stays a pure
 * visual contract (and its JSON preset format must not grow a UI-language
 * field), while the language is a process-wide setting shared by the widget,
 * the Settings window, the tray menu and desktop notifications.
 *
 * The file is `<userData>/preferences.json`; `paths.userDataDir()` is resolved
 * on every access because it honours `AGW_USER_DATA_DIR` (tests point it at a
 * temporary profile). The module is pure Node and never throws outside
 * Electron.
 */

const path = require('path');
const paths = require('./paths');
const { readJson, writeJsonAtomic } = require('./storage');
const { logError } = require('./logger');
const i18n = require('../shared/i18n.js');

/** Valid language modes; unknown values normalize to `auto`. */
const DEFAULTS = Object.freeze({ language: 'auto' });

/** Absolute path to `preferences.json`, resolved at call time. */
function preferencesPath() {
  return path.join(paths.userDataDir(), 'preferences.json');
}

/** Coerce any stored value into `{ language: 'auto' | 'ru' | 'en' }`. */
function normalizePreferences(raw) {
  const source = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : {};
  return { language: i18n.normalizeLanguage(source.language) };
}

/** Normalized preferences from disk; defaults when the file is missing/corrupt. */
function loadPreferences() {
  try {
    return normalizePreferences(readJson(preferencesPath()));
  } catch {
    return { language: DEFAULTS.language };
  }
}

/**
 * Merge `patch` into the stored preferences, normalize and persist atomically.
 * A filesystem hiccup is logged but never propagated: the caller still gets
 * the normalized object so the UI stays responsive.
 */
function savePreferences(patch) {
  const current = loadPreferences();
  const source = patch && typeof patch === 'object' && !Array.isArray(patch) ? patch : {};
  const next = normalizePreferences({
    ...current,
    ...(Object.prototype.hasOwnProperty.call(source, 'language')
      ? { language: source.language }
      : {}),
  });
  try {
    writeJsonAtomic(preferencesPath(), next);
  } catch (error) {
    logError('savePreferences', error);
  }
  return next;
}

/** Current language mode: `'auto'`, `'ru'` or `'en'`. */
function getLanguage() {
  return loadPreferences().language;
}

/** Persist a language mode (normalized) and return the saved value. */
function setLanguage(mode) {
  return savePreferences({ language: mode }).language;
}

/**
 * OS locale used by the `auto` mode. Electron's `app.getLocale()` when
 * available, otherwise the ICU default, otherwise `'en'`.
 */
function systemLocale() {
  try {
    const electron = require('electron');
    const app = electron && electron.app ? electron.app : null;
    if (app && typeof app.getLocale === 'function') {
      const locale = app.getLocale();
      if (typeof locale === 'string' && locale.trim()) return locale;
    }
  } catch {
    // Plain Node / half-initialised Electron: fall through to Intl.
  }
  try {
    const locale = new Intl.DateTimeFormat().resolvedOptions().locale;
    if (typeof locale === 'string' && locale.trim()) return locale;
  } catch {
    // A stripped ICU build may not resolve anything.
  }
  return 'en';
}

/** Concrete UI language for the current mode and system locale: `'ru'`/`'en'`. */
function resolveCurrentLanguage() {
  return i18n.resolveLanguage(getLanguage(), systemLocale());
}

module.exports = {
  DEFAULTS,
  preferencesPath,
  normalizePreferences,
  loadPreferences,
  savePreferences,
  getLanguage,
  setLanguage,
  systemLocale,
  resolveCurrentLanguage,
};
