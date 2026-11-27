'use strict';

/**
 * Desktop notifications for context pressure, ZCode vouchers and 5-hour quota
 * resets. Mirrors the Android `Notifier`/`StatusStore` behaviour and stays a
 * pure Node module: Electron is resolved lazily, so plain `node --test` can
 * import it without the `electron` package.
 *
 * Preferences live in `<userData>/notifications.json`; the path is resolved on
 * every access because `paths.userDataDir()` honours `AGW_USER_DATA_DIR`.
 */

const path = require('path');
const paths = require('./paths');
const { readJson, writeJsonAtomic } = require('./storage');
const preferences = require('./preferences');
const i18n = require('../shared/i18n.js');
const { contextInfo } = require('../renderer/context.js');
const { formatTokens, prettyModel, computeZone } = require('../renderer/format.js');

const ICON_PATH = path.join(__dirname, '..', '..', 'assets', 'icon-64.png');

/** Number of reset keys kept for de-duplication, matching Android. */
const MAX_NOTIFIED_RESETS = 30;

/** Public toggle keys accepted from the settings UI / IPC layer. */
const PUBLIC_KEYS = Object.freeze([
  'enabled',
  'context_red',
  'zcode_voucher',
  'quota_5h_reset',
]);

/** Persisted schema; every field is filled in from here on a bad/missing file. */
const DEFAULT_PREFERENCES = Object.freeze({
  schema_version: 1,
  enabled: true,
  context_red: true,
  zcode_voucher: true,
  quota_5h_reset: true,
  notified_resets: Object.freeze([]),
  last_coupon_key: null,
});

let previousState = null;
let notifiedRedChats = new Set();

/**
 * Resolve Electron lazily: this module must stay importable under plain
 * `node --test`, where `electron` may be missing or export only a path.
 */
function electronApi() {
  try {
    const electron = require('electron');
    return electron && typeof electron === 'object' ? electron : null;
  } catch {
    return null;
  }
}

/** Window helpers are Electron-bound, so they are loaded on first real use. */
function windowApi() {
  try {
    return require('./window');
  } catch {
    return null;
  }
}

/** Absolute path to `notifications.json`, resolved at call time. */
function notificationsPath() {
  return path.join(paths.userDataDir(), 'notifications.json');
}

/** Fresh default preferences; never returns the frozen constant itself. */
function defaultPreferences() {
  return {
    schema_version: 1,
    enabled: DEFAULT_PREFERENCES.enabled,
    context_red: DEFAULT_PREFERENCES.context_red,
    zcode_voucher: DEFAULT_PREFERENCES.zcode_voucher,
    quota_5h_reset: DEFAULT_PREFERENCES.quota_5h_reset,
    notified_resets: [],
    last_coupon_key: null,
  };
}

/**
 * Ordered de-duplication with FIFO eviction, mirroring
 * `(entries.filterNot { it == key } + key).takeLast(30)` from StatusStore.kt.
 * Only non-empty strings survive; a later duplicate moves the entry to the end.
 */
function normalizeNotifiedResets(raw) {
  const entries = Array.isArray(raw) ? raw : [];
  const result = [];
  for (const entry of entries) {
    if (typeof entry !== 'string' || entry.trim() === '') continue;
    const existing = result.indexOf(entry);
    if (existing !== -1) result.splice(existing, 1);
    result.push(entry);
  }
  return result.slice(-MAX_NOTIFIED_RESETS);
}

/**
 * Coerce any stored value (missing file, legacy schema, corrupt fields) into a
 * full preferences object. Never throws.
 */
function normalizeNotificationPrefs(raw) {
  try {
    const source = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : {};
    return {
      schema_version: 1,
      enabled:
        typeof source.enabled === 'boolean' ? source.enabled : DEFAULT_PREFERENCES.enabled,
      context_red:
        typeof source.context_red === 'boolean'
          ? source.context_red
          : DEFAULT_PREFERENCES.context_red,
      zcode_voucher:
        typeof source.zcode_voucher === 'boolean'
          ? source.zcode_voucher
          : DEFAULT_PREFERENCES.zcode_voucher,
      quota_5h_reset:
        typeof source.quota_5h_reset === 'boolean'
          ? source.quota_5h_reset
          : DEFAULT_PREFERENCES.quota_5h_reset,
      notified_resets: normalizeNotifiedResets(source.notified_resets),
      last_coupon_key:
        typeof source.last_coupon_key === 'string' && source.last_coupon_key.trim() !== ''
          ? source.last_coupon_key
          : null,
    };
  } catch {
    return defaultPreferences();
  }
}

