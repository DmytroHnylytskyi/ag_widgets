'use strict';

/**
 * Card 01: model context, health zones, and recent conversations.
 * Exposed as `window.AGW.context`.
 */
(function (root) {
  const AGW = (root.AGW = root.AGW || {});
  const format = AGW.format || (typeof require === 'function' ? require('./format.js') : null);
  const { clamp, computeZone, formatTokens, formatWindow, prettyModel, shortAge } = format;
  const { DEFAULT_RED_PCT, DEFAULT_YELLOW_PCT } = format;
  const CIRCUMFERENCE = 2 * Math.PI * 48; // r=48 in the SVG gauge.

  function validLevel(value) {
    return value === 'green' || value === 'yellow' || value === 'red' ? value : null;
  }

  function currentConversation(context, activeChat) {
    if (!context || !context.conversations) return null;
    const activeId = activeChat && activeChat.id;
    if (activeId) {
      // A freshly opened chat is not in context.json yet. Never fall back to
      // `context.last`, otherwise the live dot shows another chat's freshness.
      return context.conversations[activeId] || null;
    }
    const lastId = context.last;
    return lastId && context.conversations[lastId] ? context.conversations[lastId] : null;
  }

  /** Trimmed snippet text, or "" for anything that is not a string. */
  function formatSnippet(snippet) {
    return typeof snippet === 'string' ? snippet.trim() : '';
  }

  /** Model label for a recent chat; a missing model is "", never "undefined". */
  function recentModelLabel(item) {
    const raw = item && typeof item.model === 'string' ? item.model.trim() : '';
    if (!raw) return '';
    const label = typeof prettyModel === 'function' ? prettyModel(raw) : '';
    return typeof label === 'string' ? label.trim() : '';
  }

  /**
   * Pure view model for a recent-chat chip: two chats of the same model get
   * different names because the chat title comes first, and the tooltip keeps
   * the full title/workspace/model context.
   */
  function formatRecentChat(id, item, chatTitles) {
    const fromTitles =
      chatTitles && typeof chatTitles[id] === 'string' ? chatTitles[id].trim() : '';
    const fromItem = item && typeof item.title === 'string' ? item.title.trim() : '';
    const title = fromTitles || fromItem;
    const modelLabel = recentModelLabel(item);
    const workspace = item && typeof item.workspace === 'string' ? item.workspace.trim() : '';

    const label = title
      ? modelLabel && title.toLowerCase() !== modelLabel.toLowerCase()
        ? `${title} · ${modelLabel}`
        : title
      : workspace || modelLabel;

    const seen = new Set();
    const parts = [];
    for (const part of [title, workspace, modelLabel]) {
      if (!part) continue;
      const key = part.toLowerCase();
      if (seen.has(key)) continue;
      seen.add(key);
      parts.push(part);
    }

    return { label, tooltip: parts.join(' · ') };
  }

  function contextInfo(context, status, activeChat) {
    const summary = (status && status.context_summary) || {};
    const activeId = (activeChat && activeChat.id) || null;
    const conversations = (context && context.conversations) || {};
    let current = null;
    let selectedId = null;

    if (activeId && conversations[activeId]) {
      current = conversations[activeId];
      selectedId = activeId;
    } else if (!activeId && context && context.last && conversations[context.last]) {
      current = conversations[context.last];
      selectedId = context.last;
    } else if (!activeId && summary && typeof summary.tokens === 'number') {
      // context.json can be missing while status.json still has the last PC summary.
      current = {
        model: summary.model,
        tokens: summary.tokens,
        window: summary.window,
        pct: summary.pct,
        level: summary.level,
        yellow_pct: summary.yellow_pct,
        red_pct: summary.red_pct,
        warn_tokens: summary.warn_tokens,
        risk_tokens: summary.risk_tokens,
        workspace: '',
        snippet: '',
        updated_at: summary.updated_at,
      };
    }

    if (!current) {
      return {
        cur: null,
        summary,
        conversations,
        activeChat,
        activeId,
        selectedId: activeId || (context && context.last) || null,
        window: null,
        tokens: null,
        pct: null,
        level: 'unknown',
        yellowPct: DEFAULT_YELLOW_PCT,
        redPct: DEFAULT_RED_PCT,
        warnTokens: null,
        riskTokens: null,
        known: false,
      };
    }

    const window =
      typeof current.window === 'number' && current.window > 0
        ? current.window
        : typeof summary.window === 'number' && summary.window > 0
          ? summary.window
          : null;
    const tokens =
      typeof current.tokens === 'number' && Number.isFinite(current.tokens) ? current.tokens : null;

    const yellowPct =
      typeof current.yellow_pct === 'number'
        ? current.yellow_pct
        : typeof summary.yellow_pct === 'number'
          ? summary.yellow_pct
          : DEFAULT_YELLOW_PCT;
    const redPct =
      typeof current.red_pct === 'number'
        ? current.red_pct
        : typeof summary.red_pct === 'number'
          ? summary.red_pct
          : DEFAULT_RED_PCT;

    const zone = computeZone(tokens, window, yellowPct, redPct);
    const known = window !== null;

    let level = zone.level;
    if (level === 'unknown') {
      // Trust a wire level only when the window is known; never invent a
      // "green" zone for a model whose context size could not be resolved.
      const wireLevel = validLevel(current.level);
      if (wireLevel && known) level = wireLevel;
    }

    const pct =
      zone.pct !== null
        ? zone.pct
        : typeof current.pct === 'number' && Number.isFinite(current.pct)
          ? current.pct
          : null;
    const warnTokens =
      typeof current.warn_tokens === 'number' && Number.isFinite(current.warn_tokens)
        ? current.warn_tokens
        : window !== null
          ? Math.round((window * yellowPct) / 100)
          : null;
    const riskTokens =
      typeof current.risk_tokens === 'number' && Number.isFinite(current.risk_tokens)
        ? current.risk_tokens
        : window !== null
          ? Math.round((window * redPct) / 100)
          : null;

    return {
      cur: current,
      summary,
      conversations,
      activeChat,
      activeId,
      selectedId: selectedId || activeId || (context && context.last) || null,
      window,
      tokens,
      pct,
      level,
      yellowPct,
      redPct,
      warnTokens,
      riskTokens,
      known,
    };
  }

  /**
   * Paint the health zone through CSS classes and percentage variables only:
   * the colours for dark/light themes live in styles.css.
   */
  function renderZone(info) {
    const track = document.getElementById('zone-track');
    const marker = document.getElementById('zone-marker');
    const warnLabel = document.getElementById('zone-warn-label');
    const riskLabel = document.getElementById('zone-risk-label');

    if (track) {
      const state = info && info.known ? info.level : 'unknown';
      const className =
        state === 'green'
          ? 'level-green'
          : state === 'yellow'
            ? 'level-yellow'
            : state === 'red'
              ? 'level-red'
              : 'is-unknown';
      track.classList.remove('level-green', 'level-yellow', 'level-red', 'is-unknown');
      track.classList.add(className);

      if (info && info.known) {
        const warnStop = clamp(Math.round(info.yellowPct), 1, 98);
        const riskStop = clamp(Math.max(Math.round(info.redPct), warnStop + 1), 2, 100);
        track.style.setProperty('--zone-warn-stop', `${warnStop}%`);
        track.style.setProperty('--zone-risk-stop', `${riskStop}%`);
        track.title =
          `Порог ${Math.round(info.yellowPct)}% и ${Math.round(info.redPct)}% окна. ` +
          `После ~${Math.round(info.yellowPct)}% деградация на сложных задачах заметна, ` +
          `после ~${Math.round(info.redPct)}% надёжная часть окна обычно заканчивается ` +
          '(RULER, NoLiMa, Chroma Context Rot).';
      } else {
        track.title = 'Окно модели неизвестно: задайте model_windows в config.json';
      }
    }

    if (marker) marker.style.left = `${clamp(typeof info.pct === 'number' ? info.pct : 0, 0, 100)}%`;
    if (warnLabel) {
      warnLabel.textContent =
        info && info.known && info.warnTokens !== null
          ? `надёжно до ${formatTokens(info.warnTokens)}`
          : 'надёжно до —';
    }
    if (riskLabel) {
      riskLabel.textContent =
        info && info.known && info.riskTokens !== null
          ? `риск после ${formatTokens(info.riskTokens)}`
          : 'риск после —';
    }
  }

  /** Footer legend follows the backend thresholds, or shows a dash while the window is unknown. */
  function renderLegend(info) {
    const ok = document.getElementById('legend-ok');
    const warn = document.getElementById('legend-warn');
    const risk = document.getElementById('legend-risk');
    if (!ok && !warn && !risk) return;

    const known = Boolean(info && info.known);
    const yellow = known ? Math.round(info.yellowPct) : null;
    const red = known ? Math.round(info.redPct) : null;
    if (ok) ok.textContent = known ? `0–${yellow}%` : '—';
    if (warn) warn.textContent = known ? `${yellow}–${red}%` : '—';
    if (risk) risk.textContent = known ? `${red}%+` : '—';
  }

  function renderEmpty(info) {
    const hasChat = Boolean(info.activeChat && info.activeChat.id);
    const chatTitle = info.activeChat && info.activeChat.title ? info.activeChat.title : '';
    document.getElementById('ctx-tokens').textContent = '—';
    document.getElementById('ctx-token-sub').textContent = 'токенов';
    document.getElementById('ctx-model').textContent = hasChat ? 'Ожидание ответа' : 'Ожидание диалога';
    document.getElementById('ctx-workspace').textContent = hasChat ? 'Выбранный чат' : '—';
    const snippetEl = document.getElementById('ctx-snippet');
    snippetEl.textContent = hasChat
      ? 'Ответьте модели, чтобы увидеть контекст'
      : 'Отправь запрос в Antigravity';
    snippetEl.classList.remove('hidden');

    const alert = document.getElementById('ctx-alert');
    alert.classList.add('hidden');

    const pill = document.getElementById('ctx-level-pill');
    pill.className = 'status-pill level-idle';
    pill.textContent = '—';

    const ring = document.getElementById('ring-progress');
    ring.style.strokeDashoffset = CIRCUMFERENCE;
    ring.style.opacity = '0';
    ring.style.stroke = 'var(--glow)';
    document.getElementById('ring-satellite').style.opacity = '0';

    const material = document.getElementById('ctx-material');
    material.textContent = hasChat ? (chatTitle || 'НОВЫЙ ЧАТ').toUpperCase() : 'НЕТ СЕССИИ';
    material.title = chatTitle;

    const recent = document.getElementById('ctx-recent');
    recent.innerHTML = '';
    recent.classList.add('hidden');

    renderZone(info);
    renderLegend(info);
  }

  function renderRecent(info, chatTitles) {
    const container = document.getElementById('ctx-recent');
    const others = Object.entries(info.conversations || {})
      .filter(([id]) => id !== info.selectedId)
      .sort((a, b) => String(b[1].updated_at || '').localeCompare(String(a[1].updated_at || '')))
      .slice(0, 2);

    container.innerHTML = '';
    if (others.length === 0) {
      container.classList.add('hidden');
      return;
    }

    container.classList.remove('hidden');
    for (const [id, item] of others) {
      const chip = document.createElement('div');
      chip.className = 'recent-chip';
      const knownWindow = typeof item.window === 'number' && item.window > 0;
      const zone = computeZone(item.tokens, item.window);
      const itemLevel =
        (knownWindow && validLevel(item.level)) || (zone.pct !== null ? zone.level : 'unknown');
      const dot = document.createElement('span');
      dot.className = `dot-sm dot-${itemLevel}`;
      const name = document.createElement('span');
      name.className = 'recent-name';
      const recent = formatRecentChat(id, item, chatTitles);
      name.textContent = recent.label;
      if (recent.tooltip) chip.title = recent.tooltip;
      const tokens = document.createElement('span');
      tokens.className = 'recent-tok';
      tokens.textContent = formatTokens(item.tokens);
      chip.append(dot, name, tokens);
      container.appendChild(chip);
    }
  }

  function render(context, status, activeChat, chatTitles = {}) {
    const info = contextInfo(context, status, activeChat);
    if (!info.cur) {
      renderEmpty(info);
      return;
    }

    const { cur, window, tokens, pct, level } = info;
    const material = document.getElementById('ctx-material');
    document.getElementById('ctx-tokens').textContent = formatTokens(tokens);
    document.getElementById('ctx-token-sub').textContent =
      window && typeof pct === 'number' ? `${Math.round(pct)}% из ${formatWindow(window)}` : 'окно не задано';

    const ring = document.getElementById('ring-progress');
    const satellite = document.getElementById('ring-satellite');
    ring.style.opacity = '1';
    satellite.style.opacity = '1';

    const ratio =
      typeof pct === 'number'
        ? clamp(pct / 100, 0.015, 1)
        : window && tokens
          ? clamp(tokens / window, 0.015, 1)
          : 0.015;
    ring.style.strokeDashoffset = (CIRCUMFERENCE * (1 - ratio)).toFixed(2);

    const levelColor =
      level === 'red' ? 'var(--risk)' : level === 'yellow' ? 'var(--warn)' : level === 'green' ? 'var(--ok)' : 'var(--glow)';
    ring.style.stroke = levelColor;
    satellite.style.fill = levelColor;
    const angle = -Math.PI / 2 + Math.PI * 2 * ratio;
    satellite.setAttribute('cx', (70 + 48 * Math.cos(angle)).toFixed(2));
    satellite.setAttribute('cy', (70 + 48 * Math.sin(angle)).toFixed(2));

    const chatTitle = info.activeChat && info.activeChat.title;
    material.textContent = chatTitle
      ? chatTitle.toUpperCase()
      : window
        ? `${prettyModel(cur.model).toUpperCase()} · ${formatWindow(window)}`
        : prettyModel(cur.model).toUpperCase();
    material.title = chatTitle || '';

    const model = document.getElementById('ctx-model');
    model.textContent = prettyModel(cur.model);
    model.title = cur.model || '';
    const age = shortAge(cur.updated_at);
    document.getElementById('ctx-workspace').textContent =
      `${cur.workspace ? `Проект: ${cur.workspace}` : 'Без проекта'}${age ? ` · ${age}` : ''}`;
    const snippetEl = document.getElementById('ctx-snippet');
    const snippet = formatSnippet(cur.snippet);
    snippetEl.textContent = snippet;
    snippetEl.classList.toggle('hidden', !snippet);

    const pill = document.getElementById('ctx-level-pill');
    pill.className = `status-pill level-${level === 'unknown' ? 'idle' : level}`;
    pill.textContent =
      level === 'red' ? 'РИСК' : level === 'yellow' ? 'ВНИМАНИЕ' : level === 'green' ? 'OK' : '—';

    const alert = document.getElementById('ctx-alert');
    if (level === 'red') {
      alert.className = 'ctx-alert';
      alert.textContent = 'Пора /compact · зона деградации';
      alert.classList.remove('hidden');
    } else if (level === 'yellow') {
      alert.className = 'ctx-alert warn';
      alert.textContent = 'Контекст растёт · возможна деградация';
      alert.classList.remove('hidden');
    } else {
      alert.classList.add('hidden');
    }

    renderZone(info);
    renderLegend(info);
    renderRecent(info, chatTitles);
  }

  AGW.context = {
    currentConversation,
    contextInfo,
    render,
    formatSnippet,
    formatRecentChat,
  };

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = {
      currentConversation,
      contextInfo,
      render,
      formatSnippet,
      formatRecentChat,
    };
  }
})(typeof window !== 'undefined' ? window : globalThis);
