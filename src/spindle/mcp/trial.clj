(ns spindle.mcp.trial
  "Candidate snapshots for authoring tools: validate, test-run and explain a flow
   without installing anything. Nothing is written outside a temp explain archive."
  (:require [spindle.config :as config]
            [spindle.conn :as conn]
            [spindle.explain :as explain]
            [spindle.flow :as flow]
            [spindle.sql :as sql]
            [spindle.sqlfile :as sqlfile])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn candidate-tree
  "`(:tree snap)` with flows/<id>.edn replaced by `edn` and flows/<id>.sql by `sql`
   (removed when `sql` is nil)."
  [snap id edn sql]
  (let [base (str "flows/" id)]
    (cond-> (assoc (:tree snap) (str base ".edn") edn)
      sql (assoc (str base ".sql") sql)
      (nil? sql) (dissoc (str base ".sql")))))

(defn- delete-tree! [^java.io.File d]
  (doseq [^java.io.File f (reverse (file-seq d))] (.delete f)))

(defn build-candidate
  "→ {:snapshot s} | {:problems [...]}. Never installs. Plans go to a temp archive
   (deleted afterwards), so every SQL step is explained against the database."
  [snap tree]
  (let [dir (.toFile (Files/createTempDirectory "spindle-trial" (make-array FileAttribute 0)))]
    (try
      (let [r (binding [explain/*archive-dir* (str dir)] (config/build tree snap))
            live (set (mapcat (juxt :ds :client) (vals (:conns snap))))]
        (when-let [s (:snapshot r)]
          (doseq [rt (vals (:conns s)) :when (not (live (or (:ds rt) (:client rt))))]
            (conn/close! rt)))
        r)
      (finally (delete-tree! dir)))))

(defn- with-flow
  "Runs (f snapshot flow) on the candidate (when edn is given) or the current snapshot."
  [id edn sql f]
  (let [snap (config/current)]
    (if (nil? edn)
      (if-let [fl (get-in snap [:flows id])] (f snap fl) {:error "no such flow"})
      (let [{s :snapshot ps :problems} (build-candidate snap (candidate-tree snap id edn sql))]
        (if ps {:problems ps} (f s (get-in s [:flows id])))))))

(defn validate [id edn sql]
  ;; one snapshot for both: a reload in between must not mix two trees
  (let [snap (config/current)
        {ps :problems} (build-candidate snap (candidate-tree snap id edn sql))]
    (if ps {:problems ps} {:ok true})))

(defn test-run [id edn sql params]
  (with-flow id edn sql
    (fn [snap fl]
      ;; candidate conns are carried from the running snapshot, so they stay open
      (let [steps (atom [])
            ps (flow/coerce-params (:params-schema fl) (into {} (map (fn [[k v]] [(keyword k) v])) params))
            {:keys [rows partial]} (flow/execute snap fl ps
                                                 {:caller {:id "mcp-test-run"} :request-id "mcp-test-run" :no-cache? true
                                                  :on-step (fn [sid rows ms]
                                                             (swap! steps conj {:id (name sid)
                                                                                :rows (if (sequential? rows) (count rows) (if (nil? rows) 0 1))
                                                                                :ms ms}))})]
        {:output rows :steps @steps
         :partial (mapv #(update (update % :step name) :code (fn [c] (some-> c name))) partial)}))))

(defn explain [id edn sql params]
  (with-flow id edn sql
    (fn [snap fl]
      (let [ex (if params
                 (flow/coerce-params (:params-schema fl) (into {} (map (fn [[k v]] [(keyword k) v])) params))
                 (first (:examples fl)))]
        {:steps (vec (for [[sid step] (sort-by key (:steps fl))
                           :when (= :sql (:kind step))
                           :let [[text & args] (sqlfile/render (:scanned step) (explain/example-values step ex))]]
                       {:id (name sid) :plan (sql/explain! (get-in snap [:conns (:conn step)]) text args)}))}))))
