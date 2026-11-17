(ns spindle.unit-test
  (:require [clojure.test :refer [deftest is testing]]
            [spindle.env :as env]
            [spindle.executor :as executor]
            [spindle.expr :as expr]
            [spindle.httpstep :as httpstep]
            [spindle.output :as output]
            [spindle.paths :as paths]
            [spindle.sql :as sql]
            [spindle.sqlfile :as sf]))

(deftest sql-blocks-and-placeholders
  (let [blocks (sf/parse-blocks "-- :name a\nselect 1\n\n-- :name b\nselect :x::int, ':no' -- :nope\n from t where id in :ids")]
    (is (= #{"a" "b"} (set (keys blocks))))
    (let [sc (sf/scan (blocks "b"))]
      (is (= [:x :ids] (sf/params-of sc)))
      (is (= [false true] (mapv :list? (filter map? sc))))
      (is (= ["select ?::int, ':no' -- :nope\n from t where id in (?, ?)" 7 1 2]
             (sf/render sc {:x 7 :ids [1 2]}))))))

(deftest in-chunking
  (let [sc (sf/scan "select * from t where id in :ids")]
    (is (= 3 (count (sf/chunked-renders sc {:ids (range 5)} 2))))
    (is (= ["select * from t where id in (NULL)"] (sf/render sc {:ids []})))))

(deftest statement-kinds
  (is (= :read (sf/statement-kind "/* c */ WITH x AS (select 1) select * from x")))
  (is (= :write (sf/statement-kind "-- hi\nupdate t set a = 1")))
  (is (= :call (sf/statement-kind "exec dbo.p 1"))))

(deftest env-refs
  (binding [env/*getenv* {"DB_PW" "s3cret"}]
    (is (= {:value {:password "s3cret"} :missing #{}} (env/resolve-all {:password "${DB_PW}"})))
    (is (= #{"NOPE"} (:missing (env/resolve-all {:password "${NOPE}"})))))
  (is (= [[:aw :password]] (env/literal-secrets {:aw {:password "plain"}})))
  (is (empty? (env/literal-secrets {:aw {:password "${X}"}}))))

(deftest data-paths
  (let [e {:params {:id 5} :results {:lines [{:p 1} {:p 2} {:p 1} {:p nil}]}}]
    (is (= 5 (paths/resolve-path [:params :id] e)))
    (is (= [1 2] (paths/resolve-path [:lines :p] e)))
    (is (= {:value 9} (paths/as-scalar [9] :x)))
    (is (thrown? Exception (paths/as-scalar [1 2] :x)))))

(deftest sci-sandbox
  (is (= 18.90M ((expr/compile-expr '(round-half-up (* q p (- 1 d)) 2)) {:q 2 :p 10.5M :d 0.1M})))
  (is (= "BIKE" ((expr/compile-expr '(str/upper-case (:name row))) {:name "bike"})))
  (is (thrown? Exception (expr/compile-expr '(slurp "/etc/passwd"))))
  (is (thrown? Exception (expr/compile-expr '(loop [] (recur)))))
  (is (thrown? Exception (expr/compile-expr '(count (range)))))
  (is (thrown? Exception (expr/compile-expr '(System/exit 0)))))

(deftest shaping
  (let [spec (output/compile-spec
              {:from :o :one? true
               :nest {:lines {:from :l :on {:id :oid}
                              :merge {:from :s :on {:pid :pid} :select [:qty]}
                              :compute {:low '(< (or qty 0) 2)}}}
               :types {:total :decimal-string}})
        res {:o [{:id 1 :total 10.50M}]
             :l [{:oid 1 :pid 7} {:oid 1 :pid 8} {:oid 2 :pid 9}]
             :s [{:pid 7 :qty 1} {:pid 8 :qty 5}]}]
    (is (= {:id 1 :total "10.50" :lines [{:oid 1 :pid 7 :qty 1 :low true} {:oid 1 :pid 8 :qty 5 :low false}]}
           (output/shape spec res nil)))))

(deftest json-path-subset
  (is (= [{:a 1}] (httpstep/json-rows [{:a 1}] "$[*]")))
  (is (= [1 2] (httpstep/json-rows {:x {:items [{:v 1} {:v 2}]}} "$.x.items[*].v"))))

;; ---------------------------------------------------------------------------
;; executor
;; ---------------------------------------------------------------------------

(defn- dl [ms] (+ (System/nanoTime) (* ms 1000000)))

(defn- fake-run [behaviour]
  (fn [step _env cancels]
    (let [{:keys [sleep fail rows]} (behaviour (:id step))]
      (sql/register-cancel! cancels (:id step) (fn [] (swap! (:cancelled behaviour) conj (:id step))))
      (when sleep (Thread/sleep (long sleep)))
      (when fail (throw (ex-info "boom" {:spindle/code fail :step (:id step)})))
      (or rows [{:ok (:id step)}]))))

(deftest executor-order-deadline-failure
  (let [steps {:a {:deps #{} :conn :db}
               :b {:deps #{:a} :conn :db}
               :c {:deps #{:a} :conn :api}
               :r {:deps #{:b :c} :conn :x}}]
    (testing "happy"
      (let [{:keys [results]} (executor/run steps (fake-run {}) {:deadline-ns (dl 2000)})]
        (is (= #{:a :b :c :r} (set (keys results))))))
    (testing "deadline cancels running steps"
      (let [cancelled (atom #{})
            t0 (System/nanoTime)
            e (try (executor/run steps (fake-run {:b {:sleep 3000} :cancelled cancelled}) {:deadline-ns (dl 300)})
                   (catch Exception e e))]
        (is (= :timeout (:spindle/code (ex-data e))))
        (is (< (/ (- (System/nanoTime) t0) 1e6) 1000))
        (Thread/sleep 50)
        (is (contains? @cancelled :b))))
    (testing "first failure wins and siblings are cancelled"
      (let [cancelled (atom #{})
            e (try (executor/run steps (fake-run {:b {:sleep 3000} :c {:sleep 100 :fail :unavailable} :cancelled cancelled})
                                 {:deadline-ns (dl 2000)})
                   (catch Exception e e))]
        (is (= :unavailable (:spindle/code (ex-data e))))
        (is (= :c (:step (ex-data e))))
        (Thread/sleep 50)
        (is (contains? @cancelled :b))))
    (testing "optional step degrades to partial"
      (let [steps {:a {:deps #{} :conn :db} :s {:deps #{:a} :conn :api :optional true}}
            {:keys [results partial]} (executor/run steps (fake-run {:s {:fail :unavailable}}) {:deadline-ns (dl 2000)})]
        (is (nil? (:s results)))
        (is (= [{:step :s :code :unavailable}] partial))))
    (testing "or-else fallback stands in for an empty primary"
      (let [steps {:p {:deps #{} :conn :db :or-else :f} :f {:deps #{} :conn :db} :use {:deps #{:p} :conn :db}}
            {:keys [results]} (executor/run steps (fake-run {:p {:rows []} :f {:rows [{:from :archive}]}}) {:deadline-ns (dl 2000)})]
        (is (= [{:from :archive}] (:p results)))))))
