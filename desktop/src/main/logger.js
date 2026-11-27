'use strict';

const fs = require('fs');
const path = require('path');
const { STATE_DIR } = require('./paths');

const ERROR_LOG_PATH = path.join(STATE_DIR, 'desktop_errors.log');
const ERROR_LOG_MAX_BYTES = 256 * 1024;
const MAX_CAUSE_DEPTH = 4;
const CAUSE_FIELDS = ['code', 'errno', 'syscall'];

/** Keep the error log bounded: rotate to `.1` once it grows past 256 KiB. */
function rotate() {
  try {
    if (fs.statSync(ERROR_LOG_PATH).size <= ERROR_LOG_MAX_BYTES) return;
  } catch {
    return; // No log yet.
  }
  try {
    fs.rmSync(`${ERROR_LOG_PATH}.1`, { force: true });
  } catch {
    // A locked previous log is not worth crashing over.
  }
  try {
    fs.renameSync(ERROR_LOG_PATH, `${ERROR_LOG_PATH}.1`);
  } catch {
    // If rotation fails, append instead of losing the entry.
  }
}

/** `String(value)` that never throws (null-prototype objects, hostile getters). */
function stringify(value) {
  try {
    return String(value);
  } catch {
    try {
      return Object.prototype.toString.call(value);
    } catch {
      return '[unprintable]';
    }
  }
}

/** `stack || message || String(value)` that survives primitives and getters. */
function describeError(value) {
  if (value === null || value === undefined) return stringify(value);
  if (typeof value !== 'object' && typeof value !== 'function') return stringify(value);
  try {
    if (value.stack) return stringify(value.stack);
  } catch {
    // A throwing `stack` getter must not break diagnostics.
  }
  try {
    if (value.message) return stringify(value.message);
  } catch {
    // Same for `message`.
  }
  return stringify(value);
}

/**
 * Flatten an error and its `cause` chain (up to 4 links) into a loggable
 * string. Transport failures from Undici carry the real OS error in `cause`
 * (code/errno/syscall), so those fields are appended when present.
 */
function formatError(error) {
  const lines = [describeError(error)];
  const seen = new Set();
  let current = error;

  if (current !== null && (typeof current === 'object' || typeof current === 'function')) {
    seen.add(current);
  }

  for (let depth = 0; depth < MAX_CAUSE_DEPTH; depth += 1) {
    let cause;
    try {
      cause = current !== null && typeof current === 'object' ? current.cause : undefined;
    } catch {
      break;
    }
    if (cause === undefined || cause === null) break;

    const isObject = typeof cause === 'object' || typeof cause === 'function';
    if (isObject) {
      // Guard against `error.cause === error` and longer cycles.
      if (seen.has(cause)) break;
      seen.add(cause);
    }

    const details = [];
    if (isObject) {
      for (const field of CAUSE_FIELDS) {
        let value;
        try {
          value = cause[field];
        } catch {
          value = undefined;
        }
        if (value === undefined || value === null || value === '') continue;
        details.push(`${field}=${stringify(value)}`);
      }
    }

    const suffix = details.length > 0 ? ` (${details.join(', ')})` : '';
    lines.push(`  cause: ${describeError(cause)}${suffix}`);
    current = cause;
  }

  return lines.join('\n');
}

/**
 * Append a diagnostic entry. Logging is a best-effort aid: it must never take
 * the widget down, so every filesystem step is guarded.
 */
function logError(kind, error) {
  try {
    fs.mkdirSync(STATE_DIR, { recursive: true });
    rotate();
    const detail = formatError(error);
    const line = `[${new Date().toISOString()}] ${kind}: ${detail}\n`;
    fs.appendFileSync(ERROR_LOG_PATH, line, 'utf8');
  } catch {
    // Diagnostics are optional.
  }
}

module.exports = { logError, formatError, ERROR_LOG_PATH };
