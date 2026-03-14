# NL2SQL — Natural Language to Oracle SQL

A full-stack application that converts plain English questions into executable Oracle SQL queries using a Spring AI–powered LLM backend and an Angular frontend.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                     Angular Frontend (:4200)                     │
│                                                                  │
│  ┌─────────────────┐   ┌──────────────────┐   ┌─────────────┐  │
│  │  QueryEditor    │   │  ResultsGrid      │   │  History    │  │
│  │  ─────────────  │   │  ──────────────── │   │  ─────────  │  │
│  │  Schema select  │   │  Data table       │   │  List       │  │
│  │  Prompt input   │   │  CSV download     │   │  Paginated  │  │
│  │  Generate SQL   │   │  XLSX download    │   │  Delete     │  │
│  │  ▶ Run button   │   │  Pagination       │   └─────────────┘  │
│  │  💡 Explain     │   └──────────────────┘                     │
│  │  ★ Favourites   │   ┌──────────────────┐   ┌─────────────┐  │
│  │  ⬇ Extract meta │   │  Favourites      │   │ Theme       │  │
│  └─────────────────┘   │  ──────────────── │   │ ☀/☽ Toggle │  │
│                         │  Saved queries   │   └─────────────┘  │
│                         │  Click to reload │                     │
│                         └──────────────────┘                     │
└───────────────────┬──────────────────────────────────────────────┘
                    │  REST API
┌───────────────────▼──────────────────────────────────────────────┐
│                  Spring Boot Backend (:8080)                      │
│                                                                   │
│  /api/metadata   /api/sql          /api/history                  │
│  /api/favourites /api/config       /api/sql/explain              │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │                      Service Layer                          │ │
│  │                                                             │ │
│  │  MetadataExtractionService  ──► Oracle ALL_* views          │ │
│  │  SemanticTableFilterService ──► keyword scoring + FK hops   │ │
│  │  SqlGenerationService       ──► Spring AI ChatClient        │ │
│  │  SqlExplanationService      ──► Spring AI ChatClient        │ │
│  │  SqlExecutionService        ──► Oracle JdbcTemplate         │ │
│  │  FavouritesService          ──► favourites.json on disk     │ │
│  │  ExcelExportService         ──► Apache POI XSSF             │ │
│  │  QueryHistoryService        ──► H2 JPA                      │ │
│  └─────────────────────────────────────────────────────────────┘ │
└──────────────────┬─────────────────────┬────────────────────────┘
                   │                     │
        ┌──────────▼──────────┐  ┌───────▼──────────┐
        │     Oracle DB        │  │   H2 In-Memory   │
        │  Metadata extract    │  │   Query history  │
        │  SQL execution       │  └──────────────────┘
        └─────────────────────┘
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

Edit `backend/src/main/resources/application.yml` and replace the placeholder values:

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
- Serve the REST API on http://localhost:8080

### 3. Run the frontend

```bash
cd frontend
npm install
npm start          # ng serve with proxy → http://localhost:4200
```

---

## REST API Reference

### Metadata

| Method | Path | Description |
|--------|------|-------------|
| `GET`  | `/api/metadata/schemas` | List schemas that have saved metadata files |
| `GET`  | `/api/metadata/{schema}` | Get full metadata JSON for a schema |
| `POST` | `/api/metadata/extract/{schema}` | (Re-)extract metadata from Oracle and save to disk |

### SQL

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/sql/generate` | Generate SQL from a natural language prompt (does **not** execute) |
| `POST` | `/api/sql/execute`  | Execute a SQL string and return tabular results |
| `POST` | `/api/sql/explain`  | Ask the LLM to explain a SQL query in plain English |

**POST /api/sql/generate — request body:**
```json
{
  "prompt": "Show me all employees in the SALES department",
  "schemaName": "HR"
}
```

**POST /api/sql/execute — request body:**
```json
{
  "sql": "SELECT * FROM employees FETCH FIRST 100 ROWS ONLY",
  "historyId": 42
}
```

**POST /api/sql/explain — request body:**
```json
{
  "sql": "SELECT e.first_name, d.department_name FROM employees e JOIN departments d ON e.department_id = d.department_id"
}
```

### History

| Method | Path | Description |
|--------|------|-------------|
| `GET`    | `/api/history?schema=HR&page=0&size=20` | Paginated query history |
| `GET`    | `/api/history/{id}/csv`  | Re-execute and download results as CSV |
| `GET`    | `/api/history/{id}/xlsx` | Re-execute and download results as Excel (.xlsx) |
| `DELETE` | `/api/history/{id}` | Delete a history entry |

### Favourites

| Method | Path | Description |
|--------|------|-------------|
| `GET`    | `/api/favourites`     | List all saved favourite queries |
| `POST`   | `/api/favourites`     | Save a new favourite query |
| `DELETE` | `/api/favourites/{id}` | Delete a favourite by ID |

**POST /api/favourites — request body:**
```json
{
  "schemaName": "HR",
  "title": "Sales employees hired after 2020",
  "prompt": "Show employees in Sales hired after 2020",
  "sql": "SELECT ..."
}
```

### Config

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/config/security` | Returns which DML statement types are currently permitted |

