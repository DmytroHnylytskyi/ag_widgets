'use strict';

/**
 * i18n contract tests: RU/EN dictionary parity, locale resolution, translation
 * helpers, RU/EN time formatting, tray/notification localization, UI-key
 * coverage and preference persistence.
 *
 * Plain Node only: no Electron, no network. Every module under test is pointed
 * at a throw-away profile so the real %LOCALAPPDATA%\ag-widgets is never read
 * or written.
 *
 * Run with: node --test desktop/test/i18n.test.js
 */

const fs = require('fs');
const os = require('os');
const path = require('path');
const { describe, it, after } = require('node:test');
const assert = require('node:assert/strict');

// Must run before the first `require` of paths/appearance/preferences.
const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'agw-i18n-'));
process.env.AGW_APP_DIR = path.join(tmpRoot, 'app');
process.env.AGW_USER_DATA_DIR = path.join(tmpRoot, 'userdata');

const i18n = require('../src/shared/i18n.js');
const paths = require('../src/main/paths.js');
const preferences = require('../src/main/preferences.js');
const tray = require('../src/main/tray.js');
const notifications = require('../src/main/notifications.js');
const format = require('../src/renderer/format.js');

const CYRILLIC = /[А-Яа-яЁё]/;

/** `{name}` occurrences with their repeat counts, for parity checks. */
function placeholderCounts(text) {
  const counts = {};
  const pattern = /\{([A-Za-z0-9_][A-Za-z0-9_.-]*)\}/g;
  let match = pattern.exec(text);
  while (match !== null) {
    counts[match[1]] = (counts[match[1]] || 0) + 1;
    match = pattern.exec(text);
  }
  return counts;
}

/** Words that legitimately stay Latin in the Russian dictionary. */
const TECHNICAL_TOKENS = new Set([
  'AG',
  'AGW',
  'WIDGETS',
  'ANTIGRAVITY',
  'ZCODE',
  'CONTEXT',
  'LIMITS',
  'ACCESS',
  'HEALTH',
  'GEMINI',
  'CLAUDE',
  'GPT',
  'GRAPHITE',
  'PAPER',
  'OLED',
  'GLASS',
  'CLASSIC',
  'ENGLISH',
]);

/**
 * A RU value may be non-Cyrillic only when it is purely numeric/symbolic
 * (`0–40%`, `400×640`, `{pct}%`) or consists solely of brand/product tokens.
 */
function isTechnicalRuValue(value) {
  const withoutPlaceholders = value.replace(/\{[A-Za-z0-9_][A-Za-z0-9_.-]*\}/g, ' ');
  const words = withoutPlaceholders.match(/[A-Za-zА-Яа-яЁё]+/g) || [];
  if (words.length === 0) return true;
  return words.every((word) => TECHNICAL_TOKENS.has(word.toUpperCase()));
}

// ---------------------------------------------------------------------------
// 1. Dictionary parity
// ---------------------------------------------------------------------------

describe('dictionary parity', () => {
  it('ru and en carry the same key set in the same order', () => {
    const ru = i18n.keys('ru');
    const en = i18n.keys('en');

    assert.equal(ru.length, 343, 'the desktop dictionary manifest pins 343 keys');
    assert.equal(en.length, ru.length);
    assert.deepEqual(en, ru, 'both locales must expose identical keys in identical order');
    assert.deepEqual([...new Set(ru)], ru, 'keys must be unique');
  });

  it('has no empty or whitespace-only values in either locale', () => {
    for (const lang of ['ru', 'en']) {
      for (const key of i18n.keys(lang)) {
        const value = i18n.STRINGS[lang][key];
        assert.equal(typeof value, 'string', `${lang}.${key} must be a string`);
        assert.ok(value.trim().length > 0, `${lang}.${key} must not be empty or whitespace`);
      }
    }
  });

  it('uses identical placeholder names and repeat counts in both locales', () => {
    for (const key of i18n.keys('ru')) {
      const ruValue = i18n.STRINGS.ru[key];
      const enValue = i18n.STRINGS.en[key];

      assert.deepEqual(
        i18n.placeholders(enValue).slice().sort(),
        i18n.placeholders(ruValue).slice().sort(),
        `${key}: unique placeholder names must match`,
      );
      assert.deepEqual(
        placeholderCounts(enValue),
        placeholderCounts(ruValue),
        `${key}: repeated placeholders must match too (e.g. {yellow} twice)`,
      );
    }
  });
});

