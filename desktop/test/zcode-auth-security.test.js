'use strict';

/**
 * Unit tests for ZCode auth safety (OAuth URL allowlist, cancellation, secret
 * delegation) and for the pure view helpers of the ZCode card (tri-state
 * coupon and zero-balance rendering).
 *
 * Everything here is side-effect free: no network requests, no Python helper
 * runs, no DPAPI access, and no writes to %LOCALAPPDATA%. `electron` is
 * required lazily by the modules under test, so plain `node --test` works.
 */

const fs = require('fs');
const path = require('path');
const test = require('node:test');
const assert = require('node:assert/strict');

// `format.js` must land on the shared global before `zcode.js` reads it.
require('../src/renderer/format.js');
const security = require('../src/main/zcode-auth.js');
const logger = require('../src/main/logger.js');
const collector = require('../src/main/collector.js');
const zcodeView = require('../src/renderer/zcode.js');

const AUTH_SOURCE_PATH = path.join(__dirname, '..', 'src', 'main', 'zcode-auth.js');

/* ------------------------------------------------------------------ */
/* isTrustedAuthorizeUrl: allowed URLs                                 */
/* ------------------------------------------------------------------ */

test('isTrustedAuthorizeUrl accepts HTTPS Z.ai hosts', () => {
  const allowed = [
    'https://z.ai/auth?flow=1',
    'https://zcode.z.ai/api/v1/oauth/cli/authorize',
    'https://sub.z.ai:443/x',
  ];
  for (const url of allowed) {
    assert.equal(
      security.isTrustedAuthorizeUrl(url),
      true,
      `expected the URL to be trusted: ${url}`,
    );
  }
});

/* ------------------------------------------------------------------ */
/* isTrustedAuthorizeUrl: rejected URLs                                */
/* ------------------------------------------------------------------ */

test('isTrustedAuthorizeUrl rejects a non-HTTPS scheme', () => {
  assert.equal(security.isTrustedAuthorizeUrl('http://z.ai/auth'), false);
});

test('isTrustedAuthorizeUrl rejects lookalike and unrelated hosts', () => {
  const rejected = [
    'https://evil.com',
    'https://z.ai.evil.com',
    'https://notz.ai',
  ];
  for (const url of rejected) {
    assert.equal(
      security.isTrustedAuthorizeUrl(url),
      false,
      `expected the URL to be rejected: ${url}`,
    );
  }
});

test('isTrustedAuthorizeUrl rejects URLs carrying credentials', () => {
  const rejected = [
    'https://user:pass@z.ai/auth',
    'https://user@z.ai/auth',
  ];
  for (const url of rejected) {
    assert.equal(
      security.isTrustedAuthorizeUrl(url),
      false,
      `expected credentials to be rejected: ${url}`,
    );
  }
});

test('isTrustedAuthorizeUrl rejects a non-standard port', () => {
  assert.equal(security.isTrustedAuthorizeUrl('https://z.ai:8443/auth'), false);
});

test('isTrustedAuthorizeUrl rejects dangerous non-web schemes', () => {
  const rejected = [
    'file:///C:/Windows/System32/calc.exe',
    'javascript:alert(1)',
    'data:text/html,x',
  ];
  for (const url of rejected) {
    assert.equal(
      security.isTrustedAuthorizeUrl(url),
      false,
      `expected the scheme to be rejected: ${url}`,
    );
  }
});

test('isTrustedAuthorizeUrl returns false for garbage without throwing', () => {
  const garbage = ['', null, undefined, 'not a url', {}, 42];
  for (const value of garbage) {
    let result;
    assert.doesNotThrow(() => {
      result = security.isTrustedAuthorizeUrl(value);
    }, `threw on input: ${String(value)}`);
    assert.equal(result, false, `expected false for input: ${String(value)}`);
  }
});

/* ------------------------------------------------------------------ */
/* cancelZCodeLogin without an active sign-in                          */
/* ------------------------------------------------------------------ */

