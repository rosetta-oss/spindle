(ns spindle.explain
  "Mandatory explain before publishing.

   On every reload, each SQL step whose flow version has no archived plan is
   explained through its dialect (EXPLAIN / showplan) and the plan is written to
   the archive:  <SPINDLE_EXPLAIN_DIR>/<flow-id>/<flow-version>/<step>.json

   Bind values come from the flow's first :examples map for [:params …] paths;
   anything else is bound as NULL. A SQL error (unknown column, syntax) rejects
   the reload. An unreachable database does not: the plan is marked pending and
   retried on the next reload. SPINDLE_EXPLAIN=off disables the check."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jsonista.core :as json]
            [spindle.error :as err]
            [spindle.sql :as sql]
            [spindle.sqlfile :as sqlfile])
  (:import [java.time Instant]))

(defn enabled? [] (not= "off" (some-> (System/getenv "SPINDLE_EXPLAIN") str/lower-case)))

(def ^:dynamic *archive-dir* nil)

(defn archive-dir [] (or *archive-dir* (System/getenv "SPINDLE_EXPLAIN_DIR") "explain-archive"))

(defn example-values [step example]
  (into {}
        (for [{:keys [param list?]} (distinct (filter map? (:scanned step)))
              :let [path (get-in step [:in param])
                    v (when (= :params (first path)) (get-in example (rest path)))]]
          [param (if list? (if (sequential? v) (vec v) [v]) (if (sequential? v) (first v) v))])))

(def ^:private mapper (json/object-mapper {:pretty true}))

(defn explain-all!
  "→ {:problems [...] :explained n :pending n :archived n}"
  [flows conns]
  (if-not (enabled?)
    {:problems [] :explained 0 :pending 0 :archived 0 :disabled true}
    (let [out (atom {:problems [] :explained 0 :pending 0 :archived 0})]
      (doseq [[flow-id fl] flows
              [step-id step] (:steps fl)
              :when (= :sql (:kind step))
              :let [f (io/file (archive-dir) flow-id (:version fl) (str (name step-id) ".json"))]]
        (if (.isFile f)
          (swap! out update :archived inc)
          (let [rt (get conns (:conn step))
                [text & args] (sqlfile/render (:scanned step) (example-values step (first (:examples fl))))]
            (try
              (let [plan (sql/explain! rt text args)]
                (io/make-parents f)
                (spit f (json/write-value-as-string
                         {:flow flow-id :flow_version (:version fl) :step (name step-id) :conn (name (:conn step))
                          :dialect (name (or (get-in rt [:cfg :dialect]) :generic)) :at (str (Instant/now))
                          :sql text :plan plan}
                         mapper))
                (swap! out update :explained inc))
              (catch Throwable t
                (let [code (sql/classify t false)]
                  (if (#{:unavailable :timeout} code)
                    (do (log/warn (str "event=explain-pending flow=" flow-id " step=" (name step-id) " reason=" (name code)))
                        (swap! out update :pending inc))
                    (swap! out update :problems conj
                           {:path [flow-id :steps step-id]
                            :message (str "explain failed: " (.getMessage t))}))))))))
      @out)))
