# NL2SQL — Natural Language to Oracle SQL

A full-stack application that converts plain English questions into executable Oracle SQL queries using a Spring AI–powered LLM backend and an Angular frontend.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                      Angular Frontend (:4200)                        │
│                                                                      │
│  ┌──────────────────────┐   ┌─────────────────────┐                 │
│  │   QueryEditor        │   │   ResultsGrid        │                 │
│  │  ─────────────────── │   │  ────────────────── │                 │
│  │  Schema selector     │   │  Paginated table    │                 │
│  │  ⬡ Schema Browser   │   │  Column sorting     │                 │
│  │  ⚡ Templates picker │   │  Per-column filter  │                 │
│  │  Prompt textarea     │   │  Column pinning     │                 │
│  │  💬 Conversation bar │   │  Row detail panel   │                 │
│  │  ⚡ Generate SQL     │   │  CSV / XLSX export  │                 │
│  │  Confidence badge    │   └─────────────────────┘                 │
│  │  CodeMirror editor   │                                            │
│  │  ⌥ Format / Explain │   ┌──────────────┐  ┌──────────────────┐  │
│  │  ★ Save favourite   │   │   History    │  │   Favourites     │  │
│  │  ▶ Run Query        │   │  Auto-refresh│  │  Auto-refresh    │  │
│  └──────────────────────┘   └──────────────┘  └──────────────────┘  │
│                                                    ☀/☽ Theme toggle  │
└──────────────────┬──────────────────────────────────────────────────┘
                   │  REST API
┌──────────────────▼──────────────────────────────────────────────────┐
│                    Spring Boot Backend (:8080)                        │
│                                                                      │
│  /api/sql         /api/metadata    /api/history                      │
│  /api/favourites  /api/templates   /api/config                       │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                       Service Layer                          │   │
│  │                                                              │   │
│  │  MetadataExtractionService  ── Oracle ALL_* views (bulk)    │   │
│  │  SemanticTableFilterService ── keyword scoring + FK hops    │   │
│  │  SqlGenerationService       ── Spring AI + confidence score │   │
│  │                                + multi-turn conversation     │   │
│  │  PromptValidationService    ── LLM-assisted validation       │   │
│  │  SqlExplanationService      ── Plain-English explanation     │   │
│  │  SqlFormatterService        ── Pure Java SQL formatter       │   │
│  │  SqlExecutionService        ── Oracle JdbcTemplate          │   │
│  │  QueryTemplateService       ── JSON file-backed templates    │   │
│  │  FavouritesService          ── JSON file persistence        │   │
│  │  ExcelExportService         ── Apache POI XSSF              │   │
│  │  QueryHistoryService        ── H2 JPA                       │   │
│  └──────────────────────────────────────────────────────────────┘   │
└──────────────┬──────────────────────┬───────────────────────────────┘
               │                      │
   ┌───────────▼──────────┐  ┌────────▼──────────┐
   │     Oracle DB         │  │   H2 In-Memory    │
   │  Metadata extraction  │  │   Query history   │
   │  SQL execution        │  └───────────────────┘
   └──────────────────────┘
```

---

## Prerequisites

| Tool        | Version  |
|-------------|----------|
| Java        | 21+      |
| Maven       | 3.9+     |
| Node.js     | 18+      |
| Angular CLI | 17+      |
| Oracle DB   | 12c+     |

---

## Quick Start

### 1. Configure the backend

Edit `backend/src/main/resources/application.yml`:

```yaml
# Oracle connection
spring.datasource.oracle.url:      jdbc:oracle:thin:@//YOUR_HOST:1521/YOUR_SERVICE
spring.datasource.oracle.username: YOUR_ORACLE_USER
spring.datasource.oracle.password: YOUR_ORACLE_PASSWORD

# LLM endpoint (OpenAI-compatible)
spring.ai.openai.base-url: https://YOUR_COMPANY_LLM_ENDPOINT
spring.ai.openai.api-key:  YOUR_API_KEY
spring.ai.openai.chat.options.model: YOUR_MODEL_NAME

# Default schemas to extract on startup
nl2sql.metadata.default-schemas:
  - HR
  - SALES
