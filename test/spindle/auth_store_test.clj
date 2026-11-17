(ns spindle.auth-store-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [spindle.auth :as auth]
            [spindle.config :as config]
            [spindle.explain]
            [spindle.server :as server]
            [spindle.tokens :as tokens]
            [spindle.web :as web]))

(def ^:dynamic *dir* nil)

(defn- write! [dir rel text]
  (let [f (io/file dir rel)] (io/make-parents f) (spit f text)))

(defn- base-config [dir db admin-scope]
  (write! dir "connections.edn"
          (pr-str {:shop {:type :sql :dialect :sqlite :jdbc-url (str "jdbc:sqlite:" db)
                          :pool {:max 2} :defaults {:timeout "2s" :max-rows 1000}
                          :guard {:concurrency 2 :breaker {:failure-ratio [3 5] :open "1s"}}}}))
  (write! dir "auth.edn" (pr-str {:api-keys [] :admin-scope admin-scope}))
  (write! dir "flows/shop/orders.edn"
          (pr-str {:params '[:map [:id [:vector {:min 1} pos-int?]]]
                   :steps [{:id :orders :kind :sql :conn :shop :in {:id [:params :id]}}]
                   :output {:from :orders}
                   :endpoint {:path "/shop/orders" :scopes #{"sales:read"}}}))
  (write! dir "flows/shop/orders.sql" "-- :name orders\nselect id, customer from orders where id in :id"))

(use-fixtures :each
  (fn [t]
    (let [dir (doto (io/file (System/getProperty "java.io.tmpdir") (str "spindle-auth-test-" (System/nanoTime))) (.mkdirs))
          db (str (io/file dir "shop.db"))]
      (jdbc/execute! {:jdbcUrl (str "jdbc:sqlite:" db)} ["create table orders (id integer primary key, customer text)"])
      (jdbc/execute! {:jdbcUrl (str "jdbc:sqlite:" db)} ["insert into orders values (1,'Ann')"])
      (base-config (io/file dir "config") db "ops:admin")
      (reset! config/state {:snapshot nil :history [] :last-reload nil :pinned false})
      (binding [*dir* (str (io/file dir "config"))
                spindle.explain/*archive-dir* (str (io/file dir "explain"))]
        (t)))))

(defn- open-store [] (tokens/open-sqlite (str (io/file *dir* ".." "tokens.db")) *dir*))

(defn- call [h uri tok & [qs method]]
  (let [r (h {:request-method (or method :get) :uri uri :query-string qs
              :headers (cond-> {} tok (assoc "authorization" (str "Bearer " tok)))})]
    (assoc r :json (json/read-value (:body r) (json/object-mapper {:decode-key-fn true})))))

(deftest store-token-authenticates
  (is (:ok (config/reload! *dir*)))
  (let [store (open-store)
        h (server/handler {:node-id "t" :config-dir *dir* :store store})
        {:keys [token]} (tokens/issue! store {:id "t1" :scopes ["sales:read"] :created-by "test"})]
    (is (= 200 (:status (call h "/shop/orders" token "id=1"))))
    (is (= 401 (:status (call h "/shop/orders" "nope" "id=1"))))
    (is (= 401 (:status (call (server/handler {:node-id "t" :config-dir *dir*}) "/shop/orders" token "id=1")))
        "without a store the token is unknown")))

(deftest store-admin-uses-configured-scope
  (is (:ok (config/reload! *dir*)))
  (let [store (open-store)
        h (server/handler {:node-id "t" :config-dir *dir* :store store})
        ops (:token (tokens/issue! store {:id "ops" :scopes ["ops:admin"] :created-by "test"}))
        def (:token (tokens/issue! store {:id "def" :scopes ["spindle:admin"] :created-by "test"}))]
    (is (not (contains? (get-in (call h "/status" ops) [:json :config :last_reload]) :problem_count)) "admin view")
    (is (contains? (get-in (call h "/status" def) [:json :config :last_reload]) :problem_count) "default scope is not admin here")
    (is (= 200 (:status (call h "/admin/config/unpin" ops nil :post))))
    (is (= 403 (:status (call h "/admin/config/unpin" def nil :post))))))

(deftest store-token-without-snapshot
  (let [store (open-store)
        {:keys [token]} (tokens/issue! store {:id "x" :scopes ["a"] :created-by "test"})]
    (is (= {:client "x" :id "x" :scopes #{"a"}}
           (auth/authenticate nil store {:headers {"authorization" (str "Bearer " token)}})))
    (is (nil? (auth/authenticate nil nil {:headers {"authorization" (str "Bearer " token)}})))))

(deftest secure-table
  (doseq [[req env ok?]
          [[{:remote-addr "127.0.0.1"} nil true]
           [{:remote-addr "0:0:0:0:0:0:0:1"} nil true]
           [{:remote-addr "::1"} nil true]
           [{:remote-addr "10.0.0.5" :scheme :https} nil true]
           [{:remote-addr "10.0.0.5" :scheme :http :headers {"x-forwarded-proto" "https"}} nil true]
           [{:remote-addr "10.0.0.5" :scheme :http :headers {"x-forwarded-proto" "https"}} "false" false]
           [{:remote-addr "10.0.0.5" :scheme :http :headers {"x-forwarded-proto" "http"}} nil false]
           [{:remote-addr "10.0.0.5" :scheme :http} nil false]]]
    (testing (pr-str req env)
      (is (= ok? (binding [web/*env-trust-forwarded* (constantly env)] (web/secure? req)))))))

(deftest insecure-resp-shape
  (let [r (web/insecure-resp)]
    (is (= 403 (:status r)))
    (is (= {:error {:code "https_required" :message "use HTTPS"}}
           (json/read-value (:body r) (json/object-mapper {:decode-key-fn true}))))))
