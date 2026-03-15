package com.nl2sql.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nl2sql.config.Nl2SqlProperties;
import com.nl2sql.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * Extracts full Oracle schema metadata with two key optimisations:
 *
 *  1. BULK queries — one query per metadata type for the entire schema
 *     (never loops per table). 6 queries total regardless of table count.
 *
 *  2. SPLIT column fetch — ALL_TAB_COLUMNS and ALL_COL_COMMENTS are queried
 *     separately and joined in Java. The LEFT JOIN between these two views
 *     is extremely expensive on schemas with 30k+ columns because
 *     ALL_COL_COMMENTS has no useful index on (OWNER, TABLE_NAME, COLUMN_NAME)
 *     in many Oracle versions, forcing a full scan on every join row.
 *
 *  3. PARALLEL execution — all 6 queries run concurrently on a thread pool,
 *     so total wall-clock time ≈ the slowest single query, not their sum.
 */
@Slf4j
@Service
public class MetadataExtractionService {

    private final JdbcTemplate oracleJdbcTemplate;
    private final Nl2SqlProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public MetadataExtractionService(
            @Qualifier("oracleJdbcTemplate") JdbcTemplate oracleJdbcTemplate,
            Nl2SqlProperties properties) {
        this.oracleJdbcTemplate = oracleJdbcTemplate;
        this.properties = properties;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public SchemaMetadata extractAndSave(String schemaName) {
        long start = System.currentTimeMillis();
        log.info("Starting parallel bulk metadata extraction for schema: {}", schemaName);
        String owner = schemaName.toUpperCase();

        // Run all queries in parallel on a fixed thread pool
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            // Submit all queries concurrently
            Future<List<TableMetadata>>                 fTables      = pool.submit(() -> fetchAllTables(owner));
            Future<Map<String, List<ColumnMetadata>>>   fColumns     = pool.submit(() -> fetchAllColumns(owner));
            Future<Map<String, List<String>>>           fPks         = pool.submit(() -> fetchAllPrimaryKeys(owner));
            Future<Map<String, List<ForeignKeyMetadata>>> fFks       = pool.submit(() -> fetchAllForeignKeys(owner));
            Future<Map<String, List<IndexMetadata>>>    fIndexes     = pool.submit(() -> fetchAllIndexes(owner));

            // Collect results (blocks until each is ready)
            List<TableMetadata>                    tables      = fTables.get();
            Map<String, List<ColumnMetadata>>      columnsByTable = fColumns.get();
            Map<String, List<String>>              pksByTable   = fPks.get();
            Map<String, List<ForeignKeyMetadata>>  fksByTable   = fFks.get();
            Map<String, List<IndexMetadata>>       indexesByTable = fIndexes.get();

            log.info("  All queries complete — assembling {} tables", tables.size());

            // Assemble — pure in-memory map lookups, no DB calls
            for (TableMetadata table : tables) {
                String name = table.getTableName();
                table.setColumns(columnsByTable.getOrDefault(name, List.of()));
                table.setPrimaryKeys(pksByTable.getOrDefault(name, List.of()));
                table.setForeignKeys(fksByTable.getOrDefault(name, List.of()));
                table.setIndexes(indexesByTable.getOrDefault(name, List.of()));
            }

            SchemaMetadata schema = SchemaMetadata.builder()
                    .schemaName(owner)
                    .extractedAt(LocalDateTime.now().toString())
                    .tables(tables)
                    .build();

            saveToFile(schema);

            long elapsed = System.currentTimeMillis() - start;
            log.info("Extraction complete — schema={} tables={} columns={} elapsed={}ms (~{}s)",
                    owner, tables.size(),
                    columnsByTable.values().stream().mapToInt(List::size).sum(),
                    elapsed, elapsed / 1000);
            return schema;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Metadata extraction interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Metadata extraction failed: " + e.getCause().getMessage(), e.getCause());
        } finally {
            pool.shutdown();
        }
    }

    public Optional<SchemaMetadata> loadFromFile(String schemaName) {
        Path filePath = getFilePath(schemaName.toUpperCase());
        if (!Files.exists(filePath)) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(filePath.toFile(), SchemaMetadata.class));
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

    // ── Bulk Oracle queries ───────────────────────────────────────────────────

