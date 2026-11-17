(ns spindle.flow
  "Compiling flow definitions (EDN + .sql blocks) and running them.

   A flow is an instruction set — data interpreted by the engine. Compilation
   checks everything that can be checked without running: placeholders have
   bindings, paths point at existing steps, the step graph has no cycles, the
   dialect supports what the step asks for, writes are refused (the first
   version is read-only), expressions parse, and — mandatory before publishing —
   every SQL step has a timeout and a row cap.

   Step kinds: :sql, :api (alias :http). Conditions: :when (declarative)."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging]
            [jsonista.core]
            [malli.core :as m]
            [malli.error]
            [malli.transform :as mt]
            [spindle.conn :as conn]
            [spindle.dialect]
            [spindle.error :as err]
            [spindle.metrics]
            [spindle.executor :as executor]
            [spindle.httpstep :as httpstep]
            [spindle.output :as output]
            [spindle.paths :as paths]
            [spindle.sql :as sql]
            [spindle.sqlfile :as sqlfile]
            [spindle.stepcache :as stepcache])
  (:import [java.util.concurrent TimeUnit TimeoutException ExecutionException]))

(def max-steps 16)
(def max-deadline-ms 60000)

(defn- problem [path msg] {:path path :message msg})

(defn- path-steps [p] (when-let [s (paths/step-ref p)] #{s}))

(defn- request-paths [request]
  (concat (vals (:query request)) (vals (:path-params request)) (vals (:body request))
          (filter vector? (vals (:headers request)))))

;; ---------------------------------------------------------------------------
;; :when — declarative conditions over earlier results
;;   {:exists path}  {:empty path}  {:= [path value]}
;;   {:all [c ...]}  {:any [c ...]}  {:not c}
;; ---------------------------------------------------------------------------

(defn- cond-paths [c]
  (cond
    (:exists c) [(:exists c)]
    (:empty c) [(:empty c)]
    (:= c) [(first (:= c))]
    (:all c) (mapcat cond-paths (:all c))
    (:any c) (mapcat cond-paths (:any c))
    (:not c) (cond-paths (:not c))
    :else nil))

(defn- valid-cond? [c]
  (and (map? c) (= 1 (count c))
       (case (key (first c))
         (:exists :empty) (vector? (val (first c)))
         := (and (vector? (val (first c))) (= 2 (count (val (first c)))))
         (:all :any) (and (sequential? (val (first c))) (every? valid-cond? (val (first c))))
         :not (valid-cond? (val (first c)))
         false)))

(defn- present? [v] (if (sequential? v) (boolean (seq v)) (some? v)))

(defn eval-cond [c env]
  (cond
    (:exists c) (present? (paths/resolve-path (:exists c) env))
    (:empty c) (not (present? (paths/resolve-path (:empty c) env)))
    (:= c) (let [[p v] (:= c) x (paths/resolve-path p env)]
             (if (sequential? x) (= [v] (vec x)) (= v x)))
    (:all c) (every? #(eval-cond % env) (:all c))
    (:any c) (boolean (some #(eval-cond % env) (:any c)))
    (:not c) (not (eval-cond (:not c) env))
    :else false))

(defn- step-deps [{:keys [in request or-else fan-out when]}]
  (set (concat (mapcat path-steps (vals in))
               (mapcat path-steps (request-paths request))
               (path-steps fan-out)
               (mapcat path-steps (cond-paths when)))))

(defn- uses-caller? [{:keys [in request when]} conn-cfg]
  (boolean (or (some #(= :caller (first %)) (concat (vals in) (request-paths request) (cond-paths when)))
               (seq (:forward-caller conn-cfg)))))

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
              (for [{:keys [id conn in] :as s} steps-raw
                    :let [kind (if (= :api (:kind s)) :http (:kind s))
                          s (assoc s :kind kind)]]
                (let [ccfg (get conns conn)
                      d (spindle.dialect/get-dialect (:dialect ccfg))
                      caller? (uses-caller? s ccfg)
                      cache-s (stepcache/settings (:cache s))
                      base (merge {:skip-when-empty true :default []} s
                                  {:deps (step-deps s)
                                   :tuning (merge (:defaults ccfg) (:tuning s))
                                   :caller-dependent? caller?
                                   ;; a caller-dependent step is always cached per caller
                                   :step-cache (when cache-s (cond-> cache-s caller? (assoc :per :caller)))})]
                  (when (and (:when s) (not (valid-cond? (:when s))))
                    (add! [:steps id :when] "invalid condition; use {:exists path} {:empty path} {:= [path v]} {:all [..]} {:any [..]} {:not c}"))
                  (when-not (keyword? id) (add! [:steps] "every step needs a keyword :id"))
                  (when-not ccfg (add! [:steps id :conn] (str "unknown connection " conn)))
                  (doseq [dep (:deps base) :when (not (ids dep))]
                    (add! [:steps id] (str "refers to unknown step " dep)))
                  (when-let [t (:take s)]
                    (when-not (or (pos-int? t) (and (vector? t) (= :params (first t))))
                      (add! [:steps id :take] "take must be a positive integer or a [:params ...] path")))
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
                           ;; mandatory before publishing: every SQL step has a timeout and a row cap
                           (when-not (get-in base [:tuning :timeout])
                             (add! [:steps id :tuning :timeout]
                                   "every SQL step needs a timeout (step :tuning :timeout or the connection's :defaults :timeout)"))
                           (let [mr (or (:max-rows s) (get-in base [:tuning :max-rows]) (get-in raw [:limits :max-rows]))]
                             (when-not mr
                               (add! [:steps id :max-rows]
                                     "every SQL step needs a row cap (:max-rows on the step, the flow's :limits, or the connection's :defaults)"))
                             (assoc base :scanned scanned :sql-text text :max-rows mr)))))
                     :http
                     (do (when (and ccfg (not= :http (:type ccfg)))
                           (add! [:steps id :conn] "an :http step needs an :http connection"))
                         (when (not (#{:get :post nil} (get-in s [:request :method])))
                           (add! [:steps id :request :method] "only GET/POST reads are allowed for now"))
                         base)
                     (do (add! [:steps id :kind] (str "unknown step kind " kind " (use :sql or :api)")) base))])))
        deps (into {} (map (fn [[id s]] [id (set/intersection (:deps s) ids)])) compiled)
        deadline-ms (conn/parse-duration-ms (get-in raw [:limits :timeout]) 5000)
        out-spec (try (output/compile-spec (merge {:from (first (map :id steps-raw))} (:output raw)))
                      (catch Exception e (add! [:output] (.getMessage e)) nil))]
    (when (empty? steps-raw) (add! [:steps] "a flow needs at least one step"))
    (when (> (count steps-raw) max-steps) (add! [:steps] (str "more than " max-steps " steps")))
    (when (cycle? deps) (add! [:steps] "the step graph has a cycle"))
    (when (> deadline-ms max-deadline-ms) (add! [:limits :timeout] (str "deadline above " max-deadline-ms " ms")))
    ;; batch: the key is a list parameter; rows must be regroupable by it
    (when-let [{bk :key} (get-in raw [:endpoint :batch])]
      (let [entries (when (and params-schema (= :map (m/type params-schema))) (m/children params-schema))
            ks (some (fn [[k _ sch]] (when (= k bk) sch)) entries)]
        (when-not (and ks (#{:vector :sequential :set} (m/type ks)))
          (add! [:endpoint :batch :key] (str "batch key " bk " must be a list parameter in :params, e.g. [" bk " [:vector {:max 500} pos-int?]]")))
        (when (get-in raw [:output :one?]) (add! [:endpoint :batch] "a batch endpoint returns rows; drop :one? from :output"))))
    ;; keyset paging: rows (never :one?), size within the row cap, and a step that binds [:page :limit]
    (when-let [{:keys [size max-size] :or {size 50 max-size 500}} (get-in raw [:endpoint :page])]
      (let [root (get compiled (:from out-spec))
            uses-limit? (some (fn [[_ st]] (some #(= [:page :limit] %) (vals (:in st)))) compiled)]
        (when (get-in raw [:output :one?]) (add! [:endpoint :page] "a paged endpoint returns rows; drop :one? from :output"))
        (when (get-in raw [:endpoint :batch]) (add! [:endpoint :page] "an endpoint is either paged or a batch, not both"))
        (when (> size max-size) (add! [:endpoint :page :size] "size is above max-size"))
        (when (and (:max-rows root) (> (inc max-size) (:max-rows root)))
          (add! [:endpoint :page :max-size] (str "max-size + 1 must fit the root step's row cap (" (:max-rows root) ")")))
        (when-not uses-limit?
          (add! [:endpoint :page] "no step binds [:page :limit]; the root SQL must fetch size + 1 rows (e.g. limit :limit)"))))
    (if (seq @problems)
      {:problems @problems}
      {:flow {:id flow-id
              :params-schema params-schema
              :steps compiled
              :output out-spec
              :deadline-ms deadline-ms
              :parallel (:parallel raw)
              :cache (:cache raw)
              :caller-dependent? (boolean (some :caller-dependent? (vals compiled)))
              :examples (:examples raw)
              :endpoint (:endpoint raw)
              :doc (:doc raw)}})))

(defn- split-lists
  "Comma-separated strings for vector-typed params become vectors (Semalith's
   batch placeholder sends `1,2,3`)."
  [schema params]
  (if-not schema
    params
    (let [entries (when (= :map (m/type schema)) (m/children schema))
          list-keys (set (keep (fn [[k _ s]] (when (#{:vector :sequential :set} (m/type s)) k)) entries))]
      (reduce (fn [p k]
                (let [v (get p k)]
                  (cond (string? v) (assoc p k (vec (remove str/blank? (map str/trim (str/split v #",")))))
                        :else p)))
              params list-keys))))

(defn coerce-params
  "Raw request params → params decoded and validated against the flow schema." [schema raw]
  (let [raw (split-lists schema raw)
        v (m/decode schema raw (mt/transformer (mt/default-value-transformer {::mt/add-optional-keys true}) mt/string-transformer mt/strip-extra-keys-transformer))]
    (if (m/validate schema v)
      v
      (err/fail! :input_rejected (str "invalid parameters: "
                                      (pr-str (malli.error/humanize (m/explain schema v))))))))

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
                (if (= :page (first (get in param)))
                  (assoc-in acc [:values param] v)        ; keyset paging: nil on the first page is NULL, not "skip"
                  (let [{:keys [value empty]} (paths/as-scalar v param)]
                    (if empty (reduced {:empty true}) (assoc-in acc [:values param] value)))))))
          {:values {}}
          (distinct (filter map? scanned))))

(defn- resolve-request [request env]
  (let [rv (fn [m] (into (sorted-map) (map (fn [[k p]] [k (if (vector? p) (paths/resolve-path p env) p)])) m))]
    (-> request
        (update :query rv) (update :path-params rv) (update :body #(when % (rv %))) (update :headers rv))))

(defn- note!
  "Add facts to this step's trace entry (when the request is traced)."
  [env step m]
  (when-let [t (:trace env)] (swap! t update (:id step) merge m)))

(defn- cached
  "Run `compute` through the step cache when the step has :cache settings."
  [step flow-id env cache-payload compute]
  (if-let [s (when-not (:no-cache? env) (:step-cache step))]
    (let [k [(:conn step) (:kind step) cache-payload
             (when (= :caller (:per s)) (stepcache/caller-scope (:caller env)))]
          {:keys [rows cache]} (stepcache/lookup k s [flow-id (name (:id step))] compute)]
      (note! env step {:cache cache})
      rows)
    (compute)))

(declare run-step-kind)

;; ---------------------------------------------------------------------------
;; slow-query log: one JSON line per step over its threshold (logger spindle.slow)
;;   threshold: step/connection :tuning :slow ("500ms"), else SPINDLE_SLOW_MS, else 1000 ms
;; ---------------------------------------------------------------------------

(def ^:private default-slow-ms
  (delay (or (some-> (System/getenv "SPINDLE_SLOW_MS") parse-long) 1000)))

(defn- params-hash [params]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes (pr-str (into (sorted-map) params)) "UTF-8"))]
    (subs (apply str (map #(format "%02x" %) d)) 0 16)))

(defn- slow-log! [flow-id step env ns outcome rows]
  (let [limit-ms (conn/parse-duration-ms (get-in step [:tuning :slow]) @default-slow-ms)
        ms (quot ns 1000000)]
    (when (and (pos? limit-ms) (>= ms limit-ms))
      (spindle.metrics/counter! "spindle_slow_steps_total" {:endpoint flow-id :step (name (:id step))})
      (clojure.tools.logging/log "spindle.slow" :warn nil
                                 (jsonista.core/write-value-as-string
                                  {:event "slow-step" :endpoint flow-id :step (name (:id step)) :kind (name (:kind step))
                                   :conn (name (:conn step)) :flow_version (:flow-version env) :ms ms :threshold_ms limit-ms
                                   :rows rows :outcome outcome :params_hash (params-hash (:params env))
                                   :request_id (:request-id env)})))))

(defn- run-step [snapshot flow-id tag-id {:keys [deadline-ns]} step env cancels]
  (let [ctx {:deadline-ns deadline-ns :cancels cancels :tag (str flow-id "#" (name (:id step)) " " tag-id)}
        t0 (System/nanoTime)
        outcome (volatile! "error")
        nrows (volatile! nil)]
    (try
      (if (and (:when step) (not (eval-cond (:when step) env)))
        (do (vreset! outcome "skipped")
            (:default step))                  ; skipped: a stable, declared value
        (let [v (run-step-kind snapshot flow-id step env ctx)]
          (vreset! outcome "ok")
          (vreset! nrows (if (sequential? v) (count v) (if (nil? v) 0 1)))
          v))
      (finally
        (let [ns (- (System/nanoTime) t0)]
          (slow-log! flow-id step env ns @outcome @nrows)
          (note! env step {:kind (name (:kind step)) :conn (name (:conn step)) :start t0 :ms (quot ns 1000000) :outcome @outcome})
          (spindle.metrics/timer! "spindle_step_seconds" {:endpoint flow-id :step (name (:id step)) :kind (name (:kind step))} ns))))))

(defn- run-step-kind [snapshot flow-id step env ctx]
  (let [rt (get-in snapshot [:conns (:conn step)])]
        (case (:kind step)
          :sql (let [{:keys [values empty]} (sql-values step env)
                     ;; :take caps the rows returned (a number or a [:params ...] path)
                     take (let [t (:take step)] (if (vector? t) (paths/resolve-path t env) t))]
                 (if (and empty (:skip-when-empty step))
                   []
                   (cached step flow-id env [(:sql-text step) (into (sorted-map) values) take]
                           #(sql/run-step! rt step values (cond-> ctx (pos-int? take) (assoc :take take))))))
          :http (let [env (cond-> env (seq (get-in rt [:cfg :forward-caller])) (assoc :forward-caller (get-in rt [:cfg :forward-caller])))]
                  (httpstep/extract
                   step
                   (cached step flow-id env [(resolve-request (:request step) env)
                                             (when-let [f (:fan-out step)] (paths/resolve-path f env))]
                           #(httpstep/fetch! rt step env ctx)))))))

(defn execute
  "Run a compiled flow. → {:rows .. :partial [...] :checks [...] :trace [...]?}
   :no-cache? bypasses the step cache (neither read nor filled);
   :trace? collects, per step, kind, connection, ms, outcome and cache state;
   :on-step (fn [step-id rows ms]) is called after each successful step."
  [snapshot flow params {:keys [caller request-id no-cache? on-step trace? page]}]
  (let [deadline-ns (+ (System/nanoTime) (* 1000000 (:deadline-ms flow)))
        trace (when trace? (atom {}))
        env (cond-> {:params params :caller caller :deadline-ns deadline-ns :parallel (:parallel flow)
                     :page page :flow-version (:version flow) :request-id request-id}
              no-cache? (assoc :no-cache? true)
              trace (assoc :trace trace))
        run1 (fn [step env cancels] (run-step snapshot (:id flow) request-id env step env cancels))
        {:keys [results partial]}
        (executor/run (:steps flow)
                      (fn [step env cancels]
                        (let [t0 (System/nanoTime) rows (run1 step env cancels)]
                          (when trace (swap! trace update (:id step) assoc :rows (if (sequential? rows) (count rows) (if (nil? rows) 0 1))))
                          (when on-step (on-step (:id step) rows (quot (- (System/nanoTime) t0) 1000000)))
                          rows))
                      env)
        zone (some (fn [[_ s]] (get-in snapshot [:conns (:conn s) :cfg :zone])) (:steps flow))
        ;; shaping runs SCI expressions: bound by the remaining deadline
        remaining-ms (max 1 (quot (- deadline-ns (System/nanoTime)) 1000000))
        fut (future (output/shape-with-checks (:output flow) results zone))
        {:keys [rows checks]}
        (try (.get ^java.util.concurrent.Future fut remaining-ms TimeUnit/MILLISECONDS)
             (catch TimeoutException _
               (future-cancel fut)
               (err/fail! :timeout "output shaping exceeded the deadline"))
             (catch ExecutionException e
               (let [c (.getCause e)]
                 (if (:spindle/code (ex-data c)) (throw c)
                     (err/fail! :internal "output shaping failed" {} c)))))]
    (when (and (seq checks) (= :error (get-in flow [:output :on-fail])))
      (err/fail! :assertion_failed (str (count checks) " assertion(s) did not pass") {:checks checks}))
    (cond-> {:rows rows :partial partial :checks checks}
      trace (assoc :trace (vec (for [[id m] (sort-by (comp :start val) @trace)]
                                 (cond-> (-> m (dissoc :start) (assoc :step (name id)))
                                   (:cache m) (update :cache name))))))))
