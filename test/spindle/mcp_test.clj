(ns spindle.mcp-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [shuttle.client :as mc]
            [spindle.auth :as auth]
            [spindle.config :as config]
            [spindle.explain]
            [spindle.mcp :as mcp]
            [spindle.server :as server]
            [spindle.tokens :as tokens]))

(def ^:dynamic *env* nil)

(defn- write! [dir rel text]
  (let [f (io/file dir rel)] (io/make-parents f) (spit f text)))

(def ^:private secret "s3cr3t-pw-xyz")
(def ^:private flow-edn
  (pr-str {:params '[:map [:id [:vector {:min 1} pos-int?]]]
           :steps [{:id :orders :kind :sql :conn :shop :in {:id [:params :id]}}]
           :output {:from :orders}
           :endpoint {:path "/shop/orders" :scopes #{"sales:read"}}}))
(def ^:private flow-sql "-- :name orders\nselect id, customer from orders where id in :id")

(use-fixtures :each
  (fn [t]
    (let [root (doto (io/file (System/getProperty "java.io.tmpdir") (str "spindle-mcp-test-" (System/nanoTime))) (.mkdirs))
          cfg (io/file root "config")
          db (str (io/file root "shop.db"))
          url (str "jdbc:sqlite:" db)]
      (jdbc/execute! {:jdbcUrl url} ["create table orders (id integer primary key, customer text not null, note text)"])
      (jdbc/execute! {:jdbcUrl url} ["create index orders_customer on orders(customer)"])
      (jdbc/execute! {:jdbcUrl url} ["insert into orders values (1,'Zebediah-Row-Value',null)"])
      (write! cfg "secret.txt" secret)
      (write! cfg "connections.edn"
              (pr-str {:shop {:type :sql :dialect :sqlite :jdbc-url url
                              :password (str "${file:" (io/file cfg "secret.txt") "}")
                              :pool {:max 2} :defaults {:timeout "2s" :max-rows 1000}
                              :guard {:concurrency 2 :breaker {:failure-ratio [3 5] :open "1s"}}}}))
      (write! cfg "auth.edn" (pr-str {:api-keys [] :admin-scope "ops:admin"}))
      (write! cfg "flows/shop/orders.edn" flow-edn)
      (write! cfg "flows/shop/orders.sql" flow-sql)
      (reset! config/state {:snapshot nil :history [] :last-reload nil :pinned false})
      (binding [spindle.explain/*archive-dir* (str (io/file root "explain"))]
        (is (:ok (config/reload! (str cfg))))
        (let [store (tokens/open-sqlite (str (io/file root "tokens.db")) (str cfg) {})
              srv (server/start! {:port 0 :node-id "t" :config-dir (str cfg) :store store})
              port (.getLocalPort ^org.eclipse.jetty.server.ServerConnector (first (.getConnectors ^org.eclipse.jetty.server.Server srv)))
              tok #(:token (tokens/issue! store {:id %1 :scopes %2 :created-by "test"}))]
          (try
            (binding [*env* {:url (str "http://127.0.0.1:" port "/mcp")
                             :author (tok "author" ["spindle:author"])
                             :admin (tok "root" ["ops:admin"])
                             :reader (tok "reader" ["sales:read"])}]
              (t))
            (finally (.stop ^org.eclipse.jetty.server.Server srv))))))))

(defn- client [k] (mc/connect {:url (:url *env*) :bearer (get *env* k)}))

(defn- call [k tool args]
  (let [c (client k)]
    (try (mc/call-tool c tool args) (finally (mc/close c)))))

(defn- data [r] (json/read-value (mc/text r) (json/object-mapper {:decode-key-fn true})))

(deftest tools-hidden-without-scope
  (let [e (is (thrown? clojure.lang.ExceptionInfo (mc/list-tools (client :reader))))]
    (is (= 401 (:status (ex-data e)))))
  (is (thrown? clojure.lang.ExceptionInfo (mc/list-tools (mc/connect {:url (:url *env*)})))))

(deftest author-sees-tools
  (doseq [k [:author :admin]]
    (is (every? (set (map :name (mc/list-tools (client k))))
                ["list_connections" "describe_table" "describe_proc" "list_flows" "get_flow"]))))

(deftest list-connections-no-secrets
  (let [r (call :author "list_connections" {})
        s (pr-str r)]
    (is (not (str/includes? s secret)))
    (is (not (str/includes? s "jdbc:")))
    (is (not (str/includes? s "Zebediah")))
    (let [[c] (data r)]
      (is (= "shop" (:id c)))
      (is (= "sql" (:kind c)))
      (is (= "sqlite" (:dialect c)))
      (is (= "closed" (:breaker c)))
      (is (= {:timeout "2s" :max-rows 1000} (:defaults c))))))

(deftest describe-table-sqlite
  (let [r (call :author "describe_table" {:conn "shop" :table "orders"})
        d (data r)]
    (is (= ["id" "customer" "note"] (map :name (:columns d))))
    (is (= ["id"] (:primary_key d)))
    (is (= [{:name "orders_customer" :columns ["customer"] :unique false}] (:indexes d)))
    (is (false? (:nullable (second (:columns d)))))
    (is (not (str/includes? (mc/text r) "Zebediah")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no such table"
                          (call :author "describe_table" {:conn "shop" :table "nope"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no such connection"
                          (call :author "describe_table" {:conn "zzz" :table "orders"})))))

(deftest describe-proc-refusals
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no such procedure"
                        (call :author "describe_proc" {:conn "shop" :proc "p"}))))

(deftest flows-roundtrip
  (let [fl (data (call :author "list_flows" {}))]
    (is (= "shop/orders" (:id (first fl))))
    (is (= {:method "get" :path "/shop/orders" :scopes ["sales:read"]} (:endpoint (first fl)))))
  (let [g (data (call :admin "get_flow" {:id "shop/orders"}))]
    (is (= flow-edn (:edn g)))
    (is (= flow-sql (:sql g))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no such flow"
                        (call :author "get_flow" {:id "../connections"}))))

(deftest flow-id-syntax
  (is (mcp/valid-flow-id? "shop/orders-2"))
  (is (not (mcp/valid-flow-id? "../x")))
  (is (not (mcp/valid-flow-id? "Shop")))
  (is (not (mcp/valid-flow-id? "a//b")))
  (is (not (mcp/valid-flow-id? (apply str (repeat 129 "a"))))))
