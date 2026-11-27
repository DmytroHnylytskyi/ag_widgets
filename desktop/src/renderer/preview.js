'use strict';

/**
 * Mock data for design QA without Electron:
 * `index.html?preview=red|yellow|green|empty|offline|coupon|disabled|summary|api_error|unknown`.
 * `red` is the default scenario. Exposed as `window.AGW.preview`.
 */
(function (AGW) {
  const THEME_PRESETS = {
    graphite: { theme: 'graphite', classic_variant: 'dark', effectiveTheme: 'graphite', systemDark: true },
    paper: { theme: 'paper', classic_variant: 'dark', effectiveTheme: 'paper', systemDark: false },
    oled: { theme: 'oled', classic_variant: 'dark', effectiveTheme: 'oled', systemDark: true },
    'glass-dark': { theme: 'glass', classic_variant: 'dark', effectiveTheme: 'glass-dark', systemDark: true },
    'glass-light': { theme: 'glass', classic_variant: 'light', effectiveTheme: 'glass-light', systemDark: false },
    'classic-dark': { theme: 'classic', classic_variant: 'dark', effectiveTheme: 'classic-dark', systemDark: true },
    'classic-light': { theme: 'classic', classic_variant: 'light', effectiveTheme: 'classic-light', systemDark: false },
    // Legacy preview links (`?theme=dark|light`) keep the author's style.
    dark: { theme: 'classic', classic_variant: 'dark', effectiveTheme: 'classic-dark', systemDark: true },
    light: { theme: 'classic', classic_variant: 'light', effectiveTheme: 'classic-light', systemDark: false },
  };
  const APPEARANCE_THEMES = ['system', 'graphite', 'paper', 'oled', 'glass', 'classic'];

  /** `?theme=` lookup; falls back to Classic Dark for old preview links. */
  function themePreset(raw) {
    const key = typeof raw === 'string' ? raw.trim().toLowerCase() : '';
    return THEME_PRESETS[key] || THEME_PRESETS['classic-dark'];
  }

  function isoOffset(hours) {
    return new Date(Date.now() + hours * 3600 * 1000).toISOString();
  }

  function previewState(name) {
    const now = new Date().toISOString();
    const base = {
      updated_at: now,
      antigravity: {
        plan_tier: 'Pro',
        error: null,
        buckets: [
          { id: 'gemini-weekly', kind: 'weekly', remaining_fraction: 0.814, reset_time: isoOffset(126) },
          { id: 'gemini-5h', kind: '5h', remaining_fraction: 0.668, reset_time: isoOffset(2.3) },
          { id: '3p-weekly', kind: 'weekly', remaining_fraction: 0.5, reset_time: isoOffset(165) },
          { id: '3p-5h', kind: '5h', remaining_fraction: 0.0, reset_time: isoOffset(5.2) },
        ],
      },
      zcode: {
        running: true,
        coupon_state: 'absent',
        coupon_visible: false,
        matched: '',
        plan_id: '',
        method: 'api',
        auth_ok: true,
        network_error: false,
        error_kind: null,
        remaining_units: 320_000_000,
        total_units: 500_000_000,
        ends_at: isoOffset(24 * 6),
        plan_name: 'GLM 5.3',
        checked_at: now,
      },
      context_summary: {
        model: 'gemini-4-neon-high',
        tokens: 377_387,
        window: 524_288,
        pct: 71.98,
        level: 'red',
        yellow_pct: 40,
        red_pct: 65,
        warn_tokens: 209_715,
        risk_tokens: 340_787,
        updated_at: now,
      },
    };

    const contextState = {
      last: 'preview-main',
      conversations: {
        'preview-main': {
          model: 'gemini-4-neon-high',
          tokens: 377_387,
          window: 524_288,
          pct: 71.98,
          level: 'red',
          yellow_pct: 40,
          red_pct: 65,
          warn_tokens: 209_715,
          risk_tokens: 340_787,
          workspace: 'orbit-app',
          snippet: 'Собери новый экран настроек',
          updated_at: now,
        },
        'preview-two': {
          model: 'claude-opus-5-5-high',
          tokens: 128_400,
          window: 1_000_000,
          pct: 12.84,
          level: 'green',
          workspace: 'demo-site',
          updated_at: isoOffset(-1),
        },
        'preview-three': {
          model: 'gemini-4-hydrogen-xhigh',
          tokens: 510_770,
          window: 1_048_576,
          pct: 48.71,
          level: 'yellow',
          workspace: 'workspace-alpha',
          updated_at: isoOffset(-3),
        },
      },
    };

    if (name === 'empty') {
      return { context: null, status: null, now: Date.now() };
    }

    if (name === 'summary') {
      // context.json is missing, but status.json still has the last PC summary.
      return { context: null, status: base, now: Date.now() };
    }

    if (name === 'green') {
      return {
        context: {
          last: 'preview-main',
          conversations: {
            'preview-main': {
              model: 'gemini-4-hydrogen-high',
              tokens: 187_420,
              window: 1_048_576,
              pct: 17.87,
              level: 'green',
              yellow_pct: 40,
              red_pct: 65,
              warn_tokens: 419_430,
              risk_tokens: 681_574,
              workspace: 'orbit-app',
              snippet: 'Добавь тесты для нового экрана',
              updated_at: now,
            },
          },
        },
        status: {
          ...base,
          zcode: { ...base.zcode, remaining_units: 486_000_000, coupon_visible: false },
          context_summary: {
            model: 'gemini-4-hydrogen-high',
            tokens: 187_420,
            window: 1_048_576,
            pct: 17.87,
            level: 'green',
            yellow_pct: 40,
            red_pct: 65,
            warn_tokens: 419_430,
            risk_tokens: 681_574,
            updated_at: now,
          },
        },
        now: Date.now(),
      };
    }

    if (name === 'yellow') {
      const conversation = {
        ...contextState.conversations['preview-main'],
        model: 'gemini-4-carbon-medium',
        tokens: 471_000,
        window: 1_048_576,
        pct: 44.92,
        level: 'yellow',
        warn_tokens: 419_430,
        risk_tokens: 681_574,
      };
      return {
        context: { last: 'preview-main', conversations: { 'preview-main': conversation } },
        status: {
          ...base,
          zcode: { ...base.zcode, remaining_units: 250_000_000, total_units: 500_000_000 },
          context_summary: {
            model: 'gemini-4-carbon-medium',
            tokens: 471_000,
            window: 1_048_576,
            pct: 44.92,
            level: 'yellow',
            yellow_pct: 40,
            red_pct: 65,
            warn_tokens: 419_430,
            risk_tokens: 681_574,
            updated_at: now,
          },
        },
        now: Date.now(),
      };
    }

    if (name === 'disabled') {
      return {
        context: contextState,
        status: {
          ...base,
          zcode: {
            running: false,
            coupon_state: 'absent',
            coupon_visible: false,
            matched: '',
            plan_id: '',
            method: 'disabled',
            auth_ok: null,
            network_error: false,
            error_kind: null,
            remaining_units: null,
            total_units: null,
            ends_at: null,
            plan_name: '',
          },
        },
        now: Date.now(),
      };
    }

    if (name === 'offline') {
      return {
        context: contextState,
        status: {
          ...base,
          zcode: {
            ...base.zcode,
            running: false,
            method: 'api',
            auth_ok: null,
            network_error: true,
            coupon_state: 'unknown',
            coupon_visible: null,
            error_kind: 'network',
            remaining_units: null,
            total_units: null,
            ends_at: null,
          },
        },
        now: Date.now(),
      };
    }

    if (name === 'coupon' || name === 'voucher') {
      return {
        context: {
          last: 'preview-main',
          conversations: { 'preview-main': contextState.conversations['preview-main'] },
        },
        status: {
          ...base,
          zcode: {
            ...base.zcode,
            coupon_state: 'visible',
            coupon_visible: true,
            matched: 'Забрать 500M токенов',
            remaining_units: 250_000_000,
            total_units: 500_000_000,
          },
        },
        now: Date.now(),
      };
    }

    if (name === 'api_error' || name === 'unknown') {
      const rateLimited = name === 'unknown';
      return {
        context: contextState,
        status: {
          ...base,
          antigravity: {
            ...base.antigravity,
            error: rateLimited
              ? 'Не удалось обновить квоты: лимит запросов (429)'
              : 'Формат ответа /usage изменился — проверьте обновление AG Widgets',
          },
          zcode: {
            ...base.zcode,
            coupon_state: 'unknown',
            coupon_visible: null,
            matched: '',
            network_error: false,
            error_kind: rateLimited ? 'rate_limited' : 'api_changed',
          },
        },
        now: Date.now(),
      };
    }

    return { context: contextState, status: base, now: Date.now() };
  }

  function makeBridge(name) {
    const state = previewState(name);
    if (state.context && !state.activeChat) {
      state.activeChat = { id: 'preview-main', title: 'Example Session · Orbit App' };
    }

    const preset = themePreset(new URLSearchParams(location.search).get('theme'));
    const appearance = {
      schema_version: 1,
      theme: preset.theme,
      classic_variant: preset.classic_variant,
      font_scale: 1,
      opacity: 1,
    };
    const settings = {
      systemDark: preset.systemDark,
      alwaysOnTop: true,
      openAtLogin: false,
    };
    const appearanceListeners = new Set();

    function resolveEffective() {
      if (appearance.theme === 'classic') {
        return appearance.classic_variant === 'light' ? 'classic-light' : 'classic-dark';
      }
      if (appearance.theme === 'glass') {
        return settings.systemDark ? 'glass-dark' : 'glass-light';
      }
      if (appearance.theme === 'graphite' || appearance.theme === 'paper' || appearance.theme === 'oled') {
        return appearance.theme;
      }
      return settings.systemDark ? 'graphite' : 'paper';
    }

    function snapshot() {
      return {
        appearance: { ...appearance },
        effectiveTheme: resolveEffective(),
        systemDark: settings.systemDark,
        alwaysOnTop: settings.alwaysOnTop,
        openAtLogin: settings.openAtLogin,
      };
    }

    function emit() {
      const snap = snapshot();
      appearanceListeners.forEach((cb) => {
        try {
          cb(snap);
        } catch {
          // One bad listener must not break the preview bridge.
        }
      });
      return snap;
    }

    function clampStep(value, min, max) {
      const num = Number(value);
      if (!Number.isFinite(num)) return null;
      return Math.min(max, Math.max(min, Math.round(num * 20) / 20));
    }

    function updateSettings(patch) {
      const next = patch && typeof patch === 'object' ? patch : {};
      if (typeof next.theme === 'string') {
        const theme = next.theme.trim().toLowerCase();
        if (APPEARANCE_THEMES.indexOf(theme) !== -1) appearance.theme = theme;
      }
      if (next.classic_variant === 'dark' || next.classic_variant === 'light') {
        appearance.classic_variant = next.classic_variant;
      }
      const fontScale = clampStep(next.font_scale, 0.85, 1.4);
      if (fontScale !== null) appearance.font_scale = fontScale;
      const opacity = clampStep(next.opacity, 0.6, 1);
      if (opacity !== null) appearance.opacity = opacity;
      if (typeof next.alwaysOnTop === 'boolean') settings.alwaysOnTop = next.alwaysOnTop;
      if (typeof next.openAtLogin === 'boolean') settings.openAtLogin = next.openAtLogin;
      return Promise.resolve(emit());
    }

    function resetAppearance() {
      appearance.schema_version = 1;
      appearance.theme = 'system';
      appearance.classic_variant = 'dark';
      appearance.font_scale = 1;
      appearance.opacity = 1;
      return Promise.resolve(emit());
    }

    return {
      getState: () => Promise.resolve(state),
      minimizeWindow: () => {},
      refresh: async () => ({ ok: true }),
      zcodeLogin: async () => ({ ok: false, error: 'preview mode' }),
      zcodeCancelLogin: async () => ({ ok: true }),
      zcodeLogout: async () => ({ ok: false, error: 'preview mode' }),
      openExternalAction: async () => ({ ok: true }),
      quitApp: () => {},
      showMenu: () => {},
      moveWindow: () => {},
      saveWindowPosition: async () => ({ ok: true }),
      onStateUpdate: () => () => {},
      getSettings: () => Promise.resolve(snapshot()),
      updateSettings,
      resetAppearance,
      openSettings: () => Promise.resolve({ ok: true }),
      onAppearanceUpdate: (cb) => {
        if (typeof cb !== 'function') return () => {};
        appearanceListeners.add(cb);
        return () => {
          appearanceListeners.delete(cb);
        };
      },
    };
  }

  AGW.preview = { state: previewState, bridge: makeBridge };
})(window.AGW = window.AGW || {});
