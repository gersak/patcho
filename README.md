# Patcho

A simple, elegant Clojure library for managing version migrations and patches.

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

### Macros

#### `current-version`
```clojure
(current-version topic version-string)
```
Defines the current/target version for a topic.

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

### Functions

#### `apply`
```clojure
(apply topic current-version)
(apply topic current-version target-version)
```
Applies necessary patches to migrate from `current-version` to `target-version` (or the topic's current version if not specified).

- If `current-version` is nil or "0", starts from the beginning
- Automatically determines upgrade vs downgrade direction
- Executes patches in correct order

#### `available-versions`
```clojure
(available-versions)
(available-versions topic1 topic2 ...)
```
Returns a map of topics to their current versions.

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
