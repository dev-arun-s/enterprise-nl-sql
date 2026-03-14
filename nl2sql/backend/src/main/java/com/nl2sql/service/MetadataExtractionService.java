package com.nl2sql.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nl2sql.config.Nl2SqlProperties;
import com.nl2sql.model.*;
import lombok.RequiredArgsConstructor;
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
 * Extracts full Oracle schema metadata (tables, columns, PKs, FKs, indexes, comments)
 * and persists it as a JSON file under {storagePath}/{schema}.json
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataExtractionService {

    @Qualifier("oracleJdbcTemplate")
    private final JdbcTemplate oracleJdbcTemplate;

    private final Nl2SqlProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Extracts metadata for the given schema owner and saves to JSON file.
     * Returns the extracted SchemaMetadata.
     */
    public SchemaMetadata extractAndSave(String schemaName) {
        log.info("Starting metadata extraction for schema: {}", schemaName);
        String owner = schemaName.toUpperCase();

        List<TableMetadata> tables = fetchTables(owner);
        tables.forEach(t -> {
            t.setColumns(fetchColumns(owner, t.getTableName()));
            t.setPrimaryKeys(fetchPrimaryKeys(owner, t.getTableName()));
            t.setForeignKeys(fetchForeignKeys(owner, t.getTableName()));
            t.setIndexes(fetchIndexes(owner, t.getTableName()));
        });

        SchemaMetadata schema = SchemaMetadata.builder()
                .schemaName(owner)
                .extractedAt(LocalDateTime.now().toString())
                .tables(tables)
                .build();

        saveToFile(schema);
        log.info("Extraction complete for schema {} — {} tables", owner, tables.size());
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
                    .map(p -> p.getFileName().toString().replace(".json", ""))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.error("Failed to list metadata directory", e);
            return List.of();
        }
    }

    // ── Oracle queries ───────────────────────────────────────────────────────

    private List<TableMetadata> fetchTables(String owner) {
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
                        .build(), owner);
    }

    private List<ColumnMetadata> fetchColumns(String owner, String tableName) {
        String sql = """
                SELECT
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
                WHERE c.OWNER = ? AND c.TABLE_NAME = ?
                ORDER BY c.COLUMN_ID
                """;
        return oracleJdbcTemplate.query(sql, (rs, rowNum) ->
                ColumnMetadata.builder()
                        .columnName(rs.getString("COLUMN_NAME"))
                        .dataType(rs.getString("DATA_TYPE"))
                        .dataLength(rs.getInt("DATA_LENGTH"))
                        .dataPrecision(rs.getObject("DATA_PRECISION") != null ? rs.getInt("DATA_PRECISION") : null)
                        .dataScale(rs.getObject("DATA_SCALE") != null ? rs.getInt("DATA_SCALE") : null)
                        .nullable("Y".equals(rs.getString("NULLABLE")))
                        .defaultValue(rs.getString("DATA_DEFAULT"))
                        .columnOrder(rs.getInt("COLUMN_ID"))
                        .columnComment(rs.getString("COLUMN_COMMENT"))
                        .build(), owner, tableName);
    }

    private List<String> fetchPrimaryKeys(String owner, String tableName) {
        String sql = """
                SELECT cc.COLUMN_NAME
                FROM ALL_CONSTRAINTS con
                JOIN ALL_CONS_COLUMNS cc
                    ON cc.OWNER = con.OWNER
                   AND cc.CONSTRAINT_NAME = con.CONSTRAINT_NAME
                WHERE con.OWNER = ?
                  AND con.TABLE_NAME = ?
                  AND con.CONSTRAINT_TYPE = 'P'
                ORDER BY cc.POSITION
                """;
        return oracleJdbcTemplate.query(sql,
                (rs, rowNum) -> rs.getString("COLUMN_NAME"), owner, tableName);
    }

    private List<ForeignKeyMetadata> fetchForeignKeys(String owner, String tableName) {
        String sql = """
                SELECT
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
                  AND fk.TABLE_NAME = ?
                  AND fk.CONSTRAINT_TYPE = 'R'
                ORDER BY fk.CONSTRAINT_NAME, fkc.POSITION
                """;
        return oracleJdbcTemplate.query(sql, (rs, rowNum) ->
                ForeignKeyMetadata.builder()
                        .constraintName(rs.getString("CONSTRAINT_NAME"))
                        .localColumn(rs.getString("LOCAL_COLUMN"))
                        .referencedTable(rs.getString("REF_TABLE"))
                        .referencedColumn(rs.getString("REF_COLUMN"))
                        .build(), owner, tableName);
    }

    private List<IndexMetadata> fetchIndexes(String owner, String tableName) {
        String sql = """
                SELECT
                    i.INDEX_NAME,
                    i.UNIQUENESS,
                    ic.COLUMN_NAME
                FROM ALL_INDEXES i
                JOIN ALL_IND_COLUMNS ic
                    ON ic.INDEX_OWNER = i.OWNER AND ic.INDEX_NAME = i.INDEX_NAME
                WHERE i.OWNER = ? AND i.TABLE_NAME = ?
                ORDER BY i.INDEX_NAME, ic.COLUMN_POSITION
                """;

        Map<String, IndexMetadata> indexMap = new LinkedHashMap<>();
        oracleJdbcTemplate.query(sql, rs -> {
            String indexName  = rs.getString("INDEX_NAME");
            String uniqueness = rs.getString("UNIQUENESS");
            String columnName = rs.getString("COLUMN_NAME");
            indexMap.computeIfAbsent(indexName, name -> IndexMetadata.builder()
                    .indexName(name)
                    .unique("UNIQUE".equals(uniqueness))
                    .columns(new ArrayList<>())
                    .build())
                    .getColumns().add(columnName);
        }, owner, tableName);

        return new ArrayList<>(indexMap.values());
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