package com.bank.vam.iso20022.dto;

/**
 * Common Enumerations for ISO 20022 camt (Cash Management) Messages.
 *
 * <p>This class contains shared enumerations used across camt.052 (intraday report),
 * camt.053 (end-of-day statement), and camt.054 (debit/credit notification) messages.</p>
 *
 * <h2>ISO 20022 Message Family - camt:</h2>
 * <ul>
 *   <li>camt.052 - Bank to Customer Account Report (Intraday)</li>
 *   <li>camt.053 - Bank to Customer Statement (End-of-Day)</li>
 *   <li>camt.054 - Bank to Customer Debit Credit Notification (Real-Time)</li>
 *   <li>camt.056 - FI to FI Payment Cancellation Request</li>
 *   <li>camt.057 - Notification to Receive</li>
 *   <li>camt.060 - Account Reporting Request</li>
 * </ul>
 *
 * @see Camt053Dto
 * @see Camt054Dto
 * @see <a href="https://www.iso20022.org/catalogue-messages/camt-cash-management">ISO 20022 camt Messages</a>
 */
public final class CamtCommonEnums {

    private CamtCommonEnums() {
        // Utility class - prevent instantiation
    }

    // ========================================================================
    // BALANCE TYPE CODES
    // ========================================================================

    /**
     * Balance Type Codes - External code list ExternalBalanceType1Code.
     *
     * <p>Used in camt.052, camt.053, camt.054 for Bal/Tp/CdOrPrtry/Cd element.</p>
     */
    public enum BalanceTypeCode {
        // Opening Balances
        OPBD("Opening Booked", "Balance at the beginning of the statement period (booked entries only)"),
        OPAV("Opening Available", "Balance available for use at the beginning of the period"),

        // Closing Balances
        CLBD("Closing Booked", "Balance at the end of the statement period (booked entries only)"),
        CLAV("Closing Available", "Balance available for use at the end of the period"),

        // Interim Balances
        ITBD("Interim Booked", "Intraday balance at a specific time (booked entries only)"),
        ITAV("Interim Available", "Available balance at a specific intraday time"),

        // Forward/Expected Balances
        FWAV("Forward Available", "Balance that will be available on a future date"),
        XPCD("Expected", "Expected balance including pending transactions"),

        // Previous Day
        PRCD("Previously Closed Booked", "Closing balance of the previous statement period"),

        // Information Only
        INFO("Information", "Balance provided for information purposes only"),

        // Proprietary Balances (VAM-specific)
        AGGR("Aggregated", "Aggregated balance across child VAs"),
        HIER("Hierarchy", "Balance from hierarchy aggregation"),
        MIRR("Mirror", "Currency mirror balance"),
        SHDW("Shadow", "Shadow account balance");

        private final String displayName;
        private final String description;

        BalanceTypeCode(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
        public String getCode() { return name(); }
    }

    // ========================================================================
    // CREDIT/DEBIT INDICATOR
    // ========================================================================

    /**
     * Credit/Debit Indicator.
     */
    public enum CreditDebitCode {
        CRDT("Credit", "Money received / balance positive"),
        DBIT("Debit", "Money sent / balance negative");

        private final String displayName;
        private final String description;

        CreditDebitCode(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
        public String getCode() { return name(); }
    }

    // ========================================================================
    // ENTRY STATUS CODES
    // ========================================================================

    /**
     * Entry Status Codes - External code list ExternalEntryStatus1Code.
     */
    public enum EntryStatusCode {
        BOOK("Booked", "Entry is final and has been posted to the account"),
        PDNG("Pending", "Entry is pending and has not yet been posted"),
        INFO("Information", "Entry is provided for information only, not booked"),
        FUTR("Future", "Entry is scheduled for a future date");

        private final String displayName;
        private final String description;

        EntryStatusCode(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
        public String getCode() { return name(); }
    }

    // ========================================================================
    // BANK TRANSACTION CODES - Domain/Family/SubFamily
    // ========================================================================

