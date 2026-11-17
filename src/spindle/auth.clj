(ns spindle.auth
  "Caller authentication and end-user identity passthrough.

   1. The calling service authenticates with an API key (stored as SHA-256 in auth.edn):
        Authorization: Bearer <key>      or      X-Api-Key: <key>

   2. A service acting for an end user (e.g. a semantic layer serving a person)
      passes that user's identity — only if its key holds the scope
      \"spindle:delegate\":
        X-Spindle-User:        <subject>                      (opaque, ≤ 256 chars)
        X-Spindle-User-Claims: <base64url(JSON object)>       (optional, ≤ 4 KB)
      e.g. claims {\"territory_id\": 4, \"roles\": [\"sales\"]}.
      Presenting these headers without the scope is refused (403), never ignored.

   Inside flows the caller is the data path root :caller:
     [:caller :client]  the API key id           [:caller :sub]  the end user
     [:caller :claims :territory_id]              a claim
   Steps that use the caller (or connections that forward it) are cached per
   caller automatically. Endpoints may declare :caller :required.

   auth.edn:
     {:api-keys [{:id \"semantic\" :sha256 \"<hex>\" :scopes #{\"sales:read\" \"spindle:delegate\"}}]
      :admin-scope \"spindle:admin\"}"
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [spindle.error :as err]
            [spindle.tokens :as tokens])
  (:import [java.security MessageDigest]
           [java.util Base64]))

(def delegate-scope "spindle:delegate")

(defn sha256-hex [^String s]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) d))))

(defn- constant-time= [^String a ^String b]
  (MessageDigest/isEqual (.getBytes (str a) "UTF-8") (.getBytes (str b) "UTF-8")))

(defn compile-auth [{:keys [api-keys admin-scope]}]
  {:keys (mapv (fn [{:keys [id sha256 scopes]}] {:id id :sha256 (str/lower-case (str sha256)) :scopes (set scopes)}) api-keys)
   :admin-scope (or admin-scope "spindle:admin")})

(defn- presented-key [req]
  (let [h (get-in req [:headers "authorization"])]
    (or (when (and h (str/starts-with? (str/lower-case h) "bearer ")) (str/trim (subs h 7)))
        (get-in req [:headers "x-api-key"]))))

(defn- decode-claims [^String s]
  (when-not (str/blank? s)
    (when (> (count s) 5462) (err/fail! :input_rejected "X-Spindle-User-Claims too large"))
    (let [m (try (json/read-value (String. (.decode (Base64/getUrlDecoder) (str/trim s)) "UTF-8")
                                  (json/object-mapper {:decode-key-fn true}))
                 (catch Exception _ (err/fail! :input_rejected "X-Spindle-User-Claims is not base64url JSON")))]
      (when-not (map? m) (err/fail! :input_rejected "X-Spindle-User-Claims must be a JSON object"))
      m)))

(defn- end-user [req caller]
  (let [sub (get-in req [:headers "x-spindle-user"])
        claims (get-in req [:headers "x-spindle-user-claims"])]
    (when (or sub claims)
      (when-not (contains? (:scopes caller) delegate-scope)
        (err/fail! :forbidden "this key may not act for end users (scope spindle:delegate)"))
      (when (and sub (> (count sub) 256)) (err/fail! :input_rejected "X-Spindle-User too long"))
      {:sub (some-> sub str/trim) :claims (or (decode-claims claims) {})})))

(defn authenticate
  "→ caller {:client id :scopes #{..} :sub? :claims?} or nil when no/unknown key.
   auth.edn keys are tried first, then the token store (when non-nil)."
  ([auth req] (authenticate auth nil req))
  ([auth store req]
   (when-let [k (presented-key req)]
     (let [h (sha256-hex k)
           entry (or (some #(when (constant-time= (:sha256 %) h) %) (:keys auth))
                     (when store
                       (when-let [{:keys [id scopes]} (tokens/lookup store h)]
                         {:id id :scopes (set scopes)})))]
       (when entry
         (let [caller {:client (:id entry) :id (:id entry) :scopes (:scopes entry)}]
           (merge caller (end-user req caller))))))))

(defn allowed? [caller required]
  (and caller (every? (:scopes caller) required)))

(defn claims-header
  "Helper for callers and tests: claims map → header value."
  [m]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) (.getBytes (json/write-value-as-string m) "UTF-8")))
