# Spindle — small data flows published as APIs

Status: **revision 6** (2026-11-17). The decisions are recorded here and implementation has started
(v0.2-dev; see the README for what works today).
Licence: **EPL-2.0**. Open source.

## Decision log

| # | Decision |
|---|---|
| D1 | Spindle runs synchronous, request-time flows. Steps may be SQL or HTTP, mixed. It is not a workflow engine, a semantic planner or ETL. |
| D2 | Concepts: Connection (`:sql`/`:http`), Step, Flow, Endpoint. Flows are EDN plus `.sql` files, and steps refer to each other through **data paths**. |
| D3 | SQL runs as written. Spindle only binds parameters, expands and chunks `IN` lists, and adds a leading tag comment. |
| D4 | Engine: Spindle's own scheduler + promesa + virtual threads. A cancelled promise triggers `Statement.cancel`, verified on PostgreSQL, MySQL and ClickHouse. jConnect is still to be verified on a company ASE (`spikes/jconnect-cancel`). |
| D5 | Every connection has a diehard circuit breaker, a bulkhead and a rate limiter. When the breaker is open, Spindle fails fast or serves stale cached data, marked as stale. |
| D6 | Monitoring (`/metrics`, `/health/*`, `/status`), multiple nodes (stateless, no leader, local cache) and hot reload (validate, then swap atomically; the old version finishes in-flight requests) are required. |
| D7 | Dialects: first release `sybase-ase` + `postgres` (+ HTTP APIs). `mysql` and `clickhouse` are already written and tested (read, timeout, cancel) but are not developed further until Semalith meets a source it cannot read itself. A dialect only covers what Spindle adds around the author's SQL (§10.1). |
| D8 | Bundled drivers: PostgreSQL (BSD-2) and clickhouse-jdbc (Apache-2.0). Users supply MySQL drivers (GPL+FOSS exception or LGPL) and jConnect (proprietary) through `drivers/`. |
| D9 | Writes are supported: declared mutation endpoints, one transaction per connection, no two-phase commit, idempotency keys and an audit record (planned). |
| D10 | SCI expressions are allowed, with an allowlist, no I/O, no loops, and a deadline. |
| D11 | `:proc` steps cover multiple result sets, OUT parameters and the return status (planned). |
| D12 | AI agents write flows through an authoring MCP built on mcp-shuttle. The guide for agents is part of the product (planned). |
| D13 | Callers authenticate with API keys (done) and JWT (planned), with scopes per endpoint. |
| D14 | Elasticsearch/OpenSearch is supported through a `:search` step over HTTP, not JDBC (§5.6, planned after the PostgreSQL main line). |
| D15 | Development and integration tests run on PostgreSQL AdventureWorks, MySQL 8.4 and ClickHouse 26 containers. ASE is tested only on the company test server. |

## First release (scope agreed 2026-11-17)

**Goal.** One real Semalith graph in the company fetches its data through Spindle instead of the
current upstream APIs and direct reads. It succeeds when three things are better than today:

- the number of upstream calls;
- p95 latency;
- lock waits on ASE.

**In scope:**

| Area | Contents |
|---|---|
| Sources | Sybase ASE (jConnect, supplied by the user), PostgreSQL (tests and demo), HTTP APIs |
| Steps | `:sql`, `:api` (`:http` is an alias), `:when` conditions, projection output (split one heavy API into header and detail endpoints) |
| Stability | step cache (TTL per step, single-flight, stale-while-revalidate, `:per :caller`), circuit breaker, bulkhead, timeout, cancellation |
| Operations | metrics, `/health/*`, `/status`, file hot reload (validate, then swap atomically), API keys |
| Identity | **end-user identity passthrough (required)**, see §F1 |
| Publishing | **mandatory checks**: every SQL step has a timeout and a row cap; explain runs on reload and is archived. There is no approval step. See §F3 |

**Boundary rule.** A flow fetches **one business object, close to its source**: one order, a customer's credit, an
invoice and its lines. Composition across objects (customer → orders → shipments) belongs to the caller's
graph (Semalith/Pathom). Every hop is its own flow. Test: *would a caller ever want step 1's result on
its own, as a separate object?* If yes, step 1 belongs in its own flow.

**Roadmap after the first release, in order:**

