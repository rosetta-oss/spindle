'use strict';
(function () {
  var TOKEN_KEY = 'spindle.token';
  var TABS = ['Overview', 'Activity', 'Connections', 'Endpoints', 'Caches', 'Config', 'Tokens'];
  var state = { tab: 'Overview', status: null, tokens: null, showRevoked: false, timer: null,
                activity: null, window: 'last_5m', expanded: {} };
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
    state.status = null; state.tokens = null;
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
    var builder = { Activity: activityControls, Caches: cacheControls, Tokens: tokenControls }[state.tab];
    if (builder) builder(c);
    view.appendChild(c);
    view.appendChild(el('div', { id: 'data' }));
    state.shell = true;
  }
  function renderData(s) {
    var d = $('data');
    d.textContent = '';
    var f = { Overview: overview, Activity: activityTab, Connections: connections, Endpoints: endpoints, Caches: caches, Config: configTab, Tokens: tokensTab }[state.tab];
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
  function endpoints(s) {
    return table(['Method', 'Path', 'Flow'],
      (s.endpoints || []).map(function (e) { return { cells: [String(dash(e.method)).toUpperCase(), e.path, e.id] }; }));
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
