package com.nl2sql.controller;

import com.nl2sql.config.Nl2SqlProperties;
import com.nl2sql.dto.SecurityConfigResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Exposes read-only security config to the frontend
 * so the UI can show/hide INSERT and UPDATE options dynamically.
 */
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigController {

    private final Nl2SqlProperties properties;

    @GetMapping("/security")
    public ResponseEntity<SecurityConfigResponse> getSecurityConfig() {
        return ResponseEntity.ok(SecurityConfigResponse.builder()
                .allowSelect(true)
                .allowInsert(properties.isAllowInsert())
                .allowUpdate(properties.isAllowUpdate())
                .allowDelete(false)
                .build());
    }
}
