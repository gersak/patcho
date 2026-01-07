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
      (lifecycle/set-default-store! (lifecycle/->FileLifecycleStore \".lifecycle\"))

      ;; One-time setup (RECURSIVE - handles all dependencies)
      (lifecycle/setup! :my/api)
      ;; → Starts :my/database, setups :my/database
      ;; → Starts :my/cache, setups :my/cache
      ;; → Setups :my/api

      ;; Start application (RECURSIVE)
      (lifecycle/start! :my/api)
      ;; → Starts: :my/database → :my/cache → :my/api

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
  2. Run one-time setup if needed (lifecycle/setup!)
  3. Start runtime services (lifecycle/start!)

  ## Design Decisions

  - Registry pattern: Modules stored in atom (like Patcho's patches)
  - Keyword topics: Same style as Patcho (:my/module)
  - Explicit dependencies: Clear, validated at runtime
  - RECURSIVE operations: start/setup/cleanup handle deps automatically
  - Reference counting: cleanup only removes if not claimed by others
  - State tracking: Prevents double-start bugs
  - Functions not protocols: Simple, direct
  - Minimal dependencies: Just clojure.core"
  (:require
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

(defn- read-edn-file [file-path]
  "Read EDN file, return empty map if doesn't exist."
  (try
    (let [file (clojure.java.io/file file-path)]
      (if (.exists file)
        (read-string (slurp file))
        {}))
    (catch Exception _
      {})))

(defn- write-edn-file [file-path data]
  "Write data to EDN file."
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
;;; Store Management
;;; ============================================================================

(def ^:dynamic *lifecycle-store*
  "Dynamic var for the lifecycle store.

  Defaults to FileLifecycleStore with `.lifecycle` file.
  Set globally via set-default-store!, or temporarily via with-store macro."
  (->FileLifecycleStore ".lifecycle"))

(defn set-default-store!
  "Set the default lifecycle store globally.

  This sets the root binding of *lifecycle-store* for all lifecycle operations.

   Arguments:
     store - Implementation of LifecycleStore protocol

   Example:
     (set-default-store! (->FileLifecycleStore \".lifecycle\"))

     (setup! :my/app)     ; Uses the default store
     (cleanup! :my/db)    ; Uses the default store"
  [store]
  (alter-var-root #'*lifecycle-store* (constantly store)))

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
    topic   - Keyword identifying the module (e.g., :synticity/iam)
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
          :started? (atom false)})
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
               (when @(:started? module) topic))
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
                     :started? @(:started? module)
                     :has-setup? (some? (:setup module))
                     :has-cleanup? (some? (:cleanup module))}]
      ;; If a store is configured, include persistent state
      (if *lifecycle-store*
        (merge base-info (read-lifecycle-state *lifecycle-store* topic))
        base-info))))

(defn started?
  "Returns true if module is currently started."
  [topic]
  (when-let [module (get @modules topic)]
    @(:started? module)))

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

(defn- get-dependents
  "Returns set of registered modules that depend on this topic."
  [topic]
  (set (keep (fn [[t module]]
               (when (some #{topic} (:depends-on module))
                 t))
             @modules)))

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

(defn start!
  "Start a module RECURSIVELY (starts all dependencies first).

  This is idempotent - won't start if already started.
  Dependencies are started in correct order automatically.
  Records errors in module-errors atom if startup fails.

  Parameters:
    topic - Module keyword

  Example:
    (start! :my/api)
    ;; → Starts :my/database → :my/cache → :my/api"
  ([topic]
   (validate-module-exists topic)
   (let [module (get @modules topic)
         {:keys [depends-on start]} module
         start-atom (:started? module)]

     (try
       ;; Validate dependencies exist
       (validate-dependencies topic depends-on)

       ;; RECURSIVELY start dependencies first
       (doseq [dep depends-on]
         (start! dep))

       ;; Start this module (if not already started)
       (when-not @start-atom
         ;; Clear any previous error for this module
         (swap! module-errors dissoc topic)
         (start)
         (reset! start-atom true))

       (catch Exception e
         ;; Record error with timestamp
         (swap! module-errors assoc topic
                {:error e
                 :timestamp (java.util.Date.)})
         ;; Re-throw to maintain existing behavior
         (throw e)))))
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
        {:keys [stop]} module
        stop-atom (:started? module)]

    (when @stop-atom
      (stop)
      (reset! stop-atom false))))

