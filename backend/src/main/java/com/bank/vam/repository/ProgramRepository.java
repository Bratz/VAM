package com.bank.vam.repository;

import com.bank.vam.entity.Program;
import com.bank.vam.entity.Program.ProgramStatus;
import com.bank.vam.entity.Program.ProgramType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Program entity.
 * Aligned with unified_programs schema including hierarchy support.
 */
@Repository
public interface ProgramRepository extends JpaRepository<Program, UUID> {

    // ========================================================================
    // BASIC FINDERS
    // ========================================================================

    Optional<Program> findByProgramCode(String programCode);

    boolean existsByProgramCode(String programCode);

    List<Program> findByCorporateId(UUID corporateId);

    Page<Program> findByCorporateId(UUID corporateId, Pageable pageable);

    List<Program> findByCorporateIdAndStatus(UUID corporateId, ProgramStatus status);

    List<Program> findByProgramType(ProgramType programType);

    Page<Program> findByProgramType(ProgramType programType, Pageable pageable);

    List<Program> findByStatus(ProgramStatus status);

    Page<Program> findByStatus(ProgramStatus status, Pageable pageable);

    // ========================================================================
    // FILTERED QUERIES
    // ========================================================================

    @Query("""
        SELECT p FROM Program p
        WHERE (:query IS NULL OR :query = '' OR 
            LOWER(CAST(p.programCode AS string)) LIKE LOWER(CONCAT('%', CAST(:query AS string), '%')) OR
            LOWER(CAST(p.programName AS string)) LIKE LOWER(CONCAT('%', CAST(:query AS string), '%')) OR
            LOWER(CAST(p.description AS string)) LIKE LOWER(CONCAT('%', CAST(:query AS string), '%')))
        AND (:programType IS NULL OR :programType = '' OR CAST(p.programType AS string) = :programType)
        AND (:status IS NULL OR :status = '' OR CAST(p.status AS string) = :status)
        ORDER BY p.createdAt DESC
        """)
    Page<Program> findAllWithFilters(
        @Param("query") String query,
        @Param("programType") String programType,
        @Param("status") String status,
        Pageable pageable
    );

    @Query("""
        SELECT p FROM Program p
        WHERE p.corporateId = :corporateId
        AND (:query IS NULL OR :query = '' OR 
            LOWER(CAST(p.programCode AS string)) LIKE LOWER(CONCAT('%', CAST(:query AS string), '%')) OR
            LOWER(CAST(p.programName AS string)) LIKE LOWER(CONCAT('%', CAST(:query AS string), '%')) OR
            LOWER(CAST(p.description AS string)) LIKE LOWER(CONCAT('%', CAST(:query AS string), '%')))
        AND (:programType IS NULL OR :programType = '' OR CAST(p.programType AS string) = :programType)
        AND (:status IS NULL OR :status = '' OR CAST(p.status AS string) = :status)
        ORDER BY p.createdAt DESC
        """)
    Page<Program> findByCorporateIdWithFilters(
        @Param("corporateId") UUID corporateId,
        @Param("query") String query,
        @Param("programType") String programType,
        @Param("status") String status,
        Pageable pageable
    );

    // ========================================================================
    // FEATURE FILTER QUERIES
    // ========================================================================

    List<Program> findByVibanEnabledTrue();

    List<Program> findByWalletEnabledTrue();

    List<Program> findByEscrowEnabledTrue();

    List<Program> findByIhbEnabledTrue();

    // ========================================================================
    // HIERARCHY QUERIES (NEW)
    // ========================================================================

    /**
     * Find programs with hierarchy enabled.
     */
    List<Program> findByHierarchyEnabledTrue();

    /**
     * Find active programs with hierarchy enabled.
     */
    @Query("SELECT p FROM Program p WHERE p.hierarchyEnabled = true AND p.status = 'ACTIVE'")
    List<Program> findActiveWithHierarchyEnabled();

    /**
     * Find programs by hierarchy template.
     */
    List<Program> findByDefaultHierarchyTemplate(String template);

    /**
     * Find program by root hierarchy node ID.
     */
    Optional<Program> findByRootHierarchyNodeId(UUID rootHierarchyNodeId);

    /**
     * Update root hierarchy node ID.
     */
    @Modifying
    @Query("UPDATE Program p SET p.rootHierarchyNodeId = :nodeId WHERE p.id = :programId")
    int updateRootHierarchyNodeId(@Param("programId") UUID programId, @Param("nodeId") UUID nodeId);

