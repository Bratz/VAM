package com.bank.vam.repository.viban;

import com.bank.vam.entity.viban.Viban;
import com.bank.vam.entity.viban.Viban.VibanType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Viban entity.
 * CRITICAL: VIBAN lookup must be <5ms for ROBO routing.
 */
@Repository
public interface VibanRepository extends JpaRepository<Viban, UUID> {

    // ========================================================================
    // Primary lookup - CRITICAL for ROBO routing (<5ms)
    // ========================================================================

    /**
     * Find VIBAN by VIBAN string.
     * This is the primary lookup method for ROBO routing.
     * MUST be fast (<5ms) - uses unique index.
     */
    Optional<Viban> findByViban(String viban);

    /**
     * Check if VIBAN exists.
     */
    boolean existsByViban(String viban);

    // ========================================================================
    // Paginated queries (NEW - for frontend)
    // ========================================================================

    /**
     * Find all VIBANs with pagination.
     */
    Page<Viban> findAll(Pageable pageable);

    /**
     * Find VIBANs by status with pagination.
     */
    Page<Viban> findByStatus(String status, Pageable pageable);

    /**
     * Find VIBANs by pool with pagination.
     */
    Page<Viban> findByPoolId(UUID poolId, Pageable pageable);

    /**
     * Find VIBANs by program with pagination.
     */
    Page<Viban> findByProgramId(UUID programId, Pageable pageable);

    /**
     * Count VIBANs by status.
     */
    long countByStatus(String status);

    // ========================================================================
    // Statistics queries (NEW - for frontend)
    // ========================================================================

    /**
     * Sum of times used across all VIBANs.
     */
    @Query("SELECT COALESCE(SUM(v.timesUsed), 0) FROM Viban v")
    long sumTimesUsed();

    /**
     * Sum of total amount received across all VIBANs.
     */
    @Query("SELECT COALESCE(SUM(v.totalAmountReceived), 0) FROM Viban v")
    BigDecimal sumTotalAmountReceived();

    // ========================================================================
    // Pool queries (NEW - for frontend)
    // ========================================================================

    /**
     * Delete VIBANs by pool.
     */
    @Modifying
    @Query("DELETE FROM Viban v WHERE v.poolId = :poolId")
    void deleteByPoolId(@Param("poolId") UUID poolId);

    /**
     * Find VIBANs from a pool (list).
     */
    List<Viban> findByPoolId(UUID poolId);

    /**
     * Find available VIBANs in pool (returned status).
     */
    @Query("SELECT v FROM Viban v WHERE v.poolId = :poolId AND v.status = 'RETURNED'")
    List<Viban> findAvailableInPool(@Param("poolId") UUID poolId);

    /**
     * Find VIBANs scheduled for return.
     */
    @Query("SELECT v FROM Viban v WHERE v.poolId IS NOT NULL " +
           "AND v.returnScheduledAt IS NOT NULL " +
           "AND v.returnScheduledAt <= :now " +
           "AND v.status = 'ACTIVE'")
    List<Viban> findScheduledForReturn(@Param("now") LocalDateTime now);

    /**
     * Count VIBANs in pool by status.
     */
    long countByPoolIdAndStatus(UUID poolId, String status);

    // ========================================================================
    // Virtual Account queries
    // ========================================================================

    /**
     * Find all VIBANs for a virtual account.
     */
    List<Viban> findByVirtualAccountId(UUID virtualAccountId);

    /**
     * Find all VIBANs for a virtual account with status filter.
     */
    List<Viban> findByVirtualAccountIdAndStatus(UUID virtualAccountId, String status);

    /**
     * Find primary VIBAN for a virtual account.
     */
    @Query("SELECT v FROM Viban v WHERE v.virtualAccountId = :vaId AND v.isPrimary = true")
    Optional<Viban> findPrimaryByVirtualAccountId(@Param("vaId") UUID virtualAccountId);

    /**
     * Find active VIBANs for a virtual account.
     */
    @Query("SELECT v FROM Viban v WHERE v.virtualAccountId = :vaId AND v.status IN ('ACTIVE', 'PARTIAL')")
    List<Viban> findActiveByVirtualAccountId(@Param("vaId") UUID virtualAccountId);

    /**
     * Count VIBANs for a virtual account.
     */
    long countByVirtualAccountId(UUID virtualAccountId);

    // ========================================================================
    // Program queries
    // ========================================================================

    /**
     * Find all VIBANs for a program (list).
     */
    List<Viban> findByProgramId(UUID programId);

    /**
     * Find VIBANs by program and type.
     */
    List<Viban> findByProgramIdAndVibanType(UUID programId, VibanType vibanType);

    /**
     * Find VIBANs by program and status.
     */
    List<Viban> findByProgramIdAndStatus(UUID programId, String status);

    /**
     * Count VIBANs by program.
     */
    long countByProgramId(UUID programId);

    /**
     * Count VIBANs by program and status.
     */
    long countByProgramIdAndStatus(UUID programId, String status);

    // ========================================================================
    // Reference-based queries (for auto-reconciliation)
    // ========================================================================

    /**
     * Find VIBAN by reference.
     */
    Optional<Viban> findByReferenceTypeAndReferenceId(String referenceType, String referenceId);

    /**
     * Find all VIBANs for a reference type.
     */
    List<Viban> findByReferenceType(String referenceType);

    /**
     * Find VIBANs by customer reference.
     */
    List<Viban> findByCustomerReference(String customerReference);

