package com.nl2sql.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Uses the LLM to validate whether a user prompt is a meaningful
 * database question before spending tokens on SQL generation.
 *
 * The LLM responds with a strict JSON structure:
 *   { "valid": true/false, "reason": "..." }
 *
 * This catches:
 *  - Gibberish / random characters
 *  - Greetings or non-database questions ("hello", "what is the weather")
 *  - Overly vague prompts ("show me stuff")
 *  - Potentially harmful intent ("drop all tables")
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptValidationService {

    private final ChatClient chatClient;

    private static final String SYSTEM_PROMPT = """
            You are a validation assistant for a natural language to SQL system.
            
            Your job is to decide whether a user's input is a valid, meaningful
            database query question that can be translated into SQL.
            
            A VALID prompt:
            - Asks for data retrieval, aggregation, filtering, or reporting
            - Is specific enough to map to database concepts (tables, records, counts, etc.)
            - Examples: "show all employees", "count orders by region", "find customers with no orders"
            
            An INVALID prompt:
            - Is gibberish, random characters, or completely unrelated to databases
            - Is a greeting or conversational message ("hi", "hello", "thanks")
            - Is dangerously ambiguous ("show me everything", "give me all data")
            - Contains intent to damage data ("delete", "drop", "truncate", "destroy")
            - Is too vague to map to any SQL ("show me stuff", "get things")
            
            Respond with ONLY a JSON object in this exact format, no other text:
            {"valid": true, "reason": "Brief reason"}
            or
            {"valid": false, "reason": "Clear explanation of why it is invalid and what to try instead"}
            """;

    public record ValidationResult(boolean valid, String reason) {}

    /**
     * Validates the prompt using the LLM.
     * Falls back to valid=true if the LLM call fails, so a broken
     * validation service never blocks SQL generation entirely.
     */
    public ValidationResult validate(String prompt) {
        // Fast pre-checks before calling the LLM
        if (prompt == null || prompt.isBlank()) {
            return new ValidationResult(false, "Prompt cannot be empty.");
        }
        String trimmed = prompt.trim();
        if (trimmed.length() < 5) {
            return new ValidationResult(false,
                    "Prompt is too short. Please describe what data you are looking for.");
        }
        if (trimmed.length() > 2000) {
            return new ValidationResult(false,
                    "Prompt is too long (max 2000 characters). Please be more concise.");
        }

        // LLM-assisted validation
        try {
            Prompt llmPrompt = new Prompt(List.of(
                    new SystemMessage(SYSTEM_PROMPT),
                    new UserMessage("Validate this prompt: " + trimmed)
            ));

            String response = chatClient.prompt(llmPrompt).call().content();
            return parseResponse(response, trimmed);

        } catch (Exception e) {
            // Fail open — if validation LLM call fails, don't block the user
            log.warn("Prompt validation LLM call failed, defaulting to valid: {}", e.getMessage());
            return new ValidationResult(true, "Validation service unavailable — proceeding.");
        }
    }

    private ValidationResult parseResponse(String response, String originalPrompt) {
        if (response == null || response.isBlank()) {
            return new ValidationResult(true, "No validation response — proceeding.");
        }

        try {
            // Strip markdown fences if present
            String cleaned = response
                    .replaceAll("(?i)```json", "")
                    .replaceAll("```", "")
                    .trim();

            // Simple JSON parse without extra dependencies
            boolean valid   = cleaned.contains("\"valid\":true") || cleaned.contains("\"valid\": true");
            String  reason  = extractJsonString(cleaned, "reason");

            return new ValidationResult(valid,
                    reason != null ? reason : (valid ? "Prompt looks valid." : "Invalid prompt."));

        } catch (Exception e) {
            log.warn("Failed to parse validation response '{}': {}", response, e.getMessage());
            return new ValidationResult(true, "Could not parse validation — proceeding.");
        }
    }

    /** Extracts a string value from a simple JSON object by key name. */
    private String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int keyIdx = json.indexOf(search);
        if (keyIdx == -1) return null;
        int colonIdx = json.indexOf(':', keyIdx + search.length());
        if (colonIdx == -1) return null;
        int quoteStart = json.indexOf('"', colonIdx + 1);
        if (quoteStart == -1) return null;
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteEnd == -1) return null;
        return json.substring(quoteStart + 1, quoteEnd);
    }
}