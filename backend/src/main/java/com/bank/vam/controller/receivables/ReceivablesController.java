package com.bank.vam.controller.receivables;

import com.bank.vam.dto.receivables.ReceivablesDto.*;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.receivables.Receivable.CollectionRoute;
import com.bank.vam.repository.receivables.ReceivableRepository;
import com.bank.vam.service.receivables.CoboReceivableService;
import com.bank.vam.service.receivables.IntercompanyRechargeReceivableService;
import com.bank.vam.service.receivables.ReceivableNettingService;
import com.bank.vam.service.receivables.ReceivablesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ReceivablesController - Unified Receivables Management
 * 
 * Provides endpoints for:
 * - Legacy: Invoices, E-commerce Orders, Payment VIBANs, POS Collections
 * - Phase 3: COBO workflow, Intercompany management, Netting integration
 * 
 * @see ReceivablesService
 * @see CoboReceivableService
 * @see ReceivableNettingService
 * @see IntercompanyRechargeReceivableService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/receivables")
@RequiredArgsConstructor
@Tag(name = "Receivables", description = "Unified Receivables Management - Invoices, E-commerce, VIBANs, COBO, Intercompany & Netting")
@CrossOrigin(origins = "*")
public class ReceivablesController {

    private final ReceivablesService receivablesService;
    
    // Phase 3 Services
    private final ReceivableRepository receivableRepository;
    private final CoboReceivableService coboService;
    private final ReceivableNettingService nettingService;
    private final IntercompanyRechargeReceivableService rechargeService;

    // ========================================================================
    // STATS (Legacy + Phase 3)
    // ========================================================================

