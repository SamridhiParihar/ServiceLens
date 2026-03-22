# Control Flow Graph (CFG)

## What is the CFG?

For every Java method in the ingested codebase, ServiceLens builds a **Control Flow Graph** — a directed graph where nodes represent basic blocks of code and edges represent possible execution transitions. The CFG is persisted in Neo4j alongside class/method nodes and is used to answer structural questions about how code executes.

---

## Components

### `CfgBuilder`
Single-pass AST visitor that converts a method's statement tree into CFG nodes and edges.

- Handles 11 statement types: `if`, `while`, `for`, `forEach`, `try-catch-finally`, `switch`, `return`, `throw`, and plain statements
- Automatically creates an `ENTRY` node and `EXIT` node for every method
- Merges branch end-points correctly (e.g., both sides of an `if` join at the next statement)
- Appends `LOOP_BACK` edges for `while`/`for` bodies

### `CfgNode` (Neo4j node, label `CfgNode`)

| Field | Type | Description |
|---|---|---|
| `id` | String | Internal unique identifier |
| `nodeType` | Enum | ENTRY, EXIT, STATEMENT, CONDITION, LOOP_HEADER, METHOD_CALL, EXCEPTION_THROW, EXCEPTION_HANDLER, FINALLY_BLOCK |
| `codeText` | String | Source text of the block |
| `startLine` / `endLine` | int | Source location |
| `conditionExpression` | String | Expression text for CONDITION nodes |
| `calledMethodName` | String | Method name for METHOD_CALL nodes |
| `exceptionType` | String | Exception class for EXCEPTION_THROW / EXCEPTION_HANDLER nodes |
| `methodQualifiedName` | String | Owning method (for graph lookups) |

### `CfgEdge` (Neo4j relationship `CFG_EDGE`)

| Field | Type | Description |
|---|---|---|
| `edgeType` | Enum | UNCONDITIONAL, TRUE, FALSE, EXCEPTION, LOOP_BACK, FALL_THROUGH |
| `conditionText` | String | Branch condition (for TRUE/FALSE edges) |
| `exceptionType` | String | Exception type (for EXCEPTION edges) |

---

## How CFG is Persisted

`CfgSaver` persists CFG nodes using **flat Cypher** (via `Neo4jClient`) instead of Spring Data Neo4j's ORM serialization. This avoids `StackOverflowError` for large graphs with deep node chains.

Two-pass strategy:
1. `MERGE` all `CfgNode` nodes (tagged with `batchId` + `tempId` to identify the session)
2. `CREATE` all `CFG_EDGE` relationships by matching on `tempId`
3. Clean up temp properties after commit

---

## CFG Node Types Explained

```
ENTRY ──► [body starts here]
         │
         ▼
    CONDITION (if x > 0)
    ├──TRUE──► STATEMENT (doA())
    │           │
    └──FALSE──► STATEMENT (doB())
                │
                ▼
             STATEMENT (log())
                │
                ▼
             EXIT
```

| Node Type | When created |
|---|---|
| `ENTRY` | Always first node of every method |
| `EXIT` | Always last node; all return/throw paths lead here |
| `STATEMENT` | Any non-branching statement |
| `CONDITION` | `if` condition or loop test expression |
| `LOOP_HEADER` | Entry point of `while`/`for`/`forEach` loop |
| `METHOD_CALL` | Any statement that calls another method |
| `EXCEPTION_THROW` | `throw` statement |
| `EXCEPTION_HANDLER` | `catch` block entry |
| `FINALLY_BLOCK` | `finally` block entry |

---

## CFG Use Cases (Query Examples)

### 1. Conditional Call Analysis
> "Under what condition does `retryPayment()` get called?"

Walk CFG backward from the `METHOD_CALL` node for `retryPayment`. Collect all `CONDITION` nodes that have a `TRUE`/`FALSE` edge on the path from `ENTRY`.

### 2. Exception Escape Analysis
> "Can a NullPointerException escape this method?"

Find all `EXCEPTION_THROW` nodes typed `NullPointerException`. For each, check if every path to `EXIT` passes through an `EXCEPTION_HANDLER` node with a matching type.

### 3. Worst-Case Path Length
> "How many method calls are on the worst-case execution path?"

Find the longest path from `ENTRY` to `EXIT` (ignoring `LOOP_BACK` edges). Count `METHOD_CALL` nodes along that path.

### 4. Dead Code Detection
> "Is there any unreachable code in this method?"

Find `STATEMENT` or `METHOD_CALL` nodes with no incoming `CFG_EDGE` (other than `ENTRY`).

### 5. Cyclomatic Complexity
Stored directly on `MethodNode.cyclomaticComplexity`. Formula: `edges − nodes + 2`. Computed from CFG during ingestion.

---

## CFG in the Retrieval Layer

When a query is classified as `DEBUG_ERROR` or `TRACE_CALL_CHAIN`, `KnowledgeGraphService.getCfgForMethod(methodQN)` retrieves the full CFG subgraph from Neo4j. This is included in the context sent to the LLM for structural reasoning about execution paths.

---

## Limitations

- **Intra-procedural only** — the CFG models one method at a time; cross-method control flow is handled by the call graph (`CALLS` relationships on `MethodNode`)
- **No virtual dispatch resolution** — a call to an interface method creates a `METHOD_CALL` node with the declared type, not all implementations
- **Lambda bodies** — anonymous lambdas are captured as `STATEMENT` nodes, not expanded into their own CFG
