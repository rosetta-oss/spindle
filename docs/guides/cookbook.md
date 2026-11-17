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

---

## 1. Projection: split one heavy API into small endpoints

**When to use it:** an upstream returns header and details together, but callers need them separately
(a graph asks for the header first and the lines later, or both at once).

```clojure
;; flows/orders/header.edn
{:params [:map [:id pos-int?]]
 :steps [{:id :order :kind :api :conn :orders_api
          :request {:method :get :path "/orders/{id}" :path-params {:id [:params :id]}}
          :rows "$.header"
          :cache {:ttl "30s" :stale-if-error "5m"}}]
 :output {:from :order :one? true}
 :endpoint {:path "/orders/{id}/header" :scopes #{"orders:read"}}}

;; flows/orders/lines.edn — same :request, different projection
{:params [:map [:id pos-int?]]
 :steps [{:id :order :kind :api :conn :orders_api
          :request {:method :get :path "/orders/{id}" :path-params {:id [:params :id]}}
          :rows "$.details[*]"
          :cache {:ttl "30s"}}]
 :output {:from :order :select [:line :sku :qty]}
 :endpoint {:path "/orders/{id}/lines" :scopes #{"orders:read"}}}
```

**Why it works:** the step cache key is the connection plus the resolved request, and it stores the response *body*.
The `:rows` projection runs after the cache, so both endpoints share one entry, and single-flight merges
concurrent calls. The upstream sees one call per order per TTL.

**Watch out:**

- Keep the `:request` maps identical, or the endpoints get different cache keys.
- If the upstream answer depends on the user, see pattern 5.

## 2. Conditional enrichment: SQL first, API only when needed

**When to use it:** call a slow or expensive API only when the database says it is relevant.

```clojure
{:params [:map [:id string?]]
 :steps [{:id :cust :kind :sql :conn :erp :in {:id [:params :id]}}
         {:id :credit :kind :api :conn :credit_api
          :when {:exists [:cust]}                     ; skipped → :default (default [])
          :request {:method :get :path "/credit/{id}" :path-params {:id [:cust :customer_id]}}
          :rows "$"}]
 :output {:from :cust :one? true
          :nest {:credit {:from :credit :on {:customer_id :customer_id} :one? true :select [:limit]}}}
 :endpoint {:path "/customers/{id}/with-credit" :scopes #{"customers:read"}}}
```

**Conditions:** `{:exists p}`, `{:empty p}`, `{:= [p v]}`, `{:all [..]}`, `{:any [..]}`, `{:not c}`.
A skipped step returns its `:default`, which is `[]` unless you set it. The output keeps the same shape either way.

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
- Rows echo the key, so the caller can regroup them.

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

## 8. Raw search results merged with SQL and API (*roadmap*: `:search`)

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
- [ ] Do all SQL steps have a timeout and a row cap?
- [ ] Is there an `:examples` entry, so that explain uses realistic values?
- [ ] Are heavy API steps cached briefly, and do the endpoints share identical `:request` maps?
- [ ] Are caller-dependent endpoints set to `:caller :required` where needed?
- [ ] Are optional parts marked `:optional`, so that one upstream outage does not fail the whole answer?
