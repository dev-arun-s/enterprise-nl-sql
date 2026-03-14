package com.nl2sql.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ExecuteSqlRequest {
    @NotBlank(message = "sql must not be blank")
    private String sql;

    private Long historyId;   // optional - links execution to an existing history record
}
