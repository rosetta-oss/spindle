(ns spindle.integration-test
  "Integration tests against real databases. Each block runs only when its
   environment is set, so `clojure -M:test` stays green without services.

   PostgreSQL (AdventureWorks) + PostgREST:
     AW_DB_HOST AW_DB_PORT AW_DB_USER AW_DB_PASSWORD [ERP_API_URL ERP_API_TOKEN]
   MySQL:      SPINDLE_IT_MYSQL_URL SPINDLE_IT_MYSQL_USER SPINDLE_IT_MYSQL_PASSWORD
   ClickHouse: SPINDLE_IT_CH_URL SPINDLE_IT_CH_USER SPINDLE_IT_CH_PASSWORD

   For every dialect: a read works, a statement past its deadline returns 504,
   and the statement is really gone from the server afterwards (cancelled)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [spindle.auth :as auth]
            [spindle.config :as config]
            [spindle.explain]
            [spindle.server :as server]))

(defn- env [k] (System/getenv k))
(def api-key "it-key")
(def mapper (json/object-mapper {:decode-key-fn true}))
(defn- tmpdir [] (doto (io/file (System/getProperty "java.io.tmpdir") (str "spindle-it-" (System/nanoTime))) (.mkdirs)))

(use-fixtures :each (fn [t] (reset! config/state {:snapshot nil :history [] :last-reload nil :pinned false})
                       (binding [spindle.explain/*archive-dir* (str (tmpdir))] (t))))


(defn- write! [dir rel text] (let [f (io/file dir rel)] (io/make-parents f) (spit f text)))

(defn- copy-dir! [from to]
  (doseq [^java.io.File f (file-seq (io/file from)) :when (.isFile f)]
    (let [rel (str (.relativize (.toPath (io/file from)) (.toPath f)))]
      (write! to rel (slurp f)))))

(defn- call [h method uri & [qs]]
  (let [r (h {:request-method method :uri uri :query-string qs
              :headers {"authorization" (str "Bearer " api-key)}})]
    (assoc r :json (json/read-value (:body r) mapper))))

(defn- auth-edn [] (pr-str {:api-keys [{:id "it" :sha256 (auth/sha256-hex api-key) :scopes #{"sales:read" "it" "spindle:admin"}}]}))

(defn- sleeper-flow [conn sql]
  (pr-str {:steps [{:id :s :kind :sql :conn conn :sql sql}]
           :limits {:timeout "1s"}
           :endpoint {:path "/it/sleep" :scopes #{"it"}}}))

(defn- wait-until [f ms]
  (loop [t 0] (cond (f) true (> t ms) false :else (do (Thread/sleep 100) (recur (+ t 100))))))

;; ---------------------------------------------------------------------------
;; PostgreSQL / AdventureWorks
;; ---------------------------------------------------------------------------

(deftest postgres-adventureworks
  (when (env "AW_DB_HOST")
    (let [dir (tmpdir)]
      (copy-dir! "examples/adventureworks/config" dir)
      (write! dir "auth.edn" (auth-edn))
      (write! dir "flows/it/sleep.edn" (sleeper-flow :aw "select pg_sleep(5) as slept"))
      (is (= nil (:problems (config/reload! (str dir)))))
      (let [h (server/handler {:node-id "it" :config-dir (str dir)})]
        (testing "batch read by id list"
          (let [r (call h :get "/api/sales/orders" "order_id=43659,43660")]
            (is (= 200 (:status r)))
            (is (= #{43659 43660} (set (map :order_id (get-in r [:json :rows])))))
            (is (string? (get-in r [:json :rows 0 :totaldue])))))
        (testing "SQL + HTTP + SCI in one flow"
          (let [r (call h :get "/api/sales/orders/43659/with-stock")
                order (get-in r [:json :rows])]
            (is (= 200 (:status r)))
            (is (= 43659 (:order_id order)))
            (is (= 12 (count (:lines order))))
            (is (every? :line_total (:lines order)))
            (when (env "ERP_API_URL")
              (is (empty? (get-in r [:json :meta :partial])))
              (is (some #(some? (:on_hand %)) (:lines order)) "stock merged from the API"))))
        (testing "deadline → 504 and the statement is cancelled on the server"
          (let [t0 (System/nanoTime)
                r (call h :get "/it/sleep")
                ms (/ (- (System/nanoTime) t0) 1e6)
                ds {:jdbcUrl (str "jdbc:postgresql://" (env "AW_DB_HOST") ":" (env "AW_DB_PORT") "/adventureworks")
                    :user (env "AW_DB_USER") :password (env "AW_DB_PASSWORD")}]
            (is (= 504 (:status r)))
            (is (< ms 2500) (str "returned after " ms " ms"))
            (is (wait-until #(zero? (:n (jdbc/execute-one! ds ["select count(*)::int as n from pg_stat_activity where state='active' and query like '%pg_sleep(5)%' and pid <> pg_backend_pid()"])))
                            1500)
                "pg_sleep no longer active")))))))

;; ---------------------------------------------------------------------------
;; MySQL
;; ---------------------------------------------------------------------------

(deftest mysql-read-timeout-cancel
  (when (env "SPINDLE_IT_MYSQL_URL")
    (let [dir (tmpdir)]
      (write! dir "connections.edn"
              (pr-str {:my {:type :sql :dialect :mysql :jdbc-url (env "SPINDLE_IT_MYSQL_URL")
                            :username "${SPINDLE_IT_MYSQL_USER}" :password "${SPINDLE_IT_MYSQL_PASSWORD}"
                            :defaults {:timeout "3s" :max-rows 1000}}}))
      (write! dir "auth.edn" (auth-edn))
      (write! dir "flows/it/read.edn" (pr-str {:steps [{:id :r :kind :sql :conn :my :sql "select 1 as one, 'x' as s"}]
                                                :endpoint {:path "/it/read" :scopes #{"it"}}}))
      (write! dir "flows/it/sleep.edn" (sleeper-flow :my "select sleep(5) as slept"))
      (is (= nil (:problems (config/reload! (str dir)))))
      (let [h (server/handler {:node-id "it" :config-dir (str dir)})
            ds {:jdbcUrl (env "SPINDLE_IT_MYSQL_URL") :user (env "SPINDLE_IT_MYSQL_USER") :password (env "SPINDLE_IT_MYSQL_PASSWORD")}]
        (is (= [{:one 1 :s "x"}] (get-in (call h :get "/it/read") [:json :rows])))
        (let [t0 (System/nanoTime) r (call h :get "/it/sleep") ms (/ (- (System/nanoTime) t0) 1e6)]
          (is (= 504 (:status r)))
          (is (< ms 2500) (str "returned after " ms " ms"))
          (is (wait-until #(zero? (:n (jdbc/execute-one! ds ["select count(*) as n from information_schema.processlist where info like 'select sleep(5)%' or info like '/* spindle%sleep(5)%'"])))
                          1500)
              "sleep no longer running"))))))

;; ---------------------------------------------------------------------------
;; ClickHouse
;; ---------------------------------------------------------------------------

(deftest clickhouse-read-timeout-cancel
  (when (env "SPINDLE_IT_CH_URL")
    (let [dir (tmpdir)
          slow "select sum(sleepEachRow(0.25)) as s from numbers(40) settings max_block_size = 1, function_sleep_max_microseconds_per_block = 60000000"]
      (write! dir "connections.edn"
              (pr-str {:ch {:type :sql :dialect :clickhouse :jdbc-url (env "SPINDLE_IT_CH_URL")
                            :username "${SPINDLE_IT_CH_USER}" :password "${SPINDLE_IT_CH_PASSWORD}"
                            :defaults {:timeout "3s" :max-rows 1000}}}))
      (write! dir "auth.edn" (auth-edn))
      (write! dir "flows/it/read.edn" (pr-str {:params '[:map [:n pos-int?]]
                                                :steps [{:id :r :kind :sql :conn :ch :sql "select number as n from numbers(10) where number < :n order by n"
                                                         :in {:n [:params :n]}}]
                                                :endpoint {:path "/it/read" :scopes #{"it"}}}))
      (write! dir "flows/it/sleep.edn" (sleeper-flow :ch slow))
      (testing "isolation is refused for ClickHouse at load time"
        (write! dir "flows/it/bad.edn" (pr-str {:steps [{:id :r :kind :sql :conn :ch :sql "select 1" :tuning {:isolation :read-committed}}]}))
        (is (some #(re-find #"no transaction isolation" (:message %)) (:problems (config/reload! (str dir)))))
        (io/delete-file (io/file dir "flows/it/bad.edn")))
      (is (= nil (:problems (config/reload! (str dir)))))
      (let [h (server/handler {:node-id "it" :config-dir (str dir)})
            ds {:jdbcUrl (str (env "SPINDLE_IT_CH_URL") "?compress=false") :user (env "SPINDLE_IT_CH_USER") :password (env "SPINDLE_IT_CH_PASSWORD")}]
        (is (= [0 1 2] (map :n (get-in (call h :get "/it/read" "n=3") [:json :rows]))))
        (let [t0 (System/nanoTime) r (call h :get "/it/sleep") ms (/ (- (System/nanoTime) t0) 1e6)]
          (is (= 504 (:status r)))
          (is (< ms 2500) (str "returned after " ms " ms"))
          (is (wait-until #(zero? (long (:n (jdbc/execute-one! ds ["select count() as n from system.processes where query like '%sleepEachRow(0.25)%' and query not like '%system.processes%'"]))))
                          3000)
              "query no longer in system.processes"))))))
