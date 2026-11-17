(ns spindle.web
  "Shared HTTP helpers: the HTTPS guard and JSON error responses."
  (:require [jsonista.core :as json]))

(def ^:dynamic *env-trust-forwarded*
  "Returns the value of SPINDLE_TRUST_FORWARDED_PROTO (injectable for tests)."
  #(System/getenv "SPINDLE_TRUST_FORWARDED_PROTO"))

(def ^:private loopback #{"127.0.0.1" "0:0:0:0:0:0:0:1" "::1"})

(defn secure?
  "True when the request arrived over a trusted channel: loopback, TLS, or a
   proxy-terminated TLS (X-Forwarded-Proto: https) unless disabled with
   SPINDLE_TRUST_FORWARDED_PROTO=false."
  [req]
  (boolean
   (or (contains? loopback (:remote-addr req))
       (= :https (:scheme req))
       (and (= "https" (get-in req [:headers "x-forwarded-proto"]))
            (not= "false" (*env-trust-forwarded*))))))

(defn insecure-resp []
  {:status 403
   :headers {"Content-Type" "application/json"}
   :body (json/write-value-as-string {:error {:code "https_required" :message "use HTTPS"}})})
