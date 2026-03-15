package com.nl2sql.service;

import com.nl2sql.config.Nl2SqlProperties;
import com.nl2sql.dto.SqlExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Executes SQL against Oracle with configurable DML permissions.
 *
 * Rules:
 *   - SELECT / WITH (CTE)  → always allowed
 *   - INSERT               → allowed only if nl2sql.security.allow-insert=true
 *   - UPDATE               → allowed only if nl2sql.security.allow-update=true
 *   - DELETE / DROP / TRUNCATE / ALTER / etc. → NEVER allowed
 *   - Results capped at nl2sql.metadata.max-result-rows
 */
@Slf4j
@Service
public class SqlExecutionService {

    private final JdbcTemplate oracleJdbcTemplate;
    private final Nl2SqlProperties properties;

    /**
     * Explicit constructor so @Qualifier is honoured.
     * Lombok's @RequiredArgsConstructor does not propagate field-level @Qualifier
     * to constructor parameters, causing Spring to inject the @Primary (H2) template.
     */
    public SqlExecutionService(
            @Qualifier("oracleJdbcTemplate") JdbcTemplate oracleJdbcTemplate,
            Nl2SqlProperties properties) {
        this.oracleJdbcTemplate = oracleJdbcTemplate;
        this.properties = properties;
    }

    public SqlExecutionResult execute(String sql) {
        validatePermissions(sql);

        String upper = sql.trim().toUpperCase();
        boolean isDml = upper.startsWith("INSERT") || upper.startsWith("UPDATE");

        if (isDml) {
            return executeDml(sql);
        } else {
            String limitedSql = applyRowLimit(sql);
            return executeQuery(limitedSql);
        }
    }

    // ── Query (SELECT / WITH) ─────────────────────────────────────────────────

    private SqlExecutionResult executeQuery(String sql) {
        log.info("Executing SELECT: {}", sql);
        long start = System.currentTimeMillis();

        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();

        oracleJdbcTemplate.query(sql, rs -> {
            if (columns.isEmpty()) {
                int colCount = rs.getMetaData().getColumnCount();
                for (int i = 1; i <= colCount; i++) {
                    columns.add(rs.getMetaData().getColumnLabel(i));
                }
            }
            List<Object> row = new ArrayList<>();
            for (int i = 1; i <= columns.size(); i++) {
                Object val = rs.getObject(i);
                row.add(val != null ? val.toString() : null);
            }
            rows.add(row);
        });

        long elapsed = System.currentTimeMillis() - start;
        log.info("Query returned {} rows in {}ms", rows.size(), elapsed);

        return SqlExecutionResult.builder()
                .columns(columns)
                .rows(rows)
                .rowCount(rows.size())
                .executionTimeMs(elapsed)
                .executedSql(sql)
                .build();
    }

    // ── DML (INSERT / UPDATE) ─────────────────────────────────────────────────

    private SqlExecutionResult executeDml(String sql) {
        log.info("Executing DML: {}", sql);
        long start = System.currentTimeMillis();
        int affected = oracleJdbcTemplate.update(sql);
        long elapsed = System.currentTimeMillis() - start;
        log.info("DML affected {} rows in {}ms", affected, elapsed);

        return SqlExecutionResult.builder()
                .columns(List.of("rows_affected"))
                .rows(List.of(List.of(String.valueOf(affected))))
                .rowCount(affected)
                .executionTimeMs(elapsed)
                .executedSql(sql)
                .build();
    }

    // ── Permission validation ─────────────────────────────────────────────────

    private void validatePermissions(String sql) {
        String upper = sql.trim().toUpperCase();

        // DELETE is unconditionally forbidden
        if (startsWithOrContains(upper, "DELETE")) {
            throw new IllegalArgumentException(
                    "DELETE statements are never permitted.");
        }

        // Always-forbidden DDL / DCL
        List<String> alwaysForbidden = List.of(
                "DROP", "TRUNCATE", "ALTER", "CREATE", "GRANT", "REVOKE", "MERGE");
        for (String kw : alwaysForbidden) {
            if (startsWithOrContains(upper, kw)) {
                throw new IllegalArgumentException(
                        "Statement type '" + kw + "' is not permitted.");
            }
        }

        // INSERT — configurable
        if (upper.startsWith("INSERT") && !properties.isAllowInsert()) {
            throw new IllegalArgumentException(
                    "INSERT statements are disabled. Set nl2sql.security.allow-insert=true to enable.");
        }

        // UPDATE — configurable
        if (upper.startsWith("UPDATE") && !properties.isAllowUpdate()) {
            throw new IllegalArgumentException(
                    "UPDATE statements are disabled. Set nl2sql.security.allow-update=true to enable.");
        }

        // Must be SELECT, WITH, INSERT, or UPDATE at this point
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")
                && !upper.startsWith("INSERT") && !upper.startsWith("UPDATE")) {
            throw new IllegalArgumentException(
                    "Only SELECT, WITH, INSERT (if enabled), and UPDATE (if enabled) statements are permitted.");
        }
    }

    private boolean startsWithOrContains(String upper, String keyword) {
        return upper.startsWith(keyword + " ") || upper.startsWith(keyword + "\n")
                || upper.contains(" " + keyword + " ") || upper.contains("\n" + keyword + " ");
    }

    private String applyRowLimit(String sql) {
        int max = properties.getMaxResultRows();
        String upper = sql.toUpperCase();
        if (upper.contains("ROWNUM") || upper.contains("FETCH FIRST")) {
            return sql;
        }
        return sql + "\nFETCH FIRST " + max + " ROWS ONLY";
    }
}