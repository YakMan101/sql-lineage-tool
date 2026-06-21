# SQL Lineage Tool

A column-level SQL lineage tool built in Java using Apache Calcite, targeting BigQuery/dbt projects with the intent to support general SQL dialects over time.

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
│           Parser Layer                      │  ← Calcite SqlParser + BQ preprocessing
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
- Use **Apache Calcite `SqlParser`** to produce a `SqlNode` AST
- BigQuery compatibility via config + targeted preprocessing:
  - Backtick identifiers → `Quoting.BACK_TICK` config flag
  - `FORMAT_DATE`, `FORMAT_TIMESTAMP` etc. → `SqlConformanceEnum.LENIENT` config flag
  - `CAST(col AS ARRAY<STRING>)` → regex preprocess, strip type parameter (type irrelevant for lineage)
  - `SELECT * EXCEPT (...)` → JavaCC grammar extension (regex too brittle for nested CTEs/subqueries)
- Wrap behind a `SqlDialectParser` interface so the parser can be swapped for other dialects without touching downstream code
- All SQL files are parsed in one pass and ASTs fed directly into the extractor

### 3. Lineage Extractor
The extractor walks the `SqlNode` AST produced by the parser and builds column lineage edges. The parser handles syntax; the extractor handles semantics.

**Propagation strategy:** walk the `parent_map` DAG top-down starting from roots (sources and seeds), carrying known column sets forward through each model. Because schema is always known at the roots, `SELECT *` can be fully expanded at every downstream layer without needing a live database catalog.

```
sources  →  schema from manifest columns field
seeds    →  schema from CSV headers
              ↓
         stg_* models  (columns derived from parent ASTs)
              ↓
         int_* models  (columns derived from parent ASTs)
              ↓
         fct_*/dim_* models
```

**`SELECT *` on undocumented sources:** raw/airbyte source tables often have columns not declared in `schema.yml`. When `SELECT *` is encountered on a source with no known schema, propagate a `*` sentinel and emit a warning. Downstream models that reference specific columns from that table will still resolve correctly; only `SELECT *` chains remain opaque until `INFORMATION_SCHEMA` support is added.

**Schema availability by type:**

| Type | Schema source | Complete? |
|---|---|---|
| `SOURCE` | Manifest `columns` field (`schema.yml`) | Only documented columns |
| `SEED` | CSV header row | Yes — always complete |
| `MODEL` | Propagated from parent ASTs | Yes — derived during walk |

**Handles explicitly:**
- CTEs — treat as named intermediate column sets within a model
- `SELECT *` — expand using propagated parent schema, or sentinel `*` if unknown
- Expressions (`a + b AS c`) — both `a` and `b` are recorded as inputs to `c`
- Aliases — tracked so downstream models resolve column references correctly

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
├── parser/       ← Calcite wrapper, SqlDialectParser interface, BQ preprocessor
├── extractor/    ← AST walker, builds column lineage edges
├── graph/        ← ColumnNode, LineageGraph (JGraphT wrapper)
├── analysis/     ← upstream/downstream traversal logic
└── output/       ← TextRenderer, DotRenderer, JsonRenderer
```

---

## Extending the Calcite Grammar for BigQuery

Calcite's parser is generated from a JavaCC grammar file. Calcite exposes a FreeMarker templating system that lets you inject additional grammar rules at build time without forking Calcite itself.

### Build flow

```
parserImpls.ftl + config.fmpp
         ↓  (fmpp plugin at Gradle build time)
    Parser.jj  (merged grammar)
         ↓  (JavaCC compiler)
  BigQueryParserImpl.java  (generated)
         ↓
  Your code uses it via SqlParser.config()
```

### Step 1: Add the FMPP plugin to build.gradle

```groovy
plugins {
    id 'org.apache.calcite.buildtools.fmpp' version '1.37.0'
}
```

### Step 2: Create the FMPP config file

Create `src/main/codegen/config.fmpp` — tells the generator which extension points to inject into:

```
data: {
    parser: {
        keywords: [
            "EXCEPT"
        ]

        selectExpressionExtensions: [
            "SqlSelectExcept()"
        ]

        implementationFiles: [
            "parserImpls.ftl"
        ]
    }
}
```

### Step 3: Write the grammar rules

Create `src/main/codegen/includes/parserImpls.ftl` — the actual JavaCC grammar fragment:

```
SqlNode SqlSelectExcept() :
{
    SqlNodeList exceptCols;
    SqlParserPos pos;
}
{
    <STAR> { pos = getPos(); }
    <EXCEPT>
    <LPAREN>
    exceptCols = ExpressionCommaList(pos, ExprContext.ACCEPT_NON_QUERY)
    <RPAREN>
    {
        return new SqlSelectExcept(exceptCols, pos);
    }
}
```

### Step 4: Implement the SqlNode class

The grammar references `SqlSelectExcept` — implement it as a Java class extending `SqlNode`:

```java
public class SqlSelectExcept extends SqlNode {
    private final SqlNodeList exceptColumns;

    public SqlSelectExcept(SqlNodeList exceptColumns, SqlParserPos pos) {
        super(pos);
        this.exceptColumns = exceptColumns;
    }

    @Override public SqlNode clone(SqlParserPos pos) { ... }
    @Override public void unparse(SqlWriter writer, int leftPrec, int rightPrec) { ... }
    @Override public void validate(SqlValidator validator, SqlValidatorScope scope) { ... }
}
```

### Step 5: Wire the generated parser into your config

```java
SqlParser.Config config = SqlParser.config()
    .withParserFactory(BigQueryParserImpl.FACTORY)  // generated class
    .withQuoting(Quoting.BACK_TICK)                 // handles backtick identifiers
    .withConformance(SqlConformanceEnum.LENIENT);    // handles FORMAT_DATE etc.

SqlParser parser = SqlParser.create(preprocessed(sql), config);
```

Where `preprocessed(sql)` is a simple regex pass that strips `ARRAY<STRING>` angle brackets before Calcite sees the SQL (type info is irrelevant for lineage).

### BigQuery patterns and how each is handled

| Pattern | Occurrences in codebase | Approach |
|---|---|---|
| Backtick identifiers | ~17,000 | `Quoting.BACK_TICK` config flag |
| `FORMAT_DATE`, `FORMAT_TIMESTAMP` | ~473 | `SqlConformanceEnum.LENIENT` config flag |
| `CAST(col AS ARRAY<STRING>)` | ~1,000 | Regex preprocess — strip type parameter |
| `SELECT * EXCEPT (...)` | ~2,400 | JavaCC grammar extension (above) |

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
