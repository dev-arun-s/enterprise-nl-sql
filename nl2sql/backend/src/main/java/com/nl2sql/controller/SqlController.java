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
     * Validates prompt → generates SQL with confidence score → returns updated conversation history.
     */
    @PostMapping("/generate")
    public ResponseEntity<GenerateSqlResponse> generate(
            @Valid @RequestBody GenerateSqlRequest request) {

        log.info("Generate SQL | schema={} | conversationTurns={} | prompt={}",
                request.getSchemaName(),
                request.getConversationHistory() == null ? 0 : request.getConversationHistory().size(),
                request.getPrompt());

        // 1. Validate prompt
        PromptValidationService.ValidationResult validation =
                promptValidationService.validate(request.getPrompt());

        if (!validation.valid()) {
            return ResponseEntity.ok(GenerateSqlResponse.builder()
                    .schemaName(request.getSchemaName())
                    .prompt(request.getPrompt())
                    .promptValid(false)
                    .validationReason(validation.reason())
                    .build());
        }

        // 2. Generate SQL (with multi-turn context + confidence)
        SqlGenerationService.SqlGenerationResult result = sqlGenerationService.generateSql(
                request.getPrompt(),
                request.getSchemaName(),
                request.getConversationHistory()
        );

        if (!result.success()) {
            return ResponseEntity.ok(GenerateSqlResponse.builder()
                    .schemaName(request.getSchemaName())
                    .prompt(request.getPrompt())
                    .promptValid(false)
                    .validationReason(result.errorMessage())
                    .build());
        }

        // 3. Save to history
        QueryHistory saved = queryHistoryService.save(QueryHistory.builder()
                .schemaName(request.getSchemaName().toUpperCase())
                .naturalLanguagePrompt(request.getPrompt())
                .generatedSql(result.sql())
                .executed(false)
                .build());

        return ResponseEntity.ok(GenerateSqlResponse.builder()
                .historyId(saved.getId())
                .schemaName(request.getSchemaName())
                .prompt(request.getPrompt())
                .generatedSql(result.sql())
                .promptValid(true)
                .validationReason(validation.reason())
                .confidenceScore(result.confidenceScore())
                .confidenceLabel(result.confidenceLabel())
                .conversationHistory(result.updatedHistory())
                .build());
    }

    /**
     * POST /api/sql/execute
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
