'use strict';

/**
 * Renderer bootstrap: theme, window controls, dragging, refresh, and ZCode login.
 * All view modules attach to `window.AGW`.
 */
(function (AGW) {
  const params = new URLSearchParams(location.search);
  const previewName = params.get('preview');
  const REFRESH_TITLE = 'Обновить данные (F5)';
  const DARK_THEMES = new Set(['graphite', 'oled', 'glass-dark', 'classic-dark']);
  const LOCAL_THEMES = new Set([
    'graphite',
    'paper',
    'oled',
    'glass-dark',
    'glass-light',
    'classic-dark',
    'classic-light',
  ]);

  let latestState = { context: null, status: null, activeChat: null, chatTitles: {} };
  let appearanceSnapshot = null;

  function isDarkEffectiveTheme(theme) {
    return DARK_THEMES.has(theme);
  }

  /** `#rrggbb` only; anything else (shorthands, functions) becomes null. */
  function normalizeHexAccent(value) {
    if (typeof value !== 'string') return null;
    const trimmed = value.trim();
    return /^#[0-9a-fA-F]{6}$/.test(trimmed) ? trimmed.toLowerCase() : null;
  }

  /**
   * A custom accent is both a fill and a text colour, so the readability tokens
   * (`--on-accent`, `--accent-ink`, `--accent-bar`) are derived from the accent
   * luminance and the resolved card surface (S1-018). Without a custom accent
   * the theme's own contract values stay in charge.
   */
  function applyAccentTokens(theme, accent) {
    const root = document.documentElement;
    const math = AGW.themeMath;
    const tokens = accent && math ? math.resolveAccentTokens(accent, theme) : null;
    if (!tokens) {
      root.style.removeProperty('--on-accent');
      root.style.removeProperty('--accent-ink');
      root.style.removeProperty('--accent-bar');
      return;
    }
    root.style.setProperty('--on-accent', tokens.onAccent);
    root.style.setProperty('--accent-ink', tokens.accentInk);
    root.style.setProperty('--accent-bar', tokens.accentBar);
  }

  /**
   * Applies a settings snapshot `{ appearance, effectiveTheme, ... }` from the
   * main process (or the preview bridge): resolved theme, font scale and the
   * sun/moon icon state.
   */
  function applyAppearanceSnapshot(snapshot) {
    if (!snapshot || typeof snapshot !== 'object') return;
    const appearance = snapshot.appearance || {};
    const theme =
      typeof snapshot.effectiveTheme === 'string' && snapshot.effectiveTheme
        ? snapshot.effectiveTheme
        : 'graphite';
    const scale = Number(appearance.font_scale);
    const opacity = Number(appearance.opacity);
    const customAccent = normalizeHexAccent(appearance.custom_accent);

    appearanceSnapshot = {
      appearance: {
        schema_version: 1,
        theme: typeof appearance.theme === 'string' ? appearance.theme : 'system',
        classic_variant: appearance.classic_variant === 'light' ? 'light' : 'dark',
        font_scale: Number.isFinite(scale) && scale > 0 ? scale : 1,
        opacity: Number.isFinite(opacity) && opacity > 0 ? opacity : 1,
        custom_accent: customAccent,
      },
      effectiveTheme: theme,
      systemDark: snapshot.systemDark === true,
      alwaysOnTop: snapshot.alwaysOnTop !== false,
      openAtLogin: snapshot.openAtLogin === true,
    };

    document.documentElement.dataset.theme = theme;
    document.documentElement.style.setProperty(
      '--font-scale',
      String(appearanceSnapshot.appearance.font_scale),
    );
    document.documentElement.dataset.dark = isDarkEffectiveTheme(theme) ? 'true' : 'false';
    applyAccentTokens(theme, customAccent);
  }

  /** Local fallback for the browser preview when no settings bridge exists. */
  function applyLocalTheme(theme) {
    applyAppearanceSnapshot({
      appearance: {
        schema_version: 1,
        theme: theme.indexOf('classic') === 0 ? 'classic' : theme,
        classic_variant: theme.slice(-5) === 'light' ? 'light' : 'dark',
        font_scale: 1,
        opacity: 1,
      },
      effectiveTheme: theme,
    });
  }

  const bridge = window.agWidgets || (previewName ? AGW.preview.bridge(previewName) : null);

  document.getElementById('theme-btn').addEventListener('click', async () => {
    const effective =
      (appearanceSnapshot && appearanceSnapshot.effectiveTheme) ||
      document.documentElement.dataset.theme ||
      'graphite';
    const dark =
      isDarkEffectiveTheme(effective) || document.documentElement.dataset.dark === 'true';
    const currentTheme =
      (appearanceSnapshot && appearanceSnapshot.appearance && appearanceSnapshot.appearance.theme) ||
      null;

    if (bridge && typeof bridge.updateSettings === 'function') {
      try {
        if (currentTheme === 'classic') {
          await bridge.updateSettings({ classic_variant: dark ? 'light' : 'dark' });
        } else {
          await bridge.updateSettings({ theme: dark ? 'paper' : 'graphite' });
        }
        return;
      } catch {
        // Fall through to the local switch below.
      }
    }

    if (currentTheme === 'classic') applyLocalTheme(dark ? 'classic-light' : 'classic-dark');
    else applyLocalTheme(dark ? 'paper' : 'graphite');
  });

  const settingsButton = document.getElementById('settings-btn');
  if (settingsButton) {
    settingsButton.addEventListener('click', () => {
      if (bridge && typeof bridge.openSettings === 'function') bridge.openSettings();
    });
  }

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

  // Actions inside the cards (the quota error retry) ask for a refresh
  // through a DOM event so the view modules stay bridge-free.
  document.addEventListener('agw:refresh', refreshNow);

  const retryButton = document.getElementById('zcode-retry');
  if (retryButton) retryButton.addEventListener('click', refreshNow);

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
        result.refreshed ? 'Вход выполнен · обновляю баланс…' : 'Токен сохранён · нажмите «Обновить»',
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

  // Appearance first: the settings snapshot drives the resolved theme,
  // --font-scale and the sun/moon icon, then we subscribe to live updates
  // pushed by the main process (both windows react to the same channel).
  function initAppearance() {
    if (bridge && typeof bridge.getSettings === 'function') {
      if (typeof bridge.onAppearanceUpdate === 'function') {
        try {
          bridge.onAppearanceUpdate(applyAppearanceSnapshot);
        } catch {
          // Ignore bridges without subscription support.
        }
      }
      return bridge
        .getSettings()
        .then(applyAppearanceSnapshot)
        .catch(() => {});
    }

    const initial = params.get('theme');
    if (initial && LOCAL_THEMES.has(initial)) {
      applyLocalTheme(initial);
    } else {
      // No bridge and no `?theme`: the markup's default theme is authoritative.
      document.documentElement.dataset.dark = isDarkEffectiveTheme(
        document.documentElement.dataset.theme,
      )
        ? 'true'
        : 'false';
      document.documentElement.style.setProperty('--font-scale', '1');
    }
    return Promise.resolve();
  }

  initAppearance();

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
