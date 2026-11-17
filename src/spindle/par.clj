(ns spindle.par
  "Bounded parallel map on virtual threads, results in input order."
  (:require [promesa.core :as p]
            [promesa.exec :as px])
  (:import [java.util.concurrent ExecutionException Semaphore]))

(defn bounded-map
  "Apply `f` to each of `xs` with at most `n` running at once. Returns results in
   order; the first failure is thrown (calls not yet started are skipped)."
  [n f xs]
  (let [xs (vec xs)]
    (if (or (<= n 1) (<= (count xs) 1))
      (mapv f xs)
      (let [sem (Semaphore. (int n))
            failed (volatile! false)
            futs (mapv (fn [x]
                         (px/submit :vthread
                                    (fn []
                                      (.acquire sem)
                                      (try (if @failed ::skipped (f x))
                                           (catch Throwable t (vreset! failed true) (throw t))
                                           (finally (.release sem))))))
                       xs)]
        (try
          (mapv (fn [fut] (let [v @fut] v)) futs)
          (catch ExecutionException e (throw (or (.getCause e) e)))
          (finally (doseq [fut futs] (p/cancel! fut))))))))