// ---------------------------------------------------------------------------
// 2. Locale purity
// ---------------------------------------------------------------------------

describe('locale purity', () => {
  it('en contains no Cyrillic except the Russian language label', () => {
    const offenders = Object.entries(i18n.STRINGS.en).filter(([, value]) =>
      CYRILLIC.test(value),
    );

    assert.deepEqual(offenders, [['settings.language.ru', 'Русский']]);
  });

  it('ru is Cyrillic apart from technical/brand strings (>=90%)', () => {
    const entries = Object.entries(i18n.STRINGS.ru);

    const leaked = entries
      .filter(([, value]) => !CYRILLIC.test(value) && !isTechnicalRuValue(value))
      .map(([key]) => key);
    assert.deepEqual(leaked, [], 'these RU values look untranslated');

    const considered = entries.filter(([, value]) => !isTechnicalRuValue(value));
    assert.ok(
      considered.length >= 250,
      `too few translatable RU values (${considered.length}); the technical filter is too broad`,
    );

    const cyrillic = considered.filter(([, value]) => CYRILLIC.test(value)).length;
    const ratio = cyrillic / considered.length;
    assert.ok(ratio >= 0.9, `only ${(ratio * 100).toFixed(1)}% of RU values are Cyrillic`);
  });
});

// ---------------------------------------------------------------------------
// 3. normalizeLanguage
// ---------------------------------------------------------------------------

describe('normalizeLanguage', () => {
  it('accepts the three supported modes regardless of case and padding', () => {
    assert.equal(i18n.normalizeLanguage('ru'), 'ru');
    assert.equal(i18n.normalizeLanguage('RU'), 'ru');
    assert.equal(i18n.normalizeLanguage(' ru '), 'ru');
    assert.equal(i18n.normalizeLanguage('en'), 'en');
    assert.equal(i18n.normalizeLanguage('EN'), 'en');
    assert.equal(i18n.normalizeLanguage('\tEn\n'), 'en');
    assert.equal(i18n.normalizeLanguage('auto'), 'auto');
  });

  it('collapses everything else to auto', () => {
    for (const raw of ['auto', '', '   ', null, undefined, 42, true, {}, [], 'de', 'english']) {
      assert.equal(
        i18n.normalizeLanguage(raw),
        'auto',
        `unexpected result for ${JSON.stringify(raw)}`,
      );
    }
  });
});

// ---------------------------------------------------------------------------
// 4. resolveLanguage
// ---------------------------------------------------------------------------

describe('resolveLanguage', () => {
  it('honours an explicit mode for every system locale', () => {
    const locales = ['ru-RU', 'RU', 'en-US', 'de-DE', '', '   ', null, undefined, 42];

    for (const locale of locales) {
      assert.equal(i18n.resolveLanguage('ru', locale), 'ru', `ru with ${JSON.stringify(locale)}`);
      assert.equal(i18n.resolveLanguage('en', locale), 'en', `en with ${JSON.stringify(locale)}`);
    }
  });

  it('auto resolves a Russian locale to ru and anything else to en', () => {
    for (const locale of ['ru-RU', 'RU', ' ru-ru ', 'ru']) {
      assert.equal(i18n.resolveLanguage('auto', locale), 'ru', `auto with ${locale}`);
    }
    for (const locale of ['en-US', 'de-DE', '', '   ', null, undefined, 42, 'uk-UA']) {
      assert.equal(
        i18n.resolveLanguage('auto', locale),
        'en',
        `auto with ${JSON.stringify(locale)}`,
      );
    }
  });

  it('treats an unknown mode exactly like auto', () => {
    assert.equal(i18n.resolveLanguage('xx', 'ru-RU'), 'ru');
    assert.equal(i18n.resolveLanguage('xx', 'en-US'), 'en');
    assert.equal(i18n.resolveLanguage(null, 'ru-RU'), 'ru');
    assert.equal(i18n.resolveLanguage(undefined, 'de-DE'), 'en');
  });
});

// ---------------------------------------------------------------------------
// 5. t()
// ---------------------------------------------------------------------------

