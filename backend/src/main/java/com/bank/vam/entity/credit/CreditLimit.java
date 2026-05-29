package com.bank.vam.entity.credit;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Credit Limit - Allocated credit limits on accounts/entities.
 * 
 * UNIFIED ARCHITECTURE v4.2:
 * Credit Limits represent the two-layer limit model:
 * 
 * 1. EXTERNAL limits - From Core Banking System (CBS)
 *    - Linked to CreditFacility
 *    - Applied to PHYSICAL_MIRROR (shadow) accounts
 *    - Synced from CBS
 *    - Only for entities that are BANK CUSTOMERS
 * 
 * 2. INTERNAL limits - Allocated by CFO/Treasury
 *    - Not linked to CreditFacility
 *    - Applied to VirtualAccounts or LegalEntities
 *    - Managed internally
 *    - Available for ALL entities (bank customers and non-bank-customers)
 * 
 * Group Limit Hierarchy:
 * - Group Limit (targetType=CORPORATE, parentLimitId=null)
 *   └── Entity Limit (targetType=LEGAL_ENTITY, parentLimitId=groupLimitId)
 * 
 * Target Types:
 * - VIRTUAL_ACCOUNT: Limit on a specific VA
 * - LEGAL_ENTITY: Limit on an entity (aggregated for all VAs)
 * - AGGREGATION_NODE: Limit on an aggregation point
 * - SHADOW_ACCOUNT: External limit on physical mirror
 * - ROOT: Global limit on root node
 * - CORPORATE: Group-wide limit on corporate
 * 
 * Domain Model:
 * - CreditFacility (1) -> CreditLimit (N) [external limits]
 * - CreditLimit (1) -> CreditLimit (N) [parent-child internal limits]
 * - VirtualAccount.externalLimitId -> CreditLimit.id
 * - VirtualAccount.internalLimitId -> CreditLimit.id
 * - LegalEntity.internalCreditLimit mirrors CreditLimit
 */
