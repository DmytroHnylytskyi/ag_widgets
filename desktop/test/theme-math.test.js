'use strict';

/**
 * Unit tests for the custom-accent colour maths (Wave F1, S1-018).
 * Run with: node --test desktop/test/theme-math.test.js
 */

const { describe, it } = require('node:test');
const assert = require('node:assert/strict');

const math = require('../src/renderer/theme-math.js');

const ACCENTS = ['#60a5fa', '#34d399', '#a78bfa', '#f59e0b', '#f43f5e', '#38bdf8'];
const MODERN_THEMES = ['graphite', 'paper', 'oled', 'glass-dark', 'glass-light'];

describe('parseHex', () => {
  it('accepts long and short hex with or without the hash', () => {
    assert.deepEqual(math.parseHex('#1b1f26'), { r: 27, g: 31, b: 38 });
    assert.deepEqual(math.parseHex('abc'), { r: 170, g: 187, b: 204 });
    assert.deepEqual(math.parseHex('#ABC'), { r: 170, g: 187, b: 204 });
  });

  it('rejects everything else', () => {
    for (const value of ['', '#12', 'rgb(0,0,0)', null, undefined, 42, {}]) {
      assert.equal(math.parseHex(value), null, `must reject ${JSON.stringify(value)}`);
    }
  });
});

describe('contrastRatio', () => {
  it('matches the WCAG anchors', () => {
    assert.equal(Math.round(math.contrastRatio('#000000', '#ffffff') * 100) / 100, 21);
    assert.equal(math.contrastRatio('#ffffff', '#ffffff'), 1);
  });

  it('is symmetric and returns 1 for unparsable input', () => {
    const a = math.contrastRatio('#f59e0b', '#faf9f6');
    const b = math.contrastRatio('#faf9f6', '#f59e0b');
    assert.ok(Math.abs(a - b) < 1e-9, 'ratio must not depend on argument order');
    assert.equal(math.contrastRatio('nope', '#faf9f6'), 1);
  });

  it('reproduces the reported amber-on-paper failure', () => {
    const ratio = math.contrastRatio('#f59e0b', '#faf9f6');
    assert.ok(ratio > 1.9 && ratio < 2.2, `amber on paper was 2.04:1, got ${ratio}`);
  });
});

describe('ensureContrast', () => {
  it('leaves a colour that already passes untouched', () => {
    assert.equal(math.ensureContrast('#60a5fa', '#1b1f26', 4.5), '#60a5fa');
  });

  it('darkens towards the light-theme target', () => {
    const fixed = math.ensureContrast('#f59e0b', '#faf9f6', 4.6, 'dark');
    assert.ok(math.contrastRatio(fixed, '#faf9f6') >= 4.6, `${fixed} must reach 4.6:1`);
  });

  it('lightens towards the dark-theme target', () => {
    const fixed = math.ensureContrast('#1e3a8a', '#1b1f26', 4.6, 'light');
    assert.ok(math.contrastRatio(fixed, '#1b1f26') >= 4.6, `${fixed} must reach 4.6:1`);
  });
});

describe('resolveAccentTokens', () => {
  it('returns null for an invalid accent', () => {
    assert.equal(math.resolveAccentTokens('rebeccapurple', 'paper'), null);
  });

  it('picks the dark foreground for the bright amber accent', () => {
    const tokens = math.resolveAccentTokens('#f59e0b', 'paper');
    assert.equal(tokens.onAccent, '#0b0e14', 'bright accents need a dark label');
  });

  it('picks the light foreground for a deep blue accent', () => {
    const tokens = math.resolveAccentTokens('#1d4ed8', 'paper');
    assert.equal(tokens.onAccent, '#ffffff', 'deep accents need a light label');
  });

  it('keeps every preset accent readable on every modern theme', () => {
    for (const theme of MODERN_THEMES) {
      const surface = math.themeSurface(theme);
      for (const accent of ACCENTS) {
        const tokens = math.resolveAccentTokens(accent, theme);

        assert.ok(
          math.contrastRatio(tokens.accentInk, surface.card) >= 4.6,
          `${theme}/${accent}: accent ink ${tokens.accentInk} must reach 4.6:1 on ${surface.card}`,
        );
        assert.ok(
          math.contrastRatio(tokens.accentBar, surface.card) >= 3.1,
          `${theme}/${accent}: accent bar ${tokens.accentBar} must reach 3.1:1 on ${surface.card}`,
        );
        assert.ok(
          math.contrastRatio(tokens.onAccent, accent) >= 4.5,
          `${theme}/${accent}: on-accent ${tokens.onAccent} must reach 4.5:1 on ${accent}`,
        );
      }
    }
  });

  it('fixes the amber-on-paper case from the audit', () => {
    const tokens = math.resolveAccentTokens('#f59e0b', 'paper');
    assert.ok(
      math.contrastRatio(tokens.accentInk, '#faf9f6') >= 4.6,
      'the Pro badge text must leave the 2.04:1 failure behind',
    );
    assert.ok(
      math.contrastRatio(tokens.accentBar, '#faf9f6') >= 3.1,
      'bar fills must stay visible against a light card',
    );
  });
});
