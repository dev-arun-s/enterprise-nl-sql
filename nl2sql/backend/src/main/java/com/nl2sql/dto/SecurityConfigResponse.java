package com.nl2sql.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecurityConfigResponse {
    private boolean allowInsert;
    private boolean allowUpdate;
    private boolean allowDelete;   // always false
    private boolean allowSelect;   // always true
}
