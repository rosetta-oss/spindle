(ns spindle.web
  "Shared HTTP helpers: the HTTPS guard and JSON error responses."
  (:require [clojure.string :as str]
            [jsonista.core :as json]))

(def ^:dynamic *env-trust-forwarded*
  "Returns the value of SPINDLE_TRUST_FORWARDED_PROTO (injectable for tests)."
  #(System/getenv "SPINDLE_TRUST_FORWARDED_PROTO"))

(defn- loopback?
  "True for a loopback IP literal, with or without Jetty's IPv6 brackets.
   Only literals are parsed, so no DNS lookup can happen."
  [addr]
  (boolean
   (when (string? addr)
     (let [a (str/replace addr #"^\[|\]$" "")]
       (when (re-matches #"[0-9A-Fa-f:.]+" a)
         (try (.isLoopbackAddress (java.net.InetAddress/getByName a))
              (catch Exception _ false)))))))

(defn secure?
  "True when the request arrived over a trusted channel: loopback, TLS, or a
   proxy-terminated TLS (X-Forwarded-Proto: https) unless disabled with
   SPINDLE_TRUST_FORWARDED_PROTO=false."
  [req]
  (boolean
   (or (loopback? (:remote-addr req))
       (= :https (:scheme req))
       (and (= "https" (get-in req [:headers "x-forwarded-proto"]))
            (not= "false" (*env-trust-forwarded*))))))

(defn insecure-resp []
  {:status 403
   :headers {"Content-Type" "application/json"}
   :body (json/write-value-as-string {:error {:code "https_required" :message "use HTTPS"}})})
