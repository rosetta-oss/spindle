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
            [spindle.result :as result]
            [spindle.sql :as sql]
            [spindle.sqlfile :as sqlfile]
            [spindle.stepcache :as stepcache]
            [spindle.tx :as tx])
  (:import [java.util.concurrent TimeUnit TimeoutException ExecutionException]))

(def max-steps 16)
(def max-deadline-ms 60000)

(defn- problem [path msg] {:path path :message msg})

(defn- path-steps [p] (when-let [s (paths/step-ref p)] #{s}))

(defn- request-paths [request]
  (concat (paths/template-paths (vec (vals (:query request)))) (vals (:path-params request))
          (paths/template-paths (:body request))
          (filter vector? (vals (:headers request)))))

;; ---------------------------------------------------------------------------
;; :when — declarative conditions over earlier results
;;   {:exists path}  {:empty path}  {:= [path value]}
;;   {:all [c ...]}  {:any [c ...]}  {:not c}
;; ---------------------------------------------------------------------------

(def ^:private path-roots-base #{:params :caller :page :fan})

(defn- cond-paths [c]
  (cond
    (vector? c) (result/paths-in c)          ; *path-roots* bound by compile-flow
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
    (vector? c) (binding [result/*path-roots* (into path-roots-base (:step-ids env))
                          result/*resolve-path* #(paths/resolve-path % env)]
                  (= :pass (result/check c {})))
    (:exists c) (present? (paths/resolve-path (:exists c) env))
    (:empty c) (not (present? (paths/resolve-path (:empty c) env)))
    (:= c) (let [[p v] (:= c) x (paths/resolve-path p env)]
             (if (sequential? x) (= [v] (vec x)) (= v x)))
    (:all c) (every? #(eval-cond % env) (:all c))
    (:any c) (boolean (some #(eval-cond % env) (:any c)))
    (:not c) (not (eval-cond (:not c) env))
    :else false))

(defn- step-deps [{:keys [in request or-else fan-out when after]}]
  (set (concat after                       ; explicit order (e.g. a read-back after a write)
               (mapcat path-steps (vals in))
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

(defn- output-sources [spec]
  (concat [(:from spec)] (some-> spec :merge :from vector)
          (mapcat (fn [[_ sub]] (output-sources sub)) (:nest spec))))

(defn prune
  "Keep the steps the output reads, their dependencies and their fallbacks.
   `provided` maps names a :proc step provides to that step."
  [steps out-spec provided]
  (loop [keep #{} todo (set (map #(get provided % %) (remove nil? (output-sources out-spec))))]
    (if-let [id (first todo)]
      (let [st (get steps id)
            more (remove keep (map #(get provided % %) (concat (:deps st) (some-> st :or-else vector))))]
        (recur (conj keep id) (into (disj todo id) more)))
      (select-keys steps keep))))

(defn provides
  "Names a :proc step adds to the results besides its own id: :results and cursor OUTs."
  [{:keys [kind results args]}]
  (when (= :proc kind)
    (concat results (keep #(when (and (map? %) (= :cursor (:type %))) (:out %)) args))))

(defn- proc-problems [{:keys [call args in results status fail-on-status procedure]}]
  (let [ins (set (filter keyword? args))
        inouts (set (keep #(when (and (map? %) (:inout %)) (:out %)) args))
        bound (set (keys in))]
    (concat
     (when-not (and (string? call) (re-matches #"[A-Za-z_#][A-Za-z0-9_.$#]*" call))
       [":call must be a procedure name such as dbo.usp_order or sales.order_summary"])
     (for [a args :when (not (or (keyword? a) (and (map? a) (keyword? (:out a)) (contains? sql/proc-types (:type a)))))]
       (str "argument " (pr-str a) " must be an input name (keyword) or {:out :name :type t} with t in "
            (pr-str (sort (keys sql/proc-types)))))
     (for [p (set/difference (set/union ins inouts) bound)] (str "argument " p " has no binding in :in"))
     (for [p (set/difference bound ins inouts)] (str "binding " p " is not an argument"))
     (when (and fail-on-status (not= :non-zero fail-on-status)) [":fail-on-status takes :non-zero"])
     (when (and fail-on-status (not status)) [":fail-on-status needs :status (the return status field)"])
     (when (and procedure status) [":procedure (a PostgreSQL CALL) has no return status; use an OUT parameter"])
     (for [r results :when (not (keyword? r))] (str "result set name " (pr-str r) " must be a keyword")))))

(declare compile-flow*)

(def ^:private schema-sql-types
  {'int? java.sql.Types/INTEGER 'pos-int? java.sql.Types/INTEGER 'nat-int? java.sql.Types/INTEGER
   'neg-int? java.sql.Types/INTEGER :int java.sql.Types/INTEGER
   'double? java.sql.Types/DOUBLE :double java.sql.Types/DOUBLE 'number? java.sql.Types/DOUBLE
   'boolean? java.sql.Types/BIT :boolean java.sql.Types/BIT})

(defn- param-sql-types
  "Top-level params → java.sql.Types for binding a missing value as NULL.
   Strings, dates and anything else stay untyped (bound as VARCHAR on ASE)."
  [params-schema]
  (when (and params-schema (= :map (m/type params-schema)))
    (into {} (for [[k _ sch] (m/children params-schema)
                   :let [sch (if (= :maybe (m/type sch)) (first (m/children sch)) sch)
                         t (schema-sql-types (m/form sch) (schema-sql-types (m/type sch)))]
                   :when t]
               [k t]))))

(defn- null-types
  "placeholder → java.sql.Types for a step's [:params k] inputs (nil when none)."
  [in param-types]
  (not-empty (into {} (for [[ph path] in
                            :when (and (vector? path) (= 2 (count path)) (= :params (first path)))
                            :let [t (get param-types (second path))] :when t]
                        [ph t]))))

(defn- type-nulls [values nts]
  (if nts
    ;; over the typed placeholders: a missing value may be absent from `values` entirely
    (reduce-kv (fn [m k t] (if (nil? (get m k)) (assoc m k (sql/typed-null t)) m)) (or values {}) nts)
    values))

(defn compile-flow
  "→ {:flow compiled} or {:problems [...]}.
   `raw` is the flow EDN, `blocks` the .sql blocks, `conns` {id resolved-config}."
  [flow-id raw blocks conns]
  (binding [result/*path-roots* (into (into path-roots-base
                                            (map :id (or (:steps raw) (when (:sql raw) [{:id :main}]))))
                                      (mapcat provides (:steps raw)))]
    (compile-flow* flow-id raw blocks conns)))

(defn- compile-flow* [flow-id raw blocks conns]
  (let [problems (atom [])
        add! (fn [path msg] (swap! problems conj (problem (into [flow-id] path) msg)))
        steps-raw (or (:steps raw)
                      ;; shorthand: a single :sql string at the top level
                      (when (:sql raw) [{:id :main :kind :sql :conn (:conn raw) :sql (:sql raw) :in (:in raw)}]))
        params-schema (try (m/schema (or (:params raw) [:map]))
                           (catch Exception e (add! [:params] (str "invalid malli schema: " (.getMessage e))) nil))
        ;; names a :proc step provides (its result sets, cursor OUTs) → that step
        provided (into {} (for [s steps-raw n (provides s)] [n (:id s)]))
        step-ids (set (map :id steps-raw))
        ids (into step-ids (keys provided))
        mutation? (true? (get-in raw [:endpoint :mutation]))
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
                                   :null-types (when (#{:sql :proc} kind) (null-types in (param-sql-types params-schema)))
                                   :tuning (merge (:defaults ccfg) (:tuning s))
                                   :caller-dependent? caller?
                                   ;; a caller-dependent step is always cached per caller
                                   :step-cache (when cache-s (cond-> cache-s caller? (assoc :per :caller)))})]
                  (when (vector? (:when s))
                    (doseq [p (result/assert-problems (:when s))] (add! [:steps id :when] p)))
                  (when (and (map? (:when s)) (not (valid-cond? (:when s))))
                    (add! [:steps id :when] "invalid condition; use {:exists path} {:empty path} {:= [path v]} {:all [..]} {:any [..]} {:not c}"))
                  (when-not (keyword? id) (add! [:steps] "every step needs a keyword :id"))
                  (when (or (result/operators id) (path-roots-base id))
                    (add! [:steps id] (str "step id " id " is reserved (an operator or a path root); rename the step")))
                  (when-not ccfg (add! [:steps id :conn] (str "unknown connection " conn)))
                  (doseq [dep (:deps base) :when (not (ids dep))]
                    (add! [:steps id] (str "refers to unknown step " dep)))
                  (when-let [t (:take s)]
                    (when-not (or (pos-int? t) (and (vector? t) (= :params (first t))))
                      (add! [:steps id :take] "take must be a positive integer or a [:params ...] path")))
                  (when (and (:or-else s) (not (step-ids (:or-else s))))
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
                           (when (and (not= :read (sqlfile/statement-kind text)) (not mutation?))
                             (add! [:steps id] "a write statement needs a mutation endpoint (:endpoint {:mutation true ...})"))
                           (when (and (get-in base [:tuning :isolation]) (not (:isolation? d)))
                             (add! [:steps id :tuning :isolation]
                                   (str "dialect " (name (or (:dialect ccfg) :generic)) " has no transaction isolation")))
                           ;; mandatory before publishing: every SQL step has a timeout and a row cap
                           (when-not (get-in base [:tuning :timeout])
                             (add! [:steps id :tuning :timeout]
                                   "every SQL step needs a timeout (step :tuning :timeout or the connection's :defaults :timeout)"))
                           ;; precedence: step :max-rows > step :tuning > flow :limits > connection :defaults
                           (let [mr (or (:max-rows s) (get-in s [:tuning :max-rows]) (get-in raw [:limits :max-rows])
                                        (get-in ccfg [:defaults :max-rows]))]
                             (when-not mr
                               (add! [:steps id :max-rows]
                                     "every SQL step needs a row cap (:max-rows on the step, the flow's :limits, or the connection's :defaults)"))
                             (cond-> (assoc base :scanned scanned :sql-text text :max-rows mr)
                               (not= :read (sqlfile/statement-kind text)) (assoc :write? true))))))
                     :http
                     (do (when (and ccfg (not= :http (:type ccfg)))
                           (add! [:steps id :conn] "an :http step needs an :http connection"))
                         (when-let [rp (:rows s)]
                           (when-not (re-matches #"\$(\.[A-Za-z_][A-Za-z0-9_-]*|\.@vals|\[\*\])*" rp)
                             (add! [:steps id :rows] (str "unsupported JSON path " rp "; use $, $.a.b, $[*], $.a[*].b, $.a.@vals[*]"))))
                         (when (and (:concurrency s) (not (:fan-out s)))
                           (add! [:steps id :concurrency] ":concurrency is for :fan-out steps"))
                         (let [w? (contains? #{:put :patch :delete} (get-in s [:request :method]))]
                           (when (and w? (not mutation?))
                             (add! [:steps id :request :method] "PUT/PATCH/DELETE are writes: they need a mutation endpoint"))
                           (cond-> base w? (assoc :write? true))))
                     :proc
                     (do (when (and ccfg (not= :sql (:type ccfg)))
                           (add! [:steps id :conn] "a :proc step needs an :sql connection"))
                         (when (and ccfg (not (:procedures? d)))
                           (add! [:steps id :conn] (str "dialect " (name (or (:dialect ccfg) :generic)) " has no stored procedures")))
                         (doseq [p (proc-problems s)] (add! [:steps id] p))
                         (when-not (or (:read-only s) mutation?)
                           (add! [:steps id :read-only]
                                 "a procedure counts as a write: declare :read-only true, or publish it on a mutation endpoint"))
                         (when-not (get-in base [:tuning :timeout])
                           (add! [:steps id :tuning :timeout] "every :proc step needs a timeout"))
                         (let [mr (or (:max-rows s) (get-in s [:tuning :max-rows]) (get-in raw [:limits :max-rows])
                                      (get-in ccfg [:defaults :max-rows]))]
                           (when-not mr (add! [:steps id :max-rows] "every :proc step needs a row cap (per result set)"))
                           (cond-> (assoc base :max-rows mr :provides (vec (provides s)))
                             (not (:read-only s)) (assoc :write? true))))
                     (do (add! [:steps id :kind] (str "unknown step kind " kind " (use :sql, :api or :proc)")) base))])))
        ;; dependencies on a provided name are dependencies on the :proc step
        deps (into {} (map (fn [[id s]] [id (set (keep #(get provided % (step-ids %)) (:deps s)))])) compiled)
        deadline-ms (conn/parse-duration-ms (get-in raw [:limits :timeout]) 5000)
        out-spec (try (output/compile-spec (merge {:from (first (map :id steps-raw))} (:output raw)))
                      (catch Exception e (add! [:output] (.getMessage e)) nil))]
    (when (empty? steps-raw) (add! [:steps] "a flow needs at least one step"))
    (when (> (count steps-raw) max-steps) (add! [:steps] (str "more than " max-steps " steps")))
    (when (cycle? deps) (add! [:steps] "the step graph has a cycle"))
    (when (> deadline-ms max-deadline-ms) (add! [:limits :timeout] (str "deadline above " max-deadline-ms " ms")))
    ;; mutations: one transaction per connection, never cached, steps on a connection in order
    (if mutation?
      (let [ep (:endpoint raw)
            sql-conns (distinct (keep (fn [s] (when (#{:sql :proc} (:kind s)) (:conn s))) steps-raw))]
        (when-not (contains? #{:post :put :patch :delete} (:method ep))
          (add! [:endpoint :method] "a mutation endpoint uses :method :post, :put, :patch or :delete"))
        (when (:cache raw) (add! [:cache] "a mutation is never cached"))
        (doseq [s steps-raw :when (:cache s)] (add! [:steps (:id s) :cache] "steps of a mutation are never cached"))
        (doseq [s steps-raw :when (and (:optional s) (#{:sql :proc} (:kind s)))]
          (add! [:steps (:id s) :optional] "a failed statement aborts the transaction: SQL steps of a mutation cannot be :optional"))
        (when (or (:batch ep) (:page ep)) (add! [:endpoint] "a mutation endpoint is neither a batch nor paged"))
        (doseq [[c n] (:parallel raw) :when (and (> n 1) (some #{c} sql-conns))]
          (add! [:parallel c] "steps on one connection share its transaction and run one at a time"))
        (doseq [c (:commit-order ep) :when (not (some #{c} sql-conns))]
          (add! [:endpoint :commit-order] (str c " is not a connection this flow's SQL steps use")))
        (doseq [k [:idempotency :audit] :let [{:keys [conn table]} (get ep k)] :when conn]
          (when-not (= :sql (:type (get conns conn))) (add! [:endpoint k :conn] (str conn " is not an :sql connection")))
          (when-not (re-matches #"[A-Za-z_][A-Za-z0-9_.]*" (str (or table "x")))
            (add! [:endpoint k :table] "table must be a plain (schema-qualified) name")))
        (when-not (some :write? (vals compiled))
          (add! [:endpoint :mutation] "a mutation flow has no write step")))
      (do (doseq [s steps-raw :when (:expect s)] (add! [:steps (:id s) :expect] ":expect is for write steps"))
          (when-not (contains? #{nil :get :post} (get-in raw [:endpoint :method]))
            (add! [:endpoint :method] "PUT/PATCH/DELETE endpoints are mutations: add :mutation true"))))
    (doseq [[id st] compiled :when (and (:expect st) (not (:write? st)))]
      (add! [:steps id :expect] ":expect is for write steps"))
    ;; batch: the key is a list parameter; rows must be regroupable by it
    (when-let [{bk :key gb :group-by} (get-in raw [:endpoint :batch])]
      (let [entries (when (and params-schema (= :map (m/type params-schema))) (m/children params-schema))
            ks (some (fn [[k _ sch]] (when (= k bk) sch)) entries)]
        (when-not (and ks (#{:vector :sequential :set} (m/type ks)))
          (add! [:endpoint :batch :key] (str "batch key " bk " must be a list parameter in :params, e.g. [" bk " [:vector {:max 500} pos-int?]]")))
        ;; :group-by: the other parts of a compound key (order_no + order_type): one value per item, one run per group
        (doseq [g gb]
          (let [sch (some (fn [[k _ sch]] (when (= k g) sch)) entries)]
            (cond
              (= g bk) (add! [:endpoint :batch :group-by] (str g " is the batch key itself"))
              (nil? sch) (add! [:endpoint :batch :group-by] (str "group-by field " g " must be a parameter in :params"))
              (#{:vector :sequential :set} (m/type sch))
              (add! [:endpoint :batch :group-by] (str "group-by field " g " takes one value per item, not a list")))))
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
              :view (:spindle/view raw)
              :params-schema params-schema
              ;; a view runs only the steps its output needs
              :steps (if (:spindle/view raw) (prune compiled out-spec provided) compiled)
              :output out-spec
              :deadline-ms deadline-ms
              :parallel (:parallel raw)
              :mutation? mutation?
              :commit-order (when mutation?
                              (vec (distinct (concat (get-in raw [:endpoint :commit-order])
                                                     (keep (fn [s] (when (#{:sql :proc} (:kind s)) (:conn s))) steps-raw)))))
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
  ;; → {:values {...} :empty bool}. Every input is resolved even after an empty one:
  ;; with :skip-when-empty false the statement runs, and an empty input is NULL
  ;; (or an empty IN list) while the others keep their values.
  (reduce (fn [acc {:keys [param list?]}]
            (let [v (paths/resolve-path (get in param) env)]
              (if list?
                (let [l (paths/as-list v)]
                  (cond-> (assoc-in acc [:values param] (vec l)) (empty? l) (assoc :empty true)))
                (if (= :page (first (get in param)))
                  (assoc-in acc [:values param] v)        ; keyset paging: nil on the first page is NULL, not "skip"
                  (let [{:keys [value empty]} (paths/as-scalar v param)]
                    (cond-> (assoc-in acc [:values param] (when-not empty value)) empty (assoc :empty true)))))))
          {:values {}}
          (distinct (filter map? scanned))))

(defn- resolve-request [request env]
  (let [rv (fn [m] (into (sorted-map) (map (fn [[k p]] [k (if (vector? p) (paths/resolve-path p env) p)])) m))]
    (-> request
        (update :query rv) (update :path-params rv) (update :body #(when % (paths/resolve-template % env))) (update :headers rv))))

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

(declare run-step-kind run-step-kind*)

(defn affected-of
  "Rows a write step changed: its update count, or the rows it returned."
  [rows]
  (cond (and (sequential? rows) (= 1 (count rows)) (contains? (first rows) :affected)) (:affected (first rows))
        (sequential? rows) (count rows)
        :else 0))

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

(defn- check-expect! [step rows]
  (when-let [want (get-in step [:expect :rows])]
    (let [n (affected-of rows)]
      (when (not= want n)
        (err/fail! (if (zero? n) :not_found :conflict)
                   (str "step " (name (:id step)) " changed " n " rows; expected " want)
                   {:step (:id step)}))))
  rows)

(defn- run-step-kind [snapshot flow-id step env ctx]
  (let [rt (get-in snapshot [:conns (:conn step)])
        ;; mutation flows: :sql/:proc steps run on the request's transaction
        ctx (if-let [tx (when (#{:sql :proc} (:kind step)) (:tx env))]
              (assoc ctx :con #(tx/connection! tx (:conn step)) :on-broken #(tx/mark-broken! tx (:conn step)))
              ctx)
        rows (run-step-kind* snapshot flow-id step env ctx rt)]
    (when (:write? step)
      (when-let [a (:affected env)] (swap! a assoc (:id step) (affected-of rows))))
    (if (:write? step) (check-expect! step rows) rows)))

(defn- run-step-kind* [snapshot flow-id step env ctx rt]
  (let []
        (case (:kind step)
          :sql (let [{:keys [values empty]} (sql-values step env)
                     values (type-nulls values (:null-types step))
                     ;; :take caps the rows returned (a number or a [:params ...] path)
                     take (let [t (:take step)] (if (vector? t) (paths/resolve-path t env) t))]
                 (if (and empty (:skip-when-empty step))
                   (:default step)
                   (cached step flow-id env [(:sql-text step) (into (sorted-map) values) take]
                           #(sql/run-step! rt step values (cond-> (assoc ctx :chunk-parallel (get-in env [:parallel (:conn step)] 1))
                                                            (pos-int? take) (assoc :take take))))))
          :proc (let [values (into {} (for [[k p] (:in step)]
                                        [k (:value (paths/as-scalar (paths/resolve-path p env) k))]))
                      values (type-nulls values (:null-types step))]
                  (cached step flow-id env [(:call step) (into (sorted-map) values)]
                          #(sql/run-proc! rt step values ctx)))
          :http (let [env (cond-> env (seq (get-in rt [:cfg :forward-caller])) (assoc :forward-caller (get-in rt [:cfg :forward-caller])))]
                  (httpstep/extract
                   step
                   (cached step flow-id env [(resolve-request (:request step) env)
                                             (when-let [f (:fan-out step)] (paths/resolve-path f env))]
                           #(httpstep/fetch! rt step env ctx)))))))

(declare execute*)

(defn- with-outcome [^Throwable t outcome]
  (if-let [d (err/code-of t)]
    (ex-info (.getMessage t) (assoc d :outcome outcome) t)
    (ex-info "flow failed" {:spindle/code :internal :outcome outcome} t)))

(defn execute
  "Run a compiled flow. → {:rows .. :partial [...] :checks [...] :trace [...]?}
   :no-cache? bypasses the step cache (neither read nor filled);
   :trace? collects, per step, kind, connection, ms, outcome and cache state;
   :on-step (fn [step-id rows ms]) is called after each successful step.

   Mutation flows run in one transaction per connection (spindle.tx). They
   commit only with :commit? true, after (before-commit tx result) — used to
   record the idempotency key in the same transaction. Without :commit? (a
   trial run) everything is rolled back. → adds :affected {step n} and
   :outcome (:committed | :rolled_back); failures carry :outcome too."
  [snapshot flow params {:keys [commit? before-commit] :as opts}]
  (if-not (:mutation? flow)
    (execute* snapshot flow params opts nil)
    (let [tx (tx/new-tx snapshot (:commit-order flow))
          affected (atom {})]
      (try
        (let [r (assoc (execute* snapshot flow params opts {:tx tx :affected affected}) :affected @affected)]
          (if commit?
            (do (when before-commit (before-commit tx r))
                (merge r (tx/commit-all! tx)))
            (do (tx/rollback-all! tx)
                (assoc r :outcome :rolled_back :trial true))))
        (catch Throwable t
          (tx/rollback-all! tx)
          (throw (if (:outcome (ex-data t)) t (with-outcome t :rolled_back))))))))

(defn- execute*
  [snapshot flow params {:keys [caller request-id no-cache? on-step trace? page]} extra-env]
  (let [deadline-ns (+ (System/nanoTime) (* 1000000 (:deadline-ms flow)))
        trace (when trace? (atom {}))
        env (cond-> {:params params :caller caller :deadline-ns deadline-ns :parallel (:parallel flow)
                     :page page :flow-version (:version flow) :request-id request-id
                     :step-ids (into (set (keys (:steps flow))) (mapcat :provides (vals (:steps flow))))
                     :strict (set (mapcat (fn [[id st]] (when (#{:sql :proc} (:kind st)) (cons id (:provides st)))) (:steps flow)))}
              no-cache? (assoc :no-cache? true)
              trace (assoc :trace trace)
              extra-env (merge extra-env))
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
