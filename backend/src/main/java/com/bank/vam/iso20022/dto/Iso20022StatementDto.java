package com.bank.vam.iso20022.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * ISO 20022 Statement DTOs for camt.053 (Bank-to-Customer Statement) and
 * camt.054 (Bank-to-Customer Debit/Credit Notification).
 *
 * Supports:
 * - camt.053.001.08 - End-of-day account statements
 * - camt.054.001.08 - Real-time credit/debit notifications
 * - Aggregated statements with child account consolidation
 * - Async generation for large date ranges
 * - XML and JSON output formats
 */
@Schema(description = "ISO 20022 Statement DTOs")
public class Iso20022StatementDto {

    // ========================================================================
    // OUTPUT FORMATS
    // ========================================================================

    public enum OutputFormat {
        @Schema(description = "ISO 20022 XML format")
        XML,
        @Schema(description = "JSON format with ISO 20022 structure")
        JSON,
        @Schema(description = "PDF document")
        PDF,
        @Schema(description = "CSV spreadsheet")
        CSV
    }

    public enum StatementType {
        @Schema(description = "End-of-day statement (camt.053)")
        CAMT053,
        @Schema(description = "Intraday statement (camt.052)")
        CAMT052,
        @Schema(description = "Debit/Credit notification (camt.054)")
        CAMT054
    }

    public enum GenerationStatus {
        @Schema(description = "Statement generation in progress")
        PENDING,
        @Schema(description = "Statement generation in progress")
        PROCESSING,
        @Schema(description = "Statement generated successfully")
        COMPLETED,
        @Schema(description = "Statement generation failed")
        FAILED,
        @Schema(description = "Statement expired and no longer available")
        EXPIRED
    }

    // ========================================================================
    // CAMT.053 - BANK TO CUSTOMER STATEMENT REQUEST/RESPONSE
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Request to generate camt.053 Bank-to-Customer Statement")
    public static class Camt053GenerateRequest {

        @NotNull
        @Schema(description = "Virtual Account ID", required = true)
        private UUID vaId;

        @Schema(description = "Virtual Account Number (alternative to vaId)")
        private String vaNumber;

        @NotNull
        @Schema(description = "Statement period start date", required = true, example = "2024-01-01")
        private LocalDate fromDate;

        @NotNull
        @Schema(description = "Statement period end date", required = true, example = "2024-01-31")
        private LocalDate toDate;

        @Builder.Default
        @Schema(description = "Output format", defaultValue = "XML")
        private OutputFormat format = OutputFormat.XML;

        @Builder.Default
        @Schema(description = "Include child/subsidiary accounts in statement", defaultValue = "false")
        private boolean includeChildren = false;

        @Builder.Default
        @Schema(description = "Include pending (unbooked) transactions", defaultValue = "false")
        private boolean includePending = false;

        @Schema(description = "Limit number of entries (for pagination)")
        private Integer maxEntries;

        @Schema(description = "Page number for paginated results (0-based)")
        private Integer page;

        @Schema(description = "Callback URL for async notification when statement is ready")
        private String callbackUrl;

        @Schema(description = "Request correlation ID for tracking")
        private String requestId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Response for camt.053 statement generation")
    public static class Camt053Response {

        @Schema(description = "Unique statement identifier")
        private String statementId;

        @Schema(description = "ISO 20022 message ID")
        private String messageId;

        @Schema(description = "Generation status")
        private GenerationStatus status;

        @Schema(description = "Virtual Account ID")
        private UUID vaId;

        @Schema(description = "Virtual Account Number")
        private String vaNumber;

        @Schema(description = "Account IBAN/VIBAN")
        private String accountIban;

        @Schema(description = "Account currency")
        private String currency;

        @Schema(description = "Statement period start")
        private LocalDate fromDate;

        @Schema(description = "Statement period end")
        private LocalDate toDate;

        @Schema(description = "Statement generation timestamp")
        private LocalDateTime generatedAt;

