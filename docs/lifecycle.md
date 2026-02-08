# Lifecycle Management

The `patcho.lifecycle` namespace provides runtime lifecycle management for modules with dependency resolution.

## Overview

While `patcho.patch` handles **version migrations** (data/schema state transitions), `patcho.lifecycle` handles **runtime management** (starting/stopping services with dependencies).

They work together:
1. Apply patches to reach target version (`patch/level!`)
2. Start runtime services (`lifecycle/start!`)

## Quick Start

```clojure
(ns myapp.database
  (:require [patcho.lifecycle :as lifecycle]))

;; Register module at namespace load time
(lifecycle/register-module! :myapp/database
  {:start (fn [] (connect-to-db!))
   :stop (fn [] (disconnect!))})
```

```clojure
(ns myapp.cache
  (:require [patcho.lifecycle :as lifecycle]))

(lifecycle/register-module! :myapp/cache
  {:depends-on [:myapp/database]
   :setup (fn [] (create-cache-tables!))
   :cleanup (fn [] (drop-cache-tables!))
   :start (fn [] (start-cache-service!))
   :stop (fn [] (stop-cache-service!))})
```

```clojure
(ns myapp.core
  (:require
    myapp.database
    myapp.cache
    [patcho.lifecycle :as lifecycle]))

;; Start with dependencies - database starts first automatically
(lifecycle/start! :myapp/cache)

;; Later
(lifecycle/stop! :myapp/cache)
```

## Core Concepts

### Setup vs Start

| Aspect | Setup | Start |
|--------|-------|-------|
| **Purpose** | One-time initialization | Runtime activation |
| **Examples** | Create tables, initialize schema | Open connections, start threads |
| **Frequency** | Once per deployment | Every process start |
| **Persistence** | Tracked via LifecycleStore | In-memory only |
| **Idempotent** | Yes (skips if already done) | Yes (skips if already running) |

### Dependency Resolution

Dependencies are declared explicitly and resolved automatically:

```clojure
(lifecycle/register-module! :myapp/api
  {:depends-on [:myapp/cache :myapp/auth]
   :start (fn [] ...)
   :stop (fn [] ...)})

;; Starting :myapp/api automatically starts :myapp/cache and :myapp/auth first
(lifecycle/start! :myapp/api)
```

### Operation Behavior

| Operation | Recursive? | Description |
|-----------|------------|-------------|
| `setup!` | Yes | Runs setup for module and all dependencies |
| `start!` | Yes | Starts module and all dependencies (runs setup if needed) |
| `stop!` | No | Only stops this module |
| `cleanup!` | Yes* | Cleans up module and all dependents |
| `restart!` | No | Stops then starts this module |

*`cleanup!` is recursive in the opposite direction - it cleans up modules that depend on this one first.

## API Reference

### Registration

#### `register-module!`

```clojure
(register-module! topic spec)
```

Register a module with its lifecycle functions.

**Parameters:**
- `topic` - Keyword identifying the module (e.g., `:myapp/database`)
- `spec` - Map with keys:
  - `:depends-on` - Vector of topic keywords this module depends on
  - `:setup` - (Optional) Zero-arg fn for one-time setup
  - `:cleanup` - (Optional) Zero-arg fn for one-time cleanup
  - `:start` - Zero-arg fn to start runtime services
  - `:stop` - Zero-arg fn to stop runtime services

```clojure
(lifecycle/register-module! :myapp/cache
  {:depends-on [:myapp/database]
   :setup (fn [] (create-cache-tables!))
   :cleanup (fn [] (drop-cache-tables!))
   :start (fn [] (start-cache!))
   :stop (fn [] (stop-cache!))})
```

### Lifecycle Operations

#### `start!`

```clojure
(start! topic)
(start! topic & more-topics)
```

Start a module recursively (starts all dependencies first).

- Idempotent - won't start if already started
- Auto-runs setup if module has a setup function and it hasn't been completed
- Records errors in `module-errors` atom if startup fails

```clojure
(lifecycle/start! :myapp/api)
;; → Starts: :myapp/database → :myapp/cache → :myapp/api
```

#### `stop!`

```clojure
(stop! topic)
```

Stop a module (NOT recursive - only stops this module).

```clojure
(lifecycle/stop! :myapp/api)
;; Only stops :myapp/api, leaves dependencies running
```

#### `setup!`

```clojure
(setup! & topics)
```

Run one-time setup for modules recursively.

- Idempotent - tracks completion via LifecycleStore
- Starts dependencies first (setup often needs them running)
- Requires a LifecycleStore to be configured

```clojure
(lifecycle/set-store! (lifecycle/->FileLifecycleStore ".lifecycle"))
(lifecycle/setup! :myapp/cache)
```

#### `cleanup!`

```clojure
(cleanup! topic)
```

Run cleanup for a module and all its dependents.

Cleanup order:
1. First cleanup all dependents (modules that depend on this one)
2. Then cleanup this module

```clojure
(lifecycle/cleanup! :myapp/database)
;; → Cleans: :myapp/api → :myapp/cache → :myapp/database
```

