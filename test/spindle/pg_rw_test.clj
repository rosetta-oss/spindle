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

;; ---------------------------------------------------------------------------
;; writes (mutation endpoints)
;; ---------------------------------------------------------------------------

(def write-schema-sql
  ["create table if not exists spindle_it.spindle_idempotency (endpoint varchar(200) not null, idem_key varchar(128) not null,
      request_hash char(64) not null, response text not null, created_at timestamp not null, primary key (endpoint, idem_key))"
   "create table if not exists spindle_it.spindle_audit (at timestamp not null, request_id varchar(64), endpoint varchar(200),
      flow_version varchar(32), client varchar(128), sub varchar(256), idem_key varchar(128), conns varchar(400),
      affected text, outcome varchar(16), ms int, params_hash char(16))"
   "create procedure spindle_it.add_line(p_order int, p_sku text, p_qty int, p_price numeric)
      language sql as $$ insert into spindle_it.lines select p_order, coalesce(max(line), 0) + 1, p_sku, p_qty, p_price
                          from spindle_it.lines where order_id = p_order $$"])

(defn- q1 [sql & args] (first (jdbc/execute! (ds) (into [sql] args))))

(defn- mutation-flows! []
  (write! "flows/it/comment.edn"
          (pr-str {:params '[:map [:id pos-int?] [:comment [:string {:max 100}]]]
                   :steps [{:id :upd :kind :sql :conn :pg :in {:id [:params :id] :comment [:params :comment]}
                            :expect {:rows 1}
                            :sql "update spindle_it.orders set comment = :comment where id = :id"}
                           ;; a read on the same connection sees the write (same transaction)
                           {:id :order :kind :sql :conn :pg :in {:id [:params :id]} :after [:upd]
                            :sql "select id, comment from spindle_it.orders where id = :id"}]
                   :output {:from :order :one? true}
                   :endpoint {:path "/it/orders/{id}/comment" :method :put :mutation true :scopes #{"write"}
                              :idempotency {:conn :pg :table "spindle_it.spindle_idempotency"}
                              :audit {:conn :pg :table "spindle_it.spindle_audit"}
                              :evicts ["orders"]}}))
  (write! "flows/it/two-writes.edn"
          (pr-str {:params '[:map [:id pos-int?] [:sku string?] [:qty pos-int?] [:price number?] [:fail {:optional true} boolean?]]
                   :steps [{:id :add :kind :proc :conn :pg :call "spindle_it.add_line" :procedure true
                            :args [:order :sku :qty :price]
                            :in {:order [:params :id] :sku [:params :sku] :qty [:params :qty] :price [:params :price]}}
                           {:id :total :kind :sql :conn :pg :in {:id [:params :id]} :expect {:rows 1} :after [:add]
                            :sql "update spindle_it.orders set total = (select sum(qty * price) from spindle_it.lines where order_id = :id) where id = :id returning id, total"}
                           ;; fails after both writes when asked: everything rolls back
                           {:id :boom :kind :sql :conn :pg :in {:f [:params :fail]}
                            :when [:= [:params :fail] true] :after [:total]
                            :sql "select 1 / (cast(:f as int) - 1) as x"}]
                   :output {:from :total :one? true}
                   :endpoint {:path "/it/orders/{id}/lines" :method :post :mutation true :scopes #{"write"}}}))
  ;; a cached read that the comment mutation evicts
  (write! "flows/it/cached.edn"
          (pr-str {:params '[:map [:id pos-int?]]
                   :steps [{:id :o :kind :sql :conn :pg :in {:id [:params :id]} :cache {:ttl "60s" :tags [:orders]}
                            :sql "select id, comment from spindle_it.orders where id = :id"}]
                   :output {:from :o :one? true}
                   :endpoint {:path "/it/orders/{id}/cached"}})))

(deftest mutations
  (when (enabled?)
    (doseq [s write-schema-sql] (try (jdbc/execute! (ds) [s]) (catch Exception _ nil)))
    (mutation-flows!)
    (let [r (reload)] (is (:ok r) (pr-str r)))
    (testing "a write and a read in one transaction; committed; audited"
      (is (nil? (get-in (call :get "/it/orders/1/cached") [:json :rows :comment])) "cache filled before the write")
      (let [r (call :put "/it/orders/1/comment" {:body {:comment "rush"} :headers {"idempotency-key" "k-1"}})]
        (is (= 200 (:status r)) (pr-str (:json r)))
        (is (= {:id 1 :comment "rush"} (get-in r [:json :rows])) "the read saw the write")
        (is (= "committed" (get-in r [:json :meta :outcome])))
        (is (= {:upd 1} (get-in r [:json :meta :affected]))))
      (is (= "rush" (:orders/comment (q1 "select comment from spindle_it.orders where id = 1"))))
      (is (= "committed" (:spindle_audit/outcome (q1 "select outcome from spindle_it.spindle_audit where idem_key = 'k-1'")))))
    (testing ":evicts cleared the tagged cache"
      (is (= "rush" (get-in (call :get "/it/orders/1/cached") [:json :rows :comment]))))
    (testing "idempotency: same key and request → stored response, not run again"
      (jdbc/execute! (ds) ["update spindle_it.orders set comment = 'changed elsewhere' where id = 1"])
      (let [r (call :put "/it/orders/1/comment" {:body {:comment "rush"} :headers {"idempotency-key" "k-1"}})]
        (is (= 200 (:status r)))
        (is (true? (get-in r [:json :meta :idempotent_replay])))
        (is (= "changed elsewhere" (:orders/comment (q1 "select comment from spindle_it.orders where id = 1"))))))
    (testing "same key, different request → 409"
      (is (= 409 (:status (call :put "/it/orders/1/comment" {:body {:comment "other"} :headers {"idempotency-key" "k-1"}})))))
    (testing ":expect: no row matched → 404, rolled back"
      (let [r (call :put "/it/orders/999/comment" {:body {:comment "x"}})]
        (is (= 404 (:status r)))
        (is (= "rolled_back" (get-in r [:json :error :outcome])))))
    (testing "a procedure and an update commit together"
      (let [r (call :post "/it/orders/2/lines" {:body {:sku "Z" :qty 2 :price 5}})]
        (is (= 200 (:status r)) (pr-str (:json r)))
        (is (= 13.0 (double (get-in r [:json :rows :total]))))
        (is (= 2 (:count (q1 "select count(*) from spindle_it.lines where order_id = 2"))))))
    (testing "a failure after the writes rolls everything back"
      (let [before (q1 "select total from spindle_it.orders where id = 2")
            r (call :post "/it/orders/2/lines" {:body {:sku "Q" :qty 1 :price 1 :fail true}})]
        (is (= 502 (:status r)))
        (is (= "rolled_back" (get-in r [:json :error :outcome])))
        (is (= 2 (:count (q1 "select count(*) from spindle_it.lines where order_id = 2"))) "the procedure's insert was rolled back")
        (is (= before (q1 "select total from spindle_it.orders where id = 2")))))
    (testing "a trial run (no commit) never writes"
      (let [snap (config/current)
            fl (get-in snap [:flows "it/comment"])
            r (spindle.flow/execute snap fl {:id 1 :comment "trial"} {:caller {:client "it"}})]
        (is (= :rolled_back (:outcome r)))
        (is (= {:id 1 :comment "trial"} (:rows r)) "the trial sees its own write")
        (is (= "changed elsewhere" (:orders/comment (q1 "select comment from spindle_it.orders where id = 1"))))))))
