# SQL Lineage Tool - Architecture

A pure JDK-based backend for tracking SQL table lineage and column evolution in dbt projects.

**Assumes dbt is installed** - leverages compiled SQL and manifest.json for accurate lineage tracking.

## System Components

```mermaid
graph TB
    subgraph "▶ HTTP Layer"
        Server[HttpServer<br/>Port 8080]
        Handlers[HTTP Handlers<br/>Request/Response]
    end

    subgraph "▶ Service Layer"
        LTS[LineageTrackerService]
        CES[ColumnEvolutionService]
        SPS[SqlParserService]
    end

    subgraph "▶ Parser Layer"
        DMP[DbtModelParser<br/>Compiled SQL Reader]
        MR[ManifestReader<br/>manifest.json Parser]
        CSP[CalciteSqlParser<br/>SQL AST]
    end

    subgraph "▶ Model Layer"
        TL[TableLineage]
        CL[ColumnLineage]
        SF[SqlFile]
    end

    subgraph "▶ External"
        Calcite[Apache Calcite]
        Gson[Gson JSON]
        DBT[dbt target/ directory]
    end

    Server --> Handlers
    Handlers --> LTS
    Handlers --> CES
    LTS --> SPS
    CES --> SPS
    SPS --> DMP
    SPS --> MR
    SPS --> CSP
    DMP --> DBT
    MR --> DBT
    CSP --> Calcite
    LTS --> TL
    CES --> CL
    Handlers --> Gson
```

## Request Flow: Track Lineage

```mermaid
sequenceDiagram
    participant Client
    participant Handler
    participant LineageService
    participant SqlParser
    participant DbtParser
    participant ManifestReader
    participant CalciteParser
    participant Calcite

    Client->>Handler: POST /api/lineage/track<br/>{model path}
    Handler->>LineageService: trackLineage(modelPath)
    
    LineageService->>DbtParser: getCompiledSql(modelPath)
    DbtParser->>DbtParser: resolve target/compiled/ path
    DbtParser-->>LineageService: compiled SQL (Jinja expanded)
    
    LineageService->>ManifestReader: getDependencies(modelPath)
    ManifestReader->>ManifestReader: read target/manifest.json
    ManifestReader-->>LineageService: [upstream models]
    
    LineageService->>SqlParser: extractTableNames(compiledSql)
    SqlParser->>CalciteParser: parse(compiledSql)
    CalciteParser->>Calcite: parseStmt()
    Calcite-->>CalciteParser: SqlNode AST
    CalciteParser->>CalciteParser: walk AST
    CalciteParser-->>SqlParser: [table1, table2]
    SqlParser-->>LineageService: tables
    
    LineageService->>LineageService: build TableLineage
    LineageService-->>Handler: result Map
    Handler-->>Client: JSON response
```

## Request Flow: Column Evolution

```mermaid
sequenceDiagram
    participant Client
    participant Handler
    participant EvolutionService
    participant SqlParser
    participant DbtParser
    participant CalciteParser

    Client->>Handler: POST /api/lineage/evolution<br/>{SQL content}
    Handler->>EvolutionService: analyzeColumnEvolution(sql)
    
    EvolutionService->>DbtParser: preprocessDbtSql()
    DbtParser-->>EvolutionService: cleanSql
    
    EvolutionService->>SqlParser: extractColumnAliases()
    SqlParser->>CalciteParser: parse & extract SELECT list
    CalciteParser-->>SqlParser: {col1→alias1, col2→alias2}
    SqlParser-->>EvolutionService: aliases
    
    EvolutionService->>EvolutionService: build ColumnLineage[]
    EvolutionService-->>Handler: result Map
    Handler-->>Client: JSON response
```

## Data Flow: dbt SQL Processing

```mermaid
flowchart LR
    Model[dbt Model Path] --> Compiled
    
    subgraph DBT["dbt Artifacts"]
        Compiled[target/compiled/<br/>project/models/...]
        Manifest[target/manifest.json]
    end
    
    subgraph DbtParser
        ReadSQL[Read Compiled SQL]
        ReadManifest[Parse manifest.json]
    end
    
    subgraph CalciteParser
        Parse[Parse to AST]
        Walk[Walk AST]
        Tables[Extract tables]
        Columns[Extract columns]
    end
    
    Compiled --> ReadSQL
    Manifest --> ReadManifest
    ReadSQL --> Parse
    ReadManifest --> Deps[Dependencies]
    Walk --> Tables
    Walk --> Columns
    Tables --> TableList[Fully qualified<br/>table names]
    Columns --> Aliases[Column aliases]
```