test('cancelZCodeLogin is a safe no-op when no sign-in is active', () => {
  assert.deepEqual(security.cancelZCodeLogin(), { ok: true, cancelled: false });
  // Idempotent: a second call must behave exactly the same.
  assert.deepEqual(security.cancelZCodeLogin(), { ok: true, cancelled: false });
  assert.doesNotThrow(() => security.cancelZCodeLogin());
});

/* ------------------------------------------------------------------ */
/* Secret delegation to the Python DPAPI owner (static source check)   */
/* ------------------------------------------------------------------ */

/**
 * Drop `//` and block comments while keeping string literals intact, so an
 * explanatory comment mentioning `secrets.json` is not mistaken for code.
 */
function stripComments(source) {
  let out = '';
  let quote = null;
  for (let i = 0; i < source.length; i += 1) {
    const char = source[i];
    const next = source[i + 1];
    if (quote) {
      out += char;
      if (char === '\\') {
        out += next || '';
        i += 1;
      } else if (char === quote) {
        quote = null;
      }
      continue;
    }
    if (char === '"' || char === "'" || char === '`') {
      quote = char;
      out += char;
      continue;
    }
    if (char === '/' && next === '/') {
      while (i < source.length && source[i] !== '\n') i += 1;
      out += '\n';
      continue;
    }
    if (char === '/' && next === '*') {
      i += 2;
      while (i < source.length && !(source[i] === '*' && source[i + 1] === '/')) i += 1;
      i += 1;
      out += ' ';
      continue;
    }
    out += char;
  }
  return out;
}

test('zcode-auth delegates secret persistence to the Python DPAPI bridge', () => {
  const source = fs.readFileSync(AUTH_SOURCE_PATH, 'utf8');
  const code = stripComments(source);
  assert.ok(!code.includes('secrets.json'), 'zcode-auth must not touch secrets.json');
  assert.ok(!code.includes('writeJsonAtomic'), 'zcode-auth must not write secrets itself');
  assert.ok(code.includes('saveZCodeTokenViaPython'), 'token save must go through Python');
  assert.ok(code.includes('clearZCodeTokenViaPython'), 'token clear must go through Python');
});

/* ------------------------------------------------------------------ */
/* describeCoupon: tri-state contract (§2.3)                           */
/* ------------------------------------------------------------------ */

test('describeCoupon reports a visible coupon with an ok tone', () => {
  const view = zcodeView.describeCoupon({ coupon_state: 'visible', coupon_visible: true });
  assert.equal(view.state, 'visible');
  assert.equal(view.tone, 'ok');
  assert.ok(view.text.length > 0, 'visible coupon needs a non-empty label');
});

test('describeCoupon reports a confirmed absence with the exact wording', () => {
  const view = zcodeView.describeCoupon({ coupon_state: 'absent', coupon_visible: false });
  assert.equal(view.state, 'absent');
  assert.equal(view.text, 'Нет активных ваучеров');
  assert.notEqual(view.tone, 'ok');
});

test('describeCoupon never claims "no coupon" on a 429 rate limit', () => {
  const view = zcodeView.describeCoupon({
    coupon_state: 'unknown',
    coupon_visible: null,
    error_kind: 'rate_limited',
  });
  assert.equal(view.state, 'unknown');
  assert.equal(view.tone, 'warn');
  assert.ok(view.text.includes('429'), `expected the 429 hint, got: ${view.text}`);
  assert.ok(
    !view.text.includes('Нет активных ваучеров'),
    'unknown state must never say there is no coupon',
  );
});

test('describeCoupon explains every error_kind of an unknown state', () => {
  const hints = {
    server_error: 'сервера',
    api_changed: 'API',
    network: 'связи',
    auth_expired: 'токен',
    parse_failed: 'разобрать',
  };
  for (const [errorKind, hint] of Object.entries(hints)) {
    const view = zcodeView.describeCoupon({
      coupon_state: 'unknown',
      coupon_visible: null,
      error_kind: errorKind,
    });
    assert.equal(view.state, 'unknown', `wrong state for ${errorKind}`);
    assert.equal(view.tone, 'warn', `wrong tone for ${errorKind}`);
    assert.ok(view.text.includes(hint), `${errorKind} text must mention "${hint}": ${view.text}`);
    assert.ok(
      !view.text.includes('Нет активных ваучеров'),
      `${errorKind} must never claim there is no coupon`,
    );
  }
});

