package com.bank.vam.controller;

import com.bank.vam.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, Object>>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("service", "vam-service");
        health.put("timestamp", LocalDateTime.now());
        return ResponseEntity.ok(ApiResponse.success(health));
    }

    @GetMapping("/info")
    public ResponseEntity<ApiResponse<Map<String, String>>> info() {
        Map<String, String> info = new HashMap<>();
        info.put("name", "Aperture");
        info.put("tagline", "See every flow, every account, every entity");
        info.put("internalCodename", "VAM");
        info.put("version", "1.0.0");
        info.put("description", "Corporate digital banking with virtual account management, multi-bank liquidity, and AI-assisted treasury.");
        return ResponseEntity.ok(ApiResponse.success(info));
    }
}
