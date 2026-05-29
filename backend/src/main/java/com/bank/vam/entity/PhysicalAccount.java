package com.bank.vam.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * PhysicalAccount Entity - Real bank accounts for corporate treasury.
 * 
 * ENHANCED for UNIFIED ARCHITECTURE v4.2:
 * - Added shadowVaId for PHYSICAL_MIRROR VA linkage
 * - Added getSwiftCode() helper method
 * 
 * IMPORTANT DISTINCTION:
 * 
 * 1. HOME BANK ACCOUNTS (bankRelationship = INTERNAL)
 *    - Accounts held at the bank running this VAM platform
 *    - Direct integration with Core Banking System (BANCS)
 *    - Real-time balance and transaction data
 *    - Full transaction capabilities (payments, transfers)
 *    - Can be linked to Virtual Accounts as settlement accounts
 *    - Eligible for Notional Pooling and Cash Concentration
 *    - Example: Emirates NBD accounts (if ENBD runs this VAM)
 * 
 * 2. EXTERNAL BANK ACCOUNTS (bankRelationship = EXTERNAL)
 *    - Accounts held at other financial institutions
 *    - Data fetched via:
 *      • Open Banking APIs (PSD2, UAE Open Banking)
 *      • SWIFT MT940/MT942 statements
 *      • Host-to-Host file transfers
 *      • API aggregators (Plaid, Yodlee, etc.)
 *    - Usually delayed balance updates (EOD or intraday)
 *    - Limited capabilities (view-only or initiate via separate channel)
 *    - Cannot host Virtual Accounts
 *    - Limited treasury features
 *    - Example: HSBC, Citi, DBS accounts
 * 
 * Domain Model:
 * - Corporate (1) -> PhysicalAccount (N)
 * - PhysicalAccount [INTERNAL] (1) -> VirtualAccount (N) [as settlement]
 * - PhysicalAccount (1) -> VirtualAccount[PHYSICAL_MIRROR] (1) [shadow]
 * - PhysicalAccount (N) -> NotionalPool (M) [via pool membership]
 * - PhysicalAccount (1) -> SweepRule (N) [as header or participant]
 */
