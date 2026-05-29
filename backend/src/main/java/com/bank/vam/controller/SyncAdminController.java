package com.bank.vam.controller;

import com.bank.vam.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/admin/sync")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SyncAdminController {

    @GetMapping("/jobs")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getSyncJobs() {
        List<Map<String, Object>> jobs = new ArrayList<>();
        String[] jobTypes = {"BALANCE_SYNC", "TRANSACTION_SYNC", "CORPORATE_SYNC", "ACCOUNT_SYNC"};
        String[] statuses = {"RUNNING", "COMPLETED", "FAILED", "SCHEDULED"};
        
        for (int i = 0; i < 10; i++) {
            Map<String, Object> job = new LinkedHashMap<>();
            job.put("id", UUID.randomUUID());
            job.put("jobType", jobTypes[i % jobTypes.length]);
            job.put("status", statuses[i % statuses.length]);
            job.put("startedAt", LocalDateTime.now().minusMinutes(i * 30));
            job.put("completedAt", statuses[i % 4].equals("COMPLETED") ? LocalDateTime.now().minusMinutes(i * 30 - 5) : null);
            job.put("recordsProcessed", 1000 + i * 500);
            job.put("recordsFailed", i % 3);
            job.put("duration", statuses[i % 4].equals("COMPLETED") ? (5 + i) + " mins" : null);
            jobs.add(job);
        }
        
        return ResponseEntity.ok(ApiResponse.success(jobs));
    }

    @PostMapping("/jobs/{jobType}/trigger")
    public ResponseEntity<ApiResponse<Map<String, Object>>> triggerSync(@PathVariable String jobType) {
        Map<String, Object> result = new HashMap<>();
        result.put("jobId", UUID.randomUUID());
        result.put("jobType", jobType);
        result.put("status", "STARTED");
        result.put("triggeredAt", LocalDateTime.now());
        result.put("triggeredBy", "admin");
        
        log.info("Triggered sync job: {}", jobType);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/queue")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getSyncQueue() {
        List<Map<String, Object>> queue = new ArrayList<>();
        String[] types = {"BANCS_SYNC", "STATEMENT_FETCH", "BALANCE_UPDATE"};
        
        for (int i = 0; i < 8; i++) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", UUID.randomUUID());
            item.put("type", types[i % types.length]);
            item.put("entityId", UUID.randomUUID());
            item.put("priority", i < 3 ? "HIGH" : "NORMAL");
            item.put("retryCount", i % 3);
            item.put("queuedAt", LocalDateTime.now().minusMinutes(i * 5));
            queue.add(item);
        }
        
        return ResponseEntity.ok(ApiResponse.success(queue));
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSyncStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("jobsToday", 48);
        stats.put("successRate", 98.5);
        stats.put("averageDuration", "4.2 mins");
        stats.put("queueDepth", 12);
        stats.put("failedJobs24h", 3);
        stats.put("lastSuccessfulSync", LocalDateTime.now().minusMinutes(15));
        
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/logs")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getSyncLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        
        List<Map<String, Object>> logs = new ArrayList<>();
        String[] levels = {"INFO", "INFO", "WARN", "ERROR"};
        
        for (int i = 0; i < 20; i++) {
            Map<String, Object> logEntry = new HashMap<>();
            logEntry.put("timestamp", LocalDateTime.now().minusMinutes(i));
            logEntry.put("level", levels[i % levels.length]);
            logEntry.put("jobType", "BALANCE_SYNC");
            logEntry.put("message", "Sync " + (levels[i % 4].equals("ERROR") ? "failed" : "completed") + " for batch " + (i + 1));
            logEntry.put("details", levels[i % 4].equals("ERROR") ? "Connection timeout" : null);
            logs.add(logEntry);
        }
        
        return ResponseEntity.ok(ApiResponse.success(logs));
    }
}