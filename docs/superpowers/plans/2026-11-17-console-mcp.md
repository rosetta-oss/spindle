# Admin Console, Token Store and Authoring MCP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give admins a `/console` page to monitor one Spindle node and issue/revoke tokens kept in SQLite, and give token holders an authoring MCP at `/mcp` to define flows and APIs.

**Architecture:** A `spindle.tokens` store (SQLite, behind a `TokenStore` protocol) is opened in `main` and passed to `server/handler`; `auth/authenticate` checks `auth.edn` keys then the store. New admin JSON endpoints manage tokens; a static page in `resources/console/` calls them and the existing `/status` and `/admin/*`. `spindle.mcp` mounts mcp-shuttle's `routes` and implements the §19 tools on top of `config/build`.

**Tech Stack:** Clojure 1.12, Jetty (ring-jetty9-adapter), next.jdbc, `org.xerial/sqlite-jdbc`, mcp-shuttle v0.1.0, malli, plain HTML/JS/CSS.

**Spec:** `docs/superpowers/specs/2026-11-17-console-mcp-design.md`

## Global Constraints

- mcp-shuttle dep: `io.github.rosetta-oss/mcp-shuttle {:git/tag "v0.1.0" :git/sha "1d5aa1b"}`.
- `org.xerial/sqlite-jdbc {:mvn/version "3.46.1.3"}` moves from `:test` into main `:deps`.
- `SPINDLE_DB` default `data/spindle.db`, relative to the working directory; refuse a path inside the config directory.
- Token format: `spk_` + base64url (no padding) of 32 `SecureRandom` bytes. Store only lower-case SHA-256 hex.
- Admin scope = `(get-in snap [:auth :admin-scope])` (default `"spindle:admin"`). New scope `"spindle:author"`: MCP only.
- Store lookups cached ≤ 5 s (revocation effective within 5 s); `last_used_at` written at most once a minute per token.
- Clear token appears only in the `POST /admin/tokens` response. Never logged.
- `/console`, `/mcp`, `/admin/tokens*` refuse plain HTTP (403) unless remote addr is loopback or `X-Forwarded-Proto: https` (trusted unless `SPINDLE_TRUST_FORWARDED_PROTO=false`).
- Console: no inline script; header `Content-Security-Policy: default-src 'self'`; token in `sessionStorage`; poll `/status` every 5 s while visible.
- Publish, `/admin/reload`, rollback share one process-wide lock. Publish refused while pinned: `{:error "node is pinned"}`.
- `test_run` never reads or fills step caches. MCP never returns row data from `describe_*` nor secrets/JDBC URLs from `list_connections`.
- Existing `auth.edn` keys, `/status`, `/admin/*` keep working unchanged.
- Run tests on this machine with `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=Windows-ROOT clojure -M:test`.

## Review Focus

1. **Revoked or expired token used within the 5 s cache window, then after it** → works only until the window ends; refused afterwards. (Task 1 test `revocation-within-cache-window`.)
2. **Author token calling `/admin/reload`, `/admin/tokens`, or `/console` APIs** → 403, never a side effect. (Task 3 test `author-cannot-admin`.)
3. **Publish of a flow whose reload fails (bad SQL, duplicate path)** → previous files restored byte-for-byte, running config unchanged. (Task 7 test `publish-restores-on-failure`.)
4. **Flow id with path tricks (`../x`, `a\\b`, absolute, empty)** in `get_flow`/`validate_flow`/`publish` → refused with an error, nothing written outside `flows/`. (Task 6 test `flow-id-rejects-traversal`.)
5. **Issuing a token whose id equals an `auth.edn` key or a revoked token** → 409, no row written. (Task 3 test `issue-rejects-taken-ids`.)

---

### Task 1: Token store

**Files:**
- Create: `src/spindle/tokens.clj`
- Modify: `deps.edn` (move sqlite-jdbc to `:deps`)
- Test: `test/spindle/tokens_test.clj`

**Interfaces:**
- Produces:
  - `(defprotocol TokenStore (issue! [s {:keys [id scopes expires-at created-by]}]) (revoke! [s id]) (lookup [s sha256-hex]) (list-tokens [s]))`
  - `(open-sqlite path config-dir) → TokenStore` — creates parent dirs and table `tokens(id text primary key, sha256 text unique not null, scopes text not null, created_at text not null, created_by text, expires_at text, last_used_at text, revoked_at text)`; throws `ex-info` with `{:spindle/code :config}` if `path` is inside `config-dir`.
  - `issue!` → `{:id :token :scopes :created_at :expires_at}` (`:token` clear, once); throws `ex-info {:conflict true}` when id exists (revoked included).
  - `revoke!` → `true` if a live row was revoked, else `false`.
  - `lookup` → `{:id :scopes #{..}}` for a live, unexpired token, else nil. Cached 5 s per hash (nil results cached too); touches `last_used_at` ≤ once/min.
  - `list-tokens` → vector of maps without `sha256`, with `:revoked_at`, `:last_used_at`.
  - `(new-token) → "spk_…"`.