@Entity
@Table(name = "physical_accounts", indexes = {
    @Index(name = "idx_pa_account_number", columnList = "account_number"),
    @Index(name = "idx_pa_iban", columnList = "iban"),
    @Index(name = "idx_pa_corporate", columnList = "corporate_id"),
    @Index(name = "idx_pa_status", columnList = "status"),
    @Index(name = "idx_pa_bank_code", columnList = "bank_code"),
    @Index(name = "idx_pa_currency", columnList = "currency_code"),
    @Index(name = "idx_pa_bank_relationship", columnList = "bank_relationship"),
    @Index(name = "idx_pa_data_source", columnList = "data_source"),
    @Index(name = "idx_pa_shadow_va", columnList = "shadow_va_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhysicalAccount extends BaseEntity {

    // ========================================================================
    // ACCOUNT IDENTIFICATION
    // ========================================================================

    @Column(name = "account_number", unique = true, nullable = false)
    private String accountNumber;

    @Column(name = "iban", length = 34, unique = true)
    private String iban;

    @Column(name = "account_name", nullable = false)
    private String accountName;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type")
    @Builder.Default
    private AccountType accountType = AccountType.CURRENT;

    // ========================================================================
    // BANK INFORMATION
    // ========================================================================

    @Column(name = "bank_name")
    private String bankName;

    @Column(name = "bank_code", length = 20)
    private String bankCode; // SWIFT/BIC

    @Column(name = "bank_country", length = 2)
    private String bankCountry;

    @Column(name = "branch_name")
    private String branchName;

    @Column(name = "branch_code", length = 20)
    private String branchCode;

    // ========================================================================
    // BANK RELATIONSHIP - HOME vs EXTERNAL
    // ========================================================================

    /**
     * Defines whether this account is at the Home Bank (internal) or External Bank.
     * 
     * INTERNAL: Account at the bank running this VAM platform
     *   - Direct core banking integration
     *   - Real-time data
     *   - Full capabilities
     * 
     * EXTERNAL: Account at another financial institution
     *   - Data via Open Banking / SWIFT
     *   - Delayed updates
     *   - Limited capabilities
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "bank_relationship", length = 20)
    @Builder.Default
    private BankRelationship bankRelationship = BankRelationship.INTERNAL;

    /**
     * How account data is sourced/synchronized.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "data_source", length = 30)
    @Builder.Default
    private DataSource dataSource = DataSource.CORE_BANKING;

    /**
     * External connection identifier (Open Banking consent ID, SWIFT address, etc.)
     */
    @Column(name = "external_connection_id")
    private String externalConnectionId;

    /**
     * API provider for external accounts (e.g., "PLAID", "YODLEE", "TARABUT", "LEAN")
     */
    @Column(name = "api_provider", length = 50)
    private String apiProvider;

    /**
     * Open Banking consent expiry date
     */
    @Column(name = "consent_expires_at")
    private LocalDateTime consentExpiresAt;

    // ========================================================================
    // ACCOUNT CAPABILITIES
    // ========================================================================

    /**
     * Can view balance (always true for linked accounts)
     */
    @Column(name = "can_view_balance")
    @Builder.Default
    private Boolean canViewBalance = true;

    /**
     * Can view transaction history
     */
    @Column(name = "can_view_transactions")
    @Builder.Default
    private Boolean canViewTransactions = true;

    /**
     * Can initiate payments from this account
     */
    @Column(name = "can_initiate_payments")
    @Builder.Default
    private Boolean canInitiatePayments = true;

    /**
     * Can receive internal transfers
     */
    @Column(name = "can_receive_transfers")
    @Builder.Default
    private Boolean canReceiveTransfers = true;

    /**
     * Can be used as settlement account for Virtual Accounts
     * Only applicable for INTERNAL accounts
     */
    @Column(name = "can_host_virtual_accounts")
    @Builder.Default
    private Boolean canHostVirtualAccounts = true;

    /**
     * Eligible for notional pooling
     * Usually only INTERNAL accounts
     */
    @Column(name = "pooling_eligible")
    @Builder.Default
    private Boolean poolingEligible = true;

    /**
     * Eligible for cash concentration (sweeping)
     */
    @Column(name = "sweep_eligible")
    @Builder.Default
    private Boolean sweepEligible = true;

    // ========================================================================
    // OWNERSHIP
    // ========================================================================

    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    @Column(name = "entity_name")
    private String entityName; // Legal entity name

    @Column(name = "entity_code", length = 20)
    private String entityCode; // Short code (HQ, DXB, UK, SG)

    // ========================================================================
    // BALANCES
    // ========================================================================

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "current_balance", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal currentBalance = BigDecimal.ZERO;

    @Column(name = "available_balance", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal availableBalance = BigDecimal.ZERO;

    @Column(name = "ledger_balance", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal ledgerBalance = BigDecimal.ZERO;

    /**
     * Balance as of date/time (for external accounts with delayed updates)
     */
    @Column(name = "balance_as_of")
    private LocalDateTime balanceAsOf;

    // ========================================================================
    // INTEREST
    // ========================================================================

    @Column(name = "interest_rate", precision = 8, scale = 4)
    private BigDecimal interestRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "interest_type", length = 20)
    private InterestType interestType;

    /** Effective (post-tiering/fees) annual rate. Used by the Simulator
     *  Phase-2 interest-yield line; column pre-exists in schema.sql. */
    @Column(name = "effective_interest_rate", precision = 8, scale = 5)
    private BigDecimal effectiveInterestRate;

    // ========================================================================
    // OVERDRAFT (pre-existing schema columns; surfaced for Simulator Phase 2)
    // ========================================================================

    @Column(name = "overdraft_limit", precision = 19, scale = 4)
    private BigDecimal overdraftLimit;

    @Column(name = "overdraft_utilized", precision = 19, scale = 4)
    private BigDecimal overdraftUtilized;

    // ========================================================================
    // TREASURY CONFIGURATION - NOTIONAL POOLING
    // ========================================================================

    @Column(name = "pooling_enabled")
    @Builder.Default
    private Boolean poolingEnabled = false;

    @Column(name = "pool_id")
    private UUID poolId; // Link to NotionalPool

    @Column(name = "pool_reference", length = 50)
    private String poolReference;

    // ========================================================================
    // TREASURY CONFIGURATION - CASH CONCENTRATION (SWEEPING)
    // ========================================================================

    @Column(name = "sweep_enabled")
    @Builder.Default
    private Boolean sweepEnabled = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "sweep_role", length = 20)
    private SweepRole sweepRole;

    @Column(name = "sweep_rule_id")
    private UUID sweepRuleId; // Link to SweepRule

    // ========================================================================
    // VIRTUAL ACCOUNT LINKAGE (for INTERNAL accounts)
    // ========================================================================

    /**
     * Number of Virtual Accounts using this as settlement account
     */
    @Column(name = "virtual_account_count")
    @Builder.Default
    private Integer virtualAccountCount = 0;

    /**
     * UNIFIED ARCHITECTURE v4.2:
     * Link to the Shadow VA (PHYSICAL_MIRROR) that represents this account
     * in the VA hierarchy.
     */
    @Column(name = "shadow_va_id")
    private UUID shadowVaId;

    // ========================================================================
    // STATUS & LIFECYCLE
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    @Builder.Default
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(name = "opened_date")
    private LocalDate openedDate;

    @Column(name = "closed_date")
    private LocalDate closedDate;

    @Column(name = "last_transaction_at")
    private LocalDateTime lastTransactionAt;

    // ========================================================================
    // SYNC STATUS
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", length = 20)
    @Builder.Default
    private SyncStatus syncStatus = SyncStatus.SYNCED;

    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    @Column(name = "sync_error_message")
    private String syncErrorMessage;

    /**
     * How often to sync (in minutes). 0 = real-time, -1 = manual only
     */
    @Column(name = "sync_frequency_minutes")
    @Builder.Default
    private Integer syncFrequencyMinutes = 0;

    /**
     * Next scheduled sync time
     */
    @Column(name = "next_sync_at")
    private LocalDateTime nextSyncAt;

    // ========================================================================
    // BANCS INTEGRATION (for INTERNAL accounts)
    // ========================================================================

    @Column(name = "bancs_customer_id")
    private String bancsCustomerId;

    @Column(name = "bancs_account_id")
    private String bancsAccountId;

     /**
     * Link to LegalEntity for ownership tracking.
     * Allows filtering physical accounts by legal entity.
     */
    @Column(name = "legal_entity_id")
    private UUID legalEntityId;

    // ========================================================================
    // RELATIONSHIP
    // ========================================================================

    @Column(name = "relationship_manager")
    private String relationshipManager;

    // ========================================================================
    // ENUMS
    // ========================================================================

    public enum AccountType {
        CURRENT,
        SAVINGS,
        CALL_DEPOSIT,
        FIXED_DEPOSIT,
        ESCROW,
        NOSTRO,
        VOSTRO,
        POOL,
        COLLECTION,
        SETTLEMENT
    }

    public enum AccountStatus {
        ACTIVE,
        INACTIVE,
        DORMANT,
        BLOCKED,
        FROZEN,
        CLOSED,
        PENDING_VERIFICATION // For newly linked external accounts
    }

    public enum InterestType {
        CREDIT,
        DEBIT,
        TIERED,
        NONE
    }

    public enum SweepRole {
        HEADER,      // Master account that receives/sends funds
        PARTICIPANT  // Account that sweeps to/from header
    }

    public enum SyncStatus {
        SYNCED,
        PENDING,
        ERROR,
        CONSENT_EXPIRED, // Open Banking consent expired
        DISCONNECTED     // External connection lost
    }

    /**
     * Bank Relationship - distinguishes Home Bank from External Banks
     */
    public enum BankRelationship {
        /**
         * Account at the Home Bank (the bank running this VAM platform).
         * - Direct core banking integration
         * - Real-time data
         * - Full capabilities
         */
        INTERNAL,

        /**
         * Account at an external financial institution.
         * - Data via Open Banking / SWIFT / Aggregators
         * - Delayed updates
         * - Limited capabilities
         */
        EXTERNAL
    }

    /**
     * Data Source - how account data is retrieved
     */
    public enum DataSource {
        // Internal / Home Bank sources
        CORE_BANKING,      // Direct BANCS/T24/Flexcube integration
        INTERNAL_API,      // Internal bank APIs
        
        // External / Open Banking sources
        OPEN_BANKING_PSD2, // EU PSD2 Open Banking
        OPEN_BANKING_UAE,  // UAE Open Banking (Tarabut, Lean)
        OPEN_BANKING_UK,   // UK Open Banking
        OPEN_BANKING_KSA,  // Saudi Open Banking
        
        // SWIFT sources
        SWIFT_MT940,       // End of day statements
        SWIFT_MT942,       // Intraday statements
        SWIFT_CAMT053,     // ISO 20022 Bank to Customer Statement
        SWIFT_CAMT052,     // ISO 20022 Intraday Report
        
        // File-based sources
        HOST_TO_HOST,      // Bank file transfers (BAI2, etc.)
        SFTP_IMPORT,       // SFTP file import
        
        // Aggregator sources
        PLAID,             // Plaid aggregator
        YODLEE,            // Yodlee aggregator
        TARABUT,           // Tarabut Gateway (MENA)
        LEAN,              // Lean Technologies (MENA)
        SALT_EDGE,         // Salt Edge aggregator
        
        // Manual
        MANUAL_ENTRY       // Manual balance entry
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Get SWIFT/BIC code (alias for bankCode).
     * Added for ShadowAccountService compatibility.
     */
    public String getSwiftCode() {
        return bankCode;
    }

    /**
     * Set SWIFT/BIC code (alias for bankCode).
     */
    public void setSwiftCode(String swiftCode) {
        this.bankCode = swiftCode;
    }

    /**
     * Check if this account has a shadow VA linked.
     */
    public boolean hasShadowVa() {
        return shadowVaId != null;
    }

    /**
     * Check if this is a Home Bank account (internal)
     */
    public boolean isHomeBank() {
        return bankRelationship == BankRelationship.INTERNAL;
    }

    /**
     * Check if this is an External Bank account
     */
    public boolean isExternalBank() {
        return bankRelationship == BankRelationship.EXTERNAL;
    }

    /**
     * Check if account has real-time data
     */
    public boolean hasRealTimeData() {
        return isHomeBank() && (dataSource == DataSource.CORE_BANKING || dataSource == DataSource.INTERNAL_API);
    }

    /**
     * Check if account is operational
     */
    public boolean isOperational() {
        return status == AccountStatus.ACTIVE;
    }

    /**
     * Check if account can host Virtual Accounts
     */
    public boolean canHostVirtualAccounts() {
        return isHomeBank() && Boolean.TRUE.equals(canHostVirtualAccounts) && isOperational();
    }

    /**
     * Check if account is eligible for pooling
     */
    public boolean isPoolingEligible() {
        return Boolean.TRUE.equals(poolingEligible) && isOperational();
    }

    /**
     * Check if account is eligible for sweeping
     */
    public boolean isSweepEligible() {
        return Boolean.TRUE.equals(sweepEligible) && isOperational();
    }

    /**
     * Check if Open Banking consent is expired
     */
    public boolean isConsentExpired() {
        if (consentExpiresAt == null) return false;
        return LocalDateTime.now().isAfter(consentExpiresAt);
    }

    /**
     * Check if sync is needed based on frequency
     */
    public boolean needsSync() {
        if (syncFrequencyMinutes == null || syncFrequencyMinutes < 0) return false;
        if (syncFrequencyMinutes == 0) return false; // Real-time, no scheduled sync
        if (lastSyncAt == null) return true;
        return LocalDateTime.now().isAfter(lastSyncAt.plusMinutes(syncFrequencyMinutes));
    }

    /**
     * Get formatted account display (IBAN if available, else account number)
     */
    public String getDisplayAccountNumber() {
        if (iban != null && !iban.isEmpty()) {
            return iban;
        }
        return accountNumber;
    }

    /**
     * Check if account has treasury features enabled
     */
    public boolean hasTreasuryFeatures() {
        return Boolean.TRUE.equals(poolingEnabled) || Boolean.TRUE.equals(sweepEnabled);
    }

    /**
     * Update balance and sync status
     */
    public void updateBalance(BigDecimal current, BigDecimal available, BigDecimal ledger) {
        this.currentBalance = current;
        this.availableBalance = available;
        this.ledgerBalance = ledger;
        this.balanceAsOf = LocalDateTime.now();
        this.lastSyncAt = LocalDateTime.now();
        this.syncStatus = SyncStatus.SYNCED;
        this.syncErrorMessage = null;
        
        // Calculate next sync time
        if (syncFrequencyMinutes != null && syncFrequencyMinutes > 0) {
            this.nextSyncAt = LocalDateTime.now().plusMinutes(syncFrequencyMinutes);
        }
    }

    /**
     * Mark sync error
     */
    public void markSyncError(String errorMessage) {
        this.syncStatus = SyncStatus.ERROR;
        this.syncErrorMessage = errorMessage;
        this.lastSyncAt = LocalDateTime.now();
    }

    /**
     * Mark consent expired
     */
    public void markConsentExpired() {
        this.syncStatus = SyncStatus.CONSENT_EXPIRED;
        this.syncErrorMessage = "Open Banking consent has expired. Please re-authenticate.";
    }

    /**
     * Configure as internal (Home Bank) account
     */
    public void configureAsInternal(String bancsCustomerId, String bancsAccountId) {
        this.bankRelationship = BankRelationship.INTERNAL;
        this.dataSource = DataSource.CORE_BANKING;
        this.bancsCustomerId = bancsCustomerId;
        this.bancsAccountId = bancsAccountId;
        this.canViewBalance = true;
        this.canViewTransactions = true;
        this.canInitiatePayments = true;
        this.canReceiveTransfers = true;
        this.canHostVirtualAccounts = true;
        this.poolingEligible = true;
        this.sweepEligible = true;
        this.syncFrequencyMinutes = 0; // Real-time
    }

    /**
     * Configure as external (Open Banking) account
     */
    public void configureAsExternal(DataSource source, String connectionId, String provider, LocalDateTime consentExpiry) {
        this.bankRelationship = BankRelationship.EXTERNAL;
        this.dataSource = source;
        this.externalConnectionId = connectionId;
        this.apiProvider = provider;
        this.consentExpiresAt = consentExpiry;
        this.canViewBalance = true;
        this.canViewTransactions = true;
        this.canInitiatePayments = false; // Usually view-only initially
        this.canReceiveTransfers = false;
        this.canHostVirtualAccounts = false; // Cannot host VAs
        this.poolingEligible = false; // Usually not eligible
        this.sweepEligible = false;
        this.syncFrequencyMinutes = 60; // Hourly sync default
    }

    /**
     * Enable pooling
     */
    public void enablePooling(UUID poolId, String poolReference) {
        if (!isPoolingEligible()) {
            throw new IllegalStateException("Account is not eligible for pooling");
        }
        this.poolingEnabled = true;
        this.poolId = poolId;
        this.poolReference = poolReference;
    }

    /**
     * Disable pooling
     */
    public void disablePooling() {
        this.poolingEnabled = false;
        this.poolId = null;
        this.poolReference = null;
    }

    /**
     * Enable sweep as header
     */
    public void enableSweepAsHeader(UUID sweepRuleId) {
        if (!isSweepEligible()) {
            throw new IllegalStateException("Account is not eligible for sweeping");
        }
        this.sweepEnabled = true;
        this.sweepRole = SweepRole.HEADER;
        this.sweepRuleId = sweepRuleId;
    }

    /**
     * Enable sweep as participant
     */
    public void enableSweepAsParticipant(UUID sweepRuleId) {
        if (!isSweepEligible()) {
            throw new IllegalStateException("Account is not eligible for sweeping");
        }
        this.sweepEnabled = true;
        this.sweepRole = SweepRole.PARTICIPANT;
        this.sweepRuleId = sweepRuleId;
    }

    /**
     * Disable sweep
     */
    public void disableSweep() {
        this.sweepEnabled = false;
        this.sweepRole = null;
        this.sweepRuleId = null;
    }
}