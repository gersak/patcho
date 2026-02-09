# Patcho

*Component solved the runtime problem in 2013. Integrant is Component for the data-driven crowd. Patcho asks: "what about the stuff that happens once?"*

[![Clojars Project](https://img.shields.io/clojars/v/dev.gersak/patcho.svg)](https://clojars.org/dev.gersak/patcho)

---

## Setup and Start Are Different Things

Creating a database and connecting to it are fundamentally different operations. Setup reads from the environment and creates resources that persist across restarts. Start activates those resources every time your application boots. Patching—applying version migrations—happens during start via `level!`, which reads the installed version from storage and applies any pending patches to reach the current version.

[See the full lifecycle →](#the-lifecycle)

## You Need Persistent Storage

Without persistent storage, every boot looks like the first boot. Patcho needs to remember what's already been set up and which version is installed. This requires out-of-band storage—a file during development, your database in production—that tracks installed versions and setup state across process restarts.

[Configure your store →](#persistent-storage)

## The Dev/Ops Workflow

On first deploy, setup runs once to create resources from environment variables, then patches migrate from version "0" to current. On subsequent boots, setup is skipped (already done), and patches only apply if the version has changed. When you deploy a new version with schema changes, the patches bring the database up to date automatically.

[See the complete example →](#complete-example)

## Clients Need Version Awareness

Services and HTTP clients need to know what version they're talking to. The `available-versions` function exposes registered component versions, useful for API endpoints that advertise compatibility and for service-to-service version checks before making requests.

[Expose versions to clients →](#version-awareness)


>[!IMPORTANT] **⚠️ You must configure persistent storage.**
>
> Both patches and lifecycle default to in-memory stores. Without persistence, every boot looks like the first boot—patches rerun, setup repeats. Before your application does anything meaningful, call:
>
> ```clojure
> (patch/set-store! (patch/->FileVersionStore ".versions"))
> (lifecycle/set-store! (lifecycle/->FileLifecycleStore ".lifecycle"))
> ```
>
> Or use database-backed stores in production. See [Persistent Storage](#persistent-storage) for the full story, including how to handle the chicken-and-egg problem when your database *is* the store.

---

## The Lifecycle

Patcho manages five distinct phases:

| Phase | When | Purpose |
|-------|------|---------|
| **Setup** | Once ever | Create from environment/config |
| **Patches** | Once per version | Evolve the resource |
| **Start** | Every boot | Activate runtime services |
| **Stop** | Every shutdown | Deactivate runtime services |
| **Cleanup** | When removing | Destroy (reverse of setup) |

Setup is about *existence*—reading `DATABASE_URL` from the environment and creating the database. Patches are about *evolution*—adding audit columns, creating indexes, running data migrations. These are separate concerns with different timing.

The order enforces correctness:
- Can't patch a database that doesn't exist → **setup first**
- Can't start something that isn't evolved → **patches after setup**
- Can't cleanup while running → **stop before cleanup**

Here's a database module showing all phases:

```clojure
(lifecycle/register-module! :myapp/database
  {:depends-on [:myapp/config]

   ;; SETUP: Create from environment (runs ONCE, tracked in store)
   :setup (fn []
            (let [url (System/getenv "DATABASE_URL")]
              (log/info "Creating database from environment...")
              (create-database! url)))

   ;; CLEANUP: Destroy (reverse of setup)
   :cleanup (fn []
              (log/info "Dropping database...")
              (drop-database!))

   ;; START: Connect first, THEN patch, then activate
   :start (fn []
            (let [url (System/getenv "DATABASE_URL")]
              (log/info "Connecting to database...")
              (alter-var-root #'*db* (constantly (connect url)))

              (log/info "Applying pending patches...")
              (patch/level! :myapp/database)  ; Now we can patch - we're connected!

              (log/info "Starting connection pool...")
              (start-connection-pool!)))

   ;; STOP: Deactivate
   :stop (fn []
           (stop-connection-pool!)
           (disconnect!)
           (alter-var-root #'*db* (constantly nil)))})
```

---

## Persistent Storage

### Why Storage is Required

Patcho tracks two kinds of state:
- **Installed version** — which version is deployed for each component
- **Setup state** — which modules have completed one-time setup

Without persistence, `level!` would apply all patches on every boot. Without setup tracking, your database would be recreated on every restart.

### Development: File-Based Stores

Both systems default to in-memory `AtomStore` implementations. For development, set file-based stores early in your application:

```clojure
;; In your main/init — before any lifecycle or patching operations
(patch/set-store! (patch/->FileVersionStore ".versions"))
(lifecycle/set-store! (lifecycle/->FileLifecycleStore ".lifecycle"))
```

These create EDN files in your project directory that persist across restarts.

### Production: Database-Backed Stores

For production, implement the `VersionStore` and `LifecycleStore` protocols on your database type:

```clojure
(ns myapp.db.postgres
  (:require
    [next.jdbc :as jdbc]
    [patcho.patch :as patch]
    [patcho.lifecycle :as lifecycle]))

(defrecord Postgres [datasource])

;; VersionStore - tracks installed patch versions
(extend-type Postgres
  patch/VersionStore

  (read-version [db topic]
    (if-let [row (jdbc/execute-one! (:datasource db)
                   ["SELECT version FROM __component_versions__ WHERE component = ?"
                    (str topic)])]
      (:__component_versions__/version row)
      "0"))

  (write-version [db topic version]
    (jdbc/execute-one! (:datasource db)
      ["INSERT INTO __component_versions__ (component, version, updated_at)
        VALUES (?, ?, CURRENT_TIMESTAMP)
        ON CONFLICT (component)
        DO UPDATE SET version = EXCLUDED.version, updated_at = CURRENT_TIMESTAMP"
       (str topic) version])))

;; LifecycleStore - tracks setup/cleanup state
(extend-type Postgres
  lifecycle/LifecycleStore

  (read-lifecycle-state [db topic]
    (if-let [row (jdbc/execute-one! (:datasource db)
                   ["SELECT setup_complete, cleanup_complete FROM __lifecycle_state__ WHERE topic = ?"
                    (name topic)])]
      {:setup-complete? (:__lifecycle_state__/setup_complete row)
       :cleanup-complete? (:__lifecycle_state__/cleanup_complete row)}
      {:setup-complete? false :cleanup-complete? false}))

  (write-lifecycle-state [db topic state]
    (jdbc/execute-one! (:datasource db)
      ["INSERT INTO __lifecycle_state__ (topic, setup_complete, cleanup_complete, updated_at)
        VALUES (?, ?, ?, CURRENT_TIMESTAMP)
        ON CONFLICT (topic)
        DO UPDATE SET setup_complete = EXCLUDED.setup_complete,
                      cleanup_complete = EXCLUDED.cleanup_complete,
                      updated_at = CURRENT_TIMESTAMP"
       (name topic)
       (:setup-complete? state)
       (:cleanup-complete? state)])))

;; Create the patcho tables (called once during database start)
(defn create-patcho-tables! [{:keys [datasource]}]
  (jdbc/execute-one! datasource
    ["CREATE TABLE IF NOT EXISTS __component_versions__ (
        component TEXT PRIMARY KEY,
        version TEXT NOT NULL,
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)"])
  (jdbc/execute-one! datasource
    ["CREATE TABLE IF NOT EXISTS __lifecycle_state__ (
        topic TEXT PRIMARY KEY,
        setup_complete BOOLEAN DEFAULT FALSE,
        cleanup_complete BOOLEAN DEFAULT FALSE,
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)"]))
```

### The Bootstrap Pattern

There's a chicken-and-egg problem: you need the database to store state, but you need state storage to set up the database.

Both systems now handle this the same way:
- Default to in-memory `AtomStore` (patches and lifecycle)
- `set-store!` automatically migrates state when switching stores

```clojure
(lifecycle/register-module! :myapp/database
  {:start (fn []
            ;; Connect to database
            (alter-var-root #'*db* (constantly (->Postgres (connect url))))
            (create-patcho-tables! *db*)

            ;; Switch to database-backed stores — state auto-migrates
            (patch/set-store! *db*)      ; Migrates from AtomVersionStore → Postgres
            (lifecycle/set-store! *db*)  ; Migrates from AtomLifecycleStore → Postgres

            ;; Now patches use database-backed store
            (patch/level! :myapp/database))
   :stop (fn []
           (disconnect! *db*)
           (alter-var-root #'*db* (constantly nil)))})

;; Just start
(lifecycle/start! :myapp/server)
;; → Starts :myapp/database first
;; → set-store! calls migrate state from atoms → database
;; → Then starts remaining modules
```

The in-memory atoms serve as bootstrap storage. When the database module starts and calls `set-store!`, both systems automatically migrate their state to the database. No file-based intermediate step needed.

---

## Complete Example

Here's a complete example with database and cache modules, showing patches with real SQL:

```clojure
(ns myapp.database.patch
  (:require
    [clojure.tools.logging :as log]
    [next.jdbc :as jdbc]
    [patcho.patch :as patch]
    [myapp.db :refer [*db*]]
    [myapp.schema :as schema]))

(patch/current-version :myapp/database "2.0.0")

;; 1.0.0 - Initial schema
(patch/upgrade :myapp/database "1.0.0"
  (log/info "[DB] Creating initial schema...")
  (jdbc/with-transaction [tx (:datasource *db*)]
    (doseq [table [:users :accounts :transactions]]
      (jdbc/execute! tx [(schema/create-table-sql table)])
      (log/infof "[DB] Created table: %s" table)))
  (log/info "[DB] Initial schema complete"))

;; 1.5.0 - Performance indexes
(patch/upgrade :myapp/database "1.5.0"
  (log/info "[DB] Adding performance indexes...")
  (let [indexes [["users" "email" :unique]
                 ["transactions" "created_at" :btree]
                 ["transactions" "account_id" :btree]]]
    (doseq [[table column type] indexes]
      (jdbc/execute! *db* [(format "CREATE %s INDEX IF NOT EXISTS idx_%s_%s ON %s (%s)"
                                   (if (= type :unique) "UNIQUE" "")
                                   table column table column)])
      (log/infof "[DB] Created index: %s.%s (%s)" table column type))))

;; 2.0.0 - Audit columns on all tables
(patch/upgrade :myapp/database "2.0.0"
  (log/info "[DB] Adding audit columns to all entity tables...")
  (let [tables (schema/get-all-tables *db*)]
    (jdbc/with-transaction [tx (:datasource *db*)]
      (doseq [table tables]
        (jdbc/execute! tx [(format "ALTER TABLE %s ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT NOW()" table)])
        (jdbc/execute! tx [(format "ALTER TABLE %s ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP DEFAULT NOW()" table)])
        (jdbc/execute! tx [(format "ALTER TABLE %s ADD COLUMN IF NOT EXISTS created_by BIGINT REFERENCES users(id)" table)])

        ;; Add trigger for updated_at
        (jdbc/execute! tx [(format "
          CREATE OR REPLACE TRIGGER update_%s_timestamp
          BEFORE UPDATE ON %s
          FOR EACH ROW EXECUTE FUNCTION update_timestamp()" table table)])

        (log/infof "[DB] Added audit columns to: %s" table)))

    ;; Backfill existing records
    (log/info "[DB] Backfilling audit timestamps for existing records...")
    (doseq [table tables]
      (jdbc/execute! tx [(format "UPDATE %s SET created_at = NOW(), updated_at = NOW() WHERE created_at IS NULL" table)])))

  (log/info "[DB] Audit columns migration complete"))

;; Downgrade: rollback 2.0.0
(patch/downgrade :myapp/database "2.0.0"
  (log/info "[DB] Rolling back audit columns...")
  (let [tables (schema/get-all-tables *db*)]
    (doseq [table tables]
      (jdbc/execute! *db* [(format "DROP TRIGGER IF EXISTS update_%s_timestamp ON %s" table table)])
      (jdbc/execute! *db* [(format "ALTER TABLE %s DROP COLUMN IF EXISTS created_at" table)])
      (jdbc/execute! *db* [(format "ALTER TABLE %s DROP COLUMN IF EXISTS updated_at" table)])
      (jdbc/execute! *db* [(format "ALTER TABLE %s DROP COLUMN IF EXISTS created_by" table)])))
  (log/info "[DB] Audit columns rollback complete"))
```

And a cache module that depends on the database:

```clojure
(ns myapp.cache
  (:require
    [patcho.lifecycle :as lifecycle]
    [patcho.patch :as patch]
    [myapp.db :refer [*db*]]))

;; Patches for cache module (uses database connection)
(patch/current-version :myapp/cache "1.2.0")

(patch/upgrade :myapp/cache "1.0.0"
  (log/info "[CACHE] Creating cache tables...")
  (jdbc/execute! *db* ["CREATE TABLE IF NOT EXISTS cache_entries (...)"]))

(patch/upgrade :myapp/cache "1.2.0"
  (log/info "[CACHE] Adding expiration index...")
  (jdbc/execute! *db* ["CREATE INDEX idx_cache_expires ON cache_entries (expires_at)"]))

;; Lifecycle registration
(lifecycle/register-module! :myapp/cache
  {:depends-on [:myapp/database]  ; Database must be started first!

   :setup (fn []
            ;; One-time initialization (database already connected via dependency)
            (log/info "[CACHE] Setting up cache infrastructure...")
            (patch/level! :myapp/cache))  ; Apply patches - *db* is available

   :cleanup (fn []
              (log/info "[CACHE] Dropping cache tables...")
              (jdbc/execute! *db* ["DROP TABLE IF EXISTS cache_entries"]))

   :start (fn []
            (log/info "[CACHE] Starting cache service...")
            (start-eviction-thread!))

   :stop (fn []
           (log/info "[CACHE] Stopping cache service...")
           (stop-eviction-thread!))})
```

Starting the cache automatically starts the database first:

```clojure
(lifecycle/start! :myapp/cache)
;; 1. Runs setup for :myapp/database (if not done)
;; 2. Starts :myapp/database
;; 3. Runs setup for :myapp/cache (if not done)
;; 4. Starts :myapp/cache
```

---

## Version Awareness

### Exposing Versions

The `available-versions` function returns a map of all registered component versions:

```clojure
(patch/available-versions)
;; => {:myapp/database "2.0.0", :myapp/cache "1.2.0", :myapp/api "1.5.0"}

;; Query specific topics
(patch/available-versions :myapp/database :myapp/api)
;; => {:myapp/database "2.0.0", :myapp/api "1.5.0"}
```

### HTTP Endpoint

Expose versions to clients via an API endpoint:

```clojure
(GET "/api/versions" []
  {:status 200
   :body (patch/available-versions)})
```

Clients can use this to verify compatibility before making requests or to display version information in admin interfaces.

### Service-to-Service Compatibility

When services communicate, they can check versions to ensure compatibility:

```clojure
(defn compatible? [service-versions]
  (let [api-version (get service-versions :myapp/api)]
    (vrs/newer-or-equal? api-version "1.3.0")))

(let [versions (http/get "http://service/api/versions")]
  (when (compatible? versions)
    (http/post "http://service/api/data" {...})))
```

---

## Dependency Resolution

### Start is Recursive

Starting a module starts its entire dependency tree:

```clojure
;; Dependency graph:
;; :myapp/server
;; ├── :myapp/api
;; │   └── :myapp/database
;; └── :myapp/cache
;;     └── :myapp/database

(lifecycle/start! :myapp/server)
;; 1. Runs setup for each (if not done)
;; 2. Starts: :myapp/database → :myapp/api → :myapp/cache → :myapp/server
```

### Stop is Recursive (Dependents First)

Stopping a module stops all dependents first:

```clojure
(lifecycle/stop! :myapp/database)
;; Stops: :myapp/server → :myapp/api → :myapp/cache → :myapp/database
```

If you stop the foundation, everything built on it stops.

### Cleanup is Recursive (Dependents First)

Cleanup works the same way—dependents are cleaned before the module itself:

```clojure
(lifecycle/cleanup! :myapp/database)
;; Cleans: :myapp/server → :myapp/api → :myapp/cache → :myapp/database
```

### Surgical Control

For REPL development or targeted restarts, use non-recursive operations:

```clojure
(lifecycle/stop-only! :myapp/api)
;; Only stops :myapp/api — dependencies stay running

(lifecycle/restart! :myapp/cache)
;; stop-only + start — useful for REPL development
```

### Visualization

See what you're working with:

```clojure
(lifecycle/print-dependency-tree :myapp/server)
;; :myapp/server
;; ├── :myapp/api
;; │   └── :myapp/database
;; └── :myapp/cache
;;     └── :myapp/database

(lifecycle/print-system-report)
;; === System Report ===
;; Registered: 4 | Started: 2 | Stopped: 2
;;
;; [OK] Started:
;;   * :myapp/database
;;   * :myapp/cache -> [:myapp/database]
;;
;; [ ] Stopped:
;;   * :myapp/server -> [:myapp/api, :myapp/cache]
```
