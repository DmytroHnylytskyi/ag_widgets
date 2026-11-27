'use strict';

const crypto = require('crypto');
const { logError } = require('./logger');
const {
  runCollector,
  saveZCodeTokenViaPython,
  clearZCodeTokenViaPython,
} = require('./collector');

const ZCODE_INIT_URL = 'https://zcode.z.ai/api/v1/oauth/cli/init';
const ZCODE_POLL_BASE = 'https://zcode.z.ai/api/v1/oauth/cli/poll';
const LOGIN_TIMEOUT_MS = 300_000;
const CANCELLED_ERROR = 'Sign-in cancelled';

let loginInFlight = false;
let cancelController = null;

/**
 * Resolve the Electron shell lazily: this module must stay importable under
 * plain `node --test`, where the `electron` package is not available.
 */
function electronShell() {
  try {
    const electron = require('electron');
    return electron && electron.shell ? electron.shell : null;
  } catch {
    return null;
  }
}

function cancelledResult() {
  return { ok: false, error: CANCELLED_ERROR, cancelled: true };
}

/** Sleep that resolves early when the sign-in is cancelled. */
function sleep(ms, signal) {
  return new Promise((resolve) => {
    if (signal && signal.aborted) {
      resolve();
      return;
    }
    const finish = () => {
      clearTimeout(timer);
      if (signal) signal.removeEventListener('abort', finish);
      resolve();
    };
    const timer = setTimeout(finish, ms);
    if (signal) signal.addEventListener('abort', finish, { once: true });
  });
}

/**
 * Perform one JSON request. Authorized requests use `redirect: 'error'` so a
 * bearer token can never follow a redirect to another host. An external
 * `signal` (sign-in cancellation) aborts both the request and its body read.
 */
async function fetchJson(url, options = {}, timeoutMs = 20_000) {
  const { signal: externalSignal, ...rest } = options;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const abort = () => controller.abort();
  if (externalSignal) {
    if (externalSignal.aborted) controller.abort();
    else externalSignal.addEventListener('abort', abort, { once: true });
  }

  const headers = rest.headers || {};
  const authorized = Object.keys(headers).some(
    (name) => String(name).toLowerCase() === 'authorization',
  );

  try {
    const response = await fetch(url, {
      ...rest,
      redirect: authorized ? 'error' : rest.redirect,
      signal: controller.signal,
    });
    const text = await response.text();
    let json = null;
    try {
      json = JSON.parse(text);
    } catch {
      // Non-JSON error responses are handled by the HTTP status below.
    }
    return { status: response.status, json };
  } finally {
    clearTimeout(timer);
    if (externalSignal) externalSignal.removeEventListener('abort', abort);
  }
}

/**
 * Retry a JSON request on transport/network failures only. HTTP responses
 * (4xx/5xx) are results, not exceptions, so they are never retried; a user
 * cancellation is never retried either. Injectable for offline unit tests.
 */
async function fetchJsonWithRetry(
  url,
  options = {},
  {
    timeoutMs = 20_000,
    retryDelaysMs = [300, 800],
    fetchImpl = fetchJson,
    sleepImpl = sleep,
    logImpl = logError,
  } = {},
) {
  const signal = options ? options.signal : undefined;
  const isAborted = () => Boolean(signal && signal.aborted === true);
  const delays = Array.isArray(retryDelaysMs) ? retryDelaysMs : [];
  const attempts = 1 + delays.length;

  for (let attempt = 0; attempt < attempts; attempt += 1) {
    try {
      return await fetchImpl(url, options, timeoutMs);
    } catch (error) {
      // A cancelled sign-in must stop immediately; HTTP errors never get here.
      if (isAborted() || attempt >= attempts - 1) throw error;
      try {
        logImpl('zcode-login-retry', error);
      } catch {
        // Diagnostics must not break the retry loop.
      }
      await sleepImpl(delays[attempt], signal);
      if (isAborted()) throw error;
    }
  }

  // Unreachable: the last attempt either returns or throws.
  throw new Error('Request failed');
}

/**
 * Only open Z.ai HTTPS URLs, never an arbitrary scheme or host from a server
 * response, and never a URL carrying credentials or a non-standard port.
 */
function isTrustedAuthorizeUrl(value) {
  try {
    const url = new URL(String(value));
    return (
      url.protocol === 'https:' &&
      (url.hostname === 'z.ai' || url.hostname.endsWith('.z.ai')) &&
      !url.username &&
      !url.password &&
      (url.port === '' || url.port === '443')
    );
  } catch {
    return false;
  }
}

/**
 * Run the ZCode OAuth flow: open the browser, poll until the server returns a
 * token, save it through the Python DPAPI bridge, and refresh the collector.
 */
