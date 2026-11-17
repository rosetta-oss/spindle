(ns spindle.main
  "Start a Spindle node.

   Environment:
     SPINDLE_CONFIG_DIR   config directory (default ./config)
     SPINDLE_PORT         HTTP port (default 8080)
     SPINDLE_NODE_ID      node name in /status (default hostname)
     SPINDLE_POLL_MS      config poll interval (default 5000)"
  (:require [clojure.tools.logging :as log]
            [spindle.config :as config]
            [spindle.server :as server])
  (:gen-class))

(defn- env [k default] (or (System/getenv k) default))

(defn -main [& _]
  (let [dir (env "SPINDLE_CONFIG_DIR" "config")
        port (Long/parseLong (env "SPINDLE_PORT" "8080"))
        node-id (env "SPINDLE_NODE_ID" (.getHostName (java.net.InetAddress/getLocalHost)))
        poll (Long/parseLong (env "SPINDLE_POLL_MS" "5000"))
        r (config/reload! dir)]
    (when (:problems r)
      (log/error (str "initial configuration rejected: " (pr-str (:problems r))))
      (System/exit 2))
    (config/start-watcher! dir poll)
    ;; refresh gauges for connections added by later reloads
    (add-watch config/state ::gauges (fn [_ _ o n] (when (not= (:snapshot o) (:snapshot n)) (server/refresh-gauges!))))
    (server/start! {:port port :node-id node-id :config-dir dir})
    (log/info (str "event=started port=" port " node=" node-id " config=" (:ok r)))))
