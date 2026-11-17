(ns jconnect-cancel
  "Does cancellation reach Sybase ASE through jConnect?

   Runs `waitfor delay \"00:00:30\"` and cancels it after 5 seconds, four ways:

     1. promise    — promesa future on a virtual thread; p/cancel! at 5 s
                     → Statement.cancel() (the path Spindle uses)
     2. direct     — Statement.cancel() from another thread at 5 s
     3. timeout    — Statement.setQueryTimeout(5), no explicit cancel
     4. spindle    — a Spindle flow with a 5 s deadline, through the HTTP handler

   For each: elapsed time, the exception, whether the server stopped the
   statement (master..sysprocesses for that spid), and whether the same
   connection is usable afterwards (`select 1`).

   Configure with environment variables (never put credentials in files):
     ASE_URL       jdbc:sybase:Tds:<host>:<port>/<db>
     ASE_USER      login
     ASE_PASSWORD  password
     ASE_PROPS     optional, e.g. CANCEL_ALL=false;QUERY_TIMEOUT_CANCELS_ALL=false"
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [promesa.exec :as px]
            [spindle.auth :as auth]
            [spindle.config :as config]
            [spindle.server :as server])
  (:import [java.sql Connection DriverManager Statement]
           [java.util Properties]
           [java.util.concurrent CancellationException]))

(defn- env! [k] (or (System/getenv k) (throw (ex-info (str "set " k) {}))))

