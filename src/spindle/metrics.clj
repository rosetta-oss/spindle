(ns spindle.metrics
  "Micrometer registry (Prometheus format). Each node exposes its own metrics."
  (:import [io.micrometer.prometheusmetrics PrometheusMeterRegistry PrometheusConfig]
           [io.micrometer.core.instrument Counter Timer Gauge DistributionSummary MeterRegistry]
           [java.time Duration]
           [java.util.function Supplier]))

(defonce ^PrometheusMeterRegistry registry (PrometheusMeterRegistry. PrometheusConfig/DEFAULT))

(defn scrape [] (.scrape registry))

(defn- tags [m] (into-array String (mapcat (fn [[k v]] [(name k) (str v)]) m)))

(defn counter! [nm t]
  (-> (Counter/builder nm) (.tags (tags t)) (.register registry) (.increment)))

(defn timer! [nm t ^long nanos]
  (-> (Timer/builder nm)
      (.tags (tags t))
      (.publishPercentileHistogram)
      (.register registry)
      (.record (Duration/ofNanos nanos))))

(defn summary! [nm t ^double v]
  (-> (DistributionSummary/builder nm) (.tags (tags t)) (.register registry) (.record v)))

(defonce ^:private gauges (atom #{}))

(defn gauge!
  "Register a gauge once per (name, tags); `f` is called at scrape time."
  [nm t f]
  (let [k [nm t]]
    (when-not (contains? @gauges k)
      (swap! gauges conj k)
      (-> (Gauge/builder nm (reify Supplier (get [_] (double (or (f) Double/NaN)))))
          (.tags (tags t))
          (.register registry)))))
