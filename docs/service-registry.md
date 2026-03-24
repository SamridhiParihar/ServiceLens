# Service Registry

## Overview

The Service Registry is a PostgreSQL table (`service_registry`) that tracks every service that has been ingested into ServiceLens. It is the source of truth for:

- **Whether a service exists** — so `IngestionStrategyResolver` can choose between FRESH and INCREMENTAL
- **Service lifecycle status** — INGESTING → ACTIVE, or DELETING during removal
- **Metadata** — repo path, ingestion timestamps, file count

Without the registry, every ingestion would be treated as a first-time ingest and would always run the full pipeline, discarding all incremental state.

---

## Schema

```sql
CREATE TABLE IF NOT EXISTS service_registry (
    service_name    TEXT        PRIMARY KEY,
    repo_path       TEXT        NOT NULL,
    status          TEXT        NOT NULL,
    ingested_at     TIMESTAMPTZ NOT NULL,
    last_updated_at TIMESTAMPTZ NOT NULL,
    file_count      INTEGER     NOT NULL DEFAULT 0
);
```

The table is created by `PostgresSchemaInitializer` (an `ApplicationRunner` bean) on every startup using `CREATE TABLE IF NOT EXISTS` — idempotent and safe to run repeatedly.

---

## Service Status

| Status | Meaning |
|---|---|
| `INGESTING` | Ingestion is in progress (set at the start of FRESH / FORCE_FULL) |
| `ACTIVE` | Service has been fully ingested and is queryable |
| `DELETING` | `DELETE /api/services/{name}` has been called; deletion is in progress |
| `ERROR` | Reserved for failed ingestion (not yet used in auto-recovery) |

Status transitions during ingestion:
```
(not exists) → INGESTING → ACTIVE
```

Status transitions during deletion:
```
ACTIVE → DELETING → (row deleted)
```

---

## Java Model

### `ServiceRecord` (immutable record)

```java
public record ServiceRecord(
    String  serviceName,
    String  repoPath,
    ServiceStatus status,
    Instant ingestedAt,
    Instant lastUpdatedAt,
    int     fileCount
) {}
```

### `ServiceStatus` (enum)

```java
public enum ServiceStatus {
    INGESTING, ACTIVE, DELETING, ERROR
}
```

---

## Repository — Why JdbcTemplate, not JPA?

`ServiceRegistryRepository` uses `JdbcTemplate` directly rather than Spring Data JPA. The reason is a transaction manager conflict:

- Neo4j registers itself as the `@Primary` transaction manager in a Spring Boot app that uses both Spring Data Neo4j and Spring Data JPA.
- JPA requires its own transaction manager to manage entity state.
- When both exist, JPA operations can silently run under the Neo4j transaction manager, leading to subtle failures or incorrect behaviour.

`JdbcTemplate` bypasses JPA entirely and uses Spring's `DataSourceTransactionManager` explicitly, so there is no conflict. The `service_registry` table is simple enough that the full JPA machinery (entity lifecycle, dirty checking, first-level cache) adds no value here anyway.

---

## Key Operations

### UPSERT

The repository uses PostgreSQL's `INSERT ... ON CONFLICT ... DO UPDATE` to upsert a service record atomically:

```sql
INSERT INTO service_registry
    (service_name, repo_path, status, ingested_at, last_updated_at, file_count)
VALUES (?, ?, ?, ?, ?, ?)
ON CONFLICT (service_name) DO UPDATE SET
    repo_path       = EXCLUDED.repo_path,
    status          = EXCLUDED.status,
    ingested_at     = COALESCE(service_registry.ingested_at, EXCLUDED.ingested_at),
    last_updated_at = EXCLUDED.last_updated_at,
    file_count      = EXCLUDED.file_count
```

Key points:
- `EXCLUDED` refers to the row that failed to insert (i.e., the new values).
- `COALESCE(service_registry.ingested_at, EXCLUDED.ingested_at)` preserves the original `ingested_at` when the row already exists — the first ingestion timestamp is never overwritten.
- `last_updated_at` is always set to the new value.

### Status Update

`updateStatus(serviceName, status)` runs a simple `UPDATE` and is used for status transitions (e.g., marking a service as `DELETING` before removal starts).

---

## Service Facade — `ServiceRegistryService`

`ServiceRegistryService` is the single entry point for all registry operations:

| Method | Description |
|---|---|
| `isRegistered(name)` | Returns true if a row exists for this service name |
| `register(name, repoPath, fileCount)` | Upserts with status=ACTIVE; sets both timestamps to now |
| `update(name, repoPath, fileCount)` | Upserts with status=ACTIVE; preserves original `ingestedAt` |
| `markIngesting(name)` | Sets status=INGESTING |
| `markDeleting(name)` | Sets status=DELETING |
| `remove(name)` | Deletes the row |
| `find(name)` | Returns `Optional<ServiceRecord>` |
| `listAll()` | Returns all registered services |

---

## Ingestion Strategy Resolution

`IngestionStrategyResolver` uses the registry to pick the correct ingestion mode:

```
resolve(serviceName, force)
│
├─ isRegistered(serviceName) == false  →  FRESH
│    Reason: no prior data exists, full pipeline needed
│
├─ isRegistered(serviceName) == true  AND  force == false  →  INCREMENTAL
│    Reason: service exists, use hash-based diff for speed
│
└─ isRegistered(serviceName) == true  AND  force == true   →  FORCE_FULL
     Reason: user explicitly requested a clean re-index
```

Note: if the service is not registered but `force=true` is passed, the result is still `FRESH` — there is nothing to purge, so a full pipeline run is the correct action.

---

## Service Deletion Modes

`ServiceDeletionService` provides two deletion modes:

### `delete(serviceName)` — full removal

Called by `DELETE /api/services/{name}`. Removes everything:

```
1. registryService.markDeleting(serviceName)   ← status = DELETING
2. graphService.deleteServiceGraph(serviceName) ← Neo4j
3. jdbcTemplate DELETE from vector_store        ← pgvector
4. fingerprinter.clearHashes(serviceName)       ← hash file
5. registryService.remove(serviceName)          ← registry row deleted
```

Ordering guarantees: the service is marked DELETING before any data is removed, so a concurrent query during deletion gets a clear signal. The registry row is removed last so the service is not queryable after step 5.

### `purgeData(serviceName)` — data only, registry preserved

Called internally before a `FORCE_FULL` re-ingestion. Removes all stored data but **keeps the registry entry**:

```
1. graphService.deleteServiceGraph(serviceName) ← Neo4j
2. jdbcTemplate DELETE from vector_store        ← pgvector
3. fingerprinter.clearHashes(serviceName)       ← hash file
(registry entry is NOT touched)
```

After `purgeData`, the full pipeline runs and `ServiceRegistryService.update()` updates the registry with fresh timestamps and file count — preserving the original `ingestedAt`.

---

## Multi-Service Isolation

Each service is isolated by `serviceName` as the partition key across all stores:

| Store | Isolation mechanism |
|---|---|
| Neo4j | `serviceName` property on every node; label-specific indexes per label |
| pgvector | `service_name` metadata field; filter applied on every query |
| File hashes | `{dataPath}/{serviceName}/file-hashes.json` — one file per service |
| Service registry | `service_name` PRIMARY KEY — one row per service |

Ingesting, querying, or deleting one service never touches data belonging to another service.
