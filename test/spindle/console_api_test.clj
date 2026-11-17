(ns spindle.console-api-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
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

(defn- base-config [dir db]
  (write! dir "connections.edn"
          (pr-str {:shop {:type :sql :dialect :sqlite :jdbc-url (str "jdbc:sqlite:" db)
                          :pool {:max 2} :defaults {:timeout "2s" :max-rows 1000}
                          :guard {:concurrency 2 :breaker {:failure-ratio [3 5] :open "1s"}}}}))
  (write! dir "auth.edn" (pr-str {:api-keys [{:id "semantic" :sha256 (auth/sha256-hex "cfg-secret") :scopes #{"sales:read"}}]
                                  :admin-scope "ops:admin"}))
  (write! dir "flows/shop/orders.edn"
          (pr-str {:params '[:map [:id [:vector {:min 1} pos-int?]]]
                   :steps [{:id :orders :kind :sql :conn :shop :in {:id [:params :id]}}]
                   :output {:from :orders}
                   :endpoint {:path "/shop/orders" :scopes #{"sales:read"}}}))
  (write! dir "flows/shop/orders.sql" "-- :name orders\nselect id, customer from orders where id in :id"))

(use-fixtures :each
  (fn [t]
    (let [dir (doto (io/file (System/getProperty "java.io.tmpdir") (str "spindle-console-test-" (System/nanoTime))) (.mkdirs))
          db (str (io/file dir "shop.db"))]
      (jdbc/execute! {:jdbcUrl (str "jdbc:sqlite:" db)} ["create table orders (id integer primary key, customer text)"])
      (jdbc/execute! {:jdbcUrl (str "jdbc:sqlite:" db)} ["insert into orders values (1,'Ann')"])
      (base-config (io/file dir "config") db)
      (reset! config/state {:snapshot nil :history [] :last-reload nil :pinned false})
      (binding [*dir* (str (io/file dir "config"))
                spindle.explain/*archive-dir* (str (io/file dir "explain"))]
        (t)))))

(def ^:private clock (atom 1000000))

(defn- open-store []
  (reset! clock 1000000)
  (tokens/open-sqlite (str (io/file *dir* ".." "tokens.db")) *dir* {:now-ms #(deref clock)}))

(defn- call [h method uri tok & {:keys [qs body remote headers] :or {remote "127.0.0.1"}}]
  (let [r (h {:request-method method :uri uri :query-string qs :remote-addr remote
              :body (when body (java.io.ByteArrayInputStream. (.getBytes (json/write-value-as-string body) "UTF-8")))
              :headers (cond-> (or headers {}) tok (assoc "authorization" (str "Bearer " tok)))})]
    (assoc r :json (json/read-value (:body r) (json/object-mapper {:decode-key-fn true})))))

(defn- setup []
  (is (:ok (config/reload! *dir*)))
  (let [store (open-store)
        h (server/handler {:node-id "t" :config-dir *dir* :store store})
        admin (:token (tokens/issue! store {:id "root" :scopes ["ops:admin"] :created-by "test"}))]
    {:store store :h h :admin admin}))

(deftest issue-list-revoke
  (let [{:keys [h admin]} (setup)
        r (call h :post "/admin/tokens" admin :body {:id "sem-1" :scopes ["sales:read"]})
        tok (get-in r [:json :token])]
    (is (= 201 (:status r)))
    (is (= "sem-1" (get-in r [:json :id])))
    (is (string? tok))
    (is (= 200 (:status (call h :get "/shop/orders" tok :qs "id=1"))))
    (let [l (call h :get "/admin/tokens" admin)
          row (some #(when (= "sem-1" (:id %)) %) (get-in l [:json :tokens]))]
      (is (= 200 (:status l)))
      (is row)
      (is (= "root" (:created_by row)))
      (is (not (re-find (re-pattern (java.util.regex.Pattern/quote tok)) (:body l))))
      (is (not (contains? row :token)))
      (is (not (contains? row :sha256)))
      (is (= [{:id "semantic" :scopes ["sales:read"] :read_only true}] (get-in l [:json :config_keys])))
      (is (= "ops:admin" (get-in l [:json :admin_scope]))))
    (is (= 404 (:status (call h :delete "/admin/tokens/semantic" admin))) "auth.edn ids are not revocable")
    (is (= 404 (:status (call h :delete "/admin/tokens/nope" admin))))
    (let [d (call h :delete "/admin/tokens/sem-1" admin)]
      (is (= 200 (:status d)))
      (is (= {:revoked true} (:json d))))
    (swap! clock + 5001)
    (is (= 401 (:status (call h :get "/shop/orders" tok :qs "id=1"))))))

(deftest issue-rejects-taken-ids
  (let [{:keys [h admin]} (setup)
        n (fn [] (count (get-in (call h :get "/admin/tokens" admin) [:json :tokens])))
        before (n)]
    (is (= 409 (:status (call h :post "/admin/tokens" admin :body {:id "semantic" :scopes ["x"]}))))
    (is (= "conflict" (get-in (call h :post "/admin/tokens" admin :body {:id "semantic" :scopes ["x"]}) [:json :error :code])))
    (is (= 201 (:status (call h :post "/admin/tokens" admin :body {:id "old-one" :scopes ["x"]}))))
    (is (= 200 (:status (call h :delete "/admin/tokens/old-one" admin))))
    (is (= 409 (:status (call h :post "/admin/tokens" admin :body {:id "old-one" :scopes ["x"]}))))
    (is (= (inc before) (n)) "only the first old-one is listed")
    (is (= 409 (:status (call h :post "/admin/tokens" admin :body {:id "root" :scopes ["x"]}))))))

(deftest issue-validates-input
  (let [{:keys [h admin]} (setup)]
    (doseq [b [{:scopes ["x"]} {:id "ok-id"} {:id "ok-id" :scopes []} {:id "Bad Id" :scopes ["x"]}
               {:id "a" :scopes ["x"]} {:id "-lead" :scopes ["x"]}]]
      (is (= 400 (:status (call h :post "/admin/tokens" admin :body b))) (pr-str b)))
    (is (= 400 (:status (h {:request-method :post :uri "/admin/tokens" :remote-addr "127.0.0.1"
                            :body (java.io.ByteArrayInputStream. (.getBytes "{not json"))
                            :headers {"authorization" (str "Bearer " admin)}}))))
    (let [r (call h :post "/admin/tokens" admin :body {:id "exp-1" :scopes ["x"] :expires_at "2099-01-01T00:00:00Z"})]
      (is (= 201 (:status r)))
      (is (= "2099-01-01T00:00:00Z" (get-in r [:json :expires_at]))))))

(deftest author-cannot-admin
  (let [{:keys [store h]} (setup)
        author (:token (tokens/issue! store {:id "auth1" :scopes ["spindle:author"] :created-by "test"}))
        v (:version (config/current))]
    (is (= 403 (:status (call h :post "/admin/reload" author))))
    (is (= 403 (:status (call h :get "/admin/tokens" author))))
    (is (= 403 (:status (call h :post "/admin/tokens" author :body {:id "evil" :scopes ["ops:admin"]}))))
    (is (= v (:version (config/current))))))

(deftest tokens-need-https
  (let [{:keys [h admin]} (setup)
        r (call h :get "/admin/tokens" admin :remote "10.0.0.5")]
    (is (= 403 (:status r)))
    (is (= "https_required" (get-in r [:json :error :code])))
    (is (= 403 (:status (call h :post "/admin/tokens" nil :remote "10.0.0.5" :body {:id "zz" :scopes ["x"]})))
        "https check precedes auth")
    (is (= 200 (:status (call h :get "/admin/tokens" admin :remote "10.0.0.5" :headers {"x-forwarded-proto" "https"}))))))

(deftest issue-rejects-garbage-expiry
  (let [{:keys [h admin]} (setup)]
    (is (= 400 (:status (call h :post "/admin/tokens" admin :body {:id "exp-2" :scopes ["x"] :expires_at "garbage"}))))))

(deftest revoke-route-is-strict
  (let [{:keys [h admin]} (setup)]
    (is (= 201 (:status (call h :post "/admin/tokens" admin :body {:id "rv-1" :scopes ["x"]}))))
    (is (= 404 (:status (call h :delete "/admin/tokensX" admin))))
    (is (= 404 (:status (call h :delete "/admin/tokens/Bad%20Id" admin))))
    (is (= 200 (:status (call h :delete "/admin/tokens/rv%2D1" admin))))))

(deftest forwarded-proto-normalised
  (is (web/secure? {:remote-addr "10.0.0.5" :headers {"x-forwarded-proto" "HTTPS, http"}}))
  (is (not (web/secure? {:remote-addr "10.0.0.5" :headers {"x-forwarded-proto" "http, https"}})))
  (is (not (web/secure? {:remote-addr "localhost"})))
  (is (web/secure? {:remote-addr "127.0.0.1"}))
  (is (web/secure? {:remote-addr "[::1]"})))
