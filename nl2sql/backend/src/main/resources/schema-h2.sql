-- H2 in-memory schema for query history
-- Spring Boot will auto-execute this on startup via spring.jpa.hibernate.ddl-auto=create-drop
-- This file documents the schema for reference only.

CREATE TABLE IF NOT EXISTS query_history (
    id                      BIGINT          AUTO_INCREMENT PRIMARY KEY,
    schema_name             VARCHAR(128)    NOT NULL,
    natural_language_prompt VARCHAR(2000)   NOT NULL,
    generated_sql           CLOB            NOT NULL,
    executed                BOOLEAN         NOT NULL DEFAULT FALSE,
    row_count               INT,
    execution_time_ms       BIGINT,
    error_message           VARCHAR(2000),
    created_at              TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_qh_schema    ON query_history (schema_name);
CREATE INDEX IF NOT EXISTS idx_qh_created   ON query_history (created_at DESC);