        @Schema(description = "Statement summary with balances and totals")
        private StatementSummary summary;

        @Schema(description = "Transaction entries")
        private List<StatementEntry> entries;

        @Schema(description = "Child account statements (when includeChildren=true)")
        private List<ChildAccountStatement> childStatements;

        @Schema(description = "Pagination information")
        private PaginationInfo pagination;

        @Schema(description = "Download URL for generated statement file")
        private String downloadUrl;

        @Schema(description = "camt.053 XML content (when format=XML)")
        private String camt053Xml;

        @Schema(description = "Statement expiry timestamp")
        private LocalDateTime expiresAt;

        @Schema(description = "Processing time in milliseconds")
        private Long processingTimeMs;

        @Schema(description = "Error message if generation failed")
        private String errorMessage;
    }

    // ========================================================================
    // CAMT.054 - DEBIT/CREDIT NOTIFICATION REQUEST/RESPONSE
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Request to generate camt.054 Debit/Credit Notification")
    public static class Camt054GenerateRequest {

        @NotNull
        @Schema(description = "Virtual Account ID", required = true)
        private UUID vaId;

        @Schema(description = "Virtual Account Number (alternative to vaId)")
        private String vaNumber;

        @Schema(description = "Notification period start date")
        private LocalDate fromDate;

        @Schema(description = "Notification period end date")
        private LocalDate toDate;

        @Builder.Default
        @Schema(description = "Output format", defaultValue = "XML")
        private OutputFormat format = OutputFormat.XML;

        @Builder.Default
        @Schema(description = "Include only credit notifications", defaultValue = "false")
        private boolean creditsOnly = false;

        @Builder.Default
        @Schema(description = "Include only debit notifications", defaultValue = "false")
        private boolean debitsOnly = false;

        @Schema(description = "Transaction ID for single transaction notification")
        private UUID transactionId;

        @Schema(description = "Minimum amount filter")
        private BigDecimal minAmount;

        @Schema(description = "Maximum amount filter")
        private BigDecimal maxAmount;

        @Schema(description = "Request correlation ID for tracking")
        private String requestId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Response for camt.054 notification generation")
    public static class Camt054Response {

        @Schema(description = "Unique notification identifier")
        private String notificationId;

        @Schema(description = "ISO 20022 message ID")
        private String messageId;

        @Schema(description = "Generation status")
        private GenerationStatus status;

        @Schema(description = "Virtual Account ID")
        private UUID vaId;

        @Schema(description = "Virtual Account Number")
        private String vaNumber;

        @Schema(description = "Account IBAN/VIBAN")
        private String accountIban;

        @Schema(description = "Account currency")
        private String currency;

        @Schema(description = "Notification period start")
        private LocalDate fromDate;

        @Schema(description = "Notification period end")
        private LocalDate toDate;

        @Schema(description = "Notification generation timestamp")
        private LocalDateTime generatedAt;

        @Schema(description = "Notification summary")
        private NotificationSummary summary;

        @Schema(description = "Notification entries")
        private List<NotificationEntry> entries;

        @Schema(description = "camt.054 XML content (when format=XML)")
        private String camt054Xml;

        @Schema(description = "Download URL for generated notification file")
        private String downloadUrl;

        @Schema(description = "Processing time in milliseconds")
        private Long processingTimeMs;

        @Schema(description = "Error message if generation failed")
        private String errorMessage;
    }

    // ========================================================================
    // AGGREGATED STATEMENT REQUEST/RESPONSE
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Request for aggregated statement across account hierarchy")
    public static class AggregatedStatementRequest {

        @NotNull
        @Schema(description = "Parent Virtual Account ID", required = true)
        private UUID vaId;

        @NotNull
        @Schema(description = "Statement period start date", required = true)
        private LocalDate fromDate;

        @NotNull
        @Schema(description = "Statement period end date", required = true)
        private LocalDate toDate;

