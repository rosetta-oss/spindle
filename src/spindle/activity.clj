(ns spindle.activity
  "In-memory request activity for the console's Activity tab: per endpoint the
   request count, errors (by code) and latency (avg, p95, max) since start and
   over the last 5 minutes, plus the last 100 errors. Nothing is persisted: a
   restart starts from zero (Prometheus on /metrics is for long-term trends)."
  (:import [java.time Instant]))

;; latency histogram bucket upper bounds, ms (the last bucket is open-ended)
(def ^:private bounds-ms [5 10 25 50 100 250 500 1000 2500 5000 10000 30000 60000])
(def ^:private max-recent 100)
(def ^:private window-minutes 5)

(defn- empty-agg []
  {:count 0 :errors 0 :codes {} :sum-ms 0.0 :max-ms 0.0
   :hist (vec (repeat (inc (count bounds-ms)) 0))})

(defn- bucket [ms]
  (or (first (keep-indexed (fn [i b] (when (<= ms b) i)) bounds-ms)) (count bounds-ms)))

(defn- add [agg ms code]
  (cond-> (-> (or agg (empty-agg))
              (update :count inc)
              (update :sum-ms + ms)
              (update :max-ms max ms)
              (update-in [:hist (bucket ms)] inc))
    code (-> (update :errors inc) (update-in [:codes code] (fnil inc 0)))))

(defn- merge-agg [a b]
  {:count (+ (:count a) (:count b))
   :errors (+ (:errors a) (:errors b))
   :codes (merge-with + (:codes a) (:codes b))
   :sum-ms (+ (:sum-ms a) (:sum-ms b))
   :max-ms (max (:max-ms a) (:max-ms b))
   :hist (mapv + (:hist a) (:hist b))})

(defn- p95
  "Upper bound of the histogram bucket holding the 95th percentile (capped by max)."
  [{:keys [count hist max-ms]}]
  (when (pos? count)
    (let [target (Math/ceil (* 0.95 count))
          i (loop [i 0 acc 0] (let [acc (+ acc (hist i))] (if (or (>= acc target) (= i (dec (clojure.core/count hist)))) i (recur (inc i) acc))))]
      (double (min max-ms (get bounds-ms i max-ms))))))

(defonce ^:private state (atom {:started-at (str (Instant/now)) :total {} :minutes {} :recent ()}))

(defn clear! []
  (reset! state {:started-at (str (Instant/now)) :total {} :minutes {} :recent ()}))

(defn record!
  "One finished API request. `code` is nil for success, else the error code keyword."
  ([m] (record! m (System/currentTimeMillis)))
  ([{:keys [endpoint ms code caller request-id]} now-ms]
   (let [ms (double ms)
         minute (quot now-ms 60000)]
     (swap! state
            (fn [s]
              (cond-> (-> s
                          (update-in [:total endpoint] add ms code)
                          (update-in [:minutes minute endpoint] add ms code)
                          ;; keep only the minutes the window can still need
                          (update :minutes #(into {} (filter (fn [[m _]] (> m (- minute window-minutes)))) %)))
                code (update :recent #(take max-recent
                                            (conj % {:at (str (Instant/ofEpochMilli now-ms)) :endpoint endpoint
                                                     :code (name code) :ms ms :caller caller :request_id request-id})))))))
   nil))

(defn- summarise [by-endpoint]
  (->> by-endpoint
       (map (fn [[ep a]]
              {:endpoint ep :count (:count a) :errors (:errors a)
               :error_rate (if (pos? (:count a)) (/ (double (:errors a)) (:count a)) 0.0)
               :avg_ms (if (pos? (:count a)) (/ (:sum-ms a) (:count a)) 0.0)
               :p95_ms (p95 a) :max_ms (:max-ms a) :codes (:codes a)}))
       (sort-by :endpoint)
       vec))

(defn snapshot
  ([] (snapshot (System/currentTimeMillis)))
  ([now-ms]
   (let [{:keys [started-at total minutes recent]} @state
         minute (quot now-ms 60000)
         recent-5 (->> minutes
                       (filter (fn [[m _]] (> m (- minute window-minutes))))
                       (map val)
                       (apply merge-with merge-agg {}))]
     {:started_at started-at
      :window_minutes window-minutes
      :since_start (summarise total)
      :last_5m (summarise recent-5)
      :recent_errors (vec recent)})))
