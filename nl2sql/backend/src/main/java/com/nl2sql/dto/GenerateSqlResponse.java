package com.nl2sql.dto;

import com.nl2sql.model.ConversationMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateSqlResponse {
    private Long   historyId;
    private String schemaName;
    private String prompt;
    private String generatedSql;

    // Prompt / table validation result
    private boolean promptValid;
    private String  validationReason;

    // Confidence score (0–100). Null if generation failed.
    private Integer confidenceScore;
    private String  confidenceLabel;   // "High", "Medium", "Low"

    // Updated conversation history to return to the frontend
    private List<ConversationMessage> conversationHistory;
}
