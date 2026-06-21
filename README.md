# SQL Lineage Tool

A column-level SQL lineage tool built in Java using ZetaSQL, targeting BigQuery/dbt projects with the intent to support general SQL dialects over time.

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
│         Lineage Extractor                   │  ← walks resolved AST, builds graph
├─────────────────────────────────────────────┤
│           Parser Layer                      │  ← ZetaSQL via JNI, native BigQuery
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

**Cache invalidation strategy:** full rebuild if anything changed. Incremental per-model rebuild (re-parse only changed models + remove their old edges) is a later optimisation — ZetaSQL is fast enough that rebuilding 50-100 models takes milliseconds.

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
- Use **ZetaSQL** (Google's native BigQuery parser, called via JNI) to produce a fully resolved AST
- ZetaSQL performs semantic analysis — column references are resolved to source tables, types inferred, CTEs expanded — so the extractor receives a semantically complete tree
- Wrap behind a `SqlDialectParser` interface so the parser can be swapped for other dialects without touching downstream code
- No BigQuery-specific preprocessing needed — ZetaSQL handles backticks, `SELECT * EXCEPT`, `ARRAY<TYPE>`, `STRUCT`, `QUALIFY` natively
- All SQL files are parsed in one pass and ASTs fed directly into the extractor

### 3. Lineage Extractor
- Walk the ZetaSQL resolved AST to extract `source_table.column → target_table.column` edges
- Because ZetaSQL has already resolved aliases and names, the extractor focuses on edge construction rather than name resolution
- Handle explicitly:
  - CTEs (resolved by ZetaSQL, edges still need extracting)
  - Subqueries
  - `SELECT *` (already expanded by ZetaSQL's semantic analysis)
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

## ZetaSQL Setup

ZetaSQL is a C++ library called via JNI — there is no Maven artifact. It must be built from source once per target platform and bundled alongside the JAR.

```
# One-time build (requires Bazel)
git clone https://github.com/google/zetasql
cd zetasql
bazel build //zetasql/local_service:run_server
```

Platform-specific native binaries (`.so` / `.dylib`) are placed under `native/` and loaded at runtime via `System.loadLibrary()`.

---

## Package Structure

```
src/main/java/com/sqllineage/
├── cli/          ← entry point, picocli commands
├── ingestion/    ← file reader, dbt manifest parser
├── parser/       ← ZetaSQL JNI wrapper, SqlDialectParser interface
├── extractor/    ← AST walker, builds column lineage edges
├── graph/        ← ColumnNode, LineageGraph (JGraphT wrapper)
├── analysis/     ← upstream/downstream traversal logic
└── output/       ← TextRenderer, DotRenderer, JsonRenderer
```

---

## SQL Parser Options Considered

| Library | BigQuery Support | Column Lineage Built-in | Java Native | Maturity | Key Pro | Key Con |
|---|---|---|---|---|---|---|
| **ZetaSQL (JNI)** | Native — Google's actual parser | Partial — semantic analysis resolves names/aliases, you walk the AST for edges | No — C++ via JNI | High (production at Google) | Perfect BQ parsing, semantic resolution done for you, `SELECT *` expanded, aliases resolved | One-time Bazel build, platform-specific binaries, no Maven artifact |
| **Apache Calcite** | Via preprocessing only | Yes — `SqlToRelConverter` tracks full column provenance natively | Yes | Very high (Hive, Flink, Beam) | Best lineage primitives of any option, Maven artifact, huge ecosystem | 4 BQ patterns need preprocessing, `ARRAY<TYPE>` and `STRUCT` are hard to shim |
| **ANTLR4 + BQ grammar** | Full — community BQ grammar exists | No — raw parse tree only, write everything yourself | Yes | High (ANTLR4 itself), Medium (BQ grammar) | Maximum control, full BQ syntax coverage | Most work — alias resolution, CTE expansion, `SELECT *` all manual |
| **JSQLParser** | Partial — backticks work, `ARRAY<TYPE>` and `* EXCEPT` need preprocessing | No | Yes | High (5k stars, active) | Simple Maven dep, handles most common SQL, easy AST | Less BQ coverage than Calcite preprocessing path, no lineage primitives |
| **Trino Parser** | Good — Trino covers most BQ patterns | No | Yes | Very high (10k stars) | Excellent AST quality, extractable as standalone dep | Trino dialect ≠ BigQuery, you write all lineage logic, large transitive deps |
| **sqlglot_java (gtkcyber)** | Claims full | No | Yes | Very low (0 stars, 1 contributor) | Native Java, no setup overhead | Unproven in production, may silently misparse, no community |
| **ZetaSQL Python via subprocess** | Native | No | No — subprocess call | High | Sidesteps JNI complexity | Subprocess overhead, not Java, serialisation cost per query |
