(ns spindle.server
  "HTTP: published endpoints, health, status, metrics, admin."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jsonista.core :as json]
            [malli.core :as m]
            [malli.transform :as mt]
            [reitit.core :as r]
            [ring.adapter.jetty9 :as jetty]
            [spindle.auth :as auth]
            [spindle.cache :as cache]
            [spindle.config :as config]
            [spindle.conn :as conn]
            [spindle.error :as err]
            [spindle.flow :as flow]
            [spindle.metrics :as metrics]
            [spindle.stepcache :as stepcache]
            [spindle.tokens :as tokens]
            [spindle.web :as web])
  (:import [java.time Instant LocalDate LocalDateTime LocalTime OffsetDateTime]
           [java.util UUID]))

(def ^:private mapper
  (json/object-mapper
   {:encoders {LocalDate (fn [v ^com.fasterxml.jackson.core.JsonGenerator g] (.writeString g (str v)))
               LocalDateTime (fn [v ^com.fasterxml.jackson.core.JsonGenerator g] (.writeString g (str v)))
               LocalTime (fn [v ^com.fasterxml.jackson.core.JsonGenerator g] (.writeString g (str v)))
               OffsetDateTime (fn [v ^com.fasterxml.jackson.core.JsonGenerator g] (.writeString g (str v)))
               Instant (fn [v ^com.fasterxml.jackson.core.JsonGenerator g] (.writeString g (str v)))}}))

(def ^:private read-mapper (json/object-mapper {:decode-key-fn true}))

(defn- json-resp
  ([status body] (json-resp status body nil))
  ([status body headers]
   {:status status
    :headers (merge {"Content-Type" "application/json"} headers)
    :body (json/write-value-as-string body mapper)}))

(defn- error-resp [e request-id]
  (let [{code :spindle/code :keys [step retry-after-s]} (or (err/code-of e) {:spindle/code :internal})
        status (err/http-status code 500)]
    (when (= code :internal) (log/error e "internal error"))
    (json-resp status
               {:error (cond-> {:code (name code) :message (or (some-> (err/code-of e) :spindle/message)
                                                               (.getMessage ^Throwable e))
                                :request_id request-id}
                         step (assoc :step (name step)))}
               (when retry-after-s {"Retry-After" (str retry-after-s)}))))

;; ---------------------------------------------------------------------------
;; parameters
;; ---------------------------------------------------------------------------

