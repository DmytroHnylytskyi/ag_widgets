'use strict';

/**
 * Appearance settings for the desktop widget (`%LOCALAPPDATA%\ag-widgets\appearance.json`).
 *
 * Pure Node module: it must stay importable under `node --test` without Electron.
 * Six user-facing themes map onto seven resolved themes; `classic` keeps the
 * original author styling and follows its own `classic_variant`.
 */

const { APPEARANCE_PATH } = require('./paths');
const { readJson, writeJsonAtomic } = require('./storage');

const THEMES = ['system', 'graphite', 'paper', 'oled', 'glass', 'classic'];
const CLASSIC_VARIANTS = ['dark', 'light'];

const MIN_FONT_SCALE = 0.85;
const MAX_FONT_SCALE = 1.4;
const MIN_OPACITY = 0.6;
const MAX_OPACITY = 1.0;
const STEP = 0.05;

const DEFAULT_APPEARANCE = Object.freeze({
  schema_version: 1,
  theme: 'system',
  classic_variant: 'dark',
  font_scale: 1.0,
  opacity: 1.0,
});

/** Legacy `dark`/`light` names map onto the closest modern theme. */
const LEGACY_THEMES = { dark: 'graphite', light: 'paper' };

/** Window base colors used by Electron before the renderer paints anything. */
const WINDOW_BACKGROUNDS = {
  graphite: '#121417',
  paper: '#F2F1EC',
  oled: '#000000',
  'glass-dark': '#0B1220',
  'glass-light': '#E4EAF2',
  'classic-dark': '#0a0e13',
  'classic-light': '#fbfbfa',
};

/** Round to the nearest 0.05 step, then clamp into `[min, max]`. */
function clampStep(value, min, max, fallback) {
  const numeric = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(numeric)) return fallback;
  const stepped = Math.round(numeric * 20) / 20;
  return Math.min(max, Math.max(min, stepped));
}

function normalizeTheme(raw) {
  if (typeof raw !== 'string') return DEFAULT_APPEARANCE.theme;
  const value = raw.trim().toLowerCase();
  if (LEGACY_THEMES[value]) return LEGACY_THEMES[value];
  return THEMES.includes(value) ? value : DEFAULT_APPEARANCE.theme;
}

function normalizeVariant(raw) {
  if (typeof raw === 'string') {
    const value = raw.trim().toLowerCase();
    if (CLASSIC_VARIANTS.includes(value)) return value;
  }
  return DEFAULT_APPEARANCE.classic_variant;
}

/** Coerce any stored value (missing file, legacy schema, corrupt fields) into a full object. */
function normalizeAppearance(raw) {
  const source = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : {};
  return {
    schema_version: 1,
    theme: normalizeTheme(source.theme),
    classic_variant: normalizeVariant(source.classic_variant),
    font_scale: clampStep(
      source.font_scale,
      MIN_FONT_SCALE,
      MAX_FONT_SCALE,
      DEFAULT_APPEARANCE.font_scale,
    ),
    opacity: clampStep(source.opacity, MIN_OPACITY, MAX_OPACITY, DEFAULT_APPEARANCE.opacity),
  };
}

/**
 * Resolve a user theme into the concrete renderer theme id.
 * `system` and `glass` follow the OS theme; `classic` follows `classic_variant`
 * so the author's dark/light pair can be chosen independently of Windows.
 */
function resolveEffectiveTheme(appearance, systemDark) {
  const settings = normalizeAppearance(appearance);
  const dark = Boolean(systemDark);
  switch (settings.theme) {
    case 'graphite':
      return 'graphite';
    case 'paper':
      return 'paper';
    case 'oled':
      return 'oled';
    case 'glass':
      return dark ? 'glass-dark' : 'glass-light';
    case 'classic':
      return settings.classic_variant === 'light' ? 'classic-light' : 'classic-dark';
    default:
      return dark ? 'graphite' : 'paper';
  }
}

/** Base `backgroundColor` for a BrowserWindow; unknown ids fall back to graphite. */
function windowBackgroundColor(effectiveTheme) {
  const key = typeof effectiveTheme === 'string' ? effectiveTheme : '';
  return WINDOW_BACKGROUNDS[key] || WINDOW_BACKGROUNDS.graphite;
}

function loadAppearance(filePath = APPEARANCE_PATH) {
  return normalizeAppearance(readJson(filePath));
}

/** Merge `patch` into the stored settings, normalize, and persist atomically. */
function saveAppearance(patch, filePath = APPEARANCE_PATH) {
  const current = loadAppearance(filePath);
  const delta = patch && typeof patch === 'object' && !Array.isArray(patch) ? patch : {};
  const next = normalizeAppearance({ ...current, ...delta });
  writeJsonAtomic(filePath, next);
  return next;
}

function resetAppearance(filePath = APPEARANCE_PATH) {
  const next = normalizeAppearance(DEFAULT_APPEARANCE);
  writeJsonAtomic(filePath, next);
  return next;
}

module.exports = {
  THEMES,
  CLASSIC_VARIANTS,
  DEFAULT_APPEARANCE,
  normalizeAppearance,
  resolveEffectiveTheme,
  windowBackgroundColor,
  loadAppearance,
  saveAppearance,
  resetAppearance,
};