(defn setup!
  "Run one-time setup for a module RECURSIVELY.

  This is idempotent - tracks if setup already ran via LifecycleStore.
  STARTS dependencies first (setup often needs them running).

  Uses *lifecycle-store* - set it via set-default-store! or with-store macro.

  Parameters:
    topic - Module keyword

  Example:
    (set-default-store! (->FileLifecycleStore \".lifecycle\"))
    (setup! :my/cache)"
  [topic]
  (when-not *lifecycle-store*
    (throw (ex-info "No lifecycle store configured. Use set-default-store!"
                    {:module topic})))
  (validate-module-exists topic)
  (let [module (get @modules topic)
        {:keys [depends-on setup]} module
        state (read-lifecycle-state *lifecycle-store* topic)]

    (when-not setup
      (throw (ex-info "Module has no setup function"
                      {:module topic})))

    ;; Validate dependencies exist
    (validate-dependencies topic depends-on)

    ;; RECURSIVELY start dependencies first (setup needs them running!)
    (doseq [dep depends-on]
      (start! dep))

    ;; Run setup (if not already complete)
    (when-not (:setup-complete? state)
      (setup)
      (write-lifecycle-state *lifecycle-store* topic
                             (assoc state :setup-complete? true)))))

(defn cleanup!
  "Run one-time cleanup for a module RECURSIVELY with reference checking.

  This is idempotent - tracks if cleanup already ran via LifecycleStore.
  Only cleans up dependencies if no other registered modules depend on them.

  Reference counting (claimed-by semantics):
  - Cleans up this module
  - Tries to cleanup each dependency
  - Only cleans dependency if no OTHER modules depend on it

  Uses *lifecycle-store* - set it via set-default-store! or with-store macro.

  Parameters:
    topic - Module keyword

  Example:
    (set-default-store! (->FileLifecycleStore \".lifecycle\"))
    (cleanup! :my/cache)"
  [topic]
  (when-not *lifecycle-store*
    (throw (ex-info "No lifecycle store configured. Use set-default-store!"
                    {:module topic})))
  (validate-module-exists topic)
  (let [module (get @modules topic)
        {:keys [depends-on cleanup]} module
        state (read-lifecycle-state *lifecycle-store* topic)]

    (when-not cleanup
      (throw (ex-info "Module has no cleanup function"
                      {:module topic})))

    ;; Cleanup this module (if not already complete)
    (when-not (:cleanup-complete? state)
      (cleanup)
      (write-lifecycle-state *lifecycle-store* topic
                             (assoc state :cleanup-complete? true)))

    ;; RECURSIVELY try to cleanup dependencies (with reference checking)
    (doseq [dep depends-on]
      ;; Only cleanup if no OTHER active (non-cleaned-up) modules depend on it
      (let [active-dependents (get-active-dependents dep)]
        (when (empty? active-dependents)
          (cleanup! dep))))))

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
;;; Batch Operations
;;; ============================================================================

(defn- topological-sort
  "Returns modules in dependency order (dependencies first).

  Uses Kahn's algorithm for topological sorting.
  Throws ex-info if circular dependencies detected."
  [module-map]
  (loop [result []
         remaining module-map
         in-degree (into {} (map (fn [[k v]]
                                   [k (count (:depends-on v))])
                                 module-map))]
    (if (empty? remaining)
      result
      (let [ready (filter (fn [[k _]] (zero? (in-degree k))) remaining)]
        (when (empty? ready)
          (throw (ex-info "Circular dependency detected"
                          {:remaining-modules (keys remaining)})))
        (let [next-topic (ffirst ready)]
          (recur
            (conj result next-topic)
            (dissoc remaining next-topic)
            (reduce (fn [deg dep]
                      (update deg dep dec))
                    (dissoc in-degree next-topic)
                    (mapcat (fn [[k v]]
                              (when (some #{next-topic} (:depends-on v))
                                [k]))
                            remaining))))))))

(defn start-all!
  "Start all registered modules in dependency order.

  Automatically calculates correct startup order using topological sort.
  Skips modules that are already started.

  Returns:
    Vector of started module topics in order

  Example:
    (start-all!)
    ;; => [:my/database :my/cache :my/api]"
  []
  (let [sorted (topological-sort @modules)]
    (doseq [topic sorted]
      (start! topic))
    sorted))

(defn stop-all!
  "Stop all started modules in reverse dependency order.

  Stops dependents before their dependencies.
  Skips modules that are not started.

  Returns:
    Vector of stopped module topics in order

  Example:
    (stop-all!)
    ;; => [:my/api :my/cache :my/database]"
  []
  (let [sorted (reverse (topological-sort @modules))
        to-stop (filter started? sorted)]
    (doseq [topic to-stop]
      (stop! topic))
    (vec to-stop)))

(defn setup-all!
  "Run setup for all registered modules in dependency order.

  Each module's dependencies are started before its setup runs.
  Skips modules that already have setup complete.

  Uses *lifecycle-store* - set it via set-default-store! or with-store macro.

  Returns:
    Vector of setup module topics in order

  Example:
    (set-default-store! (->FileLifecycleStore \".lifecycle\"))
    (setup-all!)"
  []
  (when-not *lifecycle-store*
    (throw (ex-info "No lifecycle store configured. Use set-default-store!"
                    {})))
  (let [sorted (topological-sort @modules)]
    (doseq [topic sorted]
      (when (:has-setup? (module-info topic))
        (setup! topic)))
    sorted))

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
    ;;       :synticity/transit
    ;;
    ;;     Layer 1:
    ;;       :synticity/database → [:synticity/transit]
    ;;
    ;;     Layer 2:
    ;;       :synticity/dataset → [:synticity/database]
    ;;
    ;;     Layer 3:
    ;;       :synticity/iam → [:synticity/dataset]
    ;;       :synticity/lacinia → [:synticity/dataset]\""
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
         :modules {:synticity/transit {:status :started ...}
                   :synticity/admin {:status :stopped
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
    ;;   * :synticity/transit
    ;;   * :synticity/database -> [:synticity/transit]
    ;;
    ;; [ ] Stopped modules (ready to start):
    ;;   * :synticity/iam -> [:synticity/dataset]
    ;;
    ;; [!] Modules with errors:
    ;;   * :synticity/admin -> [:synticity/dataset]
    ;;     ERROR at 2026-01-07 18:23:45
    ;;     Message: No such var: db/*db*
    ;;     Data: {:module :synticity/admin}"
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

  Use stop-all! first if modules are running."
  []
  (reset! modules {}))
