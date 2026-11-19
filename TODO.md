# TODO

Where work continues after 2026-11-17. Everything up to `04110f7` was built and tested on PostgreSQL 16
(115 tests). Sybase ASE has not been tested yet. See `CHANGELOG.md` for what exists and
`docs/guides/cookbook.md` for how to write flows.

**Rule until the first real graph is measured:** no new features, only fixes that ASE verification needs
(see "Positioning" in `README.md` and D18 in the design).

## 1. Set up in the company environment

- [x] Put `jconn4.jar` in `drivers/` (never committed) and run `clojure -M:test`. The ASE tests skip themselves
      until `SPINDLE_IT_ASE_URL`, `SPINDLE_IT_ASE_USER` and `SPINDLE_IT_ASE_PASSWORD` are set.
      2026-11-17: done on the company's dev ASE: 112 tests, 0 failures.
- [ ] Use a read-only login for reads, and a separate test database (or schema) for `:proc` and write tests.
      Never point write tests at a shared database.
- [ ] Follow "Taking it over in the company environment" in `README.md` (config directory, keys, explain archive).

## 2. Verify on ASE (blocks real use)

- [x] **Cancel:** run `spikes/jconnect-cancel` (`waitfor delay` cancelled at the deadline). Check that the
      statement stops on the server (`sysprocesses`), and that the evicted connection is not reused.
      Check `CANCEL_ALL` and `QUERY_TIMEOUT_CANCELS_ALL`.
      2026-11-17 on dev ASE (jConnect 16, Oct 2022): `Statement.cancel()` (promise and direct) stops the
      statement on the server within ~0.5 s and the connection stays usable, with CANCEL_ALL true or false. A bare
      `setQueryTimeout` stops reading only: the spid stays in WAITFOR. With `QUERY_TIMEOUT_CANCELS_ALL=true` it stops
      too, so the ASE dialect now sets that by default, and timed-out connections are evicted. Spindle's own
      deadline cancels at the step timeout anyway (flow 504 after ~5.4 s, nothing left running).
- [x] **Explain:** showplan with `set noexec on` during reload; check the archived plan in `SPINDLE_EXPLAIN_DIR`.
      2026-11-17: the integration test archives a showplan on reload and it passes on dev ASE.
- [ ] **`:proc`:** (2026-11-17, read-only with system procedures on dev ASE; integration test covers them)
  - [x] several result sets named with `:results` (`sp_helpindex`: 3 sets);
  - [x] return status with `:status` / `:fail-on-status :non-zero` (`sp_getmessage` of an unknown number → 1 → 502);
  - [x] OUT parameters (`sp_getmessage` → `{:msg "timeouts" :status 0}`);
  - [x] an undeclared extra result set fails ("procedure returned 3 result sets; the step names 2");
  - [x] `IGNORE_DONE_IN_PROC`: with jConnect's default the in-procedure row counts hide the result sets and
        multi-set procedures fail. The ASE dialect now sets it to `true` by default.
  - [x] real read-only business procedures on the company database (2026-11-17), all 200 through `:proc`:
        a named result set, OUT parameters, a date string parameter, and a non-zero status → 502.
        Caveat: some procedures return `@@rowcount` as their status, so `:fail-on-status` must not be used there.
        Details stay in the company's private notes, not in this public repository.
  - [ ] writing procedures: skipped for now (needs the test database), with the transaction-mode item below.
- [ ] **Writes (mutation endpoints):**
  - [ ] update + read-back in one transaction;
  - [ ] rollback on failure;
  - [ ] `:expect {:rows 1}`.
- [ ] **Procedure transaction mode:** a procedure created in unchained mode fails inside the chained-mode
      transaction that jConnect opens (`sp_procxmode`). Decide between setting procedures to `anymode` and
      adding a step option such as `:autocommit true` (designed in §5.2, **not built**).
- [ ] **Idempotency and audit tables on ASE:** create them from the DDL in `src/spindle/mutation.clj`
      (adjust types: `text`, `timestamp` → `datetime`). Check that duplicate-key detection works (error 2601/2627).
- [ ] **Paging on ASE:** use `top` or `set rowcount` instead of `limit` with `[:page :limit]`.
      Write an example flow in the cookbook.
      2026-11-17: not supported, by decision. `top` takes no parameter and the first page binds NULL
      (source_error). On ASE use a fixed `top` ceiling + `:take [:params :limit]` + a `before` key with a default
      (cookbook §8); the dev orders endpoint allows up to 5000 rows.
- [x] **Long `IN` lists:** confirm chunks of 250 and the plans; try `:parallel` on chunked steps.
      2026-11-17: 600 keys → 3 chunks of 250, all 600 rows, 2.2 s; with `:parallel {:dev 2}` 1.7 s.
      Plans for chunked statements not yet inspected.
