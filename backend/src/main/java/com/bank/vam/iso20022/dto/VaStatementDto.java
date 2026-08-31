package com.bank.vam.iso20022.dto;

import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Virtual Account Statement DTOs - VAM-specific extensions bridging internal
 * models with ISO 20022 camt.053/camt.054 messages.
 *
 * <h2>VA Hierarchy Statement Considerations:</h2>
 *
 * <p>Virtual Account hierarchies require special handling for statements:</p>
 *
 * <h3>1. Individual VA Statement</h3>
 * <ul>
 *   <li>Single VA transaction history</li>
 *   <li>Own balances only</li>
 *   <li>Maps directly to camt.053 with single Stmt element</li>
 * </ul>
 *
 * <h3>2. Aggregated VA Statement (Parent with Children)</h3>
 * <ul>
 *   <li>Parent VA + all child VA transactions</li>
 *   <li>Aggregated balances from hierarchy</li>
 *   <li>Each entry tagged with source VA</li>
 *   <li>Option to show hierarchy breakdown</li>
 * </ul>
 *
 * <h3>3. Legal Entity Statement</h3>
 * <ul>
 *   <li>All VAs owned by a legal entity</li>
 *   <li>Useful for POBO/COBO reconciliation</li>
 *   <li>Cross-program if entity has multiple</li>
 * </ul>
 *
 * <h3>4. Multi-Currency Statement</h3>
 * <ul>
 *   <li>Includes Currency Mirror VAs</li>
 *   <li>Balances in original and base currency</li>
 *   <li>FX conversion rates included</li>
 * </ul>
 *
 * <h3>5. Hierarchy Balance Calculation</h3>
 * <pre>
 * ROOT (L1) - Aggregated Balance = Sum of all L2-L7 balances
 *   └── Region (L2) - Aggregated = Sum of L3-L7 under this node
 *         └── Entity (L3) - Aggregated = Sum of L4-L7 under this node
 *               └── ... (L4-L6)
 *                     └── VA (L7) - Own Balance only
 * </pre>
 *
 * @see Camt053Dto for ISO 20022 camt.053 structure
 * @see Camt054Dto for ISO 20022 camt.054 structure
 */
public class VaStatementDto {

    // ========================================================================
    // STATEMENT REQUEST TYPES
    // ========================================================================

    /**
     * Comprehensive statement request for VAM portal.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VaStatementRequest {

        /** Virtual Account ID - primary identifier */
        private UUID virtualAccountId;

        /** Alternative: VA Number */
        private String vaNumber;

        /** Alternative: VIBAN */
        private String viban;

        /** Statement period start */
        @NotNull(message = "From date is required")
        private LocalDate fromDate;

        /** Statement period end */
        @NotNull(message = "To date is required")
        private LocalDate toDate;

        /** Statement scope */
        @Builder.Default
        private StatementScope scope = StatementScope.INDIVIDUAL;

        /** Include child VA transactions */
        @Builder.Default
        private Boolean includeChildren = false;

        /** Maximum depth for child inclusion (-1 = all) */
        @Builder.Default
        private Integer maxDepth = -1;

        /** Specific child VAs to include (if not all) */
        private List<UUID> childVaIds;

        /** Include pending/unbooked transactions */
        @Builder.Default
        private Boolean includePending = false;

        /** Include internal transactions (fees, sweeps) */
        @Builder.Default
        private Boolean includeInternal = true;

        /** Filter by currency */
        private String currencyCode;

        /** Filter by movement type */
        private List<String> movementTypes;

        /** Filter by transaction category */
        private List<String> transactionCategories;

        /** Minimum amount filter */
        private BigDecimal minAmount;

        /** Maximum amount filter */
        private BigDecimal maxAmount;

        /** Output format */
        @Builder.Default
        private OutputFormat outputFormat = OutputFormat.JSON;

        /** Include ISO 20022 XML */
        @Builder.Default
        private Boolean includeCamt053Xml = false;

        /** Show hierarchy breakdown */
        @Builder.Default
        private Boolean showHierarchyBreakdown = false;

        /** Show balance in base currency */
        @Builder.Default
        private Boolean convertToBaseCurrency = false;

