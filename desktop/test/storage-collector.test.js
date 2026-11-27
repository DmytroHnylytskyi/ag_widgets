'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const test = require('node:test');
const assert = require('node:assert/strict');

const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'agw-desktop-test-'));
const appDir = path.join(tmpRoot, 'app');
const annotationsDir = path.join(tmpRoot, 'annotations');
fs.mkdirSync(path.join(appDir, 'state'), { recursive: true });
fs.mkdirSync(annotationsDir, { recursive: true });
process.env.AGW_APP_DIR = appDir;
process.env.AGW_USER_DATA_DIR = path.join(tmpRoot, 'userdata');
process.env.AGW_ANNOTATIONS_DIR = annotationsDir;

const storage = require('../src/main/storage.js');
const collector = require('../src/main/collector.js');
const tray = require('../src/main/tray.js');

/** Create an isolated subdirectory under the temporary root. */
function makeDir(name) {
  const dir = path.join(tmpRoot, name);
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

/** Build a minimal Antigravity-style annotation body for parser tests. */
function annotationText({ title, viewedSeconds } = {}) {
  const lines = ['annotations {'];
  if (typeof title === 'string') lines.push(`  title: "${title}"`);
  if (Number.isFinite(viewedSeconds)) {
    lines.push('  last_user_view_time {');
    lines.push(`    seconds: ${viewedSeconds}`);
    lines.push('  }');
  }
  lines.push('}');
  return `${lines.join('\n')}\n`;
}

/** Write one .pbtxt file and optionally pin its mtime (milliseconds). */
function writeAnnotation(dir, name, options = {}) {
  const filePath = path.join(dir, name);
  fs.writeFileSync(filePath, annotationText(options), 'utf8');
  if (Number.isFinite(options.mtimeMs)) {
    fs.utimesSync(filePath, new Date(options.mtimeMs), new Date(options.mtimeMs));
  }
  return filePath;
}

// --- parseAnnotation -------------------------------------------------------

test('parseAnnotation extracts the title and last_user_view_time', () => {
  const text = [
    'annotations {',
    '  title: "Orbit App"',
    '  last_user_view_time {',
    '    seconds: 1700000000',
    '  }',
    '}',
  ].join('\n');

  assert.deepEqual(storage.parseAnnotation(text, 1), {
    title: 'Orbit App',
    viewedAt: 1700000000,
  });
});

test('parseAnnotation falls back to mtime seconds when the view time is absent', () => {
  const text = 'annotations {\n  title: "Orbit App"\n}\n';
  assert.deepEqual(storage.parseAnnotation(text, 1700000555), {
    title: 'Orbit App',
    viewedAt: 1700000555,
  });
});

test('parseAnnotation returns an empty title when the annotation has none', () => {
  const text = 'annotations {\n  last_user_view_time {\n    seconds: 1700000000\n  }\n}\n';
  const parsed = storage.parseAnnotation(text, 0);
  assert.equal(parsed.title, '');
  assert.equal(parsed.viewedAt, 1700000000);
});

test('parseAnnotation never throws for null or non-string input', () => {
  for (const value of [null, undefined, 42, { title: 'object' }]) {
    let parsed;
    assert.doesNotThrow(() => {
      parsed = storage.parseAnnotation(value, 1700000000);
    });
    assert.equal(parsed.title, '');
  }
});

// --- detectActiveChat ------------------------------------------------------

test('detectActiveChat prefers last_user_view_time over a newer file mtime', () => {
  const dir = makeDir('annotations-priority');
  const nowMs = Date.now();
  const nowSec = Math.floor(nowMs / 1000);

  // File A: stale mtime (one hour old) but a fresh view timestamp.
  writeAnnotation(dir, 'orbit.pbtxt', {
    title: 'Orbit App',
    viewedSeconds: nowSec - 60,
    mtimeMs: nowMs - 60 * 60 * 1000,
  });
  // File B: recent mtime but no view timestamp, so mtime is the fallback.
  writeAnnotation(dir, 'legacy.pbtxt', {
    title: 'Legacy Chat',
    mtimeMs: nowMs - 2 * 60 * 1000,
  });

  assert.deepEqual(storage.detectActiveChat(dir), {
    id: 'orbit',
    title: 'Orbit App',
    viewedAt: nowSec - 60,
  });
});

test('detectActiveChat breaks equal view times with the newer mtime', () => {
  const dir = makeDir('annotations-tie');
  const nowMs = Date.now();
  const sameView = Math.floor(nowMs / 1000) - 30;

  writeAnnotation(dir, 'older.pbtxt', {
    title: 'Older',
    viewedSeconds: sameView,
    mtimeMs: nowMs - 10 * 60 * 1000,
  });
  writeAnnotation(dir, 'newer.pbtxt', {
    title: 'Newer',
    viewedSeconds: sameView,
    mtimeMs: nowMs - 60 * 1000,
  });

  const active = storage.detectActiveChat(dir);
  assert.ok(active);
  assert.equal(active.id, 'newer');
  assert.equal(active.viewedAt, sameView);
});

test('detectActiveChat includes annotations without a title', () => {
  const dir = makeDir('annotations-untitled');
  const nowSec = Math.floor(Date.now() / 1000);

  writeAnnotation(dir, 'untitled.pbtxt', { viewedSeconds: nowSec - 10 });
  writeAnnotation(dir, 'named.pbtxt', {
    title: 'Named Chat',
    viewedSeconds: nowSec - 3600,
  });

  const active = storage.detectActiveChat(dir);
  assert.ok(active);
  assert.equal(active.id, 'untitled');
  assert.equal(active.title, '');
});

test('detectActiveChat returns null for a missing directory', () => {
  assert.equal(storage.detectActiveChat(path.join(tmpRoot, 'does-not-exist')), null);
});

test('detectActiveChat ignores non-.pbtxt entries and subdirectories', () => {
  const dir = makeDir('annotations-filter');
  const nowSec = Math.floor(Date.now() / 1000);

  writeAnnotation(dir, 'real.pbtxt', {
    title: 'Real Chat',
    viewedSeconds: nowSec - 120,
  });
  // A newer annotation-like body in a non-.pbtxt file must not win.
  fs.writeFileSync(
    path.join(dir, 'notes.txt'),
    annotationText({ title: 'Notes', viewedSeconds: nowSec }),
    'utf8',
  );
  fs.writeFileSync(path.join(dir, 'status.json'), JSON.stringify({ title: 'Json' }), 'utf8');
  fs.mkdirSync(path.join(dir, 'folder.pbtxt'));

  const active = storage.detectActiveChat(dir);
  assert.ok(active);
  assert.equal(active.id, 'real');
  assert.equal(active.title, 'Real Chat');
});

// --- scanAnnotations -------------------------------------------------------

test('scanAnnotations collects non-empty titles and the active chat in one pass', () => {
  const dir = makeDir('annotations-scan');
  const nowSec = Math.floor(Date.now() / 1000);

  writeAnnotation(dir, 'alpha.pbtxt', { title: '  Alpha Chat  ', viewedSeconds: nowSec - 300 });
  writeAnnotation(dir, 'beta.pbtxt', { title: 'Beta Chat', viewedSeconds: nowSec - 100 });
  writeAnnotation(dir, 'untitled.pbtxt', { title: '', viewedSeconds: nowSec - 500 });

  const scan = storage.scanAnnotations(dir);

  assert.deepEqual(scan.chatTitles, { alpha: 'Alpha Chat', beta: 'Beta Chat' });
  assert.ok(scan.activeChat);
  assert.equal(scan.activeChat.id, 'beta');
  assert.deepEqual(scan.activeChat, storage.detectActiveChat(dir));
});

test('scanAnnotations returns an empty result for a missing directory', () => {
  assert.deepEqual(storage.scanAnnotations(path.join(tmpRoot, 'scan-missing')), {
    activeChat: null,
    chatTitles: {},
  });
});

test('readAllState exposes chatTitles for the configured annotations dir', () => {
  writeAnnotation(annotationsDir, 'renderer-chat.pbtxt', {
    title: 'Renderer Chat',
    viewedSeconds: Math.floor(Date.now() / 1000),
    mtimeMs: Date.now(),
  });

  const state = storage.readAllState();

  assert.equal(typeof state.chatTitles, 'object');
  assert.equal(state.chatTitles['renderer-chat'], 'Renderer Chat');
  assert.ok(state.activeChat);
  assert.equal(state.activeChat.id, 'renderer-chat');
});

// --- writeJsonAtomic -------------------------------------------------------

test('writeJsonAtomic writes valid JSON readable by readJson', () => {
  const dir = makeDir('storage-write');
  const filePath = path.join(dir, 'status.json');
  const data = { context_summary: { conversation_id: 'chat-1' }, ok: true };

  storage.writeJsonAtomic(filePath, data);

  assert.deepEqual(storage.readJson(filePath), data);
});

test('writeJsonAtomic leaves no *.tmp files behind', () => {
  const dir = makeDir('storage-tmp-check');
  const filePath = path.join(dir, 'status.json');

  storage.writeJsonAtomic(filePath, { first: true });
  storage.writeJsonAtomic(filePath, { second: true });

  const leftovers = fs.readdirSync(dir).filter((name) => name.endsWith('.tmp'));
  assert.deepEqual(leftovers, []);
});

test('writeJsonAtomic overwrites an existing file', () => {
  const dir = makeDir('storage-overwrite');
  const filePath = path.join(dir, 'config.json');

  storage.writeJsonAtomic(filePath, { version: 1, nested: { stale: true } });
  storage.writeJsonAtomic(filePath, { version: 2, nested: { stale: false } });

  assert.deepEqual(storage.readJson(filePath), { version: 2, nested: { stale: false } });
});

// --- collector.buildCollectorArgs -----------------------------------------

test('buildCollectorArgs adds --force only for a strict boolean true', () => {
  assert.deepEqual(collector.buildCollectorArgs(true), ['--force']);
  for (const value of [false, undefined, null, 1, 0, 'true']) {
    assert.deepEqual(
      collector.buildCollectorArgs(value),
      [],
      `expected no flags for ${String(value)}`,
    );
  }
});

// --- collector.parseCollectorOutput ---------------------------------------

test('parseCollectorOutput parses the single collector JSON line', () => {
  const stdout =
    '{"status_written":true,"topic_configured":true,"publish":{"published":false,"reason":"throttled_or_unchanged"}}';

  const parsed = collector.parseCollectorOutput(stdout);
  assert.ok(parsed);
  assert.equal(parsed.status_written, true);
  assert.equal(parsed.topic_configured, true);
  assert.deepEqual(parsed.publish, { published: false, reason: 'throttled_or_unchanged' });
});

test('parseCollectorOutput takes the last non-empty line after a warning', () => {
  const stdout = 'legacy warning: config migrated\n\n{"status_written":true,"publish":null}\n\n';

  const parsed = collector.parseCollectorOutput(stdout);
  assert.ok(parsed);
  assert.equal(parsed.status_written, true);
});

test('parseCollectorOutput returns null for empty or non-object output', () => {
  for (const value of ['', '   \n\n', 'not json', '[1,2]', '{broken']) {
    assert.equal(collector.parseCollectorOutput(value), null, `unexpected result for ${JSON.stringify(value)}`);
  }
});

// --- collector.maybePublishActiveChat -------------------------------------

test('maybePublishActiveChat runs with force:false once per active chat', async () => {
  const nowMs = Date.now();
  const nowSec = Math.floor(nowMs / 1000);
  writeAnnotation(annotationsDir, 'active-chat.pbtxt', {
    title: 'Active Chat',
    viewedSeconds: nowSec - 30,
    mtimeMs: nowMs,
  });

  let received = null;
  let runnerCalls = 0;
  let publishedCalls = 0;

  // Fake runner: no spawn, no Python; only the options are inspected.
  const runner = async (options) => {
    runnerCalls += 1;
    received = options;
    return { ok: true };
  };
  const onPublished = () => {
    publishedCalls += 1;
  };

  await collector.maybePublishActiveChat(onPublished, runner);

  assert.equal(runnerCalls, 1);
  assert.deepEqual(received, { force: false });
  assert.equal(publishedCalls, 1);

  // Same active chat again: the in-memory id cache suppresses the run.
  await collector.maybePublishActiveChat(onPublished, runner);

  assert.equal(runnerCalls, 1, 'the collector must not run twice for one chat');
  assert.equal(publishedCalls, 1);
});

// --- collector.isRunning ---------------------------------------------------

test('collector.isRunning() stays false because no real run was started', () => {
  assert.equal(collector.isRunning(), false);
});

// --- tray.buildMenuTemplate -----------------------------------------------

/** Flatten labels out of a menu template, skipping separators. */
function collectLabels(template, labels = []) {
  for (const item of template) {
    if (!item || typeof item !== 'object') continue;
    if (item.type === 'separator') continue;
    if (typeof item.label === 'string') labels.push(item.label);
    if (Array.isArray(item.submenu)) collectLabels(item.submenu, labels);
  }
  return labels;
}

const SHOW_LABEL = 'Показать виджет';
const HIDE_LABEL = 'Свернуть в трей';
const LOGOUT_LABEL = 'Выйти из Z.ai';

test('buildMenuTemplate omits "Показать виджет" in the window menu', () => {
  const labels = collectLabels(tray.buildMenuTemplate({ source: 'window', windowVisible: true }));
  assert.equal(labels.includes(SHOW_LABEL), false);
  assert.equal(labels.includes(HIDE_LABEL), true);
});

test('buildMenuTemplate offers "Показать виджет" when the tray window is hidden', () => {
  const labels = collectLabels(tray.buildMenuTemplate({ source: 'tray', windowVisible: false }));
  assert.equal(labels.includes(SHOW_LABEL), true);
  assert.equal(labels.includes(HIDE_LABEL), false);
});

test('buildMenuTemplate offers "Свернуть в трей" when the tray window is visible', () => {
  const labels = collectLabels(tray.buildMenuTemplate({ source: 'tray', windowVisible: true }));
  assert.equal(labels.includes(HIDE_LABEL), true);
  assert.equal(labels.includes(SHOW_LABEL), false);
});

test('buildMenuTemplate shows "Выйти из Z.ai" only for a connected ZCode', () => {
  const connected = collectLabels(tray.buildMenuTemplate({ zcodeConnected: true }));
  const disconnected = collectLabels(tray.buildMenuTemplate({ zcodeConnected: false }));
  assert.equal(connected.includes(LOGOUT_LABEL), true);
  assert.equal(disconnected.includes(LOGOUT_LABEL), false);
});

test('buildMenuTemplate routes checkbox clicks to injected handlers', () => {
  const calls = [];
  const template = tray.buildMenuTemplate({
    source: 'tray',
    windowVisible: true,
    alwaysOnTop: true,
    handlers: { toggleAlwaysOnTop: (checked) => calls.push(checked) },
  });
  const item = template.find((entry) => entry.label === 'Поверх всех окон');
  assert.ok(item);
  item.click({ checked: false });
  assert.deepEqual(calls, [false]);
});

test.after(() => {
  fs.rmSync(tmpRoot, { recursive: true, force: true });
});
