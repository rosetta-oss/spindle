(ns spindle.env
  "Secret and environment references in configuration.

   `${NAME}` reads an environment variable, `${file:/path}` reads a file
   (trailing newline trimmed). Secrets are never written literally: a value under
   a secret key (:password, :token, :secret, :api-key, or an auth :value) that
   contains no `${…}` reference is rejected when the configuration loads."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]))

(def ^:dynamic *getenv* (fn [k] (System/getenv k)))

(def secret-keys #{:password :token :secret :api-key :value})

(defn- resolve-ref [ref]
  (if (str/starts-with? ref "file:")
    (let [f (java.io.File. (subs ref 5))]
      (when (.isFile f) (str/trimr (slurp f))))
    (*getenv* ref)))

(defn resolve-string
  "→ {:value s :missing [refs]}"
  [s]
  (let [missing (atom [])
        v (str/replace s #"\$\{([^}]+)\}"
                       (fn [[_ ref]]
                         (or (resolve-ref ref)
                             (do (swap! missing conj ref) ""))))]
    {:value v :missing @missing}))

(defn literal-secrets
  "Paths of secret-looking keys whose values are literal strings (no ${…})."
  [data]
  (let [found (atom [])]
    (letfn [(walk* [path x]
              (cond
                (map? x) (doseq [[k v] x]
                           (if (and (secret-keys k) (string? v) (seq v) (not (str/includes? v "${")))
                             (swap! found conj (conj path k))
                             (walk* (conj path k) v)))
                (sequential? x) (doseq [[i v] (map-indexed vector x)] (walk* (conj path i) v))
                :else nil))]
      (walk* [] data))
    @found))

(defn resolve-all
  "Resolve every string in `data`. → {:value data' :missing #{refs}}."
  [data]
  (let [missing (atom #{})
        v (walk/postwalk (fn [x]
                           (if (and (string? x) (str/includes? x "${"))
                             (let [{:keys [value] m :missing} (resolve-string x)]
                               (swap! missing into m)
                               value)
                             x))
                         data)]
    {:value v :missing @missing}))
