(ns spindle.error
  "Classified failures. Every failure that leaves Spindle carries a stable `:spindle/code`;
   driver and upstream messages never reach callers.")

(def http-status
  {:input_rejected 422
   :unauthenticated 401
   :forbidden 403
   :not_found 404
   :conflict 409
   :too_many_rows 413
   :rate_limited 429
   :unavailable 503
   :timeout 504
   :source_error 502
   :assertion_failed 502
   :internal 500})

(defn fail!
  "Throw a classified failure. `data` may carry :step, :conn, :retry-after-s, :details."
  ([code msg] (fail! code msg nil))
  ([code msg data] (fail! code msg data nil))
  ([code msg data cause]
   (throw (ex-info msg (merge {:spindle/code code} data) cause))))

(defn code-of [e]
  (loop [t e depth 0]
    (cond
      (nil? t) nil
      (> depth 8) nil
      (:spindle/code (ex-data t)) (ex-data t)
      :else (recur (.getCause ^Throwable t) (inc depth)))))

(defn breaker-failure?
  "Does this failure say something about the source's health? Input errors and caps do not."
  [code]
  (contains? #{:unavailable :timeout :source_error} code))