test('describeCoupon trusts a legacy confirmed absence', () => {
  const view = zcodeView.describeCoupon({
    coupon_visible: false,
    auth_ok: true,
    network_error: false,
    error_kind: null,
  });
  assert.equal(view.state, 'absent');
  assert.equal(view.text, 'Нет активных ваучеров');
});

test('describeCoupon stays unknown for a legacy network failure', () => {
  const view = zcodeView.describeCoupon({
    coupon_visible: false,
    auth_ok: true,
    network_error: true,
    error_kind: null,
  });
  assert.equal(view.state, 'unknown');
  assert.ok(
    !view.text.includes('Нет активных ваучеров'),
    'a transport failure must not be reported as an absent coupon',
  );
});

test('describeCoupon treats a null coupon_visible as unknown', () => {
  const view = zcodeView.describeCoupon({ coupon_visible: null, coupon_state: null });
  assert.equal(view.state, 'unknown');
  assert.ok(!view.text.includes('Нет активных ваучеров'));
});

test('describeCoupon treats missing payloads as unknown', () => {
  for (const payload of [null, undefined]) {
    const view = zcodeView.describeCoupon(payload);
    assert.equal(view.state, 'unknown', `wrong state for ${String(payload)}`);
    assert.equal(view.tone, 'warn');
    assert.ok(view.text.length > 0);
  }
});

/* ------------------------------------------------------------------ */
/* balanceInfo: zero balance stays visible, unknown total hides ratio  */
/* ------------------------------------------------------------------ */

test('balanceInfo keeps a zero balance visible as an empty bar', () => {
  const info = zcodeView.balanceInfo({ remaining_units: 0, total_units: 500_000_000 });
  assert.equal(info.hasBalance, true);
  assert.equal(info.hasTotal, true);
  assert.equal(info.percent, 0);
  assert.equal(info.text, 'Остаток: 0 / 500M');
});

test('balanceInfo computes a percentage for a partial balance', () => {
  const info = zcodeView.balanceInfo({ remaining_units: 300_000_000, total_units: 500_000_000 });
  assert.equal(info.hasBalance, true);
  assert.equal(info.hasTotal, true);
  assert.equal(info.percent, 60);
});

test('balanceInfo hides the bar when the remaining amount is unknown', () => {
  const info = zcodeView.balanceInfo({ remaining_units: null, total_units: 500_000_000 });
  assert.equal(info.hasBalance, false);
  assert.equal(info.percent, null);
});

test('balanceInfo treats a zero total as no balance', () => {
  const info = zcodeView.balanceInfo({ remaining_units: 0, total_units: 0 });
  assert.equal(info.hasBalance, false);
  assert.equal(info.hasTotal, false);
  assert.equal(info.percent, null);
});

test('balanceInfo shows a total-less balance without a percentage', () => {
  const info = zcodeView.balanceInfo({ remaining_units: 5, total_units: null });
  assert.equal(info.hasBalance, true);
  assert.equal(info.hasTotal, false);
  assert.equal(info.percent, null);
  assert.equal(info.text, 'Остаток: 5');
});

test('balanceInfo handles a missing ZCode payload', () => {
  const info = zcodeView.balanceInfo(null);
  assert.equal(info.hasBalance, false);
  assert.equal(info.percent, null);
  assert.equal(info.text, '');
});

/* ------------------------------------------------------------------ */
/* Public exports and login state tracking                             */
/* ------------------------------------------------------------------ */

test('zcode view exposes its public API', () => {
  for (const name of ['render', 'setLogin', 'getLogin', 'describeCoupon', 'balanceInfo']) {
    assert.equal(typeof zcodeView[name], 'function', `missing export: ${name}`);
  }
});

test('setLogin stores the pending state and can be reset', () => {
  try {
    zcodeView.setLogin('pending');
    assert.equal(zcodeView.getLogin().state, 'pending');
  } finally {
    zcodeView.setLogin('idle');
  }
  assert.equal(zcodeView.getLogin().state, 'idle');
});

