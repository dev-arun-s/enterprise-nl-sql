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
 * Uses the LLM to produce a plain-English explanation of a SQL query.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlExplanationService {

    private final ChatClient chatClient;

    private static final String SYSTEM_PROMPT = """
            You are a database expert who explains SQL queries clearly to non-technical users.
            
            When given a SQL query, produce a concise explanation that:
            - Describes in plain English what data the query retrieves or modifies
            - Explains any JOINs (which tables are being connected and why)
            - Describes filters (WHERE conditions) in plain language
            - Mentions any sorting, grouping, or aggregation and what it achieves
            - Notes any row limits
            - Uses bullet points for clarity
            - Avoids technical jargon where possible; explain it when unavoidable
            - Keeps the explanation under 200 words
            
            Do NOT restate the SQL. Only explain what it does.
            """;

    /**
     * Returns a plain-English explanation of the given SQL query.
     */
    public String explain(String sql) {
        log.info("Requesting LLM explanation for SQL");

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage("Please explain this SQL query:\n\n" + sql)
        ));

        String response = chatClient.prompt(prompt)
                .call()
                .content();

        return response != null ? response.trim() : "Unable to generate explanation.";
    }
}
