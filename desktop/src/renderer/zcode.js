'use strict';

/**
 * Card 03: ZCode coupon, balance, and OAuth sign-in state.
 * Exposed as `window.AGW.zcode`.
 */
(function (root) {
  const AGW = (root.AGW = root.AGW || {});
  const format = AGW.format || (typeof require === 'function' ? require('./format.js') : null);
  const { clamp, formatEndDate, formatUnits } = format;

  const ERROR_REASONS = {
    rate_limited: 'лимит запросов 429',
    server_error: 'ошибка сервера',
    api_changed: 'формат API изменился',
    network: 'нет связи',
    auth_expired: 'токен истёк',
    parse_failed: 'не удалось разобрать ответ',
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

  function errorReason(zcode) {
    const kind = zcode && typeof zcode.error_kind === 'string' ? zcode.error_kind : '';
    if (ERROR_REASONS[kind]) return ERROR_REASONS[kind];
    if (zcode && zcode.network_error) return ERROR_REASONS.network;
    return 'неизвестная ошибка';
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
  function connectionLabel(zcode, classic) {
    if (!zcode) return 'Нет данных';
    if (zcode.method === 'disabled') return 'Отключён';
    if (zcode.method !== 'api') return 'Нет данных';
    if (classic) return 'Подключён';
    if (zcode.auth_ok === false) return 'Токен истёк';
    if (zcode.network_error || zcode.error_kind === 'network') return 'Нет сети';
    if (zcode.error_kind) return 'Не проверен';
    return 'Подключён';
  }

  /**
   * Tri-state coupon view per the PR #1 contract: "absent" is reported only
   * when the backend confirmed it, everything uncertain stays "unknown".
   */
  function describeCoupon(zcode) {
    if (!zcode) {
      return { state: 'unknown', text: 'Не удалось проверить ваучер: неизвестная ошибка', tone: 'warn' };
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

    if (state === 'visible') return { state, text: 'Ваучер доступен', tone: 'ok' };
    if (state === 'absent') return { state, text: 'Нет активных ваучеров', tone: 'idle' };
    return { state: 'unknown', text: `Не удалось проверить ваучер: ${errorReason(zcode)}`, tone: 'warn' };
  }

  /**
   * Balance view. `0 / 500M` must stay visible as an empty progress bar,
   * while a missing total only hides the ratio (percent stays `null`).
   */
  function balanceInfo(zcode) {
    const remaining = zcode ? zcode.remaining_units : null;
    const total = zcode ? zcode.total_units : null;
    const hasTotal = Number.isFinite(total) && total > 0;
    const hasBalance = Number.isFinite(remaining) && (remaining > 0 || hasTotal);
    const percent = hasBalance && hasTotal ? clamp(Math.round((remaining / total) * 100), 0, 100) : null;

    let text = '';
    if (hasBalance) {
      text = `Остаток: ${formatUnits(remaining)}${hasTotal ? ` / ${formatUnits(total)}` : ''}`;
    }
    return { hasBalance, hasTotal, percent, text };
  }

  function render(status, options = {}) {
    const opts = options && typeof options === 'object' && !Array.isArray(options) ? options : {};
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
          ? `Проверить ZCode ещё раз: ${errorReason(zcode)}`
          : 'Проверить ZCode ещё раз';
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
          ? 'Отменить вход'
          : loginState === 'error'
            ? 'Повторить вход'
            : 'Войти в Z.ai';
    }

    if (bar) bar.classList.add('hidden');
    if (barFill) barFill.style.width = '0%';
    if (subtitle) subtitle.title = '';
    if (title) {
      title.textContent = planName ? `ZCode · ${planName}` : 'ZCode';
    }
    if (material) {
      // Modern themes hide `#zcode-title` (the card heading already says
      // "ZCode"), so the plan name has to ride along the status line or it is
      // lost entirely (D-06).
      const statusWord = connectionLabel(zcode, classic);
      material.textContent = !classic && planName ? `${statusWord} · ${planName}` : statusWord;
    }

    if (loginState === 'pending') {
      if (subtitle) subtitle.textContent = 'Открыл браузер · завершите вход в Z.ai…';
      if (chip) {
        chip.className = 'zcode-chip chip-warn';
        chip.textContent = 'Ожидание';
      }
      if (material) material.textContent = 'Вход';
      setRetryVisible(false);
      return;
    }

    if (loginState === 'error') {
      if (subtitle) subtitle.textContent = `Не удалось войти: ${loginMessage}`;
      if (chip) {
        chip.className = 'zcode-chip chip-error';
        chip.textContent = 'Ошибка';
      }
      if (material) material.textContent = 'Вход';
      setRetryVisible(false);
      return;
    }

    if (loginState === 'success') {
      if (subtitle) subtitle.textContent = loginMessage || 'Вход выполнен · обновляю баланс…';
      if (chip) {
        chip.className = 'zcode-chip chip-available';
        chip.textContent = 'Вход выполнен';
      }
      if (material) material.textContent = 'Подключён';
      setRetryVisible(false);
      return;
    }

    if (!zcode) {
      if (subtitle) subtitle.textContent = 'Ожидание проверки';
      if (chip) {
        chip.className = 'zcode-chip chip-idle';
        chip.textContent = 'Нет данных';
      }
      setRetryVisible(false);
      return;
    }

    if (zcode.method === 'disabled') {
      if (subtitle) subtitle.textContent = 'Не подключён · войдите в Z.ai';
      if (chip) {
        chip.className = 'zcode-chip chip-idle';
        chip.textContent = 'Не подключён';
      }
      setRetryVisible(false);
      return;
    }

    if (zcode.method === 'api' && zcode.auth_ok === false) {
      if (subtitle) subtitle.textContent = 'Токен истёк · войдите снова';
      if (chip) {
        chip.className = 'zcode-chip chip-warn';
        chip.textContent = 'Нужен вход';
      }
      setRetryVisible(false);
      return;
    }

    const coupon = describeCoupon(zcode);
    const balance = balanceInfo(zcode);
    const endsDate = formatEndDate(zcode.ends_at);
    const endsText = endsDate ? ` · до ${endsDate}` : '';

    if (bar && barFill && showBalanceBar && balance.percent !== null) {
      bar.classList.remove('hidden');
      barFill.style.width = `${balance.percent}%`;
    }

    let subtitleText;
    if (coupon.state === 'visible') {
      subtitleText = balance.text ? `${balance.text}${endsText}` : 'Ваучер 300–500M · заберите в ZCode';
    } else if (coupon.state === 'absent') {
      subtitleText = balance.text ? `${balance.text}${endsText}` : coupon.text;
    } else {
      // Short human status in the card; the technical reason stays in the
      // tooltip instead of pushing the balance out of two lines (S1-046).
      subtitleText = balance.text ? `Ваучер не проверен · ${balance.text}` : 'Ваучер не проверен';
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
          ? 'Ваучер доступен'
          : coupon.state === 'absent'
            ? 'Нет ваучера'
            : 'Проверка не удалась';
    }
    setRetryVisible(needsRetry);
  }

  AGW.zcode = { render, setLogin, getLogin, describeCoupon, balanceInfo };

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = { render, setLogin, getLogin, describeCoupon, balanceInfo };
  }
})(typeof window !== 'undefined' ? window : globalThis);