/** Normalized preferences from disk, or the defaults when the file is absent. */
function loadPreferences() {
  try {
    return normalizeNotificationPrefs(readJson(notificationsPath()));
  } catch {
    return defaultPreferences();
  }
}

/** Persist normalized preferences atomically; never throws. */
function savePreferences(prefs) {
  try {
    writeJsonAtomic(notificationsPath(), normalizeNotificationPrefs(prefs));
    return true;
  } catch {
    return false;
  }
}

function getPreferences() {
  return loadPreferences();
}

/** Only the four user-facing toggles, safe for the renderer/settings window. */
function getPublicPreferences() {
  const prefs = loadPreferences();
  return {
    enabled: prefs.enabled,
    context_red: prefs.context_red,
    zcode_voucher: prefs.zcode_voucher,
    quota_5h_reset: prefs.quota_5h_reset,
  };
}

/** Merge only known boolean toggles, persist, and return the normalized result. */
function updatePreferences(patch) {
  const current = loadPreferences();
  const source = patch && typeof patch === 'object' && !Array.isArray(patch) ? patch : {};
  const next = { ...current };
  for (const key of PUBLIC_KEYS) {
    if (typeof source[key] === 'boolean') next[key] = source[key];
  }
  const normalized = normalizeNotificationPrefs(next);
  savePreferences(normalized);
  return normalized;
}

/** A reset timestamp is usable as a de-dupe key only when it is a real date. */
function validResetKey(iso) {
  if (typeof iso !== 'string') return null;
  const parsed = Date.parse(iso);
  if (!Number.isFinite(parsed) || parsed <= 0) return null;
  return iso;
}

/** Bucket id as a string; missing ids collapse to an empty string. */
function bucketIdOf(bucket) {
  if (typeof bucket.id === 'string') return bucket.id;
  return bucket.id == null ? '' : String(bucket.id);
}

/** Human label for a 5-hour bucket, matching the desktop/Android naming. */
function bucketLabel(id, lang = 'ru') {
  if (id.startsWith('gemini-')) return i18n.t('notif.bucket.gemini5h', null, lang);
  if (id.startsWith('3p-')) return i18n.t('notif.bucket.thirdParty5h', null, lang);
  return id;
}

/** Current level of one chat inside a state snapshot, `unknown` when absent. */
function chatLevel(state, id) {
  const context = state && state.context;
  const conversations = context && context.conversations;
  const conversation = conversations && conversations[id];
  if (!conversation || typeof conversation !== 'object') return 'unknown';

  const zone = computeZone(
    conversation.tokens,
    conversation.window,
    conversation.yellow_pct,
    conversation.red_pct,
  );
  if (zone.level !== 'unknown') return zone.level;

  const wireLevel = conversation.level;
  const isKnownLevel =
    wireLevel === 'green' || wireLevel === 'yellow' || wireLevel === 'red';
  if (
    isKnownLevel &&
    typeof conversation.window === 'number' &&
    conversation.window > 0
  ) {
    return wireLevel;
  }
  return 'unknown';
}

/** Defensive copy of a runtime set; a non-iterable value yields an empty set. */
function cloneSet(value) {
  const result = new Set();
  if (!value) return result;
  try {
    for (const entry of value) result.add(entry);
  } catch {
    // A malformed baseline contributes nothing.
  }
  return result;
}

/** Append a reset key with FIFO de-duplication capped at MAX_NOTIFIED_RESETS. */
function appendNotifiedReset(prefs, dedupeKey) {
  const entries = prefs.notified_resets.filter((entry) => entry !== dedupeKey);
  entries.push(dedupeKey);
  prefs.notified_resets = entries.slice(-MAX_NOTIFIED_RESETS);
}

/**
 * Pure transition between two state snapshots. Returns the events to show, the
 * preferences after de-dupe bookkeeping, and the updated set of chats already
 * warned about. Never mutates its arguments.
 */
