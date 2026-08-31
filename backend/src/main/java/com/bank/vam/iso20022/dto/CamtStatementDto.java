package com.bank.vam.iso20022.dto;

import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * DTOs for ISO 20022 camt.053/054 statement generation.
 *
 * <h2>Message Types</h2>
 * <ul>
 *   <li><b>camt.053</b> - Bank-to-Customer Statement (end of day)</li>
 *   <li><b>camt.054</b> - Bank-to-Customer Debit/Credit Notification (real-time)</li>
 * </ul>
 *
 * <h2>Balance Types (per ISO 20022)</h2>
 * <ul>
 *   <li>OPBD - Opening Booked Balance</li>
 *   <li>CLBD - Closing Booked Balance</li>
 *   <li>PRCD - Previously Closed Booked Balance</li>
 *   <li>ITBD - Interim Booked Balance</li>
 *   <li>CLAV - Closing Available Balance</li>
 *   <li>FWAV - Forward Available Balance</li>
 *   <li>INFO - Information Balance</li>
 * </ul>
 */
public class CamtStatementDto {

    // ========================================================================
    // camt.053 - BANK-TO-CUSTOMER STATEMENT REQUEST/RESPONSE
    // ========================================================================

    /**
     * Request for generating a camt.053 statement for a single account.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Camt053Request {
        // Statement identification
        private String messageId;
        private String statementId;

        // Account
        private VirtualAccount account;

        // Statement period
        private LocalDate fromDate;
        private LocalDate toDate;

        // Transactions to include
        private List<Transaction> transactions;

        // Balances
        private BigDecimal openingBalance;
        private BigDecimal closingBalance;
        private BigDecimal closingAvailable;
        private BigDecimal forwardAvailable;

        // Sequence numbers
        @Builder.Default
        private int sequenceNumber = 1;
        private Long legalSequenceNumber;

        // Pagination (for large statements)
        private Integer pageNumber;
        @Builder.Default
        private boolean lastPage = true;

        // Optional recipient
        private String recipientBic;
        private String additionalInfo;
    }

    /**
     * Request for generating an aggregated camt.053 statement.
     * Used for ROOT/AGGREGATION accounts that consolidate child VAs.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Camt053AggregatedRequest {
        // Statement identification
        private String messageId;
        private String statementId;

        // Root/Aggregation account
        private VirtualAccount rootAccount;

        // Child accounts included in aggregation
        private List<VirtualAccount> childAccounts;

        // Transactions grouped by child account
        private Map<VirtualAccount, List<Transaction>> transactionsByAccount;

        // Statement period
        private LocalDate fromDate;
        private LocalDate toDate;

        // Aggregated balances (across all children)
        private BigDecimal aggregatedOpeningBalance;
        private BigDecimal aggregatedClosingBalance;

        // Sequence
        @Builder.Default
        private int sequenceNumber = 1;
    }

    /**
     * Response containing generated camt.053 XML and metadata.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Camt053Response {
        // Generated XML
        private String xml;

        // Statement metadata
        private String messageId;
        private String statementId;

        // Account info
        private String vaNumber;
        private String viban;
        private String currency;

        // Period
        private LocalDate fromDate;
        private LocalDate toDate;

        // Summary
        private BigDecimal openingBalance;
        private BigDecimal closingBalance;
        private int creditCount;
        private BigDecimal creditSum;
        private int debitCount;
        private BigDecimal debitSum;
        private int totalEntries;

        // Processing info
        private long generationTimeMs;
        private String errorMessage;
    }

    // ========================================================================
    // camt.054 - BANK-TO-CUSTOMER DEBIT/CREDIT NOTIFICATION
    // ========================================================================

    /**
     * Request for generating a camt.054 notification.
     * Used for real-time payment notifications.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Camt054Request {
        // Notification identification
        private String messageId;
        private String notificationId;

        // Account receiving notification
        private VirtualAccount account;

        // Transactions to notify (typically just the new ones)
        private List<Transaction> transactions;

        // Sequence
        @Builder.Default
        private int sequenceNumber = 1;
    }

    /**
     * Response containing generated camt.054 XML and metadata.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Camt054Response {
        // Generated XML
        private String xml;

        // Notification metadata
        private String messageId;
        private String notificationId;

        // Account info
        private String vaNumber;
        private String viban;

        // Entries
        private int entryCount;
        private BigDecimal totalCredits;
        private BigDecimal totalDebits;

        // Processing
        private long generationTimeMs;
        private String errorMessage;
    }

    // ========================================================================
    // STATEMENT ENTRY DTOs (for detailed entry information)
    // ========================================================================

    /**
     * Individual statement entry (maps to Ntry in camt.053/054).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatementEntry {
        // Entry reference
        private String entryReference;
        private String accountServicerReference;

        // Amount
        private BigDecimal amount;
        private String currency;
        private boolean isCredit;

        // Status (BOOK, PDNG, INFO)
        private String status;

        // Dates
        private LocalDate bookingDate;
        private LocalDate valueDate;

        // Bank transaction code
        private String domainCode;
        private String familyCode;
        private String subFamilyCode;
        private String proprietaryCode;

        // Reversal indicator
        private boolean isReversal;

        // Entry details
        private EntryDetails details;

        // Additional info
        private String additionalInfo;
    }

    /**
     * Entry details (maps to NtryDtls/TxDtls).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntryDetails {
        // References
        private String messageId;
        private String instructionId;
        private String endToEndId;
        private String transactionId;
        private String uetr;  // SWIFT gpi

        // Amount
        private BigDecimal instructedAmount;
        private String instructedCurrency;

        // Related parties
        private Party debtor;
        private Party creditor;
        private String debtorAccount;
        private String creditorAccount;

        // Related account (for aggregation)
        private String relatedAccountId;
        private String relatedAccountName;

        // Remittance info
        private String unstructuredRemittance;
        private StructuredRemittance structuredRemittance;

        // Additional
        private String additionalTransactionInfo;
    }

    /**
     * Party information (debtor/creditor).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Party {
        private String name;
        private String organizationId;
        private String privateId;
        private String bic;
        private Address postalAddress;
    }

    /**
     * Postal address.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Address {
        private String streetName;
        private String buildingNumber;
        private String postCode;
        private String townName;
        private String countrySubDivision;
        private String country;
    }

    /**
     * Structured remittance information.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StructuredRemittance {
        private String referenceType;
        private String reference;
        private String issuer;
        private String invoiceNumber;
        private BigDecimal invoiceAmount;
        private LocalDate invoiceDate;
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
    public static class Balance {
        // Balance type (OPBD, CLBD, PRCD, ITBD, CLAV, FWAV, INFO)
        private BalanceType type;

        // Amount and currency
        private BigDecimal amount;
        private String currency;

        // Credit/Debit indicator
        private boolean isCredit;

        // Date
        private LocalDate date;

        // For sub-balances
        private List<SubBalance> subBalances;
    }

    /**
     * Balance type enumeration per ISO 20022.
     */
    public enum BalanceType {
        OPBD("Opening Booked"),
        CLBD("Closing Booked"),
        PRCD("Previously Closed Booked"),
        ITBD("Interim Booked"),
        CLAV("Closing Available"),
        FWAV("Forward Available"),
        INFO("Information");

