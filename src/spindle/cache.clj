(ns spindle.cache
  "Per-endpoint local cache (Caffeine): TTL, stale-while-revalidate,
   single-flight, and serving expired entries when the source fails.

   Entry = {:value v :at epoch-ms}. With :ttl T, :stale S, :stale-if-error E:
     age < T            → hit
     age < T + S        → stale hit, one background refresh (single-flight)
     source fails, age < T + max(S, E) → serve stale, marked
   Keys include the flow version, so a changed flow never serves old results."
  (:require [promesa.core :as p]
            [promesa.exec :as px]
            [spindle.conn :as conn]
            [spindle.error :as err])
  (:import [com.github.benmanes.caffeine.cache Caffeine Cache]
           [io.micrometer.core.instrument.binder.cache CaffeineCacheMetrics]
           [java.time Duration]
           [java.util.concurrent ConcurrentHashMap CompletableFuture]))

(defn settings [cfg]
  (when cfg
    (let [ttl (conn/parse-duration-ms (:ttl cfg) 0)
          stale (conn/parse-duration-ms (:stale cfg) 0)
          sie (conn/parse-duration-ms (:stale-if-error cfg) stale)]
      (when (pos? ttl)
        {:ttl ttl :stale stale :stale-if-error sie
         :empty-ttl (conn/parse-duration-ms (:empty-ttl cfg) (min ttl 30000))
         :max-entries (or (:max-entries cfg) 10000)}))))

(defn make [endpoint-id s registry]
  (let [^Cache c (-> (Caffeine/newBuilder)
                     (.maximumSize (long (:max-entries s)))
                     (.expireAfterWrite (Duration/ofMillis (+ (:ttl s) (max (:stale s) (:stale-if-error s)))))
                     (.recordStats)
                     (.build))]
    (CaffeineCacheMetrics/monitor registry c (str "endpoint:" endpoint-id) (into-array String []))
    {:cache c :settings s :inflight (ConcurrentHashMap.)}))

(defn- now [] (System/currentTimeMillis))

(defn- single-flight
  "Run (compute) once per key at a time on this node; concurrent callers share it."
  [{:keys [^ConcurrentHashMap inflight]} k compute]
  (let [mine (CompletableFuture.)
        existing (.putIfAbsent inflight k mine)]
    (if existing
      @existing
      (try
        (let [v (compute)]
          (.complete mine v)
          v)
        (catch Throwable t
          (.completeExceptionally mine t)
          (throw t))
        (finally (.remove inflight k mine))))))

(defn lookup
  "→ {:value v :cache :hit|:miss|:stale}. `compute` returns the fresh value."
  [{:keys [^Cache cache settings] :as c} k compute]
  (let [{:keys [ttl stale stale-if-error empty-ttl]} settings
        e (.getIfPresent cache k)
        age (when e (- (now) (:at e)))
        fresh-for (fn [v] (if (and (sequential? (:rows v)) (empty? (:rows v))) empty-ttl ttl))
        store! (fn [v] (.put cache k {:value v :at (now)}) v)]
    (cond
      (and e (< age (fresh-for (:value e))))
      {:value (:value e) :cache :hit}

      (and e (< age (+ ttl stale)))
      (do (px/submit :vthread #(try (single-flight c k (fn [] (store! (compute))))
                                    (catch Throwable _ nil)))
          {:value (:value e) :cache :stale})

      :else
      (try
        {:value (single-flight c k (fn [] (store! (compute)))) :cache :miss}
        (catch Throwable t
          (let [code (:spindle/code (err/code-of t))]
            (if (and e (#{:unavailable :timeout :source_error} code) (< age (+ ttl stale-if-error)))
              {:value (:value e) :cache :stale :stale-reason code}
              (throw t))))))))

(defn peek-fresh
  "The cached value under `k` when it is still fresh (within :ttl), else nil.
   Used by the batch route, which checks the cache key by key."
  [{:keys [^Cache cache settings]} k]
  (when-let [e (.getIfPresent cache k)]
    (when (< (- (now) (:at e)) (:ttl settings)) (:value e))))

(defn put! [{:keys [^Cache cache]} k v] (.put cache k {:value v :at (now)}) v)

(defn stats [{:keys [^Cache cache]}]
  (let [s (.stats cache)]
    {:entries (.estimatedSize cache) :hits (.hitCount s) :misses (.missCount s)}))
