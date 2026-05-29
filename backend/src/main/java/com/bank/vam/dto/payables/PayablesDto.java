package com.bank.vam.dto.payables;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * PayablesDto - Phase 2 Enhanced DTOs for POBO, Intercompany & Netting
 * 
 * Contains all request/response DTOs for:
 * - Payable CRUD operations
 * - POBO workflow (request, approve, execute)
 * - Intercompany payable management
 * - Netting cycle integration
 * - Statistics and reporting
 */
public class PayablesDto {

    // ========================================================================
    // CORE PAYABLE DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PayableResponse {
        private UUID id;
        private String payableNumber;
        private String externalReference;
        private String invoiceNumber;
        private String payableType;
        
        // Ownership
        private UUID corporateId;
        private UUID programId;
        private UUID virtualAccountId;
        
        // Phase 2: Entity Context
        private UUID owningEntityId;
        private String owningEntityCode;
        private String owningEntityName;
        
        // Phase 2: Party Integration
        private UUID partyId;
        private String partyCode;
        private UUID partyBankAccountId;
        
        // Vendor Info (Legacy + Party)
        private UUID vendorId;
        private String vendorName;
        private String vendorAccount;
        private String vendorBank;
        
        // Amounts
        private String currencyCode;
        private BigDecimal grossAmount;
        private BigDecimal discountAmount;
        private BigDecimal taxAmount;
        private BigDecimal withholdingTax;
        private BigDecimal netAmount;
        private BigDecimal paidAmount;
        private BigDecimal outstandingAmount;
        
        // Dates
        private LocalDate invoiceDate;
        private LocalDate receivedDate;
        private LocalDate dueDate;
        private Integer paymentTermsDays;
        private Integer daysUntilDue;
        private String agingBucket;
        
        // Status
        private String status;
        private String paymentStatus;
        private Boolean isOverdue;
        
        // Phase 2: Payment Route
        private String paymentRoute;
        private UUID paymentViaEntityId;
        private String paymentViaEntityCode;
        private String paymentRouteDescription;
        
        // Phase 2: POBO
        private UUID poboRequestId;
        private String poboRequestStatus;
        private String poboTransactionRef;
        private UUID poboIhbLoanId;
        private UUID poboRechargeId;
        private LocalDateTime poboRequestedAt;
        private String poboRequestedBy;
        private LocalDateTime poboActionedAt;
        private String poboActionedBy;
        
        // Phase 2: Intercompany
        private Boolean isIntercompany;
        private UUID counterpartyEntityId;
        private String counterpartyEntityCode;
        private String counterpartyEntityName;
        private UUID counterpartyReceivableId;
        
        // Phase 2: Netting
        private Boolean nettingEligible;
        private UUID nettingCycleId;
        private String nettingCycleRef;
        private UUID nettingEntryId;
        private String nettingStatus;
        private String nettingSettlementRef;
        private LocalDateTime nettingSettledAt;
        
        // Approval
        private Boolean approvalRequired;
        private Integer approvalLevel;
        private String approvedBy;
        private LocalDateTime approvedAt;
        private String rejectionReason;
        
        // Scheduling
        private LocalDate scheduledDate;
        private String paymentPriority;
        private String paymentMethod;
        private String paymentChannel;
        private UUID paymentBatchId;
        
        // Hierarchy
        private UUID hierarchyNodeId;
        private String hierarchyPath;
        
        // Metadata
        private String description;
        private String notes;
        private Boolean hasInvoiceDocument;
        private Integer documentCount;
        
        // Audit
        private String createdBy;
        private LocalDateTime createdAt;
        private String updatedBy;
        private LocalDateTime updatedAt;
        
