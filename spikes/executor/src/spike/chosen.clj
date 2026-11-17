(ns spike.chosen
  "The chosen design (option A): our own scheduler, promesa for virtual-thread
   futures, and cancellation that travels promise → Statement.cancel.

   Verifies two things:
   1. `p/cancel!` on a step's promise reaches `Statement.cancel` (the DB stops).
   2. The request deadline holds, and a failing step cancels its siblings."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [promesa.core :as p]
            [promesa.exec :as px]
            [spike.common :as c])
  (:import [java.util.concurrent CancellationException LinkedBlockingQueue TimeUnit]))

(defn sql-promise
  "Run `sql` on a virtual thread. Cancelling the returned promise calls Statement.cancel."
  [sql]
  (let [st (atom nil)
        cancelled? (atom false)
        fut (px/submit :vthread
                       (fn []
                         (with-open [con (jdbc/get-connection @c/ds)
                                     ps  (jdbc/prepare con [sql])]
                           (reset! st ps)
                           (when @cancelled? (.cancel ps))   ; cancelled before we got here
                           (jdbc/execute! ps nil {:builder-fn rs/as-unqualified-lower-maps}))))]
    ;; the bridge: promise cancellation → JDBC cancel
    (p/handle fut (fn [_ e]
                    (when (instance? CancellationException e)
                      (reset! cancelled? true)
                      (some-> ^java.sql.Statement @st .cancel))))
    fut))

(defn api-promise [ms fail?]
  (px/submit :vthread (fn []
                        (Thread/sleep (long ms))
                        (when fail? (throw (ex-info "upstream 503" {:category :unavailable})))
                        [{:shipment "S-1"}])))

(defn run-dag
  "steps: {id {:deps [ids] :start (fn [inputs] promise)}}."
  [steps deadline-ms]
  (let [deadline (+ (System/nanoTime) (* deadline-ms 1000000))
        q (LinkedBlockingQueue.)
        cancel-all! (fn [running] (doseq [[_ f] running] (p/cancel! f)))]
    (loop [results {} running {}]
      (let [ready (for [[id {:keys [deps]}] steps
                        :when (and (not (contains? results id)) (not (contains? running id))
                                   (every? #(contains? results %) deps))]
                    id)
            running (reduce (fn [m id]
                              (let [{:keys [deps start]} (steps id)
                                    f (start (select-keys results deps))]
                                (p/handle f (fn [v e] (.put q [id v e])))
                                (assoc m id f)))
                            running ready)]
        (if (= (count results) (count steps))
          {:ok results}
          (let [left (- deadline (System/nanoTime))
                [id v e :as msg] (when (pos? left) (.poll q left TimeUnit/NANOSECONDS))]
            (cond
              (nil? msg) (do (cancel-all! running) {:error {:category :timeout}})
              e          (do (cancel-all! (dissoc running id))
                             (let [root (or (ex-cause e) e)]
                               {:error {:category (or (:category (ex-data root)) :source) :step id}}))
              :else      (recur (assoc results id v) (dissoc running id)))))))))

(defn steps [{:keys [b-sql c-ms c-fail?]}]
  {:a      {:deps []      :start (fn [_] (sql-promise "select 1 as id"))}
   :b      {:deps [:a]    :start (fn [_] (sql-promise b-sql))}
   :c      {:deps [:a]    :start (fn [_] (api-promise c-ms c-fail?))}
   :result {:deps [:b :c] :start (fn [in] (p/resolved in))}})

(defn run [kind]
  (let [{:keys [deadline-ms] :as sc} (c/scenario kind)
        t0 (c/now)
        r (run-dag (steps sc) deadline-ms)]
    {:variant :chosen-A-promesa :scenario kind :elapsed-ms (c/ms-since t0)
     :outcome (if (:ok r) :ok (:error r))}))

(defn -main [& _]
  (c/running-sleeps)
  (doseq [kind [:happy :deadline :failure :deadline]]
    (loop [i 0] (when (and (pos? (c/running-sleeps)) (< i 60)) (Thread/sleep 100) (recur (inc i))))
    (let [r (run kind)
          now (c/running-sleeps) _ (Thread/sleep 300) later (c/running-sleeps)]
      (prn (assoc r :pg-active-now now :pg-active+300ms later))))
  (System/exit 0))
