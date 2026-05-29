package com.bank.vam.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * TransactionDto - Request/Response DTOs for transaction operations.
 * 
 * v5.2 Features:
 * - PaymentRequest for outbound payments via Shadow VA
 * - PoboPaymentRequest for Pay-On-Behalf-Of operations
 * - Comprehensive response DTOs
 */
public class TransactionDto {

    // ========================================================================
    // STATS RESPONSE
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransactionStatsResponse {
        private BigDecimal totalCredits;
        private BigDecimal totalDebits;
        private BigDecimal netFlow;
        private BigDecimal todayCredits;
        private BigDecimal todayDebits;
        private BigDecimal todayNetFlow;
        
        // Counts
        private Long totalCount;
        private Long todayCount;
        private Long pendingCount;
        private Long failedCount;
        private Long completedCount;
        
        // By channel
        private Long swiftCount;
        private Long rtgsCount;
        private Long internalCount;
        private Long poboCount;
        
        // Average transaction
        private BigDecimal averageAmount;
        private BigDecimal largestTransaction;
    }

    // ========================================================================
    // LIST / SEARCH RESPONSE
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransactionListResponse {
        private List<TransactionResponse> content;
        private Integer page;
        private Integer pageSize;
        private Long totalElements;
        private Integer totalPages;
        private TransactionSummary summary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransactionSummary {
        private BigDecimal totalCredits;
        private BigDecimal totalDebits;
        private BigDecimal netFlow;
        private Long creditCount;
        private Long debitCount;
    }

    // ========================================================================
    // TRANSACTION RESPONSE
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransactionResponse {
        private UUID id;
        private String referenceNumber;
        private String movementType;
        private String transactionCategory;
        private BigDecimal amount;
        private String currencyCode;
        
        // Account info
        private UUID vaId;
        private String vaNumber;
        private String vaName;
        
        // Counterparty info
        private String counterpartyName;
        private String counterpartyAccount;
        private UUID counterpartyVaId;
        
        // Description & Channel
        private String description;
        private String channel;
        
        // Status
        private String status;
        
        // Dates
        private LocalDateTime transactionDate;
        private LocalDate valueDate;
        private LocalDateTime createdAt;
        
        // Balances
        private BigDecimal balanceBefore;
        private BigDecimal balanceAfter;
        
        // Fees
        private BigDecimal feeAmount;
        
        // POBO fields
        private Boolean isPobo;
        private String behalfOfEntity;
        private UUID behalfOfVaId;
        
        // External references
        private String externalReference;
        private String bancsReference;
        private String correlationId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransactionDetailResponse {
        private UUID id;
        private String referenceNumber;
        private String movementType;
        private String transactionCategory;
        private BigDecimal amount;
        private String currencyCode;
        
        // Account info
        private UUID vaId;
        private String vaNumber;
        private String vaName;
        private UUID corporateId;
        private String corporateName;
        private UUID physicalAccountId;
        private String physicalAccountNumber;
        private UUID legalEntityId;
        private String legalEntityCode;
        
        // Counterparty info
        private String counterpartyName;
        private String counterpartyAccount;
        private UUID counterpartyVaId;
        private String counterpartyVaNumber;
        
        // Remitter/Beneficiary details
        private String remitterName;
        private String remitterAccount;
        private String beneficiaryName;
        private String beneficiaryAccount;
        
        // Description & Channel
        private String description;
        private String channel;
        
        // Status & dates
        private String status;
        private LocalDateTime transactionDate;
        private LocalDate valueDate;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        
        // Balances
        private BigDecimal balanceBefore;
        private BigDecimal balanceAfter;
        
        // Fees
        private BigDecimal feeAmount;
        private String feeBreakdown;
        
        // POBO fields
        private Boolean isPobo;
        private Boolean isRobo;
        private String behalfOfEntity;
        private UUID behalfOfVaId;
        
        // External references
        private String externalReference;
        private String bancsReference;
        private String correlationId;
        
        // Related transactions (for transfers)
        private List<RelatedTransaction> relatedTransactions;
        
        // Processing
        private String processingNotes;
        
        // Audit
        private String initiatedBy;
        private String approvedBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RelatedTransaction {
        private UUID id;
        private String referenceNumber;
        private String movementType;
        private BigDecimal amount;
        private String vaNumber;
        private String vaName;
        private String status;
    }

    // ========================================================================
    // SEARCH / FILTER REQUEST
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransactionSearchRequest {
        private String query;
        private String movementType;
        private String transactionCategory;
        private String status;
        private String channel;
        private UUID vaId;
        private UUID legalEntityId;
        private UUID corporateId;
        private LocalDate dateFrom;
        private LocalDate dateTo;
        private BigDecimal amountMin;
        private BigDecimal amountMax;
        private Boolean isPobo;
        private String correlationId;
        private Integer page;
        private Integer pageSize;
        private String sortBy;
        private String sortOrder;
    }

    // ========================================================================
    // OPERATION REQUESTS
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreditRequest {
        private UUID vaId;
        private BigDecimal amount;
        private LocalDate valueDate;
        private String description;
        private String channel;
        private String remitterName;
        private String remitterAccount;
        private String externalReference;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DebitRequest {
        private UUID vaId;
        private BigDecimal amount;
        private LocalDate valueDate;
        private String description;
        private String channel;
        private String beneficiaryName;
        private String beneficiaryAccount;
        private String externalReference;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransferRequest {
        private UUID fromVaId;
        private UUID toVaId;
        private BigDecimal amount;
        private String description;
        private LocalDate valueDate;
    }

    /**
     * PaymentRequest - Outbound payment to external beneficiary.
     * 
     * Flow: Source VA → Settlement VA → Shadow VA → CBS → External Beneficiary
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentRequest {
        /**
         * Source VA ID - the VA making the payment.
         */
        private UUID fromVaId;
        
        /**
         * Payment amount in source VA currency.
         */
        private BigDecimal amount;
        
        /**
         * Value date for the payment.
         */
        private LocalDate valueDate;
        
        /**
         * Payment description/reference for the beneficiary.
         */
        private String description;
        
        /**
         * Payment channel (SWIFT, RTGS, SEPA, ACH, LOCAL_CLEARING).
         */
        private String channel;
        
        /**
         * Beneficiary name (required).
         */
        private String beneficiaryName;
        
        /**
         * Beneficiary account number/IBAN (required).
         */
        private String beneficiaryAccount;
        
        /**
         * Beneficiary bank SWIFT/BIC code.
         */
        private String beneficiaryBankSwift;
        
        /**
         * Beneficiary bank name.
         */
        private String beneficiaryBankName;
        
        /**
         * Beneficiary bank address.
         */
        private String beneficiaryBankAddress;
        
        /**
         * Beneficiary address (for compliance).
         */
        private String beneficiaryAddress;
        
        /**
         * Beneficiary country code (ISO 2-letter).
         */
        private String beneficiaryCountry;
        
        /**
         * Payment purpose code (for regulatory reporting).
         */
        private String purposeCode;
        
        /**
         * External/client reference number.
         */
        private String externalReference;
        
        /**
         * Priority: NORMAL, HIGH, URGENT
         */
        private String priority;
        
        /**
         * Charge bearer: OUR, SHA, BEN
         */
        private String chargeBearer;
    }

    /**
     * PoboPaymentRequest - Pay-On-Behalf-Of payment.
     * 
     * Treasury Center (payerVaId) pays on behalf of Subsidiary (ownerVaId).
     * The cost is borne by the subsidiary (ownerVaId).
     * 
     * Flow: Owner VA → Payer VA → Shadow VA → CBS → External Beneficiary
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoboPaymentRequest {
        /**
         * Owner VA ID - the subsidiary VA that owns the payable.
         * This VA will be debited for the payment amount + fees.
         */
        private UUID ownerVaId;
        
        /**
         * Payer VA ID - the Treasury Center VA that makes the actual payment.
         * This VA provides the physical account for CBS settlement.
         */
        private UUID payerVaId;
        
        /**
         * Payment amount in owner VA currency.
         */
        private BigDecimal amount;
        
        /**
         * Value date for the payment.
         */
        private LocalDate valueDate;
        
        /**
         * Payment description.
         */
        private String description;
        
        /**
         * Payment channel (SWIFT, RTGS, SEPA).
         */
        private String channel;
        
        /**
         * Beneficiary name (required).
         */
        private String beneficiaryName;
        
        /**
         * Beneficiary account number/IBAN (required).
         */
        private String beneficiaryAccount;
        
        /**
         * Beneficiary bank SWIFT/BIC code.
         */
        private String beneficiaryBankSwift;
        
        /**
         * Beneficiary bank name.
         */
        private String beneficiaryBankName;
        
        /**
         * Beneficiary country code.
         */
        private String beneficiaryCountry;
        
        /**
         * Payment purpose code.
         */
        private String purposeCode;
        
        /**
         * External reference.
         */
        private String externalReference;
        
        /**
         * Linked payable ID (optional - for payable status update).
         */
        private UUID payableId;
        
        /**
         * Priority: NORMAL, HIGH, URGENT
         */
        private String priority;
    }

    /**
     * PaymentResponse - Response for outbound payment.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentResponse {
        private UUID transactionId;
        private String referenceNumber;
        private String correlationId;
        private String status;
        
        // Source VA
        private UUID sourceVaId;
        private String sourceVaNumber;
        private BigDecimal sourceBalanceBefore;
        private BigDecimal sourceBalanceAfter;
        
        // Shadow VA (Physical Account link)
        private UUID shadowVaId;
        private String shadowVaNumber;
        private String physicalAccountNumber;
        
        // Payment details
        private BigDecimal amount;
        private BigDecimal feeAmount;
        private BigDecimal totalDebited;
        private String currencyCode;
        
        // Beneficiary
        private String beneficiaryName;
        private String beneficiaryAccount;
        
        // Timestamps
        private LocalDateTime transactionDate;
        private LocalDate valueDate;
        
        // CBS tracking
        private String cbsReference;
        private String cbsStatus;
    }

    /**
     * PoboPaymentResponse - Response for POBO payment.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoboPaymentResponse {
        private UUID transactionId;
        private String referenceNumber;
        private String correlationId;
        private String status;
        
        // Owner VA (subsidiary)
        private UUID ownerVaId;
        private String ownerVaNumber;
        private String ownerEntityCode;
        private BigDecimal ownerBalanceBefore;
        private BigDecimal ownerBalanceAfter;
        
        // Payer VA (treasury center)
        private UUID payerVaId;
        private String payerVaNumber;
        private String payerEntityCode;
        
        // Shadow VA
        private UUID shadowVaId;
        private String shadowVaNumber;
        private String physicalAccountNumber;
        
        // Payment details
        private BigDecimal amount;
        private BigDecimal poboFee;
        private BigDecimal totalDebited;
        private String currencyCode;
        
        // Beneficiary
        private String beneficiaryName;
        private String beneficiaryAccount;
        
        // POBO tracking
        private String behalfOfEntity;
        
        // Timestamps
        private LocalDateTime transactionDate;
        private LocalDate valueDate;
    }

    /**
     * CollectionRequest - Inbound collection from external remitter via CBS.
     *
     * This is the SYMMETRIC OPPOSITE of PaymentRequest:
     * - PaymentRequest:    Source VA → Settlement VA → Shadow VA → CBS → External (outbound)
     * - CollectionRequest: External → CBS → Shadow VA → Settlement VA → Target VA (inbound)
     *
     * Flow: CBS → Shadow VA → Settlement VA → Target VA (4-leg accounting)
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CollectionRequest {
        /**
         * Target VA ID - the VA receiving the collection (final destination).
         */
        private UUID targetVaId;

        /**
         * Gross collection amount received from CBS.
         */
        private BigDecimal amount;

        /**
         * Currency code (default: AED).
         */
        private String currencyCode;

        /**
         * Value date for the collection.
         */
        private LocalDate valueDate;

        /**
         * Remitter (sender) name.
         */
        private String remitterName;

        /**
         * Remitter (sender) account number/IBAN.
         */
        private String remitterAccount;

        /**
         * Remitter bank SWIFT/BIC code.
         */
        private String remitterBic;

        /**
         * Remitter bank name.
         */
        private String remitterBankName;

        /**
         * VIBAN for routing (optional).
         * If provided, enables auto-matching and reduced fees.
         */
        private String viban;

        /**
         * CBS/Bank reference number.
         */
        private String bankReference;

        /**
         * ISO 20022 End-to-End ID.
         */
        private String endToEndId;

        /**
         * Remittance information / payment purpose.
         */
        private String remittanceInfo;

        /**
         * Invoice reference for auto-matching.
         */
        private String invoiceReference;

        /**
         * Pre-matched receivable ID (from VIBAN lookup).
         */
        private UUID matchedReceivableId;

        /**
         * Collection channel (SWIFT, RTGS, LOCAL, etc.).
         */
        private String channel;

        /**
         * External/client reference.
         */
        private String externalReference;

        /**
         * Flag indicating ROBO (Receive-On-Behalf-Of) collection.
         * When true, uses ROBO_COLLECTION_FEE instead of standard collection fee.
         */
        private Boolean isRobo;

        /**
         * The legal entity ID on whose behalf Treasury collected funds.
         * Used for ROBO collections to track intercompany obligations.
         * This is the subsidiary that should ultimately receive the funds.
         */
        private UUID behalfOfEntityId;

        /**
         * The legal entity code on whose behalf Treasury collected funds.
         * Denormalized for display, audit trail, and IC Payable description.
         */
        private String behalfOfEntityCode;
    }

    /**
     * CollectionResponse - Response for inbound collection.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CollectionResponse {
        private UUID transactionId;
        private String referenceNumber;
        private String correlationId;
        private String status;

        // Shadow VA (CBS entry point)
        private UUID shadowVaId;
        private String shadowVaNumber;
        private String physicalAccountNumber;

        // Target VA (final destination)
        private UUID targetVaId;
        private String targetVaNumber;
        private BigDecimal targetBalanceBefore;
        private BigDecimal targetBalanceAfter;

        // Amount breakdown
        private BigDecimal grossAmount;
        private BigDecimal feeAmount;
        private BigDecimal netAmount;
        private String currencyCode;

        // Remitter info
        private String remitterName;
        private String remitterAccount;

        // VIBAN routing
        private String viban;
        private boolean routedViaViban;

        // Matching status
        private String matchStatus;
        private UUID matchedReceivableId;
        private String matchedInvoiceNumber;

        // Timestamps
        private LocalDateTime transactionDate;
        private LocalDate valueDate;

        // CBS tracking
        private String bankReference;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkTransferRequest {
        private UUID sourceVaId;
        private List<TransferItem> transfers;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransferItem {
        private UUID destinationVaId;
        private BigDecimal amount;
        private String description;
        private LocalDate valueDate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkTransferResponse {
        private Integer totalCount;
        private Integer successCount;
        private Integer failedCount;
        private BigDecimal totalAmount;
        private List<TransferResult> results;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransferResult {
        private UUID destinationVaId;
        private String destinationVaNumber;
        private BigDecimal amount;
        private String status;
        private String referenceNumber;
        private String errorMessage;
    }

    /**
     * BulkPaymentRequest - Multiple outbound payments.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkPaymentRequest {
        private UUID sourceVaId;
        private List<PaymentItem> payments;
        private String batchReference;
        private String description;
    }

    /**
     * PaymentItem - Single payment in a bulk request.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentItem {
        private BigDecimal amount;
        private String beneficiaryName;
        private String beneficiaryAccount;
        private String beneficiaryBankSwift;
        private String description;
        private String externalReference;
        private LocalDate valueDate;
    }

    /**
     * BulkPaymentResponse - Results of bulk payment processing.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkPaymentResponse {
        private String batchReference;
        private Integer totalCount;
        private Integer successCount;
        private Integer failedCount;
        private BigDecimal totalAmount;
        private BigDecimal totalFees;
        private List<PaymentResult> results;
    }

    /**
     * PaymentResult - Individual payment result in bulk response.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentResult {
        private String beneficiaryName;
        private String beneficiaryAccount;
        private BigDecimal amount;
        private BigDecimal feeAmount;
        private String status;
        private String referenceNumber;
        private String errorMessage;
    }

    /**
     * BulkPoboPaymentRequest - Multiple POBO payments.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkPoboPaymentRequest {
        /**
         * Payer VA ID - Treasury Center that makes all payments.
         */
        private UUID payerVaId;
        
        /**
         * List of POBO payment items.
         */
        private List<PoboPaymentItem> payments;
        
        /**
         * Batch reference.
         */
        private String batchReference;
        
        /**
         * Description.
         */
        private String description;
    }

    /**
     * PoboPaymentItem - Single POBO payment in a bulk request.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoboPaymentItem {
        /**
         * Owner VA ID - subsidiary that owns the payable.
         */
        private UUID ownerVaId;
        
        /**
         * Payment amount.
         */
        private BigDecimal amount;
        
        /**
         * Beneficiary name.
         */
        private String beneficiaryName;
        
        /**
         * Beneficiary account.
         */
        private String beneficiaryAccount;
        
        /**
         * Beneficiary bank SWIFT.
         */
        private String beneficiaryBankSwift;
        
        /**
         * Description.
         */
        private String description;
        
        /**
         * External reference.
         */
        private String externalReference;
        
        /**
         * Linked payable ID.
         */
        private UUID payableId;
        
        /**
         * Value date.
         */
        private LocalDate valueDate;
    }

