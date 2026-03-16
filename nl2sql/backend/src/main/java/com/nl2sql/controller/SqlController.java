package com.nl2sql.controller;

import com.nl2sql.dto.*;
import com.nl2sql.model.QueryHistory;
import com.nl2sql.service.PromptValidationService;
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

    private final SqlGenerationService    sqlGenerationService;
    private final SqlExecutionService     sqlExecutionService;
    private final QueryHistoryService     queryHistoryService;
    private final PromptValidationService promptValidationService;

    /**
     * POST /api/sql/generate
     * Validates the prompt first, then generates SQL if valid.
     * Returns promptValid=false (with a reason) without calling the LLM
     * if the prompt is invalid — saving tokens and giving clear user feedback.
     */
    @PostMapping("/generate")
    public ResponseEntity<GenerateSqlResponse> generate(
            @Valid @RequestBody GenerateSqlRequest request) {

        log.info("Generate SQL | schema={} | prompt={}", request.getSchemaName(), request.getPrompt());

        // 1. Validate the prompt
        PromptValidationService.ValidationResult validation =
                promptValidationService.validate(request.getPrompt());

        if (!validation.valid()) {
            log.info("Prompt rejected by validation: {}", validation.reason());
            return ResponseEntity.ok(GenerateSqlResponse.builder()
                    .schemaName(request.getSchemaName())
                    .prompt(request.getPrompt())
                    .promptValid(false)
                    .validationReason(validation.reason())
                    .build());
        }

        // 2. Generate SQL
        SqlGenerationService.SqlGenerationResult generationResult =
                sqlGenerationService.generateSql(request.getPrompt(), request.getSchemaName());

        // 3. If the LLM could not find relevant tables, return a clear error
        if (!generationResult.success()) {
            log.info("SQL generation failed: {}", generationResult.errorMessage());
            return ResponseEntity.ok(GenerateSqlResponse.builder()
                    .schemaName(request.getSchemaName())
                    .prompt(request.getPrompt())
                    .promptValid(false)
                    .validationReason(generationResult.errorMessage())
                    .build());
        }

        // 4. Save to history
        QueryHistory saved = queryHistoryService.save(QueryHistory.builder()
                .schemaName(request.getSchemaName().toUpperCase())
                .naturalLanguagePrompt(request.getPrompt())
                .generatedSql(generationResult.sql())
                .executed(false)
                .build());

        return ResponseEntity.ok(GenerateSqlResponse.builder()
                .historyId(saved.getId())
                .schemaName(request.getSchemaName())
                .prompt(request.getPrompt())
                .generatedSql(generationResult.sql())
                .promptValid(true)
                .validationReason(validation.reason())
                .build());
    }

    /**
     * POST /api/sql/execute
     * Executes a SQL string — always user-triggered.
     */
    @PostMapping("/execute")
    public ResponseEntity<SqlExecutionResult> execute(
            @Valid @RequestBody ExecuteSqlRequest request) {

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

        return ResponseEntity.ok(result);
    }
}
