'use strict';

/**
 * Settings window renderer (`desktop/settings.html`).
 *
 * Talks to the main process only through `window.agWidgets`: it loads the
 * current snapshot, sends one patch per user action, and re-renders the
 * snapshot returned by IPC. When the bridge is absent (page opened outside
 * Electron), the window degrades to a readable notice instead of throwing.
 */
(function () {
  const THEMES = ['system', 'graphite', 'paper', 'oled', 'glass', 'classic'];
  const VARIANTS = ['dark', 'light'];
  const RANGE_DEBOUNCE_MS = 60;
  const RANGE_MIN_PERCENT = 5;

  const el = {
    notice: document.getElementById('bridge-notice'),
    themes: Array.prototype.slice.call(document.querySelectorAll('[data-theme-value]')),
    classicRow: document.getElementById('classic-variant'),
    variants: Array.prototype.slice.call(document.querySelectorAll('[data-variant-value]')),
    font: document.getElementById('font-scale'),
    fontBadge: document.getElementById('font-scale-value'),
    fontReset: document.getElementById('font-scale-reset'),
    opacity: document.getElementById('opacity'),
    opacityBadge: document.getElementById('opacity-value'),
    alwaysOnTop: document.getElementById('always-on-top'),
    openAtLogin: document.getElementById('open-at-login'),
    reset: document.getElementById('reset-appearance'),
    error: document.getElementById('settings-error'),
  };

  const bridge = window.agWidgets;
  const current = { theme: 'system', variant: 'dark', alwaysOnTop: false, openAtLogin: false };

  let queue = Promise.resolve();
  let rangeTimer = null;
  let rangePatch = null;
  let errorTimer = null;

  function bridgeReady() {
    return Boolean(bridge)
      && typeof bridge.getSettings === 'function'
      && typeof bridge.updateSettings === 'function'
      && typeof bridge.resetAppearance === 'function';
  }

  /** Normalize a 0..1 ratio into a whole percent snapped to the 5% slider step. */
  function toPercent(ratio, min, max, fallback) {
    const numeric = typeof ratio === 'number' ? ratio : Number(ratio);
    if (!Number.isFinite(numeric)) return fallback;
    const percent = Math.round((numeric * 100) / RANGE_MIN_PERCENT) * RANGE_MIN_PERCENT;
    return Math.min(max, Math.max(min, percent));
  }

  function selectTheme(theme) {
    current.theme = theme;
    el.themes.forEach(function (button) {
      const active = button.getAttribute('data-theme-value') === theme;
      button.classList.toggle('is-active', active);
      button.setAttribute('aria-pressed', active ? 'true' : 'false');
    });
    const isClassic = theme === 'classic';
    if (el.classicRow) {
      el.classicRow.classList.toggle('hidden', !isClassic);
      el.classicRow.setAttribute('aria-hidden', isClassic ? 'false' : 'true');
    }
  }

  function selectVariant(variant) {
    current.variant = variant;
    el.variants.forEach(function (button) {
      const active = button.getAttribute('data-variant-value') === variant;
      button.classList.toggle('is-active', active);
      button.setAttribute('aria-pressed', active ? 'true' : 'false');
    });
  }

  function setRange(input, badge, percent) {
    if (input) {
      input.value = String(percent);
      input.setAttribute('aria-valuetext', percent + '%');
    }
    if (badge) badge.textContent = percent + '%';
  }

  function setSwitch(button, on) {
    if (!button) return;
    const value = on ? 'true' : 'false';
    button.setAttribute('aria-checked', value);
    button.classList.toggle('is-on', Boolean(on));
  }

  function showError(message) {
    if (!el.error) return;
    el.error.textContent = message;
    el.error.classList.remove('hidden');
    if (errorTimer) clearTimeout(errorTimer);
    errorTimer = setTimeout(function () {
      errorTimer = null;
      el.error.classList.add('hidden');
    }, 4000);
  }

  function render(snapshot) {
    if (!snapshot || typeof snapshot !== 'object') return;
    const appearance = snapshot.appearance && typeof snapshot.appearance === 'object'
      ? snapshot.appearance
      : {};

    const theme = THEMES.indexOf(appearance.theme) === -1 ? 'system' : appearance.theme;
    const variant = VARIANTS.indexOf(appearance.classic_variant) === -1
      ? 'dark'
      : appearance.classic_variant;
    const fontPercent = toPercent(appearance.font_scale, 85, 140, 100);
    const opacityPercent = toPercent(appearance.opacity, 60, 100, 100);

    current.alwaysOnTop = Boolean(snapshot.alwaysOnTop);
    current.openAtLogin = Boolean(snapshot.openAtLogin);

    if (typeof snapshot.effectiveTheme === 'string' && snapshot.effectiveTheme) {
      document.documentElement.dataset.theme = snapshot.effectiveTheme;
    }
    // The accessibility scale must reach this window too (S1-036).
    document.documentElement.style.setProperty('--font-scale', String(fontPercent / 100));

    selectTheme(theme);
    selectVariant(variant);
    setRange(el.font, el.fontBadge, fontPercent);
    // At 100% the reset button has nothing to do, so it is muted (D-14).
    if (el.fontReset) el.fontReset.disabled = fontPercent === 100;
    setRange(el.opacity, el.opacityBadge, opacityPercent);
    setSwitch(el.alwaysOnTop, current.alwaysOnTop);
    setSwitch(el.openAtLogin, current.openAtLogin);
  }

  /** Serialize IPC calls so a slow response can never overwrite a newer snapshot. */
  function applyPatch(patch) {
    if (!bridgeReady() || !patch || typeof patch !== 'object') return;
    queue = queue
      .then(function () {
        return bridge.updateSettings(patch);
      })
      .then(function (snapshot) {
        if (snapshot) render(snapshot);
      })
      .catch(function () {
        showError('Не удалось применить настройку.');
      });
  }

  function queueRangePatch(patch) {
    rangePatch = Object.assign(rangePatch || {}, patch);
    if (rangeTimer) clearTimeout(rangeTimer);
    rangeTimer = setTimeout(flushRangePatch, RANGE_DEBOUNCE_MS);
  }

  function flushRangePatch() {
    if (rangeTimer) {
      clearTimeout(rangeTimer);
      rangeTimer = null;
    }
    if (!rangePatch) return;
    const patch = rangePatch;
    rangePatch = null;
    applyPatch(patch);
  }

  /** Two-step reset: the destructive action asks once, in place, then forgets. */
  const RESET_CONFIRM_MS = 4000;
  const RESET_LABEL = 'Сбросить всё';
  const RESET_CONFIRM_LABEL = 'Точно сбросить?';
  let resetConfirmTimer = null;

  function disarmReset() {
    if (resetConfirmTimer) {
      clearTimeout(resetConfirmTimer);
      resetConfirmTimer = null;
    }
    if (!el.reset) return;
    el.reset.classList.remove('is-confirming');
    el.reset.textContent = RESET_LABEL;
  }

  function armReset() {
    if (!el.reset) return;
    el.reset.classList.add('is-confirming');
    el.reset.textContent = RESET_CONFIRM_LABEL;
    if (resetConfirmTimer) clearTimeout(resetConfirmTimer);
    resetConfirmTimer = setTimeout(disarmReset, RESET_CONFIRM_MS);
  }

  function resetAppearance() {
    if (!bridgeReady() || !el.reset) return;
    disarmReset();
    el.reset.disabled = true;
    queue = queue
      .then(function () {
        return bridge.resetAppearance();
      })
      .then(function (snapshot) {
        if (snapshot) render(snapshot);
      })
      .catch(function () {
        showError('Не удалось сбросить внешний вид.');
      })
      .then(function () {
        el.reset.disabled = false;
      });
  }

  function bindEvents() {
    el.themes.forEach(function (button) {
      button.addEventListener('click', function () {
        const theme = button.getAttribute('data-theme-value');
        if (THEMES.indexOf(theme) === -1) return;
        selectTheme(theme);
        applyPatch({ theme: theme });
      });
    });

    el.variants.forEach(function (button) {
      button.addEventListener('click', function () {
        const variant = button.getAttribute('data-variant-value');
        if (VARIANTS.indexOf(variant) === -1) return;
        selectVariant(variant);
        applyPatch({ classic_variant: variant });
      });
    });

    if (el.font) {
      el.font.addEventListener('input', function () {
        const percent = toPercent(Number(el.font.value) / 100, 85, 140, 100);
        setRange(el.font, el.fontBadge, percent);
        if (el.fontReset) el.fontReset.disabled = percent === 100;
        queueRangePatch({ font_scale: percent / 100 });
      });
      el.font.addEventListener('change', flushRangePatch);
    }

    if (el.fontReset) {
      el.fontReset.addEventListener('click', function () {
        setRange(el.font, el.fontBadge, 100);
        el.fontReset.disabled = true;
        applyPatch({ font_scale: 1 });
      });
    }

    if (el.opacity) {
      el.opacity.addEventListener('input', function () {
        const percent = toPercent(Number(el.opacity.value) / 100, 60, 100, 100);
        setRange(el.opacity, el.opacityBadge, percent);
        queueRangePatch({ opacity: percent / 100 });
      });
      el.opacity.addEventListener('change', flushRangePatch);
    }

    if (el.alwaysOnTop) {
      el.alwaysOnTop.addEventListener('click', function () {
        const next = !current.alwaysOnTop;
        current.alwaysOnTop = next;
        setSwitch(el.alwaysOnTop, next);
        applyPatch({ alwaysOnTop: next });
      });
    }

    if (el.openAtLogin) {
      el.openAtLogin.addEventListener('click', function () {
        const next = !current.openAtLogin;
        current.openAtLogin = next;
        setSwitch(el.openAtLogin, next);
        applyPatch({ openAtLogin: next });
      });
    }

    if (el.reset) {
      el.reset.addEventListener('click', function () {
        if (!el.reset.classList.contains('is-confirming')) {
          armReset();
          return;
        }
        resetAppearance();
      });
    }
  }

  function showUnavailable() {
    if (el.notice) {
      el.notice.textContent = 'Настройки доступны только в приложении.';
      el.notice.classList.remove('hidden');
    }
    document.body.classList.add('is-disabled');
    Array.prototype.forEach.call(document.querySelectorAll('button, input'), function (node) {
      node.disabled = true;
    });
  }

  function start() {
    if (!bridgeReady()) {
      showUnavailable();
      return;
    }
    bindEvents();
    bridge
      .getSettings()
      .then(render)
      .catch(function () {
        showError('Не удалось загрузить настройки.');
      });
    if (typeof bridge.onAppearanceUpdate === 'function') {
      bridge.onAppearanceUpdate(render);
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start, { once: true });
  } else {
    start();
  }
})();
