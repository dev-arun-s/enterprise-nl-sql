package com.nl2sql.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.model.ConversationMessage;
import com.nl2sql.model.ConversationMessage.Role;
import com.nl2sql.model.SchemaMetadata;
import com.nl2sql.model.TableMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Generates Oracle SQL from a natural language prompt.
 *
 * Enhancements in this version:
 *  1. Multi-turn conversation — previous prompts/SQL are included in the LLM
 *     context so users can refine queries iteratively.
 *  2. Confidence scoring — LLM returns a 0-100 confidence score alongside the
 *     SQL, parsed from a structured response format.
 *  3. Two-layer table validation (sentinel + post-generation regex check).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlGenerationService {

    private final ChatClient chatClient;
    private final SemanticTableFilterService semanticFilter;
    private final MetadataExtractionService metadataService;
    private final ObjectMapper objectMapper;

    private static final String SENTINEL = "NO_RELEVANT_TABLES";

    private static final Pattern TABLE_REF_PATTERN = Pattern.compile(
            "(?:FROM|JOIN)\\s+(?:[A-Za-z_][A-Za-z0-9_]*\\.)?([A-Za-z_][A-Za-z0-9_$#]*)",
            Pattern.CASE_INSENSITIVE
    );

    // Response format the LLM must follow
    private static final String SYSTEM_PROMPT = """
            You are an expert Oracle SQL query generator supporting multi-turn conversations.

            You will be given:
            1. A JSON schema of available tables, columns, PKs, FKs, and comments.
            2. A conversation history of previous user questions and the SQL you generated.
            3. The current user question.

            STRICT RULES:
            - Use ONLY tables listed in the provided schema JSON.
            - If the question cannot be answered using those tables, respond with exactly:
              NO_RELEVANT_TABLES
            - For valid questions, respond with ONLY this exact JSON structure, no other text:
              {
                "sql": "<the complete Oracle SQL query>",
                "confidence": <integer 0-100>,
                "reasoning": "<one sentence explaining your confidence level>"
              }
            - confidence 85-100 = High: all tables/columns found, joins are clear
            - confidence 50-84  = Medium: some assumptions made, column names inferred
            - confidence 0-49   = Low: significant ambiguity or missing context
            - Use Oracle SQL syntax. Qualify columns with aliases. No DML.
            - In multi-turn mode, use context from previous turns to refine the query.
              If the user says "now add X" or "change Y to Z", apply that to the last SQL.
            """;

    public record SqlGenerationResult(
            boolean success,
            String sql,
            String errorMessage,
            Integer confidenceScore,
            String confidenceLabel,
            List<ConversationMessage> updatedHistory
    ) {
        public static SqlGenerationResult ok(String sql, int confidence, String label,
                                              List<ConversationMessage> history) {
            return new SqlGenerationResult(true, sql, null, confidence, label, history);
        }
        public static SqlGenerationResult error(String message) {
            return new SqlGenerationResult(false, null, message, null, null, null);
        }
    }

    public SqlGenerationResult generateSql(String naturalLanguagePrompt,
                                            String schemaName,
                                            List<ConversationMessage> history) {
        // 1. Load metadata
        SchemaMetadata fullSchema = metadataService.loadFromFile(schemaName)
                .orElseThrow(() -> new IllegalStateException(
                        "No metadata found for schema '" + schemaName + "'. Please extract metadata first."));

        Set<String> knownTables = fullSchema.getTables().stream()
                .map(TableMetadata::getTableName)
                .map(String::toUpperCase)
                .collect(Collectors.toSet());

        // 2. Semantic filter
        SchemaMetadata filteredSchema = semanticFilter
                .filterRelevantTables(fullSchema, naturalLanguagePrompt);

        if (filteredSchema.getTables().isEmpty()) {
            return SqlGenerationResult.error(
                    "No relevant tables found in schema '" + schemaName + "' for your question. " +
                    "Try rephrasing using table or column names from the schema.");
        }

        // 3. Serialise schema
        String schemaJson;
        try {
            schemaJson = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(filteredSchema);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialise schema metadata", e);
        }

        // 4. Build message list (system + conversation history + current question)
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));

        // Inject conversation history
        if (history != null) {
            for (ConversationMessage turn : history) {
                if (turn.getRole() == Role.USER) {
                    messages.add(new UserMessage(turn.getContent()));
                } else {
                    messages.add(new AssistantMessage(turn.getContent()));
                }
            }
        }

        // Current turn user message
        String userContent = String.format("""
                DATABASE SCHEMA (JSON) — use ONLY these tables:
                %s

                USER QUESTION:
                %s
                """, schemaJson, naturalLanguagePrompt);
        messages.add(new UserMessage(userContent));

        // 5. Call LLM
        log.debug("Calling LLM | schema={} | turns={}", schemaName,
                history == null ? 0 : history.size());
        String rawResponse = chatClient.prompt(new Prompt(messages)).call().content();
        String cleaned = cleanResponse(rawResponse);

        // 6. Sentinel check
        if (cleaned.equalsIgnoreCase(SENTINEL) || cleaned.toUpperCase().contains(SENTINEL)) {
            return SqlGenerationResult.error(
                    "Could not find relevant tables for your question in schema '" + schemaName + "'. " +
                    "Try rephrasing or check that metadata has been extracted.");
        }

        // 7. Parse structured JSON response
        String sql;
        int confidence;
        String reasoning;
        try {
            sql        = extractJsonString(cleaned, "sql");
            confidence = extractJsonInt(cleaned, "confidence");
            reasoning  = extractJsonString(cleaned, "reasoning");
            if (sql == null || sql.isBlank()) throw new IllegalStateException("Empty SQL in response");
        } catch (Exception e) {
            // Fallback: treat entire response as raw SQL with default confidence
            log.warn("Could not parse structured response, treating as raw SQL: {}", e.getMessage());
            sql        = cleanSql(cleaned);
            confidence = 60;
            reasoning  = "Confidence estimated — response was not in expected format.";
        }

        // 8. Table name validation
        List<String> unknownTables = extractReferencedTables(sql).stream()
                .filter(t -> !knownTables.contains(t.toUpperCase()))
                .distinct()
                .collect(Collectors.toList());

        if (!unknownTables.isEmpty()) {
            log.warn("LLM hallucinated table names: {}", unknownTables);
            return SqlGenerationResult.error(
                    "The generated SQL references tables that do not exist in schema '" +
                    schemaName + "': " + String.join(", ", unknownTables) + ". " +
                    "Please rephrase your question.");
        }

        // 9. Build updated conversation history
        List<ConversationMessage> updatedHistory = new ArrayList<>();
        if (history != null) updatedHistory.addAll(history);
        updatedHistory.add(ConversationMessage.builder().role(Role.USER).content(naturalLanguagePrompt).build());
        updatedHistory.add(ConversationMessage.builder().role(Role.ASSISTANT).content(sql).build());

        String label = confidence >= 85 ? "High" : confidence >= 50 ? "Medium" : "Low";
        log.info("SQL generated | schema={} | confidence={}% ({}) | tables={}", schemaName,
                confidence, label, filteredSchema.getTables().size());

        return SqlGenerationResult.ok(sql, confidence, label, updatedHistory);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<String> extractReferencedTables(String sql) {
        List<String> tables = new ArrayList<>();
        Matcher m = TABLE_REF_PATTERN.matcher(sql);
        while (m.find()) {
            String name = m.group(1).toUpperCase();
            if (!name.equals("DUAL") && !name.equals("ROWNUM")) tables.add(name);
        }
        return tables;
    }

    private String cleanResponse(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("(?i)```json", "").replaceAll("(?i)```sql", "")
                  .replaceAll("```", "").trim();
    }

    private String cleanSql(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("(?i)```sql", "").replaceAll("```", "").trim();
    }

    private String extractJsonString(String json, String key) {
        // Handles both "key": "value" and "key":"value"
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"",
                Pattern.DOTALL);
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1).replace("\\n", "\n").replace("\\\"", "\"") : null;
    }

    private int extractJsonInt(String json, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)");
        Matcher m = p.matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 60;
    }
}
