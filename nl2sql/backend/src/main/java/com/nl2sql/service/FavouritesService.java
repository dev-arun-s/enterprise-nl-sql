package com.nl2sql.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nl2sql.config.Nl2SqlProperties;
import com.nl2sql.model.FavouriteQuery;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persists favourite queries to a JSON file on disk.
 * Thread-safe via synchronized blocks on the list.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FavouritesService {

    private final Nl2SqlProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private List<FavouriteQuery> cache = new ArrayList<>();

    @PostConstruct
    public void init() {
        load();
    }

    public synchronized List<FavouriteQuery> getAll() {
        return new ArrayList<>(cache);
    }

    public synchronized FavouriteQuery add(FavouriteQuery favourite) {
        favourite.setId(UUID.randomUUID().toString());
        favourite.setSavedAt(LocalDateTime.now().toString());
        cache.add(favourite);
        persist();
        return favourite;
    }

    public synchronized boolean delete(String id) {
        boolean removed = cache.removeIf(f -> f.getId().equals(id));
        if (removed) persist();
        return removed;
    }

    public synchronized boolean exists(String id) {
        return cache.stream().anyMatch(f -> f.getId().equals(id));
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    private void load() {
        Path path = getPath();
        if (!Files.exists(path)) {
            log.info("No favourites file found at {} — starting empty", path);
            return;
        }
        try {
            cache = objectMapper.readValue(path.toFile(), new TypeReference<>() {});
            log.info("Loaded {} favourites from disk", cache.size());
        } catch (IOException e) {
            log.error("Failed to load favourites file", e);
            cache = new ArrayList<>();
        }
    }

    private void persist() {
        try {
            Path path = getPath();
            Files.createDirectories(path.getParent());
            objectMapper.writeValue(path.toFile(), cache);
        } catch (IOException e) {
            log.error("Failed to persist favourites", e);
        }
    }

    private Path getPath() {
        return Paths.get(properties.getFavouritesFile());
    }
}
