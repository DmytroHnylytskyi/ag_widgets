'use strict';

/**
 * Unit tests for the pure renderer formatting helpers.
 * Run with: node --test desktop/test/format.test.js
 */

const { describe, it } = require('node:test');
const assert = require('node:assert/strict');

const fmt = require('../src/renderer/format.js');

const DASH = '\u2014'; // em dash used as the "unknown value" placeholder

function assertClose(actual, expected, message) {
  assert.ok(Math.abs(actual - expected) < 1e-9, `${message}: got ${actual}, expected ${expected}`);
}

describe('formatTokens', () => {
  it('formats million-scale values without a trailing .0', () => {
    assert.equal(fmt.formatTokens(999_600), '1M', '999_600 must round up to 1M');
    assert.equal(fmt.formatTokens(1_200_000), '1.2M', '1_200_000 must be 1.2M');
    assert.equal(fmt.formatTokens(1_000_000), '1M', '1_000_000 must be 1M');
  });

  it('formats thousands and small values', () => {
    assert.equal(fmt.formatTokens(999), '999', '999 must stay plain');
    assert.equal(fmt.formatTokens(1_000), '1K', '1_000 must be 1K');
    assert.equal(fmt.formatTokens(999_499), '999K', '999_499 must not promote to M');
  });

  it('returns an em dash for unknown values', () => {
    assert.equal(fmt.formatTokens(null), DASH, 'null must be unknown');
    assert.equal(fmt.formatTokens(undefined), DASH, 'undefined must be unknown');
    assert.equal(fmt.formatTokens(NaN), DASH, 'NaN must be unknown');
    assert.equal(fmt.formatTokens(Infinity), DASH, 'Infinity must be unknown');
  });
});

describe('formatWindow', () => {
  it('formats sub-million windows in KiB', () => {
    assert.equal(fmt.formatWindow(524_288), '512K', '524_288 must be 512K');
    assert.equal(fmt.formatWindow(131_072), '128K', '131_072 must be 128K');
  });

  it('formats million-scale windows in MiB', () => {
    assert.equal(fmt.formatWindow(1_048_576), '1M', '1_048_576 must be 1M');
    assert.equal(fmt.formatWindow(1_000_000), '1M', '1_000_000 must be 1M');
    assert.equal(fmt.formatWindow(2_097_152), '2M', '2_097_152 must be 2M');
    assert.equal(fmt.formatWindow(4_194_304), '4M', '4_194_304 must be 4M');
  });

  it('returns an em dash for zero or unknown windows', () => {
    assert.equal(fmt.formatWindow(0), DASH, 'zero window must be unknown');
    assert.equal(fmt.formatWindow(null), DASH, 'null window must be unknown');
    assert.equal(fmt.formatWindow(NaN), DASH, 'NaN window must be unknown');
  });
});

describe('formatUnits', () => {
  it('formats large unit counts in millions', () => {
    assert.equal(fmt.formatUnits(999_600), '1M', '999_600 must round up to 1M');
    assert.equal(fmt.formatUnits(300_000_000), '300M', '300_000_000 must be 300M');
    assert.equal(fmt.formatUnits(1_500_000), '1.5M', '1_500_000 must keep one decimal');
  });

  it('formats thousands and zero', () => {
    assert.equal(fmt.formatUnits(500_000), '500K', '500_000 must be 500K');
    assert.equal(fmt.formatUnits(0), '0', 'zero must stay 0');
  });

  it('returns an empty string for unknown values', () => {
    assert.equal(fmt.formatUnits(null), '', 'null must become empty string');
    assert.equal(fmt.formatUnits(undefined), '', 'undefined must become empty string');
    assert.equal(fmt.formatUnits(NaN), '', 'NaN must become empty string');
  });
});

describe('prettyModel', () => {
  it('strips the effort suffix and title-cases the name', () => {
    assert.equal(fmt.prettyModel('gemini-4-neon-high'), 'Gemini 4 Neon', 'effort suffix must be dropped');
  });

  it('joins multi-segment versions with a dot', () => {
    assert.equal(fmt.prettyModel('gemini-4-5-pro-high'), 'Gemini 4.5 Pro', '4 5 must become 4.5');
  });

  it('keeps two-digit version components', () => {
    assert.equal(fmt.prettyModel('gemini-12-0-flash-low'), 'Gemini 12.0 Flash', '12 0 must become 12.0');
  });

  it('falls back to the unknown model label', () => {
    assert.equal(fmt.prettyModel(''), 'Неизвестная модель', 'empty input must use the fallback');
    assert.equal(fmt.prettyModel(null), 'Неизвестная модель', 'null must use the fallback');
  });
});