describe('t()', () => {
  it('defaults to Russian and keeps the U+2026 ellipsis', () => {
    assert.equal(i18n.t('tray.settings'), 'Настройки\u2026');
    assert.equal(i18n.t('tray.settings').includes('...'), false, 'must be U+2026, not three dots');
    assert.equal(i18n.t('tray.settings', null, 'en'), 'Settings\u2026');
    assert.equal(i18n.t('tray.settings', null, 'EN'), 'Settings\u2026');
    assert.equal(i18n.t('tray.settings', undefined, 'ru'), 'Настройки\u2026');
  });

  it('substitutes {placeholders} from vars', () => {
    assert.equal(
      i18n.t('dashboard.context.pctOfWindow', { pct: 42, window: '1M' }),
      '42% из 1M',
    );
    assert.equal(
      i18n.t('dashboard.context.pctOfWindow', { pct: 42, window: '1M' }, 'en'),
      '42% of 1M',
    );
    assert.equal(i18n.t('time.minutesAgo', { n: 5 }, 'en'), '5m ago');
  });

  it('keeps the placeholder when a variable is missing', () => {
    assert.equal(
      i18n.t('dashboard.context.pctOfWindow', { pct: 42 }),
      '42% из {window}',
    );
    assert.equal(
      i18n.t('dashboard.context.pctOfWindow', { pct: 42, window: null }),
      '42% из {window}',
    );
    assert.equal(
      i18n.t('dashboard.context.pctOfWindow', { pct: 42, window: undefined }),
      '42% из {window}',
    );
    assert.equal(
      i18n.t('dashboard.context.pctOfWindow', null),
      '{pct}% из {window}',
      'vars is optional',
    );
  });

  it('returns the key itself for an unknown key', () => {
    assert.equal(i18n.t('no.such.key'), 'no.such.key');
    assert.equal(i18n.t('no.such.key', { a: 1 }, 'en'), 'no.such.key');
    assert.equal(i18n.t('no.such.key', null, 'ru'), 'no.such.key');
  });
});

// ---------------------------------------------------------------------------
// 6. Time formatting (RU regression lock + EN counterpart)
// ---------------------------------------------------------------------------

const NOW = Date.parse('2026-11-26T12:00:00.000Z');
const HHMM = /^\d{2}:\d{2}$/;

function isoFromNow(offsetMs) {
  return new Date(NOW + offsetMs).toISOString();
}

/** Local 24-hour clock of an ISO string, mirroring the module contract. */
function localClock(iso) {
  const date = new Date(iso);
  return (
    String(date.getHours()).padStart(2, '0') +
    ':' +
    String(date.getMinutes()).padStart(2, '0')
  );
}

describe('shortAge', () => {
  it('keeps the RU wording and rounding', () => {
    assert.equal(i18n.shortAge('', NOW), '');
    assert.equal(i18n.shortAge('garbage', NOW), '');
    assert.equal(i18n.shortAge(null, NOW), '');
    assert.equal(i18n.shortAge(isoFromNow(-10_000), NOW), 'только что');
    assert.equal(i18n.shortAge(isoFromNow(-5 * 60_000), NOW), '5м назад');
    assert.equal(i18n.shortAge(isoFromNow(-3 * 3_600_000), NOW), '3ч назад');
    assert.equal(i18n.shortAge(isoFromNow(-2 * 86_400_000), NOW), '2д назад');
  });

  it('switches to the EN wording', () => {
    assert.equal(i18n.shortAge(isoFromNow(-10_000), NOW, 'en'), 'just now');
    assert.equal(i18n.shortAge(isoFromNow(-5 * 60_000), NOW, 'en'), '5m ago');
    assert.equal(i18n.shortAge(isoFromNow(-3 * 3_600_000), NOW, 'en'), '3h ago');
    assert.equal(i18n.shortAge(isoFromNow(-2 * 86_400_000), NOW, 'en'), '2d ago');
  });
});

