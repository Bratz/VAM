package com.bank.vam.controller;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.entity.Corporate;
import com.bank.vam.repository.CorporateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/kyc")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class KycController {

    private final CorporateRepository corporateRepository;

    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getPendingKyc(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        List<Map<String, Object>> pendingKyc = new ArrayList<>();
        String[] types = {"CORPORATE", "INDIVIDUAL", "MERCHANT"};
        String[] riskLevels = {"LOW", "MEDIUM", "HIGH"};
        
        for (int i = 0; i < 12; i++) {
            Map<String, Object> kyc = new LinkedHashMap<>();
            kyc.put("id", UUID.randomUUID());
            kyc.put("applicationRef", "KYC-2024-" + String.format("%05d", i + 1));
            kyc.put("entityType", types[i % types.length]);
            kyc.put("entityName", "Entity " + (i + 1));
            kyc.put("entityId", UUID.randomUUID());
            kyc.put("submittedAt", LocalDateTime.now().minusDays(i + 1));
            kyc.put("riskLevel", riskLevels[i % riskLevels.length]);
            kyc.put("documentsSubmitted", 3 + (i % 3));
            kyc.put("documentsVerified", i % 3);
            kyc.put("assignedTo", i % 2 == 0 ? "Compliance Officer 1" : "Compliance Officer 2");
            kyc.put("status", "PENDING_REVIEW");
            pendingKyc.add(kyc);
        }
        
        return ResponseEntity.ok(ApiResponse.success(pendingKyc));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getKycDetails(@PathVariable UUID id) {
        Map<String, Object> kyc = new LinkedHashMap<>();
        kyc.put("id", id);
        kyc.put("applicationRef", "KYC-2024-00001");
        kyc.put("entityType", "CORPORATE");
        kyc.put("entityName", "ABC Trading LLC");
        kyc.put("entityId", UUID.randomUUID());
        kyc.put("registrationNumber", "LLC-12345");
        kyc.put("incorporationDate", LocalDate.of(2020, 5, 15));
        kyc.put("incorporationCountry", "AE");
        kyc.put("businessActivity", "Import/Export");
        kyc.put("riskLevel", "MEDIUM");
        kyc.put("riskScore", 45);
        kyc.put("status", "PENDING_REVIEW");
        kyc.put("documents", Arrays.asList(
            Map.of("type", "TRADE_LICENSE", "fileName", "trade_license.pdf", "status", "VERIFIED", "uploadedAt", LocalDateTime.now().minusDays(5)),
            Map.of("type", "MOA", "fileName", "memorandum.pdf", "status", "VERIFIED", "uploadedAt", LocalDateTime.now().minusDays(5)),
            Map.of("type", "PASSPORT", "fileName", "passport_owner.pdf", "status", "PENDING", "uploadedAt", LocalDateTime.now().minusDays(3)),
            Map.of("type", "BANK_STATEMENT", "fileName", "bank_stmt.pdf", "status", "PENDING", "uploadedAt", LocalDateTime.now().minusDays(2))
        ));
        kyc.put("beneficialOwners", Arrays.asList(
            Map.of("name", "John Owner", "nationality", "AE", "ownership", 60, "pep", false),
            Map.of("name", "Jane Partner", "nationality", "GB", "ownership", 40, "pep", false)
        ));
        kyc.put("screeningResults", Map.of(
            "sanctionsHit", false,
            "pepHit", false,
            "adverseMedia", false,
            "lastScreenedAt", LocalDateTime.now().minusDays(1)
        ));
        kyc.put("submittedAt", LocalDateTime.now().minusDays(7));
        kyc.put("assignedTo", "Compliance Officer 1");
        
        return ResponseEntity.ok(ApiResponse.success(kyc));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<ApiResponse<Map<String, Object>>> approveKyc(
            @PathVariable UUID id,
            @RequestBody KycDecisionRequest request) {
        
        Map<String, Object> result = new HashMap<>();
        result.put("kycId", id);
        result.put("status", "APPROVED");
        result.put("approvedBy", request.decidedBy());
        result.put("approvedAt", LocalDateTime.now());
        result.put("validUntil", LocalDate.now().plusYears(1));
        result.put("comments", request.comments());
        
        log.info("KYC {} approved by {}", id, request.decidedBy());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rejectKyc(
            @PathVariable UUID id,
            @RequestBody KycDecisionRequest request) {
        
        Map<String, Object> result = new HashMap<>();
        result.put("kycId", id);
        result.put("status", "REJECTED");
        result.put("rejectedBy", request.decidedBy());
        result.put("rejectedAt", LocalDateTime.now());
        result.put("reason", request.reason());
        result.put("comments", request.comments());
        
        log.info("KYC {} rejected by {}: {}", id, request.decidedBy(), request.reason());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/{id}/request-info")
    public ResponseEntity<ApiResponse<Map<String, Object>>> requestAdditionalInfo(
            @PathVariable UUID id,
            @RequestBody RequestInfoRequest request) {
        
        Map<String, Object> result = new HashMap<>();
        result.put("kycId", id);
        result.put("status", "ADDITIONAL_INFO_REQUIRED");
        result.put("requestedDocuments", request.requiredDocuments());
        result.put("requestedBy", request.requestedBy());
        result.put("requestedAt", LocalDateTime.now());
        result.put("deadline", LocalDate.now().plusDays(7));
        
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getKycStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalApplications", 256);
        stats.put("pendingReview", 45);
        stats.put("approved", 180);
        stats.put("rejected", 21);
        stats.put("additionalInfoRequired", 10);
        stats.put("averageProcessingDays", 3.5);
        stats.put("approvalRate", 89.5);
        stats.put("highRiskCount", 15);
        stats.put("expiringThisMonth", 8);
        
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/expiring")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getExpiringKyc(
            @RequestParam(defaultValue = "30") int daysAhead) {
        
        List<Map<String, Object>> expiring = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Map<String, Object> item = new HashMap<>();
            item.put("entityId", UUID.randomUUID());
            item.put("entityName", "Company " + (i + 1));
            item.put("expiryDate", LocalDate.now().plusDays(i * 7 + 5));
            item.put("daysUntilExpiry", i * 7 + 5);
            item.put("lastReviewDate", LocalDate.now().minusYears(1).plusDays(i * 7 + 5));
            expiring.add(item);
        }
        
        return ResponseEntity.ok(ApiResponse.success(expiring));
    }

    // Request DTOs
    public record KycDecisionRequest(String decidedBy, String reason, String comments) {}
    public record RequestInfoRequest(List<String> requiredDocuments, String requestedBy, String message) {}
}