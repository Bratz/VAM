package com.bank.vam.iso20022.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * ISO 20022 camt.053 (Bank to Customer Statement) DTOs.
 *
 * <p>camt.053 is used for end-of-day account statements that provide complete
 * information about all entries booked to an account during a specified period.</p>
 *
 * <h2>Message Structure:</h2>
 * <pre>
 * Document (camt.053.001.08)
 *   └── BkToCstmrStmt (Bank to Customer Statement)
 *         ├── GrpHdr (Group Header)
 *         │     ├── MsgId (Message Identification)
 *         │     ├── CreDtTm (Creation Date Time)
 *         │     └── MsgPgntn (Message Pagination - optional)
 *         └── Stmt[] (Statement - 1..n)
 *               ├── Id (Statement Identification)
 *               ├── StmtPgntn (Statement Pagination)
 *               ├── ElctrncSeqNb (Electronic Sequence Number)
 *               ├── LglSeqNb (Legal Sequence Number)
 *               ├── CreDtTm (Creation Date Time)
 *               ├── FrToDt (From To Date)
 *               ├── Acct (Account)
 *               │     ├── Id (IBAN/BBAN/Other)
 *               │     ├── Tp (Account Type)
 *               │     ├── Ccy (Currency)
 *               │     ├── Nm (Account Name)
 *               │     ├── Ownr (Account Owner)
 *               │     └── Svcr (Account Servicer)
 *               ├── Bal[] (Balance - 1..n)
 *               │     ├── Tp (Balance Type: OPBD, CLBD, ITBD, etc.)
 *               │     ├── Amt (Amount with Currency)
 *               │     ├── CdtDbtInd (Credit/Debit Indicator)
 *               │     └── Dt (Date)
 *               ├── TxsSummry (Transaction Summary)
 *               │     ├── TtlNtries (Total Entries)
 *               │     ├── TtlCdtNtries (Total Credit Entries)
 *               │     └── TtlDbtNtries (Total Debit Entries)
 *               └── Ntry[] (Entry - 0..n)
 *                     ├── NtryRef (Entry Reference)
 *                     ├── Amt (Amount)
 *                     ├── CdtDbtInd (Credit/Debit)
 *                     ├── RvslInd (Reversal Indicator)
 *                     ├── Sts (Status: BOOK, PDNG, INFO)
 *                     ├── BookgDt (Booking Date)
 *                     ├── ValDt (Value Date)
 *                     ├── AcctSvcrRef (Account Servicer Reference)
 *                     ├── BkTxCd (Bank Transaction Code)
 *                     └── NtryDtls[] (Entry Details)
 *                           └── TxDtls[] (Transaction Details)
 *                                 ├── Refs (References)
 *                                 ├── Amt (Amount)
 *                                 ├── RltdPties (Related Parties)
 *                                 ├── RltdAgts (Related Agents)
 *                                 └── RmtInf (Remittance Information)
 * </pre>
 *
 * <h2>VA Hierarchy Considerations:</h2>
 * <ul>
 *   <li>Individual VA Statement: Single VA transaction history</li>
 *   <li>Aggregated VA Statement: Parent VA with all child VA transactions</li>
 *   <li>Legal Entity Statement: All VAs owned by a legal entity</li>
 *   <li>Multi-Currency Support: Statements can include currency mirrors</li>
 * </ul>
 *
 * @see <a href="https://www.iso20022.org/catalogue-messages/camt-cash-management">ISO 20022 camt Messages</a>
 * @version camt.053.001.08
 */
public class Camt053Dto {

    // ========================================================================
    // ENUMS - ISO 20022 Code Sets
    // ========================================================================

    /**
     * Balance Type Codes per ISO 20022.
     * Reference: ExternalBalanceType1Code
     */
    public enum BalanceType {
        /** Opening Booked Balance - Balance at start of statement period */
        OPBD("OPBD", "Opening Booked"),

        /** Closing Booked Balance - Balance at end of statement period */
        CLBD("CLBD", "Closing Booked"),

        /** Interim Booked Balance - Intraday balance snapshot */
        ITBD("ITBD", "Interim Booked"),

        /** Opening Available Balance */
        OPAV("OPAV", "Opening Available"),

        /** Closing Available Balance */
        CLAV("CLAV", "Closing Available"),

        /** Interim Available Balance */
        ITAV("ITAV", "Interim Available"),

        /** Forward Available Balance */
        FWAV("FWAV", "Forward Available"),

        /** Previously Closed Booked */
        PRCD("PRCD", "Previously Closed Booked"),

        /** Expected Balance - Pending transactions included */
        XPCD("XPCD", "Expected"),

        /** Information Balance - For display only */
        INFO("INFO", "Information");

        private final String code;
        private final String description;

        BalanceType(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public String getCode() { return code; }
        public String getDescription() { return description; }
    }

    /**
     * Credit/Debit Indicator.
     */
    public enum CreditDebitIndicator {
        /** Credit - Money coming into the account */
        CRDT("CRDT", "Credit"),

        /** Debit - Money going out of the account */
        DBIT("DBIT", "Debit");

        private final String code;
        private final String description;

        CreditDebitIndicator(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public String getCode() { return code; }
        public String getDescription() { return description; }
    }

