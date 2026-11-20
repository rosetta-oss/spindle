(ns spindle.output
  "Shaping step results into one output — the flow's final result stage.
   Order per row: merge → nest → compute (SCI) → derive ([:op ...]) → types →
   assert → select → rename.

   :derive  {field expr} or [[field expr] ...] (in order; later fields may use
            earlier ones) — see spindle.result
   :assert  [assertion ...] over each top-level output row, before select
   :on-fail :flag (default: failures are listed in meta.checks) | :error
            (the request fails with code assertion_failed)"
  (:require [clojure.set]
            [clojure.string :as str]
            [spindle.expr :as expr]
            [spindle.result :as result])
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

(defn- derive-pairs [d] (if (map? d) (seq d) d))

(defn spec-problems
  "Problems in the declarative parts of an output spec (recursively), as strings."
  [spec]
  (concat
   (for [[k e] (derive-pairs (:derive spec))
         p (if (keyword? k) (result/expr-problems e) [(str "derive key must be a keyword: " (pr-str k))])]
     (str "derive " k ": " p))
   (for [a (:assert spec) p (result/assert-problems a)] (str "assert: " p))
   (when-not (contains? #{nil :flag :error} (:on-fail spec)) [":on-fail is :flag or :error"])
   (mapcat (fn [[_ s]] (spec-problems s)) (:nest spec))))

(defn compile-spec
  "Compile :compute expressions inside an output spec (recursively)."
  [spec]
  (when-let [ps (seq (spec-problems spec))]
    (throw (ex-info (str/join "; " ps) {:problems ps})))
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

(defn- param-values
  "A parameter as a set of trimmed strings: comma list, number, or list. nil when empty (no filter)."
  [v]
  (let [xs (cond (nil? v) []
                 (sequential? v) (map str v)
                 :else (clojure.string/split (str v) #","))
        xs (into #{} (comp (map clojure.string/trim) (remove clojure.string/blank?)) xs)]
    (when (seq xs) xs)))

(declare keep-row?)

(defn- keep-one? [{:keys [param any-of]} results row]
  (if-let [want (param-values (get-in results [::params param]))]
    (boolean (some (fn [c] (let [v (get row c)] (and (some? v) (contains? want (clojure.string/trim (str v)))))) any-of))
    true))

(defn keep-row?
  "One check {:param :any-of} or a vector of checks (all must pass)."
  [k results row]
  (if (map? k) (keep-one? k results row) (every? #(keep-one? % results row) k)))

(defn- apply-spec [row spec results zone]
  (let [row (if-let [{:keys [from on select pick]} (:merge spec)]
              (let [idx (index-by (get results from) on)
                    ms (get idx (left-key row on))
                    _ (when (and (next ms) (not= :first pick))
                        (throw (ex-info (str "merge from " from " found " (count ms) " rows for " (pr-str (left-key row on))
                                             "; use :nest for several, or :pick :first")
                                        {:spindle/code :internal})))
                    m (first ms)]
                (merge row (if select (select-keys m select) (apply dissoc m (vals on)))))
              row)]
    (when (or (nil? (:keep-if spec)) (keep-row? (:keep-if spec) results row))
     (let [row (reduce (fn [r [k {:keys [from on one?] :as sub}]]
                      (let [kids (get (index-by (get results from) on) (left-key r on))
                            kids (shape-rows kids sub results zone)]
                        (assoc r k (if one? (first kids) (vec kids)))))
                    row (:nest spec))
        row (reduce (fn [r [k f]] (assoc r k (f r))) row (::compute spec))
        row (reduce (fn [r [k e]] (assoc r k (result/value e r))) row (derive-pairs (:derive spec)))
        row (reduce (fn [r [k t]] (if (contains? r k) (update r k #(coerce t % zone)) r)) row (:types spec))
        row (if-let [as (seq (::top-assert spec))]
              (assoc row ::checks (vec (for [a as :let [r (result/check a row)] :when (not= :pass r)]
                                         {:assert a :result r})))
              row)
        row (if-let [s (:select spec)] (select-keys row (concat s (keys (:nest spec)) [::checks])) row)
        row (if-let [rn (:rename spec)] (clojure.set/rename-keys row rn) row)
        ;; a :hidden nest feeds :compute / :derive but is not returned (e.g. raw lists with cost fields)
        row (reduce (fn [r [k sub]] (if (:hidden sub) (dissoc r k) r)) row (:nest spec))]
    row))))

(defn shape-rows [rows spec results zone]
  (into [] (keep #(apply-spec % spec results zone)) rows))

(defn shape-with-checks
  "→ {:rows vector-or-one-row :checks [{:row i :assert a :result :fail|:unknown}]}."
  [spec results zone]
  (let [rows (get results (:from spec))
        rows (if (sequential? rows) rows (if (nil? rows) [] [rows]))
        out (shape-rows rows (assoc spec ::top-assert (:assert spec)) results zone)
        checks (vec (for [[i r] (map-indexed vector out) c (::checks r)] (assoc c :row i)))
        out (mapv #(dissoc % ::checks) out)]
    {:rows (if (:one? spec) (first out) out) :checks checks}))

(defn shape
  "→ vector of output rows (or one row / nil with :one?)."
  [spec results zone]
  (:rows (shape-with-checks spec results zone)))
