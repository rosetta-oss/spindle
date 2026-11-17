(ns spindle.mcp
  "Authoring MCP: schema and flow introspection for agents. Streamable HTTP only
   (the legacy HTTP+SSE routes need :sse-writer and are not wired here)."
  (:require [shuttle.http :as shttp]
            [shuttle.tools :as tools]
            [spindle.auth :as auth]
            [spindle.config :as config]
            [spindle.conn :as conn]
            [spindle.mcp.introspect :as introspect]
            [spindle.mcp.trial :as trial]))

(def author-scope "spindle:author")
(def version "0.1.0")

(defn valid-flow-id? [s]
  (boolean (and (string? s) (<= (count s) 128)
                (re-matches #"[a-z0-9][a-z0-9_-]*(/[a-z0-9][a-z0-9_-]*)*" s))))

(defn- sql-conn
  "→ [rt nil] or [nil {:error ..}]"
  [id]
  (let [rt (get-in (config/current) [:conns (keyword id)])]
    (cond (nil? rt) [nil {:error (str "no such connection: " id)}]
          (not (:ds rt)) [nil {:error "not a SQL connection"}]
          :else [rt nil])))

(defn- safe-defaults [d]
  (into {} (remove (fn [[k _]] (re-find #"(?i)pass|secret|token|key|header|url|user|cred|auth" (name k)))) d))

(defn- metadata [rt f]
  (try (conn/guarded rt f)
       (catch Throwable _ {:error "metadata lookup failed"})))

(defn- endpoint-of [snap fid]
  (some (fn [ep] (when (= fid (:flow ep))
                   {:method (name (:method ep)) :path (:path ep) :scopes (vec (sort (:scopes ep)))}))
        (:endpoints snap)))

(def read-tools
  [{:name "list_connections"
    :description "List configured connections (id, kind, dialect, breaker state, defaults). No credentials."
    :input [:map]
    :annotations {:readOnlyHint true}
    :handler (fn [_ _]
               (vec (for [[id rt] (sort-by (comp name key) (:conns (config/current)))]
                      {:id (name id) :kind (name (:kind rt))
                       :dialect (some-> (get-in rt [:cfg :dialect]) name)
                       :breaker (name (conn/breaker-state rt))
                       :defaults (safe-defaults (get-in rt [:cfg :defaults]))})))}
   {:name "describe_table"
    :description "Columns, primary key and indexes of a table (table may be schema.table). Metadata only, no rows."
    :input [:map [:conn :string] [:table :string]]
    :annotations {:readOnlyHint true}
    :handler (fn [_ {:keys [conn table]}]
               (let [[rt err] (sql-conn conn)]
                 (or err (metadata rt #(introspect/describe-table (:ds rt) table)))))}
   {:name "describe_proc"
    :description "Parameters of a stored procedure (SQL connections only)."
    :input [:map [:conn :string] [:proc :string]]
    :annotations {:readOnlyHint true}
    :handler (fn [_ {:keys [conn proc]}]
               (let [[rt err] (sql-conn conn)]
                 (or err (metadata rt #(introspect/describe-proc (:ds rt) proc)))))}
   {:name "list_flows"
    :description "Flows in the running config with version and endpoint."
    :input [:map]
    :annotations {:readOnlyHint true}
    :handler (fn [_ _]
               (let [snap (config/current)]
                 (vec (for [[id fl] (sort-by key (:flows snap))]
                        (cond-> {:id id :version (:version fl)}
                          (endpoint-of snap id) (assoc :endpoint (endpoint-of snap id)))))))}
   {:name "get_flow"
    :description "The .edn and .sql text of a flow."
    :input [:map [:id :string]]
    :annotations {:readOnlyHint true}
    :handler (fn [_ {:keys [id]}]
               (let [snap (config/current)
                     edn (when (valid-flow-id? id) (get (:tree snap) (str "flows/" id ".edn")))]
                 (if-not edn
                   {:error "no such flow"}
                   {:id id :version (get-in snap [:flows id :version])
                    :edn edn :sql (get (:tree snap) (str "flows/" id ".sql"))})))}])

(defn- trial-tool [f]
  (fn [_ {:keys [id] :as args}]
    (if-not (valid-flow-id? id)
      {:error "invalid flow id"}
      (try (f args)
           (catch Throwable t {:error (or (ex-message t) "failed")})))))

(def trial-tools
  [{:name "validate_flow"
    :description "Validate a flow (EDN + optional SQL) together with the running config, as a reload would, without installing it."
    :input [:map [:id :string] [:edn :string] [:sql {:optional true} :string]]
    :annotations {:readOnlyHint true}
    :handler (trial-tool (fn [{:keys [id edn sql]}] (trial/validate id edn sql)))}
   {:name "test_run"
    :description "Run a flow once (the given EDN/SQL, or the published flow) without step caches. Returns output, per-step rows and ms, and partial steps."
    :input [:map [:id :string] [:edn {:optional true} :string] [:sql {:optional true} :string] [:params {:optional true} :map]]
    :annotations {:readOnlyHint true}
    :handler (trial-tool (fn [{:keys [id edn sql params]}] (trial/test-run id edn sql (or params {}))))}
   {:name "explain"
    :description "Query plan of each SQL step via the dialect (params, or the flow's first example)."
    :input [:map [:id :string] [:edn {:optional true} :string] [:sql {:optional true} :string] [:params {:optional true} :map]]
    :annotations {:readOnlyHint true}
    :handler (trial-tool (fn [{:keys [id edn sql params]}] (trial/explain id edn sql params)))}])

(def default-tools (into read-tools trial-tools))

(defn handler
  "Ring handler for /mcp (nil for other paths). Task 7 extends `tools`."
  [{:keys [store]} & [tools]]
  (shttp/routes
   (merge
    (tools/deps (vec (or tools default-tools)))
    {:authenticate (fn [req]
                     (let [snap (config/current)
                           caller (try (auth/authenticate (:auth snap) store req) (catch Throwable _ nil))
                           admin (get-in snap [:auth :admin-scope] "spindle:admin")]
                       (when (and caller (some (:scopes caller) [author-scope admin]))
                         {:id (str (:id caller)) :ctx caller})))
     :server-info {:name "spindle" :version version}
     :unauthorized-message "Send a Spindle author or admin token as a Bearer token."})))
