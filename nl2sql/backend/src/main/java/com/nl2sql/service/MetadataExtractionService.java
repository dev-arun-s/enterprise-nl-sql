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

/**
 * Extracts full Oracle schema metadata using bulk queries per metadata type
 * (5 total queries regardless of table count) rather than per-table loops.
 *
 * For a schema with 2,000 tables the old approach made ~8,000 round trips.
 * This approach makes exactly 5.
 */
@Slf4j
@Service
public class MetadataExtractionService {

    private final JdbcTemplate oracleJdbcTemplate;
    private final Nl2SqlProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Explicit constructor so @Qualifier is honoured.
     * Lombok's @RequiredArgsConstructor does not propagate field-level @Qualifier
     * to constructor parameters, causing Spring to inject the @Primary (H2) template.
     */
    public MetadataExtractionService(
            @Qualifier("oracleJdbcTemplate") JdbcTemplate oracleJdbcTemplate,
            Nl2SqlProperties properties) {
        this.oracleJdbcTemplate = oracleJdbcTemplate;
        this.properties = properties;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Extracts metadata for the given schema owner and saves to JSON file.
     *
     * Bulk strategy — exactly 5 queries total for the whole schema:
     *   1. All tables + table comments
     *   2. All columns + column comments
     *   3. All primary keys
     *   4. All foreign keys
     *   5. All indexes
     */
    public SchemaMetadata extractAndSave(String schemaName) {
        long start = System.currentTimeMillis();
        log.info("Starting bulk metadata extraction for schema: {}", schemaName);
        String owner = schemaName.toUpperCase();

        // ── 1. Fetch all tables ──────────────────────────────────────────────
        List<TableMetadata> tables = fetchAllTables(owner);
        log.info("  Fetched {} tables", tables.size());

        // Build a map for fast lookup: tableName → TableMetadata
        Map<String, TableMetadata> tableMap = new LinkedHashMap<>();
        for (TableMetadata t : tables) {
            tableMap.put(t.getTableName(), t);
        }

        // ── 2. Bulk fetch columns ────────────────────────────────────────────
        Map<String, List<ColumnMetadata>> columnsByTable = fetchAllColumns(owner);
        log.info("  Fetched columns for {} tables", columnsByTable.size());

        // ── 3. Bulk fetch primary keys ───────────────────────────────────────
        Map<String, List<String>> pksByTable = fetchAllPrimaryKeys(owner);
        log.info("  Fetched primary keys");

        // ── 4. Bulk fetch foreign keys ───────────────────────────────────────
        Map<String, List<ForeignKeyMetadata>> fksByTable = fetchAllForeignKeys(owner);
        log.info("  Fetched foreign keys");

        // ── 5. Bulk fetch indexes ────────────────────────────────────────────
        Map<String, List<IndexMetadata>> indexesByTable = fetchAllIndexes(owner);
        log.info("  Fetched indexes");

        // ── Assemble ─────────────────────────────────────────────────────────
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
        log.info("Extraction complete for schema {} — {} tables in {}ms (~{}s)",
                owner, tables.size(), elapsed, elapsed / 1000);
        return schema;
    }

    /**
     * Loads previously saved metadata from disk. Returns empty if not found.
     */
    public Optional<SchemaMetadata> loadFromFile(String schemaName) {
        Path filePath = getFilePath(schemaName.toUpperCase());
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }
        try {
            SchemaMetadata metadata = objectMapper.readValue(filePath.toFile(), SchemaMetadata.class);
            return Optional.of(metadata);
        } catch (IOException e) {
            log.error("Failed to read metadata file for schema {}", schemaName, e);
            return Optional.empty();
        }
    }

    /**
     * Returns the list of schemas that have a saved metadata file on disk.
     */
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

    // ── Bulk Oracle queries (one per metadata type) ──────────────────────────

    /**
     * Query 1 — all tables + comments for the schema in one shot.
     */
    private List<TableMetadata> fetchAllTables(String owner) {
        String sql = """
                SELECT t.TABLE_NAME, c.COMMENTS AS TABLE_COMMENT
                FROM ALL_TABLES t
                LEFT JOIN ALL_TAB_COMMENTS c
                    ON c.OWNER = t.OWNER AND c.TABLE_NAME = t.TABLE_NAME
                WHERE t.OWNER = ?
                ORDER BY t.TABLE_NAME
                """;
        return oracleJdbcTemplate.query(sql, (rs, rowNum) ->
                TableMetadata.builder()
                        .tableName(rs.getString("TABLE_NAME"))
                        .tableComment(rs.getString("TABLE_COMMENT"))
                        .columns(new ArrayList<>())
                        .primaryKeys(new ArrayList<>())
                        .foreignKeys(new ArrayList<>())
                        .indexes(new ArrayList<>())
                        .build(), owner);
    }

