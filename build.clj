(ns build
  (:require
    [clojure.edn :as edn]
    [clojure.tools.build.api :as b]
    [deps-deploy.deps-deploy :as dd]))

(def versions
  (let [{:keys [out]} (b/process
                        {:command-args ["clj" "-X" "patcho.cli/versions" ":require" "patcho.patch"]
                         :out :capture})]
    (edn/read-string out)))
(def version (:dev.gersak/patcho versions))

(def target "target/classes")

(defn create-jar []
  (let [basis (b/create-basis {})
        jar-file (format "target/patcho-%s.jar" version)]
    (b/delete {:path "target"})
    (b/copy-dir {:src-dirs ["src"]
                 :target-dir target})
    (b/write-pom {:target target
                  :lib 'dev.gersak/patcho
                  :version version
                  :basis basis})
    (b/jar {:class-dir target
            :jar-file jar-file})))

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
