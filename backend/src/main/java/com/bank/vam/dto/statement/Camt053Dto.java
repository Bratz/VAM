package com.bank.vam.dto.statement;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * DTOs for ISO 20022 camt.053 (Bank-to-Customer Statement) and camt.054 (Bank-to-Customer Debit Credit Notification).
 *
 * Supports hierarchical VA statement generation:
 * - Single VA statements (leaf level)
 * - Aggregated statements (includes all child VAs recursively)
 * - Consolidated statements (entire VA tree)
 *
 * Balance Types (ISO 20022):
 * - OPBD: Opening Booked Balance
 * - CLBD: Closing Booked Balance
 * - CLAV: Closing Available Balance
 * - OPAV: Opening Available Balance
 * - ITBD: Interim Booked Balance (intraday)
 * - ITAV: Interim Available Balance (intraday)
 * - PRCD: Previously Closed Booked Balance
 * - FWAV: Forward Available Balance
 */
public class Camt053Dto {

    // ========================================================================
    // REQUEST DTOs
    // ========================================================================

    /**
     * Request for generating a single VA statement.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatementRequest {
        private UUID vaId;
        private String vaNumber;
        private LocalDate fromDate;
        private LocalDate toDate;

        /** Statement type: CAMT053 (end-of-day) or CAMT052 (intraday) */
        @Builder.Default
        private StatementType statementType = StatementType.CAMT053;

        /** Include entry details (transactions) */
        @Builder.Default
        private boolean includeEntries = true;

        /** Maximum entries to include (pagination) */
        private Integer maxEntries;