/* ------------------------------------------------------------------ */
/* logger.formatError: cause chain diagnostics                          */
/* ------------------------------------------------------------------ */

test('formatError includes the cause chain with code/errno/syscall', () => {
  const transport = new Error('connect ECONNREFUSED 127.0.0.1:443');
  transport.code = 'ECONNREFUSED';
  transport.errno = -111;
  transport.syscall = 'connect';
  const outer = new Error('OAuth init failed (HTTP 0)');
  outer.cause = transport;

  const text = logger.formatError(outer);
  assert.ok(text.includes('OAuth init failed'), 'top-level message must be present');
  assert.ok(text.includes('cause:'), 'cause line must be present');
  assert.ok(text.includes('connect ECONNREFUSED'), 'cause stack/message must be present');
  assert.ok(text.includes('code=ECONNREFUSED'), 'cause code must be present');
  assert.ok(text.includes('errno=-111'), 'cause errno must be present');
  assert.ok(text.includes('syscall=connect'), 'cause syscall must be present');
});

test('formatError omits empty cause fields and survives primitives', () => {
  const cause = new Error('plain cause');
  const outer = new Error('outer');
  outer.cause = cause;
  const text = logger.formatError(outer);
  assert.ok(!text.includes('code='), 'missing code/errno/syscall must not be printed');
  assert.ok(!text.includes('undefined'), 'empty fields must not leak as undefined');

  assert.equal(logger.formatError('boom'), 'boom');
  assert.equal(logger.formatError(null), 'null');
  assert.equal(logger.formatError(undefined), 'undefined');
  assert.doesNotThrow(() => logger.formatError(42));
  assert.doesNotThrow(() => logger.formatError({}));
});

test('formatError survives a cyclic cause reference', () => {
  const first = new Error('first');
  const second = new Error('second');
  first.cause = second;
  second.cause = first;

  let text;
  assert.doesNotThrow(() => {
    text = logger.formatError(first);
  });
  assert.ok(text.includes('second'), 'the first cause must be reported');
  // The cycle must stop instead of recursing forever.
  assert.ok(text.split('cause:').length <= 5, `cycle not bounded: ${text}`);
});

/* ------------------------------------------------------------------ */
/* collector.preferWindowlessPython: pythonw preference                */
/* ------------------------------------------------------------------ */

test('preferWindowlessPython puts pythonw.exe before python.exe on win32', () => {
  const winPath = 'C:\\Python311\\python.exe';
  const sibling = 'C:\\Python311\\pythonw.exe';
  const existsSync = (value) => value === sibling;

  const result = collector.preferWindowlessPython([winPath, 'py'], {
    platform: 'win32',
    existsSync,
  });
  assert.deepEqual(result, [sibling, winPath, 'py']);
  assert.equal(result[0], 'C:\\Python311\\pythonw.exe');
});

test('preferWindowlessPython puts pythonw before a bare python', () => {
  const result = collector.preferWindowlessPython(['python', 'py'], {
    platform: 'win32',
    existsSync: () => false,
  });
  assert.deepEqual(result, ['pythonw', 'python', 'py']);
});

test('preferWindowlessPython keeps python.exe as a fallback when pythonw is absent', () => {
  const result = collector.preferWindowlessPython(
    ['C:\\Python311\\python.exe'],
    { platform: 'win32', existsSync: () => false },
  );
  assert.deepEqual(result, ['C:\\Python311\\python.exe']);
});

test('preferWindowlessPython removes duplicates preserving order', () => {
  const result = collector.preferWindowlessPython(
    ['python', 'python', 'pythonw', 'py', 'py'],
    { platform: 'win32', existsSync: () => false },
  );
  assert.deepEqual(result, ['pythonw', 'python', 'py']);
});

test('preferWindowlessPython skips non-string candidates on win32', () => {
  const result = collector.preferWindowlessPython(
    ['python', 42, null, undefined, {}],
    { platform: 'win32', existsSync: () => false },
  );
  assert.deepEqual(result, ['pythonw', 'python']);
});