        @Builder.Default
        @Schema(description = "Include child accounts in aggregation", defaultValue = "true")
        private boolean includeChildren = true;

        @Builder.Default
        @Schema(description = "Aggregation depth (levels of hierarchy)", defaultValue = "-1 (unlimited)")
        private int depth = -1;

        @Builder.Default
        @Schema(description = "Output format", defaultValue = "JSON")
        private OutputFormat format = OutputFormat.JSON;

        @Builder.Default
        @Schema(description = "Group by currency", defaultValue = "true")
        private boolean groupByCurrency = true;

        @Builder.Default
        @Schema(description = "Include detailed entries", defaultValue = "false")
        private boolean includeDetails = false;

        @Schema(description = "Request correlation ID for tracking")
        private String requestId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Aggregated statement response")
    public static class AggregatedStatementResponse {

        @Schema(description = "Unique statement identifier")
        private String statementId;

        @Schema(description = "Parent Virtual Account ID")
        private UUID vaId;

        @Schema(description = "Parent Virtual Account Number")
        private String vaNumber;

        @Schema(description = "Statement period start")
        private LocalDate fromDate;

        @Schema(description = "Statement period end")
        private LocalDate toDate;

        @Schema(description = "Statement generation timestamp")
        private LocalDateTime generatedAt;

        @Schema(description = "Number of accounts included")
        private int accountCount;

        @Schema(description = "Aggregated summary by currency")
        private List<CurrencySummary> currencySummaries;

        @Schema(description = "Total aggregated summary (base currency)")
        private StatementSummary totalSummary;

        @Schema(description = "Individual account statements")
        private List<ChildAccountStatement> accountStatements;

        @Schema(description = "Download URL for generated statement file")
        private String downloadUrl;

        @Schema(description = "Processing time in milliseconds")
        private Long processingTimeMs;
    }

    // ========================================================================
    // STATEMENT HISTORY REQUEST/RESPONSE
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Request to retrieve statement history")
    public static class StatementHistoryRequest {

        @Schema(description = "Virtual Account ID filter")
        private UUID vaId;

        @Schema(description = "Corporate ID filter")
        private UUID corporateId;

        @Schema(description = "Statement type filter")
        private StatementType type;

        @Schema(description = "Status filter")
        private GenerationStatus status;

        @Schema(description = "Generated after this date")
        private LocalDateTime generatedAfter;

        @Schema(description = "Generated before this date")
        private LocalDateTime generatedBefore;

        @Builder.Default
        @Schema(description = "Page number (0-based)", defaultValue = "0")
        private int page = 0;

        @Builder.Default
        @Schema(description = "Page size", defaultValue = "20")
        private int size = 20;

        @Builder.Default
        @Schema(description = "Sort field", defaultValue = "generatedAt")
        private String sortBy = "generatedAt";

        @Builder.Default
        @Schema(description = "Sort direction", defaultValue = "desc")
        private String sortDirection = "desc";
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Statement history entry")
    public static class StatementHistoryEntry {

        @Schema(description = "Statement ID")
        private String statementId;

        @Schema(description = "Statement type")
        private StatementType type;

        @Schema(description = "Virtual Account ID")
        private UUID vaId;

        @Schema(description = "Virtual Account Number")
        private String vaNumber;

        @Schema(description = "Corporate ID")
        private UUID corporateId;

        @Schema(description = "Statement period start")
        private LocalDate fromDate;

        @Schema(description = "Statement period end")
        private LocalDate toDate;

        @Schema(description = "Generation status")
        private GenerationStatus status;

        @Schema(description = "Output format")
        private OutputFormat format;

        @Schema(description = "Statement generated timestamp")
        private LocalDateTime generatedAt;

        @Schema(description = "Statement expiry timestamp")
        private LocalDateTime expiresAt;

        @Schema(description = "File size in bytes")
        private Long fileSize;

