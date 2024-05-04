;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;      http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS-IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns cognitect.http-client
  (:require
   [clojure.core.async :refer (put!) :as a])
  (:import
   [java.net SocketTimeoutException UnknownHostException ConnectException]
   [java.io EOFException]
   [java.nio ByteBuffer]
   [java.util.concurrent RejectedExecutionException TimeUnit TimeoutException]
   [org.eclipse.jetty.client HttpClient Socks4Proxy]
   [org.eclipse.jetty.client.api Request Response Result
                                 Response$CompleteListener Response$HeadersListener Response$ContentListener
                                 Response$FailureListener]
   [org.eclipse.jetty.client.util ByteBufferContentProvider]
   [org.eclipse.jetty.http HttpFields]
   [org.eclipse.jetty.util.resource Resource]
   [org.eclipse.jetty.util.log Log]
   org.eclipse.jetty.util.ssl.SslContextFactory))

(set! *warn-on-reflection* true)

;; Disable Jetty announce by default
(defn configure-jetty-announce
  "Set Jetty announce to false by default unless specified as a system property.
   Jetty defaults this setting is true"
  []
  (doto (Log/getProperties)
    (.setProperty "org.eclipse.jetty.util.log.announce"
                  (System/getProperty "org.eclipse.jetty.util.log.announce" "false"))))

;; begin copied from datomic.java.io.bbuf ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn empty-bbuf
  "Returns an array-backed bbuf with pos and lim 0, cap n."
  [n]
  (.flip (ByteBuffer/wrap (byte-array n))))

(defn unflip
  "Given a readable buffer, return a writable buffer that appends at
   the end of the buffer's valid information."
  ^ByteBuffer [^ByteBuffer b]
  (-> (.duplicate b)
      (.position (.limit b))
      (.limit (.capacity b))))

(defn expand-buffer
  "Given a readable buffer buf, returns a writeable buffer with the
   same contents as buf, plus room for extra additional bytes."
  ^ByteBuffer [^ByteBuffer buf ^long extra]
  (let [available (- (.capacity buf) (.limit buf))]
    (if (<= extra available)
      (unflip buf)
      (let [new-length (max (* 2 (.capacity buf))
                            (+ extra (.capacity buf)))
            new-buf (if (.isDirect buf)
                      (ByteBuffer/allocateDirect new-length)
                      (ByteBuffer/allocate new-length))]
        (.put new-buf (.duplicate buf))))))

(defn append-buffer
  "Given a readable buffer dest, and a readable buffer src, return
   a readable buffer that has the contents dest+src."
  ^ByteBuffer [^ByteBuffer dest ^ByteBuffer src]
  (let [^ByteBuffer result (expand-buffer dest (.remaining src))]
      (.put result (.duplicate src))
      (.flip result)))
;; end copied from datomic.java.io.bbuf ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def method-string
  {:get "GET"
   :post "POST"
   :put "PUT"
   :head "HEAD"
   :delete "DELETE"
   :patch "PATCH"})

