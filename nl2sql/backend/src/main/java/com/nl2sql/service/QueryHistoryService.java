package com.nl2sql.service;

import com.nl2sql.model.QueryHistory;
import com.nl2sql.repository.QueryHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryHistoryService {

    private final QueryHistoryRepository repository;

    public Optional<QueryHistory> findById(Long id) {
        return repository.findById(id);
    }

    public QueryHistory save(QueryHistory entry) {
        return repository.save(entry);
    }

    public Page<QueryHistory> getHistory(String schemaName, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        if (schemaName != null && !schemaName.isBlank()) {
            return repository.findBySchemaNameOrderByCreatedAtDesc(schemaName.toUpperCase(), pageable);
        }
        return repository.findAllByOrderByCreatedAtDesc(pageable);
    }

    public void delete(Long id) {
        repository.deleteById(id);
    }
}
