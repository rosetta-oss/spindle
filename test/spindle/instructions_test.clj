(ns spindle.instructions-test
  "The instruction set after the 2026-11-17 review: strict loading, one [:op]
   language for conditions, multi-view flows, consistent defaults, and the
   executor's fallback rules."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [ring.adapter.jetty9]
            [spindle.auth :as auth]
            [spindle.config :as config]
            [spindle.executor :as ex]
            [spindle.explain]
            [spindle.flow :as flow]
            [spindle.server :as server]
            [spindle.stepcache :as stepcache]))

;; ---------------------------------------------------------------------------
;; executor
;; ---------------------------------------------------------------------------

(defn- deadline [ms] (+ (System/nanoTime) (* ms 1000000)))

(deftest optional-step-with-fallback-does-not-hang
  (let [t0 (System/nanoTime)
        r (ex/run {:a {:deps #{} :conn :c :or-else :b :optional true}
                   :b {:deps #{} :conn :c}}
                  (fn [step _ _] (if (= :a (:id step)) (throw (ex-info "boom" {:spindle/code :source_error})) [{:x 1}]))
                  {:deadline-ns (deadline 2000)})]
    (is (< (- (System/nanoTime) t0) 1000000000) "answers at once, not at the deadline")
    (is (= {:a nil} (:results r)))
    (is (= [{:step :a :code :source_error}] (:partial r)))))

(deftest fallback-waits-for-its-own-dependencies
  (let [runs (atom [])
        r (ex/run {:slow {:deps #{} :conn :c2}
                   :a {:deps #{} :conn :c :or-else :b}
                   :b {:deps #{:slow} :conn :c}}
                  (fn [step env _]
                    (swap! runs conj (:id step))
                    (case (:id step)
                      :slow (do (Thread/sleep 200) [{:k 1}])
                      :a []
                      :b [{:saw-slow (contains? (:results env) :slow)}]))
                  {:deadline-ns (deadline 3000)})]
    (is (= [{:saw-slow true}] (get-in r [:results :a])) "consumers of :a see the fallback's rows")
    (is (= 1 (count (filter #{:a} @runs))) "the primary is not started again while it waits")))

;; ---------------------------------------------------------------------------
;; config and end to end (SQLite + stub API)
;; ---------------------------------------------------------------------------

(def ^:dynamic *dir* nil)
(def k "svc-key")
(def calls (atom 0))

(defonce stub
  (delay (ring.adapter.jetty9/run-jetty
          (fn [_] (swap! calls inc)
            {:status 200 :headers {"Content-Type" "application/json"}
             :body (json/write-value-as-string {:header {:id 7 :total 3} :details [{:line 1} {:line 2}]})})
          {:port 0 :join? false})))

(defn- stub-url [] (str "http://127.0.0.1:" (.getLocalPort ^org.eclipse.jetty.server.ServerConnector
                                                         (first (.getConnectors ^org.eclipse.jetty.server.Server @stub)))))

(defn- write! [rel text] (let [f (io/file *dir* rel)] (io/make-parents f) (spit f text)))

(use-fixtures :each
  (fn [t]
    (let [root (doto (io/file (System/getProperty "java.io.tmpdir") (str "spindle-ins-" (System/nanoTime))) (.mkdirs))
          db (str (io/file root "i.db"))
          ds {:jdbcUrl (str "jdbc:sqlite:" db)}]
      (jdbc/execute! ds ["create table customers (id text primary key, status text, qty integer)"])
      (jdbc/execute! ds ["insert into customers values ('C-1','A',5),('C-2','X',0)"])
      (jdbc/execute! ds ["create table prices (cid text, price numeric)"])
      (jdbc/execute! ds ["insert into prices values ('C-1', 1), ('C-1', 2)"])
      (reset! config/state {:snapshot nil :history [] :last-reload nil :pinned false})
      (reset! calls 0)
      (stepcache/clear!)
      (binding [*dir* (io/file root "config")
                spindle.explain/*archive-dir* (str (io/file root "explain"))]
        (write! "connections.edn" (pr-str {:db {:type :sql :dialect :sqlite :jdbc-url (str "jdbc:sqlite:" db)
                                                :defaults {:timeout "2s" :max-rows 5000}}
                                           :api {:type :http :base-url (stub-url) :defaults {:timeout "2s"}}}))
        (write! "auth.edn" (pr-str {:api-keys [{:id "svc" :sha256 (auth/sha256-hex k) :scopes #{"read"}}]}))
        (t)))))

(defn- reload [] (config/reload! (str *dir*)))
(defn- messages [r] (str/join "\n" (map :message (:problems r))))

(defn- call [uri]
  (let [h (server/handler {:node-id "t" :config-dir (str *dir*)})
        r (h {:request-method :get :uri uri :headers {"authorization" (str "Bearer " k)}})]
    (assoc r :json (json/read-value (:body r) (json/object-mapper {:decode-key-fn true})))))

(deftest misspelled-keys-are-load-errors
  (write! "flows/t/typos.edn"
          (pr-str {:steps [{:id :a :kind :sql :conn :db :sql "select 1 as x" :optinal true}]
                   :output {:selct [:x]}
                   :endpoint {:path "/t" :scops #{"read"}}}))
  (let [m (messages (reload))]
    (is (re-find #"optinal" m))
    (is (re-find #"selct" m))
    (is (re-find #"scops" m))))

(deftest format-version-and-json-path
  (write! "flows/t/fmt.edn" (pr-str {:spindle/format 2 :steps [{:id :a :kind :sql :conn :db :sql "select 1 as x"}]}))
  (write! "flows/t/jp.edn" (pr-str {:steps [{:id :a :kind :api :conn :api :request {:path "/x"} :rows "$.a[0]"}]}))
  (let [m (messages (reload))]
    (is (re-find #"format.*should be 1" m))
    (is (re-find #"unsupported JSON path \$\.a\[0\]" m))))

(deftest reserved-step-ids
  (write! "flows/t/res.edn" (pr-str {:steps [{:id :sum :kind :sql :conn :db :sql "select 1 as x"}]}))
  (is (re-find #"step id :sum is reserved" (messages (reload)))))

(deftest row-cap-precedence
  (write! "flows/t/cap.edn" (pr-str {:limits {:max-rows 10}
                                     :steps [{:id :a :kind :sql :conn :db :sql "select 1 as x"}
                                             {:id :b :kind :sql :conn :db :sql "select 1 as x" :tuning {:max-rows 3}}]}))
  (is (:ok (reload)))
  (let [steps (get-in (config/current) [:flows "t/cap" :steps])]
    (is (= 10 (get-in steps [:a :max-rows])) "the flow's limit beats the connection default")
    (is (= 3 (get-in steps [:b :max-rows])) "the step beats the flow")))

(deftest a-misspelled-column-fails-instead-of-returning-nothing
  (write! "flows/t/col.edn"
          (pr-str {:steps [{:id :c :kind :sql :conn :db :sql "select id from customers where id = 'C-1'"}
                           {:id :p :kind :sql :conn :db :in {:ids [:c :idd]} :sql "select * from prices where cid in :ids"}]
                   :output {:from :p}
                   :endpoint {:path "/col"}}))
  (is (:ok (reload)))
  (let [r (call "/col")]
    (is (= 500 (:status r)))
    (is (re-find #"has no column :idd" (get-in r [:json :error :message])))))

(deftest merge-with-several-matches
  (write! "flows/t/merge.edn"
          (pr-str {:steps [{:id :c :kind :sql :conn :db :sql "select id from customers where id = 'C-1'"}
                           {:id :p :kind :sql :conn :db :in {:ids [:c :id]} :sql "select cid, price from prices where cid in :ids order by price"}]
                   :output {:from :c :merge {:from :p :on {:id :cid}}}
                   :endpoint {:path "/merge"}}))
  (write! "flows/t/pick.edn"
          (pr-str {:steps [{:id :c :kind :sql :conn :db :sql "select id from customers where id = 'C-1'"}
                           {:id :p :kind :sql :conn :db :in {:ids [:c :id]} :sql "select cid, price from prices where cid in :ids order by price"}]
                   :output {:from :c :merge {:from :p :on {:id :cid} :pick :first}}
                   :endpoint {:path "/pick"}}))
  (is (:ok (reload)))
  (is (re-find #"found 2 rows" (get-in (call "/merge") [:json :error :message])))
  (is (= 1 (get-in (call "/pick") [:json :rows 0 :price]))))

(deftest when-in-the-op-language
  (write! "flows/t/when.edn"
          (pr-str {:params '[:map [:id string?]]
                   :steps [{:id :c :kind :sql :conn :db :in {:id [:params :id]}
                            :sql "select id, status, qty from customers where id = :id"}
                           {:id :p :kind :sql :conn :db :in {:ids [:c :id]}
                            :when [:all [:exists [:c]] [:= [:c :status] "A"] [:> [:c :qty] 0]]
                            :default [{:cid "none"}]
                            :sql "select cid, price from prices where cid in :ids"}]
                   :output {:from :p}
                   :endpoint {:path "/when/{id}"}}))
  (is (:ok (reload)) (pr-str (:problems (reload))))
  (is (= 2 (count (get-in (call "/when/C-1") [:json :rows]))) "all conditions pass → the step runs")
  (is (= [{:cid "none"}] (get-in (call "/when/C-2") [:json :rows])) "status X → skipped, :default")
  (is (= [{:cid "none"}] (get-in (call "/when/NOPE") [:json :rows])) "no customer → skipped, :default")
  (testing "the map form still works"
    (write! "flows/t/when-old.edn"
            (pr-str {:steps [{:id :c :kind :sql :conn :db :sql "select id from customers"}
                             {:id :p :kind :sql :conn :db :in {:ids [:c :id]} :when {:exists [:c]}
                              :sql "select cid from prices where cid in :ids"}]}))
    (is (:ok (reload))))
  (testing "a bad condition is a load error"
    (write! "flows/t/when-bad.edn"
            (pr-str {:steps [{:id :c :kind :sql :conn :db :sql "select id from customers"}
                             {:id :p :kind :sql :conn :db :when [:exists :c] :sql "select 1 as x"}]}))
    (is (re-find #":exists takes one data path" (messages (reload))))))

(deftest skipped-for-empty-input-returns-the-default
  (write! "flows/t/skip.edn"
          (pr-str {:steps [{:id :c :kind :sql :conn :db :sql "select id from customers where id = 'none'"}
                           {:id :p :kind :sql :conn :db :in {:ids [:c :id]} :default [{:cid "-"}]
                            :sql "select cid from prices where cid in :ids"}]
                   :output {:from :p}
                   :endpoint {:path "/skip"}}))
  (is (:ok (reload)))
  (is (= [{:cid "-"}] (get-in (call "/skip") [:json :rows]))))

(deftest views-share-steps-and-one-upstream-call
  (write! "flows/orders/order.edn"
          (pr-str {:params '[:map [:id pos-int?]]
                   :steps [{:id :order :kind :api :conn :api
                            :request {:path "/orders/{id}" :path-params {:id [:params :id]}}
                            :cache {:ttl "30s"}}
                           {:id :cust :kind :sql :conn :db :sql "select id, status from customers where id = 'C-1'"}]
                   :views {:header {:output {:from :order :one? true :merge {:from :cust :on {}}}
                                    :endpoint {:path "/orders/{id}/header"}}
                           :lines {:output {:from :order}
                                   :endpoint {:path "/orders/{id}/lines"}}}}))
  (is (:ok (reload)) (messages (reload)))
  (let [flows (:flows (config/current))]
    (is (contains? flows "orders/order/header"))
    (is (= #{:order} (set (keys (get-in flows ["orders/order/lines" :steps])))) "the lines view does not run :cust"))
  (is (= 200 (:status (call "/orders/7/header"))))
  (is (= 200 (:status (call "/orders/7/lines"))))
  (is (= 1 @calls) "both views share one upstream call")
  (testing "a view flow may not also have a top-level output"
    (write! "flows/orders/bad.edn"
            (pr-str {:steps [{:id :o :kind :sql :conn :db :sql "select 1 as x"}]
                     :output {:from :o}
                     :views {:a {:endpoint {:path "/a"}}}}))
    (is (re-find #"inside each view" (messages (reload))))))