    /**
     * Enable/disable hierarchy for a program.
     */
    @Modifying
    @Query("UPDATE Program p SET p.hierarchyEnabled = :enabled, p.hierarchyDepth = :depth, p.defaultHierarchyTemplate = :template WHERE p.id = :programId")
    int updateHierarchySettings(
        @Param("programId") UUID programId, 
        @Param("enabled") Boolean enabled,
        @Param("depth") Integer depth,
        @Param("template") String template
    );

    // ========================================================================
    // ADDITIONAL PROGRAM TYPE QUERIES (NEW)
    // ========================================================================

    List<Program> findByLoyaltyEnabledTrue();

    List<Program> findByGiftCardEnabledTrue();

    List<Program> findByCorporateCardEnabledTrue();

    List<Program> findByMobileMoneyEnabledTrue();

    // ========================================================================
    // COMBINED FEATURE QUERIES
    // ========================================================================

    @Query("""
        SELECT p FROM Program p
        WHERE p.status = 'ACTIVE'
        AND (
            (:vibanEnabled IS NULL OR p.vibanEnabled = :vibanEnabled) AND
            (:walletEnabled IS NULL OR p.walletEnabled = :walletEnabled) AND
            (:escrowEnabled IS NULL OR p.escrowEnabled = :escrowEnabled) AND
            (:ihbEnabled IS NULL OR p.ihbEnabled = :ihbEnabled) AND
            (:hierarchyEnabled IS NULL OR p.hierarchyEnabled = :hierarchyEnabled)
        )
        """)
    List<Program> findActiveWithFeatures(
        @Param("vibanEnabled") Boolean vibanEnabled,
        @Param("walletEnabled") Boolean walletEnabled,
        @Param("escrowEnabled") Boolean escrowEnabled,
        @Param("ihbEnabled") Boolean ihbEnabled,
        @Param("hierarchyEnabled") Boolean hierarchyEnabled
    );

    // ========================================================================
    // STATISTICS QUERIES
    // ========================================================================

    long countByStatus(ProgramStatus status);

    long countByProgramType(ProgramType programType);

    long countByCorporateId(UUID corporateId);

    long countByCorporateIdAndStatus(UUID corporateId, ProgramStatus status);

    long countByCorporateIdAndProgramType(UUID corporateId, ProgramType programType);

    long countByHierarchyEnabledTrue();

    // ========================================================================
    // PHYSICAL ACCOUNT QUERIES
    // ========================================================================

    List<Program> findByPhysicalAccountId(UUID physicalAccountId);

    long countByPhysicalAccountId(UUID physicalAccountId);

    // ========================================================================
    // CURRENCY QUERIES
    // ========================================================================

    List<Program> findByCurrencyCode(String currencyCode);

    List<Program> findByCorporateIdAndCurrencyCode(UUID corporateId, String currencyCode);

    // ========================================================================
    // ACTIVE PROGRAM QUERIES
    // ========================================================================

    @Query("SELECT p FROM Program p WHERE p.status = 'ACTIVE'")
    List<Program> findAllActive();

    @Query("SELECT p FROM Program p WHERE p.corporateId = :corporateId AND p.status = 'ACTIVE'")
    List<Program> findActiveByCorporateId(@Param("corporateId") UUID corporateId);

    @Query("SELECT p FROM Program p WHERE p.programType = :programType AND p.status = 'ACTIVE'")
    List<Program> findActiveByProgramType(@Param("programType") ProgramType programType);

    /**
     * Find active IHB program for a corporate.
     * Typically a corporate has one IHB program for treasury operations.
     */
    @Query("SELECT p FROM Program p WHERE p.corporateId = :corporateId " +
           "AND p.programType = 'IHB' AND p.status = 'ACTIVE' ORDER BY p.createdAt ASC")
    List<Program> findActiveIhbProgramsByCorporate(@Param("corporateId") UUID corporateId);

    /**
     * Find active program by corporate and type.
     */
    @Query("SELECT p FROM Program p WHERE p.corporateId = :corporateId " +
           "AND p.programType = :programType AND p.status = 'ACTIVE' ORDER BY p.createdAt ASC")
    List<Program> findActiveByCorporateIdAndProgramType(
        @Param("corporateId") UUID corporateId,
        @Param("programType") ProgramType programType);

    // ========================================================================
    // VIBAN POOL QUERIES (NEW)
    // ========================================================================

    /**
     * Find programs by VIBAN pool.
     */
    List<Program> findByDefaultVibanPoolId(UUID vibanPoolId);

    /**
     * Count programs using a specific VIBAN pool.
     */
    long countByDefaultVibanPoolId(UUID vibanPoolId);
}