    /**
     * Bank Transaction Domain Codes.
     */
    public enum BankTransactionDomain {
        PMNT("Payments", "Payment transactions"),
        CAMT("Cash Management", "Cash management operations"),
        FORX("Foreign Exchange", "FX transactions"),
        SECU("Securities", "Securities transactions"),
        ACCT("Account Management", "Account operations"),
        LDAS("Loans & Deposits", "Loan and deposit transactions"),
        TRAD("Trade Finance", "Trade finance operations");

        private final String displayName;
        private final String description;

        BankTransactionDomain(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
        public String getCode() { return name(); }
    }

    /**
     * Bank Transaction Family Codes (PMNT domain).
     */
    public enum PaymentTransactionFamily {
        // Received Payments
        RCDT("Received Credit Transfer", "Credit transfer received"),
        RCCN("Received Cash Concentration", "Cash concentration received"),
        RDDT("Received Direct Debit", "Direct debit received"),
        RCHQ("Received Cheque", "Cheque deposited"),
        RRCT("Received Returned Credit Transfer", "Return of credit transfer"),

        // Issued Payments
        ICDT("Issued Credit Transfer", "Credit transfer sent"),
        IDDT("Issued Direct Debit", "Direct debit sent"),
        ICHQ("Issued Cheque", "Cheque issued"),
        IRCT("Issued Returned Credit Transfer", "Credit transfer returned"),

        // Cash Management
        CCNC("Cash Concentration", "Cash concentration"),
        NTAV("Notional Pooling", "Notional pool transaction"),
        SWEP("Sweep", "Sweep transaction"),

        // Card Transactions
        CWDL("Card Withdrawal", "ATM/Cash withdrawal"),
        CDPT("Card Deposit", "Card deposit"),
        POSC("Point of Sale Credit", "POS credit"),
        POSD("Point of Sale Debit", "POS debit"),

        // Fees & Interest
        FEES("Fees", "Bank fees and charges"),
        COMM("Commission", "Commission charges"),
        INTR("Interest", "Interest payment or charge"),

        // Other
        MDOP("Miscellaneous Debit", "Other debit"),
        MCOP("Miscellaneous Credit", "Other credit"),
        OTHR("Other", "Other transaction type");

        private final String displayName;
        private final String description;

        PaymentTransactionFamily(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
        public String getCode() { return name(); }
    }

    /**
     * VAM-specific Bank Transaction SubFamily Codes.
     */
    public enum VamTransactionSubFamily {
        // Collection subfamilies
        COLL("Collection", "Standard collection"),
        RCIN("ROBO Collection Inward", "Receive-on-behalf-of collection"),
        ECOL("E-commerce Collection", "E-commerce platform collection"),
        QRPG("QR Payment", "QR code payment collection"),
        INVP("Invoice Payment", "Invoice-matched payment"),

        // Payment subfamilies
        PMTO("Payment Outward", "Standard outward payment"),
        POBO("POBO Payment", "Pay-on-behalf-of payment"),
        SLRY("Salary Payment", "Payroll payment"),
        VEND("Vendor Payment", "Supplier/vendor payment"),
        TAXP("Tax Payment", "Tax payment"),

        // Internal transfers
        ITRN("Internal Transfer", "VA-to-VA transfer"),
        SWEP("Sweep", "Automated sweep"),
        POOL("Pool Transfer", "Notional pool transfer"),
        FXCV("FX Conversion", "Currency conversion"),

        // Fees
        COLF("Collection Fee", "Collection processing fee"),
        PMTF("Payment Fee", "Payment processing fee"),
        ACMF("Account Maintenance Fee", "Account maintenance"),
        POBF("POBO Fee", "POBO service fee"),
        ROBF("ROBO Fee", "ROBO service fee"),

        // Interest
        CINT("Credit Interest", "Interest earned"),
        DINT("Debit Interest", "Interest charged"),
        IHBI("IHB Interest", "In-House Bank interest"),

        // Exceptions
        EXCR("Exception Credit", "Exception - unmatched credit"),
        EXDB("Exception Debit", "Exception - failed debit"),
        RVRX("Reversal Exception", "Reversal exception");

        private final String displayName;
        private final String description;

        VamTransactionSubFamily(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
        public String getCode() { return name(); }
    }

