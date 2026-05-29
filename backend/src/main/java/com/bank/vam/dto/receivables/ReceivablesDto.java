package com.bank.vam.dto.receivables;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Enhanced Receivables DTOs with VIBAN, Hierarchy, and Settlement VA Integration.
 * 
 * Week 3 Enhancements:
 * - VIBAN fields for payment routing
 * - Hierarchy fields for aggregated reporting
 * - Auto-reconciliation status fields
 * 
 * Phase 3 Integration:
 * - Collection processing with fee tracking
 * - COBO (Collect-On-Behalf-Of) operations
 * - Escrow management with fee breakdown
 * - Fee posting to Settlement VA
 * - Enhanced matching with targetVaId support
 * 
 * Backward Compatible:
 * - All existing fields preserved
 * - New fields are optional/nullable
 * - Existing API contracts unchanged
 */
public class ReceivablesDto {

    // ========================================================================
    // STATS (Enhanced with VIBAN metrics)
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReceivablesStatsResponse {
        // Existing fields (backward compatible)
        private BigDecimal totalReceivables;
        private BigDecimal collected;
        private BigDecimal inEscrow;
        private Integer pendingCount;
        
        // NEW: Outstanding tracking (Phase 3)
        private BigDecimal outstanding;
        private BigDecimal overdueAmount;
        private BigDecimal unmatchedAmount;
        
        // Invoice breakdown
        private BigDecimal invoiceTotal;
        private BigDecimal invoiceCollected;
        
        // E-commerce marketplace breakdown
        private BigDecimal ecommerceTotal;
        private BigDecimal ecommerceReleased;
        private BigDecimal ecommerceInEscrow;
        
        // VIBAN breakdown
        private BigDecimal vibanTotal;
        private BigDecimal vibanCollected;
        
        // POS Collections breakdown
        private BigDecimal posTotal;
        private BigDecimal posSettled;
        private BigDecimal posPendingSettlement;
        
        // Counts
        private Integer invoiceCount;
        private Integer ecommerceOrderCount;
        private Integer vibanCount;
        private Integer unmatchedPaymentCount;
        private Integer posTransactionCount;
        
        // NEW: Auto-reconciliation metrics (Week 3)
        private Integer autoMatchedCount;
        private Integer manualMatchedCount;
        private BigDecimal autoMatchedAmount;
        private BigDecimal manualMatchedAmount;
        private Double autoMatchRate;  // Percentage
        
        // NEW: Hierarchy aggregation (Week 3)
        private List<HierarchyReceivableSummary> hierarchySummary;
    }

