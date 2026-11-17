(ns spindle.auth
  "Caller authentication. v0.1: API keys, stored as SHA-256 hashes in auth.edn.

     {:api-keys [{:id \"semalith\" :sha256 \"<hex>\" :scopes #{\"sales:read\"}}]
      :admin-scope \"spindle:admin\"}

   A caller sends `Authorization: Bearer <key>` or `X-Api-Key: <key>`.
   Each endpoint lists the scopes it requires; a caller must hold all of them.
   JWT (issuers + JWKS) is planned (see docs/design)."
  (:require [clojure.string :as str])
  (:import [java.security MessageDigest]))

(defn sha256-hex [^String s]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) d))))

(defn- constant-time= [^String a ^String b]
  (MessageDigest/isEqual (.getBytes (str a) "UTF-8") (.getBytes (str b) "UTF-8")))

(defn compile-auth [{:keys [api-keys admin-scope]}]
  {:keys (mapv (fn [{:keys [id sha256 scopes]}] {:id id :sha256 (str/lower-case sha256) :scopes (set scopes)}) api-keys)
   :admin-scope (or admin-scope "spindle:admin")})

(defn- presented-key [req]
  (let [h (get-in req [:headers "authorization"])]
    (or (when (and h (str/starts-with? (str/lower-case h) "bearer ")) (str/trim (subs h 7)))
        (get-in req [:headers "x-api-key"]))))

(defn authenticate
  "→ caller map {:id .. :scopes #{..} :kind :api-key} or nil."
  [auth req]
  (when-let [k (presented-key req)]
    (let [h (sha256-hex k)]
      (some (fn [{:keys [sha256] :as entry}]
              (when (constant-time= sha256 h)
                {:id (:id entry) :scopes (:scopes entry) :kind :api-key}))
            (:keys auth)))))

(defn allowed? [caller required]
  (and caller (every? (:scopes caller) required)))
