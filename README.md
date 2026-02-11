# Patcho

_Component solved the runtime problem in 2013. Integrant is Component for the data-driven crowd. Patcho asks: **“what about the stuff that happens once?”**_

[![Clojars Project](https://img.shields.io/clojars/v/dev.gersak/patcho.svg)](https://clojars.org/dev.gersak/patcho)

---

## The Problem

Every serious application needs infrastructure that outlives restarts: databases, tables, Kafka topics, RabbitMQ exchanges, S3 buckets, folder structures. On first deploy, these get created. On subsequent deploys, they get evolved—new columns, new indexes, data migrations, new queues.

This logic is usually scattered:
- SQL migration files for the database
- Shell scripts for message queues
- Manual ops for cloud storage
- Custom code for everything else

And tracking what's been applied? A mix of migration tables, deploy notes, and tribal knowledge.

## The Solution

Patcho keeps all setup and migration logic in your Clojure code—version controlled, code reviewed, testable. It tracks what's been applied in persistent storage. On every boot, it checks the installed version, applies pending patches in order, and moves on.

```clojure
(patch/current-version :myapp/database "2.0.0")

(patch/upgrade :myapp/database "1.0.0"
  (create-tables! [:users :accounts :transactions]))

(patch/upgrade :myapp/database "1.5.0"
  (add-index! :transactions :created_at))

(patch/upgrade :myapp/database "2.0.0"
  (add-audit-columns! (all-tables)))

;; Inside lifecycle :start, after connecting
(patch/level! :myapp/database)
```

Works for databases. Works for Kafka. Works for S3. Works for anything you can write code for.

> [!IMPORTANT]
> **You must configure persistent storage.**
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

## No Arguments, No Problem

[How it works under the hood →](docs/internals.md)

Patches don't take arguments. This isn't a limitation—it's the point. Patches run once—there's nothing to inject.

Your database connection comes from `DATABASE_URL`. Your S3 bucket from config. Your Kafka bootstrap servers from environment variables. You're already wiring resources this way with Component, Integrant, Mount, or plain dynamic vars.

```clojure
(patch/upgrade :myapp/storage "1.0.0"
  (s3/create-bucket! (System/getenv "S3_BUCKET")))

(patch/upgrade :myapp/messaging "1.0.0"
  (kafka/create-topic! *kafka-admin* "events" {:partitions 12}))

(patch/upgrade :myapp/database "1.5.0"
  (jdbc/execute! *db* ["CREATE INDEX idx_users_email ON users(email)"]))
```

Patches read from the same environment your application reads from. The only tracked state is "what version is installed"—and that lives in persistent storage where it survives restarts.

---

## Setup Once, Start Every Boot

[Module lifecycle with dependencies →](#module-lifecycle)

Creating a database and connecting to it are different operations with different timing. Setup runs once ever—creating resources from environment. Start runs every boot—activating those resources. Patches run during start, after the resource is available.

```clojure
(lifecycle/register-module! :myapp/database
  {:setup   (fn [] (create-database! (System/getenv "DATABASE_URL")))
   :start   (fn []
              (connect!)
              (patch/level! :myapp/database))  ; patches run here
   :stop    (fn [] (disconnect!))
   :cleanup (fn [] (drop-database!))})
```

Lifecycle tracks what's been set up. Patches track what version is installed. Both persist across restarts.

---

## Know Your Version at Build Time

Your CI/CD pipeline needs to know what version you're building. Query it without starting the application:

```bash
# Get specific component version
clj -X:patcho version :topic :myapp/database :require myapp.database
# => 2.0.0

# Get all registered versions
clj -X:patcho versions :require myapp.core
# => {:myapp/database "2.0.0", :myapp/cache "1.2.0", :myapp/api "3.1.0"}

# Combine with your dev alias
clj -X:dev:patcho version :topic :my.app/dataset :require my.app.core
clj -X:dev:patcho versions :require my.app.core
```

The `:require` loads the namespace where `current-version` is declared. That's all that matters—patches don't need to load, just the version declaration.

Add the alias to your `deps.edn`:

```clojure
{:aliases
 {:patcho {:extra-deps {dev.gersak/patcho {:mvn/version "0.4.2"}}
           :ns-default patcho.cli}}}
```

---

## Know Your Version at Runtime

Service clients need to know what version is running on the other end. Expose it:

```clojure
(GET "/api/versions" []
  {:status 200
   :body (patch/available-versions)})
;; => {:myapp/database "2.0.0", :myapp/cache "1.2.0"}
```

Check compatibility before making requests:

```clojure
(let [versions (http/get "http://service/api/versions")]
  (when (compatible? (:myapp/api versions))
    (call-the-api!)))
```

---

## Persistent Storage

Without persistent storage, every boot looks like first boot. Patches rerun. Setup repeats. Chaos.

Patcho needs to remember two things:
- **Installed versions** — which patches have been applied
- **Lifecycle state** — which modules have completed one-time setup

For development, use file-based stores:

```clojure
(patch/set-store! (patch/->FileVersionStore ".versions"))
(lifecycle/set-store! (lifecycle/->FileLifecycleStore ".lifecycle"))
```

For production, implement the protocols on your database. State auto-migrates when you switch stores—start with atoms during bootstrap, switch to database after connecting.

[Database-backed stores →](#production-database-backed-stores)

---

## Module Lifecycle

Real applications have dependency graphs. Config loads first. Transit serialization initializes. Then database and storage connect. Finally, the server starts.

```
:myapp/server
├── :myapp/database
│   ├── :myapp/config
│   └── :myapp/transit
└── :myapp/storage
    └── :myapp/config
```

Patcho's lifecycle system handles this:

```clojure
;; Foundation modules - no dependencies
(lifecycle/register-module! :myapp/config
  {:start (fn [] (load-config!))
   :stop  (fn [] nil)})

(lifecycle/register-module! :myapp/transit
  {:start (fn [] (init-transit-handlers!))
   :stop  (fn [] nil)})

;; Middle tier - depends on foundation
(lifecycle/register-module! :myapp/database
  {:depends-on [:myapp/config :myapp/transit]
   :setup (fn [] (create-database!))
   :start (fn []
            (connect-db!)
            (patch/level! :myapp/database))
   :stop  (fn [] (disconnect-db!))})

(lifecycle/register-module! :myapp/storage
  {:depends-on [:myapp/config]
   :setup (fn [] (create-s3-bucket!))
   :start (fn []
            (init-s3-client!)
            (patch/level! :myapp/storage))
   :stop  (fn [] (close-s3-client!))})

;; Top level - depends on middle tier
(lifecycle/register-module! :myapp/server
  {:depends-on [:myapp/database :myapp/storage]
   :start (fn [] (start-http-server!))
   :stop  (fn [] (stop-http-server!))})
```

### Dependency Resolution

Starting a module starts its dependencies first:

```clojure
(lifecycle/start! :myapp/server)
;; Starts: config → transit → database → storage → server
```

Stopping a module stops its dependents first:

```clojure
(lifecycle/stop! :myapp/database)
;; Stops: server → database (storage stays running - different branch)
```

Visualize what you're working with:

```clojure
(lifecycle/print-dependency-tree :myapp/server)
;; :myapp/server
;; ├── :myapp/database
;; │   ├── :myapp/config
;; │   └── :myapp/transit
;; └── :myapp/storage
;;     └── :myapp/config

(lifecycle/print-system-report)
;; === System Report ===
;; Registered: 5 | Started: 5 | Stopped: 0
;;
;; [OK] Started:
;;   * :myapp/config
;;   * :myapp/transit
;;   * :myapp/database -> [:myapp/config, :myapp/transit]
;;   * :myapp/storage -> [:myapp/config]
;;   * :myapp/server -> [:myapp/database, :myapp/storage]
```

---

## Complete Example

A database module with schema evolution, and a cache module that depends on it.

### Database Module

```clojure
(ns myapp.database
  (:require
    [next.jdbc :as jdbc]
    [patcho.patch :as patch]
    [patcho.lifecycle :as lifecycle]))

(def ^:dynamic *db* nil)

;; Version declaration
(patch/current-version :myapp/database "2.0.0")

;; Patches live with the module
(patch/upgrade :myapp/database "1.0.0"
  (jdbc/execute! *db* ["CREATE TABLE users (
                          id SERIAL PRIMARY KEY,
                          email TEXT UNIQUE NOT NULL,
                          name TEXT)"]))

(patch/upgrade :myapp/database "1.5.0"
  (jdbc/execute! *db* ["CREATE INDEX idx_users_email ON users(email)"]))

(patch/upgrade :myapp/database "2.0.0"
  (jdbc/execute! *db* ["ALTER TABLE users ADD COLUMN created_at TIMESTAMP DEFAULT NOW()"]))

(patch/downgrade :myapp/database "2.0.0"
  (jdbc/execute! *db* ["ALTER TABLE users DROP COLUMN created_at"]))

;; Lifecycle
(lifecycle/register-module! :myapp/database
  {:setup (fn []
            (let [url (System/getenv "DATABASE_URL")]
              (create-database-if-not-exists! url)))

   :start (fn []
            (let [url (System/getenv "DATABASE_URL")]
              (alter-var-root #'*db* (constantly (jdbc/get-datasource url)))
              (patch/level! :myapp/database)))

   :stop (fn []
           (alter-var-root #'*db* (constantly nil)))

   :cleanup (fn []
              (drop-database! (System/getenv "DATABASE_URL")))})
```

### Cache Module

```clojure
(ns myapp.cache
  (:require
    [next.jdbc :as jdbc]
    [patcho.patch :as patch]
    [patcho.lifecycle :as lifecycle]
    [myapp.database :refer [*db*]]))

(patch/current-version :myapp/cache "1.1.0")

(patch/upgrade :myapp/cache "1.0.0"
  (jdbc/execute! *db* ["CREATE TABLE cache_entries (
                          key TEXT PRIMARY KEY,
                          value BYTEA,
                          expires_at TIMESTAMP)"]))

(patch/upgrade :myapp/cache "1.1.0"
  (jdbc/execute! *db* ["CREATE INDEX idx_cache_expires ON cache_entries(expires_at)"]))

(lifecycle/register-module! :myapp/cache
  {:depends-on [:myapp/database]  ; database starts first

   :start (fn []
            (patch/level! :myapp/cache)
            (start-eviction-thread!))

   :stop (fn []
           (stop-eviction-thread!))

   :cleanup (fn []
              (jdbc/execute! *db* ["DROP TABLE IF EXISTS cache_entries"]))})
```

### Application Entry Point

```clojure
(ns myapp.core
  (:require
    [patcho.patch :as patch]
    [patcho.lifecycle :as lifecycle]
    myapp.database
    myapp.cache
    myapp.api))

(defn -main [& args]
  ;; Configure persistent storage
  (patch/set-store! (patch/->FileVersionStore ".versions"))
  (lifecycle/set-store! (lifecycle/->FileLifecycleStore ".lifecycle"))

  ;; Start the system
  ;; → Resolves dependencies
  ;; → Runs setup for each (if not done)
  ;; → Starts each (runs patches via level!)
  (lifecycle/start! :myapp/api))
```

### What Happens on Boot

**First deploy:**
1. `setup` runs for database → creates the database
2. `start` runs for database → connects, runs patches 1.0.0 → 1.5.0 → 2.0.0
3. `setup` runs for cache → (none defined, skipped)
4. `start` runs for cache → runs patches 1.0.0 → 1.1.0, starts eviction
5. API starts

**Subsequent boots (same version):**
1. `setup` skipped (already done)
2. `start` runs for database → connects, `level!` finds 2.0.0 = 2.0.0, no patches
3. `start` runs for cache → `level!` finds 1.1.0 = 1.1.0, no patches
4. API starts

**Deploy with new cache version (1.2.0):**
1. `setup` skipped
2. Database starts, no patches needed
3. Cache starts, `level!` runs 1.2.0 patch
4. API starts

---

## Production: Database-Backed Stores

For production, implement `VersionStore` and `LifecycleStore` protocols on your database type:

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

Both systems handle this the same way:
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
