'use strict';

const assert = require('node:assert');
const test = require('node:test');

/**
 * `zcode-auth` destructures the collector API at require time, so the stub is
 * installed in the module cache before it loads and each call is delegated to a
 * mutable implementation the tests can swap.
 */
const calls = [];
let runCollectorImpl = async (options) => {
  calls.push(options);
  return { ok: true };
};
const collectorPath = require.resolve('../src/main/collector');
require.cache[collectorPath] = {
  id: collectorPath,
  filename: collectorPath,
  loaded: true,
  exports: {
    runCollector: (options) => runCollectorImpl(options),
    saveZCodeTokenViaPython: async () => ({ ok: true }),
    clearZCodeTokenViaPython: async () => ({ ok: true }),
  },
};

const zcodeAuth = require('../src/main/zcode-auth');

test('a sign-out publishes the signed-out state immediately', async () => {
  calls.length = 0;
  runCollectorImpl = async (options) => {
    calls.push(options);
    return { ok: true };
  };

  const result = await zcodeAuth.zcodeLogout();

  assert.equal(result.ok, true);
  assert.equal(result.cleared, true);
  assert.equal(calls.length, 1);
  // A plain refresh keeps the phone on the old ZCode balance until the heartbeat
  // or `changed && elapsed >= MIN_PUBLISH_INTERVAL_SEC` (~10 minutes).
  assert.equal(calls[0].force, true, 'sign-out must force an immediate publish');
  assert.equal(calls[0].waitIfBusy, false, 'sign-out must not block on a busy collector');
});

test('a busy collector still gets a forced background refresh', async () => {
  calls.length = 0;
  runCollectorImpl = async (options) => {
    calls.push(options);
    return { busy: true };
  };

  const result = await zcodeAuth.zcodeLogout();
  assert.equal(result.ok, true);
  assert.equal(result.refreshed, false, 'the UI must not wait for the busy collector');

  // The fallback run is fire-and-forget; let its microtask settle.
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(calls.length, 2);
  assert.equal(calls[1].force, true, 'the background retry must publish too');
  assert.equal(calls[1].waitIfBusy, true);
});
