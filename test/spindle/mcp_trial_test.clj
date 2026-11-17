(ns spindle.mcp-trial-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [shuttle.client :as mc]
            [spindle.config :as config]
            [spindle.explain]
            [spindle.mcp.trial :as trial]
            [spindle.server :as server]
            [spindle.stepcache :as stepcache]
            [spindle.tokens :as tokens]))

(def ^:dynamic *env* nil)

(defn- write! [dir rel text]
  (let [f (io/file dir rel)] (io/make-parents f) (spit f text)))

(def ^:private flow-edn
  (pr-str {:params '[:map [:id [:vector {:min 1} pos-int?]]]
           :examples [{:id [1]}]
           :steps [{:id :orders :kind :sql :conn :shop :in {:id [:params :id]} :cache {:ttl "30s"}}]
           :output {:from :orders}
           :endpoint {:path "/shop/orders" :scopes #{"sales:read"}}}))
(def ^:private flow-sql "-- :name orders\nselect id, customer from orders where id in :id")

(defn- files-under [d]
  (set (map str (filter #(.isFile ^java.io.File %) (file-seq (io/file d))))))

(use-fixtures :each
  (fn [t]
    (let [root (doto (io/file (System/getProperty "java.io.tmpdir") (str "spindle-trial-test-" (System/nanoTime))) (.mkdirs))
          cfg (io/file root "config")
          url (str "jdbc:sqlite:" (io/file root "shop.db"))]
      (jdbc/execute! {:jdbcUrl url} ["create table orders (id integer primary key, customer text not null)"])
      (jdbc/execute! {:jdbcUrl url} ["insert into orders values (1,'a'),(2,'b')"])
      (write! cfg "connections.edn"
              (pr-str {:shop {:type :sql :dialect :sqlite :jdbc-url url
                              :pool {:max 2} :defaults {:timeout "2s" :max-rows 1000}}}))
      (write! cfg "auth.edn" (pr-str {:api-keys [] :admin-scope "ops:admin"}))
      (write! cfg "flows/shop/orders.edn" flow-edn)
      (write! cfg "flows/shop/orders.sql" flow-sql)
      (reset! config/state {:snapshot nil :history [] :last-reload nil :pinned false})
      (stepcache/clear!)
      (binding [spindle.explain/*archive-dir* (str (io/file root "explain"))]
        (is (:ok (config/reload! (str cfg)))))
      (let [store (tokens/open-sqlite (str (io/file root "tokens.db")) (str cfg) {})
            srv (server/start! {:port 0 :node-id "t" :config-dir (str cfg) :store store})
            port (.getLocalPort ^org.eclipse.jetty.server.ServerConnector (first (.getConnectors ^org.eclipse.jetty.server.Server srv)))]
        (try
          (binding [*env* {:url (str "http://127.0.0.1:" port "/mcp") :root root :cfg cfg
                           :author (:token (tokens/issue! store {:id "author" :scopes ["spindle:author"] :created-by "test"}))}]
            (t))
          (finally (.stop ^org.eclipse.jetty.server.Server srv)))))))

(defn- call [tool args]
  (let [c (mc/connect {:url (:url *env*) :bearer (:author *env*)})]
    (try (mc/call-tool c tool args) (finally (mc/close c)))))

(defn- data [r] (json/read-value (mc/text r) (json/object-mapper {:decode-key-fn true})))

(deftest validate-ok-and-problems
  (let [v (:version (config/current))]
    (is (= {:ok true} (data (call "validate_flow" {:id "shop/orders2" :edn (str/replace flow-edn "/shop/orders" "/shop/o2") :sql flow-sql}))))
    (let [r (data (call "validate_flow" {:id "shop/bad" :edn (str/replace flow-edn "/shop/orders" "/shop/bad")
                                         :sql "-- :name orders\nselect * from nosuch where id in :id"}))]
      (is (seq (:problems r)))
      (is (str/includes? (pr-str (:problems r)) "orders")))
    (is (= v (:version (config/current))))))

(deftest test-run-does-not-touch-cache
  (let [arch (files-under (io/file "explain-archive"))
        before (stepcache/stats)
        v (:version (config/current))
        r (data (call "test_run" {:id "shop/orders" :params {:id [1 2]}}))
        r2 (data (call "test_run" {:id "shop/orders" :edn flow-edn :sql flow-sql :params {:id "1"}}))]
    (is (= [{:id 1 :customer "a"} {:id 2 :customer "b"}] (:output r)))
    (is (= [{:id 1 :customer "a"}] (:output r2)))
    (is (= "orders" (:id (first (:steps r)))))
    (is (= 2 (:rows (first (:steps r)))))
    (is (number? (:ms (first (:steps r)))))
    (is (= [] (:partial r)))
    (is (= before (stepcache/stats)))
    (is (= arch (files-under (io/file "explain-archive"))))
    (is (= v (:version (config/current))))))

(deftest explain-sqlite
  (let [r (data (call "explain" {:id "shop/orders"}))
        r2 (data (call "explain" {:id "shop/orders" :edn flow-edn :sql flow-sql :params {:id [2]}}))]
    (is (= "orders" (:id (first (:steps r)))))
    (is (seq (:plan (first (:steps r)))))
    (is (seq (:plan (first (:steps r2)))))))

(deftest flow-id-rejects-traversal
  (let [before (files-under (:root *env*))]
    (doseq [id ["../x" "a\\b" "/abs" ""]
            tool ["validate_flow" "test_run" "explain"]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid flow id"
                            (call tool {:id id :edn flow-edn :sql flow-sql :params {:id [1]}}))
          (str tool " " id)))
    (is (= before (files-under (:root *env*))))))

(deftest test-run-reports-the-database-error
  ;; passes validation (the plan is fine) but fails when executed
  (let [edn (pr-str {:steps [{:id :x :kind :sql :conn :shop :sql "select abs(-9223372036854775808) as x"}]})
        e (try (call "test_run" {:id "scratch/overflow" :edn edn :params {}}) nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e))
    (is (str/includes? (str/lower-case (pr-str (ex-data e) (ex-message e))) "integer overflow")
        "the driver's message reaches the author")))

(deftest tool-results-encode-java-time
  (is (= {:at "2026-11-13T18:31:37" :d "2026-11-13" :rows [{:t "12:30"}] :n 1}
         (spindle.mcp/json-safe {:at (java.time.LocalDateTime/of 2026 11 13 18 31 37)
                                 :d (java.time.LocalDate/of 2026 11 13)
                                 :rows [{:t (java.time.LocalTime/of 12 30)}] :n 1}))))

(deftest optional-param-binds-null
  (let [edn (pr-str {:params [:map [:x {:optional true} :string]]
                     :steps [{:id :s :kind :sql :conn :shop :skip-when-empty false
                              :sql "select coalesce(:x, 'none') as v" :in {:x [:params :x]}}]})]
    (is (= [{:v "none"}] (:output (data (call "test_run" {:id "scratch/opt" :edn edn :params {}})))))
    (is (= [{:v "given"}] (:output (data (call "test_run" {:id "scratch/opt" :edn edn :params {:x "given"}})))))))

(deftest take-caps-rows-from-a-param
  (let [edn (pr-str {:params [:map [:limit {:optional true :default 2} [:int {:min 1 :max 100}]]]
                     :steps [{:id :o :kind :sql :conn :shop :take [:params :limit]
                              :sql "select id from orders order by id"}]})]
    (is (= 2 (count (:output (data (call "test_run" {:id "scratch/take" :edn edn :params {}}))))) "default limit")
    (is (= 1 (count (:output (data (call "test_run" {:id "scratch/take" :edn edn :params {:limit 1}}))))))
    (is (= [{:id 1}] (:output (data (call "test_run" {:id "scratch/take" :edn edn :params {:limit 1}})))) "first rows kept")
    (is (<= 2 (count (:output (data (call "test_run" {:id "scratch/take" :edn edn :params {:limit 100}})))))
        "a take above the row count returns everything, no row-cap error")))

(deftest take-must-be-a-number-or-params-path
  (let [edn (pr-str {:steps [{:id :o :kind :sql :conn :shop :take [:other :x] :sql "select id from orders"}]})
        r (data (call "validate_flow" {:id "scratch/take-bad" :edn edn}))]
    (is (some #(re-find #"take" (str (:message %))) (:problems r)))))

(deftest candidate-explains-only-the-changed-flow
  ;; the live archive already holds shop/orders; a candidate adding shop/o2 must
  ;; explain one step, not re-explain every flow (that made test_run take ~40 s)
  (binding [spindle.explain/*archive-dir* (str (io/file (:root *env*) "explain"))]
    (let [snap (config/current)
          r (trial/build-candidate snap (trial/candidate-tree snap "shop/o2" (str/replace flow-edn "/shop/orders" "/shop/o2") flow-sql))]
      (is (= {:explained 1 :archived 1}
             (select-keys (get-in r [:snapshot :explain]) [:explained :archived]))))))
