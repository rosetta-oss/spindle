(ns spindle.activity-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [spindle.activity :as activity]
            [spindle.auth :as auth]
            [spindle.config :as config]
            [spindle.explain]
            [spindle.server :as server]
            [spindle.tokens :as tokens]))

(def t0 (* 1000 60 60 24 1000)) ; a whole minute

(deftest windows-counts-and-latency
  (activity/clear!)
  ;; 6 minutes ago: outside the 5-minute window
  (activity/record! {:endpoint "a" :ms 40 :code nil} (- t0 (* 6 60000)))
  (doseq [ms [10 20 30 40 50 60 70 80 90 4000]]
    (activity/record! {:endpoint "a" :ms ms :code nil} t0))
  (activity/record! {:endpoint "a" :ms 2000 :code :timeout :caller "k1" :request-id "r1"} t0)
  (activity/record! {:endpoint "b" :ms 5 :code :forbidden :caller "k2" :request-id "r2"} (+ t0 1000))
  (let [s (activity/snapshot (+ t0 2000))
        by (fn [w ep] (some #(when (= ep (:endpoint %)) %) (get s w)))]
    (testing "since start includes the old request, last 5 minutes does not"
      (is (= 12 (:count (by :since_start "a"))))
      (is (= 11 (:count (by :last_5m "a")))))
    (testing "errors, rate and codes"
      (is (= 1 (:errors (by :last_5m "a"))))
      (is (= {:timeout 1} (:codes (by :last_5m "a"))))
      (is (= 1.0 (:error_rate (by :last_5m "b")))))
    (testing "latency"
      (is (= 4000.0 (:max_ms (by :last_5m "a"))))
      (is (< 500 (:avg_ms (by :last_5m "a")) 700))
      (is (<= 2000 (:p95_ms (by :last_5m "a")) 5000) "p95 lands in the 2.5s–5s bucket region"))
    (testing "recent errors, newest first, ok requests not listed"
      (is (= ["r2" "r1"] (map :request_id (:recent_errors s))))
      (is (= {:endpoint "b" :code "forbidden" :ms 5.0 :caller "k2"}
             (select-keys (first (:recent_errors s)) [:endpoint :code :ms :caller]))))))

(deftest recent-errors-capped-at-100
  (activity/clear!)
  (dotimes [i 130] (activity/record! {:endpoint "x" :ms 1 :code :internal :request-id (str i)} t0))
  (let [r (:recent_errors (activity/snapshot t0))]
    (is (= 100 (count r)))
    (is (= "129" (:request_id (first r))))))

;; --- end to end through the handler --------------------------------------

(defn- write! [dir rel text] (let [f (io/file dir rel)] (io/make-parents f) (spit f text)))

(def ^:dynamic *dir* nil)

(use-fixtures :each
  (fn [t]
    (let [dir (doto (io/file (System/getProperty "java.io.tmpdir") (str "spindle-activity-" (System/nanoTime))) (.mkdirs))
          db (str (io/file dir "shop.db"))
          cfg (io/file dir "config")]
      (jdbc/execute! {:jdbcUrl (str "jdbc:sqlite:" db)} ["create table orders (id integer primary key, customer text)"])
      (jdbc/execute! {:jdbcUrl (str "jdbc:sqlite:" db)} ["insert into orders values (1,'Ann')"])
      (write! cfg "connections.edn" (pr-str {:shop {:type :sql :dialect :sqlite :jdbc-url (str "jdbc:sqlite:" db)
                                                    :defaults {:timeout "2s" :max-rows 1000}}}))
      (write! cfg "auth.edn" (pr-str {:api-keys [{:id "reader" :sha256 (auth/sha256-hex "rk") :scopes #{"shop:read"}}
                                                 {:id "nobody" :sha256 (auth/sha256-hex "nk") :scopes #{}}
                                                 {:id "ops" :sha256 (auth/sha256-hex "ak") :scopes #{"spindle:admin"}}]}))
      (write! cfg "flows/shop/orders.edn" (pr-str {:steps [{:id :o :kind :sql :conn :shop :sql "select id, customer from orders"}]
                                                   :endpoint {:path "/shop/orders" :scopes #{"shop:read"}}}))
      (reset! config/state {:snapshot nil :history [] :last-reload nil :pinned false})
      (activity/clear!)
      (binding [*dir* (str cfg) spindle.explain/*archive-dir* (str (io/file dir "explain"))] (t)))))

(defn- call [h uri key & [remote]]
  (let [r (h {:request-method :get :uri uri :remote-addr (or remote "127.0.0.1")
              :headers (if key {"authorization" (str "Bearer " key)} {})})]
    (assoc r :json (json/read-value (:body r) (json/object-mapper {:decode-key-fn true})))))

(deftest admin-activity-endpoint
  (is (:ok (config/reload! *dir*)))
  (let [store (tokens/open-sqlite (str (io/file *dir* ".." "tokens.db")) *dir*)
        h (server/handler {:node-id "t" :config-dir *dir* :store store})]
    (is (= 200 (:status (call h "/shop/orders" "rk"))))
    (is (= 200 (:status (call h "/shop/orders" "rk"))))
    (is (= 403 (:status (call h "/shop/orders" "nk"))))
    (let [r (call h "/admin/activity" "ak")
          a (some #(when (= "shop/orders" (:endpoint %)) %) (get-in r [:json :last_5m]))
          e (first (get-in r [:json :recent_errors]))]
      (is (= 200 (:status r)))
      (is (= 3 (:count a)))
      (is (= 1 (:errors a)))
      (is (= {:forbidden 1} (:codes a)))
      (is (= "forbidden" (:code e)))
      (is (= "nobody" (:caller e)) "the authenticated key id is recorded")
      (is (string? (:request_id e)))
      (is (string? (get-in r [:json :started_at]))))
    (testing "admin only, HTTPS guarded"
      (is (= 403 (:status (call h "/admin/activity" "rk"))))
      (is (= 403 (:status (call h "/admin/activity" "ak" "10.0.0.9")))))))