    /**
     * Entry Status Codes.
     */
    public enum EntryStatus {
        /** Booked - Entry is final and posted */
        BOOK("BOOK", "Booked"),

        /** Pending - Entry is expected but not yet booked */
        PDNG("PDNG", "Pending"),

        /** Information - Entry for information only */
        INFO("INFO", "Information"),

        /** Future - Entry scheduled for future date */
        FUTR("FUTR", "Future");

        private final String code;
        private final String description;

        EntryStatus(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public String getCode() { return code; }
        public String getDescription() { return description; }
    }

    /**
     * Statement Type - VAM-specific extension.
     */
    public enum StatementType {
        /** Individual VA statement */
        INDIVIDUAL("INDIVIDUAL", "Single Virtual Account"),

        /** Aggregated statement including child VAs */
        AGGREGATED("AGGREGATED", "Aggregated with Child VAs"),

        /** Legal entity consolidated statement */
        ENTITY("ENTITY", "Legal Entity Consolidated"),

        /** Currency-specific statement for multi-currency VA */
        CURRENCY("CURRENCY", "Currency-Specific"),

        /** Full hierarchy statement (ROOT to leaf) */
        HIERARCHY("HIERARCHY", "Full Hierarchy");

        private final String code;
        private final String description;

        StatementType(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public String getCode() { return code; }
        public String getDescription() { return description; }
    }

    // ========================================================================
    // REQUEST DTOs
    // ========================================================================

    /**
     * Request to generate a camt.053 Bank to Customer Statement.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatementRequest {

        /** Virtual Account ID - Required */
        @NotNull(message = "Virtual Account ID is required")
        private UUID virtualAccountId;

        /** Alternative: VA Number for lookup */
        private String vaNumber;

        /** Alternative: VIBAN for lookup */
        private String viban;

        /** Statement start date - Required */
        @NotNull(message = "From date is required")
        private LocalDate fromDate;

        /** Statement end date - Required */
        @NotNull(message = "To date is required")
        private LocalDate toDate;

        /** Statement type - defaults to INDIVIDUAL */
        @Builder.Default
        private StatementType statementType = StatementType.INDIVIDUAL;

        /**
         * Include child VA transactions (for AGGREGATED type).
         * When true, transactions from all child VAs are included.
         */
        @Builder.Default
        private Boolean includeChildVas = false;

        /**
         * Maximum hierarchy depth for child VA inclusion.
         * 0 = direct children only, -1 = all descendants
         */
        @Builder.Default
        private Integer maxDepth = -1;

        /**
         * Include pending transactions in statement.
         */
        @Builder.Default
        private Boolean includePending = false;

        /**
         * Include internal system transactions (fees, sweeps, etc.).
         */
        @Builder.Default
        private Boolean includeInternalTransactions = true;

        /**
         * Currency filter - if provided, only entries in this currency.
         */
        private String currencyCode;

        /**
         * Legal Entity ID filter - for ENTITY statement type.
         */
        private UUID legalEntityId;

        /**
         * Output format preference.
         */
        @Builder.Default
        private String format = "XML";  // XML, JSON, PDF

        /**
         * External client reference for tracking.
         */
        @Size(max = 35, message = "External reference must not exceed 35 characters")
        private String externalReference;

        /**
         * Request pagination for large statements.
         */
        @Builder.Default
        private Boolean paginated = false;

        /**
         * Page number (0-based) when paginated.
         */
        @Builder.Default
        private Integer pageNumber = 0;

        /**
         * Page size - max entries per page.
         */
        @Builder.Default
        private Integer pageSize = 100;
    }

    // ========================================================================
    // RESPONSE DTOs - Document Level
    // ========================================================================

    /**
     * Root document for camt.053 statement response.
     * Maps to: Document/BkToCstmrStmt
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Camt053Document {

        /** Message format version */
        @Builder.Default
        private String messageDefinitionIdentifier = "camt.053.001.08";

        /** XML namespace */
        @Builder.Default
        private String namespace = "urn:iso:std:iso:20022:tech:xsd:camt.053.001.08";

        /** Group Header - Required */
        @NotNull
        private GroupHeader groupHeader;

        /** Statement(s) - One or more statements */
        @NotNull
        private List<Statement> statements;

        /** Raw XML content (populated when format=XML) */
        private String xmlContent;

        /** Processing status */
        private ProcessingStatus processingStatus;
    }

    /**
     * Processing status for statement generation.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessingStatus {
        private boolean success;
        private String statusCode;
        private String statusMessage;
        private long processingTimeMs;
        private LocalDateTime generatedAt;
        private String generatedBy;
    }

    // ========================================================================
    // GROUP HEADER (GrpHdr)
    // ========================================================================

    /**
     * Group Header information.
     * Maps to: BkToCstmrStmt/GrpHdr
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupHeader {

        /**
         * Message Identification - Unique identifier for this message.
         * Maps to: GrpHdr/MsgId
         * Format: Up to 35 characters
         */
        @NotBlank(message = "Message ID is required")
        @Size(max = 35, message = "Message ID must not exceed 35 characters")
        private String messageId;

        /**
         * Creation Date Time - When this message was created.
         * Maps to: GrpHdr/CreDtTm
         */
        @NotNull
        private LocalDateTime creationDateTime;