        private final String description;

        BalanceType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * Sub-balance (e.g., by currency for multi-currency aggregation).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubBalance {
        private BigDecimal amount;
        private String currency;
        private boolean isCredit;
        private String description;
    }

    // ========================================================================
    // TRANSACTION SUMMARY DTOs
    // ========================================================================

    /**
     * Transaction summary (maps to TxsSummry in camt.053).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransactionSummary {
        // Total credit entries
        private int creditCount;
        private BigDecimal creditSum;

        // Total debit entries
        private int debitCount;
        private BigDecimal debitSum;

        // Total entries
        private int totalCount;
        private BigDecimal totalSum;

        // Net position
        private BigDecimal netAmount;
        private boolean isNetCredit;
    }

    // ========================================================================
    // SUPPLEMENTARY DATA DTOs (VA Hierarchy)
    // ========================================================================

    /**
     * VA hierarchy information for supplementary data.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VaHierarchyInfo {
        // Root account
        private String rootVaNumber;
        private String hierarchyPath;
        private String accountCategory;
        private int hierarchyLevel;

        // Child accounts
        private List<ChildAccountInfo> childAccounts;
    }

    /**
     * Child account information.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChildAccountInfo {
        private String vaNumber;
        private String viban;
        private String vaName;
        private String currency;
        private BigDecimal balance;
        private String status;
    }

    // ========================================================================
    // VALIDATION REQUEST/RESPONSE
    // ========================================================================

    /**
     * XSD validation request.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationRequest {
        private String xml;
        private MessageType messageType;
    }

    /**
     * Message type for validation.
     */
    public enum MessageType {
        CAMT053("camt.053.001.08"),
        CAMT054("camt.054.001.08");

        private final String schemaVersion;

        MessageType(String schemaVersion) {
            this.schemaVersion = schemaVersion;
        }

        public String getSchemaVersion() {
            return schemaVersion;
        }
    }

    /**
     * Validation response.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationResponse {
        private boolean valid;
        private List<ValidationError> errors;
        private List<String> warnings;
    }

    /**
     * Validation error details.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationError {
        private String errorType;
        private String elementPath;
        private String message;
        private int lineNumber;
        private int columnNumber;
    }
}
