(ns spindle.scenarios-test
  "The first-version scenarios, end to end (SQLite + an in-process stub API):

   1. Projection: one heavy upstream call (header + details) backs two small
      endpoints; asked separately or at the same time, the upstream is called once.
   2. SQL then API, conditionally: the API step runs only when the SQL found rows;
      the output shape stays the same either way.
   3. Step cache: TTL per step, single-flight, stale served when the upstream fails.
   4. Caller identity: end-user identity reaches SQL parameters and upstream
      headers, and caller-dependent steps are cached per caller.
   5. Publishing checks: SQL steps need a timeout and a row cap; explain runs
      and is archived; a broken statement is rejected."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [ring.adapter.jetty9 :as jetty]
            [spindle.auth :as auth]
            [spindle.config :as config]
            [spindle.explain]
            [spindle.server :as server]
            [spindle.stepcache :as stepcache]))

(def ^:dynamic *dir* nil)
(def ^:dynamic *explain-dir* nil)
(def calls (atom []))
(def upstream-mode (atom :ok))
(def key-svc "svc-key")
(def key-plain "plain-key")

(defn- stub-handler [req]
  (swap! calls conj {:uri (:uri req) :user (get-in req [:headers "x-user-id"])})
  (cond
    (= @upstream-mode :fail) {:status 503 :body "down"}
    (re-matches #"/orders/\d+" (:uri req))
    (do (Thread/sleep 300)                              ; a heavy upstream
        (let [id (Long/parseLong (subs (:uri req) 8))]
          {:status 200 :headers {"Content-Type" "application/json"}
           :body (json/write-value-as-string
                  {:header {:order_id id :customer "C-7" :total 42.5}
                   :details [{:order_id id :line 1 :sku "A"} {:order_id id :line 2 :sku "B"}]})}))
    (re-matches #"/credit/.*" (:uri req))
    {:status 200 :headers {"Content-Type" "application/json"}
     :body (json/write-value-as-string {:customer_id (subs (:uri req) 8) :limit 1000
                                        :seen_by (get-in req [:headers "x-user-id"])})}
    :else {:status 404 :body "{}"}))

(defonce stub (delay (jetty/run-jetty stub-handler {:port 0 :join? false})))
(defn- stub-url [] (str "http://127.0.0.1:" (.getLocalPort ^org.eclipse.jetty.server.ServerConnector
                                                         (first (.getConnectors ^org.eclipse.jetty.server.Server @stub)))))

(defn- write! [rel text] (let [f (io/file *dir* rel)] (io/make-parents f) (spit f text)))

(defn- setup! [dir db]
  (binding [*dir* dir]
    ;; :api steps run on :http connections
    (write! "connections.edn"
            (pr-str {:db {:type :sql :dialect :sqlite :jdbc-url (str "jdbc:sqlite:" db)
                          :defaults {:timeout "2s" :max-rows 1000}}
                     :orders_api {:type :http :base-url (stub-url) :defaults {:timeout "2s"}
                                  :guard {:concurrency 8 :breaker {:failure-ratio [50 100] :open "1s"}}}
                     :credit_api {:type :http :base-url (stub-url) :defaults {:timeout "2s"}
                                  :forward-caller {"X-User-Id" [:caller :sub]}}}))
    (write! "auth.edn" (pr-str {:api-keys [{:id "svc" :sha256 (auth/sha256-hex key-svc)
                                            :scopes #{"read" "spindle:delegate" "spindle:admin"}}
                                           {:id "plain" :sha256 (auth/sha256-hex key-plain) :scopes #{"read"}}]}))
    ;; 1. projection: two endpoints over one heavy call
    (write! "flows/orders/header.edn"
            (pr-str {:params '[:map [:id pos-int?]]
                     :steps [{:id :order :kind :api :conn :orders_api
                              :request {:method :get :path "/orders/{id}" :path-params {:id [:params :id]}}
                              :rows "$.header" :cache {:ttl "30s" :stale-if-error "5m"}}]
                     :output {:from :order :one? true}
                     :endpoint {:path "/orders/{id}/header" :scopes #{"read"}}}))
    (write! "flows/orders/lines.edn"
            (pr-str {:params '[:map [:id pos-int?]]
                     :steps [{:id :order :kind :api :conn :orders_api
                              :request {:method :get :path "/orders/{id}" :path-params {:id [:params :id]}}
                              :rows "$.details[*]" :cache {:ttl "30s"}}]
                     :output {:from :order :select [:line :sku]}
                     :endpoint {:path "/orders/{id}/lines" :scopes #{"read"}}}))
    ;; 2. SQL, then the API only if the SQL found the customer
    (write! "flows/customers/with-credit.edn"
            (pr-str {:params '[:map [:id string?]]
                     :steps [{:id :cust :kind :sql :conn :db :in {:id [:params :id]}}
                             {:id :credit :kind :api :conn :credit_api :when {:exists [:cust]}
                              :request {:method :get :path "/credit/{id}" :path-params {:id [:cust :customer_id]}}
                              :rows "$"}]
                     :output {:from :cust :one? true
                              :nest {:credit {:from :credit :on {:customer_id :customer_id} :one? true
                                              :select [:limit]}}}
                     :endpoint {:path "/customers/{id}/with-credit" :scopes #{"read"}}}))
    (write! "flows/customers/with-credit.sql"
            "-- :name cust\nselect customer_id, name from customers where customer_id = :id")
    ;; 4. identity: claim into SQL, user forwarded upstream, per-caller cache
    (write! "flows/customers/mine.edn"
            (pr-str {:steps [{:id :mine :kind :sql :conn :db :in {:t [:caller :claims :territory]}
                              :cache {:ttl "30s"}}]
                     :endpoint {:path "/customers/mine" :scopes #{"read"} :caller :required}}))
    (write! "flows/customers/mine.sql"
            "-- :name mine\nselect customer_id, name from customers where territory = :t order by customer_id")))

(use-fixtures :each
  (fn [t]
    (let [dir (doto (io/file (System/getProperty "java.io.tmpdir") (str "spindle-sc-" (System/nanoTime))) (.mkdirs))
          db (str (io/file dir "s.db"))
          ds {:jdbcUrl (str "jdbc:sqlite:" db)}]
      (jdbc/execute! ds ["create table customers (customer_id text primary key, name text, territory integer)"])
      (jdbc/execute! ds ["insert into customers values ('C-1','Ann',1),('C-2','Bo',2),('C-3','Cy',1)"])
      (reset! calls []) (reset! upstream-mode :ok) (stepcache/clear!)
      (reset! config/state {:snapshot nil :history [] :last-reload nil :pinned false})
      (setup! (io/file dir "config") db)
      (binding [*dir* (io/file dir "config")
                *explain-dir* (str (io/file dir "explain"))
                spindle.explain/*archive-dir* (str (io/file dir "explain"))]
        (let [r (config/reload! (str *dir*))] (is (:ok r) (pr-str r)))
        (t)))))

(defn- call [uri & [{:keys [key user claims]}]]
  (let [h (server/handler {:node-id "t" :config-dir (str *dir*)})
        r (h {:request-method :get :uri uri
              :headers (cond-> {"authorization" (str "Bearer " (or key key-svc))}
                         user (assoc "x-spindle-user" user)
                         claims (assoc "x-spindle-user-claims" (auth/claims-header claims)))})]
    (assoc r :json (json/read-value (:body r) (json/object-mapper {:decode-key-fn true})))))

(defn- order-calls [] (count (filter #(re-matches #"/orders/.*" (:uri %)) @calls)))

(deftest projection-shares-one-upstream-call
  (testing "sequential: header then lines → one upstream call"
    (is (= {:order_id 7 :customer "C-7" :total 42.5} (get-in (call "/orders/7/header") [:json :rows])))
    (is (= [{:line 1 :sku "A"} {:line 2 :sku "B"}] (get-in (call "/orders/7/lines") [:json :rows])))
    (is (= 1 (order-calls))))
  (testing "concurrent: header and lines at the same time → single-flight, one call"
    (reset! calls [])
    (let [a (future (call "/orders/8/header")) b (future (call "/orders/8/lines"))]
      (is (= 200 (:status @a) (:status @b)))
      (is (= 1 (order-calls)))))
  (testing "per-step hit counts are visible in /status"
    (let [st (get-in (call "/status") [:json :step_cache])]
      (is (pos? (get-in st [(keyword "orders/lines#order") :hit] 0))))))

(deftest stale-when-upstream-fails
  (call "/orders/9/header")
  ;; age the entry past its TTL without waiting 30 s: rewrite the cached timestamp
  (let [c @@#'stepcache/store]
    (doseq [[k v] (.asMap ^com.github.benmanes.caffeine.cache.Cache c)]
      (.put ^com.github.benmanes.caffeine.cache.Cache c k (assoc v :at (- (System/currentTimeMillis) 60000))))
  (reset! upstream-mode :fail))
  (let [r (call "/orders/9/header")]
    (is (= 200 (:status r)) "served from the stale entry")
    (is (= 9 (get-in r [:json :rows :order_id]))))
  (is (= 503 (:status (call "/orders/99/header"))) "no entry → the failure is reported (upstream 503 → unavailable)"))

(deftest conditional-api-step
  (testing "customer found → API called"
    (let [r (call "/customers/C-1/with-credit")]
      (is (= {:customer_id "C-1" :name "Ann" :credit {:limit 1000}} (get-in r [:json :rows])))
      (is (= 1 (count (filter #(= "/credit/C-1" (:uri %)) @calls))))))
  (testing "customer not found → API not called, output stays nil"
    (reset! calls [])
    (let [r (call "/customers/NOPE/with-credit")]
      (is (= 200 (:status r)))
      (is (nil? (get-in r [:json :rows])))
      (is (empty? @calls)))))

(deftest caller-identity
  (testing "end user is required on this endpoint"
    (is (= 401 (:status (call "/customers/mine")))))
  (testing "a key without spindle:delegate may not act for users"
    (is (= 403 (:status (call "/customers/mine" {:key key-plain :user "u1" :claims {:territory 1}})))))
  (testing "claims reach SQL; results are cached per caller, never shared"
    (let [a (call "/customers/mine" {:user "alice" :claims {:territory 1}})
          b (call "/customers/mine" {:user "bob" :claims {:territory 2}})
          a2 (call "/customers/mine" {:user "alice" :claims {:territory 1}})]
      (is (= ["C-1" "C-3"] (map :customer_id (get-in a [:json :rows]))))
      (is (= ["C-2"] (map :customer_id (get-in b [:json :rows]))))
      (is (= (get-in a [:json :rows]) (get-in a2 [:json :rows])))))
  (testing "the connection forwards the end user upstream"
    (reset! calls [])
    (call "/customers/C-2/with-credit" {:user "carol"})
    (is (= "carol" (:user (first (filter #(= "/credit/C-2" (:uri %)) @calls)))))))

(deftest publishing-checks
  (testing "explain ran and was archived for each SQL step"
    (let [files (filter #(.isFile ^java.io.File %) (file-seq (io/file *explain-dir*)))]
      (is (= 2 (count files)))
      (is (re-find #"\"plan\"" (slurp (first files))))))
  (testing "an SQL step without a row cap is refused"
    (write! "connections.edn" (clojure.string/replace (slurp (io/file *dir* "connections.edn")) " :max-rows 1000" ""))
    (let [r (config/reload! (str *dir*))]
      (is (some #(re-find #"row cap" (:message %)) (:problems r)))))
  (testing "a statement the database rejects fails explain and the reload"
    (setup! *dir* (str (io/file *dir* "other.db")))
    (write! "flows/customers/broken.edn"
            (pr-str {:steps [{:id :b :kind :sql :conn :db :sql "select no_such_column from customers"}]}))
    (let [r (config/reload! (str *dir*))]
      (is (some #(re-find #"explain failed" (:message %)) (:problems r))))))
