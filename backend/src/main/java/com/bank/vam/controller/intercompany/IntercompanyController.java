package com.bank.vam.controller.intercompany;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.intercompany.IntercompanyDto.*;
import com.bank.vam.service.intercompany.IntercompanyService;
import com.bank.vam.service.intercompany.IntercompanyTransactionService;
import com.bank.vam.service.intercompany.IntercompanyTransactionService.CreateIntercompanyTransactionRequest;
import com.bank.vam.service.intercompany.IntercompanyTransactionService.IntercompanyTransactionResult;
import com.bank.vam.service.intercompany.IntercompanyTransactionService.EntityPairSummary;
import com.bank.vam.service.intercompany.IntercompanyTransactionService.BilateralPositionDetail;
import com.bank.vam.service.intercompany.IntercompanyTransactionService.NettingEligibilityResult;
import com.bank.vam.service.intercompany.IntercompanyTransactionService.AddToNettingResult;
import com.bank.vam.service.intercompany.IntercompanyTransactionService.BilateralSettlementRequest;
import com.bank.vam.service.intercompany.IntercompanyTransactionService.BilateralSettlementResult;
import com.bank.vam.service.intercompany.IntercompanyTransactionService.TransferPricingValidation;
import com.bank.vam.service.intercompany.IntercompanyTransactionService.IntercompanyPositionSummary;
import com.bank.vam.service.intercompany.IntercompanyTransactionService.EntityIntercompanyReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Intercompany Controller - Phase 6 Enhanced REST API
 * 
 * Provides comprehensive intercompany transaction management:
 * - POBO/COBO operations
 * - Entity pair management
 * - Bilateral position tracking
 * - Netting integration
 * - Settlement workflows
 * - Transfer pricing validation
 * - Reporting & analytics
 * 
 * @see <a href="https://www.capgemini.com/wp-content/uploads/2020/12/Virtual-Acc-Management-in-Transaction-Banking.pdf">
 *      Capgemini VAM Reference</a>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/intercompany")
@RequiredArgsConstructor
@Tag(name = "Intercompany", description = "Intercompany transaction management")
public class IntercompanyController {

    private final IntercompanyService intercompanyService;
    private final IntercompanyTransactionService transactionService;

    // ========================================================================
    // PHASE 6 TASK 6.1: UNIFIED TRANSACTION CREATION
    // ========================================================================

