(ns build
  (:require
   [clojure.edn :as edn]
   [clojure.tools.build.api :as b]
   [deps-deploy.deps-deploy :as dd]))

(def version "0.1.0")
(def target "target/classes")

(defn create-jar []
  (let [basis (b/create-basis {})]
    (b/delete {:path "target"})
    (b/copy-dir {:src-dirs ["src"]
                 :target-dir target})
    (b/write-pom {:target target
                  :lib 'dev.gersak/patcho
                  :version version
                  :basis basis})
    (b/jar {:class-dir target
            :jar-file (format "target/patcho-%s.jar" version)})))

(defn release
  ([] (release nil))
  ([{t :test}]
   (create-jar)
   (let [jar-file (format "target/patcho-%s.jar" version)
         pom-file (str target "/pom.xml")
         installer (if t :local :remote)]
     (println "Deploying JAR:" jar-file)
     (dd/deploy {:installer installer
                 :sign-releases? false
                 :artifact jar-file
                 :pom-file pom-file}))))

(comment
  (def config-file "shadow-cljs.prod.edn")
  (release))
