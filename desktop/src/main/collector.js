'use strict';

const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const {
  COLLECTOR_PATH,
  CONFIG_PATH,
  ROOT_DIR,
  STATUS_PATH,
  ZCODE_AUTH_PATH,
} = require('./paths');
const { readJson, readActiveChat } = require('./storage');
const { logError } = require('./logger');

const COLLECTOR_TIMEOUT_MS = 90_000;
const ZCODE_AUTH_TIMEOUT_MS = 30_000;
const ACTIVE_PUBLISH_THROTTLE_MS = 15_000;
const MAX_OUTPUT_BYTES = 128 * 1024;
const MAX_STDERR_BYTES = 8 * 1024;

let running = false;
let currentRun = null;
let lastActivePublishAttempt = 0;
let lastTriggeredActiveId = null;
let lastReportedFailure = '';

/** Keep only the tail of a stream so a chatty child cannot exhaust memory. */
function appendTail(current, chunk, limit) {
  return (current + chunk.toString()).slice(-limit);
}

/** Interpreters to try, in order; empty and non-string config values are skipped. */
function resolvePythonCandidates() {
  const config = readJson(CONFIG_PATH) || {};
  const candidates = [config.python_path, process.env.PYTHON, 'python', 'py'].filter(
    (candidate) => typeof candidate === 'string' && candidate.trim().length > 0,
  );
  return preferWindowlessPython(candidates);
}

const WINDOWS_PYTHON_EXE = /python\.exe$/i;

/**
 * On Windows a console Python briefly flashes a console window for every
 * helper run. `pythonw.exe` starts without one, so prefer it when it exists
 * next to the configured interpreter while keeping `python.exe` as a fallback.
 * Pure and injectable so it can be unit-tested off-Windows.
 */
function preferWindowlessPython(
  candidates,
  { platform = process.platform, existsSync = fs.existsSync } = {},
) {
  const list = Array.isArray(candidates) ? candidates : [];
  if (platform !== 'win32') return list.slice();

  const result = [];
  const seen = new Set();
  const push = (value) => {
    if (seen.has(value)) return;
    seen.add(value);
    result.push(value);
  };

  for (const candidate of list) {
    // Non-string config values are skipped rather than crashing the helper.
    if (typeof candidate !== 'string') continue;

    if (WINDOWS_PYTHON_EXE.test(candidate)) {
      let sibling = null;
      try {
        // Use path.win32 so Windows paths resolve identically when tested on Linux CI.
        sibling = path.win32.join(path.win32.dirname(candidate), 'pythonw.exe');
        if (sibling === candidate || !existsSync(sibling)) sibling = null;
      } catch {
        sibling = null;
      }
      if (sibling) push(sibling);
      push(candidate);
      continue;
    }

    if (candidate.toLowerCase() === 'python') {
      push('pythonw');
      push(candidate);
      continue;
    }

    push(candidate);
  }

  return result;
}

/** Parse a whole JSON object; used for the multi-line output of zcode_auth.py. */
function parseJsonObject(text) {
  if (typeof text !== 'string') return null;
  const trimmed = text.trim();
  if (!trimmed) return null;
  try {
    const value = JSON.parse(trimmed);
    return value && typeof value === 'object' && !Array.isArray(value) ? value : null;
  } catch {
    return null;
  }
}

/**
 * The collector prints exactly one JSON line; use the last non-empty line so a
 * stray print or a legacy warning cannot break parsing.
 */
function parseCollectorOutput(stdout) {
  if (typeof stdout !== 'string') return null;
  const lines = stdout.split(/\r?\n/).filter((line) => line.trim().length > 0);
  if (lines.length === 0) return null;
  return parseJsonObject(lines[lines.length - 1]);
}

/** `--force` only for an explicit boolean true; anything else keeps the caches. */
function buildCollectorArgs(force) {
  return force === true ? ['--force'] : [];
}

/** Terminate the whole tree so a timeout never leaves an orphaned agy.exe behind. */
function killProcessTree(child) {
  if (!child || !child.pid) return;
  if (process.platform === 'win32') {
    try {
      const killer = spawn('taskkill', ['/PID', String(child.pid), '/T', '/F'], { windowsHide: true });
      killer.on('error', () => {});
      if (killer.stdout) killer.stdout.resume();
      if (killer.stderr) killer.stderr.resume();
    } catch {
      // Best effort: the direct kill below is the fallback.
    }
  }
  try {
    child.kill();
  } catch {
    // The process may already have exited.
  }
}

/** Log a real failure once per distinct message instead of on every retry. */
function reportFailure(error) {
  const message = String(error || 'Collector failed');
  if (message === lastReportedFailure) return;
  lastReportedFailure = message;
  logError('collector', message);
}

