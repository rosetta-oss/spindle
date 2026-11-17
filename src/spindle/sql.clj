(ns spindle.sql
  "Executing an :sql step: bind, chunk IN lists, apply per-step tuning, and make
   the statement cancellable. Cancellation is registered on the request's cancel
   registry so the executor's deadline reaches Statement.cancel()."
  (:require [clojure.string :as str]
            [next.jdbc.result-set :as rs]
            [spindle.conn :as conn]
            [spindle.dialect :as dialect]
            [spindle.error :as err]
            [spindle.sqlfile :as sqlfile])
  (:import [java.sql Connection PreparedStatement ResultSet ResultSetMetaData SQLException
            SQLTimeoutException SQLTransientConnectionException SQLRecoverableException
            Timestamp Date Time Array Clob]
           [java.util.concurrent.atomic AtomicBoolean]))

;; ---------------------------------------------------------------------------
;; cancel registry (one per request)
;; ---------------------------------------------------------------------------

(defn new-cancels [] (atom {:cancelled? false :hooks {}}))

(defn register-cancel! [cancels k f]
  (let [{:keys [cancelled?]} (swap! cancels assoc-in [:hooks k] f)]
    (when cancelled? (f))))

(defn deregister-cancel! [cancels k] (swap! cancels update :hooks dissoc k))

(defn cancel-all! [cancels]
  (let [[old _] (swap-vals! cancels assoc :cancelled? true)]
    (doseq [[_ f] (:hooks old)] (try (f) (catch Throwable _ nil)))))

;; ---------------------------------------------------------------------------
;; reading values
;; ---------------------------------------------------------------------------

(defn- read-value [^ResultSet rs ^ResultSetMetaData _md ^long i]
  (let [v (.getObject rs i)]
    (cond
      (nil? v) nil
      (instance? Timestamp v) (.toLocalDateTime ^Timestamp v)
      (instance? Date v) (.toLocalDate ^Date v)
      (instance? Time v) (.toLocalTime ^Time v)
      (instance? Array v) (vec (.getArray ^Array v))
      (instance? Clob v) (let [c ^Clob v] (.getSubString c 1 (int (.length c))))
      (instance? java.util.UUID v) (str v)
      (= "org.postgresql.util.PGobject" (.getName (class v))) (str v)
      :else v)))

