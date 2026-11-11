package com.bank.vam.controller;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.service.integration.IntegrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Sync administration. There is no sync job scheduler or queue in this system,
 * so jobs and queue are empty and triggering a job is refused. Logs are the
 * real integration sync log.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/sync")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SyncAdminController {

    private final IntegrationService integrationService;

    @GetMapping("/jobs")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getSyncJobs() {
        return ResponseEntity.ok(ApiResponse.success(List.of()));
    }

    @PostMapping("/jobs/{jobType}/trigger")
    public ResponseEntity<ApiResponse<Map<String, Object>>> triggerSync(@PathVariable String jobType) {
        throw new BusinessException("Not available: " + jobType + " — no sync job runner is implemented");
    }

    @GetMapping("/queue")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getSyncQueue() {
        return ResponseEntity.ok(ApiResponse.success(List.of()));
    }

    /** Counts of the (empty) job list and queue; rates and durations have nothing to be computed from. */
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSyncStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("jobsToday", 0);
        stats.put("successRate", null);
        stats.put("averageDuration", null);
        stats.put("queueDepth", 0);
        stats.put("failedJobs24h", 0);
        stats.put("lastSuccessfulSync", null);
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/logs")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getSyncLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        List<Map<String, Object>> logs = integrationService.getSyncLogs(PageRequest.of(page, size)).stream()
                .map(l -> {
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("timestamp", l.getStartTime());
                    String status = String.valueOf(l.getStatus());
                    entry.put("level", "FAILED".equals(status) ? "ERROR" : "PARTIAL".equals(status) ? "WARN" : "INFO");
                    entry.put("jobType", l.getConnectionName());
                    entry.put("message", status + " — " + l.getRecordsProcessed() + " records");
                    entry.put("details", l.getErrorMessage());
                    return entry;
                })
                .toList();
        return ResponseEntity.ok(ApiResponse.success(logs));
    }
}
