'use strict';

/**
 * Unit tests for frameless dragging (D-01).
 *
 * At fractional display scale `getBounds()` returns rounded DIPs; passing a
 * fresh reading back into `setBounds()` on every animation frame makes the
 * widget grow by 1-2px per tick. `moveWindow()` therefore freezes the size for
 * the whole drag. Plain Node only: `electron` is replaced by a recording stub.
 * Run with: node --test desktop/test/window-move.test.js
 */

const fs = require('fs');
const os = require('os');
const path = require('path');
const test = require('node:test');
const assert = require('node:assert/strict');
const Module = require('module');

const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'agw-window-move-'));
const appDir = path.join(tmpRoot, 'app');
const userDataDir = path.join(tmpRoot, 'userdata');
fs.mkdirSync(appDir, { recursive: true });
fs.mkdirSync(userDataDir, { recursive: true });
process.env.AGW_APP_DIR = appDir;
process.env.AGW_USER_DATA_DIR = userDataDir;
process.env.AGW_ANNOTATIONS_DIR = path.join(tmpRoot, 'annotations');

const WINDOW_STATE_PATH = path.join(userDataDir, 'window-state.json');
/** Title-bar slice that must stay reachable (mirrors TITLEBAR_GRAB). */
const TITLEBAR_GRAB = 48;
const PRIMARY_AREA = { x: 0, y: 0, width: 1920, height: 1040 };
const SECONDARY_AREA = { x: 1920, y: 0, width: 1280, height: 1024 };

/** Every geometry call the main process makes on the widget window. */
const calls = [];
/** Real (OS) window geometry; the test never lets a move change it. */
const geometry = { x: 0, y: 0, width: 400, height: 640 };
/** Simulated Win32 rounding: every read reports a slightly larger size. */
let simulateRounding = false;
let readInflate = 0;
const listeners = Object.create(null);

class FakeBrowserWindow {
  constructor(options) {
    geometry.x = options.x;
    geometry.y = options.y;
    geometry.width = options.width;
    geometry.height = options.height;
    this.webContents = {
      on() {},
      once() {},
      send() {},
      setWindowOpenHandler() {},
    };
  }

  on(event, handler) {
    (listeners[event] || (listeners[event] = [])).push(handler);
    return this;
  }

  once(event, handler) {
    (listeners[event] || (listeners[event] = [])).push(handler);
    return this;
  }

  getBounds() {
    const width = geometry.width + (simulateRounding ? Math.floor(readInflate / 2) : 0);
    const height = geometry.height + (simulateRounding ? Math.floor(readInflate / 3) : 0);
    if (simulateRounding) readInflate += 1;
    return { x: geometry.x, y: geometry.y, width, height };
  }

  getPosition() {
    return [geometry.x, geometry.y];
  }

  getSize() {
    return [geometry.width, geometry.height];
  }

  setPosition(x, y, animate) {
    calls.push(['setPosition', x, y, animate]);
    geometry.x = x;
    geometry.y = y;
  }

  setBounds(next, animate) {
    calls.push(['setBounds', next.x, next.y, next.width, next.height, animate]);
    Object.assign(geometry, next);
  }

  setSize(width, height, animate) {
    calls.push(['setSize', width, height, animate]);
    geometry.width = width;
    geometry.height = height;
  }

  setMinimumSize(width, height) {
    calls.push(['setMinimumSize', width, height]);
  }

  setMaximumSize(width, height) {
    calls.push(['setMaximumSize', width, height]);
  }

  setAlwaysOnTop() {}
  setOpacity() {}
  setBackgroundColor() {}
  isDestroyed() {
    return false;
  }
  isMinimized() {
    return false;
  }
  isAlwaysOnTop() {
    return true;
  }
  show() {}
  hide() {}
  focus() {}
  loadFile() {}
}

const fakeElectron = {
  app: {
    isQuitting: false,
    quit() {},
    getPath: () => userDataDir,
  },
  BrowserWindow: FakeBrowserWindow,
  screen: {
    getAllDisplays: () => [{ workArea: PRIMARY_AREA }, { workArea: SECONDARY_AREA }],
    getPrimaryDisplay: () => ({ workArea: PRIMARY_AREA }),
    getDisplayMatching: () => ({ workArea: PRIMARY_AREA }),
  },
  session: {
    defaultSession: {
      setPermissionRequestHandler() {},
      setPermissionCheckHandler() {},
    },
  },
  nativeTheme: { shouldUseDarkColors: true },
};

const originalLoad = Module._load;
Module._load = function loadWithElectronStub(request, parent, isMain) {
  if (request === 'electron') return fakeElectron;
  return originalLoad.call(this, request, parent, isMain);
};
const windowApi = require('../src/main/window.js');
Module._load = originalLoad;