    /**
     * BulkPoboPaymentResponse - Results of bulk POBO payment processing.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkPoboPaymentResponse {
        private String batchReference;
        private UUID payerVaId;
        private String payerVaNumber;
        private Integer totalCount;
        private Integer successCount;
        private Integer failedCount;
        private BigDecimal totalAmount;
        private BigDecimal totalFees;
        private List<PoboPaymentResult> results;
    }

    /**
     * PoboPaymentResult - Individual POBO payment result.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoboPaymentResult {
        private UUID ownerVaId;
        private String ownerVaNumber;
        private String ownerEntityCode;
        private String beneficiaryName;
        private String beneficiaryAccount;
        private BigDecimal amount;
        private BigDecimal feeAmount;
        private String status;
        private String referenceNumber;
        private String errorMessage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReversalRequest {
        private UUID transactionId;
        private String reason;
    }

    // ========================================================================
    // EXPORT REQUEST
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExportRequest {
        private String format;              // CSV, XLSX, PDF
        private LocalDate dateFrom;
        private LocalDate dateTo;
        private String movementType;
        private String status;
        private UUID vaId;
        private UUID legalEntityId;
        private Boolean includePobo;
    }

    // ========================================================================
    // FEE BREAKDOWN RESPONSE
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FeeBreakdownResponse {
        private BigDecimal totalFee;
        private List<FeeLineItemResponse> lineItems;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FeeLineItemResponse {
        private String chargeCode;
        private String chargeName;
        private BigDecimal amount;
        private String currency;
        private Boolean waived;
        private String waiverReason;
        private String feeOwner;
    }

    // ========================================================================
    // TRANSFER PREVIEW (for UI to show fees before confirmation)
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransferPreviewRequest {
        private UUID fromVaId;
        private UUID toVaId;
        private BigDecimal amount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransferPreviewResponse {
        private UUID fromVaId;
        private String fromVaNumber;
        private String fromVaName;
        private BigDecimal fromBalance;

        // Credit Limit Fields
        private BigDecimal creditLimit;
        private BigDecimal creditLimitUtilized;
        private BigDecimal creditLimitAvailable;
        private BigDecimal effectiveAvailableBalance;  // fromBalance + creditLimitAvailable

        private UUID toVaId;
        private String toVaNumber;
        private String toVaName;

        private BigDecimal amount;
        private String currency;

        // Classification
        private Boolean sameProgram;
        private Boolean intercompany;
        private Boolean crossBorder;
        private Boolean requiresFx;

        // Fees
        private FeeBreakdownResponse feeBreakdown;
        private BigDecimal totalDebit;

        // Validation
        private Boolean fundsAvailable;
        private String validationMessage;
    }

    // ========================================================================
    // PAYMENT PREVIEW
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentPreviewRequest {
        private UUID fromVaId;
        private BigDecimal amount;
        private String channel;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentPreviewResponse {
        private UUID fromVaId;
        private String fromVaNumber;
        private String fromVaName;
        private BigDecimal fromBalance;

        // Credit Limit Fields
        private BigDecimal creditLimit;
        private BigDecimal creditLimitUtilized;
        private BigDecimal creditLimitAvailable;
        private BigDecimal effectiveAvailableBalance;  // fromBalance + creditLimitAvailable

        private UUID shadowVaId;
        private String shadowVaNumber;
        private String physicalAccountNumber;

        private BigDecimal amount;
        private String currency;
        private String channel;

        // Fees
        private BigDecimal paymentFee;
        private BigDecimal totalDebit;

        // Validation
        private Boolean fundsAvailable;
        private Boolean shadowVaAvailable;
        private String validationMessage;
    }


        /**
     * ISO 20022 message response DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IsoMessageResponse {
        private String messageType;      // e.g., "pain.001.001.03"
        private String messageId;        // Unique message identifier
        private String xml;              // The actual XML content
        private UUID transactionId;      // Related transaction ID
        private String referenceNumber;  // Transaction reference
    }
    // ========================================================================
    // GROUPED BUSINESS TRANSACTION VIEW (Option B - Simplified View)
    // ========================================================================

    /**
     * GroupedTransactionResponse - Represents a single business operation.
     *
     * Multi-leg accounting entries (4-leg collections, 4-leg payments) are grouped
     * into a single business transaction view showing:
     * - Net effect to the user's account
     * - Gross amount, fees, net amount breakdown
     * - Expandable accounting entries for audit trail
     *
     * This provides a user-friendly view while maintaining full accounting transparency.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupedTransactionResponse {
        // Primary identification
        private String correlationId;
        private String primaryReferenceNumber;
        private UUID primaryTransactionId;

        // Business operation type
        private String operationType;  // COLLECTION, PAYMENT, POBO_PAYMENT, TRANSFER, FEE
        private String direction;      // INBOUND, OUTBOUND, INTERNAL

        // User's account info (the account they care about)
        private UUID userVaId;
        private String userVaNumber;
        private String userVaName;

        // Net effect to user's account
        private BigDecimal netAmount;           // Final amount credited/debited to user
        private BigDecimal grossAmount;         // Original amount before fees
        private BigDecimal feeAmount;           // Total fees
        private String currencyCode;
        private boolean isCredit;               // true = money in, false = money out

        // Balance changes on user's account
        private BigDecimal balanceBefore;
        private BigDecimal balanceAfter;

        // Counterparty info
        private String counterpartyName;
        private String counterpartyAccount;
        private String counterpartyType;        // EXTERNAL, INTERNAL_VA, CBS

        // Additional context
        private String description;
        private String channel;
        private String status;

        // Dates
        private LocalDateTime transactionDate;
        private LocalDate valueDate;

        // VIBAN/ROBO specific
        private String viban;
        private String matchStatus;             // AUTO, PARTIAL, MANUAL, UNMATCHED
        private UUID matchedReceivableId;

        // POBO specific
        private Boolean isPobo;
        private String behalfOfEntity;
        private UUID behalfOfVaId;

        // External references
        private String externalReference;
        private String bankReference;

        // Accounting entries (expandable detail)
        private Integer entryCount;             // Number of accounting legs
        private List<AccountingEntryResponse> accountingEntries;
    }

    /**
     * AccountingEntryResponse - Individual accounting leg in a multi-leg transaction.
     *
     * Shown in the expandable "Accounting Entries" section of the grouped view.
     *
     * IMPORTANT: Shadow VA (PHYSICAL_MIRROR) entries are filtered from user-facing views
     * by default as they are omnibus accounts used for CBS reconciliation. They are
     * only shown when includeInternal=true for full audit trail.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AccountingEntryResponse {
        private Integer legNumber;              // 1, 2, 3, 4...
        private UUID transactionId;
        private String referenceNumber;

        // Account affected
        private UUID vaId;
        private String vaNumber;
        private String vaName;
        private String accountType;             // PHYSICAL_MIRROR, SETTLEMENT, OPERATING, EXCEPTION

        // Entry details
        private String movementType;            // CREDIT, DEBIT, ROBO_CREDIT, etc.
        private String entryType;               // DEBIT or CREDIT - based on actual balance movement
        private BigDecimal amount;
        private String currencyCode;

        // Balance effect
        private BigDecimal balanceBefore;
        private BigDecimal balanceAfter;

        // Description
        private String description;
        private String processingNotes;

        // Timestamp
        private LocalDateTime transactionDate;

        // Internal account flag (Settlement VA, Shadow VA)
        // Helps UI distinguish system accounts from user accounts
        @Builder.Default
        private Boolean isInternalAccount = false;
    }

    /**
     * GroupedTransactionListResponse - Paginated list of grouped transactions.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupedTransactionListResponse {
        private List<GroupedTransactionResponse> content;
        private Integer page;
        private Integer pageSize;
        private Long totalElements;
        private Integer totalPages;
        private GroupedTransactionSummary summary;
    }

    /**
     * GroupedTransactionSummary - Summary stats for grouped transactions.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupedTransactionSummary {
        private BigDecimal totalCredits;        // Sum of net credits
        private BigDecimal totalDebits;         // Sum of net debits
        private BigDecimal totalFees;           // Sum of all fees
        private BigDecimal netFlow;             // Credits - Debits
        private Long creditCount;
        private Long debitCount;
        private Long collectionCount;
        private Long paymentCount;
        private Long poboCount;
    }

    // ========================================================================
    // LEGACY RESPONSE (for backward compatibility)
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private UUID id;
        private String movementType;
        private UUID vaId;
        private BigDecimal amount;
        private String currencyCode;
        private BigDecimal balanceBefore;
        private BigDecimal balanceAfter;
        private LocalDateTime transactionDate;
        private LocalDate valueDate;
        private String referenceNumber;
        private String description;
        private String status;
    }


}