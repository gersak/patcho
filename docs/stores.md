# Extending Store Protocols

Patcho uses two protocols for persisting state across process restarts:

- **VersionStore** (`patcho.patch`) - Tracks which version each component has been migrated to
- **LifecycleStore** (`patcho.lifecycle`) - Tracks setup/cleanup state for modules

Both come with built-in file and atom implementations, but you'll typically want database-backed stores in production.

## VersionStore Protocol

```clojure
(defprotocol VersionStore
  (read-version [this topic]
    "Read the currently installed version for the given topic.
     Should return a version string or \"0\" if not found.")
  (write-version [this topic version]
    "Persist the installed version for the given topic.
     Called automatically by apply after successful migration."))
```

### Built-in Implementations

```clojure
;; File-based (persists to EDN file)
(def store (patch/->FileVersionStore "versions.edn"))

;; Atom-based (in-memory, for testing)
(def store (patch/->AtomVersionStore (atom {})))
```

### Database Implementation

Here's a complete Postgres implementation:

```clojure
(ns myapp.db
  (:require
    [next.jdbc :as jdbc]
    [patcho.patch :as patch]))

(defrecord Postgres [datasource])

;; Table schema
(defn ensure-version-table!
  "Creates __component_versions__ table if it doesn't exist."
  [{:keys [datasource]}]
  (jdbc/execute-one!
    datasource
    ["CREATE TABLE IF NOT EXISTS __component_versions__ (
       id BIGSERIAL PRIMARY KEY,
       component TEXT NOT NULL UNIQUE,
       version TEXT NOT NULL,
       updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
     )"]))

;; Protocol implementation
(extend-type Postgres
  patch/VersionStore

  (read-version [db topic]
    (if-let [row (jdbc/execute-one!
                   (:datasource db)
                   ["SELECT version FROM __component_versions__
                     WHERE component = ?"
                    (str topic)])]
      (:__component_versions__/version row)
      "0"))

  (write-version [db topic version]
    (jdbc/execute-one!
      (:datasource db)
      ["INSERT INTO __component_versions__ (component, version, updated_at)
        VALUES (?, ?, CURRENT_TIMESTAMP)
        ON CONFLICT (component)
        DO UPDATE SET version = EXCLUDED.version,
                      updated_at = CURRENT_TIMESTAMP"
       (str topic) version])))
```

SQLite version (note the different UPSERT syntax):

```clojure
(extend-type SQLite
  patch/VersionStore

  (read-version [db topic]
    (ensure-version-table! db)
    (if-let [row (jdbc/execute-one!
                   (:datasource db)
                   ["SELECT version FROM __component_versions__
                     WHERE component = ?
                     ORDER BY updated_at DESC
                     LIMIT 1"
                    (str topic)])]
      (:__component_versions__/version row)
      "0"))

  (write-version [db topic version]
    (ensure-version-table! db)
    (jdbc/execute-one!
      (:datasource db)
      ["INSERT INTO __component_versions__ (component, version, updated_at)
        VALUES (?, ?, CURRENT_TIMESTAMP)
        ON CONFLICT (component)
        DO UPDATE SET version = excluded.version,
                      updated_at = CURRENT_TIMESTAMP"
       (str topic) version])))
```

## LifecycleStore Protocol

```clojure
(defprotocol LifecycleStore
  (read-lifecycle-state [store topic]
    "Read persistent state for a topic.
     Returns map with :setup-complete?, :cleanup-complete?, etc.")
  (write-lifecycle-state [store topic state]
    "Write persistent state for a topic.
     State is a map with :setup-complete?, :cleanup-complete?, etc."))
```

### Built-in Implementations

```clojure
;; File-based (persists to EDN file)
(def store (lifecycle/->FileLifecycleStore ".lifecycle"))

;; Atom-based (in-memory, default)
(def store (lifecycle/->AtomLifecycleStore (atom {})))
```

### Database Implementation

Postgres implementation:

