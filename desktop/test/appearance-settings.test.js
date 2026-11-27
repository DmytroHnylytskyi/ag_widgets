'use strict';

/**
 * Unit tests for the appearance contract and the Settings menu entry.
 * Plain Node only: no Electron runtime is required.
 * Run with: node --test desktop/test/appearance-settings.test.js
 */

const fs = require('fs');
const os = require('os');
const path = require('path');
const test = require('node:test');
const assert = require('node:assert/strict');

const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'agw-appearance-'));
process.env.AGW_APP_DIR = path.join(tmpRoot, 'app');

const appearance = require('../src/main/appearance.js');
const tray = require('../src/main/tray.js');

/** Absolute path to a scratch file inside the per-run temp directory. */
function scratchFile(name) {
  return path.join(tmpRoot, name);
}

const RESOLVED_THEMES = [
  'graphite',
  'paper',
  'oled',
  'glass-dark',
  'glass-light',
  'classic-dark',
  'classic-light',
];

// --- normalizeAppearance ---------------------------------------------------

test('normalizeAppearance returns defaults for empty or non-object input', () => {
  for (const raw of [null, undefined, '', 'garbage', 42, [], true]) {
    assert.deepEqual(
      appearance.normalizeAppearance(raw),
      appearance.DEFAULT_APPEARANCE,
      `unexpected defaults for ${JSON.stringify(raw)}`,
    );
  }
});

test('normalizeAppearance migrates legacy and unknown themes', () => {
  assert.equal(appearance.normalizeAppearance({ theme: 'dark' }).theme, 'graphite');
  assert.equal(appearance.normalizeAppearance({ theme: 'DARK' }).theme, 'graphite');
  assert.equal(appearance.normalizeAppearance({ theme: 'light' }).theme, 'paper');
  assert.equal(appearance.normalizeAppearance({ theme: ' Light ' }).theme, 'paper');
  assert.equal(appearance.normalizeAppearance({ theme: 'neon' }).theme, 'system');
  assert.equal(appearance.normalizeAppearance({ theme: '' }).theme, 'system');
  assert.equal(appearance.normalizeAppearance({ theme: 7 }).theme, 'system');
});

test('normalizeAppearance normalizes classic_variant and schema_version', () => {
  const normalized = appearance.normalizeAppearance({
    schema_version: 99,
    theme: 'classic',
    classic_variant: 'LIGHT',
  });
  assert.equal(normalized.schema_version, 1);
  assert.equal(normalized.theme, 'classic');
  assert.equal(normalized.classic_variant, 'light');
  assert.equal(appearance.normalizeAppearance({ classic_variant: 'dim' }).classic_variant, 'dark');
});

test('normalizeAppearance clamps and steps font_scale to 0.05', () => {
  const cases = [
    [0.5, 0.85],
    [2.0, 1.4],
    [1.07, 1.05],
    [1.38, 1.4],
  ];
  for (const [input, expected] of cases) {
    assert.equal(
      appearance.normalizeAppearance({ font_scale: input }).font_scale,
      expected,
      `font_scale ${input}`,
    );
  }
});

test('normalizeAppearance clamps and steps opacity to 0.05', () => {
  const cases = [
    [0.2, 0.6],
    [1.2, 1.0],
    [0.83, 0.85],
    // 0.97 * 20 = 19.4 -> Math.round gives 19 -> 0.95.
    [0.97, 0.95],
  ];
  for (const [input, expected] of cases) {
    assert.equal(
      appearance.normalizeAppearance({ opacity: input }).opacity,
      expected,
      `opacity ${input}`,
    );
  }
  assert.equal(appearance.normalizeAppearance({ opacity: 'nope' }).opacity, 1.0);
});

// --- resolveEffectiveTheme -------------------------------------------------

test('resolveEffectiveTheme resolves all six themes for both OS modes', () => {
  const cases = [
    ['system', true, 'graphite'],
    ['system', false, 'paper'],
    ['graphite', true, 'graphite'],
    ['graphite', false, 'graphite'],
    ['paper', true, 'paper'],
    ['paper', false, 'paper'],
    ['oled', true, 'oled'],
    ['oled', false, 'oled'],
    ['glass', true, 'glass-dark'],
    ['glass', false, 'glass-light'],
    ['classic', true, 'classic-dark'],
    ['classic', false, 'classic-dark'],
  ];
  for (const [theme, systemDark, expected] of cases) {
    assert.equal(
      appearance.resolveEffectiveTheme({ theme }, systemDark),
      expected,
      `${theme} with systemDark=${systemDark}`,
    );
  }
});