(defn- props []
  (let [p (Properties.)]
    (.setProperty p "user" (env! "ASE_USER"))
    (.setProperty p "password" (env! "ASE_PASSWORD"))
    (.setProperty p "APPLICATIONNAME" "spindle-cancel-test")
    (doseq [kv (remove str/blank? (str/split (or (System/getenv "ASE_PROPS") "") #";"))
            :let [[k v] (str/split kv #"=" 2)]]
      (.setProperty p (str/trim k) (str/trim v)))
    p))

(defn- connect ^Connection [] (DriverManager/getConnection (env! "ASE_URL") (props)))

(defn- ms [t0] (long (/ (- (System/nanoTime) t0) 1e6)))

(defn- spid [^Connection c]
  (with-open [st (.createStatement c) rs (.executeQuery st "select @@spid")]
    (.next rs) (.getInt rs 1)))

(defn- server-state
  "What the server says about `spid` right now (cmd, status)."
  [s]
  (with-open [c (connect) st (.createStatement c)
              rs (.executeQuery st (str "select cmd, status from master..sysprocesses where spid = " s))]
    (if (.next rs) {:cmd (str/trim (.getString rs 1)) :status (str/trim (.getString rs 2))} {:cmd "(gone)"})))

(defn- usable? [^Connection c]
  (try (with-open [st (.createStatement c) rs (.executeQuery st "select 1")] (.next rs) (= 1 (.getInt rs 1)))
       (catch Exception e (str "NO: " (.getMessage e)))))

(def waitfor "waitfor delay '00:00:30'")  ; single quotes: jConnect turns quoted_identifier on

(defn- report [nm t0 outcome s c]
  (Thread/sleep 300)
  (let [r {:test nm :elapsed-ms (ms t0) :outcome outcome
           :server-after (server-state s) :connection-usable-after (usable? c)}]
    (prn r) r))

(defn test-promise []
  (with-open [c (connect)]
    (let [s (spid c)
          st (.createStatement c)
          t0 (System/nanoTime)
          fut (px/submit :vthread (fn [] (.execute st waitfor) :finished))]
      (p/handle fut (fn [_ e] (when (instance? CancellationException e) (.cancel st))))
      (Thread/sleep 5000)
      (p/cancel! fut)
      ;; the promise is cancelled at once; wait for the JDBC call itself to return
      (let [t1 (System/nanoTime)
            done (loop [i 0] (if (or (> i 100) (not (str/includes? (str (:cmd (server-state s))) "WAITFOR")))
                               (ms t1) (do (Thread/sleep 100) (recur (inc i)))))]
        (report :promise t0 {:server-stopped-within-ms done} s c)))))

(defn test-direct []
  (with-open [c (connect)]
    (let [s (spid c)
          st (.createStatement c)
          t0 (System/nanoTime)]
      (future (Thread/sleep 5000) (.cancel st))
      (let [outcome (try (.execute st waitfor) :finished
                         (catch Exception e {:exception (.getName (class e)) :message (.getMessage e)
                                             :sql-state (when (instance? java.sql.SQLException e) (.getSQLState ^java.sql.SQLException e))}))]
        (report :direct t0 outcome s c)))))

(defn test-timeout []
  (with-open [c (connect)]
    (let [s (spid c)
          st (doto (.createStatement c) (.setQueryTimeout 5))
          t0 (System/nanoTime)
          outcome (try (.execute st waitfor) :finished
                       (catch Exception e {:exception (.getName (class e)) :message (.getMessage e)}))]
      (report :query-timeout t0 outcome s c))))

(defn test-spindle []
  (let [dir (doto (java.io.File. (System/getProperty "java.io.tmpdir") (str "spindle-ase-" (System/nanoTime))) (.mkdirs))
        w (fn [rel text] (let [f (java.io.File. dir rel)] (.mkdirs (.getParentFile f)) (spit f text)))
        props (into {} (for [kv (remove str/blank? (str/split (or (System/getenv "ASE_PROPS") "") #";"))
                             :let [[k v] (str/split kv #"=" 2)]]
                         [(str/trim k) (str/trim v)]))]
    (w "connections.edn" (pr-str {:ase {:type :sql :dialect :sybase-ase :jdbc-url "${ASE_URL}"
                                        :username "${ASE_USER}" :password "${ASE_PASSWORD}"
                                        :properties props}}))
    (w "auth.edn" (pr-str {:api-keys [{:id "t" :sha256 (auth/sha256-hex "k") :scopes #{"t"}}]}))
    ;; Spindle flows only run read statements, so `waitfor` cannot be used here.
    ;; A 4-way cross join over master..sysobjects (~10^9 rows) runs far longer than
    ;; the 5 s deadline; the test checks that Spindle cancels it at the deadline.
    (w "flows/t/wait.edn" (pr-str {:steps [{:id :w :kind :sql :conn :ase :tuning {:timeout "5s"} :max-rows 10
                                            :sql "select count(*) as n from master..sysobjects a, master..sysobjects b, master..sysobjects c, master..sysobjects d"}]
                                   :limits {:timeout "5s"} :endpoint {:path "/t/wait" :scopes #{"t"}}}))
    (let [r (config/reload! (str dir))]
      (if (:problems r)
        (prn {:test :spindle :config-problems (:problems r)})
        (let [h (server/handler {:node-id "t" :config-dir (str dir)})
              t0 (System/nanoTime)
              resp (h {:request-method :get :uri "/t/wait" :headers {"authorization" "Bearer k"}})]
          (Thread/sleep 1000)
          (let [r {:test :spindle-flow :elapsed-ms (ms t0) :status (:status resp) :body (:body resp)
                   :still-running (with-open [c (connect) st (.createStatement c)
                                              rs (.executeQuery st "select count(*) from master..sysprocesses where program_name like 'spindle%' and cmd not like 'AWAITING%' and spid <> @@spid")]
                                    (.next rs) (.getInt rs 1))}]
            (prn r) r))))))

(defn -main [& args]
  (println "jConnect:" (try (some-> (Class/forName "com.sybase.jdbc4.jdbc.SybDriver") .getPackage .getImplementationVersion)
                            (catch Exception _ "NOT FOUND — put jconn4.jar into drivers/")))
  (println "ASE props:" (or (System/getenv "ASE_PROPS") "(defaults)"))
  (let [which (set (map keyword (or (seq args) ["promise" "direct" "timeout" "spindle"])))]
    (when (which :promise) (test-promise))
    (when (which :direct) (test-direct))
    (when (which :timeout) (test-timeout))
    (when (which :spindle) (test-spindle)))
  (shutdown-agents)
  (System/exit 0))
