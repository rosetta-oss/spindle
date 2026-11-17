(ns spike.prom
  "Option A': promesa futures on virtual threads with p/timeout — does the
   deadline reach the database on its own, or only with our cancel hooks?"
  (:require [promesa.core :as p] [promesa.exec :as px]
            
            [spike.common :as c]))

(defn run-flow [{:keys [b-sql c-ms c-fail? deadline-ms]} cancels hooks?]
  (let [exe :vthread
        a  (px/submit exe #(c/sql-step! cancels :a "select 1 as id"))
        b  (p/then a (fn [_] (c/sql-step! cancels :b b-sql)) exe)
        cc (p/then a (fn [_] (c/api-step! cancels :c c-ms c-fail?)) exe)
        all (-> (p/all [b cc])
                (p/timeout deadline-ms ::timeout))]
    (try
      (let [v @all]
        (if (= v ::timeout)
          (do (when hooks? (c/cancel-all! cancels)) {:category :timeout})
          :ok))
      (catch Exception e
        (when hooks? (c/cancel-all! cancels))
        {:category (or (:category (ex-data (ex-cause e))) (:category (ex-data e)) :source)}))))

(defn run [kind hooks?]
  (let [sc (c/scenario kind) cancels (c/new-cancels) t0 (c/now)
        r  (run-flow sc cancels hooks?)]
    {:variant (if hooks? :promesa+hooks :promesa-only) :scenario kind
     :elapsed-ms (c/ms-since t0) :outcome r}))
