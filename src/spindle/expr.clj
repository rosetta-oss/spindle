(ns spindle.expr
  "Sandboxed expressions (SCI) for :compute fields and merge rules.

   - Only allow-listed vars and special forms; no I/O, no interop, no eval/def,
     no loop/recur/while and no infinite sequence makers.
   - Lazy sequences are bounded (:realize-max).
   - Free symbols in an expression are row columns: `(* orderqty unitprice)`
     reads the row's :orderqty and :unitprice. A column whose name is also an
     allowed function (`name`, `count`, `max`, …) is read as `(:name row)`;
     `row` is always bound to the whole row.
   - SCI has no timeout of its own: the caller runs shaping under the request
     deadline (see spindle.flow); the allowlist is what keeps an abandoned
     evaluation short, since the JVM cannot kill a thread."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [sci.core :as sci])
  (:import [java.math BigDecimal RoundingMode]))

(defn round-half-up [x places]
  (when (some? x)
    (.setScale (bigdec x) (int places) RoundingMode/HALF_UP)))

(defn parse-date [s] (when s (java.time.LocalDate/parse (str s))))

(def helpers
  {'round-half-up round-half-up
   'parse-date parse-date
   'blank? str/blank?})

(def allowed-core
  '[+ - * / quot rem mod inc dec max min abs
    = not= < > <= >= zero? pos? neg? even? odd? nil? some? true? false?
    not and or if when when-not cond case let fn if-let when-let if-some when-some do
    str subs format keyword name
    get get-in assoc dissoc select-keys keys vals contains? count first second last rest next nth
    seq empty? not-empty vec vector list hash-map set conj into concat distinct
    map mapv filter filterv remove reduce sort sort-by group-by frequencies partition-by
    some every? take drop take-while drop-while
    double long int bigdec boolean
    identity constantly comp partial juxt
    merge merge-with update update-in zipmap
    fn* let* if do quote case* throw])

(def denied (quote [loop recur while iterate repeat cycle repeatedly range eval resolve require def defn
              future pmap agent atom swap! reset! slurp spit Thread]))

(def string-ns
  {'upper-case str/upper-case 'lower-case str/lower-case 'trim str/trim 'blank? str/blank?
   'join str/join 'split str/split 'includes? str/includes? 'starts-with? str/starts-with?
   'ends-with? str/ends-with? 'replace str/replace})

(defn- ctx []
  (sci/init {:namespaces {'user helpers
                          'clojure.string string-ns
                          'str string-ns}
             :allow (into allowed-core (concat (keys helpers)
                                               (map #(symbol "clojure.string" (name %)) (keys string-ns))
                                               (map #(symbol "str" (name %)) (keys string-ns))))
             :deny denied
             :classes {}
             :realize-max 100000}))

(defn- free-symbols [form]
  (let [syms (atom #{})]
    (walk/postwalk (fn [x] (when (and (symbol? x) (nil? (namespace x))) (swap! syms conj x)) x) form)
    (->> @syms
         (remove (set allowed-core))
         (remove (set (keys helpers)))
         (remove #{'& '_ 'fn 'let 'if 'do 'quote 'row})
         ;; local bindings inside the expression are not columns; we only bind
         ;; symbols that are not otherwise defined — unknown columns read as nil
         set)))

(defn compile-expr
  "Expression form → (fn [row] value). Throws on forbidden symbols at load time."
  [form]
  (when-let [bad (seq (filter (set denied) (free-symbols form)))]
    (throw (ex-info (str "not allowed in expressions: " (str/join ", " bad))
                    {:spindle/code :input_rejected :symbols (vec bad)})))
  (let [cols (free-symbols form)
        wrapped (list 'fn '[row]
                      (list 'let (vec (mapcat (fn [s] [s (list 'get 'row (keyword (name s)))]) cols))
                            form))]
    (sci/eval-form (ctx) wrapped)))

(defn compile-pred
  "Like compile-expr but for a fn of one value bound to `%`/`x`."
  [form]
  (sci/eval-form (ctx) form))
