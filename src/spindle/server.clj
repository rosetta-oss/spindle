(ns spindle.server
  "HTTP: published endpoints, health, status, metrics, admin."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jsonista.core :as json]
            [reitit.core :as r]
            [ring.adapter.jetty9 :as jetty]
            [spindle.activity :as activity]
            [spindle.auth :as auth]
            [spindle.cache :as cache]
            [spindle.config :as config]
            [spindle.conn :as conn]
            [spindle.error :as err]
            [spindle.flow :as flow]
            [spindle.jsonout :as jsonout]
            [spindle.mcp :as mcp]
            [spindle.mutation :as mutation]
            [spindle.openapi :as openapi]
            [spindle.metrics :as metrics]
            [spindle.stepcache :as stepcache]
            [spindle.tokens :as tokens]
            [spindle.web :as web])
  (:import [java.time Instant LocalDate LocalDateTime LocalTime OffsetDateTime]
           [java.util UUID]))

(def ^:private mapper jsonout/mapper)

;; JSON integers read as Long, like query-string numbers: cache keys and key
;; lookups then agree whichever way a parameter arrived.
(def ^:private read-mapper
  (doto (json/object-mapper {:decode-key-fn true})
    (.configure com.fasterxml.jackson.databind.DeserializationFeature/USE_LONG_FOR_INTS true)))

(defn- json-resp
  ([status body] (json-resp status body nil))
  ([status body headers]
   {:status status
    :headers (merge {"Content-Type" "application/json"} headers)
    :body (json/write-value-as-string body mapper)}))

