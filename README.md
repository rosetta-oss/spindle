# Spindle

Spindle runs small data flows on request and publishes each one as an HTTP API.

- **Steps:** each step runs **SQL** against a database or calls an **HTTP API**, and the two can be mixed.
- **Data between steps:** a step reads earlier results through data paths (`[:order :customer_id]`).
- **Conditions:** a step can run only when a condition holds (`:when [:exists [:cust]]`).
- **Output:** the results are projected and merged into one output, with derived fields and checks (`:derive`, `:assert`).
- **Protection:** every connection has a pool, a circuit breaker, a bulkhead, a rate limit and a step cache,
  and every request has a deadline that really cancels statements.
- **Operations:** the configuration is hot-reloaded. Nodes are stateless, so you can run as many as you like.

Spindle is built for semantic or graph layers, for example Semalith with Pathom3, that need clean, batchable,
cacheable APIs in front of legacy databases (Sybase ASE) and heavy upstream APIs.

## Positioning

> **A data-access instruction set for legacy databases (Sybase ASE first): hand-written SQL and API calls,
> composed into small, checked flows, published as APIs for a semantic graph (Semalith).**

- **One business object per flow, fetched close to its source.** Composing objects (customer → orders → shipments)
  is the graph's job.
- **Written by people or agents, guaranteed by the engine.** Flows are data that the engine checks before they go
  live: closed schemas, a timeout and a row cap on every SQL step, an archived explain, `:assert` on the result.
  Tools such as database MCP servers let an agent *call* SQL; Spindle lets an agent *author* a checked API that
  graphs and people then call.
- **Spindle is not:**
  - **an API gateway.** Authentication, routing and rate limits go only as far as Spindle itself needs.
  - **a workflow engine.** Flows are synchronous, have a deadline and at most 16 steps. Anything that waits,
    retries over time or runs on a schedule belongs in an asynchronous engine.
  - **automatic CRUD.** Spindle never generates APIs from table definitions. It publishes only flows that were
    written and checked.
- **Writes are small and local.** A mutation endpoint does a synchronous write on one connection, in one
  transaction (for example a comment or a status). Writes that span systems, need retries over time or need
  compensation belong in an asynchronous workflow engine.
- **Proof before features.** The first release succeeds when one real graph gets fewer upstream calls, a lower
  p95 latency and fewer ASE lock waits. Until that is measured, only fixes that ASE verification needs are added.
  Open source is a by-product of a clean design, not a goal: the users are the graph and its team.

- Licence: **EPL-2.0**.
- Design: [docs/design/0001-spindle-design.md](docs/design/0001-spindle-design.md). The section "First release" covers the current scope.
- Patterns: [docs/guides/cookbook.md](docs/guides/cookbook.md).

## Status

**Target:** one real Semalith graph fetches through Spindle, with fewer upstream calls, lower p95 latency and fewer ASE lock waits.

| First-release item | State |
|---|---|
| `:sql` steps; `IN` lists expanded and chunked; SQL runs exactly as written | ✅ |
| `:api` steps (`:http` alias): data-path path/query/body/headers, list styles, per-key fan-out | ✅ |
| `:when` conditions in the `[:op …]` language (`[:exists p] [:= p v] [:> p v] [:all …]`; map form still loads), stable `:default` for skipped steps | ✅ |
| Strict loading: closed flow/step/output/endpoint maps, `:spindle/format 1`, missing-column and multi-match `:merge` errors | ✅ |
| Result stage: `:derive` and `:assert` (`[:op …]`), `:on-fail :flag\|:error`, `?trace=true` (scope `spindle:trace`) | ✅ |
| `:views`: several projections of one set of steps, each running only what it reads | ✅ |
| Projection: several endpoints share one heavy upstream call (step cache stores the body; `:rows` applied after) | ✅ |
| Step cache: TTL per step, single-flight, stale-while-revalidate, stale on failure, `:per :caller`, tags, weight bound | ✅ |
| Circuit breaker (diehard), bulkhead, rate limit per connection; deadline → `Statement.cancel` | ✅ (PostgreSQL verified) |
| End-user identity passthrough (`X-Spindle-User`, `X-Spindle-User-Claims`, scope `spindle:delegate`) | ✅ |
| Publishing checks: timeout and row cap required; explain on reload, archived; broken SQL rejected | ✅ |
| Hot reload: validate everything, atomic swap, pool carry-over/retire, rollback + pin | ✅ |
| API keys with scopes; `/metrics`, `/health/live`, `/health/ready`, `/status` (incl. per-step cache hit counts) | ✅ |
| **Sybase ASE dialect: cancel, timeout, showplan explain** | ⚠️ written; **needs the company ASE**: run `spikes/jconnect-cancel` |
| Batch route `POST /api/_batch/<id>` (cached per key), keyset paging (`:page`), `GET /openapi.json`, slow-step log | ✅ |
| `:proc` steps: OUT parameters, return status, named result sets, cursors; PostgreSQL procedures via `:procedure true` | ✅ (PostgreSQL verified; ASE needs the company server) |
| Writes: `:mutation true` endpoints, one transaction per connection, `:expect`, `:after`, `Idempotency-Key`, audit, `:evicts` | ✅ (PostgreSQL verified) |
| JWT bearer tokens from configured issuers (JWKS) next to API keys | ✅ |
| Parallelism: independent steps overlap; `:fan-out` up to `:concurrency`; `IN` chunks in parallel with `:parallel` | ✅ |

