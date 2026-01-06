# Patcho

A simple, elegant Clojure library for managing version migrations and patches.


[![Clojars Project](https://img.shields.io/clojars/v/dev.gersak/patcho.svg)](https://clojars.org/dev.gersak/patcho)

## Overview

Patcho provides a declarative way to define version upgrades and downgrades for your application or modules. It automatically determines which patches need to be applied to migrate from one version to another, executing them in the correct order.

## Features

- **Bidirectional migrations**: Define both upgrade and downgrade paths
- **Automatic patch sequencing**: Patches are applied in the correct order based on version comparison
- **Topic-based organization**: Group related patches by topic/module
- **Simple declarative API**: Use macros to define versions and migrations
- **Version comparison**: Built on version-clj for reliable semantic versioning

## Installation

Add to your `deps.edn`:

```clojure
{:deps {patcho/patcho {:local/root "path/to/patcho"}}}
```

## Usage

### Basic Example

```clojure
(require '[patcho.patch :as patch])

;; Define the current version of your application
(patch/current-version ::my-app "2.0.0")

;; Define upgrade patches
(patch/upgrade ::my-app "1.0.0"
  (println "Setting up initial database schema"))

(patch/upgrade ::my-app "1.5.0"
  (println "Adding user preferences table"))

(patch/upgrade ::my-app "2.0.0"
  (println "Migrating to new authentication system"))

;; Define downgrade patches (optional)
(patch/downgrade ::my-app "1.5.0"
  (println "Removing authentication system")
  (println "Restoring legacy auth"))

;; Apply patches to upgrade from version 1.0.0 to current
(patch/apply ::my-app "1.0.0")
;; Will execute: 1.5.0 and 2.0.0 upgrades

;; Apply patches to upgrade from nothing (nil) to current
(patch/apply ::my-app nil)
;; Will execute: 1.0.0, 1.5.0, and 2.0.0 upgrades
```

### Installed Version Tracking

Patcho can track what version is currently installed separately from the target version:

```clojure
;; Define target version (what the system should be)
(patch/current-version ::my-app "2.0.0")

;; Define installed version (what's actually deployed)
(patch/installed-version ::my-app "1.5.0")

;; Automatically migrate from installed to current version
(patch/apply ::my-app)
;; Migrates from 1.5.0 to 2.0.0

;; Check both versions
(patch/version ::my-app)          ;; => "2.0.0" (target)
(patch/deployed-version ::my-app) ;; => "1.5.0" (installed)
```

### Automatic Version Persistence

Patcho provides a `VersionStore` protocol for automatically persisting version state across application restarts. After patches are applied successfully, the new version is automatically written to the store.

#### Using a Default Store for All Topics

The simplest approach - use one store for everything:

```clojure
(require '[patcho.patch :as patch])

;; Create a file-based version store
(def store (patch/->FileVersionStore "versions.edn"))

;; Set as default for ALL topics
(patch/set-default-store! store)

;; Define multiple topics - all will use the same store automatically
(patch/current-version ::my-app "2.0.0")
(patch/current-version ::my-db "3.0.0")
(patch/current-version ::my-api "1.5.0")

(patch/installed-version ::my-app (patch/read-version store ::my-app))
(patch/installed-version ::my-db (patch/read-version store ::my-db))
(patch/installed-version ::my-api (patch/read-version store ::my-api))

;; Define patches for each topic
(patch/upgrade ::my-app "2.0.0" (println "App upgraded"))
(patch/upgrade ::my-db "3.0.0" (println "DB upgraded"))
(patch/upgrade ::my-api "1.5.0" (println "API upgraded"))

;; Apply all - they all write to the same versions.edn file
(patch/apply ::my-app)
(patch/apply ::my-db)
(patch/apply ::my-api)
```

#### Using Per-Topic Stores

For more control, register different stores for different topics:

```clojure
(require '[patcho.patch :as patch])

;; Different stores for different topics
(def app-store (patch/->FileVersionStore "app-versions.edn"))
(def db-store (patch/->FileVersionStore "db-versions.edn"))

;; Register per topic
(patch/set-store! ::my-app app-store)
(patch/set-store! ::my-db db-store)

;; Define versions
(patch/current-version ::my-app "2.0.0")
(patch/installed-version ::my-app (patch/read-version app-store ::my-app))

;; Apply patches - each writes to its own file
(patch/apply ::my-app)  ; Writes to app-versions.edn
```

#### Using In-Memory Storage (for testing)

```clojure
;; Create an atom-based store (not persisted)
(def test-store (patch/->AtomVersionStore (atom {})))

(patch/set-store! ::test-app test-store)
(patch/current-version ::test-app "1.0.0")
(patch/installed-version ::test-app (patch/read-version test-store ::test-app))

(patch/apply ::test-app)
;; Version is stored in the atom but not persisted to disk
```

#### Scoped Stores

Use `with-store` to temporarily override the registered store:

```clojure
(def global-store (patch/->FileVersionStore "versions.edn"))
(def test-store (patch/->AtomVersionStore (atom {})))

(patch/set-store! ::my-app global-store)

;; This will use test-store instead of global-store
(patch/with-store test-store
  (patch/apply ::my-app))
```

#### Custom Storage Implementations

Implement the `VersionStore` protocol for custom storage backends:

```clojure
(require '[patcho.patch :as patch])

(deftype DatabaseVersionStore [db-conn]
  patch/VersionStore
  (read-version [_ topic]
    (or (query-version-from-db db-conn topic) "0"))
  (write-version [_ topic version]
    (save-version-to-db db-conn topic version)))

(def db-store (->DatabaseVersionStore my-db-connection))
(patch/set-store! ::my-app db-store)
```

### Multiple Topics

You can manage different components independently:

```clojure
;; Database migrations
(patch/current-version ::database "3.1.0")
(patch/upgrade ::database "3.0.0" 
  (migrate-schema-v3))
(patch/upgrade ::database "3.1.0" 
  (add-indexes))

;; API versions
(patch/current-version ::api "2.0.0")
(patch/upgrade ::api "2.0.0" 
  (update-endpoints))

;; Apply patches for specific topics
(patch/apply ::database "2.0.0")
(patch/apply ::api "1.0.0")

;; Check available versions
(patch/available-versions)
;; => {::database "3.1.0", ::api "2.0.0"}
```

## API Reference

### Protocols

#### `VersionStore`
Protocol for persisting version state across application restarts.

**Methods:**
- `(read-version store topic)` - Read the currently installed version for a topic. Returns version string or "0" if not found.
- `(write-version store topic version)` - Persist the installed version for a topic. Called automatically by `apply` after successful migration.

**Built-in implementations:**
- `FileVersionStore` - Persists versions to an EDN file
- `AtomVersionStore` - Stores versions in an atom (in-memory only, for testing)

### Macros

#### `current-version`
```clojure
(current-version topic version-string)
```
Defines the current/target version for a topic.

#### `installed-version`
```clojure
(installed-version topic version-expr)
```
Defines the currently installed/deployed version for a topic. Used by the 1-arity `apply` function. Can be a static string or an expression that reads from a VersionStore.

#### `upgrade`
```clojure
(upgrade topic version & body)
```
Defines code to execute when upgrading TO the specified version.

#### `downgrade`
```clojure
(downgrade topic version & body)
```
Defines code to execute when downgrading FROM the specified version.

#### `with-store`
```clojure
(with-store store & body)
```
Execute body with a specific VersionStore bound to `*version-store*`. Overrides any globally registered stores within the scope.

### Functions

#### `apply`
```clojure
(apply topic)
(apply topic current-version)
(apply topic current-version target-version)
```
Applies necessary patches to migrate between versions:

- **1-arity**: Migrates from installed version to current version
- **2-arity**: Migrates from `current-version` to the topic's current version
- **3-arity**: Migrates from `current-version` to `target-version`

If `current-version` is nil or "0", starts from the beginning. Automatically determines upgrade vs downgrade direction and executes patches in correct order.

**After successful migration**, if a VersionStore is registered for the topic (via `set-store!` or `*version-store*`), the new version is automatically persisted.

Returns the target version if patches were applied, nil otherwise.

#### `set-store!`
```clojure
(set-store! topic store)
```
Register a VersionStore for a specific topic. This store will be used by `apply` to persist version changes for that topic.

#### `set-default-store!`
```clojure
(set-default-store! store)
```
Set a default VersionStore for ALL topics. This is the simplest approach - one store tracks versions for all your topics. The store will be used for any topic that doesn't have a specific store registered via `set-store!`.

#### `available-versions`
```clojure
(available-versions)
(available-versions topic1 topic2 ...)
```
Returns a map of topics to their current versions.

#### `registered-topics`
```clojure
(registered-topics)
```
Returns a set of all registered topics (components with `current-version` defined).

#### `level!`
```clojure
(level! topic)
```
Apply all pending patches for a component with standardized logging.

This is a convenience wrapper around `apply` that:
- Reads the installed version from the registered VersionStore
- Applies all pending patches up to current-version
- Automatically persists the new version
- Provides consistent logging across all components
- Handles missing `installed-version` gracefully (falls back to store or "0")

**Returns**: The target version if patches were applied, nil if already at target.

**Example**:
```clojure
;; Instead of writing custom level-X! functions for each component:
(defn level-iam! []
  (log/info "[IAM] Leveling...")
  (patch/apply :synticity/iam)
  (log/info "Done"))

;; Just use the generic level! function:
(patch/level! :synticity/iam)
;; [iam] Leveling component from 0.9.0 to 1.0.0...
;; [iam] Component leveled to 1.0.0
```

#### `level-all!`
```clojure
(level-all!)
```
Level all registered components in registration order.

This function discovers all topics that have been registered via `current-version` and levels each one by calling `level!`. Useful for bootstrapping or ensuring all components are up to date.

**Returns**: Map of `{topic version}` for components that were actually updated.

**Example**:
```clojure
;; Level all components at once
(patch/level-all!)
;; Leveling all registered components...
;; [iam] Leveling component from 0.9.0 to 1.0.0...
;; [iam] Component leveled to 1.0.0
;; [database] Already at version 2.0.0
;; [audit] Leveling component from 0 to 1.0.0...
;; [audit] Component leveled to 1.0.0
;; Leveling complete. Updated 2 component(s)
;; => {:synticity/iam "1.0.0" :synticity/iam-audit "1.0.0"}
```

#### `->FileVersionStore`
```clojure
(->FileVersionStore file-path)
```
Creates a file-based VersionStore that persists versions to an EDN file.

#### `->AtomVersionStore`
```clojure
(->AtomVersionStore state-atom)
```
Creates an atom-based VersionStore for in-memory version tracking (useful for testing).

## Design Philosophy

Patcho follows these principles:

1. **Simplicity**: Minimal API surface - just define versions and migrations
2. **Safety**: Patches are applied in deterministic order based on semantic versioning
3. **Flexibility**: Support for both upgrades and downgrades
4. **Modularity**: Different topics can be versioned independently

## Common Use Cases

- Database schema migrations
- API version upgrades
- Configuration format changes
- Feature flag migrations
- Data format transformations

## Best Practices

1. Always define a current version before defining patches
2. Make patches idempotent when possible
3. Test both upgrade and downgrade paths
4. Use semantic versioning for clear version progression
5. Group related changes by topic
