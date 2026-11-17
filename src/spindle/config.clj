(ns spindle.config
  "Configuration: loading, validation, immutable snapshots, hot reload.

   Layout of a config directory:
     connections.edn   {conn-id {:type :sql|:http ...}}
     auth.edn          {:api-keys [...]}
     flows/**.edn      one flow per file (+ sibling .sql with named blocks)

   Reload = read the whole tree → resolve secrets → validate everything →
   compile → atomic swap. Any problem keeps the running snapshot. Requests take
   the snapshot once at start, so in-flight requests finish on the old version.

   Connections are carried over by fingerprint: unchanged pool settings keep the
   pool and breaker state; changed guard settings rebuild guards (Failsafe
   policies are immutable, so breaker state resets); changed pool settings open
   a new pool, and the old one closes once no request still uses the old snapshot."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [malli.core :as m]
            [malli.error :as me]
            [reitit.core :as r]
            [spindle.auth :as auth]
            [spindle.cache :as cache]
            [spindle.conn :as conn]
            [spindle.env :as env]
            [spindle.flow :as flow]
            [spindle.metrics :as metrics]
            [spindle.sqlfile :as sqlfile])
  (:import [java.io File]
           [java.security MessageDigest]
           [java.time Instant]
           [java.util.concurrent.atomic AtomicLong]))

;; ---------------------------------------------------------------------------
;; schemas
;; ---------------------------------------------------------------------------

(def Guard
  [:map {:closed true}
   [:concurrency {:optional true} pos-int?]
   [:queue-wait {:optional true} [:or string? int?]]
   [:rate {:optional true} string?]
   [:breaker {:optional true}
    [:map {:closed true}
     [:failure-ratio {:optional true} [:tuple pos-int? pos-int?]]
     [:open {:optional true} [:or string? int?]]
     [:half-open-calls {:optional true} pos-int?]]]])

(def Defaults
  [:map {:closed true}
   [:timeout {:optional true} [:or string? int?]]
   [:isolation {:optional true} [:enum :read-uncommitted :read-committed :repeatable-read :serializable]]
   [:fetch-size {:optional true} pos-int?]
   [:max-rows {:optional true} pos-int?]
   [:max-in {:optional true} pos-int?]])

(def Connection
  [:multi {:dispatch :type}
   [:sql [:map {:closed true}
          [:type [:= :sql]]
          [:dialect [:enum :postgres :mysql :clickhouse :sybase-ase :generic :sqlite]]
          [:jdbc-url string?]
          [:driver {:optional true} string?]
          [:username {:optional true} string?]
          [:password {:optional true} string?]
          [:properties {:optional true} [:map-of [:or keyword? string?] any?]]
          [:zone {:optional true} string?]
          [:pool {:optional true} [:map [:max {:optional true} pos-int?] [:min-idle {:optional true} nat-int?]
                                   [:connection-timeout {:optional true} [:or string? int?]]
                                   [:max-lifetime {:optional true} [:or string? int?]]]]
          [:defaults {:optional true} Defaults]
          [:guard {:optional true} Guard]]]
   [:http [:map {:closed true}
           [:type [:= :http]]
           [:base-url string?]
           [:headers {:optional true} [:map-of [:or keyword? string?] string?]]
           [:auth {:optional true} [:map [:header string?] [:value string?]]]
           [:list-style {:optional true} [:enum :csv :repeat :postgrest-in]]
           [:pool {:optional true} [:map [:connection-timeout {:optional true} [:or string? int?]]]]
           [:defaults {:optional true} [:map [:timeout {:optional true} [:or string? int?]]]]
           [:guard {:optional true} Guard]]]])

(def Connections [:map-of keyword? Connection])

(def Path [:vector {:min 1} any?])

(def Flow
  [:map
   [:doc {:optional true} string?]
   [:params {:optional true} any?]
   [:steps {:optional true}
    [:vector [:map
              [:id keyword?]
              [:kind [:enum :sql :http]]
              [:conn keyword?]
              [:in {:optional true} [:map-of keyword? Path]]
              [:sql {:optional true} string?]
              [:request {:optional true} map?]
              [:rows {:optional true} string?]
              [:fan-out {:optional true} Path]
              [:or-else {:optional true} keyword?]
              [:optional {:optional true} boolean?]
              [:skip-when-empty {:optional true} boolean?]
              [:empty-on-404 {:optional true} boolean?]
              [:max-rows {:optional true} pos-int?]
              [:tuning {:optional true} Defaults]]]]
   [:output {:optional true} map?]
   [:parallel {:optional true} [:map-of keyword? pos-int?]]
   [:cache {:optional true} map?]
   [:limits {:optional true} [:map [:timeout {:optional true} [:or string? int?]]
                              [:max-rows {:optional true} pos-int?]]]
   [:endpoint {:optional true}
    [:map
     [:path string?]
     [:method {:optional true} [:enum :get :post]]
     [:scopes {:optional true} [:set string?]]
     [:batch {:optional true} [:map [:key keyword?]]]]]])

;; ---------------------------------------------------------------------------
;; reading
;; ---------------------------------------------------------------------------

(defn- sha256 [^bytes b]
  (apply str (map #(format "%02x" %) (.digest (MessageDigest/getInstance "SHA-256") b))))

(defn read-tree
  "All config files under `dir` → {relative-path text}."
  [dir]
  (let [root (io/file dir)
        base (.toPath root)]
    (->> (file-seq root)
         (filter #(.isFile ^File %))
         (filter #(re-find #"\.(edn|sql)$" (.getName ^File %)))
         (map (fn [^File f] [(str (.relativize base (.toPath f))) (slurp f)]))
         (into (sorted-map)))))

(defn tree-version [tree]
  (subs (sha256 (.getBytes (pr-str (vec tree)) "UTF-8")) 0 12))

(defn- read-edn [path text problems]
  (try (edn/read-string text)
       (catch Exception e (swap! problems conj {:path [path] :message (str "EDN: " (.getMessage e))}) nil)))

;; ---------------------------------------------------------------------------
;; building a snapshot
;; ---------------------------------------------------------------------------

(defn- carry-or-open [prev-conns id cfg]
  (let [old (get prev-conns id)
        fp (conn/pool-fingerprint cfg)
        gfp (conn/guard-fingerprint cfg)]
    (cond
      (and old (= fp (:fingerprint old)) (= gfp (:guard-fingerprint old))) (assoc old :cfg cfg)
      (and old (= fp (:fingerprint old))) (conn/with-new-guards old cfg)
      :else (conn/open id cfg))))

(defn build
  "tree → {:snapshot s} or {:problems [...]}. `prev` is the running snapshot (or nil)."
  [tree prev]
  (let [problems (atom [])
        conns-raw (some->> (get tree "connections.edn") (#(read-edn "connections.edn" % problems)))
        auth-raw (some->> (get tree "auth.edn") (#(read-edn "auth.edn" % problems)))
        {auth-raw :value auth-missing :missing} (env/resolve-all auth-raw)
        _ (doseq [ref auth-missing] (swap! problems conj {:path ["auth.edn"] :message (str "unresolved ${" ref "}")}))
        _ (when-not conns-raw (swap! problems conj {:path ["connections.edn"] :message "missing or empty"}))
        _ (doseq [p (env/literal-secrets conns-raw)]
            (swap! problems conj {:path (into ["connections.edn"] p) :message "literal secret: use ${ENV} or ${file:/path}"}))
        _ (when-let [e (and conns-raw (m/explain Connections conns-raw))]
            (swap! problems conj {:path ["connections.edn"] :message (pr-str (me/humanize e))}))
        {conns :value missing :missing} (env/resolve-all (or conns-raw {}))
        _ (doseq [ref missing] (swap! problems conj {:path ["connections.edn"] :message (str "unresolved ${" ref "}")}))
        flow-files (filter #(and (str/starts-with? % "flows/") (str/ends-with? % ".edn")) (keys tree))
        flows (into {}
                    (for [f flow-files
                          :let [id (-> f (subs 6) (str/replace #"\.edn$" ""))
                                raw (read-edn f (get tree f) problems)
                                sql-text (get tree (str/replace f #"\.edn$" ".sql"))
                                blocks (when sql-text (sqlfile/parse-blocks sql-text))]
                          :when raw]
                      (if-let [e (m/explain Flow raw)]
                        (do (swap! problems conj {:path [f] :message (pr-str (me/humanize e))}) nil)
                        (let [{:keys [flow] ps :problems} (try (flow/compile-flow id raw blocks conns)
                                                               (catch Exception e {:problems [{:path [f] :message (.getMessage e)}]}))]
                          (if ps
                            (do (swap! problems into ps) nil)
                            [id (assoc flow :version (tree-version {f (get tree f) :sql sql-text}))])))))
        flows (into {} (remove nil?) flows)
        endpoints (->> flows
                       (keep (fn [[id fl]] (when-let [ep (:endpoint fl)]
                                             (merge {:id id :method :get :scopes #{} :flow id} ep))))
                       vec)
        dup-paths (->> endpoints (map (juxt :method :path)) frequencies (filter #(> (val %) 1)) keys)]
    (doseq [[mth pth] dup-paths] (swap! problems conj {:path ["endpoints"] :message (str "duplicate endpoint " (name mth) " " pth)}))
    (if (seq @problems)
      {:problems @problems}
      (let [prev-conns (:conns prev)
            rts (into {} (for [[id cfg] conns] [id (carry-or-open prev-conns id cfg)]))
            caches (into {} (for [{:keys [id flow]} endpoints
                                  :let [fl (flows flow) s (cache/settings (:cache fl))]
                                  :when s]
                              (let [old (get-in prev [:caches id])]
                                [id (if (and old (= s (:settings old))) old (cache/make id s metrics/registry))])))
            router (r/router (mapv (fn [{:keys [path] :as ep}] [path {:name (keyword (:id ep)) ::endpoint ep}]) endpoints)
                             {:conflicts nil})]
        {:snapshot {:version (tree-version tree)
                    :loaded-at (str (Instant/now))
                    :tree tree
                    :conns rts
                    :flows flows
                    :endpoints endpoints
                    :router router
                    :caches caches
                    :auth (auth/compile-auth auth-raw)
                    :inflight (AtomicLong. 0)}}))))

;; ---------------------------------------------------------------------------
;; the live state
;; ---------------------------------------------------------------------------

(defonce state (atom {:snapshot nil :history [] :last-reload nil :pinned false}))

(defn current [] (:snapshot @state))

(defn- retire!
  "Close runtime connections of `old` that `new` no longer uses, once no request holds `old`."
  [old new]
  (let [live (set (mapcat (juxt :ds :client) (vals (:conns new))))
        gone (remove #(live (or (:ds %) (:client %))) (vals (:conns old)))]
    (when (seq gone)
      (Thread/startVirtualThread
       (fn []
         (let [^AtomicLong n (:inflight old)
               until (+ (System/currentTimeMillis) (+ flow/max-deadline-ms 5000))]
           (loop []
             (when (and (pos? (.get n)) (< (System/currentTimeMillis) until))
               (Thread/sleep 200) (recur)))
           (doseq [rt gone]
             (log/info (str "event=pool-retired conn=" (name (:id rt))))
             (conn/close! rt))))))))

(defn install! [snap]
  (let [old (current)]
    (swap! state (fn [s] (-> s
                             (assoc :snapshot snap)
                             (update :history #(vec (take-last 5 (conj % {:version (:version snap) :tree (:tree snap)})))))))
    (metrics/counter! "spindle_config_reloads_total" {:result "ok"})
    (when old (retire! old snap))
    snap))

(defn reload!
  "Load `tree` (or read `dir`). → {:ok version} | {:problems [...]}."
  ([dir] (reload! dir (read-tree dir)))
  ([_dir tree]
   (let [old (current)]
     (if (and old (= (:version old) (tree-version tree)))
       {:ok (:version old) :unchanged true}
       (let [{:keys [snapshot problems]} (build tree old)]
         (if problems
           (do (metrics/counter! "spindle_config_reloads_total" {:result "rejected"})
               (swap! state assoc :last-reload {:at (str (Instant/now)) :result :rejected :problems problems})
               (log/warn (str "event=config-rejected problems=" (pr-str (take 10 problems))))
               {:problems problems})
           (do (install! snapshot)
               (swap! state assoc :last-reload {:at (str (Instant/now)) :result :ok :version (:version snapshot)})
               (log/info (str "event=config-loaded version=" (:version snapshot)
                              " flows=" (count (:flows snapshot)) " conns=" (count (:conns snapshot))))
               {:ok (:version snapshot)})))))))

(defn rollback!
  "Switch this node to an earlier good version (kept in memory) and pin it."
  [version]
  (if-let [{:keys [tree]} (some #(when (= version (:version %)) %) (:history @state))]
    (let [r (reload! nil tree)]
      (when (:ok r) (swap! state assoc :pinned true))
      r)
    {:problems [{:path ["rollback"] :message (str "version " version " not in this node's history")}]}))

(defn unpin! [] (swap! state assoc :pinned false))

(defn start-watcher!
  "Poll `dir` every `interval-ms`; reload when the content changes (unless pinned)."
  [dir interval-ms]
  (Thread/startVirtualThread
   (fn []
     (loop []
       (Thread/sleep (long interval-ms))
       (try
         (when-not (:pinned @state)
           (let [tree (read-tree dir)
                 v (tree-version tree)]
             ;; reload on change; a rejected version is not retried until the files change again
             (when (and (not= v (:version (current))) (not= v (:rejected-version @state)))
               (when (:problems (reload! dir tree))
                 (swap! state assoc :rejected-version v)))))
         (catch Throwable t (log/warn t "config watcher error")))
       (recur)))))
