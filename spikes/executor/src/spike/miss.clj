(ns spike.miss
  "Alternative: missionary. A task is (fn [success failure] → cancel-fn), so a
   SQL step can make Statement.cancel its native cancellation — no hook registry."
  (:require [missionary.core :as m]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [spike.common :as c])
  (:import [java.util.concurrent Executors ExecutorService]))

(def ^ExecutorService vexec (Executors/newVirtualThreadPerTaskExecutor))

(defn sql-task
  "A missionary task running `sql` on a virtual thread; cancelling it calls Statement.cancel."
  [step sql]
  (fn [success failure]
    (let [st-ref (atom nil)
          cancelled (atom false)]
      (.submit vexec ^Runnable
               (fn []
                 (try
                   (with-open [con (jdbc/get-connection @c/ds)
                               st  (jdbc/prepare con [sql])]
                     (reset! st-ref st)
                     (when @cancelled (.cancel st))
                     (success (jdbc/execute! st nil {:builder-fn rs/as-unqualified-lower-maps})))
                   (catch Throwable t
                     (failure (if @cancelled (ex-info "cancelled" {:step step :category :cancelled}) t))))))
      (fn cancel []
        (reset! cancelled true)
        (some-> @st-ref .cancel)))))

(defn api-task [step ms fail?]
  ;; m/via on a blocking executor: cancellation interrupts the thread
  (m/via m/blk
         (Thread/sleep (long ms))
         (when fail? (throw (ex-info "upstream 503" {:step step :category :unavailable})))
         [{:shipment "S-1"}]))

(defn flow [{:keys [b-sql c-ms c-fail?]}]
  (m/sp
   (let [a (m/? (sql-task :a "select 1 as id"))
         [b cc] (m/? (m/join vector (sql-task :b b-sql) (api-task :c c-ms c-fail?)))]
     {:a a :b b :c cc})))

(defn run [kind]
  (let [{:keys [deadline-ms] :as sc} (c/scenario kind)
        t0 (c/now)
        outcome (try
                  (let [v (m/? (m/timeout (flow sc) deadline-ms ::timeout))]
                    (if (= v ::timeout) {:category :timeout} :ok))
                  (catch Throwable t
                    {:category (or (:category (ex-data t)) :source) :step (:step (ex-data t))}))]
    {:variant :missionary :scenario kind :elapsed-ms (c/ms-since t0) :outcome outcome}))

;; ---------------------------------------------------------------------------
;; dynamic DAG: compile {id {:deps #{} :task (fn [inputs] task)}} with one
;; dataflow variable per step; m/join runs every step, failure cancels siblings
;; ---------------------------------------------------------------------------

(defn- step-task [vars id deps task-fn]
  (m/sp
   (let [in (loop [ds (seq deps) in {}]
              (if ds
                (recur (next ds) (assoc in (first ds) (m/? (vars (first ds)))))
                in))]
     ((vars id) (m/? (task-fn in))))))

(defn dag [steps]
  (let [vars (into {} (map (fn [[id _]] [id (m/dfv)])) steps)]
    (m/sp
     (m/? (apply m/join (constantly nil)
                 (map (fn [[id {:keys [deps task]}]] (step-task vars id deps task)) steps)))
     (loop [ids (seq (keys vars)) out {}]
       (if ids
         (recur (next ids) (assoc out (first ids) (m/? (vars (first ids)))))
         out)))))

(defn dag-steps [{:keys [b-sql c-ms c-fail?]}]
  {:a      {:deps []      :task (fn [_] (sql-task :a "select 1 as id"))}
   :b      {:deps [:a]    :task (fn [_] (sql-task :b b-sql))}
   :c      {:deps [:a]    :task (fn [_] (api-task :c c-ms c-fail?))}
   :result {:deps [:b :c] :task (fn [in] (m/sp in))}})

(defn run-dag [kind]
  (let [{:keys [deadline-ms] :as sc} (c/scenario kind)
        t0 (c/now)
        outcome (try
                  (let [v (m/? (m/timeout (dag (dag-steps sc)) deadline-ms ::timeout))]
                    (if (= v ::timeout) {:category :timeout} :ok))
                  (catch Throwable t
                    {:category (or (:category (ex-data t)) :source) :step (:step (ex-data t))}))]
    {:variant :missionary-dag :scenario kind :elapsed-ms (c/ms-since t0) :outcome outcome}))