    // ========================================================================
    // INVOICES (Enhanced with VIBAN link)
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvoiceResponse {
        // Existing fields (backward compatible)
        private UUID id;
        private String invoiceNumber;
        private String customerName;
        private UUID customerId;
        private String customerVaNumber;
        private LocalDate invoiceDate;
        private LocalDate dueDate;
        private BigDecimal amount;
        private BigDecimal paidAmount;
        private BigDecimal outstandingAmount;
        private String currencyCode;
        private String status;
        private String description;
        private List<MatchedPaymentResponse> matchedPayments;
        
        // NEW: VIBAN integration (Week 3)
        private UUID vibanId;
        private String vibanNumber;
        private String viban;
        private String paymentLink;
        private String qrCodeData;
        
        // NEW: Auto-reconciliation (Week 3)
        private Boolean autoReconcile;
        private BigDecimal amountTolerance;
        private Boolean allowPartialPayment;
        
        // NEW: Hierarchy context (Week 3)
        private UUID hierarchyNodeId;
        private String hierarchyPath;
        private String hierarchyNodeName;
        
        // NEW: Aging (Week 3)
        private String agingBucket;
        private Integer daysUntilDue;
        private Integer daysOverdue;
        private Boolean isOverdue;

        // Phase 3: Entity Context
        private UUID owningEntityId;
        private String owningEntityCode;
        private String owningEntityName;

        // Phase 3: Intercompany
        private Boolean isIntercompany;
        private UUID intercompanyEntityId;
        private String intercompanyEntityCode;
        private String intercompanyEntityName;
        private UUID counterpartyPayableId;

        // Phase 3: COBO
        private Boolean isCobo;
        private UUID coboCollectorEntityId;
        private String coboCollectorEntityCode;
        private String coboCollectorEntityName;
        private String coboRequestStatus;
        private String coboTransactionRef;
        private UUID coboRechargeId;
        private UUID coboIhbDepositId;
        private LocalDateTime coboRequestedAt;
        private String coboRequestedBy;
        private LocalDateTime coboActionedAt;
        private String coboActionedBy;

        // Phase 3: Netting
        private Boolean nettingEligible;
        private UUID nettingCycleId;
        private String nettingCycleRef;
        private UUID nettingEntryId;
        private String nettingStatus;
        private String nettingSettlementRef;

        // Phase 3: Collection Route
        private String collectionRoute;
        private UUID subsidiaryVaId;
        private UUID treasuryVaId;

        // Audit
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MatchedPaymentResponse {
        // Existing fields (backward compatible)
        private String paymentRef;
        private BigDecimal amount;
        private LocalDateTime matchDate;
        private String matchType;
        private Integer confidence;
        
        // NEW: VIBAN tracking (Week 3)
        private UUID vibanId;
        private String viban;
        private Boolean autoMatched;
        private String matchReason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateInvoiceRequest {
        // Existing fields (backward compatible)
        private UUID customerId;
        private String customerName;
        private String customerVaNumber;
        private BigDecimal amount;
        private String currencyCode;
        private LocalDate dueDate;
        private String description;

        // Corporate and Entity context
        private UUID corporateId;
        private UUID owningEntityId;
        private String owningEntityCode;

        // NEW: Target VA (Phase 3)
        private UUID targetVaId;
        private Boolean generateViban;
        
        // NEW: VIBAN options (Week 3)
        private Boolean createViban;           // Auto-create VIBAN for this invoice
        private UUID existingVibanId;          // Link to existing VIBAN
        private Integer vibanExpiryDays;       // VIBAN validity period
        
        // NEW: Auto-reconciliation settings (Week 3)
        private Boolean autoReconcile;
        private BigDecimal amountTolerancePercent;
        private Boolean allowPartialPayment;
        private Boolean allowOverpayment;
        
        // NEW: Hierarchy context (Week 3)
        private UUID hierarchyNodeId;
        
        // NEW: Line items (Week 3)
        private List<InvoiceLineItem> lineItems;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvoiceLineItem {
        private Integer lineNumber;
        private String itemCode;
        private String description;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal discountPercent;
        private BigDecimal taxPercent;
        private BigDecimal lineAmount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecordPaymentRequest {
        // Existing fields (backward compatible)
        private BigDecimal amount;
        private LocalDate paymentDate;
        private String paymentReference;
        private String matchType;
        
        // NEW: VIBAN tracking (Week 3)
        private UUID vibanId;
        private UUID transactionId;
        private String payerName;
        private String payerAccount;
        private String remittanceInfo;
    }

    // ========================================================================
    // COLLECTION PROCESSING - NEW (Phase 3)
    // ========================================================================

    /**
     * Request to process incoming collection.
     * Supports VIBAN routing and auto-matching.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessCollectionRequest {
        private UUID targetVaId;            // Target VA for collection (required if no VIBAN)
        private String viban;               // VIBAN for auto-routing (optional)
        private BigDecimal amount;
        private String currencyCode;
        
        // Sender details
        private String senderName;
        private String senderAccount;
        private String senderBank;
        
        // Payment details
        private String remittanceInfo;
        private String bankReference;
        private String externalReference;
    }

    /**
     * Response from collection processing with fee breakdown.
     *
     * Enhanced in v5.3 to include:
     * - correlationId: Links all 4-leg accounting entries
     * - shadowVaId/shadowVaNumber: CBS entry point (mirrors physical account)
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CollectionResponse {
        private String transactionReference;
        private String correlationId;       // Links all accounting entries (v5.3)

        // Amount breakdown
        private BigDecimal grossAmount;
        private BigDecimal fee;             // Collection fee
        private BigDecimal netAmount;       // Credited to target VA

        // Target VA (final destination)
        private UUID targetVaId;
        private String targetVaNumber;
        private BigDecimal targetVaBalance; // Balance after credit

        // Shadow VA (CBS entry point - v5.3)
        private UUID shadowVaId;
        private String shadowVaNumber;

        // Matching result
        private String matchStatus;         // AUTO_MATCHED, VIBAN_ROUTED, UNMATCHED
        private String matchedInvoiceNumber;
        private UUID matchedReceivableId;

        // Settlement VA (for transparency - internal clearing)
        private UUID settlementVaId;
        private String settlementVaNumber;

        private LocalDateTime processedAt;
    }

    // ========================================================================
    // COBO (Collect On Behalf Of) - NEW (Phase 3)
    // ========================================================================

    /**
     * Request to process COBO collection.
     * Higher fee rate applies for COBO transactions.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CoboCollectionRequest {
        private UUID targetVaId;            // Target VA for collection
        private BigDecimal amount;
        private String currencyCode;
        
        // On-behalf-of details
        private String onBehalfOfEntity;    // Entity name
        private UUID onBehalfOfVaId;        // Entity's VA (for tracking)
        
        // Payer details
        private String payerName;
        private String payerAccount;
        
        // Collection details
        private String description;
        private String externalReference;
        private String collectedBy;
    }

    /**
     * Response from COBO collection with fee breakdown.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CoboCollectionResponse {
        private String transactionReference;
        
        // Amount breakdown
        private BigDecimal grossAmount;
        private BigDecimal fee;             // COBO service fee (higher than regular)
        private BigDecimal netAmount;
        
        // Target VA
        private UUID targetVaId;
        private String targetVaNumber;
        private BigDecimal targetVaBalance;
        
        // On-behalf-of
        private String onBehalfOfEntity;
        private UUID onBehalfOfVaId;
        
        // Settlement VA
        private UUID settlementVaId;
        private String settlementVaNumber;
        
        private LocalDateTime processedAt;
    }

    // ========================================================================
    // E-COMMERCE ORDERS (Enhanced with escrow VIBAN)
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EcommerceOrderResponse {
        // Existing fields (backward compatible)
        private UUID id;
        private String platform;
        private String platformLogo;
        private String platformOrderId;
        private LocalDateTime orderDate;
        private String productName;
        private Integer quantity;
        private String buyerName;
        private String buyerCity;
        private BigDecimal orderAmount;
        private BigDecimal platformFee;
        private BigDecimal netAmount;
        private String currency;
        private String paymentStatus;
        private String escrowStatus;
        private String deliveryStatus;
        private LocalDateTime paymentReceivedAt;
        private LocalDateTime deliveredAt;
        private LocalDateTime releasedAt;
        
        // NEW: VIBAN for escrow (Week 3)
        private UUID escrowVibanId;
        private String escrowViban;
        
        // NEW: Auto-reconciliation (Week 3)
        private Boolean autoReconciled;
        private Integer matchConfidence;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EcommercePlatformStats {
        // Existing fields (backward compatible)
        private String platform;
        private String platformLogo;
        private Integer orderCount;
        private BigDecimal collected;
        private BigDecimal inEscrow;
        private BigDecimal pending;
        
        // NEW: Auto-reconciliation stats (Week 3)
        private Integer autoMatchedCount;
        private Double autoMatchRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReleaseEscrowRequest {
        // Existing fields (backward compatible)
        private String reason;
        
        // NEW: Partial release (Week 3)
        private BigDecimal releaseAmount;   // Null = full release
        private Boolean partialRelease;
        
        // NEW: Target details (Phase 3)
        private UUID escrowVaId;
        private UUID targetVaId;
        private BigDecimal amount;
        private String releasedBy;
    }

    /**
     * Response from escrow release with fee breakdown.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EscrowReleaseResponse {
        private UUID escrowId;
        private String releaseReference;
        
        // Amount breakdown
        private BigDecimal grossAmount;
        private BigDecimal fee;             // Escrow service fee
        private BigDecimal netAmount;       // Credited to target
        
        // Target VA
        private UUID targetVaId;
        private String targetVaNumber;
        private BigDecimal targetVaBalance;
        
        // Settlement VA
        private UUID settlementVaId;
        private String settlementVaNumber;
        
        private String reason;
        private LocalDateTime releasedAt;
    }

    // ========================================================================
    // PAYMENT VIBANs (Enhanced)
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentVibanResponse {
        // Existing fields (backward compatible)
        private UUID id;
        private String virtualIban;
        private String reference;
        private String customerName;
        private BigDecimal expectedAmount;
        private BigDecimal receivedAmount;
        private String currency;
        private String status;
        private LocalDateTime createdAt;
        private LocalDateTime expiresAt;
        private String purpose;
        private String paymentLink;
        
        // NEW: Receivable link (Week 3)
        private UUID linkedReceivableId;
        private String linkedReceivableNumber;
        private String receivableType;
        
        // NEW: Auto-reconciliation (Week 3)
        private Boolean autoReconcile;
        private BigDecimal amountTolerance;
        private Integer paymentCount;
        private LocalDateTime lastPaymentAt;
        
        // NEW: QR Code (Week 3)
        private String qrCodeData;
        private String qrCodeUrl;

        // Phase 1: Published VA fields
        private Boolean isPublished;
        private UUID targetVaId;
        private String targetVaNumber;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreatePaymentVibanRequest {
        // Existing fields (backward compatible)
        private String customerName;
        private BigDecimal expectedAmount;
        private String currency;
        private String purpose;
        private Integer expiresInHours;
        
        // NEW: Receivable link (Week 3)
        private UUID receivableId;           // Link to existing receivable
        private String receivableType;       // INVOICE, ORDER, etc.
        
        // NEW: Auto-reconciliation (Week 3)
        private Boolean autoReconcile;
        private BigDecimal amountTolerancePercent;
        private BigDecimal minAmount;
        private BigDecimal maxAmount;
        
        // NEW: VIBAN generation (Week 3)
        private UUID virtualAccountId;       // Target VA for payments
        private UUID programId;              // Program for VIBAN pool
        private Boolean generateQrCode;
        private Boolean generatePaymentLink;
    }

    // ========================================================================
    // UNMATCHED PAYMENTS (Enhanced with suggestions)
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UnmatchedPaymentResponse {
        // Existing fields (backward compatible)
        private UUID id;
        private String paymentReference;
        private String senderName;
        private String senderAccount;
        private BigDecimal amount;
        private String currencyCode;
        private LocalDateTime receivedDate;
        private String vaNumber;
        private String vaName;
        private String remittanceInfo;
        private List<SuggestedMatchResponse> suggestedMatches;
        private String status;
        
        // NEW: VIBAN info (Week 3)
        private UUID vibanId;
        private String viban;
        
        // NEW: Target VA (Phase 3)
        private UUID targetVaId;
        
        // NEW: Match attempt tracking (Week 3)
        private Integer matchAttempts;
        private LocalDateTime lastMatchAttemptAt;
        
        // NEW: Escalation info (Week 3)
        private Boolean escalated;
        private String escalatedTo;
        private LocalDateTime escalatedAt;
        private String escalationReason;
        
        // NEW: Age tracking (Week 3)
        private Integer ageInDays;
        private String urgency;  // LOW, MEDIUM, HIGH, CRITICAL
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SuggestedMatchResponse {
        // Existing fields (backward compatible)
        private String invoiceNumber;
        private String customerName;
        private BigDecimal invoiceAmount;
        private Integer confidence;
        private String matchReason;
        
        // NEW: Enhanced matching (Week 3)
        private UUID receivableId;
        private String receivableType;
        private BigDecimal outstandingAmount;
        private LocalDate dueDate;
        private Boolean overdue;
    }

    // ========================================================================
    // PAYMENT MATCHING - ENHANCED (Phase 3)
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MatchPaymentRequest {
        // Existing fields (backward compatible)
        private UUID invoiceId;
        private String matchType;
        
        // NEW: Enhanced matching (Week 3)
        private UUID receivableId;          // Alternative to invoiceId
        private BigDecimal appliedAmount;   // For partial matching
        private String notes;
        
        // NEW: Fee processing (Phase 3) - REQUIRED for FeePostingService
        private UUID targetVaId;            // VA where payment will be credited
        private String matchedBy;           // User who performed the match
    }

    /**
     * Response from manual payment matching with fee info.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MatchPaymentResponse {
        private UUID paymentId;
        private String paymentReference;
        
        // Matched receivable
        private UUID invoiceId;
        private String invoiceNumber;
        private BigDecimal matchedAmount;
        
        // Receivable status after match
        private String receivableStatus;
        private BigDecimal outstandingAmount;
        
        // Fee charged for manual matching
        private BigDecimal matchingFee;
        
        private LocalDateTime matchedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReturnPaymentRequest {
        // Existing fields (backward compatible)
        private String reason;
        
        // NEW: Return details (Week 3)
        private String returnAccount;       // Override default return account
        private Boolean urgent;
        
        // NEW: Fee processing (Phase 3) - REQUIRED for FeePostingService
        private UUID sourceVaId;            // VA from which return will be debited
        private String returnedBy;          // User who initiated the return
    }

    /**
     * Response from return payment with fee info.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReturnPaymentResponse {
        private UUID paymentId;
        private String paymentReference;
        private String returnReference;
        private BigDecimal amount;
        private BigDecimal returnFee;       // Return processing fee
        private String reason;
        private LocalDateTime returnedAt;
    }

    // ========================================================================
    // POS COLLECTIONS (Unchanged - backward compatible)
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PosCollectionResponse {
        private UUID id;
        private String transactionRef;
        private String merchantId;
        private String merchantName;
        private String terminalId;
        private BigDecimal amount;
        private String currencyCode;
        private String paymentMethod;
        private String cardType;
        private String cardLastFour;
        private String status;
        private String settlementStatus;
        private LocalDateTime transactionDate;
        private LocalDate settlementDate;
        private BigDecimal commissionAmount;
        private BigDecimal netAmount;
        
        // NEW: Auto-reconciliation (Week 3)
        private Boolean autoReconciled;
        private UUID linkedReceivableId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PosStatsResponse {
        private BigDecimal totalCollections;
        private BigDecimal todayCollections;
        private BigDecimal pendingSettlement;
        private BigDecimal settledAmount;
        private Integer transactionCount;
        private Integer todayTransactions;
        private Double successRate;
        private BigDecimal averageTicket;
        
        // NEW: Auto-reconciliation stats (Week 3)
        private Integer autoReconciledCount;
        private Double autoReconcileRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MerchantSummary {
        private String merchantId;
        private String merchantName;
        private String category;
        private Integer transactionCount;
        private BigDecimal totalVolume;
        private BigDecimal pendingSettlement;
        private String status;
    }

    // ========================================================================
    // NEW: HIERARCHY SUMMARY (Week 3)
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HierarchyReceivableSummary {
        private UUID nodeId;
        private String nodeName;
        private String nodeCode;
        private Integer level;
        private String path;
        
        private Integer totalCount;
        private Integer openCount;
        private Integer partialCount;
        private Integer paidCount;
        private Integer overdueCount;
        
        private BigDecimal totalAmount;
        private BigDecimal paidAmount;
        private BigDecimal outstandingAmount;
        
        private String currencyCode;
    }

    // ========================================================================
    // NEW: RECONCILIATION RESULT (Week 3)
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReconciliationResultResponse {
        private Boolean success;
        private String matchType;   // DIRECT, VIBAN_LINKED, AMOUNT_BASED, MANUAL, NONE
        private UUID receivableId;
        private String receivableNumber;
        private UUID paymentId;
        private UUID unmatchedPaymentId;
        private Integer confidence;
        private String matchReason;
        private String errorMessage;
        private List<SuggestedMatchResponse> suggestedMatches;
    }

    // ========================================================================
    // NEW: BULK OPERATIONS (Week 3)
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkMatchRequest {
        private List<SingleMatchRequest> matches;
        private String matchedBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SingleMatchRequest {
        private UUID unmatchedPaymentId;
        private UUID receivableId;
        private BigDecimal appliedAmount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkMatchResponse {
        private Integer totalRequested;
        private Integer successCount;
        private Integer failCount;
        private List<ReconciliationResultResponse> results;
    }

    // ========================================================================
    // NEW: AGING REPORT (Week 3)
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgingReportResponse {
        private BigDecimal currentAmount;
        private BigDecimal days1to30Amount;
        private BigDecimal days31to60Amount;
        private BigDecimal days61to90Amount;
        private BigDecimal over90DaysAmount;
        
        private Integer currentCount;
        private Integer days1to30Count;
        private Integer days31to60Count;
        private Integer days61to90Count;
        private Integer over90DaysCount;
        
        private BigDecimal totalOutstanding;
        private Integer totalCount;
        
        private List<AgingBucketDetail> bucketDetails;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgingBucketDetail {
        private String bucket;
        private BigDecimal amount;
        private Integer count;
        private Double percentage;
        private List<InvoiceResponse> topReceivables;
    }

    // ========================================================================
    // PHASE 3: COBO CONTROLLER DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CoboSubmitRequest {
        private UUID collectorEntityId;
        private String requestedBy;
        private String notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CoboApproveRequest {
        private String approvedBy;
        private String notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CoboRejectRequest {
        private String rejectedBy;
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CoboExecuteRequest {
        private UUID treasuryVaId;
        private UUID subsidiaryVaId;
        private BigDecimal amount;
        private String paymentReference;
        private String notes;
    }

    // ========================================================================
    // PHASE 3: NETTING DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NettingAddRequest {
        private UUID nettingCycleId;
        private String addedBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NettingRemoveRequest {
        private String removedBy;
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SetNettingEligibleRequest {
        private boolean eligible;
        private String updatedBy;
    }

    // NOTE: NettingEligibleReceivable, NettingAddResult, NettingRemoveResult, NettingSummary
    // are defined in ReceivableNettingService to avoid ambiguity with wildcard imports.
    // Use service types directly: ReceivableNettingService.NettingEligibleReceivable, etc.

    // ========================================================================
    // PHASE 3: INTERCOMPANY DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateIntercompanyReceivableRequest {
        private UUID corporateId;
        private UUID owningEntityId;
        private UUID intercompanyEntityId;
        private String invoiceNumber;
        private String currencyCode;
        private BigDecimal grossAmount;
        private BigDecimal taxAmount;
        private LocalDate dueDate;
        private String description;
        private String createdBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IntercompanyBalanceSummary {
        private UUID entityId;
        private String entityCode;
        private String entityName;
        private BigDecimal totalReceivable;
        private int receivableCount;
        private BigDecimal totalPayable;
        private int payableCount;
        private BigDecimal netPosition;
    }

    // NOTE: IntercompanyReceivableResult is defined in IntercompanyRechargeReceivableService
    // to avoid ambiguity with wildcard imports. Use service type directly.

    // ========================================================================
    // PHASE 3: ENTITY CONTEXT DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateEntityRequest {
        private UUID owningEntityId;
        private String owningEntityCode;
        private String owningEntityName;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntityContext {
        private UUID entityId;
        private String entityCode;
        private String entityName;
    }

    // ========================================================================
    // PHASE 3: ENHANCED RECEIVABLE RESPONSE
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReceivableResponse {
        private UUID id;
        private String receivableNumber;
        private String externalReference;
        private String receivableType;
        private UUID corporateId;
        private UUID programId;
        private UUID virtualAccountId;
        
        // Entity Context
        private UUID owningEntityId;
        private String owningEntityCode;
        private String owningEntityName;
        
        // Customer Party
        private UUID customerPartyId;
        
        // Intercompany
        private boolean isIntercompany;
        private UUID intercompanyEntityId;
        private String intercompanyEntityCode;
        private String intercompanyEntityName;
        private UUID counterpartyPayableId;
        
        // COBO
        private boolean isCobo;
        private UUID coboCollectorEntityId;
        private String coboCollectorEntityCode;
        private String coboRequestStatus;
        private String coboTransactionRef;
        private UUID coboRechargeId;
        private UUID coboIhbDepositId;
        
        // Netting
        private boolean nettingEligible;
        private UUID nettingCycleId;
        private String nettingCycleRef;
        private UUID nettingEntryId;
        private String nettingStatus;
        private String nettingSettlementRef;
        
        // Collection Route
        private String collectionRoute;
        private String collectionRouteDescription;
        
        // Customer Info
        private UUID customerId;
        private String customerName;
        private String customerEmail;
        
        // VIBAN
        private UUID primaryVibanId;
        private String viban;
        
        // Amounts
        private String currencyCode;
        private BigDecimal grossAmount;
        private BigDecimal discountAmount;
        private BigDecimal taxAmount;
        private BigDecimal netAmount;
        private BigDecimal paidAmount;
        private BigDecimal outstandingAmount;
        
        // Dates
        private String issueDate;
        private String dueDate;
        
        // Status
        private String status;
        private String paymentStatus;
        
        // Metadata
        private String description;
        
        // Audit
        private String createdAt;
        private String updatedAt;
    }

    // ========================================================================
    // PHASE 1: CAMT.054 PROCESSING DTOs (Bank Credit Notification)
    // ========================================================================

    /**
     * Camt.054 notification request containing credit entries for processing.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Camt054NotificationRequest {
        private String notificationId;
        private String accountIban;
        private String currencyCode;
        private LocalDateTime creationDateTime;
        private List<Camt054Entry> entries;
    }

    /**
     * Individual credit entry from camt.054 notification.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Camt054Entry {
        private String entryReference;
        private String viban;
        private BigDecimal amount;
        private String currencyCode;
        private String debtor;
        private String debtorAccount;
        private String debtorAgent;
        private String remittanceInfo;
        private String endToEndId;
        private LocalDateTime bookingDate;
        private LocalDateTime valueDate;
    }

    /**
     * Response from camt.054 processing.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Camt054ProcessingResponse {
        private String notificationId;
        private List<Camt054EntryResult> results;
        private int successCount;
        private int failedCount;
        private BigDecimal totalCreditedAmount;
        private BigDecimal totalFees;
        private String currencyCode;
        private LocalDateTime processedAt;
    }

    /**
     * Result of processing a single camt.054 entry.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Camt054EntryResult {
        private String entryReference;
        private String viban;
        private String transactionReference;
        private BigDecimal grossAmount;
        private BigDecimal fee;
        private BigDecimal netAmount;
        private UUID targetVaId;
        private String targetVaNumber;
        private String matchStatus;
        private UUID matchedReceivableId;
        private String status;       // PROCESSED, FAILED
        private String errorMessage;
        private LocalDateTime processedAt;
    }

    /**
     * Request to create a published payment VIBAN linked to a receivable.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreatePublishedVibanRequest {
        private UUID targetVaId;
        private UUID receivableId;
        private String customerName;
        private BigDecimal expectedAmount;
        private String currency;
        private String purpose;
        private Integer expiresInHours;
    }

    // ========================================================================
    // NOTE: Phase 3 Statistics Types
    // ========================================================================
    // The following types are defined in their respective service classes to avoid
    // ambiguity with wildcard imports. Use the service types directly:
    //
    // - CoboReceivableService.CoboStats
    // - CoboReceivableService.CoboPreviewResult
    // - ReceivableNettingService.NettingSummary
    //
    // For Phase 3 statistics, build a Map<String, Object> directly in the controller.
}