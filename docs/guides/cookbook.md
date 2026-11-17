# Spindle flow cookbook

These patterns are for the people and agents who write flows. Each pattern shows when to use it, the flow,
and what to watch out for. Patterns marked *roadmap* use features that are designed but not built yet.

**Ground rules:**

- **One business object per flow, fetched close to its source.** Composition across objects belongs to the caller's graph.
- **SQL runs exactly as written.** Spindle only binds parameters and expands `IN` lists.
- **Steps refer to each other with data paths** such as `[:params :id]`, `[:order :customer_id]` and
  `[:caller :claims :territory_id]`. There are no string templates.
- **Every SQL step needs a timeout and a row cap.** Put them on the connection's `:defaults` once and override per step if needed.
- **Prefer caching heavy API steps for a short time** (for example `{:ttl "30s"}`) over adding endpoint caches.
- **Keys are checked.** Flow, step, output and endpoint maps are closed: a misspelled key (`:optinal`, `:selct`)
  rejects the reload. Start each file with `:spindle/format 1`.
- **One language for logic.** Step conditions (`:when`), derived fields (`:derive`) and checks (`:assert`) all use
  `[:op …]` vectors. In `:when`, operands are data paths; in the result stage, keywords are the row's fields.

---

## 1. Projection: split one heavy API into small endpoints (`:views`)

**When to use it:** an upstream returns header and details together, but callers need them separately
(a graph asks for the header first and the lines later, or both at once).

```clojure
;; flows/orders/order.edn → flows orders/order/header and orders/order/lines
{:spindle/format 1
 :params [:map [:id pos-int?]]
 :steps [{:id :order :kind :api :conn :orders_api
          :request {:method :get :path "/orders/{id}" :path-params {:id [:params :id]}}
          :cache {:ttl "30s" :stale-if-error "5m"}}]
 :views {:header {:output {:from :order :one? true :select [:header]}
                  :endpoint {:path "/orders/{id}/header" :scopes #{"orders:read"}}}
         :lines  {:output {:from :order :select [:details]}
                  :endpoint {:path "/orders/{id}/lines" :scopes #{"orders:read"}}}}}
```

**Why it works:** the steps are written once. Each view becomes its own flow (`<file>/<view>`) that runs only the
steps its output reads. The step cache stores the response *body*, keyed by connection and resolved request, so
both views share one entry, and single-flight merges concurrent calls: one upstream call per order per TTL.
(Two separate flows with identical `:request` maps share the entry too; `:views` just avoids the copy.)

**Watch out:** if the upstream answer depends on the user, see pattern 5.

## 2. Conditional enrichment: SQL first, API only when needed

**When to use it:** call a slow or expensive API only when the database says it is relevant.

```clojure
{:params [:map [:id string?]]
 :steps [{:id :cust :kind :sql :conn :erp :in {:id [:params :id]}}
         {:id :credit :kind :api :conn :credit_api
          :when [:exists [:cust]]                     ; skipped → :default (default [])
          :request {:method :get :path "/credit/{id}" :path-params {:id [:cust :customer_id]}}
          :rows "$"}]
 :output {:from :cust :one? true
          :nest {:credit {:from :credit :on {:customer_id :customer_id} :one? true :select [:limit]}}}
 :endpoint {:path "/customers/{id}/with-credit" :scopes #{"customers:read"}}}
```

**Conditions** use the `[:op …]` language with data paths as operands:
`[:exists p]`, `[:empty p]`, `[:= p v]`, `[:> p v]` (and `:< :<= :>= :not=`), `[:all c …]`, `[:any c …]`, `[:not c]`.
For example `[:all [:exists [:cust]] [:= [:cust :status] "A"]]`. A path into a step gives its value when there is
exactly one. The older map form (`{:exists p}`) still loads.

A skipped step returns its `:default`, which is `[]` unless you set it. The same holds when a step is skipped because
an input list is empty. The output keeps the same shape either way.

## 3. Batch expansion: many ids in one call

**When to use it:** a graph resolves 200 orders at once. Send one call, not 200.

