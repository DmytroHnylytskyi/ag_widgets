'use strict';

/**
 * Renderer bootstrap: theme, window controls, dragging, refresh, and ZCode login.
 * All view modules attach to `window.AGW`.
 */
(function (AGW) {
  const params = new URLSearchParams(location.search);
  const previewName = params.get('preview');
  const THEME_KEY = 'agw-theme';
  const REFRESH_TITLE = 'Обновить данные (F5)';

  let latestState = { context: null, status: null, activeChat: null, chatTitles: {} };

  function normalizeTheme(value) {
    return value === 'light' || value === 'dark' ? value : 'dark';
  }

  function applyTheme(theme, persist) {
    document.documentElement.dataset.theme = theme;
    if (persist) {
      try {
        localStorage.setItem(THEME_KEY, theme);
      } catch {
        // Private mode or a restricted profile: keep the in-memory theme.
      }
    }
  }

  function storedTheme() {
    try {
      return localStorage.getItem(THEME_KEY);
    } catch {
      return null;
    }
  }

  applyTheme(normalizeTheme(params.get('theme') || storedTheme()), false);

  document.getElementById('theme-btn').addEventListener('click', () => {
    const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
    applyTheme(next, true);
  });

  const bridge = window.agWidgets || (previewName ? AGW.preview.bridge(previewName) : null);

  document.getElementById('menu-btn').addEventListener('click', () => {
    if (bridge && typeof bridge.showMenu === 'function') bridge.showMenu();
  });
  document.getElementById('min-btn').addEventListener('click', () => {
    if (bridge && typeof bridge.minimizeWindow === 'function') bridge.minimizeWindow();
  });
  document.getElementById('quit-btn').addEventListener('click', () => {
    if (bridge && typeof bridge.quitApp === 'function') bridge.quitApp();
    else window.close();
  });
  window.addEventListener('contextmenu', (event) => {
    event.preventDefault();
    if (bridge && typeof bridge.showMenu === 'function') bridge.showMenu();
  });

  // Frameless dragging through pointer events. CSS app-region:drag would swallow
  // real clicks on the buttons in Electron, so the title bar drives IPC instead.
  // Offsets accumulate and are flushed at most once per animation frame.
  const titlebar = document.querySelector('.titlebar');
  let dragPoint = null;
  let dragOffset = { dx: 0, dy: 0 };
  let dragFrame = null;

  function flushDragOffset() {
    dragFrame = null;
    const { dx, dy } = dragOffset;
    dragOffset = { dx: 0, dy: 0 };
    if ((!dx && !dy) || !bridge || typeof bridge.moveWindow !== 'function') return;
    bridge.moveWindow(dx, dy);
  }

  titlebar.addEventListener('pointerdown', (event) => {
    if (event.button !== 0 || event.target.closest('button')) return;
    dragPoint = { x: event.screenX, y: event.screenY };
    titlebar.setPointerCapture(event.pointerId);
    event.preventDefault();
  });
  titlebar.addEventListener('pointermove', (event) => {
    if (!dragPoint) return;
    dragOffset.dx += event.screenX - dragPoint.x;
    dragOffset.dy += event.screenY - dragPoint.y;
    dragPoint = { x: event.screenX, y: event.screenY };
    if (!dragOffset.dx && !dragOffset.dy) return;
    if (dragFrame === null) dragFrame = requestAnimationFrame(flushDragOffset);
  });
  const stopDrag = (event) => {
    if (!dragPoint) return;
    dragPoint = null;
    if (dragFrame !== null) cancelAnimationFrame(dragFrame);
    flushDragOffset();
    if (bridge && typeof bridge.saveWindowPosition === 'function') bridge.saveWindowPosition();
    try {
      titlebar.releasePointerCapture(event.pointerId);
    } catch {
      // The pointer may already be released.
    }
  };
  titlebar.addEventListener('pointerup', stopDrag);
  titlebar.addEventListener('pointercancel', stopDrag);

  function renderHeader(state) {
    const timeEl = document.getElementById('updated-time');
    const dot = document.getElementById('live-dot');
    const status = state.status;
    const current = AGW.context.currentConversation(state.context, state.activeChat);
    const timestamp = (current && current.updated_at) || (status && status.updated_at);

    if (!timestamp) {
      timeEl.textContent = '—';
      timeEl.title = 'Нет данных о последнем ответе';
      dot.className = 'live-dot off';
      return;
    }

    const date = new Date(timestamp);
    if (Number.isNaN(date.getTime())) {
      timeEl.textContent = '—';
      timeEl.title = 'Нет данных о последнем ответе';
      dot.className = 'live-dot off';
      return;
    }

    const now = new Date();
    timeEl.textContent = AGW.format.shortAge(timestamp) || '—';
    timeEl.title =
      `Обновлено: ${date.toLocaleString('ru-RU')} · сейчас ${now.toLocaleTimeString('ru-RU', {
        hour: '2-digit',
        minute: '2-digit',
      })}`;
    const ageMinutes = (now.getTime() - date.getTime()) / 60000;
    dot.className = ageMinutes > 120 ? 'live-dot off' : ageMinutes > 15 ? 'live-dot stale' : 'live-dot';
  }

  function renderViews(state) {
    AGW.context.render(state.context, state.status, state.activeChat, state.chatTitles);
    AGW.quotas.render(state.status);
    AGW.zcode.render(state.status);
  }

  function render(state) {
    latestState = state || { context: null, status: null, activeChat: null, chatTitles: {} };
    renderHeader(latestState);
    renderViews(latestState);
  }

  const refreshButton = document.getElementById('refresh-btn');
  let refreshing = false;
  let refreshTimer = null;

  function clearRefreshTimer() {
    if (refreshTimer !== null) {
      clearTimeout(refreshTimer);
      refreshTimer = null;
    }
  }

  async function refreshNow() {
    if (refreshing) return;
    refreshing = true;
    clearRefreshTimer();
    refreshButton.classList.remove('error');
    refreshButton.classList.add('refreshing');
    refreshButton.title = 'Обновляю данные…';
    let failed = false;
    try {
      if (bridge && typeof bridge.refresh === 'function') {
        const result = await bridge.refresh();
        if (!result || result.ok === false) throw new Error((result && result.error) || 'collector failed');
        const state = await bridge.getState();
        if (state) render(state);
      }
    } catch (error) {
      failed = true;
      refreshButton.classList.add('error');
      refreshButton.title = `Не удалось обновить: ${error.message}`;
      refreshTimer = setTimeout(() => {
        refreshTimer = null;
        refreshButton.classList.remove('error');
        refreshButton.title = REFRESH_TITLE;
      }, 3000);
    } finally {
      refreshing = false;
      refreshButton.classList.remove('refreshing');
      if (!failed) refreshButton.title = REFRESH_TITLE;
    }
  }

  refreshButton.addEventListener('click', refreshNow);

  const loginButton = document.getElementById('zcode-login');
  let loginAttempt = 0;
  let loginTimer = null;

  function clearLoginTimer() {
    if (loginTimer !== null) {
      clearTimeout(loginTimer);
      loginTimer = null;
    }
  }

  function resetLoginLater(expectedState, delay) {
    clearLoginTimer();
    loginTimer = setTimeout(() => {
      loginTimer = null;
      if (AGW.zcode.getLogin().state === expectedState) {
        AGW.zcode.setLogin('idle');
        AGW.zcode.render(latestState.status);
      }
    }, delay);
  }

  loginButton.addEventListener('click', async () => {
    if (AGW.zcode.getLogin().state === 'pending') {
      loginAttempt += 1;
      clearLoginTimer();
      if (bridge && typeof bridge.zcodeCancelLogin === 'function') {
        try {
          await bridge.zcodeCancelLogin();
        } catch {
          // The login may have completed between the state check and the cancel.
        }
      }
      AGW.zcode.setLogin('idle');
      AGW.zcode.render(latestState.status);
      return;
    }

    const attempt = ++loginAttempt;
    clearLoginTimer();
    AGW.zcode.setLogin('pending');
    AGW.zcode.render(latestState.status);

    try {
      if (!bridge || typeof bridge.zcodeLogin !== 'function') throw new Error('Вход недоступен');
      const result = await bridge.zcodeLogin();
      if (attempt !== loginAttempt) return;
      if (!result || !result.ok) throw new Error((result && result.error) || 'вход не выполнен');

      AGW.zcode.setLogin(
        'success',
        result.refreshed ? 'Вход выполнен · обновляю баланс…' : 'Токен сохранён · нажмите ⟳ для обновления',
      );
      AGW.zcode.render(latestState.status);

      const state = await bridge.getState();
      if (attempt !== loginAttempt) return;
      if (state) render(state);

      resetLoginLater('success', 4500);
    } catch (error) {
      if (attempt !== loginAttempt) return;
      AGW.zcode.setLogin('error', String(error.message || error).slice(0, 90));
      AGW.zcode.render(latestState.status);
      resetLoginLater('error', 6000);
    }
  });

  window.addEventListener('keydown', (event) => {
    if (event.key === 'F5') {
      event.preventDefault();
      refreshNow();
    }
  });

  if (bridge) {
    bridge.onStateUpdate(render);
    bridge
      .getState()
      .then(render)
      .catch(() => render({ context: null, status: null, activeChat: null, chatTitles: {} }));
  } else {
    render({ context: null, status: null, activeChat: null, chatTitles: {} });
  }

  // Re-render periodically so relative ages and the live dot keep aging even
  // when no new state arrives from the main process.
  setInterval(() => {
    renderHeader(latestState);
    renderViews(latestState);
  }, 30_000);
})(window.AGW = window.AGW || {});
