# Data Flow Graph (DFG)

## What is the DFG?

For every Java method, ServiceLens performs **intra-procedural data-flow analysis** — tracking where every variable is defined (DEF) and where it is used (USE). The results are stored in-memory during ingestion and serialized as metadata on `MethodNode` for use in retrieval.

---

## Components

### `DfgBuilder`
Two-pass AST analyzer over a single method's body.

- **Pass 1** — collect all parameter definitions
- **Pass 2** — walk the method body, recording every `DEF` site (assignments, declarations, loop variables, caught exceptions) and every `USE` site (method arguments, conditions, return values, etc.)

SSA-inspired naming: if a variable is assigned twice, both definitions are recorded with their line numbers, allowing "which assignment reaches this use" reasoning.

### `DataFlowNode` — represents a DEF site

| Field | Type | Description |
|---|---|---|
| `variableName` | String | Variable name |
| `variableType` | String | Declared type (e.g., `Order`, `int`) |
| `defType` | Enum | PARAMETER, LOCAL_DECL, ASSIGNMENT, LOOP_VAR, CAUGHT_EXCEPTION |
| `sourceExpression` | String | RHS expression text (e.g., `orderRepo.findById(id)`) |
| `line` | int | Source line number |

### `DataFlowUse` — represents a USE site

| Field | Type | Description |
|---|---|---|
| `variableName` | String | Variable name |
| `useType` | Enum | METHOD_ARG, CONDITION, RETURN_VALUE, ASSIGNMENT_RHS, THROWN_VALUE, ARRAY_INDEX |
| `expressionContext` | String | Surrounding expression text |
| `line` | int | Source line number |

### `MethodDataFlow` — per-method DFG container

Holds `List<DataFlowNode>` (defs) and `List<DataFlowUse>` (uses). Key methods:

| Method | Returns |
|---|---|
| `getDefsFor(varName)` | All DEF nodes for a variable |
| `getUsesFor(varName)` | All USE nodes for a variable |
| `getExternalReferences()` | Variables used but never locally defined (= injected fields/beans) |

---

## DEF Types Explained

| DEF Type | Example |
|---|---|
| `PARAMETER` | `void process(Order order)` → `order` defined as PARAMETER |
| `LOCAL_DECL` | `Order o = repo.find(id);` → `o` defined as LOCAL_DECL |
| `ASSIGNMENT` | `total = price * qty;` → `total` defined as ASSIGNMENT |
| `LOOP_VAR` | `for (Item i : items)` → `i` defined as LOOP_VAR |
| `CAUGHT_EXCEPTION` | `catch (IOException e)` → `e` defined as CAUGHT_EXCEPTION |

## USE Types Explained

| USE Type | Example |
|---|---|
| `METHOD_ARG` | `save(order)` → `order` is a METHOD_ARG use |
| `CONDITION` | `if (order != null)` → `order` is a CONDITION use |
| `RETURN_VALUE` | `return result;` → `result` is a RETURN_VALUE use |
| `ASSIGNMENT_RHS` | `copy = original;` → `original` is ASSIGNMENT_RHS |
| `THROWN_VALUE` | `throw ex;` → `ex` is THROWN_VALUE |
| `ARRAY_INDEX` | `items[i]` → `i` is ARRAY_INDEX |

---

## DFG Use Cases (Query Examples)

### 1. Variable Origin Tracing
> "Where does the `amount` variable come from in `processPayment`?"

Find all `DataFlowNode` entries where `variableName == "amount"`. Returns DEF type (PARAMETER = from caller, LOCAL_DECL = computed locally, etc.) and `sourceExpression`.

### 2. Variable Usage Tracing
> "What happens to `amount` after it is set?"

Find all `DataFlowUse` entries where `variableName == "amount"`. See where it is passed as METHOD_ARG, checked in a CONDITION, or returned.

### 3. Null Safety Analysis
> "Is `order` ever null-checked before it is used?"

1. Find all USE nodes for `order`
2. Check if any have `useType == CONDITION` (null check)
3. Compare line numbers: is the CONDITION use before other uses?

### 4. External Reference Detection
> "What injected beans does this method depend on?"

Call `getExternalReferences()` — returns variables used but never defined locally. These are class fields (typically `@Autowired` beans).

### 5. Taint Analysis (manual)
> "Does user input reach the SQL query without sanitization?"

Trace from a PARAMETER DEF → follow METHOD_ARG USEs to find which methods receive the value → check if any of those methods is a database query method.

---

## DFG vs CFG — When to Use Which

| Question | Use |
|---|---|
| "Under what condition is X called?" | CFG (CONDITION nodes, TRUE/FALSE edges) |
| "Where does variable X come from?" | DFG (DEF nodes) |
| "Could this path throw an exception?" | CFG (EXCEPTION_THROW, EXCEPTION_HANDLER) |
| "Is X ever null-checked?" | DFG (CONDITION USE type) |
| "What is the cyclomatic complexity?" | CFG (edge - node + 2) |
| "What injected beans does this method use?" | DFG (external references) |

---

## Storage

DFG data is **not** persisted as separate Neo4j nodes. Instead:
- `MethodNode.dataFlowSummary` (a `@Transient` field) holds the `MethodDataFlow` object during ingestion for use in chunk metadata creation
- `MethodNode.externalReferences` (a persisted `List<String>`) stores the external reference variable names so they are queryable from Neo4j without re-running DFG analysis
- The full DFG is re-computable at any time by re-running `DfgBuilder` on the stored method source

---

## Limitations

- **Intra-procedural only** — does not track data flow across method call boundaries
- **No alias analysis** — if two variables reference the same object, they are treated independently
- **No heap modelling** — field assignments on objects are not tracked, only local variable assignments