/**
 * Run a Python helper with the first interpreter that actually starts.
 * Resolves `{ started, code, stdout, stderr, timedOut, error }`; `started` is
 * false when no candidate interpreter could be launched (or the script is
 * missing), so callers can distinguish infrastructure failures from exit codes.
 */
function runPythonHelper(scriptPath, args, { input = null, timeoutMs, missingError }) {
  return new Promise((resolve) => {
    if (!fs.existsSync(scriptPath)) {
      resolve({ started: false, error: missingError });
      return;
    }

    const candidates = resolvePythonCandidates();
    let index = 0;
    let settled = false;
    let timer = null;

    const finish = (result) => {
      if (settled) return;
      settled = true;
      if (timer) {
        clearTimeout(timer);
        timer = null;
      }
      resolve(result);
    };

    const tryNext = () => {
      if (settled) return;
      if (index >= candidates.length) {
        finish({ started: false, error: 'Python not found' });
        return;
      }

      const python = candidates[index++];
      let child;
      try {
        child = spawn(python, [scriptPath, ...args], {
          cwd: ROOT_DIR,
          windowsHide: true,
          shell: false,
          stdio: ['pipe', 'pipe', 'pipe'],
        });
      } catch {
        // A malformed interpreter path is not fatal: try the next candidate.
        tryNext();
        return;
      }

      let stdout = '';
      let stderr = '';
      let attemptActive = true;

      const abandonAttempt = () => {
        if (!attemptActive) return;
        attemptActive = false;
        if (timer) {
          clearTimeout(timer);
          timer = null;
        }
      };

      timer = setTimeout(() => {
        abandonAttempt();
        killProcessTree(child);
        finish({
          started: true,
          timedOut: true,
          code: null,
          stdout,
          stderr,
          error: 'Python helper timeout',
        });
      }, timeoutMs);

      child.stdout.on('data', (chunk) => {
        stdout = appendTail(stdout, chunk, MAX_OUTPUT_BYTES);
      });
      child.stderr.on('data', (chunk) => {
        stderr = appendTail(stderr, chunk, MAX_STDERR_BYTES);
      });
      if (child.stdin) {
        // The helper may exit before reading stdin; EPIPE must not crash main.
        child.stdin.on('error', () => {});
      }

      child.on('error', (error) => {
        if (!attemptActive) return;
        const code = error && error.code;
        abandonAttempt();
        if (code === 'ENOENT') {
          tryNext();
          return;
        }
        finish({ started: false, error: String((error && error.message) || error) });
      });

      child.on('close', (code) => {
        if (!attemptActive) return;
        abandonAttempt();
        if (code === 9009) {
          // Windows Store stub (WindowsApps\python.exe): try the next candidate.
          tryNext();
          return;
        }
        finish({ started: true, timedOut: false, code, stdout, stderr });
      });

      if (input !== null && child.stdin) {
        try {
          child.stdin.end(input);
        } catch {
          // The child failed early; `close`/`error` above still reports it.
        }
      }
    };

    tryNext();
  });
}

/** Wait until every run that was in flight when we called has settled. */
async function waitForIdle() {
  while (running && currentRun) {
    try {
      await currentRun;
    } catch {
      // A failed run still frees the collector for the fresh attempt.
    }
  }
}

/**
 * Run collector.py with the first working Python interpreter.
 * Returns `{ ok, statusWritten, publish, error?, busy? }`; a concurrent call
 * returns `{ ok: true, busy: true }` unless `waitIfBusy` asks for a fresh run.
 */
function runCollector({ force = true, waitIfBusy = false } = {}) {
  if (running) {
    if (!waitIfBusy) return Promise.resolve({ ok: true, busy: true });
    return waitForIdle().then(() => runCollector({ force, waitIfBusy: true }));
  }

  running = true;

  const run = new Promise((resolve) => {
    let settled = false;

    const finish = (result) => {
      if (settled) return;
      settled = true;
      running = false;
      currentRun = null;
      if (result.ok) {
        lastReportedFailure = '';
      } else if (result.error) {
        reportFailure(result.error);
      }
      resolve(result);
    };

    runPythonHelper(COLLECTOR_PATH, buildCollectorArgs(force), {
      timeoutMs: COLLECTOR_TIMEOUT_MS,
      missingError: 'Collector not found',
    })
      .then((outcome) => {
        if (!outcome.started) {
          finish({ ok: false, error: outcome.error || 'Python not found' });
          return;
        }
        if (outcome.timedOut) {
          finish({ ok: false, error: 'Collector timeout' });
          return;
        }

        const parsed = parseCollectorOutput(outcome.stdout);
        const statusWritten =
          parsed && Object.prototype.hasOwnProperty.call(parsed, 'status_written')
            ? parsed.status_written
            : null;
        const publish = parsed && parsed.publish !== undefined ? parsed.publish : null;
        const ok = outcome.code === 0 && parsed !== null && parsed.status_written !== false;

        let error = parsed && parsed.error ? String(parsed.error) : '';
        if (!error && outcome.stderr) error = outcome.stderr.trim();
        if (!error && !parsed) error = 'Collector produced no JSON output';
        if (!error && parsed && parsed.status_written === false) {
          error = 'Collector reported status_written=false';
        }

        const result = { ok, statusWritten, publish };
        if (error) result.error = error;
        finish(result);
      })
      .catch((error) => {
        finish({ ok: false, error: String((error && error.message) || error) });
      });
  });

  currentRun = run;
  return run;
}

