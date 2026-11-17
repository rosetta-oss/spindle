(ns spindle.proc-unit-test
  "Compiling :proc steps (no database). Running them is in pg_rw_test."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [spindle.flow :as flow]
            [spindle.sql :as sql]))

(def conns {:pg {:type :sql :dialect :postgres :jdbc-url "jdbc:postgresql://x/y" :defaults {:timeout "2s" :max-rows 100}}
            :lite {:type :sql :dialect :sqlite :jdbc-url "jdbc:sqlite::memory:" :defaults {:timeout "2s" :max-rows 100}}})

(defn- compile* [steps & [output]]
  (flow/compile-flow "t" (cond-> {:steps steps} output (assoc :output output)) {} conns))

(defn- msgs [r] (str/join "\n" (map :message (:problems r))))

(deftest call-text
  (is (= "{call sales.f(?,?)}" (sql/call-text {:call "sales.f" :args [:a {:out :b :type :integer}]})))
  (is (= "{? = call dbo.p(?)}" (sql/call-text {:call "dbo.p" :args [:a] :status :st}))))

(deftest a-good-proc-step
  (let [{:keys [flow problems]}
        (compile* [{:id :bom :kind :proc :conn :pg :call "sales.bom" :read-only true
                    :args [:id {:out :n :type :integer} {:out :lines :type :cursor}]
                    :in {:id [:params :id]} :results [:header]}
                   {:id :more :kind :sql :conn :pg :in {:ids [:lines :product_id]}
                    :sql "select * from p where id in :ids"}]
                  {:from :header :nest {:lines {:from :lines :on {:id :bom_id}}}})]
    (is (nil? problems) (pr-str problems))
    (is (= [:header :lines] (get-in flow [:steps :bom :provides])))
    (is (= #{:lines} (get-in flow [:steps :more :deps])) "a provided name is a dependency")))

(deftest proc-checks
  (let [m (msgs (compile* [{:id :p :kind :proc :conn :lite :call "x; drop table t" :args [:a {:out :b :type :blob}]
                            :in {:z [:params :z]} :fail-on-status :non-zero}]))]
    (is (re-find #"has no stored procedures" m))
    (is (re-find #":call must be a procedure name" m))
    (is (re-find #"argument \{:out :b, :type :blob\}" m))
    (is (re-find #"argument :a has no binding" m))
    (is (re-find #"binding :z is not an argument" m))
    (is (re-find #":fail-on-status needs :status" m))
    (is (re-find #"declare :read-only true" m))))
