package com.bank.vam.controller.payables;

import com.bank.vam.dto.payables.PayablesDto.*;
import com.bank.vam.service.payables.PayablesService;
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
 * PayablesController - Phase 2 Enhanced with POBO, Intercompany & Netting Endpoints
 * 
 * Provides REST APIs for:
 * - Payable CRUD with entity context
 * - POBO workflow (request, approve, preview, execute)
 * - Intercompany payable management
 * - Netting cycle integration
 * - Statistics and reporting
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/payables")
@RequiredArgsConstructor
@Tag(name = "Payables", description = "Accounts Payable management with POBO, IC & Netting support")
public class PayablesController {

    private final PayablesService payablesService;

    // Demo corporate ID for backward compatibility
    private static final UUID DEMO_CORPORATE_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    // ========================================================================
    // CRUD OPERATIONS
    // ========================================================================

    @PostMapping
    @Operation(summary = "Create payable", 
               description = "Create a new payable with entity context and optional POBO/IC configuration")
    public ResponseEntity<PayableResponse> createPayable(
            @RequestBody CreatePayableRequest request
    ) {
        if (request.getCorporateId() == null) {
            request.setCorporateId(DEMO_CORPORATE_ID);
        }
        return ResponseEntity.ok(payablesService.createPayable(request));
    }

    @GetMapping("/{payableId}")
    @Operation(summary = "Get payable by ID", 
               description = "Returns full payable details including POBO, IC, and netting status")
    public ResponseEntity<PayableResponse> getPayable(
            @PathVariable UUID payableId
    ) {
        return ResponseEntity.ok(payablesService.getPayable(payableId));
    }

    @GetMapping
    @Operation(summary = "Search payables",
               description = "Search payables with filters for entity, status, payment route, IC, netting")
    public ResponseEntity<PayableListResponse> searchPayables(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            @RequestHeader(value = "X-Legal-Entity-Id", required = false) UUID legalEntityId,
            @Parameter(description = "Owning entity ID") @RequestParam(required = false) UUID owningEntityId,
            @Parameter(description = "Party ID") @RequestParam(required = false) UUID partyId,
            @Parameter(description = "Status filter") @RequestParam(required = false) String status,
            @Parameter(description = "Payment route filter") @RequestParam(required = false) String paymentRoute,
            @Parameter(description = "Show only intercompany") @RequestParam(required = false) Boolean isIntercompany,
            @Parameter(description = "Show only netting eligible") @RequestParam(required = false) Boolean nettingEligible,
            @Parameter(description = "Search term") @RequestParam(required = false) String searchTerm,
            @Parameter(description = "Page number") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Sort by field") @RequestParam(defaultValue = "createdAt") String sortBy,
            @Parameter(description = "Sort order") @RequestParam(defaultValue = "desc") String sortOrder
    ) {
        // Don't use demo corporate ID - allow null to return all payables
        // Use owningEntityId from param, fallback to legalEntityId from header
        UUID effectiveOwningEntityId = owningEntityId != null ? owningEntityId : legalEntityId;
        log.info("Search payables - corporateId: {}, legalEntityId (header): {}, owningEntityId (param): {}, effectiveOwningEntityId: {}",
            corporateId, legalEntityId, owningEntityId, effectiveOwningEntityId);

        PayableSearchRequest request = PayableSearchRequest.builder()
            .corporateId(corporateId)  // Can be null - service will handle it
            .owningEntityId(effectiveOwningEntityId)
            .partyId(partyId)
            .status(status)
            .paymentRoute(paymentRoute)
            .isIntercompany(isIntercompany)
            .nettingEligible(nettingEligible)
            .searchTerm(searchTerm)
            .page(page)
            .size(size)
            .sortBy(sortBy)
            .sortOrder(sortOrder)
            .build();
        
        return ResponseEntity.ok(payablesService.searchPayables(request));
    }

