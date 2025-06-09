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

(defmulti _upgrade (fn [topic to] [topic to]))
(defmulti _downgrade (fn [topic to] [topic to]))
(defmulti version (fn [topic] topic))

(defn apply
  "Applies version patches to migrate from one version to another.
  
  With 2 args: Migrates from 'current' to the topic's defined current version.
  With 3 args: Migrates from 'current' to 'target' version.
  
  Arguments:
    topic   - Keyword identifying the module/component to patch
    current - Current version string (nil or \"0\" means start from beginning)  
    target  - Target version string (optional, defaults to topic's current version)
    
  The function automatically:
    - Determines upgrade vs downgrade direction
    - Finds applicable patches between versions
    - Executes patches in correct order (oldest-first for upgrades, newest-first for downgrades)
    
  Example:
    (apply ::my-app \"1.0.0\" \"2.0.0\")  ; Upgrade from 1.0.0 to 2.0.0
    (apply ::my-app \"2.0.0\" \"1.0.0\")  ; Downgrade from 2.0.0 to 1.0.0
    (apply ::my-app nil)                ; Upgrade from beginning to current"
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
                                                   (vrs/older? version current)
                                                   (vrs/newer-or-equal? version target)))
                                 data))
                             sorted)]
         (doseq [[topic version] valid-sequence]
           (case patch-direction
             :upgrade (_upgrade topic version)
             :downgrade (_downgrade topic version))))))))

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
