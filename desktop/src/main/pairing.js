'use strict';

/**
 * Bridge between the Settings window (Sync tab) and `collector/pairing.py`.
 *
 * Electron never reads or writes `secrets.json`: the Python helper owns the
 * DPAPI-encrypted ntfy topic and E2E key, exactly like `collector/zcode_auth.py`
 * owns the ZCode token. This module only spawns that helper and validates its
 * JSON contract. The pairing URI carries the topic (and the key when E2E is
 * on), so it is returned to the requesting window but never logged.
 */

const fs = require('fs');
const net = require('net');
const path = require('path');
const { spawn } = require('child_process');
const { CONFIG_PATH, ROOT_DIR } = require('./paths');
const { readJson } = require('./storage');
const { logError } = require('./logger');
const collector = require('./collector');

const PAIRING_TIMEOUT_MS = 10_000;
const MAX_OUTPUT_BYTES = 64 * 1024;
const MAX_STDERR_BYTES = 8 * 1024;
const PAIRING_SCRIPT_PATH = path.join(ROOT_DIR, 'collector', 'pairing.py');

const DEFAULT_NTFY_URL = 'https://ntfy.sh';
const NTFY_SERVER_MAX_LENGTH = 256;
/** Mirror of `agwidgets/e2e.py::NTFY_URL_RE`. */
const NTFY_URL_RE = /^https:\/\/[^\s/?#@]+(?::[0-9]{1,5})?(?:\/[^\s?#]*)?$/i;

const TOPIC_RE = /^[A-Za-z0-9_-]{1,64}$/;
const E2E_KEY_RE = /^[A-Za-z0-9_-]{43}$/;
const PAIRING_KNOWN_PARAMS = new Set(['v', 't', 's', 'k']);

const ALLOWED_PATCH_KEYS = new Set(['ntfy_enabled', 'ntfy_server', 'e2e_action']);
const E2E_ACTIONS = new Set(['enable', 'disable', 'rotate']);

/* ------------------------------------------------------------------ */
/* Python-compatible whitespace / URL splitting                        */
/* ------------------------------------------------------------------ */

/**
 * `str.isspace()` characters of Python's `str.strip()`. Kept explicit so a
 * value can be normalised byte-for-byte like `agwidgets/e2e.py` does.
 */
const PYSPACE_CLASS =
  '\\t\\n\\v\\f\\r \\x1c-\\x1f\\x85\\xa0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000';
const PYSPACE_RE = new RegExp(`[${PYSPACE_CLASS}]`);
const PY_STRIP_RE = new RegExp(`^[${PYSPACE_CLASS}]+|[${PYSPACE_CLASS}]+$`, 'g');

function isPythonSpace(char) {
  return PYSPACE_RE.test(char);
}

/** Python `str.strip()`: trims only its own whitespace set, not e.g. U+FEFF. */
function pyStrip(text) {
  return text.replace(PY_STRIP_RE, '');
}

/** Reject any whitespace, C0 control character or DEL inside the value. */
function hasSpaceOrControl(text) {
  for (const char of text) {
    const code = char.codePointAt(0);
    if (code < 0x20 || code === 0x7f || isPythonSpace(char)) return true;
  }
  return false;
}

/**
 * Split a URL into the same five components as Python's `urlsplit`.
 * Returns `null` for the bracket errors `urlsplit` raises (`Invalid IPv6 URL`).
 */
function splitUrl(raw) {
  let rest = raw;
  let scheme = '';

  const colon = rest.indexOf(':');
  if (colon > 0 && /^[A-Za-z]$/.test(rest[0])) {
    const candidate = rest.slice(0, colon);
    if (/^[A-Za-z][A-Za-z0-9+.-]*$/.test(candidate)) {
      scheme = candidate.toLowerCase();
      rest = rest.slice(colon + 1);
    }
  }

  let netloc = null;
  if (rest.startsWith('//')) {
    let end = rest.length;
    for (let i = 2; i < rest.length; i += 1) {
      const char = rest[i];
      if (char === '/' || char === '?' || char === '#') {
        end = i;
        break;
      }
    }
    netloc = rest.slice(2, end);
    rest = rest.slice(end);
  }

  let fragment = '';
  const hash = rest.indexOf('#');
  if (hash !== -1) {
    fragment = rest.slice(hash + 1);
    rest = rest.slice(0, hash);
  }

  let query = '';
  const question = rest.indexOf('?');
  if (question !== -1) {
    query = rest.slice(question + 1);
    rest = rest.slice(0, question);
  }

  return { scheme, netloc, path: rest, query, fragment };
}

/** Accept the hosts `urlsplit` allows inside brackets (IPv6 or IPvFuture). */
function isBracketedHostAcceptable(host) {
  if (/^v[0-9A-Fa-f]+\..+$/.test(host)) return true; // IPvFuture (RFC 3986)
  return net.isIPv6(host) === true;
}

/**
 * Parse a netloc the way `urllib.parse.SplitResult` does.
 * Returns `null` when `urlsplit` would raise `Invalid IPv6 URL`.
 */
function splitNetloc(netloc) {
  const value = netloc === null || netloc === undefined ? '' : netloc;

  const hasOpen = value.includes('[');
  const hasClose = value.includes(']');
  if (hasOpen !== hasClose) return null;
  if (hasOpen && hasClose) {
    const inner = value.slice(value.indexOf('[') + 1).split(']')[0];
    if (!isBracketedHostAcceptable(inner)) return null;
  }

  let username = null;
  let password = null;
  let hostport = value;
  if (value.includes('@')) {
    const at = value.lastIndexOf('@');
    const userinfo = value.slice(0, at);
    hostport = value.slice(at + 1);
    const sep = userinfo.indexOf(':');
    if (sep === -1) {
      username = userinfo;
    } else {
      username = userinfo.slice(0, sep);
      password = userinfo.slice(sep + 1);
    }
  }

  let hostname;
  let port;
  const open = hostport.indexOf('[');
  if (open !== -1) {
    const bracketed = hostport.slice(open + 1);
    const close = bracketed.indexOf(']');
    hostname = close === -1 ? bracketed : bracketed.slice(0, close);
    const after = close === -1 ? '' : bracketed.slice(close + 1);
    const sep = after.indexOf(':');
    port = sep === -1 ? '' : after.slice(sep + 1);
  } else {
    const sep = hostport.indexOf(':');
    hostname = sep === -1 ? hostport : hostport.slice(0, sep);
    port = sep === -1 ? '' : hostport.slice(sep + 1);
  }

  if (!port) port = null;
  return { username, password, hostname, port };
}

/* ------------------------------------------------------------------ */
/* normalizeNtfyServer                                                 */
/* ------------------------------------------------------------------ */

/**
 * Mirror of `agwidgets/e2e.py::normalize_ntfy_server`: return a safe
 * `https://host[:port][/path]` or `null` for every rejected input.
 */
function normalizeNtfyServer(raw) {
  if (typeof raw !== 'string') return null;

  const stripped = pyStrip(raw);
  if (!stripped) return null;
  if (hasSpaceOrControl(stripped)) return null;

  const parts = splitUrl(stripped);
  if (parts.scheme !== 'https') return null;

  const netloc = parts.netloc === null ? '' : parts.netloc;
  const hostInfo = splitNetloc(netloc);
  if (!hostInfo) return null;
  if (hostInfo.username || hostInfo.password) return null;
  if (netloc.includes('@')) return null;
  if (parts.query || parts.fragment) return null;
  if (!hostInfo.hostname) return null;

  let port = null;
  if (hostInfo.port !== null) {
    // Python's `parts.port` only accepts plain decimal digits.
    if (!/^[0-9]+$/.test(hostInfo.port)) return null;
    port = Number(hostInfo.port);
    if (!Number.isFinite(port) || port < 1 || port > 65535) return null;
  }

  const host = hostInfo.hostname.toLowerCase();
  const hostPart = host.includes(':') ? `[${host}]` : host;

  let pathname = parts.path || '';
  if (pathname.endsWith('/')) pathname = pathname.replace(/\/+$/, '');

  let normalized = `https://${hostPart}`;
  if (port !== null && port !== 443) normalized += `:${port}`;
  normalized += pathname;

  if (normalized.length > NTFY_SERVER_MAX_LENGTH) return null;
  if (!NTFY_URL_RE.test(normalized)) return null;
  return normalized;
}

/* ------------------------------------------------------------------ */
/* parsePairingUri                                                     */
/* ------------------------------------------------------------------ */

/** Percent-decode like `urllib.parse.unquote` (plus is already a space). */
function percentDecode(text) {
  return text.replace(/(?:%[0-9A-Fa-f]{2})+/g, (sequence) => {
    const bytes = [];
    for (let i = 0; i < sequence.length; i += 3) {
      bytes.push(parseInt(sequence.slice(i + 1, i + 3), 16));
    }
    // `errors='replace'` is Python's default: invalid bytes become U+FFFD.
    return Buffer.from(bytes).toString('utf8');
  });
}

/** `parse_qsl(query, keep_blank_values=True)` with manual decoding. */
function parseQueryPairs(query) {
  const pairs = [];
  if (typeof query !== 'string' || query === '') return pairs;
  for (const segment of query.split('&')) {
    if (!segment) continue;
    const eq = segment.indexOf('=');
    const name = eq === -1 ? segment : segment.slice(0, eq);
    const value = eq === -1 ? '' : segment.slice(eq + 1);
    pairs.push([
      percentDecode(name.replace(/\+/g, ' ')),
      percentDecode(value.replace(/\+/g, ' ')),
    ]);
  }
  return pairs;
}

/** A canonical base64url key is exactly 43 chars and re-encodes to itself. */
function validateE2eKey(raw) {
  const key = pyStrip(raw);
  if (!E2E_KEY_RE.test(key)) return null;
  let decoded;
  try {
    decoded = Buffer.from(key, 'base64url');
  } catch {
    return null;
  }
  if (decoded.length !== 32) return null;
  if (decoded.toString('base64url') !== key) return null;
  return key;
}

/**
 * Mirror of `agwidgets/e2e.py::parse_pairing_uri`; `null` on any error.
 * Returns `{ v: 1, topic, server, e2e_key }`.
 */
function parsePairingUri(uri) {
  if (typeof uri !== 'string') return null;
  const stripped = pyStrip(uri);
  if (!stripped) return null;

  const parts = splitUrl(stripped);
  if (parts.scheme !== 'agw') return null;

  const hostInfo = splitNetloc(parts.netloc);
  if (!hostInfo) return null;
  if ((hostInfo.hostname || '').toLowerCase() !== 'pair') return null;
  if (parts.path !== '' && parts.path !== '/') return null;

  const collected = new Map();
  for (const [name, value] of parseQueryPairs(parts.query)) {
    if (!PAIRING_KNOWN_PARAMS.has(name)) continue;
    if (collected.has(name)) return null;
    collected.set(name, value);
  }

  if (collected.get('v') !== '1') return null;
  if (!collected.has('t')) return null;

  const topic = pyStrip(collected.get('t'));
  if (!TOPIC_RE.test(topic)) return null;

  let server = DEFAULT_NTFY_URL;
  if (collected.has('s')) {
    server = normalizeNtfyServer(collected.get('s'));
    if (server === null) return null;
  }

  let e2eKey = null;
  if (collected.has('k')) {
    const rawKey = collected.get('k');
    if (!pyStrip(rawKey)) return null;
    e2eKey = validateE2eKey(rawKey);
    if (e2eKey === null) return null;
  }

  return { v: 1, topic, server, e2e_key: e2eKey };
}

/* ------------------------------------------------------------------ */
/* validatePairingPatch                                                */
/* ------------------------------------------------------------------ */

function isPlainObject(value) {
  if (value === null || typeof value !== 'object') return false;
  const proto = Object.getPrototypeOf(value);
  return proto === Object.prototype || proto === null;
}

/**
 * Turn a renderer patch into the Python CLI calls it represents.
 * `calls` keeps the fixed order ntfy_server -> ntfy_enabled -> e2e_action.
 */
function validatePairingPatch(patch) {
  if (!isPlainObject(patch)) {
    return { ok: false, error: 'pairing patch must be a plain object' };
  }

  const keys = Object.keys(patch);
  if (keys.length === 0) {
    return { ok: false, error: 'empty pairing patch' };
  }
  for (const key of keys) {
    if (!ALLOWED_PATCH_KEYS.has(key)) {
      return { ok: false, error: `unsupported pairing patch key: ${key}` };
    }
  }

  const calls = [];

  if (Object.prototype.hasOwnProperty.call(patch, 'ntfy_server')) {
    const raw = patch.ntfy_server;
    if (typeof raw !== 'string') {
      return { ok: false, error: 'ntfy_server must be a string' };
    }
    const normalized = normalizeNtfyServer(raw);
    if (normalized === null) {
      return { ok: false, error: 'invalid ntfy server' };
    }
    calls.push(['--set-server', normalized]);
  }

  if (Object.prototype.hasOwnProperty.call(patch, 'ntfy_enabled')) {
    const enabled = patch.ntfy_enabled;
    if (typeof enabled !== 'boolean') {
      return { ok: false, error: 'ntfy_enabled must be a boolean' };
    }
    calls.push(['--set-ntfy-enabled', enabled ? 'true' : 'false']);
  }

  if (Object.prototype.hasOwnProperty.call(patch, 'e2e_action')) {
    const action = patch.e2e_action;
    if (typeof action !== 'string' || !E2E_ACTIONS.has(action)) {
      return { ok: false, error: 'e2e_action must be one of enable, disable, rotate' };
    }
    calls.push(['--set-e2e', action]);
  }

  return { ok: true, calls };
}

/* ------------------------------------------------------------------ */
/* Pairing helper output                                               */
/* ------------------------------------------------------------------ */

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
 * `pairing.py` prints exactly one JSON object; it may span several lines, so
 * parse the whole output first and only fall back to the last line.
 */
function parsePairingOutput(stdout) {
  if (typeof stdout !== 'string') return null;
  const whole = parseJsonObject(stdout);
  if (whole) return whole;

  const lines = stdout.split(/\r?\n/).filter((line) => line.trim().length > 0);
  if (lines.length === 0) return null;
  return parseJsonObject(lines[lines.length - 1]);
}

/* ------------------------------------------------------------------ */
/* Python helper runner                                                */
/* ------------------------------------------------------------------ */

/** Keep only the tail of a stream so a chatty child cannot exhaust memory. */
function appendTail(current, chunk, limit) {
  return (current + chunk.toString()).slice(-limit);
}

/** Interpreters to try, in order; the same list `collector.js` uses. */
function resolvePythonCandidates() {
  const config = readJson(CONFIG_PATH) || {};
  const candidates = [config.python_path, process.env.PYTHON, 'python', 'py'].filter(
    (candidate) => typeof candidate === 'string' && candidate.trim().length > 0,
  );
  return collector.preferWindowlessPython(candidates);
}

/** Terminate the whole tree so a timeout never leaves an orphaned python behind. */
function killProcessTree(child) {
  if (!child || !child.pid) return;
  if (process.platform === 'win32') {
    try {
      const killer = spawn('taskkill', ['/PID', String(child.pid), '/T', '/F'], {
        windowsHide: true,
      });
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

/**
 * Run `collector/pairing.py` with the first interpreter that actually starts.
 * Resolves `{ started, code, stdout, stderr, timedOut, error }`.
 */
function runPairingHelper(args) {
  return new Promise((resolve) => {
    if (!fs.existsSync(PAIRING_SCRIPT_PATH)) {
      resolve({ started: false, error: 'Pairing helper not found' });
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
        child = spawn(python, [PAIRING_SCRIPT_PATH, ...args], {
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
          error: 'Pairing helper timeout',
        });
      }, PAIRING_TIMEOUT_MS);

      child.stdout.on('data', (chunk) => {
        stdout = appendTail(stdout, chunk, MAX_OUTPUT_BYTES);
      });
      child.stderr.on('data', (chunk) => {
        stderr = appendTail(stderr, chunk, MAX_STDERR_BYTES);
      });
      if (child.stdin) {
        // The helper never reads stdin; a closed pipe must not crash main.
        child.stdin.on('error', () => {});
        try {
          child.stdin.end();
        } catch {
          // The child failed early; `close`/`error` below still reports it.
        }
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
    };

    tryNext();
  });
}

/**
 * Run one pairing command and normalise the helper outcome to
 * `{ parsed, error }` where `error` is `''` on success.
 * Default runner for the two public functions; tests inject their own.
 */
async function runPairing(args) {
  const outcome = await runPairingHelper(Array.isArray(args) ? args : []);
  if (!outcome.started) {
    return { parsed: null, error: outcome.error || 'Python not found' };
  }
  if (outcome.timedOut) {
    return { parsed: null, error: 'Pairing helper timeout' };
  }

  const parsed = parsePairingOutput(outcome.stdout);
  if (!parsed) {
    const stderr = outcome.stderr ? outcome.stderr.trim() : '';
    return { parsed: null, error: stderr || 'Pairing helper produced no JSON output' };
  }

  if (outcome.code !== 0 || parsed.ok !== true) {
    let error = typeof parsed.error === 'string' ? parsed.error.trim() : '';
    if (!error && outcome.stderr) error = outcome.stderr.trim();
    if (!error) error = `Pairing helper failed (exit ${outcome.code})`;
    return { parsed, error };
  }

  return { parsed, error: '' };
}

/** Pick the injected runner or the real Python bridge. */
function pickRunner(options) {
  return options && typeof options.runner === 'function' ? options.runner : runPairing;
}

/** Call a runner defensively; a thrown error becomes `{ parsed: null, error }`. */
async function callRunner(runner, args) {
  try {
    const outcome = await runner(args);
    if (!outcome || typeof outcome !== 'object') {
      return { parsed: null, error: 'Pairing helper returned no result' };
    }
    return outcome;
  } catch (error) {
    return { parsed: null, error: String((error && error.message) || error) };
  }
}

/* ------------------------------------------------------------------ */
/* Public API                                                          */
/* ------------------------------------------------------------------ */

/**
 * Read the pairing state. The returned URI contains the topic and, with E2E
 * enabled, the key: callers may render it, but it must never be logged.
 */
async function getPairingSnapshot(options = {}) {
  const runner = pickRunner(options);
  const outcome = await callRunner(runner, ['--get']);

  if (outcome.error) return { ok: false, error: String(outcome.error) };
  const parsed = outcome.parsed;
  if (!parsed || typeof parsed !== 'object') {
    return { ok: false, error: 'Pairing helper produced no JSON output' };
  }
  if (parsed.ok === false) {
    const error = typeof parsed.error === 'string' ? parsed.error.trim() : '';
    return { ok: false, error: error || 'Pairing helper failed' };
  }

  const topic = typeof parsed.topic === 'string' ? parsed.topic : '';
  const server = typeof parsed.server === 'string' ? parsed.server : '';
  const uri = typeof parsed.uri === 'string' ? parsed.uri : '';

  // Defence in depth: the URI is what the phone scans, so it must agree with
  // the snapshot it was built from. Never log the URI itself.
  if (uri !== '') {
    const decoded = parsePairingUri(uri);
    if (!decoded || decoded.topic !== topic || decoded.server !== server) {
      return { ok: false, error: 'invalid pairing uri' };
    }
  }

  return {
    ok: true,
    topic,
    server,
    ntfy_enabled: parsed.ntfy_enabled === true,
    e2e_enabled: parsed.e2e_enabled === true,
    uri,
  };
}

/**
 * Apply a validated patch, then read a fresh snapshot. When the helper reports
 * `refresh` and publishing is on, the collector is kicked off in the
 * background; the caller never waits for it.
 */
async function updatePairing(patch, options = {}) {
  const validation = validatePairingPatch(patch);
  if (!validation.ok) return { ok: false, error: validation.error };

  const runner = pickRunner(options);
  let refresh = false;

  for (const args of validation.calls) {
    const outcome = await callRunner(runner, args);
    if (outcome.error) {
      const error = String(outcome.error);
      logError('pairing', `update ${args[0]} failed: ${error}`);
      return { ok: false, error };
    }
    if (!outcome.parsed || typeof outcome.parsed !== 'object') {
      logError('pairing', `update ${args[0]} produced no JSON output`);
      return { ok: false, error: 'Pairing helper produced no JSON output' };
    }
    if (outcome.parsed.refresh === true) refresh = true;
  }

  const snapshot = await getPairingSnapshot({ runner });

  if (refresh && snapshot.ok === true && snapshot.ntfy_enabled === true) {
    // Fire-and-forget: the Settings window gets the snapshot right away.
    try {
      void collector
        .runCollector({ force: true })
        .catch((error) => logError('pairing', String((error && error.message) || error)));
    } catch (error) {
      logError('pairing', String((error && error.message) || error));
    }
  }

  return snapshot;
}

module.exports = {
  PAIRING_TIMEOUT_MS,
  PAIRING_SCRIPT_PATH,
  normalizeNtfyServer,
  parsePairingUri,
  validatePairingPatch,
  parsePairingOutput,
  getPairingSnapshot,
  updatePairing,
};
