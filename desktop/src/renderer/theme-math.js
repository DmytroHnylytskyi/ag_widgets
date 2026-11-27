'use strict';

/**
 * Pure colour maths for the custom accent (Wave F1: S1-018).
 *
 * A user-picked accent is used twice: as a fill (buttons, bars) and as a text
 * colour (badges, active tabs). The fill needs 3:1 against the card, the text
 * needs 4.5:1 — a single raw accent can satisfy neither on both light and dark
 * themes. This module derives the `--on-accent`, `--accent-ink` and
 * `--accent-bar` tokens from one hex value plus the resolved card surface.
 *
 * No DOM access: `app.js` and `settings.js` apply the returned tokens.
 */

/** Resolved card surface per effective theme (composited over the background). */
const THEME_SURFACES = {
  graphite: { card: '#1b1f26', dark: true },
  paper: { card: '#faf9f6', dark: false },
  oled: { card: '#0e1116', dark: true },
  'glass-dark': { card: '#141d2e', dark: true },
  'glass-light': { card: '#f9fafc', dark: false },
};

/** Fallbacks from the token contract (Desktop §1.1). */
const ON_ACCENT_DARK = '#0b0e14';
const ON_ACCENT_LIGHT = '#ffffff';
const MIN_TEXT_CONTRAST = 4.6;
const MIN_GRAPHIC_CONTRAST = 3.1;
/** Relative luminance above which a dark foreground wins on a filled accent. */
const DARK_FOREGROUND_THRESHOLD = 0.32;

const HEX_PATTERN = /^#?([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/;

/** `#abc` / `#aabbcc` -> `{ r, g, b }` (0-255), or null for anything else. */
function parseHex(value) {
  if (typeof value !== 'string') return null;
  const match = HEX_PATTERN.exec(value.trim());
  if (!match) return null;
  let hex = match[1];
  if (hex.length === 3) {
    hex = hex[0] + hex[0] + hex[1] + hex[1] + hex[2] + hex[2];
  }
  return {
    r: parseInt(hex.slice(0, 2), 16),
    g: parseInt(hex.slice(2, 4), 16),
    b: parseInt(hex.slice(4, 6), 16),
  };
}

function toHexChannel(value) {
  const clamped = Math.max(0, Math.min(255, Math.round(value)));
  return clamped.toString(16).padStart(2, '0');
}

function toHex(rgb) {
  return `#${toHexChannel(rgb.r)}${toHexChannel(rgb.g)}${toHexChannel(rgb.b)}`;
}

/** WCAG 2.1 sRGB relative luminance; null when the input is not a hex colour. */
function relativeLuminance(value) {
  const rgb = typeof value === 'object' && value ? value : parseHex(value);
  if (!rgb) return null;
  const channel = (raw) => {
    const srgb = raw / 255;
    return srgb <= 0.03928 ? srgb / 12.92 : Math.pow((srgb + 0.055) / 1.055, 2.4);
  };
  return 0.2126 * channel(rgb.r) + 0.7152 * channel(rgb.g) + 0.0722 * channel(rgb.b);
}

/** WCAG contrast ratio between two hex colours; 1 when either cannot be parsed. */
function contrastRatio(a, b) {
  const la = relativeLuminance(a);
  const lb = relativeLuminance(b);
  if (la === null || lb === null) return 1;
  const lighter = Math.max(la, lb);
  const darker = Math.min(la, lb);
  return (lighter + 0.05) / (darker + 0.05);
}

function rgbToHsl(rgb) {
  const r = rgb.r / 255;
  const g = rgb.g / 255;
  const b = rgb.b / 255;
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const lightness = (max + min) / 2;
  if (max === min) return { h: 0, s: 0, l: lightness };

  const delta = max - min;
  const saturation = lightness > 0.5 ? delta / (2 - max - min) : delta / (max + min);
  let hue;
  if (max === r) hue = ((g - b) / delta + (g < b ? 6 : 0)) / 6;
  else if (max === g) hue = ((b - r) / delta + 2) / 6;
  else hue = ((r - g) / delta + 4) / 6;
  return { h: hue, s: saturation, l: lightness };
}

