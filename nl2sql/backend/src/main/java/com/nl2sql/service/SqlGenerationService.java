package com.nl2sql.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.model.SchemaMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Calls the OpenAI-compatible LLM endpoint via Spring AI to generate
 * an Oracle SQL query from a natural language prompt.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlGenerationService {

    private final ChatClient chatClient;
    private final SemanticTableFilterService semanticFilter;
    private final MetadataExtractionService metadataService;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
            You are an expert Oracle SQL query generator.
            
            You will be given:
            1. A JSON object describing the relevant database tables, their columns (with data types, nullability),
               primary keys, foreign keys, indexes, and comments.
            2. A natural language question from a user.
            
            Your task:
            - Analyse the schema carefully, paying attention to foreign keys for correct JOINs.
            - Generate a single, correct Oracle SQL SELECT query that answers the user's question.
            - Use table aliases for readability.
            - Always qualify column names with aliases when joining tables.
            - Use Oracle SQL syntax (e.g. ROWNUM for row limits if needed).
            - Do NOT use DML statements (INSERT, UPDATE, DELETE, DROP, TRUNCATE, etc.).
            - Return ONLY the SQL query — no explanation, no markdown fences, no extra text.
            """;

    /**
     * Generates an Oracle SQL query for the given prompt and schema.
     *
     * @param naturalLanguagePrompt  The user's question in plain English
     * @param schemaName             The Oracle schema owner
     * @return The generated SQL string
     */
    public String generateSql(String naturalLanguagePrompt, String schemaName) {
        // 1. Load saved metadata
        SchemaMetadata fullSchema = metadataService.loadFromFile(schemaName)
                .orElseThrow(() -> new IllegalStateException(
                        "No metadata found for schema '" + schemaName +
                        "'. Please extract metadata first via POST /api/metadata/extract/" + schemaName));

        // 2. Semantic filter — only relevant tables
        SchemaMetadata filteredSchema = semanticFilter.filterRelevantTables(fullSchema, naturalLanguagePrompt);
        log.info("Using {}/{} tables for schema {}",
                filteredSchema.getTables().size(), fullSchema.getTables().size(), schemaName);

        // 3. Serialise filtered schema to JSON for the prompt
        String schemaJson;
        try {
            schemaJson = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(filteredSchema);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialise schema metadata", e);
        }

        // 4. Build the prompt
        String userContent = String.format("""
                DATABASE SCHEMA (JSON):
                %s
                
                USER QUESTION:
                %s
                """, schemaJson, naturalLanguagePrompt);

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage(userContent)
        ));

        // 5. Call LLM
        log.debug("Sending prompt to LLM for schema {}", schemaName);
        String rawResponse = chatClient.prompt(prompt)
                .call()
                .content();

        // 6. Clean up response (strip accidental markdown fences)
        return cleanSql(rawResponse);
    }

    private String cleanSql(String raw) {
        if (raw == null) return "";
        return raw
                .replaceAll("(?i)```sql", "")
                .replaceAll("```", "")
                .trim();
    }
}
