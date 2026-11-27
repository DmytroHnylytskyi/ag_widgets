'use strict';

/**
 * Unit tests for the desktop notification contract.
 * Plain Node only: no Electron runtime is required.
 * Run with: node --test desktop/test/notifications.test.js
 */

const fs = require('fs');
const os = require('os');
const path = require('path');
const test = require('node:test');
const assert = require('node:assert/strict');

const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'agw-notifications-'));
process.env.AGW_USER_DATA_DIR = path.join(tmpRoot, 'bootstrap');

const notifications = require('../src/main/notifications.js');
const WINDOW = 524288;

/** Point the module at a clean profile directory and drop in-memory state. */
function freshPrefsDir(name) {
  const dir = path.join(tmpRoot, name);
  fs.mkdirSync(dir, { recursive: true });
  process.env.AGW_USER_DATA_DIR = dir;
  notifications.resetRuntime();
  return dir;
}

/** One conversation entry as the renderer/collector contract stores it. */
function conv(model, tokens, redPct = 65) {
  return {
    model,
    tokens,
    window: WINDOW,
    yellow_pct: 40,
    red_pct: redPct,
    updated_at: '2026-11-26T10:00:00Z',
  };
}

function stateWith({
  conversations = {},
  last = null,
  activeId = null,
  zcode = null,
  buckets = [],
} = {}) {
  return {
    context: { last, conversations },
    status: { zcode, antigravity: { buckets } },
    activeChat: activeId ? { id: activeId, title: 'Чат' } : null,
    chatTitles: {},
    now: Date.parse('2026-11-26T12:00:00Z'),
  };
}

const RED_CONV = () => conv('gemini-4-pro', 377000);
const YELLOW_CONV = () => conv('gemini-4-pro', 250000);
const YELLOW_STATE = () =>
  stateWith({ conversations: { a: YELLOW_CONV() }, last: 'a', activeId: 'a' });
const RED_STATE = () =>
  stateWith({ conversations: { a: RED_CONV() }, last: 'a', activeId: 'a' });

const VISIBLE_VOUCHER = (matched = '300M токенов') => ({
  coupon_state: 'visible',
  coupon_visible: true,
  network_error: false,
  auth_ok: true,
  plan_id: 'pro',
  matched,
});

function bucket(overrides = {}) {
  return {
    id: 'gemini-5h',
    kind: '5h',
    remaining_fraction: 0.1,
    reset_time: '2026-11-26T15:00:00Z',
    ...overrides,
  };
}

function readPrefsFile() {
  return JSON.parse(fs.readFileSync(notifications.notificationsPath(), 'utf8'));
}

// --- normalizeNotificationPrefs -------------------------------------------

test('normalizeNotificationPrefs fills defaults and never throws', () => {
  for (const raw of [null, undefined, 'garbage', 42, [], true]) {
    assert.deepEqual(
      notifications.normalizeNotificationPrefs(raw),
      notifications.DEFAULT_PREFERENCES,
      `unexpected defaults for ${JSON.stringify(raw)}`,
    );
  }

  const partial = notifications.normalizeNotificationPrefs({
    enabled: false,
    context_red: 'yes',
  });
  assert.equal(partial.enabled, false);
  assert.equal(partial.context_red, true, 'non-boolean values fall back to the default');
  assert.equal(partial.zcode_voucher, true);
  assert.equal(partial.quota_5h_reset, true);
});

test('normalizeNotificationPrefs keeps notified_resets unique and capped at 30', () => {
  const raw = [];
  for (let i = 0; i < 34; i += 1) raw.push(`chat-${i}|reset-${i}`);
  raw.push('chat-3|reset-3'); // duplicate of an earlier entry

  const normalized = notifications.normalizeNotificationPrefs({ notified_resets: raw });

  assert.equal(normalized.notified_resets.length, 30);
  assert.equal(new Set(normalized.notified_resets).size, 30, 'duplicates must be dropped');
  assert.equal(
    normalized.notified_resets[29],
    'chat-3|reset-3',
    'a repeated key moves to the end before the FIFO slice',
  );
  assert.equal(notifications.MAX_NOTIFIED_RESETS, 30);
});

