(ns spindle.paths
  "Data paths: how steps refer to request parameters and earlier results.

     [:params :order_id]            a request parameter
     [:caller :sub]                 the authenticated caller
     [:order :customerid]           column of step :order's rows → distinct non-nil values
     [:stock :items 0 :on_hand]     nested path into each row / JSON value

   A path into a step always yields a list of distinct non-nil values. Where a
   single value is needed (a scalar SQL placeholder, an HTTP path segment), the
   list must have exactly one element; more than one is an error, never a guess.")

(defn step-ref [path] (when (and (vector? path) (keyword? (first path))
                                 (not (#{:params :caller :fan} (first path))))
                        (first path)))

(defn resolve-path
  "→ the value for `path` given request `params`, `caller` and step `results`
   ({step-id rows-or-value})."
  [path {:keys [params caller results fan]}]
  (let [[root & more] path]
    (case root
      :params (get-in params more)
      :fan (get-in fan more)
      :caller (get-in caller more)
      (let [rows (get results root)
            rows (if (sequential? rows) rows (when (some? rows) [rows]))]
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