(def response-body?
  (complement #{"HEAD"}))

(defn map->jetty-request
  "Convert a Ring request map into a Jetty request. Note if :body is present
   it should be a ByteBuffer.

   See https://github.com/mmcgrana/ring/blob/master/SPEC."
  ^Request [^HttpClient jetty-client
   {:keys [server-name server-port uri query-string request-method
           scheme headers body]
    :or   {scheme "https"} :as m}]
  {:pre [(string? server-name) (integer? server-port)]}
  (let [req (doto (.newRequest jetty-client server-name server-port)
              (.method ^String (method-string request-method))
              (.scheme (name scheme))
              (.path ^String (if query-string
                               (str uri "?" query-string)
                               uri)))
        req (reduce-kv
             (fn [^Request req k v]
               (.header req ^String (name k) ^String v))
             req
             headers)
        req (if-let [to (::timeout-msec m)]
              (.timeout ^Request req to TimeUnit/MILLISECONDS)
              req)]
    (if body
      (.content ^Request req
        (ByteBufferContentProvider. (into-array [(.duplicate ^ByteBuffer body)])))
      req)))

(defn- format-headers [^HttpFields jetty-headers]
  (reduce
   (fn [m ^String n]
     (assoc m
            (.toLowerCase n java.util.Locale/ENGLISH)
            (.getValue (.getField jetty-headers n))))
   {}
   (.getFieldNamesCollection jetty-headers)))

(defn- on-headers
  "Helper for submit. Adds :status, :headers, and :body to state based on
   response."
  [state ^Response response]
  (let [headers (format-headers (.getHeaders response))]
    (cond-> (assoc state
              :status (.getStatus response)
              :headers headers)
      (response-body? (.. response getRequest getMethod))
      (assoc :body (let [lstr (get headers "content-length")
                         ln (try
                              (Long/parseLong lstr)
                              (catch NumberFormatException _ nil))]
                     (when (and ln (not (zero? ln)))
                       (empty-bbuf ln)))))))

;; jetty requires that we copy the buffer out before returning!
;; See https://www.eclipse.org/jetty/javadoc/9.4.7.v20170914/org/eclipse/jetty/client/api/Response.ContentListener.html#onContent-org.eclipse.jetty.client.api.Response-java.nio.ByteBuffer-
(defn- on-content
  "Helper for submit. Appends content to state :body"
  [state ^ByteBuffer content]
  (update state :body #(append-buffer
                        (or % (empty-bbuf (.remaining content)))
                        content)))

(defn error->category
  "Guess what categoric thing went wrong based on jetty exception.
Returns anomaly category."
  [throwable]
  (cond
    (instance? RejectedExecutionException throwable) :cognitect.anomalies/incorrect
    (instance? TimeoutException throwable) :cognitect.anomalies/unavailable
    (instance? SocketTimeoutException throwable) :cognitect.anomalies/unavailable
    (instance? ConnectException throwable) :cognitect.anomalies/unavailable
    (instance? UnknownHostException throwable) :cognitect.anomalies/not-found
    (instance? EOFException throwable) :cognitect.anomalies/unavailable))

(defn error->anomaly
  [^Throwable t]
  (if-let [cat (error->category t)]
    {:cognitect.anomalies/category cat
     :cognitect.anomalies/message (.getMessage t)
     ::throwable t}
    {:cognitect.anomalies/category :cognitect.anomalies/fault
     :cognitect.anomalies/message (.getMessage t)
     ::throwable t}))

(defn- on-failure
  "When there is a server-side error, on-headers may not have
   been invoked, so this ensures they're part of the result."
  [state ^Response response]
  (assoc state
         :status (.getStatus response)
         :headers (format-headers (.getHeaders response))))

(defn- on-complete
  "Helper for submit. Builds error map if submit failed, or Ring
response map if submit succeeded."
  [state ^Result result request]
  (merge (if (.isFailed result)
           (merge state (error->anomaly (.getFailure result)))
           state)
         (select-keys request [::meta])))

(defprotocol IClient
  (submit* [_ request ch]))

(defn submit
  "Submit an http request, channel will be filled with response. Returns ch.

Request map:

:server-name        string
:server-port         integer
:uri                string
:query-string       string, optional
:request-method     :get/:post/:put/:head
:scheme             :http or :https
:headers            map from downcased string to string
:body               ByteBuffer, optional
:cognitect.http-client/timeout-msec   opt, total request send/receive timeout
:cognitect.http-client/meta           opt, data to be added to the response map

content-type must be specified in the headers map
content-length is derived from the ByteBuffer passed to body

Response map:

:status              integer HTTP status code
:body                ByteBuffer, optional
:header              map from downcased string to string
:cognitect.http-client/meta           opt, data from the request

On error, response map is per cognitect.anomalies"
  ([client request]
     (submit client request (a/chan 1)))
  ([client request ch]
   {:pre [(every? #(contains? request %) [:server-name
                                          :server-port
                                          :uri
                                          :request-method
                                          :scheme])]}
    ;; Not Clojure 1.8 compatible. Using :pre for now
    ;; (s/assert ::submit-request request)
   (submit* client request ch)))