```

### 2. Run the backend

```bash
cd backend
mvn spring-boot:run
```

On startup the app will:
- Auto-create the H2 query history table
- Extract Oracle metadata for every schema in `default-schemas` (skips if JSON already exists)
- Load query templates from classpath (or external file if configured)
- Serve the REST API on http://localhost:8080

### 3. Run the frontend

```bash
cd frontend
npm install    # also installs CodeMirror 6 packages
npm start      # ng serve with proxy → http://localhost:4200
```

---

## REST API Reference

### Metadata

| Method | Path | Description |
|--------|------|-------------|
| `GET`  | `/api/metadata/schemas` | List schemas with saved metadata files |
| `GET`  | `/api/metadata/{schema}` | Get full metadata JSON (used by Schema Browser) |
| `POST` | `/api/metadata/extract/{schema}` | (Re-)extract metadata from Oracle |

### SQL

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/sql/generate` | Validate prompt → generate SQL with confidence score |
| `POST` | `/api/sql/execute`  | Execute a SQL string |
| `POST` | `/api/sql/explain`  | Plain-English LLM explanation of a SQL query |
| `POST` | `/api/sql/format`   | Format/pretty-print a SQL string |

**POST /api/sql/generate — request body:**
```json
{
  "prompt": "Show all employees in the SALES department",
  "schemaName": "HR",
  "conversationHistory": []
}
```

**POST /api/sql/generate — response:**
```json
{
  "historyId": 42,
  "schemaName": "HR",
  "prompt": "Show all employees in the SALES department",
  "generatedSql": "SELECT e.employee_id, e.first_name ...",
  "promptValid": true,
  "validationReason": "Prompt looks valid.",
  "confidenceScore": 92,
  "confidenceLabel": "High",
  "conversationHistory": [
    { "role": "USER",      "content": "Show all employees..." },
    { "role": "ASSISTANT", "content": "SELECT e.employee_id ..." }
  ]
}
```

### History

| Method | Path | Description |
|--------|------|-------------|
| `GET`    | `/api/history?schema=HR&page=0&size=20` | Paginated query history |
| `GET`    | `/api/history/{id}/csv`  | Re-execute and download as CSV |
| `GET`    | `/api/history/{id}/xlsx` | Re-execute and download as Excel |
| `DELETE` | `/api/history/{id}` | Delete a history entry |

### Favourites

| Method | Path | Description |
|--------|------|-------------|
| `GET`    | `/api/favourites`      | List all saved favourites |
| `POST`   | `/api/favourites`      | Save a new favourite |
| `DELETE` | `/api/favourites/{id}` | Delete a favourite |

### Templates

| Method | Path | Description |
|--------|------|-------------|
| `GET`  | `/api/templates`        | List all query templates |
| `POST` | `/api/templates/reload` | Hot-reload templates from disk (no restart needed) |