describe('formatCountdown', () => {
  it('keeps the RU wording with a local HH:MM clock', () => {
    const in4h15 = isoFromNow((4 * 3600 + 15 * 60) * 1000);
    const in2d3h = isoFromNow((2 * 86_400 + 3 * 3600) * 1000);
    const in5m = isoFromNow(5 * 60_000);
    const past = isoFromNow(-3_600_000);

    assert.equal(i18n.formatCountdown(in4h15, NOW), `4ч 15м · ${localClock(in4h15)}`);
    assert.equal(i18n.formatCountdown(in2d3h, NOW), `2д 3ч · ${localClock(in2d3h)}`);
    assert.equal(i18n.formatCountdown(in5m, NOW), `5м · ${localClock(in5m)}`);
    assert.equal(i18n.formatCountdown(past, NOW), `сброс сейчас · ${localClock(past)}`);

    for (const text of [
      i18n.formatCountdown(in4h15, NOW),
      i18n.formatCountdown(in2d3h, NOW),
      i18n.formatCountdown(in5m, NOW),
      i18n.formatCountdown(past, NOW),
    ]) {
      assert.match(text.split(' · ')[1], HHMM);
    }
  });

  it('suppresses empty, broken and epoch-zero timestamps', () => {
    for (const bad of [null, undefined, '', 'garbage', '1970-01-01T00:00:00Z']) {
      assert.equal(
        i18n.formatCountdown(bad, NOW),
        '',
        `unexpected output for ${JSON.stringify(bad)}`,
      );
    }
  });

  it('switches to the EN wording', () => {
    const in4h15 = isoFromNow((4 * 3600 + 15 * 60) * 1000);
    const in2d3h = isoFromNow((2 * 86_400 + 3 * 3600) * 1000);
    const past = isoFromNow(-3_600_000);

    assert.equal(i18n.formatCountdown(in4h15, NOW, 'en'), `4h 15m · ${localClock(in4h15)}`);
    assert.equal(i18n.formatCountdown(in2d3h, NOW, 'en'), `2d 3h · ${localClock(in2d3h)}`);
    assert.equal(i18n.formatCountdown(past, NOW, 'en'), `resets now · ${localClock(past)}`);
    assert.match(i18n.formatCountdown(in4h15, NOW, 'en').split(' · ')[1], HHMM);
  });

  it('renderer format helpers keep the RU default', () => {
    const in5m = isoFromNow(-5 * 60_000);
    const in4h15 = isoFromNow((4 * 3600 + 15 * 60) * 1000);

    assert.equal(format.shortAge(in5m, NOW), i18n.shortAge(in5m, NOW));
    assert.equal(format.shortAge(in5m, NOW), '5м назад');
    assert.equal(format.formatCountdown(in4h15, NOW), i18n.formatCountdown(in4h15, NOW));
    assert.equal(format.shortAge(in5m, NOW, 'en'), '5m ago');
    assert.equal(format.formatCountdown(in4h15, NOW, 'en'), `4h 15m · ${localClock(in4h15)}`);
  });
});

// ---------------------------------------------------------------------------
// 7. Tray menu localization
// ---------------------------------------------------------------------------

/** Separator items have no label; expose their type instead of `undefined`. */
function menuLabel(item) {
  return item.type === 'separator' ? 'separator' : item.label;
}

