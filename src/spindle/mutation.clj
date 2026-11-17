(ns spindle.mutation
  "Mutation calls (design §8): idempotency keys, audit, cache eviction.

   Idempotency-Key (optional; required when the endpoint says :required true):
     - the key, a hash of the request and the response are stored in a table on
       the endpoint's :idempotency connection, inserted in the same transaction
       as the writes, so the record and the writes commit or roll back together;
     - a repeat with the same key and request returns the stored response
       (meta.idempotent_replay true) without running the flow;
     - the same key with a different request is 409 conflict.
     Table (create it yourself; Spindle never creates tables):
       create table spindle_idempotency (
         endpoint     varchar(200) not null,
         idem_key     varchar(128) not null,
         request_hash char(64)     not null,
         response     text         not null,
         created_at   timestamp    not null,
         primary key (endpoint, idem_key))

   Audit: every mutation call logs one JSON line (logger spindle.audit) and,
   with :audit {:conn .. :table ..}, inserts a row after the outcome is known:
       create table spindle_audit (
         at timestamp not null, request_id varchar(64), endpoint varchar(200),
         flow_version varchar(32), client varchar(128), sub varchar(256),
         idem_key varchar(128), conns varchar(400), affected text,
         outcome varchar(16), ms int, params_hash char(16))"
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jsonista.core :as json]
            [spindle.cache :as cache]
            [spindle.error :as err]
            [spindle.flow :as flow]
            [spindle.jsonout :as jsonout]
            [spindle.stepcache :as stepcache]
            [spindle.tx :as tx])
  (:import [java.security MessageDigest]
           [java.sql Connection PreparedStatement Timestamp]
           [java.time Instant]))

