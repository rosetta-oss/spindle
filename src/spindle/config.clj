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
            [spindle.explain :as explain]
            [spindle.flow :as flow]
            [spindle.jwt]
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
   [:max-in {:optional true} pos-int?]
   [:slow {:optional true} [:or string? int?]]])

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
           [:forward-caller {:optional true} [:map-of string? [:vector {:min 2} keyword?]]]
           [:pool {:optional true} [:map [:connection-timeout {:optional true} [:or string? int?]]]]
           [:defaults {:optional true} [:map [:timeout {:optional true} [:or string? int?]]
                                       [:slow {:optional true} [:or string? int?]]]]
           [:guard {:optional true} Guard]]]])

(def Connections [:map-of keyword? Connection])

;; Flow schemas are closed: a misspelled key (:optinal, :chache, :selct) is a
;; load error, never silently ignored. Flows are often written by agents.

(def Path [:vector {:min 1} any?])

(def StepCache
  [:map {:closed true}
   [:ttl [:or string? int?]]
   [:stale {:optional true} [:or string? int?]]
   [:stale-if-error {:optional true} [:or string? int?]]
   [:per {:optional true} [:enum :caller]]
   [:tags {:optional true} [:vector [:or keyword? string?]]]])

(def EndpointCache
  [:map {:closed true}
   [:ttl [:or string? int?]]
   [:stale {:optional true} [:or string? int?]]
   [:stale-if-error {:optional true} [:or string? int?]]
   [:empty-ttl {:optional true} [:or string? int?]]
   [:max-entries {:optional true} pos-int?]
   [:tags {:optional true} [:vector [:or keyword? string?]]]])

(def Request
  [:map {:closed true}
   [:method {:optional true} [:enum :get :post :put :patch :delete]]
   [:path string?]
   [:path-params {:optional true} [:map-of keyword? any?]]
   [:query {:optional true} [:map-of [:or string? keyword?] any?]]
   [:body {:optional true} [:map-of keyword? any?]]
   [:headers {:optional true} [:map-of [:or string? keyword?] any?]]])

(def Step
  [:map {:closed true}
   [:id keyword?]
   [:kind [:enum :sql :api :http :proc]]
   [:conn keyword?]
   [:doc {:optional true} string?]
   [:in {:optional true} [:map-of keyword? Path]]
   [:sql {:optional true} string?]
   [:request {:optional true} Request]
   [:rows {:optional true} string?]
   [:fan-out {:optional true} Path]
   [:or-else {:optional true} keyword?]
   [:after {:optional true} [:vector keyword?]]
   [:when {:optional true} [:or map? vector?]]
   [:default {:optional true} any?]
   [:cache {:optional true} StepCache]
   [:optional {:optional true} boolean?]
   [:skip-when-empty {:optional true} boolean?]
   [:empty-on-404 {:optional true} boolean?]
   [:max-rows {:optional true} pos-int?]
   [:take {:optional true} [:or pos-int? [:vector any?]]]
   [:tuning {:optional true} Defaults]
   ;; :proc
   [:call {:optional true} string?]
   [:args {:optional true} [:vector [:or keyword? [:map {:closed true} [:out keyword?] [:type keyword?]
                                                   [:inout {:optional true} boolean?]]]]]
   [:results {:optional true} [:vector keyword?]]
   [:status {:optional true} keyword?]
   [:fail-on-status {:optional true} keyword?]
   [:read-only {:optional true} boolean?]
   [:procedure {:optional true} boolean?]     ; PostgreSQL CREATE PROCEDURE → native CALL
   ;; writes
   [:expect {:optional true} [:map {:closed true} [:rows nat-int?]]]])

(def Output
  [:schema {:registry
            {::output [:map {:closed true}
                       [:from {:optional true} keyword?]
                       [:on {:optional true} [:map-of keyword? keyword?]]
                       [:one? {:optional true} boolean?]
                       [:merge {:optional true} [:map {:closed true}
                                                 [:from keyword?]
                                                 [:on [:map-of keyword? keyword?]]
                                                 [:select {:optional true} [:vector keyword?]]
                                                 [:pick {:optional true} [:enum :first]]]]
                       [:nest {:optional true} [:map-of keyword? [:ref ::output]]]
                       [:compute {:optional true} [:map-of keyword? any?]]
                       [:derive {:optional true} [:or [:map-of keyword? any?] [:vector [:tuple keyword? any?]]]]
                       [:assert {:optional true} [:vector vector?]]
                       [:on-fail {:optional true} [:enum :flag :error]]
                       [:types {:optional true} [:map-of keyword? keyword?]]
                       [:select {:optional true} [:vector keyword?]]
                       [:rename {:optional true} [:map-of keyword? keyword?]]]}}
   ::output])