        // Computed flags
        private Boolean canBePaid;
        private Boolean canRequestPobo;
        private Boolean canAddToNetting;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreatePayableRequest {
        // Required fields
        private UUID corporateId;
        private String invoiceNumber;
        private BigDecimal grossAmount;
        private String currencyCode;
        private LocalDate dueDate;
        
        // Phase 2: Entity Context (Required for multi-entity)
        private UUID owningEntityId;
        private String owningEntityCode;
        private String owningEntityName;
        
        // Phase 2: Party Integration
        private UUID partyId;
        private UUID partyBankAccountId;
        
        // Vendor Info (Legacy - optional if partyId provided)
        private UUID vendorId;
        private String vendorName;
        private String vendorAccount;
        private String vendorBank;
        private String vendorBankCode;
        private String vendorReference;
        
        // Optional fields
        private UUID programId;
        private UUID virtualAccountId;
        private String payableType;
        private String externalReference;
        
        // Amounts
        private BigDecimal discountAmount;
        private BigDecimal taxAmount;
        private BigDecimal withholdingTax;
        
        // Dates
        private LocalDate invoiceDate;
        private LocalDate receivedDate;
        private Integer paymentTermsDays;
        
        // Hierarchy
        private UUID hierarchyNodeId;
        private String hierarchyPath;
        
        // Phase 2: Intercompany (Optional)
        private Boolean isIntercompany;
        private UUID counterpartyEntityId;
        private String counterpartyEntityCode;
        private String counterpartyEntityName;
        
        // Phase 2: Initial Payment Route
        private String paymentRoute;
        
        // Scheduling
        private LocalDate scheduledDate;
        private String paymentPriority;
        private String paymentMethod;
        private String paymentChannel;
        
        // Approval
        private Boolean approvalRequired;
        private Integer approvalLevel;
        
        // Metadata
        private String description;
        private String notes;
        
        // Audit
        private String createdBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdatePayableRequest {
        private String invoiceNumber;
        private String externalReference;
        
        // Phase 2: Party can be updated before approval
        private UUID partyId;
        private UUID partyBankAccountId;
        
        // Vendor Info
        private String vendorName;
        private String vendorAccount;
        private String vendorBank;
        
        // Amounts
        private BigDecimal grossAmount;
        private BigDecimal discountAmount;
        private BigDecimal taxAmount;
        private BigDecimal withholdingTax;
        private String currencyCode;
        
        // Dates
        private LocalDate invoiceDate;
        private LocalDate dueDate;
        private Integer paymentTermsDays;
        
        // Hierarchy
        private UUID hierarchyNodeId;
        private String hierarchyPath;
        
        // Scheduling
        private LocalDate scheduledDate;
        private String paymentPriority;
        private String paymentMethod;
        private String paymentChannel;
        
        // Metadata
        private String description;
        private String notes;
        
        // Audit
        private String updatedBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PayableListResponse {
        private List<PayableResponse> payables;
        private int page;
        private int size;
        private long totalElements;
        private int totalPages;
        private boolean hasNext;
        private boolean hasPrevious;
    }

    // ========================================================================
    // PHASE 2: POBO WORKFLOW DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoboRequestRequest {
        /** Payable IDs to request POBO for */
        private List<UUID> payableIds;
        
        /** Treasury entity that will execute payment */
        private UUID payingEntityId;
        private String payingEntityCode;
        
        /** User requesting POBO */
        private String requestedBy;
        
        /** Optional notes for treasury */
        private String notes;
        
        /** Whether to create batch or individual requests */
        private Boolean createBatch;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoboRequestResponse {
        private UUID payableId;
        private String payableNumber;
        private String poboRequestStatus;
        private LocalDateTime requestedAt;
        private String message;
        private Boolean success;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoboBatchRequestResponse {
        private List<PoboRequestResponse> results;
        private int successCount;
        private int failedCount;
        private BigDecimal totalAmount;
        private String currencyCode;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoboApprovalRequest {
        private UUID payableId;
        private Boolean approved;
        private String actionedBy;
        private String rejectionReason;
        private String notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoboBatchApprovalRequest {
        private List<UUID> payableIds;
        private Boolean approved;
        private String actionedBy;
        private String rejectionReason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoboPreviewRequest {
        private List<UUID> payableIds;
        private UUID payingEntityId;
        private UUID behalfEntityId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoboPreviewResponse {
        private UUID payingEntityId;
        private String payingEntityCode;
        private String payingEntityName;
        private UUID behalfEntityId;
        private String behalfEntityCode;
        private String behalfEntityName;

        private int payableCount;
        private BigDecimal totalPaymentAmount;
        private String currencyCode;

        // Paying Entity Balance & Credit Limit
        private UUID payingVaId;
        private String payingVaNumber;
        private BigDecimal payingVaBalance;
        private BigDecimal payingVaCreditLimit;
        private BigDecimal payingVaCreditLimitAvailable;
        private BigDecimal payingVaEffectiveAvailableBalance;  // balance + creditLimitAvailable

        // Behalf Entity Balance & Credit Limit (subsidiary being charged)
        private UUID behalfVaId;
        private String behalfVaNumber;
        private BigDecimal behalfVaBalance;
        private BigDecimal behalfVaCreditLimit;
        private BigDecimal behalfVaCreditLimitAvailable;
        private BigDecimal behalfVaEffectiveAvailableBalance;

        private List<PoboChargeBreakdown> charges;
        private BigDecimal totalCharges;
        private BigDecimal netPaymentAmount;

        private PoboIhbLoanPreview ihbLoanPreview;

        private List<String> warnings;
        private Boolean isValid;
        private String validationMessage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoboChargeBreakdown {
        private String chargeCode;
        private String chargeName;
        private String chargeType;
        private BigDecimal calculatedAmount;
        private Boolean waived;
        private String waiverReason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoboIhbLoanPreview {
        private BigDecimal principalAmount;
        private BigDecimal interestRate;
        private BigDecimal estimatedDailyInterest;
        private BigDecimal estimatedMonthlyInterest;
        private String tenor;
        private LocalDate expectedSettlementDate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoboExecuteRequest {
        private List<UUID> payableIds;
        private UUID payingEntityId;
        private String executedBy;
        private Boolean createIhbLoan;
        private Boolean createRecharge;
        private String notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoboExecuteResponse {
        private String batchTransactionRef;
        private List<PoboExecutionResult> results;
        private int successCount;
        private int failedCount;
        private BigDecimal totalExecutedAmount;
        private String currencyCode;
        private UUID ihbLoanId;
        private UUID rechargeId;
        private LocalDateTime executedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoboExecutionResult {
        private UUID payableId;
        private String payableNumber;
        private String transactionRef;
        private BigDecimal amount;
        private Boolean success;
        private String errorMessage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoboPendingApprovalResponse {
        private List<PayableResponse> payables;
        private BigDecimal totalAmount;
        private String currencyCode;
        private int count;
        private java.util.Map<String, Integer> byOwningEntity;
    }

    // ========================================================================
    // PHASE 2: INTERCOMPANY DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateIntercompanyPayableRequest {
        private UUID corporateId;
        
        // Owning entity (who owes)
        private UUID owningEntityId;
        private String owningEntityCode;
        private String owningEntityName;
        
        // Counterparty entity (who is owed)
        private UUID counterpartyEntityId;
        private String counterpartyEntityCode;
        private String counterpartyEntityName;
        
        // Party ID for counterparty as vendor
        private UUID partyId;
        
        // Amount
        private BigDecimal amount;
        private String currencyCode;
        
        // Details
        private String description;
        private String transactionType; // INTERCOMPANY_SALE, COST_ALLOCATION, MANAGEMENT_FEE, etc.
        private LocalDate dueDate;
        
        // Cross-reference
        private UUID relatedReceivableId; // Matching receivable on counterparty side
        private String externalReference;
        
        // Options
        private Boolean createMatchingReceivable; // Auto-create receivable on counterparty
        private Boolean addToNextNetting; // Include in next netting cycle
        
        // Audit
        private String createdBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IntercompanyPayableResponse {
        private UUID id;
        private String payableNumber;
        
        private UUID owningEntityId;
        private String owningEntityCode;
        private String owningEntityName;
        
        private UUID counterpartyEntityId;
        private String counterpartyEntityCode;
        private String counterpartyEntityName;
        
        private BigDecimal amount;
        private String currencyCode;
        private LocalDate dueDate;
        
        private String status;
        private String description;
        private String transactionType;
        
        private UUID counterpartyReceivableId;
        private String counterpartyReceivableNumber;
        
        private String nettingStatus;
        private UUID nettingCycleId;
        private String nettingCycleRef;
        
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IntercompanyPositionResponse {
        private UUID entityId;
        private String entityCode;
        private String entityName;
        
        private UUID counterpartyEntityId;
        private String counterpartyEntityCode;
        private String counterpartyEntityName;
        
        private BigDecimal payablesTotal;
        private BigDecimal receivablesTotal;
        private BigDecimal netPosition; // Positive = we owe them, Negative = they owe us
        
        private String currencyCode;
        
        private int payablesCount;
        private int receivablesCount;
        
        private LocalDate oldestPayableDue;
        private LocalDate oldestReceivableDue;
    }

    // ========================================================================
    // PHASE 2: NETTING INTEGRATION DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AddToNettingRequest {
        private List<UUID> payableIds;
        private UUID nettingCycleId;
        private String addedBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AddToNettingResponse {
        private UUID nettingCycleId;
        private String nettingCycleRef;
        private List<NettingAddResult> results;
        private int successCount;
        private int failedCount;
        private BigDecimal totalAmountAdded;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NettingAddResult {
        private UUID payableId;
        private String payableNumber;
        private UUID nettingEntryId;
        private Boolean success;
        private String errorMessage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RemoveFromNettingRequest {
        private List<UUID> payableIds;
        private String reason;
        private String removedBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NettingEligiblePayablesResponse {
        private List<PayableResponse> payables;
        private BigDecimal totalAmount;
        private String currencyCode;
        private int count;
        private java.util.Map<String, BigDecimal> byOwningEntity;
        private java.util.Map<String, BigDecimal> byCounterparty;
    }

    // ========================================================================
    // SEARCH & FILTER DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PayableSearchRequest {
        private UUID corporateId;
        private UUID owningEntityId;
        private UUID partyId;
        private UUID counterpartyEntityId;
        
        private String status;
        private String paymentRoute;
        private String poboRequestStatus;
        private String nettingStatus;
        
        private Boolean isIntercompany;
        private Boolean nettingEligible;
        private Boolean isOverdue;
        
        private LocalDate dueDateFrom;
        private LocalDate dueDateTo;
        private LocalDate invoiceDateFrom;
        private LocalDate invoiceDateTo;
        
        private BigDecimal amountMin;
        private BigDecimal amountMax;
        private String currencyCode;
        
        private String searchTerm;
        
        private int page;
        private int size;
        private String sortBy;
        private String sortOrder;
    }

    // ========================================================================
    // STATISTICS DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PayableStatsResponse {
        // Counts
        private long totalCount;
        private long pendingCount;
        private long approvedCount;
        private long paidCount;
        private long overdueCount;
        
        // Phase 2: Additional counts
        private long intercompanyCount;
        private long poboRequestedCount;
        private long poboPendingApprovalCount;
        private long poboExecutedCount;
        private long nettingIncludedCount;
        
        // Amounts
        private BigDecimal totalAmount;
        private BigDecimal outstandingAmount;
        private BigDecimal paidAmount;
        private BigDecimal overdueAmount;
        
        // Phase 2: Additional amounts
        private BigDecimal intercompanyAmount;
        private BigDecimal poboPendingAmount;
        private BigDecimal nettingAmount;
        
        // Aging
        private BigDecimal currentAmount;
        private BigDecimal overdue1to30Amount;
        private BigDecimal overdue31to60Amount;
        private BigDecimal overdue61to90Amount;
        private BigDecimal overdue90PlusAmount;
        
        private String currencyCode;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntityPayableStatsResponse {
        private UUID entityId;
        private String entityCode;
        private String entityName;
        
        private long totalCount;
        private long pendingCount;
        private long intercompanyCount;
        private long poboCount;
        
        private BigDecimal totalAmount;
        private BigDecimal outstandingAmount;
        private BigDecimal intercompanyAmount;
        private BigDecimal poboAmount;
        
        private String currencyCode;
    }

    // ========================================================================
    // APPROVAL & WORKFLOW DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApprovePayableRequest {
        private UUID payableId;
        private String approvedBy;
        private String notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RejectPayableRequest {
        private UUID payableId;
        private String rejectedBy;
        private String rejectionReason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SchedulePaymentRequest {
        private UUID payableId;
        private LocalDate scheduledDate;
        private String paymentPriority;
        private String paymentMethod;
        private UUID paymentBatchId;
        private String scheduledBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecordPaymentRequest {
        private UUID payableId;
        private BigDecimal amount;
        private String paymentReference;
        private LocalDate paymentDate;
        private String recordedBy;
        private String notes;
    }

    // ========================================================================
    // BATCH OPERATIONS DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchApproveRequest {
        private List<UUID> payableIds;
        private String approvedBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchScheduleRequest {
        private List<UUID> payableIds;
        private LocalDate scheduledDate;
        private String paymentPriority;
        private String scheduledBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchOperationResponse {
        private List<UUID> successIds;
        private List<BatchOperationError> errors;
        private int successCount;
        private int failedCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchOperationError {
        private UUID payableId;
        private String payableNumber;
        private String errorCode;
        private String errorMessage;
    }

    // ========================================================================
    // PHASE 1: PAYMENT EXECUTION DTOs (Direct Payment with pain.001)
    // ========================================================================

    /**
     * Request to execute a direct payment for a payable.
     * This generates a pain.001 message and initiates the payment.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentExecutionRequest {
        private UUID payableId;
        private String paymentChannel;   // BANK_TRANSFER, SWIFT, LOCAL_CLEARING
        private String paymentMethod;    // WIRE, ACH, RTGS, SEPA
        private String executedBy;
        private String notes;
    }

    /**
     * Response from payment execution.
     * Contains the payment reference, transaction ID, and pain.001 XML.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentExecutionResponse {
        private UUID payableId;
        private String payableNumber;
        private String paymentReference;
        private UUID paymentRequestId;   // Reference to PaymentRequest entity
        private UUID transactionId;      // Reference to Transaction entity
        private String pain001MessageId;
        private String pain001Xml;
        private BigDecimal amount;
        private String currencyCode;
        private UUID sourceVaId;         // Source VA that was debited
        private String sourceVaNumber;
        private BigDecimal balanceAfter; // Balance after debit
        private String status;           // EXECUTED, FAILED, PENDING
        private String errorMessage;
        private LocalDateTime executedAt;
        private String executedBy;
    }

    /**
     * Request to execute batch payment for multiple payables.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchPaymentExecutionRequest {
        private List<UUID> payableIds;
        private String paymentChannel;
        private String paymentMethod;
        private String executedBy;
        private String notes;
    }

    /**
     * Response from batch payment execution.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchPaymentExecutionResponse {
        private String batchReference;
        private List<PaymentExecutionResponse> results;
        private int successCount;
        private int failedCount;
        private BigDecimal totalAmount;
        private String currencyCode;
        private LocalDateTime executedAt;
    }

}