# Spindle

Spindle runs small data flows on request and publishes each flow as an HTTP API.

- A flow is made of steps. Each step runs **SQL** against a database or calls an **HTTP API**, and the two kinds can be mixed.
- Later steps take their inputs from earlier results through data paths (`[:order :customerid]`).
- The results are merged into one output, using declarative rules plus sandboxed SCI expressions.
- Every connection is protected by a pool, a deadline that really cancels statements, a circuit breaker, a bulkhead and a rate limit.
- Configuration is hot-reloaded. Nodes are stateless, so you can run as many as you like behind a load balancer.

Typical callers are semantic or graph layers, for example Semalith with Pathom3, that need
clean, batchable APIs in front of legacy databases and weak upstream APIs.

Licence: **EPL-2.0**. The full design is in [docs/design/0001-spindle-design.md](docs/design/0001-spindle-design.md).

## Status

**v0.2-dev.** Read-only flows work end to end on PostgreSQL. MySQL and ClickHouse can read, enforce deadlines and cancel statements.

| Area | State |
|---|---|
| Single-step and multi-step SQL flows (DAG, parallel per connection, `:or-else`, `:optional`) | ✅ |
| `:http` steps (data-path query/path/body, list styles, per-key fan-out) | ✅ |
| Output: `:nest`, `:merge`, `:compute` (SCI sandbox), `:types`, `:select`, `:rename` | ✅ |
| Deadline → `Statement.cancel` through promesa (verified on PostgreSQL, MySQL, ClickHouse) | ✅ |
| Per-connection circuit breaker (diehard), bulkhead, rate limit; stale cache on breaker open | ✅ |
| Caffeine cache: TTL, stale-while-revalidate, single-flight | ✅ |
| Hot reload (poll, full validation, atomic swap, pool carry-over/retire, rollback + pin) | ✅ |
| API keys with scopes; `/metrics` (Prometheus), `/health/live`, `/health/ready`, `/status` | ✅ |
| Dialects: `postgres` (full), `mysql`, `clickhouse` (read, timeout, cancel), `sybase-ase` (written, **not yet verified**: see below) | ⚠️ |
| Batch route `/api/_batch/…`, keyset paging, OpenAPI, explain endpoint, slow-query log | ⏳ next |
| JWT, writes (mutation endpoints, transactions, idempotency, audit), `:proc`, authoring MCP, `:search` (Elasticsearch) | ⏳ later |

A read endpoint already accepts a list key as a comma-separated string (`?order_id=1,2,3`), which is what
Semalith's api connector sends for batches. The dedicated batch route comes next.

## Requirements

- **JDK 21+** (virtual threads), for example Temurin 21.
- **Clojure CLI** 1.12+ (`clojure`), for development and tests.
- **Docker**, for integration tests and container deployment (optional).

## Quick start

```sh
git clone https://github.com/rosetta-oss/spindle.git && cd spindle
clojure -M:test                         # unit tests + SQLite end-to-end (no services needed)
SPINDLE_CONFIG_DIR=examples/adventureworks/config bin/spindle     # needs the env vars below
```

A config directory contains:

```
connections.edn   ; :sql (JDBC) and :http connections, guards, defaults; secrets as ${ENV} or ${file:/path}
auth.edn          ; API keys (SHA-256 hashes) and their scopes
flows/<ns>/<name>.edn  (+ <name>.sql with `-- :name <step-id>` blocks)
```

A flow (see `examples/adventureworks/config/flows/sales/`):

```clojure
{:params [:map [:order_id pos-int?]]
 :steps [{:id :order :kind :sql :conn :aw :in {:order_id [:params :order_id]}}
         {:id :lines :kind :sql :conn :aw :in {:order_id [:order :order_id]}}
         {:id :stock :kind :http :conn :erp_api :optional true
          :request {:method :get :path "/inventory" :query {"product_id" [:lines :productid]}} :rows "$[*]"}]
 :output {:from :order :one? true
          :nest {:lines {:from :lines :on {:order_id :order_id}
                         :merge {:from :stock :on {:productid :product_id} :select [:on_hand]}
                         :compute {:line_total (round-half-up (* orderqty unitprice) 2)}}}}
 :cache {:ttl "1m" :stale "5m"}
 :endpoint {:path "/api/sales/orders/{order_id}/with-stock" :scopes #{"sales:read"}}}
```

Callers send `Authorization: Bearer <key>`. To create a key and its hash for `auth.edn`:

```sh
KEY=$(openssl rand -hex 24); printf %s "$KEY" | shasum -a 256
```

## Databases and drivers

| Database | `:dialect` | Driver | Bundled |
|---|---|---|---|
| PostgreSQL | `:postgres` | pgjdbc (BSD-2) | yes |
| ClickHouse | `:clickhouse` | clickhouse-jdbc 0.9 `all` (Apache-2.0) | yes |
| MySQL / MariaDB | `:mysql` | MariaDB Connector/J (LGPL-2.1) or MySQL Connector/J (GPL-2.0 + FOSS exception) | **no**: put the JAR in `drivers/` |
| Sybase ASE | `:sybase-ase` | SAP jConnect 16 (proprietary) | **no**: put `jconn4.jar` in `drivers/` |

`bin/spindle` and the container image add `drivers/*.jar` to the classpath. JARs in `drivers/` are git-ignored.

### Notes per database

- **ClickHouse:** Spindle adds `compress=false` to the URL. clickhouse-jdbc 0.9 cannot read compressed responses
  from ClickHouse 26.x ("Invalid LZ4 magic byte"), and `compress=0` makes 0.9 hang and retry. ClickHouse has no
  transactions or isolation: a flow that sets `:isolation` on a ClickHouse step is rejected when the config loads.
