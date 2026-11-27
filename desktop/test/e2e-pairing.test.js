'use strict';

/**
 * Contract tests for the main-process pairing bridge
 * (`desktop/src/main/pairing.js`) against the frozen golden fixture
 * `tests/fixtures/e2e_pairing_cases.json` (schema `agw_e2e_pairing_v1`).
 *
 * The module under test spawns a Python helper in production. Every test here
 * injects a fake `runner`, so nothing touches the network, `%LOCALAPPDATA%`,
 * `secrets.json` or a Python interpreter.
 */

const fs = require('fs');
const path = require('path');
const test = require('node:test');
const assert = require('node:assert/strict');

const pairing = require('../src/main/pairing.js');
const collector = require('../src/main/collector.js');

/*
 * When a mutation asks for a refresh and the refreshed snapshot has publishing
 * on, `updatePairing` starts `collector.runCollector` fire-and-forget. That
 * would spawn the real Python collector, which these tests must never do, so
 * the collector entry point is replaced by a no-op for the whole suite.
 */
test.before(() => {
  collector.runCollector = async () => ({ ok: true, stubbed: true });
});

const FIXTURE_PATH = path.join(
  __dirname,
  '..',
  '..',
  'tests',
  'fixtures',
  'e2e_pairing_cases.json',
);

/** Load the frozen golden fixture or fail loudly with its absolute path. */
function loadFixture() {
  let raw;
  try {
    raw = fs.readFileSync(FIXTURE_PATH, 'utf8');
  } catch (error) {
    assert.fail(`missing golden fixture: ${FIXTURE_PATH} (${error.message})`);
  }
  try {
    return JSON.parse(raw);
  } catch (error) {
    assert.fail(`unparsable golden fixture: ${FIXTURE_PATH} (${error.message})`);
  }
}

const fixture = loadFixture();

/* ------------------------------------------------------------------ */
/* Fake runner and snapshot helpers                                    */
/* ------------------------------------------------------------------ */

/**
 * Build a fake runner that records every argument vector.
 * `responder(args, index)` returns `{ parsed, error }` for that call.
 */
function makeRunner(responder) {
  const calls = [];
  const runner = async (args) => {
    const copy = Array.isArray(args) ? args.slice() : args;
    calls.push(copy);
    return responder(copy, calls.length - 1);
  };
  runner.calls = calls;
  return runner;
}

/** A well-formed pairing snapshot, optionally overridden. */
function snapshotFor(overrides = {}) {
  const topic = overrides.topic !== undefined ? overrides.topic : 'agw-abc';
  const server =
    overrides.server !== undefined ? overrides.server : 'https://ntfy.sh';
  const serverQuery =
    server === 'https://ntfy.sh' ? '' : `&s=${encodeURIComponent(server)}`;
  return {
    ok: true,
    topic,
    server,
    ntfy_enabled: overrides.ntfy_enabled !== undefined ? overrides.ntfy_enabled : true,
    e2e_enabled: overrides.e2e_enabled !== undefined ? overrides.e2e_enabled : false,
    uri: `agw://pair?v=1&t=${topic}${serverQuery}`,
  };
}

/* ------------------------------------------------------------------ */
/* Golden fixture                                                      */
/* ------------------------------------------------------------------ */

test('the frozen pairing fixture is present and well-formed', () => {
  assert.equal(fixture.schema, 'agw_e2e_pairing_v1', 'unexpected fixture schema');
  assert.equal(fixture.aad, 'agw-ntfy-e2e:v1', 'unexpected AAD');
  assert.equal(fixture.default_ntfy_server, 'https://ntfy.sh', 'unexpected default server');
  assert.ok(Array.isArray(fixture.server_url_cases.valid), 'valid server cases missing');
  assert.ok(Array.isArray(fixture.server_url_cases.invalid), 'invalid server cases missing');
  assert.ok(Array.isArray(fixture.pairing_uri_cases.valid), 'valid URI cases missing');
  assert.ok(Array.isArray(fixture.pairing_uri_cases.invalid), 'invalid URI cases missing');
});

/* ------------------------------------------------------------------ */
/* Module constants                                                    */
/* ------------------------------------------------------------------ */

test('the module exposes the documented timeout and script path', () => {
  assert.equal(pairing.PAIRING_TIMEOUT_MS, 10000, 'the pairing timeout must be 10 seconds');
  assert.equal(typeof pairing.PAIRING_SCRIPT_PATH, 'string', 'the script path must be a string');
  assert.equal(
    path.basename(pairing.PAIRING_SCRIPT_PATH),
    'pairing.py',
    'the helper must be collector/pairing.py',
  );
});

