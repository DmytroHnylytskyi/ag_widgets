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
  const DENSITIES = ['compact', 'comfortable', 'spacious'];
  const PRESET_MODES = ['full', 'compact', 'minibar', 'pill'];
  const BLOCK_IDS = ['context_hero', 'quotas_grid', 'zcode_card'];
  const BLOCK_DEFAULT_OPTIONS = {
    context_hero: { show_recent: true, recent_count: 3, show_thresholds: true },
    quotas_grid: { show_gemini: true, show_3p: true, show_countdown: true },
    zcode_card: { show_balance_bar: true },
  };
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
  let appearanceOptions = null;
  let stateLoaded = false;

  function isDarkEffectiveTheme(theme) {
    return DARK_THEMES.has(theme);
  }

  function clampInt(value, min, max, fallback) {
    const numeric = typeof value === 'number' ? value : Number(value);
    if (!Number.isFinite(numeric)) return fallback;
    return Math.min(max, Math.max(min, Math.round(numeric)));
  }

  /** `#rrggbb` only; anything else (shorthands, functions) becomes null. */
  function normalizeHexAccent(value) {
    if (typeof value !== 'string') return null;
    const trimmed = value.trim();
    return /^#[0-9a-fA-F]{6}$/.test(trimmed) ? trimmed.toLowerCase() : null;
  }

  /** Even corner radius clamped into `[0, 24]`, default 12. */
  function normalizeCornerRadius(value) {
    const numeric = typeof value === 'number' ? value : Number(value);
    if (!Number.isFinite(numeric)) return 12;
    const stepped = Math.round(numeric / 2) * 2;
    return Math.min(24, Math.max(0, stepped));
  }

  function normalizeEnum(value, allowed, fallback) {
    if (typeof value !== 'string') return fallback;
    const normalized = value.trim().toLowerCase();
    return allowed.indexOf(normalized) !== -1 ? normalized : fallback;
  }

  /** Keep only known option keys; booleans are strict, `recent_count` is clamped. */
  function normalizeBlockOptions(blockId, raw) {
    const defaults = BLOCK_DEFAULT_OPTIONS[blockId];
    const source = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : {};
    const options = {};
    Object.keys(defaults).forEach(function (key) {
      if (key === 'recent_count') {
        options[key] = clampInt(source[key], 0, 5, defaults[key]);
      } else {
        options[key] = typeof source[key] === 'boolean' ? source[key] : defaults[key];
      }
    });
    return options;
  }

  /** Ordered block list: user order is kept, missing blocks are appended. */
  function normalizeBlocks(raw) {
    const entries = Array.isArray(raw) ? raw : [];
    const seen = {};
    const blocks = [];
    entries.forEach(function (entry) {
      if (!entry || typeof entry !== 'object' || Array.isArray(entry)) return;
      const id = typeof entry.id === 'string' ? entry.id.trim() : '';
      if (BLOCK_IDS.indexOf(id) === -1 || seen[id]) return;
      seen[id] = true;
      blocks.push({
        id: id,
        enabled: typeof entry.enabled === 'boolean' ? entry.enabled : true,
        options: normalizeBlockOptions(id, entry.options),
      });
    });
    BLOCK_IDS.forEach(function (id) {
      if (seen[id]) return;
      blocks.push({ id: id, enabled: true, options: normalizeBlockOptions(id, null) });
    });
    return blocks;
  }

  function hexToRgbTriplet(hex) {
    const value = hex.replace('#', '');
    return (
      parseInt(value.slice(0, 2), 16) +
      ', ' +
      parseInt(value.slice(2, 4), 16) +
      ', ' +
      parseInt(value.slice(4, 6), 16)
    );
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

  /** Reorder the card rail and hide disabled blocks; order comes from settings. */
  function applyBlockLayout(blocks) {
    const byId = {};
    blocks.forEach(function (block, index) {
      byId[block.id] = { block: block, index: index };
    });
    const nodes = document.querySelectorAll('[data-block]');
    Array.prototype.forEach.call(nodes, function (node) {
      const id = node.getAttribute('data-block');
      const entry = byId[id];
      if (!entry) return;
      node.style.order = String(entry.index);
      node.classList.toggle('hidden', entry.block.enabled === false);
    });
  }

  /** Options for one block, with defaults available before the first snapshot. */
  function blockOptionsFor(id) {
    return (appearanceOptions && appearanceOptions[id]) || BLOCK_DEFAULT_OPTIONS[id];
  }

  /**
   * Applies a settings snapshot `{ appearance, effectiveTheme, ... }` from the
   * main process (or the preview bridge): resolved theme, font scale, density,
   * form factor, corner radius, custom accent, block order and the sun/moon
   * icon state.
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
    const cornerRadius = normalizeCornerRadius(appearance.corner_radius);
    const density = normalizeEnum(appearance.density, DENSITIES, 'comfortable');
    const presetMode = normalizeEnum(appearance.preset_mode, PRESET_MODES, 'full');
    const blocks = normalizeBlocks(appearance.blocks);

    const options = {};
    blocks.forEach(function (block) {
      options[block.id] = block.options;
    });
    appearanceOptions = options;

    appearanceSnapshot = {
      appearance: {
        schema_version: 1,
        theme: typeof appearance.theme === 'string' ? appearance.theme : 'system',
        classic_variant: appearance.classic_variant === 'light' ? 'light' : 'dark',
        font_scale: Number.isFinite(scale) && scale > 0 ? scale : 1,
        opacity: Number.isFinite(opacity) && opacity > 0 ? opacity : 1,
        custom_accent: customAccent,
        corner_radius: cornerRadius,
        density: density,
        preset_mode: presetMode,
        blocks: blocks,
      },
      effectiveTheme: theme,
      systemDark: snapshot.systemDark === true,
      alwaysOnTop: snapshot.alwaysOnTop !== false,
      openAtLogin: snapshot.openAtLogin === true,
    };

    const root = document.documentElement;
    root.dataset.theme = theme;
    root.style.setProperty('--font-scale', String(appearanceSnapshot.appearance.font_scale));
    root.dataset.dark = isDarkEffectiveTheme(theme) ? 'true' : 'false';
    root.dataset.density = density;
    root.dataset.mode = presetMode;
    root.style.setProperty('--card-radius', cornerRadius + 'px');

    if (customAccent) {
      root.dataset.customAccent = 'true';
      root.style.setProperty('--custom-accent', customAccent);
      root.style.setProperty('--accent', customAccent);
      root.style.setProperty('--glow', customAccent);
      root.style.setProperty('--glow-rgb', hexToRgbTriplet(customAccent));
    } else {
      if (root.dataset.customAccent) delete root.dataset.customAccent;
      root.style.removeProperty('--custom-accent');
      root.style.removeProperty('--accent');
      root.style.removeProperty('--glow');
      root.style.removeProperty('--glow-rgb');
    }
    applyAccentTokens(theme, customAccent);

    applyBlockLayout(blocks);

    // The strip replaces the card rail in the minibar/pill form factors; the
    // markup ships it hidden so `full` and `compact` stay unchanged.
    const strip = document.getElementById('compact-strip');
    if (strip) {
      const stripMode = presetMode === 'minibar' || presetMode === 'pill';
      strip.classList.toggle('hidden', !stripMode);
    }

    // Option toggles (recent list, thresholds, quotas, balance bar) take effect
    // immediately; the 30s tick and state pushes keep the data itself fresh.
    if (stateLoaded) renderViews(latestState);
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
  // real clicks on the buttons in Electron, so the title bar (and the compact
  // strip in the minibar/pill modes) drives IPC instead. Offsets accumulate and
  // are flushed at most once per animation frame.
  function attachDrag(handle) {
    if (!handle) return;
    let dragPoint = null;
    let dragOffset = { dx: 0, dy: 0 };
    let dragFrame = null;

    function flushDragOffset() {
      dragFrame = null;
      const dx = dragOffset.dx;
      const dy = dragOffset.dy;
      dragOffset = { dx: 0, dy: 0 };
      if ((!dx && !dy) || !bridge || typeof bridge.moveWindow !== 'function') return;
      bridge.moveWindow(dx, dy);
    }

    handle.addEventListener('pointerdown', function (event) {
      if (event.button !== 0 || event.target.closest('button')) return;
      dragPoint = { x: event.screenX, y: event.screenY };
      handle.setPointerCapture(event.pointerId);
      event.preventDefault();
    });
    handle.addEventListener('pointermove', function (event) {
      if (!dragPoint) return;
      dragOffset.dx += event.screenX - dragPoint.x;
      dragOffset.dy += event.screenY - dragPoint.y;
      dragPoint = { x: event.screenX, y: event.screenY };
      if (!dragOffset.dx && !dragOffset.dy) return;
      if (dragFrame === null) dragFrame = requestAnimationFrame(flushDragOffset);
    });
    function stopDrag(event) {
      if (!dragPoint) return;
      dragPoint = null;
      if (dragFrame !== null) cancelAnimationFrame(dragFrame);
      flushDragOffset();
      if (bridge && typeof bridge.saveWindowPosition === 'function') bridge.saveWindowPosition();
      try {
        handle.releasePointerCapture(event.pointerId);
      } catch {
        // The pointer may already be released.
      }
    }
    handle.addEventListener('pointerup', stopDrag);
    handle.addEventListener('pointercancel', stopDrag);
  }

  attachDrag(document.querySelector('.titlebar'));
  attachDrag(document.getElementById('compact-strip'));

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

  /**
   * One-line summary for the minibar/pill form factors: context level + fill,
   * the 5h Gemini bucket and the ZCode plan/balance.
   */
  function renderCompactStrip(state) {
    const strip = document.getElementById('compact-strip');
    if (!strip) return;
    const ctxEl = document.getElementById('strip-ctx');
    const quotaEl = document.getElementById('strip-quota');
    const zcodeEl = document.getElementById('strip-zcode');
    const safeState = state || {};
    const status = safeState.status || null;

    let level = 'unknown';
    if (AGW.context && typeof AGW.context.contextInfo === 'function') {
      const info = AGW.context.contextInfo(safeState.context, status, safeState.activeChat);
      level = info && info.level ? info.level : 'unknown';
      if (ctxEl) {
        if (info && typeof info.tokens === 'number' && Number.isFinite(info.tokens)) {
          const pct =
            typeof info.pct === 'number' && Number.isFinite(info.pct) ? Math.round(info.pct) : null;
          // A 160px pill has room for the token count only; the percentage
          // reappears as soon as the window grows (S1-026).
          const tight =
            document.documentElement.dataset.mode === 'pill' && window.innerWidth < 180;
          ctxEl.textContent =
            AGW.format.formatTokens(info.tokens) + (pct === null || tight ? '' : ` · ${pct}%`);
        } else {
          ctxEl.textContent = '—';
        }
      }
    } else if (ctxEl) {
      ctxEl.textContent = '—';
    }
    strip.dataset.ctxLevel = level === 'green' || level === 'yellow' || level === 'red' ? level : 'unknown';

    if (quotaEl) {
      const buckets =
        status && status.antigravity && Array.isArray(status.antigravity.buckets)
          ? status.antigravity.buckets
          : [];
      const fiveHour = buckets.filter(function (bucket) {
        const id = String((bucket && bucket.id) || '').toLowerCase();
        return id.indexOf('3p-') !== 0 && (bucket.kind === '5h' || id === 'gemini-5h');
      })[0];
      const fraction =
        fiveHour && typeof fiveHour.remaining_fraction === 'number' && Number.isFinite(fiveHour.remaining_fraction)
          ? AGW.format.clamp(fiveHour.remaining_fraction, 0, 1)
          : null;
      quotaEl.textContent = fraction === null ? '—' : `5 ч ${Math.round(fraction * 100)}%`;
    }

    if (zcodeEl) {
      const zcode = status && status.zcode ? status.zcode : null;
      if (!zcode || zcode.method === 'disabled' || zcode.auth_ok === false) {
        zcodeEl.textContent = '—';
      } else {
        const planName = typeof zcode.plan_name === 'string' ? zcode.plan_name.trim() : '';
        const units =
          typeof zcode.remaining_units === 'number' && Number.isFinite(zcode.remaining_units)
            ? AGW.format.formatUnits(zcode.remaining_units)
            : '';
        zcodeEl.textContent = [planName, units].filter(Boolean).join(' ') || '—';
      }
    }
  }

  function renderViews(state) {
    AGW.context.render(
      state.context,
      state.status,
      state.activeChat,
      state.chatTitles,
      blockOptionsFor('context_hero'),
    );
    AGW.quotas.render(state.status, blockOptionsFor('quotas_grid'));
    AGW.zcode.render(state.status, blockOptionsFor('zcode_card'));
    renderCompactStrip(state);
    updateScrollAffordance();
  }

  /**
   * The card rail is the only scroller. When it overflows, `data-scrollable`
   * drives a thin visible scrollbar plus a bottom fade so the hidden rest of
   * the summary is discoverable without guessing the wheel (S1-024, S1-025).
   */
  function updateScrollAffordance() {
    const rail = document.getElementById('widget-cards');
    if (!rail) return;
    const maxScroll = rail.scrollHeight - rail.clientHeight;
    const scrollable = maxScroll > 2;
    rail.dataset.scrollable = scrollable ? 'true' : 'false';
    // Content shorter than the rail is centred instead of leaving a 250px void
    // under the empty/reordered state (S1-054).
    rail.dataset.fits = scrollable ? 'false' : 'true';
    rail.dataset.scrollEnd =
      scrollable && rail.scrollTop >= maxScroll - 2 ? 'true' : 'false';
  }

  function render(state) {
    latestState = state || { context: null, status: null, activeChat: null, chatTitles: {} };
    stateLoaded = true;
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

  const cardsRail = document.getElementById('widget-cards');
  if (cardsRail) cardsRail.addEventListener('scroll', updateScrollAffordance, { passive: true });
  window.addEventListener('resize', () => {
    updateScrollAffordance();
    if (stateLoaded) renderCompactStrip(latestState);
  });

  const stripRefreshButton = document.getElementById('strip-refresh-btn');
  if (stripRefreshButton) stripRefreshButton.addEventListener('click', refreshNow);
  const stripSettingsButton = document.getElementById('strip-settings-btn');
  if (stripSettingsButton) {
    stripSettingsButton.addEventListener('click', () => {
      if (bridge && typeof bridge.openSettings === 'function') bridge.openSettings();
    });
  }

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
        AGW.zcode.render(latestState.status, blockOptionsFor('zcode_card'));
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
      AGW.zcode.render(latestState.status, blockOptionsFor('zcode_card'));
      return;
    }

    const attempt = ++loginAttempt;
    clearLoginTimer();
    AGW.zcode.setLogin('pending');
    AGW.zcode.render(latestState.status, blockOptionsFor('zcode_card'));

    try {
      if (!bridge || typeof bridge.zcodeLogin !== 'function') throw new Error('Вход недоступен');
      const result = await bridge.zcodeLogin();
      if (attempt !== loginAttempt) return;
      if (!result || !result.ok) throw new Error((result && result.error) || 'вход не выполнен');

      AGW.zcode.setLogin(
        'success',
        result.refreshed ? 'Вход выполнен · обновляю баланс…' : 'Токен сохранён · нажмите «Обновить»',
      );
      AGW.zcode.render(latestState.status, blockOptionsFor('zcode_card'));

      const state = await bridge.getState();
      if (attempt !== loginAttempt) return;
      if (state) render(state);

      resetLoginLater('success', 4500);
    } catch (error) {
      if (attempt !== loginAttempt) return;
      AGW.zcode.setLogin('error', String(error.message || error).slice(0, 90));
      AGW.zcode.render(latestState.status, blockOptionsFor('zcode_card'));
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
      document.documentElement.dataset.density = 'comfortable';
      document.documentElement.dataset.mode = 'full';
      document.documentElement.style.setProperty('--font-scale', '1');
      document.documentElement.style.setProperty('--card-radius', '12px');
      applyBlockLayout(normalizeBlocks(null));
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
