(ns spindle.result-test
  "The final result stage: [:op ...] derive, assertions, trace."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [spindle.auth :as auth]
            [spindle.config :as config]
            [spindle.explain]
            [spindle.result :as result]
            [spindle.server :as server]))

(deftest expressions
  (let [row {:qty 3 :price 2.5M :disc nil :name "A" :lines [{:t 1.5M} {:t 2.5M}] :cust {:id 7}}]
    (is (= 7.5M (result/value [:* :qty :price] row)))
    (is (= 3M (result/value [:+ :qty :disc] row)) "+ sums what is there")
    (is (nil? (result/value [:- :qty :disc] row)) "- needs both")
    (is (nil? (result/value [:/ :qty 0] row)) "division by zero → nil")
    (is (= 0.33M (result/value [:round [:/ 1 3] 2] row)))
    (is (= "A" (result/value [:coalesce :disc :name] row)))
    (is (= 4M (result/value [:sum :lines :t] row)))
    (is (= 2 (result/value [:count :lines] row)))
    (is (= 7 (result/value [:get :cust :id] row)))
    (is (= "big" (result/value [:if [:> :qty 2] "big" "small"] row)))
    (is (= "A-3" (result/value [:concat :name "-" :qty :disc] row)))))

(deftest assertions
  (let [row {:total 4.001M :lines [{:t 1.5M} {:t 2.5M}] :name "" :d "2026-01-02"}]
    (is (= :pass (result/check [:= :total [:sum :lines :t] {:tolerance 0.01}] row)))
    (is (= :fail (result/check [:= :total [:sum :lines :t]] row)))
    (is (= :unknown (result/check [:> :missing 1] row)))
    (is (= :fail (result/check [:present :name] row)) "blank text is missing")
    (is (= :pass (result/check [:< :d "2026-02-01"] row)) "text compares as text")
    (is (= :fail (result/check [:all [:present :total] [:present :name]] row)))
    (is (= :pass (result/check [:any [:present :total] [:present :name]] row)))
    (is (= :pass (result/check [:not [:present :name]] row))))
  (testing "problems are found before running"
    (is (seq (result/expr-problems [:pow 2 3])))
    (is (seq (result/expr-problems [:- 1])))
    (is (seq (result/assert-problems [:like :a "x"])))
    (is (empty? (result/assert-problems [:= :a [:sum :lines :t] {:tolerance 0.01}])))))

;; ---------------------------------------------------------------------------
;; end to end (SQLite)
;; ---------------------------------------------------------------------------

(def ^:dynamic *dir* nil)
(def key-svc "svc-key")
(def key-plain "plain-key")

(defn- write! [rel text] (let [f (io/file *dir* rel)] (io/make-parents f) (spit f text)))