/* ------------------------------------------------------------------ */
/* normalizeNtfyServer: golden server URLs                             */
/* ------------------------------------------------------------------ */

test('normalizeNtfyServer normalizes every valid server URL', () => {
  for (const item of fixture.server_url_cases.valid) {
    assert.equal(
      pairing.normalizeNtfyServer(item.input),
      item.expected,
      `unexpected normalization for ${JSON.stringify(item.input)}`,
    );
  }
});

test('normalizeNtfyServer rejects every invalid server URL', () => {
  for (const input of fixture.server_url_cases.invalid) {
    assert.equal(
      pairing.normalizeNtfyServer(input),
      null,
      `expected null for ${JSON.stringify(input)}`,
    );
  }
});

/* ------------------------------------------------------------------ */
/* normalizeNtfyServer: extra hostile inputs                           */
/* ------------------------------------------------------------------ */

test('normalizeNtfyServer tolerates non-strings and rejects hostile values', () => {
  for (const input of [null, undefined, 42, {}, true, [], () => {}]) {
    assert.equal(
      pairing.normalizeNtfyServer(input),
      null,
      `expected null for ${typeof input}`,
    );
  }

  const invalidStrings = [
    'https://ntfy.sh:0',
    'https://ntfy.sh:65536',
    'ftp://ntfy.sh',
    'https://ntfy .sh',
    // A real NUL is a control character and must be rejected. (The literal
    // two-character sequence "\\x00" is not a control character and passes the
    // mirrored NTFY_URL_RE in agwidgets/e2e.py, so it is not a negative case.)
    'https://ntfy.sh\u0000',
  ];
  for (const input of invalidStrings) {
    assert.equal(
      pairing.normalizeNtfyServer(input),
      null,
      `expected null for ${JSON.stringify(input)}`,
    );
  }
});

test('normalizeNtfyServer rejects a 257-character string', () => {
  const tooLong = 'https://ntfy.sh/' + 'a'.repeat(241);
  assert.equal(tooLong.length, 257, 'the probe must be exactly 257 characters');
  assert.equal(pairing.normalizeNtfyServer(tooLong), null, 'over-long servers are rejected');
});

/* ------------------------------------------------------------------ */
/* parsePairingUri: golden cases                                       */
/* ------------------------------------------------------------------ */

test('parsePairingUri accepts every valid fixture URI', () => {
  for (const item of fixture.pairing_uri_cases.valid) {
    const parsed = pairing.parsePairingUri(item.uri);
    assert.notEqual(parsed, null, `expected a result for ${item.uri}`);
    assert.equal(parsed.v, 1, `unsupported version for ${item.uri}`);
    assert.equal(parsed.topic, item.topic, `unexpected topic for ${item.uri}`);
    assert.equal(parsed.server, item.server, `unexpected server for ${item.uri}`);
    if (item.e2e_key === null) {
      assert.strictEqual(parsed.e2e_key, null, `expected null key for ${item.uri}`);
    } else {
      assert.equal(parsed.e2e_key, item.e2e_key, `unexpected key for ${item.uri}`);
    }
  }
});

test('parsePairingUri rejects every invalid fixture URI', () => {
  for (const item of fixture.pairing_uri_cases.invalid) {
    assert.strictEqual(
      pairing.parsePairingUri(item.uri),
      null,
      `expected null (${item.reason}) for ${item.uri}`,
    );
  }
});

/* ------------------------------------------------------------------ */
/* parsePairingUri: extra edge cases                                   */
/* ------------------------------------------------------------------ */

test('parsePairingUri handles a missing key and unknown parameters', () => {
  const withoutKey = pairing.parsePairingUri('agw://pair?v=1&t=agw-abc');
  assert.notEqual(withoutKey, null, 'a keyless URI must still pair');
  assert.strictEqual(withoutKey.e2e_key, null, 'a missing key must stay null');

  const withExtra = pairing.parsePairingUri('agw://pair?v=1&t=agw-abc&foo=bar&t2=x');
  assert.notEqual(withExtra, null, 'unknown parameters must be ignored');
  assert.equal(withExtra.topic, 'agw-abc', 'unknown parameters must not change the topic');
  assert.equal(withExtra.server, 'https://ntfy.sh', 'the default server must be used');
  assert.strictEqual(withExtra.e2e_key, null, 'no key must be inferred from extras');
});

