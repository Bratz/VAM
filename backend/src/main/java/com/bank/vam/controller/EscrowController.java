package com.bank.vam.controller;

import com.bank.vam.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/escrow")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class EscrowController {

    private final com.bank.vam.config.MarketProfileProperties marketProfile;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getAllEscrowAccounts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        
        // Mock escrow accounts
        List<Map<String, Object>> escrows = new ArrayList<>();
        String[] statuses = {"ACTIVE", "PENDING_RELEASE", "RELEASED", "DISPUTED"};
        
        for (int i = 0; i < 10; i++) {
            Map<String, Object> escrow = new LinkedHashMap<>();
            escrow.put("id", UUID.randomUUID());
            escrow.put("escrowReference", "ESC-2024-" + String.format("%05d", i + 1));
            escrow.put("escrowType", i % 2 == 0 ? "TRADE" : "REAL_ESTATE");
            escrow.put("buyerName", "Buyer Corp " + (i + 1));
            escrow.put("sellerName", "Seller Ltd " + (i + 1));
            escrow.put("amount", BigDecimal.valueOf(100000 + i * 50000));
            escrow.put("currencyCode", marketProfile.getDefaultCurrency());
            escrow.put("status", statuses[i % statuses.length]);
            escrow.put("releaseConditions", "Upon delivery confirmation");
            escrow.put("createdAt", LocalDateTime.now().minusDays(i * 5));
            escrow.put("expiryDate", LocalDate.now().plusMonths(3 - i % 3));
            escrows.add(escrow);
        }
        
        return ResponseEntity.ok(ApiResponse.success(escrows));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getEscrowDetails(@PathVariable UUID id) {
        Map<String, Object> escrow = new LinkedHashMap<>();
        escrow.put("id", id);
        escrow.put("escrowReference", "ESC-2024-00001");
        escrow.put("escrowType", "TRADE");
        escrow.put("buyerId", UUID.randomUUID());
        escrow.put("buyerName", "Buyer Corp");
        escrow.put("sellerId", UUID.randomUUID());
        escrow.put("sellerName", "Seller Ltd");
        escrow.put("amount", BigDecimal.valueOf(250000));
        escrow.put("currencyCode", marketProfile.getDefaultCurrency());
        escrow.put("status", "ACTIVE");
        escrow.put("releaseConditions", Arrays.asList(
            Map.of("condition", "Goods delivered", "status", "PENDING"),
            Map.of("condition", "Quality inspection passed", "status", "PENDING"),
            Map.of("condition", "Documentation complete", "status", "COMPLETED")
        ));
        escrow.put("milestones", Arrays.asList(
            Map.of("name", "Initial deposit", "amount", BigDecimal.valueOf(50000), "status", "COMPLETED"),
            Map.of("name", "Delivery milestone", "amount", BigDecimal.valueOf(100000), "status", "PENDING"),
            Map.of("name", "Final payment", "amount", BigDecimal.valueOf(100000), "status", "PENDING")
        ));
        escrow.put("createdAt", LocalDateTime.now().minusDays(30));
        escrow.put("expiryDate", LocalDate.now().plusMonths(2));
        
        return ResponseEntity.ok(ApiResponse.success(escrow));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> createEscrow(@RequestBody CreateEscrowRequest request) {
        Map<String, Object> escrow = new LinkedHashMap<>();
        escrow.put("id", UUID.randomUUID());
        escrow.put("escrowReference", "ESC-" + LocalDate.now().getYear() + "-" + String.format("%05d", System.currentTimeMillis() % 100000));
        escrow.put("escrowType", request.escrowType());
        escrow.put("buyerId", request.buyerId());
        escrow.put("sellerId", request.sellerId());
        escrow.put("amount", request.amount());
        escrow.put("currencyCode", request.currencyCode());
        escrow.put("status", "PENDING_FUNDING");
        escrow.put("releaseConditions", request.releaseConditions());
        escrow.put("createdAt", LocalDateTime.now());
        escrow.put("expiryDate", request.expiryDate());
        
        log.info("Created escrow: {}", escrow.get("escrowReference"));
        return ResponseEntity.ok(ApiResponse.success(escrow));
    }

    @PostMapping("/{id}/fund")
    public ResponseEntity<ApiResponse<Map<String, Object>>> fundEscrow(
            @PathVariable UUID id,
            @RequestBody FundRequest request) {
        
        Map<String, Object> result = new HashMap<>();
        result.put("escrowId", id);
        result.put("fundedAmount", request.amount());
        result.put("fundingReference", "FND" + System.currentTimeMillis());
        result.put("status", "FUNDED");
        result.put("fundedAt", LocalDateTime.now());
        
        log.info("Funded escrow {} with {}", id, request.amount());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/{id}/release")
    public ResponseEntity<ApiResponse<Map<String, Object>>> releaseEscrow(
            @PathVariable UUID id,
            @RequestBody ReleaseRequest request) {
        
        Map<String, Object> result = new HashMap<>();
        result.put("escrowId", id);
        result.put("releasedAmount", request.amount());
        result.put("releaseReference", "REL" + System.currentTimeMillis());
        result.put("releasedTo", request.releaseTo());
        result.put("status", "RELEASED");
        result.put("releasedAt", LocalDateTime.now());
        result.put("approvedBy", request.approvedBy());
        
        log.info("Released escrow {} - amount {} to {}", id, request.amount(), request.releaseTo());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/{id}/dispute")
    public ResponseEntity<ApiResponse<Map<String, Object>>> disputeEscrow(
            @PathVariable UUID id,
            @RequestBody DisputeRequest request) {
        
        Map<String, Object> result = new HashMap<>();
        result.put("escrowId", id);
        result.put("disputeReference", "DSP" + System.currentTimeMillis());
        result.put("disputeReason", request.reason());
        result.put("raisedBy", request.raisedBy());
        result.put("status", "DISPUTED");
        result.put("disputedAt", LocalDateTime.now());
        
        log.info("Escrow {} disputed: {}", id, request.reason());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getEscrowStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalEscrows", 45);
        stats.put("activeEscrows", 28);
        stats.put("pendingRelease", 8);
        stats.put("disputed", 3);
        stats.put("totalValue", BigDecimal.valueOf(12500000));
        stats.put("releasedThisMonth", BigDecimal.valueOf(3200000));
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/types")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getEscrowTypes() {
        List<Map<String, Object>> types = Arrays.asList(
            Map.of("code", "TRADE", "name", "Trade Escrow", "description", "For goods/services trade"),
            Map.of("code", "REAL_ESTATE", "name", "Real Estate Escrow", "description", "Property transactions"),
            Map.of("code", "M_AND_A", "name", "M&A Escrow", "description", "Mergers and acquisitions"),
            Map.of("code", "MILESTONE", "name", "Milestone Escrow", "description", "Project milestone payments"),
            Map.of("code", "RENT", "name", "Rent Escrow", "description", "Rental deposits and payments")
        );
        return ResponseEntity.ok(ApiResponse.success(types));
    }

    // Request DTOs
    public record CreateEscrowRequest(
        String escrowType,
        UUID buyerId,
        UUID sellerId,
        BigDecimal amount,
        String currencyCode,
        List<String> releaseConditions,
        LocalDate expiryDate
    ) {}

    public record FundRequest(BigDecimal amount, String sourceAccount, String reference) {}
    public record ReleaseRequest(BigDecimal amount, String releaseTo, String approvedBy, String notes) {}
    public record DisputeRequest(String reason, String raisedBy, List<String> evidence) {}
}