package com.bank.vam.entity.hierarchy;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Legal Entity - Represents a corporate subsidiary, branch, or other legal structure.
 * 
 * FIXED v5.1.3:
 * - operatingCurrencies now uses Hibernate 6 @JdbcTypeCode(SqlTypes.JSON) for PostgreSQL JSONB
 * - This is the correct approach for Hibernate 6.x with Spring Boot 3.x
 */
@Entity
@Table(name = "legal_entities",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_legal_entity_code", columnNames = {"entity_code"}),
        @UniqueConstraint(name = "uq_legal_entity_bancs_customer", columnNames = {"bancs_customer_id"})
    },
    indexes = {
        @Index(name = "idx_le_parent", columnList = "parent_entity_id"),
        @Index(name = "idx_le_corporate", columnList = "corporate_id"),
        @Index(name = "idx_le_country", columnList = "country_code"),
        @Index(name = "idx_le_status", columnList = "status"),
        @Index(name = "idx_le_type", columnList = "entity_type"),
        @Index(name = "idx_le_hierarchy_path", columnList = "hierarchy_path"),
        @Index(name = "idx_le_treasury_center", columnList = "is_treasury_center"),
        @Index(name = "idx_le_functional_currency", columnList = "functional_currency"),
        @Index(name = "idx_le_bank_customer", columnList = "is_bank_customer"),
        @Index(name = "idx_le_bancs_customer", columnList = "bancs_customer_id"),
        @Index(name = "idx_le_hierarchy_node", columnList = "hierarchy_node_id"),
        @Index(name = "idx_le_program", columnList = "program_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LegalEntity extends BaseEntity {

    // ========================================================================
    // IDENTITY
    // ========================================================================

    @Column(name = "entity_code", nullable = false, unique = true, length = 20)
    private String entityCode;

    @Column(name = "entity_name", nullable = false, length = 200)
    private String entityName;

    @Column(name = "short_name", length = 50)
    private String shortName;

    // ========================================================================
    // HIERARCHY (self-referencing legal entity tree)
    // ========================================================================

    @Column(name = "parent_entity_id")
    private UUID parentEntityId;

    @Column(name = "hierarchy_path", length = 500)
    private String hierarchyPath;

    @Column(name = "hierarchy_level")
    @Builder.Default
    private Integer hierarchyLevel = 0;

    // ========================================================================
    // VA HIERARCHY INTEGRATION (Currency Mirror Support)
    // ========================================================================

    @Column(name = "hierarchy_node_id")
    private UUID hierarchyNodeId;

    @Column(name = "program_id")
    private UUID programId;

    @Column(name = "currency_mirrors_initialized")
    @Builder.Default
    private Boolean currencyMirrorsInitialized = false;

    /**
     * List of operating currencies for this entity.
     * 
     * FIXED v5.1.3: Using Hibernate 6 native JSON support.
     * Database stores as JSONB: ["EUR", "USD", "GBP"]
     * 
     * Key annotations:
     * - @JdbcTypeCode(SqlTypes.JSON) tells Hibernate to use JSON type
     * - @Column(columnDefinition = "jsonb") ensures PostgreSQL uses JSONB
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "operating_currencies", columnDefinition = "jsonb")
    @Builder.Default
    private List<String> operatingCurrencies = new ArrayList<>();

    // ========================================================================
    // OWNERSHIP & CONSOLIDATION
    // ========================================================================

    @Column(name = "ownership_percent", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal ownershipPercent = new BigDecimal("100.00");

    @Enumerated(EnumType.STRING)
    @Column(name = "consolidation_method", length = 20)
    @Builder.Default
    private ConsolidationMethod consolidationMethod = ConsolidationMethod.FULL;

    // ========================================================================
    // CLASSIFICATION
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 30)
    @Builder.Default
    private EntityType entityType = EntityType.SUBSIDIARY;

    @Column(name = "legal_form", length = 50)
    private String legalForm;

    // ========================================================================
    // JURISDICTION
    // ========================================================================

    @Column(name = "country_code", length = 2)
    private String countryCode;

    @Column(name = "jurisdiction", length = 100)
    private String jurisdiction;

    @Column(name = "tax_id", length = 50)
    private String taxId;

    @Column(name = "registration_number", length = 50)
    private String registrationNumber;

    // ========================================================================
    // CURRENCY
    // ========================================================================

    @Column(name = "functional_currency", nullable = false, length = 3)
    private String functionalCurrency;

    @Column(name = "reporting_currency", length = 3)
    private String reportingCurrency;

    // ========================================================================
    // BANK RELATIONSHIP
    // ========================================================================

    @Column(name = "is_bank_customer")
    @Builder.Default
    private Boolean isBankCustomer = false;

    @Column(name = "bancs_customer_id", length = 50, unique = true)
    private String bancsCustomerId;

    // ========================================================================
    // TREASURY CONFIGURATION
    // ========================================================================

    @Column(name = "is_treasury_center")
    @Builder.Default
    private Boolean isTreasuryCenter = false;

    @Column(name = "can_hold_physical_accounts")
    @Builder.Default
    private Boolean canHoldPhysicalAccounts = true;

    @Column(name = "can_participate_pooling")
    @Builder.Default
    private Boolean canParticipatePooling = true;

    @Column(name = "can_participate_netting")
    @Builder.Default
    private Boolean canParticipateNetting = true;

    // ========================================================================
    // INTERNAL CREDIT CONFIGURATION
    // ========================================================================

    @Column(name = "internal_credit_limit", precision = 19, scale = 4)
    private BigDecimal internalCreditLimit;

    @Column(name = "internal_limit_utilized", precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal internalLimitUtilized = BigDecimal.ZERO;

    @Column(name = "internal_limit_currency", length = 3)
    private String internalLimitCurrency;

    @Column(name = "limit_warning_threshold", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal limitWarningThreshold = new BigDecimal("80.00");

    // ========================================================================
    // STATUS & VALIDITY
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @Builder.Default
    private EntityStatus status = EntityStatus.ACTIVE;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    // ========================================================================
    // CORPORATE LINK
    // ========================================================================

    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    // ========================================================================
    // CONTACT INFORMATION
    // ========================================================================

    @Column(name = "contact_email", length = 100)
    private String contactEmail;

    @Column(name = "contact_phone", length = 30)
    private String contactPhone;

    @Column(name = "registered_address", length = 500)
    private String registeredAddress;

        // ========================================================================
    // IN-HOUSE BANKING (IHB) CONFIGURATION (NEW - Phase 2)
    // ========================================================================

    /**
     * Whether this entity participates in In-House Banking.
     */
    @Column(name = "ihb_enabled")
    @Builder.Default
    private Boolean ihbEnabled = false;

    /**
     * IHB credit limit - maximum borrowing allowed through IHB.
     * Set by Treasury/CFO, separate from internal credit limit.
     */
    @Column(name = "ihb_credit_limit", precision = 19, scale = 4)
    private BigDecimal ihbCreditLimit;

    /**
     * Current IHB exposure - total borrowed via IHB.
     */
    @Column(name = "ihb_current_exposure", precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal ihbCurrentExposure = BigDecimal.ZERO;

    /**
     * Available IHB limit (ihbCreditLimit - ihbCurrentExposure).
     */
    @Column(name = "ihb_available_limit", precision = 19, scale = 4)
    private BigDecimal ihbAvailableLimit;

    /**
     * Spread added to base rate when this entity LENDS to others.
     * Default: 0.50% (50 bps)
     */
    @Column(name = "lending_rate_spread", precision = 8, scale = 5)
    @Builder.Default
    private BigDecimal lendingRateSpread = new BigDecimal("0.50000");

    /**
     * Spread added to base rate when this entity BORROWS from others.
     * Default: 0.75% (75 bps)
     */
    @Column(name = "borrowing_rate_spread", precision = 8, scale = 5)
    @Builder.Default
    private BigDecimal borrowingRateSpread = new BigDecimal("0.75000");

    /**
     * Whether this entity can act as a LENDER in IHB transactions.
     * Usually TRUE for Treasury Centers and parent companies.
     */
    @Column(name = "can_lend")
    @Builder.Default
    private Boolean canLend = false;

    /**
     * Whether this entity can act as a BORROWER in IHB transactions.
     * Usually TRUE for subsidiaries.
     */
    @Column(name = "can_borrow")
    @Builder.Default
    private Boolean canBorrow = true;

    /**
     * Primary Virtual Account for IHB transaction settlements.
     * If null, auto-resolved from entity's operational VAs.
     */
    @Column(name = "settlement_va_id")
    private UUID settlementVaId;

    /**
     * Primary currency for IHB operations.
     * Defaults to functionalCurrency if not set.
     */
    @Column(name = "ihb_currency", length = 3)
    private String ihbCurrency;

    /**
     * Interest Configuration for IHB lending/borrowing rates.
     * Only applicable for Treasury Center entities (canLend=true).
     * 
     * This links the Treasury's IHB rates to a proper InterestConfiguration
     * instead of using hardcoded base rates.
     * 
     * The config should be INTERNAL type with:
     * - effectiveCreditRate = deposit rate (what Treasury pays depositors)
     * - effectiveDebitRate = lending rate (what borrowers pay Treasury)
     */
    @Column(name = "ihb_interest_config_id")
    private UUID ihbInterestConfigId;

    /**
     * Total amount currently lent to other entities.
     */
    @Column(name = "total_lent_out", precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal totalLentOut = BigDecimal.ZERO;

    /**
     * Total deposits placed with treasury.
     */
    @Column(name = "total_deposited", precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal totalDeposited = BigDecimal.ZERO;

    /**
     * Net IHB position: lent + deposited - borrowed.
     * Positive = net lender, Negative = net borrower
     */
    @Column(name = "net_ihb_position", precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal netIhbPosition = BigDecimal.ZERO;

    // ========================================================================
    // ENUMS
    // ========================================================================

    public enum EntityType {
        HOLDING, SUBSIDIARY, BRANCH, REPRESENTATIVE, 
        JOINT_VENTURE, ASSOCIATE, SPV, TREASURY_CENTER
    }

    public enum ConsolidationMethod {
        FULL, PROPORTIONAL, EQUITY, NONE
    }

    public enum EntityStatus {
        ACTIVE, INACTIVE, SUSPENDED, PENDING_APPROVAL, CLOSED
    }

    // ========================================================================
    // HELPER METHODS - Status
    // ========================================================================

    public boolean isActive() { return status == EntityStatus.ACTIVE; }
    public boolean isInactive() { return status == EntityStatus.INACTIVE; }
    public boolean isSuspended() { return status == EntityStatus.SUSPENDED; }
    public boolean isClosed() { return status == EntityStatus.CLOSED; }

    // ========================================================================
    // HELPER METHODS - Bank Customer
    // ========================================================================

    public boolean isBankCustomer() { return Boolean.TRUE.equals(isBankCustomer); }
    
    public boolean hasBancsCustomerId() { 
        return bancsCustomerId != null && !bancsCustomerId.trim().isEmpty(); 
    }
    
    public boolean canHaveExternalLimits() { 
        return isBankCustomer() && isActive(); 
    }
    
    public boolean isInternalLimitOnly() { 
        return !isBankCustomer(); 
    }
    
    public String getBancsCustomerIdOrNull() { 
        return isBankCustomer() ? bancsCustomerId : null; 
    }

    // ========================================================================
    // HELPER METHODS - Hierarchy
    // ========================================================================

    public boolean isRoot() { return parentEntityId == null; }
    public boolean isHolding() { return entityType == EntityType.HOLDING; }
    public boolean isSubsidiary() { return entityType == EntityType.SUBSIDIARY; }
    public boolean isBranch() { return entityType == EntityType.BRANCH; }
    public boolean isTreasury() { 
        return Boolean.TRUE.equals(isTreasuryCenter) || entityType == EntityType.TREASURY_CENTER; 
    }

    public static String buildHierarchyPath(String parentPath, String entityCode) {
        if (parentPath == null || parentPath.isEmpty()) return "/" + entityCode;
        return parentPath + "/" + entityCode;
    }

    public String getParentPath() {
        if (hierarchyPath == null || hierarchyPath.isEmpty()) return "";
        int lastSlash = hierarchyPath.lastIndexOf('/');
        return lastSlash > 0 ? hierarchyPath.substring(0, lastSlash) : "";
    }

    // ========================================================================
    // HELPER METHODS - Currency Mirror Integration
    // ========================================================================

    public boolean hasHierarchyNode() { return hierarchyNodeId != null; }
    public boolean hasProgram() { return programId != null; }
    public boolean isReadyForCurrencyMirrors() { return hasHierarchyNode() && hasProgram() && isActive(); }
    public boolean hasCurrencyMirrorsInitialized() { return Boolean.TRUE.equals(currencyMirrorsInitialized); }
    public void markCurrencyMirrorsInitialized() { this.currencyMirrorsInitialized = true; }

    // ========================================================================
    // HELPER METHODS - Operating Currencies
    // ========================================================================

    public void addOperatingCurrency(String currency) {
        if (operatingCurrencies == null) operatingCurrencies = new ArrayList<>();
        if (currency != null && !operatingCurrencies.contains(currency)) {
            operatingCurrencies.add(currency);
        }
    }

    public void removeOperatingCurrency(String currency) {
        if (operatingCurrencies != null) operatingCurrencies.remove(currency);
    }

    public boolean operatesInCurrency(String currency) {
        if (currency == null) return false;
        if (currency.equals(functionalCurrency)) return true;
        return operatingCurrencies != null && operatingCurrencies.contains(currency);
    }

    public List<String> getAllCurrencies() {
        List<String> all = new ArrayList<>();
        if (functionalCurrency != null) all.add(functionalCurrency);
        if (operatingCurrencies != null) {
            for (String c : operatingCurrencies) {
                if (!all.contains(c)) all.add(c);
            }
        }
        return all;
    }

    // ========================================================================
    // HELPER METHODS - Credit Limit
    // ========================================================================

    public boolean hasInternalLimit() {
        return internalCreditLimit != null && internalCreditLimit.compareTo(BigDecimal.ZERO) > 0;
    }

    public BigDecimal getAvailableInternalLimit() {
        if (internalCreditLimit == null) return BigDecimal.ZERO;
        BigDecimal utilized = internalLimitUtilized != null ? internalLimitUtilized : BigDecimal.ZERO;
        return internalCreditLimit.subtract(utilized);
    }

    public BigDecimal getUtilizationPercent() {
        if (internalCreditLimit == null || internalCreditLimit.compareTo(BigDecimal.ZERO) == 0) 
            return BigDecimal.ZERO;
        BigDecimal utilized = internalLimitUtilized != null ? internalLimitUtilized : BigDecimal.ZERO;
        return utilized.multiply(new BigDecimal("100"))
            .divide(internalCreditLimit, 2, java.math.RoundingMode.HALF_UP);
    }

    public boolean isLimitWarning() {
        BigDecimal threshold = limitWarningThreshold != null ? limitWarningThreshold : new BigDecimal("80.00");
        return getUtilizationPercent().compareTo(threshold) >= 0;
    }

    public boolean isLimitBreached() {
        return getUtilizationPercent().compareTo(new BigDecimal("100")) >= 0;
    }

    public void utilizeLimit(BigDecimal amount) {
        if (internalLimitUtilized == null) internalLimitUtilized = BigDecimal.ZERO;
        internalLimitUtilized = internalLimitUtilized.add(amount);
    }

    public void releaseLimit(BigDecimal amount) {
        if (internalLimitUtilized == null) internalLimitUtilized = BigDecimal.ZERO;
        internalLimitUtilized = internalLimitUtilized.subtract(amount);
        if (internalLimitUtilized.compareTo(BigDecimal.ZERO) < 0) internalLimitUtilized = BigDecimal.ZERO;
    }

    // ========================================================================
    // HELPER METHODS - Consolidation & Validity
    // ========================================================================

    public boolean isFullyConsolidated() { return consolidationMethod == ConsolidationMethod.FULL; }
    
    public boolean isMajorityOwned() {
        return ownershipPercent != null && ownershipPercent.compareTo(new BigDecimal("50")) > 0;
    }

    public boolean isAssociateOwned() {
        if (ownershipPercent == null) return false;
        return ownershipPercent.compareTo(new BigDecimal("20")) >= 0 
            && ownershipPercent.compareTo(new BigDecimal("50")) <= 0;
    }

    public boolean isCurrentlyValid() {
        LocalDate today = LocalDate.now();
        if (effectiveFrom != null && today.isBefore(effectiveFrom)) return false;
        if (effectiveTo != null && today.isAfter(effectiveTo)) return false;
        return true;
    }

        // ========================================================================
    // HELPER METHODS - IHB
    // ========================================================================

    public boolean isIhbEnabled() {
        return Boolean.TRUE.equals(ihbEnabled);
    }

    public boolean canLend() {
        return isIhbEnabled() && Boolean.TRUE.equals(canLend) && isActive();
    }

    public boolean canBorrow() {
        return isIhbEnabled() && Boolean.TRUE.equals(canBorrow) && isActive();
    }

    public boolean hasIhbLimit() {
        return ihbCreditLimit != null && ihbCreditLimit.compareTo(BigDecimal.ZERO) > 0;
    }

    public BigDecimal getAvailableIhbLimit() {
        if (ihbCreditLimit == null) return BigDecimal.ZERO;
        BigDecimal exposure = ihbCurrentExposure != null ? ihbCurrentExposure : BigDecimal.ZERO;
        return ihbCreditLimit.subtract(exposure).max(BigDecimal.ZERO);
    }

    public BigDecimal getIhbUtilizationPercent() {
        if (ihbCreditLimit == null || ihbCreditLimit.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal exposure = ihbCurrentExposure != null ? ihbCurrentExposure : BigDecimal.ZERO;
        return exposure.multiply(new BigDecimal("100"))
            .divide(ihbCreditLimit, 2, java.math.RoundingMode.HALF_UP);
    }

    public boolean isIhbLimitWarning() {
        return getIhbUtilizationPercent().compareTo(new BigDecimal("80")) >= 0;
    }

    public boolean isIhbLimitBreached() {
        return getIhbUtilizationPercent().compareTo(new BigDecimal("100")) >= 0;
    }

    public boolean canBorrowAmount(BigDecimal amount) {
        if (!canBorrow() || amount == null) return false;
        if (!hasIhbLimit()) return true; // Unlimited if no limit set
        return getAvailableIhbLimit().compareTo(amount) >= 0;
    }

    public void utilizeIhbLimit(BigDecimal amount) {
        if (ihbCurrentExposure == null) ihbCurrentExposure = BigDecimal.ZERO;
        ihbCurrentExposure = ihbCurrentExposure.add(amount);
        ihbAvailableLimit = getAvailableIhbLimit();
        updateNetIhbPosition();
    }

    public void releaseIhbLimit(BigDecimal amount) {
        if (ihbCurrentExposure == null) ihbCurrentExposure = BigDecimal.ZERO;
        ihbCurrentExposure = ihbCurrentExposure.subtract(amount);
        if (ihbCurrentExposure.compareTo(BigDecimal.ZERO) < 0) {
            ihbCurrentExposure = BigDecimal.ZERO;
        }
        ihbAvailableLimit = getAvailableIhbLimit();
        updateNetIhbPosition();
    }

    public void addLentAmount(BigDecimal amount) {
        if (totalLentOut == null) totalLentOut = BigDecimal.ZERO;
        totalLentOut = totalLentOut.add(amount);
        updateNetIhbPosition();
    }

    public void reduceLentAmount(BigDecimal amount) {
        if (totalLentOut == null) totalLentOut = BigDecimal.ZERO;
        totalLentOut = totalLentOut.subtract(amount);
        if (totalLentOut.compareTo(BigDecimal.ZERO) < 0) {
            totalLentOut = BigDecimal.ZERO;
        }
        updateNetIhbPosition();
    }

    public void addDepositedAmount(BigDecimal amount) {
        if (totalDeposited == null) totalDeposited = BigDecimal.ZERO;
        totalDeposited = totalDeposited.add(amount);
        updateNetIhbPosition();
    }

    public void reduceDepositedAmount(BigDecimal amount) {
        if (totalDeposited == null) totalDeposited = BigDecimal.ZERO;
        totalDeposited = totalDeposited.subtract(amount);
        if (totalDeposited.compareTo(BigDecimal.ZERO) < 0) {
            totalDeposited = BigDecimal.ZERO;
        }
        updateNetIhbPosition();
    }

    private void updateNetIhbPosition() {
        BigDecimal lent = totalLentOut != null ? totalLentOut : BigDecimal.ZERO;
        BigDecimal deposited = totalDeposited != null ? totalDeposited : BigDecimal.ZERO;
        BigDecimal borrowed = ihbCurrentExposure != null ? ihbCurrentExposure : BigDecimal.ZERO;
        netIhbPosition = lent.add(deposited).subtract(borrowed);
    }

    public String getEffectiveIhbCurrency() {
        return ihbCurrency != null ? ihbCurrency : functionalCurrency;
    }


    /**
     * Check if this Treasury Center has an IHB interest config attached.
     */
    public boolean hasIhbInterestConfig() {
        return ihbInterestConfigId != null;
    }

    /**
     * Check if entity is a valid Treasury Center with rates configured.
     */
    public boolean isTreasuryCenterWithRates() {
        return canLend() && hasIhbInterestConfig();
    }

    /**
     * Get the IHB interest configuration ID.
     */
    public UUID getIhbInterestConfigId() {
        return ihbInterestConfigId;
    }

    /**
     * Set the IHB interest configuration ID.
     */
    public void setIhbInterestConfigId(UUID ihbInterestConfigId) {
        this.ihbInterestConfigId = ihbInterestConfigId;
    }

    public boolean hasSettlementVa() {
        return settlementVaId != null;
    }


    // ========================================================================
    // FACTORY METHODS
    // ========================================================================

    public static LegalEntity createHolding(UUID corporateId, String entityCode, 
                                             String entityName, String functionalCurrency,
                                             boolean isBankCustomer, String bancsCustomerId) {
        return LegalEntity.builder()
            .corporateId(corporateId).entityCode(entityCode).entityName(entityName)
            .entityType(EntityType.HOLDING).functionalCurrency(functionalCurrency)
            .reportingCurrency(functionalCurrency).hierarchyLevel(0)
            .hierarchyPath("/" + entityCode).ownershipPercent(new BigDecimal("100.00"))
            .consolidationMethod(ConsolidationMethod.FULL).isBankCustomer(isBankCustomer)
            .bancsCustomerId(isBankCustomer ? bancsCustomerId : null)
            .canHoldPhysicalAccounts(isBankCustomer)
            .canParticipatePooling(true).canParticipateNetting(true)
            .status(EntityStatus.ACTIVE).effectiveFrom(LocalDate.now())
            .currencyMirrorsInitialized(false)
            .operatingCurrencies(new ArrayList<>())
            .build();
    }

    @Deprecated
    public static LegalEntity createHolding(UUID corporateId, String entityCode, 
                                             String entityName, String functionalCurrency) {
        return createHolding(corporateId, entityCode, entityName, functionalCurrency, false, null);
    }

    public static LegalEntity createSubsidiary(UUID corporateId, UUID parentEntityId,
                                                String parentPath, int level, String entityCode, 
                                                String entityName, String functionalCurrency, 
                                                BigDecimal ownershipPercent, boolean isBankCustomer, 
                                                String bancsCustomerId) {
        return LegalEntity.builder()
            .corporateId(corporateId).parentEntityId(parentEntityId).entityCode(entityCode)
            .entityName(entityName).entityType(EntityType.SUBSIDIARY).functionalCurrency(functionalCurrency)
            .reportingCurrency(functionalCurrency).hierarchyLevel(level)
            .hierarchyPath(buildHierarchyPath(parentPath, entityCode)).ownershipPercent(ownershipPercent)
            .consolidationMethod(ownershipPercent.compareTo(new BigDecimal("50")) > 0 
                ? ConsolidationMethod.FULL : ConsolidationMethod.EQUITY)
            .isBankCustomer(isBankCustomer).bancsCustomerId(isBankCustomer ? bancsCustomerId : null)
            .canHoldPhysicalAccounts(isBankCustomer).canParticipatePooling(true).canParticipateNetting(true)
            .status(EntityStatus.ACTIVE).effectiveFrom(LocalDate.now())
            .currencyMirrorsInitialized(false)
            .operatingCurrencies(new ArrayList<>())
            .build();
    }

    @Deprecated
    public static LegalEntity createSubsidiary(UUID corporateId, UUID parentEntityId, String parentPath, 
                                                int level, String entityCode, String entityName, 
                                                String functionalCurrency, BigDecimal ownershipPercent) {
        return createSubsidiary(corporateId, parentEntityId, parentPath, level, entityCode, entityName, 
                                functionalCurrency, ownershipPercent, false, null);
    }

    public static LegalEntity createTreasuryCenter(UUID corporateId, UUID parentEntityId, String parentPath, 
                                                    int level, String entityCode, String entityName, 
                                                    String functionalCurrency, boolean isBankCustomer, 
                                                    String bancsCustomerId) {
        return LegalEntity.builder()
            .corporateId(corporateId).parentEntityId(parentEntityId).entityCode(entityCode)
            .entityName(entityName).entityType(EntityType.TREASURY_CENTER).functionalCurrency(functionalCurrency)
            .reportingCurrency(functionalCurrency).hierarchyLevel(level)
            .hierarchyPath(buildHierarchyPath(parentPath, entityCode)).ownershipPercent(new BigDecimal("100.00"))
            .consolidationMethod(ConsolidationMethod.FULL).isTreasuryCenter(true).isBankCustomer(isBankCustomer)
            .bancsCustomerId(isBankCustomer ? bancsCustomerId : null).canHoldPhysicalAccounts(isBankCustomer)
            .canParticipatePooling(true).canParticipateNetting(true)
            .status(EntityStatus.ACTIVE).effectiveFrom(LocalDate.now())
            .currencyMirrorsInitialized(false)
            .operatingCurrencies(new ArrayList<>())
            .build();
    }

    @Deprecated
    public static LegalEntity createTreasuryCenter(UUID corporateId, UUID parentEntityId, String parentPath, 
                                                    int level, String entityCode, String entityName, 
                                                    String functionalCurrency) {
        return createTreasuryCenter(corporateId, parentEntityId, parentPath, level, entityCode, 
                                    entityName, functionalCurrency, false, null);
    }

        /**
     * Enable IHB for this entity.
     * 
     * @param creditLimit Maximum borrowing limit
     * @param canLend Whether entity can lend
     * @param canBorrow Whether entity can borrow
     * @param lendingSpread Spread for lending (default 0.50%)
     * @param borrowingSpread Spread for borrowing (default 0.75%)
     */
    public void enableIhb(BigDecimal creditLimit, boolean canLend, boolean canBorrow,
                          BigDecimal lendingSpread, BigDecimal borrowingSpread) {
        this.ihbEnabled = true;
        this.ihbCreditLimit = creditLimit;
        this.ihbAvailableLimit = creditLimit;
        this.ihbCurrentExposure = BigDecimal.ZERO;
        this.canLend = canLend;
        this.canBorrow = canBorrow;
        this.lendingRateSpread = lendingSpread != null ? lendingSpread : new BigDecimal("0.50000");
        this.borrowingRateSpread = borrowingSpread != null ? borrowingSpread : new BigDecimal("0.75000");
        this.ihbCurrency = this.functionalCurrency;
        this.totalLentOut = BigDecimal.ZERO;
        this.totalDeposited = BigDecimal.ZERO;
        this.netIhbPosition = BigDecimal.ZERO;
    }

    /**
     * Enable IHB with default settings.
     */
    public void enableIhb(BigDecimal creditLimit) {
        enableIhb(creditLimit, false, true, null, null);
    }

    /**
     * Enable IHB for a Treasury Center (can lend, typically no borrowing limit).
     */
    public void enableIhbAsTreasuryCenter() {
        this.ihbEnabled = true;
        this.canLend = true;
        this.canBorrow = false;
        this.lendingRateSpread = new BigDecimal("0.25000"); // Lower spread for treasury
        this.borrowingRateSpread = BigDecimal.ZERO;
        this.ihbCurrency = this.functionalCurrency;
        this.totalLentOut = BigDecimal.ZERO;
        this.totalDeposited = BigDecimal.ZERO;
        this.netIhbPosition = BigDecimal.ZERO;
    }

    /**
     * Disable IHB for this entity.
     */
    public void disableIhb() {
        this.ihbEnabled = false;
        // Keep historical data but disable future operations
    }

}