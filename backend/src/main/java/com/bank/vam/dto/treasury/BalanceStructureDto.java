package com.bank.vam.dto.treasury;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * DTOs for Balance Structure / Treasury Hierarchy
 * Matches Capgemini's Virtual Cash Management hierarchy model
 * 
 * Enhanced with:
 * - specialType: REGULAR, SETTLEMENT, EXCEPTION, CURRENCY_MIRROR
 * - accountCategory: ROOT, AGGREGATION, CURRENCY_MIRROR, PHYSICAL_MIRROR, SETTLEMENT, EXCEPTION, TRANSACTION, etc.
 */
public class BalanceStructureDto {

    // ========================================================================
    // NODE TYPES ENUM
    // ========================================================================
    
    public enum NodeType {
        GROUP,           // Top-level corporate group
        REGION,          // Regional grouping (EMEA, APAC, etc.)
        ENTITY,          // Legal entity / subsidiary
        VIRTUAL_ACCOUNT, // Individual virtual account
        SHADOW_ACCOUNT   // Pool header / notional pool shadow account
    }

    // ========================================================================
    // SPECIAL VA TYPE ENUM (NEW)
    // ========================================================================
    
    public enum SpecialVaType {
        REGULAR,         // Normal virtual account
        SETTLEMENT,      // Settlement VA for fee postings
        EXCEPTION,       // Exception VA for unmatched transactions
        CURRENCY_MIRROR  // Currency mirror for FX aggregation
    }

    // ========================================================================
    // ACCOUNT CATEGORY ENUM (NEW)
    // ========================================================================
    
    public enum AccountCategory {
        ROOT,            // L1 root node
        AGGREGATION,     // L2-L6 aggregation/consolidation node
        CURRENCY_MIRROR, // Currency mirror account
        PHYSICAL_MIRROR, // Shadow of physical bank account
        SETTLEMENT,      // Settlement VA
        EXCEPTION,       // Exception VA
        TRANSACTION,     // Regular transaction VA (L7)
        COLLECTION,      // Collection VA
        DISBURSEMENT,    // Disbursement VA
        INTERCOMPANY     // Intercompany VA
    }

    // ========================================================================
    // MAIN HIERARCHY NODE DTO
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HierarchyNode {
        private String id;
        private String name;
        private String accountNumber;
        private NodeType type;
        private int level;
        private String currencyCode;
        
        // ====================================================================
        // NEW: Special Type and Account Category (for icon differentiation)
        // ====================================================================
        private SpecialVaType specialType;      // REGULAR, SETTLEMENT, EXCEPTION, CURRENCY_MIRROR
        private AccountCategory accountCategory; // ROOT, AGGREGATION, SETTLEMENT, etc.
        
        // Balance information
        private BigDecimal localBalance;
        private BigDecimal consolidatedBalance;  // In reporting currency
        private BigDecimal availableBalance;
        
        // Intercompany positions (from IHB)
        private BigDecimal intercompanyReceivable;
        private BigDecimal intercompanyPayable;
        private BigDecimal netPosition;
        
        // Interest allocation (from Notional Pooling)
        private BigDecimal interestRate;
        private BigDecimal interestAllocation;
        
        // Participation flags
        private boolean participatesInPooling;
        private boolean participatesInNetting;
        private boolean participatesInSweep;
        private String sweepTarget;
        
        // Hierarchy
        private String parentId;
        private List<HierarchyNode> children;
        
        // ====================================================================
        // NEW: Additional metadata for special VAs
        // ====================================================================
        private Integer coveredVaCount;      // For SETTLEMENT VAs: number of VAs covered
        private Integer pendingExceptions;   // For EXCEPTION VAs: pending exception count
        private BigDecimal mirrorBalance;    // For CURRENCY_MIRROR: mirror-specific balance
        private BigDecimal balanceInBase;    // For CURRENCY_MIRROR: mirror balance converted to base currency
        private BigDecimal fxRate;           // For CURRENCY_MIRROR: FX conversion rate
        private String fxRateAt;             // For CURRENCY_MIRROR: FX rate timestamp
        private String baseCurrency;         // Base currency for FX conversion
        private String primaryViban;         // Primary VIBAN if assigned
        private Integer vibanCount;          // Number of VIBANs assigned
        
        // ====================================================================
        // ENHANCED: Owning Entity Information (for Legal Entity display)
        // ====================================================================
        private String owningEntityId;       // UUID of owning legal entity
        private String owningEntityCode;     // Code of owning legal entity
        private String owningEntityName;     // Name of owning legal entity
        private String owningEntityType;     // Type: PARENT, SUBSIDIARY, BRANCH, etc.
        
        // ====================================================================
        // ENHANCED: IHB Summary (for In-House Banking display)
        // ====================================================================
        private IhbSummary ihb;              // IHB configuration if enabled
    }
    