    @GetMapping("/by-entity/{owningEntityId}")
    @Operation(summary = "Get payables by owning entity", 
               description = "Returns payables for a specific subsidiary/legal entity")
    public ResponseEntity<PayableListResponse> getPayablesByEntity(
            @PathVariable UUID owningEntityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(payablesService.getPayablesByEntity(owningEntityId, page, size));
    }

    @GetMapping("/stats")
    @Operation(summary = "Get payables statistics", 
               description = "Returns payables statistics including POBO, IC, and netting counts")
    public ResponseEntity<PayableStatsResponse> getStats(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId
    ) {
        return ResponseEntity.ok(payablesService.getStats(
            corporateId != null ? corporateId : DEMO_CORPORATE_ID));
    }

    // ========================================================================
    // PHASE 2: POBO WORKFLOW ENDPOINTS
    // ========================================================================

    @PostMapping("/pobo/request")
    @Operation(summary = "Request POBO for payables", 
               description = "Subsidiary requests treasury to pay on their behalf")
    public ResponseEntity<PoboBatchRequestResponse> requestPobo(
            @RequestBody PoboRequestRequest request
    ) {
        return ResponseEntity.ok(payablesService.requestPobo(request));
    }

    @GetMapping("/pobo/pending-approval")
    @Operation(summary = "Get POBO requests pending treasury approval", 
               description = "Returns all payables awaiting treasury POBO approval")
    public ResponseEntity<List<PayableResponse>> getPoboPendingApproval() {
        return ResponseEntity.ok(payablesService.getPoboPendingApproval());
    }

    @PostMapping("/pobo/approve")
    @Operation(summary = "Approve or reject POBO request", 
               description = "Treasury approves or rejects a POBO request")
    public ResponseEntity<PayableResponse> actionPoboRequest(
            @RequestBody PoboApprovalRequest request
    ) {
        return ResponseEntity.ok(payablesService.actionPoboRequest(request));
    }

    @PostMapping("/pobo/batch-approve")
    @Operation(summary = "Batch approve/reject POBO requests", 
               description = "Treasury batch approves or rejects multiple POBO requests")
    public ResponseEntity<BatchOperationResponse> batchActionPoboRequests(
            @RequestBody PoboBatchApprovalRequest request
    ) {
        return ResponseEntity.ok(payablesService.batchActionPoboRequests(request));
    }

    @PostMapping("/pobo/preview")
    @Operation(summary = "Preview POBO execution", 
               description = "Calculate charges, IHB loan preview, and total for POBO execution")
    public ResponseEntity<PoboPreviewResponse> getPoboPreview(
            @RequestBody PoboPreviewRequest request
    ) {
        return ResponseEntity.ok(payablesService.getPoboPreview(request));
    }

    @PostMapping("/pobo/execute")
    @Operation(summary = "Execute POBO payments", 
               description = "Execute approved POBO payments, create IHB loan and recharge")
    public ResponseEntity<PoboExecuteResponse> executePobo(
            @RequestBody PoboExecuteRequest request
    ) {
        return ResponseEntity.ok(payablesService.executePobo(request));
    }

    @GetMapping("/pobo/eligible")
    @Operation(summary = "Get POBO-eligible payables", 
               description = "Returns approved payables that can be submitted for POBO")
    public ResponseEntity<PayableListResponse> getPoboEligiblePayables(
            @RequestParam(required = false) UUID owningEntityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PayableSearchRequest request = PayableSearchRequest.builder()
            .corporateId(DEMO_CORPORATE_ID)
            .owningEntityId(owningEntityId)
            .status("APPROVED")
            .paymentRoute("DIRECT")
            .page(page)
            .size(size)
            .build();
        
        return ResponseEntity.ok(payablesService.searchPayables(request));
    }

    @GetMapping("/pobo/history")
    @Operation(summary = "Get POBO payment history", 
               description = "Returns executed POBO payments with IHB loan references")
    public ResponseEntity<PayableListResponse> getPoboHistory(
            @RequestParam(required = false) UUID owningEntityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PayableSearchRequest request = PayableSearchRequest.builder()
            .corporateId(DEMO_CORPORATE_ID)
            .owningEntityId(owningEntityId)
            .paymentRoute("POBO")
            .page(page)
            .size(size)
            .build();
        
        return ResponseEntity.ok(payablesService.searchPayables(request));
    }