test('parsePairingUri accepts the trailing-slash form', () => {
  const parsed = pairing.parsePairingUri('agw://pair/?v=1&t=agw-abc');
  assert.notEqual(parsed, null, 'agw://pair/ must be valid');
  assert.equal(parsed.topic, 'agw-abc', 'the topic must survive the slash form');
});

/* ------------------------------------------------------------------ */
/* validatePairingPatch: shape rejection                               */
/* ------------------------------------------------------------------ */

test('validatePairingPatch rejects non-objects and empty patches', () => {
  for (const patch of [null, undefined, 'ntfy_enabled=true', [], 42, true, () => {}]) {
    const result = pairing.validatePairingPatch(patch);
    assert.equal(result.ok, false, `expected rejection for ${typeof patch}`);
    assert.equal(typeof result.error, 'string', 'a rejection must carry an error string');
    assert.ok(result.error.length > 0, 'a rejection must carry a message');
  }

  const empty = pairing.validatePairingPatch({});
  assert.equal(empty.ok, false, 'an empty patch changes nothing and must be rejected');
  assert.equal(typeof empty.error, 'string', 'an empty patch must explain itself');
});

test('validatePairingPatch rejects unknown keys', () => {
  const result = pairing.validatePairingPatch({ foo: 1 });
  assert.equal(result.ok, false, 'unknown keys must be rejected');
  assert.equal(typeof result.error, 'string', 'a rejection must carry an error string');
});

/* ------------------------------------------------------------------ */
/* validatePairingPatch: ntfy_enabled                                  */
/* ------------------------------------------------------------------ */

test('validatePairingPatch maps ntfy_enabled to a boolean CLI argument', () => {
  const stringified = pairing.validatePairingPatch({ ntfy_enabled: 'true' });
  assert.equal(stringified.ok, false, 'a string boolean must be rejected');

  const enabled = pairing.validatePairingPatch({ ntfy_enabled: true });
  assert.equal(enabled.ok, true, 'a boolean must be accepted');
  assert.deepEqual(
    enabled.calls,
    [['--set-ntfy-enabled', 'true']],
    'true must become the literal "true"',
  );

  const disabled = pairing.validatePairingPatch({ ntfy_enabled: false });
  assert.equal(disabled.ok, true, 'false must be accepted');
  assert.deepEqual(
    disabled.calls,
    [['--set-ntfy-enabled', 'false']],
    'false must become the literal "false"',
  );
});

/* ------------------------------------------------------------------ */
/* validatePairingPatch: ntfy_server                                   */
/* ------------------------------------------------------------------ */

test('validatePairingPatch forwards the normalized ntfy_server', () => {
  const normalized = pairing.validatePairingPatch({ ntfy_server: 'https://ntfy.sh:443/' });
  assert.equal(normalized.ok, true, 'a valid server must be accepted');
  assert.deepEqual(
    normalized.calls,
    [['--set-server', 'https://ntfy.sh']],
    'the server must be normalized before it reaches the helper',
  );

  const cleartext = pairing.validatePairingPatch({ ntfy_server: 'http://ntfy.sh' });
  assert.equal(cleartext.ok, false, 'cleartext HTTP must be rejected');
  assert.equal(typeof cleartext.error, 'string', 'a rejection must carry an error string');
});

/* ------------------------------------------------------------------ */
/* validatePairingPatch: e2e_action                                    */
/* ------------------------------------------------------------------ */

test('validatePairingPatch maps known e2e actions and rejects the rest', () => {
  for (const action of ['enable', 'disable', 'rotate']) {
    const result = pairing.validatePairingPatch({ e2e_action: action });
    assert.equal(result.ok, true, `action ${action} must be accepted`);
    assert.deepEqual(result.calls, [['--set-e2e', action]], `action ${action} must be forwarded`);
  }

  const bogus = pairing.validatePairingPatch({ e2e_action: 'bogus' });
  assert.equal(bogus.ok, false, 'unknown actions must be rejected');
  assert.equal(typeof bogus.error, 'string', 'a rejection must carry an error string');
});

/* ------------------------------------------------------------------ */
/* validatePairingPatch: combined ordering                             */
/* ------------------------------------------------------------------ */

test('validatePairingPatch emits server, then enabled, then action', () => {
  const result = pairing.validatePairingPatch({
    e2e_action: 'rotate',
    ntfy_enabled: true,
    ntfy_server: 'https://ntfy.sh:443/',
  });
  assert.equal(result.ok, true, 'a fully valid patch must be accepted');
  assert.deepEqual(
    result.calls,
    [
      ['--set-server', 'https://ntfy.sh'],
      ['--set-ntfy-enabled', 'true'],
      ['--set-e2e', 'rotate'],
    ],
    'the helper must be driven in a deterministic order',
  );
});

