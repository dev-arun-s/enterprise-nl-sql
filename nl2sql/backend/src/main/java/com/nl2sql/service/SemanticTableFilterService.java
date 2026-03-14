package com.nl2sql.service;

import com.nl2sql.model.ColumnMetadata;
import com.nl2sql.model.ForeignKeyMetadata;
import com.nl2sql.model.SchemaMetadata;
import com.nl2sql.model.TableMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Selects a subset of tables most relevant to the user's prompt.
 *
 * Strategy (no embeddings required):
 *   1. Tokenise the prompt into lowercase words (stop-words removed).
 *   2. Score each table by how many tokens appear in its name, comment, or column names/comments.
 *   3. Return the top-N tables, then expand the set by one hop of FK relationships
 *      so that JOIN targets are always included.
 */
@Slf4j
@Service
public class SemanticTableFilterService {

    private static final int TOP_N = 10;

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "shall",
            "should", "may", "might", "must", "can", "could",
            "i", "me", "my", "we", "our", "you", "your",
            "it", "its", "this", "that", "these", "those",
            "of", "in", "to", "for", "on", "with", "at", "by", "from",
            "and", "or", "but", "not", "so", "as",
            "all", "any", "some", "no", "get", "give", "show",
            "list", "find", "fetch", "select", "query", "return"
    );

    /**
     * Returns a filtered SchemaMetadata containing only the tables relevant to the prompt.
     */
    public SchemaMetadata filterRelevantTables(SchemaMetadata fullSchema, String userPrompt) {
        if (fullSchema.getTables() == null || fullSchema.getTables().isEmpty()) {
            return fullSchema;
        }

        Set<String> tokens = tokenise(userPrompt);
        log.debug("Prompt tokens: {}", tokens);

        // Score each table
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (TableMetadata table : fullSchema.getTables()) {
            int score = scoreTable(table, tokens);
            if (score > 0) {
                scores.put(table.getTableName(), score);
            }
        }

        // Take top-N by score
        Set<String> selectedNames = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(TOP_N)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Expand by one FK hop
        Set<String> expanded = expandByForeignKeys(fullSchema, selectedNames);
        log.info("Semantic filter: {} → {} tables selected (after FK expansion)",
                fullSchema.getTables().size(), expanded.size());

        // Build filtered schema
        List<TableMetadata> filteredTables = fullSchema.getTables().stream()
                .filter(t -> expanded.contains(t.getTableName()))
                .collect(Collectors.toList());

        return SchemaMetadata.builder()
                .schemaName(fullSchema.getSchemaName())
                .extractedAt(fullSchema.getExtractedAt())
                .tables(filteredTables)
                .build();
    }

    // ── Scoring ──────────────────────────────────────────────────────────────

    private int scoreTable(TableMetadata table, Set<String> tokens) {
        int score = 0;
        String tableLower = table.getTableName().toLowerCase();

        for (String token : tokens) {
            // Table name exact word match scores higher
            if (tableLower.equals(token) || tableLower.contains(token)) score += 10;

            // Table comment match
            if (table.getTableComment() != null &&
                    table.getTableComment().toLowerCase().contains(token)) score += 5;

            // Column name match
            if (table.getColumns() != null) {
                for (ColumnMetadata col : table.getColumns()) {
                    if (col.getColumnName().toLowerCase().contains(token)) score += 3;
                    if (col.getColumnComment() != null &&
                            col.getColumnComment().toLowerCase().contains(token)) score += 2;
                }
            }
        }
        return score;
    }

    /** Expand selected tables by including FK-referenced tables (one hop). */
    private Set<String> expandByForeignKeys(SchemaMetadata schema, Set<String> selected) {
        Set<String> expanded = new HashSet<>(selected);
        Map<String, TableMetadata> tableMap = schema.getTables().stream()
                .collect(Collectors.toMap(TableMetadata::getTableName, t -> t));

        for (String tableName : new HashSet<>(selected)) {
            TableMetadata table = tableMap.get(tableName);
            if (table == null || table.getForeignKeys() == null) continue;
            for (ForeignKeyMetadata fk : table.getForeignKeys()) {
                expanded.add(fk.getReferencedTable());
            }
        }
        return expanded;
    }

    // ── Tokeniser ────────────────────────────────────────────────────────────

    private Set<String> tokenise(String text) {
        return Arrays.stream(text.toLowerCase().split("[^a-z0-9_]+"))
                .filter(t -> t.length() > 2)
                .filter(t -> !STOP_WORDS.contains(t))
                .collect(Collectors.toSet());
    }
}