- [ ] **Step 1: Write failing tests** in `tokens_test.clj` (store on a temp file):
  - `issue-and-lookup`: `(:token r)` starts with `"spk_"`, length 47; `(lookup s (auth/sha256-hex (:token r)))` = `{:id "a" :scopes #{"spindle:author"}}`; the DB file contains no clear token (`(not (str/includes? (slurp-bytes-as-latin1 f) (:token r)))`).
  - `duplicate-id-conflicts`: second `issue!` with id `"a"` throws with `(:conflict (ex-data e))`; also after `revoke!`.
  - `expired-token-refused`: `:expires-at` one second in the past → `lookup` nil.
  - `revocation-within-cache-window`: lookup, revoke, lookup again → still found; with the cache clock advanced 5 s (inject `:now-ms` fn via `open-sqlite` opts) → nil.
  - `db-inside-config-dir-refused`: `(open-sqlite "cfg/data.db" "cfg")` throws.
  - `list-hides-hashes`: no `:sha256` key in any listed map.
- [ ] **Step 2: Run** `clojure -M:test -n spindle.tokens-test` → FAIL (namespace missing).
- [ ] **Step 3: Implement** `spindle.tokens` with next.jdbc on `jdbc:sqlite:<path>`; scopes stored as JSON (jsonista); timestamps ISO-8601 UTC strings; cache is a Caffeine cache with `expireAfterWrite` 5 s driven by an injectable ticker (`open-sqlite path config-dir {:now-ms f}` 3-arity). Move sqlite-jdbc in `deps.edn`.
- [ ] **Step 4: Run** the namespace → PASS; run full suite → still 0 failures.
- [ ] **Step 5: Commit** `feat(tokens): SQLite token store`.

### Task 2: Authentication with the store, HTTPS guard

**Files:**
- Modify: `src/spindle/auth.clj` (`authenticate`), `src/spindle/server.clj:93,160-162` (call sites, `handler` opts)
- Create: `src/spindle/web.clj` (HTTPS guard + shared JSON helpers if needed)
- Test: `test/spindle/auth_store_test.clj`

**Interfaces:**
- Consumes: `tokens/lookup`.
- Produces:
  - `(auth/authenticate auth store req)` — `auth.edn` keys first, then `(lookup store h)` when `store` non-nil; returns same caller shape `{:client :id :scopes}` (+ end user). 2-arity `(authenticate auth req)` kept, = store nil.
  - `server/handler` accepts `{:node-id :config-dir :store}`; `admin?` uses the store.
  - `(web/secure? req) → boolean` — true if `:remote-addr` is `127.0.0.1`/`0:0:0:0:0:0:0:1`/`::1`, or `(:scheme req)` is `:https`, or header `x-forwarded-proto` = `"https"` and `SPINDLE_TRUST_FORWARDED_PROTO` ≠ `"false"`.
  - `(web/insecure-resp) → {:status 403 …}` JSON `{:error {:code "https_required" :message "use HTTPS"}}`.

- [ ] **Step 1: Failing tests:** `store-token-authenticates` (store token with `sales:read` calls a SQLite e2e endpoint → 200); `store-admin-uses-configured-scope`: `auth.edn` `:admin-scope "ops:admin"`, store token with `"ops:admin"` → `/status` admin view (has `:problems` key in last_reload when present) and token with `"spindle:admin"` → not admin; `secure?` table test over the five cases above.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement.** Store tokens bypass the config snapshot, so they work with `snap` nil-safe `(:auth snap)`.
- [ ] **Step 4: Run** new tests + full suite → PASS.
- [ ] **Step 5: Commit** `feat(auth): store tokens, HTTPS guard`.

### Task 3: Token admin endpoints

**Files:**
- Modify: `src/spindle/server.clj` (admin `case`; HTTPS guard on `/admin/tokens*`)
- Test: `test/spindle/console_api_test.clj`

**Interfaces:**
- Consumes: Task 1 store, Task 2 `admin?`, `web/secure?`.
- Produces:
  - `GET /admin/tokens` → `{:tokens [...store rows...] :config_keys [{:id :scopes :read_only true}] :admin_scope "<configured>"}`
  - `POST /admin/tokens` JSON `{id, scopes[], expires_at?}` → 201 `{:id :token :scopes :expires_at}`; 409 `{:error {:code "conflict"}}` when id is taken in store or `auth.edn`; 400 on missing id/empty scopes or id not matching `^[a-z0-9][a-z0-9._-]{1,62}$`. `created_by` = caller id. Log `event=token-issued id=… by=…` (no token).
  - `DELETE /admin/tokens/<id>` → 200 `{:revoked true}`; 404 if no live store token (incl. `auth.edn` ids). Log `event=token-revoked`.