describe('tray menu localization', () => {
  it('localizes every action and keeps the order in both languages', () => {
    const options = {
      source: 'tray',
      windowVisible: false,
      zcodeConnected: true,
      presetMode: 'compact',
    };

    const ru = tray.buildMenuTemplate(options);
    const en = tray.buildMenuTemplate({ ...options, lang: 'en' });

    assert.deepEqual(
      ru.map(menuLabel),
      [
        'Показать виджет',
        'Поверх всех окон',
        'Запускать при входе',
        'Уведомления',
        'Настройки…',
        'separator',
        'Режим окна',
        'Открыть папку конфига',
        'Открыть Z.ai',
        'Выйти из Z.ai',
        'separator',
        'Выход',
      ],
    );
    assert.deepEqual(
      en.map(menuLabel),
      [
        'Show widget',
        'Always on top',
        'Launch at sign-in',
        'Notifications',
        'Settings…',
        'separator',
        'Window mode',
        'Open config folder',
        'Open Z.ai',
        'Sign out of Z.ai',
        'separator',
        'Quit',
      ],
    );

    assert.equal(ru.length, en.length);
    assert.deepEqual(
      ru.map((item) => item.type),
      en.map((item) => item.type),
      'item types must not depend on the language',
    );

    for (const index of [1, 2, 3]) assert.equal(ru[index].type, 'checkbox');
    assert.equal(ru[5].type, 'separator');
    assert.equal(ru[10].type, 'separator');
  });

  it('offers the visible-window action only when the widget is hidden', () => {
    const ruHidden = tray.buildMenuTemplate({ source: 'tray', windowVisible: false });
    const enHidden = tray.buildMenuTemplate({
      source: 'tray',
      windowVisible: false,
      lang: 'en',
    });
    assert.equal(ruHidden[0].label, 'Показать виджет');
    assert.equal(enHidden[0].label, 'Show widget');

    const ruVisible = tray.buildMenuTemplate({ source: 'tray' });
    const enVisible = tray.buildMenuTemplate({ source: 'tray', lang: 'en' });
    assert.equal(ruVisible[0].label, 'Свернуть в трей');
    assert.equal(enVisible[0].label, 'Minimize to tray');
    assert.equal(
      ruVisible.some((item) => item.label === 'Показать виджет'),
      false,
    );
    assert.equal(
      enVisible.some((item) => item.label === 'Show widget'),
      false,
    );
  });

  it('localizes the window-mode radio submenu', () => {
    const ru = tray.buildMenuTemplate({ source: 'tray', presetMode: 'compact' });
    const en = tray.buildMenuTemplate({ source: 'tray', presetMode: 'compact', lang: 'en' });

    const ruSubmenu = ru.find((item) => item.label === 'Режим окна').submenu;
    const enSubmenu = en.find((item) => item.label === 'Window mode').submenu;

    assert.deepEqual(
      ruSubmenu.map((item) => item.label),
      ['Полный', 'Компактный', 'Мини-бар', 'Пилюля'],
    );
    assert.deepEqual(
      enSubmenu.map((item) => item.label),
      ['Full', 'Compact', 'Mini bar', 'Pill'],
    );
    for (const item of ruSubmenu.concat(enSubmenu)) {
      assert.equal(item.type, 'radio');
    }
    assert.deepEqual(
      ruSubmenu.map((item) => item.checked),
      [false, true, false, false],
    );
    assert.deepEqual(
      enSubmenu.map((item) => item.checked),
      ruSubmenu.map((item) => item.checked),
    );
  });
});

// ---------------------------------------------------------------------------
// 8. Notification localization
// ---------------------------------------------------------------------------

const WINDOW = 524288;

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

function stateWith({ conversations = {}, last = null, activeId = null, zcode = null, buckets = [] } = {}) {
  return {
    context: { last, conversations },
    status: { zcode, antigravity: { buckets } },
    activeChat: activeId ? { id: activeId, title: 'Чат' } : null,
    chatTitles: {},
    now: Date.parse('2026-11-26T12:00:00Z'),
  };
}

const YELLOW_STATE = () =>
  stateWith({ conversations: { a: conv('gemini-4-pro', 250000) }, last: 'a', activeId: 'a' });
const RED_STATE = () =>
  stateWith({ conversations: { a: conv('gemini-4-pro', 377000) }, last: 'a', activeId: 'a' });

function visibleVoucher(matched = '') {
  return {
    coupon_state: 'visible',
    coupon_visible: true,
    network_error: false,
    auth_ok: true,
    plan_id: 'pro',
    matched,
  };
}

function bucketState(remainingFraction) {
  return stateWith({
    buckets: [
      {
        id: 'gemini-5h',
        kind: '5h',
        remaining_fraction: remainingFraction,
        reset_time: '2026-11-26T15:00:00Z',
      },
    ],
  });
}

