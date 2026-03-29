package com.nl2sql.controller;

import com.nl2sql.model.QueryTemplate;
import com.nl2sql.service.QueryTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/templates")
@RequiredArgsConstructor
public class QueryTemplateController {

    private final QueryTemplateService templateService;

    /** GET /api/templates — list all templates */
    @GetMapping
    public ResponseEntity<List<QueryTemplate>> getAll() {
        return ResponseEntity.ok(templateService.getAll());
    }

    /** POST /api/templates/reload — hot-reload from disk without restart */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reload() {
        List<QueryTemplate> reloaded = templateService.reload();
        return ResponseEntity.ok(Map.of(
                "reloaded", true,
                "count", reloaded.size()
        ));
    }
}
