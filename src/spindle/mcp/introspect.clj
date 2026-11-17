(ns spindle.mcp.introspect
  "Schema metadata through JDBC DatabaseMetaData. Never reads table rows."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc])
  (:import [java.sql DatabaseMetaData ResultSet]))

(defn- split-name [s]
  (let [[a b] (str/split s #"\." 2)]
    (if b [a b] [nil a])))

(defn- rows [^ResultSet rs f]
  (with-open [rs rs]
    (loop [acc []] (if (.next rs) (recur (conj acc (f rs))) acc))))

(defn- name-variants
  "Catalogs fold case differently: try the name as given, then upper and lower."
  [n]
  (distinct [n (str/upper-case n) (str/lower-case n)]))

(defn- table-columns [^DatabaseMetaData md schema t]
  (rows (.getColumns md nil schema t "%")
        (fn [^ResultSet r] {:name (.getString r "COLUMN_NAME")
                            :type (.getString r "TYPE_NAME")
                            :nullable (= DatabaseMetaData/columnNullable (.getInt r "NULLABLE"))
                            :size (.getInt r "COLUMN_SIZE")})))

(defn describe-table [ds table]
  (let [[schema tbl] (split-name table)]
    (with-open [con (jdbc/get-connection ds)]
      (let [^DatabaseMetaData md (.getMetaData con)
            [real cols] (some (fn [t] (let [c (table-columns md schema t)] (when (seq c) [t c])))
                              (name-variants tbl))]
        (if-not cols
          {:error "no such table"}
          (let [pk (->> (rows (.getPrimaryKeys md nil schema real)
                              (fn [^ResultSet r] [(.getInt r "KEY_SEQ") (.getString r "COLUMN_NAME")]))
                        (sort-by first) (mapv second))
                idx (->> (rows (.getIndexInfo md nil schema real false true)
                               (fn [^ResultSet r] {:name (.getString r "INDEX_NAME")
                                                   :pos (.getInt r "ORDINAL_POSITION")
                                                   :col (.getString r "COLUMN_NAME")
                                                   :unique (not (.getBoolean r "NON_UNIQUE"))}))
                         (filter #(and (:name %) (:col %)))
                         (group-by :name)
                         (mapv (fn [[n es]] {:name n :columns (mapv :col (sort-by :pos es)) :unique (:unique (first es))}))
                         (sort-by :name) vec)]
            {:columns cols :primary_key pk :indexes idx}))))))

(def ^:private proc-modes
  {1 "in" 2 "inout" 4 "out" 5 "return" 3 "result"})

(defn describe-proc [ds proc]
  (let [[schema p] (split-name proc)]
    (with-open [con (jdbc/get-connection ds)]
      (let [^DatabaseMetaData md (.getMetaData con)
            ps (some (fn [n]
                       (let [r (rows (.getProcedureColumns md nil schema n "%")
                                     (fn [^ResultSet r] {:name (.getString r "COLUMN_NAME")
                                                         :type (.getString r "TYPE_NAME")
                                                         :mode (get proc-modes (.getInt r "COLUMN_TYPE") "unknown")}))]
                         (when (seq r) r)))
                     (name-variants p))]
        (if ps {:params ps} {:error "no such procedure"})))))
