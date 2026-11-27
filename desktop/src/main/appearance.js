'use strict';

/**
 * Appearance settings for the desktop widget (`%LOCALAPPDATA%\ag-widgets\appearance.json`).
 *
 * Pure Node module: it must stay importable under `node --test` without Electron.
 * Six user-facing themes map onto seven resolved themes; `classic` keeps the
 * original author styling and follows its own `classic_variant`.
 *
 * Beyond the Wave C theme contract the file also owns the widget builder:
 * form factor (`preset_mode`), block order/options, custom accent, corner
 * radius and density. Older files that only carry the Wave C keys keep loading
 * unchanged - every new field is filled in from `DEFAULT_APPEARANCE`.
 */

const { APPEARANCE_PATH } = require('./paths');
const { readJson, writeJsonAtomic } = require('./storage');

const THEMES = ['system', 'graphite', 'paper', 'oled', 'glass', 'classic'];
const CLASSIC_VARIANTS = ['dark', 'light'];
const DENSITIES = ['compact', 'comfortable', 'spacious'];
const PRESET_MODES = ['full', 'compact', 'minibar', 'pill'];

/**
 * Desktop block ids. Persisted order is the user's order; the same blocks are
 * addressed on Android as `header`/`context`/`quotas`/`zcode`, so the shared
 * fixtures speak the short names and `BLOCK_ALIASES` maps them here.
 */
const BLOCK_IDS = ['context_hero', 'quotas_grid', 'zcode_card'];

const BLOCK_ALIASES = Object.freeze({
  context: 'context_hero',
  context_hero: 'context_hero',
  quotas: 'quotas_grid',
  quotas_grid: 'quotas_grid',
  zcode: 'zcode_card',
  zcode_card: 'zcode_card',
});

/** Per-block option keys and their defaults (strict boolean / clamped int). */
const BLOCK_OPTION_DEFAULTS = Object.freeze({
  context_hero: Object.freeze({ show_recent: true, recent_count: 3, show_thresholds: true }),
  quotas_grid: Object.freeze({ show_gemini: true, show_3p: true, show_countdown: true }),
  zcode_card: Object.freeze({ show_balance_bar: true }),
});

const BLOCK_LABELS = Object.freeze({
  context_hero: 'Контекст',
  quotas_grid: 'Лимиты',
  zcode_card: 'ZCode',
});

const MIN_FONT_SCALE = 0.85;
const MAX_FONT_SCALE = 1.4;
const MIN_OPACITY = 0.6;
const MAX_OPACITY = 1.0;
const STEP = 0.05;

const MIN_CORNER_RADIUS = 0;
const MAX_CORNER_RADIUS = 24;
const CORNER_RADIUS_STEP = 2;
const DEFAULT_CORNER_RADIUS = 12;
const MIN_RECENT_COUNT = 0;
const MAX_RECENT_COUNT = 5;
const DEFAULT_RECENT_COUNT = 3;

/** Hard cap for `importAppearanceJson`; keeps a pasted file from stalling IPC. */
const MAX_IMPORT_BYTES = 16384;

/**
 * Window geometry per form factor (px). `*` fields are the constraints handed
 * to `BrowserWindow.setMinimumSize` / `setMaximumSize` by `window.js`.
 */
const FORM_FACTOR_BOUNDS = Object.freeze({
  full: Object.freeze({
    width: 400,
    height: 640,
    minWidth: 320,
    minHeight: 440,
    maxWidth: 560,
    maxHeight: 860,
  }),
  compact: Object.freeze({
    width: 330,
    height: 270,
    minWidth: 280,
    minHeight: 190,
    maxWidth: 440,
    maxHeight: 480,
  }),
  minibar: Object.freeze({
    width: 480,
    height: 48,
    minWidth: 320,
    minHeight: 44,
    maxWidth: 720,
    maxHeight: 68,
  }),
  pill: Object.freeze({
    width: 196,
    height: 42,
    minWidth: 160,
    minHeight: 38,
    maxWidth: 280,
    maxHeight: 56,
  }),
});

/** Every block enabled, canonical order: the untouched author layout. */
function allBlocksEnabled() {
  return BLOCK_IDS.map((id) => ({
    id,
    enabled: true,
    options: { ...BLOCK_OPTION_DEFAULTS[id] },
  }));
}

/**
 * One-click presets. Each patch is merged over the current appearance, so a
 * preset never silently drops the user's theme unless it declares one.
 */
