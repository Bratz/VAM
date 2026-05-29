package com.bank.vam.repository.hierarchy;

import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.hierarchy.LegalEntity.EntityStatus;
import com.bank.vam.entity.hierarchy.LegalEntity.EntityType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for LegalEntity operations.
 * 
 * Provides:
 * - Standard CRUD operations
 * - Hierarchy traversal (ancestors, descendants)
 * - Corporate and status filtering
 * - Credit limit queries
 * - Bank customer queries
 */
@Repository
public interface LegalEntityRepository extends JpaRepository<LegalEntity, UUID> {

    // ========================================================================
    // BASIC FINDERS
    // ========================================================================

    /**
     * Find entity by unique code.
     */
    Optional<LegalEntity> findByEntityCode(String entityCode);

    /**
     * Find all entities for a corporate.
     */
    List<LegalEntity> findByCorporateIdOrderByHierarchyPath(UUID corporateId);

    /**
     * Find all entities for a corporate with status.
     */
    List<LegalEntity> findByCorporateIdAndStatus(UUID corporateId, EntityStatus status);

    /**
     * Find all entities by type.
     */
    List<LegalEntity> findByCorporateIdAndEntityType(UUID corporateId, EntityType entityType);

    /**
     * Find root entities (no parent) for a corporate.
     */
    List<LegalEntity> findByCorporateIdAndParentEntityIdIsNull(UUID corporateId);

    /**
     * Find direct children of an entity.
     */
    List<LegalEntity> findByParentEntityIdOrderByEntityName(UUID parentEntityId);

    /**
     * Find all active entities for a corporate.
     */
    @Query("SELECT le FROM LegalEntity le WHERE le.corporateId = :corporateId AND le.status = 'ACTIVE' ORDER BY le.hierarchyPath")
    List<LegalEntity> findAllActiveByCorporate(@Param("corporateId") UUID corporateId);

    // ========================================================================
    // HIERARCHY QUERIES
    // ========================================================================

    /**
     * Find all descendants using materialized path.
     */
    @Query("SELECT le FROM LegalEntity le WHERE le.hierarchyPath LIKE CONCAT(:parentPath, '/%') ORDER BY le.hierarchyPath")
    List<LegalEntity> findDescendants(@Param("parentPath") String parentPath);

    /**
     * Find all ancestors by traversing parent chain.
     */
    @Query(value = """
        WITH RECURSIVE ancestors AS (
            SELECT * FROM legal_entities WHERE id = :entityId
            UNION ALL
            SELECT le.* FROM legal_entities le
            INNER JOIN ancestors a ON le.id = a.parent_entity_id
        )
        SELECT * FROM ancestors ORDER BY hierarchy_level
        """, nativeQuery = true)
    List<LegalEntity> findAncestors(@Param("entityId") UUID entityId);

    /**
     * Find all entities at a specific hierarchy level.
     */
    List<LegalEntity> findByCorporateIdAndHierarchyLevel(UUID corporateId, Integer hierarchyLevel);

    /**
     * Count direct children.
     */
    long countByParentEntityId(UUID parentEntityId);

    // ========================================================================
    // BANK CUSTOMER QUERIES
    // ========================================================================

    /**
     * Find all bank customers for a corporate.
     * Bank customers have isBankCustomer = true and can have EXTERNAL credit limits.
     */
    List<LegalEntity> findByCorporateIdAndIsBankCustomerTrue(UUID corporateId);

    /**
     * Find all non-bank-customer entities for a corporate.
     * Non-bank-customers can only have INTERNAL credit limits.
     */
    List<LegalEntity> findByCorporateIdAndIsBankCustomerFalse(UUID corporateId);

    /**
     * Find active bank customers for a corporate.
     */
    @Query("SELECT le FROM LegalEntity le WHERE le.corporateId = :corporateId AND le.isBankCustomer = true AND le.status = 'ACTIVE' ORDER BY le.entityName")
    List<LegalEntity> findActiveBankCustomers(@Param("corporateId") UUID corporateId);

    /**
     * Find entity by BANCS Customer ID.
     */
    Optional<LegalEntity> findByBancsCustomerId(String bancsCustomerId);

