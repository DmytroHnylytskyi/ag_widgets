'use strict';

/**
 * Contract tests for the main-process clipboard helper
 * (`desktop/src/main/clipboard.js`).
 *
 * The module resolves Electron's `clipboard` lazily; every test injects its own
 * `writeFn`, so nothing here loads `electron` or touches the real clipboard.
 */

const test = require('node:test');
const assert = require('node:assert/strict');

const {
  MAX_CLIPBOARD_TEXT,
  copyTextToClipboard,
} = require('../src/main/clipboard.js');

/** Collect what a fake `writeFn` received. */
function recordingWriter() {
  const calls = [];
  const writeFn = (value) => {
    calls.push(value);
  };
  return { calls, writeFn };
}

test('MAX_CLIPBOARD_TEXT is 32768', () => {
  assert.equal(MAX_CLIPBOARD_TEXT, 32768);
});

test('copies a pairing URI verbatim', () => {
  const text = 'agw://pair?v=1&t=example-topic-0123456789abcdef';
  const { calls, writeFn } = recordingWriter();

  const result = copyTextToClipboard(text, writeFn);

  assert.deepEqual(result, { ok: true });
  assert.equal(calls.length, 1);
  assert.equal(calls[0], text);
});

test('copies a pretty-printed multi-line JSON payload', () => {
  const text = JSON.stringify({ theme: 'dark', blocks: ['clock', 'date'] }, null, 2);
  assert.ok(text.includes('\n'));
  const { calls, writeFn } = recordingWriter();

  const result = copyTextToClipboard(text, writeFn);

  assert.deepEqual(result, { ok: true });
  assert.equal(calls.length, 1);
  assert.equal(calls[0], text);
});

test('rejects an empty string', () => {
  const { calls, writeFn } = recordingWriter();

  const result = copyTextToClipboard('', writeFn);

  assert.equal(result.ok, false);
  assert.ok(typeof result.error === 'string' && result.error.length > 0);
  assert.equal(calls.length, 0);
});

test('rejects non-string values', () => {
  const values = [null, undefined, 123, {}, []];

  for (const value of values) {
    const { calls, writeFn } = recordingWriter();
    const result = copyTextToClipboard(value, writeFn);
    assert.equal(result.ok, false, `expected ${String(value)} to be rejected`);
    assert.ok(typeof result.error === 'string' && result.error.length > 0);
    assert.equal(calls.length, 0);
  }
});

test('accepts exactly MAX_CLIPBOARD_TEXT characters', () => {
  const text = 'a'.repeat(MAX_CLIPBOARD_TEXT);
  const { calls, writeFn } = recordingWriter();

  const result = copyTextToClipboard(text, writeFn);

  assert.deepEqual(result, { ok: true });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].length, MAX_CLIPBOARD_TEXT);
});

test('rejects MAX_CLIPBOARD_TEXT + 1 characters', () => {
  const text = 'a'.repeat(MAX_CLIPBOARD_TEXT + 1);
  const { calls, writeFn } = recordingWriter();

  const result = copyTextToClipboard(text, writeFn);

  assert.equal(result.ok, false);
  assert.ok(typeof result.error === 'string' && result.error.length > 0);
  assert.equal(calls.length, 0);
});

test('turns a throwing writeFn into a failed result', () => {
  const writeFn = () => {
    throw new Error('boom');
  };

  let result;
  assert.doesNotThrow(() => {
    result = copyTextToClipboard('some text', writeFn);
  });

  assert.equal(result.ok, false);
  assert.ok(result.error.includes('boom'));
});
