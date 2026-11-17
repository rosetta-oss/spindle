(ns spindle.jwt-test
  "JWT bearer tokens: an in-process JWKS, tokens signed in the test."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [ring.adapter.jetty9 :as jetty]
            [spindle.auth :as auth]
            [spindle.config :as config]
            [spindle.explain]
            [spindle.server :as server])
  (:import [com.nimbusds.jose JWSAlgorithm JWSHeader$Builder]
           [com.nimbusds.jose.crypto RSASSASigner]
           [com.nimbusds.jose.jwk JWKSet RSAKey]
           [com.nimbusds.jose.jwk.gen RSAKeyGenerator]
           [com.nimbusds.jwt JWTClaimsSet$Builder SignedJWT]
           [java.util Date]))

(def good-key (.generate (.keyID (RSAKeyGenerator. 2048) "k1")))
(def other-key (.generate (.keyID (RSAKeyGenerator. 2048) "k1")))   ; same kid, wrong key

(defonce jwks (delay (jetty/run-jetty
                      (fn [_] {:status 200 :headers {"Content-Type" "application/json"}
                               :body (.toString (JWKSet. ^RSAKey (.toPublicJWK ^RSAKey good-key)))})
                      {:port 0 :join? false})))
(defn- jwks-url [] (str "http://127.0.0.1:" (.getLocalPort ^org.eclipse.jetty.server.ServerConnector
                                                          (first (.getConnectors ^org.eclipse.jetty.server.Server @jwks)))
                        "/jwks"))

(defn- token [{:keys [key iss aud sub exp-s scope claims] :or {key good-key iss "https://idp.test/" aud "spindle"
                                                                sub "alice" exp-s 300 scope "read"}}]
  (let [b (cond-> (-> (JWTClaimsSet$Builder.) (.issuer iss) (.audience ^String aud)
                      (.expirationTime (Date. (+ (System/currentTimeMillis) (* 1000 exp-s)))))
            sub (.subject sub)
            scope (.claim "scope" scope))
        b (reduce (fn [b [k v]] (.claim b (name k) v)) b claims)
        jwt (SignedJWT. (.build (.keyID (JWSHeader$Builder. JWSAlgorithm/RS256) "k1")) (.build b))]
    (.sign jwt (RSASSASigner. ^RSAKey key))
    (.serialize jwt)))

(def ^:dynamic *dir* nil)
(defn- write! [rel text] (let [f (io/file *dir* rel)] (io/make-parents f) (spit f text)))

(use-fixtures :each
  (fn [t]
    (let [root (doto (io/file (System/getProperty "java.io.tmpdir") (str "spindle-jwt-" (System/nanoTime))) (.mkdirs))
          db (str (io/file root "j.db"))]
      (jdbc/execute! {:jdbcUrl (str "jdbc:sqlite:" db)} ["create table t (owner text, n integer)"])
      (jdbc/execute! {:jdbcUrl (str "jdbc:sqlite:" db)} ["insert into t values ('alice', 1), ('bob', 2)"])
      (reset! config/state {:snapshot nil :history [] :last-reload nil :pinned false})
      (binding [*dir* (io/file root "config")
                spindle.explain/*archive-dir* (str (io/file root "explain"))]
        (write! "connections.edn" (pr-str {:db {:type :sql :dialect :sqlite :jdbc-url (str "jdbc:sqlite:" db)
                                                :defaults {:timeout "2s" :max-rows 100}}}))
        (write! "auth.edn" (pr-str {:api-keys [{:id "svc" :sha256 (auth/sha256-hex "svc-key") :scopes #{"read"}}]
                                    :jwt [{:id "idp" :issuer "https://idp.test/" :jwks-url (jwks-url) :audience "spindle"}]}))
        (write! "flows/t/mine.edn"
                (pr-str {:steps [{:id :m :kind :sql :conn :db :in {:me [:caller :sub]}
                                  :sql "select owner, n from t where owner = :me"}]
                         :endpoint {:path "/mine" :scopes #{"read"} :caller :required}}))
        (let [r (config/reload! (str *dir*))] (is (:ok r) (pr-str r)))
        (t)))))

(defn- call [bearer & [headers]]
  (let [h (server/handler {:node-id "t" :config-dir (str *dir*)})
        r (h {:request-method :get :uri "/mine" :headers (merge {"authorization" (str "Bearer " bearer)} headers)})]
    (assoc r :json (json/read-value (:body r) (json/object-mapper {:decode-key-fn true})))))

(deftest jwt-callers
  (testing "a valid token: its subject is the end user"
    (let [r (call (token {}))]
      (is (= 200 (:status r)) (pr-str (:json r)))
      (is (= [{:owner "alice" :n 1}] (get-in r [:json :rows])))))
  (testing "rejected: wrong key, expired, wrong audience, wrong issuer, no subject"
    (doseq [[why t] [["wrong key" (token {:key other-key})]
                     ["expired" (token {:exp-s -120})]
                     ["audience" (token {:aud "other"})]
                     ["issuer" (token {:iss "https://evil.test/"})]
                     ["no sub" (token {:sub nil})]]]
      (is (= 401 (:status (call t))) why)))
  (testing "scopes come from the scope claim"
    (is (= 403 (:status (call (token {:scope "other"}))))))
  (testing "delegation headers still need spindle:delegate"
    (is (= 403 (:status (call (token {}) {"x-spindle-user" "bob"}))))
    (is (= [{:owner "bob" :n 2}]
           (get-in (call (token {:scope "read spindle:delegate"}) {"x-spindle-user" "bob"}) [:json :rows]))))
  (testing "API keys keep working next to JWT"
    (is (= 401 (:status (call "svc-key"))) "no end user → 401 on a :caller :required endpoint, but the key is accepted")))

(deftest jwt-config-checks
  (write! "auth.edn" (pr-str {:jwt [{:id "x" :issuer "i" :audience "a" :jwks-url "http://idp.example.com/jwks"}]}))
  (is (some #(re-find #"must be https" (:message %)) (:problems (config/reload! (str *dir*))))))
