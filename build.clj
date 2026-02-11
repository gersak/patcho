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

(defn create-jar [_]
  (let [basis (b/create-basis {})
        jar-file (format "target/patcho-%s.jar" version)]
    (b/delete {:path "target"})
    (b/copy-dir {:src-dirs ["src"]
                 :target-dir target})
    (b/write-pom {:target target
                  :lib 'dev.gersak/patcho
                  :version version
                  :basis basis
                  :src-dirs ["src"]
                  :scm {:url "https://github.com/gersak/patcho"
                        :connection "scm:git:git://github.com/gersak/patcho.git"
                        :developerConnection "scm:git:ssh://git@github.com/gersak/patcho.git"
                        :tag (str "v" version)}
                  :pom-data [[:description "Component versioning and lifecycle management for Clojure"]
                             [:url "https://github.com/gersak/patcho"]
                             [:licenses
                              [:license
                               [:name "MIT"]
                               [:url "https://opensource.org/licenses/MIT"]]]
                             [:developers
                              [:developer
                               [:name "Robert Gersak"]]]]})
    (b/jar {:class-dir target
            :jar-file jar-file})))

(defn release
  ([] (release nil))
  ([{t :test}]
   (create-jar nil)
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
