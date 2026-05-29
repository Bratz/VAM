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
@RequestMapping("/api/v1/ecommerce")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class EcommerceController {

    private final com.bank.vam.config.MarketProfileProperties marketProfile;

    // ========== DASHBOARD ==========
    
    @GetMapping("/dashboard/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDashboardStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalMerchants", 156);
        stats.put("activeMerchants", 142);
        stats.put("totalCollections", BigDecimal.valueOf(45678900));
        stats.put("todayCollections", BigDecimal.valueOf(234500));
        stats.put("pendingSettlements", BigDecimal.valueOf(1234000));
        stats.put("successRate", 98.5);
        stats.put("averageTicketSize", BigDecimal.valueOf(450));
        stats.put("transactionsToday", 521);
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/dashboard/trends")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getCollectionTrends(
            @RequestParam(defaultValue = "30") int days) {
        
        List<Map<String, Object>> trends = new ArrayList<>();
        Random random = new Random(42);
        
        for (int i = days; i >= 0; i--) {
            Map<String, Object> point = new HashMap<>();
            point.put("date", LocalDate.now().minusDays(i).toString());
            point.put("collections", BigDecimal.valueOf(150000 + random.nextInt(100000)));
            point.put("transactions", 400 + random.nextInt(200));
            point.put("refunds", BigDecimal.valueOf(random.nextInt(5000)));
            trends.add(point);
        }
        
        return ResponseEntity.ok(ApiResponse.success(trends));
    }

    // ========== MERCHANTS ==========
    
    @GetMapping("/merchants")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getAllMerchants(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        
        List<Map<String, Object>> merchants = new ArrayList<>();
        String[] categories = {"Retail", "F&B", "Electronics", "Fashion", "Services"};
        String[] statuses = {"ACTIVE", "ACTIVE", "ACTIVE", "PENDING", "SUSPENDED"};
        
        for (int i = 0; i < 15; i++) {
            Map<String, Object> merchant = new LinkedHashMap<>();
            merchant.put("id", UUID.randomUUID());
            merchant.put("merchantId", "MER" + String.format("%06d", 100000 + i));
            merchant.put("merchantName", "Merchant Store " + (i + 1));
            merchant.put("tradeName", "Store " + (i + 1));
            merchant.put("category", categories[i % categories.length]);
            merchant.put("mcc", "5411");
            merchant.put("status", statuses[i % statuses.length]);
            merchant.put("settlementAccount", "VA" + String.format("%010d", 2000000 + i));
            merchant.put("commissionRate", BigDecimal.valueOf(1.5 + (i % 3) * 0.5));
            merchant.put("monthlyVolume", BigDecimal.valueOf(50000 + i * 25000));
            merchant.put("onboardedAt", LocalDateTime.now().minusDays(i * 10));
            merchants.add(merchant);
        }
        
        return ResponseEntity.ok(ApiResponse.success(merchants));
    }

    @GetMapping("/merchants/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMerchantDetails(@PathVariable UUID id) {
        Map<String, Object> merchant = new LinkedHashMap<>();
        merchant.put("id", id);
        merchant.put("merchantId", "MER100001");
        merchant.put("merchantName", "Premium Retail Store");
        merchant.put("tradeName", "Premium Store");
        merchant.put("legalName", "Premium Retail LLC");
        merchant.put("registrationNumber", "LLC-12345");
        merchant.put("taxId", "TAX-67890");
        merchant.put("category", "Retail");
        merchant.put("mcc", "5411");
        merchant.put("status", "ACTIVE");
        merchant.put("settlementAccount", "VA2000000001");
        merchant.put("settlementFrequency", "DAILY");
        merchant.put("commissionRate", BigDecimal.valueOf(2.0));
        merchant.put("contactName", "John Merchant");
        merchant.put("contactEmail", "john@premiumstore.com");
        merchant.put("contactPhone", "+971501234567");
        merchant.put("address", "Dubai Mall, Shop 123");
        merchant.put("terminals", Arrays.asList(
            Map.of("terminalId", "TRM001", "type", "POS", "status", "ACTIVE"),
            Map.of("terminalId", "TRM002", "type", "ECOM", "status", "ACTIVE")
        ));
        merchant.put("monthlyStats", Map.of(
            "volume", BigDecimal.valueOf(450000),
            "transactions", 2340,
            "avgTicket", BigDecimal.valueOf(192),
            "chargebacks", 3
        ));
        merchant.put("onboardedAt", LocalDateTime.now().minusMonths(6));
        
        return ResponseEntity.ok(ApiResponse.success(merchant));
    }

    @PostMapping("/merchants")
    public ResponseEntity<ApiResponse<Map<String, Object>>> onboardMerchant(@RequestBody MerchantOnboardRequest request) {
        Map<String, Object> merchant = new LinkedHashMap<>();
        merchant.put("id", UUID.randomUUID());
        merchant.put("merchantId", "MER" + System.currentTimeMillis() % 1000000);
        merchant.put("merchantName", request.merchantName());
        merchant.put("status", "PENDING_APPROVAL");
        merchant.put("createdAt", LocalDateTime.now());
        merchant.put("message", "Merchant application submitted for review");
        
        log.info("Merchant onboarding initiated: {}", request.merchantName());
        return ResponseEntity.ok(ApiResponse.success(merchant));
    }

    @PostMapping("/merchants/{id}/approve")
    public ResponseEntity<ApiResponse<Map<String, Object>>> approveMerchant(@PathVariable UUID id) {
        Map<String, Object> result = new HashMap<>();
        result.put("merchantId", id);
        result.put("status", "ACTIVE");
        result.put("approvedAt", LocalDateTime.now());
        result.put("message", "Merchant approved and activated");
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ========== COLLECTIONS ==========
    
    @GetMapping("/collections")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getCollections(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) UUID merchantId,
            @RequestParam(required = false) String status) {
        
        List<Map<String, Object>> collections = new ArrayList<>();
        String[] paymentMethods = {"CARD", "WALLET", "BANK_TRANSFER", "CASH"};
        String[] statuses = {"COMPLETED", "COMPLETED", "PENDING", "FAILED"};
        
        for (int i = 0; i < 20; i++) {
            Map<String, Object> collection = new LinkedHashMap<>();
            collection.put("id", UUID.randomUUID());
            collection.put("transactionRef", "TXN" + String.format("%012d", System.currentTimeMillis() - i * 1000));
            collection.put("merchantId", "MER" + String.format("%06d", 100000 + (i % 10)));
            collection.put("merchantName", "Merchant " + ((i % 10) + 1));
            collection.put("amount", BigDecimal.valueOf(100 + i * 50));
            collection.put("currencyCode", marketProfile.getDefaultCurrency());
            collection.put("paymentMethod", paymentMethods[i % paymentMethods.length]);
            collection.put("status", statuses[i % statuses.length]);
            collection.put("customerRef", "CUST" + String.format("%06d", i + 1));
            collection.put("terminalId", "TRM" + String.format("%03d", (i % 5) + 1));
            collection.put("transactionDate", LocalDateTime.now().minusHours(i));
            collections.add(collection);
        }
        
        return ResponseEntity.ok(ApiResponse.success(collections));
    }

    @GetMapping("/collections/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCollectionDetails(@PathVariable UUID id) {
        Map<String, Object> collection = new LinkedHashMap<>();
        collection.put("id", id);
        collection.put("transactionRef", "TXN202412050001");
        collection.put("merchantId", "MER100001");
        collection.put("merchantName", "Premium Store");
        collection.put("amount", BigDecimal.valueOf(1250.00));
        collection.put("currencyCode", marketProfile.getDefaultCurrency());
        collection.put("paymentMethod", "CARD");
        collection.put("cardType", "VISA");
        collection.put("cardLastFour", "4242");
        collection.put("status", "COMPLETED");
        collection.put("authCode", "AUTH123456");
        collection.put("rrn", "RRN789012");
        collection.put("settlementStatus", "SETTLED");
        collection.put("settlementDate", LocalDate.now());
        collection.put("commissionAmount", BigDecimal.valueOf(25.00));
        collection.put("netAmount", BigDecimal.valueOf(1225.00));
        collection.put("transactionDate", LocalDateTime.now().minusHours(2));
        
        return ResponseEntity.ok(ApiResponse.success(collection));
    }

    // ========== SETTLEMENTS ==========
    
    @GetMapping("/settlements")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getSettlements(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        List<Map<String, Object>> settlements = new ArrayList<>();
        
        for (int i = 0; i < 10; i++) {
            Map<String, Object> settlement = new LinkedHashMap<>();
            settlement.put("id", UUID.randomUUID());
            settlement.put("settlementRef", "SET" + String.format("%08d", 20241205 * 100 + i));
            settlement.put("merchantId", "MER" + String.format("%06d", 100000 + i));
            settlement.put("merchantName", "Merchant " + (i + 1));
            settlement.put("grossAmount", BigDecimal.valueOf(50000 + i * 10000));
            settlement.put("commission", BigDecimal.valueOf(1000 + i * 200));
            settlement.put("netAmount", BigDecimal.valueOf(49000 + i * 9800));
            settlement.put("transactionCount", 50 + i * 10);
            settlement.put("settlementDate", LocalDate.now().minusDays(i));
            settlement.put("status", i < 3 ? "PENDING" : "COMPLETED");
            settlements.add(settlement);
        }
        
        return ResponseEntity.ok(ApiResponse.success(settlements));
    }

    @PostMapping("/settlements/process")
    public ResponseEntity<ApiResponse<Map<String, Object>>> processSettlements() {
        Map<String, Object> result = new HashMap<>();
        result.put("processedCount", 12);
        result.put("totalAmount", BigDecimal.valueOf(1234567));
        result.put("batchReference", "BATCH" + LocalDate.now().toString().replace("-", ""));
        result.put("processedAt", LocalDateTime.now());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // Request DTOs
    public record MerchantOnboardRequest(
        String merchantName,
        String tradeName,
        String legalName,
        String registrationNumber,
        String category,
        String mcc,
        String contactName,
        String contactEmail,
        String contactPhone,
        String settlementAccount,
        BigDecimal commissionRate
    ) {}
}