    /**
     * Find entities with BANCS Customer ID.
     */
    @Query("SELECT le FROM LegalEntity le WHERE le.corporateId = :corporateId AND le.bancsCustomerId IS NOT NULL ORDER BY le.entityName")
    List<LegalEntity> findEntitiesWithBancsCustomerId(@Param("corporateId") UUID corporateId);

    /**
     * Search entities by BANCS Customer ID.
     */
    @Query("SELECT le FROM LegalEntity le WHERE le.corporateId = :corporateId AND LOWER(le.bancsCustomerId) LIKE LOWER(CONCAT('%', :search, '%'))")
    List<LegalEntity> searchByBancsCustomerId(@Param("corporateId") UUID corporateId, @Param("search") String search);

    /**
     * Count bank customers for a corporate.
     */
    long countByCorporateIdAndIsBankCustomerTrue(UUID corporateId);

    /**
     * Check if BANCS Customer ID exists.
     */
    boolean existsByBancsCustomerId(String bancsCustomerId);

    // ========================================================================
    // TREASURY QUERIES
    // ========================================================================

    /**
     * Find all treasury centers for a corporate.
     */
    @Query("SELECT le FROM LegalEntity le WHERE le.corporateId = :corporateId AND le.isTreasuryCenter = true AND le.status = 'ACTIVE'")
    List<LegalEntity> findTreasuryCenters(@Param("corporateId") UUID corporateId);

    /**
     * Find entities that can hold physical accounts.
     */
    @Query("SELECT le FROM LegalEntity le WHERE le.corporateId = :corporateId AND le.canHoldPhysicalAccounts = true AND le.status = 'ACTIVE'")
    List<LegalEntity> findEntitiesWithPhysicalAccounts(@Param("corporateId") UUID corporateId);

    /**
     * Find entities that can participate in pooling.
     */
    @Query("SELECT le FROM LegalEntity le WHERE le.corporateId = :corporateId AND le.canParticipatePooling = true AND le.status = 'ACTIVE'")
    List<LegalEntity> findPoolingEligible(@Param("corporateId") UUID corporateId);

    /**
     * Find entities that can participate in netting.
     */
    @Query("SELECT le FROM LegalEntity le WHERE le.corporateId = :corporateId AND le.canParticipateNetting = true AND le.status = 'ACTIVE'")
    List<LegalEntity> findNettingEligible(@Param("corporateId") UUID corporateId);

    // ========================================================================
    // CREDIT LIMIT QUERIES
    // ========================================================================

    /**
     * Find entities with internal credit limits.
     */
    @Query("SELECT le FROM LegalEntity le WHERE le.corporateId = :corporateId AND le.internalCreditLimit IS NOT NULL AND le.internalCreditLimit > 0")
    List<LegalEntity> findEntitiesWithLimits(@Param("corporateId") UUID corporateId);

    /**
     * Find entities with limit utilization above threshold.
     */
    @Query("""
        SELECT le FROM LegalEntity le 
        WHERE le.corporateId = :corporateId 
        AND le.internalCreditLimit IS NOT NULL 
        AND le.internalCreditLimit > 0
        AND (le.internalLimitUtilized / le.internalCreditLimit * 100) >= le.limitWarningThreshold
        """)
    List<LegalEntity> findEntitiesAtWarningLevel(@Param("corporateId") UUID corporateId);

    /**
     * Find entities with breached limits (100% utilized).
     */
    @Query("""
        SELECT le FROM LegalEntity le 
        WHERE le.corporateId = :corporateId 
        AND le.internalCreditLimit IS NOT NULL 
        AND le.internalCreditLimit > 0
        AND le.internalLimitUtilized >= le.internalCreditLimit
        """)
    List<LegalEntity> findEntitiesWithBreachedLimits(@Param("corporateId") UUID corporateId);

    /**
     * Get total allocated limits for a corporate.
     */
    @Query("SELECT COALESCE(SUM(le.internalCreditLimit), 0) FROM LegalEntity le WHERE le.corporateId = :corporateId")
    BigDecimal getTotalAllocatedLimits(@Param("corporateId") UUID corporateId);

    /**
     * Get total utilized limits for a corporate.
     */
    @Query("SELECT COALESCE(SUM(le.internalLimitUtilized), 0) FROM LegalEntity le WHERE le.corporateId = :corporateId")
    BigDecimal getTotalUtilizedLimits(@Param("corporateId") UUID corporateId);

