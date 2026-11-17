(ns spike.guard
  "Breaker + bulkhead (diehard/Failsafe) per source, state listeners, and
   Prometheus output for breaker state, Hikari pool and Caffeine stats."
  (:require [diehard.core :as dh] [diehard.circuit-breaker :as dcb] [diehard.bulkhead :as dbh]
            [spike.common :as c])
  (:import [io.micrometer.prometheusmetrics PrometheusMeterRegistry PrometheusConfig]
           [io.micrometer.core.instrument Gauge]
           [io.micrometer.core.instrument.binder.cache CaffeineCacheMetrics]
           [com.github.benmanes.caffeine.cache Caffeine Cache]
           [com.zaxxer.hikari.metrics.micrometer MicrometerMetricsTrackerFactory]
           [dev.failsafe CircuitBreaker]))

(def registry (PrometheusMeterRegistry. PrometheusConfig/DEFAULT))
(def events (atom []))

(defn source-breaker [source]
  (dcb/circuit-breaker
   {:failure-threshold-ratio [3 5]          ; 3 failures in the last 5 calls
    :delay-ms 1500                          ; open → half-open
    :on-open      (fn [_e] (swap! events conj [source :open (System/currentTimeMillis)]))
    :on-half-open (fn [_e] (swap! events conj [source :half-open (System/currentTimeMillis)]))
    :on-close     (fn [_e] (swap! events conj [source :closed (System/currentTimeMillis)]))}))

(def breaker (source-breaker "shop"))
(def bulkhead (dbh/bulkhead {:concurrency 2}))

(def state-code {"CLOSED" 0 "HALF_OPEN" 1 "OPEN" 2})
(defn register-gauges! []
  (-> (Gauge/builder "spindle_breaker_state"
                     (reify java.util.function.Supplier
                       (get [_] (double (state-code (str (.getState ^CircuitBreaker breaker)))))))
      (.description "0 closed, 1 half-open, 2 open")
      (.tag "source" "shop")
      (.register registry)))

(defn guarded-sql [sql]
  (dh/with-circuit-breaker breaker
    (dh/with-bulkhead bulkhead
      (c/sql-step! (c/new-cancels) :s sql))))

(defn try-call [sql]
  (try (guarded-sql sql) :ok
       (catch dev.failsafe.CircuitBreakerOpenException _ :fast-fail-open)
       (catch Exception e (keyword (str "error-" (.getSimpleName (class e)))))))

(defn -main [& _]
  (register-gauges!)
  ;; Hikari's own Micrometer tracker (must be set before the pool starts)
  (let [ds (com.zaxxer.hikari.HikariDataSource.
            (doto (com.zaxxer.hikari.HikariConfig.)
              (.setJdbcUrl c/db-url) (.setUsername "spike") (.setPassword "spike")
              (.setPoolName "shop")
              (.setMetricsTrackerFactory (MicrometerMetricsTrackerFactory. registry))))]
    (with-open [con (.getConnection ds)] nil))
  ;; Caffeine with recordStats, bound to the registry
  (let [^Cache cache (-> (Caffeine/newBuilder) (.maximumSize 100) (.recordStats) (.build))]
    (CaffeineCacheMetrics/monitor registry cache "flow_order_full" (into-array String []))
    (.put cache "k1" "v") (.getIfPresent cache "k1") (.getIfPresent cache "k2"))
  (let [calls (concat (repeat 5 "select 1/0")        ; failures → open
                      ["select 1"]                   ; while open → fast fail
                      [:wait]
                      ["select 1" "select 1" "select 1"])] ; half-open → closed
    (doseq [s calls]
      (if (= s :wait)
        (do (Thread/sleep 1700) (println "after wait, state" (str (.getState ^CircuitBreaker breaker))))
        (println s "→" (try-call s) "| state" (str (.getState ^CircuitBreaker breaker))))))
  (println "listener events:" (mapv (partial take 2) @events))
  (println "--- prometheus (selected) ---")
  (doseq [l (clojure.string/split-lines (.scrape registry))
          :when (re-find #"^(spindle_breaker_state|hikaricp_connections_(active|idle|pending)|cache_gets_total|cache_size)" l)]
    (println l))
  (System/exit 0))
