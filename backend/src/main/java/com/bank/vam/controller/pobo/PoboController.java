package com.bank.vam.controller.pobo;

import com.bank.vam.dto.pobo.PoboDto.*;
import com.bank.vam.service.pobo.PoboExecutionService;
import com.bank.vam.service.pobo.PoboExecutionService.*;
import com.bank.vam.service.pobo.PoboPreviewService;
import com.bank.vam.service.pobo.PoboPreviewService.*;
import com.bank.vam.service.pobo.PoboService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * POBO Controller - Phase 5 Enhanced REST API
 * 
 * PHASE 5 ENDPOINTS:
 * - POST /preview - Preview POBO with charges and IHB loan estimate (5.5)
 * - POST /preview/batch - Preview batch POBO (5.5)
 * - POST /execute - Execute single POBO payment (5.1)
 * - POST /execute/batch - Execute batch POBO payments (5.6)
 * - GET /history - POBO execution history (5.7)
 * 
 * EXISTING ENDPOINTS (preserved):
 * - Authorization management
 * - Recharge management
 * - POBO validation
 * - Statistics
 * 
 * @see <a href="https://www.capgemini.com/wp-content/uploads/2020/12/Virtual-Acc-Management-in-Transaction-Banking.pdf">
 *      Capgemini VAM Reference</a>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/pobo")
@RequiredArgsConstructor
@Tag(name = "POBO", description = "Pay On Behalf Of operations")
public class PoboController {

    private final PoboService poboService;
    private final PoboExecutionService executionService;
    private final PoboPreviewService previewService;

    // ========================================================================
    // PHASE 5 TASK 5.5: PREVIEW ENDPOINTS
    // ========================================================================
    
