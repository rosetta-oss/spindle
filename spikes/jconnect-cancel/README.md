# jConnect cancellation test (Sybase ASE)

Checks that cancellation and timeouts really reach Sybase ASE through SAP jConnect.
Spindle's design depends on this, and it can only be verified against a real ASE.

## What it does

It runs `waitfor delay "00:00:30"` and cancels it after 5 seconds, in three ways:

| Test | How it cancels |
|---|---|
| `promise` | a promesa future on a virtual thread; `p/cancel!` at 5 s → `Statement.cancel()` (Spindle's path) |
| `direct` | `Statement.cancel()` from another thread at 5 s |
| `timeout` | `Statement.setQueryTimeout(5)`, with no explicit cancel |

A fourth test, `spindle`, runs a Spindle flow with a 5 s deadline through the real HTTP handler.
Its query is a 4-way cross join on `master..sysobjects`, because Spindle flows run only read statements.

For each test the output shows:

- the elapsed time;
- the exception;
- what `master..sysprocesses` reports for the spid afterwards;
- whether the same connection still works (`select 1`).

## Run

Requirements: JDK 21+ and the Clojure CLI.

1. Copy jConnect 16 (`jconn4.jar`) to `drivers/jconn4.jar` at the repository root. It is never committed.
2. Set the connection in the environment:

   ```sh
   export ASE_URL='jdbc:sybase:Tds:<host>:<port>/<database>'
   export ASE_USER='<login>'
   export ASE_PASSWORD='<password>'      # or: export ASE_PASSWORD="$(cat /path/to/secret)"
   ```

3. Run all tests, or only some of them:

   ```sh
   cd spikes/jconnect-cancel
   clojure -M -m jconnect-cancel
   clojure -M -m jconnect-cancel promise direct
   ```

4. Repeat with the cancel properties changed:

   ```sh
   ASE_PROPS='CANCEL_ALL=false' clojure -M -m jconnect-cancel
   ASE_PROPS='QUERY_TIMEOUT_CANCELS_ALL=true' clojure -M -m jconnect-cancel timeout
   ```

## What a pass looks like

- **Elapsed time:** about 5 s (≤ 6 s), not 30 s.
- **Server state:** `:server-after {:cmd "AWAITING COMMAND" …}`. The spid is idle, so the statement is gone.
- **Connection:** `:connection-usable-after true`. If it is not usable, Spindle evicts such connections from the pool, which works but costs a reconnect.
- **The `spindle` test:** returns `:status 504` after about 5 s, with `:still-running 0`.

## Record the results

Paste the printed maps into `docs/design/0001-spindle-design.md` §6.3, together with the
jConnect version (printed first) and the ASE version (`select @@version`).