        @Schema(description = "File size formatted (e.g., '256 KB')")
        private String fileSizeFormatted;

        @Schema(description = "Entry count in statement")
        private Integer entryCount;

        @Schema(description = "Download URL")
        private String downloadUrl;

        @Schema(description = "Includes child accounts")
        private boolean includesChildren;

        @Schema(description = "Request ID for tracking")
        private String requestId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Statement history list response")
    public static class StatementHistoryResponse {

        @Schema(description = "Statement history entries")
        private List<StatementHistoryEntry> statements;

        @Schema(description = "Pagination information")
        private PaginationInfo pagination;
    }

    // ========================================================================
    // STATEMENT DETAIL & DOWNLOAD
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Detailed statement information")
    public static class StatementDetail {

        @Schema(description = "Statement ID")
        private String statementId;

        @Schema(description = "Statement type")
        private StatementType type;

        @Schema(description = "Generation status")
        private GenerationStatus status;

        @Schema(description = "Virtual Account ID")
        private UUID vaId;

        @Schema(description = "Virtual Account Number")
        private String vaNumber;

        @Schema(description = "Virtual Account Name")
        private String vaName;

        @Schema(description = "Account IBAN/VIBAN")
        private String accountIban;

        @Schema(description = "Account currency")
        private String currency;

        @Schema(description = "Corporate ID")
        private UUID corporateId;

        @Schema(description = "Corporate name")
        private String corporateName;

        @Schema(description = "Statement period start")
        private LocalDate fromDate;

        @Schema(description = "Statement period end")
        private LocalDate toDate;

        @Schema(description = "Statement generation timestamp")
        private LocalDateTime generatedAt;

        @Schema(description = "Statement expiry timestamp")
        private LocalDateTime expiresAt;

        @Schema(description = "Output format")
        private OutputFormat format;

        @Schema(description = "Statement summary")
        private StatementSummary summary;

        @Schema(description = "Transaction entries")
        private List<StatementEntry> entries;

        @Schema(description = "Pagination for entries")
        private PaginationInfo pagination;

        @Schema(description = "Download URL")
        private String downloadUrl;

        @Schema(description = "Includes child accounts")
        private boolean includesChildren;

        @Schema(description = "Child account count")
        private Integer childAccountCount;

        @Schema(description = "File size in bytes")
        private Long fileSize;

        @Schema(description = "Processing time in milliseconds")
        private Long processingTimeMs;

        @Schema(description = "ISO 20022 message ID")
        private String messageId;

        @Schema(description = "Electronic sequence number")
        private Integer sequenceNumber;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Statement download response")
    public static class StatementDownloadResponse {

        @Schema(description = "Statement ID")
        private String statementId;

        @Schema(description = "File name")
        private String fileName;

        @Schema(description = "Content type (MIME)")
        private String contentType;

        @Schema(description = "File size in bytes")
        private Long fileSize;

        @Schema(description = "Download URL (presigned, temporary)")
        private String downloadUrl;

        @Schema(description = "URL expiry time")
        private LocalDateTime urlExpiresAt;

        @Schema(description = "Content (for inline response)")
        private String content;
    }

    // ========================================================================
    // SUPPORTING STRUCTURES
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Statement summary with balances and totals")
    public static class StatementSummary {

        @Schema(description = "Opening balance at period start")
        private BigDecimal openingBalance;

        @Schema(description = "Opening balance date")
        private LocalDate openingBalanceDate;

        @Schema(description = "Opening balance credit/debit indicator")
        private String openingBalanceIndicator;

        @Schema(description = "Closing balance at period end")
        private BigDecimal closingBalance;

        @Schema(description = "Closing balance date")
        private LocalDate closingBalanceDate;

        @Schema(description = "Closing balance credit/debit indicator")
        private String closingBalanceIndicator;

        @Schema(description = "Available balance")
        private BigDecimal availableBalance;

        @Schema(description = "Currency code")
        private String currency;

