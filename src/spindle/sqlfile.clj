(ns spindle.sqlfile
  "SQL files: named blocks (`-- :name <id>`) and `:param` placeholders.

   SQL is never rewritten. The only changes Spindle makes to the text are
   replacing placeholders with `?` (or `(?, ?, …)` for lists) and a leading
   tag comment. The scanner skips string literals, quoted identifiers, comments
   and `::` casts, so `'a:b'` or `x::int` are never taken for placeholders."
  (:require [clojure.string :as str]))

(defn parse-blocks
  "Text of a .sql file → {\"block-name\" \"sql\"} in file order (array map semantics via vector of pairs)."
  [text]
  (let [lines (str/split-lines text)]
    (loop [ls lines cur nil buf [] out []]
      (if-let [l (first ls)]
        (if-let [[_ nm] (re-matches #"^\s*--\s*:name\s+([A-Za-z0-9_\-]+)\s*$" l)]
          (recur (rest ls) nm [] (cond-> out cur (conj [cur (str/trim (str/join "\n" buf))])))
          (recur (rest ls) cur (conj buf l) out))
        (let [out (cond-> out cur (conj [cur (str/trim (str/join "\n" buf))]))]
          (into {} out))))))

(defn- ident-start? [c] (or (Character/isLetter (char c)) (= \_ c)))
(defn- ident-part? [c] (or (Character/isLetterOrDigit (char c)) (= \_ c)))

(defn scan
  "Split SQL into a vector of literal strings and placeholder maps
   {:param kw :list? bool}. `:list?` is true when the placeholder directly
   follows the keyword IN (that is how the author asks for a list)."
  [^String sql]
  (let [n (count sql)
        sb (StringBuilder.)
        out (transient [])]
    (letfn [(flush! [] (when (pos? (.length sb)) (conj! out (.toString sb)) (.setLength sb 0)))]
      (loop [i 0]
        (if (>= i n)
          (do (flush!) (persistent! out))
          (let [c (.charAt sql i)]
            (cond
              ;; string literal '...' with '' escapes
              (= c \')
              (let [j (loop [j (inc i)]
                        (cond (>= j n) n
                              (and (= (.charAt sql j) \') (< (inc j) n) (= (.charAt sql (inc j)) \')) (recur (+ j 2))
                              (= (.charAt sql j) \') (inc j)
                              :else (recur (inc j))))]
                (.append sb (subs sql i j)) (recur j))
              ;; quoted identifier "..." or `...`
              (or (= c \") (= c \`))
              (let [j (let [k (str/index-of sql (str c) (inc i))] (if k (inc k) n))]
                (.append sb (subs sql i j)) (recur j))
              ;; line comment
              (and (= c \-) (< (inc i) n) (= (.charAt sql (inc i)) \-))
              (let [j (or (str/index-of sql "\n" i) n)]
                (.append sb (subs sql i j)) (recur j))
              ;; block comment
              (and (= c \/) (< (inc i) n) (= (.charAt sql (inc i)) \*))
              (let [j (let [k (str/index-of sql "*/" (+ i 2))] (if k (+ k 2) n))]
                (.append sb (subs sql i j)) (recur j))
              ;; PostgreSQL dollar quoting $$...$$ (tagless only)
              (and (= c \$) (< (inc i) n) (= (.charAt sql (inc i)) \$))
              (let [j (let [k (str/index-of sql "$$" (+ i 2))] (if k (+ k 2) n))]
                (.append sb (subs sql i j)) (recur j))
              ;; :: cast
              (and (= c \:) (< (inc i) n) (= (.charAt sql (inc i)) \:))
              (do (.append sb "::") (recur (+ i 2)))
              ;; placeholder
              (and (= c \:) (< (inc i) n) (ident-start? (.charAt sql (inc i)))
                   (or (zero? i) (not (ident-part? (.charAt sql (dec i))))))
              (let [j (loop [j (inc i)] (if (and (< j n) (ident-part? (.charAt sql j))) (recur (inc j)) j))
                    nm (subs sql (inc i) j)
                    before (str/trimr (.toString sb))
                    list? (boolean (re-find #"(?i)(^|[^A-Za-z0-9_])in$" before))]
                (flush!)
                (conj! out {:param (keyword nm) :list? list?})
                (recur j))
              :else (do (.append sb c) (recur (inc i))))))))))

(defn params-of
  "Placeholder names used in a scanned SQL (distinct, in order)."
  [scanned]
  (->> scanned (filter map?) (map :param) distinct vec))

(defn statement-kind
  "First keyword of the statement: :read for select/with/values/show/explain, :write otherwise.
   Used only to refuse writes where they are not allowed; never to rewrite."
  [sql]
  (let [s (-> sql
              (str/replace #"(?s)/\*.*?\*/" " ")
              (str/replace #"(?m)--.*$" " ")
              str/triml
              str/lower-case)
        kw (re-find #"^[a-z]+" s)]
    (cond
      (#{"select" "with" "values" "show" "explain" "describe" "desc" "table"} kw) :read
      (#{"exec" "execute" "call"} kw) :call
      :else :write)))

(defn render
  "Scanned SQL + values → [sql & args]. List placeholders expand to (?, ?, …);
   an empty list renders as (NULL), which matches nothing."
  [scanned values]
  (loop [parts scanned sb (StringBuilder.) args (transient [])]
    (if-let [p (first parts)]
      (if (string? p)
        (do (.append sb ^String p) (recur (rest parts) sb args))
        (let [v (get values (:param p))]
          (if (:list? p)
            (let [vs (vec v)]
              (if (empty? vs)
                (.append sb "(NULL)")
                (.append sb (str "(" (str/join ", " (repeat (count vs) "?")) ")")))
              (recur (rest parts) sb (reduce conj! args vs)))
            (do (.append sb "?") (recur (rest parts) sb (conj! args v))))))
      (into [(.toString sb)] (persistent! args)))))

(defn chunked-renders
  "Render once per chunk of the (single) list parameter that exceeds `max-in`.
   More than one oversized list parameter in one statement is refused: the
   cross product would multiply statements."
  [scanned values max-in]
  (let [list-params (->> scanned (filter #(and (map? %) (:list? %))) (map :param) distinct)
        big (filter #(> (count (get values %)) max-in) list-params)]
    (cond
      (empty? big) [(render scanned values)]
      (> (count big) 1) (throw (ex-info "more than one IN list exceeds the dialect's limit"
                                        {:spindle/code :input_rejected :params (vec big)}))
      :else (let [k (first big)]
              (mapv #(render scanned (assoc values k %)) (partition-all max-in (get values k)))))))
