(ns spindle.openapi
  "GET /openapi.json — an OpenAPI 3.1 document generated from the published
   endpoints: their malli parameter schemas, methods, scopes, batch and paging
   forms, the response envelope and the error codes."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.json-schema :as mjs]
            [spindle.error :as err]))

(defn- json-schema [s] (try (mjs/transform s) (catch Exception _ {})))

(defn- path-params [path] (set (map (comp keyword second) (re-seq #"\{([^}]+)\}" path))))

(defn- entries [schema]
  (when (and schema (= :map (m/type schema)))
    (for [[k props s] (m/children schema)]
      {:name k :required (not (:optional props)) :schema (json-schema s)})))

(def ^:private envelope
  {:type "object"
   :properties {:rows {:description "an array of rows, or one row (object) for single-row endpoints"}
                :next {:type ["string" "null"] :description "keyset cursor for the next page (paged endpoints)"}
                :meta {:type "object"
                       :properties {:endpoint {:type "string"} :config_version {:type "string"}
                                    :flow_version {:type "string"} :cache {:type "string"}
                                    :elapsed_ms {:type "integer"}
                                    :partial {:type "array" :items {:type "object"}}
                                    :checks {:type "array" :items {:type "object"}}}}}})

(def ^:private error-schema
  {:type "object"
   :properties {:error {:type "object"
                        :properties {:code {:type "string" :enum (mapv name (sort (keys err/http-status)))}
                                     :message {:type "string"} :request_id {:type "string"} :step {:type "string"}
                                     :outcome {:type "string"}}}}})

(defn- responses []
  (merge {"200" {:description "rows" :content {"application/json" {:schema {"$ref" "#/components/schemas/Envelope"}}}}}
         (into {} (for [st (sort (distinct (vals err/http-status)))]
                    [(str st) {:description (str/join ", " (sort (map name (keep (fn [[c s]] (when (= s st) c)) err/http-status))))
                               :content {"application/json" {:schema {"$ref" "#/components/schemas/Error"}}}}]))))

(defn- operation [{:keys [id scopes method batch page caller doc] :as ep} fl]
  (let [pp (path-params (:path ep))
        es (entries (:params-schema fl))
        query? (= :get method)
        params (concat
                (for [{:keys [name schema]} es :when (pp name)]
                  {:name (clojure.core/name name) :in "path" :required true :schema schema})
                (when query?
                  (for [{:keys [name required schema]} es :when (not (pp name))]
                    {:name (clojure.core/name name) :in "query" :required required
                     :schema (if (= "array" (:type schema))
                               {:type "string" :description "comma-separated list"} schema)}))
                (when page
                  [{:name "size" :in "query" :required false
                    :schema {:type "integer" :minimum 1 :maximum (:max-size page 500) :default (:size page 50)}}
                   {:name "after" :in "query" :required false :schema {:type "string"}
                    :description "the `next` cursor of the previous page"}])
                (when caller
                  [{:name "X-Spindle-User" :in "header" :required (= :required caller) :schema {:type "string"}
                    :description "end user (callers with scope spindle:delegate)"}]))
        body-entries (when-not query? (remove #(pp (:name %)) es))]
    (cond-> {:operationId id
             :summary (or doc (:doc fl) id)
             :security [{"bearer" (vec (sort scopes))} {"apiKey" (vec (sort scopes))}]
             :x-spindle-scopes (vec (sort scopes))
             :parameters (vec params)
             :responses (responses)}
      (seq body-entries)
      (assoc :requestBody {:required true
                           :content {"application/json"
                                     {:schema {:type "object"
                                               :properties (into {} (map (fn [e] [(name (:name e)) (:schema e)])) body-entries)
                                               :required (vec (keep #(when (:required %) (name (:name %))) body-entries))}}}})
      (:mutation ep) (assoc :x-spindle-mutation true
                            :description "Mutation: send Idempotency-Key to make a retry safe."))))

(defn- batch-operation [{:keys [id batch scopes] :as ep} fl]
  (let [es (entries (:params-schema fl))]
    {:operationId (str id ":batch")
     :summary (str "Batch form of " id ": one call for many " (name (:key batch)))
     :security [{"bearer" (vec (sort scopes))} {"apiKey" (vec (sort scopes))}]
     :requestBody {:required true
                   :content {"application/json"
                             {:schema {:type "object"
                                       :properties (into {} (map (fn [e] [(name (:name e)) (:schema e)])) es)
                                       :required [(name (:key batch))]}}}}
     :responses (responses)}))

(defn- oa-path [p] p)

(defn document [snap]
  {:openapi "3.1.0"
   :info {:title "Spindle" :version (str (:version snap))}
   :paths (reduce
           (fn [acc ep]
             (let [fl (get-in snap [:flows (:flow ep)])
                   acc (assoc-in acc [(oa-path (:path ep)) (name (:method ep))] (operation ep fl))]
               (if (:batch ep)
                 (assoc-in acc [(str "/api/_batch/" (:id ep)) "post"] (batch-operation ep fl))
                 acc)))
           (sorted-map) (:endpoints snap))
   :components {:securitySchemes {"bearer" {:type "http" :scheme "bearer"
                                            :description "API key or JWT as a bearer token"}
                                  "apiKey" {:type "apiKey" :in "header" :name "X-Api-Key"}}
                :schemas {"Envelope" envelope "Error" error-schema}}})
