(ns spindle.jsonout
  "JSON for responses: java.time values as ISO strings."
  (:require [jsonista.core :as json])
  (:import [java.time Instant LocalDate LocalDateTime LocalTime OffsetDateTime]))

(defn- as-string [v ^com.fasterxml.jackson.core.JsonGenerator g] (.writeString g (str v)))

(def mapper
  (json/object-mapper
   {:encoders {LocalDate as-string LocalDateTime as-string LocalTime as-string
               OffsetDateTime as-string Instant as-string}}))

(defn write [v] (json/write-value-as-string v mapper))