// --- persistence -----------------------------------------------------------

test('loadPreferences falls back to defaults on a corrupt file', () => {
  const dir = freshPrefsDir('corrupt');
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(notifications.notificationsPath(), '{oops', 'utf8');

  assert.deepEqual(notifications.loadPreferences(), notifications.DEFAULT_PREFERENCES);
});

test('updatePreferences persists only known boolean keys', () => {
  freshPrefsDir('update');
  notifications.updatePreferences({ enabled: false, bogus: 1 });

  const stored = notifications.getPreferences();
  assert.equal(stored.enabled, false);
  assert.equal('bogus' in stored, false, 'unknown keys must not be persisted');
  assert.equal(stored.context_red, true);
  assert.equal(stored.zcode_voucher, true);
  assert.equal(stored.quota_5h_reset, true);
});

test('getPublicPreferences hides dedup state', () => {
  freshPrefsDir('public');
  const publicPrefs = notifications.getPublicPreferences();

  assert.deepEqual(
    Object.keys(publicPrefs).sort(),
    ['context_red', 'enabled', 'quota_5h_reset', 'zcode_voucher'].sort(),
  );
});

// --- red-zone events -------------------------------------------------------

test('the first snapshot is baseline only', () => {
  freshPrefsDir('baseline');

  assert.deepEqual(notifications.handleStateUpdate(RED_STATE()), []);
  assert.deepEqual(notifications.handleStateUpdate(RED_STATE()), []);

  const evaluated = notifications.evaluateNotifications(
    null,
    RED_STATE(),
    notifications.getPreferences(),
    {},
  );
  assert.deepEqual(evaluated.events, []);
});

test('a yellow to red transition notifies once', () => {
  freshPrefsDir('yellow-red');

  assert.deepEqual(notifications.handleStateUpdate(YELLOW_STATE()), []);

  const events = notifications.handleStateUpdate(RED_STATE());
  assert.equal(events.length, 1);
  assert.equal(events[0].kind, 'context_red');
  assert.equal(events[0].title, 'Пора сделать /compact');
  assert.equal(events[0].body, 'Gemini 4 Pro: 377K (72%) — контекст в красной зоне');

  assert.deepEqual(notifications.handleStateUpdate(RED_STATE()), []);
});

test('returning to yellow re-arms the red alert', () => {
  freshPrefsDir('rearm');

  notifications.handleStateUpdate(YELLOW_STATE());
  assert.equal(notifications.handleStateUpdate(RED_STATE()).length, 1);
  assert.deepEqual(notifications.handleStateUpdate(YELLOW_STATE()), []);
  assert.equal(notifications.handleStateUpdate(RED_STATE()).length, 1);
});

test('switching to a different red chat notifies', () => {
  freshPrefsDir('switch');

  const bothRed = () => ({
    context: { last: 'a', conversations: { a: RED_CONV(), b: RED_CONV() } },
    status: { zcode: null, antigravity: { buckets: [] } },
    activeChat: { id: 'a', title: 'Чат A' },
    chatTitles: {},
    now: Date.parse('2026-11-26T12:00:00Z'),
  });

  assert.deepEqual(notifications.handleStateUpdate(bothRed()), []);

  const switched = bothRed();
  switched.activeChat = { id: 'b', title: 'Чат B' };
  switched.context.last = 'b';

  const events = notifications.handleStateUpdate(switched);
  assert.equal(events.length, 1);
  assert.equal(events[0].kind, 'context_red');
});

test('context_red toggle silences the red zone', () => {
  freshPrefsDir('no-red');
  notifications.updatePreferences({ context_red: false });

  notifications.handleStateUpdate(YELLOW_STATE());
  assert.deepEqual(notifications.handleStateUpdate(RED_STATE()), []);

  // Other channels stay alive: a voucher still notifies afterwards.
  const events = notifications.handleStateUpdate(
    stateWith({ conversations: { a: RED_CONV() }, last: 'a', activeId: 'a', zcode: VISIBLE_VOUCHER() }),
  );
  assert.equal(events.length, 1);
  assert.equal(events[0].kind, 'zcode_voucher');
});

