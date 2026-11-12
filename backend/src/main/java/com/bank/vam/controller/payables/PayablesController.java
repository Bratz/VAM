package com.bank.vam.controller.payables;

import com.bank.vam.dto.payables.PayablesDto.*;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.service.payables.PayablesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

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

// ========================================================================
    // CRUD OPERATIONS
    // ========================================================================

    @PostMapping
    @Operation(summary = "Create payable", 
               description = "Create a new payable with entity context and optional POBO/IC configuration")
    public ResponseEntity<PayableResponse> createPayable(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            @RequestBody CreatePayableRequest request
    ) {
        request.setCorporateId(requireCorporate(request.getCorporateId() != null ? request.getCorporateId() : corporateId));
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

    @PutMapping("/{payableId}")
    @Operation(summary = "Update payable",
               description = "Update editable fields of a DRAFT or REJECTED payable")
    public ResponseEntity<PayableResponse> updatePayable(
            @PathVariable UUID payableId,
            @RequestBody UpdatePayableRequest request
    ) {
        return ResponseEntity.ok(payablesService.updatePayable(payableId, request));
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
        // Use owningEntityId from param, fallback to legalEntityId from header
        UUID effectiveOwningEntityId = owningEntityId != null ? owningEntityId : legalEntityId;
        log.info("Search payables - corporateId: {}, legalEntityId (header): {}, owningEntityId (param): {}, effectiveOwningEntityId: {}",
            corporateId, legalEntityId, owningEntityId, effectiveOwningEntityId);

        PayableSearchRequest request = PayableSearchRequest.builder()
            .corporateId(requireCorporate(corporateId))
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
        return ResponseEntity.ok(payablesService.getStats(requireCorporate(corporateId)));
    }

    // ========================================================================
    // POBO: decided by enrolment (In-House Bank), not requested per payable (spec R7)
    // ========================================================================

    @PostMapping("/pobo/preview")
    @Operation(summary = "How the payables would be paid today",
               description = "Per payable: POBO (payer, payment account, IHB current headroom, refusal if over) or direct (and why)")
    public ResponseEntity<List<PaymentRoutePreview>> getPoboPreview(@RequestBody PoboPreviewRequest request) {
        return ResponseEntity.ok(payablesService.getPoboPreview(request));
    }

    @GetMapping("/pobo/history")
    @Operation(summary = "Get POBO payment history", 
               description = "Returns executed POBO payments")
    public ResponseEntity<PayableListResponse> getPoboHistory(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            @RequestParam(required = false) UUID owningEntityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PayableSearchRequest request = PayableSearchRequest.builder()
            .corporateId(requireCorporate(corporateId))
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
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            @RequestBody CreateIntercompanyPayableRequest request
    ) {
        request.setCorporateId(requireCorporate(request.getCorporateId() != null ? request.getCorporateId() : corporateId));
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
            .corporateId(requireCorporate(corporateId))
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
        // Clearing the payable's cycle fields alone would leave its netting entry behind in the cycle.
        throw new BusinessException("Not available: removing individual payables from a netting cycle"
            + " — their netting entries are not removed; cancel the cycle instead");
    }

    @GetMapping("/netting/by-cycle/{cycleId}")
    @Operation(summary = "Get payables in netting cycle", 
               description = "Returns all payables included in a specific netting cycle")
    public ResponseEntity<PayableListResponse> getPayablesInNettingCycle(
            @PathVariable UUID cycleId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return ResponseEntity.ok(payablesService.getPayablesInNettingCycle(cycleId, page, size));
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
        return ResponseEntity.ok(payablesService.submitForApproval(payableId, submittedBy));
    }

    @PostMapping("/{payableId}/approve")
    @Operation(summary = "Approve payable",
               description = "Approve a pending payable")
    public ResponseEntity<PayableResponse> approvePayable(
            @PathVariable UUID payableId,
            @RequestBody ApprovePayableRequest request
    ) {
        request.setPayableId(payableId);
        return ResponseEntity.ok(payablesService.approvePayable(payableId, request.getApprovedBy(), request.getNotes()));
    }

    @PostMapping("/{payableId}/reject")
    @Operation(summary = "Reject payable",
               description = "Reject a pending payable")
    public ResponseEntity<PayableResponse> rejectPayable(
            @PathVariable UUID payableId,
            @RequestBody RejectPayableRequest request
    ) {
        return ResponseEntity.ok(payablesService.rejectPayable(
            payableId, request.getRejectedBy(), request.getRejectionReason()));
    }

    @PostMapping("/{payableId}/schedule")
    @Operation(summary = "Schedule payment",
               description = "Schedule an approved payable for payment")
    public ResponseEntity<PayableResponse> schedulePayment(
            @PathVariable UUID payableId,
            @RequestBody SchedulePaymentRequest request
    ) {
        return ResponseEntity.ok(payablesService.schedulePayment(
            payableId, request.getScheduledDate(), request.getPaymentPriority(),
            request.getPaymentMethod(), request.getScheduledBy()));
    }

    @PostMapping("/{payableId}/record-payment")
    @Operation(summary = "Record payment",
               description = "Record a payment against a payable")
    public ResponseEntity<PayableResponse> recordPayment(
            @PathVariable UUID payableId,
            @RequestBody RecordPaymentRequest request
    ) {
        request.setPayableId(payableId);
        return ResponseEntity.ok(payablesService.recordPayment(payableId, request));
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
        return ResponseEntity.ok(eachPayable(request.getPayableIds(),
            id -> payablesService.approvePayable(id, request.getApprovedBy(), null)));
    }

    @PostMapping("/batch/schedule")
    @Operation(summary = "Batch schedule payments", 
               description = "Schedule multiple payables for payment")
    public ResponseEntity<BatchOperationResponse> batchSchedule(
            @RequestBody BatchScheduleRequest request
    ) {
        return ResponseEntity.ok(eachPayable(request.getPayableIds(),
            id -> payablesService.schedulePayment(id, request.getScheduledDate(), request.getPaymentPriority(),
                null, request.getScheduledBy())));
    }

    /** Runs the single-payable action on each id; one failure does not stop the rest. */
    private static BatchOperationResponse eachPayable(List<UUID> ids, Consumer<UUID> action) {
        List<UUID> succeeded = new ArrayList<>();
        List<BatchOperationError> errors = new ArrayList<>();
        for (UUID id : ids) {
            try {
                action.accept(id);
                succeeded.add(id);
            } catch (RuntimeException e) {
                errors.add(BatchOperationError.builder().payableId(id).errorMessage(e.getMessage()).build());
            }
        }
        return BatchOperationResponse.builder()
            .successIds(succeeded)
            .errors(errors)
            .successCount(succeeded.size())
            .failedCount(errors.size())
            .build();
    }

    /** Payables belong to one corporate; never fall back to a default one. */
    private static UUID requireCorporate(UUID corporateId) {
        if (corporateId == null) {
            throw new BusinessException("corporateId is required (X-Corporate-Id header or request body)");
        }
        return corporateId;
    }
}