(defn- read-rows [^ResultSet rs max-rows]
  (let [md (.getMetaData rs)
        n (.getColumnCount md)
        cols (mapv #(keyword (str/lower-case (.getColumnLabel md (int %)))) (range 1 (inc n)))]
    (loop [acc (transient []) k 0]
      (if (.next rs)
        (if (and max-rows (>= k max-rows))
          (err/fail! :too_many_rows "row cap exceeded" {:max-rows max-rows})
          (recur (conj! acc (loop [i 1 m (transient {})]
                              (if (> i n) (persistent! m)
                                  (recur (inc i) (assoc! m (cols (dec i)) (read-value rs md i))))))
                 (inc k)))
        (persistent! acc)))))

;; ---------------------------------------------------------------------------
;; failure classification (driver messages are logged, never returned)
;; ---------------------------------------------------------------------------

(defn classify [^Throwable e cancelled?]
  (let [msg (str/lower-case (or (.getMessage e) ""))]
    (cond
      (:spindle/code (ex-data e)) (:spindle/code (ex-data e))
      cancelled? :timeout
      (instance? SQLTimeoutException e) :timeout
      (re-find #"cancel|timeout|timed out|interrupted|max_execution_time|maximum statement execution time" msg) :timeout
      (instance? SQLTransientConnectionException e) :unavailable
      (instance? SQLRecoverableException e) :unavailable
      (re-find #"connection|socket|refused|reset|broken pipe|no route|unknown host|is not available|too many" msg) :unavailable
      :else :source_error)))

;; ---------------------------------------------------------------------------
;; execution
;; ---------------------------------------------------------------------------

(defn- remaining-s [deadline-ns]
  (let [ms (quot (- deadline-ns (System/nanoTime)) 1000000)]
    (when (<= ms 0) (err/fail! :timeout "deadline passed before statement"))
    (max 1 (long (Math/ceil (/ ms 1000.0))))))

(defn- run-one!
  [rt ^String sql-text args {:keys [tuning deadline-ns cancels step-id tag max-rows]}]
  (let [{:keys [ds] d :dialect} rt
        cancel-key (Object.)
        cancelled (AtomicBoolean. false)
        text (str "/* spindle " tag " */ " sql-text)]
    (let [^Connection con (try (.getConnection ^javax.sql.DataSource ds)
                               (catch Throwable t
                                 (err/fail! (if (= :timeout (classify t false)) :unavailable (classify t false))
                                            "no database connection" {:step step-id :conn (:id rt)} t)))
          broken (volatile! false)]
      (try
        (when-let [iso (:isolation tuning)]
          (when (:isolation? d) (.setTransactionIsolation con (int (dialect/isolation-levels iso)))))
        (with-open [^PreparedStatement ps (.prepareStatement con text)]
          (doseq [[i v] (map-indexed vector args)]
            (.setObject ps (int (inc i)) v))
          (.setQueryTimeout ps (int (min (remaining-s deadline-ns)
                                         (long (Math/ceil (/ (conn/parse-duration-ms (:timeout tuning) 30000) 1000.0))))))
          (when max-rows (.setMaxRows ps (int (inc max-rows))))
          (when-let [fs (:fetch-size tuning)] (.setFetchSize ps (int fs)))
          (register-cancel! cancels cancel-key (fn [] (.set cancelled true) (try (.cancel ps) (catch Throwable _ nil))))
          (try
            (with-open [rs (.executeQuery ps)]
              (read-rows rs max-rows))
            (catch Throwable t
              (vreset! broken (.get cancelled))
              (let [code (classify t (.get cancelled))]
                (err/fail! code (if (= code :timeout) "statement cancelled or timed out" "statement failed")
                           {:step step-id :conn (:id rt)} t)))
            (finally (deregister-cancel! cancels cancel-key))))
        (finally
          (if @broken
            ;; a cancelled connection may be mid-protocol: do not hand it to the next borrower
            (do (conn/evict! rt con) (try (.close con) (catch Throwable _ nil)))
            (try (.close con) (catch Throwable _ nil))))))))

(defn run-step!
  "Execute a compiled :sql step with resolved `values` (placeholder → value).
   IN lists are chunked to the dialect's limit; chunks run one after another on
   this request's share of the connection."
  [rt {:keys [scanned id tuning max-rows] :as _step} values ctx]
  (let [max-in (or (:max-in tuning) (:max-in (:dialect rt)))
        renders (sqlfile/chunked-renders scanned values max-in)]
    (loop [rs renders acc []]
      (if-let [[text & args] (first rs)]
        (let [rows (conn/guarded rt #(run-one! rt text args (assoc ctx :tuning tuning :step-id id
                                                                   :max-rows (when max-rows (- max-rows (count acc))))))]
          (recur (rest rs) (into acc rows)))
        acc))))

(defn explain!
  "Plan for one rendered statement via the dialect, on a fresh (non-pooled) connection."
  [rt sql-text args]
  (let [d (:dialect rt)
        ex (:explain d)]
    (cond
      (fn? ex)
      (with-open [^Connection con (.getConnection ^javax.sql.DataSource (:ds rt))
                  ^PreparedStatement ps (.prepareStatement con (ex sql-text))]
        (doseq [[i v] (map-indexed vector args)] (.setObject ps (int (inc i)) v))
        (with-open [rs (.executeQuery ps)] (read-rows rs 10000)))
      :else [{:plan "explain not supported for this dialect yet"}])))