(deftype Client
  [^HttpClient jetty-client pending-ops pending-ops-limit]
  IClient
  (submit*
   [_ request ch]
   (if (< pending-ops-limit (swap! pending-ops inc))
     (do
       (put! ch (merge {:cognitect.anomalies/category :cognitect.anomalies/busy
                        :cognitect.anomalies/message (str "Ops limit reached: " pending-ops-limit)}
                       (select-keys request [::meta])))
       (swap! pending-ops dec))
     (try
       (let [jr (map->jetty-request jetty-client request)
             state (atom {})
             jr (.onResponseHeaders jr (reify Response$HeadersListener
                                         (onHeaders
                                           [_ response]
                                           (swap! state on-headers response))))
             jr (.onResponseContent jr (reify Response$ContentListener
                                         (onContent
                                           [_ _ content]
                                           (swap! state on-content content))))
             jr (.onResponseFailure jr (reify Response$FailureListener
                                         (onFailure [_ response _]
                                           (swap! state on-failure response))))
             listener (reify Response$CompleteListener
                        (onComplete
                          [_ result]
                          (put! ch (on-complete @state result request))
                          (swap! pending-ops dec)))]
         (.send jr listener))
       (catch RejectedExecutionException _
         (put! ch (merge {:cognitect.anomalies/category :cognitect.anomalies/unavailable
                          :cognitect.anomalies/message "Rejected by executor"}
                         (select-keys request [::meta])))
         (swap! pending-ops dec))
       (catch Throwable t
         (put! ch (merge (error->anomaly t) (select-keys request [::meta])))
         (swap! pending-ops dec))))
   ch))

(defn ssl-context-factory
  ^SslContextFactory [{:keys [classpath-trust-store trust-store-password trust-store validate-hostnames]
                       :or {validate-hostnames true}}]
  (let [factory (SslContextFactory. false)]
    (if validate-hostnames
      (.setEndpointIdentificationAlgorithm factory "HTTPS")
      ;; Default changed in 9.4.15
      ;; https://github.com/eclipse/jetty.project/issues/3466
      (.setEndpointIdentificationAlgorithm factory nil))
    (when trust-store
      (.setTrustStore factory trust-store))
    (when classpath-trust-store
      (.setTrustStoreResource factory (Resource/newClassPathResource classpath-trust-store)))
    (when trust-store-password
      (.setTrustStorePassword factory trust-store-password))
    factory))

(defn create
  "Creates an http-client that can be used with submit. Takes a config map with
   the following keys:

   :follow-redirects                 boolean, defaults to true
   :resolve-timeout                  in msec, default 5000
   :connect-timeout                  in msec, default 5000
   :idle-timeout                     default 0 (Jetty default)
   :max-connections-per-destination  default 64
   :max-threads                      200
   :min-threads                      8
   :pending-ops-limit                default 64
   :classpath-trust-store            classpath location of trust store
   :trust-store-password             trust store password
   :trust-store                      java.security.KeyStore instance
   :validate-hostnames               boolean, defaults to true
   :proxy-host                       optional host to use for proxy, string, defaults to 'localhost' if proxy-port is provided with no proxy-host
   :proxy-port                       optional port to use for proxy, int"
  [{:keys [follow-redirects resolve-timeout connect-timeout idle-timeout max-connections-per-destination
           max-threads min-threads
           pending-ops-limit proxy-host proxy-port]
    :or   {follow-redirects true
           resolve-timeout 5000
           connect-timeout 5000
           idle-timeout 0
           max-connections-per-destination 64
           max-threads 200
           min-threads 8
           pending-ops-limit 64}
    :as   config}]
  (configure-jetty-announce)
  (let [jetty-client (doto (HttpClient. (ssl-context-factory config))
                       (.setFollowRedirects follow-redirects)
                       (.setAddressResolutionTimeout resolve-timeout)
                       (.setConnectTimeout connect-timeout)
                       (.setIdleTimeout idle-timeout)
                       (.setMaxConnectionsPerDestination max-connections-per-destination)
                       ;; these are Jetty defaults, except for the queue size
                       (.setExecutor (doto (org.eclipse.jetty.util.thread.QueuedThreadPool.
                                            max-threads min-threads 60000
                                            (java.util.concurrent.LinkedBlockingQueue. ^int (* 2 pending-ops-limit)))
                                       (.setDaemon true))))]
    ;; This is jetty defaults, except daemonizing scheduler
    (.setScheduler jetty-client (org.eclipse.jetty.util.thread.ScheduledExecutorScheduler.
                                  (str (-> jetty-client class .getSimpleName) "@" (.hashCode jetty-client) "-scheduler") true))
    (when proxy-port
      (-> jetty-client
          .getProxyConfiguration
          .getProxies
          (.add (Socks4Proxy.
                  (or ^String proxy-host
                      "localhost")
                  ^int proxy-port))))
    (.start jetty-client)
    (Client.
      jetty-client
      (atom 0)
      pending-ops-limit)))

(defn stop
  "Shuts down the client, releasing any resources that might be held
open."
  [^Client client]
  (.stop ^HttpClient (.-jetty_client client)))