(defn- split-lists
  "Comma-separated strings for vector-typed params become vectors (Semalith's
   batch placeholder sends `1,2,3`)."
  [schema params]
  (if-not schema
    params
    (let [entries (when (= :map (m/type schema)) (m/children schema))
          list-keys (set (keep (fn [[k _ s]] (when (#{:vector :sequential :set} (m/type s)) k)) entries))]
      (reduce (fn [p k]
                (let [v (get p k)]
                  (cond (string? v) (assoc p k (vec (remove str/blank? (map str/trim (str/split v #",")))))
                        :else p)))
              params list-keys))))

(defn- request-params [req match]
  (let [qp (into {} (map (fn [[k v]] [(keyword k) v])) (:query-params req))
        body (when (and (:body req) (#{:post} (:request-method req)))
               (let [s (slurp (:body req))] (when-not (str/blank? s) (json/read-value s read-mapper))))]
    (merge qp (when (map? body) body) (:path-params match))))

(defn- coerce-params [schema raw]
  (let [raw (split-lists schema raw)
        v (m/decode schema raw (mt/transformer mt/string-transformer mt/strip-extra-keys-transformer))]
    (if (m/validate schema v)
      v
      (err/fail! :input_rejected (str "invalid parameters: "
                                      (pr-str (malli.error/humanize (m/explain schema v))))))))

;; ---------------------------------------------------------------------------
;; endpoint handler
;; ---------------------------------------------------------------------------

(defn- query-string-params [req]
  (if-let [qs (:query-string req)]
    (into {} (for [pair (str/split qs #"&") :when (seq pair)
                   :let [[k v] (str/split pair #"=" 2)]]
               [(java.net.URLDecoder/decode ^String k "UTF-8") (java.net.URLDecoder/decode (str v) "UTF-8")]))
    {}))

(defn- run-endpoint [snap store ep match req request-id]
  (let [caller (auth/authenticate (:auth snap) store req)]
    (when-not caller (err/fail! :unauthenticated "missing or invalid API key"))
    (when-not (auth/allowed? caller (:scopes ep)) (err/fail! :forbidden "missing scope"))
    (when (and (= :required (:caller ep)) (not (:sub caller)))
      (err/fail! :unauthenticated "this endpoint needs the end user (X-Spindle-User)"))
    (let [fl (get-in snap [:flows (:flow ep)])
          params (coerce-params (:params-schema fl) (request-params (assoc req :query-params (query-string-params req)) match))
          compute #(flow/execute snap fl params {:caller caller :request-id request-id})
          c (get-in snap [:caches (:id ep)])
          {:keys [value] cache-state :cache} (if c
                                              ;; the endpoint cache is per caller whenever any step depends on the caller
                                              (cache/lookup c [(:version fl) params
                                                               (when (:caller-dependent? fl) (stepcache/caller-scope caller))]
                                                            compute)
                                              {:value (compute) :cache :none})]
      (when (= cache-state :stale) (metrics/counter! "spindle_cache_stale_served_total" {:endpoint (:id ep)}))
      {:body {:rows (:rows value)
              :next nil
              :meta {:endpoint (:id ep) :config_version (:version snap) :flow_version (:version fl)
                     :cache (name cache-state) :partial (mapv #(update (update % :step name) :code name) (:partial value))}}})))

;; ---------------------------------------------------------------------------
;; status
;; ---------------------------------------------------------------------------

(defn- breaker-info [rt]
  (let [st (conn/breaker-state rt)]
    (cond-> {:state (name st)}
      (= st :open) (assoc :half_open_due_after_ms (get-in rt [:guards :breaker-delay-ms])))))

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

(defn- read-json-body [req]
  (try
    (let [s (if (:body req) (slurp (:body req)) "")]
      (if (str/blank? s) {} (json/read-value s read-mapper)))
    (catch Exception _ ::invalid)))

(defn- issue-token [snap store req]
  (let [body (read-json-body req)
        {:keys [id scopes expires_at]} (when (map? body) body)
        caller (:id (auth/authenticate (:auth snap) store req))]
    (cond
      (not (map? body)) (bad-request "invalid JSON body")
      (not (and (string? id) (re-matches token-id-re id))) (bad-request "id must match ^[a-z0-9][a-z0-9._-]{1,62}$")
      (not (and (sequential? scopes) (seq scopes) (every? string? scopes))) (bad-request "scopes must be a non-empty list of strings")
      (not (or (nil? expires_at) (string? expires_at))) (bad-request "expires_at must be an ISO-8601 instant")
      (some #(= id (:id %)) (get-in snap [:auth :keys])) (json-resp 409 {:error {:code "conflict" :message "id already in use"}})
      :else
      (try
        (let [r (tokens/issue! store {:id id :scopes (vec scopes) :expires-at expires_at :created-by caller})]
          (log/info (str "event=token-issued id=" id " by=" caller))
          (json-resp 201 (select-keys r [:id :token :scopes :expires_at])))
        (catch clojure.lang.ExceptionInfo e
          (if (:conflict (ex-data e))
            (json-resp 409 {:error {:code "conflict" :message "id already in use"}})
            (throw e)))
        (catch java.time.DateTimeException _ (bad-request "expires_at must be an ISO-8601 instant"))))))

(defn- token-admin [snap store req]
  (let [uri (:uri req) method (:request-method req)]
    (cond
      (and (= uri "/admin/tokens") (= method :get))
      (json-resp 200 {:tokens (tokens/list-tokens store)
                      :config_keys (mapv (fn [k] {:id (:id k) :scopes (vec (sort (:scopes k))) :read_only true})
                                         (get-in snap [:auth :keys]))
                      :admin_scope (get-in snap [:auth :admin-scope] "spindle:admin")})

      (and (= uri "/admin/tokens") (= method :post)) (issue-token snap store req)

      (and (= method :delete) (str/starts-with? uri "/admin/tokens/"))
      (let [id (subs uri (count "/admin/tokens/"))]
        (if (and (not (str/blank? id)) (tokens/revoke! store id))
          (do (log/info (str "event=token-revoked id=" id " by=" (:id (auth/authenticate (:auth snap) store req))))
              (json-resp 200 {:revoked true}))
          (json-resp 404 {:error {:code "not_found" :message "no such live token"}})))

      :else (json-resp 404 {:error {:code "not_found"}}))))

(defn handler [{:keys [node-id config-dir store]}]
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

          (and (str/starts-with? uri "/admin/tokens") (not (web/secure? req))) (web/insecure-resp)

          (str/starts-with? uri "/admin/")
          (if-not (admin? snap store req)
            (json-resp 403 {:error {:code "forbidden" :message "admin scope required"}})
            (case (if (str/starts-with? uri "/admin/tokens") :tokens [(:request-method req) uri])
              :tokens (if store (token-admin snap store req) (json-resp 404 {:error {:code "not_found"}}))
              [:post "/admin/reload"] (let [r (config/reload! config-dir)] (register-gauges!) (json-resp (if (:ok r) 200 422) r))
              [:post "/admin/config/rollback"]
              (let [v (get (query-string-params req) "to")
                    r (config/rollback! v)] (json-resp (if (:ok r) 200 422) r))
              [:post "/admin/config/unpin"] (do (config/unpin!) (json-resp 200 {:pinned false}))
              [:post "/admin/cache/evict"]
              (let [tags (remove str/blank? (str/split (str (get (query-string-params req) "tag")) #","))]
                (json-resp 200 {:evicted (stepcache/evict-tags! tags)}))
              (json-resp 404 {:error {:code "not_found"}})))

          :else
          (if-let [match (and snap (r/match-by-path (:router snap) uri))]
            (let [ep (get-in match [:data :spindle.config/endpoint])]
              (if (not= (:request-method req) (:method ep))
                (json-resp 405 {:error {:code "method_not_allowed"}})
                (let [^java.util.concurrent.atomic.AtomicLong inflight (:inflight snap)]
                  (.incrementAndGet inflight)
                  (try
                    (let [{:keys [body]} (run-endpoint snap store ep match req request-id)]
                      (metrics/timer! "spindle_request_seconds" {:endpoint (:id ep) :outcome "ok"} (- (System/nanoTime) t0))
                      (json-resp 200 body))
                    (catch Throwable e
                      (let [code (or (:spindle/code (err/code-of e)) :internal)]
                        (metrics/timer! "spindle_request_seconds" {:endpoint (:id ep) :outcome "error"} (- (System/nanoTime) t0))
                        (metrics/counter! "spindle_request_errors_total" {:endpoint (:id ep) :code (name code)}))
                      (error-resp e request-id))
                    (finally (.decrementAndGet inflight))))))
            (json-resp 404 {:error {:code "not_found" :message "no such endpoint"}})))
        (catch Throwable e (error-resp e request-id))))))

(defn start! [{:keys [port] :as opts}]
  (register-gauges!)
  (jetty/run-jetty (handler opts) {:port port :join? false :virtual-threads? true}))

(def refresh-gauges! register-gauges!)