describe('notification localization', () => {
  const prefs = notifications.DEFAULT_PREFERENCES;

  it('renders the red-zone event in EN', () => {
    const result = notifications.evaluateNotifications(YELLOW_STATE(), RED_STATE(), prefs, {
      lang: 'en',
    });

    assert.equal(result.events.length, 1);
    assert.equal(result.events[0].kind, 'context_red');
    assert.equal(result.events[0].title, 'Time to run /compact');
    assert.equal(
      result.events[0].body,
      'Gemini 4 Pro: 377K (72%) — context is in the red zone',
    );
  });

  it('renders the voucher event in EN', () => {
    const prev = stateWith({ zcode: { coupon_state: 'hidden', coupon_visible: false } });
    const curr = stateWith({ zcode: visibleVoucher('') });

    const result = notifications.evaluateNotifications(prev, curr, prefs, { lang: 'en' });

    assert.equal(result.events.length, 1);
    assert.equal(result.events[0].kind, 'zcode_voucher');
    assert.equal(result.events[0].title, 'A voucher is available in ZCode!');
    assert.equal(result.events[0].body, 'Open ZCode to claim 300–500M tokens');
  });

  it('renders the 5h quota event in EN', () => {
    const result = notifications.evaluateNotifications(
      bucketState(0.1),
      bucketState(0.95),
      prefs,
      { lang: 'en' },
    );

    assert.equal(result.events.length, 1);
    assert.equal(result.events[0].kind, 'quota_5h_reset');
    // The dictionary/manifest pin "refreshed" (task brief said "updated").
    assert.equal(result.events[0].title, 'Quota Gemini (5h) refreshed');
    assert.equal(result.events[0].body, 'Available: 95%');
  });

  it('keeps the previous Russian strings when no language is given', () => {
    const red = notifications.evaluateNotifications(YELLOW_STATE(), RED_STATE(), prefs);
    assert.equal(red.events[0].title, 'Пора сделать /compact');
    assert.equal(
      red.events[0].body,
      'Gemini 4 Pro: 377K (72%) — контекст в красной зоне',
    );

    const voucher = notifications.evaluateNotifications(
      stateWith({ zcode: { coupon_state: 'hidden', coupon_visible: false } }),
      stateWith({ zcode: visibleVoucher('') }),
      prefs,
    );
    assert.equal(voucher.events[0].title, 'В ZCode доступен ваучер!');
    assert.equal(voucher.events[0].body, 'Откройте ZCode, чтобы забрать 300–500M токенов');

    const quota = notifications.evaluateNotifications(bucketState(0.1), bucketState(0.95), prefs);
    assert.equal(quota.events[0].title, 'Квота Gemini (5 ч) обновилась');
    assert.equal(quota.events[0].body, 'Доступно: 95%');
  });

  it('falls back to RU for an unsupported runtime language', () => {
    const result = notifications.evaluateNotifications(YELLOW_STATE(), RED_STATE(), prefs, {
      lang: 'de',
    });
    assert.equal(result.events[0].title, 'Пора сделать /compact');
  });
});

// ---------------------------------------------------------------------------
// 9. UI key coverage (no orphan keys in HTML/JS)
// ---------------------------------------------------------------------------

