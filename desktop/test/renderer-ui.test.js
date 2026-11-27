'use strict';

/**
 * Unit tests for the DOM-free renderer view helpers.
 * Run with: node --test desktop/test/renderer-ui.test.js
 */

const { describe, it } = require('node:test');
const assert = require('node:assert/strict');

const ctx = require('../src/renderer/context.js');
const quotas = require('../src/renderer/quotas.js');

describe('formatSnippet', () => {
  it('trims a real snippet', () => {
    assert.equal(ctx.formatSnippet('Hello'), 'Hello', 'plain text must survive');
    assert.equal(ctx.formatSnippet('  Hello  '), 'Hello', 'surrounding spaces must be trimmed');
  });

  it('returns an empty string for empty and non-string values', () => {
    assert.equal(ctx.formatSnippet(''), '', 'empty string must stay empty');
    assert.equal(ctx.formatSnippet('   '), '', 'whitespace must collapse to empty');
    assert.equal(ctx.formatSnippet(null), '', 'null must become empty');
    assert.equal(ctx.formatSnippet(undefined), '', 'undefined must become empty');
  });
});

describe('formatRecentChat', () => {
  const model = 'gpt-oss-120b';

  it('labels same-model chats by their titles', () => {
    const titles = { a: 'Ремонт', b: 'Рефакторинг' };
    const first = ctx.formatRecentChat('a', { model }, titles);
    const second = ctx.formatRecentChat('b', { model }, titles);

    assert.notEqual(first.label, second.label, 'two chats of one model must differ');
    assert.ok(first.label.includes('Ремонт'), 'label must contain its own title');
    assert.ok(second.label.includes('Рефакторинг'), 'label must contain its own title');
    assert.ok(first.label.includes('Gpt Oss 120b'), 'label must append the model');
    assert.ok(first.tooltip.includes('Ремонт'), 'tooltip must keep the title');
  });

  it('falls back to the workspace and then to the model', () => {
    const byWorkspace = ctx.formatRecentChat('x', { model, workspace: 'ag_widgets' }, {});
    assert.equal(byWorkspace.label, 'ag_widgets', 'workspace must win over the model');

    const byModel = ctx.formatRecentChat('x', { model }, {});
    assert.equal(byModel.label, 'Gpt Oss 120b', 'the model is the last fallback');
    assert.equal(byModel.tooltip, 'Gpt Oss 120b', 'tooltip must not hold empty parts');
  });

  it('does not duplicate a title that already matches the model', () => {
    const view = ctx.formatRecentChat(
      'x',
      { model: 'gemini-4-pro', workspace: 'ag_widgets' },
      { x: 'Gemini 4 Pro' },
    );
    assert.equal(view.label, 'Gemini 4 Pro', 'equal title and model must not repeat');
    assert.equal(
      view.tooltip,
      'Gemini 4 Pro · ag_widgets',
      'tooltip must dedupe case-insensitively and keep the workspace',
    );
  });

  it('keeps each tooltip part only once', () => {
    const view = ctx.formatRecentChat(
      'x',
      { model, workspace: 'Ремонт', title: 'ремонт' },
      {},
    );
    assert.equal(
      view.tooltip,
      'ремонт · Gpt Oss 120b',
      'case-insensitive duplicates must not appear twice',
    );
  });
});

describe('planTierView', () => {
  it('hides the badge while the tier is unknown', () => {
    for (const antigravity of [{ plan_tier: null }, { plan_tier: '' }, { plan_tier: '  ' }, null]) {
      const view = quotas.planTierView(antigravity);
      assert.equal(view.visible, false, 'unknown tier must hide the badge');
      assert.equal(view.text, '', 'unknown tier must not render a placeholder');
    }
  });

  it('shows a real tier', () => {
    const view = quotas.planTierView({ plan_tier: 'Pro' });
    assert.equal(view.visible, true, 'a tier must be visible');
    assert.equal(view.text, 'Pro', 'a tier must be passed through trimmed');
    assert.equal(view.title, '', 'no error means no tooltip');
  });

  it('reports collection errors in the tooltip', () => {
    const view = quotas.planTierView({ plan_tier: null, error: 'timeout' });
    assert.equal(view.hasError, true, 'an error must be flagged');
    assert.equal(view.visible, false, 'an error alone must not show the badge');
    assert.ok(view.title.includes('timeout'), 'tooltip must carry the error text');
    assert.ok(view.title.startsWith('Квоты не обновились: '), 'tooltip must keep the prefix');
  });
});
