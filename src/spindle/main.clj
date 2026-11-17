(ns spindle.main
  "Start a Spindle node.

   Environment:
     SPINDLE_CONFIG_DIR   config directory (default ./config)
     SPINDLE_PORT         HTTP port (default 8080)
     SPINDLE_NODE_ID      node name in /status (default hostname)
     SPINDLE_POLL_MS      config poll interval (default 5000)
     SPINDLE_DB           token store (default data/spindle.db; not inside the config dir)
     SPINDLE_DEV_TOKEN    development only (16+ characters): a token accepted from this machine
                          only, with the admin and author scopes and every endpoint scope; the
                          console offers a one-click Dev login. Never set it on a shared server."
  (:require [clojure.tools.logging :as log]
            [spindle.config :as config]
            [spindle.server :as server]
            [spindle.tokens :as tokens])
  (:gen-class))

(defn- env [k default] (or (System/getenv k) default))

(defn -main [& _]
  (let [dir (env "SPINDLE_CONFIG_DIR" "config")
        port (Long/parseLong (env "SPINDLE_PORT" "8080"))
        node-id (env "SPINDLE_NODE_ID" (.getHostName (java.net.InetAddress/getLocalHost)))
        poll (Long/parseLong (env "SPINDLE_POLL_MS" "5000"))
        db (env "SPINDLE_DB" "data/spindle.db")
        store (try (tokens/open-sqlite db dir)
                   (catch Throwable t
                     (log/error (str "event=store-failed db=" db " error=" (ex-message t)))
                     (System/exit 2)))
        r (config/reload! dir)]
    (when (:problems r)
      (log/error (str "initial configuration rejected: " (pr-str (:problems r))))
      (System/exit 2))
    (config/start-watcher! dir poll)
    ;; refresh gauges for connections added by later reloads
    (add-watch config/state ::gauges (fn [_ _ o n] (when (not= (:snapshot o) (:snapshot n)) (server/refresh-gauges!))))
    (server/start! {:port port :node-id node-id :config-dir dir :store store})
    (log/info (str "event=started port=" port " node=" node-id " config=" (:ok r)))))