    /** Query 1 — all tables + table-level comments */
    private List<TableMetadata> fetchAllTables(String owner) {
        long t = System.currentTimeMillis();
        String sql = """
                SELECT t.TABLE_NAME, c.COMMENTS AS TABLE_COMMENT
                FROM ALL_TABLES t
                LEFT JOIN ALL_TAB_COMMENTS c
                    ON c.OWNER = t.OWNER AND c.TABLE_NAME = t.TABLE_NAME
                WHERE t.OWNER = ?
                ORDER BY t.TABLE_NAME
                """;
        List<TableMetadata> result = oracleJdbcTemplate.query(sql, (rs, rowNum) ->
                TableMetadata.builder()
                        .tableName(rs.getString("TABLE_NAME"))
                        .tableComment(rs.getString("TABLE_COMMENT"))
                        .columns(new ArrayList<>())
                        .primaryKeys(new ArrayList<>())
                        .foreignKeys(new ArrayList<>())
                        .indexes(new ArrayList<>())
                        .build(), owner);
        log.debug("  fetchAllTables: {} rows in {}ms", result.size(), System.currentTimeMillis() - t);
        return result;
    }

    /**
     * Query 2 (split into 2a + 2b) — columns and column comments fetched
     * separately then merged in Java.
     *
     * WHY SPLIT:
     *   ALL_TAB_COLUMNS LEFT JOIN ALL_COL_COMMENTS with 32k+ rows forces Oracle
     *   to evaluate the join for every column row. ALL_COL_COMMENTS is a view
     *   backed by SYS.COMMENT$ which may not have an efficient index path for
     *   bulk owner-scoped queries, leading to repeated full/range scans.
     *
     *   Fetching both flat and merging in Java using a HashMap is O(n) and
     *   avoids the join overhead entirely.
     */
    private Map<String, List<ColumnMetadata>> fetchAllColumns(String owner) {
        long t = System.currentTimeMillis();

        // 2a — column definitions (no join)
        String colSql = """
                SELECT
                    TABLE_NAME,
                    COLUMN_NAME,
                    DATA_TYPE,
                    DATA_LENGTH,
                    DATA_PRECISION,
                    DATA_SCALE,
                    NULLABLE,
                    DATA_DEFAULT,
                    COLUMN_ID
                FROM ALL_TAB_COLUMNS
                WHERE OWNER = ?
                ORDER BY TABLE_NAME, COLUMN_ID
                """;

        // Build map: "TABLE.COLUMN" → ColumnMetadata (mutable builder pattern)
        Map<String, ColumnMetadata> columnIndex = new LinkedHashMap<>();
        Map<String, List<ColumnMetadata>> result = new LinkedHashMap<>();

        oracleJdbcTemplate.query(colSql, rs -> {
            String tableName  = rs.getString("TABLE_NAME");
            String columnName = rs.getString("COLUMN_NAME");
            Object precObj    = rs.getObject("DATA_PRECISION");
            Object scaleObj   = rs.getObject("DATA_SCALE");

            ColumnMetadata col = ColumnMetadata.builder()
                    .columnName(columnName)
                    .dataType(rs.getString("DATA_TYPE"))
                    .dataLength(rs.getInt("DATA_LENGTH"))
                    .dataPrecision(precObj != null ? ((Number) precObj).intValue() : null)
                    .dataScale(scaleObj   != null ? ((Number) scaleObj).intValue()  : null)
                    .nullable("Y".equals(rs.getString("NULLABLE")))
                    .defaultValue(rs.getString("DATA_DEFAULT"))
                    .columnOrder(rs.getInt("COLUMN_ID"))
                    .build();

            columnIndex.put(tableName + "." + columnName, col);
            result.computeIfAbsent(tableName, k -> new ArrayList<>()).add(col);
        }, owner);

        log.debug("  fetchAllColumns (definitions): {} columns in {}ms",
                columnIndex.size(), System.currentTimeMillis() - t);

        // 2b — column comments (separate query, no join)
        long t2 = System.currentTimeMillis();
        String commentSql = """
                SELECT TABLE_NAME, COLUMN_NAME, COMMENTS
                FROM ALL_COL_COMMENTS
                WHERE OWNER = ?
                  AND COMMENTS IS NOT NULL
                """;

        oracleJdbcTemplate.query(commentSql, rs -> {
            String key     = rs.getString("TABLE_NAME") + "." + rs.getString("COLUMN_NAME");
            String comment = rs.getString("COMMENTS");
            ColumnMetadata col = columnIndex.get(key);
            if (col != null) {
                col.setColumnComment(comment);
            }
        }, owner);

        log.debug("  fetchAllColumns (comments): merged in {}ms", System.currentTimeMillis() - t2);
        return result;
    }

