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
public class QueryTemplate {
    private String id;
    private String category;   // e.g. "Aggregation", "Date Range", "Top N"
    private String title;
    private String description;
    private String prompt;     // the NL prompt to inject into the editor
    private List<String> tags;
}
