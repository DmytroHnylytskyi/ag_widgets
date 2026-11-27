'use strict';

const fs = require('fs');
const path = require('path');
const {
  ANNOTATIONS_DIR,
  CONTEXT_PATH,
  STATE_DIR,
  STATUS_PATH,
} = require('./paths');
const { logError } = require('./logger');

const STATE_DEBOUNCE_MS = 120;
const ANNOTATIONS_DEBOUNCE_MS = 500;
const POLL_INTERVAL_MS = 5000;
const ERROR_LOG_INTERVAL_MS = 60_000;
const RENAME_ATTEMPTS = 3;
const RENAME_RETRY_MS = 25;

let tmpCounter = 0;

/** Read and parse a JSON file; returns null when missing or malformed. */
function readJson(filePath) {
  try {
    if (!fs.existsSync(filePath)) return null;
    const raw = fs.readFileSync(filePath, 'utf8').trim();
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

/** Brief synchronous pause; only used between atomic-rename attempts. */
function sleepSync(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

/**
 * Write JSON atomically so readers never observe a partially written file.
 * Windows can reject a rename while an antivirus or another reader holds the
 * target, so `EPERM`/`EBUSY` are retried a few times before giving up.
 */
function writeJsonAtomic(filePath, data) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  const tmp = `${filePath}.${process.pid}.${tmpCounter++}.tmp`;
  try {
    fs.writeFileSync(tmp, JSON.stringify(data, null, 2), 'utf8');
    let lastError = null;
    for (let attempt = 0; attempt < RENAME_ATTEMPTS; attempt += 1) {
      try {
        fs.renameSync(tmp, filePath);
        return;
      } catch (error) {
        lastError = error;
        const retryable = Boolean(error) && (error.code === 'EPERM' || error.code === 'EBUSY');
        if (!retryable || attempt === RENAME_ATTEMPTS - 1) break;
        sleepSync(RENAME_RETRY_MS);
      }
    }
    throw lastError || new Error('Atomic write failed');
  } catch (error) {
    try {
      fs.rmSync(tmp, { force: true });
    } catch {
      // Best effort: a stray temp file is harmless.
    }
    throw error;
  }
}

/**
 * Extract the title and last-view timestamp from one Antigravity annotation.
 * Never throws: a file without a title or timestamp falls back gracefully.
 */
function parseAnnotation(text, fallbackViewedAtSeconds) {
  const source = typeof text === 'string' ? text : '';
  const title = source.match(/title\s*:\s*"([^"]*)"/);
  const viewed = source.match(/last_user_view_time\s*:?\s*\{\s*seconds\s*:\s*(\d+)/);
  return {
    title: title ? title[1] : '',
    viewedAt: viewed ? Number(viewed[1]) : fallbackViewedAtSeconds,
  };
}

/**
 * Read every annotation once and derive both derived views from that pass.
 *
 * `activeChat` is the file with the latest `last_user_view_time` (mtime only as
 * a fallback when the timestamp is missing). `chatTitles` maps every
 * conversation id with a non-empty trimmed title to that title. Only ids and
 * titles are exposed; message text never leaves Antigravity. A single
 * unreadable file is skipped; an unreadable directory yields no results.
 */
function scanAnnotations(dir = ANNOTATIONS_DIR) {
  const chatTitles = {};
  let entries;
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch {
    return { activeChat: null, chatTitles };
  }

  let best = null;
  for (const entry of entries) {
    if (!entry.isFile() || !entry.name.endsWith('.pbtxt')) continue;
    const filePath = path.join(dir, entry.name);
    try {
      const mtimeMs = fs.statSync(filePath).mtimeMs;
      const parsed = parseAnnotation(fs.readFileSync(filePath, 'utf8'), Math.round(mtimeMs / 1000));
      const id = entry.name.replace(/\.pbtxt$/, '');
      const trimmedTitle = typeof parsed.title === 'string' ? parsed.title.trim() : '';
      if (trimmedTitle) chatTitles[id] = trimmedTitle;

      const candidate = {
        id,
        title: parsed.title,
        viewedAt: parsed.viewedAt,
        mtimeMs,
      };
      if (
        !best ||
        candidate.viewedAt > best.viewedAt ||
        (candidate.viewedAt === best.viewedAt && candidate.mtimeMs > best.mtimeMs)
      ) {
        best = candidate;
      }
    } catch {
      // A single unreadable annotation must not hide the other chats.
    }
  }

  return {
    activeChat: best ? { id: best.id, title: best.title, viewedAt: best.viewedAt } : null,
    chatTitles,
  };
}

/** Chat currently open in Antigravity; delegates to the single scan. */
function detectActiveChat(dir = ANNOTATIONS_DIR) {
  return scanAnnotations(dir).activeChat;
}

/** Compatibility wrapper used by collector.js and readAllState. */
function readActiveChat() {
  return detectActiveChat(ANNOTATIONS_DIR);
}

/** Snapshot consumed by the renderer. */
function readAllState() {
  const scan = scanAnnotations(ANNOTATIONS_DIR);
  return {
    context: readJson(CONTEXT_PATH),
    status: readJson(STATUS_PATH),
    activeChat: scan.activeChat,
    chatTitles: scan.chatTitles,
    now: Date.now(),
  };
}

/**
 * Watch the state directory and Antigravity annotations.
 * `onStateChange` is debounced; `onAnnotationsChange` fires for chat switches.
 * Returns a `stopWatching()` cleanup that closes watchers and clears timers.
 */
function startWatchers(onStateChange, onAnnotationsChange, options = {}) {
  const isWindowActive =
    typeof options.isWindowActive === 'function' ? options.isWindowActive : () => true;
  const onState = typeof onStateChange === 'function' ? onStateChange : null;
  const onAnnotations = typeof onAnnotationsChange === 'function' ? onAnnotationsChange : null;

  let stateTimer = null;
  let annotationsTimer = null;
  let pollTimer = null;
  let stateWatcher = null;
  let annotationsWatcher = null;
  let stopped = false;
  let lastErrorAt = 0;

  /** Diagnostics must never flood the log on every raw fs.watch event. */
  const reportError = (error) => {
    const now = Date.now();
    if (now - lastErrorAt < ERROR_LOG_INTERVAL_MS) return;
    lastErrorAt = now;
    logError('annotations', error);
  };

  const invoke = (callback) => {
    if (!callback) return;
    try {
      callback();
    } catch (error) {
      reportError(error);
    }
  };

  const scheduleState = () => {
    clearTimeout(stateTimer);
    stateTimer = setTimeout(() => invoke(onState), STATE_DEBOUNCE_MS);
  };

  const scheduleAnnotations = () => {
    if (!onAnnotations) return;
    clearTimeout(annotationsTimer);
    annotationsTimer = setTimeout(() => invoke(onAnnotations), ANNOTATIONS_DEBOUNCE_MS);
  };

  /** Attach lazily: Antigravity may be installed after the widget starts. */
  const attachAnnotationsWatcher = () => {
    if (stopped || annotationsWatcher) return Boolean(annotationsWatcher);
    try {
      const watcher = fs.watch(ANNOTATIONS_DIR, { persistent: false }, () => {
        scheduleState();
        scheduleAnnotations();
      });
      watcher.on('error', (error) => {
        reportError(error);
        try {
          watcher.close();
        } catch {
          // Already closed.
        }
        if (annotationsWatcher === watcher) annotationsWatcher = null;
      });
      annotationsWatcher = watcher;
    } catch (error) {
      // Retried on the next poll tick once the folder exists.
      reportError(error);
    }
    return Boolean(annotationsWatcher);
  };

  try {
    fs.mkdirSync(STATE_DIR, { recursive: true });
    stateWatcher = fs.watch(STATE_DIR, { persistent: false }, (_event, filename) => {
      if (!filename || filename === 'context.json' || filename === 'status.json') scheduleState();
    });
    stateWatcher.on('error', reportError);
  } catch (error) {
    // The polling interval below keeps the UI updated even if fs.watch fails.
    reportError(error);
  }

  attachAnnotationsWatcher();

  const poll = () => {
    if (stopped) return;
    attachAnnotationsWatcher();

    let active = true;
    try {
      active = Boolean(isWindowActive());
    } catch (error) {
      reportError(error);
    }
    // Skip refreshes while hidden in the tray; the first visible tick reads
    // immediately instead of waiting for another 5 s interval.
    if (!active) return;
    invoke(onState);
  };

  pollTimer = setInterval(poll, POLL_INTERVAL_MS);

  return function stopWatching() {
    if (stopped) return;
    stopped = true;
    clearTimeout(stateTimer);
    clearTimeout(annotationsTimer);
    clearInterval(pollTimer);
    stateTimer = null;
    annotationsTimer = null;
    pollTimer = null;
    for (const watcher of [stateWatcher, annotationsWatcher]) {
      if (!watcher) continue;
      try {
        watcher.close();
      } catch {
        // Already closed by an error handler.
      }
    }
    stateWatcher = null;
    annotationsWatcher = null;
  };
}

module.exports = {
  readJson,
  writeJsonAtomic,
  parseAnnotation,
  scanAnnotations,
  detectActiveChat,
  readActiveChat,
  readAllState,
  startWatchers,
};