#### `restart!`

```clojure
(restart! topic)
```

Stop then start a module. Useful for REPL development.

```clojure
(lifecycle/restart! :myapp/cache)
```

### State Inspection

#### `registered-modules`

```clojure
(registered-modules) ; => [:myapp/database :myapp/cache :myapp/api]
```

#### `started-modules`

```clojure
(started-modules) ; => [:myapp/database :myapp/cache]
```

#### `started?`

```clojure
(started? :myapp/cache) ; => true
```

#### `module-info`

```clojure
(module-info :myapp/cache)
;; => {:depends-on [:myapp/database]
;;     :started? true
;;     :has-setup? true
;;     :has-cleanup? true
;;     :setup-complete? true
;;     :cleanup-complete? false}
```

### Store Management

#### `set-store!`

```clojure
(set-store! store)
```

Set the default LifecycleStore globally. Automatically migrates state from the previous store.

```clojure
(lifecycle/set-store! (lifecycle/->FileLifecycleStore ".lifecycle"))
```

#### `reset-store!`

```clojure
(reset-store!)
```

Reset to a fresh in-memory AtomLifecycleStore. Does NOT migrate state.

#### `with-store`

```clojure
(with-store store & body)
```

Execute body with a specific store bound temporarily.

```clojure
(lifecycle/with-store test-store
  (lifecycle/setup! :myapp/test-module))
```

### Visualization

#### `print-dependency-tree`

```clojure
(print-dependency-tree)        ; All root modules
(print-dependency-tree topic)  ; Specific module
```

```
:myapp/api
├── :myapp/cache
│   └── :myapp/database
└── :myapp/auth
    └── :myapp/database
```

#### `print-topology-layers`

```clojure
(print-topology-layers)
```

```
Layer 0 (foundation):
  :myapp/database

Layer 1:
  :myapp/cache → [:myapp/database]
  :myapp/auth → [:myapp/database]

Layer 2 (top-level):
  :myapp/api → [:myapp/cache, :myapp/auth]
```

#### `print-system-report`

```clojure
(print-system-report)
```

```
=== System Report ===
Registered: 4 | Started: 2 | Stopped: 2

[OK] Started modules:
  * :myapp/database
  * :myapp/cache -> [:myapp/database]

[ ] Stopped modules (ready to start):
  * :myapp/api -> [:myapp/cache, :myapp/auth]

[!] Modules with errors:
  * :myapp/auth -> [:myapp/database]
    ERROR at 2026-01-07 18:23:45
    Message: Connection refused
```

### Error Handling

#### `system-report`

Returns a data structure with complete system status:

```clojure
(system-report)
;; => {:registered 4
;;     :started 2
;;     :stopped 2
;;     :modules {:myapp/database {:status :started
;;                                :depends-on []
;;                                :dependents [:myapp/cache]}
;;               :myapp/auth {:status :stopped
;;                            :error {:message "Connection refused"
;;                                    :timestamp #inst "..."
;;                                    :exception #<Exception>}}}}
```

#### `clear-errors!`

```clojure
(clear-errors!)
```

Clear all recorded module errors. Useful before retrying startup.

### Testing Utilities

#### `reset-registry!`

```clojure
(reset-registry!)
```

Clear all registered modules. For testing only - stop modules first.

## Common Patterns

### Application Bootstrap

```clojure
(ns myapp.main
  (:require
    ;; Load modules (registers them)
    myapp.database
    myapp.cache
    myapp.api
    [patcho.lifecycle :as lifecycle]))

(defn -main []
  ;; Set persistent store
  (lifecycle/set-store! (lifecycle/->FileLifecycleStore ".lifecycle"))

  ;; Start top-level module (dependencies start automatically)
  (lifecycle/start! :myapp/api)

  ;; Add shutdown hook
  (.addShutdownHook
    (Runtime/getRuntime)
    (Thread. #(lifecycle/stop! :myapp/api))))
```

### REPL Development

```clojure
;; Reload and restart a module
(require 'myapp.cache :reload)
(lifecycle/restart! :myapp/cache)

;; Check what's running
(lifecycle/print-system-report)

;; If something went wrong
(lifecycle/clear-errors!)
(lifecycle/start! :myapp/cache)
```

### Database Bootstrap Pattern

When your database is itself a lifecycle module:

```clojure
;; 1. Start with in-memory store
(lifecycle/set-store! (lifecycle/->AtomLifecycleStore (atom {})))

;; 2. Setup and start database
(lifecycle/start! :myapp/database)

;; 3. Switch to database-backed store (state auto-migrates)
(lifecycle/set-store! db/*db*)

;; 4. Continue with other modules
(lifecycle/start! :myapp/api)
```

### Test Fixtures

```clojure
(use-fixtures :each
  (fn [f]
    (lifecycle/reset-registry!)
    (lifecycle/reset-store!)
    (f)
    (lifecycle/reset-registry!)
    (lifecycle/reset-store!)))
```
