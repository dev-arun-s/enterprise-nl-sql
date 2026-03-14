package com.nl2sql.controller;

import com.nl2sql.dto.*;
import com.nl2sql.model.QueryHistory;
import com.nl2sql.service.QueryHistoryService;
import com.nl2sql.service.SqlExecutionService;
import com.nl2sql.service.SqlGenerationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/sql")
@RequiredArgsConstructor
public class SqlController {

    private final SqlGenerationService sqlGenerationService;
    private final SqlExecutionService sqlExecutionService;
    private final QueryHistoryService queryHistoryService;

    /**
     * POST /api/sql/generate
     * Generates SQL from a natural language prompt — does NOT execute it.
     */
    @PostMapping("/generate")
    public ResponseEntity<GenerateSqlResponse> generate(@Valid @RequestBody GenerateSqlRequest request) {
        log.info("Generate SQL | schema={} | prompt={}", request.getSchemaName(), request.getPrompt());

        String generatedSql = sqlGenerationService.generateSql(
                request.getPrompt(), request.getSchemaName());

        QueryHistory history = QueryHistory.builder()
                .schemaName(request.getSchemaName().toUpperCase())
                .naturalLanguagePrompt(request.getPrompt())
                .generatedSql(generatedSql)
                .executed(false)
                .build();

        QueryHistory saved = queryHistoryService.save(history);

        return ResponseEntity.ok(GenerateSqlResponse.builder()
                .historyId(saved.getId())
                .schemaName(request.getSchemaName())
                .prompt(request.getPrompt())
                .generatedSql(generatedSql)
                .build());
    }

    /**
     * POST /api/sql/execute
     * Executes a SQL string (always user-triggered, never automatic).
     */
    @PostMapping("/execute")
    public ResponseEntity<SqlExecutionResult> execute(@Valid @RequestBody ExecuteSqlRequest request) {
        log.info("Execute SQL | historyId={}", request.getHistoryId());

        SqlExecutionResult result;
        try {
            result = sqlExecutionService.execute(request.getSql());
        } catch (Exception e) {
            log.error("SQL execution error", e);
            result = SqlExecutionResult.builder()
                    .executedSql(request.getSql())
                    .errorMessage(e.getMessage())
                    .build();
        }

        // Capture in a final variable so it can be referenced inside the lambda
        final SqlExecutionResult finalResult = result;

        if (request.getHistoryId() != null) {
            queryHistoryService.findById(request.getHistoryId()).ifPresent(h -> {
                h.setExecuted(true);
                h.setRowCount(finalResult.getRowCount());
                h.setExecutionTimeMs(finalResult.getExecutionTimeMs());
                h.setErrorMessage(finalResult.getErrorMessage());
                queryHistoryService.save(h);
            });
        }

        return ResponseEntity.ok(finalResult);
    }
}