    // ========================================================================
    // COPY/DUPLICATE INDICATOR
    // ========================================================================

    /**
     * Copy/Duplicate Indicator - For resent messages.
     */
    public enum CopyDuplicateCode {
        CODU("Copy Duplicate", "Copy of a previously sent message"),
        COPY("Copy", "Copy for information"),
        DUPL("Duplicate", "Duplicate due to technical issue");

        private final String displayName;
        private final String description;

        CopyDuplicateCode(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
        public String getCode() { return name(); }
    }

    // ========================================================================
    // CHARGE BEARER CODES
    // ========================================================================

    /**
     * Charge Bearer Codes - Who pays the charges.
     */
    public enum ChargeBearerCode {
        DEBT("Debtor", "All charges borne by debtor"),
        CRED("Creditor", "All charges borne by creditor"),
        SHAR("Shared", "Charges shared between parties"),
        SLEV("Service Level", "Charges as per service level agreement");

        private final String displayName;
        private final String description;

        ChargeBearerCode(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
        public String getCode() { return name(); }
    }

    // ========================================================================
    // INSTRUCTION PRIORITY
    // ========================================================================

    /**
     * Instruction Priority Codes.
     */
    public enum InstructionPriorityCode {
        HIGH("High", "High priority - expedited processing"),
        NORM("Normal", "Normal priority - standard processing");

        private final String displayName;
        private final String description;

        InstructionPriorityCode(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
        public String getCode() { return name(); }
    }

    // ========================================================================
    // PAYMENT STATUS CODES
    // ========================================================================

    /**
     * Transaction/Payment Status Codes.
     */
    public enum TransactionStatusCode {
        // Accepted statuses
        ACCP("Accepted Customer Profile", "Authentication and verification successful"),
        ACSP("Accepted Settlement In Process", "Settlement processing initiated"),
        ACSC("Accepted Settlement Completed", "Settlement completed successfully"),
        ACWC("Accepted With Change", "Accepted with modifications"),

        // Pending statuses
        PDNG("Pending", "Payment is pending"),
        RCVD("Received", "Payment instruction received"),

        // Rejected/Failed statuses
        RJCT("Rejected", "Payment rejected"),
        CANC("Cancelled", "Payment cancelled"),

        // Return statuses
        RTND("Returned", "Payment returned"),
        RTRN("Return", "Return of funds");

        private final String displayName;
        private final String description;

        TransactionStatusCode(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
        public String getCode() { return name(); }
    }

    // ========================================================================
    // ACCOUNT TYPE CODES
    // ========================================================================

    /**
     * Account Type Codes.
     */
    public enum AccountTypeCode {
        // Standard types
        CACC("Current Account", "Current/checking account"),
        CASH("Cash Payment", "Cash payment account"),
        SVGS("Savings", "Savings account"),
        LOAN("Loan", "Loan account"),

        // VAM-specific
        VACC("Virtual Account", "Virtual account"),
        VIBA("VIBAN Account", "Virtual IBAN account"),
        POOL("Pool Account", "Pooling account"),
        SWEP("Sweep Account", "Sweep target account"),
        SETL("Settlement Account", "Settlement account"),
        EXCP("Exception Account", "Exception handling account"),
        MIRR("Mirror Account", "Currency mirror account"),
        SHDW("Shadow Account", "Physical account shadow");

        private final String displayName;
        private final String description;

        AccountTypeCode(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
        public String getCode() { return name(); }
    }

    // ========================================================================
    // REPORTING SOURCE
    // ========================================================================

    /**
     * Reporting Source Codes.
     */
    public enum ReportingSourceCode {
        ACCT("Account", "Report from account system"),
        CHAN("Channel", "Report from channel"),
        SECU("Securities", "Report from securities system"),
        OTHR("Other", "Other reporting source"),

        // VAM-specific
        VAMS("VAM System", "Virtual Account Management System"),
        COBS("Core Banking", "Core Banking System"),
        TRSR("Treasury", "Treasury Management System");

        private final String displayName;
        private final String description;

        ReportingSourceCode(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
        public String getCode() { return name(); }
    }
}