**Still open**, in order:

1. Elasticsearch/OpenSearch (`:search`);
2. composite keys in data paths (`[:lines [:order_id :line_no]]`), expressions as `:in` values, `:examples` with expected results;
3. SCI expressions (in the code, outside the guarantees; prefer `:derive`);
4. MySQL and ClickHouse: the dialects exist and their read, timeout and cancel tests pass, but they are on hold until needed.

Authoring MCP, `:proc`, writes, JWT and parallelism are done; ASE behaviour of `:proc` and writes still needs the company server.

## Requirements

- **JDK 21+** (virtual threads), for example Temurin 21.
- **Clojure CLI** 1.12+ (`clojure`), for development and tests.
- **Docker** is optional, for the container image.

## Run the tests

```sh
clojure -M:test
```

This needs no services. It runs:

- the unit tests;
- SQLite end-to-end tests: hot reload, rejection, rollback, caching;
- the **first-release scenarios** in `test/spindle/scenarios_test.clj`, against an in-process stub API:
  - projection with one shared upstream call (sequential and concurrent);
  - a conditional API step;
  - stale serving when the upstream fails;
  - per-caller identity and caching;
  - the publishing checks.

Integration tests run only when their environment variables are set:

| Suite | Environment variables |
|---|---|
| PostgreSQL (AdventureWorks) + its PostgREST API | `AW_DB_HOST AW_DB_PORT AW_DB_USER AW_DB_PASSWORD` and optionally `ERP_API_URL ERP_API_TOKEN` |
| MySQL (on hold) | `SPINDLE_IT_MYSQL_URL SPINDLE_IT_MYSQL_USER SPINDLE_IT_MYSQL_PASSWORD` (MariaDB driver + MySQL 8: add `?allowPublicKeyRetrieval=true`) |
| ClickHouse (on hold) | `SPINDLE_IT_CH_URL SPINDLE_IT_CH_USER SPINDLE_IT_CH_PASSWORD` |
| PostgreSQL, read-write (`:proc`, writes, parallel chunks): **a throwaway database**, schema `spindle_it` is dropped and recreated | `SPINDLE_IT_PG_URL SPINDLE_IT_PG_USER SPINDLE_IT_PG_PASSWORD` |

AdventureWorks is Microsoft's MIT-licensed sample database (PostgreSQL port: `lorint/AdventureWorks-for-Postgres`).
The examples in `examples/adventureworks/config/` use it.

## Taking it over in the company environment

### 1. Sybase ASE driver

jConnect is SAP's proprietary JDBC driver. Spindle never bundles it.

1. Copy jConnect 16 (`jconn4.jar`, from the ASE installer or SDK) to `drivers/jconn4.jar`. Files in `drivers/` are git-ignored.
2. `bin/spindle` and the container image put `drivers/*.jar` on the classpath.

### 2. Verify cancellation on ASE first

Spindle's timeouts depend on this, so run it before anything else:

```sh
export ASE_URL='jdbc:sybase:Tds:<host>:<port>/<database>'
export ASE_USER='<login>'
export ASE_PASSWORD="$(cat /path/to/secret)"
cd spikes/jconnect-cancel && clojure -M -m jconnect-cancel
```

The test runs `waitfor delay "00:00:30"` and cancels it after 5 s in four ways. A pass means:

- each test takes about 5 s;
- the spid is idle afterwards (`AWAITING COMMAND`);
- the connection is still usable.

See [spikes/jconnect-cancel/README.md](spikes/jconnect-cancel/README.md). Paste the printed results into design doc §6.3.

### 3. Configure a connection

Create `config/connections.edn`. Secrets are always `${ENV}` or `${file:/path}`; literal secrets are rejected.

