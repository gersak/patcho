(ns patcho.cli
  "CLI tool functions for querying Patcho topic versions.

  Usage:
    clj -T:patcho version :topic :myapp/database :require myapp.patches
    clj -T:patcho versions :require myapp.patches
    clj -T:patcho topics :require myapp.patches

  The `version` command returns a plain string for easy shell capture:

    VERSION=$(clj -T:patcho version :topic :myapp/database :require myapp.patches)

  The `versions` and `topics` commands return EDN for programmatic use:

    (def versions
      (let [{:keys [out]} (b/process
                            {:command-args [\"clj\" \"-T:patcho\" \"versions\" \":require\" \"myapp.patches\"]
                             :out :capture})]
        (edn/read-string out)))"
  (:require
    [patcho.patch :as patch]))

(defn- require-namespaces
  "Require one or more namespaces. Accepts symbol or vector of symbols."
  [ns-spec]
  (let [namespaces (if (vector? ns-spec) ns-spec [ns-spec])]
    (doseq [ns-sym namespaces]
      (require ns-sym :reload))))

(defn version
  "Returns the version string for a specific topic.

  Arguments:
    :topic   - Topic keyword to query (required)
    :require - Namespace symbol or vector of symbols to load (required)

  Example:
    clj -T:patcho version :topic :myapp/database :require myapp.patches

  Output:
    2.0.0"
  [{:keys [topic require]}]
  (when-not topic
    (println "Error: :topic is required")
    (System/exit 1))
  (when-not require
    (println "Error: :require is required")
    (System/exit 1))
  (require-namespaces require)
  (if-let [v (patch/topic-version topic)]
    (println v)
    (do
      (println "Error: topic not registered:" topic)
      (System/exit 1))))

(defn versions
  "Returns EDN map of all registered topics and their current versions.

  Arguments:
    :require - Namespace symbol or vector of symbols to load (required)

  Example:
    clj -T:patcho versions :require myapp.patches
    clj -T:patcho versions :require '[myapp.patches myapp.db]'

  Output:
    {:myapp/database \"2.0.0\", :myapp/cache \"1.2.0\"}"
  [{:keys [require]}]
  (when-not require
    (println "Error: :require is required")
    (System/exit 1))
  (require-namespaces require)
  (println (pr-str (patch/available-versions))))

(defn topics
  "Returns EDN set of all registered topics.

  Arguments:
    :require - Namespace symbol or vector of symbols to load (required)

  Example:
    clj -T:patcho topics :require myapp.patches

  Output:
    #{:myapp/database :myapp/cache}"
  [{:keys [require]}]
  (when-not require
    (println "Error: :require is required")
    (System/exit 1))
  (require-namespaces require)
  (println (pr-str (patch/registered-topics))))
