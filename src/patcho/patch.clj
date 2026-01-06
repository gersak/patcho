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

(deftype FileVersionStore [file-path]
  VersionStore
  (read-version [_ topic]
    (try
      (or (some-> file-path slurp read-string (get topic)) "0")
      (catch Exception _ "0")))
  (write-version [_ topic version]
    (let [current (try (some-> file-path slurp read-string)
                       (catch Exception _ {}))]
      (spit file-path (pr-str (assoc current topic version))))))

(deftype AtomVersionStore [state-atom]
  VersionStore
  (read-version [_ topic]
    (get @state-atom topic "0"))
  (write-version [_ topic version]
    (swap! state-atom assoc topic version)))

;;; Store management

(def ^:dynamic *version-store*
  "Dynamic var for the version store.

  Defaults to FileVersionStore with `.versions` file.
  Set globally via set-default-store!, or temporarily via with-store macro."
  (->FileVersionStore ".versions"))

(defn set-default-store!
  "Set the default version store globally.

  This sets the root binding of *version-store* for all patching operations.

   Arguments:
     store - Implementation of VersionStore protocol

   Example:
     (set-default-store! (->FileVersionStore \".versions\"))

     (apply ::my-app)    ; Uses the default store
     (level! ::my-db)    ; Uses the default store"
  [store]
  (alter-var-root #'*version-store* (constantly store)))

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

(defn apply
  "Applies version patches to migrate from one version to another.

  With 1 arg:  Migrates from deployed-version to the topic's current version.
  With 2 args: Migrates from 'current' to the topic's current version.
  With 3 args: Migrates from 'current' to 'target' version.

  After successful migration, if a VersionStore is registered for this topic
  (via set-store! or *version-store*), the new version is persisted automatically.

  Arguments:
    topic   - Keyword identifying the module/component to patch
    current - Current version string (nil or \"0\" means start from beginning)
    target  - Target version string (optional, defaults to topic's current version)

  The function automatically:
    - Determines upgrade vs downgrade direction
    - Finds applicable patches between versions
    - Executes patches in correct order (oldest-first for upgrades, newest-first for downgrades)
    - Persists the new version to the registered VersionStore (if configured)

  Returns:
    The target version if patches were applied, nil otherwise

  Example:
    (apply ::my-app \"1.0.0\" \"2.0.0\")  ; Upgrade from 1.0.0 to 2.0.0
    (apply ::my-app \"2.0.0\" \"1.0.0\")  ; Downgrade from 2.0.0 to 1.0.0
    (apply ::my-app nil)                ; Upgrade from beginning to current"
  ([topic]
   ;; Handle missing deployed-version gracefully
   (let [current (try
                   (deployed-version topic)
                   (catch IllegalArgumentException _
                     ;; No installed-version defined, try store or default to "0"
                     (if *version-store*
                       (read-version *version-store* topic)
                       "0")))]
     (apply topic current)))
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
  "Defines the currently installed/deployed version for a topic.

  This version is used as the starting point when calling apply with only 1 argument.
  Can be a static version string or a dynamic expression (like reading from a store).

  Arguments:
    topic - Keyword identifying the module/component
    body  - Expression(s) that return a version string

  Examples:
    ; Static version
    (installed-version ::my-app \"1.5.0\")

    ; Read from a VersionStore
    (def store (->FileVersionStore \"versions.edn\"))
    (installed-version ::my-app (read-version store ::my-app))

    ; Read from a file directly
    (installed-version ::my-app
      (some-> \"version.txt\" slurp str/trim))"
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

(defn registered-topics
  "Returns a set of all registered topics (components with current-version defined).

  Returns:
    Set of topic keywords

  Example:
    (registered-topics)  ; => #{:synticity/iam :synticity/iam-audit :synticity/dataset}"
  []
  (set (keys (methods version))))

(defn level!
  "Apply all pending patches for a component with logging.

  This is a convenience wrapper around apply that:
  - Reads the installed version from the registered VersionStore
  - Applies all pending patches up to current-version
  - Automatically persists the new version
  - Provides consistent logging across all components

  Arguments:
    topic - Keyword identifying the module/component

  Returns:
    The target version if patches were applied, nil otherwise

  Example:
    ; Instead of writing custom level-X! functions:
    (defn level-iam! []
      (log/info \"[IAM] Leveling...\")
      (apply :synticity/iam)
      (log/info \"Done\"))

    ; Just use:
    (level! :synticity/iam)"
  ([topic]
   (let [;; Handle topics that don't have installed-version defined
         current (try
                   (deployed-version topic)
                   (catch IllegalArgumentException _
                     ;; No installed-version defined, try store or default to "0"
                     (if *version-store*
                       (read-version *version-store* topic)
                       "0")))
         target (version topic)]
     ;; Only log and apply if there's actually work to do
     (if (or (vrs/older? target current)
             (vrs/newer? target current))
       (do
         (println (format "[%s] Leveling component from %s to %s..."
                          (name topic) current target))
         (apply topic)
         (println (format "[%s] Component leveled to %s"
                          (name topic)
                          (if *version-store*
                            (read-version *version-store* topic)
                            target)))
         target)
       (do
         (println (format "[%s] Already at version %s" (name topic) current))
         nil))))
  ([topic & more-topics]
   (doseq [t (cons topic more-topics)]
     (level! t))))

(defn level-all!
  "Level all registered components in registration order.

  This function discovers all topics that have been registered via current-version
  and levels each one by calling level!

  Returns:
    Map of {topic version} for components that were actually updated

  Example:
    ; Level all components at once
    (level-all!)
    ; => {:synticity/iam \"1.0.0\" :synticity/iam-audit \"1.0.0\"}"
  []
  (println "Leveling all registered components...")
  (let [results (reduce
                 (fn [acc topic]
                   (if-let [new-version (level! topic)]
                     (assoc acc topic new-version)
                     acc))
                 {}
                 (registered-topics))]
    (println (format "Leveling complete. Updated %d component(s)" (count results)))
    results))

(comment
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