test('resolveEffectiveTheme honours classic_variant regardless of the OS theme', () => {
  for (const systemDark of [true, false]) {
    assert.equal(
      appearance.resolveEffectiveTheme({ theme: 'classic', classic_variant: 'dark' }, systemDark),
      'classic-dark',
    );
    assert.equal(
      appearance.resolveEffectiveTheme({ theme: 'classic', classic_variant: 'light' }, systemDark),
      'classic-light',
    );
  }
});

// --- load / save / reset ---------------------------------------------------

test('loadAppearance falls back to defaults for a missing file', () => {
  const file = scratchFile(path.join('missing', 'appearance.json'));
  assert.deepEqual(appearance.loadAppearance(file), appearance.DEFAULT_APPEARANCE);
});

test('loadAppearance falls back to defaults for corrupt JSON', () => {
  const file = scratchFile(path.join('corrupt', 'appearance.json'));
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, '{ this is not json', 'utf8');
  assert.deepEqual(appearance.loadAppearance(file), appearance.DEFAULT_APPEARANCE);
});

test('saveAppearance round-trips and merges partial patches', () => {
  const file = scratchFile(path.join('round-trip', 'appearance.json'));

  const first = appearance.saveAppearance({ theme: 'oled', font_scale: 1.2 }, file);
  assert.equal(first.theme, 'oled');
  assert.equal(first.font_scale, 1.2);
  assert.deepEqual(appearance.loadAppearance(file), first);

  const second = appearance.saveAppearance({ opacity: 0.8 }, file);
  assert.equal(second.theme, 'oled', 'a partial patch must keep the stored theme');
  assert.equal(second.opacity, 0.8);
  assert.deepEqual(appearance.loadAppearance(file), second);
});

test('saveAppearance leaves no *.tmp files behind', () => {
  const dir = scratchFile('tmp-check');
  const file = path.join(dir, 'appearance.json');

  appearance.saveAppearance({ theme: 'paper' }, file);
  appearance.saveAppearance({ theme: 'graphite' }, file);

  const leftovers = fs.readdirSync(dir).filter((name) => name.endsWith('.tmp'));
  assert.deepEqual(leftovers, []);
});

test('resetAppearance restores the defaults on disk', () => {
  const file = scratchFile(path.join('reset', 'appearance.json'));
  appearance.saveAppearance(
    { theme: 'classic', classic_variant: 'light', opacity: 0.7 },
    file,
  );

  const reset = appearance.resetAppearance(file);

  assert.deepEqual(reset, appearance.DEFAULT_APPEARANCE);
  assert.deepEqual(appearance.loadAppearance(file), appearance.DEFAULT_APPEARANCE);
});

// --- windowBackgroundColor -------------------------------------------------

test('windowBackgroundColor returns a #rrggbb color for every resolved theme', () => {
  for (const theme of RESOLVED_THEMES) {
    assert.match(
      appearance.windowBackgroundColor(theme),
      /^#[0-9a-fA-F]{6}$/,
      `invalid background for ${theme}`,
    );
  }
});

test('windowBackgroundColor falls back to graphite for unknown ids', () => {
  const graphite = appearance.windowBackgroundColor('graphite');
  for (const value of ['unknown', '', undefined, null, 12]) {
    assert.equal(appearance.windowBackgroundColor(value), graphite);
  }
});

// --- tray menu -------------------------------------------------------------

test('buildMenuTemplate puts "Настройки…" after the checkboxes and before a separator', () => {
  const calls = [];
  const template = tray.buildMenuTemplate({
    source: 'tray',
    windowVisible: true,
    handlers: { openSettings: () => calls.push('open') },
  });

  const settingsIndex = template.findIndex((item) => item.label === 'Настройки…');
  const openAtLoginIndex = template.findIndex((item) => item.label === 'Запускать при входе');

  assert.ok(settingsIndex > -1, 'the Settings item must exist');
  assert.equal(settingsIndex, openAtLoginIndex + 1, 'it goes right after the checkboxes');
  assert.equal(template[settingsIndex + 1].type, 'separator');

  template[settingsIndex].click();
  assert.deepEqual(calls, ['open']);
});

test.after(() => {
  fs.rmSync(tmpRoot, { recursive: true, force: true });
});
