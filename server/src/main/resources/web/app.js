/*
 * Web UI of the search engine. No libraries, no build step.
 *
 * Security rule: everything that comes from the server (book names, snippets, error messages,
 * expansion terms) is put into the page only with textContent or createTextNode, never by building
 * HTML strings. Snippets are texts of books, so they may contain markup such as a script tag. A
 * test (WebUiTest) fails if this file starts to parse HTML.
 */
(function () {
  'use strict';

  var SEARCH_TIMEOUT_MS = 120000; // the whole search, from the click to the "done" message
  var HEALTH_INTERVAL_MS = 10000;
  var INDEX_TIMEOUT_MS = 30 * 60 * 1000;
  var MAX_QUERY_LENGTH = 1000;

  // Very common English words are not highlighted: the server ignores them in the query too.
  var STOP_WORDS = new Set(['the', 'and', 'of', 'to', 'in', 'is', 'it', 'for', 'on', 'with', 'as', 'by',
    'at', 'an', 'or', 'be', 'are', 'was', 'were', 'from', 'that', 'this', 'but', 'not', 'his', 'her']);

  function $(id) { return document.getElementById(id); }

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) { node.className = className; }
    if (text !== undefined && text !== null) { node.textContent = text; }
    return node;
  }

  // ---------------------------------------------------------------- formatting

  function formatDuration(ms) {
    if (!isFinite(ms) || ms < 0) { return '—'; }
    if (ms < 1000) { return Math.round(ms) + ' мс'; }
    return (ms / 1000).toFixed(ms < 10000 ? 2 : 1).replace('.', ',') + ' с';
  }

  function plural(n, one, few, many) {
    var m10 = n % 10, m100 = n % 100;
    if (m10 === 1 && m100 !== 11) { return one; }
    if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) { return few; }
    return many;
  }

  function formatNumber(n) {
    return Number(n).toLocaleString('uk-UA');
  }

  /** "gutenberg/Moby_Dick.txt#3" -> title "Moby Dick", part 3, folder "gutenberg". */
  function describeDoc(docId) {
    var path = String(docId), part = null;
    var m = /^(.*)#(\d+)$/.exec(path);
    if (m) { path = m[1]; part = Number(m[2]); }
    var segments = path.split('/').filter(function (s) { return s.length > 0; });
    var file = segments.length ? segments.pop() : path;
    var title = file.replace(/\.txt$/i, '').replace(/_+/g, ' ').replace(/\s+/g, ' ').trim();
    var numbered = /^(?:pg)?(\d+)$/i.exec(title);
    if (numbered) { title = 'Книга № ' + numbered[1]; }
    if (!title) { title = String(docId); }
    return { title: title, part: part, folder: segments.join('/') };
  }

  // ---------------------------------------------------------------- highlighting

  /** Splits a text into words (letters and digits, any language). */
  function words(text) {
    return (String(text).toLowerCase().match(/[\p{L}\p{N}]+/gu) || []);
  }

  function escapeRegExp(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }

  /**
   * Puts the snippet into the element as text, wrapping the query words in <mark>.
   * classOf maps a lower-case word to "hl-orig" or "hl-exp".
   */
  function renderSnippet(target, text, classOf) {
    var terms = Object.keys(classOf).sort(function (a, b) { return b.length - a.length; });
    if (terms.length === 0) { target.appendChild(document.createTextNode(text)); return; }
    var pattern = new RegExp('(?<![\\p{L}\\p{N}])(?:' + terms.map(escapeRegExp).join('|') + ')(?![\\p{L}\\p{N}])', 'giu');
    var last = 0, match;
    while ((match = pattern.exec(text)) !== null) {
      if (match.index > last) { target.appendChild(document.createTextNode(text.slice(last, match.index))); }
      var mark = el('mark', classOf[match[0].toLowerCase()] || 'hl-orig', match[0]);
      target.appendChild(mark);
      last = match.index + match[0].length;
      if (match[0].length === 0) { pattern.lastIndex++; }
    }
    if (last < text.length) { target.appendChild(document.createTextNode(text.slice(last))); }
  }

  // ---------------------------------------------------------------- pipeline view

  var STEP_IDS = { expand: 'step-expand', search: 'step-search', done: 'step-done' };
  var stepNodes = {};
  Object.keys(STEP_IDS).forEach(function (key) {
    var li = $(STEP_IDS[key]);
    stepNodes[key] = {
      li: li,
      status: li.querySelector('.step-status'),
      timer: li.querySelector('.step-timer'),
      detail: li.querySelector('.step-detail')
    };
  });

  function setStep(key, state, statusText) {
    var step = stepNodes[key];
    step.li.dataset.state = state;
    step.li.setAttribute('aria-busy', state === 'running' ? 'true' : 'false');
    step.status.textContent = statusText;
    if (state !== 'running') { step.timer.textContent = ''; }
  }

  function setDetail(key, node) {
    var detail = stepNodes[key].detail;
    detail.textContent = '';
    if (node) { detail.appendChild(node); }
  }

  function resetPipeline() {
    Object.keys(stepNodes).forEach(function (key) {
      setStep(key, 'pending', 'Очікує');
      setDetail(key, null);
    });
  }

  // ---------------------------------------------------------------- search run

  var run = null; // the search that is shown now; null when nothing is running

  var form = $('search-form');
  var input = $('q');
  var hint = $('q-hint');
  var banner = $('banner');
  var bannerText = $('banner-text');
  var retryBtn = $('retry-btn');
  var resultsList = $('results');
  var resultsEmpty = $('results-empty');
  var resultsSummary = $('results-summary');
  var legend = $('legend');
  var lastQuery = '';

  function showHint(text, isError) {
    hint.textContent = text;
    hint.className = isError ? 'hint error' : 'hint';
    input.setAttribute('aria-invalid', isError ? 'true' : 'false');
  }

  function showBanner(text, canRetry) {
    bannerText.textContent = text;
    retryBtn.hidden = !canRetry;
    banner.hidden = false;
  }

  function clearBanner() {
    banner.hidden = true;
    bannerText.textContent = '';
    retryBtn.hidden = true;
  }

  function clearResults(message) {
    resultsList.textContent = '';
    resultsSummary.textContent = '';
    legend.hidden = true;
    resultsEmpty.hidden = !message;
    resultsEmpty.textContent = message || '';
  }

  /** Stops the timers and the socket of a run. Its messages are ignored from now on. */
  function dispose(r) {
    if (!r) { return; }
    r.active = false;
    clearTimeout(r.timeoutId);
    clearInterval(r.tickId);
    if (r.socket) {
      r.socket.onopen = r.socket.onmessage = r.socket.onerror = r.socket.onclose = null;
      try { r.socket.close(); } catch (e) { /* already closed */ }
    }
  }

  function currentStepKey(r) {
    return r.expandDone ? (r.searchDone ? 'done' : 'search') : 'expand';
  }

  function tick(r) {
    var key = currentStepKey(r);
    stepNodes[key].timer.textContent = '· ' + formatDuration(performance.now() - r.startedAt);
  }

  /** The run cannot go on: mark the current step as failed and offer to retry. */
  function failRun(r, message, canRetry) {
    if (!r.active) { return; }
    if (r.failed) { // the server already reported an error: just stop waiting for "done"
      dispose(r);
      if (run === r) { run = null; }
      return;
    }
    var key = currentStepKey(r);
    setStep(key, 'error', 'Помилка');
    var detail = el('p', 'step-error', message);
    setDetail(key, detail);
    markRestSkipped(key);
    showBanner(message, canRetry);
    resultsSummary.textContent = 'Пошук не виконано.';
    dispose(r);
    if (run === r) { run = null; }
  }

  function markRestSkipped(failedKey) {
    var order = ['expand', 'search', 'done'];
    for (var i = order.indexOf(failedKey) + 1; i < order.length; i++) {
      setStep(order[i], 'skipped', 'Не виконано');
    }
  }

  function startSearch(query) {
    var text = query.trim();
    if (!text) {
      showHint('Введіть запит: одне або кілька слів.', true);
      input.focus();
      return;
    }
    showHint('', false);
    lastQuery = text;
    dispose(run);
    clearBanner();
    clearResults('');
    resetPipeline();

    var r = {
      active: true, query: text, startedAt: performance.now(), socket: null, opened: false,
      expandDone: false, searchDone: false, finished: false, failed: false,
      terms: { orig: Object.create(null), exp: Object.create(null) },
      timeoutId: 0, tickId: 0
    };
    run = r;
    setStep('expand', 'running', 'Виконується…');
    r.tickId = setInterval(function () { tick(r); }, 100);
    r.timeoutId = setTimeout(function () {
      failRun(r, 'Час очікування відповіді (' + SEARCH_TIMEOUT_MS / 1000 + ' с) вичерпано.', true);
    }, SEARCH_TIMEOUT_MS);

    var socket;
    try {
      var scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
      socket = new WebSocket(scheme + '//' + location.host + '/ws/search');
    } catch (e) {
      failRun(r, 'Не вдалося створити з\'єднання із сервером.', true);
      return;
    }
    r.socket = socket;
    socket.onopen = function () {
      r.opened = true;
      socket.send(JSON.stringify({ query: text }));
    };
    socket.onmessage = function (event) { onMessage(r, event.data); };
    socket.onerror = function () { /* onclose follows and reports the problem */ };
    socket.onclose = function () {
      if (!r.active || r.finished) { return; }
      failRun(r, r.opened
        ? 'З\'єднання із сервером перервано до завершення пошуку.'
        : 'Не вдалося підключитися до сервера. Перевірте, що він запущений.', true);
    };
  }

  function onMessage(r, raw) {
    if (!r.active) { return; }
    var message;
    try { message = JSON.parse(raw); } catch (e) { return; }
    if (!message || typeof message.stage !== 'string') { return; }
    var elapsed = performance.now() - r.startedAt;
    var data = message.data || {};

    if (message.stage === 'query_expansion') {
      onExpansion(r, data, elapsed);
    } else if (message.stage === 'keyword_search') {
      onKeywordSearch(r, data, elapsed);
    } else if (message.stage === 'error') {
      onServerError(r, data);
    } else if (message.stage === 'done') {
      onDone(r, elapsed);
    }
  }

  function onExpansion(r, data, elapsed) {
    var original = typeof data.original === 'string' ? data.original : r.query;
    var expanded = Array.isArray(data.expanded_terms) ? data.expanded_terms.filter(function (t) { return typeof t === 'string'; }) : [];
    words(original).forEach(function (w) { if (!STOP_WORDS.has(w)) { r.terms.orig[w] = 'hl-orig'; } });
    expanded = expanded.filter(function (t) { return t.trim().length > 0; });
    expanded.forEach(function (t) {
      var w = t.trim().toLowerCase();
      if (!r.terms.orig[w]) { r.terms.exp[w] = 'hl-exp'; }
    });

    var box = el('div');
    var origList = el('ul', 'chips');
    origList.setAttribute('aria-label', 'Оригінальний запит');
    origList.appendChild(el('li', 'chip chip-orig', original));
    box.appendChild(el('p', 'chip-label', 'Запит'));
    box.appendChild(origList);
    if (expanded.length > 0) {
      var expList = el('ul', 'chips');
      expList.setAttribute('aria-label', 'Розширені терміни');
      expanded.forEach(function (t) { expList.appendChild(el('li', 'chip chip-exp', t)); });
      box.appendChild(el('p', 'chip-label', 'Розширення (' + expanded.length + ')'));
      box.appendChild(expList);
    } else {
      box.appendChild(el('p', 'step-note', 'Розширення недоступне, пошук за оригінальним запитом.'));
    }
    r.expandDone = true;
    setStep('expand', 'done', 'Завершено · ' + formatDuration(elapsed));
    setDetail('expand', box);
    setStep('search', 'running', 'Виконується…');
  }

  function onKeywordSearch(r, data, elapsed) {
    var results = Array.isArray(data.results) ? data.results : [];
    var timeMs = typeof data.time_ms === 'number' ? data.time_ms : NaN;
    r.searchDone = true;
    setStep('search', 'done', 'Завершено · ' + formatDuration(elapsed));
    var info = 'Знайдено: ' + results.length + ' ' + plural(results.length, 'результат', 'результати', 'результатів')
      + (isFinite(timeMs) ? ' · пошук: ' + timeMs + ' мс' : '');
    setDetail('search', el('p', null, info));
    setStep('done', 'running', 'Очікуємо завершення…');
    renderResults(r, results, timeMs);
  }

  function onServerError(r, data) {
    var text = typeof data.message === 'string' && data.message ? data.message : 'Невідома помилка сервера.';
    var key = currentStepKey(r);
    r.failed = true;
    setStep(key, 'error', 'Помилка');
    setDetail(key, el('p', 'step-error', text));
    markRestSkipped(key);
    showBanner('Сервер повідомив про помилку: ' + text, false);
    resultsSummary.textContent = 'Пошук не виконано.';
  }

  function onDone(r, elapsed) {
    r.finished = true;
    if (!r.failed) {
      setStep('done', 'done', 'Завершено · ' + formatDuration(elapsed));
      setDetail('done', el('p', null, 'Загальний час: ' + formatDuration(elapsed)));
    }
    var finished = r;
    dispose(finished);
    if (run === finished) { run = null; }
  }

  // ---------------------------------------------------------------- results view

  function renderResults(r, results, timeMs) {
    resultsList.textContent = '';
    if (results.length === 0) {
      legend.hidden = true;
      resultsSummary.textContent = 'Нічого не знайдено.';
      var empty = $('stat-docs').textContent === '0'
        ? 'Індекс порожній. Спершу побудуйте його на панелі «Система».'
        : 'За цим запитом нічого не знайдено. Спробуйте інші слова.';
      resultsEmpty.textContent = empty;
      resultsEmpty.hidden = false;
      return;
    }
    resultsEmpty.hidden = true;
    resultsEmpty.textContent = '';
    legend.hidden = false;
    resultsSummary.textContent = 'Знайдено ' + results.length + ' ' + plural(results.length, 'результат', 'результати', 'результатів')
      + (isFinite(timeMs) ? ' за ' + timeMs + ' мс' : '');

    var classOf = Object.assign(Object.create(null), r.terms.exp, r.terms.orig); // a word typed by the user wins
    results.forEach(function (item, index) {
      resultsList.appendChild(buildResult(item, index + 1, classOf));
    });
  }

  function buildResult(item, rank, classOf) {
    var doc = describeDoc(item.doc_id);
    var score = typeof item.score === 'number' && isFinite(item.score) ? Math.min(1, Math.max(0, item.score)) : 0;

    var li = el('li', 'result');
    li.appendChild(el('span', 'rank', String(rank)));

    var head = el('div');
    var title = el('h3', 'book');
    title.appendChild(document.createTextNode(doc.title));
    if (doc.part !== null) { title.appendChild(el('span', 'part', 'Частина ' + doc.part)); }
    head.appendChild(title);
    var idLine = el('p', 'docid', String(item.doc_id));
    idLine.title = String(item.doc_id);
    head.appendChild(idLine);
    li.appendChild(head);

    var scoreBox = el('div', 'score');
    scoreBox.setAttribute('role', 'img');
    scoreBox.setAttribute('aria-label', 'Релевантність ' + score.toFixed(3).replace('.', ',') + ' з 1');
    var track = el('span', 'score-track');
    var fill = el('span', 'score-fill');
    fill.style.width = (score * 100).toFixed(1) + '%'; // CSSOM, allowed by the Content-Security-Policy
    track.appendChild(fill);
    scoreBox.appendChild(track);
    scoreBox.appendChild(el('span', 'score-num', score.toFixed(3).replace('.', ',')));
    li.appendChild(scoreBox);

    var snippet = el('p', 'snippet');
    var text = typeof item.snippet === 'string' ? item.snippet : '';
    if (text) {
      renderSnippet(snippet, text, classOf);
    } else {
      snippet.className = 'snippet none';
      snippet.textContent = 'Фрагмент недоступний.';
    }
    li.appendChild(snippet);
    return li;
  }

  // ---------------------------------------------------------------- health and stats

  var healthDot = $('health-dot');
  var healthText = $('health-text');

  function fetchJson(url, options, timeoutMs) {
    var controller = new AbortController();
    var timer = setTimeout(function () { controller.abort(); }, timeoutMs);
    var init = Object.assign({}, options || {}, { signal: controller.signal });
    return fetch(url, init).then(function (response) {
      clearTimeout(timer);
      return response.json().catch(function () { return null; }).then(function (body) {
        return { ok: response.ok, status: response.status, body: body };
      });
    }, function (error) {
      clearTimeout(timer);
      throw error;
    });
  }

  function setHealth(state, text) {
    healthDot.dataset.state = state;
    healthText.textContent = text;
  }

  function refreshHealth() {
    return fetchJson('/api/health', null, 5000).then(function (res) {
      if (res.ok && res.body && res.body.status === 'ok') {
        if (res.body.ollama) { setHealth('ok', 'Ollama: доступна'); }
        else { setHealth('warn', 'Ollama: недоступна, пошук без розширення'); }
      } else {
        setHealth('down', 'Сервер відповів помилкою (HTTP ' + res.status + ')');
      }
    }, function () {
      setHealth('down', 'Сервер недоступний');
    });
  }

  function refreshStats() {
    return fetchJson('/api/stats', null, 5000).then(function (res) {
      if (!res.ok || !res.body) { throw new Error('stats'); }
      var docs = Number(res.body.documents) || 0;
      $('stat-docs').textContent = formatNumber(docs);
      $('stat-time').textContent = docs > 0 ? formatDuration(Number(res.body.index_build_time_ms)) : '—';
      $('stat-threads').textContent = docs > 0 ? String(res.body.threads_used) : '—';
      $('stats-note').textContent = docs > 0 ? '' : 'Індекс порожній. Побудуйте його кнопкою нижче.';
    }, function () {
      $('stat-docs').textContent = '—';
      $('stat-time').textContent = '—';
      $('stat-threads').textContent = '—';
      $('stats-note').textContent = 'Статистика недоступна.';
    });
  }

  function poll() {
    if (document.hidden) { return; }
    refreshHealth();
    refreshStats();
  }

  // ---------------------------------------------------------------- index builder

  var indexForm = $('index-form');
  var indexBtn = $('index-btn');
  var threadsInput = $('threads');
  var indexProgress = $('index-progress');
  var indexProgressText = $('index-progress-text');
  var indexResult = $('index-result');
  var indexing = false;

  function showIndexResult(kind, text) {
    indexResult.className = 'index-result ' + kind;
    indexResult.textContent = text;
  }

  function buildIndex() {
    if (indexing) { return; }
    var threads = Number(threadsInput.value);
    if (!Number.isInteger(threads) || threads < 1 || threads > 1024) {
      showIndexResult('error', 'Кількість потоків має бути цілим числом від 1 до 1024.');
      threadsInput.focus();
      return;
    }
    indexing = true;
    indexBtn.disabled = true;
    threadsInput.disabled = true;
    showIndexResult('', '');
    indexProgress.hidden = false;
    var started = performance.now();
    indexProgressText.textContent = 'Індексація… 0 с';
    var tickId = setInterval(function () {
      indexProgressText.textContent = 'Індексація… ' + Math.round((performance.now() - started) / 1000) + ' с';
    }, 1000);

    fetchJson('/api/index', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ threads: threads })
    }, INDEX_TIMEOUT_MS).then(function (res) {
      var body = res.body || {};
      if (res.ok) {
        showIndexResult('ok', 'Готово: ' + formatNumber(body.documents_indexed) + ' '
          + plural(Number(body.documents_indexed), 'документ', 'документи', 'документів')
          + ' за ' + formatDuration(Number(body.time_ms)) + ' (потоків: ' + threads + ').');
      } else if (res.status === 409) {
        showIndexResult('warn', 'Індексація вже виконується (її запустили в іншому місці). Зачекайте і спробуйте знову.');
      } else {
        var reason = typeof body.message === 'string' && body.message ? body.message : 'без пояснення';
        showIndexResult('error', 'Помилка сервера (HTTP ' + res.status + '): ' + reason);
      }
    }, function (error) {
      if (error && error.name === 'AbortError') {
        showIndexResult('error', 'Сервер не відповів за ' + INDEX_TIMEOUT_MS / 60000 + ' хв. Індексація могла тривати далі, перевірте статистику.');
      } else {
        showIndexResult('warn', 'З\'єднання із сервером перервано. Якщо індексація вже йшла, вона могла завершитись на сервері: статистика оновиться сама.');
      }
    }).then(function () {
      clearInterval(tickId);
      indexProgress.hidden = true;
      indexing = false;
      indexBtn.disabled = false;
      threadsInput.disabled = false;
      refreshStats();
      refreshHealth();
    });
  }

  // ---------------------------------------------------------------- start

  form.addEventListener('submit', function (event) {
    event.preventDefault();
    startSearch(input.value);
  });
  input.addEventListener('input', function () {
    if (hint.classList.contains('error')) { showHint('', false); }
  });
  retryBtn.addEventListener('click', function () { startSearch(lastQuery); });
  indexForm.addEventListener('submit', function (event) {
    event.preventDefault();
    buildIndex();
  });

  threadsInput.value = String(Math.min(1024, Math.max(1, navigator.hardwareConcurrency || 4)));
  resetPipeline();
  poll();
  setInterval(poll, HEALTH_INTERVAL_MS);
  document.addEventListener('visibilitychange', function () { if (!document.hidden) { poll(); } });

  var initial = new URLSearchParams(location.search).get('q');
  if (initial) {
    input.value = initial.slice(0, MAX_QUERY_LENGTH);
    startSearch(initial);
  } else {
    input.focus();
  }
})();
