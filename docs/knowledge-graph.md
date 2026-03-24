# Neo4j Knowledge Graph

## Overview

ServiceLens uses Neo4j as a structural knowledge store for the Java codebase. Every class, method, and control-flow block is a node; every inheritance, call, dependency, and override is a relationship. This enables graph traversal queries that vector search alone cannot answer — things like "what calls this method?", "what would break if I change this class?", or "walk me through the full call chain from this endpoint."

---

## Node Types

### `Class` node

Represents any Java type: class, interface, enum, record, or annotation.

| Property | Type | Description |
|---|---|---|
| `qualifiedName` | String (PK) | Fully qualified name (e.g., `com.example.OrderService`) |
| `simpleName` | String | Short name (`OrderService`) |
| `packageName` | String | Package (`com.example`) |
| `filePath` | String | Absolute source file path |
| `serviceName` | String | Logical service name (isolation key) |
| `isAbstract` | boolean | Whether the class is abstract |
| `isPublic` | boolean | Whether the class is public |
| `isInterface` | boolean | Whether it is an interface |
| `nodeType` | Enum | CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE |
| `annotations` | List\<String\> | All annotations present |
| `springStereotype` | String | `@Service`, `@Controller`, `@Repository`, `@Configuration`, etc. |
| `javadocSummary` | String | First sentence of class-level Javadoc |

### `Method` node

Represents a single method or constructor.

| Property | Type | Description |
|---|---|---|
| `qualifiedName` | String (PK) | `com.example.OrderService.processOrder(Long,String)` |
| `simpleName` | String | `processOrder` |
| `className` / `packageName` | String | Owning class and package |
| `filePath` | String | Source file |
| `serviceName` | String | Logical service name |
| `returnType` | String | Declared return type |
| `parameterSignature` | String | `(Long,String)` |
| `startLine` / `endLine` | int | Source location |
| `content` | String | Full method source text |
| `annotations` | List\<String\> | All annotations |
| `isEndpoint` | boolean | True if `@GetMapping` / `@PostMapping` etc. |
| `httpMethod` | String | `GET`, `POST`, `PUT`, `DELETE`, `PATCH` |
| `endpointPath` | String | URL path (e.g., `/api/orders/{id}`) |
| `isTransactional` | boolean | True if `@Transactional` |
| `isScheduled` | boolean | True if `@Scheduled` |
| `scheduleExpression` | String | Cron or fixedRate expression |
| `isEventHandler` | boolean | True if `@EventListener` |
| `isTestMethod` | boolean | True if `@Test` |
| `javadocSummary` | String | Javadoc description |
| `cfgNodeCount` | int | Number of CFG nodes (structural complexity indicator) |
| `cyclomaticComplexity` | int | McCabe cyclomatic complexity |
| `externalReferences` | List\<String\> | Bean/field names used but not locally defined (from DFG) |

### `CfgNode` node

Represents a basic block in a method's control flow graph. See [cfg.md](cfg.md) for the full field listing.

---

## Relationships

### Class-level relationships

| Relationship | From | To | Properties | Meaning |
|---|---|---|---|---|
| `INHERITS` | Class | Class | — | `extends` (superclass) |
| `IMPLEMENTS` | Class | Class | — | `implements` (interface) |
| `DEPENDS_ON` | Class | Class | `fieldName`, `fieldType` | Injected dependency (field type) |
| `DEFINES` | Class | Method | — | Method declared in this class |

### Method-level relationships

| Relationship | From | To | Properties | Meaning |
|---|---|---|---|---|
| `CALLS` | Method | Method | `callLine`, `callType` | Method invocation |
| `OVERRIDES` | Method | Method | — | Method overrides parent |

`callType` values: `DIRECT`, `SUPER`, `INTERFACE`

### CFG relationships

| Relationship | From | To | Properties | Meaning |
|---|---|---|---|---|
| `CFG_EDGE` | CfgNode | CfgNode | `edgeType`, `conditionText`, `exceptionType` | Control flow transition |

---

## Graph Queries (via `KnowledgeGraphService`)

| Method | What it does |
|---|---|
| `saveFileResult(result)` | Persists all nodes from one file's ingestion result |
| `deleteServiceGraph(serviceName)` | Removes all nodes/relationships for a service (delegates to `GraphDeleter`) |
| `deleteByFilePath(filePath)` | Removes all nodes for one file (used in incremental ingestion) |
| `findCallers(methodQN)` | Returns all methods with a `CALLS` edge to this method |
| `findCallChain(methodQN, serviceName)` | BFS/DFS forward traversal of `CALLS` edges (depth 5) |
| `findSubclasses(classQN)` | Returns all classes with `INHERITS` pointing to this class |
| `findImplementors(interfaceQN)` | Returns all classes with `IMPLEMENTS` pointing to this interface |
| `findDependents(classQN, serviceName)` | Returns all classes with `DEPENDS_ON` pointing to this class |
| `findEndpoints(serviceName)` | Returns all `MethodNode` where `isEndpoint == true` |
| `findTransactionalMethods(serviceName)` | Returns all `MethodNode` where `isTransactional == true` |
| `findHighComplexityMethods(serviceName, minComplexity)` | Returns methods above cyclomatic complexity threshold |
| `getCfgForMethod(methodQN)` | Returns full CFG subgraph for one method |
| `getServiceStats(serviceName)` | Returns aggregate stats (class count, method count, endpoint count, etc.) |

