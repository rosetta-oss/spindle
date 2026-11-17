'use strict';
(function () {
  var TOKEN_KEY = 'spindle.token';
  var TABS = ['Overview', 'Connections', 'Endpoints', 'Caches', 'Config', 'Tokens'];
  var state = { tab: 'Overview', status: null, tokens: null, showRevoked: false, timer: null };

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
      if (r.status === 401 || r.status === 403) { logout('Token rejected. Log in with an admin token.'); throw new Error('auth'); }
      return r.text().then(function (t) {
        var j = null; try { j = t ? JSON.parse(t) : null; } catch (e) { /* not json */ }
        return { status: r.status, ok: r.ok, body: j };
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
    return p.then(render).catch(fail);
  }
  function loadTokens() {
    return api('GET', '/admin/tokens').then(function (r) {
      if (r.ok) state.tokens = r.body;
      else if (r.status === 404) state.tokens = { tokens: [], config_keys: [], disabled: true };
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
    var title = $('title');
    title.textContent = '';
    title.appendChild(document.createTextNode('Spindle · ' + dash(s.node) + ' · config ' + dash(cfg.version) + ' '));
    if (cfg.pinned) title.appendChild(badge('pinned', 'pinned'));
    var nav = $('tabs');
    nav.textContent = '';
    TABS.forEach(function (t) {
      nav.appendChild(el('button', { class: t === state.tab ? 'active' : '', text: t, onclick: function () { state.tab = t; refresh(); } }));
    });
    var view = $('view');
    view.textContent = '';
    var f = { Overview: overview, Connections: connections, Endpoints: endpoints, Caches: caches, Config: configTab, Tokens: tokensTab }[state.tab];
    view.appendChild(f(s));
  }

  function breakerBadge(b) { var v = dash(b).toLowerCase(); return badge(v.toUpperCase(), v); }
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
    var open = conns.filter(function (c) { return String(c.breaker).toLowerCase() === 'open'; }).length;
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
  function caches(s) {
    var wrap = el('div');
    var rows = [];
    var stepCache = s.step_cache || {};
    Object.keys(stepCache).forEach(function (k) {
      var v = stepCache[k];
      rows.push({ cells: ['step_cache', k, typeof v === 'object' ? JSON.stringify(v) : v] });
    });
    var cs = s.caches || {};
    Object.keys(cs).forEach(function (k) {
      var v = cs[k];
      rows.push({ cells: ['cache', k, typeof v === 'object' ? JSON.stringify(v) : v] });
    });
    wrap.appendChild(table(['Kind', 'Name', 'Stats'], rows));
    var tag = el('input', { id: 'evict-tag', placeholder: 'tag' });
    wrap.appendChild(el('div', { class: 'inline' }, [tag, el('button', { class: 'danger', text: 'Evict tag', onclick: function () {
      var t = tag.value.trim();
      if (!t) return;
      if (!confirm('Evict cache entries tagged "' + t + '"?')) return;
      api('POST', '/admin/cache/evict?tag=' + encodeURIComponent(t)).then(function (r) {
        $('banner').textContent = r.ok ? '' : 'Evict failed: ' + errMsg(r);
        if (r.ok) alert('Evicted tag ' + t);
        refresh();
      }).catch(fail);
    } })]));
    return wrap;
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

  function scopeList(scopes) { return (scopes || []).join(', '); }
  function tokensTab(s) {
    var wrap = el('div'), t = state.tokens;
    wrap.appendChild(el('div', { class: 'inline' }, [
      el('button', { text: '+ Issue token', onclick: openIssue }),
      el('label', {}, [el('input', Object.assign({ type: 'checkbox', id: 'show-revoked' }, state.showRevoked ? { checked: 'checked' } : {})), ' show revoked'])
    ]));
    wrap.querySelector('#show-revoked').addEventListener('change', function (e) { state.showRevoked = e.target.checked; render(); });
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
  function revoke(id) {
    if (!confirm('Revoke token "' + id + '"? It stops working immediately.')) return;
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
      $('secret-value').textContent = r.body.token;
      $('secret-dialog').showModal();
      refresh();
    }).catch(fail);
  }
  function closeSecret() { $('secret-value').textContent = ''; $('secret-dialog').close(); }
  function copySecret() {
    var v = $('secret-value').textContent;
    if (navigator.clipboard) navigator.clipboard.writeText(v).then(function () { $('secret-copy').textContent = 'Copied'; });
  }

  // ---- boot ----
  function enter() {
    $('login').hidden = true;
    $('app').hidden = false;
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
  document.addEventListener('visibilitychange', function () { if (!document.hidden && getToken()) refresh(); });

  if (getToken()) enter(); else logout('');
})();