## AST Walking Pattern

```mermaid
graph TD
    Root[SqlSelect]
    Root --> Select[SELECT clause]
    Root --> From[FROM clause]
    
    Select --> Col1[SqlBasicCall: AS]
    Select --> Col2[SqlIdentifier]
    
    Col1 --> Orig[ord_id]
    Col1 --> Alias[order_id]
    
    From --> Join[SqlJoin]
    Join --> Left[stg_orders]
    Join --> Right[stg_products]
```

## Class Structure

```mermaid
classDiagram
    class Application {
        +main(String[]) void
        -initServices()
        -createHttpServer()
        -registerEndpoints()
    }

    class HttpHandler {
        <<interface>>
        +handle(HttpExchange) void
    }

    class TrackLineageHandler {
        -LineageTrackerService service
        -Gson gson
        +handle(HttpExchange) void
    }

    class LineageTrackerService {
        -SqlParserService parser
        -DbtModelParser dbtParser
        -Map~String,TableLineage~ cache
        +trackLineage(String) Map
        +getAllLineage() Map
    }

    class ColumnEvolutionService {
        -SqlParserService parser
        -Map~String,List~ cache
        +analyzeColumnEvolution(String) Map
        +getAllColumnEvolution() Map
    }

    class SqlParserService {
        -CalciteSqlParser calciteParser
        -DbtModelParser dbtParser
        -ManifestReader manifestReader
        +extractTableNames(String) List
        +extractColumnAliases(String) Map
    }

    class CalciteSqlParser {
        -SqlParser.Config config
        +parse(String) SqlNode
        +extractTableNames(String) List
        +extractColumnAliases(String) Map
    }

    class DbtModelParser {
        -String projectRoot
        -String compiledDir
        +getCompiledSql(String) String
        +resolveCompiledPath(String) Path
    }

    class ManifestReader {
        -String manifestPath
        -Map~String,DbtNode~ nodes
        +readManifest() Map
        +getDependencies(String) List
        +getColumns(String) Map
    }

    class TableLineage {
        -String tableName
        -List~String~ parentTables
        -List~String~ childTables
    }

    class ColumnLineage {
        -String columnName
        -List~String~ previousNames
        -String tableName
        -String changeType
    }

    Application --> TrackLineageHandler
    TrackLineageHandler ..|> HttpHandler
    TrackLineageHandler --> LineageTrackerService
    LineageTrackerService --> SqlParserService
    ColumnEvolutionService --> SqlParserService
    SqlParserService --> CalciteSqlParser
    SqlParserService --> DbtModelParser
    SqlParserService --> ManifestReader
    LineageTrackerService --> TableLineage
    ColumnEvolutionService --> ColumnLineage
```

## Example: Processing stg_orders.sql

```mermaid
flowchart TD
    Start["models/staging/stg_orders.sql"] --> Resolve[Resolve compiled path]
    Resolve --> Read["Read target/compiled/<br/>project/models/staging/<br/>stg_orders.sql"]
    Read --> Manifest["Read target/manifest.json<br/>for dependencies"]
    Manifest --> Deps["Dependencies:<br/>raw_orders"]
    Read --> Parse[Calcite parse]
    Parse --> AST[SqlNode AST]
    AST --> WalkFrom[Walk FROM clause]
    AST --> WalkSelect[Walk SELECT list]
    WalkFrom --> Tables["Tables:<br/>analytics.raw_orders<br/>(fully qualified)"]
    WalkSelect --> Aliases["Aliases:<br/>ord_id to order_id<br/>amt to order_amount"]
    Deps --> BuildLineage[Build TableLineage]
    Tables --> BuildLineage
    Aliases --> BuildEvolution["Build ColumnLineage[]"]
    BuildLineage --> StoreL[Store in cache]
    BuildEvolution --> StoreE[Store in cache]
    StoreL --> Return[Return JSON]
    StoreE --> Return
```

## HTTP Server Setup

```mermaid
graph LR
    Main[main method] --> Create[Create services]
    Create --> Parser[CalciteSqlParser]
    Create --> Dbt[DbtModelParser]
    Create --> Manifest[ManifestReader]
    Create --> SqlParser[SqlParserService]
    Create --> Lineage[LineageTrackerService]
    Create --> Evolution[ColumnEvolutionService]
    
    Create --> Server["HttpServer.create<br/>port 8080"]
    Server --> Map1["/api/lineage/track"]
    Server --> Map2["/api/lineage/evolution"]
    Server --> Map3["/api/lineage"]
    Server --> Map4["/api/column-evolution"]
    
    Map1 --> Handler1[TrackLineageHandler]
    Map2 --> Handler2[EvolutionHandler]
    Map3 --> Handler3[GetAllLineageHandler]
    Map4 --> Handler4[GetAllEvolutionHandler]
    
    Server --> Start[server.start]
```