test('enabled false silences every channel', () => {
  freshPrefsDir('disabled');
  notifications.updatePreferences({ enabled: false });

  notifications.handleStateUpdate(
    stateWith({
      conversations: { a: YELLOW_CONV() },
      last: 'a',
      activeId: 'a',
      zcode: { coupon_state: 'hidden', coupon_visible: false },
      buckets: [bucket()],
    }),
  );

  const events = notifications.handleStateUpdate(
    stateWith({
      conversations: { a: RED_CONV() },
      last: 'a',
      activeId: 'a',
      zcode: VISIBLE_VOUCHER(),
      buckets: [bucket({ remaining_fraction: 0.95 })],
    }),
  );

  assert.deepEqual(events, []);
});

// --- ZCode voucher ---------------------------------------------------------

test('a visible voucher notifies and is remembered on disk', () => {
  freshPrefsDir('voucher');

  const hidden = stateWith({ zcode: { coupon_state: 'hidden', coupon_visible: false } });
  const visible = stateWith({ zcode: VISIBLE_VOUCHER() });

  assert.deepEqual(notifications.handleStateUpdate(hidden), []);

  const first = notifications.handleStateUpdate(visible);
  assert.equal(first.length, 1);
  assert.equal(first[0].kind, 'zcode_voucher');
  assert.equal(first[0].title, 'В ZCode доступен ваучер!');
  assert.equal(first[0].body, '300M токенов');

  assert.equal(readPrefsFile().last_coupon_key, 'pro|300M токенов');

  // Simulated restart: the key is read back from disk, not from memory.
  notifications.resetRuntime();
  assert.deepEqual(notifications.handleStateUpdate(visible), []);
  assert.deepEqual(notifications.handleStateUpdate(visible), []);

  const newer = notifications.handleStateUpdate(
    stateWith({ zcode: VISIBLE_VOUCHER('500M токенов') }),
  );
  assert.equal(newer.length, 1);
  assert.equal(newer[0].body, '500M токенов');
});

test('a voucher already visible at startup does not notify on the next poll', () => {
  freshPrefsDir('voucher-baseline');

  const visible = stateWith({ zcode: VISIBLE_VOUCHER() });
  assert.deepEqual(notifications.handleStateUpdate(visible), []);
  assert.deepEqual(notifications.handleStateUpdate(visible), []);
});

test('muting notifications preserves warned red chats instead of clearing them', () => {
  freshPrefsDir('mute-preserves-red');

  const evaluated = notifications.evaluateNotifications(
    YELLOW_STATE(),
    RED_STATE(),
    { ...notifications.DEFAULT_PREFERENCES, enabled: false },
    { notifiedRedChats: new Set(['a']) },
  );
  assert.deepEqual(evaluated.events, []);
  assert.deepEqual([...evaluated.notifiedRedChats], ['a']);
});

test('a voucher without matched text uses the fallback body', () => {
  freshPrefsDir('voucher-fallback');

  notifications.handleStateUpdate(stateWith({ zcode: { coupon_state: 'hidden' } }));
  const events = notifications.handleStateUpdate(
    stateWith({ zcode: VISIBLE_VOUCHER('') }),
  );

  assert.equal(events.length, 1);
  assert.equal(events[0].body, 'Откройте ZCode, чтобы забрать 300–500M токенов');
});

test('network errors and disabled auth suppress the voucher', () => {
  for (const [name, override] of [
    ['voucher-network', { network_error: true }],
    ['voucher-auth', { auth_ok: false }],
  ]) {
    freshPrefsDir(name);
    notifications.handleStateUpdate(stateWith({ zcode: { coupon_state: 'hidden' } }));

    const events = notifications.handleStateUpdate(
      stateWith({ zcode: { ...VISIBLE_VOUCHER(), ...override } }),
    );
    assert.deepEqual(events, [], name);
  }
});

// --- 5h quota events -------------------------------------------------------

