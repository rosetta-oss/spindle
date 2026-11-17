# Changelog

## Unreleased (v0.2-dev)

- **Flows:** EDN flow definitions with `.sql` named blocks; data paths between steps; `IN` lists expanded and chunked per dialect.
- **Execution:** DAG executor (own scheduler, promesa, virtual threads); request deadline with `Statement.cancel`; `:or-else`, `:optional`.
- **Step kinds:** `:sql` and `:http` (data-path query/path/body, list styles, per-key fan-out).
- **Output:** `:nest`, `:merge`, `:compute` (SCI sandbox), `:types`, `:select`, `:rename`.
- **Guards per connection:** circuit breaker (diehard) with state events, bulkhead, rate limiter.
- **Cache:** Caffeine with TTL, stale-while-revalidate, single-flight, stale serving on source failure.
- **Hot reload:** full validation, atomic swap, pool carry-over and retirement, rollback with pin.
- **HTTP API:** API keys with scopes; `/metrics`, `/health/live`, `/health/ready`, `/status`, `/admin/*`.
- **Dialects:** postgres, mysql, clickhouse (read, timeout and cancel verified), sybase-ase (not yet verified).
- **Examples and tests:** AdventureWorks example config; integration tests for PostgreSQL, MySQL and ClickHouse.
- **jConnect:** cancellation test in `spikes/jconnect-cancel`.

### First-release scope (agreed 2026-11-17)

- **`:api` steps.** `:api` is the step name, and `:http` remains an alias. Header values can be data paths.
  Connections can forward the caller with `:forward-caller`.
- **`:when` conditions.** Declarative conditions; a skipped step returns its `:default`.
- **Step-level cache.** Shared across endpoints. It stores HTTP bodies, so `:rows` projections share one upstream call.
  It has single-flight, stale-while-revalidate, stale-on-failure, `:per :caller`, tags and a weight bound.
  Hit, miss and stale counts appear in `/status` and the metrics.
- **End-user identity.** `X-Spindle-User` / `X-Spindle-User-Claims` with the `spindle:delegate` scope.
  Endpoints can set `:caller :required`. Caller-dependent steps and endpoints are cached per caller.
- **Publishing checks.** Every SQL step needs a timeout and a row cap. Explain runs on reload and is archived
  under `SPINDLE_EXPLAIN_DIR`, and SQL errors reject the reload. ASE uses showplan.
- **Admin and status.** `POST /admin/cache/evict?tag=`. `/status` shows reload problem details to admin keys only.
- **Config loading.** Hidden files are ignored.
- **Tests.** `test/spindle/scenarios_test.clj` covers projection, conditional API, stale on failure, identity and the publishing checks.
