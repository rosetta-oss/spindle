(ns spindle.result
  "The final result stage: derived fields and assertions, written as data.

   A flow fetches with steps, then finalizes one result. Besides the SCI
   `:compute` (free-form), the output spec takes a small declarative language,
   the same shape as dossier's templates, so that agents and people can read
   and check it without running code:

   An expression, over one output row:
     :field                  the row's value
     12.5  \"text\"  true      a constant (numbers become decimals)
     [:+ a b ...]            sum of the operands that have a value (none → nil)
     [:- a b] [:* a b ...] [:/ a b]   every operand needed, else nil; / by 0 → nil
     [:round x n]            HALF_UP to n places
     [:coalesce a b ...]     the first operand with a value
     [:if c a b]             c is an assertion or expression (truthy)
     [:concat a b ...]       text join of the operands that have a value
     [:sum :part :f]         sum of f over the rows nested under :part
     [:count :part]          number of rows nested under :part
     [:get :part :f]         f of the single row nested under :part (one?)

   An assertion (over one output row) is :pass, :fail or :unknown (a value it
   needs is missing):
     [:= a b {:tolerance 0.01}]  [:not= a b]  [:< a b]  [:<= a b]  [:> a b]  [:>= a b]
     [:present :f ...]       every field has a value (blank text counts as missing)
     [:all c ...] [:any c ...] [:not c]

   Numbers compare as decimals within the tolerance (default 0); dates and text
   compare as they are.

   The same language is used for step conditions (:when [...]), where operands
   are data paths instead of row fields:
     [:exists [:cust]]   [:empty [:lines]]   [:> [:params :qty] 0]
     [:all [:exists [:cust]] [:= [:cust :status] \"A\"]]
   A path into a step yields its values: none → nil, one → that value, more →
   the list (which then compares as unequal)."
  (:require [clojure.string :as str])
  (:import [java.math BigDecimal MathContext RoundingMode]))

(def arithmetic #{:+ :- :* :/ :round :coalesce :if :concat :sum :count :get})
(def comparisons #{:= :not= :< :<= :> :>=})
(def logical #{:present :exists :empty :all :any :not})

(def operators (into (into arithmetic comparisons) logical))

;; In step conditions, vectors starting with one of these roots are data paths,
;; resolved by *resolve-path* (bound by the flow engine).
(def ^:dynamic *path-roots* #{})
(def ^:dynamic *resolve-path* nil)

(defn- path? [e] (and (vector? e) (contains? *path-roots* (first e))))

(defn- path-value [e]
  (let [v (*resolve-path* e)]
    (if (sequential? v) (case (count v) 0 nil 1 (first v) (vec v)) v)))

(defn- dec* ^BigDecimal [x]
  (cond (nil? x) nil
        (instance? BigDecimal x) x
        (number? x) (bigdec x)
        (string? x) (try (BigDecimal. (str/trim ^String x)) (catch NumberFormatException _ nil))
        :else nil))

(defn has? [v] (not (or (nil? v) (and (string? v) (str/blank? v)))))

(declare value check assert-problems)

(defn- part-rows [row part]
  (let [v (get row part)] (cond (sequential? v) v (map? v) [v] :else [])))

(defn value
  "Evaluate expression `e` over `row`."
  [e row]
  (cond
    (path? e) (path-value e)
    (keyword? e) (get row e)
    (vector? e)
    (let [[op & args] e
          vs (fn [] (map #(value % row) args))]
      (case op
        :+ (let [ns (keep dec* (vs))] (when (seq ns) (reduce #(.add ^BigDecimal %1 ^BigDecimal %2) ns)))
        :- (let [[a b] (map dec* (vs))] (when (and a b) (.subtract a b)))
        :* (let [ns (map dec* (vs))] (when (every? some? ns) (reduce #(.multiply ^BigDecimal %1 ^BigDecimal %2) ns)))
        :/ (let [[a b] (map dec* (vs))]
             (when (and a b (not (zero? (.signum b)))) (.stripTrailingZeros (.divide a b MathContext/DECIMAL64))))
        :round (let [[x n] args x (dec* (value x row))] (when x (.setScale x (int n) RoundingMode/HALF_UP)))
        :coalesce (some #(let [v (value % row)] (when (has? v) v)) args)
        :if (let [[c a b] args
                  t (if (and (vector? c) (or (comparisons (first c)) (logical (first c))))
                      (= :pass (check c row))
                      (boolean (value c row)))]
              (value (if t a b) row))
        :concat (str/join (keep #(let [v (value % row)] (when (has? v) (str v))) args))
        :sum (let [[part f] args ns (keep #(dec* (get % f)) (part-rows row part))]
               (if (seq ns) (reduce #(.add ^BigDecimal %1 ^BigDecimal %2) ns) BigDecimal/ZERO))
        :count (count (part-rows row (first args)))
        :get (let [[part f] args] (get (first (part-rows row part)) f))
        (throw (ex-info (str "unknown operator " op) {:op op}))))
    (number? e) (dec* e)
    :else e))

(defn- compare* [op a b {:keys [tolerance] :or {tolerance 0}}]
  (let [na (dec* a) nb (dec* b)]
    (if (and na nb (or (number? a) (number? b)))
      (let [d (.subtract na nb) tol (dec* tolerance)
            eq (<= (.compareTo (.abs d) tol) 0)
            c (if eq 0 (.signum d))]
        (case op := eq :not= (not eq) :< (neg? c) :<= (<= c 0) :> (pos? c) :>= (>= c 0)))
      (let [c (compare a b)]
        (case op := (= a b) :not= (not= a b) :< (neg? c) :<= (<= c 0) :> (pos? c) :>= (>= c 0))))))

(defn check
  "Evaluate assertion `a` over `row` → :pass | :fail | :unknown."
  [a row]
  (let [[op & args] a]
    (cond
      (comparisons op)
      (let [[x y opts] args a (value x row) b (value y row)]
        (if (or (not (has? a)) (not (has? b)))
          :unknown
          (if (try (compare* op a b (if (map? opts) opts {})) (catch ClassCastException _ false)) :pass :fail)))
      (= :present op) (if (every? #(has? (value % row)) args) :pass :fail)
      (= :exists op) (if (has? (value (first args) row)) :pass :fail)
      (= :empty op) (if (has? (value (first args) row)) :fail :pass)
      (= :all op) (let [rs (map #(check % row) args)]
                    (cond (some #{:fail} rs) :fail (some #{:unknown} rs) :unknown :else :pass))
      (= :any op) (let [rs (map #(check % row) args)]
                    (cond (some #{:pass} rs) :pass (some #{:unknown} rs) :unknown :else :fail))
      (= :not op) (case (check (first args) row) :pass :fail :fail :pass :unknown)
      :else (throw (ex-info (str "unknown assertion " op) {:op op})))))

;; ---------------------------------------------------------------------------
;; compile-time checks
;; ---------------------------------------------------------------------------

(defn expr-problems
  "Problems in an expression (as strings)."
  [e]
  (cond
    (path? e) nil
    (vector? e)
    (let [[op & args] e]
      (cond
        (not (keyword? op)) [(str "expression must start with an operator: " (pr-str e))]
        (arithmetic op)
        (concat
         (case op
           (:- :/) (when (not= 2 (count args)) [(str op " takes two operands")])
           :round (when-not (and (= 2 (count args)) (nat-int? (second args))) [":round takes [x places]"])
           :if (when (not= 3 (count args)) [":if takes [condition then else]"])
           (:sum :get) (when-not (and (= 2 (count args)) (every? keyword? args)) [(str op " takes [:part :field]")])
           :count (when-not (and (= 1 (count args)) (keyword? (first args))) [":count takes [:part]"])
           nil)
         (when-not (#{:sum :count :get :round} op)
           (mapcat #(if (and (= :if op) (vector? %) (or (comparisons (first %)) (logical (first %))))
                      (assert-problems %) (expr-problems %))
                   args)))
        :else [(str "unknown operator " op " in " (pr-str e))]))
    :else nil))

(defn assert-problems
  "Problems in an assertion (as strings)."
  [a]
  (if-not (vector? a)
    [(str "an assertion is a vector: " (pr-str a))]
    (let [[op & args] a]
      (cond
        (comparisons op) (let [[x y opts] args]
                           (concat (when-not (<= 2 (count args) 3) [(str op " takes [a b opts?]")])
                                   (when (and opts (not (map? opts))) [(str op " options must be a map")])
                                   (expr-problems x) (expr-problems y)))
        (= :present op) (when (empty? args) [":present needs at least one field"])
        (#{:exists :empty} op) (when-not (and (= 1 (count args)) (path? (first args)))
                                 [(str op " takes one data path, e.g. [" op " [:cust]]")])
        (#{:all :any} op) (mapcat assert-problems args)
        (= :not op) (assert-problems (first args))
        :else [(str "unknown assertion " op " in " (pr-str a))]))))

(defn paths-in
  "Data paths used in a condition (with *path-roots* bound)."
  [e]
  (cond (path? e) [e]
        (vector? e) (mapcat paths-in (rest e))
        :else nil))