**GET /api/config/security — response:**
```json
{
  "allowSelect": true,
  "allowInsert": false,
  "allowUpdate": false,
  "allowDelete": false
}
```

---

## How It Works

### Metadata Extraction

At startup (or on-demand via the UI ⬇ button or the API), the service queries these Oracle data dictionary views:

| View | Data Extracted |
|------|----------------|
| `ALL_TABLES` + `ALL_TAB_COMMENTS` | Table names and comments |
| `ALL_TAB_COLUMNS` + `ALL_COL_COMMENTS` | Columns, data types, nullability, default values, comments |
| `ALL_CONSTRAINTS` + `ALL_CONS_COLUMNS` | Primary keys and foreign key relationships |
| `ALL_INDEXES` + `ALL_IND_COLUMNS` | Indexes and their columns |

Results are serialised to `./metadata/{SCHEMA}.json` on disk.

### Semantic Table Filtering

Before calling the LLM, the app scores every table in the schema against the user's prompt to reduce token usage and improve accuracy:

1. **Tokenise** the prompt — remove stop-words, split on non-alphanumeric characters
2. **Score** each table:
   - Table name match: **+10 pts**
   - Table comment match: **+5 pts**
   - Column name match: **+3 pts**
   - Column comment match: **+2 pts**
3. **Select top 10** tables by score
4. **Expand by one FK hop** — include any tables referenced via foreign keys so JOIN targets are always present

Only the filtered subset is injected into the LLM prompt.

### SQL Generation

The system prompt instructs the LLM to:
- Return **only** the SQL query (no markdown, no explanation)
- Use **Oracle SQL syntax** (`FETCH FIRST N ROWS ONLY`, etc.)
- Qualify column names with aliases when joining
- Never generate DML statements (enforced again server-side)

### SQL Explanation

A separate LLM call with a plain-English explanation prompt. The LLM is instructed to:
- Describe what data the query retrieves
- Explain JOINs, WHERE conditions, GROUP BY, ORDER BY in plain language
- Stay under 200 words and use bullet points
- Never restate the SQL verbatim

### SQL Execution Safety

The `SqlExecutionService` enforces the following rules on every execution:

| Statement | Allowed? | Config flag |
|-----------|----------|-------------|
| `SELECT` / `WITH` (CTE) | ✅ Always | — |
| `INSERT` | ⚙️ Configurable | `nl2sql.security.allow-insert` |
| `UPDATE` | ⚙️ Configurable | `nl2sql.security.allow-update` |
| `DELETE` | ❌ Never | No config flag — hardcoded block |
| `DROP`, `TRUNCATE`, `ALTER`, `CREATE`, `GRANT`, `REVOKE`, `MERGE` | ❌ Never | — |

Results for SELECT queries are capped at `nl2sql.metadata.max-result-rows` (default 500) using `FETCH FIRST N ROWS ONLY`.

### Excel Export

Built with Apache POI (XSSF). Each exported file includes:
- Bold header row with grey background
- Data rows with automatic numeric type detection
- Auto-sized columns (capped at 50 characters wide)

### Favourites Persistence

Saved to `./metadata/favourites.json` on disk (path configurable via `nl2sql.metadata.favourites-file`). The service holds an in-memory cache and writes synchronously on every add/delete. Loaded automatically at startup.

### Dark / Light Theme

Driven by CSS custom properties on the `[data-theme]` attribute of `<html>`. The `ThemeService` persists the user's preference to `localStorage` and falls back to the OS `prefers-color-scheme` media query on first visit. Toggle available in the header (☀ / ☽).

---

## Project Structure

