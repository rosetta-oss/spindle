(ns spindle.jwt
  "JWT bearer tokens from configured issuers (design §12).

   auth.edn:
     {:jwt [{:id \"corp\"                          ; names the issuer in logs and audit
             :issuer \"https://idp.example.com/\"   ; must equal the token's iss
             :jwks-url \"https://idp.example.com/.well-known/jwks.json\"
             :audience \"spindle\"                   ; must be in the token's aud
             :scope-claim \"scope\"                  ; default: scope, else scp
             :algs [\"RS256\" \"ES256\"]             ; default RS256, ES256, PS256
             :clock-skew-s 60}]}

   Checked: signature (key from the issuer's JWKS, cached and refreshed by
   kid), exp and nbf (with the skew), iss, aud, sub present. The JWKS URL must
   be https, except on localhost.

   A verified token becomes the caller {:client \"jwt:<id>\" :sub <sub>
   :claims {...} :scopes #{...}}: the token's subject is the end user, so
   [:caller :sub] and [:caller :claims ...] work as with delegated identity."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [com.nimbusds.jose JWSAlgorithm]
           [com.nimbusds.jose.jwk.source JWKSourceBuilder]
           [com.nimbusds.jose.proc JWSVerificationKeySelector SecurityContext]
           [com.nimbusds.jwt JWTClaimsSet$Builder]
           [com.nimbusds.jwt.proc DefaultJWTClaimsVerifier DefaultJWTProcessor]
           [java.net URI]))

(defn- local-host? [^URI u] (contains? #{"localhost" "127.0.0.1" "::1" "[::1]"} (.getHost u)))

(defn config-problems [jwt-cfgs]
  (for [[i {:keys [id issuer jwks-url audience]}] (map-indexed vector jwt-cfgs)
        p (concat
           (when-not (and (string? id) (re-matches #"[a-z0-9][a-z0-9._-]{0,62}" id)) ["id must be a short lowercase name"])
           (when-not (string? issuer) [":issuer is required"])
           (when-not (string? audience) [":audience is required"])
           (if-not (string? jwks-url)
             [":jwks-url is required"]
             (let [u (try (URI. jwks-url) (catch Exception _ nil))]
               (when-not (and u (or (= "https" (.getScheme u)) (and (= "http" (.getScheme u)) (local-host? u))))
                 [":jwks-url must be https (http only on localhost)"]))))]
    {:path ["auth.edn" :jwt i] :message p}))

(defn- processor [{:keys [issuer jwks-url audience algs clock-skew-s]}]
  (let [source (-> (JWKSourceBuilder/create (.toURL (URI. jwks-url)))
                   (.retrying true)
                   (.build))
        algs (set (map #(JWSAlgorithm/parse ^String %) (or algs ["RS256" "ES256" "PS256"])))
        verifier (doto (DefaultJWTClaimsVerifier.
                        (java.util.HashSet. ^java.util.Collection [audience])
                        (.build (.issuer (JWTClaimsSet$Builder.) issuer))
                        (java.util.HashSet. ^java.util.Collection ["sub" "exp"])
                        nil)
                   (.setMaxClockSkew (int (or clock-skew-s 60))))]
    (doto (DefaultJWTProcessor.)
      (.setJWSKeySelector (JWSVerificationKeySelector. ^java.util.Set algs source))
      (.setJWTClaimsSetVerifier verifier))))

(defn compile-issuers [jwt-cfgs]
  (mapv (fn [c] (assoc c :processor (processor c))) jwt-cfgs))

(defn jwt-shaped? [^String token]
  (and (str/starts-with? token "eyJ") (= 2 (count (filter #{\.} token)))))

(defn- scopes-of [claims scope-claim]
  (let [v (or (get claims (keyword (or scope-claim "scope"))) (when-not scope-claim (:scp claims)))]
    (set (cond (string? v) (remove str/blank? (str/split v #"\s+"))
               (sequential? v) (map str v)
               :else []))))

(defn- ->clj [x]
  (cond (instance? java.util.Map x) (into {} (map (fn [[k v]] [(keyword k) (->clj v)])) x)
        (instance? java.util.List x) (mapv ->clj x)
        :else x))

(defn verify
  "→ caller map for a valid token from one of `issuers`, else nil."
  [issuers ^String token]
  (some (fn [{:keys [id processor scope-claim]}]
          (try
            (let [cs (.process ^DefaultJWTProcessor processor token (cast SecurityContext nil))
                  claims (->clj (.toJSONObject cs))]
              {:client (str "jwt:" id) :id (str "jwt:" id) :sub (.getSubject cs)
               :claims (dissoc claims :iss :aud :exp :nbf :iat :jti :sub)
               :scopes (scopes-of claims scope-claim) :jwt true})
            (catch Exception e
              (log/debug (str "event=jwt-rejected issuer=" id " reason=" (.getMessage e)))
              nil)))
        issuers))
