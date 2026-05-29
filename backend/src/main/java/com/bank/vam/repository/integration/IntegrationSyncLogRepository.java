package com.bank.vam.repository.integration;

import com.bank.vam.entity.integration.IntegrationSyncLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface IntegrationSyncLogRepository extends JpaRepository<IntegrationSyncLog, UUID> {
    
    /**
     * Find sync logs by connection ID
     */
    Page<IntegrationSyncLog> findByConnectionId(UUID connectionId, Pageable pageable);

    /**
     * Find sync logs by flow ID
     */
    Page<IntegrationSyncLog> findByFlowId(UUID flowId, Pageable pageable);

    /**
     * Find sync logs by status - Used by IntegrationService.getSyncLogs(filter, pageable)
     */
    Page<IntegrationSyncLog> findByStatus(IntegrationSyncLog.SyncStatus status, Pageable pageable);

    /**
     * Find all sync logs ordered by start time descending
     */
    @Query("SELECT l FROM IntegrationSyncLog l ORDER BY l.startTime DESC")
    Page<IntegrationSyncLog> findAllOrderByStartTimeDesc(Pageable pageable);

    /**
     * Find recent sync logs by connection ID
     */
    @Query("SELECT l FROM IntegrationSyncLog l WHERE l.connection.id = :connectionId ORDER BY l.startTime DESC")
    List<IntegrationSyncLog> findRecentByConnectionId(@Param("connectionId") UUID connectionId, Pageable pageable);

    /**
     * Find sync logs by flow ID (list version)
     */
    @Query("SELECT l FROM IntegrationSyncLog l WHERE l.flow.id = :flowId ORDER BY l.startTime DESC")
    List<IntegrationSyncLog> findByFlowIdList(@Param("flowId") UUID flowId);

    /**
     * Find sync logs within date range
     */
    @Query("SELECT l FROM IntegrationSyncLog l WHERE l.startTime BETWEEN :startDate AND :endDate ORDER BY l.startTime DESC")
    Page<IntegrationSyncLog> findByDateRange(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            Pageable pageable);

    /**
     * Find sync logs by connection and status
     */
    @Query("SELECT l FROM IntegrationSyncLog l WHERE l.connection.id = :connectionId AND l.status = :status ORDER BY l.startTime DESC")
    Page<IntegrationSyncLog> findByConnectionIdAndStatus(
            @Param("connectionId") UUID connectionId,
            @Param("status") IntegrationSyncLog.SyncStatus status,
            Pageable pageable);

    /**
     * Count sync logs by status
     */
    long countByStatus(IntegrationSyncLog.SyncStatus status);

    /**
     * Count sync logs by connection ID
     */
    long countByConnectionId(UUID connectionId);

    /**
     * Count sync logs by connection ID and status
     */
    long countByConnectionIdAndStatus(UUID connectionId, IntegrationSyncLog.SyncStatus status);

    /**
     * Find failed sync logs for retry
     */
    @Query("SELECT l FROM IntegrationSyncLog l WHERE l.status = 'FAILED' AND l.startTime > :since ORDER BY l.startTime DESC")
    List<IntegrationSyncLog> findFailedSince(@Param("since") LocalDateTime since);

    /**
     * Find running sync logs (potentially stuck)
     * FIX: Changed from 'IN_PROGRESS' to 'RUNNING' to match entity enum
     */
    @Query("SELECT l FROM IntegrationSyncLog l WHERE l.status = 'RUNNING' AND l.startTime < :threshold")
    List<IntegrationSyncLog> findStuckSyncs(@Param("threshold") LocalDateTime threshold);

    /**
     * Get total records processed today
     */
    @Query("SELECT COALESCE(SUM(l.recordsProcessed), 0) FROM IntegrationSyncLog l WHERE l.startTime >= :today")
    long sumRecordsProcessedSince(@Param("today") LocalDateTime today);

    /**
     * Get total records failed today
     */
    @Query("SELECT COALESCE(SUM(l.recordsFailed), 0) FROM IntegrationSyncLog l WHERE l.startTime >= :today")
    long sumRecordsFailedSince(@Param("today") LocalDateTime today);

    /**
     * Count syncs today
     */
    @Query("SELECT COUNT(l) FROM IntegrationSyncLog l WHERE l.startTime >= :today")
    long countSyncsSince(@Param("today") LocalDateTime today);
}