```clojure
{:erp {:type :sql
       :dialect :sybase-ase
       :jdbc-url "jdbc:sybase:Tds:${ASE_SERVER}:${ASE_PORT}/${ASE_DB}"
       :username "${ASE_USER}" :password "${ASE_PASSWORD}"
       :zone "UTC"                                   ; ASE datetime has no zone; set the server's zone
       :properties {"LANGUAGE" "us_english"}         ; any jConnect property
       :pool {:max 6}
       :defaults {:timeout "5s" :max-rows 5000}       ; both are mandatory for every SQL step
       :guard {:concurrency 4 :queue-wait "500ms" :rate "100/s"
               :breaker {:failure-ratio [10 20] :open "30s" :half-open-calls 3}}}
 :orders_api {:type :http
              :base-url "${ORDERS_API_URL}"
              :auth {:header "Authorization" :value "Bearer ${ORDERS_API_TOKEN}"}
              :forward-caller {"X-User-Id" [:caller :sub]}   ; optional: pass the end user upstream
              :defaults {:timeout "3s"}
              :guard {:concurrency 8 :breaker {:failure-ratio [5 10] :open "20s"}}}}
```

**Isolation:** for data where dirty reads are acceptable, add `:isolation :read-uncommitted` to `:defaults` or to a step's
`:tuning`. You can also write `at isolation read uncommitted` in the SQL. Spindle tags its connections
`APPLICATIONNAME=spindle`, so DBAs can find them in `sp_who` and `sysprocesses`.

### 4. Keys and identity

`config/auth.edn` stores **hashes** of API keys:

```clojure
{:api-keys [{:id "semalith" :sha256 "${SEMALITH_KEY_SHA256}" :scopes #{"orders:read" "spindle:delegate"}}
            {:id "ops" :sha256 "${OPS_KEY_SHA256}" :scopes #{"spindle:admin"}}]}
```

To create a key and its hash:

```sh
KEY=$(openssl rand -hex 24); printf %s "$KEY" | shasum -a 256
```

How a caller identifies itself and its end user:

- **The calling service** sends `Authorization: Bearer <key>`.
- **The end user**, when the service acts for one, goes in these headers. The key must hold `spindle:delegate`:
  - `X-Spindle-User: <user id>`
  - `X-Spindle-User-Claims: <base64url JSON>`, for example `{"territory_id": 4}`.
- **Inside flows** the user is available as `[:caller :sub]` and `[:caller :claims :territory_id]`. Steps that use it are cached
  per user automatically.

### 5. Write flows

Write flows under `config/flows/<ns>/<name>.edn`, with SQL in `<name>.sql` blocks (`-- :name <step-id>`).
Start from the patterns in [docs/guides/cookbook.md](docs/guides/cookbook.md): projection, conditional enrichment,
batch, archive fallback, per-user data, degrade on failure.

Add `:examples [{:id 12345}]` to a flow so that the mandatory explain binds realistic values.
Plans are archived under `SPINDLE_EXPLAIN_DIR`.

### 6. Run

```sh
clojure -T:build uber                                  # → target/spindle.jar
SPINDLE_CONFIG_DIR=config bin/spindle                   # adds drivers/*.jar
# or: docker build -t spindle . && docker run -p 8080:8080 \
#       -v $PWD/config:/opt/spindle/config -v $PWD/drivers:/opt/spindle/drivers \
#       -v $PWD/explain-archive:/opt/spindle/explain-archive -e SPINDLE_EXPLAIN_DIR=/opt/spindle/explain-archive spindle
```

| Variable | Default | Meaning |
|---|---|---|
| `SPINDLE_CONFIG_DIR` | `config` | config directory, polled for changes |
| `SPINDLE_PORT` | `8080` | HTTP port |
| `SPINDLE_NODE_ID` | hostname | shown in `/status` |
| `SPINDLE_POLL_MS` | `5000` | config poll interval |
| `SPINDLE_EXPLAIN_DIR` | `explain-archive` | where plans are archived on publish |
| `SPINDLE_EXPLAIN` | on | `off` disables the explain check (not recommended) |
| `SPINDLE_STEP_CACHE_MB` | `256` | step-cache weight bound per node |
| `SPINDLE_DB` | `data/spindle.db` | token store (SQLite); must not be inside the config directory |
| `SPINDLE_TRUST_FORWARDED_PROTO` | `true` | trust `X-Forwarded-Proto: https` from the reverse proxy |
| `SPINDLE_SLOW_MS` | `1000` | slow-step threshold (logger `spindle.slow`); per step or connection: `:tuning :slow` |

**Operations:**

- `GET /status` shows:
  - the config version, its explain summary and the last reload result (details for admin keys only);
  - for every connection, the breaker, bulkhead, pool and last error;
  - hit, miss and stale counts per step.
- Admin endpoints (`spindle:admin` scope):
  - `POST /admin/reload`
  - `POST /admin/config/rollback?to=<version>` (pins the node)
  - `POST /admin/config/unpin`
  - `POST /admin/cache/evict?tag=<tag>`
- **Several nodes:** point every node at the same config source (a git checkout or a shared directory). Each node
  reports the version it is running. Each node has its own cache, so nodes can differ for at most the TTL.

## Console, tokens and MCP

