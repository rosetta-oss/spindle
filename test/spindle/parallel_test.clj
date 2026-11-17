(ns spindle.parallel-test
  "Parallelism inside one request: independent steps overlap; fan-out calls run
   up to :concurrency at once, in order; a failure stops the rest."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [ring.adapter.jetty9 :as jetty]
            [spindle.auth :as auth]
            [spindle.config :as config]
            [spindle.explain]
            [spindle.par :as par]
            [spindle.server :as server]
            [spindle.stepcache :as stepcache]))

(def in-flight (atom 0))
(def peak (atom 0))

(defonce stub
  (delay (jetty/run-jetty
          (fn [req]
            (let [n (swap! in-flight inc)]
              (swap! peak max n)
              (try (Thread/sleep 300)
                   (let [id (subs (:uri req) 7)]
                     (if (= id "bad")
                       {:status 500 :body "x"}
                       {:status 200 :headers {"Content-Type" "application/json"}
                        :body (json/write-value-as-string {:id id :ok true})}))
                   (finally (swap! in-flight dec)))))
          {:port 0 :join? false :virtual-threads? true})))

(defn- url [] (str "http://127.0.0.1:" (.getLocalPort ^org.eclipse.jetty.server.ServerConnector
                                                    (first (.getConnectors ^org.eclipse.jetty.server.Server @stub)))))

(def ^:dynamic *dir* nil)
(defn- write! [rel text] (let [f (io/file *dir* rel)] (io/make-parents f) (spit f text)))

(use-fixtures :each
  (fn [t]
    (let [root (doto (io/file (System/getProperty "java.io.tmpdir") (str "spindle-par-" (System/nanoTime))) (.mkdirs))
          db (str (io/file root "p.db"))]
      (jdbc/execute! {:jdbcUrl (str "jdbc:sqlite:" db)} ["create table ids (id text)"])
      (jdbc/execute! {:jdbcUrl (str "jdbc:sqlite:" db)} ["insert into ids values ('a'),('b'),('c'),('d'),('e'),('f')"])
      (reset! config/state {:snapshot nil :history [] :last-reload nil :pinned false})
      (reset! peak 0) (stepcache/clear!)
      (binding [*dir* (io/file root "config")
                spindle.explain/*archive-dir* (str (io/file root "explain"))]
        (write! "connections.edn" (pr-str {:db {:type :sql :dialect :sqlite :jdbc-url (str "jdbc:sqlite:" db)
                                                :defaults {:timeout "2s" :max-rows 100}}
                                           :api {:type :http :base-url (url) :defaults {:timeout "3s"}
                                                 :guard {:concurrency 16}}
                                           :api2 {:type :http :base-url (url) :defaults {:timeout "3s"}}}))
        (write! "auth.edn" (pr-str {:api-keys [{:id "svc" :sha256 (auth/sha256-hex "k") :scopes #{"read"}}]}))
        (write! "flows/p/fan.edn"
                (pr-str {:steps [{:id :ids :kind :sql :conn :db :sql "select id from ids order by id"}
                                 {:id :items :kind :api :conn :api :fan-out [:ids :id] :concurrency 3
                                  :request {:path "/items/{id}" :path-params {:id [:fan]}}}]
                         :output {:from :items}
                         :limits {:timeout "5s"}
                         :endpoint {:path "/fan"}}))
        (write! "flows/p/two.edn"
                (pr-str {:steps [{:id :x :kind :api :conn :api :request {:path "/items/x"}}
                                 {:id :y :kind :api :conn :api2 :request {:path "/items/y"}}]
                         :output {:from :x :merge {:from :y :on {} :select []}}
                         :endpoint {:path "/two"}}))
        (let [r (config/reload! (str *dir*))] (is (:ok r) (pr-str r)))
        (t)))))

(defn- call [uri]
  (let [h (server/handler {:node-id "t" :config-dir (str *dir*)})
        t0 (System/currentTimeMillis)
        r (h {:request-method :get :uri uri :headers {"authorization" "Bearer k"}})]
    (assoc r :ms (- (System/currentTimeMillis) t0)
           :json (json/read-value (:body r) (json/object-mapper {:decode-key-fn true})))))

(deftest fan-out-runs-in-parallel-in-order
  (let [r (call "/fan")]
    (is (= 200 (:status r)) (pr-str (:json r)))
    (is (= ["a" "b" "c" "d" "e" "f"] (map :id (get-in r [:json :rows]))) "results keep the input order")
    (is (= 3 @peak) "at most :concurrency calls at once")
    (is (< (:ms r) 1300) "6 calls × 300 ms in about 2 rounds, not 1.8 s")))

(deftest steps-on-different-connections-overlap
  (let [r (call "/two")]
    (is (= 200 (:status r)) (pr-str (:json r)))
    (is (< (:ms r) 550) "two 300 ms calls at the same time")))

(deftest bounded-map-stops-on-failure
  (let [started (atom 0)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom"
                          (par/bounded-map 1 (fn [x] (swap! started inc) (when (= x 2) (throw (ex-info "boom" {}))) x)
                                           [1 2 3 4])))
    (is (= 2 @started) "with n = 1 the later items never start")))