async function zcodeLogin(deps = {}) {
  const { fetchJsonImpl = fetchJsonWithRetry, logErrorImpl = logError } = deps || {};
  if (loginInFlight) return { ok: false, error: 'Login already in progress' };
  loginInFlight = true;
  const controller = new AbortController();
  cancelController = controller;

  try {
    const pollToken = crypto.randomBytes(32).toString('hex');
    const authHeaders = {
      Authorization: `Bearer ${pollToken}`,
      Accept: 'application/json',
    };

    // `Connection: close` stops Undici from reusing a keep-alive socket that
    // the server may have dropped right after a sign-out.
    const init = await fetchJsonImpl(ZCODE_INIT_URL, {
      method: 'POST',
      headers: { ...authHeaders, 'Content-Type': 'application/json', Connection: 'close' },
      body: JSON.stringify({ provider: 'zai' }),
      signal: controller.signal,
    });
    const initData = init.json && init.json.data;
    if (init.status !== 200 || !init.json || init.json.code !== 0 || !initData) {
      throw new Error(`OAuth init failed (HTTP ${init.status})`);
    }

    const flowId = String(initData.flow_id || '').trim();
    const authorizeUrl = String(initData.authorize_url || '').trim();
    if (!flowId || !authorizeUrl) throw new Error('OAuth init returned an empty flow');
    if (!isTrustedAuthorizeUrl(authorizeUrl)) throw new Error('Refusing an untrusted sign-in URL');

    const intervalSec = Math.max(2, Math.min(10, Number(initData.poll_interval_sec) || 3));
    const shell = electronShell();
    try {
      if (!shell || typeof shell.openExternal !== 'function') {
        throw new Error('Electron shell is unavailable');
      }
      await shell.openExternal(new URL(authorizeUrl).toString());
    } catch {
      throw new Error('Could not open the browser for sign-in');
    }

    const pollUrl = `${ZCODE_POLL_BASE}/${encodeURIComponent(flowId)}`;
    const deadline = Date.now() + LOGIN_TIMEOUT_MS;

    while (Date.now() < deadline) {
      await sleep(intervalSec * 1000, controller.signal);
      if (controller.signal.aborted) throw new Error(CANCELLED_ERROR);

      let poll;
      try {
        poll = await fetchJson(pollUrl, { headers: authHeaders, signal: controller.signal });
      } catch (error) {
        if (controller.signal.aborted) throw new Error(CANCELLED_ERROR);
        continue;
      }
      if (controller.signal.aborted) throw new Error(CANCELLED_ERROR);

      if (poll.status >= 400 && poll.status < 500 && poll.status !== 408 && poll.status !== 429) {
        throw new Error(`Sign-in session expired (HTTP ${poll.status})`);
      }

      const data = poll.json && poll.json.data;
      if (!data) continue;
      const status = String(data.status || '');
      if (status === 'pending') continue;
      if (status === 'failed') throw new Error('Sign-in was rejected');
      if (status === 'ready') {
        const token = String(data.token || '').trim();
        if (!token) throw new Error('Server did not return a token');
        if (controller.signal.aborted) throw new Error(CANCELLED_ERROR);

        // The Python helper is the single owner of secrets.json: it encrypts
        // the token with DPAPI and never exposes it through argv.
        const saved = await saveZCodeTokenViaPython(token);
        if (!saved || saved.ok !== true) {
          throw new Error((saved && saved.error) || 'Could not save the ZCode token');
        }

        // A sign-out may have started while the Python helper was writing the
        // token; undo that write so the user's cancellation always wins.
        if (controller.signal.aborted) {
          await clearZCodeTokenViaPython();
          return cancelledResult();
        }

        const refreshed = await runCollector({ force: true, waitIfBusy: true });
        return {
          ok: true,
          refreshed: Boolean(refreshed && refreshed.ok),
          encrypted: Boolean(saved.encrypted),
        };
      }
    }

    throw new Error('Sign-in timed out');
  } catch (error) {
    if (controller.signal.aborted) return cancelledResult();
    try {
      logErrorImpl('zcode-login', error);
    } catch {
      // Diagnostics must never mask the sign-in failure.
    }
    return { ok: false, error: String((error && error.message) || error) };
  } finally {
    loginInFlight = false;
    if (cancelController === controller) cancelController = null;
  }
}

/** Abort an active sign-in; safe to call at any time, including no-op cases. */
function cancelZCodeLogin() {
  const cancelled = Boolean(cancelController);
  if (cancelController) {
    try {
      cancelController.abort();
    } catch {
      // Already aborted.
    }
  }
  return { ok: true, cancelled };
}

/**
 * Forget the saved ZCode token through the Python DPAPI bridge and refresh the
 * collector so the widget switches to the signed-out state immediately.
 */
async function zcodeLogout() {
  cancelZCodeLogin();

  let cleared = false;
  try {
    const result = await clearZCodeTokenViaPython();
    if (!result || result.ok !== true) {
      throw new Error((result && result.error) || 'Could not clear the ZCode token');
    }
    cleared = true;

    // A sign-out must never block the UI on a collector run: ask for a
    // non-blocking refresh and fall back to a background one when busy.
    const refreshed = await runCollector({ force: false, waitIfBusy: false });
    if (refreshed && refreshed.busy === true) {
      runCollector({ force: false, waitIfBusy: true }).catch(() => {});
      return { ok: true, cleared: true, refreshed: false };
    }
    return {
      ok: true,
      cleared: true,
      refreshed: Boolean(refreshed && refreshed.ok),
    };
  } catch (error) {
    logError('zcode-token', error);
    return {
      ok: false,
      cleared,
      refreshed: false,
      error: String((error && error.message) || error),
    };
  }
}

module.exports = {
  zcodeLogin,
  cancelZCodeLogin,
  zcodeLogout,
  fetchJsonWithRetry,
  isTrustedAuthorizeUrl,
};
