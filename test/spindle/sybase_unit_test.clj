(ns spindle.sybase-unit-test
  "Sybase ASE dialect tests that need no database: the dialect entry, IN-list
   chunking at ASE's bind-parameter budget, and the showplan explain path
   (driven through a fake JDBC connection that records what Spindle sends)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [spindle.dialect :as dialect]
            [spindle.sql :as sql]
            [spindle.sqlfile :as sf])
  (:import [java.sql Connection PreparedStatement SQLException SQLWarning Statement]
           [javax.sql DataSource]))

(def ase (dialect/get-dialect :sybase-ase))

(deftest ase-dialect-entry
  (is (= "com.sybase.jdbc4.jdbc.SybDriver" (:driver ase)))
  (is (= {"APPLICATIONNAME" "spindle" "X" "1"} ((:app-name ase) {"X" "1"} "spindle"))
      "connections are tagged so they show up as program_name in sysprocesses")
  (is (= 250 (:max-in ase)))
  (is (= :showplan (:explain ase)))
  (is (= ["set rowcount 0"] (:session-reset ase)))
  (is (every? true? ((juxt :isolation? :transactions? :procedures?) ase)))
  (testing "the jConnect URL is used as written"
    (is (= "jdbc:sybase:Tds:h:4100/CIS" (dialect/url-for ase "jdbc:sybase:Tds:h:4100/CIS")))))

(deftest ase-in-list-chunking
  (let [sc (sf/scan "select id from master..sysobjects where id in :ids")
        renders (sf/chunked-renders sc {:ids (range 600)} (:max-in ase))]
    (is (= 3 (count renders)) "600 ids → chunks of 250, 250, 100")
    (is (= [250 250 100] (map (comp count rest) renders)))
    (is (= (range 600) (mapcat rest renders)) "every id bound exactly once, in order")
    (is (every? #(<= (count (re-seq #"\?" (first %))) 250) renders))))

(defn- warnings [& msgs]
  (when (seq msgs)
    (let [[w & more] (map #(SQLWarning. ^String %) msgs)]
      (reduce (fn [^SQLWarning a b] (.setNextWarning a b) a) w more)
      w)))

(defn- fake-ase
  "A DataSource whose connection records every statement and parameter and
   answers the prepared statement with showplan-style warnings."
  [log {:keys [ps-warnings con-warnings fail? throw-warnings?]}]
  (let [ps (reify PreparedStatement
             (^void setQueryTimeout [_ ^int s] (swap! log conj [:timeout s]))
             (^void setObject [_ ^int i ^Object v] (swap! log conj [:bind i v]))
             (^boolean execute [_]
               (swap! log conj [:execute])
               (if fail? (throw (SQLException. "noexec: no result")) false))
             (getWarnings [_] (apply warnings ps-warnings))
             (close [_] (when throw-warnings? (throw (SQLWarning. "thrown on ps close")))))
        st (reify Statement
             (^boolean execute [_ ^String s]
               (swap! log conj [:session s])
               (if throw-warnings? (throw (SQLWarning. (str "thrown by " s))) false))
             (close [_] (when throw-warnings? (throw (SQLWarning. "thrown on close")))))
        con (reify Connection
              (createStatement [_] st)
              (^PreparedStatement prepareStatement [_ ^String s] (swap! log conj [:prepare s]) ps)
              (getWarnings [_] (apply warnings con-warnings))
              (close [_] (swap! log conj [:close])))]
    (reify DataSource (getConnection [_] con))))

(deftest ase-showplan-explain
  (let [log (atom [])
        rt {:id :ase :dialect ase
            :ds (fake-ase log {:ps-warnings ["QUERY PLAN FOR STATEMENT 1 (at line 1)."
                                             "    STEP 1"]
                               :con-warnings ["The type of query is SELECT."]})}
        plan (sql/explain! rt "select name from sysobjects where id = ?" [42])]
    (testing "showplan + noexec are set before the statement, so it is compiled, never run"
      (is (= [[:session "set showplan on"] [:session "set noexec on"]]
             (take 2 @log)))
      (is (= [:prepare "select name from sysobjects where id = ?"] (nth @log 2))))
    (testing "parameters are bound and a timeout is set"
      (is (some #{[:bind 1 42]} @log))
      (is (some #{[:timeout 10]} @log)))
    (testing "plan text is collected from statement and connection warnings"
      (is (= 1 (count plan)))
      (is (str/includes? (:plan (first plan)) "QUERY PLAN FOR STATEMENT 1"))
      (is (str/includes? (:plan (first plan)) "STEP 1"))
      (is (str/includes? (:plan (first plan)) "The type of query is SELECT.")))
    (testing "the explain connection is closed (and evicted) so showplan never leaks into the pool"
      (is (= [:close] (last @log))))))

(deftest ase-showplan-explain-tolerates-noexec-error
  (let [log (atom [])
        rt {:id :ase :dialect ase
            :ds (fake-ase log {:fail? true :ps-warnings ["QUERY PLAN FOR STATEMENT 1"]})}]
    (is (= [{:plan "QUERY PLAN FOR STATEMENT 1"}] (sql/explain! rt "select 1" [])))
    (is (= [:close] (last @log)))))

(deftest ase-showplan-explain-collects-thrown-warnings
  ;; jConnect throws showplan output as SybSQLWarning from execute and Statement.close
  (let [log (atom [])
        rt {:id :ase :dialect ase
            :ds (fake-ase log {:throw-warnings? true :ps-warnings ["QUERY PLAN FOR STATEMENT 1"]})}
        plan (:plan (first (sql/explain! rt "select 1" [])))]
    (is (str/includes? plan "QUERY PLAN FOR STATEMENT 1"))
    (is (str/includes? plan "thrown by set showplan on"))
    (is (str/includes? plan "thrown on ps close"))
    (is (= [:close] (last @log)))))
