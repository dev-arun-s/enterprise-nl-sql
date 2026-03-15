package com.nl2sql.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nl2sql.config.Nl2SqlProperties;
import com.nl2sql.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * Optimised metadata extractor for large Oracle schemas (2k+ tables, 30k+ columns).
 *
 * Performance strategy:
 *  1. Bulk queries  — 6 queries total for the whole schema (no per-table loops)
 *  2. Split columns — ALL_TAB_COLUMNS and ALL_COL_COMMENTS fetched separately,
 *                     joined in Java to avoid expensive Oracle view join
 *  3. Pre-sized collections — ArrayList and HashMap initialised with expected
 *                     capacity to avoid resize/rehash overhead
 *  4. Plain JDBC RowMapper with primitive extraction — avoids reflection overhead
 *  5. Parallel execution — all queries run concurrently
 *  6. Streaming JSON write — Jackson writes directly to a BufferedOutputStream
 *                     to avoid building the full JSON string in memory
 *  7. fetchRowCount hint — JdbcTemplate fetch size set to 5000 to reduce
 *                     JDBC round trips for large result sets
 */
@Slf4j
@Service
public class MetadataExtractionService {

    private static final int JDBC_FETCH_SIZE  = 5000;
    private static final int INITIAL_TABLE_CAPACITY  = 2048;
    private static final int INITIAL_COLUMN_CAPACITY = 40000;

    private final JdbcTemplate oracleJdbcTemplate;
    private final Nl2SqlProperties properties;

    // Use a non-pretty ObjectWriter for production — pretty printing 32k columns
    // adds ~40% to serialisation time due to extra whitespace writes.
    // Switch to writerWithDefaultPrettyPrinter() during debugging only.
    private final ObjectWriter objectWriter = new ObjectMapper()
            .disable(SerializationFeature.INDENT_OUTPUT)
            .writer();

    public MetadataExtractionService(
            @Qualifier("oracleJdbcTemplate") JdbcTemplate oracleJdbcTemplate,
            Nl2SqlProperties properties) {
        this.oracleJdbcTemplate = oracleJdbcTemplate;
        this.properties = properties;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public SchemaMetadata extractAndSave(String schemaName) {
        long start = System.currentTimeMillis();
        log.info("Starting metadata extraction for schema: {}", schemaName);
        String owner = schemaName.toUpperCase();

        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            // Submit all queries concurrently
            Future<List<TableMetadata>>                   fTables  = pool.submit(() -> fetchAllTables(owner));
            Future<Map<String, List<ColumnMetadata>>>     fCols    = pool.submit(() -> fetchAllColumns(owner));
            Future<Map<String, List<String>>>             fPks     = pool.submit(() -> fetchAllPrimaryKeys(owner));
            Future<Map<String, List<ForeignKeyMetadata>>> fFks     = pool.submit(() -> fetchAllForeignKeys(owner));
            Future<Map<String, List<IndexMetadata>>>      fIndexes = pool.submit(() -> fetchAllIndexes(owner));

            // Call Future.get() directly so InterruptedException and
            // ExecutionException are thrown and caught here — not hidden inside timed()
            List<TableMetadata>                   tables      = timed("tables",   fTables);
            Map<String, List<ColumnMetadata>>     colsByTable = timed("columns",  fCols);
            Map<String, List<String>>             pksByTable  = timed("pks",      fPks);
            Map<String, List<ForeignKeyMetadata>> fksByTable  = timed("fks",      fFks);
            Map<String, List<IndexMetadata>>      idxByTable  = timed("indexes",  fIndexes);

            // Assemble — O(n) map lookups only, no DB calls
            long assemble = System.currentTimeMillis();
            for (TableMetadata t : tables) {
                String n = t.getTableName();
                t.setColumns    (colsByTable.getOrDefault(n, List.of()));
                t.setPrimaryKeys(pksByTable .getOrDefault(n, List.of()));
                t.setForeignKeys(fksByTable .getOrDefault(n, List.of()));
                t.setIndexes    (idxByTable .getOrDefault(n, List.of()));
            }
            log.info("  Assemble: {}ms", System.currentTimeMillis() - assemble);

            SchemaMetadata schema = SchemaMetadata.builder()
                    .schemaName(owner)
                    .extractedAt(LocalDateTime.now().toString())
                    .tables(tables)
                    .build();

            long save = System.currentTimeMillis();
            saveToFile(schema);
            log.info("  JSON write: {}ms", System.currentTimeMillis() - save);

            log.info("Extraction complete — schema={} tables={} columns={} total={}ms",
                    owner, tables.size(),
                    colsByTable.values().stream().mapToInt(List::size).sum(),
                    System.currentTimeMillis() - start);
            return schema;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Metadata extraction interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new RuntimeException("Metadata extraction failed: " + cause.getMessage(), cause);
        } finally {
            pool.shutdown();
        }
    }