describe('UI key coverage', () => {
  const desktopDir = path.join(__dirname, '..');
  const rendererDir = path.join(desktopDir, 'src', 'renderer');
  const htmlFiles = ['index.html', 'settings.html'].map((name) => path.join(desktopDir, name));
  const jsFiles = fs
    .readdirSync(rendererDir)
    .filter((name) => name.endsWith('.js') && name !== 'theme-math.js')
    .map((name) => path.join(rendererDir, name));

  const ATTRIBUTE_PATTERN =
    /\b(data-i18n(?:-title|-aria|-placeholder)?)\s*=\s*(?:"([^"]*)"|'([^']*)')/g;
  // `t('key')`, `i18n.t('key')` and the renderer-local `tr('key')` aliases.
  const CALL_PATTERN = /\b(?:i18n\.)?(?:t|tr)\s*\(\s*(['"])([^'"\n]+)\1/g;

  it('index.html and settings.html reference only existing keys', () => {
    let total = 0;

    for (const file of htmlFiles) {
      const source = fs.readFileSync(file, 'utf8');
      const name = path.basename(file);
      let match = ATTRIBUTE_PATTERN.exec(source);

      while (match !== null) {
        const attribute = match[1];
        const key = (match[2] !== undefined ? match[2] : match[3]).trim();

        assert.notEqual(key, '', `${name}: ${attribute} must not be empty`);
        assert.ok(i18n.hasKey(key), `${name}: ${attribute}="${key}" is missing from the dictionary`);
        total += 1;
        match = ATTRIBUTE_PATTERN.exec(source);
      }
    }

    assert.ok(total >= 100, `expected a meaningful number of data-i18n attributes, found ${total}`);
  });

  it('renderer modules call t()/tr() with existing keys only', () => {
    let total = 0;

    for (const file of jsFiles) {
      const source = fs.readFileSync(file, 'utf8');
      const name = path.basename(file);
      let match = CALL_PATTERN.exec(source);

      while (match !== null) {
        const key = match[2];
        assert.ok(i18n.hasKey(key), `${name}: t("${key}") is missing from the dictionary`);
        total += 1;
        match = CALL_PATTERN.exec(source);
      }
    }

    assert.ok(total >= 100, `expected a meaningful number of t() calls, found ${total}`);
  });
});

// ---------------------------------------------------------------------------
// 10. applyDomTranslations
// ---------------------------------------------------------------------------

/** Minimal DOM node: attribute map plus the writable properties we check. */
function createNode(attributes) {
  const store = { ...attributes };
  return {
    _attributes: store,
    textContent: '',
    getAttribute(name) {
      return Object.prototype.hasOwnProperty.call(store, name) ? store[name] : null;
    },
    setAttribute(name, value) {
      store[name] = String(value);
    },
  };
}

/** Stub document keyed by selector; `documentElement` is a mutable object. */
function createDocument(groups, navigatorLanguage) {
  return {
    documentElement: {},
    defaultView: navigatorLanguage ? { navigator: { language: navigatorLanguage } } : undefined,
    querySelectorAll(selector) {
      return groups[selector] || [];
    },
  };
}

describe('applyDomTranslations', () => {
  it('writes textContent/title/aria-label/placeholder and <html lang> in EN', () => {
    const text = createNode({ 'data-i18n': 'settings.title' });
    const title = createNode({ 'data-i18n-title': 'tray.settings' });
    const aria = createNode({ 'data-i18n-aria': 'titlebar.refresh' });
    const placeholder = createNode({ 'data-i18n-placeholder': 'settings.json.aria' });

    const doc = createDocument({
      '[data-i18n]': [text],
      '[data-i18n-title]': [title],
      '[data-i18n-aria]': [aria],
      '[data-i18n-placeholder]': [placeholder],
    });

    assert.equal(i18n.applyDomTranslations(doc, 'en'), 'en');
    assert.equal(doc.documentElement.lang, 'en');
    assert.equal(text.textContent, 'Settings');
    assert.equal(title._attributes.title, 'Settings…');
    assert.equal(aria._attributes['aria-label'], 'Refresh data');
    assert.equal(placeholder._attributes.placeholder, 'Preset JSON');
  });

  it('switches the same document back to RU', () => {
    const text = createNode({ 'data-i18n': 'settings.title' });
    const title = createNode({ 'data-i18n-title': 'tray.settings' });
    const aria = createNode({ 'data-i18n-aria': 'titlebar.refresh' });
    const placeholder = createNode({ 'data-i18n-placeholder': 'settings.json.aria' });

    const doc = createDocument({
      '[data-i18n]': [text],
      '[data-i18n-title]': [title],
      '[data-i18n-aria]': [aria],
      '[data-i18n-placeholder]': [placeholder],
    });

    assert.equal(i18n.applyDomTranslations(doc, 'ru'), 'ru');
    assert.equal(doc.documentElement.lang, 'ru');
    assert.equal(text.textContent, 'Настройки');
    assert.equal(title._attributes.title, 'Настройки…');
    assert.equal(aria._attributes['aria-label'], 'Обновить данные');
    assert.equal(placeholder._attributes.placeholder, 'JSON пресета');
  });

  it('resolves auto from the document navigator language', () => {
    const text = createNode({ 'data-i18n': 'settings.title' });
    const doc = createDocument({ '[data-i18n]': [text] }, 'ru-RU');

    assert.equal(i18n.applyDomTranslations(doc, 'auto'), 'ru');
    assert.equal(doc.documentElement.lang, 'ru');
    assert.equal(text.textContent, 'Настройки');
  });

  it('tolerates null/partial documents without throwing', () => {
    assert.equal(i18n.applyDomTranslations(null, 'en'), 'en');
    assert.equal(i18n.applyDomTranslations({}, 'en'), 'en');
    assert.equal(i18n.applyDomTranslations(undefined, 'ru'), 'ru');
    assert.doesNotThrow(() => i18n.applyDomTranslations({ documentElement: {} }, 'en'));
  });

  it('an unknown or empty key does not break the remaining nodes', () => {
    const unknown = createNode({ 'data-i18n': 'no.such.key' });
    const empty = createNode({ 'data-i18n': '   ' });
    const ok = createNode({ 'data-i18n': 'settings.title' });
    const broken = {}; // no getAttribute: must be skipped, not throw

    const doc = createDocument({ '[data-i18n]': [unknown, empty, broken, ok] });

    assert.equal(i18n.applyDomTranslations(doc, 'ru'), 'ru');
    assert.equal(unknown.textContent, 'no.such.key', 'a missing key falls back to the key');
    assert.equal(empty.textContent, '', 'an empty key is skipped');
    assert.equal(ok.textContent, 'Настройки', 'later nodes are still translated');
  });
});

// ---------------------------------------------------------------------------
// 11. preferences.js
// ---------------------------------------------------------------------------

/** Switch the profile to a fresh directory inside the test temp root. */
function freshUserData(name) {
  const dir = path.join(tmpRoot, 'profiles', name);
  fs.mkdirSync(dir, { recursive: true });
  process.env.AGW_USER_DATA_DIR = dir;
  return dir;
}

describe('preferences language persistence', () => {
  it('stores only the language in <userData>/preferences.json', () => {
    const dir = freshUserData('set-en');
    const file = path.join(dir, 'preferences.json');

    // appearance.json must stay untouched: the language is not a visual field.
    assert.equal(fs.existsSync(path.join(paths.APP_DIR, 'appearance.json')), false);

    assert.equal(preferences.setLanguage('en'), 'en');
    assert.equal(preferences.getLanguage(), 'en');
    assert.equal(preferences.loadPreferences().language, 'en');
    assert.equal(preferences.preferencesPath(), file);
    assert.equal(
      path.dirname(preferences.preferencesPath()),
      paths.userDataDir(),
      'the file must live in the userData directory, not in AGW_APP_DIR',
    );
    assert.equal(fs.existsSync(file), true, 'preferences.json must be created');

    const stored = JSON.parse(fs.readFileSync(file, 'utf8'));
    assert.deepEqual(stored, { language: 'en' });
  });

  it('normalizes an unknown mode to auto', () => {
    freshUserData('unknown-mode');

    assert.equal(preferences.setLanguage('xx'), 'auto');
    assert.equal(preferences.getLanguage(), 'auto');
    assert.equal(preferences.loadPreferences().language, 'auto');
    assert.equal(preferences.setLanguage(' RU '), 'ru');
  });

  it('resolves a concrete ru/en language even in auto mode', () => {
    freshUserData('resolve');

    preferences.setLanguage('en');
    assert.equal(preferences.resolveCurrentLanguage(), 'en');

    preferences.setLanguage('ru');
    assert.equal(preferences.resolveCurrentLanguage(), 'ru');

    preferences.setLanguage('auto');
    assert.ok(
      ['ru', 'en'].includes(preferences.resolveCurrentLanguage()),
      'auto must always resolve to ru or en',
    );
  });

  it('repeated saves are idempotent and ignore unknown fields', () => {
    const dir = freshUserData('idempotent');
    const file = path.join(dir, 'preferences.json');

    assert.deepEqual(preferences.savePreferences({ language: 'ru' }), { language: 'ru' });
    const first = fs.readFileSync(file, 'utf8');

    assert.deepEqual(preferences.savePreferences({ language: 'ru' }), { language: 'ru' });
    const second = fs.readFileSync(file, 'utf8');
    assert.equal(second, first, 'rewriting the same value must not change the file');

    preferences.savePreferences({ language: 'en', bogus: 42 });
    const stored = JSON.parse(fs.readFileSync(file, 'utf8'));
    assert.deepEqual(stored, { language: 'en' }, 'unknown keys must not be persisted');

    // A patch without `language` keeps the stored value.
    preferences.savePreferences({ bogus: true });
    assert.deepEqual(JSON.parse(fs.readFileSync(file, 'utf8')), { language: 'en' });
  });

  it('never creates or modifies appearance.json', () => {
    freshUserData('appearance-untouched');

    fs.mkdirSync(paths.APP_DIR, { recursive: true });
    const appearanceFile = path.join(paths.APP_DIR, 'appearance.json');
    const sentinel = '{"sentinel":"keep-me"}';
    fs.writeFileSync(appearanceFile, sentinel, 'utf8');

    preferences.setLanguage('en');
    preferences.savePreferences({ language: 'ru' });

    assert.equal(fs.readFileSync(appearanceFile, 'utf8'), sentinel);
    assert.equal(fs.existsSync(path.join(paths.APP_DIR, 'preferences.json')), false);
  });
});

after(() => {
  fs.rmSync(tmpRoot, { recursive: true, force: true });
});