function evaluateNotifications(prevState, currState, prefs, runtimeState = {}) {
  const prefsN = normalizeNotificationPrefs(prefs);
  const runtime = runtimeState && typeof runtimeState === 'object' ? runtimeState : {};
  // Event texts follow the interface language; RU is the contract default.
  const lang =
    typeof runtime.lang === 'string' && runtime.lang.trim().toLowerCase() === 'en'
      ? 'en'
      : 'ru';

  // First snapshot after startup is a baseline: remember it, notify nothing.
  if (prevState == null || currState == null) {
    return {
      events: [],
      nextPrefs: prefsN,
      notifiedRedChats: cloneSet(runtime.notifiedRedChats),
    };
  }

  if (prefsN.enabled === false) {
    return {
      events: [],
      nextPrefs: prefsN,
      notifiedRedChats: cloneSet(runtime.notifiedRedChats),
    };
  }

  const events = [];
  const nextPrefs = prefsN;
  const redChats = cloneSet(runtime.notifiedRedChats);

  // Channel 1: context red zone. Inform at most once per chat until it leaves
  // the risky zone (green/yellow) or the app restarts.
  const currInfo = contextInfo(currState.context, currState.status, currState.activeChat);
  const prevInfo = contextInfo(prevState.context, prevState.status, prevState.activeChat);
  const currId = currInfo.activeId || currInfo.selectedId || null;
  const prevId = prevInfo.activeId || prevInfo.selectedId || null;

  const becameRed = prevId === currId && prevInfo.level !== 'red';
  const switchedToRedChat = prevId !== currId;
  if (
    prefsN.context_red === true &&
    currId &&
    currInfo.level === 'red' &&
    !redChats.has(currId) &&
    (becameRed || switchedToRedChat)
  ) {
    redChats.add(currId);
    const pct =
      typeof currInfo.pct === 'number' && Number.isFinite(currInfo.pct) ? currInfo.pct : 0;
    events.push({
      kind: 'context_red',
      title: i18n.t('notif.contextRed.title', null, lang),
      body: i18n.t(
        'notif.contextRed.body',
        {
          model: prettyModel(currInfo.cur && currInfo.cur.model, lang),
          tokens: formatTokens(currInfo.tokens),
          pct: Math.round(pct),
        },
        lang,
      ),
    });
  }

  // A chat that dropped back to green/yellow may warn again on the next ascent.
  // Unknown/missing chats stay in the set: dropping the data must not cause a
  // duplicate warning when the conversation reappears.
  for (const id of Array.from(redChats)) {
    const level = chatLevel(currState, id);
    if (level === 'green' || level === 'yellow') redChats.delete(id);
  }

  // Channel 2: ZCode voucher, mirroring Notifier.kt:74-88.
  const visibleVoucherKey = (z) =>
    z &&
    z.coupon_state === 'visible' &&
    z.coupon_visible === true &&
    !z.network_error &&
    z.auth_ok !== false
      ? `${z.plan_id || ''}|${z.matched || ''}`
      : null;
  const zcode = currState.status && currState.status.zcode;
  const couponKey = visibleVoucherKey(zcode);
  const prevCouponKey = visibleVoucherKey(prevState.status && prevState.status.zcode);
  if (prefsN.zcode_voucher === true && couponKey !== null) {
    // Skip when the exact same voucher was already visible in the baseline /
    // previous snapshot so a fresh install never spams on the first 5s poll.
    if (couponKey !== prevCouponKey && couponKey !== nextPrefs.last_coupon_key) {
      nextPrefs.last_coupon_key = couponKey;
      const detail = String(zcode.matched || '').trim();
      events.push({
        kind: 'zcode_voucher',
        title: i18n.t('notif.couponAvailable.title', null, lang),
        body: detail || i18n.t('notif.couponFallback.body', null, lang),
      });
    }
  }

  // Channel 3: 5-hour quota reset, mirroring Notifier.kt:110-127.
  const currBucketsValue =
    currState.status && currState.status.antigravity && currState.status.antigravity.buckets;
  const prevBucketsValue =
    prevState.status && prevState.status.antigravity && prevState.status.antigravity.buckets;
  const currBuckets = Array.isArray(currBucketsValue) ? currBucketsValue : [];
  const prevBuckets = Array.isArray(prevBucketsValue) ? prevBucketsValue : [];

  for (const bucket of currBuckets) {
    if (!bucket || typeof bucket !== 'object' || bucket.kind !== '5h') continue;
    const prevBucket = prevBuckets.find(
      (candidate) =>
        candidate && typeof candidate === 'object' && candidate.id === bucket.id,
    );
    if (!prevBucket) continue;

    const prevFrac =
      typeof prevBucket.remaining_fraction === 'number' &&
      Number.isFinite(prevBucket.remaining_fraction)
        ? prevBucket.remaining_fraction
        : null;
    if (prevFrac === null || prevFrac >= 0.99) continue;

    const currFrac =
      typeof bucket.remaining_fraction === 'number' &&
      Number.isFinite(bucket.remaining_fraction)
        ? bucket.remaining_fraction
        : null;

    const grew = currFrac !== null && currFrac - prevFrac >= 0.2;
    let isReset = grew;
    if (!isReset) {
      const prevReset = validResetKey(prevBucket.reset_time);
      const currReset = validResetKey(bucket.reset_time);
      const resetAdvanced =
        prevReset !== null &&
        currReset !== null &&
        Date.parse(currReset) > Date.parse(prevReset);
      const quotaDidNotDrop = currFrac === null || currFrac > prevFrac + 0.01;
      isReset = resetAdvanced && quotaDidNotDrop;
    }
    if (!isReset) continue;

    // Key on the time before the reset so a future timestamp is never marked.
    const resetKey =
      validResetKey(prevBucket.reset_time) ||
      validResetKey(bucket.reset_time) ||
      'changed';
    const dedupeKey = `${bucketIdOf(bucket)}|${resetKey}`;
    if (nextPrefs.notified_resets.includes(dedupeKey)) continue;
    // A disabled channel must not consume the de-dupe key.
    if (prefsN.quota_5h_reset !== true) continue;

    appendNotifiedReset(nextPrefs, dedupeKey);
    events.push({
      kind: 'quota_5h_reset',
      title: i18n.t(
        'notif.quotaReset.title',
        { bucket: bucketLabel(bucketIdOf(bucket), lang) },
        lang,
      ),
      body:
        currFrac !== null
          ? i18n.t('notif.quotaAvailable.body', { pct: Math.round(currFrac * 100) }, lang)
          : i18n.t('notif.quotaResetLimit.body', null, lang),
    });
  }

  return { events, nextPrefs, notifiedRedChats: redChats };
}