    /** Query 3 — all primary keys for the schema */
    private Map<String, List<String>> fetchAllPrimaryKeys(String owner) {
        long t = System.currentTimeMillis();
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

        Map<String, List<String>> result = new LinkedHashMap<>();
        oracleJdbcTemplate.query(sql, rs -> {
            result.computeIfAbsent(rs.getString("TABLE_NAME"), k -> new ArrayList<>())
                  .add(rs.getString("COLUMN_NAME"));
        }, owner);

        log.debug("  fetchAllPrimaryKeys: {}ms", System.currentTimeMillis() - t);
        return result;
    }

    /** Query 4 — all foreign keys for the schema */
    private Map<String, List<ForeignKeyMetadata>> fetchAllForeignKeys(String owner) {
        long t = System.currentTimeMillis();
        String sql = """
                SELECT
                    fk.TABLE_NAME,
                    fk.CONSTRAINT_NAME,
                    fkc.COLUMN_NAME       AS LOCAL_COLUMN,
                    pk.TABLE_NAME         AS REF_TABLE,
                    pkc.COLUMN_NAME       AS REF_COLUMN
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

        Map<String, List<ForeignKeyMetadata>> result = new LinkedHashMap<>();
        oracleJdbcTemplate.query(sql, rs -> {
            String tableName = rs.getString("TABLE_NAME");
            ForeignKeyMetadata fk = ForeignKeyMetadata.builder()
                    .constraintName(rs.getString("CONSTRAINT_NAME"))
                    .localColumn(rs.getString("LOCAL_COLUMN"))
                    .referencedTable(rs.getString("REF_TABLE"))
                    .referencedColumn(rs.getString("REF_COLUMN"))
                    .build();
            result.computeIfAbsent(tableName, k -> new ArrayList<>()).add(fk);
        }, owner);

        log.debug("  fetchAllForeignKeys: {}ms", System.currentTimeMillis() - t);
        return result;
    }

    /** Query 5 — all indexes for the schema */
    private Map<String, List<IndexMetadata>> fetchAllIndexes(String owner) {
        long t = System.currentTimeMillis();
        String sql = """
                SELECT
                    i.TABLE_NAME,
                    i.INDEX_NAME,
                    i.UNIQUENESS,
                    ic.COLUMN_NAME
                FROM ALL_INDEXES i
                JOIN ALL_IND_COLUMNS ic
                    ON ic.INDEX_OWNER = i.OWNER AND ic.INDEX_NAME = i.INDEX_NAME
                WHERE i.OWNER = ?
                ORDER BY i.TABLE_NAME, i.INDEX_NAME, ic.COLUMN_POSITION
                """;

        // tableName → (indexName → IndexMetadata)
        Map<String, Map<String, IndexMetadata>> grouped = new LinkedHashMap<>();
        oracleJdbcTemplate.query(sql, rs -> {
            String tableName  = rs.getString("TABLE_NAME");
            String indexName  = rs.getString("INDEX_NAME");
            String uniqueness = rs.getString("UNIQUENESS");
            String columnName = rs.getString("COLUMN_NAME");

            grouped.computeIfAbsent(tableName, k -> new LinkedHashMap<>())
                   .computeIfAbsent(indexName, k -> IndexMetadata.builder()
                           .indexName(indexName)
                           .unique("UNIQUE".equals(uniqueness))
                           .columns(new ArrayList<>())
                           .build())
                   .getColumns().add(columnName);
        }, owner);

        Map<String, List<IndexMetadata>> result = new LinkedHashMap<>();
        grouped.forEach((table, indexMap) ->
                result.put(table, new ArrayList<>(indexMap.values())));

        log.debug("  fetchAllIndexes: {}ms", System.currentTimeMillis() - t);
        return result;
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    private void saveToFile(SchemaMetadata schema) {
        try {
            Path dir = Paths.get(properties.getStoragePath());
            Files.createDirectories(dir);
            Path filePath = getFilePath(schema.getSchemaName());
            objectMapper.writeValue(filePath.toFile(), schema);
            log.info("Metadata saved to {}", filePath.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to save metadata for schema " + schema.getSchemaName(), e);
        }
    }

    private Path getFilePath(String schemaName) {
        return Paths.get(properties.getStoragePath(), schemaName.toUpperCase() + ".json");
    }
}