test('validatePairingPatch rejects a patch with one bad field', () => {
  const result = pairing.validatePairingPatch({
    ntfy_server: 'https://ntfy.sh',
    e2e_action: 'explode',
  });
  assert.equal(result.ok, false, 'an invalid action must poison the whole patch');
  assert.equal(
    result.calls === undefined || result.calls.length === 0,
    true,
    'a rejected patch must not schedule any calls',
  );
});

/* ------------------------------------------------------------------ */
/* parsePairingOutput                                                  */
/* ------------------------------------------------------------------ */

test('parsePairingOutput accepts compact and pretty-printed JSON', () => {
  const compact = '{"ok":true,"topic":"agw-abc"}';
  assert.deepEqual(pairing.parsePairingOutput(compact), { ok: true, topic: 'agw-abc' });

  const payload = {
    ok: true,
    topic: 'agw-abc',
    server: 'https://ntfy.sh',
    ntfy_enabled: true,
    e2e_enabled: false,
    uri: 'agw://pair?v=1&t=agw-abc',
  };
  const pretty = JSON.stringify(payload, null, 2);
  assert.ok(pretty.includes('\n'), 'the probe must really be multi-line');
  assert.deepEqual(pairing.parsePairingOutput(pretty), payload);
});

test('parsePairingOutput falls back to the last non-empty line', () => {
  assert.deepEqual(
    pairing.parsePairingOutput('warning: python is cold\n{"ok":true}'),
    { ok: true },
    'a leading warning line must not break parsing',
  );
  assert.deepEqual(
    pairing.parsePairingOutput('{"ok":true}\n\n'),
    { ok: true },
    'trailing blank lines must be tolerated',
  );
});

test('parsePairingOutput returns null for empty or non-object output', () => {
  for (const stdout of ['', '   ', '\n\n', 'not json', '[1,2]', '"str"', '42']) {
    assert.strictEqual(
      pairing.parsePairingOutput(stdout),
      null,
      `expected null for ${JSON.stringify(stdout)}`,
    );
  }
});

/* ------------------------------------------------------------------ */
/* getPairingSnapshot                                                  */
/* ------------------------------------------------------------------ */

test('getPairingSnapshot asks the helper for --get and returns the snapshot', async () => {
  const snapshot = snapshotFor();
  const runner = makeRunner(() => ({ parsed: { ...snapshot }, error: '' }));

  const result = await pairing.getPairingSnapshot({ runner });

  assert.deepEqual(runner.calls, [['--get']], 'the helper must be called with --get exactly once');
  assert.equal(result.ok, true, 'a healthy snapshot must be ok');
  assert.equal(result.topic, snapshot.topic, 'the topic must be preserved');
  assert.equal(result.server, snapshot.server, 'the server must be preserved');
  assert.equal(result.ntfy_enabled, snapshot.ntfy_enabled, 'ntfy_enabled must be preserved');
  assert.equal(result.e2e_enabled, snapshot.e2e_enabled, 'e2e_enabled must be preserved');
  assert.equal(result.uri, snapshot.uri, 'the pairing URI must be preserved');
});

test('getPairingSnapshot surfaces a runner failure', async () => {
  const runner = makeRunner(() => ({ parsed: null, error: 'spawn failed' }));

  const result = await pairing.getPairingSnapshot({ runner });

  assert.deepEqual(runner.calls, [['--get']], 'the helper must still be asked once');
  assert.equal(result.ok, false, 'a runner failure must not look healthy');
  assert.equal(result.error, 'spawn failed', 'the runner error must be preserved');
});

test('getPairingSnapshot rejects a snapshot with an untrusted pairing URI', async () => {
  const snapshot = { ...snapshotFor(), uri: 'https://evil.example/x' };
  const runner = makeRunner(() => ({ parsed: snapshot, error: '' }));

  const result = await pairing.getPairingSnapshot({ runner });

  assert.equal(result.ok, false, 'an untrusted URI must be rejected');
  assert.equal(result.error, 'invalid pairing uri', 'the rejection must be explicit');
});

test('getPairingSnapshot rejects a URI whose topic disagrees with the snapshot', async () => {
  const snapshot = { ...snapshotFor(), uri: 'agw://pair?v=1&t=agw-def' };
  const runner = makeRunner(() => ({ parsed: snapshot, error: '' }));

  const result = await pairing.getPairingSnapshot({ runner });

  assert.equal(result.ok, false, 'a topic mismatch must be rejected');
  assert.equal(typeof result.error, 'string', 'a rejection must carry an error string');
});

