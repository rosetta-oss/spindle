(ns spike.main
  (:require [spike.common :as c]
            [spike.own :as own]
            [spike.prom :as prom]
            [spike.nod :as nod]
            [spike.miss :as miss]))

(defn- settle-and-probe
  "Right after a run: are pg_sleep statements still active? (checked at once and 300 ms later)"
  []
  (let [now   (c/running-sleeps)
        _     (Thread/sleep 300)
        later (c/running-sleeps)]
    {:pg-active-now now :pg-active+300ms later}))

(defn- wait-clean []
  (loop [i 0] (when (and (pos? (c/running-sleeps)) (< i 60)) (Thread/sleep 100) (recur (inc i)))))

(defn -main [& _]
  (c/running-sleeps)                               ; warm the pool
  (doseq [run [#(own/run %) #(prom/run % false) #(prom/run % true) #(nod/run %) #(miss/run %) #(miss/run-dag %)]
          kind [:happy :deadline :failure]]
    (wait-clean)
    (let [r (run kind)]
      (prn (merge r (settle-and-probe)))))
  (shutdown-agents)
  (System/exit 0))