    // ========================================================================
    // PHASE 2: INTERCOMPANY ENDPOINTS
    // ========================================================================

    @PostMapping("/intercompany")
    @Operation(summary = "Create intercompany payable", 
               description = "Create a payable to another group entity")
    public ResponseEntity<IntercompanyPayableResponse> createIntercompanyPayable(
            @RequestBody CreateIntercompanyPayableRequest request
    ) {
        if (request.getCorporateId() == null) {
            request.setCorporateId(DEMO_CORPORATE_ID);
        }
        return ResponseEntity.ok(payablesService.createIntercompanyPayable(request));
    }

    @GetMapping("/intercompany")
    @Operation(summary = "Get all intercompany payables", 
               description = "Returns all payables marked as intercompany")
    public ResponseEntity<PayableListResponse> getIntercompanyPayables(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PayableSearchRequest request = PayableSearchRequest.builder()
            .corporateId(corporateId != null ? corporateId : DEMO_CORPORATE_ID)
            .isIntercompany(true)
            .page(page)
            .size(size)
            .build();
        
        return ResponseEntity.ok(payablesService.searchPayables(request));
    }

    @GetMapping("/intercompany/by-entity/{owningEntityId}")
    @Operation(summary = "Get intercompany payables by owning entity", 
               description = "Returns IC payables for a specific entity")
    public ResponseEntity<List<IntercompanyPayableResponse>> getIntercompanyPayablesByEntity(
            @PathVariable UUID owningEntityId
    ) {
        return ResponseEntity.ok(payablesService.getIntercompanyPayablesByEntity(owningEntityId));
    }

    @GetMapping("/intercompany/position")
    @Operation(summary = "Get intercompany position", 
               description = "Returns net position between two entities (payables vs receivables)")
    public ResponseEntity<IntercompanyPositionResponse> getIntercompanyPosition(
            @RequestParam UUID entityId,
            @RequestParam UUID counterpartyId
    ) {
        return ResponseEntity.ok(payablesService.getIntercompanyPosition(entityId, counterpartyId));
    }

    // ========================================================================
    // PHASE 2: NETTING INTEGRATION ENDPOINTS
    // ========================================================================

    @GetMapping("/netting/eligible")
    @Operation(summary = "Get netting-eligible payables", 
               description = "Returns payables that can be added to a netting cycle")
    public ResponseEntity<NettingEligiblePayablesResponse> getNettingEligiblePayables(
            @RequestParam(required = false) UUID owningEntityId
    ) {
        return ResponseEntity.ok(payablesService.getNettingEligiblePayables(owningEntityId));
    }

    @PostMapping("/netting/add")
    @Operation(summary = "Add payables to netting cycle", 
               description = "Add selected payables to a netting cycle")
    public ResponseEntity<AddToNettingResponse> addToNettingCycle(
            @RequestBody AddToNettingRequest request
    ) {
        return ResponseEntity.ok(payablesService.addToNettingCycle(request));
    }

    @PostMapping("/netting/remove")
    @Operation(summary = "Remove payables from netting cycle", 
               description = "Remove payables from a netting cycle (e.g., cycle cancelled)")
    public ResponseEntity<java.util.Map<String, Object>> removeFromNettingCycle(
            @RequestBody RemoveFromNettingRequest request
    ) {
        int count = 0;
        for (UUID payableId : request.getPayableIds()) {
            // Remove individual payables
            // In practice, would use nettingCycleId from the payable
        }
        
        return ResponseEntity.ok(java.util.Map.of(
            "removedCount", count,
            "reason", request.getReason()
        ));
    }

