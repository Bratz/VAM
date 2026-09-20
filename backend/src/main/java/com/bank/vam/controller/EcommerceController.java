package com.bank.vam.controller;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.service.ecommerce.EcommerceService;
import com.bank.vam.service.ecommerce.EcommerceService.CollectionAccountResponse;
import com.bank.vam.service.ecommerce.EcommerceService.CollectionResponse;
import com.bank.vam.service.ecommerce.EcommerceService.DashboardStatsResponse;
import com.bank.vam.service.ecommerce.EcommerceService.MerchantResponse;
import com.bank.vam.service.ecommerce.EcommerceService.SettlementResponse;
import com.bank.vam.service.ecommerce.EcommerceService.TrendPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * E-commerce collections, served from the movements the platform actually
 * records on COLLECTION-type program accounts. Nothing here is generated.
 *
 * Merchant registration is deliberately absent: va_movements carries the
 * acquiring columns (merchant_id, merchant_name, terminal_id, …) but there is
 * no merchant entity, table or feed behind them, so onboarding and approval
 * have nowhere to write. Those two endpoints answer 501 rather than report a
 * success that never happened.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ecommerce")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class EcommerceController {

    private final EcommerceService ecommerceService;

    // ========== DASHBOARD ==========

    @GetMapping("/dashboard/stats")
    public ResponseEntity<ApiResponse<DashboardStatsResponse>> getDashboardStats(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId) {
        return ResponseEntity.ok(ApiResponse.success(ecommerceService.getDashboardStats(corporateId)));
    }

    @GetMapping("/dashboard/trends")
    public ResponseEntity<ApiResponse<List<TrendPoint>>> getCollectionTrends(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ApiResponse.success(ecommerceService.getCollectionTrends(corporateId, days)));
    }

    // ========== COLLECTION ACCOUNTS ==========

    /** The accounts money is collected into, with their real credited volume. */
    @GetMapping("/collection-accounts")
    public ResponseEntity<ApiResponse<List<CollectionAccountResponse>>> getCollectionAccounts(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId) {
        return ResponseEntity.ok(ApiResponse.success(ecommerceService.getCollectionAccounts(corporateId)));
    }

    // ========== MERCHANTS ==========

    @GetMapping("/merchants")
    public ResponseEntity<ApiResponse<List<MerchantResponse>>> getAllMerchants(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId) {
        return ResponseEntity.ok(ApiResponse.success(ecommerceService.getMerchants(corporateId)));
    }

    @PostMapping("/merchants")
    public ResponseEntity<ApiResponse<Void>> onboardMerchant(@RequestBody(required = false) Object request) {
        return notImplemented("Merchant onboarding needs a merchant registry, which this platform does not have yet. "
            + "va_movements carries the acquiring columns but no entity or feed writes them.");
    }

    @PostMapping("/merchants/{id}/approve")
    public ResponseEntity<ApiResponse<Void>> approveMerchant(@PathVariable UUID id) {
        return notImplemented("Merchant approval needs a merchant registry, which this platform does not have yet.");
    }

    // ========== COLLECTIONS ==========

    @GetMapping("/collections")
    public ResponseEntity<ApiResponse<List<CollectionResponse>>> getCollections(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            @RequestParam(required = false) UUID vaId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(
            ecommerceService.getCollections(corporateId, vaId, status, page, size)));
    }

    @GetMapping("/collections/{id}")
    public ResponseEntity<ApiResponse<CollectionResponse>> getCollectionDetails(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(ecommerceService.getCollection(id)));
    }

    // ========== SETTLEMENTS ==========

    @GetMapping("/settlements")
    public ResponseEntity<ApiResponse<List<SettlementResponse>>> getSettlements(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(ApiResponse.success(ecommerceService.getSettlements(corporateId, limit)));
    }

    @PostMapping("/settlements/process")
    public ResponseEntity<ApiResponse<Void>> processSettlements() {
        return notImplemented("Collection settlement runs through the sweep engine, not this endpoint. "
            + "Settlements listed here are the SWEEP_OUT movements it produces.");
    }

    private static ResponseEntity<ApiResponse<Void>> notImplemented(String message) {
        log.info("Rejected a call to an unimplemented e-commerce endpoint: {}", message);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
            .body(ApiResponse.error(message));
    }
}
