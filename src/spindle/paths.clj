(ns spindle.paths
  "Data paths: how steps refer to request parameters and earlier results.

     [:params :order_id]            a request parameter
     [:caller :sub]                 the authenticated caller
     [:page :limit]                 keyset paging: page size + 1; [:page :after_<key>]
                                    the last key of the previous page (nil on page 1)
     [:order :customerid]           column of step :order's rows → distinct non-nil values
     [:stock :items 0 :on_hand]     nested path into each row / JSON value

   A path into a step always yields a list of distinct non-nil values. Where a
   single value is needed (a scalar SQL placeholder, an HTTP path segment), the
   list must have exactly one element; more than one is an error, never a guess.")

(defn step-ref [path] (when (and (vector? path) (keyword? (first path))
                                 (not (#{:params :caller :fan :page} (first path))))
                        (first path)))

(defn resolve-path
  "→ the value for `path` given request `params`, `caller` and step `results`
   ({step-id rows-or-value})."
  [path {:keys [params caller results fan page strict]}]
  (let [[root & more] path]
    (case root
      :params (get-in params more)
      :fan (get-in fan more)
      :page (get-in page more)
      :caller (get-in caller more)
      (let [rows (get results root)
            rows (if (sequential? rows) rows (when (some? rows) [rows]))]
        ;; SQL rows always carry every column: a path naming a column none of
        ;; them has is a typo in the flow, not "no value"
        (when (and (contains? strict root) (seq more) (seq rows) (map? (first rows))
                   (not-any? #(contains? % (first more)) rows))
          (throw (ex-info (str "step " root " has no column " (first more)
                               " (columns: " (pr-str (vec (keys (first rows)))) ")")
                          {:spindle/code :internal :path path})))
        (->> rows
             (map #(get-in % more))
             (mapcat #(if (sequential? %) % [%]))
             (remove nil?)
             distinct
             vec)))))

(defn as-scalar
  "Collapse a resolved value for a scalar slot. → {:value v} | {:empty true}."
  [v slot]
  (cond
    (not (sequential? v)) (if (nil? v) {:empty true} {:value v})
    (empty? v) {:empty true}
    (= 1 (count v)) {:value (first v)}
    :else (throw (ex-info (str "path for " (name slot) " yields " (count v) " values; a single value is required")
                          {:spindle/code :input_rejected :slot slot}))))

(defn as-list [v]
  (cond (nil? v) [] (sequential? v) (vec v) :else [v]))

(defn path?
  "A data path is a vector whose first element is a keyword ([:params :id]);
   any other value in a request template is a literal."
  [x] (and (vector? x) (keyword? (first x))))

(defn resolve-template
  "Resolve an HTTP request template: data paths become their values, maps and
   vectors are walked, anything else is a literal. E.g.
   {:orderNos [:params :order_no] :includeDeleted true :orderTypes [1]} or
   [{:orderNo [:params :order_no] :orderType 8}] for a JSON array body."
  [t env]
  (cond (path? t) (resolve-path t env)
        (map? t) (into {} (map (fn [[k v]] [k (resolve-template v env)])) t)
        (vector? t) (mapv #(resolve-template % env) t)
        :else t))

(defn template-paths
  "Every data path inside a request template."
  [t]
  (cond (path? t) [t]
        (map? t) (mapcat template-paths (vals t))
        (vector? t) (mapcat template-paths t)
        :else []))
