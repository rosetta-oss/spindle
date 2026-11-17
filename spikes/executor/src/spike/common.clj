(ns spike.common
  "Shared pieces for the executor spike: a pool, a cancellable SQL step, a fake
   API step, and a probe that counts statements still running in Postgres."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [com.zaxxer.hikari HikariConfig HikariDataSource]))

(def db-url (or (System/getenv "SPIKE_DB_URL") "jdbc:postgresql://127.0.0.1:55432/spike"))

(defonce ds
  (delay
    (HikariDataSource.
     (doto (HikariConfig.)
       (.setJdbcUrl db-url)
       (.setUsername "spike")
       (.setPassword (or (System/getenv "SPIKE_DB_PASSWORD") "spike"))
       (.setMaximumPoolSize 6)
       (.addDataSourceProperty "ApplicationName" "spindle-spike")))))

(defn now [] (System/nanoTime))
(defn ms-since [t0] (long (/ (- (System/nanoTime) t0) 1e6)))

;; ---------------------------------------------------------------------------
;; cancel registry: one per request; every blocking step registers a hook
;; ---------------------------------------------------------------------------

(defn new-cancels [] (atom {:cancelled? false :hooks {}}))

(defn register! [cancels id f]
  (let [{:keys [cancelled?]} (swap! cancels assoc-in [:hooks id] f)]
    ;; registered after cancel-all! already ran: fire at once
    (when cancelled? (f))))

(defn deregister! [cancels id] (swap! cancels update :hooks dissoc id))

(defn cancel-all! [cancels]
  (let [[old _] (swap-vals! cancels assoc :cancelled? true)]
    (doseq [[_ f] (:hooks old)] (try (f) (catch Throwable _ nil)))))

(defn check-not-cancelled! [cancels step]
  (when (:cancelled? @cancels)
    (throw (ex-info "cancelled before start" {:step step :category :cancelled}))))

;; ---------------------------------------------------------------------------
;; steps
;; ---------------------------------------------------------------------------

(defn sql-step!
  "Run `sql` with Statement.cancel registered as this step's cancel hook."
  [cancels step sql]
  (check-not-cancelled! cancels step)
  (with-open [con (jdbc/get-connection @ds)
              st  (jdbc/prepare con [sql])]
    (register! cancels step #(.cancel st))
    (try
      (jdbc/execute! st nil {:builder-fn rs/as-unqualified-lower-maps})
      (finally (deregister! cancels step)))))

(defn api-step!
  "Stand-in for an HTTP call: sleeps (interruptible), optionally fails."
  [cancels step ms fail?]
  (check-not-cancelled! cancels step)
  (let [t (Thread/currentThread)]
    (register! cancels step #(.interrupt t))
    (try
      (Thread/sleep (long ms))
      (when fail? (throw (ex-info "upstream 503" {:step step :category :unavailable})))
      [{:shipment "S-1" :status "in_transit"}]
      (finally (deregister! cancels step)))))

;; ---------------------------------------------------------------------------
;; probe
;; ---------------------------------------------------------------------------

(defn running-sleeps
  "How many pg_sleep statements from this spike are still active in Postgres."
  []
  (with-open [con (jdbc/get-connection @ds)]
    (:n (jdbc/execute-one! con ["select count(*)::int as n from pg_stat_activity
                                  where state = 'active' and query like 'select pg_sleep%'
                                    and application_name = 'spindle-spike'"]
                           {:builder-fn rs/as-unqualified-lower-maps}))))

;; The flow every variant runs:
;;   :a (sql, fast) → :b (sql, slow)  ┐
;;                  → :c (api)        ┘→ :result
(defn scenario [kind]
  (case kind
    :happy    {:b-sql "select pg_sleep(0.2) as slept" :c-ms 300 :c-fail? false :deadline-ms 3000}
    :deadline {:b-sql "select pg_sleep(5) as slept"   :c-ms 300 :c-fail? false :deadline-ms 1000}
    :failure  {:b-sql "select pg_sleep(5) as slept"   :c-ms 300 :c-fail? true  :deadline-ms 3000}))
