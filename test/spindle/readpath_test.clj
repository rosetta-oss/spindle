(ns spindle.readpath-test
  "Batch route, keyset paging, OpenAPI and the slow-step log (SQLite)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [spindle.auth :as auth]
            [spindle.config :as config]
            [spindle.explain]
            [spindle.server :as server]
            [spindle.stepcache :as stepcache]))

(def ^:dynamic *dir* nil)
(def k "svc-key")

(defn- write! [rel text] (let [f (io/file *dir* rel)] (io/make-parents f) (spit f text)))

(use-fixtures :each
  (fn [t]
    (let [root (doto (io/file (System/getProperty "java.io.tmpdir") (str "spindle-rp-" (System/nanoTime))) (.mkdirs))
          db (str (io/file root "r.db"))
          ds {:jdbcUrl (str "jdbc:sqlite:" db)}]
      (jdbc/execute! ds ["create table orders (id integer primary key, customer text)"])
      (doseq [i (range 1 26)] (jdbc/execute! ds ["insert into orders values (?, ?)" i (str "C-" (mod i 3))]))
      ;; order 1 exists as type 1 and type 2: a compound key (order_no, order_type) keeps them apart
      (jdbc/execute! ds ["create table order_lines (order_no integer, order_type integer, line_no integer, qty integer)"])
      (doseq [[o t l q] [[1 1 1 10] [1 1 2 11] [1 2 1 20] [2 2 1 30] [3 1 1 40]]]
        (jdbc/execute! ds ["insert into order_lines values (?, ?, ?, ?)" o t l q]))
      (reset! config/state {:snapshot nil :history [] :last-reload nil :pinned false})
      (stepcache/clear!)
      (binding [*dir* (io/file root "config")
                spindle.explain/*archive-dir* (str (io/file root "explain"))]
        (write! "connections.edn" (pr-str {:db {:type :sql :dialect :sqlite :jdbc-url (str "jdbc:sqlite:" db)
                                                :defaults {:timeout "2s" :max-rows 1000}}}))
        (write! "auth.edn" (pr-str {:api-keys [{:id "svc" :sha256 (auth/sha256-hex k) :scopes #{"read"}}]}))
        (write! "flows/orders/by-id.edn"
                (pr-str {:params '[:map [:id [:vector {:min 1 :max 100} pos-int?]]]
                         :steps [{:id :o :kind :sql :conn :db :in {:ids [:params :id]}
                                  :sql "select id, customer from orders where id in :ids order by id"}]
                         :cache {:ttl "30s"}
                         :endpoint {:path "/orders" :scopes #{"read"} :batch {:key :id}}}))
        (write! "flows/orders/lines.edn"
                (pr-str {:params '[:map [:order_no [:vector {:min 1 :max 3} pos-int?]] [:order_type pos-int?]]
                         :steps [{:id :l :kind :sql :conn :db :in {:nos [:params :order_no] :typ [:params :order_type]}
                                  :sql "select order_no, order_type, line_no, qty from order_lines where order_no in :nos and order_type = :typ order by order_no, line_no"}]
                         :endpoint {:path "/orders/lines" :scopes #{"read"} :batch {:key :order_no :group-by [:order_type]}}}))
        (write! "flows/orders/list.edn"
                (pr-str {:params '[:map [:customer {:optional true} string?]]
                         :steps [{:id :o :kind :sql :conn :db
                                  :in {:after [:page :after_id] :limit [:page :limit]}
                                  :sql "select id, customer from orders where (:after is null or id > :after) order by id limit :limit"}]
                         :endpoint {:path "/orders/all" :scopes #{"read"} :page {:key [:id] :size 10 :max-size 20}}}))
        (let [r (config/reload! (str *dir*))] (is (:ok r) (pr-str r)))
        (t)))))

(defn- call [method uri & [body]]
  (let [h (server/handler {:node-id "t" :config-dir (str *dir*)})
        [path qs] (str/split uri #"\?" 2)
        r (h (cond-> {:request-method method :uri path :query-string qs
                      :headers {"authorization" (str "Bearer " k)}}
               body (assoc :body (java.io.ByteArrayInputStream. (.getBytes (json/write-value-as-string body) "UTF-8")))))]
    (assoc r :json (json/read-value (:body r) (json/object-mapper {:decode-key-fn true})))))

(deftest batch-route
  (testing "one call for many keys; rows echo the key"
    (let [r (call :post "/api/_batch/orders/by-id" {:id [3 1 2]})]
      (is (= 200 (:status r)))
      (is (= [3 1 2] (map :id (get-in r [:json :rows]))) "rows come back in key order")
      (is (= {:keys 3 :cached 0 :fetched 3} (get-in r [:json :meta :batch])))))
  (testing "the comma form works, and cached keys are not fetched again"
    (let [r (call :post "/api/_batch/orders/by-id" {:id "1,2,4"})]
      (is (= [1 2 4] (map :id (get-in r [:json :rows]))))
      (is (= {:keys 3 :cached 2 :fetched 1} (get-in r [:json :meta :batch])))))
  (testing "a key with no row returns nothing for it"
    (is (= [5] (map :id (get-in (call :post "/api/_batch/orders/by-id" {:id [5 999]}) [:json :rows])))))
  (testing "GET is refused, unknown ids are 404"
    (is (= 405 (:status (call :get "/api/_batch/orders/by-id"))))
    (is (= 404 (:status (call :post "/api/_batch/orders/list" {:id [1]}))))))

(deftest batch-compound-key
  (testing "an array of (order_no, order_type) items: one run per order_type, rows echo both parts"
    (let [r (call :post "/api/_batch/orders/lines" [{:order_no 1 :order_type 1} {:order_no 1 :order_type 2} {:order_no 2 :order_type 2}])
          rows (get-in r [:json :rows])]
      (is (= 200 (:status r)) (pr-str (:json r)))
      (is (= #{[1 1 10] [1 1 11] [1 2 20] [2 2 30]} (set (map (juxt :order_no :order_type :qty) rows))))
      (is (= {:keys 3 :cached 0 :fetched 3 :groups 2} (get-in r [:json :meta :batch])))))
  (testing "only the pairs asked for: (1, 1) and (2, 2) do not bring back (1, 2)"
    (let [rows (get-in (call :post "/api/_batch/orders/lines" [{:order_no 1 :order_type 1} {:order_no 2 :order_type 2}]) [:json :rows])]
      (is (= #{[1 1] [2 2]} (set (map (juxt :order_no :order_type) rows))))))
  (testing "the map form still works: a list of keys with one order_type"
    (is (= #{3 1} (set (map :order_no (get-in (call :post "/api/_batch/orders/lines" {:order_no [1 3] :order_type 1}) [:json :rows]))))))
  (testing "items are checked: both parts required, nothing else, at most the key list's :max"
    (is (= 422 (:status (call :post "/api/_batch/orders/lines" [{:order_no 1}]))))
    (is (= 422 (:status (call :post "/api/_batch/orders/lines" [{:order_no 1 :order_type 1 :qty 5}]))))
    (is (= 422 (:status (call :post "/api/_batch/orders/lines" []))))
    (is (= 422 (:status (call :post "/api/_batch/orders/lines" (for [i (range 1 5)] {:order_no i :order_type 1}))))
        "4 items, the key list allows 3"))
  (testing "OpenAPI offers both body shapes"
    (let [doc (:json (call :get "/openapi.json"))
          sch (get-in doc [:paths (keyword "/api/_batch/orders/lines") :post :requestBody :content :application/json :schema])]
      (is (= 2 (count (:oneOf sch))))
      (is (= ["order_no" "order_type"] (get-in sch [:oneOf 1 :items :required]))))))

(deftest batch-group-by-is-checked-on-load
  (write! "flows/orders/bad.edn"
          (pr-str {:params '[:map [:order_no [:vector pos-int?]] [:kinds [:vector pos-int?]]]
                   :steps [{:id :l :kind :sql :conn :db :in {:nos [:params :order_no]}
                            :sql "select order_no from order_lines where order_no in :nos"}]
                   :endpoint {:path "/orders/bad" :scopes #{"read"} :batch {:key :order_no :group-by [:order_type :kinds :order_no]}}}))
  (let [r (config/reload! (str *dir*))
        msgs (set (map :message (:problems r)))]
    (is (not (:ok r)))
    (is (some #(str/includes? % "order_type must be a parameter") msgs) (pr-str msgs))
    (is (some #(str/includes? % "kinds takes one value per item") msgs))
    (is (some #(str/includes? % "is the batch key itself") msgs))))

(deftest keyset-paging
  (let [p1 (call :get "/orders/all")
        p2 (call :get (str "/orders/all?after=" (get-in p1 [:json :next])))
        p3 (call :get (str "/orders/all?after=" (get-in p2 [:json :next])))]
    (is (= (range 1 11) (map :id (get-in p1 [:json :rows]))))
    (is (= (range 11 21) (map :id (get-in p2 [:json :rows]))))
    (is (= (range 21 26) (map :id (get-in p3 [:json :rows]))))
    (is (nil? (get-in p3 [:json :next])) "last page has no cursor"))
  (testing "size within max-size"
    (is (= 3 (count (get-in (call :get "/orders/all?size=3") [:json :rows]))))
    (is (= 422 (:status (call :get "/orders/all?size=21"))))
    (is (= 422 (:status (call :get "/orders/all?after=notacursor"))))))

(deftest paging-and-batch-checks
  (write! "flows/orders/badpage.edn"
          (pr-str {:steps [{:id :o :kind :sql :conn :db :sql "select id from orders"}]
                   :endpoint {:path "/bad" :page {:key [:id]}}}))
  (write! "flows/orders/badbatch.edn"
          (pr-str {:params '[:map [:id pos-int?]]
                   :steps [{:id :o :kind :sql :conn :db :in {:id [:params :id]} :sql "select id from orders where id = :id"}]
                   :endpoint {:path "/bad2" :batch {:key :id}}}))
  (let [ms (map :message (:problems (config/reload! (str *dir*))))]
    (is (some #(re-find #"no step binds \[:page :limit\]" %) ms))
    (is (some #(re-find #"must be a list parameter" %) ms))))

(deftest openapi
  (let [r (call :get "/openapi.json")
        doc (:json r)]
    (is (= 200 (:status r)))
    (is (= "3.1.0" (:openapi doc)))
    (is (get-in doc [:paths (keyword "/orders") :get]))
    (is (get-in doc [:paths (keyword "/api/_batch/orders/by-id") :post :requestBody]))
    (is (some #(= "after" (:name %)) (get-in doc [:paths (keyword "/orders/all") :get :parameters])))
    (is (= ["read"] (get-in doc [:paths (keyword "/orders") :get :x-spindle-scopes]))))
  (testing "needs a valid key"
    (let [h (server/handler {:node-id "t" :config-dir (str *dir*)})]
      (is (= 401 (:status (h {:request-method :get :uri "/openapi.json" :headers {}})))))))

(deftest slow-step-log
  (write! "flows/orders/slow.edn"
          (pr-str {:steps [{:id :o :kind :sql :conn :db :tuning {:slow 1}
                            :sql "with recursive c(x) as (select 1 union all select x+1 from c where x < 300000) select count(*) as n from c"}]
                   :endpoint {:path "/slow"}}))
  (is (:ok (config/reload! (str *dir*))))
  (let [logged (atom [])]
    (with-redefs [clojure.tools.logging/log* (fn [_ level _ msg] (when (str/includes? (str msg) "slow-step") (swap! logged conj msg)))]
      (call :get "/slow"))
    (is (= 1 (count @logged)))
    (let [m (json/read-value (first @logged) (json/object-mapper {:decode-key-fn true}))]
      (is (= "orders/slow" (:endpoint m)))
      (is (= "o" (:step m)))
      (is (= 1 (:rows m)))
      (is (string? (:params_hash m))))))
