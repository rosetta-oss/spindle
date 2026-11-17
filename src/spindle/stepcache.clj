(ns spindle.stepcache
  "Step-level cache, shared by every flow and endpoint on this node.

   Why at the step level: one heavy upstream call (an API that returns header and
   details together) usually backs several small endpoints (/header, /lines).
   Callers such as Semalith ask for them separately and often at the same time;
   with the cache on the step, the upstream is called once.

   Key   = [connection, step kind, normalised request, caller scope]
           SQL: the statement text + bound values; HTTP: method, path, query,
           body and forwarded headers after data paths are resolved.
           Caller scope is part of the key whenever the step depends on the
           caller (uses [:caller …], the connection forwards the caller, or the
           step says :cache {:per :caller}) — so A's rows are never served to B.
   Value = {:rows .. :at epoch-ms :tags #{..}}

   Per step:  :cache {:ttl \"30s\" :stale \"2m\" :stale-if-error \"10m\" :per :caller :tags [..]}
   SQL steps are not cached unless they say so.

   - single-flight: identical concurrent misses run the upstream once;
   - stale-while-revalidate: within :stale after expiry, serve and refresh in the background;
   - on a source failure (breaker open, timeout, 5xx), an entry within
     :stale-if-error is served and marked stale;
   - bounded by total weight (approximate bytes; SPINDLE_STEP_CACHE_MB, default 256);
   - each node has its own cache: across nodes, results may differ for up to the TTL."
  (:require [promesa.exec :as px]
            [spindle.conn :as conn]
            [spindle.error :as err]
            [spindle.metrics :as metrics])
  (:import [com.github.benmanes.caffeine.cache Caffeine Cache Weigher]
           [io.micrometer.core.instrument.binder.cache CaffeineCacheMetrics]
           [java.security MessageDigest]
           [java.time Duration]
           [java.util.concurrent CompletableFuture ConcurrentHashMap]
           [java.util.concurrent.atomic AtomicLong]))

(defn estimate-bytes
  "Rough size of a cached value, for the weight bound."
  [x]
  (cond
    (nil? x) 8
    (string? x) (+ 40 (* 2 (count x)))
    (map? x) (reduce-kv (fn [n k v] (+ n 32 (estimate-bytes k) (estimate-bytes v))) 48 x)
    (coll? x) (reduce (fn [n v] (+ n 8 (estimate-bytes v))) 48 x)
    :else 24))

(def ^:private max-mb
  (delay (Long/parseLong (or (System/getenv "SPINDLE_STEP_CACHE_MB") "256"))))

(defonce ^:private store
  (delay
    (let [^Cache c (-> (Caffeine/newBuilder)
                       (.maximumWeight (* 1024 1024 (long @max-mb)))
                       (.weigher (reify Weigher (weigh [_ _ v] (int (min Integer/MAX_VALUE (:weight v 1))))))
                       ;; hard upper bound; freshness is decided per entry below
                       (.expireAfterWrite (Duration/ofHours 24))
                       (.recordStats)
                       (.build))]
      (CaffeineCacheMetrics/monitor metrics/registry c "steps" (into-array String []))
      c)))

(defonce ^:private ^ConcurrentHashMap inflight (ConcurrentHashMap.))

;; per (endpoint, step) counters for /status
(defonce ^:private ^ConcurrentHashMap counters (ConcurrentHashMap.))

(defn- count! [stat-key result]
  (let [^AtomicLong a (.computeIfAbsent counters [stat-key result]
                                        (reify java.util.function.Function (apply [_ _] (AtomicLong. 0))))]
    (.incrementAndGet a))
  (metrics/counter! "spindle_step_cache_total" {:endpoint (first stat-key) :step (second stat-key) :result (name result)}))

(defn stats
  "{[endpoint step] {:hit n :miss n :stale n}}"
  []
  (reduce (fn [m [[sk r] ^AtomicLong a]] (assoc-in m [sk r] (.get a))) {} counters))

(defn settings [cfg]
  (when cfg
    (let [ttl (conn/parse-duration-ms (:ttl cfg) 0)]
      (when (pos? ttl)
        {:ttl ttl
         :stale (conn/parse-duration-ms (:stale cfg) 0)
         :stale-if-error (conn/parse-duration-ms (:stale-if-error cfg) (conn/parse-duration-ms (:stale cfg) 0))
         :per (:per cfg)
         :tags (set (map name (:tags cfg)))}))))

(defn caller-scope
  "Stable, non-reversible key for the caller's identity (subject + claims)."
  [caller]
  (when (or (:sub caller) (seq (:claims caller)))
    (let [d (.digest (MessageDigest/getInstance "SHA-256")
                     (.getBytes (pr-str [(:client caller) (:sub caller) (into (sorted-map) (:claims caller))]) "UTF-8"))]
      (apply str (map #(format "%02x" %) (take 16 d))))))

(defn- single-flight [k compute]
  (let [mine (CompletableFuture.)
        existing (.putIfAbsent inflight k mine)]
    (if existing
      (try @existing
           (catch java.util.concurrent.ExecutionException e (throw (.getCause e))))
      (try
        (let [v (compute)] (.complete mine v) v)
        (catch Throwable t (.completeExceptionally mine t) (throw t))
        (finally (.remove inflight k mine))))))

(defn lookup
  "→ {:rows .. :cache :hit|:miss|:stale}. `compute` returns fresh rows."
  [k {:keys [ttl stale stale-if-error tags]} stat-key compute]
  (let [^Cache c @store
        e (.getIfPresent c k)
        age (when e (- (System/currentTimeMillis) (:at e)))
        store! (fn [rows]
                 (.put c k {:rows rows :at (System/currentTimeMillis) :tags tags
                            :weight (estimate-bytes rows)})
                 rows)]
    (cond
      (and e (< age ttl))
      (do (count! stat-key :hit) {:rows (:rows e) :cache :hit})

      (and e (< age (+ ttl stale)))
      (do (count! stat-key :stale)
          (px/submit :vthread #(try (single-flight k (fn [] (store! (compute)))) (catch Throwable _ nil)))
          {:rows (:rows e) :cache :stale})

      :else
      (try
        (count! stat-key :miss)
        {:rows (single-flight k (fn [] (store! (compute)))) :cache :miss}
        (catch Throwable t
          (let [code (:spindle/code (err/code-of t))]
            (if (and e (#{:unavailable :timeout :source_error} code) (< age (+ ttl stale-if-error)))
              (do (count! stat-key :stale) {:rows (:rows e) :cache :stale :stale-reason code})
              (throw t))))))))

(defn evict-tags!
  "Drop every entry carrying one of `tags` (on this node). → number evicted."
  [tags]
  (let [ts (set (map name tags))
        m (.asMap ^Cache @store)
        ks (keep (fn [[k v]] (when (some ts (:tags v)) k)) m)]
    (doseq [k ks] (.remove m k))
    (count ks)))

(defn clear! [] (.invalidateAll ^Cache @store) (.clear inflight) (.clear counters))
