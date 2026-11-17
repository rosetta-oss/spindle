(ns spindle.executor
  "The DAG executor: our own small scheduler, promesa futures on virtual threads.

   - A step starts when all its dependencies have finished.
   - At most `parallel` steps per connection run at once within one request
     (default 1), so one request cannot take several connections of a pool.
   - The first fatal failure, or the deadline, cancels every running step:
     `p/cancel!` on the step's promise fires the step's cancel hooks
     (Statement.cancel for SQL, aborting the exchange for HTTP).
   - The error reported is the first real failure; steps cancelled because of
     it are never reported as the cause.
   - `:or-else` runs a fallback when a step returns no rows; consumers of the
     step then see the fallback's rows. The fallback still waits for its own
     dependencies. A primary that fails as `:optional`, or returns rows, skips
     its fallback.
   - `:optional` steps degrade to nil and are listed in `:partial`."
  (:require [promesa.core :as p]
            [promesa.exec :as px]
            [spindle.error :as err]
            [spindle.sql :as sql])
  (:import [java.util.concurrent CancellationException LinkedBlockingQueue TimeUnit]))

(defn- start-step [run-fn step env]
  (let [cancels (sql/new-cancels)
        fut (px/submit :vthread (fn [] (run-fn step env cancels)))]
    ;; promise cancellation → the step's own cancel hooks (Statement.cancel, HTTP abort)
    (p/handle fut (fn [_ e]
                    (when (instance? CancellationException e)
                      (sql/cancel-all! cancels))))
    fut))

(defn run
  "steps: {id {:deps #{ids} :conn k :or-else id? :fallback? bool :optional bool}}
   run-fn: (fn [step env cancels] rows)
   → {:results {id rows} :partial [{:step id :code kw}]} or throws the first failure."
  [steps run-fn {:keys [deadline-ns parallel] :as env}]
  (let [q (LinkedBlockingQueue.)
        fallbacks (set (keep :or-else (vals steps)))
        limit (fn [c] (get parallel c 1))]
    (loop [results {} running {} partial [] skipped #{} wanted {}]   ; wanted: {fallback primary}
      (let [done? (fn [id] (or (contains? results id) (contains? skipped id)))
            busy (frequencies (map (comp :conn steps) (keys running)))
            [running busy]
            (reduce (fn [[running busy] [id {:keys [deps conn]}]]
                      (if (and (not (done? id)) (not (contains? running id))
                               (or (not (contains? fallbacks id)) (contains? wanted id)) ; fallbacks start only on demand
                               (not (some #{id} (vals wanted)))         ; a primary waiting for its fallback
                               (every? done? deps)
                               (< (get busy conn 0) (limit conn)))
                        (let [f (start-step run-fn (assoc (steps id) :id id) (assoc env :results results))]
                          (p/handle f (fn [v e] (.put q [id v e])))
                          [(assoc running id f) (update busy conn (fnil inc 0))])
                        [running busy]))
                    [running busy]
                    (sort-by key steps))]
        (if (every? done? (keys steps))
          {:results results :partial partial}
          (let [left (- deadline-ns (System/nanoTime))
                [id v e :as msg] (when (pos? left) (.poll q left TimeUnit/NANOSECONDS))
                cancel-running! (fn [rs] (doseq [[_ f] rs] (p/cancel! f)))]
            (cond
              (nil? msg)
              (do (cancel-running! running)
                  (err/fail! :timeout "flow deadline passed" {:running (vec (keys running))}))

              e
              (let [root (if (instance? java.util.concurrent.CompletionException e) (.getCause ^Throwable e) e)
                    code (or (:spindle/code (err/code-of root)) :internal)
                    st (steps id)]
                (if (:optional st)
                  (recur (into (assoc results id nil) (map (fn [n] [n nil]) (:provides st)))
                         (dissoc running id) (conj partial {:step id :code code})
                         (if-let [fb (:or-else st)] (conj skipped fb) skipped) wanted)
                  (do (cancel-running! (dissoc running id))
                      (throw (if (:spindle/code (ex-data root))
                               root
                               (ex-info "step failed" {:spindle/code code :step id} root))))))

              :else
              (let [st (steps id)
                    running (dissoc running id)]
                (cond
                  ;; empty result with a fallback → start the fallback now
                  (and (:or-else st) (empty? v))
                  ;; the primary is not done until its fallback is; the scheduler
                  ;; starts the fallback once the fallback's own dependencies are done
                  (recur results running partial skipped (assoc wanted (:or-else st) id))

                  ;; a fallback finished: its rows stand in for the primary
                  (contains? fallbacks id)
                  (let [primary (some (fn [[pid s]] (when (= id (:or-else s)) pid)) steps)]
                    (recur (assoc results id v primary v) running partial skipped wanted))

                  ;; a :proc step with named result sets adds each set under its name
                  (:spindle/sets v)
                  (recur (merge (assoc results id (:spindle/self v)) (:spindle/sets v)) running partial skipped wanted)

                  :else
                  (let [skipped (if-let [fb (:or-else st)] (conj skipped fb) skipped)]
                    (recur (assoc results id v) running partial skipped wanted)))))))))))