```clojure
{:params [:map [:order_id [:vector {:min 1 :max 500} pos-int?]]]
 :steps [{:id :order :kind :sql :conn :erp :in {:order_id [:params :order_id]}}]
 :endpoint {:path "/api/sales/orders" :scopes #{"sales:read"} :batch {:key :order_id}}}
```

```sql
-- :name order
select order_id, customer_id, total from orders where order_id in :order_id
```

**How it works:**

- `?order_id=1,2,3` and JSON arrays both work. Semalith's api connector sends the comma form.
- `IN` lists are chunked per dialect (ASE 250, PostgreSQL 1000).
- Callers send `POST /api/_batch/<endpoint-id>` with `{"order_id": [1, 2, 3]}` (or `"1,2,3"`). With an endpoint
  `:cache`, keys already cached are not fetched again; `meta.batch` says how many were.
- Rows must echo the key (`:batch {:key :order_id :column :salesorderid}` when the column is named differently),
  so the caller can regroup them.

**Watch out:** on ASE, very long `IN` lists hurt plans. Keep `:max` modest. Staging the ids in a temp table
is on the roadmap.

## 4. Current table, else archive

**When to use it:** a row lives in a current table or, once it is old, in an archive table.

```clojure
{:steps [{:id :hdr      :kind :sql :conn :erp :in {:id [:params :id]} :or-else :hdr_arch}
         {:id :hdr_arch :kind :sql :conn :erp :in {:id [:params :id]}}]
 :output {:from :hdr :one? true}}
```

`:or-else` runs the fallback only when the primary returns no rows. Consumers of `:hdr` then see the
fallback's rows. If some keys of a batch are current and others archived, use one block with `union all`
instead.

## 4b. Keyset paging

```clojure
{:steps [{:id :orders :kind :sql :conn :erp
          :in {:after [:page :after_order_id] :limit [:page :limit]}}]
 :endpoint {:path "/orders" :page {:key [:order_id] :size 50 :max-size 500}}}
```

```sql
-- :name orders
select order_id, customer_id from orders
 where (cast(:after as int) is null or order_id > :after)
 order by order_id
 limit :limit
```

`[:page :limit]` is the page size + 1, so Spindle can tell whether there is a next page. `[:page :after_<key>]` is
NULL on the first page. The response carries `next`; callers pass it back as `?after=`, and `?size=` asks for a
smaller page, up to `:max-size`. On ASE, write `top` or `set rowcount` instead of `limit`.

## 5. Per-user data: identity passthrough

**When to use it:** results depend on who is asking: row-level security, or upstream permissions.

```clojure
;; connections.edn — forward the end user to an upstream
{:sales_api {:type :http :base-url "${SALES_API_URL}"
             :forward-caller {"X-User-Id" [:caller :sub]}}}

;; flow — a claim as a SQL parameter
{:steps [{:id :mine :kind :sql :conn :erp :in {:territory [:caller :claims :territory_id]}
          :cache {:ttl "30s"}}]                       ; automatically per caller
 :endpoint {:path "/customers/mine" :scopes #{"customers:read"} :caller :required}}
```

The caller (a service key with `spindle:delegate`) sends `X-Spindle-User` and `X-Spindle-User-Claims`
(base64url JSON). Any step that touches `[:caller …]`, or runs on a forwarding connection, is cached
per caller, so one user's rows are never served to another.

## 6. Upstream failure: degrade, don't fail

**When to use it:** part of the answer is optional, for example stock levels next to an order.

```clojure
{:id :stock :kind :api :conn :inventory_api :optional true
 :request {:method :get :path "/inventory" :query {"product_id" [:lines :productid]}}
 :rows "$[*]" :cache {:ttl "30s" :stale-if-error "10m"}}
```

**What happens when the upstream fails:**

1. If the cache holds an entry still within `:stale-if-error`, that entry is served.
2. Otherwise the step yields `nil`, and `meta.partial` lists it.
3. The rest of the flow still answers. An open breaker fails fast without touching the upstream.

## 7. Tuning a slow SQL step (ASE)

- **Isolation:** for data where dirty reads are acceptable, set `:tuning {:isolation :read-uncommitted}`, or write
  `at isolation read uncommitted` / `noholdlock` in the SQL. The default level 1 takes shared locks while reading.
