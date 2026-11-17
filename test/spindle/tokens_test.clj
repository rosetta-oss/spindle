(ns spindle.tokens-test
  (:require [clojure.test :refer [deftest is testing]]
            [spindle.auth :as auth]
            [spindle.tokens :as tokens])
  (:import [java.nio.file Files]
           [java.time Instant]))

(defn- tmp-db []
  (let [dir (Files/createTempDirectory "spindle-tokens" (make-array java.nio.file.attribute.FileAttribute 0))]
    (str (.resolve dir "tokens.db"))))

(defn- bytes-as-latin1 [path]
  (String. (Files/readAllBytes (java.nio.file.Paths/get path (make-array String 0))) "ISO-8859-1"))

(deftest issue-and-lookup
  (let [f (tmp-db)
        s (tokens/open-sqlite f "cfg-elsewhere")
        r (tokens/issue! s {:id "a" :scopes #{"spindle:author"} :created-by "tester"})]
    (is (.startsWith ^String (:token r) "spk_"))
    (is (= 47 (count (:token r))))
    (is (= {:id "a" :scopes #{"spindle:author"}}
           (tokens/lookup s (auth/sha256-hex (:token r)))))
    (is (not (.contains ^String (bytes-as-latin1 f) ^String (:token r))))))

(deftest duplicate-id-conflicts
  (let [s (tokens/open-sqlite (tmp-db) "cfg-elsewhere")]
    (tokens/issue! s {:id "a" :scopes #{"x"}})
    (is (true? (try (tokens/issue! s {:id "a" :scopes #{"x"}}) false
                    (catch clojure.lang.ExceptionInfo e (:conflict (ex-data e))))))
    (is (true? (tokens/revoke! s "a")))
    (is (true? (try (tokens/issue! s {:id "a" :scopes #{"x"}}) false
                    (catch clojure.lang.ExceptionInfo e (:conflict (ex-data e))))))
    (is (false? (tokens/revoke! s "a")))
    (is (false? (tokens/revoke! s "nope")))))

(deftest expired-token-refused
  (let [s (tokens/open-sqlite (tmp-db) "cfg-elsewhere")
        r (tokens/issue! s {:id "a" :scopes #{"x"} :expires-at (str (.minusSeconds (Instant/now) 1))})]
    (is (nil? (tokens/lookup s (auth/sha256-hex (:token r)))))))

(deftest revocation-within-cache-window
  (let [now (atom 1000000)
        s (tokens/open-sqlite (tmp-db) "cfg-elsewhere" {:now-ms (fn [] @now)})
        h (auth/sha256-hex (:token (tokens/issue! s {:id "a" :scopes #{"x"}})))]
    (is (some? (tokens/lookup s h)))
    (tokens/revoke! s "a")
    (is (some? (tokens/lookup s h)) "still cached")
    (swap! now + 5001)
    (is (nil? (tokens/lookup s h)))))

(deftest unknown-hash-is-nil
  (let [s (tokens/open-sqlite (tmp-db) "cfg-elsewhere")]
    (is (nil? (tokens/lookup s (auth/sha256-hex "spk_nope"))))))

(deftest last-used-written-at-most-once-a-minute
  (let [now (atom (.toEpochMilli (Instant/now)))
        s (tokens/open-sqlite (tmp-db) "cfg-elsewhere" {:now-ms (fn [] @now)})
        h (auth/sha256-hex (:token (tokens/issue! s {:id "a" :scopes #{"x"}})))
        last-used #(:last_used_at (first (tokens/list-tokens s)))]
    (is (nil? (last-used)))
    (tokens/lookup s h)
    (let [t1 (last-used)]
      (is (some? t1))
      (reset! now (+ @now 10000))
      (tokens/lookup s h)
      (is (= t1 (last-used)) "within a minute: not rewritten")
      (reset! now (+ @now 61000))
      (tokens/lookup s h)
      (is (not= t1 (last-used))))))

(deftest db-inside-config-dir-refused
  (let [e (try (tokens/open-sqlite "cfg/data.db" "cfg") nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e))
    (is (= :config (:spindle/code (ex-data e))))))

(deftest list-hides-hashes
  (let [s (tokens/open-sqlite (tmp-db) "cfg-elsewhere")]
    (tokens/issue! s {:id "a" :scopes #{"x"}})
    (tokens/issue! s {:id "b" :scopes #{"y"}})
    (tokens/revoke! s "b")
    (let [l (tokens/list-tokens s)]
      (is (= 2 (count l)))
      (is (every? #(not (contains? % :sha256)) l))
      (is (every? #(contains? % :revoked_at) l))
      (is (every? #(contains? % :last_used_at) l))
      (is (some? (:revoked_at (second l)))))))

(deftest failed-touch-does-not-break-lookup
  (let [f (tmp-db)
        s (tokens/open-sqlite f "cfg-elsewhere")
        h (auth/sha256-hex (:token (tokens/issue! s {:id "a" :scopes #{"x"}})))
        conn (java.sql.DriverManager/getConnection (str "jdbc:sqlite:" f))]
    (try
      (with-open [st (.createStatement conn)]
        (.execute st "pragma busy_timeout = 0")
        (.execute st "begin immediate"))
      (is (= {:id "a" :scopes #{"x"}} (tokens/lookup s h)) "write lock held: touch fails, auth still works")
      (finally (with-open [st (.createStatement conn)] (.execute st "rollback")) (.close conn)))
    (is (nil? (:last_used_at (first (tokens/list-tokens s)))))
    (tokens/lookup s h)
    (is (some? (:last_used_at (first (tokens/list-tokens s)))) "failed touch was not remembered")))

(deftest non-spk-keys-skip-the-store
  (let [calls (atom 0)
        store (reify tokens/TokenStore
                (lookup [_ _] (swap! calls inc) nil)
                (issue! [_ _]) (revoke! [_ _]) (list-tokens [_]))
        req (fn [k] {:headers {"authorization" (str "Bearer " k)}})]
    (is (nil? (auth/authenticate {:keys []} store (req "not-a-token"))))
    (is (zero? @calls))
    (is (nil? (auth/authenticate {:keys []} store (req "spk_unknown"))))
    (is (= 1 @calls))))
