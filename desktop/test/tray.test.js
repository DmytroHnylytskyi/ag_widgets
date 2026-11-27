'use strict';

/**
 * Unit tests for the tray/popup menu notifications checkbox.
 * The menu template is pure, so no Electron runtime is required.
 * Run with: node --test desktop/test/tray.test.js
 */

const fs = require('fs');
const os = require('os');
const path = require('path');
const test = require('node:test');
const assert = require('node:assert/strict');

// appearance.js resolves APPEARANCE_PATH from APP_DIR at require time; keep
// both the menu build and its lazy reads out of the real user profile.
const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'agw-tray-'));
process.env.AGW_APP_DIR = path.join(tmpRoot, 'app');
process.env.AGW_USER_DATA_DIR = path.join(tmpRoot, 'userdata');

const tray = require('../src/main/tray.js');

function notificationsItem(template) {
  return template.find((item) => item.label === 'Уведомления');
}

test('the tray menu adds a notifications checkbox', () => {
  const template = tray.buildMenuTemplate({ source: 'tray' });
  const item = notificationsItem(template);

  assert.ok(item, 'the Уведомления item must exist');
  assert.equal(item.type, 'checkbox');
  assert.equal(item.checked, true);
});

test('notificationsEnabled false unchecks the item', () => {
  const template = tray.buildMenuTemplate({ source: 'tray', notificationsEnabled: false });
  const item = notificationsItem(template);

  assert.ok(item, 'the Уведомления item must exist');
  assert.equal(item.type, 'checkbox');
  assert.equal(item.checked, false);
});

test('the notifications item sits between open-at-login and Settings', () => {
  const template = tray.buildMenuTemplate({ source: 'tray' });

  const openAtLoginIndex = template.findIndex((item) => item.label === 'Запускать при входе');
  const notificationsIndex = template.findIndex((item) => item.label === 'Уведомления');
  const settingsIndex = template.findIndex((item) => item.label === 'Настройки…');
  const windowModeIndex = template.findIndex((item) => item.label === 'Режим окна');

  assert.ok(openAtLoginIndex > -1, 'the autostart item must exist');
  assert.ok(notificationsIndex > -1, 'the notifications item must exist');
  assert.ok(settingsIndex > -1, 'the Settings item must exist');
  assert.ok(windowModeIndex > -1, 'the window-mode submenu must exist');

  assert.equal(notificationsIndex, openAtLoginIndex + 1);
  assert.equal(settingsIndex, notificationsIndex + 1);
  assert.equal(template[settingsIndex + 1].type, 'separator');
  assert.ok(windowModeIndex > settingsIndex + 1, 'Режим окна goes after the separator');
});

test('the notifications click routes to an injected handler', () => {
  const calls = [];
  const template = tray.buildMenuTemplate({
    source: 'tray',
    notificationsEnabled: false,
    handlers: { toggleNotifications: (checked) => calls.push(checked) },
  });
  const item = notificationsItem(template);

  assert.ok(item, 'the Уведомления item must exist');

  item.click({ checked: false });
  assert.deepEqual(calls, [false]);

  // Without an explicit `checked` flag the click must invert the rendered value.
  item.click({});
  assert.deepEqual(calls, [false, true]);
});

test('the item exists in both the tray and the window menu', () => {
  const trayItem = notificationsItem(tray.buildMenuTemplate({ source: 'tray' }));
  const windowItem = notificationsItem(tray.buildMenuTemplate({ source: 'window' }));

  assert.ok(trayItem, 'the tray menu must offer the notifications checkbox');
  assert.ok(windowItem, 'the window menu must offer the notifications checkbox');
  assert.equal(windowItem.type, 'checkbox');
});

test.after(() => {
  fs.rmSync(tmpRoot, { recursive: true, force: true });
});
