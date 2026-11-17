(ns spindle.conn
  "Runtime connections: one pool (for :sql) or HTTP client (for :http), plus
   one set of guards — rate limiter, bulkhead, circuit breaker — per connection.

   Guard order per call: breaker open? → rate limit → bulkhead → breaker permit → call.
   (The breaker is checked first so that an open breaker fails fast without
   queueing; the permit is taken after the bulkhead so a half-open trial is not
   lost to a bulkhead rejection.)"
  (:require [clojure.tools.logging :as log]
            [diehard.spec]
            [diehard.circuit-breaker :as dcb]
            [diehard.rate-limiter :as drl]
            [spindle.dialect :as dialect]
            [spindle.error :as err]
            [spindle.metrics :as metrics])
  (:import [com.zaxxer.hikari HikariConfig HikariDataSource]
           [com.zaxxer.hikari.metrics.micrometer MicrometerMetricsTrackerFactory]
           [dev.failsafe CircuitBreaker]
           [java.net.http HttpClient HttpClient$Redirect]
           [java.security MessageDigest]
           [java.time Duration Instant]
           [java.util.concurrent Executors Semaphore TimeUnit]))

(defn- sha256 [^String s]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) d))))

(defn parse-duration-ms
  "\"3s\" \"500ms\" \"2m\" or a number of ms."
  [x default]
  (cond
    (nil? x) default
    (number? x) (long x)
    :else (let [[_ n u] (re-matches #"(\d+)\s*(ms|s|m|h)?" (str x))]
            (if n
              (* (Long/parseLong n) (case u "s" 1000 "m" 60000 "h" 3600000 1))
              default))))

(defn parse-rate
  "\"50/s\" → [50 1000]; \"600/m\" → [600 60000]."
  [x]
  (when x
    (let [[_ n u] (re-matches #"(\d+)\s*/\s*(s|m)" (str x))]
      (when n [(Long/parseLong n) (if (= u "m") 60000 1000)]))))

;; ---------------------------------------------------------------------------
;; fingerprints: what forces a new pool vs. what only rebuilds guards
;; ---------------------------------------------------------------------------

(defn pool-fingerprint
  "Pool-relevant settings, with secrets already resolved (so a rotated secret
   file counts as a change). Hashed so no secret is held as a key."
  [cfg]
  (sha256 (pr-str (into (sorted-map)
                        (select-keys cfg [:type :dialect :driver :jdbc-url :username :password
                                          :properties :pool :base-url :headers])))))

(defn guard-fingerprint [cfg] (sha256 (pr-str (:guard cfg))))

;; ---------------------------------------------------------------------------
;; guards
;; ---------------------------------------------------------------------------

(defn- log-event [ev m] (log/info (str "event=" (name ev) " " (pr-str m))))

(defn make-guards [conn-id {:keys [guard]}]
  (let [{:keys [concurrency queue-wait rate breaker]} guard
        {:keys [failure-ratio open half-open-calls]} breaker
        [fails of] (or failure-ratio [10 20])
        cb (dcb/circuit-breaker
            {:failure-threshold-ratio [fails of]
             :delay-ms (parse-duration-ms open 30000)
             :success-threshold (or half-open-calls 3)
             ;; diehard listeners take ONE argument (the event); a zero-arity
             ;; fn would fail silently because Failsafe swallows listener errors.
             :on-open (fn [_e] (log-event :breaker-open {:conn conn-id})
                        (metrics/counter! "spindle_breaker_transitions_total" {:conn conn-id :to "open"}))
             :on-half-open (fn [_e] (log-event :breaker-half-open {:conn conn-id})
                             (metrics/counter! "spindle_breaker_transitions_total" {:conn conn-id :to "half_open"}))
             :on-close (fn [_e] (log-event :breaker-closed {:conn conn-id})
                         (metrics/counter! "spindle_breaker_transitions_total" {:conn conn-id :to "closed"}))})
        [r per] (parse-rate rate)]
    {:breaker cb
     :breaker-opened-at (atom nil)
     :breaker-delay-ms (parse-duration-ms open 30000)
     :bulkhead (Semaphore. (int (or concurrency 4)) true)
     :bulkhead-limit (or concurrency 4)
     :queue-wait-ms (parse-duration-ms queue-wait 500)
     :rate-limiter (when r (drl/rate-limiter {:rate (/ (double r) (/ per 1000.0))}))}))

(defn breaker-state [{:keys [guards]}]
  (case (str (.getState ^CircuitBreaker (:breaker guards)))
    "CLOSED" :closed "HALF_OPEN" :half-open "OPEN" :open :unknown))

(defn guarded
  "Run (f) behind the connection's guards. `classify` maps an exception to a code."
  [{:keys [id guards state]} f]
  (let [{:keys [^CircuitBreaker breaker ^Semaphore bulkhead queue-wait-ms rate-limiter]} guards]
    ;; fail fast only while the open delay runs: once it has passed, fall through to
    ;; tryAcquirePermit, which is where Failsafe moves OPEN -> HALF_OPEN. (Checking
    ;; isOpen alone kept the breaker open for good.)
    (when (and (.isOpen breaker) (pos? (.toMillis (.getRemainingDelay breaker))))
      (metrics/counter! "spindle_breaker_rejected_total" {:conn id})
      (err/fail! :unavailable "circuit breaker open" {:conn id :retry-after-s 5}))
    (when rate-limiter
      (when-not (drl/try-acquire rate-limiter 1 queue-wait-ms)
        (metrics/counter! "spindle_rate_limited_total" {:conn id})
        (err/fail! :rate_limited "connection rate limit" {:conn id :retry-after-s 1})))
    (when-not (.tryAcquire bulkhead (long queue-wait-ms) TimeUnit/MILLISECONDS)
      (metrics/counter! "spindle_bulkhead_rejected_total" {:conn id})
      (err/fail! :unavailable "bulkhead full" {:conn id :retry-after-s 1}))
    (try
      (when-not (.tryAcquirePermit breaker)
        (metrics/counter! "spindle_breaker_rejected_total" {:conn id})
        (err/fail! :unavailable "circuit breaker open" {:conn id :retry-after-s 5}))
      (try
        (let [v (f)]
          (.recordSuccess breaker)
          v)
        (catch Throwable t
          (let [code (:spindle/code (err/code-of t))]
            (if (err/breaker-failure? (or code :source_error))
              (do (.recordFailure breaker)
                  (swap! state assoc :last-error {:category (name (or code :source_error))
                                                  :at (str (Instant/now))}))
              (.recordSuccess breaker)))
          (throw t)))
      (finally (.release bulkhead)))))

;; ---------------------------------------------------------------------------
;; pools and clients
;; ---------------------------------------------------------------------------

(defn- open-pool ^HikariDataSource [conn-id generation {:keys [jdbc-url username password driver properties pool]
                                                        dk :dialect :as cfg}]
  (let [d (dialect/get-dialect dk)
        props ((:app-name d) (into (or (:property-defaults d) {}) (map (fn [[k v]] [(name k) (str v)])) properties) "spindle")
        hc (doto (HikariConfig.)
             (.setPoolName (str "spindle-" (name conn-id) "-" generation))
             (.setJdbcUrl (dialect/url-for d jdbc-url))
             (.setMaximumPoolSize (int (or (:max pool) 6)))
             (.setMinimumIdle (int (min (or (:min-idle pool) 1) (or (:max pool) 6))))
             (.setConnectionTimeout (parse-duration-ms (:connection-timeout pool) 2000))
             (.setMaxLifetime (parse-duration-ms (:max-lifetime pool) 1800000))
             ;; do not fail startup or reload when the database is down: the breaker handles that
             (.setInitializationFailTimeout -1)
             (.setMetricsTrackerFactory (MicrometerMetricsTrackerFactory. metrics/registry)))]
    (when-let [dc (or driver (:driver d))] (.setDriverClassName hc dc))
    (when username (.setUsername hc username))
    (when password (.setPassword hc password))
    (doseq [[k v] props] (.addDataSourceProperty hc k v))
    (HikariDataSource. hc)))

(defn- open-http-client ^HttpClient [{:keys [pool]}]
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofMillis (parse-duration-ms (:connection-timeout pool) 2000)))
      (.followRedirects HttpClient$Redirect/NEVER)
      (.executor (Executors/newVirtualThreadPerTaskExecutor))
      (.build)))

