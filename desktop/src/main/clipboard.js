'use strict';

/**
 * Pure clipboard helper shared by the main process and `node --test`.
 *
 * This module must stay importable without Electron: `src/main/ipc.js`
 * requires `electron` on its first line, so the testable logic lives here and
 * the Electron API is resolved lazily on first real use.
 */

/** Upper bound for a single clipboard payload (pairing URI or appearance JSON). */
const MAX_CLIPBOARD_TEXT = 32 * 1024;

/**
 * Resolve Electron's `clipboard` lazily: this module must stay importable
 * under plain `node --test`, where the `electron` package may be missing or
 * only export the runtime path instead of the API surface.
 */
function clipboardApi() {
  const electron = require('electron');
  if (!electron || !electron.clipboard) throw new Error('Clipboard API is unavailable');
  return electron.clipboard;
}

/**
 * Copy a short UTF-8 string; returns `{ ok: true }` or `{ ok: false, error }`.
 *
 * `writeFn` is injectable so tests never touch the real Electron clipboard.
 * Any exception thrown by `writeFn` (or the lazy `require('electron')`) is
 * turned into a failed result instead of escaping to the IPC layer.
 */
function copyTextToClipboard(text, writeFn) {
  if (typeof text !== 'string') {
    return { ok: false, error: 'Invalid clipboard text' };
  }
  if (text.length === 0) {
    return { ok: false, error: 'Clipboard text is empty' };
  }
  if (text.length > MAX_CLIPBOARD_TEXT) {
    return { ok: false, error: 'Clipboard text is too large' };
  }

  const write = typeof writeFn === 'function'
    ? writeFn
    : (value) => clipboardApi().writeText(value);

  try {
    write(text);
    return { ok: true };
  } catch (error) {
    return {
      ok: false,
      error: error && error.message ? String(error.message) : 'Clipboard write failed',
    };
  }
}

module.exports = { MAX_CLIPBOARD_TEXT, copyTextToClipboard };