- [ ] **Step 1: Failing tests** (SQLite e2e config + temp store, plain ring requests with `:remote-addr "127.0.0.1"`):
  - `issue-list-revoke`: issue → 201 with token; new token reaches its endpoint; list shows it without token/hash; revoke → 200; after advancing store clock 5 s the token gets 401.
  - `issue-rejects-taken-ids`: id equal to an `auth.edn` key → 409; id of a revoked token → 409; nothing new in list.
  - `author-cannot-admin`: author token on `POST /admin/reload`, `GET /admin/tokens`, `POST /admin/tokens` → 403; config version unchanged.
  - `tokens-need-https`: `:remote-addr "10.0.0.5"`, no forwarded header → 403 `https_required`; with `x-forwarded-proto: https` → 200.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(admin): token endpoints`.

### Task 4: Console page

**Files:**
- Create: `resources/console/index.html`, `resources/console/console.js`, `resources/console/console.css`
- Modify: `src/spindle/server.clj` (serve `/console`, `/console/` → `index.html`, `/console/<file>` from classpath `console/`; HTTPS guard; CSP header; `Cache-Control: no-store`)
- Test: `test/spindle/console_page_test.clj`

**Interfaces:**
- Consumes: `/status`, `/admin/reload`, `/admin/config/rollback?to=`, `/admin/config/unpin`, `/admin/cache/evict?tag=`, `/admin/tokens` (GET/POST/DELETE).
- Produces: UI per spec §5: login box (token → `sessionStorage["spindle.token"]`); header with node, config version, pinned badge, Reload, Log out; tabs Overview | Connections | Endpoints | Caches | Config | Tokens; issue dialog with checkboxes author / admin (configured name from `admin_scope`) / delegate (unchecked, warning text "Lets this token act for end users"), free-text scopes, optional expiry; token shown once with Copy; confirms on rollback, revoke, evict. Any 401/403 → back to login. Polling stops when `document.hidden`.

- [ ] **Step 1: Failing tests:** `GET /console` → 200 `text/html` with CSP `default-src 'self'`; `index.html` has no `<script>` without `src` and no `on…=` attributes (regex); `/console/console.js` → 200 `text/javascript`; `/console/../auth.edn` → 404; non-local without HTTPS → 403.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** page + serving (classpath `io/resource`, reject any path segment `..`).
- [ ] **Step 4: Run** → PASS. Then start Spindle on the SQLite example config with a `.claude/launch.json` entry and verify in the browser pane: login, every tab renders `/status`, issue + revoke a token, reload button; screenshot.
- [ ] **Step 5: Commit** `feat(console): admin web console`.

### Task 5: MCP mount and read-only tools

**Files:**
- Modify: `deps.edn` (mcp-shuttle)
- Create: `src/spindle/mcp.clj`, `src/spindle/mcp/introspect.clj`
- Modify: `src/spindle/server.clj` (try `(mcp/routes …)` before admin/endpoint routing, behind HTTPS guard)
- Test: `test/spindle/mcp_test.clj` (Jetty on port 0 + `shuttle.client`)

**Interfaces:**
- Consumes: `auth/authenticate` with store; `config/current`.
- Produces:
  - `(spindle.mcp/handler {:store :config-dir}) → ring handler or nil` built with `shuttle.http/routes` + `shuttle.tools/deps`; `:authenticate` → caller if it has `spindle:author` or the admin scope, else nil (401 message "Send a Spindle author or admin token as a Bearer token."); `:server-info {:name "spindle" :version <spindle version>}`.
  - Tools (malli input schemas):
    - `list_connections []` → `[{:id :kind :dialect :breaker :defaults}]` (no `:jdbc-url`, `:username`, `:password`, `:headers`, `:base-url` query secrets).
    - `describe_table [:map [:conn :string] [:table :string]]` → `{:columns [{:name :type :nullable :size}] :primary_key [..] :indexes [{:name :columns :unique}]}` via `DatabaseMetaData` (`table` may be `schema.table`); `{:error "no such table"}` when empty.
    - `describe_proc [:map [:conn :string] [:proc :string]]` → `{:params [{:name :type :mode}]}` via `getProcedureColumns`; refuses non-SQL conns.
    - `list_flows []` → `[{:id :version :endpoint {:method :path :scopes}}]`.
    - `get_flow [:map [:id :string]]` → `{:id :version :edn <text> :sql <text or nil>}` from `(:tree snap)`.
  - `(spindle.mcp/valid-flow-id? s) → boolean`: `^[a-z0-9][a-z0-9_-]*(/[a-z0-9][a-z0-9_-]*)*$`, ≤ 128 chars.

- [ ] **Step 1: Failing tests:** `tools-hidden-without-scope` (token with only `sales:read` → 401); `author-sees-tools` (list-tools names = the 9 tools of Tasks 5–7, once those exist — here assert ⊇ the 5 above); `list-connections-no-secrets` (`pr-str` of result contains neither the password nor `jdbc:`); `describe-table-sqlite` (example SQLite table → known column names, no row values); `get-flow-roundtrip` (text equals the file).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(mcp): mount authoring MCP with read-only tools`.