(defonce ^:private generation (atom 0))

(defn open
  "Build a runtime connection from resolved config."
  [conn-id cfg]
  (let [g (swap! generation inc)
        kind (:type cfg)
        rt {:id conn-id
            :kind kind
            :cfg cfg
            :dialect (dialect/get-dialect (:dialect cfg))
            :fingerprint (pool-fingerprint cfg)
            :guard-fingerprint (guard-fingerprint cfg)
            :guards (make-guards conn-id cfg)
            :state (atom {:opened-at (str (Instant/now))})}]
    (case kind
      :sql (assoc rt :ds (open-pool conn-id g cfg))
      :http (assoc rt :client (open-http-client cfg)))))

(defn with-new-guards [rt cfg]
  (assoc rt :cfg cfg :guards (make-guards (:id rt) cfg) :guard-fingerprint (guard-fingerprint cfg)))

(defn close! [rt]
  (when-let [^HikariDataSource ds (:ds rt)]
    (try (.close ds) (catch Exception _ nil)))
  (when-let [^HttpClient c (:client rt)]
    (try (.close c) (catch Throwable _ nil))))

(defn pool-stats [{:keys [^HikariDataSource ds]}]
  (when-let [mx (some-> ds .getHikariPoolMXBean)]
    {:active (.getActiveConnections mx) :idle (.getIdleConnections mx)
     :pending (.getThreadsAwaitingConnection mx) :max (.getMaximumPoolSize ds)}))

(defn evict! [{:keys [^HikariDataSource ds]} con]
  (when (and ds con)
    (try (.evictConnection ds con) (catch Exception _ nil))))