    public Optional<SchemaMetadata> loadFromFile(String schemaName) {
        Path path = getFilePath(schemaName.toUpperCase());
        if (!Files.exists(path)) return Optional.empty();
        try {
            // Use a buffered reader for large files
            try (BufferedInputStream bis = new BufferedInputStream(
                    new FileInputStream(path.toFile()), 256 * 1024)) {
                return Optional.of(new ObjectMapper().readValue(bis, SchemaMetadata.class));
            }
        } catch (IOException e) {
            log.error("Failed to read metadata file for schema {}", schemaName, e);
            return Optional.empty();
        }
    }

    public List<String> listAvailableSchemas() {
        Path dir = Paths.get(properties.getStoragePath());
        if (!Files.exists(dir)) return List.of();
        try {
            return Files.list(dir)
                    .filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().equals("favourites.json"))
                    .map(p -> p.getFileName().toString().replace(".json", ""))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.error("Failed to list metadata directory", e);
            return List.of();
        }
    }

    // ── Bulk queries ──────────────────────────────────────────────────────────

    /** Query 1 — tables + table comments */
    private List<TableMetadata> fetchAllTables(String owner) {
        String sql = """
                SELECT t.TABLE_NAME, c.COMMENTS
                FROM ALL_TABLES t
                LEFT JOIN ALL_TAB_COMMENTS c
                    ON c.OWNER = t.OWNER AND c.TABLE_NAME = t.TABLE_NAME
                WHERE t.OWNER = ?
                ORDER BY t.TABLE_NAME
                """;
        List<TableMetadata> list = new ArrayList<>(INITIAL_TABLE_CAPACITY);
        withFetchSize(fetchSize -> fetchSize.query(sql, rs -> {
            list.add(TableMetadata.builder()
                    .tableName(rs.getString(1))
                    .tableComment(rs.getString(2))
                    .columns(new ArrayList<>())
                    .primaryKeys(new ArrayList<>())
                    .foreignKeys(new ArrayList<>())
                    .indexes(new ArrayList<>())
                    .build());
        }, owner));
        return list;
    }

    /**
     * Query 2a + 2b — columns and comments fetched separately, merged in Java.
     *
     * Splitting avoids the expensive LEFT JOIN between ALL_TAB_COLUMNS and
     * ALL_COL_COMMENTS on large schemas. The merge is a simple HashMap lookup
     * keyed on "TABLE_NAME|COLUMN_NAME".
     */
    private Map<String, List<ColumnMetadata>> fetchAllColumns(String owner) {
        // 2a — column definitions
        String colSql = """
                SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE,
                       DATA_LENGTH, DATA_PRECISION, DATA_SCALE,
                       NULLABLE, DATA_DEFAULT, COLUMN_ID
                FROM ALL_TAB_COLUMNS
                WHERE OWNER = ?
                ORDER BY TABLE_NAME, COLUMN_ID
                """;

        // Pre-size: assume average 16 columns per table
        Map<String, List<ColumnMetadata>> byTable = new HashMap<>(INITIAL_TABLE_CAPACITY * 2);
        // Index for comment merge: "TABLE|COL" → ColumnMetadata
        Map<String, ColumnMetadata> index = new HashMap<>(INITIAL_COLUMN_CAPACITY * 2);

        withFetchSize(t -> t.query(colSql, rs -> {
            String tbl = rs.getString(1);
            String col = rs.getString(2);
            Object prec = rs.getObject(5);
            Object scl  = rs.getObject(6);

            ColumnMetadata cm = ColumnMetadata.builder()
                    .columnName(col)
                    .dataType(rs.getString(3))
                    .dataLength(rs.getInt(4))
                    .dataPrecision(prec != null ? ((Number) prec).intValue() : null)
                    .dataScale    (scl  != null ? ((Number) scl ).intValue() : null)
                    .nullable("Y".equals(rs.getString(7)))
                    .defaultValue(rs.getString(8))
                    .columnOrder(rs.getInt(9))
                    .build();

            byTable.computeIfAbsent(tbl, k -> new ArrayList<>(20)).add(cm);
            index.put(tbl + "|" + col, cm);
        }, owner));

        // 2b — comments only (skip NULLs — typically 80–90% of columns have no comment)
        String cmtSql = """
                SELECT TABLE_NAME, COLUMN_NAME, COMMENTS
                FROM ALL_COL_COMMENTS
                WHERE OWNER = ?
                  AND COMMENTS IS NOT NULL
                """;

        withFetchSize(t -> t.query(cmtSql, rs -> {
            ColumnMetadata cm = index.get(rs.getString(1) + "|" + rs.getString(2));
            if (cm != null) cm.setColumnComment(rs.getString(3));
        }, owner));

        return byTable;
    }

    /** Query 3 — primary keys */
    private Map<String, List<String>> fetchAllPrimaryKeys(String owner) {
        String sql = """
                SELECT con.TABLE_NAME, cc.COLUMN_NAME
                FROM ALL_CONSTRAINTS con
                JOIN ALL_CONS_COLUMNS cc
                    ON cc.OWNER = con.OWNER
                   AND cc.CONSTRAINT_NAME = con.CONSTRAINT_NAME
                WHERE con.OWNER = ?
                  AND con.CONSTRAINT_TYPE = 'P'
                ORDER BY con.TABLE_NAME, cc.POSITION
                """;
        Map<String, List<String>> result = new HashMap<>(INITIAL_TABLE_CAPACITY * 2);
        withFetchSize(t -> t.query(sql, rs -> {
            result.computeIfAbsent(rs.getString(1), k -> new ArrayList<>(4))
                  .add(rs.getString(2));
        }, owner));
        return result;
    }

    /** Query 4 — foreign keys */
    private Map<String, List<ForeignKeyMetadata>> fetchAllForeignKeys(String owner) {
        String sql = """
                SELECT fk.TABLE_NAME, fk.CONSTRAINT_NAME,
                       fkc.COLUMN_NAME, pk.TABLE_NAME, pkc.COLUMN_NAME
                FROM ALL_CONSTRAINTS fk
                JOIN ALL_CONS_COLUMNS fkc
                    ON fkc.OWNER = fk.OWNER AND fkc.CONSTRAINT_NAME = fk.CONSTRAINT_NAME
                JOIN ALL_CONSTRAINTS pk
                    ON pk.OWNER = fk.R_OWNER AND pk.CONSTRAINT_NAME = fk.R_CONSTRAINT_NAME
                JOIN ALL_CONS_COLUMNS pkc
                    ON pkc.OWNER = pk.OWNER
                   AND pkc.CONSTRAINT_NAME = pk.CONSTRAINT_NAME
                   AND pkc.POSITION = fkc.POSITION
                WHERE fk.OWNER = ?
                  AND fk.CONSTRAINT_TYPE = 'R'
                ORDER BY fk.TABLE_NAME, fk.CONSTRAINT_NAME, fkc.POSITION
                """;
        Map<String, List<ForeignKeyMetadata>> result = new HashMap<>(INITIAL_TABLE_CAPACITY * 2);
        withFetchSize(t -> t.query(sql, rs -> {
            result.computeIfAbsent(rs.getString(1), k -> new ArrayList<>(4))
                  .add(ForeignKeyMetadata.builder()
                          .constraintName(rs.getString(2))
                          .localColumn(rs.getString(3))
                          .referencedTable(rs.getString(4))
                          .referencedColumn(rs.getString(5))
                          .build());
        }, owner));
        return result;
    }

    /** Query 5 — indexes */
    private Map<String, List<IndexMetadata>> fetchAllIndexes(String owner) {
        String sql = """
                SELECT i.TABLE_NAME, i.INDEX_NAME, i.UNIQUENESS, ic.COLUMN_NAME
                FROM ALL_INDEXES i
                JOIN ALL_IND_COLUMNS ic
                    ON ic.INDEX_OWNER = i.OWNER AND ic.INDEX_NAME = i.INDEX_NAME
                WHERE i.OWNER = ?
                ORDER BY i.TABLE_NAME, i.INDEX_NAME, ic.COLUMN_POSITION
                """;

        // tableName → indexName → IndexMetadata
        Map<String, Map<String, IndexMetadata>> grouped =
                new HashMap<>(INITIAL_TABLE_CAPACITY * 2);

        withFetchSize(t -> t.query(sql, rs -> {
            String tbl  = rs.getString(1);
            String idx  = rs.getString(2);
            String uniq = rs.getString(3);
            String col  = rs.getString(4);

            grouped.computeIfAbsent(tbl, k -> new LinkedHashMap<>(8))
                   .computeIfAbsent(idx, k -> IndexMetadata.builder()
                           .indexName(idx)
                           .unique("UNIQUE".equals(uniq))
                           .columns(new ArrayList<>(4))
                           .build())
                   .getColumns().add(col);
        }, owner));

        Map<String, List<IndexMetadata>> result = new HashMap<>(grouped.size() * 2);
        grouped.forEach((tbl, idxMap) ->
                result.put(tbl, new ArrayList<>(idxMap.values())));
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Sets JDBC fetch size on the template before running the callback.
     * A fetch size of 5000 means the JDBC driver retrieves 5000 rows per
     * network round trip instead of the default (usually 10 or 1).
     * For 32k rows this reduces round trips from ~3200 to ~7.
     */
    private void withFetchSize(java.util.function.Consumer<JdbcTemplate> action) {
        int previous = oracleJdbcTemplate.getFetchSize();
        oracleJdbcTemplate.setFetchSize(JDBC_FETCH_SIZE);
        try {
            action.accept(oracleJdbcTemplate);
        } finally {
            oracleJdbcTemplate.setFetchSize(previous);
        }
    }

    /**
     * Writes JSON directly to a BufferedOutputStream (256 KB buffer).
     * Avoids materialising the entire JSON string in memory before writing.
     */
    private void saveToFile(SchemaMetadata schema) {
        try {
            Path dir = Paths.get(properties.getStoragePath());
            Files.createDirectories(dir);
            Path filePath = getFilePath(schema.getSchemaName());
            try (OutputStream os = new BufferedOutputStream(
                    new FileOutputStream(filePath.toFile()), 256 * 1024)) {
                objectWriter.writeValue(os, schema);
            }
            log.info("Metadata saved to {}", filePath.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to save metadata for schema " + schema.getSchemaName(), e);
        }
    }

    private Path getFilePath(String schemaName) {
        return Paths.get(properties.getStoragePath(), schemaName.toUpperCase() + ".json");
    }

    /**
     * Waits for a Future and logs how long it took.
     * Declared to throw InterruptedException and ExecutionException so the
     * caller's try/catch blocks are reachable and the compiler is satisfied.
     */
    private <T> T timed(String label, Future<T> future)
            throws InterruptedException, ExecutionException {
        long t = System.currentTimeMillis();
        T result = future.get();
        log.info("  fetch {}: {}ms", label, System.currentTimeMillis() - t);
        return result;
    }
}