---

## Service Graph Deletion — GraphDeleter

`deleteServiceGraph(serviceName)` delegates to `GraphDeleter`, which runs batched `DETACH DELETE` queries until all nodes for the service are gone.

### Why label-specific queries?

Neo4j indexes are label-scoped. A query like:
```cypher
MATCH (n {serviceName: $serviceName}) DETACH DELETE n
```
performs a **full graph scan** because there is no label — Neo4j cannot use any index. On large graphs this causes connection timeouts (tested: 2.5+ minutes for a 600-file service).

`GraphDeleter` instead runs **three separate label-specific queries**:
```cypher
MATCH (n:CfgNode  {serviceName: $serviceName}) WITH n LIMIT 500 DETACH DELETE n RETURN count(n) AS deleted
MATCH (n:Method   {serviceName: $serviceName}) WITH n LIMIT 500 DETACH DELETE n RETURN count(n) AS deleted
MATCH (n:Class    {serviceName: $serviceName}) WITH n LIMIT 500 DETACH DELETE n RETURN count(n) AS deleted
```

Each query hits a dedicated index (`class_service`, `method_service`, `cfg_service`) and completes in milliseconds. Deletion loops until all labels return 0.

### Why DETACH DELETE instead of SDN repositories?

Spring Data Neo4j repositories load entire entities into memory before deleting them (N+1 queries). For a 600-file service this means thousands of `MATCH` queries to load entities followed by individual `DELETE` calls — the root cause of the original `ConnectionReadTimeoutException` after 2.5 minutes. `DETACH DELETE` runs entirely server-side: one Cypher statement, no entity materialisation, no JVM memory pressure.

### Why no `@Transactional` on GraphDeleter?

`Neo4jClient` auto-manages a transaction per query. Wrapping the entire multi-batch loop in `@Transactional(REQUIRES_NEW)` keeps a connection checked out for the full loop duration, which exhausts the connection pool under load and produces `BoltServiceUnavailableException` (connection establishment timeout). Removing the annotation lets each batch run in its own auto-committed transaction.

---

## Visual Schema

```
      ┌──────────────────────────────────────────┐
      │                 Class                    │
      │  qualifiedName (PK)                      │
      │  springStereotype                        │
      │  nodeType (CLASS/INTERFACE/ENUM/...)      │
      └──┬──────────┬──────────────┬─────────────┘
         │          │              │
    INHERITS   IMPLEMENTS      DEPENDS_ON
         │          │              │
         ▼          ▼              ▼
       Class     Class           Class

         DEFINES ──────────────────────────────────────►
                                                         │
      ┌──────────────────────────────────────────────────▼──┐
      │                    Method                            │
      │  qualifiedName (PK)                                  │
      │  isEndpoint / httpMethod / endpointPath              │
      │  isTransactional / isScheduled / isEventHandler      │
      │  cyclomaticComplexity / cfgNodeCount                 │
      └──┬──────────────────────────────┬────────────────────┘
         │                              │
       CALLS                        OVERRIDES
         │                              │
         ▼                              ▼
       Method                         Method
         │
       (has CFG sub-graph)
         │
      ┌──▼──────────────┐
      │    CfgNode       │──CFG_EDGE──► CfgNode ──► ...
      │  ENTRY/CONDITION │
      │  /LOOP_HEADER/...│
      └──────────────────┘
```

---

## CfgSaver — Why Flat Cypher?

Spring Data Neo4j serializes entity graphs recursively. For large CFG graphs (100+ nodes per method), this causes `StackOverflowError`. `CfgSaver` bypasses SDN and uses `Neo4jClient` directly with a flat two-pass Cypher approach:

1. `MERGE` all `CfgNode` nodes tagged with `batchId` and `tempId`
2. `CREATE` all `CFG_EDGE` relationships by matching on `tempId`
3. `REMOVE` temp properties

This keeps Neo4j's transaction size predictable and avoids JVM stack issues.

---

## Indexes and Constraints

Primary key fields (`qualifiedName` on `Class` and `Method`, `id` on `CfgNode`) are backed by uniqueness constraints created automatically by Spring Data Neo4j on first startup.

Additional `serviceName` indexes are created by `Neo4jConfig` on startup to support fast label-specific deletion and retrieval:

| Index name | Label | Property |
|---|---|---|
| `class_service` | `Class` | `serviceName` |
| `method_service` | `Method` | `serviceName` |
| `cfg_service` | `CfgNode` | `serviceName` |

All three are created with `CREATE INDEX ... IF NOT EXISTS`, so they are idempotent across restarts.
