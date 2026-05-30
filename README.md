# SQL Lineage Tool

A column-level SQL lineage tool built in Java using Apache Calcite, initially targeting BigQuery/dbt projects with the intent to support general SQL dialects over time.

---

## Architecture

```
┌─────────────────────────────────────────────┐
│              CLI / API Layer                │  ← entry point, flags, output format
├─────────────────────────────────────────────┤
│              Output Layer                   │  ← text tree, DOT/Graphviz, JSON
├─────────────────────────────────────────────┤
│            Analysis Layer                   │  ← upstream/downstream traversal
├─────────────────────────────────────────────┤
│           Lineage Graph (DAG)               │  ← JGraphT, nodes=columns, edges=deps
├─────────────────────────────────────────────┤
│         Lineage Extractor                   │  ← walks AST, builds graph
├─────────────────────────────────────────────┤
│           Parser Layer                      │  ← Calcite SqlParser + dialect config
├─────────────────────────────────────────────┤
│          Ingestion Layer                    │  ← SQL files, dbt manifest.json, BQ catalog
└─────────────────────────────────────────────┘
```

---

## Build vs Query Time

The pipeline splits into two phases:

**Build time** (done upfront, results cached to disk):
```
Ingestion → Parser → Extractor → Graph → serialise to cache
```

**Query time** (instant, hits cached graph):
```
load cache → Analysis → Output
```

On startup, file mtimes are compared against the cache timestamp. For dbt projects, only `manifest.json` needs to be watched — `dbt compile` regenerates it and all compiled SQL together, so it acts as a single invalidation signal. For raw SQL projects, individual file mtimes are checked.

**Cache invalidation strategy:** full rebuild if anything changed. Incremental per-model rebuild (re-parse only changed models + remove their old edges) is a later optimisation — Calcite is fast enough that rebuilding 50-100 models takes milliseconds.

```
cache/
└── lineage-graph.json   ← serialised DAG, regenerated on any SQL/manifest change
```

---

## Layers — Build Order

### 1. Ingestion
Read SQL from multiple sources — done upfront at build time, not per query:
- `.sql` files directly from disk
- `dbt/target/manifest.json` — parse model definitions and table-level deps (dbt generates this; leverage it rather than re-deriving table lineage). Also acts as the cache invalidation signal for dbt projects.
- Future: BigQuery `INFORMATION_SCHEMA` to pull live DDLs

### 2. Parser
Done upfront at build time alongside ingestion — not live per query:
- Use `Apache Calcite SqlParser` to produce a `SqlNode` AST
- Wrap behind a `SqlDialectParser` interface so BigQuery dialect can be swapped for Snowflake/Redshift/etc without touching downstream code
- Calcite supports BigQuery via `SqlDialect.DatabaseProduct.BIG_QUERY`
- All SQL files are parsed in one pass and ASTs fed directly into the extractor

### 3. Lineage Extractor
- Implement `SqlShuttle` (Calcite's AST visitor) to walk the tree
- Extract `source_table.column → target_table.column` edges
- Handle explicitly:
  - CTEs
  - Subqueries
  - `SELECT *` expansion
  - Expressions (`a + b AS c` means both `a` and `b` feed `c`)

### 4. Lineage Graph
- Use **JGraphT** — mature Java graph library, handles DAGs well
- `ColumnNode { table, column, transformationType }`
- Directed edges, optionally labelled with transformation (e.g. `CAST`, `CONCAT`)
- Table-level graph = projection of column graph (collapse nodes by table)

### 5. Analysis
- `LineageService.upstream(table, column, depth)` — BFS/DFS back through the DAG
- `LineageService.downstream(table, column, depth)` — forward traversal
- `depth = -1` for full lineage

### 6. Output
- **Text** — indented tree to stdout
- **DOT format** — pipe to Graphviz `dot` for PNG/SVG (no Java rendering needed)
- **JSON** — for programmatic use or future UI

---

## Package Structure

```
src/main/java/com/sqllineage/
├── cli/          ← entry point, picocli commands
├── ingestion/    ← file reader, dbt manifest parser
├── parser/       ← Calcite wrapper, SqlDialectParser interface
├── extractor/    ← SqlShuttle implementation, AST walking
├── graph/        ← ColumnNode, LineageGraph (JGraphT wrapper)
├── analysis/     ← upstream/downstream traversal logic
└── output/       ← TextRenderer, DotRenderer, JsonRenderer
```
