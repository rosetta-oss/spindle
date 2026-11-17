(ns build
  "clojure -T:build uber  → target/spindle.jar"
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_] (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (b/compile-clj {:basis @basis :ns-compile '[spindle.main] :class-dir class-dir})
  (b/uber {:class-dir class-dir :uber-file "target/spindle.jar" :basis @basis :main 'spindle.main}))
