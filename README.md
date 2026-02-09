# Patcho

A Clojure library for version migrations and module lifecycle management.

[![Clojars Project](https://img.shields.io/clojars/v/dev.gersak/patcho.svg)](https://clojars.org/dev.gersak/patcho)

## Why Patcho?

Patcho addresses two concerns that existing solutions handle incompletely:

### Version Migrations

Database migration tools abound, but what about migrating your *application* state? When your system evolves—new triggers, converted column types, backfilled data, recalculated caches—you need reliable version transitions.

Patcho patches are **stateless transitions**. They don't receive arguments. They reach into whatever context they need: database connections, deployed schemas, configuration. This isn't a limitation—it's the point.

```clojure
;; Patches access context themselves
(patch/upgrade :myapp/audit "1.0.1"
  (let [db *db*                           ; Dynamic var
        model (deployed-model)            ; Function call
        entities (get-entities model)]    ; Whatever you need
    (doseq [entity entities]
      (add-audit-columns! db entity))))
```

The patch defines *what* happens at version `1.0.1`. How to get the database connection, the model, the entities—that's your concern, not Patcho's. This forces clean separation and makes patches self-contained.

### Module Lifecycle

[Component](https://github.com/stuartsierra/component) and [Integrant](https://github.com/weavejester/integrant) are excellent libraries for managing runtime dependencies. But they focus on **start/stop** cycles—what happens every time your application boots.

What about **one-time operations**?

- Creating database tables
- Installing triggers
- Initializing schema
- Setting up external resources

These aren't "start" operations—you don't want them running every boot. And stopping your app doesn't mean dropping tables.

Patcho separates these concerns:

| | Patches | Lifecycle |
|---|---|---|
| **Purpose** | Version transitions | Runtime management |
| **When** | Once per version | Every boot |
| **Examples** | Create tables, add triggers, migrate data | Open connections, spawn threads |
| **Persistence** | Version tracked in store | Started state in-memory |

```clojure
;; Patches define what happens at each version
(patch/upgrade :myapp/cache "1.0.0" (create-cache-tables!))
(patch/upgrade :myapp/cache "1.1.0" (add-cache-indexes!))

;; Lifecycle manages runtime - level! applies pending patches
(lifecycle/register-module! :myapp/cache
  {:depends-on [:myapp/database]
   :start (fn []
            (patch/level! :myapp/cache)     ; Bring to current version
            (connect-to-cache!))
   :stop  (fn [] (disconnect!))})
```

## Installation

```clojure
{:deps {dev.gersak/patcho {:mvn/version "0.3.0"}}}
```

---

## Version Patching

### The Basics

```clojure
(ns myapp.database.patch
  (:require
    [patcho.patch :as patch]
    [myapp.db :refer [*db*]]))

;; What version should the system be at?
(patch/current-version :myapp/database "2.0.0")

;; What version is actually installed?
(patch/installed-version :myapp/database
  (patch/read-version *db* :myapp/database))

;; Define what happens at each version boundary
(patch/upgrade :myapp/database "1.0.0"
  (println "Creating initial schema")
  (create-tables! *db*))

(patch/upgrade :myapp/database "1.5.0"
  (println "Adding indexes")
  (add-indexes! *db*))

(patch/upgrade :myapp/database "2.0.0"
  (println "Converting integer columns to bigint")
  (convert-int-to-bigint! *db*))

;; Downgrade patches (optional - for rollback)
(patch/downgrade :myapp/database "2.0.0"
  (println "Reverting bigint conversion")
  (convert-bigint-to-int! *db*))
```

### Applying Patches

```clojure
;; Bring system to current version (from installed version)
(patch/level! :myapp/database)
;; If installed is 1.0.0, runs: 1.5.0, 2.0.0

;; Or explicit version range
(patch/apply :myapp/database "1.0.0" "2.0.0")
;; Runs: 1.5.0, 2.0.0

;; Downgrade
(patch/apply :myapp/database "2.0.0" "1.5.0")
;; Runs: 2.0.0 downgrade
```

### Version Persistence

Patcho **must** track what version is installed. Without this, it can't know which patches to apply.

```clojure
;; Set a persistent store
(patch/set-store! (patch/->FileVersionStore "versions.edn"))

;; Or use your database (implement VersionStore protocol)
(patch/set-store! *db*)

;; After patches run, the new version is automatically persisted
(patch/level! :myapp/database)
;; → Runs patches
;; → Writes new version to store
```

Built-in stores:
- `FileVersionStore` - EDN file
- `AtomVersionStore` - In-memory (testing only)

For database-backed stores, see [Extending Stores](docs/stores.md).

### Real-World Example

From a production system—patches that fix column types and add immutability triggers:

```clojure
(ns myapp.dataset.patch
  (:require
    [patcho.patch :as patch]
    [myapp.db :refer [*db*]]
    [myapp.dataset :as dataset]))

(patch/current-version :myapp/dataset "1.0.1")
(patch/installed-version :myapp/dataset
  (patch/read-version *db* :myapp/dataset))

;; Patch reaches into context: *db*, dataset/deployed-model, etc.
(patch/upgrade :myapp/dataset "0.5.0"
  (when (postgres? *db*)
    (log/info "Fixing integer types")
    (doseq [entity (get-entities (dataset/deployed-model))]
      (convert-int-columns! *db* entity))

    (log/info "Removing NOT NULL constraints")
    (doseq [entity (get-entities (dataset/deployed-model))]
      (drop-mandatory-constraints! *db* entity))))

(patch/upgrade :myapp/dataset "1.0.0"
  (log/info "Dataset initialized at v1.0.0"))

(patch/upgrade :myapp/dataset "1.0.1"
  (when (postgres? *db*)
    (log/info "Installing ID immutability triggers")
    (doseq [table (get-entity-tables)]
      (create-trigger! *db* table))))

;; Downgrade undoes the trigger installation
(patch/downgrade :myapp/dataset "1.0.1"
  (when (postgres? *db*)
    (log/info "Removing ID immutability triggers")
    (doseq [table (get-entity-tables)]
      (drop-trigger! *db* table))))
```

Key observations:
- Patches access `*db*` directly—no arguments passed
- Conditional logic (`when (postgres? *db*)`) for database-specific patches
- Each patch is self-contained and idempotent
- Version determines *when*, implementation determines *how*

### Exposing Versions to Clients

Frontend apps and API clients often need to know what version they're talking to. Patcho makes this trivial:

```clojure
;; Get all module versions
(patch/available-versions)
;; => {:myapp/database "2.0.0"
;;     :myapp/api "1.5.0"
;;     :myapp/auth "1.0.0"}

;; Or specific modules
(patch/available-versions :myapp/api :myapp/auth)
;; => {:myapp/api "1.5.0"
;;     :myapp/auth "1.0.0"}
```

Expose via HTTP endpoint:

```clojure
(defn version-handler [_]
  {:status 200
   :body (patch/available-versions)})

;; GET /api/versions
;; => {"myapp/database": "2.0.0", "myapp/api": "1.5.0", ...}
```

This is valuable for:
- Client compatibility checks
- Debugging production issues
- Feature detection
- Deployment verification

---

## Lifecycle Management

### The Basics

```clojure
(ns myapp.cache
  (:require
    [patcho.lifecycle :as lifecycle]
    [patcho.patch :as patch]))

(patch/current-version :myapp/cache "1.2.0")
(patch/installed-version :myapp/cache (patch/read-version *db* :myapp/cache))

(patch/upgrade :myapp/cache "1.0.0" (create-cache-tables!))
(patch/upgrade :myapp/cache "1.2.0" (add-cache-indexes!))

(lifecycle/register-module! :myapp/cache
  {:depends-on [:myapp/database]

   ;; Runtime start - level! applies any pending patches
   :start (fn []
            (patch/level! :myapp/cache)    ; Apply patches if needed
            (connect-to-cache!)
            (start-eviction-thread!))

   ;; Runtime stop
   :stop (fn []
           (stop-eviction-thread!)
           (disconnect!))})
```

Notice how `level!` fits naturally into `:start`. Each module brings itself to the correct version when it starts. Patches handle schema evolution; lifecycle handles runtime state.

### Starting Modules

```clojure
;; Start a module (dependencies start automatically)
(lifecycle/start! :myapp/api)
;; → Starts: :myapp/database → :myapp/cache → :myapp/api
;; → Each module's :start runs level! to apply pending patches
```

### Stopping Modules

```clojure
;; Stop is NOT recursive (only stops this module)
(lifecycle/stop! :myapp/api)
;; Dependencies stay running—they might be shared

;; Restart for development
(lifecycle/restart! :myapp/cache)
```

### Visualization

```clojure
(lifecycle/print-dependency-tree :myapp/api)
;; :myapp/api
;; ├── :myapp/cache
;; │   └── :myapp/database
;; └── :myapp/auth
;;     └── :myapp/database

(lifecycle/print-system-report)
;; === System Report ===
;; Registered: 4 | Started: 2 | Stopped: 2
;;
;; [OK] Started modules:
;;   * :myapp/database
;;   * :myapp/cache -> [:myapp/database]
;;
;; [ ] Stopped modules:
;;   * :myapp/api -> [:myapp/cache, :myapp/auth]
```

### Bootstrap Pattern

When your database is itself a lifecycle module and you want to store versions in that database:

```clojure
;; 1. Start with file-based store (or in-memory for testing)
(patch/set-store! (patch/->FileVersionStore ".versions"))

;; 2. Start database module (level! runs, creates tables)
(lifecycle/start! :myapp/database)

;; 3. Switch to database store for subsequent modules
(patch/set-store! *db*)

;; 4. Continue with other modules (versions now tracked in database)
(lifecycle/start! :myapp/api)
```

---

## Documentation

- [Lifecycle Management](docs/lifecycle.md) - Complete lifecycle API reference
- [Extending Stores](docs/stores.md) - Database-backed persistence (Postgres, SQLite examples)

## Philosophy

**Patches are version transitions, not data pipelines.** They don't receive arguments because migration logic shouldn't depend on caller-provided data. Resources come from the environment—dynamic vars, function calls, protocol implementations. This makes patches reproducible and self-documenting.

**Level fits into start.** Each module brings itself to the correct version when it starts. Patches handle schema evolution; lifecycle handles runtime state. They compose naturally.

**Persistence is required.** Patching depends on knowing what version is installed. Without persistent storage, you can't know which patches to apply. File-based stores work for simple cases; production systems use database-backed stores.