(def Endpoint
  [:map {:closed true}
   [:path string?]
   [:method {:optional true} [:enum :get :post :put :patch :delete]]
   [:doc {:optional true} string?]
   ;; mutations (design §8)
   [:mutation {:optional true} boolean?]
   [:commit-order {:optional true} [:vector keyword?]]
   [:idempotency {:optional true} [:map {:closed true} [:conn keyword?] [:table {:optional true} string?]
                                   [:required {:optional true} boolean?]]]
   [:audit {:optional true} [:map {:closed true} [:conn keyword?] [:table {:optional true} string?]]]
   [:evicts {:optional true} [:vector [:or keyword? string?]]]
   [:scopes {:optional true} [:set string?]]
   [:caller {:optional true} [:enum :required :optional]]
   [:batch {:optional true} [:map {:closed true} [:key keyword?] [:column {:optional true} keyword?]]]
   [:page {:optional true} [:map {:closed true}
                            [:key [:vector {:min 1} keyword?]]
                            [:size {:optional true} pos-int?]
                            [:max-size {:optional true} pos-int?]]]])

(def View
  [:map {:closed true}
   [:doc {:optional true} string?]
   [:output {:optional true} Output]
   [:endpoint {:optional true} Endpoint]
   [:cache {:optional true} EndpointCache]])

(def flow-format 1)

(def Flow
  [:map {:closed true}
   [:spindle/format {:optional true} [:= flow-format]]
   [:spindle/view {:optional true} keyword?]
   [:doc {:optional true} string?]
   [:params {:optional true} any?]
   [:steps {:optional true} [:vector Step]]
   ;; shorthand: one SQL step
   [:sql {:optional true} string?]
   [:conn {:optional true} keyword?]
   [:in {:optional true} [:map-of keyword? Path]]
   [:output {:optional true} Output]
   [:views {:optional true} [:map-of keyword? View]]
   [:parallel {:optional true} [:map-of keyword? pos-int?]]
   [:cache {:optional true} EndpointCache]
   [:limits {:optional true} [:map {:closed true}
                              [:timeout {:optional true} [:or string? int?]]
                              [:max-rows {:optional true} pos-int?]]]
   [:examples {:optional true} [:vector map?]]
   [:endpoint {:optional true} Endpoint]])

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
         ;; hidden files (editor swap files, macOS ._ AppleDouble files) are not config
         (remove #(some (fn [^java.nio.file.Path p] (.startsWith (str p) "."))
                        (iterator-seq (.iterator (.relativize base (.toPath ^File %))))))
         ;; "/" on every OS: flow ids and the flows/ prefix are matched on it
         (map (fn [^File f] [(str/replace (str (.relativize base (.toPath f))) java.io.File/separator "/") (slurp f)]))
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

(defn- expand-views
  "A flow file → [[flow-id raw] ...]. With :views, one flow per view: the shared
   steps, params and limits, plus the view's own :output, :endpoint and :cache;
   each view runs only the steps its output needs (spindle.flow/prune)."
  [file id raw problems]
  (if-let [views (:views raw)]
    (if (or (:output raw) (:endpoint raw))
      (do (swap! problems conj {:path [file] :message "a flow with :views puts :output and :endpoint inside each view"}) [])
      (for [[vk v] views]
        [(str id "/" (name vk))
         (-> raw (dissoc :views) (merge (select-keys v [:output :endpoint :cache :doc])) (assoc :spindle/view vk))]))
    [[id raw]]))