test('preferWindowlessPython leaves the list unchanged off Windows', () => {
  const input = ['C:\\Python311\\python.exe', 'python', 'py'];
  const existsSync = () => {
    throw new Error('existsSync must not be called off Windows');
  };
  const result = collector.preferWindowlessPython(input, { platform: 'linux', existsSync });
  assert.deepEqual(result, input);
  assert.notEqual(result, input, 'the result must be a copy');
});

/* ------------------------------------------------------------------ */
/* zcode-auth.fetchJsonWithRetry: transport-only retries               */
/* ------------------------------------------------------------------ */

test('fetchJsonWithRetry retries twice and succeeds on the third attempt', async () => {
  let calls = 0;
  const sleeps = [];
  const result = await security.fetchJsonWithRetry(
    'https://zcode.z.ai/api/v1/oauth/cli/init',
    {},
    {
      fetchImpl: async () => {
        calls += 1;
        if (calls < 3) throw new TypeError('fetch failed');
        return { status: 200, json: { code: 0 } };
      },
      sleepImpl: async (delay) => {
        sleeps.push(delay);
      },
      logImpl: () => {},
    },
  );

  assert.equal(calls, 3, 'one call plus two retries');
  assert.deepEqual(sleeps, [300, 800], 'retry delays must follow retryDelaysMs');
  assert.equal(result.status, 200);
});

test('fetchJsonWithRetry does not retry when the signal is already aborted', async () => {
  const controller = new AbortController();
  controller.abort();
  let calls = 0;

  await assert.rejects(
    security.fetchJsonWithRetry(
      'https://zcode.z.ai/api/v1/oauth/cli/init',
      { signal: controller.signal },
      {
        fetchImpl: async () => {
          calls += 1;
          throw new TypeError('fetch failed');
        },
        sleepImpl: async () => {
          throw new Error('sleep must not run after cancellation');
        },
        logImpl: () => {},
      },
    ),
    /fetch failed/,
  );
  assert.equal(calls, 1, 'a cancelled sign-in must not retry');
});

test('fetchJsonWithRetry does not retry an HTTP 400/500 response', async () => {
  for (const status of [400, 500]) {
    let calls = 0;
    const result = await security.fetchJsonWithRetry(
      'https://zcode.z.ai/api/v1/oauth/cli/init',
      {},
      {
        fetchImpl: async () => {
          calls += 1;
          return { status, json: null };
        },
        sleepImpl: async () => {
          throw new Error('sleep must not run for an HTTP response');
        },
        logImpl: () => {},
      },
    );
    assert.equal(calls, 1, `HTTP ${status} must not be retried`);
    assert.equal(result.status, status);
  }
});

test('fetchJsonWithRetry rethrows the last error after exhausting attempts', async () => {
  let calls = 0;
  const last = new TypeError('fetch failed');
  last.code = 'ECONNRESET';
  const sleeps = [];

  await assert.rejects(
    security.fetchJsonWithRetry(
      'https://zcode.z.ai/api/v1/oauth/cli/init',
      {},
      {
        fetchImpl: async () => {
          calls += 1;
          throw last;
        },
        sleepImpl: async (delay) => {
          sleeps.push(delay);
        },
        logImpl: () => {},
      },
    ),
    (error) => {
      assert.equal(error, last, 'the original error must be rethrown');
      return true;
    },
  );
  assert.equal(calls, 3, 'one call plus two retries');
  assert.deepEqual(sleeps, [300, 800]);
});

test('the init request sends Connection: close through the injected fetch', async () => {
  let captured = null;
  const result = await security.zcodeLogin({
    logErrorImpl: () => {},
    fetchJsonImpl: async (url, options) => {
      captured = { url, options };
      throw new TypeError('fetch failed');
    },
  });

  assert.ok(captured, 'the init request must be attempted');
  assert.equal(captured.url, 'https://zcode.z.ai/api/v1/oauth/cli/init');
  assert.equal(captured.options.method, 'POST');
  assert.equal(captured.options.headers.Connection, 'close');
  assert.equal(captured.options.headers['Content-Type'], 'application/json');
  assert.equal(result.ok, false);
});
