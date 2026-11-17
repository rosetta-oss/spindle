# Admin console, token store and authoring MCP — design

Date: 2026-11-17 · Status: draft for approval

## Intent

Only **admins** use the console. They use it to:

1. **monitor** a Spindle node (config, connections, caches, endpoints);
2. **issue and revoke tokens** that let people — or their AI agents — use the **authoring MCP** to define flows and APIs.

The MCP is design §19, built now on **mcp-shuttle** (`io.github.rosetta-oss/mcp-shuttle`, v0.1.0).

## Goals

- `/console`: one page an admin opens in a browser to see the node's health and run admin actions.
- Tokens kept in a **database**, SQLite first, issued and revoked from the console without editing files or reloading.
- `/mcp`: the authoring tools of §19 for holders of an author token.

## Non-goals (this round)

- More than one node. One node, one SQLite file. (Multi-node needs a shared DB; the store is behind a small protocol so Postgres can come later.)
- Database engines other than SQLite for the token store.
- User accounts, passwords, SSO. Login is a token.
- Charts or history in the console (`/metrics` + Prometheus/Grafana remain the way to see trends).
- Mutation flows in `test_run` (Spindle runs read statements only today).

## Anti-goals

- An author token must never be able to reload, roll back, evict caches or issue tokens.
- A token in clear text is never stored, logged, or shown twice.
- The MCP never returns row data from `describe_*`, never secrets from `list_connections`.
- Nothing that works today (keys in `auth.edn`, `/admin/*`, `/status`) breaks.

## Constraints

- One Clojure process, existing Jetty server and handler (`spindle.server/handler`).
- SQLite driver moves from the `:test` alias into main deps (`org.xerial/sqlite-jdbc`, Apache-2.0).
- mcp-shuttle as a git dep; Java 21+ (already required).
- Config stays files: publishing a flow writes `flows/<id>.edn` (+ `.sql`) into the config directory and reloads.

## Approaches considered (console)

| | Approach | Trade-off |
|---|---|---|
| **A (picked)** | One static HTML + plain JS page in `resources/console/`, served at `/console`, calling JSON endpoints | No new deps, no JS build; every console action is also a scriptable endpoint. Plain look, polls instead of live push. |
| B | Server-rendered pages (hiccup + htmx) | No hand-written JS, but two deps and a cookie login with CSRF to build. |
| C | Separate SPA (React) with its own build | Richest UI; a Node toolchain and second build for one admin on one node. |