(defn build
  "tree → {:snapshot s} or {:problems [...]}. `prev` is the running snapshot (or nil)."
  [tree prev]
  (let [problems (atom [])
        conns-raw (some->> (get tree "connections.edn") (#(read-edn "connections.edn" % problems)))
        auth-raw (some->> (get tree "auth.edn") (#(read-edn "auth.edn" % problems)))
        {auth-raw :value auth-missing :missing} (env/resolve-all auth-raw)
        _ (doseq [ref auth-missing] (swap! problems conj {:path ["auth.edn"] :message (str "unresolved ${" ref "}")}))
        _ (swap! problems into (spindle.jwt/config-problems (:jwt auth-raw)))
        _ (when-not conns-raw (swap! problems conj {:path ["connections.edn"] :message "missing or empty"}))
        _ (doseq [p (env/literal-secrets conns-raw)]
            (swap! problems conj {:path (into ["connections.edn"] p) :message "literal secret: use ${ENV} or ${file:/path}"}))
        _ (when-let [e (and conns-raw (m/explain Connections conns-raw))]
            (swap! problems conj {:path ["connections.edn"] :message (pr-str (me/humanize e))}))
        {conns :value missing :missing} (env/resolve-all (or conns-raw {}))
        _ (doseq [ref missing] (swap! problems conj {:path ["connections.edn"] :message (str "unresolved ${" ref "}")}))
        flow-files (filter #(and (str/starts-with? % "flows/") (str/ends-with? % ".edn")) (keys tree))
        flow-defs (for [f flow-files
                        :let [id (-> f (subs 6) (str/replace #"\.edn$" ""))
                              raw (read-edn f (get tree f) problems)
                              sql-text (get tree (str/replace f #"\.edn$" ".sql"))]
                        :when raw
                        :let [e (m/explain Flow raw)]
                        :when (or (not e)
                                  (do (swap! problems conj {:path [f] :message (pr-str (me/humanize e))}) false))
                        [vid vraw] (expand-views f id raw problems)]
                    {:file f :id vid :raw vraw :sql-text sql-text})
        _ (doseq [[vid n] (frequencies (map :id flow-defs)) :when (> n 1)]
            (swap! problems conj {:path ["flows"] :message (str "flow id " vid " is defined twice (a file and a view?)")}))
        flows (into {}
                    (for [{:keys [file id raw sql-text]} flow-defs
                          :let [blocks (when sql-text (sqlfile/parse-blocks sql-text))
                                {:keys [flow] ps :problems} (try (flow/compile-flow id raw blocks conns)
                                                                 (catch Exception e {:problems [{:path [file] :message (.getMessage e)}]}))]]
                      (if ps
                        (do (swap! problems into ps) nil)
                        [id (assoc flow :version (tree-version {file (get tree file) :sql sql-text}))])))
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
            ;; mandatory explain (archived); SQL errors reject the whole reload
            ex (explain/explain-all! flows rts)
            _ (when (seq (:problems ex))
                (let [prev-live (set (mapcat (juxt :ds :client) (vals prev-conns)))]
                  (doseq [rt (vals rts) :when (not (prev-live (or (:ds rt) (:client rt))))]
                    (conn/close! rt))))
            router (r/router (mapv (fn [{:keys [path] :as ep}] [path {:name (keyword (:id ep)) ::endpoint ep}]) endpoints)
                             {:conflicts nil})]
        (if (seq (:problems ex))
          {:problems (:problems ex)}
        {:snapshot {:version (tree-version tree)
                    :explain (dissoc ex :problems)
                    :loaded-at (str (Instant/now))
                    :tree tree
                    :conns rts
                    :flows flows
                    :endpoints endpoints
                    :router router
                    :caches caches
                    :auth (auth/compile-auth auth-raw)
                    :inflight (AtomicLong. 0)}})))))

;; ---------------------------------------------------------------------------
;; the live state
;; ---------------------------------------------------------------------------

(defonce state (atom {:snapshot nil :history [] :last-reload nil :pinned false}))

(defn current [] (:snapshot @state))

(defonce ^java.util.concurrent.locks.ReentrantLock write-lock
  (java.util.concurrent.locks.ReentrantLock.))

(defmacro with-write-lock
  "Runs body holding the process-wide lock shared by publish, /admin/reload,
   rollback and the file watcher."
  [& body]
  `(let [l# write-lock]
     (.lock l#)
     (try ~@body (finally (.unlock l#)))))

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
         (with-write-lock
           (when-not (:pinned @state)
             (let [tree (read-tree dir)
                   v (tree-version tree)]
               ;; reload on change; a rejected version is not retried until the files change again
               (when (and (not= v (:version (current))) (not= v (:rejected-version @state)))
                 (when (:problems (reload! dir tree))
                   (swap! state assoc :rejected-version v))))))
         (catch Throwable t (log/warn t "config watcher error")))
       (recur)))))