## API Endpoints

| Method | Endpoint | Request | Response |
|--------|----------|---------|----------|
| POST | `/api/lineage/track` | Model path (e.g., "models/staging/stg_orders.sql") | `{"sourceTables": [], "dependencies": [], "fullyQualifiedTables": [], ...}` |
| POST | `/api/lineage/evolution` | Model path | `{"columns": [{"columnName": "", "previousNames": [], ...}]}` |
| GET | `/api/lineage` | - | All cached lineage data |
| GET | `/api/column-evolution` | - | All cached column evolution data |

## Dependencies

**Core:**
- JDK 21 (HttpServer, standard library)

**External:**
- Apache Calcite 1.36.0 (SQL parsing)
- Gson 2.10.1 (JSON serialization)

**Assumes:**
- dbt installed in user environment
- Access to `target/compiled/` and `target/manifest.json` from dbt project

## Directory Structure

```
java-backend/
├── pom.xml
├── ARCHITECTURE.md
└── src/main/java/com/sqllineage/
    ├── Application.java              # Entry point, HTTP server setup
    ├── controller/
    │   └── LineageController.java    # HTTP handlers (HttpHandler implementations)
    ├── service/
    │   ├── SqlParserService.java          # Coordinates parsing
    │   ├── LineageTrackerService.java
    │   └── ColumnEvolutionService.java
    ├── parser/
    │   ├── CalciteSqlParser.java          # SQL AST parsing
    │   ├── DbtModelParser.java            # Read compiled SQL from target/
    │   └── ManifestReader.java            # Parse manifest.json
    ├── model/
    │   ├── TableLineage.java
    │   ├── ColumnLineage.java
    │   ├── DbtNode.java                   # manifest.json node representation
    │   └── SqlFile.java
    └── util/
        └── SqlUtils.java                  # AST walking utilities
```

## Design Patterns

**▶ Layered Architecture**
- HTTP Layer: Request handling, routing
- Service Layer: Business logic, caching
- Parser Layer: dbt artifacts + SQL AST processing
- Model Layer: Data structures

**▶ Manual Dependency Injection**
- Constructor-based dependency passing
- No framework annotations
- Explicit object lifecycle management

**▶ dbt Artifact Parsing**
1. DbtModelParser: Read compiled SQL from `target/compiled/`
2. ManifestReader: Parse `target/manifest.json` for lineage metadata
3. CalciteSqlParser: Parse fully-expanded SQL to AST

**▶ Visitor Pattern**
- Walk Calcite's SqlNode tree
- Extract tables from FROM clauses
- Extract aliases from SELECT lists

## Error Handling

```mermaid
flowchart TD
    Request[HTTP Request] --> Handler[Handler.handle]
    Handler --> Try{try-catch}
    Try -->|success| Process[Process request]
    Try -->|SqlParseException| Log1[Log parse error]
    Try -->|Exception| Log2[Log general error]
    Process --> Success[HTTP 200 + JSON]
    Log1 --> Error[HTTP 500 + error JSON]
    Log2 --> Error
```

## Build & Run

```bash
# Ensure dbt project is compiled first
cd /path/to/dbt-project
dbt compile

# Then build Java backend
cd java-backend
mvn clean compile

# Run (point to dbt project root)
mvn exec:java -Dexec.mainClass="com.sqllineage.Application" \
  -Dexec.args="/path/to/dbt-project"

# Package
mvn clean package
java -jar target/sql-lineage-backend-1.0.0-SNAPSHOT.jar /path/to/dbt-project
```

## Test Data

Sample dbt models in `test-data/` demonstrate:
- Jinja templating (`{{ ref() }}`, `{{ source() }}`)
- Column renaming (AS clauses)
- Multi-table joins
- Lineage across staging → intermediate → marts layers

**To use test data:**
```bash
# Compile test data as a dbt project
cd test-data
dbt compile

# Then run backend pointing to test-data
cd ../java-backend
mvn exec:java -Dexec.mainClass="com.sqllineage.Application" -Dexec.args="../test-data"
```

## Distribution Notes

**For VS Code Extension:** Bundle a minimal JRE (~50-100MB) inside the extension package to avoid requiring users to install Java. The extension can spawn the bundled Java runtime to start the HTTP server automatically on activation.

**Alternative:** Use GraalVM Native Image to compile to a platform-specific executable (no JRE needed), though this adds build complexity.
