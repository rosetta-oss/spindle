(ns spike.nod
  "Option B: compile the flow to a nodely env and evaluate it with the
   virtual-future engine. nodely has no deadline or cancellation, so both are
   wrapped around it: a deadline on the outer future, and leaf functions that
   fire cancel-all! when they fail (to get fail-fast)."
  (:require [nodely.api.v0 :as n]
            [nodely.data :as data]
            [spike.common :as c])
  (:import [java.util.concurrent TimeUnit TimeoutException ExecutionException]))

(defn- leaf
  "A nodely leaf from {:deps #{..} :f (fn [inputs])}; a failure cancels the rest."
  [cancels {:keys [deps f]}]
  (data/leaf deps (fn [in]
                    (try (f in)
                         (catch Throwable t
                           (c/cancel-all! cancels)
                           (throw t))))))

(defn compile-env [steps cancels]
  (into {} (map (fn [[id st]] [id (leaf cancels st)])) steps))

(defn run [kind]
  (let [{:keys [deadline-ms] :as sc} (c/scenario kind)
        cancels (c/new-cancels)
        steps   ((requiring-resolve 'spike.own/flow) sc cancels)
        env     (compile-env steps cancels)
        t0      (c/now)
        fut     (future (n/eval-key env :result {::n/engine :async.virtual-futures}))
        outcome (try (.get ^java.util.concurrent.Future fut deadline-ms TimeUnit/MILLISECONDS)
                     :ok
                     (catch TimeoutException _
                       (c/cancel-all! cancels)
                       (future-cancel fut)
                       {:category :timeout})
                     (catch ExecutionException e
                       (let [root (loop [t e] (if-let [nx (.getCause ^Throwable t)] (recur nx) t))]
                         {:category (or (:category (ex-data root)) :source)
                          :step (:step (ex-data root))})))]
    {:variant :nodely :scenario kind :elapsed-ms (c/ms-since t0) :outcome outcome}))
