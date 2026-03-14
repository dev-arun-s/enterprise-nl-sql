package com.nl2sql.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TableMetadata {
    private String tableName;
    private String tableComment;
    private List<ColumnMetadata> columns;
    private List<String> primaryKeys;
    private List<ForeignKeyMetadata> foreignKeys;
    private List<IndexMetadata> indexes;
}