        /** Page number for entry pagination */
        @Builder.Default
        private int page = 0;
    }

    /**
     * Request for generating an aggregated statement (includes child VAs).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AggregatedStatementRequest {
        private UUID aggregationVaId;
        private LocalDate fromDate;
        private LocalDate toDate;

        @Builder.Default
        private StatementType statementType = StatementType.CAMT053;

        /** Include entries from all child VAs */
        @Builder.Default
        private boolean includeChildEntries = true;

        /** Include VA identifier in entry details for traceability */
        @Builder.Default
        private boolean includeVaIdentifiers = true;

        /** Maximum hierarchy depth to traverse (null = unlimited) */
        private Integer maxDepth;

        /** Include summary per child VA */
        @Builder.Default
        private boolean includeSummaryByVa = false;

        /** Maximum entries to include */
        private Integer maxEntries;
    }

    // ========================================================================
    // RESPONSE DTOs (Statement Structure)
    // ========================================================================

    /**
     * Complete camt.053 statement response.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Camt053Statement {
        /** Unique message identification */
        private String messageId;

        /** Statement identification */
        private String statementId;

        /** Electronic sequence number */
        private int sequenceNumber;

        /** Statement creation timestamp */
        private LocalDateTime creationDateTime;

        /** Statement period */
        private LocalDate fromDate;
        private LocalDate toDate;

        /** Account information */
        private AccountInfo account;

        /** Whether this is an aggregated (hierarchical) statement */
        private boolean aggregated;

        /** List of child VA IDs included (for aggregated statements) */
        @Builder.Default
        private List<UUID> includedVaIds = new ArrayList<>();

        /** Balances (OPBD, CLBD, AVLB, etc.) */
        @Builder.Default
        private List<Balance> balances = new ArrayList<>();

        /** Transaction summary */
        private TransactionSummary transactionSummary;

        /** Individual entries (transactions) */
        @Builder.Default
        private List<StatementEntry> entries = new ArrayList<>();

        /** Summary per child VA (optional) */
        @Builder.Default
        private List<VaSummary> vaSummaries = new ArrayList<>();

        /** Generated camt.053 XML */
        private String xml;

        /** Processing metadata */
        private ProcessingInfo processingInfo;
    }

    /**
     * Account information in the statement.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AccountInfo {
        private UUID vaId;
        private String vaNumber;
        private String vaName;
        private String iban;  // VIBAN
        private String currencyCode;
        private String accountCategory;
        private UUID corporateId;
        private UUID programId;

        /** Owner name (corporate or legal entity) */
        private String ownerName;

        /** Servicer BIC */
        @Builder.Default
        private String servicerBic = "BANKAEXX";
    }

    /**
     * Balance information (OPBD, CLBD, etc.).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Balance {
        /** Balance type code: OPBD, CLBD, CLAV, ITBD, etc. */
        private BalanceType type;

        /** Amount (absolute value) */
        private BigDecimal amount;

        /** Currency code */
        private String currencyCode;

        /** Credit or Debit indicator */
        private CreditDebitIndicator creditDebitIndicator;

        /** Balance date */
        private LocalDate date;

        /** Balance timestamp (for intraday) */
        private LocalDateTime dateTime;
    }

    /**
     * Transaction summary (total credits/debits).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransactionSummary {
        /** Total number of entries */
        private int totalEntries;

        /** Credit entries */
        private int creditEntryCount;
        private BigDecimal creditEntrySum;

        /** Debit entries */
        private int debitEntryCount;
        private BigDecimal debitEntrySum;

        /** Net movement */
        private BigDecimal netMovement;
    }

    /**
     * Individual statement entry (transaction).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatementEntry {
        /** Entry reference (transaction reference) */
        private String entryReference;

        /** Amount */
        private BigDecimal amount;

        /** Currency code */
        private String currencyCode;

        /** Credit or Debit indicator */
        private CreditDebitIndicator creditDebitIndicator;

        /** Entry status: BOOK (booked), PDNG (pending) */
        @Builder.Default
        private EntryStatus status = EntryStatus.BOOK;

        /** Booking date */
        private LocalDate bookingDate;

        /** Value date */
        private LocalDate valueDate;

        /** Booking datetime (for sorting) */
        private LocalDateTime bookingDateTime;

        /** Account servicer reference */
        private String accountServicerReference;

        /** Bank transaction code */
        private BankTransactionCode bankTransactionCode;

        /** Entry details */
        private EntryDetails entryDetails;

        /** Source VA identifier (for aggregated statements) */
        private UUID sourceVaId;
        private String sourceVaNumber;
        private String sourceVaName;
    }

    /**
     * Entry details (transaction details).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntryDetails {
        /** Transaction ID */
        private UUID transactionId;

        /** Message ID */
        private String messageId;

        /** End-to-end ID */
        private String endToEndId;

        /** Instruction ID */
        private String instructionId;

        /** Related parties */
        private RelatedParties relatedParties;

        /** Remittance information (unstructured) */
        private String remittanceInfo;

        /** Structured remittance reference */
        private String structuredReference;

        /** Movement type */
        private String movementType;

        /** Channel */
        private String channel;
    }

    /**
     * Related parties in entry.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RelatedParties {
        /** Debtor (payer) */
        private String debtorName;
        private String debtorAccount;
        private String debtorBic;

        /** Creditor (payee) */
        private String creditorName;
        private String creditorAccount;
        private String creditorBic;
    }

    /**
     * Bank transaction code (ISO 20022 domain/family/subfamily).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BankTransactionCode {
        /** Domain: PMNT (Payments), CAMT (Cash Management), etc. */
        @Builder.Default
        private String domain = "PMNT";

        /** Family: RCDT (Received Credit Transfer), etc. */
        private String family;

        /** Sub-family: ESCT (SEPA Credit Transfer), etc. */
        private String subFamily;

        /** Proprietary code (internal) */
        private String proprietaryCode;
    }

    /**
     * Summary per child VA (for aggregated statements).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VaSummary {
        private UUID vaId;
        private String vaNumber;
        private String vaName;
        private String currencyCode;

        /** Opening balance for this VA */
        private BigDecimal openingBalance;

        /** Closing balance for this VA */
        private BigDecimal closingBalance;

        /** Transaction counts */
        private int creditCount;
        private BigDecimal creditSum;
        private int debitCount;
        private BigDecimal debitSum;

        /** Net movement */
        private BigDecimal netMovement;

        /** Entry count in statement */
        private int entryCount;

        /** Hierarchy level */
        private int hierarchyLevel;
    }

    /**
     * Processing information.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessingInfo {
        /** Processing time in milliseconds */
        private long processingTimeMs;

        /** Number of VAs processed (for aggregated) */
        private int vasProcessed;

        /** Total entries before pagination */
        private int totalEntriesBeforePagination;

        /** Hierarchy depth traversed */
        private int hierarchyDepth;

        /** Cache status */
        private boolean usedCache;

        /** Generation timestamp */
        private LocalDateTime generatedAt;
    }

    // ========================================================================
    // BALANCE CALCULATION DTOs
    // ========================================================================

    /**
     * Request for calculating aggregated balance.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BalanceCalculationRequest {
        private UUID vaId;
        private LocalDate asOfDate;

        /** Include child VAs in calculation */
        @Builder.Default
        private boolean includeChildren = true;

        /** Maximum hierarchy depth */
        private Integer maxDepth;
    }

    /**
     * Aggregated balance result.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AggregatedBalance {
        private UUID vaId;
        private String vaNumber;
        private LocalDate asOfDate;

        /** Current/booked balance */
        private BigDecimal bookedBalance;

        /** Available balance */
        private BigDecimal availableBalance;

        /** Held/blocked balance */
        private BigDecimal heldBalance;

        /** Credit limit (if applicable) */
        private BigDecimal creditLimit;

        /** Effective available (available + credit limit) */
        private BigDecimal effectiveAvailable;

        /** Currency code */
        private String currencyCode;

        /** Number of child VAs included */
        private int childVaCount;

        /** List of child VA IDs included */
        @Builder.Default
        private List<UUID> includedVaIds = new ArrayList<>();

        /** Balance breakdown by currency (for multi-currency aggregation) */
        @Builder.Default
        private List<CurrencyBalance> currencyBreakdown = new ArrayList<>();
    }

    /**
     * Balance per currency (for multi-currency aggregation).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurrencyBalance {
        private String currencyCode;
        private BigDecimal bookedBalance;
        private BigDecimal availableBalance;
        private int vaCount;
    }

    // ========================================================================
    // HIERARCHY DTOs
    // ========================================================================

    /**
     * VA hierarchy node for tree traversal.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VaHierarchyNode {
        private UUID vaId;
        private String vaNumber;
        private String vaName;
        private UUID parentVaId;
        private int hierarchyLevel;
        private String hierarchyPath;
        private String accountCategory;
        private String currencyCode;
        private BigDecimal currentBalance;
        private BigDecimal availableBalance;

        /** Children (populated during tree traversal) */
        @Builder.Default
        private List<VaHierarchyNode> children = new ArrayList<>();

        /** Is this a leaf node (no children) */
        private boolean leaf;
    }

    // ========================================================================
    // ENUMS
    // ========================================================================

    public enum StatementType {
        /** End-of-day statement (camt.053) */
        CAMT053,
        /** Intraday statement (camt.052) */
        CAMT052,
        /** Debit/Credit notification (camt.054) */
        CAMT054
    }

    public enum BalanceType {
        /** Opening Booked Balance */
        OPBD,
        /** Closing Booked Balance */
        CLBD,
        /** Closing Available Balance */
        CLAV,
        /** Opening Available Balance */
        OPAV,
        /** Interim Booked Balance */
        ITBD,
        /** Interim Available Balance */
        ITAV,
        /** Previously Closed Booked Balance */
        PRCD,
        /** Forward Available Balance */
        FWAV
    }

    public enum CreditDebitIndicator {
        CRDT,
        DBIT
    }

    public enum EntryStatus {
        /** Booked (final) */
        BOOK,
        /** Pending */
        PDNG,
        /** Information */
        INFO
    }
}
