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
