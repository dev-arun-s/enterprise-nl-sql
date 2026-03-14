package com.nl2sql.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GenerateSqlRequest {

    @NotBlank(message = "prompt must not be blank")
    private String prompt;

    @NotBlank(message = "schemaName must not be blank")
    private String schemaName;
}
