(ns patcho.lifecycle
  "Module lifecycle management with dependency resolution.

  Provides runtime lifecycle management for modules with explicit dependency
  declaration and automatic startup ordering.

  ## Design Philosophy

  This namespace provides a lightweight registry for managing module lifecycle:
  - Modules register themselves at namespace load time
  - Dependencies are declared explicitly
  - Operations are RECURSIVE - automatically handle dependencies
  - Cleanup uses reference counting (claimed-by semantics)
  - State tracking prevents double-start/stop
  - Setup phase is separate from start/stop (one-time vs runtime)

  ## Usage

  ### Registration (at namespace load time)

      (ns my.module
        (:require [patcho.lifecycle :as lifecycle]))

      (lifecycle/register-module! :my/module
        {:depends-on [:other/module]
         :setup (fn []
                  (create-tables!))
         :cleanup (fn []
                    (drop-tables!))
         :start (fn []
                  (start-connections!)
                  (subscribe-to-events!))
         :stop (fn []
                 (unsubscribe!)
                 (close-connections!))})

  ### Application Startup

      (ns my.app
        (:require
          my.database      ; Registers :my/database
          my.cache         ; Registers :my/cache (depends on :my/database)
          my.api           ; Registers :my/api (depends on :my/cache)
          [patcho.lifecycle :as lifecycle]))

      ;; Set default store once (typically in main or init)
      (lifecycle/set-store! (lifecycle/->FileLifecycleStore \".lifecycle\"))

      ;; Simple approach: Just start! (auto-runs setup if needed)
      (lifecycle/start! :my/api)
      ;; → Setups (if needed) and starts: :my/database → :my/cache → :my/api
      ;; Setup is idempotent - only runs once, tracked via lifecycle store

      ;; OR explicit control: Run setup separately (optional)
      (lifecycle/setup! :my/api)  ; One-time: setup all dependencies
      (lifecycle/start! :my/api)  ; Runtime: start all dependencies

      ;; Visualize dependencies
      (lifecycle/print-dependency-tree :my/api)
      ;; :my/api
      ;; └── :my/cache
      ;;     └── :my/database

      ;; Later: stop (NOT recursive - only stops this module)
      (lifecycle/stop! :my/api)

      ;; Cleanup (RECURSIVE with reference checking)
      (lifecycle/cleanup! :my/api)
      ;; → Cleans :my/api
      ;; → Cleans :my/cache (if no other modules depend on it)
      ;; → Cleans :my/database (if no other modules depend on it)

  ## Relationship to patcho.patch

  Lifecycle management is complementary to version management:
  - patcho.patch: Version migrations (data/schema state transitions)
  - patcho.lifecycle: Runtime management (start/stop with dependencies)

  They work together:
  1. Apply patches to reach target version (patch/level!)
  2. Start runtime services (lifecycle/start!) - auto-runs setup if needed

  Note: start! automatically runs setup for modules that have a setup function,
  making the system easy to use while maintaining explicit control when needed.

  ## Design Decisions

  - Registry pattern: Modules stored in atom (like Patcho's patches)
  - Keyword topics: Same style as Patcho (:my/module)
  - Explicit dependencies: Clear, validated at runtime
  - RECURSIVE operations: start/setup/cleanup handle deps automatically
  - Auto-setup: start! runs setup if needed (idempotent via lifecycle store)
  - Reference counting: cleanup only removes if not claimed by others
  - State tracking: Prevents double-start bugs
  - Functions not protocols: Simple, direct
  - Minimal dependencies: Just clojure.core"
  (:require
    [clojure.java.io :as io]
    [clojure.set :as set]
    [clojure.string :as str]))

;;; ============================================================================
;;; Registry
;;; ============================================================================

(defonce ^{:private true
           :doc "Registry of all registered modules.

  Structure:
    {topic-keyword {:depends-on [...]
                    :setup fn
                    :cleanup fn
                    :start fn
                    :stop fn
                    :started? (atom false)}}"}
  modules
  (atom {}))

(def ^{:doc "Registry of module errors. Structure: {topic {:error Exception :timestamp Date}}"}
  module-errors
  (atom {}))

;;; ============================================================================
;;; LifecycleStore Protocol
;;; ============================================================================

(defprotocol LifecycleStore
  "Persistent storage for lifecycle state tracking.

  Tracks one-time operations (setup, cleanup) across process restarts.
  Runtime state (started?) is NOT persisted - it's in-memory only."
  (read-lifecycle-state [store topic]
    "Read persistent state for a topic.
    Returns map with :setup-complete?, :cleanup-complete?, etc.")
  (write-lifecycle-state [store topic state]
    "Write persistent state for a topic.
    State is a map with :setup-complete?, :cleanup-complete?, etc."))

;;; ============================================================================
;;; File-based LifecycleStore
;;; ============================================================================

(defn- read-edn-file
  "Read EDN file, return empty map if doesn't exist."
  [file-path]
  (try
    (let [file (io/file file-path)]
      (if (.exists file)
        (read-string (slurp file))
        {}))
    (catch Exception _
      {})))

(defn- write-edn-file
  "Write data to EDN file."
  [file-path data]
  (spit file-path (pr-str data)))

(defrecord FileLifecycleStore [file-path]
  LifecycleStore
  (read-lifecycle-state [_ topic]
    (get (read-edn-file file-path) topic {}))
  (write-lifecycle-state [_ topic state]
    (let [current (read-edn-file file-path)
          updated (assoc current topic state)]
      (write-edn-file file-path updated))))

;;; ============================================================================
;;; Atom-based LifecycleStore (in-memory)
;;; ============================================================================

(defrecord AtomLifecycleStore [state-atom]
  LifecycleStore
  (read-lifecycle-state [_ topic]
    (get @state-atom topic {}))
  (write-lifecycle-state [_ topic state]
    (swap! state-atom assoc topic state)))

(defn migrate-store!
  "Migrate lifecycle state from one store to another.

  Useful for bootstrapping: start with AtomLifecycleStore, setup database,
  then migrate state to database-backed store.

  Args:
    from-store - Source LifecycleStore to read from
    to-store   - Destination LifecycleStore to write to
    topics     - Collection of topic keywords to migrate (or nil for all registered)

  Returns:
    Set of migrated topics

  Example:
    ;; Bootstrap with in-memory store
    (def bootstrap-store (->AtomLifecycleStore (atom {})))
    (set-store! bootstrap-store)
    (setup! :synthigy/database)

    ;; Migrate to database store
    (migrate-store! bootstrap-store db/*db* nil)
    (set-store! db/*db*)"
  [from-store to-store topics]
  (let [topics (or topics (keys @modules))]
    (doseq [topic topics]
      (let [state (read-lifecycle-state from-store topic)]
        (when (seq state)
          (write-lifecycle-state to-store topic state))))
    (set topics)))

;;; ============================================================================
;;; Store Management
;;; ============================================================================

(def ^:dynamic *lifecycle-store*
  "Dynamic var for the lifecycle store.

  Defaults to AtomLifecycleStore (in-memory). State is lost on restart unless
  you explicitly set a persistent store (FileLifecycleStore, database, etc.).

  Set globally via set-store!, or temporarily via with-store macro.
  State is automatically migrated when switching stores."
  (->AtomLifecycleStore (atom {})))

(defn set-store!
  "Set the default lifecycle store globally.

  Automatically migrates state from the previous store to the new one.
  This makes store transitions seamless (e.g., from bootstrap AtomLifecycleStore
  to database-backed store).

   Arguments:
     store - Implementation of LifecycleStore protocol (or nil to clear)

   Example:
     ;; Bootstrap with in-memory store
     (set-store! (->AtomLifecycleStore (atom {})))
     (setup! :my/database)

     ;; Switch to DB - state auto-migrates
     (set-store! db/*db*)

     (setup! :my/app)     ; Uses database store"
  [store]
  (alter-var-root #'*lifecycle-store*
                  (fn [old-store]
                    (when (and old-store (not= old-store store))
                      (migrate-store! old-store store nil))
                    store)))

(defn reset-store!
  "Reset lifecycle store to a fresh in-memory AtomLifecycleStore.

  Use this when cleaning up the database to clear all persisted state
  and start fresh. Does NOT migrate state (old state is discarded).

  Example:
    ;; In database cleanup
    (reset-store!)  ; Fresh atom store, no state"
  []
  (alter-var-root #'*lifecycle-store* (constantly (->AtomLifecycleStore (atom {})))))

(defmacro with-store
  "Execute body with a specific LifecycleStore bound to *lifecycle-store*.

   Useful for testing or temporary overrides.

   Arguments:
     store - Implementation of LifecycleStore protocol
     body  - Expressions to execute with the store bound

   Example:
     (with-store (->FileLifecycleStore \".test-lifecycle\")
       (setup! :my/app)
       (cleanup! :other-app))"
  [store & body]
  `(binding [*lifecycle-store* ~store]
     ~@body))

;;; ============================================================================
;;; Registration
;;; ============================================================================

(defn register-module!
  "Register a module with its lifecycle functions and dependencies.

  This should be called at namespace load time (top-level form).

  Parameters:
    topic   - Keyword identifying the module (e.g., :synthigy/iam)
    spec    - Map with keys:
              :depends-on   - Vector of topic keywords this module depends on
              :setup        - (Optional) fn taking no args for one-time setup
              :cleanup      - (Optional) fn taking no args for one-time cleanup
              :start        - fn taking no args to start runtime services
              :stop         - fn taking no args to stop runtime services

  Example:
    (register-module! :my/database
      {:start (fn [] (connect! db-config))
       :stop (fn [] (disconnect!))})

    (register-module! :my/cache
      {:depends-on [:my/database]
       :setup (fn [] (create-cache-tables!))
       :cleanup (fn [] (drop-cache-tables!))
       :start (fn [] (start-cache!))
       :stop (fn [] (stop-cache!))})"
  [topic {:keys [depends-on setup cleanup start stop]
          :or {stop (fn [] nil)
               start (fn [] nil)}}]
  {:pre [(keyword? topic)
         (or (nil? depends-on) (vector? depends-on))
         (fn? start)
         (fn? stop)]}
  (swap! modules assoc topic
         {:depends-on (or depends-on [])
          :setup setup
          :cleanup cleanup
          :start start
          :stop stop
          :started? false})
  nil)

;;; ============================================================================
;;; State Inspection
;;; ============================================================================

(defn registered-modules
  "Returns vector of all registered module topics."
  []
  (vec (keys @modules)))

(defn started-modules
  "Returns vector of currently started module topics."
  []
  (vec (keep (fn [[topic module]]
               (when (:started? module) topic))
             @modules)))

(defn module-info
  "Returns info map for a module, or nil if not registered.

  If *lifecycle-store* is set, also includes :setup-complete? and :cleanup-complete?.

  Returns:
    {:depends-on [...]
     :started? true/false
     :has-setup? true/false
     :has-cleanup? true/false
     :setup-complete? true/false    ; Only if *lifecycle-store* is set
     :cleanup-complete? true/false} ; Only if *lifecycle-store* is set"
  [topic]
  (when-let [module (get @modules topic)]
    (let [base-info {:depends-on (:depends-on module)
                     :started? (:started? module)
                     :has-setup? (some? (:setup module))
                     :has-cleanup? (some? (:cleanup module))}]
      ;; If a store is configured, include persistent state
      (if *lifecycle-store*
        (merge base-info (read-lifecycle-state *lifecycle-store* topic))
        base-info))))


(defn setup-complete?
  [topic]
  (:setup-complete? (read-lifecycle-state *lifecycle-store* topic)))


(defn started?
  "Returns true if module is currently started."
  [topic]
  (when-let [module (get @modules topic)]
    (:started? module)))

;;; ============================================================================
;;; Dependency Helpers
;;; ============================================================================

(defn- validate-module-exists
  "Throws if module is not registered."
  [topic]
  (when-not (contains? @modules topic)
    (throw (ex-info "Module not registered"
                    {:module topic
                     :registered (registered-modules)}))))

(defn- validate-dependencies
  "Validates that all dependencies are registered."
  [topic depends-on]
  (let [missing (remove #(contains? @modules %) depends-on)]
    (when (seq missing)
      (throw (ex-info "Missing module dependencies"
                      {:module topic
                       :missing-dependencies missing
                       :registered (registered-modules)})))))

(defn- get-active-dependents
  "Returns set of registered modules that depend on this topic AND haven't been cleaned up yet."
  [topic]
  (when *lifecycle-store*
    (set (keep (fn [[t module]]
                 (when (and (some #{topic} (:depends-on module))
                            (not (:cleanup-complete? (read-lifecycle-state *lifecycle-store* t))))
                   t))
               @modules))))

;;; ============================================================================
;;; Lifecycle Operations
;;; ============================================================================

;; Forward declarations (setup! is defined later but called from start!)
(declare setup!)

(defn start!
  "Start a module RECURSIVELY (starts all dependencies first).

  This is idempotent - won't start if already started.
  Dependencies are started in correct order automatically.
  Records errors in module-errors atom if startup fails.

  If module has a setup function and setup hasn't been completed yet,
  automatically runs setup first (idempotent via lifecycle store).

  Parameters:
    topic - Module keyword

  Example:
    (start! :my/api)
    ;; → Setups (if needed) and starts: :my/database → :my/cache → :my/api"
  ([topic]
   (validate-module-exists topic)
   (let [module (get @modules topic)
         {:keys [depends-on start started? setup]} module]
     ;; Early exit if already started - no need to recurse
     (when-not started?
       (try
         ;; Validate dependencies exist
         (validate-dependencies topic depends-on)

         ;; Auto-run setup if module has one (idempotent via lifecycle store)
         (when (and (ifn? setup) *lifecycle-store*)
           (setup! topic))

         ;; RECURSIVELY start dependencies first
         (doseq [dep depends-on]
           (start! dep))

         ;; Start this module
         ;; Clear any previous error for this module
         (swap! module-errors dissoc topic)
         (start)
         (swap! modules assoc-in [topic :started?] true)

         (catch Exception e
           ;; Record error with timestamp
           (swap! module-errors assoc topic
                  {:error e
                   :timestamp (java.util.Date.)})
           ;; Re-throw to maintain existing behavior
           (throw e))))))
  ([topic & more-topics]
   (doseq [t (cons topic more-topics)]
     (start! t))))

(defn stop!
  "Stop a module (NOT recursive - only stops this module).

  This is idempotent - won't stop if already stopped.

  Parameters:
    topic - Module keyword

  Example:
    (stop! :my/cache)"
  [topic]
  (validate-module-exists topic)
  (let [module (get @modules topic)
        {:keys [stop started?]} module]
    ;; Only stop if actually running
    (when started?
      (stop)
      (swap! modules assoc-in [topic :started?] false))))

(defn- setup-single!
  "Run one-time setup for a single module RECURSIVELY.

  Internal function - use setup! instead."
  [topic]
  (when-not *lifecycle-store*
    (throw (ex-info "No lifecycle store configured. Use set-store!"
                    {:module topic})))
  (validate-module-exists topic)
  (let [module (get @modules topic)
        {:keys [depends-on setup]} module]

    ;; Early exit if already setup - no need to recurse
    (when-not (setup-complete? topic)
      ;; Validate dependencies exist
      (validate-dependencies topic depends-on)

      ;; Make sure that everything is setup
      (doseq [dep depends-on]
        (setup-single! dep))

      ;; RECURSIVELY start dependencies first (setup needs them running!)
      (doseq [dep depends-on]
        (start! dep))

      ;; Run setup
      (when (and (ifn? setup) (not (setup-complete? topic)))
        (setup))
      ;; Mark setup complete and reset cleanup-complete so cleanup can run again
      (write-lifecycle-state *lifecycle-store* topic
                             (assoc (read-lifecycle-state *lifecycle-store* topic)
                               :setup-complete? true
                               :cleanup-complete? false)))))

(defn setup!
  "Run one-time setup for one or more modules RECURSIVELY.

  This is idempotent - tracks if setup already ran via LifecycleStore.
  STARTS dependencies first (setup often needs them running).

  Uses *lifecycle-store* - set it via set-store! or with-store macro.

  Parameters:
    topics - One or more module keywords

  Example:
    (set-store! (->FileLifecycleStore \".lifecycle\"))
    (setup! :my/cache)
    (setup! :my/db :my/cache :my/api)"
  [& topics]
  (doseq [topic topics]
    (setup-single! topic)))

(defn- get-dependants
  "Returns set of registered modules that depend on this topic."
  [topic]
  (set (keep (fn [[t module]]
               (when (some #{topic} (:depends-on module))
                 t))
             @modules)))

(defn cleanup!
  "Run one-time cleanup for a module and all its dependants (modules that depend on it).

  Cleanup order:
  1. First cleanup all dependants (modules that depend on this one) - recursively
  2. Then cleanup this module

  This ensures no module is left in an inconsistent state with a missing dependency.
  Dependencies (modules this one depends on) are NOT cleaned - they may be shared.

  This is idempotent - tracks if cleanup already ran via LifecycleStore.

  Uses *lifecycle-store* - set it via set-store! or with-store macro.

  Parameters:
    topic - Module keyword

  Example:
    (set-store! (->FileLifecycleStore \".lifecycle\"))
    (cleanup! :my/database)  ; Also cleans up :my/cache if it depends on :my/database"
  [topic]
  (when-not *lifecycle-store*
    (throw (ex-info "No lifecycle store configured. Use set-store!"
                    {:module topic})))
  (validate-module-exists topic)

  ;; First, recursively cleanup all dependants (modules that depend on this one)
  (doseq [dependant (get-dependants topic)]
    (cleanup! dependant))

  ;; Stop this module before cleanup (must stop before destroying resources)
  (stop! topic)

  ;; Then cleanup this module (if it has a cleanup function)
  ;; Always run cleanup - it should be idempotent (safe to run multiple times)
  (let [module (get @modules topic)
        {:keys [cleanup]} module]
    (when cleanup
      (cleanup)
      ;; Mark cleanup complete and reset setup-complete so setup can run again
      ;; Note: we write state AFTER cleanup runs, in case cleanup deletes the store
      (when *lifecycle-store*
        (try
          (write-lifecycle-state *lifecycle-store* topic
                                 {:cleanup-complete? true
                                  :setup-complete? false})
          (catch Exception _ nil))))))

(defn restart!
  "Restart a module (stop then start).

  Useful for REPL development when code changes.

  Parameters:
    topic - Module keyword

  Example:
    (restart! :my/cache)"
  [topic]
  (stop! topic)
  (start! topic))

;;; ============================================================================
;;; Dependency Visualization
;;; ============================================================================

(defn- build-dependency-tree
  "Build dependency tree structure for a topic.

  Returns:
    {:topic keyword
     :children [{:topic ... :children [...]} ...]}"
  [topic]
  (let [module (get @modules topic)]
    {:topic topic
     :children (mapv build-dependency-tree (:depends-on module))}))

(defn- tree->lines
  "Convert tree structure to vector of [indent text] pairs."
  [tree prefix is-last?]
  (let [{:keys [topic children]} tree
        connector (if is-last? "└── " "├── ")
        extension (if is-last? "    " "│   ")
        current-line (str prefix connector (name topic))
        child-count (count children)
        child-lines (mapcat (fn [idx child]
                              (let [is-last-child? (= idx (dec child-count))]
                                (tree->lines child
                                             (str prefix extension)
                                             is-last-child?)))
                            (range child-count)
                            children)]
    (cons current-line child-lines)))

(defn dependency-tree-string
  "Generate ASCII tree visualization of module dependencies.

  With no arguments, shows all root modules (modules nothing depends on).
  With topic argument, shows dependency tree for that module.

  Parameters:
    topic - (Optional) Module keyword to visualize

  Returns:
    String with ASCII tree visualization

  Example:
    (dependency-tree-string :my/api)
    ;; => \":my/api
    ;;     ├── :my/cache
    ;;     │   └── :my/database
    ;;     └── :my/auth
    ;;         └── :my/database\""
  ([]
   (let [all-topics (set (keys @modules))
         all-deps (set (mapcat :depends-on (vals @modules)))
         roots (set/difference all-topics all-deps)]
     (if (empty? roots)
       "(no modules registered)"
       (str/join "\n\n" (map dependency-tree-string (sort roots))))))
  ([topic]
   (validate-module-exists topic)
   (let [tree (build-dependency-tree topic)
         lines (cons (str (name topic))
                     (let [children (:children tree)
                           child-count (count children)]
                       (mapcat (fn [idx child]
                                 (let [is-last? (= idx (dec child-count))]
                                   (tree->lines child "" is-last?)))
                               (range child-count)
                               children)))]
     (str/join "\n" lines))))

(defn print-dependency-tree
  "Print ASCII tree visualization of module dependencies.

  With no arguments, shows all root modules.
  With topic argument, shows dependency tree for that module.

  Parameters:
    topic - (Optional) Module keyword to visualize

  Example:
    (print-dependency-tree :my/api)
    ;; Prints:
    ;; :my/api
    ;; ├── :my/cache
    ;; │   └── :my/database
    ;; └── :my/auth
    ;;     └── :my/database"
  ([]
   (println (dependency-tree-string)))
  ([topic]
   (println (dependency-tree-string topic))))

;;; ============================================================================
;;; Dependency Graph
;;; ============================================================================

(defn dependency-graph
  "Returns the dependency graph as a map.

  Useful for visualization or debugging.

  Returns:
    {topic {:depends-on [...] :dependents [...]}}

  Example:
    (dependency-graph)
    ;; => {:my/database {:depends-on [] :dependents [:my/cache]}
    ;;     :my/cache {:depends-on [:my/database] :dependents [:my/api]}
    ;;     :my/api {:depends-on [:my/cache] :dependents []}}"
  []
  (let [deps (into {} (map (fn [[k v]] [k (:depends-on v)]) @modules))
        dependents (reduce-kv
                     (fn [acc topic depends-on]
                       (reduce (fn [a dep]
                                 (update a dep (fnil conj []) topic))
                               acc
                               depends-on))
                     {}
                     deps)]
    (into {}
          (map (fn [[topic depends-on]]
                 [topic {:depends-on depends-on
                         :dependents (get dependents topic [])}])
               deps))))

;;; ============================================================================
;;; Layered Topology Visualization
;;; ============================================================================

(defn- layer-label
  "Generate label for a layer number."
  [layer-num total-layers]
  (cond
    (= layer-num 0) "Layer 0 (foundation)"
    (= layer-num (dec total-layers)) (str "Layer " layer-num " (top-level)")
    :else (str "Layer " layer-num)))

(defn- calculate-layers
  "Calculate dependency layers for all modules.

  Returns map of {topic layer-number} where:
  - Layer 0 = no dependencies
  - Layer N = max(dependency layers) + 1

  Uses iterative algorithm to assign layers."
  [module-map]
  (let [all-topics (set (keys module-map))]
    (loop [layers {}
           remaining all-topics]
      (if (empty? remaining)
        layers
        (let [;; Find modules whose deps are all in layers already
              ready (filter (fn [topic]
                              (let [deps (:depends-on (module-map topic))]
                                (every? #(contains? layers %) deps)))
                            remaining)]
          (if (empty? ready)
            (throw (ex-info "Circular dependency detected"
                            {:remaining (vec remaining)}))
            ;; Assign layer based on max dependency layer + 1
            (let [new-layers (reduce (fn [acc topic]
                                       (let [deps (:depends-on (module-map topic))
                                             max-dep-layer (if (empty? deps)
                                                             -1
                                                             (apply max (map layers deps)))]
                                         (assoc acc topic (inc max-dep-layer))))
                                     layers
                                     ready)]
              (recur new-layers (set/difference remaining (set ready))))))))))

(defn- group-by-layer
  "Group modules by their layer number.

  Returns sorted vector of [layer-num [topics...]]."
  [layers]
  (->> layers
       (group-by second)
       (sort-by first)
       (mapv (fn [[layer topics]]
               [layer (sort (map first topics))]))))

(defn topology-layers-string
  "Generate layered list visualization of all modules.

  Shows modules grouped by dependency depth with explicit dependencies.

  Returns:
    String with layered list visualization

  Example:
    (topology-layers-string)
    ;; => \"Layer 0 (foundation):
    ;;       :synthigy/transit
    ;;
    ;;     Layer 1:
    ;;       :synthigy/database → [:synthigy/transit]
    ;;
    ;;     Layer 2:
    ;;       :synthigy/dataset → [:synthigy/database]
    ;;
    ;;     Layer 3:
    ;;       :synthigy/iam → [:synthigy/dataset]
    ;;       :synthigy/lacinia → [:synthigy/dataset]\""
  []
  (if (empty? @modules)
    "(no modules registered)"
    (let [module-map @modules
          layers (calculate-layers module-map)
          grouped (group-by-layer layers)
          max-layer (apply max (map first grouped))]
      (->> grouped
           (map (fn [[layer-num layer-modules]]
                  (let [label (layer-label layer-num max-layer)
                        module-lines (map (fn [topic]
                                            (let [deps (:depends-on (module-map topic))]
                                              (if (empty? deps)
                                                (str "  " topic)
                                                (str "  " topic " → ["
                                                     (str/join ", " deps)
                                                     "]"))))
                                          layer-modules)]
                    (str label ":\n" (str/join "\n" module-lines)))))
           (str/join "\n\n")))))

(defn print-topology-layers
  "Print layered list visualization of all modules.

  Shows modules grouped by dependency depth with explicit dependencies.

  Example:
    (print-topology-layers)
    ;; Prints:
    ;; Layer 0 (foundation):
    ;;   transit
    ;;
    ;; Layer 1:
    ;;   database → [transit]
    ;;
    ;; Layer 2:
    ;;   dataset → [database]"
  []
  (println (topology-layers-string)))

;;; ============================================================================
;;; System Report
;;; ============================================================================

(defn system-report
  "Get comprehensive system status report including errors.

  Returns map with:
    :registered - Total count of registered modules
    :started - Count of started modules
    :stopped - Count of stopped modules
    :modules - Map of {topic {:status :started/:stopped
                              :depends-on [...]
                              :dependents [...]
                              :missing-dependencies [...]
                              :error {:message \"...\"
                                      :timestamp Date
                                      :exception Exception}}}

  Example:
    (system-report)
    ;=> {:registered 7
         :started 3
         :stopped 4
         :modules {:synthigy/transit {:status :started ...}
                   :synthigy/admin {:status :stopped
                                     :error {:message \"No such var: db/*db*\"
                                             :timestamp #inst \"2026-01-07\"
                                             :exception #<ExceptionInfo ...>}}}}}"
  []
  (let [all-modules (registered-modules)
        started (set (started-modules))
        graph (dependency-graph)
        errors @module-errors

        modules-data (into {}
                           (map (fn [topic]
                                  (let [info (module-info topic)
                                        deps (:depends-on info)
                                        all-registered (set all-modules)
                                        missing-deps (remove all-registered deps)
                                        error-info (get errors topic)]
                                    [topic (cond-> {:status (if (started? topic) :started :stopped)
                                                    :depends-on deps
                                                    :dependents (get-in graph [topic :dependents] [])}
                                             (seq missing-deps)
                                             (assoc :missing-dependencies (vec missing-deps))

                                             error-info
                                             (assoc :error {:message (.getMessage (:error error-info))
                                                            :timestamp (:timestamp error-info)
                                                            :exception (:error error-info)}))]))
                                all-modules))]
    {:registered (count all-modules)
     :started (count started)
     :stopped (- (count all-modules) (count started))
     :modules modules-data}))

(defn- format-exception
  "Format exception for printing with message, ex-data, and abbreviated stack trace."
  [exception]
  (let [message (.getMessage exception)
        cause (when-let [c (.getCause exception)] (.getMessage c))
        ex-data (when (instance? clojure.lang.ExceptionInfo exception)
                  (ex-data exception))
        stack-trace (take 5 (.getStackTrace exception))]
    (str/join "\n"
              (remove nil?
                      [(str "     Message: " message)
                       (when cause (str "     Cause: " cause))
                       (when ex-data (str "     Data: " (pr-str ex-data)))
                       (when (seq stack-trace)
                         (str "     Stack trace (top 5 frames):\n"
                              (str/join "\n"
                                        (map #(str "       " %) stack-trace))))]))))

(defn print-system-report
  "Print formatted system status report showing started/stopped modules and errors.

  Uses markers:
    [OK] - Started modules
    [ ]  - Stopped modules (ready to start)
    [!]  - Modules with errors or missing dependencies

  Example:
    (print-system-report)
    ;; === System Report ===
    ;; Registered: 7 | Started: 3 | Stopped: 4
    ;;
    ;; [OK] Started modules:
    ;;   * :synthigy/transit
    ;;   * :synthigy/database -> [:synthigy/transit]
    ;;
    ;; [ ] Stopped modules (ready to start):
    ;;   * :synthigy/iam -> [:synthigy/dataset]
    ;;
    ;; [!] Modules with errors:
    ;;   * :synthigy/admin -> [:synthigy/dataset]
    ;;     ERROR at 2026-01-07 18:23:45
    ;;     Message: No such var: db/*db*
    ;;     Data: {:module :synthigy/admin}"
  []
  (let [{:keys [registered started stopped modules]} (system-report)
        started-modules (filter #(= :started (get-in modules [% :status])) (keys modules))
        stopped-modules (filter #(= :stopped (get-in modules [% :status])) (keys modules))
        modules-with-errors (filter #(get-in modules [% :error]) (keys modules))
        modules-with-missing-deps (filter #(seq (get-in modules [% :missing-dependencies])) (keys modules))]

    (println "=== System Report ===")
    (println (str "Registered: " registered
                  " | Started: " started
                  " | Stopped: " stopped
                  (when (or (seq modules-with-errors)
                            (seq modules-with-missing-deps))
                    " | Issues detected")))

    ;; Started modules
    (when (seq started-modules)
      (println "\n[OK] Started modules:")
      (doseq [topic (sort started-modules)]
        (let [deps (get-in modules [topic :depends-on])]
          (println (str "  * " topic
                        (when (seq deps)
                          (str " -> [" (str/join ", " deps) "]")))))))

    ;; Stopped modules (without errors)
    (let [stopped-ok (remove (set modules-with-errors) stopped-modules)]
      (when (seq stopped-ok)
        (println "\n[ ] Stopped modules (ready to start):")
        (doseq [topic (sort stopped-ok)]
          (let [deps (get-in modules [topic :depends-on])]
            (println (str "  * " topic
                          (when (seq deps)
                            (str " -> [" (str/join ", " deps) "]"))))))))

    ;; Modules with errors
    (when (seq modules-with-errors)
      (println "\n[!] Modules with errors:")
      (doseq [topic (sort modules-with-errors)]
        (let [deps (get-in modules [topic :depends-on])
              error (get-in modules [topic :error])]
          (println (str "  * " topic
                        (when (seq deps)
                          (str " -> [" (str/join ", " deps) "]"))))
          (println (str "    ERROR at " (:timestamp error)))
          (println (str/join "\n" (map #(str "    " %)
                                       (str/split (format-exception (:exception error)) #"\n")))))))

    ;; Missing dependencies
    (when (seq modules-with-missing-deps)
      (println "\n[!] Missing dependencies:")
      (doseq [topic (sort modules-with-missing-deps)]
        (let [missing (get-in modules [topic :missing-dependencies])]
          (println (str "  * " topic " depends on "
                        (str/join ", " missing)
                        " (not registered)")))))))


(comment
  (print-system-report))

(defn clear-errors!
  "Clear all recorded module errors.

  Useful after fixing issues and before retrying startup.

  Example:
    (clear-errors!)
    (start! :my/module)  ; Fresh start without old errors"
  []
  (reset! module-errors {}))

;;; ============================================================================
;;; Testing Utilities
;;; ============================================================================

(defn reset-registry!
  "Clear all registered modules.

  WARNING: This is primarily for testing. Using in production
  may leave modules in inconsistent state.

  Stop your running modules first before calling this."
  []
  (reset! modules {}))