(defn- sha256 [^String s]
  (apply str (map #(format "%02x" %) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8")))))

(defn request-hash [endpoint-id params caller]
  (sha256 (pr-str [endpoint-id (into (sorted-map) params) (:client caller) (:sub caller)])))

(defn- idem-table [ep] (or (get-in ep [:idempotency :table]) "spindle_idempotency"))
(defn- audit-table [ep] (or (get-in ep [:audit :table]) "spindle_audit"))

(defn- read-mapper [] (json/object-mapper {:decode-key-fn true}))

(defn- lookup
  "→ {:request_hash h :response s} or nil (own pooled connection, auto-commit)."
  [snap ep k]
  (let [rt (get-in snap [:conns (get-in ep [:idempotency :conn])])]
    (with-open [^Connection c (.getConnection ^javax.sql.DataSource (:ds rt))
                ^PreparedStatement ps (.prepareStatement c (str "select request_hash, response from " (idem-table ep)
                                                                " where endpoint = ? and idem_key = ?"))]
      (.setString ps 1 (:id ep)) (.setString ps 2 k)
      (with-open [rs (.executeQuery ps)]
        (when (.next rs) {:request_hash (str/trim (.getString rs 1)) :response (.getString rs 2)})))))

(defn- replay-or-conflict [stored h]
  (if (= h (:request_hash stored))
    {:body (assoc-in (json/read-value (:response stored) (read-mapper)) [:meta :idempotent_replay] true)}
    (err/fail! :conflict "Idempotency-Key was used with a different request")))

(defn- record! [tx ep k h body]
  (let [^Connection c (tx/connection! tx (get-in ep [:idempotency :conn]))]
    (with-open [^PreparedStatement ps (.prepareStatement c (str "insert into " (idem-table ep)
                                                                " (endpoint, idem_key, request_hash, response, created_at)"
                                                                " values (?, ?, ?, ?, ?)"))]
      (.setString ps 1 (:id ep)) (.setString ps 2 k) (.setString ps 3 h) (.setString ps 4 body)
      (.setTimestamp ps 5 (Timestamp/from (Instant/now)))
      (.executeUpdate ps))))

(defn- unique-violation? [^Throwable t]
  (loop [t t]
    (cond (nil? t) false
          (and (instance? java.sql.SQLException t)
               (or (= "23505" (.getSQLState ^java.sql.SQLException t))          ; PostgreSQL
                   (#{2601 2627} (.getErrorCode ^java.sql.SQLException t))     ; ASE / SQL Server
                   (re-find #"(?i)duplicate|unique" (str (.getMessage t)))))
          true
          :else (recur (.getCause t)))))

(defn- audit! [snap ep fl caller k outcome affected ms params request-id]
  (let [ev {:event "mutation" :at (str (Instant/now)) :request_id request-id :endpoint (:id ep)
            :flow_version (:version fl) :client (:client caller) :sub (:sub caller)
            :scopes (vec (sort (:scopes caller))) :idempotency_key k
            :conns (mapv name (:commit-order fl)) :affected (into (sorted-map) (map (fn [[s n]] [(name s) n])) affected)
            :outcome (name outcome) :ms ms
            :params_hash (subs (sha256 (pr-str (into (sorted-map) params))) 0 16)}]
    (log/log "spindle.audit" :info nil (jsonout/write ev))
    (when-let [cid (get-in ep [:audit :conn])]
      (try
        (let [rt (get-in snap [:conns cid])]
          (with-open [^Connection c (.getConnection ^javax.sql.DataSource (:ds rt))
                      ^PreparedStatement ps (.prepareStatement
                                             c (str "insert into " (audit-table ep)
                                                    " (at, request_id, endpoint, flow_version, client, sub, idem_key, conns,"
                                                    " affected, outcome, ms, params_hash) values (?,?,?,?,?,?,?,?,?,?,?,?)"))]
            (.setTimestamp ps 1 (Timestamp/from (Instant/now)))
            (doseq [[i v] (map-indexed vector [request-id (:id ep) (:version fl) (:client caller) (:sub caller) k
                                               (str/join "," (:conns ev)) (jsonout/write (:affected ev)) (:outcome ev)])]
              (.setObject ps (int (+ 2 i)) v))
            (.setInt ps 11 (int ms))
            (.setString ps 12 (:params_hash ev))
            (.executeUpdate ps)))
        (catch Throwable t (log/warn t (str "event=audit-insert-failed endpoint=" (:id ep))))))))

(defn- evict! [snap ep]
  (when-let [tags (seq (map name (:evicts ep)))]
    (stepcache/evict-tags! tags)
    ;; endpoint caches carry tags too
    (doseq [{:keys [id flow]} (:endpoints snap)
            :let [ctags (set (map name (get-in snap [:flows flow :cache :tags])))]
            :when (some ctags tags)]
      (when-let [c (get-in snap [:caches id])] (cache/clear! c)))))

(defn run
  "→ {:body {...}} for a mutation endpoint call. `meta-fn` builds the response meta."
  [snap ep fl params caller request-id idem-key meta-fn]
  (let [t0 (System/nanoTime)
        store? (get-in ep [:idempotency :conn])
        _ (when (and idem-key (> (count idem-key) 128)) (err/fail! :input_rejected "Idempotency-Key longer than 128 characters"))
        _ (when (and idem-key (not store?)) (err/fail! :input_rejected "this endpoint does not take an Idempotency-Key"))
        _ (when (and (get-in ep [:idempotency :required]) (not idem-key)) (err/fail! :input_rejected "Idempotency-Key is required"))
        h (when idem-key (request-hash (:id ep) params caller))
        stored (when idem-key (lookup snap ep idem-key))
        ms #(quot (- (System/nanoTime) t0) 1000000)]
    (if stored
      (replay-or-conflict stored h)
      (let [body-of (fn [r] {:rows (:rows r) :next nil
                             :meta (assoc (meta-fn r) :outcome "committed"
                                          :affected (into (sorted-map) (map (fn [[s n]] [(name s) n])) (:affected r)))})
            attempt (fn []
                      (flow/execute snap fl params
                                    {:caller caller :request-id request-id :commit? true
                                     :before-commit (fn [tx r] (when idem-key (record! tx ep idem-key h (jsonout/write (body-of r)))))}))]
        (try
          (let [r (attempt)]
            (audit! snap ep fl caller idem-key :committed (:affected r) (ms) params request-id)
            (evict! snap ep)
            {:body (body-of r)})
          (catch Throwable t
            (if (and idem-key (unique-violation? t))
              ;; a concurrent call with the same key committed first
              (if-let [s (lookup snap ep idem-key)] (replay-or-conflict s h) (throw t))
              (let [outcome (or (:outcome (err/code-of t)) :rolled_back)]
                (audit! snap ep fl caller idem-key outcome {} (ms) params request-id)
                (when (= :partial outcome) (evict! snap ep))
                (throw t)))))))))