function hslToRgb(hsl) {
  const { h, s, l } = hsl;
  if (s === 0) {
    const grey = l * 255;
    return { r: grey, g: grey, b: grey };
  }
  const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
  const p = 2 * l - q;
  const channel = (t) => {
    let value = t;
    if (value < 0) value += 1;
    if (value > 1) value -= 1;
    if (value < 1 / 6) return p + (q - p) * 6 * value;
    if (value < 1 / 2) return q;
    if (value < 2 / 3) return p + (q - p) * (2 / 3 - value) * 6;
    return p;
  };
  return {
    r: channel(h + 1 / 3) * 255,
    g: channel(h) * 255,
    b: channel(h - 1 / 3) * 255,
  };
}

/** Shift a colour's HSL lightness by `delta` (-1..1), keeping hue/saturation. */
function shiftLightness(value, delta) {
  const rgb = parseHex(value);
  if (!rgb) return null;
  const hsl = rgbToHsl(rgb);
  return toHex(hslToRgb({ h: hsl.h, s: hsl.s, l: Math.max(0, Math.min(1, hsl.l + delta)) }));
}

/**
 * Smallest lightness shift (1 % steps) that lifts `foreground` to
 * `target` contrast against `background`. Falls back to white/black when no
 * shift inside the HSL gamut reaches the target.
 */
function ensureContrast(foreground, background, target, direction) {
  const start = parseHex(foreground);
  if (!start) return null;
  const tone = direction === 'light' || direction === 'dark'
    ? direction
    : (relativeLuminance(background) || 0) > 0.4
      ? 'dark'
      : 'light';
  const step = tone === 'light' ? 0.01 : -0.01;

  let best = toHex(start);
  for (let index = 0; index <= 100; index += 1) {
    const candidate = shiftLightness(foreground, step * index);
    if (!candidate) break;
    if (contrastRatio(candidate, background) >= target) return candidate;
    best = candidate;
  }
  // The HSL gamut ran out: pure white/black is the strongest available contrast.
  return tone === 'light' ? '#ffffff' : '#000000';
}

/** Surface descriptor for an effective theme; unknown themes look like graphite. */
function themeSurface(theme) {
  return THEME_SURFACES[theme] || THEME_SURFACES.graphite;
}

/**
 * Tokens derived from a custom accent on a resolved theme surface:
 * - `onAccent`: text on a filled accent button (>= 4.5:1 by luminance choice),
 * - `accentInk`: accent used as text on the card (>= 4.6:1),
 * - `accentBar`: accent used for bars/graphics on the card (>= 3.1:1).
 */
function resolveAccentTokens(accent, theme) {
  const rgb = parseHex(accent);
  if (!rgb) return null;
  const surface = themeSurface(theme);
  const luminance = relativeLuminance(accent);
  // Contract default: bright accents get the dark label. Saturated mid-tones
  // (e.g. #f43f5e) miss 4.5:1 that way, so fall back to the other foreground
  // whenever the preferred one is not readable — the fill itself never changes.
  const preferred = luminance > DARK_FOREGROUND_THRESHOLD ? ON_ACCENT_DARK : ON_ACCENT_LIGHT;
  const fallback = preferred === ON_ACCENT_DARK ? ON_ACCENT_LIGHT : ON_ACCENT_DARK;
  const onAccent =
    contrastRatio(preferred, accent) >= MIN_TEXT_CONTRAST ? preferred : fallback;
  const direction = surface.dark ? 'light' : 'dark';

  return {
    onAccent,
    accentInk: ensureContrast(accent, surface.card, MIN_TEXT_CONTRAST, direction),
    accentBar: ensureContrast(accent, surface.card, MIN_GRAPHIC_CONTRAST, direction),
  };
}

const api = {
  THEME_SURFACES,
  MIN_TEXT_CONTRAST,
  MIN_GRAPHIC_CONTRAST,
  parseHex,
  toHex,
  relativeLuminance,
  contrastRatio,
  shiftLightness,
  ensureContrast,
  themeSurface,
  resolveAccentTokens,
};

if (typeof module !== 'undefined' && module.exports) {
  module.exports = api;
}

if (typeof window !== 'undefined') {
  const AGW = (window.AGW = window.AGW || {});
  AGW.themeMath = api;
}
