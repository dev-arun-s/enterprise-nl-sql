package com.nl2sql.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.model.SchemaMetadata;
import com.nl2sql.model.TableMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Generates Oracle SQL from a natural language prompt.
 *
 * Two-layer table validation:
 *
 *  Layer 1 — LLM sentinel signal
 *    The system prompt instructs the LLM to respond with a special sentinel
 *    string "NO_RELEVANT_TABLES" if it cannot map the question to any table in
 *    the provided schema JSON. This is caught before any SQL is returned.
 *
 *  Layer 2 — Post-generation table name verification
 *    After a SQL string is received, every table name referenced in FROM and
 *    JOIN clauses is extracted and checked against the full schema's known table
 *    names. Any unrecognised table names are reported as a clear error.
 *    This catches hallucinated table names even when the LLM ignores the sentinel.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlGenerationService {

    private final ChatClient chatClient;
    private final SemanticTableFilterService semanticFilter;
    private final MetadataExtractionService metadataService;
    private final ObjectMapper objectMapper;

    /** Sentinel the LLM returns when it cannot find relevant tables */
    private static final String SENTINEL = "NO_RELEVANT_TABLES";

    /**
     * Regex to extract table names from FROM and JOIN clauses.
     * Handles:
     *   FROM   table_name alias
     *   FROM   schema.table_name alias
     *   JOIN   table_name alias
     *   LEFT/RIGHT/INNER/OUTER/FULL/CROSS JOIN table_name alias
     *
     * Captures the bare table name (group 1), ignoring schema prefix and alias.
     */
    private static final Pattern TABLE_REF_PATTERN = Pattern.compile(
            "(?:FROM|JOIN)\\s+(?:[A-Za-z_][A-Za-z0-9_]*\\.)?([A-Za-z_][A-Za-z0-9_$#]*)",
            Pattern.CASE_INSENSITIVE
    );

    private static final String SYSTEM_PROMPT = """
            You are an expert Oracle SQL query generator.
            
            You will be given:
            1. A JSON object listing the ONLY tables available in this schema,
               with their columns, data types, primary keys, foreign keys and comments.
            2. A natural language question from a user.
            
            STRICT RULES — you MUST follow all of them:
            - Use ONLY the tables listed in the provided JSON. Never reference any
              table that does not appear in the JSON, even if you think it might exist.
            - If the user's question cannot be answered using the provided tables,
              respond with exactly the single word: NO_RELEVANT_TABLES
              Do not add any other text, explanation, or SQL.
            - If the question can be answered, generate a single correct Oracle SQL
              SELECT query. Use table aliases. Qualify columns when joining.
            - Use Oracle SQL syntax (FETCH FIRST n ROWS ONLY for limits, etc.).
            - Do NOT use DML statements (INSERT, UPDATE, DELETE, DROP, TRUNCATE, etc.).
            - Return ONLY the raw SQL query or the sentinel NO_RELEVANT_TABLES.
              No markdown fences, no explanation, no extra text.
            """;

    /**
     * Result wrapper returned by generateSql.
     * If success=false, sql is null and errorMessage explains the problem.
     */
    public record SqlGenerationResult(
            boolean success,
            String sql,
            String errorMessage
    ) {
        public static SqlGenerationResult ok(String sql) {
            return new SqlGenerationResult(true, sql, null);
        }
        public static SqlGenerationResult error(String message) {
            return new SqlGenerationResult(false, null, message);
        }
    }

    /**
     * Generates an Oracle SQL query for the given prompt and schema.
     * Returns a SqlGenerationResult — always check success() before using sql().
     */
    public SqlGenerationResult generateSql(String naturalLanguagePrompt, String schemaName) {

        // 1. Load saved metadata
        SchemaMetadata fullSchema = metadataService.loadFromFile(schemaName)
                .orElseThrow(() -> new IllegalStateException(
                        "No metadata found for schema '" + schemaName +
                        "'. Please extract metadata first."));

        // Build set of known table names for validation (upper-cased for comparison)
        Set<String> knownTables = fullSchema.getTables().stream()
                .map(TableMetadata::getTableName)
                .map(String::toUpperCase)
                .collect(Collectors.toSet());

        // 2. Semantic filter — select most relevant tables for the prompt
        SchemaMetadata filteredSchema = semanticFilter
                .filterRelevantTables(fullSchema, naturalLanguagePrompt);

        log.info("Using {}/{} tables for schema {}",
                filteredSchema.getTables().size(), fullSchema.getTables().size(), schemaName);

        // If semantic filter returned zero tables, short-circuit before the LLM call
        if (filteredSchema.getTables().isEmpty()) {
            return SqlGenerationResult.error(
                    "No relevant tables found in schema '" + schemaName + "' for your question. " +
                    "Try rephrasing with table or column names from the schema, " +
                    "or check that metadata has been extracted.");
        }

        // 3. Serialise filtered schema to JSON for the prompt
        String schemaJson;
        try {
            schemaJson = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(filteredSchema);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialise schema metadata", e);
        }

        // 4. Build prompt
        String userContent = String.format("""
                DATABASE SCHEMA (JSON) — use ONLY these tables:
                %s
                
                USER QUESTION:
                %s
                """, schemaJson, naturalLanguagePrompt);

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage(userContent)
        ));

        // 5. Call LLM
        log.debug("Calling LLM for schema={} prompt={}", schemaName, naturalLanguagePrompt);
        String rawResponse = chatClient.prompt(prompt).call().content();

        // 6. Clean up markdown fences
        String sql = cleanSql(rawResponse);

        // 7. Layer 1 — check for sentinel signal
        if (sql.equalsIgnoreCase(SENTINEL) || sql.toUpperCase().contains(SENTINEL)) {
            log.info("LLM returned NO_RELEVANT_TABLES sentinel for prompt: {}", naturalLanguagePrompt);
            return SqlGenerationResult.error(
                    "Could not find relevant tables for your question in schema '" + schemaName + "'. " +
                    "The available tables do not appear to contain the data you are asking about. " +
                    "Try rephrasing, or check that metadata has been extracted and is up to date.");
        }

        // 8. Layer 2 — verify every table referenced in the SQL exists in the schema
        List<String> unknownTables = extractReferencedTables(sql).stream()
                .filter(t -> !knownTables.contains(t.toUpperCase()))
                .distinct()
                .collect(Collectors.toList());

        if (!unknownTables.isEmpty()) {
            log.warn("LLM hallucinated table names: {} — schema={}", unknownTables, schemaName);
            return SqlGenerationResult.error(
                    "The generated SQL references table(s) that do not exist in schema '" +
                    schemaName + "': " + String.join(", ", unknownTables) + ". " +
                    "Please rephrase your question using the actual table or column names.");
        }

        log.info("SQL generated and validated successfully for schema={}", schemaName);
        return SqlGenerationResult.ok(sql);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Extracts all table names referenced after FROM and JOIN keywords.
     * Returns them in upper-case.
     */
    private List<String> extractReferencedTables(String sql) {
        List<String> tables = new ArrayList<>();
        Matcher m = TABLE_REF_PATTERN.matcher(sql);
        while (m.find()) {
            String name = m.group(1).toUpperCase();
            // Skip Oracle built-in pseudo-tables / sub-query aliases
            if (!name.equals("DUAL") && !name.equals("ROWNUM")) {
                tables.add(name);
            }
        }
        return tables;
    }

    private String cleanSql(String raw) {
        if (raw == null) return "";
        return raw
                .replaceAll("(?i)```sql", "")
                .replaceAll("```", "")
                .trim();
    }
}
