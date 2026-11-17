(ns spindle.sqlite-e2e-test
  "End to end without external services: SQLite file DB, a config directory,
   the HTTP handler, hot reload, rejection of bad config, and caching."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [spindle.auth :as auth]
            [spindle.config :as config]
            [spindle.explain]
            [spindle.server :as server]))

(def ^:dynamic *dir* nil)

(def api-key "test-key-123")

(defn- write! [dir rel text]
  (let [f (io/file dir rel)] (io/make-parents f) (spit f text)))

(defn- base-config [dir db]
  (write! dir "connections.edn"
          (pr-str {:shop {:type :sql :dialect :sqlite :jdbc-url (str "jdbc:sqlite:" db)
                          :pool {:max 2} :defaults {:timeout "2s" :max-rows 1000}
                          :guard {:concurrency 2 :breaker {:failure-ratio [3 5] :open "1s"}}}}))
  (write! dir "auth.edn" (pr-str {:api-keys [{:id "t" :sha256 (auth/sha256-hex api-key) :scopes #{"shop:read" "spindle:admin"}}]}))
  (write! dir "flows/shop/orders.edn"
          (pr-str {:params '[:map [:id [:vector {:min 1} pos-int?]]]
                   :steps [{:id :orders :kind :sql :conn :shop :in {:id [:params :id]}}
                           {:id :lines :kind :sql :conn :shop :in {:id [:orders :id]}}]
                   :output {:from :orders
                            :nest {:lines {:from :lines :on {:id :order_id}
                                           :compute {:total '(* qty price)}}}}
                   :cache {:ttl "10s"}
                   :endpoint {:path "/shop/orders" :scopes #{"shop:read"}}}))
  (write! dir "flows/shop/orders.sql"
          "-- :name orders\nselect id, customer from orders where id in :id\n\n-- :name lines\nselect order_id, sku, qty, price from lines where order_id in :id"))

(use-fixtures :each
  (fn [t]
    (let [dir (doto (io/file (System/getProperty "java.io.tmpdir") (str "spindle-test-" (System/nanoTime))) (.mkdirs))
          db (str (io/file dir "shop.db"))]
      (jdbc/execute! {:jdbcUrl (str "jdbc:sqlite:" db)} ["create table orders (id integer primary key, customer text)"])
      (jdbc/execute! {:jdbcUrl (str "jdbc:sqlite:" db)} ["create table lines (order_id integer, sku text, qty integer, price real)"])
      (jdbc/execute! {:jdbcUrl (str "jdbc:sqlite:" db)} ["insert into orders values (1,'Ann'),(2,'Bo')"])
      (jdbc/execute! {:jdbcUrl (str "jdbc:sqlite:" db)} ["insert into lines values (1,'A',2,1.5),(1,'B',1,4.0),(2,'C',3,1.0)"])
      (base-config (io/file dir "config") db)
      (reset! config/state {:snapshot nil :history [] :last-reload nil :pinned false})
      (binding [*dir* (str (io/file dir "config"))
                spindle.explain/*archive-dir* (str (io/file dir "explain"))]
        (t)))))

(defn- call [h method uri & [{:keys [key qs]}]]
  (let [r (h {:request-method method :uri uri :query-string qs
              :headers (cond-> {} key (assoc "authorization" (str "Bearer " key)))})]
    (assoc r :json (json/read-value (:body r) (json/object-mapper {:decode-key-fn true})))))

(deftest end-to-end
  (is (:ok (config/reload! *dir*)))
  (let [h (server/handler {:node-id "t" :config-dir *dir*})]
    (testing "auth"
      (is (= 401 (:status (call h :get "/shop/orders" {:qs "id=1"}))))
      (is (= 401 (:status (call h :get "/shop/orders" {:qs "id=1" :key "wrong"})))))
    (testing "two-step flow with nesting and SCI compute; list param from comma string"
      (let [r (call h :get "/shop/orders" {:qs "id=1,2" :key api-key})]
        (is (= 200 (:status r)))
        (is (= 2 (count (get-in r [:json :rows]))))
        (is (= 3.0 (get-in r [:json :rows 0 :lines 0 :total])))
        (is (= "miss" (get-in r [:json :meta :cache])))
        (is (= "hit" (get-in (call h :get "/shop/orders" {:qs "id=1,2" :key api-key}) [:json :meta :cache])))))
    (testing "home redirects to the console"
      (let [r (h {:request-method :get :uri "/" :headers {}})]
        (is (= 302 (:status r)))
        (is (= "/console" (get-in r [:headers "Location"])))))
    (testing "bad params"
      (is (= 422 (:status (call h :get "/shop/orders" {:qs "id=abc" :key api-key})))))
    (testing "status + metrics"
      (server/refresh-gauges!)
      (is (= "closed" (get-in (call h :get "/status") [:json :connections 0 :breaker :state])))
      (is (re-find #"spindle_breaker_state" (:body (h {:request-method :get :uri "/metrics"}))) "gauge registered"))))

(deftest hot-reload-and-rejection
  (is (:ok (config/reload! *dir*)))
  (let [v1 (:version (config/current))
        h (server/handler {:node-id "t" :config-dir *dir*})]
    (testing "a broken flow is rejected and the old snapshot stays"
      (write! *dir* "flows/shop/bad.edn" (pr-str {:steps [{:id :x :kind :sql :conn :shop :sql "select :nobind"}]
                                                   :endpoint {:path "/bad"}}))
      (let [r (config/reload! *dir*)]
        (is (:problems r))
        (is (= v1 (:version (config/current))))))
    (testing "a cycle is rejected"
      (write! *dir* "flows/shop/bad.edn" (pr-str {:steps [{:id :x :kind :sql :conn :shop :sql "select :a" :in {:a [:y :v]}}
                                                           {:id :y :kind :sql :conn :shop :sql "select :b" :in {:b [:x :v]}}]}))
      (is (some #(re-find #"cycle" (:message %)) (:problems (config/reload! *dir*)))))
    (testing "writes are refused in read flows"
      (write! *dir* "flows/shop/bad.edn" (pr-str {:steps [{:id :x :kind :sql :conn :shop :sql "delete from orders"}]}))
      (is (some #(re-find #"needs a mutation endpoint" (:message %)) (:problems (config/reload! *dir*)))))
    (testing "a good new flow is picked up; the pool is carried over"
      (io/delete-file (io/file *dir* "flows/shop/bad.edn"))
      (let [ds-before (get-in (config/current) [:conns :shop :ds])]
        (write! *dir* "flows/shop/customer.edn"
                (pr-str {:params '[:map [:id pos-int?]]
                         :sql "select customer from orders where id = :id" :conn :shop :in {:id [:params :id]}
                         :endpoint {:path "/shop/customer/{id}" :scopes #{"shop:read"}}}))
        (is (:ok (config/reload! *dir*)))
        (is (identical? ds-before (get-in (config/current) [:conns :shop :ds])))
        (is (= "Bo" (get-in (call h :get "/shop/customer/2" {:key api-key}) [:json :rows 0 :customer])))))
    (testing "rollback pins this node to an earlier version"
      (let [r (config/rollback! v1)]
        (is (:ok r))
        (is (:pinned @config/state))
        (is (= 404 (:status (call h :get "/shop/customer/2" {:key api-key}))))))))

(deftest slow-sqlite-statement-is-interrupted
  ;; SQLite runs in-process and its driver ignores setQueryTimeout: a statement past
  ;; its timeout must still be interrupted and its pooled connection given back, or a
  ;; few slow calls exhaust the pool. Own connection: pool of 2, no breaker.
  (let [db (subs (:jdbc-url (:shop (read-string (slurp (str *dir* "/connections.edn"))))) (count "jdbc:sqlite:"))]
    (write! *dir* "connections.edn"
            (pr-str {:shop {:type :sql :dialect :sqlite :jdbc-url (str "jdbc:sqlite:" db)
                            :pool {:max 2} :defaults {:timeout "2s" :max-rows 1000}}
                     :slow {:type :sql :dialect :sqlite :jdbc-url (str "jdbc:sqlite:" db)
                            :pool {:max 2} :defaults {:timeout "1s" :max-rows 1000}}})))
  (write! *dir* "flows/shop/slow.edn"
          (pr-str {:params '[:map [:n pos-int?]]
                   :steps [{:id :slow :kind :sql :conn :slow :in {:n [:params :n]}}]
                   :endpoint {:path "/shop/slow" :scopes #{"shop:read"}}}))
  (write! *dir* "flows/shop/slow.sql"
          "-- :name slow
with recursive c(x) as (select 1 union all select x + 1 from c where x < :n) select count(*) as n from c")
  (write! *dir* "flows/shop/fast.edn"
          (pr-str {:steps [{:id :fast :kind :sql :conn :slow}]
                   :endpoint {:path "/shop/fast" :scopes #{"shop:read"}}}))
  (write! *dir* "flows/shop/fast.sql" "-- :name fast
select count(*) as n from orders")
  (let [r (config/reload! *dir*)] (is (:ok r) (pr-str r)))
  (let [h (server/handler {:node-id "t" :config-dir *dir*})
        slow #(let [t0 (System/nanoTime)
                    r (call h :get "/shop/slow" {:qs "n=2000000000" :key api-key})]
                [(:status r) (get-in r [:json :error :code]) (/ (- (System/nanoTime) t0) 1e9)])]
    (testing "past its 1 s timeout the statement stops and the call fails as a timeout"
      (let [[status code secs] (slow)]
        (is (= 504 status))
        (is (= "timeout" code))
        (is (< secs 3.0) (str "took " secs " s"))))
    (testing "more slow calls than the pool has connections: each still stops in time"
      (dotimes [_ 3]
        (let [[status code secs] (slow)]
          (is (= 504 status) (str status " " code))
          (is (< secs 3.0) (str "took " secs " s")))))
    (testing "the pool still has free connections"
      (let [r (call h :get "/shop/fast" {:key api-key})]
        (is (= 200 (:status r)) (pr-str (:json r)))))))

(deftest open-breaker-half-opens-after-its-delay
  ;; :shop's breaker opens after 3 failures in 5 calls and should let a trial call
  ;; through after "1s". It used to stay open for good: the fast-fail check asked
  ;; isOpen(), and Failsafe only moves OPEN -> HALF_OPEN inside tryAcquirePermit().
  (write! *dir* "flows/shop/bad.edn"
          (pr-str {:steps [{:id :bad :kind :sql :conn :shop}]
                   :endpoint {:path "/shop/bad" :scopes #{"shop:read"}}}))
  (write! *dir* "flows/shop/bad.sql" "-- :name bad\nselect id from orders where 1 = (select count(*) from orders)")
  (spindle.explain/enabled?)
  (is (:ok (config/reload! *dir*)))
  (let [h (server/handler {:node-id "t" :config-dir *dir*})
        rt (get-in (config/current) [:conns :shop])]
    ;; trip the breaker by recording failures directly on its guards
    (dotimes [_ 5] (.recordFailure ^dev.failsafe.CircuitBreaker (get-in rt [:guards :breaker])))
    (testing "open: calls fail fast"
      (let [r (call h :get "/shop/orders" {:qs "id=1" :key api-key})]
        (is (= 503 (:status r)))
        (is (= "circuit breaker open" (get-in r [:json :error :message])))))
    (testing "after the 1 s delay a trial call goes through and succeeds"
      (Thread/sleep 1300)
      (let [r (call h :get "/shop/orders" {:qs "id=2" :key api-key})]
        (is (= 200 (:status r)) (pr-str (:json r)))))))