    /**
     * Check if reference already has a VIBAN.
     */
    boolean existsByReferenceTypeAndReferenceId(String referenceType, String referenceId);

    // ========================================================================
    // Expiry queries
    // ========================================================================

    /**
     * Find expired VIBANs.
     */
    @Query("SELECT v FROM Viban v WHERE v.status = 'ACTIVE' " +
           "AND v.validUntil IS NOT NULL AND v.validUntil < :now")
    List<Viban> findExpired(@Param("now") LocalDateTime now);

    /**
     * Find VIBANs expiring soon.
     */
    @Query("SELECT v FROM Viban v WHERE v.status = 'ACTIVE' " +
           "AND v.validUntil IS NOT NULL " +
           "AND v.validUntil BETWEEN :now AND :threshold")
    List<Viban> findExpiringSoon(@Param("now") LocalDateTime now, @Param("threshold") LocalDateTime threshold);

    // ========================================================================
    // Hierarchy queries
    // ========================================================================

    /**
     * Find VIBANs by hierarchy node.
     */
    List<Viban> findByHierarchyNodeId(UUID hierarchyNodeId);

    // ========================================================================
    // Statistics queries
    // ========================================================================

    /**
     * Get total amount received for a virtual account.
     */
    @Query("SELECT COALESCE(SUM(v.totalAmountReceived), 0) FROM Viban v WHERE v.virtualAccountId = :vaId")
    BigDecimal getTotalAmountReceived(@Param("vaId") UUID virtualAccountId);

    /**
     * Get usage statistics by type.
     */
    @Query("SELECT v.vibanType, COUNT(v), COALESCE(SUM(v.timesUsed), 0), COALESCE(SUM(v.totalAmountReceived), 0) " +
           "FROM Viban v WHERE v.programId = :programId GROUP BY v.vibanType")
    List<Object[]> getStatsByType(@Param("programId") UUID programId);

    /**
     * Get status distribution.
     */
    @Query("SELECT v.status, COUNT(v) FROM Viban v WHERE v.programId = :programId GROUP BY v.status")
    List<Object[]> getStatusDistribution(@Param("programId") UUID programId);

    // ========================================================================
    // Update queries
    // ========================================================================

    /**
     * Update VIBAN status.
     */
    @Modifying
    @Query("UPDATE Viban v SET v.status = :status WHERE v.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("status") String status);

    /**
     * Update usage stats (called after payment).
     */
    @Modifying
    @Query("UPDATE Viban v SET v.timesUsed = v.timesUsed + 1, " +
           "v.totalAmountReceived = v.totalAmountReceived + :amount, " +
           "v.lastUsedAt = :timestamp, v.lastPaymentAmount = :amount WHERE v.id = :id")
    int updateUsageStats(@Param("id") UUID id, 
                         @Param("amount") BigDecimal amount, 
                         @Param("timestamp") LocalDateTime timestamp);

    /**
     * Mark as paid (for single-use VIBANs).
     */
    @Modifying
    @Query("UPDATE Viban v SET v.status = 'PAID', v.timesUsed = v.timesUsed + 1, " +
           "v.totalAmountReceived = v.totalAmountReceived + :amount, " +
           "v.lastUsedAt = :timestamp, v.lastPaymentAmount = :amount WHERE v.id = :id")
    int markAsPaid(@Param("id") UUID id, 
                   @Param("amount") BigDecimal amount, 
                   @Param("timestamp") LocalDateTime timestamp);

    /**
     * Mark as expired (batch update).
     */
    @Modifying
    @Query("UPDATE Viban v SET v.status = 'EXPIRED' " +
           "WHERE v.status = 'ACTIVE' AND v.validUntil IS NOT NULL AND v.validUntil < :now")
    int markExpired(@Param("now") LocalDateTime now);

    /**
     * Return to pool.
     */
    @Modifying
    @Query("UPDATE Viban v SET v.status = 'RETURNED', v.virtualAccountId = NULL, " +
           "v.referenceType = NULL, v.referenceId = NULL, " +
           "v.customerName = NULL, v.customerReference = NULL, " +
           "v.timesUsed = 0, v.totalAmountReceived = 0 WHERE v.id = :id")
    int returnToPool(@Param("id") UUID id);

    // ========================================================================
    // Lookup with validation (for ROBO routing)
    // ========================================================================

    /**
     * Find VIBAN with all info needed for routing.
     * Returns VIBAN with validation status.
     */
    @Query("SELECT v FROM Viban v " +
           "LEFT JOIN FETCH VirtualAccount va ON v.virtualAccountId = va.id " +
           "WHERE v.viban = :viban")
    Optional<Viban> findByVibanWithVirtualAccount(@Param("viban") String viban);

    /**
     * Find active VIBAN for routing (validates status and expiry).
     */
    @Query("SELECT v FROM Viban v WHERE v.viban = :viban " +
           "AND v.status IN ('ACTIVE', 'PARTIAL') " +
           "AND (v.validUntil IS NULL OR v.validUntil > :now)")
    Optional<Viban> findActiveViban(@Param("viban") String viban, @Param("now") LocalDateTime now);

    // ========================================================================
    // Batch operations
    // ========================================================================

    /**
     * Find VIBANs by IDs.
     */
    List<Viban> findByIdIn(List<UUID> ids);

    /**
     * Delete by virtual account.
     */
    void deleteByVirtualAccountId(UUID virtualAccountId);

    /**
     * Delete by program.
     */
    void deleteByProgramId(UUID programId);
}