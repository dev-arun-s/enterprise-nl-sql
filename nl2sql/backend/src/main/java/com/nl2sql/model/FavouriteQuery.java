package com.nl2sql.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FavouriteQuery {
    private String id;           // UUID
    private String schemaName;
    private String title;        // user-supplied label
    private String prompt;       // original NL prompt
    private String sql;          // saved SQL
    private String savedAt;      // ISO datetime
}