const BUILT_IN_PRESETS = Object.freeze({
  full: Object.freeze({
    id: 'full',
    label: 'Полный',
    hint: 'Вертикальный дашборд с тремя блоками',
    patch: Object.freeze({
      theme: 'system',
      preset_mode: 'full',
      density: 'comfortable',
      blocks: allBlocksEnabled(),
    }),
  }),
  compact: Object.freeze({
    id: 'compact',
    label: 'Компактный',
    hint: 'Плотная карточка без второстепенных деталей',
    patch: Object.freeze({
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
    }),
  }),
  minibar: Object.freeze({
    id: 'minibar',
    label: 'Мини-бар',
    hint: 'Горизонтальная полоска для края экрана',
    patch: Object.freeze({
      preset_mode: 'minibar',
      density: 'compact',
      corner_radius: 12,
    }),
  }),
  pill: Object.freeze({
    id: 'pill',
    label: 'Пилюля',
    hint: 'Только контекст: ● 442K · 84%',
    patch: Object.freeze({
      preset_mode: 'pill',
      density: 'compact',
      corner_radius: 20,
    }),
  }),
  paper: Object.freeze({
    id: 'paper',
    label: 'Светлая бумага',
    hint: 'Тёплая светлая тема',
    patch: Object.freeze({
      theme: 'paper',
      preset_mode: 'full',
      corner_radius: 12,
      custom_accent: null,
    }),
  }),
  classic: Object.freeze({
    id: 'classic',
    label: 'Classic',
    hint: 'Оригинальный вид автора, 1:1',
    patch: Object.freeze({
      theme: 'classic',
      classic_variant: 'dark',
      preset_mode: 'full',
      corner_radius: 10,
      density: 'comfortable',
      custom_accent: null,
      blocks: allBlocksEnabled(),
    }),
  }),
});

const PRESET_IDS = Object.keys(BUILT_IN_PRESETS);

function buildDefaultAppearance() {
  return {
    schema_version: 1,
    theme: 'system',
    classic_variant: 'dark',
    custom_accent: null,
    opacity: 1.0,
    corner_radius: DEFAULT_CORNER_RADIUS,
    font_scale: 1.0,
    density: 'comfortable',
    preset_mode: 'full',
    blocks: allBlocksEnabled(),
  };
}

const DEFAULT_APPEARANCE = Object.freeze(buildDefaultAppearance());

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

/** Round to the nearest even value (2px step), then clamp into `[0, 24]`. */
function clampCornerRadius(value, fallback = DEFAULT_CORNER_RADIUS) {
  const numeric = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(numeric)) return fallback;
  const stepped = Math.round(numeric / CORNER_RADIUS_STEP) * CORNER_RADIUS_STEP;
  return Math.min(MAX_CORNER_RADIUS, Math.max(MIN_CORNER_RADIUS, stepped));
}