        @Schema(description = "Total number of entries")
        private int entryCount;

        @Schema(description = "Number of credit entries")
        private int creditCount;

        @Schema(description = "Total credit amount")
        private BigDecimal creditTotal;

        @Schema(description = "Number of debit entries")
        private int debitCount;

        @Schema(description = "Total debit amount")
        private BigDecimal debitTotal;

        @Schema(description = "Net movement (credits - debits)")
        private BigDecimal netMovement;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Statement entry (transaction)")
    public static class StatementEntry {

        @Schema(description = "Entry sequence number")
        private Integer sequenceNumber;

        @Schema(description = "Transaction ID")
        private UUID transactionId;

        @Schema(description = "Entry reference (Account servicer reference)")
        private String entryReference;

        @Schema(description = "Transaction amount")
        private BigDecimal amount;

        @Schema(description = "Currency code")
        private String currency;

        @Schema(description = "Credit/Debit indicator (CRDT/DBIT)")
        private String creditDebitIndicator;

        @Schema(description = "Is reversal entry")
        private boolean reversal;

        @Schema(description = "Entry status (BOOK/PDNG)")
        private String status;

        @Schema(description = "Booking date")
        private LocalDate bookingDate;

        @Schema(description = "Value date")
        private LocalDate valueDate;

        @Schema(description = "Bank transaction code (domain/family/subfamily)")
        private BankTransactionCode bankTransactionCode;

        @Schema(description = "End-to-end ID")
        private String endToEndId;

        @Schema(description = "Instruction ID")
        private String instructionId;

        @Schema(description = "Message ID")
        private String messageId;

        @Schema(description = "Debtor (payer) details")
        private PartyDetails debtor;

        @Schema(description = "Debtor account")
        private AccountDetails debtorAccount;

        @Schema(description = "Creditor (payee) details")
        private PartyDetails creditor;

        @Schema(description = "Creditor account")
        private AccountDetails creditorAccount;

        @Schema(description = "Remittance information (unstructured)")
        private String remittanceInfo;

        @Schema(description = "Structured remittance reference")
        private String structuredReference;

        @Schema(description = "Additional entry information")
        private String additionalInfo;

        @Schema(description = "Balance after this entry")
        private BigDecimal balanceAfter;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Notification summary")
    public static class NotificationSummary {

        @Schema(description = "Total notifications")
        private int totalCount;

        @Schema(description = "Credit notification count")
        private int creditCount;

        @Schema(description = "Total credit amount")
        private BigDecimal creditTotal;

        @Schema(description = "Debit notification count")
        private int debitCount;

        @Schema(description = "Total debit amount")
        private BigDecimal debitTotal;

        @Schema(description = "Currency code")
        private String currency;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Notification entry")
    public static class NotificationEntry {

        @Schema(description = "Transaction ID")
        private UUID transactionId;

        @Schema(description = "Entry reference")
        private String entryReference;

        @Schema(description = "Amount")
        private BigDecimal amount;

        @Schema(description = "Currency")
        private String currency;

        @Schema(description = "Credit/Debit indicator")
        private String creditDebitIndicator;

        @Schema(description = "Booking timestamp")
        private LocalDateTime bookingDateTime;

        @Schema(description = "Value date")
        private LocalDate valueDate;

        @Schema(description = "End-to-end ID")
        private String endToEndId;

        @Schema(description = "Counterparty details")
        private PartyDetails counterparty;

        @Schema(description = "Counterparty account")
        private AccountDetails counterpartyAccount;

        @Schema(description = "Remittance information")
        private String remittanceInfo;

        @Schema(description = "Balance after transaction")
        private BigDecimal balanceAfter;

        @Schema(description = "Notification status")
        private String status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Child account statement summary")
    public static class ChildAccountStatement {

        @Schema(description = "Child Virtual Account ID")
        private UUID vaId;

