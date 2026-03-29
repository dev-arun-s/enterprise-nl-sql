package com.nl2sql.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single turn in a multi-turn SQL generation conversation.
 * Stored in the user's session (in-memory, per HTTP session or passed back from frontend).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMessage {
    public enum Role { USER, ASSISTANT }

    private Role   role;
    private String content;   // USER: the NL prompt  |  ASSISTANT: the generated SQL
}
