'use strict';

/**
 * Card 03: ZCode coupon, balance, and OAuth sign-in state.
 * Exposed as `window.AGW.zcode`.
 */
(function (root) {
  const AGW = (root.AGW = root.AGW || {});
  const format = AGW.format || (typeof require === 'function' ? require('./format.js') : null);
  const i18n = AGW.i18n || (typeof require === 'function' ? require('../shared/i18n.js') : null);
  const { clamp, formatEndDate, formatUnits } = format;

  /** Dictionary lookup with the RU default so Node tests stay byte-identical. */
  function tr(key, vars, lang) {
    return i18n && typeof i18n.t === 'function' ? i18n.t(key, vars, lang) : key;
  }

  const ERROR_REASON_KEYS = {
    rate_limited: 'dashboard.zcode.reason.rateLimited',
    server_error: 'dashboard.zcode.reason.serverError',
    api_changed: 'dashboard.zcode.reason.apiChanged',
    network: 'dashboard.zcode.reason.network',
    auth_expired: 'dashboard.zcode.reason.authExpired',
    parse_failed: 'dashboard.zcode.reason.parseFailed',
  };

  let loginState = 'idle';
  let loginMessage = '';

  function setLogin(state, message = '') {
    loginState = state;
    loginMessage = message;
  }

  function getLogin() {
    return { state: loginState, message: loginMessage };
  }

  function errorReason(zcode, lang = 'ru') {
    const kind = zcode && typeof zcode.error_kind === 'string' ? zcode.error_kind : '';
    if (ERROR_REASON_KEYS[kind]) return tr(ERROR_REASON_KEYS[kind], null, lang);
    if (zcode && zcode.network_error) return tr(ERROR_REASON_KEYS.network, null, lang);
    return tr('dashboard.zcode.reason.unknown', null, lang);
  }

  /** Classic keeps the author's wording; modern themes use sentence case. */
  function isClassicTheme() {
    const theme =
      typeof document !== 'undefined' && document.documentElement && document.documentElement.dataset
        ? document.documentElement.dataset.theme
        : '';
    return typeof theme === 'string' && theme.indexOf('classic') === 0;
  }

  /**
   * Word for the card top line. A failed probe must not report "Подключён"
   * next to "Ваучер не проверен" (D-12), while Classic keeps its 1:1 wording.
   */
  function connectionLabel(zcode, classic, lang = 'ru') {
    if (!zcode) return tr('dashboard.zcode.noData', null, lang);
    if (zcode.method === 'disabled') return tr('dashboard.zcode.statusDisabled', null, lang);
    if (zcode.method !== 'api') return tr('dashboard.zcode.noData', null, lang);
    if (classic) return tr('dashboard.zcode.statusConnected', null, lang);
    if (zcode.auth_ok === false) return tr('dashboard.zcode.statusExpired', null, lang);
    if (zcode.network_error || zcode.error_kind === 'network') {
      return tr('dashboard.zcode.statusOffline', null, lang);
    }
    if (zcode.error_kind) return tr('dashboard.zcode.statusUnverified', null, lang);
    return tr('dashboard.zcode.statusConnected', null, lang);
  }

  /**
   * Tri-state coupon view per the PR #1 contract: "absent" is reported only
   * when the backend confirmed it, everything uncertain stays "unknown".
   */
  function describeCoupon(zcode, lang = 'ru') {
    if (!zcode) {
      return {
        state: 'unknown',
        text: tr('dashboard.zcode.couponUnknownNoData', null, lang),
        tone: 'warn',
      };
    }

    let state;
    if (zcode.coupon_state === 'visible' || zcode.coupon_state === 'absent' || zcode.coupon_state === 'unknown') {
      state = zcode.coupon_state;
    } else if (zcode.coupon_visible === true) {
      state = 'visible';
    } else if (zcode.coupon_visible === false) {
      // Legacy payloads without `coupon_state`: a confirmed absence is only
      // trustworthy when auth succeeded and there was no transport error.
      const confirmedAbsent =
        zcode.method === 'disabled' ||
        (zcode.auth_ok === true && !zcode.network_error && !zcode.error_kind);
      state = confirmedAbsent ? 'absent' : 'unknown';
    } else {
      state = 'unknown';
    }

    if (state === 'visible') {
      return { state, text: tr('dashboard.zcode.couponVisibleText', null, lang), tone: 'ok' };
    }
    if (state === 'absent') {
      return { state, text: tr('dashboard.zcode.couponAbsentText', null, lang), tone: 'idle' };
    }
    return {
      state: 'unknown',
      text: tr('dashboard.zcode.couponUnknownText', { reason: errorReason(zcode, lang) }, lang),
      tone: 'warn',
    };
  }

  /**
   * Balance view. `0 / 500M` must stay visible as an empty progress bar,
   * while a missing total only hides the ratio (percent stays `null`).
   */
  function balanceInfo(zcode, lang = 'ru') {
    const remaining = zcode ? zcode.remaining_units : null;
    const total = zcode ? zcode.total_units : null;
    const hasTotal = Number.isFinite(total) && total > 0;
    const hasBalance = Number.isFinite(remaining) && (remaining > 0 || hasTotal);
    const percent = hasBalance && hasTotal ? clamp(Math.round((remaining / total) * 100), 0, 100) : null;

    let text = '';
    if (hasBalance) {
      text = hasTotal
        ? tr(
            'dashboard.zcode.balanceWithTotal',
            { remaining: formatUnits(remaining), total: formatUnits(total) },
            lang,
          )
        : tr('dashboard.zcode.balanceOnly', { remaining: formatUnits(remaining) }, lang);
    }
    return { hasBalance, hasTotal, percent, text };
  }

  function render(status, options = {}) {
    const opts = options && typeof options === 'object' && !Array.isArray(options) ? options : {};
    const lang = opts.lang === 'en' ? 'en' : 'ru';
    const showBalanceBar = opts.show_balance_bar !== false;
    const title = document.getElementById('zcode-title');
    const material = document.getElementById('zcode-material');
    const subtitle = document.getElementById('zcode-sub');
    const chip = document.getElementById('zcode-chip');
    const bar = document.getElementById('zcode-bar');
    const barFill = document.getElementById('zcode-bar-fill');
    const actions = document.getElementById('zcode-actions');
    const loginButton = document.getElementById('zcode-login');
    const retryButton = document.getElementById('zcode-retry');
    const zcode = status && status.zcode;
    const classic = isClassicTheme();
    const planName =
      zcode && zcode.method === 'api' && typeof zcode.plan_name === 'string'
        ? zcode.plan_name.trim()
        : '';

    const needsLogin = !zcode || zcode.method !== 'api' || zcode.auth_ok === false;
    // A transport/API failure has no login to offer: the same slot carries a
    // retry action instead, so the cause never hides behind the fold (S1-045).
    const needsRetry = Boolean(
      zcode && zcode.method === 'api' && zcode.auth_ok !== false && (zcode.network_error || zcode.error_kind),
    );
    function setRetryVisible(visible) {
      if (retryButton) {
        retryButton.classList.toggle('hidden', !visible);
        retryButton.title = visible
          ? tr('dashboard.zcode.retryTooltipReason', { reason: errorReason(zcode, lang) }, lang)
          : tr('dashboard.zcode.retryTooltip', null, lang);
      }
      if (chip) chip.classList.toggle('hidden', visible);
    }
    setRetryVisible(false);
    const actionsVisible =
      loginState === 'pending' || loginState === 'error' || (loginState === 'idle' && needsLogin);
    if (actions) actions.classList.toggle('hidden', !actionsVisible);
    if (loginButton) {
      loginButton.disabled = loginState === 'success';
      loginButton.textContent =
        loginState === 'pending'
          ? tr('dashboard.zcode.cancelLogin', null, lang)
          : loginState === 'error'
            ? tr('dashboard.zcode.retryLogin', null, lang)
            : tr('dashboard.zcode.login', null, lang);
    }

    if (bar) bar.classList.add('hidden');
    if (barFill) barFill.style.width = '0%';
    if (subtitle) subtitle.title = '';
    if (title) {
      title.textContent = planName
        ? tr('dashboard.zcode.planTitle', { plan: planName }, lang)
        : tr('dashboard.zcode.defaultTitle', null, lang);
    }
    if (material) {
      // Modern themes hide `#zcode-title` (the card heading already says
      // "ZCode"), so the plan name has to ride along the status line or it is
      // lost entirely (D-06).
      const statusWord = connectionLabel(zcode, classic, lang);
      material.textContent = !classic && planName
        ? tr('dashboard.zcode.materialWithPlan', { status: statusWord, plan: planName }, lang)
        : statusWord;
    }

    if (loginState === 'pending') {
      if (subtitle) subtitle.textContent = tr('dashboard.zcode.pendingSubtitle', null, lang);
      if (chip) {
        chip.className = 'zcode-chip chip-warn';
        chip.textContent = tr('dashboard.zcode.pendingChip', null, lang);
      }
      if (material) material.textContent = tr('dashboard.zcode.materialSignIn', null, lang);
      setRetryVisible(false);
      return;
    }

    if (loginState === 'error') {
      if (subtitle) {
        subtitle.textContent = tr('dashboard.zcode.errorSubtitle', { message: loginMessage }, lang);
      }
      if (chip) {
        chip.className = 'zcode-chip chip-error';
        chip.textContent = tr('dashboard.zcode.errorChip', null, lang);
      }
      if (material) material.textContent = tr('dashboard.zcode.materialSignIn', null, lang);
      setRetryVisible(false);
      return;
    }

    if (loginState === 'success') {
      if (subtitle) {
        subtitle.textContent =
          loginMessage || tr('dashboard.zcode.loginSuccessRefreshing', null, lang);
      }
      if (chip) {
        chip.className = 'zcode-chip chip-available';
        chip.textContent = tr('dashboard.zcode.signedIn', null, lang);
      }
      if (material) material.textContent = tr('dashboard.zcode.statusConnected', null, lang);
      setRetryVisible(false);
      return;
    }

    if (!zcode) {
      if (subtitle) subtitle.textContent = tr('dashboard.zcode.waitingCheck', null, lang);
      if (chip) {
        chip.className = 'zcode-chip chip-idle';
        chip.textContent = tr('dashboard.zcode.noData', null, lang);
      }
      setRetryVisible(false);
      return;
    }

    if (zcode.method === 'disabled') {
      if (subtitle) subtitle.textContent = tr('dashboard.zcode.disabledSubtitle', null, lang);
      if (chip) {
        chip.className = 'zcode-chip chip-idle';
        chip.textContent = tr('dashboard.zcode.disabledChip', null, lang);
      }
      setRetryVisible(false);
      return;
    }

    if (zcode.method === 'api' && zcode.auth_ok === false) {
      if (subtitle) subtitle.textContent = tr('dashboard.zcode.expiredSubtitle', null, lang);
      if (chip) {
        chip.className = 'zcode-chip chip-warn';
        chip.textContent = tr('dashboard.zcode.loginRequiredChip', null, lang);
      }
      setRetryVisible(false);
      return;
    }

    const coupon = describeCoupon(zcode, lang);
    const balance = balanceInfo(zcode, lang);
    const endsDate = formatEndDate(zcode.ends_at);
    const endsText = endsDate ? ` · ${tr('dashboard.zcode.endsAt', { date: endsDate }, lang)}` : '';

    if (bar && barFill && showBalanceBar && balance.percent !== null) {
      bar.classList.remove('hidden');
      barFill.style.width = `${balance.percent}%`;
    }

    let subtitleText;
    if (coupon.state === 'visible') {
      subtitleText = balance.text
        ? `${balance.text}${endsText}`
        : tr('dashboard.zcode.couponCta', null, lang);
    } else if (coupon.state === 'absent') {
      subtitleText = balance.text ? `${balance.text}${endsText}` : coupon.text;
    } else {
      // Short human status in the card; the technical reason stays in the
      // tooltip instead of pushing the balance out of two lines (S1-046).
      subtitleText = balance.text
        ? tr('dashboard.zcode.unverifiedWithBalance', { balance: balance.text }, lang)
        : tr('dashboard.zcode.unverified', null, lang);
    }

    if (subtitle) {
      subtitle.textContent = subtitleText;
      subtitle.title = coupon.state === 'visible' ? zcode.matched || '' : coupon.text;
    }
    if (chip) {
      chip.className =
        coupon.state === 'visible'
          ? 'zcode-chip chip-available'
          : coupon.state === 'absent'
            ? 'zcode-chip chip-none'
            : 'zcode-chip chip-warn';
      chip.textContent =
        coupon.state === 'visible'
          ? tr('dashboard.zcode.couponVisibleText', null, lang)
          : coupon.state === 'absent'
            ? tr('dashboard.zcode.chipAbsent', null, lang)
            : tr('dashboard.zcode.chipCheckFailed', null, lang);
    }
    setRetryVisible(needsRetry);
  }

  AGW.zcode = { render, setLogin, getLogin, describeCoupon, balanceInfo };

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = { render, setLogin, getLogin, describeCoupon, balanceInfo };
  }
})(typeof window !== 'undefined' ? window : globalThis);
