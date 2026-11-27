'use strict';

/**
 * Golden tests for the widget builder: the shared `appearance_cases.json`
 * fixture (replayed by Android too), the new appearance fields, built-in
 * presets, export/import and the form factor bounds.
 * Plain Node only: no Electron runtime is required.
 * Run with: node --test desktop/test/builder-appearance.test.js
 */

const fs = require('fs');
const os = require('os');
const path = require('path');
const test = require('node:test');
const assert = require('node:assert/strict');

const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'agw-builder-'));
process.env.AGW_APP_DIR = path.join(tmpRoot, 'app');

const appearance = require('../src/main/appearance.js');
const tray = require('../src/main/tray.js');

const FIXTURE_PATH = path.join(
  __dirname,
  '..',
  '..',
  'tests',
  'fixtures',
  'appearance_cases.json',
);
const FIXTURE = JSON.parse(fs.readFileSync(FIXTURE_PATH, 'utf8'));

/** Absolute path to a scratch file inside the per-run temp directory. */
function scratchFile(name) {
  return path.join(tmpRoot, name);
}

// --- shared fixture: accent / clamps / blocks ------------------------------

test('fixture accent_cases match normalizeHexAccent', () => {
  for (const entry of FIXTURE.accent_cases) {
    assert.equal(
      appearance.normalizeHexAccent(entry.input),
      entry.expected,
      `accent ${JSON.stringify(entry.input)}`,
    );
  }
});

test('fixture clamp_cases match normalizeAppearance', () => {
  const fields = {
    font_scale: 'font_scale',
    corner_radius: 'corner_radius',
    opacity_desktop: 'opacity',
    density: 'density',
    preset_mode: 'preset_mode',
  };
  for (const [caseName, field] of Object.entries(fields)) {
    for (const entry of FIXTURE.clamp_cases[caseName]) {
      const normalized = appearance.normalizeAppearance({ [field]: entry.input });
      assert.equal(
        normalized[field],
        entry.expected,
        `${caseName} input ${JSON.stringify(entry.input)}`,
      );
    }
  }
});

test('fixture opacity_android matches clampStep with an Android range', () => {
  for (const entry of FIXTURE.clamp_cases.opacity_android) {
    assert.equal(
      appearance.clampStep(entry.input, 0, 1, 0.95),
      entry.expected,
      `opacity_android ${entry.input}`,
    );
  }
});

test('fixture blocks_cases match normalizeBlocks', () => {
  for (const entry of FIXTURE.blocks_cases) {
    assert.deepEqual(
      appearance.normalizeBlocks(entry.input),
      entry.desktop,
      `blocks case ${entry.name}`,
    );
  }
});

test('fixture import_success_cases parse into normalized appearances', () => {
  for (const entry of FIXTURE.import_success_cases) {
    const result = appearance.importAppearanceJson(entry.input);
    assert.equal(result.ok, true, `import case ${entry.name}`);
    assert.equal(result.appearance.theme, entry.expected_theme, entry.name);
    if (Object.prototype.hasOwnProperty.call(entry, 'expected_accent')) {
      assert.equal(result.appearance.custom_accent, entry.expected_accent, entry.name);
    }
    if (Object.prototype.hasOwnProperty.call(entry, 'expected_corner_radius')) {
      assert.equal(result.appearance.corner_radius, entry.expected_corner_radius, entry.name);
    }
    if (Object.prototype.hasOwnProperty.call(entry, 'expected_density')) {
      assert.equal(result.appearance.density, entry.expected_density, entry.name);
    }
  }
});

test('fixture import_error_cases report the shared error codes', () => {
  for (const entry of FIXTURE.import_error_cases) {
    const input =
      typeof entry.pad_chars === 'number'
        ? '{"pad":"' + 'x'.repeat(entry.pad_chars) + '"}'
        : entry.input;
    const result = appearance.importAppearanceJson(input);
    assert.equal(result.ok, false, `import error ${entry.name}`);
    assert.equal(result.code, entry.error_code, `import error ${entry.name}`);
  }
});

test('MAX_IMPORT_BYTES matches the shared fixture', () => {
  assert.equal(appearance.MAX_IMPORT_BYTES, FIXTURE.import_max_bytes);
});

// --- new fields: backward compatibility -----------------------------------

test('normalizeAppearance backfills a Wave C file with builder defaults', () => {
  const legacy = {
    schema_version: 1,
    theme: 'oled',
    classic_variant: 'light',
    font_scale: 1.2,
    opacity: 0.8,
  };
  const normalized = appearance.normalizeAppearance(legacy);

  assert.deepEqual(normalized, {
    ...appearance.DEFAULT_APPEARANCE,
    theme: 'oled',
    classic_variant: 'light',
    font_scale: 1.2,
    opacity: 0.8,
  });
  assert.equal(normalized.custom_accent, null);
  assert.equal(normalized.corner_radius, 12);
  assert.equal(normalized.density, 'comfortable');
  assert.equal(normalized.preset_mode, 'full');
  assert.deepEqual(
    normalized.blocks.map((block) => block.id),
    ['context_hero', 'quotas_grid', 'zcode_card'],
  );
});

// --- built-in presets ------------------------------------------------------