    // ========================================================================
    // UPDATE OPERATIONS
    // ========================================================================

    /**
     * Update internal limit utilized.
     */
    @Modifying
    @Query("UPDATE LegalEntity le SET le.internalLimitUtilized = :utilized, le.updatedAt = CURRENT_TIMESTAMP WHERE le.id = :entityId")
    int updateLimitUtilized(@Param("entityId") UUID entityId, @Param("utilized") BigDecimal utilized);

    /**
     * Update status.
     */
    @Modifying
    @Query("UPDATE LegalEntity le SET le.status = :status, le.updatedAt = CURRENT_TIMESTAMP WHERE le.id = :entityId")
    int updateStatus(@Param("entityId") UUID entityId, @Param("status") EntityStatus status);

    /**
     * Update internal credit limit.
     */
    @Modifying
    @Query("""
        UPDATE LegalEntity le 
        SET le.internalCreditLimit = :limit, 
            le.internalLimitCurrency = :currency,
            le.updatedAt = CURRENT_TIMESTAMP 
        WHERE le.id = :entityId
        """)
    int updateInternalLimit(@Param("entityId") UUID entityId, 
                           @Param("limit") BigDecimal limit,
                           @Param("currency") String currency);

    /**
     * Clear internal credit limit (used when CreditLimit is deleted).
     */
    @Modifying
    @Query("""
        UPDATE LegalEntity le 
        SET le.internalCreditLimit = NULL, 
            le.internalLimitUtilized = 0,
            le.internalLimitCurrency = NULL,
            le.updatedAt = CURRENT_TIMESTAMP 
        WHERE le.id = :entityId
        """)
    int clearInternalLimit(@Param("entityId") UUID entityId);

    /**
     * Update bank customer status and BANCS ID.
     */
    @Modifying
    @Query("""
        UPDATE LegalEntity le 
        SET le.isBankCustomer = :isBankCustomer, 
            le.bancsCustomerId = :bancsCustomerId,
            le.canHoldPhysicalAccounts = :isBankCustomer,
            le.updatedAt = CURRENT_TIMESTAMP 
        WHERE le.id = :entityId
        """)
    int updateBankCustomerStatus(@Param("entityId") UUID entityId, 
                                  @Param("isBankCustomer") Boolean isBankCustomer,
                                  @Param("bancsCustomerId") String bancsCustomerId);

    // ========================================================================
    // COUNTRY/JURISDICTION QUERIES
    // ========================================================================

    /**
     * Find entities by country.
     */
    List<LegalEntity> findByCorporateIdAndCountryCode(UUID corporateId, String countryCode);

    /**
     * Find entities by functional currency.
     */
    List<LegalEntity> findByCorporateIdAndFunctionalCurrency(UUID corporateId, String currency);

    /**
     * Get distinct countries for a corporate.
     */
    @Query("SELECT DISTINCT le.countryCode FROM LegalEntity le WHERE le.corporateId = :corporateId AND le.countryCode IS NOT NULL")
    List<String> findDistinctCountries(@Param("corporateId") UUID corporateId);

    /**
     * Get distinct currencies for a corporate.
     */
    @Query("SELECT DISTINCT le.functionalCurrency FROM LegalEntity le WHERE le.corporateId = :corporateId")
    List<String> findDistinctCurrencies(@Param("corporateId") UUID corporateId);

    // ========================================================================
    // EXISTENCE CHECKS
    // ========================================================================

    /**
     * Check if entity code exists.
     */
    boolean existsByEntityCode(String entityCode);

    /**
     * Check if entity has children.
     */
    boolean existsByParentEntityId(UUID parentEntityId);

    // ========================================================================
    // SEARCH
    // ========================================================================

    /**
     * Search entities by name or code.
     */
    @Query("""
        SELECT le FROM LegalEntity le 
        WHERE le.corporateId = :corporateId 
        AND (LOWER(le.entityName) LIKE LOWER(CONCAT('%', :search, '%')) 
             OR LOWER(le.entityCode) LIKE LOWER(CONCAT('%', :search, '%')))
        ORDER BY le.entityName
        """)
    List<LegalEntity> searchByNameOrCode(@Param("corporateId") UUID corporateId, @Param("search") String search);

