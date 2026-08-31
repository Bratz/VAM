package com.bank.vam.repository.treasury;

import com.bank.vam.entity.treasury.SweepExecution;
import com.bank.vam.entity.treasury.SweepExecution.ExecutionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface SweepExecutionRepository extends JpaRepository<SweepExecution, UUID> {

    Page<SweepExecution> findByRuleId(UUID ruleId, Pageable pageable);

    List<SweepExecution> findByStatus(ExecutionStatus status);

    @Query("SELECT e FROM SweepExecution e WHERE e.executionTime >= :startTime ORDER BY e.executionTime DESC")
    List<SweepExecution> findRecentExecutions(@Param("startTime") LocalDateTime startTime);

    @Query("SELECT e FROM SweepExecution e WHERE e.rule.id = :ruleId ORDER BY e.executionTime DESC")
    List<SweepExecution> findByRuleIdOrderByExecutionTimeDesc(@Param("ruleId") UUID ruleId);

    @Query("SELECT e FROM SweepExecution e ORDER BY e.executionTime DESC")
    Page<SweepExecution> findAllOrderByExecutionTimeDesc(Pageable pageable);

    @Query("SELECT COUNT(e) FROM SweepExecution e WHERE e.status = 'SUCCESS' AND e.executionTime >= :startTime")
    long countSuccessfulSince(@Param("startTime") LocalDateTime startTime);

    @Query("SELECT COALESCE(SUM(e.sweepAmount), 0) FROM SweepExecution e WHERE e.status = 'SUCCESS' AND e.executionTime >= :startTime")
    BigDecimal sumSweptSince(@Param("startTime") LocalDateTime startTime);
    
    // ============================================================================
    // DASHBOARD METHODS
    // ============================================================================
    
    /**
     * Sum total swept amount since a given time (use for "today" by passing start of day)
     */
    @Query("SELECT COALESCE(SUM(e.sweepAmount), 0) FROM SweepExecution e " +
           "WHERE e.status = 'SUCCESS' AND e.executionTime >= :since")
    BigDecimal sumTotalSweptSince(@Param("since") LocalDateTime since);
    
    /**
     * Count executions since a given time
     */
    @Query("SELECT COUNT(e) FROM SweepExecution e WHERE e.executionTime >= :since")
    long countExecutionsSince(@Param("since") LocalDateTime since);
    
    /**
     * Count successful executions since a given time
     */
    @Query("SELECT COUNT(e) FROM SweepExecution e WHERE e.status = 'SUCCESS' AND e.executionTime >= :since")
    long countSuccessfulExecutionsSince(@Param("since") LocalDateTime since);
    
    /**
     * Count failed executions since a given time
     */
    @Query("SELECT COUNT(e) FROM SweepExecution e WHERE e.status = 'FAILED' AND e.executionTime >= :since")
    long countFailedExecutionsSince(@Param("since") LocalDateTime since);
    
    /**
     * Get execution status summary since a given time
     */
    @Query("SELECT e.status, COUNT(e), COALESCE(SUM(e.sweepAmount), 0) FROM SweepExecution e " +
           "WHERE e.executionTime >= :since GROUP BY e.status")
    List<Object[]> getStatusSummary(@Param("since") LocalDateTime since);
    
    /**
     * Get recent executions with pagination
     */
    @Query("SELECT e FROM SweepExecution e ORDER BY e.executionTime DESC")
    Page<SweepExecution> findRecentExecutionsPage(Pageable pageable);

    // ========================================================================
    // SETTLEMENT SERVICE QUERIES (Option B Architecture)
    // ========================================================================

    /**
     * Find execution by execution reference.
     */
    java.util.Optional<SweepExecution> findByExecutionReference(String executionReference);

    /**
     * Find all COMMITTED executions (awaiting settlement).
     */
    @Query("SELECT e FROM SweepExecution e WHERE e.status = 'COMMITTED'")
    List<SweepExecution> findAllCommitted();

    /**
     * Find COMMITTED executions since a given time.
     */
    @Query("SELECT e FROM SweepExecution e WHERE e.status = 'COMMITTED' AND e.executionTime >= :since")
    List<SweepExecution> findCommittedSince(@Param("since") LocalDateTime since);

    /**
     * Sum committed sweep amounts.
     */
    @Query("SELECT COALESCE(SUM(e.sweepAmount), 0) FROM SweepExecution e WHERE e.status = 'COMMITTED'")
    BigDecimal sumCommittedAmount();

    /**
     * Count COMMITTED executions.
     */
    @Query("SELECT COUNT(e) FROM SweepExecution e WHERE e.status = 'COMMITTED'")
    long countCommitted();
}
