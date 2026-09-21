package com.bank.vam.repository;

import com.bank.vam.entity.Program;
import com.bank.vam.entity.Program.ProgramStatus;
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

    boolean existsByProgramCode(String programCode);

    List<Program> findByCorporateId(UUID corporateId);

    Page<Program> findByCorporateId(UUID corporateId, Pageable pageable);

    List<Program> findByCorporateIdAndStatus(UUID corporateId, ProgramStatus status);

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
        AND (:status IS NULL OR :status = '' OR CAST(p.status AS string) = :status)
        ORDER BY p.createdAt DESC
        """)
    Page<Program> findAllWithFilters(
        @Param("query") String query,
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
        AND (:status IS NULL OR :status = '' OR CAST(p.status AS string) = :status)
        ORDER BY p.createdAt DESC
        """)
    Page<Program> findByCorporateIdWithFilters(
        @Param("corporateId") UUID corporateId,
        @Param("query") String query,
        @Param("status") String status,
        Pageable pageable
    );

    // ========================================================================
    // FEATURE FILTER QUERIES
    // ========================================================================

    List<Program> findByRootHierarchyNodeIdIsNull();

    // ========================================================================
    // HIERARCHY QUERIES (NEW)
    // ========================================================================

    /**
     * Update root hierarchy node ID.
     */
    @Modifying
    @Query("UPDATE Program p SET p.rootHierarchyNodeId = :nodeId WHERE p.id = :programId")
    int updateRootHierarchyNodeId(@Param("programId") UUID programId, @Param("nodeId") UUID nodeId);

    // ========================================================================
    // STATISTICS QUERIES
    // ========================================================================

    long countByStatus(ProgramStatus status);

    long countByCorporateId(UUID corporateId);

    long countByCorporateIdAndStatus(UUID corporateId, ProgramStatus status);

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

}