    /**
     * Search entities by name, code, or BANCS Customer ID.
     */
    @Query("""
        SELECT le FROM LegalEntity le 
        WHERE le.corporateId = :corporateId 
        AND (LOWER(le.entityName) LIKE LOWER(CONCAT('%', :search, '%')) 
             OR LOWER(le.entityCode) LIKE LOWER(CONCAT('%', :search, '%'))
             OR LOWER(le.bancsCustomerId) LIKE LOWER(CONCAT('%', :search, '%')))
        ORDER BY le.entityName
        """)
    List<LegalEntity> searchByNameCodeOrBancsId(@Param("corporateId") UUID corporateId, @Param("search") String search);

    // ============================================================================
// PATCH: LegalEntityRepository.java - Add IHB Query Methods
// ============================================================================
// 
// Add these methods to the existing LegalEntityRepository
//
// LOCATION: backend/src/main/java/com/bank/vam/repository/hierarchy/LegalEntityRepository.java
// ============================================================================

    // ========================================================================
    // IHB QUERY METHODS (NEW - Phase 2)
    // ========================================================================

    /**
     * Find all IHB-enabled entities for a corporate.
     */
    List<LegalEntity> findByCorporateIdAndIhbEnabledTrue(UUID corporateId);

    /**
     * Find all IHB-enabled entities across all corporates.
     */
    List<LegalEntity> findByIhbEnabledTrue();

    /**
     * Find treasury centers for a corporate.
     */
    List<LegalEntity> findByCorporateIdAndIsTreasuryCenterTrue(UUID corporateId);

    /**
     * Find entities that can lend.
     */
    List<LegalEntity> findByCorporateIdAndCanLendTrue(UUID corporateId);

    /**
     * Find entities that can borrow.
     */
    List<LegalEntity> findByCorporateIdAndCanBorrowTrue(UUID corporateId);

    /**
     * Find entities with IHB limit warnings (utilization >= 80%).
     */
    @Query("SELECT e FROM LegalEntity e WHERE e.corporateId = :corporateId " +
           "AND e.ihbEnabled = true " +
           "AND e.ihbCreditLimit > 0 " +
           "AND (e.ihbCurrentExposure * 100 / e.ihbCreditLimit) >= 80")
    List<LegalEntity> findEntitiesWithIhbLimitWarning(@Param("corporateId") UUID corporateId);

    /**
     * Find entities with IHB limit breached (utilization >= 100%).
     */
    @Query("SELECT e FROM LegalEntity e WHERE e.corporateId = :corporateId " +
           "AND e.ihbEnabled = true " +
           "AND e.ihbCreditLimit > 0 " +
           "AND e.ihbCurrentExposure >= e.ihbCreditLimit")
    List<LegalEntity> findEntitiesWithIhbLimitBreached(@Param("corporateId") UUID corporateId);

    /**
     * Get total IHB exposure for a corporate.
     */
    @Query("SELECT COALESCE(SUM(e.ihbCurrentExposure), 0) FROM LegalEntity e " +
           "WHERE e.corporateId = :corporateId AND e.ihbEnabled = true")
    BigDecimal getTotalIhbExposure(@Param("corporateId") UUID corporateId);

    /**
     * Get total IHB credit limit for a corporate.
     */
    @Query("SELECT COALESCE(SUM(e.ihbCreditLimit), 0) FROM LegalEntity e " +
           "WHERE e.corporateId = :corporateId AND e.ihbEnabled = true")
    BigDecimal getTotalIhbCreditLimit(@Param("corporateId") UUID corporateId);

    /**
     * ENHANCED: Find all treasury centers (entities that can lend) across all corporates.
     * Used by IHB service to find sweep targets.
     */
    List<LegalEntity> findByCanLendTrue();

    /**
     * ENHANCED: Find treasury center for a specific corporate.
     */
    @Query("SELECT e FROM LegalEntity e WHERE e.corporateId = :corporateId AND e.canLend = true AND e.ihbEnabled = true")
    Optional<LegalEntity> findTreasuryCenterByCorporate(@Param("corporateId") UUID corporateId);


}