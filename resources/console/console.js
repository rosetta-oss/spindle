'use strict';
(function () {
  var TOKEN_KEY = 'spindle.token';
  var TABS = ['Overview', 'Activity', 'Connections', 'Endpoints', 'Try it', 'Caches', 'Config', 'Tokens'];
  var state = { tab: 'Overview', status: null, tokens: null, showRevoked: false, timer: null,
                activity: null, window: 'last_5m', expanded: {},
                openapi: null, tryPath: null, epOpen: null, epDefs: {}, tryValues: {}, callToken: '' };
  // Activity thresholds: rows above them are flagged
  var MAX_ERROR_RATE = 0.05, MAX_P95_MS = 5000;

  function $(id) { return document.getElementById(id); }
  function getToken() { try { return sessionStorage.getItem(TOKEN_KEY); } catch (e) { return null; } }
  function setToken(t) { try { if (t) sessionStorage.setItem(TOKEN_KEY, t); else sessionStorage.removeItem(TOKEN_KEY); } catch (e) { /* ignore */ } }

  // ---- DOM helpers: every server string goes through textContent ----
  function el(tag, props, children) {
    var n = document.createElement(tag);
    Object.keys(props || {}).forEach(function (k) {
      if (k === 'text') n.textContent = props[k];
      else if (k === 'class') n.className = props[k];
      else if (k === 'onclick') n.addEventListener('click', props[k]);
      else n.setAttribute(k, props[k]);
    });
    (children || []).forEach(function (c) { if (c != null) n.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); });
    return n;
  }
  function badge(text, cls) { return el('span', { class: 'badge ' + (cls || ''), text: String(text) }); }
  function dash(v) { return v == null || v === '' ? '—' : String(v); }
  function fmtTime(v) {
    if (v == null) return '—';
    var d = new Date(typeof v === 'number' ? v : Date.parse(v));
    return isNaN(d) ? String(v) : d.toLocaleString();
  }
  function table(head, rows) {
    var tb = el('tbody');
    rows.forEach(function (r) {
      var tr = el('tr', r.cls ? { class: r.cls } : {});
      r.cells.forEach(function (c) {
        var td = el('td');
        td.appendChild(c instanceof Node ? c : document.createTextNode(dash(c)));
        tr.appendChild(td);
      });
      tb.appendChild(tr);
    });
    return el('table', {}, [el('thead', {}, [el('tr', {}, head.map(function (h) { return el('th', { text: h }); }))]), tb]);
  }
  function section(title, node) { return el('div', {}, [el('h2', { text: title }), node]); }

  // ---- API ----
  function logout(msg) {
    setToken(null);
    stopPolling();
    state.status = null; state.tokens = null; state.openapi = null; state.epOpen = null; state.epDefs = {}; state.callToken = ''; state.tryValues = {};
    $('app').hidden = true;
    $('login').hidden = false;
    $('login-error').textContent = msg || '';
  }
  function api(method, path, body) {
    var opts = { method: method, headers: { Authorization: 'Bearer ' + getToken() } };
    if (body !== undefined) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body); }
    return fetch(path, opts).then(function (r) {
      return r.text().then(function (t) {
        var j = null; try { j = t ? JSON.parse(t) : null; } catch (e) { /* not json */ }
        var res = { status: r.status, ok: r.ok, body: j };
        if (r.status === 401 || r.status === 403) {
          var https = j && j.error && j.error.code === 'https_required';
          logout(https ? errMsg(res) : 'Token rejected. Log in with an admin token.');
          throw new Error('auth');
        }
        return res;
      });
    });
  }
  function fail(e) { if (e && e.message !== 'auth') $('banner').textContent = 'Request failed: ' + e.message; }
  function errMsg(r) { return (r.body && r.body.error && (r.body.error.message || r.body.error.code)) || ('HTTP ' + r.status); }

  // ---- data loading ----
  function refresh() {
    if (!getToken()) return Promise.resolve();
    var p = api('GET', '/status').then(function (r) {
      if (r.ok) { state.status = r.body; $('banner').textContent = ''; }
      else $('banner').textContent = 'Status failed: ' + errMsg(r);
    });
    if (state.tab === 'Tokens') p = p.then(loadTokens);
    if (state.tab === 'Activity') p = p.then(loadActivity);
    return p.then(render).catch(fail);
  }
  function loadTokens() {
    return api('GET', '/admin/tokens').then(function (r) {
      if (r.ok) state.tokens = r.body;
      else if (r.status === 404) state.tokens = { tokens: [], config_keys: [], disabled: true };
    });
  }
  function loadActivity() {
    return api('GET', '/admin/activity').then(function (r) {
      if (r.ok) state.activity = r.body;
      else $('banner').textContent = 'Activity failed: ' + errMsg(r);
    });
  }
  function startPolling() {
    stopPolling();
    state.timer = setInterval(function () { if (!document.hidden) refresh(); }, 5000);
  }
  function stopPolling() { if (state.timer) { clearInterval(state.timer); state.timer = null; } }

  // ---- rendering ----
  function render() {
    var s = state.status;
    if (!s) return;
    var cfg = s.config || {};
    renderHeader(s);
    if (!state.shell) buildControls();
    renderData(s);
  }
  function renderHeader(s) {
    var cfg = s.config || {};
    var title = $('title');
    title.textContent = '';
    title.appendChild(document.createTextNode('Spindle · ' + dash(s.node) + ' · config ' + dash(cfg.version) + ' '));
    if (cfg.pinned) title.appendChild(badge('pinned', 'pinned'));
  }
  function buildNav() {
    var nav = $('tabs');
    nav.textContent = '';
    TABS.forEach(function (t) {
      nav.appendChild(el('button', { class: t === state.tab ? 'active' : '', text: t, onclick: function () {
        state.tab = t; state.shell = false; buildNav(); render(); refresh();
      } }));
    });
  }
  // Controls (inputs, buttons with typed state) are built once per tab switch;
  // only the data area is rebuilt on each poll.
  function buildControls() {
    var view = $('view');
    view.textContent = '';
    var c = el('div', { id: 'controls' });
    var builder = { Endpoints: endpointControls, Activity: activityControls, Caches: cacheControls, Tokens: tokenControls, 'Try it': tryControls }[state.tab];
    if (builder) builder(c);
    view.appendChild(c);
    view.appendChild(el('div', { id: 'data' }));
    state.shell = true;
  }
  function renderData(s) {
    var d = $('data');
    // Endpoints only changes when the config, the open definition or the filter does: rebuilding it
    // on every poll would reset the reader's scroll position in the list and the definition.
    if (state.tab === 'Endpoints') {
      var def = (state.epDefs || {})[state.epOpen] || {};
      var key = [(s.config || {}).version, (s.endpoints || []).length, state.epOpen, state.epFilter,
                 def.loading ? 'l' : def.error ? 'e' : def.edn ? 'd' : '-'].join('|');
      if (d.firstChild && state.epKey === key) return;
      state.epKey = key;
    } else state.epKey = null;
    d.textContent = '';
    var f = { Overview: overview, Activity: activityTab, Connections: connections, Endpoints: endpoints, 'Try it': function () { return document.createTextNode(''); },
              Caches: caches, Config: configTab, Tokens: tokensTab }[state.tab];
    d.appendChild(f(s));
  }

  function breakerState(b) { return (b && b.state) || b; }
  function breakerBadge(b) { var v = String(dash(breakerState(b))).toLowerCase(); return badge(v.toUpperCase(), v); }
  function bulk(b) { return b ? b.in_use + '/' + b.limit + ' (' + (b.queued || 0) + ' queued)' : '—'; }
  function poolText(p) {
    if (!p) return '—';
    return Object.keys(p).map(function (k) { return k + ' ' + p[k]; }).join(', ');
  }
  function errText(e) {
    if (e == null) return null;
    return typeof e === 'string' ? e : (e.message || e.code || JSON.stringify(e));
  }

  function overview(s) {
    var cfg = s.config || {}, conns = s.connections || [];
    var open = conns.filter(function (c) { return String(breakerState(c.breaker)).toLowerCase() === 'open'; }).length;
    return table(['Item', 'Value'], [
      { cells: ['Node', s.node] },
      { cells: ['Config version', cfg.version] },
      { cells: ['Loaded at', fmtTime(cfg.loaded_at)] },
      { cells: ['Pinned', cfg.pinned ? 'yes' : 'no'] },
      { cells: ['Endpoints', (s.endpoints || []).length] },
      { cells: ['Connections', conns.length + (open ? ' (' + open + ' with open breaker)' : '')] }
    ]);
  }
  function connections(s) {
    return table(['Id', 'Type', 'Breaker', 'Bulkhead', 'Pool', 'Last error'],
      (s.connections || []).map(function (c) {
        return { cells: [c.id, c.type, breakerBadge(c.breaker), bulk(c.bulkhead), poolText(c.pool), errText(c.last_error)] };
      }));
  }
  // Endpoints: click a path to see its definition (parameters, the flow's .edn with its
  // comments, the .sql). Definitions are fetched once and kept, so polling does not refetch.
  function endpointControls(c) {
    var q = el('input', { type: 'search', class: 'ep-search', placeholder: 'Filter by path or flow', autocomplete: 'off' });
    q.value = state.epFilter || '';
    q.addEventListener('input', function () { state.epFilter = q.value; if (state.status) renderData(state.status); });
    c.appendChild(q);
  }
  function epGroups(eps) {
    var key2 = function (p) { return '/' + p.split('/').filter(Boolean).slice(0, 2).join('/'); };
    var n = {};
    eps.forEach(function (e) { var k = key2(e.path); n[k] = (n[k] || 0) + 1; });
    var groups = {};
    eps.forEach(function (e) {
      var k = n[key2(e.path)] > 1 ? key2(e.path) : '/' + (e.path.split('/').filter(Boolean)[0] || '');
      (groups[k] = groups[k] || []).push(e);
    });
    return Object.keys(groups).sort().map(function (k) { return { name: k, eps: groups[k] }; });
  }
  function endpoints(s) {
    var ver = (s.config || {}).version;
    if (state.epDefsVer !== ver) { state.epDefs = {}; state.epDefsVer = ver; fetchDef(state.epOpen); } // a reload changes definitions
    var f = (state.epFilter || '').trim().toLowerCase();
    var eps = (s.endpoints || []).filter(function (e) {
      return !f || e.path.toLowerCase().indexOf(f) >= 0 || String(e.id).toLowerCase().indexOf(f) >= 0;
    }).sort(function (a, b) { return a.path < b.path ? -1 : a.path > b.path ? 1 : 0; });
    var list = el('div', { class: 'ep-list' });
    list.appendChild(el('div', { class: 'ep-count muted', text: eps.length + ' of ' + (s.endpoints || []).length + ' endpoints' }));
    epGroups(eps).forEach(function (g) {
      var card = el('div', { class: 'ep-group' }, [el('div', { class: 'ep-group-name' }, [g.name, el('span', { class: 'ep-n', text: String(g.eps.length) })])]);
      g.eps.forEach(function (e) {
        var open = state.epOpen === e.id;
        var rest = e.path.indexOf(g.name) === 0 && e.path !== g.name ? e.path.slice(g.name.length) : e.path;
        card.appendChild(el('button', { class: 'ep-row' + (open ? ' active' : ''), title: 'Show the definition',
                                        onclick: function () { openEndpoint(open ? null : e.id); } }, [
          el('span', { class: 'ep-method m-' + String(e.method).toLowerCase(), text: String(dash(e.method)).toUpperCase() }),
          el('span', { class: 'ep-path' }, rest === e.path ? [el('b', { text: e.path })]
                                                           : [el('span', { class: 'muted', text: g.name }), el('b', { text: rest })]),
          el('span', { class: 'ep-flow', text: e.id })]));
      });
      list.appendChild(card);
    });
    if (!eps.length) list.appendChild(el('p', { class: 'muted', text: 'No endpoint matches.' }));
    var wrap = el('div', { class: 'ep-layout' + (state.epOpen ? ' with-detail' : '') }, [list]);
    if (state.epOpen) wrap.appendChild(endpointDetail(state.epOpen));
    return wrap;
  }
  function fetchDef(id) {
    if (!id || state.epDefs[id]) return;
    state.epDefs[id] = { loading: true };
    api('GET', '/admin/flows/' + id.split('/').map(encodeURIComponent).join('/')).then(function (r) {
      state.epDefs[id] = r.ok ? r.body : { error: errMsg(r) };
      render();
    }).catch(function (e) { if (e && e.message !== 'auth') { state.epDefs[id] = { error: e.message }; render(); } });
  }
  function openEndpoint(id) {
    state.epOpen = id;
    fetchDef(id);
    render();
  }
  function schemaText(sc) {
    if (!sc) return '—';
    var t = sc.type === 'array' ? (((sc.items || {}).type || 'any') + '[]') : (sc.type || 'any');
    var lim = [];
    if (sc.enum) lim.push('one of ' + sc.enum.join(', '));
    if (sc.minimum != null) lim.push('≥ ' + sc.minimum);
    if (sc.maximum != null) lim.push('≤ ' + sc.maximum);
    if (sc.minLength != null || sc.maxLength != null) lim.push('length ' + dash(sc.minLength) + '..' + dash(sc.maxLength));
    if (sc.minItems != null || sc.maxItems != null) lim.push('items ' + dash(sc.minItems) + '..' + dash(sc.maxItems));
    if (sc.format) lim.push(sc.format);
    return t + (lim.length ? ' (' + lim.join('; ') + ')' : '');
  }
  function paramRows(oa) {
    var rows = [];
    Object.keys(oa || {}).sort().forEach(function (path) {
      Object.keys(oa[path]).forEach(function (m) {
        var op = oa[path][m] || {};
        (op.parameters || []).forEach(function (p) {
          rows.push({ cells: [m.toUpperCase() + ' ' + path, p.name, p.in, p.required ? 'yes' : 'no', schemaText(p.schema), p.description] });
        });
        var body = (((op.requestBody || {}).content || {})['application/json'] || {}).schema;
        if (body && body.properties) {
          Object.keys(body.properties).forEach(function (k) {
            rows.push({ cells: [m.toUpperCase() + ' ' + path, k, 'body', (body.required || []).indexOf(k) >= 0 ? 'yes' : 'no',
                                schemaText(body.properties[k]), body.properties[k].description] });
          });
        }
      });
    });
    return rows;
  }
  // The :sql strings of a flow's .edn (EDN string escapes undone).
  function sqlStrings(edn) {
    var out = [], re = /:sql\s+"((?:[^"\\]|\\.)*)"/g, m;
    while ((m = re.exec(edn || ''))) out.push(m[1].replace(/\\(["\\])/g, '$1').replace(/\\n/g, '\n'));
    return out;
  }
  // A light SQL layout for reading only (never executed): a new line before each clause, indented
  // by parenthesis depth; text inside quotes is left alone.
  var SQL_BREAK = /^(select|from|where|and|or|union all|union|order by|group by|having|left join|right join|inner join|cross join|join|on|with|limit|case|when|else|end)\b/i;
  function formatSql(q) {
    if (/\n/.test(q)) return q; // already laid out by its author
    var out = '', depth = 0, i = 0, inStr = false, cases = [], clause = [];
    var pad = function (d) { return new Array(d + 1).join('    '); };
    var nl = function (d, extra) { out = out.replace(/ +$/, '') + (out ? '\n' : '') + pad(d) + (extra || ''); };
    while (i < q.length) {
      var c = q[i];
      if (inStr) { out += c; if (c === "'") inStr = false; i++; continue; }
      if (c === "'") { inStr = true; out += c; i++; continue; }
      if (c === '(') { depth++; out += c; i++; continue; }
      if (c === ')') { depth = Math.max(0, depth - 1); out += c; i++; continue; }
      var inCase = cases.length && cases[cases.length - 1] === depth;
      // one column per line in a select list
      if (c === ',' && clause[depth] === 'select' && !inCase) { out += ','; nl(depth, '       '); i++; while (q[i] === ' ') i++; continue; }
      var prev = i === 0 ? ' ' : q[i - 1];
      if (/[\s(]/.test(prev)) {
        var m = SQL_BREAK.exec(q.slice(i)), w = m && m[1].toLowerCase();
        if (w === 'case') cases.push(depth);
        else if (w === 'end' && inCase) cases.pop();
        else if (w && !inCase && !/^(on|when|else|end)$/.test(w)) {
          if (w === 'and' || w === 'or') { if (clause[depth] !== 'select') nl(depth, '  '); }
          else { nl(depth); clause[depth] = w; }
        }
      }
      out += c; i++;
    }
    return out;
  }
  function endpointDetail(id) {
    var d = (state.epDefs || {})[id] || { loading: true };
    var box = el('div', { class: 'ep-detail' });
    box.appendChild(el('div', { class: 'ep-head' }, [
      el('h2', { text: d.endpoint ? d.endpoint.method.toUpperCase() + ' ' + d.endpoint.path : id }),
      el('button', { text: 'Close', onclick: function () { openEndpoint(null); } })]));
    if (d.loading) { box.appendChild(el('p', { class: 'muted', text: 'Loading…' })); return box; }
    if (d.error) { box.appendChild(el('p', { class: 'error', text: 'Could not load the definition: ' + d.error })); return box; }
    var ep = d.endpoint || {};
    box.appendChild(table(['Item', 'Value'], [
      { cells: ['Flow', d.id] }, { cells: ['Version', d.version] },
      { cells: ['Scopes', (ep.scopes || []).join(', ') || 'none'] },
      { cells: ['Batch form', ep.batch ? 'POST /api/_batch/' + ep.id : 'no'] },
      { cells: ['Paging', ep.page ? 'yes (size, after)' : 'no'] }]));
    var pr = paramRows(d.openapi);
    box.appendChild(section('Parameters', pr.length ? table(['Call', 'Name', 'In', 'Required', 'Type', 'Description'], pr)
                                                    : el('p', { class: 'muted', text: 'No parameters.' })));
    var stmts = sqlStrings(d.edn);
    stmts.forEach(function (q, i) {
      box.appendChild(section('SQL' + (stmts.length > 1 ? ' — step ' + (i + 1) : '') + ' (laid out for reading)',
                              el('pre', { class: 'try-body ep-src', text: formatSql(q) })));
    });
    if (d.sql) box.appendChild(section('SQL (' + d.id + '.sql)', el('pre', { class: 'try-body ep-src', text: formatSql(d.sql) })));
    box.appendChild(section('Flow definition (' + d.id + '.edn)', el('pre', { class: 'try-body ep-src', text: d.edn })));
    return box;
  }
  function cacheRows(kind, obj) {
    return Object.keys(obj || {}).map(function (k) {
      var v = obj[k];
      if (v && typeof v === 'object') return { cells: [kind, k, v.entries, v.hits, v.misses] };
      return { cells: [kind, k, v, null, null] };
    });
  }
  function caches(s) {
    return table(['Kind', 'Name', 'Entries', 'Hits', 'Misses'],
      cacheRows('step_cache', s.step_cache).concat(cacheRows('cache', s.caches)));
  }
  function cacheControls(c) {
    var tag = el('input', { id: 'evict-tag', placeholder: 'tag' });
    c.appendChild(el('div', { class: 'inline' }, [tag, el('button', { class: 'danger', text: 'Evict tag', onclick: function () {
      var t = tag.value.trim();
      if (!t) return;
      if (!confirm('Evict cache entries tagged "' + t + '"?')) return;
      api('POST', '/admin/cache/evict?tag=' + encodeURIComponent(t)).then(function (r) {
        $('banner').textContent = r.ok ? '' : 'Evict failed: ' + errMsg(r);
        if (r.ok) alert('Evicted tag ' + t);
        refresh();
      }).catch(fail);
    } })]));
  }
  function configTab(s) {
    var cfg = s.config || {}, wrap = el('div');
    var lr = cfg.last_reload;
    wrap.appendChild(section('Last reload', lr ? table(['Field', 'Value'], [
      { cells: ['Result', lr.ok === false ? badge('failed', 'bad') : badge('ok', 'ok')] },
      { cells: ['At', fmtTime(lr.at || lr.time)] },
      { cells: ['Problems', problemsText(lr.problems || lr.errors)] }
    ]) : el('p', { class: 'muted', text: 'No reload yet.' })));
    var hist = cfg.history || [];
    wrap.appendChild(section('Version history', table(['Version', 'Loaded at', ''], hist.map(function (h) {
      var v = typeof h === 'object' ? h.version : h;
      var at = typeof h === 'object' ? fmtTime(h.loaded_at) : '—';
      var cur = v === cfg.version;
      return { cells: [v, at, cur ? badge('current') : el('button', { class: 'danger', text: 'Roll back', onclick: function () {
        if (!confirm('Roll back config to ' + v + '? The node will be pinned.')) return;
        api('POST', '/admin/config/rollback?to=' + encodeURIComponent(v)).then(function (r) {
          $('banner').textContent = r.ok ? '' : 'Rollback failed: ' + errMsg(r);
          refresh();
        }).catch(fail);
      } })] };
    }))));
    if (cfg.pinned) {
      wrap.appendChild(el('button', { text: 'Unpin', onclick: function () {
        api('POST', '/admin/config/unpin').then(function () { refresh(); }).catch(fail);
      } }));
    }
    return wrap;
  }
  function problemsText(p) {
    if (!p || !p.length) return 'none';
    return p.map(function (x) { return typeof x === 'string' ? x : JSON.stringify(x); }).join('\n');
  }

  // ---- activity ----
  function ms(v) { return v == null ? '—' : (v >= 1000 ? (v / 1000).toFixed(2) + ' s' : Math.round(v) + ' ms'); }
  function pct(v) { return v == null ? '—' : (v * 100).toFixed(1) + '%'; }
  function activityControls(c) {
    var wins = [['last_5m', 'Last 5 minutes'], ['since_start', 'Since start']];
    var bar = el('div', { class: 'inline' });
    wins.forEach(function (w) {
      bar.appendChild(el('button', { class: state.window === w[0] ? 'active' : '', text: w[1], onclick: function () {
        state.window = w[0];
        Array.prototype.forEach.call(bar.children, function (b, i) { b.className = wins[i][0] === state.window ? 'active' : ''; });
        renderData(state.status);
      } }));
    });
    c.appendChild(bar);
  }
  function activityTab() {
    var wrap = el('div'), a = state.activity;
    if (!a) { wrap.appendChild(el('p', { class: 'muted', text: 'Loading…' })); return wrap; }
    var rows = a[state.window] || [];
    wrap.appendChild(el('p', { class: 'muted', text: (state.window === 'last_5m' ? 'Last ' + a.window_minutes + ' minutes' : 'Since ' + fmtTime(a.started_at)) +
      ' · flagged: error rate > ' + pct(MAX_ERROR_RATE) + ' or p95 > ' + ms(MAX_P95_MS) + ' · click an API for its error codes' }));
    var out = [];
    rows.forEach(function (r) {
      var badRate = r.error_rate > MAX_ERROR_RATE, badP95 = r.p95_ms != null && r.p95_ms > MAX_P95_MS;
      var name = el('button', { class: 'link', text: (state.expanded[r.endpoint] ? '▾ ' : '▸ ') + r.endpoint, onclick: function () {
        state.expanded[r.endpoint] = !state.expanded[r.endpoint]; renderData(state.status);
      } });
      out.push({ cls: badRate || badP95 ? 'flagged' : '', cells: [name, r.count, r.errors,
        badRate ? badge(pct(r.error_rate), 'bad') : pct(r.error_rate), ms(r.avg_ms),
        badP95 ? badge(ms(r.p95_ms), 'bad') : ms(r.p95_ms), ms(r.max_ms)] });
      if (state.expanded[r.endpoint]) {
        var codes = Object.keys(r.codes || {});
        out.push({ cls: 'detail', cells: ['', codes.length ? codes.map(function (k) { return k + ': ' + r.codes[k]; }).join(', ') : 'no errors', '', '', '', '', ''] });
      }
    });
    wrap.appendChild(rows.length ? table(['API', 'Calls', 'Errors', 'Error rate', 'Avg', 'p95', 'Max'], out)
      : el('p', { class: 'muted', text: 'No API calls in this period.' }));
    var errs = a.recent_errors || [];
    wrap.appendChild(section('Recent errors (last ' + errs.length + ', newest first)', errs.length
      ? table(['Time', 'API', 'Code', 'Duration', 'Caller', 'request_id'], errs.map(function (e) {
          return { cells: [fmtTime(e.at), e.endpoint, badge(e.code, 'bad'), ms(e.ms), e.caller, el('code', { text: dash(e.request_id) })] };
        }))
      : el('p', { class: 'muted', text: 'No errors since start.' })));
    return wrap;
  }

  function scopeList(scopes) { return (scopes || []).join(', '); }
  function tokensTab(s) {
    var wrap = el('div'), t = state.tokens;
    if (!t) { wrap.appendChild(el('p', { class: 'muted', text: 'Loading…' })); return wrap; }
    if (t.disabled) { wrap.appendChild(el('p', { class: 'muted', text: 'Token store is not enabled on this node.' })); }
    var live = (t.tokens || []).filter(function (x) { return !x.revoked_at; });
    var shown = state.showRevoked ? t.tokens || [] : live;
    var admin = t.admin_scope;
    var stale = live.filter(function (x) { return admin && (x.scopes || []).some(function (sc) { return /:admin$/.test(sc) && sc !== admin; }); });
    if (stale.length) wrap.appendChild(el('p', { class: 'error', text: 'Some tokens hold an admin scope that is not the configured admin scope (' + admin + ').' }));
    wrap.appendChild(table(['Id', 'Scopes', 'Created', 'By', 'Expires', 'Last used', ''], shown.map(function (x) {
      var revoked = !!x.revoked_at;
      return { cls: revoked ? 'revoked' : '', cells: [x.id, scopeList(x.scopes), fmtTime(x.created_at), x.created_by, fmtTime(x.expires_at),
        fmtTime(x.last_used_at),
        revoked ? badge('revoked ' + fmtTime(x.revoked_at), 'bad') : el('button', { class: 'danger', text: 'Revoke', onclick: function () { revoke(x.id); } })] };
    })));
    wrap.appendChild(section('Config keys (read-only, from auth.edn)', table(['Id', 'Scopes'],
      (t.config_keys || []).map(function (k) { return { cells: [k.id, scopeList(k.scopes)] }; }))));
    return wrap;
  }
  function tokenControls(c) {
    var cb = el('input', { type: 'checkbox', id: 'show-revoked' });
    cb.checked = state.showRevoked;
    cb.addEventListener('change', function (e) { state.showRevoked = e.target.checked; renderData(state.status); });
    c.appendChild(el('div', { class: 'inline' }, [
      el('button', { text: '+ Issue token', onclick: openIssue }),
      el('label', {}, [cb, ' show revoked'])
    ]));
  }
  function revoke(id) {
    if (!confirm('Revoke token "' + id + '"? It stops working within 5 seconds.')) return;
    api('DELETE', '/admin/tokens/' + encodeURIComponent(id)).then(function (r) {
      $('banner').textContent = r.ok ? '' : 'Revoke failed: ' + errMsg(r);
      refresh();
    }).catch(fail);
  }

  // ---- try it ----
  // Calls a published endpoint with a caller's token (not the admin token: endpoints check
  // their own scopes). The token stays in memory only. Everything lives in the controls area,
  // so polling never wipes the form or the result.
  function tryControls(c) {
    var box = el('div', { id: 'try' }, [el('p', { class: 'muted', text: 'Loading endpoints…' })]);
    c.appendChild(box);
    var p = state.openapi ? Promise.resolve() : api('GET', '/openapi.json').then(function (r) {
      if (r.ok) state.openapi = r.body; else throw new Error(errMsg(r));
    });
    p.then(function () { buildTry(box); }).catch(function (e) {
      if (e && e.message !== 'auth') box.textContent = 'Could not load endpoints: ' + e.message;
    });
  }
  function tryOps() {
    var paths = (state.openapi && state.openapi.paths) || {};
    return Object.keys(paths).filter(function (p) { return paths[p].get; }).sort()
      .map(function (p) { return { path: p, op: paths[p].get }; });
  }
  function buildTry(box) {
    box.textContent = '';
    var ops = tryOps();
    if (!ops.length) { box.appendChild(el('p', { class: 'muted', text: 'No GET endpoints are published.' })); return; }
    if (!state.tryPath || !ops.some(function (o) { return o.path === state.tryPath; })) state.tryPath = ops[0].path;
    var sel = el('select', { id: 'try-endpoint' }, ops.map(function (o) {
      var opt = el('option', { value: o.path, text: 'GET ' + o.path });
      if (o.path === state.tryPath) opt.selected = true;
      return opt;
    }));
    sel.addEventListener('change', function () { state.tryPath = sel.value; state.tryValues = {}; buildTry(box); });
    var tok = el('input', { id: 'try-token', type: 'password', autocomplete: 'off', placeholder: 'token with the endpoint’s scope' });
    tok.value = state.callToken;
    tok.addEventListener('input', function () { state.callToken = tok.value.trim(); });
    var op = ops.filter(function (o) { return o.path === state.tryPath; })[0].op;
    // default test values: the flow's first :example, unless something was typed already
    var ex = (op['x-spindle-examples'] || [])[0];
    if (ex && !Object.keys(state.tryValues).some(function (k) { return state.tryValues[k]; })) {
      Object.keys(ex).forEach(function (k) {
        var v = ex[k];
        state.tryValues[k] = Array.isArray(v) ? v.join(',') : (v == null ? '' : String(v));
      });
    }
    var fields = (op.parameters || []).filter(function (p) { return p.in === 'query'; }).map(function (p) {
      var sc = p.schema || {};
      var hint = [sc.type, sc.description, sc.minimum != null ? 'min ' + sc.minimum : null,
                  sc.maximum != null ? 'max ' + sc.maximum : null, sc.pattern ? 'pattern ' + sc.pattern : null]
        .filter(function (x) { return x != null; }).join(', ');
      var inp = el('input', { 'data-param': p.name, placeholder: hint });
      inp.value = state.tryValues[p.name] || '';
      inp.addEventListener('input', function () { state.tryValues[p.name] = inp.value; });
      return el('label', {}, [el('span', { text: p.name + (p.required ? ' *' : '') }), inp]);
    });
    var out = el('div', { id: 'try-result' });
    var send = el('button', { id: 'try-send', text: 'Send', type: 'submit' });
    var form = el('form', { class: 'try-form' }, [
      el('label', {}, [el('span', { text: 'Endpoint' }), sel]),
      el('label', {}, [el('span', { text: 'Call as' }), tok]),
      el('p', { class: 'muted', text: 'Paste a token issued with this endpoint’s scope (Tokens tab). It is kept in this page only.' })
    ].concat(fields).concat([el('div', { class: 'actions' }, [send])]));
    form.addEventListener('submit', function (e) { e.preventDefault(); sendTry(op, out, send); });
    box.appendChild(form);
    box.appendChild(out);
  }
  function tryUrl(op) {
    var qs = (op.parameters || []).filter(function (p) { return p.in === 'query'; })
      .map(function (p) { var v = (state.tryValues[p.name] || '').trim(); return v ? encodeURIComponent(p.name) + '=' + encodeURIComponent(v) : null; })
      .filter(Boolean).join('&');
    return state.tryPath + (qs ? '?' + qs : '');
  }
  function sendTry(op, out, send) {
    out.textContent = '';
    var missing = (op.parameters || []).filter(function (p) { return p.required && !(state.tryValues[p.name] || '').trim(); }).map(function (p) { return p.name; });
    if (missing.length) { out.appendChild(el('p', { class: 'error', text: 'Required: ' + missing.join(', ') })); return; }
    if (!state.callToken) { out.appendChild(el('p', { class: 'error', text: 'Paste a token to call as.' })); return; }
    var url = tryUrl(op), t0 = performance.now();
    send.disabled = true;
    // plain fetch, not api(): a 401/403 here is about the caller's token, not the admin session
    fetch(url, { headers: { Authorization: 'Bearer ' + state.callToken } }).then(function (r) {
      return r.text().then(function (t) {
        var ms = Math.round(performance.now() - t0), j = null;
        try { j = t ? JSON.parse(t) : null; } catch (e) { /* not json */ }
        var rows = j && j.rows;
        var meta = 'HTTP ' + r.status + ' · ' + ms + ' ms' + (Array.isArray(rows) ? ' · ' + rows.length + ' rows' : '') +
                   (j && j.meta && j.meta.request_id ? ' · ' + j.meta.request_id : '') +
                   (j && j.error && j.error.request_id ? ' · ' + j.error.request_id : '');
        out.appendChild(el('p', {}, [badge(r.status, r.ok ? 'ok' : 'bad'), ' ' + meta]));
        out.appendChild(el('code', { class: 'try-curl', text: 'curl -H "Authorization: Bearer $TOKEN" "' + location.origin + url + '"' }));
        out.appendChild(el('pre', { class: 'try-body', text: j ? JSON.stringify(j, null, 2) : t }));
      });
    }).catch(function (e) {
      out.appendChild(el('p', { class: 'error', text: 'Request failed: ' + e.message }));
    }).then(function () { send.disabled = false; });
  }

  // ---- issue dialog ----
  function openIssue() {
    var adminScope = (state.tokens && state.tokens.admin_scope) || 'spindle:admin';
    $('scope-admin').value = adminScope;
    $('scope-admin-label').textContent = 'admin (' + adminScope + ')';
    $('issue-form').reset();
    $('issue-error').textContent = '';
    $('issue-dialog').showModal();
  }
  function submitIssue(e) {
    e.preventDefault();
    var scopes = ['scope-author', 'scope-admin', 'scope-delegate'].filter(function (i) { return $(i).checked; }).map(function (i) { return $(i).value; });
    $('scope-extra').value.split(/[\s,]+/).filter(Boolean).forEach(function (x) { if (scopes.indexOf(x) < 0) scopes.push(x); });
    var body = { id: $('issue-id').value.trim(), scopes: scopes };
    var exp = $('issue-expiry').value;
    if (exp) body.expires_at = new Date(exp).toISOString();
    if (!scopes.length) { $('issue-error').textContent = 'Pick at least one scope.'; return; }
    api('POST', '/admin/tokens', body).then(function (r) {
      if (!r.ok) { $('issue-error').textContent = r.status === 409 ? 'A token with that name already exists.' : errMsg(r); return; }
      $('issue-dialog').close();
      $('secret-copy').textContent = 'Copy';
      $('secret-value').textContent = r.body.token;
      $('secret-dialog').showModal();
      refresh();
    }).catch(fail);
  }
  function clearSecret() { $('secret-value').textContent = ''; $('secret-copy').textContent = 'Copy'; }
  function closeSecret() { $('secret-dialog').close(); }
  function copySecret() {
    var v = $('secret-value').textContent;
    if (navigator.clipboard) navigator.clipboard.writeText(v).then(function () { $('secret-copy').textContent = 'Copied'; });
  }

  // ---- boot ----
  function enter() {
    $('login').hidden = true;
    $('app').hidden = false;
    state.shell = false;
    buildNav();
    refresh();
    startPolling();
  }
  $('login-form').addEventListener('submit', function (e) {
    e.preventDefault();
    setToken($('login-token').value.trim());
    $('login-token').value = '';
    enter();
  });
  // Development: when the server has SPINDLE_DEV_TOKEN and this page is on the same machine,
  // /console/dev-token hands it over; one click logs in and Try it calls with it too.
  function checkDev() {
    fetch('/console/dev-token').then(function (r) { return r.ok ? r.json() : null; })
      .then(function (b) {
        state.devToken = (b && b.token) || ''; $('dev-login').hidden = !state.devToken;
        // still logged in with the dev token after a page reload: Try it calls with it by default
        if (state.devToken && !state.callToken && getToken() === state.devToken) {
          state.callToken = state.devToken;
          var t = $('try-token'); if (t && !t.value) t.value = state.devToken;
        }
      })
      .catch(function () { $('dev-login').hidden = true; });
  }
  $('dev-login').addEventListener('click', function () {
    if (!state.devToken) return;
    setToken(state.devToken);
    state.callToken = state.devToken;
    enter();
  });
  checkDev();
  $('logout-btn').addEventListener('click', function () { logout(''); });
  $('reload-btn').addEventListener('click', function () {
    api('POST', '/admin/reload').then(function (r) {
      $('banner').textContent = r.ok ? '' : 'Reload failed: ' + errMsg(r);
      refresh();
    }).catch(fail);
  });
  $('issue-form').addEventListener('submit', submitIssue);
  $('issue-cancel').addEventListener('click', function () { $('issue-dialog').close(); });
  $('secret-copy').addEventListener('click', copySecret);
  $('secret-close').addEventListener('click', closeSecret);
  $('secret-dialog').addEventListener('close', clearSecret);
  document.addEventListener('visibilitychange', function () { if (!document.hidden && getToken()) refresh(); });

  if (getToken()) enter(); else logout('');
})();