Picked **A** (user's choice): one admin, one node; JSON endpoints keep everything testable.

## Design

### 1. Token store (`spindle.tokens`)

- File: `SPINDLE_DB` (default `data/spindle.db`), created with its schema on first start.
- Table `tokens`: `id` (text, unique, human name), `sha256` (hex, unique), `scopes` (JSON array), `created_at`, `created_by` (token id of the issuing admin), `expires_at` (nullable), `last_used_at`, `revoked_at` (nullable).
- **Issue:** 32 random bytes (`SecureRandom`), base64url, prefixed `spk_` so leaks are greppable. Return the clear token once; store only SHA-256.
- **Check:** on each request, hash the presented key; look up in `auth.edn` keys first, then the store (not revoked, not expired). Store hits are cached in memory ≤ 5 s so revocation takes effect within 5 s; `last_used_at` is written at most once a minute per token.
- Behind a protocol `TokenStore` (`issue! revoke! lookup list`) so a Postgres store can be added later.

### 2. Scopes

- `spindle:admin` (existing): console, `/admin/*`, token management, **and** the MCP.
- `spindle:author` (new): the MCP only. Cannot call `/admin/*` or the token endpoints.
- A token may also carry ordinary API scopes (e.g. `sales:read`) so one token can call published endpoints.

### 3. First admin (bootstrap)

An admin key in `auth.edn` (as today) logs into the console. From there an admin can issue more admin tokens into the store. No default credentials ship.

### 4. Admin JSON endpoints (new, admin scope)

| Method + path | Does |
|---|---|
| `GET /admin/tokens` | list store tokens (no hashes) + `auth.edn` keys marked read-only |
| `POST /admin/tokens` `{id, scopes, expires_at?}` | issue; response holds the clear token once |
| `DELETE /admin/tokens/<id>` | revoke (soft: sets `revoked_at`) |

Existing `/status`, `/admin/reload`, `/admin/config/rollback`, `/admin/config/unpin`, `/admin/cache/evict` are reused unchanged.

### 5. Console page (`/console`)

Served as static files from `resources/console/` (`index.html`, `console.js`, `console.css`). No framework, no build.

```
┌ Spindle · node-1 · config 4e1a1b75 (pinned) ──────────── [Reload] [Log out] ┐
│ Overview | Connections | Endpoints | Caches | Config | Tokens               │
├─────────────────────────────────────────────────────────────────────────────┤
│ Connections                                                                  │
│  ase   sql  breaker CLOSED  bulkhead 2/8 (0 queued)  pool 3 active/6  last err —
│  erp   http breaker OPEN    …                                      last err timeout
│ Tokens                                     [+ Issue token]                   │
│  alice-agent  author  created 11-17 by ops  last used 2 min ago   [Revoke]   │
└─────────────────────────────────────────────────────────────────────────────┘
```

- **Login:** paste an admin token; kept in `sessionStorage` (this tab only); sent as `Authorization: Bearer`. A 403 returns to the login box.
- **Overview/Connections/Endpoints/Caches/Config:** render `/status` (admin view), refreshed every 5 s while the tab is visible.
- **Config tab:** last reload result with problems, version history; buttons for Reload, Roll back to a version, Unpin.
- **Caches tab:** step-cache hit counts; evict by tag.
- **Tokens tab:** list, issue (name, scopes as checkboxes: author / admin / free text, optional expiry), show the clear token once with a copy button, revoke with confirm.
- Destructive actions (rollback, revoke, evict) ask for confirmation.
- Served with `Content-Security-Policy: default-src 'self'` and no inline script.

### 6. Authoring MCP (`spindle.mcp`, at `/mcp`)

Mounted with `shuttle.http/routes` before Spindle's own routing. `:authenticate` uses the same token check; the caller sees the tools only with `spindle:author` or `spindle:admin`.

| Tool | Does |
|---|---|
| `list_connections` | id, kind, dialect, health (breaker), defaults; never URLs with credentials or secrets |
| `describe_table` | `{conn, table}` → columns, types, nullability, keys, indexes from JDBC `DatabaseMetaData`; never rows |
| `describe_proc` | `{conn, proc}` → parameters from `getProcedureColumns` |
| `list_flows` / `get_flow` | published flows, endpoints, versions; `get_flow` returns the EDN (+ SQL) text |
| `validate_flow` | `{id, edn, sql?}` → runs the full reload validation on the current config plus this flow, without installing; returns problems with paths |
| `test_run` | `{id, edn, sql?, params}` or `{id, params}` → runs it once in a temporary snapshot; returns output, per-step rows, timings, `meta.partial` |
| `explain` | `{id, edn?, params?}` → plan per SQL step via the dialect |
| `publish` | `{id, edn, sql?}` → validate; write `flows/<id>.edn` (+`.sql`) atomically; reload; on reload failure restore the previous files. Returns new version + reload result |

Every publish is logged (`event=flow-published flow=… by=<token id>`).

### 7. Wiring

- `spindle.main`: open the store; pass it to `server/handler`.
- `spindle.server/handler`: `/console` static files → `/mcp` (shuttle) → `/admin/tokens*` → existing routes. `auth/authenticate` gains the store fallback.

### 8. Decisions settled after review (author's calls)

- **Admin scope name:** the configured `:admin-scope` from `auth.edn` (default `spindle:admin`) is what the console issues and what counts as admin. Changing it in `auth.edn` changes admin rights for store tokens too; the console warns when store tokens hold a scope that is no longer the admin scope.
- **Where the store sits:** opened once in `main`, passed to `server/handler`; `auth/authenticate` takes `(auth-snapshot, store, request)`. Store tokens do not depend on the config snapshot: they keep working through failed reloads and rollbacks.
- **Ids:** one namespace. `issue!` rejects an id already in `auth.edn` or the store (revoked ids stay taken). `DELETE /admin/tokens/<id>` acts on the store only; `auth.edn` keys show read-only.
- **`spindle:delegate`:** allowed on store tokens, but the console shows it as a separate, unchecked-by-default box with a warning.
- **Publish concurrency:** one process-wide lock around publish, `/admin/reload` and rollback. Publish is refused (`{:error "node is pinned"}`) while the node is pinned; unpin first.
- **`test_run`:** never reads or fills step caches; runs on a temporary snapshot; HTTP steps are called (reads only).
- **HTTPS (user's decision):** admins reach Spindle over HTTPS through a reverse proxy. `/console`, `/mcp` and `/admin/tokens*` refuse plain HTTP (403 with a message) unless the request comes from localhost or carries `X-Forwarded-Proto: https`. `SPINDLE_TRUST_FORWARDED_PROTO` (default true) can switch off trusting that header.
- **DB location:** `SPINDLE_DB` defaults to `data/spindle.db` relative to the working directory, and the builder must refuse a path inside the config directory, so config rollback can never bring back a revoked token.

## Left to the builder

- Exact SQL schema DDL and migrations approach (single `create table if not exists` is fine for v1).
- Console styling.
- How `validate_flow`/`test_run` build a temporary snapshot from `config/build` (reuse, don't duplicate validation).
- Test layout: unit tests for the store and scope checks, an e2e test on SQLite for console endpoints and every MCP tool via `shuttle.client`.
