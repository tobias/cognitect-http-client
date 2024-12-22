(ns build
  "For help, run:

  clojure -A:deps -T:build help/doc"
  (:refer-clojure :exclude [test])
  (:require
   [clojure.tools.build.api :as b]
   [deps-deploy.deps-deploy :as dd]))

(def lib 'org.tcrawley/cognitect-http-client)
(def version "1.11.130")
(def class-dir "target/classes")

(defn- pom-template
  [version]
  [[:description "Fork of com.cognitect/http-client that uses Jetty 11"]
   [:url "https://github.com/tobias/cognitect-http-client"]
   [:licenses
    [:license
     [:name "Apache License, Version 2.0"]
     [:url "http://www.apache.org/licenses/LICENSE-2.0"]]]
   [:scm
    [:url "https://github.com/tobias/cognitect-http-client"]
    [:connection "scm:git:https://github.com/tobias/cognitect-http-client.git"]
    [:developerConnection "scm:git:ssh:git@github.com:tobias/cognitect-http-client.git"]
    [:tag (str "v" version)]]])

(def ^:private basis
  (delay (b/create-basis {})))

(defn- jar-opts
  [opts]
  (println "Version:" version)
  (assoc opts
         :lib lib   :version version
         :jar-file  (format "target/%s-%s.jar" lib version)
         :basis     @basis
         :class-dir class-dir
         :target    "target"
         :src-dirs  ["src"]
         :pom-data  (pom-template version)))

(defn clean
  [_]
  (println "Removing target/ ...")
  (b/delete {:path "target"}))

(defn build-jar
  [opts]
  (let [j-opts (jar-opts opts)]
    (println "Writing pom.xml ...")
    (b/write-pom j-opts)
    (println "Copying source ...")
    (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
    (println "Building" (:jar-file j-opts) "...")
    (b/jar j-opts)))

(defn deploy
  "Build the jar and deploy it to Clojars. Expects env vars CLOJARS_USERNAME &
  CLOJARS_PASSWORD."
  [opts]
  (clean opts)
  (build-jar opts)
  (let [{:keys [jar-file] :as opts} (jar-opts opts)]
    (dd/deploy {:installer :remote :artifact (b/resolve-path jar-file)
                :pom-file (b/pom-path (select-keys opts [:lib :class-dir]))}))
  opts)