### Config

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/config/security` | Returns which DML types are currently permitted |

---

## How It Works

### Metadata Extraction

Queries Oracle data dictionary views in 6 parallel bulk queries (not per-table loops):

| Query | Views | Data |
|-------|-------|------|
| 1 | `ALL_TABLES` + `ALL_TAB_COMMENTS` | Table names and comments |
| 2a | `ALL_TAB_COLUMNS` | Columns, types, nullability (no JOIN) |
| 2b | `ALL_COL_COMMENTS` | Column comments (separate, merged in Java) |
| 3 | `ALL_CONSTRAINTS` + `ALL_CONS_COLUMNS` | Primary keys |
| 4 | `ALL_CONSTRAINTS` + `ALL_CONS_COLUMNS` | Foreign keys |
| 5 | `ALL_INDEXES` + `ALL_IND_COLUMNS` | Indexes |

**Why split columns from comments (2a + 2b)?**
`ALL_TAB_COLUMNS.DATA_DEFAULT` is a LONG column. Oracle silently resets the JDBC fetch size to 1 whenever a LONG column is present in a result set, unless `useFetchSizeWithLongColumn=true` is set. Splitting avoids the expensive LEFT JOIN on large schemas (30k+ columns) and the merged result is done in Java using a HashMap.

Results are serialised to `./metadata/{SCHEMA}.json`.

### Semantic Table Filtering

Before calling the LLM, scores every table in the schema against the prompt:

1. **Tokenise** the prompt (remove stop-words)
2. **Score** each table: name match (+10), comment (+5), column name (+3), column comment (+2)
3. **Select top 10** by score
4. **Expand by one FK hop** so JOIN targets are always included

Only the filtered subset is injected into the LLM prompt.

### SQL Generation with Confidence Score

The system prompt instructs the LLM to return a structured JSON response:

```json
{
  "sql": "SELECT ...",
  "confidence": 92,
  "reasoning": "All tables and columns found directly in the schema."
}
```

Confidence levels:
- **85–100% (High / green)** — all tables/columns found, joins are clear
- **50–84% (Medium / amber)** — some assumptions made, column names inferred
- **0–49% (Low / red)** — significant ambiguity, review before running

Two-layer table validation prevents hallucinated table names:
1. **Sentinel check** — LLM returns `NO_RELEVANT_TABLES` if it can't find matching tables
2. **Post-generation regex check** — all `FROM`/`JOIN` table names verified against known schema tables

### Multi-turn Conversation

Each `generate` call can carry a `conversationHistory` array of previous USER/ASSISTANT turns. The LLM uses this context to refine queries iteratively:

```
Turn 1: "Show all employees in Sales"       → SELECT e.* FROM employees ...
Turn 2: "Now add their department name"     → adds JOIN departments d ON ...
Turn 3: "Filter to those hired after 2020"  → adds WHERE e.hire_date > DATE '2020-01-01'
```

Conversation resets when the user clicks **✕ New Conversation** or switches schema.

### Prompt Validation

Two-tier validation before calling the SQL generation LLM:

- **Tier 1 (instant):** blank, < 5 chars, > 2000 chars → reject immediately
- **Tier 2 (LLM call):** gibberish, greetings, dangerous intent, too vague → reject with explanation

Fails open — if the validation LLM call itself fails, the request proceeds to generation.

### SQL Formatter

Pure Java server-side formatter (no external library). Rules applied:
- Major keywords (`SELECT`, `FROM`, `WHERE`, `JOIN`, etc.) on new lines, uppercase
- SELECT columns one per line, indented 4 spaces
- `AND`/`OR`/`ON` on separate indented lines
- Preserves nested parentheses (functions, sub-queries)

### SQL Execution Safety

| Statement | Allowed | Config |
|-----------|---------|--------|
| `SELECT` / `WITH` | ✅ Always | — |
| `INSERT` | ⚙️ Configurable | `nl2sql.security.allow-insert` |
| `UPDATE` | ⚙️ Configurable | `nl2sql.security.allow-update` |
| `DELETE` | ❌ Never | Hardcoded block |
| `DROP`, `TRUNCATE`, `ALTER`, etc. | ❌ Never | — |

Trailing semicolons are stripped before execution (Oracle JDBC does not accept them).
Results capped at `nl2sql.metadata.max-result-rows` using `FETCH FIRST N ROWS ONLY`.

### Query Templates

Templates loaded from JSON file on disk (hot-reloadable) or classpath fallback.
12 built-in templates across 6 categories: Top N, Aggregation, Date Range, Joins, Data Quality, Search.

**Using templates:**
1. Click **⚡ Templates** in the editor
2. Browse/filter by category or keyword
3. Click a template — its prompt loads into the textarea
4. Replace `{placeholder}` tokens with actual table/column names
5. Generate SQL as normal

**Adding custom templates** — edit `query-templates.json`, then call `POST /api/templates/reload`.

### Schema Browser

Modal accessible via **⬡** button next to the schema selector. Features:
- Expandable table/column tree with search highlighting
- Shows columns with data type, nullability, PK/FK indicators, comments
- FK relationship section showing referenced tables/columns
- Expand All / Collapse All buttons
- Click table `+` or any column to insert the name into the prompt

---

## UI Feature Guide

### Generating a Query
1. Select a schema from the dropdown
2. Optionally click **⬡** to browse tables or **⚡ Templates** to load a template
3. Type or edit your question in the prompt box
4. Click **⚡ Generate SQL** — the LLM validates the prompt, finds relevant tables, and generates SQL
5. Review the **confidence badge** (green/amber/red %) — low confidence means review carefully
6. Optionally click **⌥ Format** to pretty-print or **💡 Explain** for a plain-English explanation
7. Edit the SQL directly in the CodeMirror editor if needed (MODIFIED badge appears)
8. Click **▶ Run Query** to execute

### Multi-turn Refinement
After generating SQL, type a follow-up in the prompt box:
- *"now add a filter for orders in 2024"*
- *"also show the customer's email address"*
- *"change the sort order to ascending"*

The **💬 N turn conversation** bar shows how many turns are active. Click **✕ New Conversation** to start fresh.

### Schema Browser
- Click **⬡** next to the schema selector
- Search for a table or column name — matching items are highlighted
- Click `+` on a table row to insert the table name into the prompt
- Click any column row to insert `TABLE.COLUMN` into the prompt

### Query Templates
- Click **⚡ Templates** above the prompt box
- Filter by category pill or type to search
- Click a template card to load its prompt
- Replace `{placeholder}` tokens with actual names using the Schema Browser

### Results Grid
- **Sort** — click any column header (toggle ascending/descending)
- **Filter** — click **⚲ Filter** to show per-column filter inputs; matching text is highlighted
- **Pin** — click the 📍 icon on a column header to pin it to the left while scrolling horizontally
- **Row detail** — click any row to open a side panel showing all values vertically
- **Download** — CSV and XLSX buttons re-execute the query and stream the file

### SQL Editor (CodeMirror)
- Full Oracle SQL syntax highlighting
- Line numbers and active line indicator
- Undo/redo (`Ctrl+Z` / `Ctrl+Y`)
- Keyword autocompletion
- **⌥ Format** — pretty-print with consistent indentation
- **↺ Reset** — revert to original generated SQL (shown when MODIFIED badge is visible)

### History & Favourites
Both panels auto-refresh immediately when:
- A new query is generated
- A query is executed (row count updates)
- A favourite is saved

Manual **↻** refresh button also available on each panel.

### Theme Toggle
Click **☀** / **☽** in the header to switch dark/light mode. Preference is saved to `localStorage`.

---

## Project Structure

```
nl2sql/
├── backend/
│   ├── pom.xml                              # Spring Boot 3.3, Spring AI, ojdbc11, POI
│   └── src/main/
│       ├── java/com/nl2sql/
│       │   ├── Nl2SqlApplication.java
│       │   ├── config/
│       │   │   ├── AiConfig.java            # ChatClient bean
│       │   │   ├── CorsConfig.java
│       │   │   ├── DataSourceConfig.java    # Oracle + H2 dual datasource with Oracle JDBC tuning
│       │   │   └── Nl2SqlProperties.java    # Typed config (metadata + security + templates)
│       │   ├── controller/
│       │   │   ├── ConfigController.java
│       │   │   ├── FavouritesController.java
│       │   │   ├── GlobalExceptionHandler.java
│       │   │   ├── MetadataController.java
│       │   │   ├── QueryHistoryController.java
│       │   │   ├── QueryTemplateController.java
│       │   │   ├── SqlController.java       # generate + execute (multi-turn + confidence)
│       │   │   ├── SqlExplainController.java
│       │   │   └── SqlFormatController.java
│       │   ├── dto/
│       │   │   ├── ExecuteSqlRequest.java
│       │   │   ├── ExplainSqlRequest/Response.java
│       │   │   ├── GenerateSqlRequest.java  # includes conversationHistory
│       │   │   ├── GenerateSqlResponse.java # includes confidenceScore + conversationHistory
│       │   │   ├── SecurityConfigResponse.java
│       │   │   └── SqlExecutionResult.java
│       │   ├── model/
│       │   │   ├── ColumnMetadata.java
│       │   │   ├── ConversationMessage.java # USER/ASSISTANT turns
│       │   │   ├── FavouriteQuery.java
│       │   │   ├── ForeignKeyMetadata.java
│       │   │   ├── IndexMetadata.java
│       │   │   ├── QueryHistory.java
│       │   │   ├── QueryTemplate.java
│       │   │   ├── SchemaMetadata.java
│       │   │   └── TableMetadata.java
│       │   ├── repository/
│       │   │   └── QueryHistoryRepository.java
│       │   └── service/
│       │       ├── ExcelExportService.java       # Apache POI XLSX
│       │       ├── FavouritesService.java        # JSON file persistence
│       │       ├── MetadataExtractionService.java # 6 parallel bulk queries
│       │       ├── MetadataStartupRunner.java
│       │       ├── PromptValidationService.java  # LLM-assisted validation
│       │       ├── QueryHistoryService.java
│       │       ├── QueryTemplateService.java     # classpath + external file
│       │       ├── SemanticTableFilterService.java
│       │       ├── SqlExecutionService.java      # DML permissions + semicolon strip
│       │       ├── SqlExplanationService.java
│       │       ├── SqlFormatterService.java      # Pure Java formatter
│       │       └── SqlGenerationService.java     # multi-turn + confidence + validation
│       └── resources/
│           ├── application.yml
│           ├── schema-h2.sql
│           └── templates/
│               └── query-templates.json          # 12 built-in templates
└── frontend/
    ├── angular.json
    ├── package.json                              # includes CodeMirror 6 packages
    ├── proxy.conf.json
    ├── tsconfig.json / tsconfig.app.json
    └── src/
        ├── index.html
        ├── main.ts
        ├── styles.css                            # Dark/light CSS variables
        └── app/
            ├── app.component.ts                  # Root layout with flex budget
            ├── models/
            │   └── api.models.ts
            ├── services/
            │   ├── api.service.ts
            │   ├── refresh.service.ts            # Event bus for auto-refresh
            │   └── theme.service.ts
            └── components/
                ├── header/                       # Brand + theme toggle
                ├── query-editor/                 # Full editor with all features
                ├── schema-browser/               # Expandable table/column tree modal
                ├── query-templates/              # Template picker modal
                ├── results-grid-v2/              # Grid with filter/pin/row-detail
                ├── history/                      # Auto-refreshing history panel
                └── favourites/                   # Auto-refreshing favourites panel
