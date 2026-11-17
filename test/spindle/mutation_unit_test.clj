(ns spindle.mutation-unit-test
  "Compile rules for mutation flows (no database; running them is in pg_rw_test)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [spindle.flow :as flow]))

(def conns {:pg {:type :sql :dialect :postgres :jdbc-url "jdbc:postgresql://x/y" :defaults {:timeout "2s" :max-rows 100}}
            :api {:type :http :base-url "http://x"}})

(defn- msgs [raw] (str/join "\n" (map :message (:problems (flow/compile-flow "t" raw {} conns)))))

(def upd {:id :upd :kind :sql :conn :pg :in {:id [:params :id]} :sql "update t set x = 1 where id = :id"})

(deftest a-good-mutation
  (let [{:keys [flow problems]}
        (flow/compile-flow "t" {:steps [upd {:id :o :kind :sql :conn :pg :after [:upd] :sql "select 1 as x"}]
                                :endpoint {:path "/t" :method :put :mutation true}} {} conns)]
    (is (nil? problems) (pr-str problems))
    (is (:mutation? flow))
    (is (= [:pg] (:commit-order flow)))
    (is (true? (get-in flow [:steps :upd :write?])))
    (is (= #{:upd} (get-in flow [:steps :o :deps])) ":after orders steps")))

(deftest writes-need-a-mutation-endpoint
  (is (re-find #"needs a mutation endpoint" (msgs {:steps [upd]})))
  (is (re-find #"PUT/PATCH/DELETE are writes" (msgs {:steps [{:id :a :kind :api :conn :api :request {:method :delete :path "/x"}}]})))
  (is (re-find #"add :mutation true" (msgs {:steps [{:id :a :kind :sql :conn :pg :sql "select 1 as x"}]
                                            :endpoint {:path "/t" :method :put}})))
  (is (re-find #":expect is for write steps" (msgs {:steps [{:id :a :kind :sql :conn :pg :sql "select 1 as x" :expect {:rows 1}}]}))))

(deftest mutation-rules
  (let [m (msgs {:steps [(assoc upd :cache {:ttl "5s"} :optional true)]
                 :cache {:ttl "5s"}
                 :parallel {:pg 2}
                 :endpoint {:path "/t" :method :get :mutation true :commit-order [:other]
                            :idempotency {:conn :api}}})]
    (is (re-find #"uses :method :post, :put" m))
    (is (re-find #"a mutation is never cached" m))
    (is (re-find #"steps of a mutation are never cached" m))
    (is (re-find #"cannot be :optional" m))
    (is (re-find #"run one at a time" m))
    (is (re-find #":other is not a connection" m))
    (is (re-find #":api is not an :sql connection" m)))
  (is (re-find #"has no write step" (msgs {:steps [{:id :a :kind :sql :conn :pg :sql "select 1 as x"}]
                                           :endpoint {:path "/t" :method :post :mutation true}}))))
