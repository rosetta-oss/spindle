(ns spindle.tokens
  "SQLite-backed store of authoring tokens. Only the SHA-256 of a token is
   persisted; the clear token is returned once by issue! and never logged."
  (:require [clojure.tools.logging :as log]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [com.github.benmanes.caffeine.cache Caffeine Ticker]
           [java.nio.file Files Path Paths]
           [java.security SecureRandom]
           [java.sql SQLException]
           [java.time Instant]
           [java.util Base64]
           [java.util.concurrent ConcurrentHashMap TimeUnit]))

(defprotocol TokenStore
  (issue! [s opts] "opts {:id :scopes :expires-at :created-by} -> {:id :token :scopes :created_at :expires_at}")
  (revoke! [s id] "true if a live row was revoked")
  (lookup [s sha256-hex] "{:id :scopes} for a live, unexpired token, else nil")
  (list-tokens [s] "all tokens, without hashes"))

(def ^:private rng (SecureRandom.))

(defn- sha256-hex [^String s]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) d))))

(defn new-token []
  (let [b (byte-array 32)]
    (.nextBytes rng b)
    (str "spk_" (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b))))

(def ^:private ddl
  "create table if not exists tokens(
     id text primary key, sha256 text unique not null, scopes text not null,
     created_at text not null, created_by text, expires_at text,
     last_used_at text, revoked_at text)")

(def ^:private cache-ms 5000)
(def ^:private touch-ms 60000)

(defn- iso [ms] (str (Instant/ofEpochMilli ms)))

(defn- parse-ms [x] (when x (.toEpochMilli (Instant/parse (str x)))))

(defn- ->path ^Path [p] (.normalize (.toAbsolutePath (Paths/get (str p) (make-array String 0)))))

(defn- inside? [path dir]
  (let [p (->path path) d (->path dir)]
    (.startsWith p d)))

(defn- conflict?
  "SQLITE_CONSTRAINT (base result code 19, any extended code such as
   PRIMARYKEY or UNIQUE)."
  [^SQLException e]
  (let [code (if (instance? org.sqlite.SQLiteException e)
               (.code (.getResultCode ^org.sqlite.SQLiteException e))
               (.getErrorCode e))]
    (= 19 (bit-and (long code) 0xff))))

(defn- fetch-live [ds h now]
  (when-let [row (jdbc/execute-one! ds ["select id, scopes, expires_at from tokens where sha256 = ? and revoked_at is null" h]
                                    {:builder-fn rs/as-unqualified-maps})]
    {:id (:id row)
     :scopes (set (json/read-value (:scopes row)))
     :exp-ms (parse-ms (:expires_at row))
     :now now}))

(defn open-sqlite
  "Opens (creating if needed) the token DB at path. Refuses a path inside config-dir.
   opts {:now-ms f} injects the clock (default System/currentTimeMillis)."
  ([path config-dir] (open-sqlite path config-dir {}))
  ([path config-dir {:keys [now-ms] :or {now-ms #(System/currentTimeMillis)}}]
   (when (and config-dir (inside? path config-dir))
     (throw (ex-info (str "Token database " path " must not live inside the config directory " config-dir)
                     {:spindle/code :config})))
   (when-let [parent (.getParent (->path path))] (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0)))
   (let [ds (jdbc/get-datasource {:jdbcUrl (str "jdbc:sqlite:" (->path path))})
         _ (jdbc/execute! ds [ddl])
         ticker (reify Ticker (read [_] (* (long (now-ms)) 1000000)))
         cache (-> (Caffeine/newBuilder)
                   (.maximumSize 10000)
                   (.expireAfterWrite cache-ms TimeUnit/MILLISECONDS)
                   (.ticker ticker)
                   (.build))
         touched (ConcurrentHashMap.)
         load (fn [h] (or (fetch-live ds h (now-ms)) ::none))
         touch! (fn [id now]
                  (let [prev (.get touched id)]
                    (when (or (nil? prev) (>= (- now (long prev)) touch-ms))
                      (try
                        (jdbc/execute! ds ["update tokens set last_used_at = ? where id = ?" (iso now) id])
                        (.put touched id now)
                        (catch Exception e
                          (log/warn (str "event=token-touch-failed id=" id " error=" (.getName (class e)))))))))]
     (reify TokenStore
       (issue! [_ {:keys [id scopes expires-at created-by]}]
         (let [token (new-token)
               now (now-ms)
               created (iso now)
               expires (some-> expires-at parse-ms iso)]
           (try
             (jdbc/execute! ds ["insert into tokens(id, sha256, scopes, created_at, created_by, expires_at) values (?,?,?,?,?,?)"
                                id (sha256-hex token) (json/write-value-as-string (vec (sort scopes)))
                                created created-by expires])
             (catch SQLException e
               (if (conflict? e)
                 (throw (ex-info (str "Token id already exists: " id) {:conflict true :id id}))
                 (throw e))))
           {:id id :token token :scopes (set scopes) :created_at created :expires_at expires}))
       (revoke! [_ id]
         (let [n (:next.jdbc/update-count
                  (jdbc/execute-one! ds ["update tokens set revoked_at = ? where id = ? and revoked_at is null" (iso (now-ms)) id]))]
           (pos? n)))
       (lookup [_ h]
         (let [v (.get cache h (reify java.util.function.Function (apply [_ k] (load k))))
               now (now-ms)]
           (when (and (map? v) (or (nil? (:exp-ms v)) (< now (long (:exp-ms v)))))
             (touch! (:id v) now)
             {:id (:id v) :scopes (:scopes v)})))
       (list-tokens [_]
         (mapv #(update % :scopes (fn [s] (set (json/read-value s))))
               (jdbc/execute! ds ["select id, scopes, created_at, created_by, expires_at, last_used_at, revoked_at from tokens order by rowid"]
                              {:builder-fn rs/as-unqualified-maps})))))))