- [x] **NULL binding:** `setNull` fallback (JZ0SE) with `isnull(?, ...)` patterns.
      2026-11-17: cause found. ASE has no type-neutral NULL: `setNull(VARCHAR)` fails against an int column
      ("Implicit conversion from VARCHAR to INT"), `Types/NULL`/`OTHER` are unsupported, parameter metadata
      cannot resolve markers. Fixed: a missing `[:params k]` value is bound as NULL of the type its schema
      implies (int → INTEGER, double → DOUBLE, boolean → BIT; strings and dates stay VARCHAR).
      `[:page …]` and step-output inputs are still untyped. Integration test covers it.

## 3. First real graph (the first-release goal)

- [ ] Pick one Semalith graph and write its flows: one business object per flow, `:views` for projections,
      `:batch` for keys Semalith resolves in bulk.
- [ ] Measure before/after: upstream call count, p95 latency, ASE lock waits.
- [ ] Use `?trace=true` (scope `spindle:trace`) and the slow-step log (`SPINDLE_SLOW_MS`) while tuning.
- [ ] Decide JWT vs API keys for Semalith → Spindle; for JWT, configure the issuer in `auth.edn` `:jwt`.

## 4. Instruction set: next improvements (from the 2026-11-17 review)

- [ ] **Composite keys in data paths:** `[:lines [:order_id :line_no]]` → pairs; on ASE expand to `OR`-ed pairs
      or a temp table.
- [ ] **Expressions as `:in` values** (`[:op …]`), for values computed between steps.
- [ ] **`:examples` with expected results:** run them on publish (like dossier's sample checks); lint paths
      and output fields against the columns seen.
- [ ] **Unify "no data / failure" handling:** today `:when`/`:default`, `:skip-when-empty`, `:or-else`, `:optional`
      and `:empty-on-404` are separate. Decide whether to group them as one `:on {:skipped … :empty … :error …}`.
      Consider `:or-else` on failure as well.
- [ ] **Single-row not found:** `:on-empty :not_found` → 404 instead of `rows: null`.

## 5. Order-desk needs (from a review of real customer requests, 2026-11-19)

A sample of a shared order-desk mailbox's requests was checked against the first graph's flows. Order status is
covered: finding an order by our number, the customer's PO or the vendor PO; the vendor ETA with its next action;
cartons, carrier, tracking and proof of delivery; quote lines and the orders a quote became; parts, stock, open POs
and lead times. Most order-status and lead-time questions can be answered with that.

The new items below are **flows** (flow configuration, kept in the company's own configuration, not in this
repository). They need no new engine features, so the feature freeze above does not block them. Checking them needs
real data (see the precondition at the end).

**Flows the requests need, most important first:**

- [ ] **Order holds and line states:** why an order is held (credit, pricing, compliance, stock), and each line's
      state (allocated, back ordered, non-cancellable). Requests ask why an order is stuck, or whether a released
      account's held orders can ship now.
- [ ] **Invoices and serial numbers:** an invoice by its number, its lines, and the serial numbers per carton (by order
      or carton). Requests dispute quantities on an invoice or ask when it was billed.
- [ ] **Returns (RMA):** by return request number or by order: status, labels / call tags, receipt, credit memo.
      Returns must be followed until closed, and today this means looking in another system every day.
- [ ] **Quotes by the number customers write**, the quote's validity, and deal / bid registrations with their expiry
      (today only by internal id or by customer).
- [ ] **Freight on an order or invoice**, and the free-freight rule.
- [ ] **Warehouse cut-off times** (may be configuration data rather than a query): ship from a warehouse that has not
      cut off yet.
- [ ] **Product extras:** replacement for an end-of-life part, warranty / service pack to product fit, country of
      origin, export classification.
- [ ] **Who owns an account** (sales rep, desk): the account procedure flow exists but needs the real database.
- [ ] **Look up by the vendor's own order number.**

**On the graph side** (Semalith, no new flows needed):

- [ ] Link the entities: order line → product / stock / lead time / open PO; part match → product / stock / lead time;
      order → customer; product → vendor. Then "when does it arrive, is it in stock" is one query instead of 3–4
      rounds (most of an assistant's 20–56 s per answer).
- [ ] Saved queries for the common requests: an order in full (state, lines, shipments, ETA, next action) and supply
      for a part (stock, open POs, lead time).
- [ ] Extend `next_action` to the new objects: who handles a hold, what a return waits for, who takes an invoice
      dispute.

**Writes (later):** new orders, changes and cancellations are a large share of the requests. Start with one common,
reversible write (for example cancelling a back-ordered line, or changing the ship-to address): declared as a
mutation in the graph, drafted by the assistant and executed only after a person confirms.

- [ ] **Precondition:** the flows run on a test sample today, so an order can be missing and the account procedure
      cannot run. Checking holds, invoices / serials and returns needs the real database, or a sample that covers the
      mailbox's last 90 days of orders.

Suggested order: holds and line states, invoices and serial numbers, returns, together with the graph links.

## 6. Later

- [ ] `:search` (Elasticsearch / OpenSearch), design §5.6.
- [ ] `/catalog/<endpoint-id>?format=semalith` (ready-to-paste operation EDN).
- [ ] `POST /admin/explain/<endpoint-id>?analyze=true`.
- [ ] Shared idempotency store for connections that cannot hold the table (design §23, question 2).
- [ ] MySQL / ClickHouse: on hold until needed.