test('applyPreset applies every built-in preset to a clean file', () => {
  for (const id of appearance.PRESET_IDS) {
    const file = scratchFile(path.join('presets', `${id}.json`));
    const result = appearance.applyPreset(id, file);
    assert.ok(result, `preset ${id} must apply`);
    assert.deepEqual(appearance.loadAppearance(file), result, `preset ${id} must persist`);
  }
});

test('applyPreset classic restores the author look', () => {
  const file = scratchFile(path.join('presets', 'classic-detail.json'));
  const result = appearance.applyPreset('classic', file);

  assert.equal(result.theme, 'classic');
  assert.equal(result.classic_variant, 'dark');
  assert.equal(result.corner_radius, 10);
  assert.equal(result.preset_mode, 'full');
  assert.equal(result.custom_accent, null);
  assert.equal(result.blocks.length, 3);
  assert.ok(result.blocks.every((block) => block.enabled === true));
});

test('applyPreset pill switches the form factor and rounds the corners', () => {
  const file = scratchFile(path.join('presets', 'pill-detail.json'));
  const result = appearance.applyPreset('pill', file);

  assert.equal(result.preset_mode, 'pill');
  assert.equal(result.corner_radius, 20);
});

test('applyPreset returns null for an unknown id and leaves the file alone', () => {
  const fresh = scratchFile(path.join('unknown', 'appearance.json'));
  assert.equal(appearance.applyPreset('nope', fresh), null);
  assert.equal(fs.existsSync(fresh), false, 'an unknown preset must not create the file');

  const existing = scratchFile(path.join('unknown', 'existing.json'));
  appearance.saveAppearance({ theme: 'paper', corner_radius: 6 }, existing);
  const before = fs.readFileSync(existing, 'utf8');
  assert.equal(appearance.applyPreset('WIDE', existing), null);
  assert.equal(fs.readFileSync(existing, 'utf8'), before, 'the file must stay untouched');
});

// --- export / import round-trip -------------------------------------------

test('exportAppearanceJson emits schema v1 with all three blocks', () => {
  const exported = appearance.exportAppearanceJson({ theme: 'paper' });
  const parsed = JSON.parse(exported);

  assert.equal(parsed.schema_version, 1);
  assert.equal(parsed.blocks.length, 3);
  assert.equal(parsed.theme, 'paper');
});

test('export/import round-trip is lossless', () => {
  const sample = {
    theme: 'glass',
    classic_variant: 'light',
    custom_accent: '#34D399',
    corner_radius: 7,
    density: 'SPACIOUS',
    preset_mode: 'compact',
    font_scale: 1.1,
    opacity: 0.85,
    blocks: [{ id: 'zcode', enabled: false }],
  };

  const roundTrip = appearance.importAppearanceJson(appearance.exportAppearanceJson(sample));

  assert.equal(roundTrip.ok, true);
  assert.deepEqual(roundTrip.appearance, appearance.normalizeAppearance(sample));
});

// --- form factor bounds ----------------------------------------------------

test('FORM_FACTOR_BOUNDS keep the default size inside every min/max pair', () => {
  for (const mode of appearance.PRESET_MODES) {
    const bounds = appearance.formFactorBounds(mode);
    assert.ok(bounds.minWidth <= bounds.width, `${mode}: minWidth`);
    assert.ok(bounds.width <= bounds.maxWidth, `${mode}: maxWidth`);
    assert.ok(bounds.minHeight <= bounds.height, `${mode}: minHeight`);
    assert.ok(bounds.height <= bounds.maxHeight, `${mode}: maxHeight`);
  }
});

test('formFactorBounds falls back to the full layout for unknown modes', () => {
  assert.deepEqual(appearance.formFactorBounds('unknown'), appearance.FORM_FACTOR_BOUNDS.full);
  assert.deepEqual(appearance.formFactorBounds(null), appearance.FORM_FACTOR_BOUNDS.full);
});

// --- tray window-mode submenu ---------------------------------------------

test('the "Режим окна" submenu follows the Settings separator and routes clicks', () => {
  const calls = [];
  const template = tray.buildMenuTemplate({
    source: 'tray',
    presetMode: 'pill',
    handlers: { setWindowMode: (id) => calls.push(id) },
  });

  const settingsIndex = template.findIndex((item) => item.label === 'Настройки…');
  const modeIndex = template.findIndex((item) => item.label === 'Режим окна');

  assert.equal(modeIndex, settingsIndex + 2, 'the submenu goes right after the separator');
  assert.equal(template[modeIndex - 1].type, 'separator');

  const submenu = template[modeIndex].submenu;
  assert.deepEqual(
    submenu.map((item) => item.label),
    ['Полный', 'Компактный', 'Мини-бар', 'Пилюля'],
  );
  assert.deepEqual(
    submenu.map((item) => item.checked),
    [false, false, false, true],
  );
  assert.ok(submenu.every((item) => item.type === 'radio'));

  submenu[1].click();
  assert.deepEqual(calls, ['compact']);
});

test('buildMenuTemplate defaults the mode submenu to "full"', () => {
  const template = tray.buildMenuTemplate({ source: 'tray' });
  const submenu = template.find((item) => item.label === 'Режим окна').submenu;
  assert.equal(submenu[0].checked, true);
  assert.equal(submenu.filter((item) => item.checked).length, 1);
});

test.after(() => {
  fs.rmSync(tmpRoot, { recursive: true, force: true });
});
