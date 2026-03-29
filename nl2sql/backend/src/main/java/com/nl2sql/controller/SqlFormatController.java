package com.nl2sql.controller;

import com.nl2sql.service.SqlFormatterService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/sql")
@RequiredArgsConstructor
public class SqlFormatController {

    private final SqlFormatterService formatterService;

    @Data
    public static class FormatRequest {
        @NotBlank
        private String sql;
    }

    /**
     * POST /api/sql/format
     * Formats the given SQL string and returns the formatted version.
     */
    @PostMapping("/format")
    public ResponseEntity<Map<String, String>> format(
            @Valid @RequestBody FormatRequest request) {
        String formatted = formatterService.format(request.getSql());
        return ResponseEntity.ok(Map.of("formattedSql", formatted));
    }
}
