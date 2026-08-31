package com.bank.vam.iso20022.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * ISO 20022 Payment DTOs - Lightweight models that map directly to existing Transaction fields.
 *
 * These DTOs serve as a bridge between ISO 20022 messages and the existing Transaction entity.
 * No new database fields are created - everything maps to existing columns.
 *
 * Field Mapping:
 * - messageId -> Transaction.referenceNumber
 * - instructionId -> Transaction.externalReference
 * - endToEndId -> Transaction.correlationId
 * - amount/currency -> Transaction.amount/currencyCode
 * - debtor -> Transaction.remitterName/remitterAccount
 * - creditor -> Transaction.beneficiaryName/beneficiaryAccount
 * - remittanceInfo -> Transaction.description
 */
public class Iso20022PaymentDto {

    // ========================================================================
    // INWARD PAYMENT (pacs.008 / camt.054) - Maps to ROBO Credit
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InwardPaymentRequest {
        // Message identification - maps to Transaction.referenceNumber
        private String messageId;

        // Original instruction ID from sender - maps to Transaction.externalReference
        private String instructionId;

        // End-to-end tracking - maps to Transaction.correlationId
        private String endToEndId;

        // Amount info - maps to Transaction.amount/currencyCode
        private BigDecimal amount;
        private String currency;

        // VIBAN for routing - used for VIBAN lookup, maps to Transaction.viban
        private String creditorAccount;

        // Debtor (sender) info - maps to Transaction.remitter*
        private String debtorName;
        private String debtorAccount;
        private String debtorBic;

        // Creditor (receiver) - for reference, VA determined by VIBAN lookup
        private String creditorName;
        private String creditorBic;

        // Remittance information - maps to Transaction.description
        private String remittanceInfo;
        private String structuredRef;

        // Dates
        private LocalDate requestedExecutionDate;
        private LocalDateTime creationDateTime;

        // Channel info - maps to Transaction.channel
        @Builder.Default
        private String channel = "ISO20022";

        // Raw XML for audit
        private String rawXml;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InwardPaymentResponse {
        private boolean success;
        private String statusCode;
        private String statusReason;

        // Transaction reference - from Transaction.referenceNumber
        private String transactionReference;
        private UUID transactionId;

        // VA credited
        private UUID virtualAccountId;
        private String vaNumber;

        // VIBAN used for routing
        private UUID vibanId;
        private String viban;

        // Balances
        private BigDecimal balanceBefore;
        private BigDecimal balanceAfter;

        // Reconciliation result
        private boolean autoReconciled;
        private String reconciledReferenceType;
        private String reconciledReferenceId;

        // Processing metrics
        private long processingTimeMs;

        // Error info if failed
        private String errorCode;
        private String errorMessage;
    }

    // ========================================================================
    // OUTWARD PAYMENT (pain.001) - Maps to POBO Debit
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OutwardPaymentRequest {
        // Source VA for debit
        private UUID sourceVaId;

        // Message identification - auto-generated or provided
        private String messageId;
        private String paymentInfoId;
        private String instructionId;
        private String endToEndId;

        // Amount - maps to Transaction.amount/currencyCode
        private BigDecimal amount;
        private String currency;

        // Debtor (source) info - populated from VA
        private String debtorName;
        private String debtorAccount;
        private String debtorBic;

        // Creditor (beneficiary) - maps to Transaction.beneficiary*
        private String creditorName;
        private String creditorAccount;
        private String creditorBic;
        private String creditorBankName;

        // Remittance info - maps to Transaction.description
        private String remittanceInfo;
        private String structuredRef;

        // Dates
        private LocalDate requestedExecutionDate;

        // POBO indicator - maps to Transaction.isPobo
        @Builder.Default
        private boolean isPobo = false;
        private String behalfOfEntity;
        private UUID behalfOfVaId;

        // Urgency/priority
        @Builder.Default
        private String instructionPriority = "NORM";  // NORM, HIGH

        // Service level
        @Builder.Default
        private String serviceLevel = "SEPA";  // SEPA, NURG, URGP
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OutwardPaymentResponse {
        private boolean success;
        private String statusCode;
        private String statusReason;

        // Transaction info
        private String transactionReference;
        private UUID transactionId;

        // Message info
        private String messageId;
        private String pain001Xml;  // Generated ISO 20022 XML

        // Source VA
        private UUID sourceVaId;
        private BigDecimal balanceBefore;
        private BigDecimal balanceAfter;

        // Fee info
        private BigDecimal feeAmount;
        private BigDecimal netAmount;

        // Processing
        private long processingTimeMs;

        // Error info
        private String errorCode;
        private String errorMessage;
    }

    // ========================================================================
    // PAYMENT STATUS (pain.002 / pacs.002)
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentStatusRequest {
        private String originalMessageId;
        private String originalInstructionId;
        private String originalEndToEndId;
        private String transactionReference;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentStatusResponse {
        private String messageId;
        private String originalMessageId;
        private String originalInstructionId;
        private String originalEndToEndId;

        // Status - maps to Transaction.status
        private String transactionStatus;  // ACCP, ACSP, ACSC, RJCT, PDNG
        private String statusReasonCode;
        private String statusReasonDescription;

        // Transaction details
        private UUID transactionId;
        private String transactionReference;
        private BigDecimal amount;
        private String currency;
        private LocalDateTime transactionDate;
        private LocalDate valueDate;

        // pain.002 XML if requested
        private String pain002Xml;
    }

    // ========================================================================
    // BANK STATEMENT (camt.053)
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatementRequest {
        private UUID virtualAccountId;
        private String vaNumber;
        private LocalDate fromDate;
        private LocalDate toDate;
        @Builder.Default
        private String format = "CAMT053";  // CAMT053, CAMT052 (intraday)
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatementResponse {
        private String messageId;
        private String statementId;

        // Account info
        private String accountIban;
        private String accountCurrency;

        // Period
        private LocalDate fromDate;
        private LocalDate toDate;

        // Balances
        private BigDecimal openingBalance;
        private BigDecimal closingBalance;

        // Summary
        private int creditCount;
        private BigDecimal creditSum;
        private int debitCount;
        private BigDecimal debitSum;

        // camt.053 XML
        private String camt053Xml;

        // Entry count
        private int entryCount;
    }

    // ========================================================================
    // BULK PAYMENT (pain.001 with multiple transactions)
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkPaymentRequest {
        private UUID sourceVaId;
        private String messageId;
        private String paymentInfoId;

        // Common fields for all payments
        private String debtorName;
        private String debtorAccount;
        private String debtorBic;

        private LocalDate requestedExecutionDate;
        private String serviceLevel;

        // Individual payment instructions
        private java.util.List<PaymentInstruction> instructions;

        // POBO settings
        @Builder.Default
        private boolean isPobo = false;
        private String behalfOfEntity;
        private UUID behalfOfVaId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentInstruction {
        private String instructionId;
        private String endToEndId;

        private BigDecimal amount;
        private String currency;

        private String creditorName;
        private String creditorAccount;
        private String creditorBic;

        private String remittanceInfo;
        private String structuredRef;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkPaymentResponse {
        private boolean success;
        private String messageId;
        private String pain001Xml;

        private int totalCount;
        private int successCount;
        private int failedCount;

        private BigDecimal totalAmount;
        private BigDecimal totalFees;

        private java.util.List<PaymentInstructionResult> results;

        private long processingTimeMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentInstructionResult {
        private String instructionId;
        private String endToEndId;
        private boolean success;
        private String transactionReference;
        private UUID transactionId;
        private String errorCode;
        private String errorMessage;
    }
}
