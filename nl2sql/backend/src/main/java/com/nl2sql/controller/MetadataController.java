package com.nl2sql.controller;

import com.nl2sql.model.SchemaMetadata;
import com.nl2sql.service.MetadataExtractionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/metadata")
@RequiredArgsConstructor
public class MetadataController {

    private final MetadataExtractionService metadataExtractionService;

    /**
     * Trigger metadata extraction (or re-extraction) for a schema.
     * POST /api/metadata/extract/{schemaName}
     */
    @PostMapping("/extract/{schemaName}")
    public ResponseEntity<Map<String, Object>> extract(@PathVariable String schemaName) {
        log.info("Manual metadata extraction requested for schema: {}", schemaName);
        SchemaMetadata metadata = metadataExtractionService.extractAndSave(schemaName);
        return ResponseEntity.ok(Map.of(
                "schema", metadata.getSchemaName(),
                "tableCount", metadata.getTables().size(),
                "extractedAt", metadata.getExtractedAt()
        ));
    }

    /**
     * Get the saved metadata for a schema.
     * GET /api/metadata/{schemaName}
     */
    @GetMapping("/{schemaName}")
    public ResponseEntity<SchemaMetadata> getMetadata(@PathVariable String schemaName) {
        return metadataExtractionService.loadFromFile(schemaName)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * List all schemas that have a saved metadata file.
     * GET /api/metadata/schemas
     */
    @GetMapping("/schemas")
    public ResponseEntity<List<String>> listSchemas() {
        return ResponseEntity.ok(metadataExtractionService.listAvailableSchemas());
    }
}