```clojure
(ns myapp.db
  (:require
    [next.jdbc :as jdbc]
    [patcho.lifecycle :as lifecycle]))

;; Table schema
(defn ensure-lifecycle-table!
  [{:keys [datasource]}]
  (jdbc/execute-one!
    datasource
    ["CREATE TABLE IF NOT EXISTS __lifecycle_state__ (
       topic TEXT PRIMARY KEY,
       setup_complete BOOLEAN DEFAULT FALSE,
       cleanup_complete BOOLEAN DEFAULT FALSE,
       updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
     )"]))

(extend-type Postgres
  lifecycle/LifecycleStore

  (read-lifecycle-state [db topic]
    (if-let [row (jdbc/execute-one!
                   (:datasource db)
                   ["SELECT setup_complete, cleanup_complete
                     FROM __lifecycle_state__
                     WHERE topic = ?"
                    (name topic)])]
      {:setup-complete? (:__lifecycle_state__/setup_complete row)
       :cleanup-complete? (:__lifecycle_state__/cleanup_complete row)}
      {:setup-complete? false
       :cleanup-complete? false}))

  (write-lifecycle-state [db topic state]
    (jdbc/execute-one!
      (:datasource db)
      ["INSERT INTO __lifecycle_state__ (topic, setup_complete, cleanup_complete, updated_at)
        VALUES (?, ?, ?, CURRENT_TIMESTAMP)
        ON CONFLICT (topic)
        DO UPDATE SET setup_complete = EXCLUDED.setup_complete,
                      cleanup_complete = EXCLUDED.cleanup_complete,
                      updated_at = CURRENT_TIMESTAMP"
       (name topic)
       (:setup-complete? state false)
       (:cleanup-complete? state false)])))
```

SQLite (note boolean handling - SQLite uses 0/1):

```clojure
(extend-type SQLite
  lifecycle/LifecycleStore

  (read-lifecycle-state [db topic]
    (ensure-lifecycle-table! db)
    (if-let [row (jdbc/execute-one!
                   (:datasource db)
                   ["SELECT setup_complete, cleanup_complete
                     FROM __lifecycle_state__
                     WHERE topic = ?"
                    (name topic)])]
      {:setup-complete? (= 1 (:__lifecycle_state__/setup_complete row))
       :cleanup-complete? (= 1 (:__lifecycle_state__/cleanup_complete row))}
      {:setup-complete? false
       :cleanup-complete? false}))

  (write-lifecycle-state [db topic state]
    (ensure-lifecycle-table! db)
    (jdbc/execute-one!
      (:datasource db)
      ["INSERT INTO __lifecycle_state__ (topic, setup_complete, cleanup_complete, updated_at)
        VALUES (?, ?, ?, CURRENT_TIMESTAMP)
        ON CONFLICT (topic)
        DO UPDATE SET setup_complete = excluded.setup_complete,
                      cleanup_complete = excluded.cleanup_complete,
                      updated_at = CURRENT_TIMESTAMP"
       (name topic)
       (if (:setup-complete? state) 1 0)
       (if (:cleanup-complete? state) 1 0)])))
```

## Best Practices

### 1. Idempotent Table Creation

Always use `CREATE TABLE IF NOT EXISTS`. Call it in your implementation methods or during application startup:

```clojure
(read-version [db topic]
  (ensure-version-table! db)  ; Safe to call repeatedly
  ...)
```

### 2. Use a Single Store for Both Protocols

If your database type implements both protocols, use the same instance:

```clojure
(def db (->Postgres datasource))

(patch/set-store! db)      ; For version tracking
(lifecycle/set-store! db)  ; For lifecycle state
```

### 3. Store Migration (Bootstrap Pattern)

When your database itself is managed by lifecycle, you need a bootstrap pattern:

```clojure
;; 1. Start with in-memory store
(lifecycle/set-store! (lifecycle/->AtomLifecycleStore (atom {})))

;; 2. Setup database (tracked in memory)
(lifecycle/setup! :myapp/database)
(lifecycle/start! :myapp/database)

;; 3. Migrate state to database store (automatic via set-store!)
(lifecycle/set-store! db/*db*)

;; 4. Continue with other modules (now tracked in database)
(lifecycle/setup! :myapp/cache)
```

The `set-store!` function automatically migrates state from the old store to the new one.

### 4. Error Handling

Handle missing tables gracefully in `read-*` methods:

```clojure
(read-version [db topic]
  (try
    (query-version db topic)
    (catch Exception _
      "0")))  ; Return default on any error
```

### 5. Topic Serialization

Topics are keywords (`:myapp/database`). Store them consistently:
- As strings: `(str topic)` → `":myapp/database"`
- As names: `(name topic)` → `"database"` (loses namespace!)

Prefer `(str topic)` to preserve the full keyword including namespace.

## Testing Custom Stores

```clojure
(deftest my-store-test
  (let [db (create-test-database)
        store (->MyDatabase db)]

    ;; VersionStore
    (is (= "0" (patch/read-version store :test/app)))
    (patch/write-version store :test/app "1.0.0")
    (is (= "1.0.0" (patch/read-version store :test/app)))

    ;; LifecycleStore
    (is (= {:setup-complete? false :cleanup-complete? false}
           (lifecycle/read-lifecycle-state store :test/app)))
    (lifecycle/write-lifecycle-state store :test/app {:setup-complete? true})
    (is (= true (:setup-complete?
                  (lifecycle/read-lifecycle-state store :test/app))))))
```
