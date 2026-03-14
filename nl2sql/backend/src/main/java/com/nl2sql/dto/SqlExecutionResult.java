package com.nl2sql.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SqlExecutionResult {
    private String executedSql;
    private List<String> columns;
    private List<List<Object>> rows;
    private int rowCount;
    private long executionTimeMs;
    private String errorMessage;
}
