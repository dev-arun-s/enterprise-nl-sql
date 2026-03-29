package com.nl2sql.dto;

import com.nl2sql.model.ConversationMessage;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class GenerateSqlRequest {

    @NotBlank(message = "prompt must not be blank")
    private String prompt;

    @NotBlank(message = "schemaName must not be blank")
    private String schemaName;

    /**
     * Previous turns in the conversation (sent from frontend).
     * Empty / null = start of a new conversation.
     * Each entry alternates USER → ASSISTANT → USER → ...
     */
    private List<ConversationMessage> conversationHistory;
}
