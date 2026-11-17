(ns spindle.console-page-test
  (:require [clojure.test :refer [deftest is testing]]
            [spindle.server :as server]))

(defn- get* [uri & {:keys [remote] :or {remote "127.0.0.1"}}]
  ((server/handler {:node-id "n1" :config-dir "none"})
   {:request-method :get :uri uri :remote-addr remote :headers {}}))

(deftest console-index
  (doseq [uri ["/console" "/console/"]]
    (let [r (get* uri)]
      (is (= 200 (:status r)))
      (is (re-find #"text/html" (get-in r [:headers "Content-Type"])))
      (is (= "default-src 'self'; frame-ancestors 'none'" (get-in r [:headers "Content-Security-Policy"])))
      (is (= "nosniff" (get-in r [:headers "X-Content-Type-Options"])))
      (is (= "no-store" (get-in r [:headers "Cache-Control"]))))))

(deftest index-has-no-inline-script
  (let [html (slurp (:body (get* "/console")))]
    (is (not (re-find #"<script(?![^>]*\bsrc=)" html)))
    (is (not (re-find #"(?i)\son[a-z]+\s*=" html)))))

(deftest static-assets
  (let [js (get* "/console/console.js")]
    (is (= 200 (:status js)))
    (is (re-find #"text/javascript" (get-in js [:headers "Content-Type"]))))
  (is (re-find #"text/css" (get-in (get* "/console/console.css") [:headers "Content-Type"]))))

(deftest traversal-and-missing
  (is (= 404 (:status (get* "/console/../auth.edn"))))
  (is (= 404 (:status (get* "/console/%2e%2e/auth.edn"))))
  (is (= 404 (:status (get* "/console/nope.js")))))

(deftest https-required
  (testing "non-local without TLS is refused"
    (is (= 403 (:status (get* "/console" :remote "10.1.2.3"))))
    (is (= 403 (:status (get* "/console/console.js" :remote "10.1.2.3"))))))

(deftest forwarded-https-and-bad-extension
  (let [h (server/handler {:node-id "n1" :config-dir "none"})]
    (is (= 200 (:status (h {:request-method :get :uri "/console" :remote-addr "10.1.2.3"
                            :headers {"x-forwarded-proto" "https"}}))))
    (is (= 404 (:status (get* "/console/x.edn"))))))