    @GetMapping("/stats")
    @Operation(summary = "Get receivables stats", description = "Get unified stats across invoices, e-commerce, and VIBANs")
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId) {
        ReceivablesStatsResponse stats = receivablesService.getStats(corporateId);
        return ResponseEntity.ok(Map.of("success", true, "data", stats));
    }

    @GetMapping("/stats/phase3")
    @Operation(summary = "Get Phase 3 enhanced stats", description = "Stats including COBO, Intercompany, and Netting")
    public ResponseEntity<Map<String, Object>> getPhase3Stats(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId) {
        log.info("API: Get Phase 3 stats for corporate {}", corporateId);
        
        // Get COBO stats from service
        CoboReceivableService.CoboStats coboStats = coboService.getCoboStats(corporateId);
        
        // Get Netting summary from service
        ReceivableNettingService.NettingSummary nettingSummary = nettingService.getNettingSummary(corporateId);
        
        // Build response
        Map<String, Object> stats = new HashMap<>();
        stats.put("corporateId", corporateId);
        stats.put("coboStats", coboStats);
        stats.put("nettingSummary", nettingSummary);
        
        return ResponseEntity.ok(Map.of("success", true, "data", stats));
    }

    // ========================================================================
    // INVOICES (Legacy)
    // ========================================================================

    @GetMapping("/invoices")
    @Operation(summary = "Get all invoices", description = "List invoices with optional status and entity filter")
    public ResponseEntity<Map<String, Object>> getInvoices(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            @RequestHeader(value = "X-Legal-Entity-Id", required = false) UUID legalEntityId,
            @RequestParam(required = false) String status) {
        log.info("API: Get invoices - corporateId: {}, legalEntityId: {}, status: {}", corporateId, legalEntityId, status);
        List<InvoiceResponse> invoices = receivablesService.getAllInvoices(corporateId, legalEntityId, status);
        return ResponseEntity.ok(Map.of("success", true, "data", invoices));
    }

    @GetMapping("/invoices/{invoiceId}")
    @Operation(summary = "Get invoice details", description = "Get invoice with matched payments")
    public ResponseEntity<Map<String, Object>> getInvoice(@PathVariable UUID invoiceId) {
        InvoiceResponse invoice = receivablesService.getInvoice(invoiceId);
        return ResponseEntity.ok(Map.of("success", true, "data", invoice));
    }

    @PostMapping("/invoices")
    @Operation(summary = "Create invoice", description = "Create a new receivable invoice")
    public ResponseEntity<Map<String, Object>> createInvoice(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            @RequestBody CreateInvoiceRequest request) {
        log.info("API: Create invoice - corporateId from header: {}", corporateId);
        InvoiceResponse invoice = receivablesService.createInvoice(corporateId, request);
        return ResponseEntity.ok(Map.of("success", true, "data", invoice, "message", "Invoice created successfully"));
    }

    @PostMapping("/invoices/{invoiceId}/record-payment")
    @Operation(summary = "Record payment", description = "Record a payment against an invoice")
    public ResponseEntity<Map<String, Object>> recordPayment(
            @PathVariable UUID invoiceId,
            @RequestBody RecordPaymentRequest request) {
        InvoiceResponse invoice = receivablesService.recordPayment(invoiceId, request);
        return ResponseEntity.ok(Map.of("success", true, "data", invoice, "message", "Payment recorded successfully"));
    }

    // ========================================================================
    // E-COMMERCE ORDERS (Legacy)
    // ========================================================================

    @GetMapping("/ecommerce/orders")
    @Operation(summary = "Get e-commerce orders", description = "List marketplace orders with escrow tracking")
    public ResponseEntity<Map<String, Object>> getEcommerceOrders(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String escrowStatus) {
        List<EcommerceOrderResponse> orders = receivablesService.getAllEcommerceOrders(corporateId, platform, escrowStatus);
        return ResponseEntity.ok(Map.of("success", true, "data", orders));
    }

    @GetMapping("/ecommerce/platform-stats")
    @Operation(summary = "Get platform stats", description = "Get collection stats by platform (Amazon, Noon)")
    public ResponseEntity<Map<String, Object>> getPlatformStats(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId) {
        List<EcommercePlatformStats> stats = receivablesService.getPlatformStats(corporateId);
        return ResponseEntity.ok(Map.of("success", true, "data", stats));
    }

    @PostMapping("/ecommerce/orders/{orderId}/release-escrow")
    @Operation(summary = "Release escrow", description = "Release funds from escrow after delivery confirmation")
    public ResponseEntity<Map<String, Object>> releaseEscrow(
            @PathVariable UUID orderId,
            @RequestBody ReleaseEscrowRequest request) {
        EcommerceOrderResponse order = receivablesService.releaseEscrow(orderId, request);
        return ResponseEntity.ok(Map.of("success", true, "data", order, "message", "Escrow released successfully"));
    }

    // ========================================================================
    // PAYMENT VIBANs (Legacy)
    // ========================================================================

    @GetMapping("/vibans")
    @Operation(summary = "Get payment VIBANs", description = "List payment links/VIBANs with expected amounts")
    public ResponseEntity<Map<String, Object>> getPaymentVibans(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            @RequestParam(required = false) String status) {
        List<PaymentVibanResponse> vibans = receivablesService.getAllPaymentVibans(corporateId, status);
        return ResponseEntity.ok(Map.of("success", true, "data", vibans));
    }

    @PostMapping("/vibans")
    @Operation(summary = "Create payment VIBAN", description = "Create a new payment link with expected amount")
    public ResponseEntity<Map<String, Object>> createPaymentViban(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            @RequestBody CreatePaymentVibanRequest request) {
        PaymentVibanResponse viban = receivablesService.createPaymentViban(corporateId, request);
        return ResponseEntity.ok(Map.of("success", true, "data", viban, "message", "Payment VIBAN created successfully"));
    }

    @PostMapping("/vibans/{vibanId}/cancel")
    @Operation(summary = "Cancel payment VIBAN", description = "Cancel an active payment VIBAN")
    public ResponseEntity<Map<String, Object>> cancelPaymentViban(@PathVariable UUID vibanId) {
        PaymentVibanResponse viban = receivablesService.cancelPaymentViban(vibanId);
        return ResponseEntity.ok(Map.of("success", true, "data", viban, "message", "Payment VIBAN cancelled"));
    }

    // ========================================================================
    // UNMATCHED PAYMENTS (Legacy)
    // ========================================================================

    @GetMapping("/unmatched")
    @Operation(summary = "Get unmatched payments", description = "List payments that need manual matching")
    public ResponseEntity<Map<String, Object>> getUnmatchedPayments(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId) {
        List<UnmatchedPaymentResponse> payments = receivablesService.getUnmatchedPayments(corporateId);
        return ResponseEntity.ok(Map.of("success", true, "data", payments));
    }

    @PostMapping("/unmatched/{paymentId}/match")
    @Operation(summary = "Match payment to invoice", description = "Manually match an unmatched payment to an invoice")
    public ResponseEntity<Map<String, Object>> matchPayment(
            @PathVariable UUID paymentId,
            @RequestBody MatchPaymentRequest request) {
        UnmatchedPaymentResponse payment = receivablesService.matchPayment(paymentId, request);
        return ResponseEntity.ok(Map.of("success", true, "data", payment, "message", "Payment matched successfully"));
    }

    @PostMapping("/unmatched/{paymentId}/return")
    @Operation(summary = "Return payment", description = "Return an unmatched payment to sender")
    public ResponseEntity<Map<String, Object>> returnPayment(
            @PathVariable UUID paymentId,
            @RequestBody ReturnPaymentRequest request) {
        UnmatchedPaymentResponse payment = receivablesService.returnPayment(paymentId, request);
        return ResponseEntity.ok(Map.of("success", true, "data", payment, "message", "Payment return initiated"));
    }

    @PostMapping("/unmatched/{paymentId}/escalate")
    @Operation(summary = "Escalate payment", description = "Escalate an unmatched payment for review")
    public ResponseEntity<Map<String, Object>> escalatePayment(@PathVariable UUID paymentId) {
        UnmatchedPaymentResponse payment = receivablesService.escalatePayment(paymentId);
        return ResponseEntity.ok(Map.of("success", true, "data", payment, "message", "Payment escalated for review"));
    }

    // ========================================================================
    // POS COLLECTIONS (Legacy)
    // ========================================================================

    @GetMapping("/pos/collections")
    @Operation(summary = "Get POS collections", description = "List POS/terminal transactions")
    public ResponseEntity<Map<String, Object>> getPosCollections(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String merchantId) {
        List<PosCollectionResponse> collections = receivablesService.getAllPosCollections(corporateId, status, merchantId);
        return ResponseEntity.ok(Map.of("success", true, "data", collections));
    }

    @GetMapping("/pos/stats")
    @Operation(summary = "Get POS stats", description = "Get POS collection statistics")
    public ResponseEntity<Map<String, Object>> getPosStats(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId) {
        PosStatsResponse stats = receivablesService.getPosStats(corporateId);
        return ResponseEntity.ok(Map.of("success", true, "data", stats));
    }

    @GetMapping("/pos/merchants")
    @Operation(summary = "Get merchant summaries", description = "Get collection summary by merchant")
    public ResponseEntity<Map<String, Object>> getMerchantSummaries(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId) {
        List<MerchantSummary> summaries = receivablesService.getMerchantSummaries(corporateId);
        return ResponseEntity.ok(Map.of("success", true, "data", summaries));
    }

    // ========================================================================
    // PHASE 3: COBO ENDPOINTS
    // ========================================================================

    @Operation(summary = "Submit receivable for COBO collection")
    @PostMapping("/{receivableId}/cobo/submit")
    public ResponseEntity<Map<String, Object>> submitForCobo(
            @PathVariable UUID receivableId,
            @RequestBody CoboSubmitRequest request) {
        log.info("API: Submit receivable {} for COBO", receivableId);
        
        Receivable receivable = coboService.submitForCobo(
            receivableId,
            request.getCollectorEntityId(),
            request.getRequestedBy()
        );
        
        return ResponseEntity.ok(Map.of(
            "success", true, 
            "data", toReceivableResponse(receivable),
            "message", "COBO request submitted successfully"
        ));
    }

    @Operation(summary = "Approve COBO request (Treasury)")
    @PostMapping("/{receivableId}/cobo/approve")
    public ResponseEntity<Map<String, Object>> approveCobo(
            @PathVariable UUID receivableId,
            @RequestBody CoboApproveRequest request) {
        log.info("API: Approve COBO for receivable {}", receivableId);
        
        Receivable receivable = coboService.approveCobo(receivableId, request.getApprovedBy());
        
        return ResponseEntity.ok(Map.of(
            "success", true, 
            "data", toReceivableResponse(receivable),
            "message", "COBO request approved"
        ));
    }

    @Operation(summary = "Reject COBO request (Treasury)")
    @PostMapping("/{receivableId}/cobo/reject")
    public ResponseEntity<Map<String, Object>> rejectCobo(
            @PathVariable UUID receivableId,
            @RequestBody CoboRejectRequest request) {
        log.info("API: Reject COBO for receivable {}", receivableId);
        
        Receivable receivable = coboService.rejectCobo(
            receivableId, 
            request.getRejectedBy(), 
            request.getReason()
        );
        
        return ResponseEntity.ok(Map.of(
            "success", true, 
            "data", toReceivableResponse(receivable),
            "message", "COBO request rejected"
        ));
    }

    @Operation(summary = "Execute COBO collection")
    @PostMapping("/{receivableId}/cobo/collect")
    public ResponseEntity<Map<String, Object>> executeCoboCollection(
            @PathVariable UUID receivableId,
            @RequestBody CoboExecuteRequest request) {
        log.info("API: Execute COBO collection for receivable {}", receivableId);
        
        // Build service request using service's inner class
        CoboReceivableService.CoboCollectionRequest serviceRequest = 
            CoboReceivableService.CoboCollectionRequest.builder()
                .receivableId(receivableId)
                .treasuryVaId(request.getTreasuryVaId())
                .subsidiaryVaId(request.getSubsidiaryVaId())
                .amount(request.getAmount())
                .paymentReference(request.getPaymentReference())
                .notes(request.getNotes())
                .build();
        
        CoboReceivableService.CoboCollectionResult result = coboService.executeCoboCollection(serviceRequest);
        
        return ResponseEntity.ok(Map.of(
            "success", true, 
            "data", result,
            "message", "COBO collection executed successfully"
        ));
    }

    @Operation(summary = "Preview COBO collection (calculate fees)")
    @GetMapping("/{receivableId}/cobo/preview")
    public ResponseEntity<Map<String, Object>> previewCobo(@PathVariable UUID receivableId) {
        log.info("API: Preview COBO for receivable {}", receivableId);
        
        // Use service's inner class directly
        CoboReceivableService.CoboPreviewResult result = coboService.previewCoboCollection(receivableId);
        
        return ResponseEntity.ok(Map.of("success", true, "data", result));
    }

    @Operation(summary = "Get receivables pending COBO approval")
    @GetMapping("/cobo/pending-approval")
    public ResponseEntity<Map<String, Object>> getPendingCoboApproval(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId) {
        log.info("API: Get pending COBO approvals for corporate {}", corporateId);
        
        List<Receivable> receivables;
        if (corporateId != null) {
            receivables = receivableRepository.findPendingCoboApprovalByCorporate(corporateId);
        } else {
            receivables = coboService.findPendingCoboApproval();
        }
        
        List<ReceivableResponse> responses = receivables.stream()
            .map(this::toReceivableResponse)
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(Map.of("success", true, "data", responses));
    }

    @Operation(summary = "Get COBO statistics")
    @GetMapping("/cobo/stats")
    public ResponseEntity<Map<String, Object>> getCoboStats(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId) {
        log.info("API: Get COBO stats for corporate {}", corporateId);
        
        // Use service's inner class directly
        CoboReceivableService.CoboStats stats = coboService.getCoboStats(corporateId);
        
        return ResponseEntity.ok(Map.of("success", true, "data", stats));
    }

    // ========================================================================
    // PHASE 3: NETTING ENDPOINTS
    // ========================================================================

    @Operation(summary = "Get netting-eligible receivables")
    @GetMapping("/netting/eligible")
    public ResponseEntity<Map<String, Object>> getNettingEligible(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId) {
        log.info("API: Get netting-eligible receivables for corporate {}", corporateId);
        
        // Use service's inner class directly
        List<ReceivableNettingService.NettingEligibleReceivable> receivables;
        if (corporateId != null) {
            receivables = nettingService.getNettingEligibleReceivables(corporateId);
        } else {
            // Get from all corporates
            List<Receivable> all = receivableRepository.findNettingEligibleReceivables();
            receivables = all.stream()
                .map(r -> ReceivableNettingService.NettingEligibleReceivable.builder()
                    .id(r.getId())
                    .receivableNumber(r.getReceivableNumber())
                    .customerName(r.getCustomerName())
                    .owningEntityCode(r.getOwningEntityCode())
                    .intercompanyEntityCode(r.getIntercompanyEntityCode())
                    .outstandingAmount(r.getOutstandingAmount())
                    .currencyCode(r.getCurrencyCode())
                    .dueDate(r.getDueDate() != null ? r.getDueDate().toString() : null)
                    .isIntercompany(Boolean.TRUE.equals(r.getIsIntercompany()))
                    .status(r.getStatus().name())
                    .build())
                .collect(Collectors.toList());
        }
        
        return ResponseEntity.ok(Map.of("success", true, "data", receivables));
    }

    @Operation(summary = "Add receivable to netting cycle")
    @PostMapping("/{receivableId}/netting/add")
    public ResponseEntity<Map<String, Object>> addToNettingCycle(
            @PathVariable UUID receivableId,
            @RequestBody NettingAddRequest request) {
        log.info("API: Add receivable {} to netting cycle {}", receivableId, request.getNettingCycleId());
        
        // Service method takes only (receivableId, cycleId) - no addedBy parameter
        ReceivableNettingService.NettingAddResult result = nettingService.addToNettingCycle(
            receivableId, 
            request.getNettingCycleId()
        );
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", result,
            "message", "Receivable added to netting cycle"
        ));
    }

    @Operation(summary = "Remove receivable from netting cycle")
    @PostMapping("/{receivableId}/netting/remove")
    public ResponseEntity<Map<String, Object>> removeFromNettingCycle(
            @PathVariable UUID receivableId) {
        log.info("API: Remove receivable {} from netting cycle", receivableId);
        
        // Service method takes only receivableId
        ReceivableNettingService.NettingRemoveResult result = nettingService.removeFromNettingCycle(receivableId);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", result,
            "message", "Receivable removed from netting cycle"
        ));
    }

    @Operation(summary = "Get receivables in netting cycle")
    @GetMapping("/netting/cycle/{cycleId}")
    public ResponseEntity<Map<String, Object>> getReceivablesInCycle(@PathVariable UUID cycleId) {
        log.info("API: Get receivables in netting cycle {}", cycleId);
        
        // Use the correct service method
        List<ReceivableNettingService.NettingReceivableEntry> entries = 
            nettingService.getReceivablesInCycle(cycleId);
        
        return ResponseEntity.ok(Map.of("success", true, "data", entries));
    }

    @Operation(summary = "Get netting summary statistics")
    @GetMapping("/netting/summary")
    public ResponseEntity<Map<String, Object>> getNettingSummary(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId) {
        log.info("API: Get netting summary for corporate {}", corporateId);
        
        // Use service's inner class directly
        ReceivableNettingService.NettingSummary summary = nettingService.getNettingSummary(corporateId);
        
        return ResponseEntity.ok(Map.of("success", true, "data", summary));
    }

    @Operation(summary = "Set receivable netting eligibility")
    @PutMapping("/{receivableId}/netting/eligible")
    public ResponseEntity<Map<String, Object>> setNettingEligible(
            @PathVariable UUID receivableId,
            @RequestBody SetNettingEligibleRequest request) {
        log.info("API: Set netting eligible {} for receivable {}", request.isEligible(), receivableId);
        
        Receivable receivable = nettingService.setNettingEligible(receivableId, request.isEligible());
        
        return ResponseEntity.ok(Map.of(
            "success", true, 
            "data", toReceivableResponse(receivable),
            "message", "Netting eligibility updated"
        ));
    }

    // ========================================================================
    // PHASE 3: INTERCOMPANY ENDPOINTS
    // ========================================================================

    @Operation(summary = "Get intercompany receivables")
    @GetMapping("/intercompany")
    public ResponseEntity<Map<String, Object>> getIntercompanyReceivables(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("API: Get intercompany receivables for corporate {}", corporateId);
        
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Receivable> receivablePage;
        
        if (corporateId != null) {
            receivablePage = receivableRepository.findIntercompanyReceivablesByCorporate(corporateId, pageable);
        } else {
            receivablePage = receivableRepository.findIntercompanyReceivables(pageable);
        }
        
        List<ReceivableResponse> responses = receivablePage.getContent().stream()
            .map(this::toReceivableResponse)
            .collect(Collectors.toList());
        
        Map<String, Object> pageData = new HashMap<>();
        pageData.put("content", responses);
        pageData.put("totalElements", receivablePage.getTotalElements());
        pageData.put("totalPages", receivablePage.getTotalPages());
        pageData.put("page", page);
        pageData.put("size", size);
        
        return ResponseEntity.ok(Map.of("success", true, "data", pageData));
    }

    @Operation(summary = "Get intercompany balance summary")
    @GetMapping("/intercompany/balance")
    public ResponseEntity<Map<String, Object>> getIntercompanyBalance(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId) {
        log.info("API: Get intercompany balance for corporate {}", corporateId);
        
        List<Object[]> balances = receivableRepository.getIntercompanyBalanceSummary(corporateId);
        
        List<IntercompanyBalanceSummary> summaries = balances.stream()
            .map(row -> IntercompanyBalanceSummary.builder()
                .entityId((UUID) row[0])
                .entityCode((String) row[1])
                .entityName((String) row[2])
                .totalReceivable((BigDecimal) row[3])
                .receivableCount(((Number) row[4]).intValue())
                .build())
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(Map.of("success", true, "data", summaries));
    }

    @Operation(summary = "Get intercompany receivables between entities")
    @GetMapping("/intercompany/between")
    public ResponseEntity<Map<String, Object>> getIntercompanyBetweenEntities(
            @RequestParam UUID entity1Id,
            @RequestParam UUID entity2Id) {
        log.info("API: Get intercompany receivables between {} and {}", entity1Id, entity2Id);
        
        List<Receivable> receivables = receivableRepository.findIntercompanyReceivablesBetweenEntities(entity1Id, entity2Id);
        
        List<ReceivableResponse> responses = receivables.stream()
            .map(this::toReceivableResponse)
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(Map.of("success", true, "data", responses));
    }

    @Operation(summary = "Create intercompany receivable")
    @PostMapping("/intercompany")
    public ResponseEntity<Map<String, Object>> createIntercompanyReceivable(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            @RequestBody CreateIntercompanyReceivableRequest request) {
        log.info("API: Create intercompany receivable for corporate {}", corporateId);
        
        // Build service request using service's inner class
        IntercompanyRechargeReceivableService.IntercompanyReceivableRequest serviceRequest = 
            IntercompanyRechargeReceivableService.IntercompanyReceivableRequest.builder()
                .corporateId(corporateId != null ? corporateId : request.getCorporateId())
                .owningEntityId(request.getOwningEntityId())
                .counterpartyEntityId(request.getIntercompanyEntityId())
                .amount(request.getGrossAmount())
                .currencyCode(request.getCurrencyCode())
                .dueDate(request.getDueDate())
                .description(request.getDescription())
                .createdBy(request.getCreatedBy())
                .build();
        
        IntercompanyRechargeReceivableService.IntercompanyReceivableResult result = 
            rechargeService.createIntercompanyReceivable(serviceRequest);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", result,
            "message", "Intercompany receivable created"
        ));
    }

    @Operation(summary = "Link receivable to counterparty payable")
    @PostMapping("/{receivableId}/link-payable/{payableId}")
    public ResponseEntity<Map<String, Object>> linkCounterpartyPayable(
            @PathVariable UUID receivableId,
            @PathVariable UUID payableId) {
        log.info("API: Link receivable {} to payable {}", receivableId, payableId);
        
        rechargeService.linkCounterpartyPayable(receivableId, payableId);
        
        Receivable receivable = receivableRepository.findById(receivableId)
            .orElseThrow(() -> new RuntimeException("Receivable not found: " + receivableId));
        
        return ResponseEntity.ok(Map.of(
            "success", true, 
            "data", toReceivableResponse(receivable),
            "message", "Counterparty payable linked"
        ));
    }

    // ========================================================================
    // PHASE 3: ENTITY CONTEXT ENDPOINTS
    // ========================================================================

    @Operation(summary = "Get receivables by owning entity")
    @GetMapping("/by-entity/{entityId}")
    public ResponseEntity<Map<String, Object>> getByEntity(
            @PathVariable UUID entityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("API: Get receivables by owning entity {}", entityId);
        
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Receivable> receivablePage = receivableRepository.findByOwningEntityId(entityId, pageable);
        
        List<ReceivableResponse> responses = receivablePage.getContent().stream()
            .map(this::toReceivableResponse)
            .collect(Collectors.toList());
        
        Map<String, Object> pageData = new HashMap<>();
        pageData.put("content", responses);
        pageData.put("totalElements", receivablePage.getTotalElements());
        pageData.put("totalPages", receivablePage.getTotalPages());
        pageData.put("page", page);
        pageData.put("size", size);
        
        return ResponseEntity.ok(Map.of("success", true, "data", pageData));
    }

    @Operation(summary = "Get receivables by customer party")
    @GetMapping("/by-party/{partyId}")
    public ResponseEntity<Map<String, Object>> getByParty(
            @PathVariable UUID partyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("API: Get receivables by customer party {}", partyId);
        
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Receivable> receivablePage = receivableRepository.findByCustomerPartyId(partyId, pageable);
        
        List<ReceivableResponse> responses = receivablePage.getContent().stream()
            .map(this::toReceivableResponse)
            .collect(Collectors.toList());
        
        Map<String, Object> pageData = new HashMap<>();
        pageData.put("content", responses);
        pageData.put("totalElements", receivablePage.getTotalElements());
        pageData.put("totalPages", receivablePage.getTotalPages());
        pageData.put("page", page);
        pageData.put("size", size);
        
        return ResponseEntity.ok(Map.of("success", true, "data", pageData));
    }

    @Operation(summary = "Get receivables by collection route")
    @GetMapping("/by-route")
    public ResponseEntity<Map<String, Object>> getByRoute(
            @RequestParam String route,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("API: Get receivables by collection route {}", route);
        
        CollectionRoute collectionRoute = CollectionRoute.valueOf(route.toUpperCase());
        
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Receivable> receivablePage = receivableRepository.findByCollectionRoute(collectionRoute, pageable);
        
        List<ReceivableResponse> responses = receivablePage.getContent().stream()
            .map(this::toReceivableResponse)
            .collect(Collectors.toList());
        
        Map<String, Object> pageData = new HashMap<>();
        pageData.put("content", responses);
        pageData.put("totalElements", receivablePage.getTotalElements());
        pageData.put("totalPages", receivablePage.getTotalPages());
        pageData.put("page", page);
        pageData.put("size", size);
        
        return ResponseEntity.ok(Map.of("success", true, "data", pageData));
    }

    @Operation(summary = "Update receivable owning entity")
    @PutMapping("/{receivableId}/entity")
    public ResponseEntity<Map<String, Object>> updateOwningEntity(
            @PathVariable UUID receivableId,
            @RequestBody UpdateEntityRequest request) {
        log.info("API: Update owning entity for receivable {}", receivableId);
        
        receivableRepository.updateOwningEntity(
            receivableId, 
            request.getOwningEntityId(),
            request.getOwningEntityCode(),
            request.getOwningEntityName()
        );
        
        Receivable receivable = receivableRepository.findById(receivableId)
            .orElseThrow(() -> new RuntimeException("Receivable not found: " + receivableId));
        
        return ResponseEntity.ok(Map.of(
            "success", true, 
            "data", toReceivableResponse(receivable),
            "message", "Owning entity updated"
        ));
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private ReceivableResponse toReceivableResponse(Receivable r) {
        return ReceivableResponse.builder()
            .id(r.getId())
            .receivableNumber(r.getReceivableNumber())
            .externalReference(r.getExternalReference())
            .receivableType(r.getReceivableType() != null ? r.getReceivableType().name() : null)
            .corporateId(r.getCorporateId())
            .programId(r.getProgramId())
            .virtualAccountId(r.getVirtualAccountId())
            // Entity Context
            .owningEntityId(r.getOwningEntityId())
            .owningEntityCode(r.getOwningEntityCode())
            .owningEntityName(r.getOwningEntityName())
            // Customer Party
            .customerPartyId(r.getCustomerPartyId())
            // Intercompany
            .isIntercompany(Boolean.TRUE.equals(r.getIsIntercompany()))
            .intercompanyEntityId(r.getIntercompanyEntityId())
            .intercompanyEntityCode(r.getIntercompanyEntityCode())
            .intercompanyEntityName(r.getIntercompanyEntityName())
            .counterpartyPayableId(r.getCounterpartyPayableId())
            // COBO
            .isCobo(Boolean.TRUE.equals(r.getIsCobo()))
            .coboCollectorEntityId(r.getCoboCollectorEntityId())
            .coboCollectorEntityCode(r.getCoboCollectorEntityCode())
            .coboRequestStatus(r.getCoboRequestStatus() != null ? r.getCoboRequestStatus().name() : null)
            .coboTransactionRef(r.getCoboTransactionRef())
            .coboRechargeId(r.getCoboRechargeId())
            .coboIhbDepositId(r.getCoboIhbDepositId())
            // Netting
            .nettingEligible(Boolean.TRUE.equals(r.getNettingEligible()))
            .nettingCycleId(r.getNettingCycleId())
            .nettingCycleRef(r.getNettingCycleRef())
            .nettingEntryId(r.getNettingEntryId())
            .nettingStatus(r.getNettingStatus() != null ? r.getNettingStatus().name() : null)
            .nettingSettlementRef(r.getNettingSettlementRef())
            // Collection Route
            .collectionRoute(r.getCollectionRoute() != null ? r.getCollectionRoute().name() : null)
            .collectionRouteDescription(r.getCollectionRouteDescription())
            // Customer Info
            .customerId(r.getCustomerId())
            .customerName(r.getCustomerName())
            .customerEmail(r.getCustomerEmail())
            // VIBAN
            .primaryVibanId(r.getPrimaryVibanId())
            .viban(r.getViban())
            // Amounts
            .currencyCode(r.getCurrencyCode())
            .grossAmount(r.getGrossAmount())
            .discountAmount(r.getDiscountAmount())
            .taxAmount(r.getTaxAmount())
            .netAmount(r.getNetAmount())
            .paidAmount(r.getPaidAmount())
            .outstandingAmount(r.getOutstandingAmount())
            // Dates
            .issueDate(r.getIssueDate() != null ? r.getIssueDate().toString() : null)
            .dueDate(r.getDueDate() != null ? r.getDueDate().toString() : null)
            // Status
            .status(r.getStatus().name())
            .paymentStatus(r.getPaymentStatus() != null ? r.getPaymentStatus().name() : null)
            // Metadata
            .description(r.getDescription())
            // Audit
            .createdAt(r.getCreatedAt() != null ? r.getCreatedAt().toString() : null)
            .updatedAt(r.getUpdatedAt() != null ? r.getUpdatedAt().toString() : null)
            .build();
    }
}