    /**
     * Query 2 — all columns + comments for every table in the schema.
     * Returns a map of tableName → List<ColumnMetadata>.
     */
    private Map<String, List<ColumnMetadata>> fetchAllColumns(String owner) {
        String sql = """
                SELECT
                    c.TABLE_NAME,
                    c.COLUMN_NAME,
                    c.DATA_TYPE,
                    c.DATA_LENGTH,
                    c.DATA_PRECISION,
                    c.DATA_SCALE,
                    c.NULLABLE,
                    c.DATA_DEFAULT,
                    c.COLUMN_ID,
                    cc.COMMENTS AS COLUMN_COMMENT
                FROM ALL_TAB_COLUMNS c
                LEFT JOIN ALL_COL_COMMENTS cc
                    ON cc.OWNER = c.OWNER
                   AND cc.TABLE_NAME = c.TABLE_NAME
                   AND cc.COLUMN_NAME = c.COLUMN_NAME
                WHERE c.OWNER = ?
                ORDER BY c.TABLE_NAME, c.COLUMN_ID
                """;

        Map<String, List<ColumnMetadata>> result = new LinkedHashMap<>();
        oracleJdbcTemplate.query(sql, rs -> {
            String tableName  = rs.getString("TABLE_NAME");
            String columnName = rs.getString("COLUMN_NAME");
            String dataType   = rs.getString("DATA_TYPE");
            int    dataLength = rs.getInt("DATA_LENGTH");
            Object precObj    = rs.getObject("DATA_PRECISION");
            Object scaleObj   = rs.getObject("DATA_SCALE");
            String nullable   = rs.getString("NULLABLE");
            String defVal     = rs.getString("DATA_DEFAULT");
            int    colId      = rs.getInt("COLUMN_ID");
            String comment    = rs.getString("COLUMN_COMMENT");

            ColumnMetadata col = ColumnMetadata.builder()
                    .columnName(columnName)
                    .dataType(dataType)
                    .dataLength(dataLength)
                    .dataPrecision(precObj != null ? ((Number) precObj).intValue() : null)
                    .dataScale(scaleObj   != null ? ((Number) scaleObj).intValue()  : null)
                    .nullable("Y".equals(nullable))
                    .defaultValue(defVal)
                    .columnOrder(colId)
                    .columnComment(comment)
                    .build();

            result.computeIfAbsent(tableName, k -> new ArrayList<>()).add(col);
        }, owner);

        return result;
    }

    /**
     * Query 3 — all primary key columns for every table in the schema.
     * Returns a map of tableName → List<pkColumnName>.
     */
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

        Map<String, List<String>> result = new LinkedHashMap<>();
        oracleJdbcTemplate.query(sql, rs -> {
            String tableName  = rs.getString("TABLE_NAME");
            String columnName = rs.getString("COLUMN_NAME");
            result.computeIfAbsent(tableName, k -> new ArrayList<>()).add(columnName);
        }, owner);

        return result;
    }

    /**
     * Query 4 — all foreign keys for every table in the schema.
     * Returns a map of tableName → List<ForeignKeyMetadata>.
     */
    private Map<String, List<ForeignKeyMetadata>> fetchAllForeignKeys(String owner) {
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
            String tableName      = rs.getString("TABLE_NAME");
            String constraintName = rs.getString("CONSTRAINT_NAME");
            String localColumn    = rs.getString("LOCAL_COLUMN");
            String refTable       = rs.getString("REF_TABLE");
            String refColumn      = rs.getString("REF_COLUMN");

            ForeignKeyMetadata fk = ForeignKeyMetadata.builder()
                    .constraintName(constraintName)
                    .localColumn(localColumn)
                    .referencedTable(refTable)
                    .referencedColumn(refColumn)
                    .build();

            result.computeIfAbsent(tableName, k -> new ArrayList<>()).add(fk);
        }, owner);

        return result;
    }

    /**
     * Query 5 — all indexes for every table in the schema.
     * Returns a map of tableName → List<IndexMetadata>.
     */
    private Map<String, List<IndexMetadata>> fetchAllIndexes(String owner) {
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

            grouped
                .computeIfAbsent(tableName, k -> new LinkedHashMap<>())
                .computeIfAbsent(indexName, k -> IndexMetadata.builder()
                        .indexName(indexName)
                        .unique("UNIQUE".equals(uniqueness))
                        .columns(new ArrayList<>())
                        .build())
                .getColumns().add(columnName);
        }, owner);

        // Flatten inner map values to a list per table
        Map<String, List<IndexMetadata>> result = new LinkedHashMap<>();
        grouped.forEach((table, indexMap) ->
                result.put(table, new ArrayList<>(indexMap.values())));
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