/** Fire every listener registered for `event` on the widget window. */
function emit(event) {
  for (const handler of listeners[event] || []) handler();
}

/** Resolve after `ms` so a debounced save has a chance to hit the disk. */
function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

windowApi.createWindow();

test('moveWindow freezes the drag size and never feeds rounding back', () => {
  assert.ok(windowApi.getWindow(), 'the widget window must exist');

  // Start a fresh drag session: the cached size is captured on the first tick.
  windowApi.saveCurrentWindowBounds();
  geometry.x = 120;
  geometry.y = 140;
  geometry.width = 406;
  geometry.height = 641;
  calls.length = 0;
  simulateRounding = true;
  readInflate = 0;

  for (let index = 0; index < 40; index += 1) {
    windowApi.moveWindow(index % 2 === 0 ? 3 : -3, index % 2 === 0 ? 2 : -2);
  }
  simulateRounding = false;

  const boundsCalls = calls.filter((call) => call[0] === 'setBounds');
  assert.equal(boundsCalls.length, 40, 'every tick must move the window');
  assert.equal(
    calls.filter((call) => call[0] === 'setPosition').length,
    0,
    'the drag must carry the frozen size, not a size-less move',
  );
  assert.deepEqual(
    boundsCalls.map((call) => [call[3], call[4]]),
    Array.from({ length: 40 }, () => [406, 641]),
    'every tick must reuse the size captured when the drag started',
  );
  assert.equal(geometry.width, 406, 'width must not grow over 40 frames');
  assert.equal(geometry.height, 641, 'height must not grow over 40 frames');
  assert.equal(boundsCalls[0][5], false, 'no animation while dragging');
});

test('moveWindow keeps integer coordinates and clamps across displays', () => {
  geometry.width = 400;
  geometry.height = 640;
  windowApi.saveCurrentWindowBounds();

  // Far right of the secondary display: the union's right edge is 1920 + 1280.
  geometry.x = 3100;
  geometry.y = 600;
  calls.length = 0;
  windowApi.moveWindow(400, 0);
  assert.equal(geometry.x, 3200 - TITLEBAR_GRAB, 'the title bar must stay on screen');

  // Far left: the window may hang off the left edge, but not entirely.
  geometry.x = -300;
  windowApi.moveWindow(-400, 0);
  assert.equal(geometry.x, 0 - geometry.width + TITLEBAR_GRAB, 'the left clamp must hold');

  // Above the desktop: the top edge never leaves the work area.
  geometry.y = 200;
  windowApi.moveWindow(0, -900);
  assert.equal(geometry.y, PRIMARY_AREA.y, 'the top edge must stay below the desktop top');

  // Below: the title strip stays reachable on the tallest display.
  geometry.y = 900;
  windowApi.moveWindow(0, 500);
  assert.equal(geometry.y, 1040 - TITLEBAR_GRAB, 'the bottom clamp must hold');

  // Fractional coordinates are rounded before they reach the OS.
  geometry.x = 100.4;
  geometry.y = 100.6;
  windowApi.moveWindow(3, 2);
  assert.equal(geometry.x, 103, 'x must be an integer');
  assert.equal(geometry.y, 103, 'y must be an integer');
});

test('a stray resize right after a move does not persist the window size', async () => {
  if (fs.existsSync(WINDOW_STATE_PATH)) fs.rmSync(WINDOW_STATE_PATH, { force: true });
  windowApi.saveCurrentWindowBounds();
  if (fs.existsSync(WINDOW_STATE_PATH)) fs.rmSync(WINDOW_STATE_PATH, { force: true });

  windowApi.moveWindow(1, 1);
  // Windows may emit `resize` from a fractional-DPI move; it must be ignored.
  emit('resize');
  emit('resized');
  await sleep(300);

  assert.equal(
    fs.existsSync(WINDOW_STATE_PATH),
    false,
    'a move must not write window-state.json through a spurious resize',
  );
});

test('a real resize after the guard window is still persisted', async () => {
  await sleep(200);
  emit('resize');
  await sleep(300);

  assert.ok(fs.existsSync(WINDOW_STATE_PATH), 'a user resize must still be saved');
  const saved = JSON.parse(fs.readFileSync(WINDOW_STATE_PATH, 'utf8'));
  assert.equal(saved.modeBounds.full.width, geometry.width);
  assert.equal(saved.modeBounds.full.height, geometry.height);
});

test('a real resize releases the frozen drag size', () => {
  geometry.width = 430;
  geometry.height = 700;
  emit('resize');
  windowApi.moveWindow(2, 2);
  const lastCall = calls.filter((call) => call[0] === 'setBounds').pop();
  assert.deepEqual([lastCall[3], lastCall[4]], [430, 700], 'the next drag re-reads the size');
});