- **MySQL 8 (caching_sha2_password) with MariaDB Connector/J:** add `?allowPublicKeyRetrieval=true` to the URL, or use TLS.

### Sybase ASE with jConnect

1. Get jConnect 16 from SAP (it ships with the ASE installer and SDK). Copy `jconn4.jar` into `drivers/`.
2. Declare the connection in `connections.edn`:

   ```clojure
   {:legacy {:type :sql
             :dialect :sybase-ase
             :driver "com.sybase.jdbc4.jdbc.SybDriver"            ; the default for :sybase-ase
             :jdbc-url "jdbc:sybase:Tds:${ASE_SERVER}:${ASE_PORT}/${ASE_DB}"
             :username "${ASE_USER}" :password "${ASE_PASSWORD}"
             :zone "UTC"                                          ; ASE datetime has no zone
             :properties {"LANGUAGE" "us_english"}                ; any jConnect property
             :pool {:max 6}
             :defaults {:timeout "5s" :isolation :read-uncommitted}  ; only where dirty reads are fine
             :guard {:concurrency 4 :breaker {:failure-ratio [10 20] :open "30s"}}}}
   ```

3. Spindle tags its connections with `APPLICATIONNAME=spindle`, so DBAs can see them in `sp_who` and `sysprocesses`.

4. **Before production, run the cancellation test** in [`spikes/jconnect-cancel/`](spikes/jconnect-cancel/README.md).
   It checks that cancelling a `waitfor delay "00:00:30"` at 5 s really stops the statement on the server, and that the
   connection is still usable afterwards.

## Tests

```sh
clojure -M:test
```

This runs the unit tests and the SQLite end-to-end tests. The integration tests are skipped unless their environment variables are set:

| Suite | Environment |
|---|---|
| PostgreSQL / AdventureWorks (+ PostgREST) | `AW_DB_HOST AW_DB_PORT AW_DB_USER AW_DB_PASSWORD` [`ERP_API_URL ERP_API_TOKEN`] |
| MySQL | `SPINDLE_IT_MYSQL_URL SPINDLE_IT_MYSQL_USER SPINDLE_IT_MYSQL_PASSWORD` |
| ClickHouse | `SPINDLE_IT_CH_URL SPINDLE_IT_CH_USER SPINDLE_IT_CH_PASSWORD` |

Each database suite checks three things:

- a read works;
- a statement that runs past the deadline returns `504`;
- the statement is really gone from the server afterwards (`pg_stat_activity`, `processlist` or `system.processes`).

AdventureWorks is Microsoft's MIT-licensed sample database. The tests use lorint's PostgreSQL port
(`lorint/AdventureWorks-for-Postgres`), with PostgREST in front of it for the `:http` step.

## Running

| Variable | Default | Meaning |
|---|---|---|
| `SPINDLE_CONFIG_DIR` | `config` | config directory; polled for changes |
| `SPINDLE_PORT` | `8080` | HTTP port |
| `SPINDLE_NODE_ID` | hostname | shown in `/status` |
| `SPINDLE_POLL_MS` | `5000` | config poll interval |

Build and run:

```sh
clojure -T:build uber && bin/spindle          # uberjar + drivers/
docker build -t spindle . && docker run -p 8080:8080 \
  -v $PWD/config:/opt/spindle/config -v $PWD/drivers:/opt/spindle/drivers spindle
```

**Operations:**

- `GET /status` shows the config version, the last reload result, and every connection's breaker, bulkhead and pool state.
- `POST /admin/reload`, `POST /admin/config/rollback?to=<version>` and `POST /admin/config/unpin` need a key with the `spindle:admin` scope.
- **Several nodes:** point every node at the same config source (a git checkout or a shared directory). Each node reports
  the version it is running, and there is no leader.

## Development

The code is in `src/spindle/`:

| File | Purpose |
|---|---|
| `config.clj` | loading, validation, snapshots, hot reload |
| `flow.clj` | compiling and running flows |
| `executor.clj` | the DAG scheduler: promesa futures on virtual threads |
| `sql.clj` | SQL steps: binding, `IN` chunking, cancel, tuning |
| `httpstep.clj` | HTTP steps |
| `output.clj` | output shaping |
| `expr.clj` | SCI sandbox |
| `conn.clj` | pools and guards |
| `dialect.clj` | per-database differences |
| `cache.clj` | Caffeine cache |
| `auth.clj` | API keys |
| `server.clj` | HTTP handler |
| `metrics.clj` | metrics |

**Rules:**

- SQL is executed **as written**. Spindle only binds parameters and expands `IN` lists.
- Driver and upstream messages never reach callers.
- No secrets in config files: write `${ENV}` or `${file:/path}`.

### Next steps

1. **Batch route** `POST /api/_batch/<endpoint>` with a per-key cache, plus keyset paging.
2. **API description and tuning:** OpenAPI 3.1 generated from the malli schemas, `POST /admin/explain/<endpoint>`, and the slow-query log.
3. **Sybase ASE:** run `spikes/jconnect-cancel` on a real ASE, then finish the `:sybase-ase` dialect (showplan explain, session reset) and record the results in the design doc.
4. **More callers and step types:** JWT issuers, then writes (mutation endpoints, one transaction per connection, idempotency keys, audit) and `:proc` steps.
5. **Authoring MCP:** for agents writing flows, on mcp-shuttle (`list_connections`, `describe_table`, `validate_flow`, `test_run`, `explain`, `publish`).
6. **Search step:** `:search` for Elasticsearch/OpenSearch (Query DSL as EDN, ES|QL, search_after + PIT).

## Licence

Copyright the Spindle contributors. Distributed under the Eclipse Public License 2.0 (see `LICENSE`).
Third-party drivers placed in `drivers/` keep their own licences.
