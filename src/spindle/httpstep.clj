(ns spindle.httpstep
  "Executing an :http step against an :http connection.

   Request values are data paths (never string templates) or literals; a body
   may nest maps and arrays, and may itself be an array:
     {:method :get :path \"/inventory/{id}\" :path-params {:id [:params :id]}
      :query {\"product_id\" [:lines :productid]} :body {\"ids\" [:params :ids]}}
   A list in :query is encoded with the connection's :list-style
   (:csv \"1,2\", :repeat ?k=1&k=2, :postgrest-in \"in.(1,2)\").
   :fan-out <path> makes one call per value of that path; inside the request
   the current value is the path [:fan]."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [spindle.conn :as conn]
            [spindle.error :as err]
            [spindle.par :as par]
            [spindle.paths :as paths]
            [spindle.sql :as sql])
  (:import [java.net URLEncoder]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration]
           [java.util.concurrent CompletableFuture TimeUnit TimeoutException ExecutionException CancellationException]))

(def ^:private mapper (json/object-mapper {:decode-key-fn true}))

(defn- enc [x] (URLEncoder/encode (str x) StandardCharsets/UTF_8))

(defn- query-pairs [k v list-style]
  (if (sequential? v)
    (case list-style
      :repeat (map (fn [x] [k x]) v)
      :postgrest-in [[k (str "in.(" (str/join "," v) ")")]]
      [[k (str/join "," v)]])
    (when (some? v) [[k v]])))

(defn json-rows
  "Tiny JSON-path subset: $ | $[*] | $.a.b | $.a[*] | $.a[*].b, plus `@vals`:
   the values of an object whose keys are data (e.g. {\"<id>\": [...]}):
   $.data.@vals[*]"
  [data path]
  (let [p (or path "$")
        segs (->> (str/split (str/replace (subs p 1) #"\[\*\]" ".*") #"\.")
                  (remove str/blank?))]
    (letfn [(walk* [x [s & more]]
              (cond
                (nil? s) (if (sequential? x) x (when (some? x) [x]))
                (= s "*") (mapcat #(walk* % more) (if (sequential? x) x [x]))
                (= s "@vals") (walk* (if (map? x) (vec (vals x)) x) more)
                :else (walk* (get x (keyword s)) more)))]
      (vec (walk* data segs)))))

(defn- build-request [{:keys [cfg]} {:keys [method path path-params query body headers]} env deadline-ns]
  (let [path* (reduce (fn [p [k pth]]
                        (let [{:keys [value empty]} (paths/as-scalar (paths/resolve-path pth env) k)]
                          (when empty (err/fail! :input_rejected (str "no value for path parameter " (name k))))
                          (str/replace p (str "{" (name k) "}") (enc value))))
                      path path-params)
        qs (->> query
                (mapcat (fn [[k pth]] (query-pairs (name k) (paths/resolve-template pth env) (:list-style cfg))))
                (map (fn [[k v]] (str (enc k) "=" (if (and (string? v) (str/starts-with? v "in.(")) v (enc v)))))
                (str/join "&"))
        url (str (str/replace (:base-url cfg) #"/$" "") path* (when (seq qs) (str "?" qs)))
        remaining-ms (max 1 (quot (- deadline-ns (System/nanoTime)) 1000000))
        timeout-ms (min remaining-ms (conn/parse-duration-ms (get-in cfg [:defaults :timeout]) 5000))
        body-str (when body (json/write-value-as-string (paths/resolve-template body env)))
        b (-> (HttpRequest/newBuilder (java.net.URI. url))
              (.timeout (Duration/ofMillis timeout-ms))
              (.header "Accept" "application/json")
              (.header "User-Agent" "spindle"))]
    ;; static connection headers, step headers (literal or data path), and the
    ;; connection's :forward-caller headers ({header path}, e.g. {"X-User-Id" [:caller :sub]})
    (doseq [[k v] (merge (:headers cfg) headers (:forward-caller cfg))
            :let [v (if (vector? v)
                      (let [r (paths/resolve-path v env)] (if (sequential? r) (str/join "," r) r))
                      v)]
            :when (some? v)]
      (.header b (name k) (str v)))
    (when-let [{:keys [header value]} (:auth cfg)] (.header b header value))
    (case (or method :get)
      :get (.GET b)
      :post (.POST (.header b "Content-Type" "application/json")
                   (HttpRequest$BodyPublishers/ofString (or body-str "{}")))
      (:put :patch) (.method (.header b "Content-Type" "application/json") (str/upper-case (name method))
                             (HttpRequest$BodyPublishers/ofString (or body-str "{}")))
      :delete (.DELETE b))
    {:request (.build b) :timeout-ms timeout-ms}))

(defn- classify-status [s]
  (cond (= s 429) :rate_limited
        (#{502 503 504} s) :unavailable
        (>= s 500) :source_error
        :else :source_error))

(defn- call-once! [rt step env {:keys [deadline-ns cancels]}]
  (let [{:keys [request timeout-ms]} (build-request rt (:request step) env deadline-ns)
        ^HttpClient client (:client rt)
        ^CompletableFuture fut (.sendAsync client request (HttpResponse$BodyHandlers/ofString))
        k (Object.)]
    (sql/register-cancel! cancels k #(.cancel fut true))
    (try
      (let [resp (.get fut (+ timeout-ms 50) TimeUnit/MILLISECONDS)
            status (.statusCode resp)]
        (cond
          (<= 200 status 299)
          (let [b (.body resp)] (if (str/blank? b) nil (json/read-value b mapper)))
          (and (= 404 status) (:empty-on-404 step)) nil
          :else (err/fail! (classify-status status) (str "upstream status " status)
                           {:step (:id step) :conn (:id rt) :status status})))
      (catch CancellationException _ (err/fail! :timeout "request cancelled" {:step (:id step)}))
      (catch TimeoutException _ (.cancel fut true) (err/fail! :timeout "request timed out" {:step (:id step)}))
      (catch ExecutionException e
        (let [c (.getCause e)]
          (err/fail! (if (instance? java.net.http.HttpTimeoutException c) :timeout :unavailable)
                     "upstream call failed" {:step (:id step) :conn (:id rt)} c)))
      (finally (sql/deregister-cancel! cancels k)))))

(defn- fan-values
  "Values to fan out over. With :fan-where {col v | [v ...]} only rows of the
   source step whose columns match give a value (e.g. only rows of one type)."
  [{:keys [fan-out fan-where]} env]
  (if-not fan-where
    (paths/as-list (paths/resolve-path fan-out env))
    (let [[s & more] fan-out
          match? (fn [r] (every? (fn [[k v]] (let [x (get r k)] (if (vector? v) (some #(= x %) v) (= x v)))) fan-where))]
      (->> (get-in env [:results s]) paths/as-list (filter match?)
           (map #(get-in % more)) (remove nil?) distinct vec))))

(defn fetch!
  "Call the upstream; → vector of parsed response bodies (one per call; fan-out
   makes several). Bodies, not rows, are what the step cache stores, so several
   steps/endpoints projecting different parts of one response share one call.
   With :fan-key each body is kept with its fan value ({::fan v ::body b})."
  [rt step env ctx]
  (if (:fan-out step)
    (let [vs (fan-values step env)]
      ;; one call per value, up to :concurrency at once (default 4); the
      ;; connection's bulkhead still bounds calls across all requests
      (par/bounded-map (or (:concurrency step) 4)
                       (fn [v] (let [b (conn/guarded rt #(call-once! rt step (assoc env :fan v) ctx))]
                                 (if (:fan-key step) {::fan v ::body b} b)))
                       vs))
    [(conn/guarded rt #(call-once! rt step env ctx))]))

(defn extract
  "Apply the step's :rows JSON path to each body and concatenate. With
   :fan-key, every row gets the fan value it was fetched for under that key."
  [step bodies]
  (let [k (:fan-key step)]
    (vec (mapcat (fn [b]
                   (if k
                     (map #(if (map? %) (assoc % k (::fan b)) %) (when (some? (::body b)) (json-rows (::body b) (:rows step))))
                     (if (nil? b) [] (json-rows b (:rows step)))))
                 bodies))))

(defn run-step! [rt step env ctx] (extract step (fetch! rt step env ctx)))