- **Token store:** opened at start from `SPINDLE_DB` (default `data/spindle.db`, relative to the working
  directory). Spindle refuses to start when this path is inside `SPINDLE_CONFIG_DIR`, so a config rollback can
  never bring back a revoked token. Back up `data/`; it is git-ignored.
- **Console** (`/console`, admin scope): issue tokens (shown once), list and revoke them. Keys in `auth.edn` show
  read-only. Store tokens keep working through failed reloads and rollbacks.
- **Scopes:**
  - `spindle:admin` (or the `:admin-scope` in `auth.edn`): console, `/admin/*`, all MCP tools;
  - `spindle:author`: the authoring MCP (introspection, `validate_flow`, `test_run`, `explain`, `publish`);
  - `spindle:delegate`: may be put on store tokens, but is a separate, unchecked-by-default box with a warning;
  - flow scopes (e.g. `sales:read`): call endpoints.
- **Authoring MCP** at `/mcp` (Streamable HTTP). Add it to Claude Code with an author token:

  ```bash
  claude mcp add --transport http spindle https://<host>/mcp --header "Authorization: Bearer <token>"
  ```

  `publish` validates first, writes `flows/<id>.edn` (+ `.sql`) atomically, reloads, and restores the previous
  files if the reload fails. Publish, `POST /admin/reload`, rollback and the file watcher share one lock.
  Publish is refused (`node is pinned`) after a rollback; `POST /admin/config/unpin` first. Every publish is
  logged as `event=flow-published flow=<id> by=<token id> version=<v>`.
  Limits: one request body at most 1 MiB (413), 300 requests per minute per token (429 with `Retry-After`).
  Only Streamable HTTP is served; the legacy `/mcp/sse` transport answers 404.
- **HTTPS:** `/console`, `/mcp` and `/admin/tokens*` refuse plain HTTP (403) unless the request comes from
  loopback or carries `X-Forwarded-Proto: https`. The header is **trusted by default**; deployments reachable
  without the proxy must set `SPINDLE_TRUST_FORWARDED_PROTO=false`, so clients cannot set that header themselves. Note that loopback is trusted:
  a reverse proxy on the same host that forwards **plain HTTP** from loopback passes the guard too, so make sure
  such a proxy only accepts HTTPS from the outside.

## Databases and drivers

| Database | `:dialect` | Driver | Bundled |
|---|---|---|---|
| Sybase ASE | `:sybase-ase` | SAP jConnect 16 (proprietary) | **no**: `drivers/jconn4.jar` |
| PostgreSQL | `:postgres` | pgjdbc (BSD-2) | yes |
| MySQL / MariaDB (on hold) | `:mysql` | MariaDB Connector/J (LGPL-2.1) or MySQL Connector/J (GPL-2.0 + FOSS exception) | **no**: `drivers/` |
| ClickHouse (on hold) | `:clickhouse` | clickhouse-jdbc 0.9 `all` (Apache-2.0) | yes. Spindle adds `compress=false`: 0.9 cannot read ClickHouse 26's compressed responses, and `compress=0` hangs |

## Development notes

Source files in `src/spindle/`:

| Area | Files |
|---|---|
| Configuration | `config.clj` (load, validate, snapshot, reload), `explain.clj` (mandatory explain archive) |
| Flows | `flow.clj` (compile and run, `:when`, `:views`, step cache use), `executor.clj` (DAG scheduler: promesa + virtual threads), `par.clj` |
| Steps | `sql.clj` (`:sql`, `:proc`), `httpstep.clj`, `stepcache.clj` |
| Result | `output.clj` (merge, nest, select), `result.clj` (`[:op …]` derive, assert, conditions), `expr.clj` (SCI) |
| Writes | `tx.clj` (one transaction per connection), `mutation.clj` (idempotency, audit, eviction) |
| HTTP | `server.clj` (endpoints, batch, paging, trace), `openapi.clj`, `jsonout.clj` |
| Infrastructure | `conn.clj` (pools and guards), `dialect.clj`, `auth.clj` (keys and identity), `jwt.clj`, `metrics.clj` |

**Rules:**

- SQL runs exactly as written.
- Driver and upstream messages never reach callers.
- No secrets in config files.

**Next steps:**

1. Run `spikes/jconnect-cancel` on the company ASE. Fix the `:sybase-ase` dialect if needed, and record the results in design §6.3.
2. Write the first real flows for the target Semalith graph. Measure upstream calls, p95 latency and ASE lock waits before and after.
3. Add the batch route `POST /api/_batch/<endpoint>` with a per-key cache, keyset paging, OpenAPI 3.1 and the slow-query log.
4. Add `:proc` steps for ASE stored procedures: several result sets, OUT parameters, return status.

## Licence

Copyright the Spindle contributors. Distributed under the Eclipse Public License 2.0 (see `LICENSE`).
Third-party drivers placed in `drivers/` keep their own licences.