@Entity
@Table(name = "credit_limits",
    indexes = {
        @Index(name = "idx_cl_corporate", columnList = "corporate_id"),
        @Index(name = "idx_cl_facility", columnList = "credit_facility_id"),
        @Index(name = "idx_cl_target", columnList = "target_id"),
        @Index(name = "idx_cl_target_type", columnList = "target_type"),
        @Index(name = "idx_cl_limit_type", columnList = "limit_type"),
        @Index(name = "idx_cl_status", columnList = "status"),
        @Index(name = "idx_cl_currency", columnList = "limit_currency"),
        @Index(name = "idx_cl_parent_limit", columnList = "parent_limit_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditLimit extends BaseEntity {

    // ========================================================================
    // IDENTITY
    // ========================================================================

    /**
     * Corporate that owns this limit.
     */
    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    /**
     * Name/description of limit.
     */
    @Column(name = "limit_name", length = 200)
    private String limitName;

    // ========================================================================
    // LIMIT CLASSIFICATION
    // ========================================================================

    /**
     * Type of limit: EXTERNAL (from CBS) or INTERNAL (from CFO).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "limit_type", nullable = false, length = 20)
    private LimitType limitType;

    /**
     * What this limit is applied to.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 30)
    private TargetType targetType;

    /**
     * ID of the target (VA, Entity, Corporate, etc.).
     */
    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    // ========================================================================
    // SOURCE (for EXTERNAL limits)
    // ========================================================================

    /**
     * Source credit facility (for EXTERNAL limits).
     */
    @Column(name = "credit_facility_id")
    private UUID creditFacilityId;

    /**
     * External reference from CBS.
     */
    @Column(name = "external_reference", length = 50)
    private String externalReference;

    // ========================================================================
    // HIERARCHY (for Group → Entity limits)
    // ========================================================================

    /**
     * Parent limit ID for hierarchical limits.
     * 
     * Group → Entity hierarchy:
     * - Group Limit (targetType=CORPORATE, parentLimitId=null)
     *   └── Entity Limit (targetType=LEGAL_ENTITY, parentLimitId=groupLimitId)
     * 
     * Sum of child limits cannot exceed parent limit.
     */
    @Column(name = "parent_limit_id")
    private UUID parentLimitId;

    /**
     * Amount allocated from this limit to child limits.
     * Only applicable for GROUP limits.
     * allocatedToChildren ≤ limitAmount
     */
    @Column(name = "allocated_to_children", precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal allocatedToChildren = BigDecimal.ZERO;

    // ========================================================================
    // LIMIT VALUES
    // ========================================================================

    /**
     * Sanctioned/approved limit amount.
     */
    @Column(name = "limit_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal limitAmount;

    /**
     * Currency of the limit.
     */
    @Column(name = "limit_currency", nullable = false, length = 3)
    private String limitCurrency;

    /**
     * Current utilization.
     */
    @Column(name = "utilized_amount", precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal utilizedAmount = BigDecimal.ZERO;

    /**
     * Available limit (limit - utilized).
     */
    @Column(name = "available_amount", precision = 19, scale = 4)
    private BigDecimal availableAmount;

    /**
     * Held/blocked amount (pending utilization).
     */
    @Column(name = "held_amount", precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal heldAmount = BigDecimal.ZERO;

    // ========================================================================
    // LIMIT CONTROL (hard/soft, approval)
    // ========================================================================

    /**
     * Whether this is a hard limit.
     * 
     * Hard limit (true): Transactions exceeding limit are BLOCKED
     * Soft limit (false): Only WARNING is raised, transaction proceeds
     */
    @Column(name = "is_hard_limit")
    @Builder.Default
    private Boolean isHardLimit = true;

    /**
     * Whether transactions above threshold require approval.
     */
    @Column(name = "requires_approval")
    @Builder.Default
    private Boolean requiresApproval = false;

    /**
     * Utilization percentage that triggers approval requirement.
     * Example: 80 means approval needed when utilization > 80%
     */
    @Column(name = "approval_threshold_percent", precision = 5, scale = 2)
    private BigDecimal approvalThresholdPercent;

    // ========================================================================
    // THRESHOLDS
    // ========================================================================

    /**
     * Warning threshold percentage.
     */
    @Column(name = "warning_threshold_percent", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal warningThresholdPercent = new BigDecimal("80.00");

    /**
     * Critical threshold percentage.
     */
    @Column(name = "critical_threshold_percent", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal criticalThresholdPercent = new BigDecimal("95.00");

    // ========================================================================
    // VALIDITY
    // ========================================================================

    /**
     * When limit becomes effective.
     */
    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    /**
     * When limit expires.
     */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    // ========================================================================
    // STATUS
    // ========================================================================

    /**
     * Limit status.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @Builder.Default
    private LimitStatus status = LimitStatus.ACTIVE;

    // ========================================================================
    // APPROVAL
    // ========================================================================

    /**
     * Who approved this limit.
     */
    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    /**
     * When approved.
     */
    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    /**
     * Approval notes.
     */
    @Column(name = "approval_notes", length = 500)
    private String approvalNotes;

    // ========================================================================
    // CBS SYNC (for EXTERNAL limits)
    // ========================================================================

    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    @Column(name = "source_system", length = 50)
    private String sourceSystem;

    // ========================================================================
    // NOTES
    // ========================================================================

    @Column(name = "notes", length = 1000)
    private String notes;

    // ========================================================================
    // ENUMS
    // ========================================================================

    /**
     * Type of limit source.
     */
    public enum LimitType {
        EXTERNAL,   // From CBS (bank-provided)
        INTERNAL    // From CFO (internally allocated)
    }

    /**
     * What the limit is applied to.
     */
    public enum TargetType {
        VIRTUAL_ACCOUNT,    // Limit on a specific VA
        LEGAL_ENTITY,       // Limit on an entity
        AGGREGATION_NODE,   // Limit on an aggregation point
        SHADOW_ACCOUNT,     // Limit on physical mirror
        ROOT,               // Global limit on root node
        CORPORATE           // Group-wide limit on corporate
    }

    /**
     * Limit status.
     */
    public enum LimitStatus {
        DRAFT,
        PENDING_APPROVAL,
        ACTIVE,
        SUSPENDED,
        EXPIRED,
        CANCELLED,
        BREACHED
    }

    // ========================================================================
    // HELPER METHODS - Status
    // ========================================================================

    public boolean isActive() {
        return status == LimitStatus.ACTIVE;
    }

    public boolean isExternal() {
        return limitType == LimitType.EXTERNAL;
    }

    public boolean isInternal() {
        return limitType == LimitType.INTERNAL;
    }

    public boolean isExpired() {
        if (effectiveTo == null) return false;
        return LocalDate.now().isAfter(effectiveTo);
    }

    public boolean isCurrentlyValid() {
        LocalDate today = LocalDate.now();
        if (effectiveFrom != null && today.isBefore(effectiveFrom)) {
            return false;
        }
        if (effectiveTo != null && today.isAfter(effectiveTo)) {
            return false;
        }
        return true;
    }

    // ========================================================================
    // HELPER METHODS - Hierarchy
    // ========================================================================

    /**
     * Check if this is a group-level limit.
     */
    public boolean isGroupLimit() {
        return targetType == TargetType.CORPORATE && parentLimitId == null;
    }

    /**
     * Check if this limit has a parent (is a sub-limit).
     */
    public boolean hasParentLimit() {
        return parentLimitId != null;
    }

    /**
     * Check if this is a hard limit (blocks transactions).
     */
    public boolean isHard() {
        return Boolean.TRUE.equals(isHardLimit);
    }

    /**
     * Check if this is a soft limit (warning only).
     */
    public boolean isSoft() {
        return !isHard();
    }

    /**
     * Check if approval is required based on current utilization.
     */
    public boolean needsApproval() {
        if (!Boolean.TRUE.equals(requiresApproval)) {
            return false;
        }
        BigDecimal threshold = approvalThresholdPercent != null 
            ? approvalThresholdPercent 
            : new BigDecimal("80");
        return getUtilizationPercent().compareTo(threshold) >= 0;
    }

    /**
     * Get unallocated amount (for group limits).
     */
    public BigDecimal getUnallocatedAmount() {
        if (limitAmount == null) return BigDecimal.ZERO;
        BigDecimal allocated = allocatedToChildren != null ? allocatedToChildren : BigDecimal.ZERO;
        return limitAmount.subtract(allocated);
    }

    /**
     * Allocate to child limit.
     */
    public void allocateToChild(BigDecimal amount) {
        if (allocatedToChildren == null) allocatedToChildren = BigDecimal.ZERO;
        BigDecimal newAllocation = allocatedToChildren.add(amount);
        if (newAllocation.compareTo(limitAmount) > 0) {
            throw new IllegalArgumentException("Allocation would exceed limit amount");
        }
        allocatedToChildren = newAllocation;
    }

    /**
     * Release child allocation.
     */
    public void releaseChildAllocation(BigDecimal amount) {
        if (allocatedToChildren == null) allocatedToChildren = BigDecimal.ZERO;
        allocatedToChildren = allocatedToChildren.subtract(amount);
        if (allocatedToChildren.compareTo(BigDecimal.ZERO) < 0) {
            allocatedToChildren = BigDecimal.ZERO;
        }
    }

    // ========================================================================
    // HELPER METHODS - Utilization
    // ========================================================================

    public BigDecimal getUtilizationPercent() {
        if (limitAmount == null || limitAmount.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal utilized = utilizedAmount != null ? utilizedAmount : BigDecimal.ZERO;
        return utilized.multiply(new BigDecimal("100"))
            .divide(limitAmount, 2, java.math.RoundingMode.HALF_UP);
    }

    public boolean isAtWarningLevel() {
        return getUtilizationPercent().compareTo(warningThresholdPercent) >= 0;
    }

    public boolean isAtCriticalLevel() {
        return getUtilizationPercent().compareTo(criticalThresholdPercent) >= 0;
    }

    public boolean isBreached() {
        return getUtilizationPercent().compareTo(new BigDecimal("100")) >= 0;
    }

    public void recalculateAvailable() {
        if (limitAmount != null) {
            BigDecimal utilized = utilizedAmount != null ? utilizedAmount : BigDecimal.ZERO;
            BigDecimal held = heldAmount != null ? heldAmount : BigDecimal.ZERO;
            this.availableAmount = limitAmount.subtract(utilized).subtract(held);
        }
    }

    // ========================================================================
    // HELPER METHODS - Operations
    // ========================================================================

    /**
     * Check if amount can be utilized.
     */
    public boolean canUtilize(BigDecimal amount) {
        recalculateAvailable();
        return availableAmount != null && availableAmount.compareTo(amount) >= 0;
    }

    /**
     * Utilize limit.
     */
    public void utilize(BigDecimal amount) {
        if (utilizedAmount == null) utilizedAmount = BigDecimal.ZERO;
        utilizedAmount = utilizedAmount.add(amount);
        recalculateAvailable();
        
        if (isBreached()) {
            status = LimitStatus.BREACHED;
        }
    }

    /**
     * Release utilized limit.
     */
    public void release(BigDecimal amount) {
        if (utilizedAmount == null) utilizedAmount = BigDecimal.ZERO;
        utilizedAmount = utilizedAmount.subtract(amount);
        if (utilizedAmount.compareTo(BigDecimal.ZERO) < 0) {
            utilizedAmount = BigDecimal.ZERO;
        }
        recalculateAvailable();
        
        if (status == LimitStatus.BREACHED && !isBreached()) {
            status = LimitStatus.ACTIVE;
        }
    }

    /**
     * Hold limit (pending utilization).
     */
    public void hold(BigDecimal amount) {
        if (heldAmount == null) heldAmount = BigDecimal.ZERO;
        heldAmount = heldAmount.add(amount);
        recalculateAvailable();
    }

    /**
     * Release hold.
     */
    public void releaseHold(BigDecimal amount) {
        if (heldAmount == null) heldAmount = BigDecimal.ZERO;
        heldAmount = heldAmount.subtract(amount);
        if (heldAmount.compareTo(BigDecimal.ZERO) < 0) {
            heldAmount = BigDecimal.ZERO;
        }
        recalculateAvailable();
    }

    /**
     * Convert hold to utilization (when transaction settles).
     */
    public void settleHold(BigDecimal amount) {
        releaseHold(amount);
        utilize(amount);
    }

    // ========================================================================
    // LIFECYCLE
    // ========================================================================

    @PrePersist
    @PreUpdate
    private void preSave() {
        recalculateAvailable();
    }

    // ========================================================================
    // BUILDER FACTORY METHODS
    // ========================================================================

    /**
     * Create an external limit (from CBS).
     */
    public static CreditLimit createExternalLimit(UUID corporateId, UUID targetId,
                                                   TargetType targetType, UUID facilityId,
                                                   BigDecimal amount, String currency) {
        CreditLimit limit = CreditLimit.builder()
            .corporateId(corporateId)
            .limitType(LimitType.EXTERNAL)
            .targetType(targetType)
            .targetId(targetId)
            .creditFacilityId(facilityId)
            .limitAmount(amount)
            .limitCurrency(currency)
            .isHardLimit(true)
            .status(LimitStatus.ACTIVE)
            .effectiveFrom(LocalDate.now())
            .build();
        limit.recalculateAvailable();
        return limit;
    }

    /**
     * Create an internal limit (from CFO).
     */
    public static CreditLimit createInternalLimit(UUID corporateId, UUID targetId,
                                                   TargetType targetType, String limitName,
                                                   BigDecimal amount, String currency,
                                                   String approvedBy) {
        CreditLimit limit = CreditLimit.builder()
            .corporateId(corporateId)
            .limitType(LimitType.INTERNAL)
            .targetType(targetType)
            .targetId(targetId)
            .limitName(limitName)
            .limitAmount(amount)
            .limitCurrency(currency)
            .isHardLimit(true)
            .approvedBy(approvedBy)
            .approvedAt(LocalDateTime.now())
            .status(LimitStatus.ACTIVE)
            .effectiveFrom(LocalDate.now())
            .build();
        limit.recalculateAvailable();
        return limit;
    }

    /**
     * Create a group-level internal limit (corporate-wide).
     */
    public static CreditLimit createGroupLimit(UUID corporateId, String limitName,
                                                BigDecimal amount, String currency,
                                                String approvedBy, boolean isHardLimit) {
        CreditLimit limit = CreditLimit.builder()
            .corporateId(corporateId)
            .limitType(LimitType.INTERNAL)
            .targetType(TargetType.CORPORATE)
            .targetId(corporateId)  // Target is the corporate itself
            .limitName(limitName)
            .limitAmount(amount)
            .limitCurrency(currency)
            .isHardLimit(isHardLimit)
            .approvedBy(approvedBy)
            .approvedAt(LocalDateTime.now())
            .status(LimitStatus.ACTIVE)
            .effectiveFrom(LocalDate.now())
            .build();
        limit.recalculateAvailable();
        return limit;
    }

    /**
     * Create an entity-level internal limit (sub-limit under group).
     */
    public static CreditLimit createEntitySubLimit(UUID corporateId, UUID entityId,
                                                    UUID parentLimitId, String limitName,
                                                    BigDecimal amount, String currency,
                                                    String approvedBy, boolean isHardLimit,
                                                    boolean requiresApproval, 
                                                    BigDecimal approvalThreshold) {
        CreditLimit limit = CreditLimit.builder()
            .corporateId(corporateId)
            .limitType(LimitType.INTERNAL)
            .targetType(TargetType.LEGAL_ENTITY)
            .targetId(entityId)
            .parentLimitId(parentLimitId)
            .limitName(limitName)
            .limitAmount(amount)
            .limitCurrency(currency)
            .isHardLimit(isHardLimit)
            .requiresApproval(requiresApproval)
            .approvalThresholdPercent(approvalThreshold)
            .approvedBy(approvedBy)
            .approvedAt(LocalDateTime.now())
            .status(LimitStatus.ACTIVE)
            .effectiveFrom(LocalDate.now())
            .build();
        limit.recalculateAvailable();
        return limit;
    }
}