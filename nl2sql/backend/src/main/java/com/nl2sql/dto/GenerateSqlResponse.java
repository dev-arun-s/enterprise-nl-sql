package com.nl2sql.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateSqlResponse {
    private Long historyId;
    private String schemaName;
    private String prompt;
    private String generatedSql;
    private SqlExecutionResult executionResult;  // null if executeImmediately = false
}
