package com.nl2sql.service;

import com.nl2sql.config.Nl2SqlProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * On startup, extracts Oracle metadata for every schema listed under
 * nl2sql.metadata.default-schemas in application.yml.
 *
 * Skips a schema if its JSON file already exists on disk
 * (avoids re-extraction on every restart during development).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataStartupRunner implements ApplicationRunner {

    private final MetadataExtractionService metadataExtractionService;
    private final Nl2SqlProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        List<String> schemas = properties.getDefaultSchemas();
        if (schemas == null || schemas.isEmpty()) {
            log.info("No default schemas configured — skipping startup metadata extraction.");
            return;
        }

        for (String schema : schemas) {
            try {
                if (metadataExtractionService.loadFromFile(schema).isPresent()) {
                    log.info("Metadata file already exists for schema '{}' — skipping extraction.", schema);
                } else {
                    log.info("Extracting metadata for default schema '{}'...", schema);
                    metadataExtractionService.extractAndSave(schema);
                }
            } catch (Exception e) {
                log.error("Failed to extract metadata for schema '{}': {}", schema, e.getMessage());
            }
        }
    }
}