- **Plans:** check `<SPINDLE_EXPLAIN_DIR>/<flow>/<version>/<step>.json` after each publish. Add `:examples`
  to the flow so that explain binds realistic values.
- **Hints:** write them in the SQL (`(index …)`); Spindle never rewrites SQL.
- **Row caps and timeouts:** keep them tight. A cancelled statement releases its locks, and Spindle evicts the
  connection if the cancel leaves it in an unknown state.

## 8. Optional filters and page size (ASE)

**When to use it:** a list endpoint with optional filters, a caller-chosen page size and "next page" paging.

```clojure
{:params [:map
          [:to_acct_no pos-int?]
          [:limit  {:optional true :default 50} [:int {:min 1 :max 500}]]
          [:before {:optional true :default 2147483647} pos-int?]
          [:from   {:optional true :default "1900-01-01"} [:re "^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$"]]]
 :steps [{:id :orders :kind :sql :conn :ase :take [:params :limit]
          :sql "select top 500 order_no, entry_datetime from order_header
                where to_acct_no = :to_acct_no and order_no < :before
                  and entry_datetime >= convert(datetime, :from)
                order by order_no desc"
          :in {:to_acct_no [:params :to_acct_no] :before [:params :before] :from [:params :from]}}]
 :endpoint {:path "/orders"}}
```

- **`:default` in the params schema** fills in a missing optional parameter, so the SQL needs no NULL handling. Prefer this on ASE.
- **`:take`** (a number or a `[:params …]` path) returns at most that many rows. The driver stops reading at N (`setMaxRows`; jConnect sends `set rowcount`). It is capped by the step's row cap and never fails with "row cap exceeded". ASE does not accept a parameter in `top`, so keep a fixed `top` as the ceiling.
- **Paging:** pass the last row's `order_no` as `before` for the next page. Every page is an index range, so later pages stay fast and new rows cause no duplicates.

## 8b. Finalize the result: derive and check

```clojure
:output {:from :order :one? true
         :nest   {:lines {:from :lines :on {:salesorderid :salesorderid}
                          :derive [[:amount [:round [:* :orderqty :unitprice] 2]]]}}
         :derive [[:lines_total [:sum :lines :amount]]
                  [:line_count  [:count :lines]]]
         :assert [[:= :subtotal :lines_total {:tolerance 0.01}]
                  [:present :customerid]]
         :on-fail :flag}
```

- **Expressions:** `:+ :- :* :/ :round :coalesce :if :concat`, and over nested rows `[:sum :part :f]`,
  `[:count :part]`, `[:get :part :f]`. `+` sums what is present; the others need every operand.
- **Assertions:** `:= :not= :< :<= :> :>=` (numbers within `:tolerance`), `:present`, `:all :any :not`. Each is
  pass, fail or unknown (a value it needs is missing).
- **`:on-fail :flag`** lists failures in `meta.checks`; **`:error`** fails the request with `assertion_failed` (502).
- **`:merge`** takes exactly one matching row. Several matches are an error; use `:nest`, or `:pick :first` when
  any one will do.
- Callers with scope `spindle:trace` can add `?trace=true` to see, per step, its kind, connection, time, rows,
  cache state and whether it was skipped.

## 8c. Stored procedures and functions (`:proc`)

```clojure
{:id :bom :kind :proc :conn :erp :read-only true
 :call "dbo.usp_bom"                                   ; a plain name; Spindle builds {call …}
 :args [:product_id :as_of {:out :row_count :type :integer}]   ; in order: inputs from :in, OUT parameters
 :in {:product_id [:params :id] :as_of [:params :as_of]}
 :status :status :fail-on-status :non-zero             ; {? = call …}; non-zero fails the request
 :results [:header :components]}                       ; result sets, in order → named row sets
```

- Later steps and the output read `:header` and `:components` like steps; the step's own id holds one row with the
  status and the OUT values. Without `:results`, the step returns its only result set (or that one row).
- An undeclared extra result set is an error; a missing one is empty. Every result set has the row cap.
- OUT type `:cursor` (PostgreSQL refcursor) is read as a named row set too.
- **PostgreSQL:** a set-returning function is simplest as `:sql` (`select * from f(:id)`); a `CREATE PROCEDURE`
  needs `:procedure true` (native `CALL`; OUT values come back as its row).
