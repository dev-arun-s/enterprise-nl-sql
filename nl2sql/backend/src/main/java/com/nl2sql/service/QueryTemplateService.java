package com.nl2sql.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.config.Nl2SqlProperties;
import com.nl2sql.model.QueryTemplate;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Loads query templates from a JSON file.
 *
 * Resolution order:
 *  1. External file at nl2sql.metadata.templates-file (if exists) — editable without restart
 *  2. Classpath fallback: templates/query-templates.json bundled in the JAR
 *
 * Hot-reload: GET /api/templates/reload re-reads the file without restart.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryTemplateService {

    private final Nl2SqlProperties properties;
    private final ObjectMapper objectMapper;

    private List<QueryTemplate> templates = List.of();

    @PostConstruct
    public void init() {
        reload();
    }

    public List<QueryTemplate> getAll() {
        return templates;
    }

    public List<QueryTemplate> reload() {
        templates = loadFromExternalFile()
                .or(this::loadFromClasspath)
                .orElse(List.of());
        log.info("Loaded {} query templates", templates.size());
        return templates;
    }

    private java.util.Optional<List<QueryTemplate>> loadFromExternalFile() {
        String path = properties.getTemplatesFile();
        if (path == null || path.isBlank()) return java.util.Optional.empty();
        Path filePath = Paths.get(path);
        if (!Files.exists(filePath)) return java.util.Optional.empty();
        try {
            List<QueryTemplate> loaded = objectMapper.readValue(
                    filePath.toFile(), new TypeReference<>() {});
            log.info("Loaded templates from external file: {}", filePath.toAbsolutePath());
            return java.util.Optional.of(loaded);
        } catch (IOException e) {
            log.error("Failed to load templates from {}: {}", filePath, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private java.util.Optional<List<QueryTemplate>> loadFromClasspath() {
        try {
            ClassPathResource resource = new ClassPathResource("templates/query-templates.json");
            if (!resource.exists()) return java.util.Optional.empty();
            try (InputStream is = resource.getInputStream()) {
                List<QueryTemplate> loaded = objectMapper.readValue(is, new TypeReference<>() {});
                log.info("Loaded templates from classpath fallback");
                return java.util.Optional.of(loaded);
            }
        } catch (IOException e) {
            log.error("Failed to load templates from classpath: {}", e.getMessage());
            return java.util.Optional.empty();
        }
    }
}