        @Schema(description = "Child Virtual Account Number")
        private String vaNumber;

        @Schema(description = "Child Virtual Account Name")
        private String vaName;

        @Schema(description = "Account IBAN/VIBAN")
        private String accountIban;

        @Schema(description = "Currency")
        private String currency;

        @Schema(description = "Hierarchy level")
        private int hierarchyLevel;

        @Schema(description = "Parent VA ID")
        private UUID parentVaId;

        @Schema(description = "Summary for this account")
        private StatementSummary summary;

        @Schema(description = "Entries for this account (when includeDetails=true)")
        private List<StatementEntry> entries;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Summary grouped by currency")
    public static class CurrencySummary {

        @Schema(description = "Currency code")
        private String currency;

        @Schema(description = "Number of accounts in this currency")
        private int accountCount;

        @Schema(description = "Aggregated summary")
        private StatementSummary summary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Bank transaction code (ISO 20022)")
    public static class BankTransactionCode {

        @Schema(description = "Domain code")
        private String domain;

        @Schema(description = "Family code")
        private String family;

        @Schema(description = "Subfamily code")
        private String subFamily;

        @Schema(description = "Proprietary code")
        private String proprietary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Party (person/organization) details")
    public static class PartyDetails {

        @Schema(description = "Name")
        private String name;

        @Schema(description = "Address line 1")
        private String addressLine1;

        @Schema(description = "Address line 2")
        private String addressLine2;

        @Schema(description = "City")
        private String city;

        @Schema(description = "Country code (ISO)")
        private String country;

        @Schema(description = "Postal code")
        private String postalCode;

        @Schema(description = "Identification")
        private String identification;

        @Schema(description = "BIC code")
        private String bic;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Account details")
    public static class AccountDetails {

        @Schema(description = "Account IBAN")
        private String iban;

        @Schema(description = "Account number (if not IBAN)")
        private String accountNumber;

        @Schema(description = "Account type")
        private String accountType;

        @Schema(description = "Currency")
        private String currency;

        @Schema(description = "Account name")
        private String name;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Pagination information")
    public static class PaginationInfo {

        @Schema(description = "Current page number (0-based)")
        private int page;

        @Schema(description = "Page size")
        private int size;

        @Schema(description = "Total elements")
        private long totalElements;

        @Schema(description = "Total pages")
        private int totalPages;

        @Schema(description = "Has next page")
        private boolean hasNext;

        @Schema(description = "Has previous page")
        private boolean hasPrevious;
    }

    // ========================================================================
    // ASYNC JOB TRACKING
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Async statement generation job status")
    public static class AsyncJobStatus {

        @Schema(description = "Job ID")
        private String jobId;

        @Schema(description = "Statement ID (when completed)")
        private String statementId;

        @Schema(description = "Job status")
        private GenerationStatus status;

        @Schema(description = "Progress percentage (0-100)")
        private Integer progressPercent;

        @Schema(description = "Current processing step")
        private String currentStep;

        @Schema(description = "Job start time")
        private LocalDateTime startedAt;

        @Schema(description = "Estimated completion time")
        private LocalDateTime estimatedCompletion;

        @Schema(description = "Job completion time")
        private LocalDateTime completedAt;

        @Schema(description = "Download URL (when completed)")
        private String downloadUrl;

        @Schema(description = "Error message (if failed)")
        private String errorMessage;

        @Schema(description = "Entries processed so far")
        private Integer entriesProcessed;

        @Schema(description = "Total entries to process")
        private Integer totalEntries;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Async job creation response")
    public static class AsyncJobResponse {

        @Schema(description = "Job ID for tracking")
        private String jobId;

        @Schema(description = "Job status")
        private GenerationStatus status;

        @Schema(description = "Message")
        private String message;

        @Schema(description = "URL to check job status")
        private String statusUrl;

        @Schema(description = "Estimated completion time")
        private LocalDateTime estimatedCompletion;
    }
}
