package com.nl2sql.repository;

import com.nl2sql.model.QueryHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface QueryHistoryRepository extends JpaRepository<QueryHistory, Long> {
    Page<QueryHistory> findBySchemaNameOrderByCreatedAtDesc(String schemaName, Pageable pageable);
    Page<QueryHistory> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
