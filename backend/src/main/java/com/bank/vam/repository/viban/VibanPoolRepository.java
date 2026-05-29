package com.bank.vam.repository.viban;

import com.bank.vam.entity.viban.VibanPool;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for VibanPool entity.
 * Manages VIBAN pool operations.
 */
@Repository
public interface VibanPoolRepository extends JpaRepository<VibanPool, UUID> {

    // ========================================================================
    // Basic queries
    // ========================================================================

    /**
     * Find all pools for a program.
     */
    List<VibanPool> findByProgramId(UUID programId);

    /**
     * Find pool by code.
     */
    Optional<VibanPool> findByProgramIdAndPoolCode(UUID programId, String poolCode);

    /**
     * Find active pools for a program.
     */
    List<VibanPool> findByProgramIdAndStatus(UUID programId, String status);

    /**
     * Check if pool code exists.
     */
    boolean existsByProgramIdAndPoolCode(UUID programId, String poolCode);

    // ========================================================================
    // Status queries (NEW - for frontend)
    // ========================================================================

    /**
     * Find all pools by status.
     */
    List<VibanPool> findByStatus(String status);

    /**
     * Count pools by status (global).
     */
    long countByStatus(String status);

    // ========================================================================
    // Availability queries
    // ========================================================================

    /**
     * Find pools with available VIBANs.
     */
    @Query("SELECT p FROM VibanPool p WHERE p.programId = :programId " +
           "AND p.status = 'ACTIVE' AND p.availableCount > 0")
    List<VibanPool> findAvailablePools(@Param("programId") UUID programId);

    /**
     * Find pool with most available VIBANs.
     */
    @Query("SELECT p FROM VibanPool p WHERE p.programId = :programId " +
           "AND p.status = 'ACTIVE' AND p.availableCount > 0 " +
           "ORDER BY p.availableCount DESC")
    List<VibanPool> findPoolsOrderByAvailability(@Param("programId") UUID programId);

    /**
     * Find exhausted pools.
     */
    @Query("SELECT p FROM VibanPool p WHERE p.programId = :programId AND p.status = 'EXHAUSTED'")
    List<VibanPool> findExhaustedPools(@Param("programId") UUID programId);

    /**
     * Find pools below threshold.
     */
    @Query("SELECT p FROM VibanPool p WHERE p.programId = :programId " +
           "AND p.status = 'ACTIVE' " +
           "AND (p.availableCount * 100.0 / p.poolSize) < p.lowThresholdPercent")
    List<VibanPool> findPoolsBelowThreshold(@Param("programId") UUID programId);

    // ========================================================================
    // Statistics queries
    // ========================================================================

    /**
     * Get total pool capacity for a program.
     */
    @Query("SELECT COALESCE(SUM(p.poolSize), 0) FROM VibanPool p WHERE p.programId = :programId")
    Long getTotalCapacity(@Param("programId") UUID programId);

    /**
     * Get total available VIBANs for a program.
     */
    @Query("SELECT COALESCE(SUM(p.availableCount), 0) FROM VibanPool p " +
           "WHERE p.programId = :programId AND p.status = 'ACTIVE'")
    Long getTotalAvailable(@Param("programId") UUID programId);

    /**
     * Get pool utilization statistics.
     */
    @Query("SELECT p.poolCode, p.poolSize, p.availableCount, " +
           "(p.poolSize - p.availableCount) as assigned, " +
           "((p.poolSize - p.availableCount) * 100.0 / p.poolSize) as utilizationPercent " +
           "FROM VibanPool p WHERE p.programId = :programId")
    List<Object[]> getPoolUtilization(@Param("programId") UUID programId);

    /**
     * Count pools by status for a program.
     */
    @Query("SELECT p.status, COUNT(p) FROM VibanPool p WHERE p.programId = :programId GROUP BY p.status")
    List<Object[]> countByStatusForProgram(@Param("programId") UUID programId);

    // ========================================================================
    // Update queries
    // ========================================================================

    /**
     * Decrement available count.
     */
    @Modifying
    @Query("UPDATE VibanPool p SET p.availableCount = p.availableCount - 1, " +
           "p.status = CASE WHEN p.availableCount <= 1 THEN 'EXHAUSTED' ELSE p.status END " +
           "WHERE p.id = :id AND p.availableCount > 0")
    int decrementAvailable(@Param("id") UUID id);

    /**
     * Decrement available count by amount.
     */
    @Modifying
    @Query("UPDATE VibanPool p SET p.availableCount = p.availableCount - :count, " +
           "p.status = CASE WHEN p.availableCount <= :count THEN 'EXHAUSTED' ELSE p.status END " +
           "WHERE p.id = :id AND p.availableCount >= :count")
    int decrementAvailableBy(@Param("id") UUID id, @Param("count") int count);

    /**
     * Increment available count.
     */
    @Modifying
    @Query("UPDATE VibanPool p SET p.availableCount = LEAST(p.availableCount + 1, p.poolSize), " +
           "p.status = CASE WHEN p.status = 'EXHAUSTED' THEN 'ACTIVE' ELSE p.status END " +
           "WHERE p.id = :id")
    int incrementAvailable(@Param("id") UUID id);

    /**
     * Increment available count by amount.
     */
    @Modifying
    @Query("UPDATE VibanPool p SET p.availableCount = LEAST(p.availableCount + :count, p.poolSize), " +
           "p.status = CASE WHEN p.status = 'EXHAUSTED' AND :count > 0 THEN 'ACTIVE' ELSE p.status END " +
           "WHERE p.id = :id")
    int incrementAvailableBy(@Param("id") UUID id, @Param("count") int count);

    /**
     * Update pool status.
     */
    @Modifying
    @Query("UPDATE VibanPool p SET p.status = :status WHERE p.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("status") String status);

    /**
     * Update pool size (e.g., when adding more VIBANs).
     */
    @Modifying
    @Query("UPDATE VibanPool p SET p.poolSize = :newSize, " +
           "p.availableCount = p.availableCount + (:newSize - p.poolSize), " +
           "p.status = CASE WHEN p.availableCount + (:newSize - p.poolSize) > 0 " +
           "AND p.status = 'EXHAUSTED' THEN 'ACTIVE' ELSE p.status END " +
           "WHERE p.id = :id")
    int expandPool(@Param("id") UUID id, @Param("newSize") int newSize);

    /**
     * Reserve VIBANs.
     */
    @Modifying
    @Query("UPDATE VibanPool p SET p.availableCount = p.availableCount - :count, " +
           "p.reservedCount = p.reservedCount + :count " +
           "WHERE p.id = :id AND p.availableCount >= :count")
    int reserve(@Param("id") UUID id, @Param("count") int count);

    /**
     * Release reservation.
     */
    @Modifying
    @Query("UPDATE VibanPool p SET p.availableCount = p.availableCount + :count, " +
           "p.reservedCount = GREATEST(p.reservedCount - :count, 0), " +
           "p.status = CASE WHEN p.status = 'EXHAUSTED' THEN 'ACTIVE' ELSE p.status END " +
           "WHERE p.id = :id")
    int releaseReservation(@Param("id") UUID id, @Param("count") int count);

    /**
     * Confirm reservation (convert to assignment).
     */
    @Modifying
    @Query("UPDATE VibanPool p SET p.reservedCount = GREATEST(p.reservedCount - :count, 0) " +
           "WHERE p.id = :id")
    int confirmReservation(@Param("id") UUID id, @Param("count") int count);

    // ========================================================================
    // Delete queries
    // ========================================================================

    /**
     * Delete by program.
     */
    void deleteByProgramId(UUID programId);
}