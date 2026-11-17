(ns spike.own
  "Option A: a home-grown DAG executor on JDK virtual threads.
   Steps start when their inputs are done; first fatal error or the deadline
   cancels everything (cancel hooks + interrupt); close waits for threads."
  (:require [spike.common :as c])
  (:import [java.util.concurrent Executors ExecutorService LinkedBlockingQueue TimeUnit Future]))

(defn run-dag
  "steps: {id {:deps #{ids} :f (fn [inputs] rows)}}; returns {:ok results} or {:error ...}."
  [steps {:keys [deadline-ms cancels]}]
  (let [deadline (+ (System/nanoTime) (* deadline-ms 1000000))
        done-q   (LinkedBlockingQueue.)
        ^ExecutorService ex (Executors/newVirtualThreadPerTaskExecutor)]
    (try
      (loop [results {} running {}]
        (let [ready (for [[id {:keys [deps]}] steps
                          :when (and (not (contains? results id))
                                     (not (contains? running id))
                                     (every? #(contains? results %) deps))]
                      id)
              running (reduce (fn [m id]
                                (let [{:keys [deps f]} (steps id)
                                      in (select-keys results deps)]
                                  (assoc m id (.submit ex ^Callable
                                                       (fn []
                                                         (.put done-q
                                                               (try [id :ok (f in)]
                                                                    (catch Throwable t [id :error t])))
                                                         nil)))))
                              running ready)]
          (if (= (count results) (count steps))
            {:ok results}
            (let [left (- deadline (System/nanoTime))
                  [id status v :as msg] (when (pos? left) (.poll done-q left TimeUnit/NANOSECONDS))]
              (cond
                (nil? msg)          (do (c/cancel-all! cancels)
                                        (doseq [[_ ^Future fu] running] (.cancel fu true))
                                        {:error {:category :timeout}})
                (= status :error)   (do (c/cancel-all! cancels)
                                        (doseq [[_ ^Future fu] running] (.cancel fu true))
                                        {:error {:category (or (:category (ex-data v)) :source)
                                                 :step id}})
                :else (recur (assoc results id v) (dissoc running id)))))))
      (finally
        ;; close: bounded wait so no step outlives the request
        (.shutdown ex)
        (when-not (.awaitTermination ex 2 TimeUnit/SECONDS)
          (.shutdownNow ex))))))

(defn flow [{:keys [b-sql c-ms c-fail?]} cancels]
  {:a      {:deps #{} :f (fn [_] (c/sql-step! cancels :a "select 1 as id"))}
   :b      {:deps #{:a} :f (fn [_] (c/sql-step! cancels :b b-sql))}
   :c      {:deps #{:a} :f (fn [_] (c/api-step! cancels :c c-ms c-fail?))}
   :result {:deps #{:b :c} :f (fn [{:keys [b c]}] {:b b :c c})}})

(defn run [kind]
  (let [sc (c/scenario kind) cancels (c/new-cancels) t0 (c/now)
        r  (run-dag (flow sc cancels) {:deadline-ms (:deadline-ms sc) :cancels cancels})]
    {:variant :own :scenario kind :elapsed-ms (c/ms-since t0)
     :outcome (if (:ok r) :ok (:error r))}))
