(ns spindle.pg-rw-test
  "PostgreSQL tests that need a database Spindle may create objects in:
   stored functions and procedures (:proc). Runs only when SPINDLE_IT_PG_URL,
   SPINDLE_IT_PG_USER and SPINDLE_IT_PG_PASSWORD are set; use a throwaway
   database. Everything lives in schema spindle_it, recreated per run."
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

(defn- env [k] (System/getenv k))
(defn- enabled? [] (some? (env "SPINDLE_IT_PG_URL")))
(defn- ds [] {:jdbcUrl (env "SPINDLE_IT_PG_URL") :user (env "SPINDLE_IT_PG_USER") :password (env "SPINDLE_IT_PG_PASSWORD")})

(def schema-sql
  ["drop schema if exists spindle_it cascade"
   "create schema spindle_it"
   "create table spindle_it.orders (id int primary key, customer text not null, total numeric(12,2) not null, comment text)"
   "create table spindle_it.lines (order_id int references spindle_it.orders(id), line int, sku text, qty int, price numeric(12,2), primary key (order_id, line))"
   "insert into spindle_it.orders values (1, 'C-1', 12.50, null), (2, 'C-2', 3.00, null)"
   "insert into spindle_it.lines values (1, 1, 'A', 2, 2.50), (1, 2, 'B', 1, 7.50), (2, 1, 'C', 3, 1.00)"
   ;; a set-returning function: one result set
   "create function spindle_it.order_lines(p_id int) returns table (order_id int, line int, sku text, qty int, price numeric)
      language sql stable as $$ select order_id, line, sku, qty, price from spindle_it.lines where order_id = p_id order by line $$"
   ;; OUT parameters
   "create function spindle_it.order_stats(p_id int, out n int, out amount numeric)
      language sql stable as $$ select count(*)::int, coalesce(sum(qty * price), 0) from spindle_it.lines where order_id = p_id $$"
   ;; a return value used as the status
   "create function spindle_it.order_check(p_id int) returns int
      language sql stable as $$ select case when exists (select 1 from spindle_it.orders where id = p_id) then 0 else 1 end $$"
   ;; several row sets through refcursors
   "create function spindle_it.order_bundle(p_id int, out hdr refcursor, out lns refcursor) language plpgsql as $$
      begin
        open hdr for select id, customer, total from spindle_it.orders where id = p_id;
        open lns for select order_id, line, sku from spindle_it.lines where order_id = p_id order by line;
      end $$"
   ;; slow, for cancellation
   "create function spindle_it.slow(p_s int) returns int language sql as $$ select 1 from pg_sleep(p_s) $$"])

(def ^:dynamic *dir* nil)
(def k "it-key")

(defn- write! [rel text] (let [f (io/file *dir* rel)] (io/make-parents f) (spit f text)))

(use-fixtures :once
  (fn [t]
    (when (enabled?)
      (let [d (ds)] (doseq [s schema-sql] (jdbc/execute! d [s])))
      (t))))

(use-fixtures :each
  (fn [t]
    (let [root (doto (io/file (System/getProperty "java.io.tmpdir") (str "spindle-pgrw-" (System/nanoTime))) (.mkdirs))]
      (reset! config/state {:snapshot nil :history [] :last-reload nil :pinned false})
      (stepcache/clear!)
      (binding [*dir* (io/file root "config")
                spindle.explain/*archive-dir* (str (io/file root "explain"))]
        (write! "connections.edn"
                (pr-str {:pg {:type :sql :dialect :postgres :jdbc-url "${SPINDLE_IT_PG_URL}"
                              :username "${SPINDLE_IT_PG_USER}" :password "${SPINDLE_IT_PG_PASSWORD}"
                              :defaults {:timeout "3s" :max-rows 1000}}}))
        (write! "auth.edn" (pr-str {:api-keys [{:id "it" :sha256 (auth/sha256-hex k)
                                                :scopes #{"read" "write" "spindle:admin" "spindle:trace"}}]}))
        (t)))))

(defn- reload [] (config/reload! (str *dir*)))

(defn- call [method uri & [{:keys [body headers]}]]
  (let [h (server/handler {:node-id "it" :config-dir (str *dir*)})
        [path qs] (str/split uri #"\?" 2)
        r (h (cond-> {:request-method method :uri path :query-string qs
                      :headers (merge {"authorization" (str "Bearer " k)} headers)}
               body (assoc :body (java.io.ByteArrayInputStream. (.getBytes (json/write-value-as-string body) "UTF-8")))))]
    (assoc r :json (json/read-value (:body r) (json/object-mapper {:decode-key-fn true})))))

;; ---------------------------------------------------------------------------
;; :proc
;; ---------------------------------------------------------------------------

(deftest proc-steps
  (when (enabled?)
    (write! "flows/it/lines.edn"
            (pr-str {:params '[:map [:id pos-int?]]
                     :steps [{:id :lines :kind :proc :conn :pg :read-only true
                              :call "spindle_it.order_lines" :args [:id] :in {:id [:params :id]}}]
                     :endpoint {:path "/it/orders/{id}/lines"}}))
    (write! "flows/it/stats.edn"
            (pr-str {:params '[:map [:id pos-int?]]
                     :steps [{:id :stats :kind :proc :conn :pg :read-only true :call "spindle_it.order_stats"
                              :args [:id {:out :n :type :integer} {:out :amount :type :numeric}]
                              :in {:id [:params :id]}}]
                     :output {:from :stats :one? true}
                     :endpoint {:path "/it/orders/{id}/stats"}}))
    (write! "flows/it/checked.edn"
            (pr-str {:params '[:map [:id pos-int?]]
                     :steps [{:id :chk :kind :proc :conn :pg :read-only true :call "spindle_it.order_check"
                              :args [:id] :in {:id [:params :id]} :status :status :fail-on-status :non-zero}]
                     :output {:from :chk :one? true}
                     :endpoint {:path "/it/orders/{id}/check"}}))
    (write! "flows/it/bundle.edn"
            (pr-str {:params '[:map [:id pos-int?]]
                     :steps [{:id :b :kind :proc :conn :pg :read-only true :call "spindle_it.order_bundle"
                              :args [:id {:out :hdr :type :cursor} {:out :lns :type :cursor}]
                              :in {:id [:params :id]}}
                             ;; a step reading a provided row set
                             {:id :skus :kind :sql :conn :pg :in {:skus [:lns :sku]}
                              :sql "select sku, count(*) as n from spindle_it.lines where sku in :skus group by sku order by sku"}]
                     :output {:from :hdr :one? true
                              :nest {:lines {:from :lns :on {:id :order_id} :select [:line :sku]}}
                              :derive [[:line_count [:count :lines]]]}
                     :endpoint {:path "/it/orders/{id}/bundle"}}))
    (write! "flows/it/slow.edn"
            (pr-str {:steps [{:id :s :kind :proc :conn :pg :read-only true :call "spindle_it.slow"
                              :args [:secs] :in {:secs [:params :secs]} :tuning {:timeout "1s"}}]
                     :params '[:map [:secs pos-int?]]
                     :endpoint {:path "/it/slow"}}))
    (let [r (reload)] (is (:ok r) (pr-str r)))
    (testing "a set-returning function is one result set"
      (is (= ["A" "B"] (map :sku (get-in (call :get "/it/orders/1/lines") [:json :rows])))))
    (testing "OUT parameters become one row"
      (let [row (get-in (call :get "/it/orders/1/stats") [:json :rows])]
        (is (= 2 (:n row)))
        (is (= 12.5 (double (:amount row))))))
    (testing "return status: 0 passes, non-zero fails with :fail-on-status"
      (is (= {:status 0} (get-in (call :get "/it/orders/1/check") [:json :rows])))
      (let [r (call :get "/it/orders/99/check")]
        (is (= 502 (:status r)))
        (is (re-find #"status 1" (get-in r [:json :error :message])))))
    (testing "cursor OUTs are named row sets that later steps and the output read"
      (let [r (call :get "/it/orders/1/bundle?trace=true")]
        (is (= 200 (:status r)) (pr-str (:json r)))
        (is (= "C-1" (get-in r [:json :rows :customer])))
        (is (= [{:line 1 :sku "A"} {:line 2 :sku "B"}] (get-in r [:json :rows :lines])))
        (is (= 2 (get-in r [:json :rows :line_count])))
        (is (= ["b" "skus"] (map :step (get-in r [:json :meta :trace]))))))
    (testing "the step timeout cancels the call"
      (let [t0 (System/currentTimeMillis) r (call :get "/it/slow?secs=10")]
        (is (= 504 (:status r)))
        (is (< (- (System/currentTimeMillis) t0) 4000))))))
