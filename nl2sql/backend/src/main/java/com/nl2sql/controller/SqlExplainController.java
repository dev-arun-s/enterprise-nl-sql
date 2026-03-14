package com.nl2sql.controller;

import com.nl2sql.dto.ExplainSqlRequest;
import com.nl2sql.dto.ExplainSqlResponse;
import com.nl2sql.service.SqlExplanationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sql")
@RequiredArgsConstructor
public class SqlExplainController {

    private final SqlExplanationService explanationService;

    /**
     * POST /api/sql/explain
     * Returns a plain-English explanation of the provided SQL.
     */
    @PostMapping("/explain")
    public ResponseEntity<ExplainSqlResponse> explain(@Valid @RequestBody ExplainSqlRequest request) {
        String explanation = explanationService.explain(request.getSql());
        return ResponseEntity.ok(ExplainSqlResponse.builder()
                .sql(request.getSql())
                .explanation(explanation)
                .build());
    }
}