- A procedure counts as a write unless `:read-only true`; writing procedures belong in mutation endpoints.
- **ASE:** the dialect sets jConnect's `IGNORE_DONE_IN_PROC=true`; without it the row counts of statements inside a
  procedure hide its result sets. Don't turn it off in `:properties`.
- Check what a procedure returns before using `:fail-on-status :non-zero`: some return a row count or a code
  (e.g. `return @@rowcount`), and then success is non-zero.

## 8d. Writes (mutation endpoints)

```clojure
{:spindle/format 1
 :params [:map [:id pos-int?] [:comment [:string {:max 128}]]]
 :steps [{:id :upd :kind :sql :conn :erp :expect {:rows 1}
          :in {:id [:params :id] :comment [:params :comment]}}
         {:id :order :kind :sql :conn :erp :after [:upd] :in {:id [:params :id]}}]
 :output {:from :order :one? true}
 :endpoint {:path "/orders/{id}/comment" :method :put :mutation true :scopes #{"orders:write"}
            :idempotency {:conn :erp :table "spindle_idempotency"}
            :audit {:conn :erp :table "spindle_audit"}
            :evicts ["orders"]}}
```

- All SQL and `:proc` steps on one connection share **one transaction**; reads in it see the writes. Commit happens
  after every step and the output succeeded; anything else rolls back. Responses and errors say
  `outcome`: `committed`, `rolled_back`, or `partial` (a later connection's commit failed; avoid writing to two).
- `:expect {:rows n}`: another count rolls back with 404 (none) or 409.
- `:after [:step]` orders steps that no data path links (a read-back after a write).
- `Idempotency-Key`: stored with the response in the same transaction; a repeat returns it, a different request
  with the same key is 409. The tables' DDL is in `src/spindle/mutation.clj`; Spindle never creates tables.
- Never cached, never `:optional`, no `:parallel` on the written connection. HTTP writes (PUT/PATCH/DELETE) cannot
  be rolled back: put them last, or make the upstream idempotent.
- A trial run (MCP `test_run`) of a mutation flow always rolls back.

## 8e. Many calls at once

- **Fan-out:** `{:fan-out [:lines :productid] :concurrency 4}` calls the API once per value, up to 4 at once, results
  in order. The connection's `:guard {:concurrency n}` still bounds calls across all requests.
- **Long `IN` lists:** chunks (ASE 250, PostgreSQL 1000, or `:tuning {:max-in n}`) run in parallel when the flow sets
  `:parallel {:erp 2}`; by default one statement per connection per request.

## 9. Raw search results merged with SQL and API (*roadmap*: `:search`)

```clojure
{:steps [{:id :hits :kind :search :conn :es
          :query {:query {:match {:text [:params :q]}} :size 20}
          :rows "$.hits.hits[*]._source"}
         {:id :products :kind :sql :conn :erp :in {:ids [:hits :product_id]}}
         {:id :stock :kind :api :conn :inventory_api :optional true
          :request {:method :get :path "/inventory" :query {"product_id" [:hits :product_id]}} :rows "$[*]"}]
 :output {:from :hits
          :merge {:from :products :on {:product_id :product_id} :select [:name :list_price]}}}
```

Query DSL is EDN data, with data paths at value positions. ES|QL takes `?` parameters. Paging uses
`search_after` plus a point in time (PIT).

---

## Checklist before publishing

- [ ] Does the flow fetch one business object? If not, split it and let the graph compose.
- [ ] Does the file start with `:spindle/format 1`, and does it reload without problems?
- [ ] Several projections of one source? Use `:views`, not copies of the same steps.
- [ ] Do all SQL steps have a timeout and a row cap?
- [ ] Is there an `:examples` entry, so that explain uses realistic values?
- [ ] Are heavy API steps cached briefly, and do the endpoints share identical `:request` maps?
- [ ] Are caller-dependent endpoints set to `:caller :required` where needed?
- [ ] Are optional parts marked `:optional`, so that one upstream outage does not fail the whole answer?