        /** Base currency for conversion */
        private String baseCurrency;

        /** Legal Entity filter (for ENTITY scope) */
        private UUID legalEntityId;

        /** Program filter */
        private UUID programId;

        /** Corporate filter */
        private UUID corporateId;

        /** Pagination */
        @Builder.Default
        private Integer page = 0;

        @Builder.Default
        private Integer pageSize = 100;

        /** Client reference for tracking */
        private String clientReference;
    }

    /**
     * Statement scope options.
     */
    public enum StatementScope {
        /** Single VA only */
        INDIVIDUAL,

        /** VA with all children */
        AGGREGATED,

        /** All VAs for a legal entity */
        ENTITY,

        /** All VAs in a program */
        PROGRAM,

        /** Full hierarchy from ROOT */
        HIERARCHY,

        /** Specific currency only */
        CURRENCY
    }

    /**
     * Output format options.
     */
    public enum OutputFormat {
        /** JSON response */
        JSON,

        /** ISO 20022 camt.053 XML */
        CAMT053,

        /** PDF document */
        PDF,

        /** CSV export */
        CSV,

        /** Excel export */
        XLSX,

        /** MT940 (legacy SWIFT) */
        MT940
    }

    // ========================================================================
    // STATEMENT RESPONSE
    // ========================================================================

    /**
     * Comprehensive statement response for VAM portal.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VaStatementResponse {

        /** Statement unique identifier */
        private String statementId;

        /** Message ID (ISO 20022 format) */
        private String messageId;

        /** Statement scope used */
        private StatementScope scope;

        /** Generation timestamp */
        private LocalDateTime generatedAt;

        /** Statement period */
        private LocalDate fromDate;
        private LocalDate toDate;

        /** Primary account information */
        private VaAccountInfo primaryAccount;

        /** Program information */
        private ProgramInfo program;

        /** Corporate information */
        private CorporateInfo corporate;

        /** Balance information */
        private BalanceInfo balances;

        /** Transaction summary */
        private TransactionSummary summary;

        /** Transaction entries */
        private List<VaStatementEntry> entries;

        /** Child VA breakdown (if aggregated) */
        private List<ChildVaSummary> childVaSummaries;

        /** Hierarchy breakdown (if requested) */
        private HierarchyBreakdown hierarchyBreakdown;

        /** Currency breakdown (for multi-currency) */
        private List<CurrencyBreakdown> currencyBreakdowns;

        /** Pagination info */
        private PaginationInfo pagination;

        /** ISO 20022 camt.053 XML (if requested) */
        private String camt053Xml;

        /** Processing info */
        private ProcessingInfo processingInfo;
    }

    // ========================================================================
    // ACCOUNT INFO DTOs
    // ========================================================================

    /**
     * Virtual Account information for statements.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VaAccountInfo {
        private UUID id;
        private String vaNumber;
        private String vaName;
        private String viban;
        private String currencyCode;
        private String accountCategory;  // ROOT, AGGREGATION, TRANSACTION, etc.
        private String accountType;      // REAL, VIRTUAL
        private String status;

        // Hierarchy context
        private UUID parentVaId;
        private String parentVaNumber;
        private String hierarchyPath;
        private Integer hierarchyLevel;
        private Integer childCount;

        // Ownership
        private UUID owningEntityId;
        private String owningEntityCode;
        private String owningEntityName;

        // Physical account (if applicable)
        private UUID physicalAccountId;
        private String physicalAccountNumber;
        private String physicalAccountIban;
        private String bankBic;
        private String bankName;
    }

    /**
     * Program information.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProgramInfo {
        private UUID id;
        private String code;
        private String name;
        private String type;
        private String baseCurrency;
    }

    /**
     * Corporate information.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CorporateInfo {
        private UUID id;
        private String name;
        private String registrationNumber;
        private String country;
    }

    // ========================================================================
    // BALANCE DTOs
    // ========================================================================

    /**
     * Balance information for statement.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BalanceInfo {
        /** Opening booked balance */
        private BalanceDetail openingBooked;

        /** Closing booked balance */
        private BalanceDetail closingBooked;

        /** Opening available balance */
        private BalanceDetail openingAvailable;

        /** Closing available balance */
        private BalanceDetail closingAvailable;

        /** Aggregated balance (for parent VAs) */
        private BalanceDetail aggregated;

        /** Mirror balance (for currency mirror VAs) */
        private BalanceDetail mirror;

        /** Balance in base currency (if conversion requested) */
        private BalanceDetail baseConverted;

        /** Credit limit info (if applicable) */
        private CreditLimitInfo creditLimit;
    }

    /**
     * Individual balance detail.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BalanceDetail {
        private BigDecimal amount;
        private String currencyCode;
        private String type;  // OPBD, CLBD, ITBD, etc.
        private String creditDebitIndicator;  // CRDT, DBIT
        private LocalDate date;
        private LocalDateTime dateTime;
    }

    /**
     * Credit limit information.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreditLimitInfo {
        private BigDecimal limit;
        private BigDecimal utilized;
        private BigDecimal available;
        private String currencyCode;
    }

    // ========================================================================
    // TRANSACTION SUMMARY
    // ========================================================================

    /**
     * Transaction summary for statement period.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransactionSummary {
        /** Total number of entries */
        private Integer totalEntries;

        /** Credit entries summary */
        private EntrySummary creditSummary;

        /** Debit entries summary */
        private EntrySummary debitSummary;

        /** Net movement */
        private BigDecimal netMovement;

        /** Currency */
        private String currencyCode;

        /** Breakdown by transaction type */
        private Map<String, EntrySummary> byTransactionType;

        /** Breakdown by movement type */
        private Map<String, EntrySummary> byMovementType;

        /** POBO/ROBO summary */
        private PoboRoboSummary poboRoboSummary;

        /** Fee summary */
        private FeeSummary feeSummary;
    }

    /**
     * Entry summary (count and sum).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntrySummary {
        private Integer count;
        private BigDecimal sum;
    }

    /**
     * POBO/ROBO transaction summary.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoboRoboSummary {
        private Integer poboCount;
        private BigDecimal poboAmount;
        private Integer roboCount;
        private BigDecimal roboAmount;
    }

    /**
     * Fee summary.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FeeSummary {
        private Integer feeCount;
        private BigDecimal totalFees;
        private Map<String, BigDecimal> byFeeType;
    }

    // ========================================================================
    // STATEMENT ENTRY
    // ========================================================================

    /**
     * Individual statement entry (transaction).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VaStatementEntry {
        // Core fields
        private UUID transactionId;
        private String referenceNumber;
        private String entryReference;

        // Amount
        private BigDecimal amount;
        private String currencyCode;
        private String creditDebitIndicator;

        // Status
        private String status;  // BOOK, PDNG, etc.

        // Dates
        private LocalDateTime bookingDateTime;
        private LocalDate bookingDate;
        private LocalDate valueDate;

        // Movement info
        private String movementType;
        private String transactionCategory;

        // Bank transaction code (ISO 20022)
        private String domainCode;
        private String familyCode;
        private String subFamilyCode;

        // Description
        private String description;
        private String additionalInfo;

        // Balances
        private BigDecimal balanceBefore;
        private BigDecimal balanceAfter;

        // Counterparty
        private PartyInfo remitter;
        private PartyInfo beneficiary;

        // References
        private String endToEndId;
        private String correlationId;
        private String externalReference;
        private String bankReference;

        // VIBAN routing
        private String viban;
        private String matchStatus;
        private String matchedInvoiceReference;

        // POBO/ROBO
        private Boolean isPobo;
        private Boolean isRobo;
        private String behalfOfEntity;
        private UUID behalfOfVaId;

        // Fees (if applicable)
        private BigDecimal feeAmount;
        private String feeType;

        // Child VA info (for aggregated statements)
        private UUID sourceVaId;
        private String sourceVaNumber;
        private String sourceVaName;

        // Legal entity (for entity statements)
        private UUID legalEntityId;
        private String legalEntityCode;

        // Related transactions
        private List<String> relatedTransactionIds;

        // Channel
        private String channel;

        // Remittance info
        private String remittanceUnstructured;
        private StructuredRemittance remittanceStructured;
    }

    /**
     * Party information (remitter/beneficiary).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartyInfo {
        private String name;
        private String accountNumber;
        private String iban;
        private String bic;
        private String bankName;
        private String country;
        private String addressLine1;
        private String addressLine2;
    }

    /**
     * Structured remittance information.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StructuredRemittance {
        private String documentType;
        private String documentNumber;
        private LocalDate documentDate;
        private BigDecimal documentAmount;
        private String creditorReference;
    }

    // ========================================================================
    // HIERARCHY BREAKDOWN
    // ========================================================================

    /**
     * Child VA summary for aggregated statements.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChildVaSummary {
        private UUID vaId;
        private String vaNumber;
        private String vaName;
        private String currencyCode;
        private Integer hierarchyLevel;
        private String owningEntityCode;

        // Balances
        private BigDecimal openingBalance;
        private BigDecimal closingBalance;

        // Transaction counts
        private Integer creditCount;
        private BigDecimal creditSum;
        private Integer debitCount;
        private BigDecimal debitSum;
        private BigDecimal netMovement;
    }

    /**
     * Full hierarchy breakdown.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HierarchyBreakdown {
        private UUID rootVaId;
        private String rootVaNumber;
        private String rootVaName;
        private List<HierarchyLevelSummary> levels;
        private BigDecimal totalAggregatedBalance;
        private String baseCurrency;
    }

    /**
     * Summary by hierarchy level.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HierarchyLevelSummary {
        private Integer level;
        private String levelName;
        private Integer nodeCount;
        private BigDecimal totalBalance;
        private BigDecimal totalCredits;
        private BigDecimal totalDebits;
    }

    /**
     * Currency breakdown for multi-currency statements.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurrencyBreakdown {
        private String currencyCode;
        private Integer vaCount;
        private BigDecimal totalBalance;
        private BigDecimal totalCredits;
        private BigDecimal totalDebits;
        private BigDecimal balanceInBase;
        private BigDecimal fxRate;
        private LocalDateTime fxRateTimestamp;
    }

    // ========================================================================
    // PAGINATION & PROCESSING
    // ========================================================================

    /**
     * Pagination information.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaginationInfo {
        private Integer page;
        private Integer pageSize;
        private Integer totalPages;
        private Long totalEntries;
        private Boolean hasMore;
        private Boolean isFirstPage;
        private Boolean isLastPage;
    }

    /**
     * Processing information.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessingInfo {
        private Boolean success;
        private String statusCode;
        private String statusMessage;
        private Long processingTimeMs;
        private LocalDateTime requestedAt;
        private LocalDateTime completedAt;
        private String generatedBy;
        private String clientReference;
        private String downloadUrl;
        private LocalDateTime expiresAt;
    }

    // ========================================================================
    // NOTIFICATION PREFERENCE (for camt.054)
    // ========================================================================

    /**
     * Statement notification preferences.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatementNotificationPreference {
        private UUID virtualAccountId;
        private Boolean enableRealTimeNotifications;
        private Boolean enableDailySummary;
        private Boolean enableMonthlySummary;
        private String preferredFormat;  // CAMT054, JSON, EMAIL
        private String webhookUrl;
        private String emailAddress;
        private BigDecimal minimumNotificationAmount;
        private List<String> notificationTypes;  // CREDIT, DEBIT, ALL
        private Boolean includeChildVas;
    }

    // ========================================================================
    // EXPORT REQUEST
    // ========================================================================

    /**
     * Statement export request.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExportStatementRequest {
        private String statementId;
        private OutputFormat format;
        private Boolean includeTransactionDetails;
        private Boolean includeBalanceHistory;
        private Boolean includeHierarchyBreakdown;
        private String language;  // EN, AR, etc.
        private String dateFormat;
        private String numberFormat;
    }

    /**
     * Statement export response.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExportStatementResponse {
        private String exportId;
        private String statementId;
        private OutputFormat format;
        private String status;  // PROCESSING, READY, FAILED
        private String downloadUrl;
        private Long fileSize;
        private LocalDateTime generatedAt;
        private LocalDateTime expiresAt;
        private String errorMessage;
    }
}
