# Objective Comparison: Clojure Lifecycle & Migration Libraries

## The Landscape

These libraries solve **different problems** that are often conflated:

| Problem | Libraries |
|---------|-----------|
| **Runtime lifecycle** (start/stop every boot) | Component, Integrant, Mount |
| **Database migrations** | Migratus, Ragtime, Joplin |
| **One-time setup + component versioning** | Patcho |

---

## Runtime Lifecycle Libraries

### [Component](https://github.com/stuartsierra/component) (Stuart Sierra, 2013)

**Approach:** Protocol-based. Components implement `Lifecycle` protocol with `start`/`stop`.

```clojure
(defrecord Database [host port connection]
  component/Lifecycle
  (start [this] (assoc this :connection (connect host port)))
  (stop [this] (disconnect connection) (assoc this :connection nil)))
```

| Pros | Cons |
|------|------|
| Explicit dependencies | All-or-nothing adoption |
| Testable (swap components) | Boilerplate-heavy |
| Multiple instances possible | Changes code structure significantly |
| Industry standard | Steep learning curve |

---

### [Integrant](https://github.com/weavejester/integrant) (James Reeves)

**Approach:** Data-driven. Config is EDN, components are multimethods.

```clojure
;; config.edn
{:db/postgres {:host "localhost" :port 5432}
 :app/handler {:db #ig/ref :db/postgres}}

;; code
(defmethod ig/init-key :db/postgres [_ {:keys [host port]}]
  (connect host port))
```

| Pros | Cons |
|------|------|
| Config as data (EDN) | Less explicit than Component |
| Easier REPL workflow | Magic via multimethods |
| Suspend/resume support | Still requires buy-in |
| Foundation for Duct framework | |

---

### [Mount](https://github.com/tolitius/mount) (tolitius)

**Approach:** Macro-based. `defstate` with `:start`/`:stop` expressions.

```clojure
(defstate db
  :start (connect (env :db-url))
  :stop (disconnect db))
```

| Pros | Cons |
|------|------|
| Minimal boilerplate | Global state (vars) |
| Easy to add to existing code | Can't have multiple instances |
| No protocol ceremony | Implicit dependencies |
| Familiar (just vars) | Couples namespaces to resources |

---

### Comparison Matrix: Runtime Lifecycle

| Feature | Component | Integrant | Mount |
|---------|-----------|-----------|-------|
| **Adoption effort** | High | Medium | Low |
| **Config style** | Code | Data (EDN) | Code |
| **Multiple instances** | Yes | Yes | No |
| **Dependency tracking** | Explicit | Explicit | Implicit (ns order) |
| **REPL workflow** | Manual | Good | Good |
| **Testability** | Excellent | Good | Harder |
| **Boilerplate** | High | Medium | Low |

---

## Database Migration Libraries

### [Migratus](https://github.com/yogthos/migratus)

**Approach:** Timestamp-based SQL files with `--;;` separator.

```
resources/migrations/
├── 20240101120000-create-users.up.sql
├── 20240101120000-create-users.down.sql
```

| Pros | Cons |
|------|------|
| Handles branch conflicts well | SQL-only |
| Lein plugin with generators | Database-specific |
| Used by Luminus | Only migrations, no setup/teardown |
| Tracks all applied migrations | |

---

### [Ragtime](https://github.com/weavejester/ragtime)

**Approach:** Database-independent migration interface.

| Pros | Cons |
|------|------|
| Database agnostic interface | Sequential numbering by default |
| Multiple strategies available | Less tooling than Migratus |
| Foundation for Joplin | |

---

### [Joplin](https://github.com/juxt/joplin)

**Approach:** Built on Ragtime, supports multiple datastores.

| Pros | Cons |
|------|------|
| SQL, Datomic, ES, Cassandra, etc. | More complex |
| Seeding support | Less active development |
| Multi-datastore | |

---

## Where Patcho Fits

**Patcho is orthogonal to all of the above.**

| Aspect | Component/Integrant/Mount | Migratus/Ragtime | Patcho |
|--------|---------------------------|------------------|--------|
| **When** | Every boot | Schema changes | Once per version |
| **What** | Start/stop services | Database DDL | Any one-time operation |
| **Scope** | Runtime connections | Database only | Any component |
| **Versioning** | No | Yes (DB only) | Yes (any component) |
| **Setup/Cleanup** | No | No | Yes |
| **Bidirectional** | Stop/Start | Up/Down | Upgrade/Downgrade + Setup/Cleanup |

---

## Patcho's Unique Value

### 1. **Component-level versioning** (not just database)

```clojure
;; Version your cache, your search index, your API...
(patch/current-version :myapp/elasticsearch "2.1.0")
(patch/current-version :myapp/redis-cache "1.5.0")
(patch/current-version :myapp/database "3.0.0")
```

Migratus/Ragtime only version the database.

### 2. **Setup/Cleanup lifecycle** (not just migrations)

```clojure
(lifecycle/register-module! :myapp/database
  {:setup   (fn [] (create-database-from-env!))   ;; Once ever
   :cleanup (fn [] (drop-database!))              ;; Reverse of setup
   :start   (fn [] (connect!) (patch/level! :myapp/database))
   :stop    (fn [] (disconnect!))})
```

Component/Integrant/Mount don't distinguish "create resource" from "connect to resource".

### 3. **Works WITH other libraries**

```clojure
;; Patcho + Integrant
(defmethod ig/init-key :myapp/database [_ config]
  (let [conn (connect config)]
    (patch/level! :myapp/database)  ;; Apply pending patches
    conn))

;; Patcho + Mount
(defstate db
  :start (do (connect!) (patch/level! :myapp/database) db-conn)
  :stop (disconnect!))
```

### 4. **CLI queryable** (unique to Patcho)

```bash
clj -X:patcho versions :require myapp.core
# => {:myapp/database "3.0.0", :myapp/cache "1.5.0"}
```

No other library exposes component versions to CLI/CI.

---

## Summary: When to Use What

| Use Case | Library |
|----------|---------|
| Managing service start/stop with explicit deps | **Component** or **Integrant** |
| Simple state management, existing codebase | **Mount** |
| Database schema migrations (SQL) | **Migratus** |
| Multi-datastore migrations | **Joplin** |
| One-time setup that persists across restarts | **Patcho** |
| Component versioning beyond database | **Patcho** |
| Bidirectional upgrades/downgrades for any component | **Patcho** |
| CI/CD version visibility | **Patcho** |

---

## The Complete Stack

For a production Clojure app, you might use:

```
┌─────────────────────────────────────────────┐
│            Your Application                 │
├─────────────────────────────────────────────┤
│  Integrant/Component/Mount                  │
│  (start/stop every boot)                    │
├─────────────────────────────────────────────┤
│  Patcho                                     │
│  (setup once, patch per version)            │
├─────────────────────────────────────────────┤
│  Infrastructure                             │
│  (Database, Cache, Search, etc.)            │
└─────────────────────────────────────────────┘
```

Patcho doesn't replace Component/Integrant/Mount—it fills the gap they leave.

---

## Sources

- [Contrasting Component and Mount](https://yogthos.net/posts/2016-01-19-ContrastingComponentAndMount.html)
- [Mount differences from Component](https://github.com/tolitius/mount/blob/master/doc/differences-from-component.md)
- [Integrant GitHub](https://github.com/weavejester/integrant)
- [Migratus vs Ragtime comparison](https://ask.clojure.org/index.php/9598/comparison-b-w-ragtime-and-migratus)
- [Luminus Migrations](https://luminusweb.com/docs/migrations.html)
