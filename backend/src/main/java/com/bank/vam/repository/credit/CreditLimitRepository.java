package com.bank.vam.repository.credit;

import com.bank.vam.entity.credit.CreditLimit;
import com.bank.vam.entity.credit.CreditLimit.LimitStatus;
import com.bank.vam.entity.credit.CreditLimit.LimitType;
import com.bank.vam.entity.credit.CreditLimit.TargetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for CreditLimit operations.
 * 
 * UPDATED v5.2.0:
 * - FundsAvailabilityService integration
 * - Hierarchy Operations support (Move, Merge, Limit Transfer)
 * - NEW: Multi-Currency Support (multiple group/entity limits per currency)
 * - NEW: VA-Level Limits support
 * 
 * MULTI-CURRENCY ARCHITECTURE:
 * ============================
 * Group Limits:   ONE limit per currency per corporate
 * Entity Limits:  ONE limit per currency per entity
 * VA Limits:      ONE limit per VA (inherits entity's currency)
 * 
 * Hierarchy: Corporate → Entity → VA
 */
@Repository
public interface CreditLimitRepository extends JpaRepository<CreditLimit, UUID> {

    // ========================================================================
    // BASIC FINDERS
    // ========================================================================

    List<CreditLimit> findByCorporateIdOrderByLimitName(UUID corporateId);

    List<CreditLimit> findByCorporateId(UUID corporateId);

    List<CreditLimit> findByCorporateIdAndStatus(UUID corporateId, LimitStatus status);

    List<CreditLimit> findByCorporateIdAndLimitType(UUID corporateId, LimitType limitType);

    List<CreditLimit> findByTargetId(UUID targetId);

    List<CreditLimit> findByTargetIdAndStatus(UUID targetId, LimitStatus status);

    Optional<CreditLimit> findByTargetIdAndLimitTypeAndStatus(
        UUID targetId, LimitType limitType, LimitStatus status);

    List<CreditLimit> findByCreditFacilityId(UUID facilityId);

    /**
     * Find limits by target type.
     */
    List<CreditLimit> findByTargetType(TargetType targetType);

    /**
     * Find limits by corporate and target type.
     */
    List<CreditLimit> findByCorporateIdAndTargetType(UUID corporateId, TargetType targetType);

    // ========================================================================
    // ACTIVE LIMIT LOOKUPS
    // ========================================================================

    /**
     * Find active external limit for target.
     */
    @Query("""
        SELECT cl FROM CreditLimit cl 
        WHERE cl.targetId = :targetId 
        AND cl.limitType = 'EXTERNAL' 
        AND cl.status = 'ACTIVE'
        AND (cl.effectiveFrom IS NULL OR cl.effectiveFrom <= CURRENT_DATE)
        AND (cl.effectiveTo IS NULL OR cl.effectiveTo >= CURRENT_DATE)
        """)
    Optional<CreditLimit> findActiveExternalLimit(@Param("targetId") UUID targetId);

    /**
     * Find active internal limit for target.
     * @deprecated Use findActiveInternalLimitByTargetAndCurrency for multi-currency support
     */
    @Query("""
        SELECT cl FROM CreditLimit cl 
        WHERE cl.targetId = :targetId 
        AND cl.limitType = 'INTERNAL' 
        AND cl.status = 'ACTIVE'
        AND (cl.effectiveFrom IS NULL OR cl.effectiveFrom <= CURRENT_DATE)
        AND (cl.effectiveTo IS NULL OR cl.effectiveTo >= CURRENT_DATE)
        """)
    Optional<CreditLimit> findActiveInternalLimit(@Param("targetId") UUID targetId);

    /**
     * Find all active limits for target.
     */
    @Query("""
        SELECT cl FROM CreditLimit cl 
        WHERE cl.targetId = :targetId 
        AND cl.status = 'ACTIVE'
        AND (cl.effectiveFrom IS NULL OR cl.effectiveFrom <= CURRENT_DATE)
        AND (cl.effectiveTo IS NULL OR cl.effectiveTo >= CURRENT_DATE)
        ORDER BY cl.limitType
        """)
    List<CreditLimit> findActiveLimitsForTarget(@Param("targetId") UUID targetId);

    /**
     * Find active limits by corporate and target type.
     */
    @Query("""
        SELECT cl FROM CreditLimit cl 
        WHERE cl.corporateId = :corporateId 
        AND cl.targetType = :targetType 
        AND cl.status = 'ACTIVE'
        """)
    List<CreditLimit> findActiveByTargetType(@Param("corporateId") UUID corporateId,
                                              @Param("targetType") TargetType targetType);

    // ========================================================================
    // NEW v5.2.0: MULTI-CURRENCY GROUP LIMIT QUERIES
    // ========================================================================

    /**
     * Find ALL active group limits for a corporate (multi-currency).
     * Returns one limit per currency.
     * 
     * GROUP limit criteria:
     * - targetType = CORPORATE
     * - parentLimitId IS NULL (root level)
     * - limitType = INTERNAL
     * - status = ACTIVE
     */
    @Query("""
        SELECT cl FROM CreditLimit cl 
        WHERE cl.corporateId = :corporateId 
        AND cl.targetType = 'CORPORATE'
        AND cl.parentLimitId IS NULL
        AND cl.limitType = 'INTERNAL'
        AND cl.status = 'ACTIVE'
        ORDER BY cl.limitCurrency
        """)
    List<CreditLimit> findAllGroupLimitsByCorporate(@Param("corporateId") UUID corporateId);

    /**
     * Find group limit for a specific currency.
     */
    @Query("""
        SELECT cl FROM CreditLimit cl 
        WHERE cl.corporateId = :corporateId 
        AND cl.targetType = 'CORPORATE'
        AND cl.parentLimitId IS NULL
        AND cl.limitType = 'INTERNAL'
        AND cl.limitCurrency = :currency
        AND cl.status = 'ACTIVE'
        """)
    Optional<CreditLimit> findGroupLimitByCorporateAndCurrency(
        @Param("corporateId") UUID corporateId,
        @Param("currency") String currency);

    /**
     * Check if group limit exists for currency.
     */
    @Query("""
        SELECT COUNT(cl) > 0 FROM CreditLimit cl 
        WHERE cl.corporateId = :corporateId 
        AND cl.targetType = 'CORPORATE'
        AND cl.parentLimitId IS NULL
        AND cl.limitType = 'INTERNAL'
        AND cl.limitCurrency = :currency
        AND cl.status = 'ACTIVE'
        """)
    boolean existsGroupLimitForCurrency(
        @Param("corporateId") UUID corporateId,
        @Param("currency") String currency);

    /**
     * Get distinct currencies with group limits.
     */
    @Query("""
        SELECT DISTINCT cl.limitCurrency FROM CreditLimit cl 
        WHERE cl.corporateId = :corporateId 
        AND cl.targetType = 'CORPORATE'
        AND cl.parentLimitId IS NULL
        AND cl.limitType = 'INTERNAL'
        AND cl.status = 'ACTIVE'
        ORDER BY cl.limitCurrency
        """)
    List<String> findGroupLimitCurrencies(@Param("corporateId") UUID corporateId);

    // ========================================================================
    // NEW v5.2.0: MULTI-CURRENCY ENTITY LIMIT QUERIES
    // ========================================================================

    /**
     * Find active internal limit for target AND currency.
     * This is the key query for multi-currency entity limits.
     */
    @Query("""
        SELECT cl FROM CreditLimit cl 
        WHERE cl.targetId = :targetId 
        AND cl.limitCurrency = :currency
        AND cl.limitType = 'INTERNAL'
        AND cl.status = 'ACTIVE'
        AND (cl.effectiveFrom IS NULL OR cl.effectiveFrom <= CURRENT_DATE)
        AND (cl.effectiveTo IS NULL OR cl.effectiveTo >= CURRENT_DATE)
        """)
    Optional<CreditLimit> findActiveInternalLimitByTargetAndCurrency(
        @Param("targetId") UUID targetId,
        @Param("currency") String currency);

    /**
     * Find ALL active internal limits for an entity (all currencies).
     */
    @Query("""
        SELECT cl FROM CreditLimit cl 
        WHERE cl.targetId = :targetId 
        AND cl.targetType = 'LEGAL_ENTITY'
        AND cl.limitType = 'INTERNAL'
        AND cl.status = 'ACTIVE'
        ORDER BY cl.limitCurrency
        """)
    List<CreditLimit> findAllEntityLimitsByTarget(@Param("targetId") UUID targetId);

    /**
     * Check if entity limit exists for currency.
     */
    @Query("""
        SELECT COUNT(cl) > 0 FROM CreditLimit cl 
        WHERE cl.targetId = :targetId 
        AND cl.targetType = 'LEGAL_ENTITY'
        AND cl.limitCurrency = :currency
        AND cl.limitType = 'INTERNAL'
        AND cl.status = 'ACTIVE'
        """)
    boolean existsEntityLimitForCurrency(
        @Param("targetId") UUID targetId,
        @Param("currency") String currency);

    /**
     * Get distinct currencies with entity limits for a target.
     */
    @Query("""
        SELECT DISTINCT cl.limitCurrency FROM CreditLimit cl 
        WHERE cl.targetId = :targetId 
        AND cl.targetType = 'LEGAL_ENTITY'
        AND cl.limitType = 'INTERNAL'
        AND cl.status = 'ACTIVE'
        ORDER BY cl.limitCurrency
        """)
    List<String> findEntityLimitCurrencies(@Param("targetId") UUID targetId);

    /**
     * Find entity limits by parent group limit.
     * Used to get all entity limits allocated from a specific group limit.
     */
    @Query("""
        SELECT cl FROM CreditLimit cl 
        WHERE cl.parentLimitId = :groupLimitId 
        AND cl.targetType = 'LEGAL_ENTITY'
        AND cl.status = 'ACTIVE'
        ORDER BY cl.targetId, cl.limitCurrency
        """)
    List<CreditLimit> findEntityLimitsByGroupLimit(@Param("groupLimitId") UUID groupLimitId);

    // ========================================================================
    // NEW v5.2.0: VA-LEVEL LIMIT QUERIES
    // ========================================================================

    /**
     * Find VA limit by VA ID.
     */
    @Query("""
        SELECT cl FROM CreditLimit cl 
        WHERE cl.targetId = :vaId 
        AND cl.targetType = 'VIRTUAL_ACCOUNT'
        AND cl.limitType = 'INTERNAL'
        AND cl.status = 'ACTIVE'
        """)
    Optional<CreditLimit> findVaLimit(@Param("vaId") UUID vaId);

    /**
     * Find all VA limits under an entity.
     * Joins with VirtualAccount to filter by owningEntityId.
     */
    @Query("""
        SELECT cl FROM CreditLimit cl 
        WHERE cl.targetType = 'VIRTUAL_ACCOUNT'
        AND cl.limitType = 'INTERNAL'
        AND cl.status = 'ACTIVE'
        AND EXISTS (
            SELECT 1 FROM VirtualAccount va 
            WHERE va.id = cl.targetId 
            AND va.owningEntityId = :entityId
        )
        ORDER BY cl.limitCurrency, cl.targetId
        """)
    List<CreditLimit> findVaLimitsByEntity(@Param("entityId") UUID entityId);

    /**
     * Find VA limits by parent entity limit.
     * Used to get VAs allocated from a specific entity limit.
     */
    @Query("""
        SELECT cl FROM CreditLimit cl 
        WHERE cl.parentLimitId = :entityLimitId 
        AND cl.targetType = 'VIRTUAL_ACCOUNT'
        AND cl.status = 'ACTIVE'
        ORDER BY cl.targetId
        """)
    List<CreditLimit> findVaLimitsByEntityLimit(@Param("entityLimitId") UUID entityLimitId);

    /**
     * Sum VA limit amounts under an entity limit.
     */
    @Query("""
        SELECT COALESCE(SUM(cl.limitAmount), 0) FROM CreditLimit cl 
        WHERE cl.parentLimitId = :entityLimitId 
        AND cl.targetType = 'VIRTUAL_ACCOUNT'
        AND cl.status = 'ACTIVE'
        """)
    BigDecimal sumVaLimitAmounts(@Param("entityLimitId") UUID entityLimitId);

    /**
     * Count VAs with limits under an entity.
     */
    @Query("""
        SELECT COUNT(cl) FROM CreditLimit cl 
        WHERE cl.targetType = 'VIRTUAL_ACCOUNT'
        AND cl.limitType = 'INTERNAL'
        AND cl.status = 'ACTIVE'
        AND EXISTS (
            SELECT 1 FROM VirtualAccount va 
            WHERE va.id = cl.targetId 
            AND va.owningEntityId = :entityId
        )
        """)
    long countVaLimitsByEntity(@Param("entityId") UUID entityId);

    // ========================================================================
    // THRESHOLD QUERIES
    // ========================================================================

    /**
     * Find limits at or above warning threshold.
     */
    @Query("""
        SELECT cl FROM CreditLimit cl 
        WHERE cl.corporateId = :corporateId 
        AND cl.status = 'ACTIVE'
        AND cl.limitAmount > 0
        AND (cl.utilizedAmount / cl.limitAmount * 100) >= cl.warningThresholdPercent
        ORDER BY (cl.utilizedAmount / cl.limitAmount) DESC
        """)
    List<CreditLimit> findLimitsAtWarningLevel(@Param("corporateId") UUID corporateId);

    /**
     * Find limits at or above critical threshold.
     */
    @Query("""
        SELECT cl FROM CreditLimit cl 
        WHERE cl.corporateId = :corporateId 
        AND cl.status = 'ACTIVE'
        AND cl.limitAmount > 0
        AND (cl.utilizedAmount / cl.limitAmount * 100) >= cl.criticalThresholdPercent
        ORDER BY (cl.utilizedAmount / cl.limitAmount) DESC
        """)
    List<CreditLimit> findLimitsAtCriticalLevel(@Param("corporateId") UUID corporateId);

    // ========================================================================
    // AGGREGATION QUERIES
    // ========================================================================

    /**
     * Get total allocated limits by type.
     */
    @Query("""
        SELECT COALESCE(SUM(cl.limitAmount), 0) FROM CreditLimit cl 
        WHERE cl.corporateId = :corporateId 
        AND cl.limitType = :limitType
        AND cl.status = 'ACTIVE'
        """)
    BigDecimal getTotalAllocated(@Param("corporateId") UUID corporateId,
                                  @Param("limitType") LimitType limitType);

    /**
     * Get total utilized by type.
     */
    @Query("""
        SELECT COALESCE(SUM(cl.utilizedAmount), 0) FROM CreditLimit cl 
        WHERE cl.corporateId = :corporateId 
        AND cl.limitType = :limitType
        AND cl.status = 'ACTIVE'
        """)
    BigDecimal getTotalUtilized(@Param("corporateId") UUID corporateId,
                                 @Param("limitType") LimitType limitType);

    /**
     * Get total available by type.
     */
    @Query("""
        SELECT COALESCE(SUM(cl.availableAmount), 0) FROM CreditLimit cl 
        WHERE cl.corporateId = :corporateId 
        AND cl.limitType = :limitType
        AND cl.status = 'ACTIVE'
        """)
    BigDecimal getTotalAvailable(@Param("corporateId") UUID corporateId,
                                  @Param("limitType") LimitType limitType);

    /**
     * NEW v5.2.0: Get total by type and currency.
     */
    @Query("""
        SELECT COALESCE(SUM(cl.limitAmount), 0) FROM CreditLimit cl 
        WHERE cl.corporateId = :corporateId 
        AND cl.limitType = :limitType
        AND cl.limitCurrency = :currency
        AND cl.status = 'ACTIVE'
        """)
    BigDecimal getTotalAllocatedByCurrency(
        @Param("corporateId") UUID corporateId,
        @Param("limitType") LimitType limitType,
        @Param("currency") String currency);

    // ========================================================================
    // UPDATE OPERATIONS
    // ========================================================================

    /**
     * Update utilization.
     */
    @Modifying
    @Query("""
        UPDATE CreditLimit cl 
        SET cl.utilizedAmount = :utilized,
            cl.availableAmount = cl.limitAmount - :utilized - COALESCE(cl.heldAmount, 0),
            cl.updatedAt = CURRENT_TIMESTAMP
        WHERE cl.id = :id
        """)
    int updateUtilization(@Param("id") UUID id, @Param("utilized") BigDecimal utilized);

    /**
     * Update status.
     */
    @Modifying
    @Query("UPDATE CreditLimit cl SET cl.status = :status, cl.updatedAt = CURRENT_TIMESTAMP WHERE cl.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("status") LimitStatus status);

    /**
     * Increment utilization.
     */
    @Modifying
    @Query("""
        UPDATE CreditLimit cl 
        SET cl.utilizedAmount = cl.utilizedAmount + :amount,
            cl.availableAmount = cl.availableAmount - :amount,
            cl.updatedAt = CURRENT_TIMESTAMP
        WHERE cl.id = :id
        """)
    int incrementUtilization(@Param("id") UUID id, @Param("amount") BigDecimal amount);

    /**
     * Decrement utilization.
     */
    @Modifying
    @Query("""
        UPDATE CreditLimit cl 
        SET cl.utilizedAmount = GREATEST(cl.utilizedAmount - :amount, 0),
            cl.availableAmount = cl.limitAmount - GREATEST(cl.utilizedAmount - :amount, 0) - COALESCE(cl.heldAmount, 0),
            cl.updatedAt = CURRENT_TIMESTAMP
        WHERE cl.id = :id
        """)
    int decrementUtilization(@Param("id") UUID id, @Param("amount") BigDecimal amount);

    // ========================================================================
    // EXPIRY QUERIES
    // ========================================================================

    /**
     * Find limits expiring soon.
     */
    @Query("""
        SELECT cl FROM CreditLimit cl 
        WHERE cl.status = 'ACTIVE'
        AND cl.effectiveTo IS NOT NULL
        AND cl.effectiveTo <= :expiryDate
        ORDER BY cl.effectiveTo
        """)
    List<CreditLimit> findExpiringSoon(@Param("expiryDate") LocalDate expiryDate);

    /**
     * Find expired but still active limits.
     */
    @Query("""
        SELECT cl FROM CreditLimit cl 
        WHERE cl.status = 'ACTIVE'
        AND cl.effectiveTo IS NOT NULL
        AND cl.effectiveTo < CURRENT_DATE
        """)
    List<CreditLimit> findExpiredActive();

    /**
     * Bulk expire limits.
     */
    @Modifying
    @Query("""
        UPDATE CreditLimit cl 
        SET cl.status = 'EXPIRED', cl.updatedAt = CURRENT_TIMESTAMP 
        WHERE cl.status = 'ACTIVE' 
        AND cl.effectiveTo IS NOT NULL 
        AND cl.effectiveTo < CURRENT_DATE
        """)
    int expireAllExpired();

    // ========================================================================
    // EXISTENCE CHECKS
    // ========================================================================

    boolean existsByTargetIdAndLimitTypeAndStatus(UUID targetId, LimitType limitType, LimitStatus status);

    long countByCorporateIdAndStatus(UUID corporateId, LimitStatus status);

    // ========================================================================
    // HIERARCHY OPERATIONS - GROUP LIMIT QUERIES
    // ========================================================================

    /**
     * Find GROUP limit for a corporate (single - backward compatible).
     * @deprecated Use findGroupLimitByCorporateAndCurrency for multi-currency
     */
    @Query("""
        SELECT cl FROM CreditLimit cl 
        WHERE cl.corporateId = :corporateId 
        AND cl.targetType = 'CORPORATE'
        AND cl.parentLimitId IS NULL
        AND cl.status = 'ACTIVE'
        """)
    Optional<CreditLimit> findGroupLimitByCorporate(@Param("corporateId") UUID corporateId);

    /**
     * Find active limit by target ID (first matching).
     */
    @Query("""
        SELECT cl FROM CreditLimit cl 
        WHERE cl.targetId = :targetId 
        AND cl.status = 'ACTIVE'
        ORDER BY cl.limitType
        """)
    Optional<CreditLimit> findFirstActiveByTargetId(@Param("targetId") UUID targetId);

    // ========================================================================
    // HIERARCHY OPERATIONS - PARENT-CHILD LIMIT QUERIES
    // ========================================================================

    /**
     * Find child limits by parent limit ID.
     */
    @Query("""
        SELECT cl FROM CreditLimit cl 
        WHERE cl.parentLimitId = :parentLimitId 
        AND cl.status = 'ACTIVE'
        """)
    List<CreditLimit> findByParentLimitId(@Param("parentLimitId") UUID parentLimitId);

    /**
     * Find all child limits by parent (including non-active).
     */
    List<CreditLimit> findAllByParentLimitId(UUID parentLimitId);

    /**
     * Count child limits under a parent.
     */
    @Query("""
        SELECT COUNT(cl) FROM CreditLimit cl 
        WHERE cl.parentLimitId = :parentLimitId 
        AND cl.status = 'ACTIVE'
        """)
    long countChildLimits(@Param("parentLimitId") UUID parentLimitId);

    /**
     * Sum allocated amounts under a parent.
     */
    @Query("""
        SELECT COALESCE(SUM(cl.limitAmount), 0) FROM CreditLimit cl 
        WHERE cl.parentLimitId = :parentLimitId 
        AND cl.status = 'ACTIVE'
        """)
    BigDecimal sumChildLimitAmounts(@Param("parentLimitId") UUID parentLimitId);

    // ========================================================================
    // HIERARCHY OPERATIONS - CORPORATE LIMIT COUNTS
    // ========================================================================

    /**
     * Count active limits by corporate ID.
     */
    @Query("""
        SELECT COUNT(cl) FROM CreditLimit cl 
        WHERE cl.corporateId = :corporateId 
        AND cl.status = 'ACTIVE'
        """)
    long countByCorporateId(@Param("corporateId") UUID corporateId);

    /**
     * Sum utilized amounts for all limits under a corporate.
     */
    @Query("""
        SELECT COALESCE(SUM(cl.utilizedAmount), 0) FROM CreditLimit cl 
        WHERE cl.corporateId = :corporateId 
        AND cl.status = 'ACTIVE'
        """)
    BigDecimal sumUtilizedByCorporate(@Param("corporateId") UUID corporateId);

    // ========================================================================
    // HIERARCHY OPERATIONS - ENTITY AND ROOT LIMITS
    // ========================================================================

    /**
     * Find entity limits for a corporate (excludes GROUP level).
     */
    @Query("""
        SELECT cl FROM CreditLimit cl 
        WHERE cl.corporateId = :corporateId 
        AND cl.targetType = 'LEGAL_ENTITY'
        AND cl.status = 'ACTIVE'
        """)
    List<CreditLimit> findEntityLimitsByCorporate(@Param("corporateId") UUID corporateId);

    /**
     * Find root-level limits (no parent) for a corporate.
     */
    @Query("""
        SELECT cl FROM CreditLimit cl 
        WHERE cl.corporateId = :corporateId 
        AND cl.parentLimitId IS NULL
        AND cl.status = 'ACTIVE'
        """)
    List<CreditLimit> findRootLimits(@Param("corporateId") UUID corporateId);

    /**
     * Find limit by target for aggregation node.
     */
    @Query("""
        SELECT cl FROM CreditLimit cl 
        WHERE cl.targetId = :targetId 
        AND cl.targetType = 'AGGREGATION_NODE'
        AND cl.status = 'ACTIVE'
        """)
    Optional<CreditLimit> findAggregationNodeLimit(@Param("targetId") UUID targetId);

    // ========================================================================
    // HIERARCHY OPERATIONS - MIGRATION & BULK OPERATIONS
    // ========================================================================

    /**
     * Find limits that need to be migrated during acquisition.
     */
    @Query("""
        SELECT cl FROM CreditLimit cl 
        WHERE cl.corporateId = :corporateId 
        AND cl.status = 'ACTIVE'
        ORDER BY cl.parentLimitId NULLS FIRST, cl.targetType
        """)
    List<CreditLimit> findLimitsForMigration(@Param("corporateId") UUID corporateId);

    /**
     * Bulk update corporate ID for limits.
     */
    @Modifying
    @Query("""
        UPDATE CreditLimit cl 
        SET cl.corporateId = :newCorporateId,
            cl.updatedAt = CURRENT_TIMESTAMP
        WHERE cl.corporateId = :oldCorporateId 
        AND cl.status = 'ACTIVE'
        """)
    int bulkUpdateCorporateId(
        @Param("oldCorporateId") UUID oldCorporateId,
        @Param("newCorporateId") UUID newCorporateId);

    /**
     * Bulk cancel limits for a corporate.
     */
    @Modifying
    @Query("""
        UPDATE CreditLimit cl 
        SET cl.status = 'CANCELLED', 
            cl.updatedAt = CURRENT_TIMESTAMP,
            cl.notes = CONCAT(COALESCE(cl.notes, ''), ' | Cancelled during M&A operation')
        WHERE cl.corporateId = :corporateId 
        AND cl.status = 'ACTIVE'
        """)
    int bulkCancelLimits(@Param("corporateId") UUID corporateId);

    /**
     * Re-parent limits.
     */
    @Modifying
    @Query("""
        UPDATE CreditLimit cl 
        SET cl.parentLimitId = :newParentLimitId,
            cl.updatedAt = CURRENT_TIMESTAMP
        WHERE cl.parentLimitId = :oldParentLimitId 
        AND cl.status = 'ACTIVE'
        """)
    int bulkReparentLimits(
        @Param("oldParentLimitId") UUID oldParentLimitId,
        @Param("newParentLimitId") UUID newParentLimitId);
}