1. stored procedures (`:proc`), probably needed for ASE;
2. Elasticsearch / OpenSearch (`:search`, §5.6);
3. SCI expressions (already implemented, but not part of the first release's guarantees);
4. authoring MCP (agents write flows);
5. writes (mutation endpoints);
6. JWT;
7. better parallelism in multi-step DAGs;
8. MySQL and ClickHouse (only once Semalith needs them).

### F1. End-user identity passthrough

A semantic layer serves people. When it calls Spindle on behalf of a person, three things must follow that person:

- the SQL parameters (row-level filters);
- the upstream API calls (so upstream permissions apply);
- the cache key, so that **A's rows are never served to B**.

**Wire format.** The caller authenticates as a service with its API key, and adds:

```
Authorization: Bearer <service key>           ; key must hold scope "spindle:delegate" to send the next two
X-Spindle-User: <subject>                     ; opaque, stable user id, ≤ 256 chars
X-Spindle-User-Claims: <base64url(JSON)>      ; optional object, ≤ 4 KB, e.g. {"territory_id":4,"roles":["sales"]}
```

**Rules:**

- A key without `spindle:delegate` that sends these headers gets `403`. The headers are never silently ignored.
- An endpoint can declare `:caller :required`. A call without `X-Spindle-User` then gets `401`.
- In flows the caller is the data-path root `:caller`:
  - `[:caller :client]` is the service key id;
  - `[:caller :sub]` is the end user;
  - `[:caller :claims :territory_id]` is a claim.
- An `:http` connection can forward the caller with `:forward-caller {"X-User-Id" [:caller :sub]}`.
- **Automatic per-caller caching.** A step that uses `[:caller …]` (in `:in`, `:request` or `:when`), or that runs on a
  connection with `:forward-caller`, is cached **per caller**, whatever its `:cache` says.
  The endpoint-level cache of a flow that contains such a step is per caller as well.
  The cache key holds a hash of (client, subject, claims), never the raw values.
- **Trust.** Spindle trusts the delegating service to tell the truth about the user, the same way a database trusts
  an application login. Signed identity (JWT from the identity provider) is on the roadmap. It replaces the claims
  header without changing how flows refer to `:caller`.

### F2. Step-level cache

The cache sits on the step (one upstream call), not only on the endpoint, so several endpoints that project
the same heavy call share it. This is the projection scenario: `/orders/{id}/header` and
`/orders/{id}/lines` both come from one upstream `GET /orders/{id}`.

```clojure
{:id :order :kind :api :conn :orders_api
 :request {:method :get :path "/orders/{id}" :path-params {:id [:params :id]}}
 :rows "$.header"                                  ; projection is applied after the cache
 :cache {:ttl "30s" :stale "2m" :stale-if-error "10m" :per :caller :tags [:orders]}}
```

**Key:** connection + step kind + normalised request + caller scope.

- For SQL, the normalised request is the statement text plus the bound values.
- For HTTP, it is the method, path, query, body and forwarded headers after data paths are resolved, plus the fan-out values.

**What is stored:** the raw response body (HTTP) or rows (SQL). JSON-path projection happens after the cache,
which is what lets different projections share one entry.

**Defaults:** SQL steps are not cached unless they say so. API steps are cached only when they say so.

**Behaviour:**

| Feature | What happens |
|---|---|
| Single-flight | concurrent identical misses make one upstream call; the others wait for it |
| Stale-while-revalidate | after `:ttl`, an entry is served for up to `:stale` while one background refresh runs |
| Stale on failure | when the source fails (breaker open, timeout, 5xx), an entry still within `:stale-if-error` is served |
| Bound | total weight in approximate bytes (`SPINDLE_STEP_CACHE_MB`, default 256) |
| Tags | entries keep their `:tags`; `POST /admin/cache/evict?tag=orders` drops them on that node. Mutation endpoints will declare `:invalidates [tags]` when writes arrive |
| Observability | `/status` → `step_cache` gives hit/miss/stale counts per `endpoint#step`; metric `spindle_step_cache_total{endpoint,step,result}`; Caffeine metrics under `cache="steps"` |

**Several nodes:** each node has its own cache. Two nodes can return different data for the same key
for **at most the TTL**. Choose TTLs with that in mind: for example 30 s for a heavy, slowly changing API, and no caching for
data that must be exact.

The endpoint-level result cache (`:cache` on the flow) is optional, and sits on top.

### F3. Mandatory checks before a flow goes live

When the config loads (startup or hot reload), every flow is checked. Any failure keeps the old version running:

1. **Every SQL step has a timeout:** step `:tuning :timeout`, or the connection's `:defaults :timeout`.
2. **Every SQL step has a row cap:** step `:max-rows`, flow `:limits :max-rows`, or the connection's `:defaults :max-rows`.
3. **Explain runs and is archived.** Each SQL step whose flow version has no archived plan is explained
   through the dialect (`EXPLAIN` / showplan) and written to
   `<SPINDLE_EXPLAIN_DIR>/<flow>/<flow-version>/<step>.json`. The file holds the SQL, the dialect, the time and the plan.
   - **Bind values:** from the flow's first `:examples` map for `[:params …]` paths; everything else is NULL.
   - **SQL errors** (unknown column, syntax) **reject the reload.**
   - **An unreachable database** does not reject it: the plan is retried on the next reload.

There is no approval step. The archive gives DBAs a record of every plan that went live.

## 0. Summary

Spindle runs **small flows on request** and publishes each one as an HTTP API.

- **Steps.** A flow is a handful of steps. A step runs SQL or a stored procedure on a database,
  calls an HTTP API, or computes a value. Step kinds can be mixed freely.
- **Data between steps.** Later steps take their inputs from earlier results by **data path**
  (`[:order :customerid]`). The engine works out the order and runs independent steps in parallel.
- **Output.** The results are merged into one output. Declarative operations cover most needs, and
  sandboxed SCI expressions handle the rest.
- **Publishing.** An **endpoint** publishes the flow as REST, documented with OpenAPI and with a batch
  form for graph layers. Flows may read or **write**.
- **Protection.** Every connection has a pool, a circuit breaker, a bulkhead and a rate limit.
  Every request has one deadline, and cancellation reaches the database (`Statement.cancel`).
- **Operations.** Configuration is **hot-reloaded**. Nodes are stateless, so Spindle scales out behind a load balancer.
- **Authoring.** Flows are EDN plus `.sql` files. People or **AI agents** write them; agents
  work through an authoring MCP server (§19).

What Spindle is **not**:

- **Not an asynchronous workflow engine.** It has no schedules, waits, durable state or human steps.
- **Not a semantic or graph planner.** Planning across entities belongs to the caller, for example Semalith with Pathom3.
- **Not ETL.** It does not move data in bulk between systems.

## 1. Goals and non-goals

Goals:

- Flows of SQL, stored-procedure, HTTP and expression steps, run synchronously on request.
- Reads, plus declared **writes**: one transaction per connection, idempotency keys, and an audit record.
- SQL runs **as written**. Spindle only binds parameters and expands `IN` lists. Each step can set isolation, fetch size,
  row count and timeout. An explain endpoint and a slow-query log support tuning.
- REST, OpenAPI, and batch endpoints that avoid N+1 calls from graph layers.
- Local cache with TTL, stale-while-revalidate, single-flight, and stale serving while a breaker is open.
- Monitoring, multiple nodes and hot reload are **required** (§16–18).
- Any JDBC database. Drivers can be bring-your-own, which covers proprietary ones such as SAP jConnect.

Non-goals:

- **Ad-hoc SQL** from callers.
- **Durable or asynchronous workflows**, retries over time, and compensation.
- **Two-phase commit** across connections (§8.3).
- **Automatic exposure of whole tables**, PostgREST-style.
- **Bulk data movement (ETL)** and BI dashboards.

## 2. Core concepts

| Concept | What it is | Where it lives |
|---|---|---|
| **Connection** | A named source. `:sql` is a JDBC database with a pool and dialect. `:http` is an HTTP API with a base URL and auth. Each connection has its own circuit breaker, bulkhead, rate limit and **defaults** (timeout, isolation, …). | `connections.edn` |
| **Step** | One unit of work: `:sql`, `:proc`, `:http` or `:expr`. A step reads its inputs by data path and returns rows or a value. | inside a flow |
| **Flow** | Parameters (malli schema), steps, merge rules, output shape and cache settings. A flow is an **instruction set**: data that the engine interprets. | `flows/<ns>/<name>.edn` + `.sql` |
| **Endpoint** | Publishes a flow over HTTP. It holds the path and method, the required auth scopes, whether it is a **mutation**, batch support and the OpenAPI description. | `endpoints.edn` (or `:endpoint` inside the flow) |

## 3. Architecture

```
     callers: Semalith (api connector, batch) · services · scripts
                         │  REST/JSON + OpenAPI
        ┌────────────────┴─────────────────┐
        │  load balancer (no leader)        │
        └──────┬───────────────────┬────────┘
          Spindle node A       Spindle node B   ...     ← each reads the same config source
   ┌────────────────────────────────────────────┐
   │ HTTP: reitit + malli, Jetty 12, virtual threads
   │   /api/…  /openapi.json  /metrics  /health/*  /status  /admin/*  /mcp
   │ Auth     : API keys + JWT, scopes per endpoint
   │ Config   : snapshot atom ← loader (watch/poll → validate → swap)
   │ Runner   : DAG executor (promesa + virtual threads), deadline, cancel
   │ Steps    : :sql · :proc · :http · :expr (SCI sandbox)
   │ Guards   : rate limit → breaker → bulkhead → deadline   (per connection)
   │ Cache    : Caffeine (TTL, SWR, single-flight)
   │ Output   : merge / shape → JSON
   └────────────────────────────────────────────┘
        │ JDBC (bundled or drivers/)              │ HTTP
     PostgreSQL · Sybase ASE · …              existing APIs
```

One request, in order:

1. Authenticate the caller and check the endpoint's scopes.
2. Take the current **config snapshot**. The whole request uses this snapshot, even if a reload happens meanwhile.
3. Validate the parameters.
4. Look up the cache for each key.
5. Run the flow's DAG for the keys that missed the cache.
6. Merge the results and shape the output.
7. Respond.

## 4. Definitions

### 4.1 Files and layout

```
config/                              # the config source: a git repo or a shared directory
  connections.edn                    # :sql and :http connections, guards, defaults
  endpoints.edn                      # optional; endpoints may also sit in flow files
  auth.edn                           # API keys (hashed), JWT issuers, scopes
  flows/
    sales/order.edn  sales/order.sql
    sales/order-with-stock.edn
    sales/order-comment.edn          # a mutation
```

- **Flows** are EDN. SQL lives in a sibling `.sql` file with named blocks (`-- :name <step-id>`).
  A short statement may be written inline. SQL in its own file can be opened in an SQL client,
  run by a DBA, and reviewed as SQL.
- **Secrets** are never stored in the files. Write `${ENV_VAR}` or `${file:/run/secrets/name}`.
  Literal secrets are rejected when the config loads.

### 4.2 Data paths, not string templates

Steps refer to parameters and to earlier results with **data paths**:

| Path | Meaning |
|---|---|
| `[:params :order_id]` | a request parameter |
| `[:order :customerid]` | column `customerid` of step `:order`'s rows |
| `[:stock :items 0 :on_hand]` | a nested path into an `:http` step's JSON |

**How many values a path yields** depends on the target parameter's schema:

- **List parameter:** the path yields the distinct non-nil values across rows.
- **Scalar parameter:** the path must yield exactly one value. More than one is an error, never a guess.
- **No values:** the step is skipped and returns empty (`:skip-when-empty`, default true).

There is no string interpolation anywhere. SQL gets JDBC bind parameters, and HTTP gets encoded path, query and body values.

### 4.3 SQL parameters and `IN` lists

- `:name` in SQL binds one value.
- If the parameter's schema is a list, `:name` expands to `(?, ?, …)`.
- **`IN` is chunked automatically.** The chunk size comes from the dialect (Postgres 1000, ASE 250 by default).
  Each chunk runs as its own statement, and the rows are concatenated. Very long lists can use a session temp table instead (§5.1).
- SQL is never rewritten or generated, and hints stay where the author put them.

## 5. Step kinds

Common keys:

| Key | Meaning |
|---|---|
| `:id`, `:kind`, `:conn` | name, kind, connection |
| `:in` | parameter → data path |
| `:session` | steps sharing a session id use **one connection, in order** (temp tables, `set` options). In a mutation this is also the transaction (§8). |
| `:or-else` | step to run if this one returns no rows |
| `:optional` | a failure becomes `nil` plus an entry in `meta.partial` |
| `:tuning` | `:isolation`, `:fetch-size`, `:max-rows` (row count), `:timeout`, dialect `:set` options; the connection's defaults apply when unset |

### 5.1 `:sql`

Runs a named block with bound parameters and returns rows.

- **Sessions.** A session can create temp tables, declared in `:temp-tables`. Afterwards Spindle drops them and runs
  the dialect's session reset. If cleanup fails, the connection is evicted instead of being returned to the pool.
- **Writes.** A write statement (`insert`/`update`/`delete`/`merge`) is allowed only in a flow published by a
  **mutation** endpoint (§8). Read endpoints reject flows that contain writes when the config loads.

### 5.2 `:proc` — stored procedures

Calls a procedure through `CallableStatement`. ASE, SQL Server, Postgres procedures and functions are covered through the dialect.

```clojure
{:id :bom :kind :proc :conn :erp
 :call "dbo.uspGetBillOfMaterials"           ; or {? = call ...} built by the dialect
 :in   {:StartProductID [:params :product_id]
        :CheckDate      [:params :as_of]}
 :out  {:row_count :int}                     ; OUT parameters → values
 :results [:components]                      ; result sets, in order → named row sets
 :status :status}                            ; the return status → value (ASE / SQL Server)
```

How Spindle reads a procedure's results:

- **Result sets.** Spindle loops `execute` → `getResultSet` / `getMoreResults` / `getUpdateCount` until done.
  Result sets are named in the order listed in `:results`. An extra, undeclared result set is an error, so
  nothing is silently dropped. A missing one becomes empty.
- **OUT parameters** are read after all result sets have been consumed, as JDBC requires.
- **The return status** comes from the `{? = call …}` form. A flow can declare `:fail-on-status #(not= 0 %)`
  (an SCI expression, §7.2) to treat a non-zero status as a failure.
- **Writes.** A procedure counts as a write unless it is declared `:read-only true`.

ASE notes, for the dialect and the guide:

- **Procedure transaction mode.** A procedure created in *unchained* mode fails inside a
  transaction that jConnect opens in chained mode (`sp_procxmode`). Mutation flows that
  call such procedures need the procedure set to `anymode`, or the step set to `:autocommit true`.
  To be verified on a test ASE.
- **`IGNORE_DONE_IN_PROC`** (a jConnect property) affects how row counts from inside procedures are reported.
  To be measured.

### 5.3 `:http`

Calls an `:http` connection and returns rows taken from the JSON response.

```clojure
{:id :stock :kind :http :conn :erp_api
 :request {:method :get :path "/inventory"
           :query {"product_id" [:in-list [:lines :productid]]}}   ; → in.(1,2,3) via the connection's list style
 :rows "$[*]"}
```

- **Lists.** `:fan-out :per-key` makes one call per value when the API takes only one id per call,
  bounded by the connection's bulkhead. Otherwise the list goes into one call, using the connection's
  list encoding (`:csv`, `:repeat`, `:postgrest-in`).
- **Responses.** Non-2xx responses are classified (§11.3). JSON is parsed with a size cap.
- **Writes.** POST, PUT, PATCH and DELETE are writes. They are allowed only in mutation flows, and they
  cannot be rolled back (§8.3).

### 5.4 `:expr` — computed values (planned)

A pure computation over earlier results, written as an SCI expression (§7.2). It does no I/O.

### 5.6 `:search` — Elasticsearch / OpenSearch (planned)

- **Not through JDBC.** Elastic's SQL JDBC driver is under the Elastic License, and some features need a paid subscription.
  A `:search` step instead calls the REST API over an `:http` connection with `:flavor :elasticsearch | :opensearch`.
- **Client.** It uses `java.net.http` directly, the same client as `:http` steps, not `elasticsearch-java`. Reasons:
  - one client, one set of guards and one cancel path;
  - no extra dependency tree;
  - it works with OpenSearch too, which the Elastic Java client does not support.
- **Query forms.**
  - **Query DSL written as EDN data.** Parameters go in as data paths at value positions, never by string
    concatenation: `{:query {:terms {:product_id [:lines :productid]}}}`.
  - **ES|QL**, with `?` parameters (`{:esql "FROM reviews | WHERE product_id == ? | LIMIT 50" :params [[:params :id]]}`).
    This is Elasticsearch only.
- **Results.** Hits become rows (`_source` plus `_id`/`_score`). Aggregations become rows through an `:aggs` path
  (bucket keys and values become columns), so `:merge` and `:nest` work on them unchanged.
- **Paging** uses `search_after` with a point in time (PIT), never scroll.
- **Stability.** Searches use the same breaker, bulkhead and rate limiter as other connections. The deadline is set
  both as the request's `timeout` and at the HTTP layer. On cancel, the HTTP request is aborted; long searches started
  with `wait_for_completion=false` are cancelled with `_tasks/<id>/_cancel`.
- **Scope.** Reads only at first. Index, update and bulk writes come later.
- **Testing:** single-node Elasticsearch (arm64, security off) or OpenSearch, indexed with AdventureWorks
  products and product reviews.

## 6. Flow execution

### 6.1 Model: a small DAG

- **Dependencies** come from the data paths in `:in`, from `:or-else`, and from session order. The DAG is compiled and
  checked for cycles when the config loads.
- **Parallelism.** Independent steps run in parallel. By default **at most one step per connection runs at a time
  within a request**, so a single request cannot take several connections from a pool.
  A flow raises this with `:parallel {:aw 2}`. Steps on different connections always overlap.
- **Limits** checked when the config loads:
  - at most 16 steps;
  - a required deadline (default 5 s, maximum 60 s);
  - row caps per step and per flow.
- **No loops, no waits, no conditions** apart from `:or-else` and `:skip-when-empty`. Anything needing more
  belongs in a workflow engine or in two flows composed by the caller.

### 6.2 Engine: own executor + promesa + virtual threads (decided)

- **The scheduler is Spindle's own**, about 50 lines:
  - start every step whose inputs are ready;
  - wait for the next completion or the deadline;
  - on the first fatal error or when the deadline passes, cancel everything that is still running.
- **Each step is a promesa promise** running blocking code on a **virtual thread** (`px/submit :vthread`).
- **Cancellation goes all the way through.** Cancelling a step's promise runs that step's cancel action:
  - `Statement.cancel()` for `:sql` and `:proc`;
  - an aborted request for `:http`;
  - interrupt and abandon for `:expr`.

  A flag covers a cancel that arrives before the statement exists.
- **Error reporting.** The error reported is the **first** failure, with its step and category. Steps that were cancelled
  as a result are never reported as the cause.
- **Behind a protocol.** The executor sits behind an internal protocol, so it can move to the JDK's
  `StructuredTaskScope` once that is final (JDK 28, JEP 543).

### 6.3 Spike evidence

The spike code is in `spikes/executor/`. It ran against PostgreSQL 17 on 2026-11-17.

- **Flow:** `a` (SQL) → `b` (SQL `pg_sleep(5)`) and `c` (an API stand-in) → `result`.
- **Deadline scenario:** 1 s deadline.
- **Failure scenario:** `c` fails at 300 ms.
- **Measured:** whether the statement was still active in `pg_stat_activity` after the call returned.

| Variant | deadline: returns | statement left in DB | failure: returns | error reported |
|---|---|---|---|---|
| **Chosen: own scheduler + promesa, `p/cancel!` → `Statement.cancel`** | 1.04 s | **0** | **0.39 s** | `unavailable` at step `c` ✔ |
| promesa `p/all` + `p/timeout` alone | 1.00 s | **1 (still running)** | 3.0 s | `timeout` ✘ |
| nodely (wrapped) | 1.03 s | 0 | 0.72 s | wrong step ✘ |
| missionary | 1.10 s | 0 | 0.48 s | ✔ |

Findings that shape the design:

1. **Only `Statement.cancel()` stops the database.** A promise timeout or a thread interrupt alone leaves the query
   running. The bridge from promise cancellation to the statement is therefore required.
2. **`p/all` waits for all of its inputs** (it follows `CompletableFuture.allOf`), so it is not fail-fast.
   The scheduler does not use it for that.
3. **Still open: jConnect.** The same mechanism has to be verified on Sybase ASE with jConnect,
   including the `CANCEL_ALL` and `QUERY_TIMEOUT_CANCELS_ALL` properties. This needs an ASE
   instance (§23, question 1).

### 6.4 Errors and partial results

- **All-or-nothing by default.** If a step fails, the call fails with a classified error (§11.3).
- **Optional steps.** An `:optional` step that fails yields `nil`, the call still returns 200, and `meta.partial` lists the step.
- **Failures are never empty results.** "No data" and "source down" stay different answers.

## 7. Merge and output

### 7.1 Declarative operations (the default)

`:output` builds the response from the step results:

| Operation | Effect |
|---|---|
| `:from` | the root step |
| `:select`, `:rename` | choose and rename columns |
| `:types` | `:trim`, `:int`, `:long`, `:decimal`, `:decimal-string` (exact money), `:date`, `:datetime` (with the connection's `:zone`), `:bool`, `:nil-if-blank` |
| `:nest` | attach child rows from another step by key, any depth; `:one? true` for a single child |
| `:merge` | add columns from another step's rows by key (flat enrichment) |
| `:compute` | add fields from SCI expressions (§7.2) |

Batch endpoints always keep the batch key, so callers can regroup the rows.

### 7.2 SCI expressions (decided: allowed)

For merge rules and transforms that the declarative operations cannot express:

```clojure
:compute {:line_total (* qty unitprice (- 1 unitpricediscount))
          :low_stock  (< (or on_hand 0) safety_stock_level)}
```

- **Interpreter.** Expressions run in **SCI**, the Small Clojure Interpreter (borkdude/sci, EPL-1.0, compatible with EPL-2.0).
  Each expression is parsed and checked when the config loads, and evaluated per row or per result.
- **Allowlist.** Only listed functions are available: arithmetic, comparison, `str`, a subset of
  `clojure.string`, collection functions such as `get`, `map`, `filter`, `reduce`, `group-by` and
  `sort-by`, and Spindle helpers such as `round-half-up` and `parse-date`.
  - **Forbidden:** no I/O, no Java interop, no `eval`, `resolve`, `require` or `def`, no atoms.
  - **No unbounded looping:** `loop`, `recur`, `while`, `iterate`, `repeat` and `cycle` are excluded.
  - **Lazy sequences are bounded** with `:realize-max`.
- **Time limit.** SCI has no timeout of its own. Expressions run on a virtual thread under the
  request's deadline, plus a per-expression budget (default 50 ms). When the budget is exceeded, the
  call fails with `timeout` and the thread is abandoned.

  The allowlist (no loops, bounded sequences, inputs bounded by row caps) is what keeps an abandoned
  thread from running for long. The JVM cannot kill a thread, so the allowlist is the real protection.
- **Same input, same output.** Expressions are deterministic, apart from an explicit `now`, which is taken once per request.

## 8. Writes (decided: supported)

### 8.1 Declaring a mutation

- An endpoint declares `:mutation true`. Only mutation endpoints may publish flows that write, and they use `POST`, `PUT` or `DELETE`.
- When the config loads, Spindle checks that flow and endpoint agree. A write step reachable from a read
  endpoint is rejected.
- Following the project's principle that **writes are normal**, there is no approval workflow.
  Control comes from scopes (§12), the mutation flag and the audit record.

### 8.2 Transactions

- **On one connection, one transaction.** All write steps of a request on the same `:sql` connection run on
  **one connection inside one transaction**: auto-commit is off, and the connection's isolation level or the flow's setting applies.
- **Commit.** Spindle commits only after **every step of the flow has succeeded**. Any failure, a cancellation or the deadline rolls back.
- **The commit itself is never cancelled.** If the deadline passes while the commit is in progress, the response is `504`
  with `"outcome": "unknown"`, and the caller should check with a read or retry with the idempotency key.
- **Reads in the same flow.** Read steps on the same connection join the transaction, so they see its writes.

### 8.3 Across connections: no two-phase commit

- Spindle does **not** coordinate transactions across connections, and it has no two-phase commit.
- **Order of commits.** If a flow writes to two databases, each commits on its own in a fixed order (the order
  of the `:commit-order` list, or of declaration). If a later commit fails after an earlier one succeeded,
  the response is `502` with `"outcome": "partial"` and lists what was committed.
- **HTTP writes cannot be rolled back.** Run them last, after the SQL commits, or make the endpoint idempotent.
- **Compensation** (undoing an earlier write) is the caller's job or a workflow engine's job.
- The guide tells authors: *one mutation flow should write to one connection whenever possible.*

### 8.4 Retries and idempotency

- **Writes are never retried automatically.**
- **With an `Idempotency-Key` header,** a mutation is safe to repeat:
  - Spindle records the key, a hash of the request and the response.
  - A repeat with the same key and the same request returns the stored response without running the flow again.
  - The same key with a different request gets `409`.
- **Where keys are stored.** Keys must be visible to every node, so they cannot live in node memory.
  - **Proposal:** a small table (`spindle_idempotency`) in the written connection, inserted **in the same
    transaction** as the write. The record and the write then succeed or fail together.
  - **Fallback:** for connections without such a table, a configured shared store. This is open (§23).
- **Retrying after a connection failure.** Only with a key, and only when the failure happened before the commit started.

### 8.5 Audit

Every mutation call writes an audit event:

- request id;
- endpoint and flow version;
- caller identity (key id or JWT subject) and scopes;
- idempotency key;
- connections touched;
- rows affected per step;
- outcome (`committed`, `rolled_back`, `partial`, `unknown`);
- duration;
- a **hash** of the parameters (values only for parameters marked non-sensitive).

Sinks:

- the structured log (always);
- optionally an audit table (`spindle_audit`) on a configured connection;
- optionally an HTTP sink.

## 9. Examples (AdventureWorks)

The demo and the integration tests use **AdventureWorks**: Microsoft's MIT-licensed sample database, in its
PostgreSQL port (lorint/AdventureWorks-for-Postgres), with a PostgREST API in front of it. Semalith's
`erp-demo` uses the same setup, which makes the end-to-end demo possible (§21).

`connections.edn`

```clojure
{:aw      {:type :sql :dialect :postgres
           :jdbc-url "jdbc:postgresql://${AW_DB_HOST}:5432/adventureworks"
           :username "${AW_DB_USER}" :password "${file:/run/secrets/aw_db_password}"
           :pool {:max 6}
           :defaults {:timeout "3s" :isolation :read-committed :fetch-size 500}
           :guard {:concurrency 4 :rate "100/s"
                   :breaker {:failure-ratio [10 20] :open "30s" :half-open-calls 3}}}
 :erp_api {:type :http
           :base-url "${ERP_API_URL}"
           :list-style :postgrest-in
           :defaults {:timeout "2s"}
           :guard {:concurrency 8 :rate "50/s" :breaker {:failure-ratio [5 10] :open "20s"}}}}
```

### 9.1 One SQL step

`flows/sales/order.edn`

```clojure
{:doc    "Sales order headers by id."
 :params [:map [:order_id [:vector {:min 1 :max 500} pos-int?]]]
 :steps  [{:id :order :kind :sql :conn :aw :in {:order_id [:params :order_id]}}]
 :output {:from :order :types {:orderdate :date :totaldue :decimal-string}}
 :cache  {:ttl "5m" :stale "30m"}
 :endpoint {:path "/sales/orders" :scopes #{"sales:read"} :batch {:key :order_id}}}
```

`flows/sales/order.sql`

```sql
-- :name order
select salesorderid as order_id, orderdate, status, customerid, subtotal, taxamt, freight, totaldue
  from sales.salesorderheader
 where salesorderid in :order_id
```

### 9.2 SQL + HTTP + SCI in one flow

`flows/sales/order-with-stock.edn` reads an order and its lines from SQL, gets stock for the line products from the
API, and merges everything into one tree with computed fields.

```clojure
{:doc    "Order with lines, product names, line totals and current stock."
 :params [:map [:order_id pos-int?]]
 :steps
 [{:id :order :kind :sql :conn :aw :in {:order_id [:params :order_id]}}
  {:id :lines :kind :sql :conn :aw :in {:order_id [:order :order_id]}}
  {:id :stock :kind :http :conn :erp_api :optional true
   :request {:method :get :path "/inventory"
             :query {"product_id" [:in-list [:lines :productid]]}}
   :rows "$[*]"}]
 :output
 {:from :order
  :one? true
  :nest {:lines {:from :lines :on {:order_id :order_id}
                 :merge {:from :stock :on {:productid :product_id}
                         :select [:on_hand :safety_stock_level]}
                 :compute {:line_total (round-half-up (* orderqty unitprice (- 1 unitpricediscount)) 2)
                           :low_stock  (and on_hand (< on_hand safety_stock_level))}
                 :types {:unitprice :decimal-string :line_total :decimal-string}}}}
 :cache {:ttl "1m" :stale "5m"}
 :endpoint {:path "/sales/orders/{order_id}/with-stock" :scopes #{"sales:read"}}}
```

```sql
-- :name order
select salesorderid as order_id, orderdate, status, customerid, totaldue
  from sales.salesorderheader where salesorderid = :order_id

-- :name lines
select d.salesorderid as order_id, d.salesorderdetailid as line_id, d.productid, p.name as product,
       d.orderqty, d.unitprice, d.unitpricediscount
  from sales.salesorderdetail d join production.product p on p.productid = d.productid
 where d.salesorderid = :order_id
```

How it runs:

- `lines` waits for `order`, and `stock` waits for `lines`.
- If the API is down or its breaker is open, the order still comes back. `on_hand` is `null` and `meta.partial` lists `stock`.

### 9.3 A mutation

`flows/sales/order-comment.edn`

```clojure
{:doc    "Set an order's comment; idempotent with Idempotency-Key."
 :params [:map [:order_id pos-int?] [:comment [:string {:max 128}]]]
 :steps  [{:id :upd :kind :sql :conn :aw
           :in {:order_id [:params :order_id] :comment [:params :comment]}
           :expect {:rows 1}}                  ; anything else → rollback + 404/409
          {:id :order :kind :sql :conn :aw :in {:order_id [:params :order_id]}}]
 :output {:from :order :one? true}
 :endpoint {:path "/sales/orders/{order_id}/comment" :method :put
            :mutation true :scopes #{"sales:write"}}}
```

```sql
-- :name upd
update sales.salesorderheader set comment = :comment, modifieddate = now()
 where salesorderid = :order_id
```

Both steps run in one transaction on `:aw`. The read sees the update, and both commit together.

## 10. Dialects and drivers

### 10.1 Dialects

| Capability | `:postgres` | `:mysql` | `:clickhouse` | `:sybase-ase` |
|---|---|---|---|---|
| Connection tag | `ApplicationName` | `connectionAttributes program_name` | `client_name` | `APPLICATIONNAME` |
| Cancel (verified) | `Statement.cancel` → cancel request ✔ | → `KILL QUERY` ✔ | → server-side cancel ✔ | TDS attention (to verify) |
| Isolation | `setTransactionIsolation` | same | **none**: setting `:isolation` is rejected at load | same, or `at isolation …` in SQL |
| Row cap | `setMaxRows` | same | same | same (`set rowcount`/`top` in SQL also possible) |
| Transactions / writes | yes | yes | **no**: writes must be declared non-transactional | yes (watch procedure chained mode) |
| Procedures | `call` / functions | `CALL` | **none** | `{? = call}`, return status, several result sets |
| Temp tables | `create temp table` | `create temporary table` | none (session-scoped only over HTTP sessions) | `#name` |
| Session reset | `DISCARD TEMP; RESET ALL` | (reset by pool) | none | `set rowcount 0`, drop `#` tables |
| Explain | `EXPLAIN (FORMAT JSON)` | `EXPLAIN FORMAT=JSON` | `EXPLAIN` | `set showplan on` + `set noexec on` |
| Execution stats | `EXPLAIN (ANALYZE, BUFFERS)` | `EXPLAIN ANALYZE` | `system.query_log` | `set statistics io, time on` |
| `IN` chunk size | 1000 | 1000 | 10000 | 250 |
| URL defaults | none | none | `compress=false` (clickhouse-jdbc 0.9 + ClickHouse 26) | none |

A `:generic` dialect (JDBC only) covers other databases.

### 10.2 Drivers: bundled and bring-your-own

- **Bundled:** the PostgreSQL JDBC driver (BSD-2) and clickhouse-jdbc 0.9 with the `all` classifier (Apache-2.0).
  The `all` classifier includes httpclient5; the plain JAR does not.
- **MySQL.** MySQL Connector/J is GPL-2.0 with the Universal FOSS Exception. The exception permits combining it with
  EPL-2.0 software, but shipping it inside Spindle's own distribution would put a GPL component into every
  downstream build. So it is **not bundled**: users put Connector/J or MariaDB Connector/J (LGPL-2.1) into `drivers/`.
  The test alias pulls MariaDB Connector/J for CI only.
- **Bring your own:** every other driver goes as a JAR into `drivers/` (mounted at
  `/opt/spindle/drivers` in containers). The launcher puts these JARs on the classpath.
- **Each connection names its `:driver` class.** If the class is missing, that connection fails with
  a clear message, and the other connections keep working.
- **Spindle never redistributes a proprietary driver.**

**Sybase ASE with jConnect.** jConnect comes from SAP: it ships with the ASE installer and the SDK. To use it:

1. Copy `jconn4.jar` (jConnect 16) into `drivers/`.
2. Set `:driver "com.sybase.jdbc4.jdbc.SybDriver"`.
3. Use the URL format `jdbc:sybase:Tds:<host>:<port>/<db>`.

jConnect properties Spindle uses:

| Property | Use |
|---|---|
| `APPLICATIONNAME` | tags connections so DBAs can identify Spindle |
| `CANCEL_ALL`, `QUERY_TIMEOUT_CANCELS_ALL` | cancel scope; defaults still to be verified |
| `DYNAMIC_PREPARE` | ASE statement cache; to be measured |
| `SELECT_OPENS_CURSOR`, `IMPLICIT_CURSOR_FETCH_SIZE` | only these make `:fetch-size` take effect on ASE |
| `IGNORE_DONE_IN_PROC` | row counts from procedures |
| `CHARSET`, `LANGUAGE` | character set and message language |

ASE tuning notes for the guide:

- **Isolation.** ASE's default level 1 takes shared locks while reading. Use
  `:isolation :read-uncommitted`, or `at isolation read uncommitted` / `noholdlock` in the SQL, where
  dirty reads are acceptable.
- **Reading speed.** Read rows promptly; at level 1 a slow reader holds locks for longer.
- **Long `IN` lists.** Prefer `#temp` tables.
- **Fixed-width columns.** `char` columns are padded; use `:trim`.
- **Dates.** `datetime` has no time zone; declare the connection's `:zone`.

## 11. HTTP interface

### 11.1 REST and OpenAPI

| Route | Purpose |
|---|---|
| endpoint paths (`/api/…`) | flows, as declared; `GET` for reads, `POST`/`PUT`/`DELETE` for mutations |
| `POST /api/_batch/<endpoint-id>` | the batch form of a read endpoint (§11.2) |
| `GET /openapi.json` | OpenAPI 3.1, generated from the endpoints' malli schemas and output shapes; includes security schemes and scopes |
| `GET /catalog/<endpoint-id>?format=semalith` | ready-to-paste Semalith operation EDN |
| `/metrics`, `/health/live`, `/health/ready`, `/status` | monitoring (§16) |
| `/admin/*`, `/mcp` | admin and authoring (admin scope only) |

Response envelope:

```json
{"rows": [ ... ], "next": null,
 "meta": {"endpoint": "sales/order-with-stock", "config_version": "c41e9a0", "flow_version": "7be2…",
          "cache": "hit|miss|stale", "elapsed_ms": 38, "partial": []}}
```

### 11.2 Batch endpoints for Semalith (no N+1)

A graph layer resolving 200 orders should make one call, not 200.

- **Batch form.** Every read endpoint with `:batch {:key …}` also accepts the key as a list (a JSON array, or the
  comma-separated string that Semalith's api connector sends) at `POST /api/_batch/<id>`.
- **Caching per key.** The cache is checked per key, and only the missing keys run the flow.
- **Execution.** Spindle runs the flow **once** with the list: `IN` chunks for SQL, and a list or a bounded fan-out for HTTP.
  Every row echoes its key, so the caller can regroup the rows.

How this maps onto Semalith's api connector:

| Semalith setting | Spindle side |
|---|---|
| `:batch {:parent-key … :child-fk "<key>" :placeholder … :separator ","}` | the batch route and its list parameter |
| `:data_path "$.rows"` and `:field_mapping` | the response envelope |
| `:collection :paging {:strategy :cursor :next-path "$.next"}` | keyset paging (§11.4) |
| `:status-map` | the error codes below |

### 11.3 Errors

| HTTP | code | meaning |
|---|---|---|
| 400 / 422 | `input_rejected` | invalid parameters (lets Semalith split a batch to find the bad id) |
| 401 / 403 | `unauthenticated` / `forbidden` | no or bad credentials / missing scope |
| 404 | `not_found` | a mutation's `:expect` found no row |
| 409 | `conflict` | idempotency key reused with different input, or an `:expect` mismatch |
| 413 | `too_many_rows` | over the row cap; results are never silently cut short |
| 429 | `rate_limited` + `Retry-After` | rate limit hit |
| 503 | `unavailable` + `Retry-After` | breaker open or bulkhead full |
| 504 | `timeout` (+ `outcome` for mutations) | deadline passed; statements were cancelled |
| 502 | `source_error` (+ `outcome`) | the database or upstream API failed |

Driver and upstream messages never reach callers. They are logged with sensitive parts redacted.

### 11.4 Paging

Spindle uses **keyset paging only**. An endpoint declares `:page {:key [...] :size … :max-size …}`. The root SQL
receives `:after_*` and `:limit` parameters, Spindle binds `size + 1` to detect a next page, and it returns an opaque `next` cursor.

## 12. Authentication and authorisation

- **API keys.**
  - Stored hashed in `auth.edn`, each with an id, scopes and an optional per-key rate limit.
  - Sent as `Authorization: Bearer <key>` or `X-Api-Key`.
- **JWT.**
  - Configured issuers, each with a JWKS URL, audience, and which claim holds the scopes (`scope` or `scp` by default).
  - The signature and `exp`/`nbf` are checked, and the JWKS is cached and refreshed.
- **Scopes.**
  - Each endpoint declares the scopes it requires (`#{"sales:read"}`), and a caller must hold all of them.
  - Mutation endpoints usually require a `…:write` scope.
- **Admin.** The `spindle:admin` scope is required for `/admin/*`, explain, and the **authoring MCP**. Nothing else exposes those functions.
- **Identity in the audit and logs.** The caller's identity (key id or JWT `sub`) goes into the audit record and the logs,
  and can be bound into SQL as a parameter (`[:caller :sub]`) for row-level filtering.

## 13. Stability (required)

Every step passes four guards in this order: **rate limit → circuit breaker → bulkhead → deadline**.
They are built with **diehard** (on Failsafe 3), one set **per connection**, and configured in `connections.edn`.

- **Circuit breaker.**
  - **What counts as a failure:** connection-class errors, timeouts and slow calls (optional `:slow-call`).
    Input errors, `too_many_rows` and `:expect` mismatches do not count, because the source is healthy.
  - **When it is open:** calls fail fast with `503` and `Retry-After`, and the source is not touched.
    If the cache holds an expired entry, Spindle serves it with `meta.cache = "stale"`. This can be turned off per endpoint.
  - **State changes** (`on-open`, `on-half-open`, `on-close`) are logged as events and counted as metrics.
  - **Spike lessons:**
    - diehard listeners take **one argument** (the event). A zero-argument function fails
      silently, because Failsafe swallows listener errors. A test covers this.
    - Half-open is **lazy**: it starts on the first call after the delay, not on a timer.
      `/status` therefore shows `half_open_due_at`.
  - **Optional per-endpoint breaker:** an endpoint can add its own breaker on top, so one slow flow does not trip the whole connection.
- **Bulkhead.** A semaphore per connection, no larger than the pool, with a short queue wait. When it is full the call gets `503`.
- **Rate limit.** One per connection, plus one per caller. Over the limit the call gets `429`.
- **Retry.** Reads only, transient errors only, at most once, and never beyond the deadline.
  Mutations follow §8.4.
- **Pool.** HikariCP, small (4–8). The connection timeout is 2 s, and `maxLifetime` stays below any
  idle cut-off on the database or firewall side. Hikari's Micrometer tracker is on.
- **Deadline.**
  1. One deadline per request: the endpoint timeout, optionally shortened by the caller's `Spindle-Timeout-Ms` header.
  2. Each step's own timeout is capped by the time that remains.
  3. When the deadline passes, the executor cancels every running step (§6.2).
  4. A connection that does not come back after a cancel is evicted.

## 14. Cache

- **Implementation:** Caffeine, **local to each node**. A shared cache (Redis) is a later option.
- **Key:** endpoint id + flow version + normalised parameters. Batch endpoints get one entry per key.
- **Freshness:**
  - `:ttl` is how long an entry stays fresh;
  - `:stale` is how long it may still be served while one background refresh runs (stale-while-revalidate);
  - identical concurrent calls on one node share one run (**single-flight**).
- **Empty results** are cached briefly (`:empty-ttl`).
- **Stale serving.** When the connection's breaker is open, or a refresh fails, an expired entry within `:stale-if-error` is
  served and marked as stale.
- **Mutations** are never cached. A mutation endpoint may list read endpoints whose entries it **evicts**
  on commit (`:evicts [...]`), on the local node only. Other nodes rely on their TTLs; this is documented.

## 15. SQL tuning and diagnostics

Per step (with defaults from the connection):

| Setting | How |
|---|---|
| `:isolation` | `setTransactionIsolation`; restored when the connection returns to the pool |
| `:fetch-size` | `setFetchSize` (see the dialect notes) |
| `:max-rows` | `setMaxRows(n+1)` → `413` above `n` |
| `:timeout` | `setQueryTimeout`, capped by the request deadline |
| `:set` | allow-listed dialect session options, reset after the step |

Diagnostics:

- **Explain:** `POST /admin/explain/<endpoint-id>` with parameters.
  - Default mode: each SQL step's plan (showplan / `EXPLAIN`), captured on a dedicated, non-pooled connection.
  - `?analyze=true`: really executes the flow and returns `statistics io, time` (ASE) or `EXPLAIN ANALYZE, BUFFERS` (Postgres) per step.
    For mutation flows this runs inside a transaction that is always rolled back.
- **Slow-query log:** a JSON line per step that exceeds its threshold. It holds the endpoint, step, flow version, elapsed time,
  row count, a hash of the parameters and the request id, plus an optional sampled plan.
- **Statement tag:** every statement starts with `/* spindle <endpoint>#<step> <request-id> */`, so DBAs can trace it.

## 16. Monitoring (required)

Every node exposes its own state. Prometheus aggregates across nodes.

**Metrics** (Micrometer: Prometheus `/metrics`, optional OTLP):

| Area | Metrics |
|---|---|
| Breakers | `spindle_breaker_state{conn}` (0 closed, 1 half-open, 2 open), `spindle_breaker_transitions_total{conn,to}`, `spindle_breaker_rejected_total{conn}` |
| Bulkheads | `spindle_bulkhead_in_use{conn}`, `spindle_bulkhead_queued{conn}`, `spindle_bulkhead_rejected_total{conn}` |
| Rate limits | `spindle_rate_limited_total{conn,caller}` |
| Pools | Hikari's own `hikaricp_connections_{active,idle,pending}`, acquire time, timeouts |
| Cache | Caffeine `cache_gets_total{result}`, `cache_size`, `cache_evictions_total`, `spindle_cache_stale_served_total{endpoint}` |
| Endpoints | `spindle_request_seconds{endpoint,outcome}`, `spindle_request_errors_total{endpoint,code}`, `spindle_partial_total{endpoint}` |
| Steps | `spindle_step_seconds{endpoint,step,kind}`, `spindle_step_rows`, `spindle_step_errors_total{…,category}`, `spindle_cancels_total{conn}`, `spindle_evictions_total{conn}` |
| Writes | `spindle_mutations_total{endpoint,outcome}`, `spindle_idempotent_replays_total{endpoint}` |
| SCI | `spindle_expr_seconds{endpoint}`, `spindle_expr_timeouts_total{endpoint}` |
| Config | `spindle_config_info{version}`, `spindle_config_reloads_total{result}`, `spindle_config_last_success_timestamp` |
| Slow queries | `spindle_slow_steps_total{endpoint,step}` + the slow-query log |

The spike confirmed that the breaker-state gauge, the Hikari tracker and the Caffeine stats all appear in the Prometheus scrape.
Labels come only from configured ids, so the number of label values stays bounded.

**Health and status:**

- `GET /health/live`: the process is alive.
- `GET /health/ready`: a config is loaded, and the connections each endpoint needs are not all breaker-open.
- `GET /status`: JSON. It contains:
  - the node id;
  - the **config version it is running**, when that version was loaded, and the last reload result or error;
  - for every connection: breaker state, `since` and `half_open_due_at`, bulkhead and pool usage, and the last error category and time;
  - cache totals.
- **Later:** a simple read-only status page rendered from `/status`.

**Logs:** structured JSON. Breaker transitions, reloads, slow steps, cancels and mutations are events
with stable names, so any log system can alert on them.

## 17. Multiple nodes (required)

- **Stateless nodes** behind a load balancer. There is **no leader** and no coordination between nodes.
- **Each node holds its own:**
  - cache (Caffeine);
  - breaker, bulkhead and rate-limit state;
  - config snapshot.
- **Shared state lives only in:**
  - the **config source**;
  - the databases, which hold the idempotency and audit tables.
- **Capacity budget.** Database connections = nodes × pool max. Concurrent statements = nodes × bulkhead. Size these with the DBA.
- **Version drift.** Every node reports its running config version in `/status` and in `spindle_config_info`,
  so drift between nodes is visible right away.
- **Open question (§23):** should breaker state be shared across nodes? The proposal is **independent**. A failed source trips
  each node's breaker within a few calls anyway, and sharing would add a dependency to every call.

## 18. Hot configuration reload (required)

**Covers:** flows and their SQL files, endpoints, connections (including breaker, bulkhead, rate-limit and cache
settings) and auth. In short, everything apart from the node's own startup settings (port, config source, node id).

**Sources:**

- **A directory**, watched with a file watcher plus polling every 5 s as a fallback (watchers are unreliable on network
  and overlay file systems).
- **Every node reads the same source:**
  - a **git** repository that each node pulls on an interval, pinned to a branch or tag; or
  - a **shared directory**.
- A central configuration service is a later option.

**Reload sequence:**

1. Read the complete new tree, then resolve secrets from the environment or files.
2. **Validate everything:**
   - EDN and malli schemas;
   - SQL blocks parse, and every placeholder has an `:in` binding of the right shape;
   - data paths point to existing steps;
   - the step graph has **no cycles**;
   - the §6.1 limits;
   - mutation rules (§8.1);
   - SCI expressions parse against the allowlist;
   - endpoint paths are unique;
   - drivers are present.
3. **On success,** build a new immutable snapshot and swap the atom (`reset!`), as one atomic step.
4. **On failure,** keep the old snapshot, log the error, count it in metrics, and show it in `/status`.
   A partial set is never applied.

**Requests in flight:** a request takes the snapshot once, when it starts, so it **finishes on the old version**.

**Connections:**

- **Fingerprint.** Each connection has a fingerprint of its pool-relevant settings: URL, user, secret version, driver,
  properties and pool size.
- **Fingerprint changed:** a **new pool** is built for the new snapshot. The old pool closes once no in-flight request
  still uses the old snapshot. Spindle tracks this with a reference count per snapshot, and the wait is
  bounded by the maximum deadline plus a grace period.
- **Fingerprint unchanged:** the pool, **breaker state** and cache entries carry over.
- **Only guard settings changed:** new diehard policies are built. Failsafe policies cannot be changed in place,
  so breaker state then resets to closed; this is documented. Pools are kept.

**Cache across reloads:** keys include the flow version, so changed flows never serve old results. Unchanged
flows keep their entries.

**Rollback:**

- **The normal way:** roll back in the config source (a git revert, or restoring the previous directory).
  Every node converges on it.
- **Emergency, one node:** each node keeps its last 5 good snapshots, and
  `POST /admin/config/rollback?to=<version>` switches that node back. The node is then **pinned** to that version
  until it is unpinned, and `/status` shows `pinned: true`.

**Secrets:** never in the config files. They come from `${ENV}` or `${file:…}`, are resolved at load time, and never
appear in `/status`, logs or snapshot dumps. A rotated secret file counts as a connection change.

## 19. Authoring MCP (decided: agents write flows)

People can write flows by hand, but the main author is expected to be an **AI agent**. It works through an
authoring MCP server built on mcp-shuttle (EPL-2.0), at `/mcp`, and only with the **admin scope**.

| Tool | What it does |
|---|---|
| `list_connections` | connections, their kind, dialect, health and defaults; never secrets |
| `describe_table` | columns, types, keys and indexes from database metadata; read-only, never row data |
| `describe_proc` | procedure parameters (in and out) and, where the dialect supports it, result-set shapes |
| `list_flows` / `get_flow` | published flows and endpoints, with their versions |
| `validate_flow` | runs the full reload validation (§18) on an inline flow and returns findings with paths |
| `test_run` | runs an inline or published flow with sample parameters. Returns the output, per-step row counts, timings and `meta.partial`. **Mutation flows run inside a transaction that is always rolled back**, and `:http` write steps are not called (shown as "would call"). |
| `explain` | plans and statistics for each SQL step (§15) |
| `publish` | writes the flow into the config source (a git commit or a directory write), which triggers the hot reload on every node. Returns the new version and the reload result. |

**The agent's loop:**

1. `list_connections`, then `describe_table` / `describe_proc`.
2. Write the EDN and SQL.
3. `validate_flow`.
4. `test_run` with sample parameters, then `explain`.
5. Fix and repeat.
6. `publish`.

People review the published SQL and the results.

**The guide is part of the product.** The agent's instructions are written, versioned and tested like code:

- what flows are for and what they are not;
- the instruction set with examples;
- data paths;
- SQL tuning rules per dialect (the ASE notes in §10.2);
- when a flow needs a mutation endpoint, and how to keep it to one connection;
- the SCI allowlist;
- common errors and how to fix them.

The guide ships as MCP resources and in `docs/guides/`. A set of authoring tasks on the AdventureWorks demo
measures guide quality: does an agent produce a correct flow that passes `test_run`?

## 20. Libraries

| Need | Choice |
|---|---|
| JDBC / pool | next.jdbc, HikariCP |
| breaker, bulkhead, rate limit, retry | diehard (Failsafe 3) |
| futures on virtual threads | promesa (MPL-2.0), with our own scheduler (§6.2) |
| cache | Caffeine |
| expressions | SCI (EPL-1.0) |
| HTTP server | Jetty 12 (`ring-jetty9-adapter`), virtual threads, JDK 21+ |
| routing, schemas, OpenAPI | reitit + malli (malli → JSON Schema → OpenAPI 3.1) |
| JWT | buddy-sign (Apache-2.0) or nimbus-jose-jwt (Apache-2.0) |
| HTTP client | `java.net.http.HttpClient` |
| JSON / JSON path | jsonista + a small JSON-path subset |
| metrics | Micrometer + Prometheus; Hikari and Caffeine binders; optional OTLP |
| MCP | mcp-shuttle |
| build | deps.edn + tools.build; an uberjar plus a container image |

All of these licences are compatible with EPL-2.0. The list gets re-checked when dependencies are pinned.
Libraries looked at and not chosen: nodely, missionary, plumatic Graph, Pathom3, manifold, core.async(.flow).
§6.3 explains why for the first two.

## 21. Demo and deployment

- **Demo.** The repository's example is AdventureWorks on PostgreSQL plus a PostgREST API. It contains the §9 flows, a
  compose file and generated keys. Data comes from the MIT-licensed sample, never from real company data.
- **End-to-end.** A Semalith graph calls Spindle's batch endpoints through its existing api connector, and
  answers questions that need both the SQL and the API data. This shows the whole path:
  agent writes flow → publish → hot reload → Semalith resolves through Spindle.
- **Container.** JDK 21. Mount `config/`, `drivers/` and secrets. Two or more replicas behind a load balancer.

## 22. Project

- **Licence:** EPL-2.0.
- **Repository layout:**
  - `src/spindle/` (core);
  - `src/spindle/dialect/`;
  - `drivers/` (git-ignored apart from its README);
  - `examples/adventureworks/`;
  - `docs/design/`, `docs/guides/` (including the agent guide);
  - `spikes/`;
  - `test/`.
- **Tests:**
  - unit tests (SQLite/H2 and a stub HTTP server);
  - integration tests on PostgreSQL with AdventureWorks;
  - fault tests: cancel, eviction, breaker, bulkhead, reload mid-request, pool swap, rollback of a mutation, idempotent replay, SCI time limit;
  - dialect tests for proprietary databases only when their driver and a test URL are provided.
- **Hygiene:** no company data, hosts, accounts or business names. No driver JARs. A denylist check runs before publishing.

## 23. Open questions

1. **A test ASE for the jConnect cancel spike.** A company test database, or a SAP ASE Developer Edition
   container? The first test host is arm64, and ASE images are x86-64 only, so the container would need another host
   or emulation. This also covers verifying `CANCEL_ALL`, procedure transaction modes and `IGNORE_DONE_IN_PROC`.
2. **Where idempotency keys are stored** when a written connection cannot hold the `spindle_idempotency` table.
3. **Breaker state across nodes:** independent (proposed) or shared?
4. **Publishing to git.** Should `publish` commit and push to git (it then needs push credentials), or write to a shared
   directory that git-sync mirrors?
5. **Cache eviction on mutations** across nodes: are TTLs enough, or is a broadcast needed later?
6. **Per-key `:or-else`** (fall back only for the keys that are missing).
7. **The SCI allowlist:** start minimal and add on request (proposed)?
8. **Baseline JDK:** 21 now, `StructuredTaskScope` at 28+?

## 24. Phases (proposal, to be confirmed by the maintainer)

| Version | Contents |
|---|---|
| **v0.1** | Single-step `:sql` flows; REST endpoints; API-key auth with scopes; pool, timeout and cancel; breaker, bulkhead and rate limit; metrics, `/health/*`, `/status`; **hot reload** (directory source, validation, atomic swap, pool rebuild); Postgres dialect; AdventureWorks example; deployed on the first test host |
| **v0.2** | Multi-step DAG executor (promesa + virtual threads); `:http` steps; merge and output; **SCI expressions**; Caffeine cache (TTL, SWR, single-flight, stale on breaker open); **batch endpoints** (moved up from v0.3, because the Semalith end-to-end demo needs them to avoid N+1); **authoring MCP, read and test part** (`list_connections`, `describe_table`, `validate_flow`, `test_run`) plus the first agent guide |
| **v0.3** | **Writes** (mutation endpoints, transaction per connection, idempotency keys, audit); JWT; explain and the slow-query log; OpenAPI; status page; MCP `explain` and `publish` (git or directory); Semalith end-to-end demo |
| **v0.4** | Sybase ASE dialect with jConnect (needs question 1); **`:proc` steps** (several result sets, OUT parameters, return status); jConnect cancel verification; agent-guide evaluation tasks |

Reasons for this order:

- **SCI** arrives with merge in v0.2, because merge is where it is needed.
- **The authoring MCP is split.** Its read and test tools only need the engine, so agents can start writing flows early.
  `publish` waits for the git or directory decision.
- **Writes** wait until the read path is stable and auth includes JWT.
- **`:proc` and ASE** wait for a test ASE. Postgres procedures could come earlier if needed.

## 25. Later

- **A shared cache** (Redis), if hit-rate metrics justify it.
- **A central configuration service**, replacing git or the shared directory.
- **core.async.flow** for background work: cache pre-warming, scheduled refresh, metrics pipelines.
  It is not used for running requests, because it is built for long-lived streaming graphs, not run-once DAGs with deadlines.
- **More dialects:** SQL Server, Oracle, MySQL.