/* ------------------------------------------------------------------ */
/* updatePairing                                                       */
/* ------------------------------------------------------------------ */

test('updatePairing normalizes the server and orders its calls', async () => {
  const fresh = snapshotFor({ ntfy_enabled: true });
  const runner = makeRunner((args) => {
    if (args[0] === '--get') return { parsed: { ...fresh }, error: '' };
    return { parsed: { ok: true, changed: true, refresh: true }, error: '' };
  });

  const result = await pairing.updatePairing(
    { ntfy_server: 'https://ntfy.sh:443/', ntfy_enabled: true },
    { runner },
  );

  assert.deepEqual(
    runner.calls,
    [
      ['--set-server', 'https://ntfy.sh'],
      ['--set-ntfy-enabled', 'true'],
      ['--get'],
    ],
    'mutations must precede the refresh and use the normalized server',
  );
  assert.equal(result.ok, true, 'a successful update must be ok');
  assert.equal(result.server, fresh.server, 'the fresh snapshot must be returned');
});

test('updatePairing stops the chain at the first failing call', async () => {
  const runner = makeRunner((args) => {
    if (args[0] === '--set-server') return { parsed: null, error: 'spawn failed' };
    return { parsed: snapshotFor(), error: '' };
  });

  const result = await pairing.updatePairing(
    { ntfy_server: 'https://ntfy.sh', ntfy_enabled: true },
    { runner },
  );

  assert.deepEqual(
    runner.calls,
    [['--set-server', 'https://ntfy.sh']],
    'later mutations and the refresh must not run after a failure',
  );
  assert.equal(result.ok, false, 'a failed chain must be reported');
  assert.equal(result.error, 'spawn failed', 'the runner error must be preserved');
});

test('updatePairing returns a {ok:false} mutation reply instead of refreshing', async () => {
  // The real runner (`runPairing`) turns the helper's `{"ok":false,...}` output
  // into `{ parsed, error }` with a non-empty error, so mirror that shape.
  const runner = makeRunner((args) => {
    if (args[0] === '--get') return { parsed: snapshotFor(), error: '' };
    return {
      parsed: { ok: false, error: 'server rejected' },
      error: 'server rejected',
    };
  });

  const result = await pairing.updatePairing({ ntfy_enabled: true }, { runner });

  assert.deepEqual(
    runner.calls,
    [['--set-ntfy-enabled', 'true']],
    'a rejected mutation must not be followed by a refresh',
  );
  assert.equal(result.ok, false, 'the mutation failure must be surfaced');
  assert.equal(result.error, 'server rejected', 'the helper message must be preserved');
});

test('updatePairing returns the snapshot read after the mutations', async () => {
  const fresh = snapshotFor({ server: 'https://ntfy.example.com/sub', ntfy_enabled: false });
  const runner = makeRunner((args) => {
    if (args[0] === '--get') return { parsed: { ...fresh }, error: '' };
    return { parsed: { ok: true, changed: true, refresh: false }, error: '' };
  });

  const result = await pairing.updatePairing(
    { ntfy_server: 'https://ntfy.example.com/sub' },
    { runner },
  );

  assert.deepEqual(
    runner.calls,
    [['--set-server', 'https://ntfy.example.com/sub'], ['--get']],
    'the refresh must come after the mutation',
  );
  assert.equal(result.ok, true, 'the refreshed snapshot must be healthy');
  assert.equal(result.server, 'https://ntfy.example.com/sub', 'the fresh server must be returned');
  assert.equal(result.ntfy_enabled, false, 'the fresh flags must be returned, not the mutation reply');
});

test('updatePairing resolves even when the helper keeps asking for a refresh', async () => {
  const runner = makeRunner((args) => {
    if (args[0] === '--get') {
      return { parsed: snapshotFor({ ntfy_enabled: true }), error: '' };
    }
    return { parsed: { ok: true, changed: true, refresh: true }, error: '' };
  });

  let timer;
  const timeout = new Promise((resolve) => {
    timer = setTimeout(() => resolve('timeout'), 1500);
    if (typeof timer.unref === 'function') timer.unref();
  });

  let outcome;
  try {
    outcome = await Promise.race([
      pairing.updatePairing({ ntfy_enabled: true }, { runner }).then(() => 'resolved'),
      timeout,
    ]);
  } finally {
    clearTimeout(timer);
  }

  assert.equal(outcome, 'resolved', 'a refresh flag must never deadlock updatePairing');
});