    /**
     * IHB Summary embedded in hierarchy node
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IhbSummary {
        private boolean enabled;
        private boolean isTreasuryCenter;    // canLend = true
        private boolean canLend;
        private boolean canBorrow;
        private BigDecimal creditLimit;
        private BigDecimal currentExposure;
        private BigDecimal availableLimit;
        private BigDecimal utilizationPercent;
        private BigDecimal targetCashBalance;
        private boolean sweepEnabled;
        private String sweepFrequency;       // DAILY, REAL_TIME
    }

    // ========================================================================
    // SUMMARY STATS DTO
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BalanceSummary {
        private BigDecimal consolidatedBalance;
        private BigDecimal netPosition;
        private BigDecimal totalIntercompanyReceivable;
        private BigDecimal totalIntercompanyPayable;
        private BigDecimal netIntercompanyPosition;
        private BigDecimal poolRate;
        private BigDecimal monthlyInterestAllocation;
        private String reportingCurrency;
        
        // Counts
        private int totalEntities;
        private int totalVirtualAccounts;
        private int poolParticipants;
        private int sweepParticipants;
        private int nettingParticipants;
        
        // NEW: Special VA counts
        private int settlementVaCount;
        private int exceptionVaCount;
        private int currencyMirrorCount;
        
        // Currencies involved
        private List<String> currencies;
    }

    // ========================================================================
    // PHYSICAL ACCOUNT (REAL BANK ACCOUNT) DTO
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PhysicalAccountInfo {
        private UUID id;
        private String bankName;
        private String bankBic;
        private String accountNumber;
        private String iban;
        private String accountName;
        private BigDecimal balance;
        private BigDecimal availableBalance;
        private String currency;
        private String accountType;
        private String status;
    }

    // ========================================================================
    // NODE DETAIL DTO (for detail panel)
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NodeDetail {
        private String id;
        private String name;
        private String accountNumber;
        private NodeType type;
        private String currencyCode;
        
        // NEW: Special type info
        private SpecialVaType specialType;
        private AccountCategory accountCategory;
        
        // Balances
        private BigDecimal localBalance;
        private BigDecimal consolidatedBalance;
        private BigDecimal availableBalance;
        private BigDecimal holdAmount;
        
        // Intercompany
        private BigDecimal intercompanyReceivable;
        private BigDecimal intercompanyPayable;
        private BigDecimal netIntercompanyPosition;
        private List<IntercompanyPosition> icPositions;
        
        // Participation details
        private boolean participatesInPooling;
        private String poolReference;
        private BigDecimal poolContributionPercent;
        
        private boolean participatesInNetting;
        private String nettingCycleReference;
        
        private boolean participatesInSweep;
        private String sweepRuleReference;
        private String sweepTarget;
        private String sweepFrequency;
        
        // Interest
        private BigDecimal interestRate;
        private BigDecimal monthlyInterestAllocation;
        private BigDecimal ytdInterestAllocation;
        
        // NEW: Special VA specific fields
        private Integer coveredVaCount;      // For SETTLEMENT
        private Integer pendingExceptions;   // For EXCEPTION
        private BigDecimal mirrorBalance;    // For CURRENCY_MIRROR
        private BigDecimal fxRate;           // For CURRENCY_MIRROR
        private String fxRateAt;             // For CURRENCY_MIRROR
        
        // Metadata
        private String manager;
        private String costCenter;
        private String externalReference;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IntercompanyPosition {
        private String counterpartyId;
        private String counterpartyName;
        private String positionType; // RECEIVABLE or PAYABLE
        private BigDecimal amount;
        private String currencyCode;
        private String loanReference;
    }

    // ========================================================================
    // REQUEST DTOs
    // ========================================================================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HierarchyRequest {
        private UUID corporateId;
        private String reportingCurrency;
        private boolean includeZeroBalances;
        private boolean includeInactiveAccounts;
        private List<NodeType> nodeTypes; // Filter by type
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateParticipationRequest {
        private Boolean participatesInPooling;
        private String poolReference;
        
        private Boolean participatesInNetting;
        private String nettingCycleReference;
        
        private Boolean participatesInSweep;
        private String sweepRuleReference;
        private String sweepTarget;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateNodeRequest {
        private String name;
        private NodeType type;
        private String parentId;
        private String currencyCode;
        private UUID virtualAccountId; // Link to existing VA
        private String manager;
        private String costCenter;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MoveNodeRequest {
        private String newParentId;
    }

    // ========================================================================
    // CURRENCY CONVERSION DTO
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConvertedBalance {
        private BigDecimal originalAmount;
        private String originalCurrency;
        private BigDecimal convertedAmount;
        private String targetCurrency;
        private BigDecimal exchangeRate;
    }

    // ========================================================================
    // EXPORT DTO
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExportRequest {
        private String format; // CSV, XLSX, PDF
        private String reportingCurrency;
        private boolean includeDetails;
        private boolean includeIntercompany;
        private boolean includeInterest;
    }
}