```
nl2sql/
├── backend/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/nl2sql/
│       │   ├── Nl2SqlApplication.java
│       │   ├── config/
│       │   │   ├── AiConfig.java               # ChatClient bean
│       │   │   ├── CorsConfig.java             # CORS for Angular dev server
│       │   │   ├── DataSourceConfig.java       # Dual Oracle + H2 datasources
│       │   │   └── Nl2SqlProperties.java       # Typed config (metadata + security)
│       │   ├── controller/
│       │   │   ├── ConfigController.java       # GET /api/config/security
│       │   │   ├── FavouritesController.java   # CRUD /api/favourites
│       │   │   ├── GlobalExceptionHandler.java # Unified error responses
│       │   │   ├── MetadataController.java     # Extract + list + get metadata
│       │   │   ├── QueryHistoryController.java # History, CSV, XLSX download
│       │   │   ├── SqlController.java          # Generate + Execute
│       │   │   └── SqlExplainController.java   # POST /api/sql/explain
│       │   ├── dto/
│       │   │   ├── ExecuteSqlRequest.java
│       │   │   ├── ExplainSqlRequest.java
│       │   │   ├── ExplainSqlResponse.java
│       │   │   ├── GenerateSqlRequest.java
│       │   │   ├── GenerateSqlResponse.java
│       │   │   ├── SecurityConfigResponse.java
│       │   │   └── SqlExecutionResult.java
│       │   ├── model/
│       │   │   ├── ColumnMetadata.java
│       │   │   ├── FavouriteQuery.java         # Favourite query model
│       │   │   ├── ForeignKeyMetadata.java
│       │   │   ├── IndexMetadata.java
│       │   │   ├── QueryHistory.java           # JPA entity (H2)
│       │   │   ├── SchemaMetadata.java
│       │   │   └── TableMetadata.java
│       │   ├── repository/
│       │   │   └── QueryHistoryRepository.java
│       │   └── service/
│       │       ├── ExcelExportService.java     # Apache POI XLSX export
│       │       ├── FavouritesService.java      # JSON-file-backed favourites
│       │       ├── MetadataExtractionService.java
│       │       ├── MetadataStartupRunner.java
│       │       ├── QueryHistoryService.java
│       │       ├── SemanticTableFilterService.java
│       │       ├── SqlExecutionService.java    # DML permission enforcement
│       │       ├── SqlExplanationService.java  # LLM plain-English explanation
│       │       └── SqlGenerationService.java
│       └── resources/
│           ├── application.yml
│           └── schema-h2.sql
└── frontend/
    ├── angular.json
    ├── package.json
    ├── proxy.conf.json                         # Dev proxy → :8080
    ├── tsconfig.json
    ├── tsconfig.app.json
    └── src/
        ├── index.html
        ├── main.ts
        ├── styles.css                          # Dual dark/light CSS variables
        └── app/
            ├── app.component.ts               # Root shell layout
            ├── models/
            │   └── api.models.ts              # All TypeScript interfaces
            ├── services/
            │   ├── api.service.ts             # HTTP client for all endpoints
            │   └── theme.service.ts           # Dark/light theme management
            └── components/
                ├── header/                    # Brand + theme toggle
                ├── query-editor/              # Prompt → Generate → Run + Explain + Favourite
                ├── results-grid/              # Paginated table + CSV/XLSX download
                ├── history/                   # Paginated query history
                └── favourites/               # Saved favourite queries
```

---

## Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `nl2sql.metadata.default-schemas` | `[HR, SALES]` | Schemas extracted on startup |
| `nl2sql.metadata.storage-path` | `./metadata` | Directory for metadata JSON files |
| `nl2sql.metadata.max-result-rows` | `500` | Row cap for SQL execution |
| `nl2sql.metadata.favourites-file` | `./metadata/favourites.json` | Favourites persistence file |
| `nl2sql.security.allow-insert` | `false` | Allow INSERT statement execution |
| `nl2sql.security.allow-update` | `false` | Allow UPDATE statement execution |
| `spring.ai.openai.chat.options.temperature` | `0.1` | Low = deterministic SQL |
| `spring.ai.openai.chat.options.max-tokens` | `2048` | Max LLM response tokens |

---

## UI Walkthrough

### Generating a Query
1. Select a schema from the dropdown
2. Type a question in the prompt box (or click a quick example chip)
3. Click **⚡ Generate SQL** — the LLM generates the SQL and displays it
4. Review the SQL type badge (SELECT / INSERT / UPDATE)
5. Click **▶ Run Query** to execute — results appear in the right panel

### Extracting Metadata
- Click the **⬇** icon next to the schema selector
- A modal confirms how many tables were extracted and when
- Re-extraction overwrites the existing JSON file

### Explaining a Query
- After generating SQL, click **💡 Explain**
- A modal shows the SQL and a plain-English bullet-point explanation from the LLM

### Saving a Favourite
- After generating SQL, click **★ Save**
- Enter a descriptive title and confirm
- The query appears in the **★ FAVOURITES** panel (bottom-right)
- Click any favourite to load its SQL back

### Downloading Results
- After running a query, click **⬇ CSV** or **⬇ XLSX** in the results toolbar
- The file is re-executed server-side and streamed directly to the browser

### Theme Toggle
- Click **☀** / **☽** in the top-right header to switch between dark and light mode
- Preference is saved to `localStorage` and remembered on next visit

---

## Extending the App

### Enable INSERT / UPDATE execution
```yaml
nl2sql:
  security:
    allow-insert: true
    allow-update: true
```
Restart the backend. The Angular UI automatically reads the security config on load and enables the Run button for those statement types.

### Add a new schema at runtime
```
POST http://localhost:8080/api/metadata/extract/FINANCE
```
Or use the ⬇ button in the UI after selecting the schema.

### Increase result rows
```yaml
nl2sql.metadata.max-result-rows: 1000
```

### Tune semantic table filtering
Edit `SemanticTableFilterService.java`:
- Increase `TOP_N` from `10` to include more tables per prompt
- Adjust the scoring weights for different match types
- Increase FK expansion from 1 hop to 2 for deeper relationship traversal

### Change LLM model or temperature
```yaml
spring:
  ai:
    openai:
      chat:
        options:
          model: gpt-4o          # any model your endpoint exposes
          temperature: 0.0       # 0.0 = maximally deterministic
          max-tokens: 4096
```