    @PostMapping("/preview")
    @Operation(summary = "Preview POBO", 
        description = "Calculate all charges, IHB loan terms, and total cost before executing POBO")
    public ResponseEntity<PoboPreviewResponse> previewPobo(
            @RequestBody PoboPreviewRequest request) {
        log.info("POBO preview requested for payable: {}", request.getPayableId());
        PoboPreviewResponse response = previewService.previewPobo(request);
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/preview/batch")
    @Operation(summary = "Preview batch POBO", 
        description = "Preview multiple payables with aggregated totals and eligibility check")
    public ResponseEntity<BatchPoboPreviewResponse> previewBatchPobo(
            @RequestBody BatchPoboPreviewRequest request) {
        log.info("Batch POBO preview requested for {} payables", request.getPayableIds().size());
        BatchPoboPreviewResponse response = previewService.previewBatchPobo(request);
        return ResponseEntity.ok(response);
    }

    // ========================================================================
    // PHASE 5 TASK 5.1, 5.6: EXECUTION ENDPOINTS
    // ========================================================================
    
    @PostMapping("/execute")
    @Operation(summary = "Execute POBO payment", 
        description = "Execute single POBO payment with optional IHB loan creation")
    public ResponseEntity<PoboExecutionResult> executePobo(
            @RequestBody PoboExecuteRequest request) {
        log.info("POBO execution requested for payable: {}", request.getPayableId());
        PoboExecutionResult result = executionService.executePoboPayment(request);
        return ResponseEntity.ok(result);
    }
    
    @PostMapping("/execute/batch")
    @Operation(summary = "Execute batch POBO", 
        description = "Execute multiple POBO payments in a single batch")
    public ResponseEntity<BatchPoboResult> executeBatchPobo(
            @RequestBody BatchPoboExecuteRequest request) {
        log.info("Batch POBO execution requested for {} payables", request.getPayableIds().size());
        BatchPoboResult result = executionService.executeBatchPobo(request);
        return ResponseEntity.ok(result);
    }

    // ========================================================================
    // PHASE 5 TASK 5.2: VALIDATION ENDPOINT
    // ========================================================================
    
    @PostMapping("/validate")
    @Operation(summary = "Validate POBO", 
        description = "Check if POBO payment is authorized without executing")
    public ResponseEntity<ValidatePoboResponse> validatePobo(
            @RequestBody ValidatePoboRequest request) {
        log.info("POBO validation for {} -> {}", 
            request.getPayerEntityCode(), request.getBehalfEntityCode());
        ValidatePoboResponse response = poboService.validatePoboPayment(request);
        return ResponseEntity.ok(response);
    }

    // ========================================================================
    // AUTHORIZATION MANAGEMENT (Existing - preserved)
    // ========================================================================
    
    @GetMapping("/authorizations")
    @Operation(summary = "Get all authorizations", description = "List all active POBO authorizations")
    public ResponseEntity<List<AuthorizationResponse>> getAllAuthorizations() {
        return ResponseEntity.ok(poboService.getAllAuthorizations());
    }
    
    @GetMapping("/authorizations/{id}")
    @Operation(summary = "Get authorization by ID")
    public ResponseEntity<AuthorizationResponse> getAuthorization(
            @PathVariable UUID id) {
        return ResponseEntity.ok(poboService.getAuthorization(id));
    }
    
    @GetMapping("/authorizations/code/{code}")
    @Operation(summary = "Get authorization by code")
    public ResponseEntity<AuthorizationResponse> getAuthorizationByCode(
            @PathVariable String code) {
        return ResponseEntity.ok(poboService.getAuthorizationByCode(code));
    }
    
    @GetMapping("/authorizations/payer/{payerEntityId}")
    @Operation(summary = "Get authorizations for payer entity")
    public ResponseEntity<List<AuthorizationResponse>> getAuthorizationsForPayer(
            @PathVariable UUID payerEntityId) {
        return ResponseEntity.ok(poboService.getAuthorizationsForPayer(payerEntityId));
    }
    
    @GetMapping("/authorizations/behalf/{behalfEntityId}")
    @Operation(summary = "Get authorizations for behalf entity")
    public ResponseEntity<List<AuthorizationResponse>> getAuthorizationsForBehalf(
            @PathVariable UUID behalfEntityId) {
        return ResponseEntity.ok(poboService.getAuthorizationsForBehalf(behalfEntityId));
    }
    
    @PostMapping("/authorizations")
    @Operation(summary = "Create authorization", description = "Create new POBO authorization")
    public ResponseEntity<AuthorizationResponse> createAuthorization(
            @RequestBody CreateAuthorizationRequest request) {
        log.info("Creating POBO authorization: {} -> {}", 
            request.getPayerEntityCode(), request.getBehalfEntityCode());
        return ResponseEntity.ok(poboService.createAuthorization(request));
    }
    
    @PutMapping("/authorizations/{id}/limits")
    @Operation(summary = "Update authorization limits")
    public ResponseEntity<AuthorizationResponse> updateLimits(
            @PathVariable UUID id,
            @RequestBody UpdateLimitsRequest request) {
        return ResponseEntity.ok(poboService.updateLimits(id, request));
    }
    
    @PostMapping("/authorizations/{id}/suspend")
    @Operation(summary = "Suspend authorization")
    public ResponseEntity<Void> suspendAuthorization(
            @PathVariable UUID id,
            @RequestParam String reason) {
        poboService.suspendAuthorization(id, reason);
        return ResponseEntity.ok().build();
    }
    
    @PostMapping("/authorizations/{id}/reactivate")
    @Operation(summary = "Reactivate authorization")
    public ResponseEntity<Void> reactivateAuthorization(@PathVariable UUID id) {
        poboService.reactivateAuthorization(id);
        return ResponseEntity.ok().build();
    }

    // ========================================================================
    // RECHARGE MANAGEMENT (Existing - preserved)
    // ========================================================================
    
    @GetMapping("/recharges")
    @Operation(summary = "Get all recharges")
    public ResponseEntity<List<RechargeResponse>> getAllRecharges() {
        return ResponseEntity.ok(poboService.getAllRecharges());
    }
    
    @GetMapping("/recharges/pending")
    @Operation(summary = "Get pending recharges")
    public ResponseEntity<List<RechargeResponse>> getPendingRecharges() {
        return ResponseEntity.ok(poboService.getPendingRecharges());
    }
    
    @GetMapping("/recharges/entity/{entityId}")
    @Operation(summary = "Get recharges for entity")
    public ResponseEntity<List<RechargeResponse>> getRechargesForEntity(
            @PathVariable UUID entityId) {
        return ResponseEntity.ok(poboService.getRechargesForEntity(entityId));
    }
    
    @PostMapping("/recharges")
    @Operation(summary = "Create recharge manually", 
        description = "Manual recharge creation (normally auto-created via POBO execution)")
    public ResponseEntity<RechargeResponse> createRecharge(
            @RequestBody CreateRechargeRequest request) {
        return ResponseEntity.ok(poboService.createRecharge(request));
    }
    
    @PostMapping("/recharges/{id}/approve")
    @Operation(summary = "Approve recharge")
    public ResponseEntity<RechargeResponse> approveRecharge(
            @PathVariable UUID id,
            @RequestParam String approver) {
        return ResponseEntity.ok(poboService.approveRecharge(id, approver));
    }

    @PostMapping("/recharges/{id}/reject")
    @Operation(summary = "Reject recharge")
    public ResponseEntity<RechargeResponse> rejectRecharge(
            @PathVariable UUID id,
            @RequestParam String rejector,
            @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(poboService.rejectRecharge(id, rejector, reason));
    }

    @PostMapping("/recharges/{id}/settle/ihb")
    @Operation(summary = "Settle recharge via IHB loan")
    public ResponseEntity<RechargeResponse> settleViaIhbLoan(
            @PathVariable UUID id,
            @RequestParam UUID ihbLoanId,
            @RequestParam String loanReference) {
        return ResponseEntity.ok(poboService.settleViaIhbLoan(id, ihbLoanId, loanReference));
    }
    
    @PostMapping("/recharges/{id}/settle/netting")
    @Operation(summary = "Settle recharge via netting")
    public ResponseEntity<RechargeResponse> settleViaNetting(
            @PathVariable UUID id,
            @RequestParam String nettingReference) {
        return ResponseEntity.ok(poboService.settleViaNetting(id, nettingReference));
    }
    
    @PostMapping("/recharges/{id}/validate-arm-length")
    @Operation(summary = "Validate arm's length pricing", 
        description = "Mark transfer pricing as compliant")
    public ResponseEntity<RechargeResponse> validateArmLength(
            @PathVariable UUID id,
            @RequestParam(required = false) String notes) {
        return ResponseEntity.ok(poboService.validateArmLength(id, notes));
    }

    // ========================================================================
    // STATISTICS (Existing - preserved)
    // ========================================================================
    
    @GetMapping("/stats")
    @Operation(summary = "Get POBO statistics")
    public ResponseEntity<PoboStatsResponse> getStats() {
        return ResponseEntity.ok(poboService.getStats());
    }

    // ========================================================================
    // PHASE 5 TASK 5.7: HISTORY ENDPOINTS
    // ========================================================================
    
    @GetMapping("/history")
    @Operation(summary = "Get POBO execution history", 
        description = "List all POBO executions with filtering options")
    public ResponseEntity<PoboHistoryResponse> getHistory(
            @RequestParam(required = false) UUID treasuryEntityId,
            @RequestParam(required = false) UUID subsidiaryEntityId,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        // TODO: Implement history query with filtering
        // For now, return empty response - would integrate with execution history table
        return ResponseEntity.ok(PoboHistoryResponse.builder()
            .totalElements(0L)
            .totalPages(0)
            .page(page)
            .size(size)
            .executions(List.of())
            .build());
    }
    
    @GetMapping("/history/{executionId}")
    @Operation(summary = "Get POBO execution details")
    public ResponseEntity<PoboExecutionDetailResponse> getExecutionDetails(
            @PathVariable UUID executionId) {
        
        // TODO: Implement execution detail lookup
        // Would include: payable details, recharge, IHB loan, fee breakdown, timestamps
        return ResponseEntity.notFound().build();
    }

    // ========================================================================
    // SUBSIDIARY DASHBOARD ENDPOINTS
    // ========================================================================
    
    @GetMapping("/subsidiary/{entityId}/dashboard")
    @Operation(summary = "Get subsidiary POBO dashboard", 
        description = "Summary of POBO usage, outstanding recharges, and IHB position")
    public ResponseEntity<SubsidiaryPoboDashboard> getSubsidiaryDashboard(
            @PathVariable UUID entityId) {
        
        // Get authorizations where this entity is the "behalf" entity
        List<AuthorizationResponse> authorizations = poboService.getAuthorizationsForBehalf(entityId);
        
        // Get outstanding recharges
        List<RechargeResponse> recharges = poboService.getRechargesForEntity(entityId);
        
        // Calculate totals
        var pendingRecharges = recharges.stream()
            .filter(r -> r.getStatus().name().equals("PENDING") || r.getStatus().name().equals("APPROVED"))
            .toList();
        
        var totalOutstanding = pendingRecharges.stream()
            .map(RechargeResponse::getTotalRecharge)
            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        
        return ResponseEntity.ok(SubsidiaryPoboDashboard.builder()
            .entityId(entityId)
            .activeAuthorizations(authorizations.size())
            .pendingRecharges(pendingRecharges.size())
            .totalOutstandingRecharge(totalOutstanding)
            .authorizations(authorizations)
            .recentRecharges(recharges.stream().limit(10).toList())
            .build());
    }

    // ========================================================================
    // TREASURY DASHBOARD ENDPOINTS
    // ========================================================================
    
    @GetMapping("/treasury/{entityId}/dashboard")
    @Operation(summary = "Get treasury POBO dashboard", 
        description = "Overview of all subsidiaries' POBO status and pending actions")
    public ResponseEntity<TreasuryPoboDashboard> getTreasuryDashboard(
            @PathVariable UUID entityId) {
        
        // Get authorizations where this entity is the payer
        List<AuthorizationResponse> authorizations = poboService.getAuthorizationsForPayer(entityId);
        
        // Get pending recharges
        List<RechargeResponse> pendingRecharges = poboService.getPendingRecharges();
        
        // Get overall stats
        PoboStatsResponse stats = poboService.getStats();
        
        return ResponseEntity.ok(TreasuryPoboDashboard.builder()
            .entityId(entityId)
            .totalSubsidiaries(authorizations.size())
            .activeAuthorizations(authorizations.stream()
                .filter(a -> "ACTIVE".equals(a.getStatus()))
                .count())
            .pendingApprovals(pendingRecharges.size())
            .totalRechargesOutstanding(stats.getTotalRechargesOutstanding())
            .totalServiceFeesCollected(stats.getTotalServiceFeesCollected())
            .armLengthValidationsPending(stats.getArmLengthValidationsPending())
            .subsidiaryStatuses(authorizations.stream()
                .map(this::toSubsidiaryStatus)
                .toList())
            .pendingRecharges(pendingRecharges)
            .build());
    }
    
    private SubsidiaryPoboStatus toSubsidiaryStatus(AuthorizationResponse auth) {
        return SubsidiaryPoboStatus.builder()
            .entityId(auth.getBehalfEntityId())
            .entityCode(auth.getBehalfEntityCode())
            .authorizationCode(auth.getAuthorizationCode())
            .status(auth.getStatus())
            .dailyLimit(auth.getDailyLimit())
            .usedToday(auth.getUsedToday())
            .remainingDailyLimit(auth.getRemainingDailyLimit())
            .monthlyLimit(auth.getMonthlyLimit())
            .usedThisMonth(auth.getUsedThisMonth())
            .remainingMonthlyLimit(auth.getRemainingMonthlyLimit())
            .build();
    }
}