test('a 5h bucket refill of 20 points notifies', () => {
  freshPrefsDir('quota-5h');

  notifications.handleStateUpdate(stateWith({ buckets: [bucket({ remaining_fraction: 0.1 })] }));

  const events = notifications.handleStateUpdate(
    stateWith({ buckets: [bucket({ remaining_fraction: 0.95 })] }),
  );
  assert.equal(events.length, 1);
  assert.equal(events[0].kind, 'quota_5h_reset');
  assert.equal(events[0].title, 'Квота Gemini (5 ч) обновилась');
  assert.equal(events[0].body, 'Доступно: 95%');

  assert.deepEqual(
    notifications.handleStateUpdate(stateWith({ buckets: [bucket({ remaining_fraction: 0.95 })] })),
    [],
  );
});

test('a 3p bucket is labelled Claude/GPT', () => {
  freshPrefsDir('quota-3p');

  const id = '3p-5h';
  notifications.handleStateUpdate(
    stateWith({ buckets: [bucket({ id, remaining_fraction: 0.1 })] }),
  );
  const events = notifications.handleStateUpdate(
    stateWith({ buckets: [bucket({ id, remaining_fraction: 0.95 })] }),
  );

  assert.equal(events.length, 1);
  assert.equal(events[0].title, 'Квота Claude/GPT (5 ч) обновилась');
});

test('weekly buckets are ignored', () => {
  freshPrefsDir('quota-weekly');

  notifications.handleStateUpdate(
    stateWith({ buckets: [bucket({ id: 'gemini-weekly', kind: 'weekly', remaining_fraction: 0.1 })] }),
  );
  const events = notifications.handleStateUpdate(
    stateWith({ buckets: [bucket({ id: 'gemini-weekly', kind: 'weekly', remaining_fraction: 0.95 })] }),
  );

  assert.deepEqual(events, []);
});

test('an epoch-zero reset_time never counts as a real reset', () => {
  freshPrefsDir('quota-epoch');

  notifications.handleStateUpdate(
    stateWith({
      buckets: [
        bucket({
          remaining_fraction: 0.5,
          reset_time: '1970-01-01T00:00:00Z',
        }),
      ],
    }),
  );
  const events = notifications.handleStateUpdate(
    stateWith({
      buckets: [
        bucket({
          remaining_fraction: 0.51,
          reset_time: '2026-11-27T00:00:00Z',
        }),
      ],
    }),
  );

  assert.deepEqual(events, []);
});

test('notified_resets is capped at 30 on disk', () => {
  freshPrefsDir('quota-cap');

  for (let i = 0; i < 31; i += 1) {
    const id = `bucket-${i}`;
    const prevReset = `2026-11-26T10:${String(i).padStart(2, '0')}:00Z`;
    const nextReset = `2026-11-27T10:${String(i).padStart(2, '0')}:00Z`;

    notifications.handleStateUpdate(
      stateWith({ buckets: [bucket({ id, remaining_fraction: 0.1, reset_time: prevReset })] }),
    );
    const events = notifications.handleStateUpdate(
      stateWith({ buckets: [bucket({ id, remaining_fraction: 0.95, reset_time: nextReset })] }),
    );
    assert.equal(events.length, 1, `bucket-${i} must notify`);
  }

  const stored = readPrefsFile();
  assert.equal(stored.notified_resets.length, 30);
  assert.equal(new Set(stored.notified_resets).size, 30);
  assert.ok(
    stored.notified_resets[29].startsWith('bucket-30|'),
    'FIFO keeps the newest reset key',
  );
});

// --- runtime / capability --------------------------------------------------

test('sendTestNotification reports an unsupported channel under plain Node', () => {
  assert.deepEqual(notifications.sendTestNotification(), { ok: false, supported: false });
});

test("evaluateNotifications does not mutate the caller's runtime state", () => {
  freshPrefsDir('runtime-immutable');

  const runtime = { notifiedRedChats: new Set(['a']) };
  notifications.evaluateNotifications(
    null,
    RED_STATE(),
    notifications.getPreferences(),
    runtime,
  );

  assert.deepEqual([...runtime.notifiedRedChats], ['a']);
});

test.after(() => {
  fs.rmSync(tmpRoot, { recursive: true, force: true });
});