```

---

## Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `nl2sql.metadata.default-schemas` | `[HR, SALES]` | Schemas extracted on startup |
| `nl2sql.metadata.storage-path` | `./metadata` | Directory for metadata JSON files |
| `nl2sql.metadata.max-result-rows` | `500` | Row cap for SQL execution |
| `nl2sql.metadata.favourites-file` | `./metadata/favourites.json` | Favourites persistence file |
| `nl2sql.metadata.templates-file` | `""` | External templates JSON (empty = use classpath) |
| `nl2sql.security.allow-insert` | `false` | Allow INSERT statement execution |
| `nl2sql.security.allow-update` | `false` | Allow UPDATE statement execution |
| `spring.ai.openai.chat.options.temperature` | `0.1` | Low = deterministic SQL |
| `spring.ai.openai.chat.options.max-tokens` | `2048` | Max LLM response tokens |
| `spring.datasource.oracle.hikari.maximum-pool-size` | `15` | Oracle connection pool size |

### Oracle JDBC Performance Settings (DataSourceConfig.java)

| Property | Value | Effect |
|----------|-------|--------|
| `defaultRowPrefetch` | `5000` | Rows buffered per server round-trip (default is 10) |
| `useFetchSizeWithLongColumn` | `true` | Keeps prefetch active when LONG columns are present |
| `oracle.jdbc.implicitStatementCacheSize` | `20` | Cached PreparedStatements per connection |
| `oracle.net.CONNECT_TIMEOUT` | `10000ms` | Fail fast if Oracle unreachable |
| `oracle.jdbc.ReadTimeout` | `300000ms` | 5-minute query timeout |

---

## Customisation

### Enable INSERT / UPDATE execution
```yaml
nl2sql:
  security:
    allow-insert: true
    allow-update: true
```

### Add custom query templates
Edit `query-templates.json` and call `POST /api/templates/reload` — no restart needed:
```json
{
  "id": "my-template",
  "category": "My Category",
  "title": "My template",
  "description": "What it does",
  "prompt": "Show all {table} records where {column} = '{value}'",
  "tags": ["custom"]
}
```

### Add a new schema at runtime
```
POST http://localhost:8080/api/metadata/extract/FINANCE
```
Or use the **⬇** button in the UI.

### Change LLM model or temperature
```yaml
spring:
  ai:
    openai:
      chat:
        options:
          model: gpt-4o
          temperature: 0.0   # 0.0 = maximally deterministic
          max-tokens: 4096
```

### Tune semantic table filtering
Edit `SemanticTableFilterService.java`:
- Increase `TOP_N` from `10` to include more tables
- Adjust scoring weights for different match types
- Increase FK expansion hops for deeper relationship traversal