        /**
         * Message Recipient - Optional identifier for recipient.
         * Maps to: GrpHdr/MsgRcpt
         */
        private Party messageRecipient;

        /**
         * Message Pagination - For multi-page statements.
         * Maps to: GrpHdr/MsgPgntn
         */
        private Pagination messagePagination;

        /**
         * Original Business Query - Reference to original request.
         * Maps to: GrpHdr/OrgnlBizQry
         */
        private OriginalBusinessQuery originalBusinessQuery;

        /**
         * Additional Information - Free-form additional info.
         * Maps to: GrpHdr/AddtlInf
         */
        private String additionalInformation;
    }

    /**
     * Pagination information for multi-page statements.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Pagination {
        /** Current page number */
        private Integer pageNumber;

        /** Is this the last page? */
        private Boolean lastPageIndicator;

        /** Total number of pages */
        private Integer totalPages;

        /** Total number of entries across all pages */
        private Integer totalEntries;
    }

    /**
     * Reference to original business query.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OriginalBusinessQuery {
        /** Original message ID that requested this statement */
        private String messageId;

        /** Original message name identifier */
        private String messageNameId;

        /** Original creation date time */
        private LocalDateTime creationDateTime;
    }

    // ========================================================================
    // STATEMENT (Stmt)
    // ========================================================================

    /**
     * Account Statement.
     * Maps to: BkToCstmrStmt/Stmt
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Statement {

        /**
         * Statement Identification - Unique ID for this statement.
         * Maps to: Stmt/Id
         */
        @NotBlank(message = "Statement ID is required")
        @Size(max = 35, message = "Statement ID must not exceed 35 characters")
        private String statementId;

        /**
         * Statement Pagination - For multi-page statements.
         * Maps to: Stmt/StmtPgntn
         */
        private Pagination statementPagination;

        /**
         * Electronic Sequence Number - Sequential number.
         * Maps to: Stmt/ElctrncSeqNb
         */
        private Long electronicSequenceNumber;

        /**
         * Legal Sequence Number - Regulatory sequence number.
         * Maps to: Stmt/LglSeqNb
         */
        private Long legalSequenceNumber;

        /**
         * Creation Date Time - When statement was created.
         * Maps to: Stmt/CreDtTm
         */
        @NotNull
        private LocalDateTime creationDateTime;

        /**
         * From To Date - Statement period.
         * Maps to: Stmt/FrToDt
         */
        private DatePeriod fromToDate;

        /**
         * Reporting Source - Source of reporting data.
         * Maps to: Stmt/RptgSrc
         */
        private String reportingSource;

        /**
         * Account - The account this statement is for.
         * Maps to: Stmt/Acct
         */
        @NotNull
        private AccountInfo account;

        /**
         * Related Account - For statements that reference another account.
         * Maps to: Stmt/RltdAcct
         */
        private AccountInfo relatedAccount;

        /**
         * Interest - Interest information for the period.
         * Maps to: Stmt/Intrst
         */
        private List<InterestInfo> interest;

        /**
         * Balance - Account balances (OPBD, CLBD, etc.).
         * Maps to: Stmt/Bal
         */
        @NotNull
        private List<Balance> balances;

        /**
         * Transactions Summary - Aggregated transaction totals.
         * Maps to: Stmt/TxsSummry
         */
        private TransactionsSummary transactionsSummary;

        /**
         * Entry - Individual transaction entries.
         * Maps to: Stmt/Ntry
         */
        private List<Entry> entries;

        /**
         * Additional Statement Information.
         * Maps to: Stmt/AddtlStmtInf
         */
        private String additionalStatementInformation;

        // ====================================================================
        // VAM-Specific Extensions (not in ISO 20022 schema)
        // ====================================================================

        /** Statement type for VAM */
        private StatementType statementType;

        /** Program information */
        private UUID programId;
        private String programCode;
        private String programName;

        /** Corporate information */
        private UUID corporateId;
        private String corporateName;

        /** Legal entity information */
        private UUID legalEntityId;
        private String legalEntityCode;

        /** Hierarchy path if applicable */
        private String hierarchyPath;

        /** Number of child VAs included (for aggregated) */
        private Integer childVaCount;

        /** Child VA IDs included */
        private List<UUID> includedChildVaIds;
    }

    /**
     * Date period for statement.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DatePeriod {
        @NotNull
        private LocalDate fromDate;

        @NotNull
        private LocalDate toDate;
    }

    // ========================================================================
    // ACCOUNT INFORMATION (Acct)
    // ========================================================================

    /**
     * Account Information.
     * Maps to: Stmt/Acct
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AccountInfo {

        /**
         * Account Identification.
         * Maps to: Acct/Id
         */
        @NotNull
        private AccountIdentification identification;

        /**
         * Account Type.
         * Maps to: Acct/Tp
         */
        private AccountType type;

        /**
         * Account Currency.
         * Maps to: Acct/Ccy
         */
        @Size(min = 3, max = 3)
        private String currency;

        /**
         * Account Name.
         * Maps to: Acct/Nm
         */
        @Size(max = 70)
        private String name;

        /**
         * Account Owner.
         * Maps to: Acct/Ownr
         */
        private Party owner;

        /**
         * Account Servicer (Bank).
         * Maps to: Acct/Svcr
         */
        private BranchAndFinancialInstitution servicer;

        // VAM Extensions
        private UUID virtualAccountId;
        private String vaNumber;
        private String viban;
        private String accountCategory;  // ROOT, AGGREGATION, TRANSACTION, etc.
    }

    /**
     * Account Identification.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AccountIdentification {
        /** IBAN - International Bank Account Number */
        private String iban;

        /** BBAN - Basic Bank Account Number */
        private String bban;

        /** Other - Proprietary identification */
        private GenericIdentification other;
    }

    /**
     * Generic Identification for proprietary IDs.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GenericIdentification {
        /** Identification value */
        private String id;

        /** Scheme name (proprietary or code) */
        private String schemeName;

        /** Issuer of the identification */
        private String issuer;
    }

    /**
     * Account Type.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AccountType {
        /** Code from external code list */
        private String code;

        /** Proprietary type */
        private String proprietary;
    }

    // ========================================================================
    // BALANCE (Bal)
    // ========================================================================

    /**
     * Balance Information.
     * Maps to: Stmt/Bal
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Balance {

        /**
         * Balance Type (OPBD, CLBD, ITBD, etc.).
         * Maps to: Bal/Tp
         */
        @NotNull
        private BalanceType type;

        /**
         * Credit Line Included - Whether credit line is included in balance.
         * Maps to: Bal/CdtLine
         */
        private CreditLine creditLine;

        /**
         * Amount - Balance amount with currency.
         * Maps to: Bal/Amt
         */
        @NotNull
        private AmountWithCurrency amount;

        /**
         * Credit/Debit Indicator.
         * Maps to: Bal/CdtDbtInd
         */
        @NotNull
        private CreditDebitIndicator creditDebitIndicator;

        /**
         * Date - Balance date.
         * Maps to: Bal/Dt
         */
        @NotNull
        private DateInfo date;

        /**
         * Availability - Availability of funds.
         * Maps to: Bal/Avlbty
         */
        private List<CashAvailability> availability;
    }

    /**
     * Amount with Currency.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AmountWithCurrency {
        /** Amount value */
        @NotNull
        private BigDecimal value;

        /** ISO 4217 Currency Code */
        @NotBlank
        @Size(min = 3, max = 3)
        private String currency;
    }

    /**
     * Date Information - Either specific date or date/time.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DateInfo {
        /** Specific date */
        private LocalDate date;

        /** Date and time */
        private LocalDateTime dateTime;
    }

    /**
     * Credit Line information.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreditLine {
        /** Is credit line included in balance? */
        private Boolean included;

        /** Credit line amount */
        private AmountWithCurrency amount;
    }

    /**
     * Cash Availability details.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CashAvailability {
        /** Availability date */
        private DateInfo date;

        /** Number of days until available */
        private Integer numberOfDays;

        /** Available amount */
        private AmountWithCurrency amount;

        /** Credit/Debit indicator for availability */
        private CreditDebitIndicator creditDebitIndicator;
    }

    // ========================================================================
    // TRANSACTIONS SUMMARY (TxsSummry)
    // ========================================================================

    /**
     * Transactions Summary.
     * Maps to: Stmt/TxsSummry
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransactionsSummary {

        /**
         * Total Number of Entries.
         * Maps to: TxsSummry/TtlNtries
         */
        private TotalEntries totalEntries;

        /**
         * Total Credit Entries.
         * Maps to: TxsSummry/TtlCdtNtries
         */
        private NumberAndSumOfTransactions totalCreditEntries;

        /**
         * Total Debit Entries.
         * Maps to: TxsSummry/TtlDbtNtries
         */
        private NumberAndSumOfTransactions totalDebitEntries;

        /**
         * Total Entries Per Bank Transaction Code.
         * Maps to: TxsSummry/TtlNtriesPerBkTxCd
         */
        private List<TotalEntriesPerBankTransactionCode> totalEntriesPerBankTransactionCode;
    }

    /**
     * Total Entries count and amount.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TotalEntries {
        /** Number of entries */
        private Integer numberOfEntries;

        /** Sum of amounts */
        private BigDecimal sum;

        /** Total net entry amount */
        private BigDecimal totalNetEntryAmount;

        /** Credit/Debit indicator for net amount */
        private CreditDebitIndicator creditDebitIndicator;
    }

    /**
     * Number and Sum of Transactions.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NumberAndSumOfTransactions {
        /** Number of entries */
        private Integer numberOfEntries;

        /** Sum of amounts */
        private BigDecimal sum;
    }

    /**
     * Total Entries Per Bank Transaction Code.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TotalEntriesPerBankTransactionCode {
        /** Number of entries for this code */
        private Integer numberOfEntries;

        /** Sum of amounts for this code */
        private BigDecimal sum;

        /** Total net entry amount */
        private BigDecimal totalNetEntryAmount;

        /** Credit/Debit indicator */
        private CreditDebitIndicator creditDebitIndicator;

        /** Bank transaction code */
        private BankTransactionCode bankTransactionCode;
    }

    // ========================================================================
    // ENTRY (Ntry)
    // ========================================================================

    /**
     * Statement Entry - Individual transaction line.
     * Maps to: Stmt/Ntry
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Entry {

        /**
         * Entry Reference - Unique reference for this entry.
         * Maps to: Ntry/NtryRef
         */
        @Size(max = 35)
        private String entryReference;

        /**
         * Amount.
         * Maps to: Ntry/Amt
         */
        @NotNull
        private AmountWithCurrency amount;

        /**
         * Credit/Debit Indicator.
         * Maps to: Ntry/CdtDbtInd
         */
        @NotNull
        private CreditDebitIndicator creditDebitIndicator;

        /**
         * Reversal Indicator - Is this a reversal?
         * Maps to: Ntry/RvslInd
         */
        private Boolean reversalIndicator;

        /**
         * Status - Entry status (BOOK, PDNG, INFO).
         * Maps to: Ntry/Sts
         */
        @NotNull
        private EntryStatus status;

        /**
         * Booking Date - When entry was booked.
         * Maps to: Ntry/BookgDt
         */
        private DateInfo bookingDate;

        /**
         * Value Date - When funds become available.
         * Maps to: Ntry/ValDt
         */
        private DateInfo valueDate;

        /**
         * Account Servicer Reference - Bank's reference.
         * Maps to: Ntry/AcctSvcrRef
         */
        @Size(max = 35)
        private String accountServicerReference;

        /**
         * Availability - Funds availability.
         * Maps to: Ntry/Avlbty
         */
        private List<CashAvailability> availability;

        /**
         * Bank Transaction Code.
         * Maps to: Ntry/BkTxCd
         */
        private BankTransactionCode bankTransactionCode;

        /**
         * Commission Waiver Indicator.
         * Maps to: Ntry/ComssnWvrInd
         */
        private Boolean commissionWaiverIndicator;

        /**
         * Additional Information.
         * Maps to: Ntry/AddtlInfInd
         */
        private String additionalInformationIndicator;

        /**
         * Amount Details.
         * Maps to: Ntry/AmtDtls
         */
        private AmountDetails amountDetails;

        /**
         * Charges - Charges applied.
         * Maps to: Ntry/Chrgs
         */
        private Charges charges;

        /**
         * Technical Input Channel - How entry was created.
         * Maps to: Ntry/TechInptChanl
         */
        private String technicalInputChannel;

        /**
         * Interest - Interest information.
         * Maps to: Ntry/Intrst
         */
        private List<InterestInfo> interest;

        /**
         * Card Transaction - Card-specific details.
         * Maps to: Ntry/CardTx
         */
        private CardTransaction cardTransaction;

        /**
         * Entry Details - Detailed transaction information.
         * Maps to: Ntry/NtryDtls
         */
        private List<EntryDetails> entryDetails;

        /**
         * Additional Entry Information.
         * Maps to: Ntry/AddtlNtryInf
         */
        private String additionalEntryInformation;

        // ====================================================================
        // VAM-Specific Extensions
        // ====================================================================

        /** VAM Transaction ID */
        private UUID transactionId;

        /** Source VA ID (for aggregated statements) */
        private UUID sourceVaId;

        /** Source VA Number */
        private String sourceVaNumber;

        /** Owning entity code */
        private String owningEntityCode;

        /** Movement type from VAM */
        private String movementType;

        /** Transaction category */
        private String transactionCategory;

        /** Is POBO transaction */
        private Boolean isPobo;

        /** Behalf of entity (for POBO) */
        private String behalfOfEntity;

        /** Balance before this entry */
        private BigDecimal balanceBefore;

        /** Balance after this entry */
        private BigDecimal balanceAfter;
    }

    // ========================================================================
    // ENTRY DETAILS (NtryDtls)
    // ========================================================================

    /**
     * Entry Details - Batch or transaction level details.
     * Maps to: Ntry/NtryDtls
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntryDetails {

        /**
         * Batch Information.
         * Maps to: NtryDtls/Btch
         */
        private BatchInfo batch;

        /**
         * Transaction Details.
         * Maps to: NtryDtls/TxDtls
         */
        private List<TransactionDetails> transactionDetails;
    }

    /**
     * Batch Information.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchInfo {
        /** Message identification */
        private String messageId;

        /** Payment information identification */
        private String paymentInformationId;

        /** Number of transactions in batch */
        private Integer numberOfTransactions;

        /** Total amount of batch */
        private AmountWithCurrency totalAmount;

        /** Credit/Debit indicator */
        private CreditDebitIndicator creditDebitIndicator;
    }

    /**
     * Transaction Details.
     * Maps to: NtryDtls/TxDtls
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransactionDetails {

        /**
         * References - Various transaction references.
         * Maps to: TxDtls/Refs
         */
        private TransactionReferences references;

        /**
         * Amount - Transaction amount.
         * Maps to: TxDtls/Amt
         */
        private AmountWithCurrency amount;

        /**
         * Credit/Debit Indicator.
         * Maps to: TxDtls/CdtDbtInd
         */
        private CreditDebitIndicator creditDebitIndicator;

        /**
         * Amount Details - Instructed vs actual amounts.
         * Maps to: TxDtls/AmtDtls
         */
        private AmountDetails amountDetails;

        /**
         * Availability - Funds availability.
         * Maps to: TxDtls/Avlbty
         */
        private List<CashAvailability> availability;

        /**
         * Bank Transaction Code.
         * Maps to: TxDtls/BkTxCd
         */
        private BankTransactionCode bankTransactionCode;

        /**
         * Charges - Transaction charges.
         * Maps to: TxDtls/Chrgs
         */
        private Charges charges;

        /**
         * Interest - Interest applied.
         * Maps to: TxDtls/Intrst
         */
        private List<InterestInfo> interest;

        /**
         * Related Parties - Debtor, Creditor, etc.
         * Maps to: TxDtls/RltdPties
         */
        private RelatedParties relatedParties;

        /**
         * Related Agents - Banks involved.
         * Maps to: TxDtls/RltdAgts
         */
        private RelatedAgents relatedAgents;

        /**
         * Local Instrument.
         * Maps to: TxDtls/LclInstrm
         */
        private LocalInstrument localInstrument;

        /**
         * Purpose - Payment purpose.
         * Maps to: TxDtls/Purp
         */
        private Purpose purpose;

        /**
         * Related Remittance Information.
         * Maps to: TxDtls/RltdRmtInf
         */
        private List<RemittanceLocation> relatedRemittanceInformation;

        /**
         * Remittance Information.
         * Maps to: TxDtls/RmtInf
         */
        private RemittanceInformation remittanceInformation;

        /**
         * Related Dates.
         * Maps to: TxDtls/RltdDts
         */
        private RelatedDates relatedDates;

        /**
         * Related Price.
         * Maps to: TxDtls/RltdPric
         */
        private RelatedPrice relatedPrice;

        /**
         * Additional Transaction Information.
         * Maps to: TxDtls/AddtlTxInf
         */
        private String additionalTransactionInformation;
    }

    // ========================================================================
    // TRANSACTION REFERENCES (Refs)
    // ========================================================================

    /**
     * Transaction References.
     * Maps to: TxDtls/Refs
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransactionReferences {

        /** Message Identification */
        @Size(max = 35)
        private String messageId;

        /** Account Servicer Reference */
        @Size(max = 35)
        private String accountServicerReference;

        /** Payment Information Identification */
        @Size(max = 35)
        private String paymentInformationId;

        /** Instruction Identification */
        @Size(max = 35)
        private String instructionId;

        /** End-to-End Identification */
        @Size(max = 35)
        private String endToEndId;

        /** Transaction Identification */
        @Size(max = 35)
        private String transactionId;

        /** Mandate Identification */
        @Size(max = 35)
        private String mandateId;

        /** Cheque Number */
        @Size(max = 35)
        private String chequeNumber;

        /** Clearing System Reference */
        @Size(max = 35)
        private String clearingSystemReference;

        /** Account Owner Transaction Identification */
        @Size(max = 35)
        private String accountOwnerTransactionId;

        /** Account Servicer Transaction Identification */
        @Size(max = 35)
        private String accountServicerTransactionId;

        /** Market Infrastructure Transaction Identification */
        @Size(max = 35)
        private String marketInfrastructureTransactionId;

        /** Processing Identification */
        @Size(max = 35)
        private String processingId;

        /** Proprietary References */
        private List<ProprietaryReference> proprietaryReferences;
    }

    /**
     * Proprietary Reference.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProprietaryReference {
        private String type;
        private String reference;
    }

    // ========================================================================
    // RELATED PARTIES (RltdPties)
    // ========================================================================

    /**
     * Related Parties in a transaction.
     * Maps to: TxDtls/RltdPties
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RelatedParties {

        /** Debtor (payer) */
        private PartyWithAccount debtor;

        /** Debtor Account */
        private AccountInfo debtorAccount;

        /** Ultimate Debtor (original payer) */
        private Party ultimateDebtor;

        /** Creditor (payee) */
        private PartyWithAccount creditor;

        /** Creditor Account */
        private AccountInfo creditorAccount;

        /** Ultimate Creditor (final beneficiary) */
        private Party ultimateCreditor;

        /** Trading Party */
        private Party tradingParty;

        /** Proprietary Party Information */
        private List<ProprietaryParty> proprietaryParties;
    }

    /**
     * Party with Account information.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartyWithAccount {
        private Party party;
        private AccountInfo account;
    }

    /**
     * Party Information.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Party {
        /** Name */
        @Size(max = 140)
        private String name;

        /** Postal Address */
        private PostalAddress postalAddress;

        /** Identification */
        private PartyIdentification identification;

        /** Country of Residence */
        @Size(min = 2, max = 2)
        private String countryOfResidence;

        /** Contact Details */
        private ContactDetails contactDetails;
    }

    /**
     * Postal Address.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PostalAddress {
        private String addressType;
        private String department;
        private String subDepartment;
        private String streetName;
        private String buildingNumber;
        private String buildingName;
        private String floor;
        private String postBox;
        private String room;
        private String postCode;
        private String townName;
        private String townLocationName;
        private String districtName;
        private String countrySubDivision;

        @Size(min = 2, max = 2)
        private String country;

        private List<String> addressLines;
    }

    /**
     * Party Identification.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartyIdentification {
        /** Organisation Identification */
        private OrganisationIdentification organisationId;

        /** Private Identification */
        private PrivateIdentification privateId;
    }

    /**
     * Organisation Identification.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrganisationIdentification {
        /** Any BIC */
        private String anyBic;

        /** LEI - Legal Entity Identifier */
        private String lei;

        /** Other identifications */
        private List<GenericIdentification> other;
    }

    /**
     * Private (Individual) Identification.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PrivateIdentification {
        /** Date and Place of Birth */
        private DateAndPlaceOfBirth dateAndPlaceOfBirth;

        /** Other identifications */
        private List<GenericIdentification> other;
    }

    /**
     * Date and Place of Birth.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DateAndPlaceOfBirth {
        private LocalDate birthDate;
        private String provinceOfBirth;
        private String cityOfBirth;
        private String countryOfBirth;
    }

    /**
     * Contact Details.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContactDetails {
        private String namePrefix;
        private String name;
        private String phoneNumber;
        private String mobileNumber;
        private String faxNumber;
        private String emailAddress;
        private String emailPurpose;
        private String jobTitle;
        private String responsibility;
        private String department;
        private String other;
    }

    /**
     * Proprietary Party.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProprietaryParty {
        private String type;
        private Party party;
    }

    // ========================================================================
    // RELATED AGENTS (RltdAgts)
    // ========================================================================

    /**
     * Related Agents (Banks).
     * Maps to: TxDtls/RltdAgts
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RelatedAgents {
        /** Debtor Agent (Debtor's Bank) */
        private BranchAndFinancialInstitution debtorAgent;

        /** Creditor Agent (Creditor's Bank) */
        private BranchAndFinancialInstitution creditorAgent;

        /** Intermediary Agent 1 */
        private BranchAndFinancialInstitution intermediaryAgent1;

        /** Intermediary Agent 2 */
        private BranchAndFinancialInstitution intermediaryAgent2;

        /** Intermediary Agent 3 */
        private BranchAndFinancialInstitution intermediaryAgent3;

        /** Receiving Agent */
        private BranchAndFinancialInstitution receivingAgent;

        /** Delivering Agent */
        private BranchAndFinancialInstitution deliveringAgent;

        /** Issuing Agent */
        private BranchAndFinancialInstitution issuingAgent;

        /** Settlement Place */
        private BranchAndFinancialInstitution settlementPlace;

        /** Proprietary Agents */
        private List<ProprietaryAgent> proprietaryAgents;
    }

    /**
     * Branch and Financial Institution Identification.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BranchAndFinancialInstitution {
        /** Financial Institution Identification */
        private FinancialInstitutionIdentification financialInstitutionId;

        /** Branch Identification */
        private BranchData branchId;
    }

    /**
     * Financial Institution Identification.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FinancialInstitutionIdentification {
        /** BIC/SWIFT code */
        @Size(min = 8, max = 11)
        private String bicfi;

        /** Clearing System Member Identification */
        private ClearingSystemMemberIdentification clearingSystemMemberId;

        /** LEI */
        @Size(max = 20)
        private String lei;

        /** Name */
        @Size(max = 140)
        private String name;

        /** Postal Address */
        private PostalAddress postalAddress;

        /** Other Identification */
        private GenericIdentification other;
    }

    /**
     * Clearing System Member Identification.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClearingSystemMemberIdentification {
        private String clearingSystemId;
        private String memberId;
    }

    /**
     * Branch Data.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BranchData {
        private String id;
        private String lei;
        private String name;
        private PostalAddress postalAddress;
    }

    /**
     * Proprietary Agent.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProprietaryAgent {
        private String type;
        private BranchAndFinancialInstitution agent;
    }

    // ========================================================================
    // SUPPORTING TYPES
    // ========================================================================

    /**
     * Bank Transaction Code.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BankTransactionCode {
        /** Domain Code */
        private String domainCode;

        /** Family Code */
        private String familyCode;

        /** Sub-Family Code */
        private String subFamilyCode;

        /** Proprietary Code */
        private String proprietaryCode;

        /** Issuer of Proprietary Code */
        private String issuer;
    }

    /**
     * Amount Details.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AmountDetails {
        /** Instructed Amount */
        private AmountWithCurrency instructedAmount;

        /** Transaction Amount */
        private AmountWithCurrency transactionAmount;

        /** Counter Value Amount */
        private AmountWithCurrency counterValueAmount;

        /** Announced Posting Amount */
        private AmountWithCurrency announcedPostingAmount;

        /** Proprietary Amount */
        private List<ProprietaryAmount> proprietaryAmounts;
    }

    /**
     * Proprietary Amount.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProprietaryAmount {
        private String type;
        private AmountWithCurrency amount;
    }

    /**
     * Charges Information.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Charges {
        /** Total Charges and Tax Amount */
        private AmountWithCurrency totalChargesAndTaxAmount;

        /** Individual Charge Records */
        private List<ChargeRecord> records;
    }

    /**
     * Individual Charge Record.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChargeRecord {
        /** Charge Amount */
        private AmountWithCurrency amount;

        /** Credit/Debit Indicator */
        private CreditDebitIndicator creditDebitIndicator;

        /** Charge Included Indicator */
        private Boolean chargeIncludedIndicator;

        /** Charge Type */
        private String type;

        /** Rate */
        private BigDecimal rate;

        /** Bearer */
        private String bearer;

        /** Agent */
        private BranchAndFinancialInstitution agent;

        /** Tax */
        private TaxCharges tax;
    }

    /**
     * Tax on Charges.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaxCharges {
        private String identification;
        private BigDecimal rate;
        private AmountWithCurrency amount;
    }

    /**
     * Interest Information.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InterestInfo {
        /** Interest Type */
        private String type;

        /** Rate */
        private List<InterestRate> rates;

        /** From To Date */
        private DatePeriod fromToDate;

        /** Reason */
        private String reason;

        /** Tax */
        private TaxCharges tax;
    }

    /**
     * Interest Rate.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InterestRate {
        private String type;
        private BigDecimal rate;
        private DatePeriod validityRange;
    }

    /**
     * Card Transaction.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CardTransaction {
        private String cardNumber;
        private String cardBrand;
        private String cardType;
        private String merchantId;
        private String merchantName;
        private String merchantCategoryCode;
        private String terminalId;
        private String transactionId;
        private LocalDateTime transactionDateTime;
    }

    /**
     * Local Instrument.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LocalInstrument {
        private String code;
        private String proprietary;
    }

    /**
     * Purpose of Transaction.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Purpose {
        private String code;
        private String proprietary;
    }

    /**
     * Remittance Location.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RemittanceLocation {
        private String remittanceId;
        private List<RemittanceLocationDetails> remittanceLocationDetails;
    }

    /**
     * Remittance Location Details.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RemittanceLocationDetails {
        private String method;
        private String electronicAddress;
        private PostalAddress postalAddress;
    }

    /**
     * Remittance Information.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RemittanceInformation {
        /** Unstructured remittance information */
        private List<String> unstructured;

        /** Structured remittance information */
        private List<StructuredRemittanceInfo> structured;
    }

    /**
     * Structured Remittance Information.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StructuredRemittanceInfo {
        /** Referred Document Information */
        private List<ReferredDocumentInfo> referredDocumentInformation;

        /** Referred Document Amount */
        private ReferredDocumentAmount referredDocumentAmount;

        /** Creditor Reference Information */
        private CreditorReferenceInfo creditorReferenceInformation;

        /** Invoicer */
        private Party invoicer;

        /** Invoicee */
        private Party invoicee;

        /** Tax Remittance */
        private TaxRemittance taxRemittance;

        /** Additional Remittance Information */
        private List<String> additionalRemittanceInformation;
    }

    /**
     * Referred Document Information.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReferredDocumentInfo {
        private String type;
        private String number;
        private LocalDate relatedDate;
        private List<String> lineDetails;
    }

    /**
     * Referred Document Amount.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReferredDocumentAmount {
        private AmountWithCurrency duePayableAmount;
        private List<DiscountAmountAndType> discountAppliedAmount;
        private AmountWithCurrency creditNoteAmount;
        private List<TaxAmountAndType> taxAmount;
        private List<AdjustmentAmountAndReason> adjustmentAmountAndReason;
        private AmountWithCurrency remittedAmount;
    }

    /**
     * Discount Amount and Type.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiscountAmountAndType {
        private String type;
        private AmountWithCurrency amount;
    }

    /**
     * Tax Amount and Type.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaxAmountAndType {
        private String type;
        private AmountWithCurrency amount;
    }

    /**
     * Adjustment Amount and Reason.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AdjustmentAmountAndReason {
        private AmountWithCurrency amount;
        private CreditDebitIndicator creditDebitIndicator;
        private String reason;
        private String additionalInformation;
    }

    /**
     * Creditor Reference Information.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreditorReferenceInfo {
        private String type;
        private String reference;
    }

    /**
     * Tax Remittance.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaxRemittance {
        private Party creditor;
        private Party debtor;
        private String administrationZone;
        private String referenceNumber;
        private String method;
        private AmountWithCurrency totalTaxableBaseAmount;
        private AmountWithCurrency totalTaxAmount;
        private LocalDate date;
        private Long sequenceNumber;
        private List<TaxRecord> records;
    }

    /**
     * Tax Record.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaxRecord {
        private String type;
        private String category;
        private String categoryDetails;
        private String debtorStatus;
        private String certificateId;
        private String formsCode;
        private DatePeriod period;
        private TaxAmount taxAmount;
        private String additionalInformation;
    }

    /**
     * Tax Amount.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaxAmount {
        private BigDecimal rate;
        private AmountWithCurrency taxableBaseAmount;
        private AmountWithCurrency totalAmount;
        private List<TaxRecordDetails> details;
    }

    /**
     * Tax Record Details.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaxRecordDetails {
        private DatePeriod period;
        private AmountWithCurrency amount;
    }

    /**
     * Related Dates.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RelatedDates {
        private LocalDate acceptanceDateTime;
        private LocalDate tradeActivityContractualSettlementDate;
        private LocalDate tradeDate;
        private LocalDate interbankSettlementDate;
        private LocalDate startDate;
        private LocalDate endDate;
        private LocalDate transactionDateTime;
        private List<ProprietaryDate> proprietaryDates;
    }

    /**
     * Proprietary Date.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProprietaryDate {
        private String type;
        private LocalDate date;
    }

    /**
     * Related Price.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RelatedPrice {
        private String dealPrice;
        private String type;
        private AmountWithCurrency amount;
    }
}