### Task 6: validate_flow, test_run, explain

**Files:**
- Modify: `src/spindle/mcp.clj`; Create: `src/spindle/mcp/trial.clj`
- Modify: `src/spindle/config.clj` (extract nothing new unless needed; reuse `build`)
- Test: `test/spindle/mcp_trial_test.clj`

**Interfaces:**
- Consumes: `config/build tree prev`, `config/current`, `server/run-endpoint` (or the executor it calls), `sql/explain!`.
- Produces:
  - `(trial/candidate-tree snap id edn sql) → tree` — `(:tree snap)` with `flows/<id>.edn` (+ `.sql`, or the old `.sql` removed when `sql` nil and the flow is new).
  - `(trial/build-candidate snap tree) → {:snapshot s} | {:problems [...]}` — calls `config/build tree snap` with `spindle.explain/*archive-dir*` bound to a temp dir; closes any connection runtime it opened that is not in `snap` (none expected).
  - Tools: `validate_flow {id edn sql?}` → `{:ok true}` or `{:problems [...]}`; `test_run {id edn? sql? params}` → `{:output … :steps [{:id :rows :ms}] :partial [...]}` run against the candidate (or current) snapshot with step caches bypassed (bind/flag the executor's cache off); `explain {id edn? sql? params?}` → `{:steps [{:id :plan}]}` via `sql/explain!` per `:sql` step.

- [ ] **Step 1: Failing tests:** `validate-ok-and-problems` (good flow → ok; flow with `select * from nosuch` → problem mentioning the step; running config version unchanged); `test-run-does-not-touch-cache` (cached endpoint: after `test_run`, `/status` step-cache counts unchanged, explain archive dir unchanged); `explain-sqlite` (plan rows non-empty); `flow-id-rejects-traversal` (`"../x"`, `"a\\b"`, `"/abs"`, `""` → `isError` result; no file created outside `flows/`).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** If the executor has no cache bypass, add a `:no-cache?` option to its context. **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(mcp): validate, test_run, explain`.

### Task 7: publish, shared lock, wiring and docs

**Files:**
- Modify: `src/spindle/mcp.clj`, `src/spindle/config.clj` (`config/write-lock`), `src/spindle/server.clj` (wrap `/admin/reload` and rollback in the lock), `src/spindle/main.clj` (open store from `SPINDLE_DB`, pass `:store`), `README.md` (console, tokens, MCP, env vars), `.gitignore` (`data/`)
- Test: `test/spindle/mcp_publish_test.clj`

**Interfaces:**
- Consumes: Task 6 `trial/*`, `config/reload!`, `config/state`.
- Produces:
  - `config/write-lock` — a `java.util.concurrent.locks.ReentrantLock`; `(config/with-write-lock & body)` macro.
  - Tool `publish {id edn sql?}` → `{:version v :reload {...}}`; `{:error "node is pinned"}` when pinned; validates first (Task 6); writes files via temp file + `Files/move ATOMIC_MOVE`; `reload!`; on failure restores previous bytes (or deletes new files) and returns `{:error "reload failed" :problems [...]}`. Logs `event=flow-published flow=<id> by=<caller id> version=<v>`.

- [ ] **Step 1: Failing tests:** `publish-new-endpoint` (author publishes `it/hello` → new version; endpoint answers 200); `publish-restores-on-failure` (publish a flow duplicating an existing path → error; files byte-equal to before; version unchanged); `publish-refused-when-pinned` (rollback to previous version → publish returns pinned error; no file change); `publish-and-reload-serialised` (two futures publishing different flows → both end in the final config; no exception).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement**, plus `main` wiring and README section "Console, tokens and MCP" (env `SPINDLE_DB`, `SPINDLE_TRUST_FORWARDED_PROTO`, scopes, MCP URL for Claude Code: `claude mcp add --transport http spindle https://<host>/mcp --header "Authorization: Bearer <token>"`).
- [ ] **Step 4: Run** full suite → 0 failures; start the server and connect an MCP client (`shuttle.client`) end-to-end: list → describe → validate → publish → call endpoint.
- [ ] **Step 5: Commit** `feat(mcp): publish; wire store in main; docs`.
