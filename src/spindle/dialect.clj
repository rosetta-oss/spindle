(ns spindle.dialect
  "What differs between databases — only for what Spindle itself adds around the
   author's SQL. The author's SQL is executed as written.

   | key              | meaning                                                        |
   |------------------|----------------------------------------------------------------|
   | :driver          | default JDBC driver class                                      |
   | :app-name        | fn [props name] → props that tag connections                   |
   | :url-defaults    | fn [url] → url with required defaults                          |
   | :isolation?      | supports JDBC transaction isolation                            |
   | :transactions?   | supports transactions (writes in one transaction)              |
   | :procedures?     | supports stored procedures                                     |
   | :max-in          | IN-list chunk size (bind-parameter budget)                     |
   | :explain         | fn [sql] → [sql] that returns a plan, or :showplan, or nil     |
   | :session-reset   | statements to run before returning a session connection        |
   | :read-only-hint  | whether Connection.setReadOnly is meaningful                   |"
  (:require [clojure.string :as str]))

(defn- add-url-param [url k v]
  (if (re-find (re-pattern (str "[?&;]" k "=")) (str/lower-case url))
    url
    (str url (cond (not (str/includes? url "?")) "?"
                   (or (str/ends-with? url "?") (str/ends-with? url "&")) ""
                   :else "&")
         k "=" v)))

(def dialects
  {:postgres
   {:driver "org.postgresql.Driver"
    :app-name (fn [props nm] (assoc props "ApplicationName" nm))
    :isolation? true :transactions? true :procedures? true
    :max-in 1000
    :explain (fn [sql] (str "EXPLAIN (FORMAT JSON) " sql))
    :session-reset ["DISCARD TEMP" "RESET ALL"]}

   :mysql
   {:driver "org.mariadb.jdbc.Driver"
    ;; Connector/J and MariaDB both accept connectionAttributes; harmless if ignored
    :app-name (fn [props nm] (assoc props "connectionAttributes" (str "program_name:" nm)))
    :isolation? true :transactions? true :procedures? true
    :max-in 1000
    :explain (fn [sql] (str "EXPLAIN FORMAT=JSON " sql))
    :session-reset []}

   :clickhouse
   {:driver "com.clickhouse.jdbc.ClickHouseDriver"
    :app-name (fn [props nm] (assoc props "client_name" nm))
    ;; clickhouse-jdbc 0.9 cannot read LZ4-compressed responses from ClickHouse 26.x
    ;; ("Invalid LZ4 magic byte"). It needs compress=false; compress=0 makes 0.9 hang
    ;; and retry for minutes (0.6.x used compress=0). An explicit compress= in the URL wins.
    :url-defaults (fn [url] (add-url-param url "compress" "false"))
    :isolation? false :transactions? false :procedures? false
    :max-in 10000
    :explain (fn [sql] (str "EXPLAIN " sql))
    :session-reset []}

   :sybase-ase
   {:driver "com.sybase.jdbc4.jdbc.SybDriver"
    :app-name (fn [props nm] (assoc props "APPLICATIONNAME" nm))
    :isolation? true :transactions? true :procedures? true
    :max-in 250
    :explain :showplan
    :session-reset ["set rowcount 0"]}

   :generic
   {:driver nil
    :app-name (fn [props _] props)
    :isolation? true :transactions? true :procedures? true
    :max-in 500
    :explain nil
    :session-reset []}

   ;; for tests
   :sqlite
   {:driver "org.sqlite.JDBC"
    :app-name (fn [props _] props)
    :isolation? false :transactions? true :procedures? false
    :max-in 500
    :explain (fn [sql] (str "EXPLAIN QUERY PLAN " sql))
    :session-reset []}})

(defn get-dialect [k] (or (dialects k) (dialects :generic)))

(defn url-for [d url] (if-let [f (:url-defaults d)] (f url) url))

(def isolation-levels
  {:read-uncommitted java.sql.Connection/TRANSACTION_READ_UNCOMMITTED
   :read-committed   java.sql.Connection/TRANSACTION_READ_COMMITTED
   :repeatable-read  java.sql.Connection/TRANSACTION_REPEATABLE_READ
   :serializable     java.sql.Connection/TRANSACTION_SERIALIZABLE})