describe('computeZone', () => {
  it('computes pct, zone and token thresholds', () => {
    const zone = fmt.computeZone(105_453, 524_288);
    assertClose(zone.pct, 20.11, 'pct must be rounded to two decimals');
    assert.equal(zone.level, 'green', '20.11% must be green');
    assert.equal(zone.warnTokens, 209_715, 'warn threshold is 40% of the window');
    assert.equal(zone.riskTokens, 340_787, 'risk threshold is 65% of the window');
  });

  it('treats exactly the yellow threshold as yellow', () => {
    const yellow = fmt.computeZone(400, 1000);
    assertClose(yellow.pct, 40, 'pct must be 40');
    assert.equal(yellow.level, 'yellow', 'exactly 40% must be yellow');

    const green = fmt.computeZone(399, 1000);
    assertClose(green.pct, 39.9, 'pct must be 39.9');
    assert.equal(green.level, 'green', '39.9% must be green');
  });

  it('treats exactly the red threshold as red', () => {
    const red = fmt.computeZone(650, 1000);
    assertClose(red.pct, 65, 'pct must be 65');
    assert.equal(red.level, 'red', 'exactly 65% must be red');

    const yellow = fmt.computeZone(649, 1000);
    assertClose(yellow.pct, 64.9, 'pct must be 64.9');
    assert.equal(yellow.level, 'yellow', '64.9% must be yellow');
  });

  it('honours custom thresholds', () => {
    const yellow = fmt.computeZone(600, 1000, 50, 80);
    assertClose(yellow.pct, 60, 'pct must be 60');
    assert.equal(yellow.level, 'yellow', '60% with 50/80 thresholds must be yellow');
    assert.equal(yellow.warnTokens, 500, 'custom warn threshold must be 50% of the window');
    assert.equal(yellow.riskTokens, 800, 'custom risk threshold must be 80% of the window');

    const red = fmt.computeZone(800, 1000, 50, 80);
    assertClose(red.pct, 80, 'pct must be 80');
    assert.equal(red.level, 'red', 'exactly 80% with 50/80 thresholds must be red');

    // Contract 2.4: level compares the rounded pct against yellowPct,
    // so 30% stays green when the custom yellow threshold is 50%.
    const green = fmt.computeZone(300, 1000, 50, 80);
    assertClose(green.pct, 30, 'pct must be 30');
    assert.equal(green.level, 'green', '30% with a 50% yellow threshold must be green');
  });

  it('returns unknown for a missing or non-positive window', () => {
    const expected = { pct: null, level: 'unknown', warnTokens: null, riskTokens: null };
    assert.deepEqual(fmt.computeZone(1000, null), expected, 'null window must be unknown');
    assert.deepEqual(fmt.computeZone(1000, 0), expected, 'zero window must be unknown');
  });

  it('returns unknown for non-finite token counts', () => {
    assert.equal(fmt.computeZone(NaN, 1000).level, 'unknown', 'NaN tokens must be unknown');
    assert.equal(fmt.computeZone(NaN, 1000).pct, null, 'NaN tokens must yield a null pct');
    assert.equal(fmt.computeZone(undefined, 1000).level, 'unknown', 'undefined tokens must be unknown');
    assert.equal(fmt.computeZone(null, 1000).level, 'unknown', 'null tokens must be unknown');
  });
});

describe('module exports', () => {
  it('exposes the default threshold constants', () => {
    assert.equal(fmt.DEFAULT_YELLOW_PCT, 40, 'default yellow threshold must be 40');
    assert.equal(fmt.DEFAULT_RED_PCT, 65, 'default red threshold must be 65');
  });

  it('exposes clock and helper functions', () => {
    assert.equal(typeof fmt.formatCountdown, 'function', 'formatCountdown must be exported');
    assert.equal(typeof fmt.formatEndDate, 'function', 'formatEndDate must be exported');
    assert.equal(typeof fmt.clamp, 'function', 'clamp must be exported');
    assert.equal(typeof fmt.shortAge, 'function', 'shortAge must be exported');
    assert.equal(fmt.clamp(5, 0, 3), 3, 'clamp must cap at max');
    assert.equal(fmt.clamp(-1, 0, 3), 0, 'clamp must floor at min');
    // Antigravity CLI emits Unix epoch 0 for unused 100% buckets (e.g. 3p-5h).
    assert.equal(
      fmt.formatCountdown('1970-01-01T00:00:00Z'),
      '',
      'epoch-zero sentinel must not render as "сброс сейчас"',
    );
  });
});

// --- wave 2 i18n regressions (BUGFIXES #18) -------------------------------

it('formatEndDate follows the interface language, not the OS locale', () => {
  const iso = '2026-12-05T10:00:00Z';

  assert.match(fmt.formatEndDate(iso, 'ru'), /^05[.]12$/, 'RU uses DD.MM');
  assert.match(fmt.formatEndDate(iso, 'en'), /^12[/]05$/, 'EN uses MM/DD');
  assert.equal(fmt.formatEndDate(iso), fmt.formatEndDate(iso, 'ru'), 'RU stays the default');
  assert.equal(fmt.formatEndDate('', 'en'), '');
  assert.equal(fmt.formatEndDate('not-a-date', 'en'), '');
});

it('describeError localizes known collector failures and keeps the detail', () => {
  const keys = [];
  const translate = (key, vars) => {
    keys.push(key);
    return vars && vars.message ? `${key}:${vars.message}` : key;
  };

  assert.equal(fmt.describeError('another collector run is active: lock', translate), 'errors.busy');
  assert.equal(fmt.describeError('config.json is not valid JSON', translate), 'errors.config');
  assert.equal(fmt.describeError('cannot write status.json: disk full', translate), 'errors.write');
  assert.equal(fmt.describeError('', translate), 'errors.generic');

  const unknown = fmt.describeError('AGY exploded at step 3', translate);
  assert.match(unknown, /^errors[.]withDetail:AGY exploded at step 3$/);
  assert.ok(keys.length >= 5, 'every branch asks the dictionary');
});

it('describeLoginError uses the error code, then the raw message, then the fallback', () => {
  const translate = (key) => key;

  assert.equal(
    fmt.describeLoginError({ ok: false, error_code: 'login_in_progress', error: 'Login already in progress' }, translate),
    'dashboard.zcode.loginInProgress',
    'a known code must never surface the English main-process text',
  );
  assert.equal(fmt.describeLoginError({ ok: false, error: 'network down' }, translate), 'network down');
  assert.equal(fmt.describeLoginError(null, translate), 'dashboard.zcode.loginFailed');
});

