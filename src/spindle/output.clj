(ns spindle.output
  "Shaping step results into one output. Order per row:
   merge → nest → compute → types → select → rename."
  (:require [clojure.set]
            [clojure.string :as str]
            [spindle.expr :as expr])
  (:import [java.math BigDecimal]
           [java.time LocalDate LocalDateTime OffsetDateTime ZoneId Instant]))

(defn- ->bigdec [x] (cond (nil? x) nil (instance? BigDecimal x) x :else (bigdec x)))

(defn- coerce [t v zone]
  (when (some? v)
    (case t
      :trim (if (string? v) (str/trimr v) v)
      :int (int (if (string? v) (Long/parseLong (str/trim v)) v))
      :long (long (if (string? v) (Long/parseLong (str/trim v)) v))
      :decimal (->bigdec v)
      :decimal-string (.toPlainString ^BigDecimal (->bigdec v))
      :date (cond (instance? LocalDateTime v) (str (.toLocalDate ^LocalDateTime v))
                  (instance? OffsetDateTime v) (str (.toLocalDate ^OffsetDateTime v))
                  :else (str v))
      :datetime (cond (instance? LocalDateTime v)
                      (str (.atZone ^LocalDateTime v (ZoneId/of (or zone "UTC"))))
                      :else (str v))
      :bool (cond (boolean? v) v
                  (number? v) (not (zero? v))
                  :else (contains? #{"y" "yes" "t" "true" "1"} (str/lower-case (str/trim (str v)))))
      :nil-if-blank (if (and (string? v) (str/blank? v)) nil v)
      v)))

(defn compile-spec
  "Compile :compute expressions inside an output spec (recursively)."
  [spec]
  (cond-> spec
    (:compute spec) (assoc ::compute (into {} (map (fn [[k form]] [k (expr/compile-expr form)])) (:compute spec)))
    (:nest spec) (update :nest #(into {} (map (fn [[k s]] [k (compile-spec s)])) %))
    (:merge spec) (update :merge compile-spec)))

(defn- index-by [rows on]
  ;; on: {left-col right-col}; key on the right-hand rows
  (let [rk (vals on)]
    (group-by #(mapv (fn [c] (get % c)) rk) rows)))

(defn- left-key [row on] (mapv #(get row %) (keys on)))

(declare shape-rows)

(defn- apply-spec [row spec results zone]
  (let [row (if-let [{:keys [from on select]} (:merge spec)]
              (let [idx (index-by (get results from) on)
                    m (first (get idx (left-key row on)))]
                (merge row (if select (select-keys m select) (apply dissoc m (vals on)))))
              row)
        row (reduce (fn [r [k {:keys [from on one?] :as sub}]]
                      (let [kids (get (index-by (get results from) on) (left-key r on))
                            kids (shape-rows kids sub results zone)]
                        (assoc r k (if one? (first kids) (vec kids)))))
                    row (:nest spec))
        row (reduce (fn [r [k f]] (assoc r k (f r))) row (::compute spec))
        row (reduce (fn [r [k t]] (if (contains? r k) (update r k #(coerce t % zone)) r)) row (:types spec))
        row (if-let [s (:select spec)] (select-keys row (concat s (keys (:nest spec)))) row)
        row (if-let [rn (:rename spec)] (clojure.set/rename-keys row rn) row)]
    row))

(defn shape-rows [rows spec results zone]
  (mapv #(apply-spec % spec results zone) rows))

(defn shape
  "→ vector of output rows (or one row / nil with :one?)."
  [spec results zone]
  (let [rows (get results (:from spec))
        rows (if (sequential? rows) rows (if (nil? rows) [] [rows]))
        out (shape-rows rows spec results zone)]
    (if (:one? spec) (first out) out)))