(defn- error-resp [e request-id]
  (let [{code :spindle/code :keys [step retry-after-s checks outcome committed]} (or (err/code-of e) {:spindle/code :internal})
        status (err/http-status code 500)]
    (when (= code :internal) (log/error e "internal error"))
    (json-resp status
               {:error (cond-> {:code (name code) :message (or (some-> (err/code-of e) :spindle/message)
                                                               (.getMessage ^Throwable e))
                                :request_id request-id}
                         step (assoc :step (name step))
                         (seq checks) (assoc :checks (mapv #(update % :result name) checks))
                         outcome (assoc :outcome (name outcome))
                         (seq committed) (assoc :committed (mapv name committed)))}
               (when retry-after-s {"Retry-After" (str retry-after-s)}))))

;; ---------------------------------------------------------------------------
;; parameters
;; ---------------------------------------------------------------------------

(defn- request-params [req match]
  (let [qp (into {} (map (fn [[k v]] [(keyword k) v])) (:query-params req))
        body (when (and (:body req) (#{:post :put :patch :delete} (:request-method req)))
               (let [s (slurp (:body req))] (when-not (str/blank? s) (json/read-value s read-mapper))))]
    (merge qp (when (map? body) body) (:path-params match))))

;; ---------------------------------------------------------------------------
;; endpoint handler
;; ---------------------------------------------------------------------------

(defn- query-string-params [req]
  (if-let [qs (:query-string req)]
    (into {} (for [pair (str/split qs #"&") :when (seq pair)
                   :let [[k v] (str/split pair #"=" 2)]]
               [(java.net.URLDecoder/decode ^String k "UTF-8") (java.net.URLDecoder/decode (str v) "UTF-8")]))
    {}))

(def trace-scope "spindle:trace")

(defn- authorize! [snap store ep req who]
  (let [caller (auth/authenticate (:auth snap) store req)]
    (vreset! who (:id caller))
    (when-not caller (err/fail! :unauthenticated "missing or invalid API key"))
    (when-not (auth/allowed? caller (:scopes ep)) (err/fail! :forbidden "missing scope"))
    (when (and (= :required (:caller ep)) (not (:sub caller)))
      (err/fail! :unauthenticated "this endpoint needs the end user (X-Spindle-User)"))
    caller))

(defn- trace-allowed? [snap caller raw]
  (and (= "true" (str (:trace raw)))
       (or (auth/allowed? caller #{trace-scope})
           (auth/allowed? caller #{(get-in snap [:auth :admin-scope] "spindle:admin")}))))

(defn- cache-key [fl params caller page]
  ;; the endpoint cache is per caller whenever any step depends on the caller
  [(:version fl) params (when (:caller-dependent? fl) (stepcache/caller-scope caller)) page])

(defn- meta-of [snap ep fl value cache-state t0]
  (cond-> {:endpoint (:id ep) :config_version (:version snap) :flow_version (:version fl)
           :cache (name cache-state) :elapsed_ms (quot (- (System/nanoTime) t0) 1000000)
           :partial (mapv #(update (update % :step name) :code name) (:partial value))}
    (seq (:checks value)) (assoc :checks (mapv #(update % :result name) (:checks value)))
    (:trace value) (assoc :trace (:trace value))))

;; --- keyset paging -----------------------------------------------------------
;; endpoint :page {:key [:col ...] :size 50 :max-size 500}; request ?size=&after=<cursor>
;; The root SQL binds [:page :limit] (size + 1) and [:page :after_<col>] (nil on page 1).

(defn encode-cursor [m]
  (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder))
                   (.getBytes (json/write-value-as-string m mapper) "UTF-8")))

(defn- decode-cursor [^String s]
  (try (let [m (json/read-value (String. (.decode (java.util.Base64/getUrlDecoder) (str/trim s)) "UTF-8") read-mapper)]
         (if (map? m) m (throw (ex-info "not a map" {}))))
       (catch Exception _ (err/fail! :input_rejected "invalid paging cursor"))))

(defn- page-env [{:keys [key size max-size] :or {size 50 max-size 500}} raw]
  (let [n (if-let [s (:size raw)]
            (or (when-let [v (parse-long (str s))] (when (<= 1 v max-size) v))
                (err/fail! :input_rejected (str "size must be 1.." max-size)))
            size)
        after (when-let [c (:after raw)] (when-not (str/blank? (str c)) (decode-cursor (str c))))]
    (when (and after (not= (set key) (set (keys after))))
      (err/fail! :input_rejected "paging cursor does not match this endpoint"))
    (into {:limit (inc n) :size n}
          (map (fn [k] [(keyword (str "after_" (name k))) (get after k)]) key))))

(defn- page-rows [{:keys [key]} {:keys [size]} rows]
  (if (> (count rows) size)
    (let [kept (subvec (vec rows) 0 size)
          last-row (peek kept)]
      (when-not (every? #(contains? last-row %) key)
        (err/fail! :internal (str "paged rows must carry the page key " (pr-str key))))
      {:rows kept :next (encode-cursor (into (sorted-map) (select-keys last-row key)))})
    {:rows (vec rows) :next nil}))

(declare run-read)

(defn- run-endpoint [snap store ep match req request-id who]
  (let [caller (authorize! snap store ep req who)
        fl (get-in snap [:flows (:flow ep)])
        raw (request-params (assoc req :query-params (query-string-params req)) match)
        ;; ?trace=true: how each step ran, for callers holding spindle:trace (or admin)
        trace? (trace-allowed? snap caller raw)
        page (when-let [pg (:page ep)] (page-env pg raw))
        params (flow/coerce-params (:params-schema fl) (dissoc raw :trace :size :after))
        t0 (System/nanoTime)]
    (if (:mutation ep)
      (mutation/run snap ep fl params caller request-id (get-in req [:headers "idempotency-key"])
                    #(meta-of snap ep fl % :none t0))
      (run-read snap ep fl params caller request-id trace? page t0))))

(defn- run-read [snap ep fl params caller request-id trace? page t0]
  (let [compute #(flow/execute snap fl params {:caller caller :request-id request-id :trace? trace?
                                                :page (some-> page (dissoc :size))})
        c (when-not trace? (get-in snap [:caches (:id ep)]))
        {:keys [value] cache-state :cache} (if c
                                            (cache/lookup c (cache-key fl params caller page) compute)
                                            {:value (compute) :cache :none})
        {:keys [rows next]} (if page (page-rows (:page ep) page (:rows value)) {:rows (:rows value)})]
    (when (= cache-state :stale) (metrics/counter! "spindle_cache_stale_served_total" {:endpoint (:id ep)}))
    {:body {:rows rows :next next :meta (meta-of snap ep fl value cache-state t0)}}))

;; --- batch route: POST /api/_batch/<endpoint-id> --------------------------------
;; The key is a list (JSON array or "1,2,3"); the flow runs once for the keys the
;; endpoint cache does not hold, rows are split per key (by :column, default the
;; key) and cached per key, so single and batch calls share entries.

(def batch-prefix "/api/_batch/")

(defn- run-batch [snap store ep req request-id who]
  (let [caller (authorize! snap store ep req who)
        fl (get-in snap [:flows (:flow ep)])
        {bk :key col :column} (:batch ep)
        col (or col bk)
        raw (request-params (assoc req :query-params (query-string-params req)) nil)
        params (flow/coerce-params (:params-schema fl) (dissoc raw :trace))
        ks (vec (distinct (get params bk)))
        _ (when (empty? ks) (err/fail! :input_rejected (str "batch key " (name bk) " is empty")))
        t0 (System/nanoTime)
        c (get-in snap [:caches (:id ep)])
        key-of (fn [k] (cache-key fl (assoc params bk [k]) caller nil))
        hits (if c (into {} (keep (fn [k] (when-let [v (cache/peek-fresh c (key-of k))] [k v]))) ks) {})
        missing (vec (remove #(contains? hits %) ks))
        value (when (seq missing)
                (flow/execute snap fl (assoc params bk missing) {:caller caller :request-id request-id}))
        by-key (group-by #(get % col) (:rows value))]
    (when (and c value (empty? (:partial value)) (empty? (:checks value)))
      (doseq [k missing] (cache/put! c (key-of k) {:rows (vec (get by-key k [])) :partial [] :checks []})))
    (when (and value (some #(not (contains? % col)) (:rows value)))
      (err/fail! :internal (str "batch rows must echo the key column " (name col))))
    {:body {:rows (vec (mapcat #(if-let [h (hits %)] (:rows h) (get by-key %)) ks))
            :next nil
            :meta (-> (meta-of snap ep fl (or value {}) (cond (empty? missing) :hit (empty? hits) (if c :miss :none) :else :partial) t0)
                      (assoc :batch {:keys (count ks) :cached (count hits) :fetched (count missing)}))}}))

;; ---------------------------------------------------------------------------
;; status
;; ---------------------------------------------------------------------------

(defn- breaker-info [rt]
  (let [st (conn/breaker-state rt)]
    (cond-> {:state (name st)}
      ;; time left before the next call may go through as a half-open trial
      (= st :open) (assoc :half_open_due_after_ms
                          (.toMillis (.getRemainingDelay ^dev.failsafe.CircuitBreaker (get-in rt [:guards :breaker])))))))

(defn status-body [node-id admin?]
  (let [snap (config/current)
        s @config/state
        lr (:last-reload s)]
    {:node node-id
     :config {:version (:version snap) :loaded_at (:loaded-at snap) :pinned (:pinned s)
              ;; problem details can name tables and columns: admins only
              :last_reload (if admin? lr (-> lr (dissoc :problems) (assoc :problem_count (count (:problems lr)))))
              :history (mapv :version (:history s))
              :explain (:explain snap)}
     :step_cache (into {} (map (fn [[[ep st] m]] [(str ep "#" st) m])) (stepcache/stats))
     :endpoints (mapv (fn [ep] {:id (:id ep) :method (name (:method ep)) :path (:path ep)}) (:endpoints snap))
     :connections
     (vec (for [[id rt] (:conns snap)]
            (cond-> {:id (name id) :type (name (:kind rt))
                     :breaker (breaker-info rt)
                     :bulkhead {:limit (get-in rt [:guards :bulkhead-limit])
                                :in_use (- (get-in rt [:guards :bulkhead-limit])
                                           (.availablePermits ^java.util.concurrent.Semaphore (get-in rt [:guards :bulkhead])))
                                :queued (.getQueueLength ^java.util.concurrent.Semaphore (get-in rt [:guards :bulkhead]))}
                     :last_error (:last-error @(:state rt))}
              (:ds rt) (assoc :pool (conn/pool-stats rt)))))
     :caches (into {} (map (fn [[id c]] [id (cache/stats c)])) (:caches snap))}))

(defn- register-gauges! []
  ;; gauges look up the *current* snapshot, so they survive reloads
  (doseq [[id _] (:conns (config/current))]
    (let [cid (name id)
          rt #(get-in (config/current) [:conns id])]
      (metrics/gauge! "spindle_breaker_state" {:conn cid}
                      #(when-let [r (rt)] ({:closed 0 :half-open 1 :open 2} (conn/breaker-state r))))
      (metrics/gauge! "spindle_bulkhead_in_use" {:conn cid}
                      #(when-let [r (rt)] (- (get-in r [:guards :bulkhead-limit])
                                             (.availablePermits ^java.util.concurrent.Semaphore (get-in r [:guards :bulkhead])))))
      (metrics/gauge! "spindle_bulkhead_queued" {:conn cid}
                      #(when-let [r (rt)] (.getQueueLength ^java.util.concurrent.Semaphore (get-in r [:guards :bulkhead])))))))

(defn- admin? [snap store req]
  (auth/allowed? (auth/authenticate (:auth snap) store req) #{(get-in snap [:auth :admin-scope] "spindle:admin")}))

;; ---------------------------------------------------------------------------
;; token admin
;; ---------------------------------------------------------------------------

(def ^:private token-id-re #"^[a-z0-9][a-z0-9._-]{1,62}$")

(defn- bad-request [msg] (json-resp 400 {:error {:code "bad_request" :message msg}}))

(defn- conflict-resp [] (json-resp 409 {:error {:code "conflict" :message "id already in use"}}))

(defn- read-json-body [req]
  (try
    (let [s (if (:body req) (slurp (:body req)) "")]
      (if (str/blank? s) {} (json/read-value s read-mapper)))
    (catch Exception _ ::invalid)))

(defn- issue-token [snap store caller req]
  (let [body (read-json-body req)
        {:keys [id scopes expires_at]} (when (map? body) body)
        by (:id caller)]
    (cond
      (not (map? body)) (bad-request "invalid JSON body")
      (not (and (string? id) (re-matches token-id-re id))) (bad-request "id must match ^[a-z0-9][a-z0-9._-]{1,62}$")
      (not (and (sequential? scopes) (seq scopes) (every? string? scopes))) (bad-request "scopes must be a non-empty list of strings")
      (not (or (nil? expires_at) (string? expires_at))) (bad-request "expires_at must be an ISO-8601 instant")
      (some #(= id (:id %)) (get-in snap [:auth :keys])) (conflict-resp)
      :else
      (try
        (let [r (tokens/issue! store {:id id :scopes (vec scopes) :expires-at expires_at :created-by by})]
          (log/info (str "event=token-issued id=" id " by=" by))
          (json-resp 201 (select-keys r [:id :token :scopes :expires_at])))
        (catch clojure.lang.ExceptionInfo e
          (if (:conflict (ex-data e))
            (conflict-resp)
            (throw e)))
        (catch java.time.DateTimeException _ (bad-request "expires_at must be an ISO-8601 instant"))))))

(defn- tokens-uri?
  "Exactly /admin/tokens or below it: /admin/tokensX is not the token API."
  [uri]
  (or (= uri "/admin/tokens") (str/starts-with? uri "/admin/tokens/")))

(defn- token-admin
  "`caller` is the admin already authenticated by the router (authenticated once
   per request: a store lookup can touch the token row)."
  [snap store caller req]
  (let [uri (:uri req) method (:request-method req)]
    (cond
      (and (= uri "/admin/tokens") (= method :get))
      (json-resp 200 {:tokens (tokens/list-tokens store)
                      :config_keys (mapv (fn [k] {:id (:id k) :scopes (vec (sort (:scopes k))) :read_only true})
                                         (get-in snap [:auth :keys]))
                      :admin_scope (get-in snap [:auth :admin-scope] "spindle:admin")})

      (and (= uri "/admin/tokens") (= method :post)) (issue-token snap store caller req)

      (and (= method :delete) (str/starts-with? uri "/admin/tokens/"))
      (let [id (try (java.net.URLDecoder/decode (subs uri (count "/admin/tokens/")) "UTF-8")
                    (catch IllegalArgumentException _ nil))]
        (if (and id (re-matches token-id-re id) (tokens/revoke! store id))
          (do (log/info (str "event=token-revoked id=" id " by=" (:id caller)))
              (json-resp 200 {:revoked true}))
          (json-resp 404 {:error {:code "not_found" :message "no such live token"}})))

      :else (json-resp 404 {:error {:code "not_found"}}))))

;; --- console (static admin UI) ---

(def ^:private console-types
  {"html" "text/html; charset=utf-8" "js" "text/javascript; charset=utf-8" "css" "text/css; charset=utf-8"})

(defn- console-resp [uri]
  (let [rel (-> uri (subs (count "/console")) (str/replace #"^/" ""))
        rel (if (str/blank? rel) "index.html" rel)
        ext (last (str/split rel #"\."))
        res (when (and (not (some #{".." "." ""} (str/split rel #"/" -1)))
                       (re-matches #"[A-Za-z0-9_.\-/]+" rel)
                       (contains? console-types ext))
              (io/resource (str "console/" rel)))]
    (if res
      {:status 200
       :headers {"Content-Type" (console-types ext)
                 ;; default-src does not cover framing: refuse it explicitly (clickjacking)
                 "Content-Security-Policy" "default-src 'self'; frame-ancestors 'none'"
                 "Cache-Control" "no-store"
                 "X-Content-Type-Options" "nosniff"}
       :body (io/input-stream res)}
      (json-resp 404 {:error {:code "not_found"}}))))

(defn- endpoint-call
  "Run one published call (`f` takes the `who` volatile) with metrics, activity and errors."
  [ep request-id t0 f]
  (let [snap (config/current)
        ^java.util.concurrent.atomic.AtomicLong inflight (:inflight snap)
        who (volatile! nil)]
    (.incrementAndGet inflight)
    (try
      (let [{:keys [body status headers]} (f who)]
        (metrics/timer! "spindle_request_seconds" {:endpoint (:id ep) :outcome "ok"} (- (System/nanoTime) t0))
        (activity/record! {:endpoint (:id ep) :ms (/ (- (System/nanoTime) t0) 1e6) :caller @who :request-id request-id})
        (json-resp (or status 200) body headers))
      (catch Throwable e
        (let [code (or (:spindle/code (err/code-of e)) :internal)]
          (metrics/timer! "spindle_request_seconds" {:endpoint (:id ep) :outcome "error"} (- (System/nanoTime) t0))
          (metrics/counter! "spindle_request_errors_total" {:endpoint (:id ep) :code (name code)})
          (activity/record! {:endpoint (:id ep) :ms (/ (- (System/nanoTime) t0) 1e6) :code code :caller @who :request-id request-id}))
        (error-resp e request-id))
      (finally (.decrementAndGet inflight)))))

(defn- handler* [{:keys [node-id config-dir store]} mcp-handler]
  (fn [req]
    (let [request-id (str (UUID/randomUUID))
          uri (:uri req)
          snap (config/current)
          t0 (System/nanoTime)]
      (try
        (cond
          (= uri "/health/live") (json-resp 200 {:status "live"})
          (= uri "/health/ready")
          (if (and snap (seq (:conns snap))
                   (not (every? #(= :open (conn/breaker-state %)) (vals (:conns snap)))))
            (json-resp 200 {:status "ready" :config_version (:version snap)})
            (json-resp 503 {:status "not_ready"}))
          (= uri "/metrics") {:status 200 :headers {"Content-Type" "text/plain; version=0.0.4"} :body (metrics/scrape)}
          (= uri "/status") (json-resp 200 (status-body node-id (admin? snap store req)))
          ;; home: the console is the front page
          (and (= uri "/") (= :get (:request-method req))) {:status 302 :headers {"Location" "/console"} :body ""}

          (or (= uri "/console") (str/starts-with? uri "/console/"))
          (if (web/secure? req) (console-resp uri) (web/insecure-resp))

          (and (or (tokens-uri? uri) (= uri "/admin/activity")) (not (web/secure? req))) (web/insecure-resp)

          (or (= uri "/mcp") (str/starts-with? uri "/mcp/"))
          (if (web/secure? req)
            (or (mcp-handler req) (json-resp 404 {:error {:code "not_found"}}))
            (web/insecure-resp))

          (str/starts-with? uri "/admin/")
          (let [caller (auth/authenticate (:auth snap) store req)]
            (if-not (auth/allowed? caller #{(get-in snap [:auth :admin-scope] "spindle:admin")})
              (json-resp 403 {:error {:code "forbidden" :message "admin scope required"}})
              (case (if (tokens-uri? uri) :tokens [(:request-method req) uri])
                :tokens (if store (token-admin snap store caller req) (json-resp 404 {:error {:code "not_found"}}))
                [:post "/admin/reload"] (let [r (config/with-write-lock (config/reload! config-dir))] (register-gauges!) (json-resp (if (:ok r) 200 422) r))
                [:post "/admin/config/rollback"]
                (let [v (get (query-string-params req) "to")
                      r (config/with-write-lock (config/rollback! v))] (json-resp (if (:ok r) 200 422) r))
                [:get "/admin/activity"] (json-resp 200 (activity/snapshot))
                [:post "/admin/config/unpin"] (do (config/unpin!) (json-resp 200 {:pinned false}))
                [:post "/admin/cache/evict"]
                (let [tags (remove str/blank? (str/split (str (get (query-string-params req) "tag")) #","))]
                  (json-resp 200 {:evicted (stepcache/evict-tags! tags)}))
                (json-resp 404 {:error {:code "not_found"}}))))

          (= uri "/openapi.json")
          (if (auth/authenticate (:auth snap) store req)
            (json-resp 200 (openapi/document snap))
            (json-resp 401 {:error {:code "unauthenticated" :message "missing or invalid API key"}}))

          (str/starts-with? uri batch-prefix)
          (let [id (subs uri (count batch-prefix))
                ep (some #(when (and (= id (:id %)) (:batch %)) %) (:endpoints snap))]
            (cond
              (not ep) (json-resp 404 {:error {:code "not_found" :message "no batch endpoint with this id"}})
              (not= :post (:request-method req)) (json-resp 405 {:error {:code "method_not_allowed"}})
              :else (endpoint-call ep request-id t0 #(run-batch snap store ep req request-id %))))

          :else
          (if-let [match (and snap (r/match-by-path (:router snap) uri))]
            (let [ep (get-in match [:data :spindle.config/endpoint])]
              (if (not= (:request-method req) (:method ep))
                (json-resp 405 {:error {:code "method_not_allowed"}})
                (endpoint-call ep request-id t0 #(run-endpoint snap store ep match req request-id %))))
            (json-resp 404 {:error {:code "not_found" :message "no such endpoint"}})))
        (catch Throwable e (error-resp e request-id))))))

(defn handler [opts]
  (handler* opts (mcp/handler opts)))

(defn start! [{:keys [port] :as opts}]
  (register-gauges!)
  (jetty/run-jetty (handler opts) {:port port :join? false :virtual-threads? true}))

(def refresh-gauges! register-gauges!)