(use-fixtures :each
  (fn [t]
    (let [root (doto (io/file (System/getProperty "java.io.tmpdir") (str "spindle-res-" (System/nanoTime))) (.mkdirs))
          db (str (io/file root "r.db"))
          ds {:jdbcUrl (str "jdbc:sqlite:" db)}]
      (jdbc/execute! ds ["create table orders (id integer primary key, total numeric)"])
      (jdbc/execute! ds ["create table lines (order_id integer, line integer, qty integer, price numeric)"])
      (jdbc/execute! ds ["insert into orders values (1, 10.0), (2, 99.0)"])
      (jdbc/execute! ds ["insert into lines values (1,1,2,2.5),(1,2,1,5.0),(2,1,1,1.0)"])
      (reset! config/state {:snapshot nil :history [] :last-reload nil :pinned false})
      (binding [*dir* (io/file root "config")
                spindle.explain/*archive-dir* (str (io/file root "explain"))]
        (write! "connections.edn" (pr-str {:db {:type :sql :dialect :sqlite :jdbc-url (str "jdbc:sqlite:" db)
                                                :defaults {:timeout "2s" :max-rows 1000}}}))
        (write! "auth.edn" (pr-str {:api-keys [{:id "svc" :sha256 (auth/sha256-hex key-svc) :scopes #{"read" "spindle:trace"}}
                                               {:id "plain" :sha256 (auth/sha256-hex key-plain) :scopes #{"read"}}]}))
        (write! "flows/orders/checked.edn"
                (pr-str {:params '[:map [:id pos-int?]]
                         :steps [{:id :order :kind :sql :conn :db :in {:id [:params :id]}}
                                 {:id :lines :kind :sql :conn :db :in {:ids [:order :id]}}]
                         :output {:from :order :one? true
                                  :nest {:lines {:from :lines :on {:id :order_id}
                                                 :derive [[:amount [:* :qty :price]]]
                                                 :select [:line :amount]}}
                                  :derive [[:lines_total [:sum :lines :amount]]
                                           [:line_count [:count :lines]]]
                                  :assert [[:= :total :lines_total {:tolerance 0.01}]]}
                         :endpoint {:path "/orders/{id}/checked" :scopes #{"read"}}}))
        (write! "flows/orders/checked.sql"
                (str "-- :name order\nselect id, total from orders where id = :id\n"
                     "-- :name lines\nselect order_id, line, qty, price from lines where order_id in :ids order by line\n"))
        (write! "flows/orders/strict.edn"
                (pr-str {:params '[:map [:id pos-int?]]
                         :steps [{:id :order :kind :sql :conn :db :in {:id [:params :id]} :sql "select id, total from orders where id = :id"}]
                         :output {:from :order :one? true :assert [[:< :total 50]] :on-fail :error}
                         :endpoint {:path "/orders/{id}/strict" :scopes #{"read"}}}))
        (let [r (config/reload! (str *dir*))] (is (:ok r) (pr-str r)))
        (t)))))

(defn- call [uri & [k]]
  (let [h (server/handler {:node-id "t" :config-dir (str *dir*)})
        [path qs] (clojure.string/split uri #"\?" 2)
        r (h {:request-method :get :uri path :query-string qs
              :headers {"authorization" (str "Bearer " (or k key-svc))}})]
    (assoc r :json (json/read-value (:body r) (json/object-mapper {:decode-key-fn true})))))

(deftest derive-and-assert-end-to-end
  (testing "derived fields, nested and top level; a passing check adds nothing"
    (let [r (call "/orders/1/checked")]
      (is (= 200 (:status r)))
      (is (= 10.0 (double (get-in r [:json :rows :lines_total]))))
      (is (= 2 (get-in r [:json :rows :line_count])))
      (is (= [5.0 5.0] (map (comp double :amount) (get-in r [:json :rows :lines]))))
      (is (nil? (get-in r [:json :meta :checks])))))
  (testing "a failing check is flagged in meta.checks (default :on-fail :flag)"
    (let [r (call "/orders/2/checked")]
      (is (= 200 (:status r)))
      (is (= "fail" (get-in r [:json :meta :checks 0 :result])))))
  (testing ":on-fail :error fails the request"
    (is (= 200 (:status (call "/orders/1/strict"))))
    (let [r (call "/orders/2/strict")]
      (is (= 502 (:status r)))
      (is (= "assertion_failed" (get-in r [:json :error :code]))))))

(deftest trace
  (testing "spindle:trace callers see how each step ran"
    (let [t (get-in (call "/orders/1/checked?trace=true") [:json :meta :trace])]
      (is (= ["order" "lines"] (map :step t)))
      (is (every? #(= "ok" (:outcome %)) t))
      (is (= [1 2] (map :rows t)) "rows each step returned")))
  (testing "callers without the scope get no trace"
    (is (nil? (get-in (call "/orders/1/checked?trace=true" key-plain) [:json :meta :trace])))))

(deftest bad-expressions-reject-the-reload
  (write! "flows/orders/bad.edn"
          (pr-str {:steps [{:id :o :kind :sql :conn :db :sql "select id from orders"}]
                   :output {:derive {:x [:pow :id 2]}}}))
  (let [r (config/reload! (str *dir*))]
    (is (some #(re-find #"unknown operator :pow" (:message %)) (:problems r)) (pr-str r))))
