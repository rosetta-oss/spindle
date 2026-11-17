(ns spindle.flow
  "Compiling flow definitions (EDN + .sql blocks) and running them.

   A flow is an instruction set — data interpreted by the engine. Compilation
   checks everything that can be checked without running: placeholders have
   bindings, paths point at existing steps, the step graph has no cycles, the
   dialect supports what the step asks for, writes are refused (v0.1/v0.2 are
   read-only; mutation endpoints arrive later), and expressions parse."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [malli.core :as m]
            [spindle.conn :as conn]
            [spindle.dialect]
            [spindle.error :as err]
            [spindle.metrics]
            [spindle.executor :as executor]
            [spindle.httpstep :as httpstep]
            [spindle.output :as output]
            [spindle.paths :as paths]
            [spindle.sql :as sql]
            [spindle.sqlfile :as sqlfile])
  (:import [java.util.concurrent TimeUnit TimeoutException ExecutionException]))

(def max-steps 16)
(def max-deadline-ms 60000)

(defn- problem [path msg] {:path path :message msg})

(defn- path-steps [p] (when-let [s (paths/step-ref p)] #{s}))

(defn- step-deps [{:keys [in request or-else fan-out]}]
  (set (concat (mapcat path-steps (vals in))
               (mapcat path-steps (vals (:query request)))
               (mapcat path-steps (vals (:path-params request)))
               (mapcat path-steps (vals (:body request)))
               (path-steps fan-out))))

(defn- cycle? [deps]
  ;; deps: {id #{ids}}; Kahn's algorithm
  (loop [deps deps]
    (let [ready (set (keep (fn [[id ds]] (when (empty? ds) id)) deps))]
      (cond
        (empty? deps) false
        (empty? ready) true
        :else (recur (into {} (map (fn [[id ds]] [id (set/difference ds ready)]))
                           (apply dissoc deps ready)))))))

(defn compile-flow
  "→ {:flow compiled} or {:problems [...]}.
   `raw` is the flow EDN, `blocks` the .sql blocks, `conns` {id resolved-config}."
  [flow-id raw blocks conns]
  (let [problems (atom [])
        add! (fn [path msg] (swap! problems conj (problem (into [flow-id] path) msg)))
        steps-raw (or (:steps raw)
                      ;; shorthand: a single :sql string at the top level
                      (when (:sql raw) [{:id :main :kind :sql :conn (:conn raw) :sql (:sql raw) :in (:in raw)}]))
        params-schema (try (m/schema (or (:params raw) [:map]))
                           (catch Exception e (add! [:params] (str "invalid malli schema: " (.getMessage e))) nil))
        ids (set (map :id steps-raw))
        compiled
        (into {}
              (for [{:keys [id kind conn in] :as s} steps-raw]
                (let [ccfg (get conns conn)
                      d (spindle.dialect/get-dialect (:dialect ccfg))
                      base (merge {:skip-when-empty true} s
                                  {:deps (step-deps s)
                                   :tuning (merge (:defaults ccfg) (:tuning s))})]
                  (when-not (keyword? id) (add! [:steps] "every step needs a keyword :id"))
                  (when-not ccfg (add! [:steps id :conn] (str "unknown connection " conn)))
                  (doseq [dep (:deps base) :when (not (ids dep))]
                    (add! [:steps id] (str "refers to unknown step " dep)))
                  (when (and (:or-else s) (not (ids (:or-else s))))
                    (add! [:steps id :or-else] (str "unknown fallback step " (:or-else s))))
                  [id
                   (case kind
                     :sql
                     (let [text (or (:sql s) (get blocks (name id)))]
                       (when (and ccfg (not= :sql (:type ccfg)))
                         (add! [:steps id :conn] "an :sql step needs an :sql connection"))
                       (if-not text
                         (do (add! [:steps id] (str "no SQL: neither :sql nor a `-- :name " (name id) "` block")) base)
                         (let [scanned (sqlfile/scan text)
                               used (set (sqlfile/params-of scanned))
                               bound (set (keys in))]
                           (doseq [p (set/difference used bound)]
                             (add! [:steps id :in] (str "placeholder :" (name p) " has no binding in :in")))
                           (doseq [p (set/difference bound used)]
                             (add! [:steps id :in] (str "binding " p " is not used in the SQL")))
                           (when (not= :read (sqlfile/statement-kind text))
                             (add! [:steps id] "only read statements are allowed (writes come with mutation endpoints)"))
                           (when (and (get-in base [:tuning :isolation]) (not (:isolation? d)))
                             (add! [:steps id :tuning :isolation]
                                   (str "dialect " (name (or (:dialect ccfg) :generic)) " has no transaction isolation")))
                           (assoc base :scanned scanned :sql-text text
                                  :max-rows (or (:max-rows s) (get-in base [:tuning :max-rows]) (:max-rows raw) 10000)))))
                     :http
                     (do (when (and ccfg (not= :http (:type ccfg)))
                           (add! [:steps id :conn] "an :http step needs an :http connection"))
                         (when (not (#{:get :post nil} (get-in s [:request :method])))
                           (add! [:steps id :request :method] "only GET/POST reads are allowed for now"))
                         base)
                     (do (add! [:steps id :kind] (str "unknown step kind " kind)) base))])))
        deps (into {} (map (fn [[id s]] [id (set/intersection (:deps s) ids)])) compiled)
        deadline-ms (conn/parse-duration-ms (get-in raw [:limits :timeout]) 5000)
        out-spec (try (output/compile-spec (merge {:from (first (map :id steps-raw))} (:output raw)))
                      (catch Exception e (add! [:output] (.getMessage e)) nil))]
    (when (empty? steps-raw) (add! [:steps] "a flow needs at least one step"))
    (when (> (count steps-raw) max-steps) (add! [:steps] (str "more than " max-steps " steps")))
    (when (cycle? deps) (add! [:steps] "the step graph has a cycle"))
    (when (> deadline-ms max-deadline-ms) (add! [:limits :timeout] (str "deadline above " max-deadline-ms " ms")))
    (if (seq @problems)
      {:problems @problems}
      {:flow {:id flow-id
              :params-schema params-schema
              :steps compiled
              :output out-spec
              :deadline-ms deadline-ms
              :parallel (:parallel raw)
              :cache (:cache raw)
              :endpoint (:endpoint raw)
              :doc (:doc raw)}})))

;; ---------------------------------------------------------------------------
;; running
;; ---------------------------------------------------------------------------

(defn- sql-values [{:keys [scanned in]} env]
  ;; → {:values {...}} or {:empty true}
  (reduce (fn [acc {:keys [param list?]}]
            (let [v (paths/resolve-path (get in param) env)]
              (if list?
                (let [l (paths/as-list v)]
                  (if (empty? l) (reduced {:empty true}) (assoc-in acc [:values param] l)))
                (let [{:keys [value empty]} (paths/as-scalar v param)]
                  (if empty (reduced {:empty true}) (assoc-in acc [:values param] value))))))
          {:values {}}
          (distinct (filter map? scanned))))

(defn- run-step [snapshot flow-id tag-id {:keys [deadline-ns]} step env cancels]
  (let [rt (get-in snapshot [:conns (:conn step)])
        ctx {:deadline-ns deadline-ns :cancels cancels :tag (str flow-id "#" (name (:id step)) " " tag-id)}
        t0 (System/nanoTime)]
    (try
      (case (:kind step)
        :sql (let [{:keys [values empty]} (sql-values step env)]
               (if (and empty (:skip-when-empty step))
                 []
                 (sql/run-step! rt step values ctx)))
        :http (httpstep/run-step! rt step env ctx))
      (finally
        (spindle.metrics/timer! "spindle_step_seconds" {:endpoint flow-id :step (name (:id step)) :kind (name (:kind step))}
                                (- (System/nanoTime) t0))))))

(defn execute
  "Run a compiled flow. → {:rows .. :partial [...]}"
  [snapshot flow params {:keys [caller request-id]}]
  (let [deadline-ns (+ (System/nanoTime) (* 1000000 (:deadline-ms flow)))
        env {:params params :caller caller :deadline-ns deadline-ns :parallel (:parallel flow)}
        {:keys [results partial]}
        (executor/run (:steps flow)
                      (fn [step env cancels] (run-step snapshot (:id flow) request-id env step env cancels))
                      env)
        zone (some (fn [[_ s]] (get-in snapshot [:conns (:conn s) :cfg :zone])) (:steps flow))
        ;; shaping runs SCI expressions: bound by the remaining deadline
        remaining-ms (max 1 (quot (- deadline-ns (System/nanoTime)) 1000000))
        fut (future (output/shape (:output flow) results zone))
        rows (try (.get ^java.util.concurrent.Future fut remaining-ms TimeUnit/MILLISECONDS)
                  (catch TimeoutException _
                    (future-cancel fut)
                    (err/fail! :timeout "output shaping exceeded the deadline"))
                  (catch ExecutionException e
                    (let [c (.getCause e)]
                      (if (:spindle/code (ex-data c)) (throw c)
                          (err/fail! :internal "output shaping failed" {} c)))))]
    {:rows rows :partial partial}))
