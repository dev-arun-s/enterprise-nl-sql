package com.nl2sql.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Root wrapper for a full schema's metadata.
 * Serialised to  {storagePath}/{schemaName}.json
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SchemaMetadata {
    private String schemaName;
    private String extractedAt;
    private List<TableMetadata> tables;
}
