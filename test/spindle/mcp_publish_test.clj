(ns spindle.mcp-publish-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [shuttle.client :as mc]
            [spindle.config :as config]
            [spindle.explain]
            [spindle.server :as server]
            [spindle.stepcache :as stepcache]
            [spindle.tokens :as tokens]))

(def ^:dynamic *env* nil)

(defn- write! [dir rel text]
  (let [f (io/file dir rel)] (io/make-parents f) (spit f text)))

(defn- flow-edn [path]
  (pr-str {:params '[:map [:id [:vector {:min 1} pos-int?]]]
           :examples [{:id [1]}]
           :steps [{:id :orders :kind :sql :conn :shop :in {:id [:params :id]}}]
           :output {:from :orders}
           :endpoint {:path path :scopes #{"sales:read"}}}))
(def ^:private flow-sql "-- :name orders\nselect id, customer from orders where id in :id")

(defn- flow-bytes [cfg]
  (into {} (for [^java.io.File f (file-seq (io/file cfg "flows")) :when (.isFile f)]
             [(str f) (vec (java.nio.file.Files/readAllBytes (.toPath f)))])))

(use-fixtures :each
  (fn [t]
    (let [root (doto (io/file (System/getProperty "java.io.tmpdir") (str "spindle-publish-test-" (System/nanoTime))) (.mkdirs))
          cfg (io/file root "config")
          url (str "jdbc:sqlite:" (io/file root "shop.db"))]
      (jdbc/execute! {:jdbcUrl url} ["create table orders (id integer primary key, customer text not null)"])
      (jdbc/execute! {:jdbcUrl url} ["insert into orders values (1,'a'),(2,'b')"])
      (write! cfg "connections.edn"
              (pr-str {:shop {:type :sql :dialect :sqlite :jdbc-url url
                              :pool {:max 2} :defaults {:timeout "2s" :max-rows 1000}}}))
      (write! cfg "auth.edn" (pr-str {:api-keys [] :admin-scope "ops:admin"}))
      (write! cfg "flows/shop/orders.edn" (flow-edn "/shop/orders"))
      (write! cfg "flows/shop/orders.sql" flow-sql)
      (reset! config/state {:snapshot nil :history [] :last-reload nil :pinned false})
      (stepcache/clear!)
      (binding [spindle.explain/*archive-dir* (str (io/file root "explain"))]
        (is (:ok (config/reload! (str cfg))))
        (let [store (tokens/open-sqlite (str (io/file root "tokens.db")) (str cfg) {})
              srv (server/start! {:port 0 :node-id "t" :config-dir (str cfg) :store store})
              port (.getLocalPort ^org.eclipse.jetty.server.ServerConnector (first (.getConnectors ^org.eclipse.jetty.server.Server srv)))]
          (try
            (binding [*env* {:base (str "http://127.0.0.1:" port) :url (str "http://127.0.0.1:" port "/mcp") :cfg cfg
                             :author (:token (tokens/issue! store {:id "author" :scopes ["spindle:author"] :created-by "test"}))
                             :reader (:token (tokens/issue! store {:id "reader" :scopes ["sales:read"] :created-by "test"}))}]
              (t))
            (finally (.stop ^org.eclipse.jetty.server.Server srv))))))))

(defn- call [tool args]
  (let [c (mc/connect {:url (:url *env*) :bearer (:author *env*)})]
    (try (mc/call-tool c tool args) (finally (mc/close c)))))

(defn- data [r] (json/read-value (mc/text r) (json/object-mapper {:decode-key-fn true})))

(defn- get-endpoint [path]
  (let [req (-> (java.net.http.HttpRequest/newBuilder (java.net.URI. (str (:base *env*) path "?id=1")))
                (.header "Authorization" (str "Bearer " (:reader *env*))) (.build))]
    (.statusCode (.send (java.net.http.HttpClient/newHttpClient) req (java.net.http.HttpResponse$BodyHandlers/ofString)))))

(deftest publish-new-endpoint
  (let [v (:version (config/current))
        r (data (call "publish" {:id "it/hello" :edn (flow-edn "/it/hello") :sql flow-sql}))]
    (is (string? (:version r)))
    (is (not= v (:version r)))
    (is (= (:version r) (:version (config/current))))
    (is (.exists (io/file (:cfg *env*) "flows/it/hello.edn")))
    (is (= 200 (get-endpoint "/it/hello")))))

(deftest publish-restores-on-failure
  (let [before (flow-bytes (:cfg *env*))
        v (:version (config/current))
        ;; duplicate path: passes nothing new, fails in validation/reload
        r (data (call "publish" {:id "shop/orders" :edn (flow-edn "/shop/orders") :sql "-- :name orders\nselect * from nosuch where id in :id"}))
        r2 (data (call "publish" {:id "it/dup" :edn (flow-edn "/shop/orders") :sql flow-sql}))]
    (is (seq (:problems r)))
    (is (seq (:problems r2)))
    (is (= before (flow-bytes (:cfg *env*))))
    (is (= v (:version (config/current))))
    ;; reload itself fails after the files are written: overwrite + new file both restored
    (with-redefs [config/reload! (fn [& _] {:problems [{:message "boom"}]})]
      (doseq [[id path] [["shop/orders" "/shop/orders"] ["it/new" "/it/new"]]]
        (let [r (try (call "publish" {:id id :edn (str (flow-edn path) " ") :sql flow-sql}) (catch Exception e (ex-message e)))]
          (is (str/includes? (str r) "reload failed")))))
    (is (= before (flow-bytes (:cfg *env*))))
    (is (= v (:version (config/current))))))

(deftest publish-refused-when-pinned
  (let [v1 (:version (config/current))
        _ (is (:version (data (call "publish" {:id "it/a" :edn (flow-edn "/it/a") :sql flow-sql}))))
        _ (is (:ok (config/rollback! v1)))
        before (flow-bytes (:cfg *env*))
        r (try (call "publish" {:id "it/b" :edn (flow-edn "/it/b") :sql flow-sql}) (catch Exception e (ex-message e)))]
    ;; shuttle.client raises on tool errors; the message carries the JSON body
    (is (str/includes? (str r) "{\"error\":\"node is pinned\"}"))
    (is (= before (flow-bytes (:cfg *env*))))
    (is (= v1 (:version (config/current))))))

(deftest publish-and-reload-serialised
  (let [fs (doall (for [n ["p1" "p2" "p3"]]
                    (future (data (call "publish" {:id (str "it/" n) :edn (flow-edn (str "/it/" n)) :sql flow-sql})))))
        rl (future (config/with-write-lock (config/reload! (str (:cfg *env*)))))
        rs (mapv deref fs)]
    @rl
    (is (every? :version rs) (pr-str rs))
    (is (every? #(contains? (:flows (config/current)) %) ["it/p1" "it/p2" "it/p3"]))
    (is (= (config/tree-version (config/read-tree (str (:cfg *env*)))) (:version (config/current))))))

(deftest e2e-list-describe-validate-publish-call
  (let [conns (data (call "list_connections" {}))
        table (data (call "describe_table" {:conn "shop" :table "orders"}))
        valid (data (call "validate_flow" {:id "it/e2e" :edn (flow-edn "/it/e2e") :sql flow-sql}))
        pub (data (call "publish" {:id "it/e2e" :edn (flow-edn "/it/e2e") :sql flow-sql}))
        status (get-endpoint "/it/e2e")]
    (is (= "shop" (:id (first conns))))
    (is (= {:ok true} valid))
    (is (:version pub))
    (is (= 200 status))))
