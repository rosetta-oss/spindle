# Executor spike (2026-11-17)

Compares DAG runners for Spindle on deadline, cancellation and error reporting, plus
diehard breaker listeners and Micrometer output. Results are in the design doc, §5.4 and §10.

- `own.clj`: own executor on virtual threads and a cancel-hook registry
- `prom.clj`: promesa `p/all` + `p/timeout`, with and without hooks
- `nod.clj`: nodely env (`:async.virtual-futures`) with a wrapper for the deadline and fail-fast
- `miss.clj`: missionary (a hand-written flow, and a dynamic DAG with `m/dfv`)
- `chosen.clj`: the chosen design (own scheduler + promesa; `p/cancel!` → `Statement.cancel`)
- `guard.clj`: diehard breaker and bulkhead, listeners, Prometheus scrape (breaker gauge, Hikari, Caffeine)

Run against a throwaway PostgreSQL (user/password/db `spike`) on `127.0.0.1:55432`:

    clojure -M -m spike.chosen   # chosen design: cancel + deadline
    clojure -M -m spike.main     # all executor variants
    clojure -M -m spike.guard    # breaker + metrics

Override with `SPIKE_DB_URL` / `SPIKE_DB_PASSWORD`. Spike code only, not part of Spindle.
