(ns spindle.mcp
  "Authoring MCP: schema and flow introspection for agents. Streamable HTTP only
   (the legacy HTTP+SSE routes need :sse-writer and are not wired here)."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.walk]
            [shuttle.http :as shttp]
            [shuttle.tools :as tools]
            [spindle.auth :as auth]
            [spindle.config :as config]
            [spindle.conn :as conn]
            [spindle.mcp.introspect :as introspect]
            [spindle.mcp.trial :as trial])
  (:import [java.nio.file CopyOption Files LinkOption Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(def author-scope "spindle:author")
(def version "0.1.0")

(defn valid-flow-id? [s]
  (boolean (and (string? s) (<= (count s) 128)
                (re-matches #"[a-z0-9][a-z0-9_-]*(/[a-z0-9][a-z0-9_-]*)*" s))))

(defn json-safe
  "Tool results go through shuttle's JSON encoder, which knows no java.time:
   dates and times become their ISO-8601 strings (as Spindle's endpoints send them)."
  [x]
  (clojure.walk/postwalk
   #(if (or (instance? java.time.temporal.TemporalAccessor %) (instance? java.util.UUID %)) (str %) %)
   x))

(defn- db-message
  "The database's own message from the first SQLException in the cause chain, or nil.
   Authors need it to fix their SQL; API callers never see it (endpoints stay generic)."
  [^Throwable t]
  (some #(when (instance? java.sql.SQLException %) (some-> (.getMessage ^Throwable %) str/trim not-empty))
        (take-while some? (iterate #(.getCause ^Throwable %) t))))

(defn- sql-conn
  "→ [rt nil] or [nil {:error ..}]"
  [id]
  (let [rt (get-in (config/current) [:conns (keyword id)])]
    (cond (nil? rt) [nil {:error (str "no such connection: " id)}]
          (not (:ds rt)) [nil {:error "not a SQL connection"}]
          :else [rt nil])))

(def ^:private default-keys
  "Allow-list: the keys of config/Defaults (the only connection defaults Spindle uses)."
  #{:timeout :isolation :fetch-size :max-rows :max-in})

(defn- safe-defaults [d]
  (select-keys d default-keys))

(defn- metadata
  "The author gets a generic error (driver messages can carry hosts and users);
   the class goes to the log so an operator can see why lookups fail."
  [rt f]
  (try (conn/guarded rt f)
       (catch Throwable t
         (log/warn (str "event=mcp-metadata-failed error=" (.getName (class t))))
         {:error "metadata lookup failed"})))

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
      (try (json-safe (f args))
           (catch Throwable t
             (when-not (instance? clojure.lang.ExceptionInfo t)
               (log/warn (str "event=mcp-tool-failed flow=" id " error=" (.getName (class t)))))
             (let [msg (or (ex-message t) "failed")
                   db (db-message t)]
               (cond-> {:error msg} (and db (not= db msg)) (assoc :database_error db))))))))

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

(defn- write-atomic!
  "bytes nil -> delete. Otherwise temp file in the same directory + ATOMIC_MOVE."
  [^Path p ^bytes b]
  (if (nil? b)
    (Files/deleteIfExists p)
    (let [dir (.getParent p)
          _ (Files/createDirectories dir (make-array FileAttribute 0))
          tmp (Files/createTempFile dir ".publish-" ".tmp" (make-array FileAttribute 0))]
      (try (Files/write tmp b (make-array java.nio.file.OpenOption 0))
           (Files/move tmp p (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
           (finally (Files/deleteIfExists tmp))))))

(defn- read-bytes [^Path p]
  (when (Files/exists p (make-array LinkOption 0)) (Files/readAllBytes p)))

(defn publish!
  "Validate, write flows/<id>.edn (+ .sql; removed when sql is nil) atomically,
   reload. On reload failure the previous files are restored exactly.
   Validation (which explains every SQL step against the database) runs outside
   the write lock so a slow database does not stall reloads and other publishes;
   the reload inside the lock validates again against whatever is current then.
   The pin is checked on both sides: early to refuse cheaply, inside to be sure."
  [config-dir caller id edn sql]
  (if (:pinned @config/state)
    {:error "node is pinned"}
    (let [{ps :problems} (trial/validate id edn sql)]
      (if ps
        {:problems ps}
        (config/with-write-lock
          (if (:pinned @config/state)
            {:error "node is pinned"}
            (let [flows (.normalize (.toAbsolutePath (.resolve (Path/of (str config-dir) (make-array String 0)) "flows")))
                  files (vec (for [[ext text] [[".edn" edn] [".sql" sql]]
                                   :let [p (.normalize (.resolve flows (str id ext)))]]
                               (do (when-not (.startsWith p flows) (throw (ex-info "invalid flow id" {})))
                                   [p (some-> ^String text (.getBytes "UTF-8"))])))
                  before (mapv (fn [[p _]] [p (read-bytes p)]) files)
                  restore! (fn []
                             (let [errs (reduce (fn [errs [p b]]
                                                  (try (write-atomic! p b) errs
                                                       (catch Throwable t (conj errs t))))
                                                [] before)]
                               (when (seq errs)
                                 (log/warn (str "event=publish-restore-failed flow=" id " failures=" (count errs))))
                               errs))]
              (try (doseq [[p b] files] (write-atomic! p b))
                   (catch Throwable t
                     (doseq [e (restore!)] (.addSuppressed t e))
                     (throw t)))
              (let [r (try (config/reload! config-dir)
                           (catch Throwable t {:problems [{:message (or (ex-message t) "reload failed")}]}))]
                (if (:ok r)
                  (do (log/info (str "event=flow-published flow=" id " by=" (:id caller) " version=" (:ok r)))
                      {:version (:ok r) :reload r})
                  (do (restore!)
                      {:error "reload failed" :problems (:problems r)}))))))))))

(defn- publish-tool [config-dir]
  {:name "publish"
   :description "Validate and publish a flow: writes flows/<id>.edn (+ .sql) atomically and reloads. Refused while the node is pinned."
   :input [:map [:id :string] [:edn :string] [:sql {:optional true} :string]]
   :handler (fn [ctx {:keys [id edn sql]}]
              (if-not (valid-flow-id? id)
                {:error "invalid flow id"}
                (try (publish! config-dir ctx id edn sql)
                     (catch Throwable t
                       (when-not (instance? clojure.lang.ExceptionInfo t)
                         (log/warn (str "event=publish-failed flow=" id " error=" (.getName (class t)))))
                       {:error (or (ex-message t) "failed")}))))})

(def default-tools (into read-tools trial-tools))

(def default-max-body-bytes
  "One JSON-RPC message carries at most a flow's EDN and SQL; 1 MiB is far above
   that and keeps a single caller from making the node buffer arbitrary bodies."
  (* 1024 1024))

(def default-rate
  "[requests per-ms] per caller id. Generous for an agent's edit-validate-run
   loop; a runaway client gets 429 instead of tying up connections."
  [300 60000])

(defn handler
  "Ring handler for /mcp (nil for other paths). `publish` is offered when :config-dir is set.
   opts :mcp-max-body-bytes and :mcp-rate override the limits above.
   The legacy SSE routes answer 404: without an :sse-writer shuttle would
   register a session and then fail."
  [{:keys [store config-dir mcp-max-body-bytes mcp-rate]} & [tools]]
  (let [[n per-ms] (or mcp-rate default-rate)
        routes (shttp/routes
                (merge
                 (tools/deps (vec (or tools (cond-> default-tools config-dir (conj (publish-tool config-dir))))))
                 {:authenticate (fn [req]
                                  (let [snap (config/current)
                                        caller (try (auth/authenticate (:auth snap) store req) (catch Throwable _ nil))
                                        admin (get-in snap [:auth :admin-scope] "spindle:admin")]
                                    (when (and caller (some (:scopes caller) [author-scope admin]))
                                      {:id (str (:id caller)) :ctx caller})))
                  :rate-limit? (shttp/rate-limiter n per-ms)
                  :max-body-bytes (or mcp-max-body-bytes default-max-body-bytes)
                  :server-info {:name "spindle" :version version}
                  :unauthorized-message "Send a Spindle author or admin token as a Bearer token."}))]
    (fn [req]
      (when (= "/mcp" (:uri req))
        (routes req)))))