/** `#rrggbb` only: everything else (shorthands, functions, urls) becomes null. */
function normalizeHexAccent(raw) {
  if (typeof raw !== 'string') return null;
  const value = raw.trim();
  return /^#[0-9a-fA-F]{6}$/.test(value) ? value.toLowerCase() : null;
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

/** Trim + lowercase membership check with a fallback for anything unknown. */
function normalizeEnum(raw, allowed, fallback) {
  if (typeof raw !== 'string') return fallback;
  const value = raw.trim().toLowerCase();
  return allowed.includes(value) ? value : fallback;
}

function normalizeDensity(raw) {
  return normalizeEnum(raw, DENSITIES, DEFAULT_APPEARANCE.density);
}

function normalizePresetMode(raw) {
  return normalizeEnum(raw, PRESET_MODES, DEFAULT_APPEARANCE.preset_mode);
}

/** Canonical desktop block id for a stored/canonical alias, null when unknown. */
function normalizeBlockId(raw) {
  if (typeof raw !== 'string') return null;
  return BLOCK_ALIASES[raw.trim().toLowerCase()] || null;
}

function normalizeRecentCount(raw, fallback = DEFAULT_RECENT_COUNT) {
  const numeric = typeof raw === 'number' ? raw : Number(raw);
  if (raw === null || raw === undefined || raw === '' || !Number.isFinite(numeric)) return fallback;
  const rounded = Math.round(numeric);
  return Math.min(MAX_RECENT_COUNT, Math.max(MIN_RECENT_COUNT, rounded));
}

/** Keep only known option keys; booleans are strict, `recent_count` is clamped. */
function normalizeBlockOptions(blockId, raw) {
  const defaults = BLOCK_OPTION_DEFAULTS[blockId];
  const source = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : {};
  const options = {};
  for (const key of Object.keys(defaults)) {
    const fallback = defaults[key];
    if (key === 'recent_count') {
      options[key] = normalizeRecentCount(source[key], fallback);
    } else {
      options[key] = typeof source[key] === 'boolean' ? source[key] : fallback;
    }
  }
  return options;
}

function normalizeBlock(blockId, raw) {
  const source = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : {};
  return {
    id: blockId,
    enabled: typeof source.enabled === 'boolean' ? source.enabled : true,
    options: normalizeBlockOptions(blockId, source.options),
  };
}

/**
 * Ordered, de-duplicated block list: unknown ids and malformed entries are
 * dropped, duplicates after the first occurrence are ignored, and every known
 * block the user did not mention is appended with its defaults.
 */
function normalizeBlocks(raw) {
  const entries = Array.isArray(raw) ? raw : [];
  const result = [];
  const seen = new Set();
  for (const entry of entries) {
    if (!entry || typeof entry !== 'object' || Array.isArray(entry)) continue;
    const blockId = normalizeBlockId(entry.id);
    if (!blockId || seen.has(blockId)) continue;
    seen.add(blockId);
    result.push(normalizeBlock(blockId, entry));
  }
  for (const blockId of BLOCK_IDS) {
    if (!seen.has(blockId)) result.push(normalizeBlock(blockId, null));
  }
  return result;
}

/** Coerce any stored value (missing file, legacy schema, corrupt fields) into a full object. */
function normalizeAppearance(raw) {
  const source = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : {};
  return {
    schema_version: 1,
    theme: normalizeTheme(source.theme),
    classic_variant: normalizeVariant(source.classic_variant),
    custom_accent: normalizeHexAccent(source.custom_accent),
    opacity: clampStep(source.opacity, MIN_OPACITY, MAX_OPACITY, DEFAULT_APPEARANCE.opacity),
    corner_radius: clampCornerRadius(source.corner_radius, DEFAULT_APPEARANCE.corner_radius),
    font_scale: clampStep(
      source.font_scale,
      MIN_FONT_SCALE,
      MAX_FONT_SCALE,
      DEFAULT_APPEARANCE.font_scale,
    ),
    density: normalizeDensity(source.density),
    preset_mode: normalizePresetMode(source.preset_mode),
    blocks: normalizeBlocks(source.blocks),
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

/** Bounds for a form factor; unknown modes fall back to `full`. */
function formFactorBounds(presetMode) {
  return FORM_FACTOR_BOUNDS[normalizePresetMode(presetMode)];
}

/**
 * Merge a preset over the stored appearance and persist it. Returns the fresh
 * normalized appearance, or `null` for an unknown preset id (the IPC layer
 * turns that into `{ ok: false }`).
 */
function applyPreset(presetId, filePath = APPEARANCE_PATH) {
  const preset =
    typeof presetId === 'string'
      ? BUILT_IN_PRESETS[presetId.trim().toLowerCase()] || null
      : null;
  if (!preset) return null;
  const current = loadAppearance(filePath);
  const next = normalizeAppearance({ ...current, ...preset.patch });
  writeJsonAtomic(filePath, next);
  return next;
}

/** Pretty-printed, already normalized JSON suitable for clipboard/export. */
function exportAppearanceJson(value) {
  return JSON.stringify(normalizeAppearance(value), null, 2);
}

/**
 * Parse a pasted preset. Never touches disk and never throws: on any problem
 * the caller gets `{ ok: false, error, code, reason }` and keeps the current
 * configuration. `reason` is a stable machine key the IPC layer maps to a
 * localized message; `error`/`code` keep their historical Russian/string values.
 */
function importAppearanceJson(rawText) {
  if (typeof rawText !== 'string') {
    return {
      ok: false,
      error: 'Ожидается JSON-строка',
      code: 'invalid',
      reason: 'not_string',
    };
  }
  const trimmed = rawText.trim();
  if (!trimmed) {
    return { ok: false, error: 'Пустой JSON', code: 'invalid', reason: 'empty' };
  }
  if (Buffer.byteLength(trimmed, 'utf8') > MAX_IMPORT_BYTES) {
    return { ok: false, error: 'JSON больше 16 КБ', code: 'too_large', reason: 'too_large' };
  }
  let parsed;
  try {
    parsed = JSON.parse(trimmed);
  } catch {
    return { ok: false, error: 'Некорректный JSON', code: 'invalid', reason: 'invalid_json' };
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return {
      ok: false,
      error: 'Корень JSON должен быть объектом',
      code: 'invalid',
      reason: 'root_not_object',
    };
  }
  return { ok: true, appearance: normalizeAppearance(parsed) };
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
  DENSITIES,
  PRESET_MODES,
  BLOCK_IDS,
  BLOCK_LABELS,
  BLOCK_OPTION_DEFAULTS,
  FORM_FACTOR_BOUNDS,
  BUILT_IN_PRESETS,
  PRESET_IDS,
  MAX_IMPORT_BYTES,
  DEFAULT_APPEARANCE,
  clampStep,
  clampCornerRadius,
  normalizeHexAccent,
  normalizeBlocks,
  normalizeBlockId,
  normalizeDensity,
  normalizePresetMode,
  normalizeAppearance,
  resolveEffectiveTheme,
  windowBackgroundColor,
  formFactorBounds,
  applyPreset,
  exportAppearanceJson,
  importAppearanceJson,
  loadAppearance,
  saveAppearance,
  resetAppearance,
  STEP,
};