/** True when the current Electron build can post desktop notifications. */
function isNotificationSupported() {
  const electron = electronApi();
  if (!electron || typeof electron.Notification !== 'function') return false;
  try {
    return Boolean(electron.Notification.isSupported());
  } catch {
    return false;
  }
}

/**
 * Show one desktop notification. Missing support or any Electron failure
 * resolves to `false`; this function never throws. A click focuses the widget.
 */
function showNotification(options) {
  const payload =
    options && typeof options === 'object' && !Array.isArray(options) ? options : {};
  if (!isNotificationSupported()) return false;

  const electron = electronApi();
  if (!electron || typeof electron.Notification !== 'function') return false;

  try {
    const notification = new electron.Notification({
      title: payload.title,
      body: payload.body,
      icon: ICON_PATH,
      silent: false,
    });
    if (notification && typeof notification.on === 'function') {
      notification.on('click', () => {
        const api = windowApi();
        if (api && typeof api.showWindow === 'function') {
          try {
            api.showWindow();
          } catch {
            // A destroyed window must not turn a click into a crash.
          }
        }
      });
    }
    if (notification && typeof notification.show === 'function') {
      notification.show();
    }
    return true;
  } catch {
    return false;
  }
}

/** Settings-window smoke test; reports support separately from the result. */
function sendTestNotification() {
  if (!isNotificationSupported()) return { ok: false, supported: false };
  const lang = preferences.resolveCurrentLanguage();
  const ok = showNotification({
    title: i18n.t('notif.test.title', null, lang),
    body: i18n.t('notif.test.body', null, lang),
  });
  return { ok, supported: true };
}

/**
 * Feed a fresh state snapshot into the notification pipeline. Persists de-dupe
 * bookkeeping only when it actually changed and returns the events to show.
 */
function handleStateUpdate(state) {
  const prefs = loadPreferences();
  const result = evaluateNotifications(previousState, state, prefs, {
    notifiedRedChats,
    lang: preferences.resolveCurrentLanguage(),
  });

  previousState = state;
  notifiedRedChats = result.notifiedRedChats;

  if (JSON.stringify(result.nextPrefs) !== JSON.stringify(prefs)) {
    savePreferences(result.nextPrefs);
  }

  for (const event of result.events) {
    showNotification(event);
  }

  return result.events;
}

/** Reset the baseline and warned-chat set (tests / app restart). */
function resetRuntime() {
  previousState = null;
  notifiedRedChats = new Set();
}

module.exports = {
  DEFAULT_PREFERENCES,
  MAX_NOTIFIED_RESETS,
  notificationsPath,
  normalizeNotificationPrefs,
  loadPreferences,
  savePreferences,
  getPreferences,
  getPublicPreferences,
  updatePreferences,
  evaluateNotifications,
  handleStateUpdate,
  resetRuntime,
  sendTestNotification,
  showNotification,
  isNotificationSupported,
};