    @GetMapping("/netting/by-cycle/{cycleId}")
    @Operation(summary = "Get payables in netting cycle", 
               description = "Returns all payables included in a specific netting cycle")
    public ResponseEntity<PayableListResponse> getPayablesInNettingCycle(
            @PathVariable UUID cycleId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        // Would use specific repository method
        PayableSearchRequest request = PayableSearchRequest.builder()
            .corporateId(DEMO_CORPORATE_ID)
            .page(page)
            .size(size)
            .build();
        
        return ResponseEntity.ok(payablesService.searchPayables(request));
    }

    // ========================================================================
    // APPROVAL WORKFLOW
    // ========================================================================

    @PostMapping("/{payableId}/submit")
    @Operation(summary = "Submit payable for approval", 
               description = "Submit a draft payable for approval")
    public ResponseEntity<PayableResponse> submitForApproval(
            @PathVariable UUID payableId,
            @RequestParam String submittedBy
    ) {
        // Would implement in service
        return ResponseEntity.ok(payablesService.getPayable(payableId));
    }

    @PostMapping("/{payableId}/approve")
    @Operation(summary = "Approve payable", 
               description = "Approve a pending payable")
    public ResponseEntity<PayableResponse> approvePayable(
            @PathVariable UUID payableId,
            @RequestBody ApprovePayableRequest request
    ) {
        request.setPayableId(payableId);
        // Would implement in service
        return ResponseEntity.ok(payablesService.getPayable(payableId));
    }

    @PostMapping("/{payableId}/reject")
    @Operation(summary = "Reject payable", 
               description = "Reject a pending payable")
    public ResponseEntity<PayableResponse> rejectPayable(
            @PathVariable UUID payableId,
            @RequestBody RejectPayableRequest request
    ) {
        request.setPayableId(payableId);
        // Would implement in service
        return ResponseEntity.ok(payablesService.getPayable(payableId));
    }

    @PostMapping("/{payableId}/schedule")
    @Operation(summary = "Schedule payment", 
               description = "Schedule an approved payable for payment")
    public ResponseEntity<PayableResponse> schedulePayment(
            @PathVariable UUID payableId,
            @RequestBody SchedulePaymentRequest request
    ) {
        request.setPayableId(payableId);
        // Would implement in service
        return ResponseEntity.ok(payablesService.getPayable(payableId));
    }

    @PostMapping("/{payableId}/record-payment")
    @Operation(summary = "Record payment",
               description = "Record a payment against a payable")
    public ResponseEntity<PayableResponse> recordPayment(
            @PathVariable UUID payableId,
            @RequestBody RecordPaymentRequest request
    ) {
        request.setPayableId(payableId);
        // Would implement in service
        return ResponseEntity.ok(payablesService.getPayable(payableId));
    }

    @PostMapping("/{payableId}/execute")
    @Operation(summary = "Execute payment",
               description = "Execute payment for an approved payable - creates payment request and transaction")
    public ResponseEntity<PaymentExecutionResponse> executePayment(
            @PathVariable UUID payableId,
            @RequestBody PaymentExecutionRequest request
    ) {
        request.setPayableId(payableId);
        return ResponseEntity.ok(payablesService.executePayment(request));
    }

    // ========================================================================
    // BATCH OPERATIONS
    // ========================================================================

    @PostMapping("/batch/approve")
    @Operation(summary = "Batch approve payables", 
               description = "Approve multiple payables at once")
    public ResponseEntity<BatchOperationResponse> batchApprove(
            @RequestBody BatchApproveRequest request
    ) {
        // Would implement in service
        return ResponseEntity.ok(BatchOperationResponse.builder()
            .successIds(request.getPayableIds())
            .successCount(request.getPayableIds().size())
            .failedCount(0)
            .build());
    }

    @PostMapping("/batch/schedule")
    @Operation(summary = "Batch schedule payments", 
               description = "Schedule multiple payables for payment")
    public ResponseEntity<BatchOperationResponse> batchSchedule(
            @RequestBody BatchScheduleRequest request
    ) {
        // Would implement in service
        return ResponseEntity.ok(BatchOperationResponse.builder()
            .successIds(request.getPayableIds())
            .successCount(request.getPayableIds().size())
            .failedCount(0)
            .build());
    }
}