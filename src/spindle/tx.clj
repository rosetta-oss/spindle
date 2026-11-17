(ns spindle.tx
  "Transactions for mutation flows (design §8).

   - One transaction per :sql connection per request: the first step on a
     connection opens it (auto-commit off, the connection's isolation); every
     later :sql/:proc step on that connection — reads too — runs on it, so
     reads see the request's own writes.
   - Commit happens once, after every step and the output succeeded, in the
     flow's commit order. Any failure, cancellation or the deadline rolls
     everything back. No two-phase commit: if a later commit fails after an
     earlier one succeeded, the outcome is :partial and lists what committed.
   - The commit itself is never cancelled.
   - A connection that was cancelled mid-statement is evicted, not reused."
  (:require [clojure.tools.logging :as log]
            [spindle.conn :as conn]
            [spindle.dialect :as dialect]
            [spindle.error :as err])
  (:import [java.sql Connection]))

(defn new-tx
  "→ tx state for one request. `order` is the commit order (connection ids)."
  [snapshot order]
  (atom {:snapshot snapshot :order (vec order) :open {} :broken #{}}))

(defn connection!
  "The request's connection for `conn-id`, opened on first use."
  ^Connection [tx conn-id]
  (or (get-in @tx [:open conn-id])
      (let [rt (get-in @tx [:snapshot :conns conn-id])
            ^Connection c (try (.getConnection ^javax.sql.DataSource (:ds rt))
                               (catch Throwable t
                                 (err/fail! :unavailable "no database connection" {:conn conn-id} t)))]
        (try
          (.setAutoCommit c false)
          (when-let [iso (get-in rt [:cfg :defaults :isolation])]
            (when (:isolation? (:dialect rt)) (.setTransactionIsolation c (int (dialect/isolation-levels iso)))))
          (catch Throwable t (try (.close c) (catch Throwable _ nil)) (throw t)))
        (swap! tx assoc-in [:open conn-id] c)
        c)))

(defn mark-broken!
  "A statement on this connection was cancelled: roll back and evict at the end."
  [tx conn-id]
  (swap! tx update :broken conj conn-id))

(defn- release! [tx conn-id ^Connection c]
  (let [rt (get-in @tx [:snapshot :conns conn-id])]
    (if (contains? (:broken @tx) conn-id)
      (do (conn/evict! rt c) (try (.close c) (catch Throwable _ nil)))
      (do (try (.setAutoCommit c true) (catch Throwable _ (conn/evict! rt c)))
          (try (.close c) (catch Throwable _ nil))))))

(defn rollback-all! [tx]
  (doseq [[id ^Connection c] (:open @tx)]
    (try (.rollback c) (catch Throwable t (log/warn t (str "event=rollback-failed conn=" (name id))) (mark-broken! tx id)))
    (release! tx id c))
  (swap! tx assoc :open {}))

(defn commit-all!
  "Commit every open connection in order. → {:outcome :committed :committed [ids]}
   or throws with :outcome :rolled_back (first commit failed) or :partial."
  [tx]
  (let [{:keys [open order]} @tx
        ids (concat (filter open order) (remove (set order) (keys open)))]
    (loop [[id & more] ids done []]
      (if-not id
        (do (doseq [[id c] open] (release! tx id c))
            (swap! tx assoc :open {})
            {:outcome :committed :committed done})
        (let [^Connection c (get open id)
              r (try (.commit c) :ok (catch Throwable t t))]
          (if (= :ok r)
            (recur more (conj done id))
            (do (doseq [rid more] (try (.rollback ^Connection (get open rid)) (catch Throwable _ nil)))
                (mark-broken! tx id)
                (doseq [[oid oc] open] (release! tx oid oc))
                (swap! tx assoc :open {})
                (err/fail! :source_error (if (seq done) "commit failed after an earlier commit succeeded" "commit failed")
                           {:outcome (if (seq done) :partial :rolled_back) :committed done :conn id} r))))))))