    @PostMapping("/transactions")
    @Operation(summary = "Create intercompany transaction", 
        description = "Create unified IC transaction from any source")
    public ResponseEntity<ApiResponse<IntercompanyTransactionResult>> createTransaction(
            @RequestBody CreateIntercompanyTransactionRequest request) {
        log.info("Creating IC transaction: {} -> {}", 
            request.getSourceEntityCode(), request.getTargetEntityCode());
        IntercompanyTransactionResult result = transactionService.createIntercompanyTransaction(request);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/transactions/from-payable/{payableId}")
    @Operation(summary = "Create IC transaction from payable", 
        description = "Create intercompany transaction from an existing payable")
    public ResponseEntity<ApiResponse<IntercompanyTransactionResult>> createFromPayable(
            @PathVariable UUID payableId) {
        log.info("Creating IC transaction from payable: {}", payableId);
        IntercompanyTransactionResult result = transactionService.createFromPayable(payableId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/transactions/from-receivable/{receivableId}")
    @Operation(summary = "Create IC transaction from receivable", 
        description = "Create intercompany transaction from an existing receivable")
    public ResponseEntity<ApiResponse<IntercompanyTransactionResult>> createFromReceivable(
            @PathVariable UUID receivableId) {
        log.info("Creating IC transaction from receivable: {}", receivableId);
        IntercompanyTransactionResult result = transactionService.createFromReceivable(receivableId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ========================================================================
    // PHASE 6 TASK 6.2: ENTITY PAIR MANAGEMENT
    // ========================================================================

    @GetMapping("/entity-pairs")
    @Operation(summary = "Get entity pairs with IC activity", 
        description = "List all entity pairs that have intercompany transactions")
    public ResponseEntity<ApiResponse<List<EntityPairSummary>>> getEntityPairs(
            @RequestParam UUID corporateId) {
        log.debug("Getting entity pairs for corporate: {}", corporateId);
        List<EntityPairSummary> pairs = transactionService.getEntityPairs(corporateId);
        return ResponseEntity.ok(ApiResponse.success(pairs));
    }

    @GetMapping("/bilateral-position")
    @Operation(summary = "Get bilateral position", 
        description = "Get detailed position between two entities")
    public ResponseEntity<ApiResponse<BilateralPositionDetail>> getBilateralPosition(
            @RequestParam UUID entity1Id,
            @RequestParam UUID entity2Id) {
        log.debug("Getting bilateral position: {} <-> {}", entity1Id, entity2Id);
        BilateralPositionDetail position = transactionService.getBilateralPosition(entity1Id, entity2Id);
        return ResponseEntity.ok(ApiResponse.success(position));
    }

    // ========================================================================
    // PHASE 6 TASK 6.3: NETTING INTEGRATION
    // ========================================================================

    @GetMapping("/netting-eligibility")
    @Operation(summary = "Check netting eligibility", 
        description = "Check if transactions between entities are eligible for netting")
    public ResponseEntity<ApiResponse<NettingEligibilityResult>> checkNettingEligibility(
            @RequestParam UUID entity1Id,
            @RequestParam UUID entity2Id) {
        log.debug("Checking netting eligibility: {} <-> {}", entity1Id, entity2Id);
        NettingEligibilityResult result = transactionService.getNettingEligibleTransactions(entity1Id, entity2Id);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/add-to-netting/{cycleId}")
    @Operation(summary = "Add to netting cycle", 
        description = "Add IC transactions and recharges to a netting cycle")
    public ResponseEntity<ApiResponse<AddToNettingResult>> addToNettingCycle(
            @PathVariable UUID cycleId,
            @RequestBody AddToNettingRequest request) {
        log.info("Adding to netting cycle: {}", cycleId);
        AddToNettingResult result = transactionService.addToNettingCycle(
            cycleId, request.getTransactionIds(), request.getRechargeIds());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ========================================================================
    // PHASE 6 TASK 6.5: SETTLEMENT
    // ========================================================================

    @PostMapping("/settle-bilateral")
    @Operation(summary = "Settle bilateral position", 
        description = "Settle all outstanding transactions between two entities")
    public ResponseEntity<ApiResponse<BilateralSettlementResult>> settleBilateral(
            @RequestBody BilateralSettlementRequest request) {
        log.info("Settling bilateral: {} <-> {}", request.getEntity1Id(), request.getEntity2Id());
        BilateralSettlementResult result = transactionService.settleBilateralPosition(request);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ========================================================================
    // PHASE 6 TASK 6.6: TRANSFER PRICING
    // ========================================================================

    @PostMapping("/transactions/{transactionId}/validate-transfer-pricing")
    @Operation(summary = "Validate transfer pricing", 
        description = "Validate arm's length pricing for an IC transaction")
    public ResponseEntity<ApiResponse<TransferPricingValidation>> validateTransferPricing(
            @PathVariable UUID transactionId,
            @RequestParam(required = false) String notes) {
        log.info("Validating transfer pricing for: {}", transactionId);
        TransferPricingValidation result = transactionService.validateTransferPricing(transactionId, notes);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ========================================================================
    // PHASE 6 TASK 6.7: REPORTING
    // ========================================================================

    @GetMapping("/position-summary")
    @Operation(summary = "Get corporate IC position summary", 
        description = "Get aggregated intercompany position for a corporate")
    public ResponseEntity<ApiResponse<IntercompanyPositionSummary>> getPositionSummary(
            @RequestParam UUID corporateId) {
        log.debug("Getting position summary for corporate: {}", corporateId);
        IntercompanyPositionSummary summary = transactionService.getCorporatePositionSummary(corporateId);
        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    @GetMapping("/entity-report/{entityId}")
    @Operation(summary = "Get entity IC report", 
        description = "Get intercompany activity report for an entity")
    public ResponseEntity<ApiResponse<EntityIntercompanyReport>> getEntityReport(
            @PathVariable UUID entityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        log.debug("Getting entity report: {} from {} to {}", entityId, startDate, endDate);
        EntityIntercompanyReport report = transactionService.getEntityReport(entityId, startDate, endDate);
        return ResponseEntity.ok(ApiResponse.success(report));
    }

    // ========================================================================
    // EXISTING ENDPOINTS (preserved from IntercompanyService)
    // ========================================================================

    // --- Entity Management ---
    
    @GetMapping("/entities")
    @Operation(summary = "Get entities with IHB positions")
    public ResponseEntity<ApiResponse<List<EntityWithPositionResponse>>> getEntitiesWithPositions(
            @RequestParam(required = false) UUID corporateId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String entityType) {
        return ResponseEntity.ok(ApiResponse.success(
            intercompanyService.getEntitiesWithPositions(status, entityType)));
    }

    @GetMapping("/entities/{entityId}")
    @Operation(summary = "Get entity with position")
    public ResponseEntity<ApiResponse<EntityWithPositionResponse>> getEntityWithPosition(
            @PathVariable UUID entityId) {
        return ResponseEntity.ok(ApiResponse.success(
            intercompanyService.getEntityWithPosition(entityId)));
    }

    @GetMapping("/entities/{entityId}/position-detail")
    @Operation(summary = "Get detailed entity position")
    public ResponseEntity<ApiResponse<EntityPositionDetailResponse>> getEntityPositionDetail(
            @PathVariable UUID entityId) {
        return ResponseEntity.ok(ApiResponse.success(
            intercompanyService.getEntityPositionDetail(entityId)));
    }

    @PostMapping("/entities/{entityId}/validate")
    @Operation(summary = "Validate entity for IC transaction")
    public ResponseEntity<ApiResponse<EntityValidationResponse>> validateEntity(
            @PathVariable UUID entityId,
            @RequestBody EntityValidationRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
            intercompanyService.validateEntity(entityId, request)));
    }

    // --- POBO Operations ---

    @PostMapping("/pobo/preview")
    @Operation(summary = "Preview POBO transaction")
    public ResponseEntity<ApiResponse<PoboPreviewResponse>> previewPobo(
            @RequestBody PoboRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
            intercompanyService.previewPobo(request)));
    }

    @PostMapping("/pobo/execute")
    @Operation(summary = "Execute POBO payment")
    public ResponseEntity<ApiResponse<PoboResultResponse>> executePobo(
            @RequestBody PoboRequest request) {
        log.info("Executing POBO: {} pays for {}", 
            request.getPayingEntityId(), request.getBehalfEntityId());
        return ResponseEntity.ok(ApiResponse.success(
            intercompanyService.executePobo(request)));
    }

    @GetMapping("/pobo")
    @Operation(summary = "Get POBO transactions")
    public ResponseEntity<ApiResponse<List<IntercompanyTransactionResponse>>> getPoboTransactions(
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(ApiResponse.success(
            intercompanyService.getPoboTransactions(entityId, role, status, startDate, endDate)));
    }

    // --- COBO Operations ---

    @PostMapping("/cobo/setup")
    @Operation(summary = "Setup COBO collection")
    public ResponseEntity<ApiResponse<CoboResultResponse>> setupCobo(
            @RequestBody CoboRequest request) {
        log.info("Setting up COBO: {} collects for {}", 
            request.getCollectingEntityId(), request.getBehalfEntityId());
        return ResponseEntity.ok(ApiResponse.success(
            intercompanyService.setupCobo(request)));
    }

    @PostMapping("/cobo/{transactionRef}/collect")
    @Operation(summary = "Process COBO collection")
    public ResponseEntity<ApiResponse<CoboCollectionResponse>> processCoboCollection(
            @PathVariable String transactionRef,
            @RequestBody CoboCollectionRequest request) {
        log.info("Processing COBO collection: {}", transactionRef);
        return ResponseEntity.ok(ApiResponse.success(
            intercompanyService.processCoboCollection(transactionRef, request)));
    }

    @GetMapping("/cobo")
    @Operation(summary = "Get COBO transactions")
    public ResponseEntity<ApiResponse<List<IntercompanyTransactionResponse>>> getCoboTransactions(
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(ApiResponse.success(
            intercompanyService.getCoboTransactions(entityId, role, status, startDate, endDate)));
    }

    // --- Transaction Details ---

    @GetMapping("/transactions/{transactionRef}")
    @Operation(summary = "Get IC transaction details")
    public ResponseEntity<ApiResponse<IntercompanyTransactionDetailResponse>> getTransaction(
            @PathVariable String transactionRef) {
        return ResponseEntity.ok(ApiResponse.success(
            intercompanyService.getIntercompanyTransaction(transactionRef)));
    }

    @GetMapping("/transactions")
    @Operation(summary = "Get IC transactions list")
    public ResponseEntity<ApiResponse<List<IntercompanyTransactionResponse>>> getTransactions(
            @RequestParam(required = false) UUID corporateId,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.success(
            intercompanyService.getTransactions(corporateId, entityId, type, status, page, size)));
    }

    // --- Settlement Operations ---

    @GetMapping("/unsettled")
    @Operation(summary = "Get unsettled transactions")
    public ResponseEntity<ApiResponse<List<IntercompanyTransactionResponse>>> getUnsettledTransactions(
            @RequestParam(required = false) UUID entityId) {
        return ResponseEntity.ok(ApiResponse.success(
            intercompanyService.getUnsettledTransactions(entityId)));
    }

    @PostMapping("/settlement/calculate")
    @Operation(summary = "Calculate net settlement")
    public ResponseEntity<ApiResponse<NetSettlementResponse>> calculateNetSettlement(
            @RequestBody NetSettlementRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
            intercompanyService.calculateNetSettlement(request)));
    }

    @PostMapping("/settlement/execute")
    @Operation(summary = "Execute settlement")
    public ResponseEntity<ApiResponse<SettlementResultResponse>> executeSettlement(
            @RequestBody SettlementRequest request) {
        log.info("Executing settlement for {} transactions", request.getTransactionIds().size());
        return ResponseEntity.ok(ApiResponse.success(
            intercompanyService.executeSettlement(request)));
    }

    @GetMapping("/settlement/history")
    @Operation(summary = "Get settlement history")
    public ResponseEntity<ApiResponse<List<SettlementHistoryResponse>>> getSettlementHistory(
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(ApiResponse.success(
            intercompanyService.getSettlementHistory(entityId, startDate, endDate)));
    }

    @GetMapping("/settlement/{settlementRef}")
    @Operation(summary = "Get settlement details")
    public ResponseEntity<ApiResponse<SettlementDetailResponse>> getSettlement(
            @PathVariable String settlementRef) {
        return ResponseEntity.ok(ApiResponse.success(
            intercompanyService.getSettlement(settlementRef)));
    }

    // --- Statistics ---

    @GetMapping("/stats")
    @Operation(summary = "Get intercompany statistics")
    public ResponseEntity<ApiResponse<IntercompanyStatsResponse>> getStats(
            @RequestParam(required = false) UUID corporateId) {
        return ResponseEntity.ok(ApiResponse.success(
            intercompanyService.getStats()));
    }

    @GetMapping("/activity-report")
    @Operation(summary = "Get activity report")
    public ResponseEntity<ApiResponse<IntercompanyActivityReport>> getActivityReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) UUID entityId) {
        return ResponseEntity.ok(ApiResponse.success(
            intercompanyService.getActivityReport(startDate, endDate, entityId)));
    }

    // ========================================================================
    // PHASE 6: ADDITIONAL REQUEST DTOs
    // ========================================================================

    @lombok.Data
    public static class AddToNettingRequest {
        private List<UUID> transactionIds;
        private List<UUID> rechargeIds;
    }
}