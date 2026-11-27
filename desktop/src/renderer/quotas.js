'use strict';

/**
 * Card 02: Antigravity limits (Gemini and Claude/GPT buckets).
 * Exposed as `window.AGW.quotas`.
 */
(function (root) {
  const AGW = (root.AGW = root.AGW || {});
  const format = AGW.format || (typeof require === 'function' ? require('./format.js') : null);
  const i18n = AGW.i18n || (typeof require === 'function' ? require('../shared/i18n.js') : null);
  const { clamp, formatCountdown } = format;

  /** Dictionary lookup with the RU default so Node tests stay byte-identical. */
  function tr(key, vars, lang) {
    return i18n && typeof i18n.t === 'function' ? i18n.t(key, vars, lang) : key;
  }

  const BUCKET_PRIORITY = ['gemini-5h', 'gemini-weekly', '3p-5h', '3p-weekly'];
  const MAX_ROWS = 4;

  function bucketLabel(bucket, lang = 'ru') {
    const id = String(bucket.id || '').toLowerCase();
    const group = id.startsWith('gemini-')
      ? tr('dashboard.quotas.groupGemini', null, lang)
      : id.startsWith('3p-')
        ? tr('dashboard.quotas.groupThirdParty', null, lang)
        : '';
    const kind =
      bucket.kind === '5h'
        ? tr('dashboard.quotas.kind5h', null, lang)
        : bucket.kind === 'weekly'
          ? tr('dashboard.quotas.kindWeekly', null, lang)
          : bucket.id || tr('dashboard.quotas.kindFallback', null, lang);
    return group ? tr('dashboard.quotas.bucketGrouped', { group, kind }, lang) : kind;
  }

  function bucketPriority(bucket) {
    const id = String((bucket && bucket.id) || '').toLowerCase();
    const index = BUCKET_PRIORITY.indexOf(id);
    return index === -1 ? BUCKET_PRIORITY.length : index;
  }

  /**
   * Pure per-bucket view model for the quota row.
   * An unknown fraction yields `null` percent and a neutral, zero-width fill.
   */
  function bucketView(bucket, lang = 'ru') {
    const safe = bucket || {};
    const id = String(safe.id || '');
    const isThirdParty = id.startsWith('3p-');
    const fraction =
      typeof safe.remaining_fraction === 'number' && Number.isFinite(safe.remaining_fraction)
        ? clamp(safe.remaining_fraction, 0, 1)
        : null;
    const percent = fraction === null ? null : Math.round(fraction * 100);

    let fillClass = 'bar-fill';
    if (fraction === null) fillClass += ' unknown';
    else if (fraction <= 0.001) fillClass += ' empty';
    else if (fraction <= 0.15) fillClass += ' low';
    else if (isThirdParty) fillClass += ' third';

    return {
      percent,
      percentText: percent === null ? '—' : tr('dashboard.quotas.percent', { pct: percent }, lang),
      fillClass,
      label: bucketLabel(safe, lang),
    };
  }

  /**
   * Pure view model for the plan-tier badge: hidden while the tier is unknown
   * instead of painting an empty "—" badge, with the error kept in the tooltip.
   */
  function planTierView(antigravity, lang = 'ru') {
    const planTier =
      antigravity && typeof antigravity.plan_tier === 'string' ? antigravity.plan_tier.trim() : '';
    const error =
      antigravity && typeof antigravity.error === 'string' ? antigravity.error.trim() : '';
    return {
      visible: Boolean(planTier),
      text: planTier,
      hasError: Boolean(error),
      title: error ? tr('dashboard.quotas.errorTooltip', { error }, lang) : '',
    };
  }

  /**
   * Error callout: one short human line plus an in-place retry. The technical
   * reason stays in the tooltip instead of pushing the card past the fold
   * (S1-046).
   */
  function renderError(errorBox, error, lang = 'ru') {
    if (!errorBox) return;
    errorBox.textContent = '';
    if (!error) {
      errorBox.removeAttribute('title');
      errorBox.classList.add('hidden');
      return;
    }

    const message = document.createElement('span');
    message.textContent = tr('dashboard.quotas.error', null, lang);
    const retry = document.createElement('button');
    retry.type = 'button';
    retry.className = 'quota-retry';
    retry.textContent = tr('dashboard.quotas.retry', null, lang);
    retry.addEventListener('click', () => {
      document.dispatchEvent(new CustomEvent('agw:refresh'));
    });
    errorBox.append(message, retry);
    errorBox.title = error;
    errorBox.classList.remove('hidden');
  }

  function render(status, options = {}) {
    const opts = options && typeof options === 'object' && !Array.isArray(options) ? options : {};
    const lang = opts.lang === 'en' ? 'en' : 'ru';
    const showGemini = opts.show_gemini !== false;
    const showThirdParty = opts.show_3p !== false;
    const showCountdown = opts.show_countdown !== false;
    const tier = document.getElementById('plan-tier');
    const list = document.getElementById('quota-list');
    const extra = document.getElementById('quota-extra');
    const errorBox = document.getElementById('quota-error');
    const antigravity = status && status.antigravity;
    const error = antigravity && typeof antigravity.error === 'string' ? antigravity.error : '';
    const tierView = planTierView(antigravity, lang);

    if (tier) {
      tier.classList.toggle('hidden', !tierView.visible);
      tier.textContent = tierView.text;
      if (tierView.title) tier.title = tierView.title;
      else tier.removeAttribute('title');
    }
    renderError(errorBox, error, lang);

    if (!list) return;

    const allBuckets =
      antigravity && Array.isArray(antigravity.buckets) ? antigravity.buckets : [];
    // Gemini buckets carry no `3p-` prefix; Claude/GPT buckets do.
    const buckets = allBuckets.filter((bucket) => {
      const id = String((bucket && bucket.id) || '').toLowerCase();
      return id.startsWith('3p-') ? showThirdParty : showGemini;
    });

    if (buckets.length === 0) {
      if (extra) {
        extra.textContent = '';
        extra.title = '';
        extra.classList.add('hidden');
      }
      list.className = 'quota-list empty';
      list.innerHTML = '';
      const message = document.createElement('div');
      message.className = 'empty-msg';
      message.textContent = tr('dashboard.quotas.empty', null, lang);
      list.appendChild(message);
      return;
    }

    const order = { '5h': 0, weekly: 1, other: 2 };
    const sorted = buckets.slice().sort((a, b) => {
      const byPriority = bucketPriority(a) - bucketPriority(b);
      if (byPriority !== 0) return byPriority;
      const aGemini = String(a.id || '').startsWith('gemini-') ? 0 : 1;
      const bGemini = String(b.id || '').startsWith('gemini-') ? 0 : 1;
      if (aGemini !== bGemini) return aGemini - bGemini;
      return (order[a.kind] ?? 2) - (order[b.kind] ?? 2);
    });

    const hidden = sorted.slice(MAX_ROWS);
    if (extra) {
      if (hidden.length > 0) {
        extra.textContent = `+${hidden.length}`;
        extra.title = tr(
          'dashboard.quotas.hiddenTitle',
          { labels: hidden.map((bucket) => bucketLabel(bucket, lang)).join(', ') },
          lang,
        );
        extra.classList.remove('hidden');
      } else {
        extra.textContent = '';
        extra.title = '';
        extra.classList.add('hidden');
      }
    }

    list.className = 'quota-list';
    list.innerHTML = '';

    sorted.slice(0, MAX_ROWS).forEach((bucket, index) => {
      const view = bucketView(bucket, lang);
      const id = String(bucket.id || '');
      const isThirdParty = id.startsWith('3p-');
      const isGemini = id.startsWith('gemini-');

      const row = document.createElement('div');
      row.className = 'quota-row';

      const top = document.createElement('div');
      top.className = 'quota-top';

      const label = document.createElement('span');
      label.className = 'quota-label';
      const dot = document.createElement('span');
      dot.className = `quota-group-dot ${isThirdParty ? 'third' : 'gem'}`;
      const name = document.createElement('span');
      name.textContent = view.label;
      label.append(dot, name);

      const reset = document.createElement('span');
      reset.className = 'quota-reset';
      const percentEl = document.createElement('b');
      percentEl.textContent = view.percentText;
      reset.appendChild(percentEl);
      const countdown = showCountdown ? formatCountdown(bucket.reset_time, null, lang) : '';
      if (countdown) reset.append(` · ${countdown}`);

      const indexEl = document.createElement('span');
      indexEl.className = 'quota-index';
      indexEl.textContent = String(index + 1).padStart(2, '0');
      top.append(indexEl, label, reset);

      const track = document.createElement('div');
      track.className = 'bar-track';
      const fill = document.createElement('div');
      fill.className = view.fillClass;
      fill.style.width = `${view.percent === null ? 0 : view.percent}%`;
      track.appendChild(fill);

      row.append(top, track);
      if (!isGemini && !isThirdParty) name.style.opacity = '0.8';
      list.appendChild(row);
    });
  }

  AGW.quotas = { render, bucketView, planTierView };

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = { render, bucketView, planTierView };
  }
})(typeof window !== 'undefined' ? window : globalThis);
