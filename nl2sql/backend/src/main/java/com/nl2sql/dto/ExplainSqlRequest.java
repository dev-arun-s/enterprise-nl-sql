package com.nl2sql.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ExplainSqlRequest {
    @NotBlank(message = "sql must not be blank")
    private String sql;
}