/**
 * Publish immediately when the Antigravity annotation file points at a chat
 * different from the last published summary. `force: false` keeps the agy
 * (15 min) and ZCode (10 min) caches, while the in-memory id cache stops the
 * retry loop while `context.json` is stale or empty.
 */
async function maybePublishActiveChat(onPublished, runner = runCollector) {
  if (running) return;

  const active = readActiveChat();
  if (!active || !active.id) return;

  const status = readJson(STATUS_PATH) || {};
  const summary = status.context_summary || {};
  if (summary.conversation_id === active.id) return;
  if (lastTriggeredActiveId === active.id) return;
  if (Date.now() - lastActivePublishAttempt < ACTIVE_PUBLISH_THROTTLE_MS) return;

  lastActivePublishAttempt = Date.now();

  let result;
  try {
    result = await runner({ force: false });
  } catch (error) {
    logError('maybePublishActiveChat', error);
    return;
  }

  if (result && result.ok === true) {
    lastTriggeredActiveId = active.id;
    if (typeof onPublished === 'function') onPublished();
  }
}

/** Turn a helper outcome into `{ ok, error }` using the auth script contract. */
function parseAuthOutcome(outcome) {
  if (!outcome.started) return { parsed: null, error: outcome.error || 'Python not found' };
  if (outcome.timedOut) return { parsed: null, error: 'ZCode auth helper timeout' };

  const parsed = parseJsonObject(outcome.stdout);
  const stderr = outcome.stderr ? outcome.stderr.trim() : '';
  if (outcome.code === 0 && parsed && parsed.ok === true) return { parsed, error: '' };

  let error = parsed && parsed.error ? String(parsed.error) : stderr;
  if (!error && !parsed) error = 'ZCode auth helper produced no JSON output';
  if (!error) error = `ZCode auth helper failed (exit ${outcome.code})`;
  return { parsed, error };
}

/**
 * Save a JWT through Python so DPAPI owns the only encrypted copy. The token
 * is written to stdin and never appears in process arguments.
 */
async function saveZCodeTokenViaPython(token) {
  if (typeof token !== 'string' || token.trim().length === 0) {
    return { ok: false, encrypted: false, device_mid: '', error: 'Token must not be empty' };
  }

  const outcome = await runPythonHelper(ZCODE_AUTH_PATH, ['--set-token-stdin', '--no-verify'], {
    input: `${token}\n`,
    timeoutMs: ZCODE_AUTH_TIMEOUT_MS,
    missingError: 'ZCode auth helper not found',
  });
  const { parsed, error } = parseAuthOutcome(outcome);
  const encrypted = parsed ? parsed.encrypted === true : false;
  const deviceMid = parsed && typeof parsed.device_mid === 'string' ? parsed.device_mid : '';

  if (error) {
    logError('saveZCodeTokenViaPython', error);
    return { ok: false, encrypted, device_mid: deviceMid, error };
  }
  return { ok: true, encrypted, device_mid: deviceMid };
}

/** Remove the saved ZCode token through the Python helper (keeps device_mid). */
async function clearZCodeTokenViaPython() {
  const outcome = await runPythonHelper(ZCODE_AUTH_PATH, ['--clear-token'], {
    timeoutMs: ZCODE_AUTH_TIMEOUT_MS,
    missingError: 'ZCode auth helper not found',
  });
  const { error } = parseAuthOutcome(outcome);

  if (error) {
    logError('clearZCodeTokenViaPython', error);
    return { ok: false, error };
  }
  return { ok: true };
}

module.exports = {
  runCollector,
  maybePublishActiveChat,
  isRunning: () => running,
  buildCollectorArgs,
  parseCollectorOutput,
  preferWindowlessPython,
  saveZCodeTokenViaPython,
  clearZCodeTokenViaPython,
};
