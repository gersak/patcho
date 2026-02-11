(ns patcho.patch
  "A simple version migration system for Clojure applications.
  
  Patcho provides a declarative way to define version-based patches that can be
  applied to migrate between different versions of your application or modules.
  
  Key features:
  - Automatic patch sequencing based on semantic versioning
  - Support for both upgrades and downgrades
  - Topic-based organization for modular systems
  - Simple macro-based API
  
  Basic usage:
    (require '[patcho.patch :as patch])
    
    ; Define current version
    (patch/current-version ::my-app \"2.0.0\")
    
    ; Define upgrade patches
    (patch/upgrade ::my-app \"1.0.0\"
      (println \"Initial setup\"))
    
    (patch/upgrade ::my-app \"2.0.0\"
      (println \"Major upgrade\"))
    
    ; Apply patches
    (patch/apply ::my-app \"1.0.0\")  ; Runs 2.0.0 upgrade
    (patch/apply ::my-app nil)        ; Runs all upgrades"
  (:refer-clojure :exclude [apply])
  (:require
    [version-clj.core :as vrs]))

;;; Protocol for version persistence

(defprotocol VersionStore
  "Protocol for persisting version state across application restarts"
  (read-version [this topic]
    "Read the currently installed version for the given topic.
     Should return a version string or nil/\"0\" if not found.")
  (write-version [this topic version]
    "Persist the installed version for the given topic.
     Called automatically by apply after successful migration."))

;;; Built-in VersionStore implementations

#?(:clj
   (deftype FileVersionStore [file-path]
     VersionStore
     (read-version [_ topic]
       (try
         (or (some-> file-path slurp read-string (get topic)) "0")
         (catch Exception _ "0")))
     (write-version [_ topic version]
       (let [current (try (some-> file-path slurp read-string)
                          (catch Exception _ {}))]
         (spit file-path (pr-str (assoc current topic version)))))))

(deftype AtomVersionStore [state-atom]
  VersionStore
  (read-version [_ topic]
    (get @state-atom topic "0"))
  (write-version [_ topic version]
    (swap! state-atom assoc topic version)))

;;; Store management

(def ^:dynamic *version-store*
  "Dynamic var for the version store.

  Defaults to AtomVersionStore (in-memory). State is lost on restart unless
  you explicitly set a persistent store (FileVersionStore, database, etc.).

  Set globally via set-store!, or temporarily via with-store macro.
  State is automatically migrated when switching stores."
  (->AtomVersionStore (atom {})))


(declare registered-topics)

(defn migrate-store!
  "Migrate version state from one store to another.

  Useful for bootstrapping: start with AtomVersionStore, setup database,
  then migrate state to database-backed store.

  Args:
    from-store - Source VersionStore to read from
    to-store   - Destination VersionStore to write to
    topics     - Collection of topic keywords to migrate (or nil for all registered)

  Returns:
    Set of migrated topics

  Example:
    ;; Bootstrap with in-memory store
    (def bootstrap-store (->AtomVersionStore (atom {})))
    (set-store! bootstrap-store)
    (level! :myapp/database)

    ;; Migrate to database store
    (migrate-store! bootstrap-store *db* nil)
    (set-store! *db*)"
  [from-store to-store topics]
  (let [topics (or topics (registered-topics))]
    (doseq [topic topics]
      (let [version (read-version from-store topic)]
        (when (and version (not= version "0"))
          (write-version to-store topic version))))
    (set topics)))

(defn set-store!
  "Set the default version store globally.

  Automatically migrates state from the previous store to the new one.
  This makes store transitions seamless (e.g., from bootstrap AtomVersionStore
  to database-backed store).

  Arguments:
    store - Implementation of VersionStore protocol (or nil to clear)

  Example:
    ;; Bootstrap with in-memory store (default)
    (level! :my/database)

    ;; Switch to DB - state auto-migrates
    (set-store! *db*)

    (level! :my/app)  ; Uses database store"
  [store]
  #?(:clj
     (alter-var-root #'*version-store*
                     (fn [old-store]
                       (when (and old-store store (not= old-store store))
                         (migrate-store! old-store store nil))
                       store))
     :cljs
     (let [old-store *version-store*]
       (when (and old-store store (not= old-store store))
         (migrate-store! old-store store nil))
       (set! *version-store* store))))


(defmacro with-store
  "Execute body with a specific VersionStore bound to *version-store*.

   Useful for testing or temporary overrides.

   Arguments:
     store - Implementation of VersionStore protocol
     body  - Expressions to execute with the store bound

   Example:
     (with-store (->FileVersionStore \".test-versions\")
       (apply ::my-app)
       (level! ::other-app))"
  [store & body]
  `(binding [*version-store* ~store]
     ~@body))

;;; Core multimethods

(defmulti _upgrade (fn [topic to] [topic to]))
(defmulti _downgrade (fn [topic to] [topic to]))
(defmulti version (fn [topic] topic))
(defmulti deployed-version (fn [topic] topic))

;; Default: read from store
(defmethod deployed-version :default [topic]
  (if *version-store*
    (read-version *version-store* topic)
    "0"))

(defn apply
  "Applies version patches to migrate from one version to another.

  With 1 arg:  Migrates from deployed-version to current-version.
  With 2 args: Migrates from 'current' to the topic's current version.
  With 3 args: Migrates from 'current' to 'target' version.

  The deployed-version is determined by the deployed-version multimethod,
  which defaults to reading from *version-store* but can be overridden
  via the installed-version macro.

  After successful migration, the new version is persisted to *version-store*.

  Arguments:
    topic   - Keyword identifying the module/component to patch
    current - Current version string (nil or \"0\" means start from beginning)
    target  - Target version string (optional, defaults to topic's current version)

  The function automatically:
    - Determines upgrade vs downgrade direction
    - Finds applicable patches between versions
    - Executes patches in correct order (oldest-first for upgrades, newest-first for downgrades)
    - Persists the new version to *version-store*

  Returns:
    The target version if patches were applied, nil otherwise

  Example:
    (apply ::my-app \"1.0.0\" \"2.0.0\")  ; Upgrade from 1.0.0 to 2.0.0
    (apply ::my-app \"2.0.0\" \"1.0.0\")  ; Downgrade from 2.0.0 to 1.0.0
    (apply ::my-app)                    ; Upgrade from installed to current"
  ([topic]
   (apply topic (deployed-version topic)))
  ([topic current] (apply topic current (version topic)))
  ([topic current target]
   (let [current (or current "0")]
     (when (or
             (vrs/older? target current)
             (vrs/newer? target current))
       (let [patch-direction (if (vrs/newer? target current)
                               :upgrade
                               :downgrade)
             patch-sequence (filter
                              (fn [[_topic _]]
                                (= _topic topic))
                              (keys
                                (methods (case patch-direction
                                           :upgrade _upgrade
                                           :downgrade _downgrade))))
             sorted (sort-by
                      second
                      (case patch-direction
                        :upgrade vrs/older?
                        :downgrade vrs/newer?)
                      patch-sequence)
             valid-sequence (keep
                              (fn [[_ version :as data]]
                                (when (case patch-direction
                                        :upgrade (and
                                                   (vrs/older-or-equal? version target)
                                                   (vrs/newer? version current))
                                        :downgrade (and
                                                     (vrs/older-or-equal? version current)
                                                     (vrs/newer? version target)))
                                  data))
                              sorted)]
         (doseq [[topic version] valid-sequence]
           (case patch-direction
             :upgrade (_upgrade topic version)
             :downgrade (_downgrade topic version)))

         ;; After successful migration, persist the new version
         (when *version-store*
           (write-version *version-store* topic target))

         target)))))

(defmacro upgrade
  "Defines code to execute when upgrading TO the specified version.
  
  The body will be executed when applying patches that include this version
  in the upgrade path.
  
  Arguments:
    topic   - Keyword identifying the module/component
    to      - Version string this upgrade migrates TO
    body    - Code to execute for the upgrade
    
  Example:
    (upgrade ::database \"2.0.0\"
      (add-column :users :preferences :jsonb)
      (migrate-user-settings))"
  [topic to & body]
  `(defmethod _upgrade [~topic ~to]
     [~'_ ~'_]
     ~@body))

(defmacro downgrade
  "Defines code to execute when downgrading FROM the specified version.
  
  The body will be executed when applying patches that include this version
  in the downgrade path. Note: downgrade happens FROM this version to a 
  lower version.
  
  Arguments:
    topic   - Keyword identifying the module/component
    to      - Version string this downgrade migrates FROM
    body    - Code to execute for the downgrade
    
  Example:
    (downgrade ::database \"2.0.0\"
      (drop-column :users :preferences)
      (restore-legacy-settings))"
  [topic to & body]
  `(defmethod _downgrade [~topic ~to]
     [~'_ ~'_]
     ~@body))

(defmacro current-version
  "Defines the current/target version for a topic.

  This version is used as the default target when calling apply
  with only 2 arguments.

  Arguments:
    topic   - Keyword identifying the module/component
    body    - Should return a version string

  Example:
    (current-version ::my-app \"2.5.0\")

    ; Can also compute version dynamically
    (current-version ::my-app
      (read-version-from-file))"
  [topic & body]
  `(defmethod version ~topic
     [~'_]
     ~@body))

(defmacro installed-version
  "Defines where to read the installed/deployed version for a topic.

  Use when the topic has its own version tracking (e.g., dataset deploy history,
  external system). If not defined, falls back to reading from *version-store*.

  Arguments:
    topic   - Keyword identifying the module/component
    body    - Should return a version string (or \"0\" if not installed)

  Example:
    ; Read from dataset's deploy history instead of version store
    (installed-version :synthigy.iam/model
      (or (get-version-from-deploy-history) \"0\"))

    ; Read from external API
    (installed-version :external/service
      (fetch-installed-version-from-api))"
  [topic & body]
  `(defmethod deployed-version ~topic
     [~'_]
     ~@body))

(defn available-versions
  "Returns a map of topics to their current versions.

  With no args: Returns all registered topic versions.
  With args: Returns versions only for specified topics.

  Arguments:
    topics - Zero or more topic keywords to query

  Returns:
    Map of {topic version-string} for registered topics

  Example:
    (available-versions)                    ; => {:app \"2.0.0\" :db \"1.5.0\"}
    (available-versions :app)               ; => {:app \"2.0.0\"}
    (available-versions :app :db :unknown)  ; => {:app \"2.0.0\" :db \"1.5.0\"}"
  ([& topics]
   (reduce-kv
     (fn [r k f]
       (assoc r k (f k)))
     nil
     (if (empty? topics)
       (methods version)
       (select-keys (methods version) topics)))))


(defn topic-version
  "Returns the currently deployed/installed version for a topic."
  [topic]
  (get (available-versions) topic))

(defn registered-topics
  "Returns a set of all registered topics (components with current-version defined).

  Returns:
    Set of topic keywords

  Example:
    (registered-topics)  ; => #{:synthigy/iam :synthigy/iam-audit :synthigy/dataset}"
  []
  (set (keys (methods version))))

(defn level!
  "Apply all pending patches for a component.

  Reads the installed version via deployed-version (defaults to *version-store*,
  can be overridden via installed-version macro), applies patches to reach
  current-version, and persists the new version.

  Arguments:
    topic - Keyword identifying the module/component

  Returns:
    The target version if patches were applied, nil if already at target.

  Example:
    (level! :myapp/database)"
  ([topic]
   (let [current (deployed-version topic)
         target (version topic)]
     (when (or (vrs/older? target current)
               (vrs/newer? target current))
       (apply topic)
       target)))
  ([topic & more-topics]
   (doseq [t (cons topic more-topics)]
     (level! t))))


(current-version :dev.gersak/patcho "0.4.2")

(comment
  (topic-version :dev.gersak/patcho)
  (available-versions :dev.gersak/patcho :synthigy/dataset)
  (vrs/newer? "0" "0.0.0")
  (vrs/older? "0" "0.0.0")
  (available-versions)
  (available-versions :eywa/robotics)
  (upgrade ::datasets "0.0.1" (println "Patching 0.3.37"))
  (upgrade ::datasets "0.3.37" (println "Patching 0.3.37"))
  (upgrade ::datasets "0.3.38" (println "Patching 0.3.38"))
  (upgrade ::datasets "0.3.39" (println "Patching 0.3.39"))
  (upgrade ::datasets "0.3.40" (println "Patching 0.3.40"))
  (upgrade ::datasets "0.3.41" (println "Patching 0.3.41"))
  (upgrade ::datasets "0.3.42" (println "Patching 0.3.42"))

  (vrs/newer-or-equal? "0.0.1" "0")
  (apply ::datasets nil "0.0.1")
  (macroexpand-1
    `(upgrade ::datasets "0.3.37" (println "Patching 0.3.37")))
  (group-by first (keys (methods _upgrade))))
