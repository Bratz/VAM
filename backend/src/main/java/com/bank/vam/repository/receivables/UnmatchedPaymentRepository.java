package com.bank.vam.repository.receivables;

import com.bank.vam.entity.receivables.UnmatchedPayment;
import com.bank.vam.entity.receivables.UnmatchedPayment.UnmatchedStatus;
import com.bank.vam.entity.receivables.UnmatchedPayment.UnmatchedPriority;
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
 * Repository for UnmatchedPayment entity.
 * 
 * ENHANCED for Phase 3: Added queries for:
 * - Aged payment processing
 * - Exception VA routing
 * - Program-based filtering
 * - Priority management
 * - Dashboard statistics
 */
@Repository
public interface UnmatchedPaymentRepository extends JpaRepository<UnmatchedPayment, UUID> {

    // ========================================================================
    // BASIC LOOKUPS
    // ========================================================================

    Optional<UnmatchedPayment> findByTransactionId(UUID transactionId);
    
    Optional<UnmatchedPayment> findByPaymentReference(String paymentReference);
    
    /**
     * Find by bank reference (for bank statement reconciliation).
     * NEW: Added for bank statement processing.
     */
    Optional<UnmatchedPayment> findByBankReference(String bankReference);
    
    List<UnmatchedPayment> findByVibanId(UUID vibanId);
    
    List<UnmatchedPayment> findByVirtualAccountId(UUID virtualAccountId);
    
    /**
     * Find by program ID.
     * NEW: Added for multi-program support.
     */
    List<UnmatchedPayment> findByProgramId(UUID programId);
    
    /**
     * Find by program ID with pagination.
     * NEW: Added for multi-program support.
     */
    Page<UnmatchedPayment> findByProgramId(UUID programId, Pageable pageable);

    // ========================================================================
    // STATUS QUERIES
    // ========================================================================

    List<UnmatchedPayment> findByStatus(UnmatchedStatus status);
    
    Page<UnmatchedPayment> findByStatus(UnmatchedStatus status, Pageable pageable);
    
    long countByStatus(UnmatchedStatus status);
    
    @Query("SELECT up.status, COUNT(up) FROM UnmatchedPayment up GROUP BY up.status")
    List<Object[]> countByStatusGrouped();
    
    /**
     * Find by status and program.
     * NEW: Added for program filtering.
     */
    List<UnmatchedPayment> findByStatusAndProgramId(UnmatchedStatus status, UUID programId);
    
    /**
     * Find by status and program with pagination.
     * NEW: Added for program filtering.
     */
    Page<UnmatchedPayment> findByStatusAndProgramId(UnmatchedStatus status, UUID programId, Pageable pageable);

    // ========================================================================
    // PENDING QUERIES
    // ========================================================================

    /**
     * Find all pending unmatched payments.
     */
    @Query("SELECT up FROM UnmatchedPayment up WHERE up.status = 'PENDING' ORDER BY up.paymentDate DESC")
    List<UnmatchedPayment> findAllPending();
    
    /**
     * Find all pending with pagination.
     * NEW: Added for large datasets.
     */
    @Query("SELECT up FROM UnmatchedPayment up WHERE up.status = 'PENDING' ORDER BY up.paymentDate DESC")
    Page<UnmatchedPayment> findAllPending(Pageable pageable);

    /**
     * Find pending by virtual account.
     */
    @Query("SELECT up FROM UnmatchedPayment up WHERE up.virtualAccountId = :vaId AND up.status = 'PENDING' ORDER BY up.paymentDate DESC")
    List<UnmatchedPayment> findPendingByVirtualAccount(@Param("vaId") UUID vaId);
    
    /**
     * Find pending by program.
     * NEW: Added for program filtering.
     */
    @Query("SELECT up FROM UnmatchedPayment up WHERE up.programId = :programId AND up.status = 'PENDING' ORDER BY up.paymentDate DESC")
    List<UnmatchedPayment> findPendingByProgram(@Param("programId") UUID programId);

    /**
     * Count pending.
     */
    @Query("SELECT COUNT(up) FROM UnmatchedPayment up WHERE up.status = 'PENDING'")
    long countPending();
    
    /**
     * Count pending by program.
     * NEW: Added for program filtering.
     */
    @Query("SELECT COUNT(up) FROM UnmatchedPayment up WHERE up.programId = :programId AND up.status = 'PENDING'")
    long countPendingByProgram(@Param("programId") UUID programId);

    // ========================================================================
    // AGED PAYMENT QUERIES (NEW - For Exception VA Routing)
    // ========================================================================

    /**
     * Find aged pending payments (older than cutoff date).
     * Used by batch job to move aged payments to Exception VA.
     * NEW: Core query for aged payment processing.
     */
    @Query("SELECT up FROM UnmatchedPayment up " +
           "WHERE up.status = 'PENDING' " +
           "AND up.paymentDate < :cutoffDate " +
           "ORDER BY up.paymentDate ASC")
    List<UnmatchedPayment> findAgedPendingPayments(@Param("cutoffDate") LocalDateTime cutoffDate);
    
    /**
     * Find aged pending payments by program.
     * NEW: Added for program-specific batch processing.
     */
    @Query("SELECT up FROM UnmatchedPayment up " +
           "WHERE up.programId = :programId " +
           "AND up.status = 'PENDING' " +
           "AND up.paymentDate < :cutoffDate " +
           "ORDER BY up.paymentDate ASC")
    List<UnmatchedPayment> findAgedPendingPaymentsByProgram(
            @Param("programId") UUID programId,
            @Param("cutoffDate") LocalDateTime cutoffDate);
    
    /**
     * Find payments ready for auto-escalation.
     * NEW: Added for auto-escalation workflow.
     */
    @Query("SELECT up FROM UnmatchedPayment up " +
           "WHERE up.status = 'PENDING' " +
           "AND up.paymentDate < :escalationCutoff " +
           "AND up.paymentDate >= :exceptionCutoff " +
           "ORDER BY up.paymentDate ASC")
    List<UnmatchedPayment> findPaymentsForAutoEscalation(
            @Param("escalationCutoff") LocalDateTime escalationCutoff,
            @Param("exceptionCutoff") LocalDateTime exceptionCutoff);
    
    /**
     * Find payments ready for exception routing (escalated and still aged).
     * NEW: Added for Exception VA batch processing.
     */
    @Query("SELECT up FROM UnmatchedPayment up " +
           "WHERE up.status IN ('PENDING', 'ESCALATED') " +
           "AND up.paymentDate < :cutoffDate " +
           "ORDER BY up.paymentDate ASC")
    List<UnmatchedPayment> findPaymentsForExceptionRouting(@Param("cutoffDate") LocalDateTime cutoffDate);
    
    /**
     * Count aged pending payments.
     * NEW: Added for dashboard statistics.
     */
    @Query("SELECT COUNT(up) FROM UnmatchedPayment up " +
           "WHERE up.status = 'PENDING' " +
           "AND up.paymentDate < :cutoffDate")
    long countAgedPendingPayments(@Param("cutoffDate") LocalDateTime cutoffDate);
    
    /**
     * Sum aged pending amount.
     * NEW: Added for dashboard statistics.
     */
    @Query("SELECT COALESCE(SUM(up.amount), 0) FROM UnmatchedPayment up " +
           "WHERE up.status = 'PENDING' " +
           "AND up.paymentDate < :cutoffDate")
    BigDecimal sumAgedPendingAmount(@Param("cutoffDate") LocalDateTime cutoffDate);

    // ========================================================================
    // EXCEPTION VA ROUTING QUERIES (NEW)
    // ========================================================================

    /**
     * Find payments moved to Exception VA.
     * NEW: Added for Exception VA tracking.
     */
    @Query("SELECT up FROM UnmatchedPayment up " +
           "WHERE up.status = 'MOVED_TO_EXCEPTION' " +
           "ORDER BY up.movedToExceptionAt DESC")
    List<UnmatchedPayment> findMovedToException();
    
    /**
     * Find payments moved to Exception VA with pagination.
     * NEW: Added for Exception VA tracking.
     */
    @Query("SELECT up FROM UnmatchedPayment up " +
           "WHERE up.status = 'MOVED_TO_EXCEPTION' " +
           "ORDER BY up.movedToExceptionAt DESC")
    Page<UnmatchedPayment> findMovedToException(Pageable pageable);
    
    /**
     * Find by exception transaction ID.
     * NEW: Added for Exception VA tracking.
     */
    Optional<UnmatchedPayment> findByExceptionTransactionId(UUID exceptionTransactionId);
    
    /**
     * Count moved to exception.
     * NEW: Added for dashboard statistics.
     */
    @Query("SELECT COUNT(up) FROM UnmatchedPayment up WHERE up.status = 'MOVED_TO_EXCEPTION'")
    long countMovedToException();
    
    /**
     * Sum moved to exception amount.
     * NEW: Added for dashboard statistics.
     */
    @Query("SELECT COALESCE(SUM(up.amount), 0) FROM UnmatchedPayment up WHERE up.status = 'MOVED_TO_EXCEPTION'")
    BigDecimal sumMovedToExceptionAmount();

    // ========================================================================
    // PRIORITY QUERIES (NEW)
    // ========================================================================

    /**
     * Find by priority.
     * NEW: Added for priority-based handling.
     */
    List<UnmatchedPayment> findByPriority(UnmatchedPriority priority);
    
    /**
     * Find pending by priority.
     * NEW: Added for priority-based handling.
     */
    @Query("SELECT up FROM UnmatchedPayment up " +
           "WHERE up.status = 'PENDING' " +
           "AND up.priority = :priority " +
           "ORDER BY up.paymentDate ASC")
    List<UnmatchedPayment> findPendingByPriority(@Param("priority") UnmatchedPriority priority);
    
    /**
     * Find urgent pending payments.
     * NEW: Added for urgent payment handling.
     */
    @Query("SELECT up FROM UnmatchedPayment up " +
           "WHERE up.status = 'PENDING' " +
           "AND up.priority IN ('HIGH', 'URGENT') " +
           "ORDER BY up.priority DESC, up.paymentDate ASC")
    List<UnmatchedPayment> findUrgentPending();
    
    /**
     * Count by priority.
     * NEW: Added for dashboard statistics.
     */
    @Query("SELECT up.priority, COUNT(up) FROM UnmatchedPayment up " +
           "WHERE up.status = 'PENDING' " +
           "GROUP BY up.priority")
    List<Object[]> countPendingByPriority();

    // ========================================================================
    // AMOUNT QUERIES
    // ========================================================================

    /**
     * Sum pending amount.
     */
    @Query("SELECT COALESCE(SUM(up.amount), 0) FROM UnmatchedPayment up WHERE up.status = 'PENDING'")
    BigDecimal sumPendingAmount();

    /**
     * Sum pending by virtual account.
     */
    @Query("SELECT COALESCE(SUM(up.amount), 0) FROM UnmatchedPayment up WHERE up.virtualAccountId = :vaId AND up.status = 'PENDING'")
    BigDecimal sumPendingByVirtualAccount(@Param("vaId") UUID vaId);
    
    /**
     * Sum pending by program.
     * NEW: Added for program statistics.
     */
    @Query("SELECT COALESCE(SUM(up.amount), 0) FROM UnmatchedPayment up " +
           "WHERE up.programId = :programId AND up.status = 'PENDING'")
    BigDecimal sumPendingByProgram(@Param("programId") UUID programId);
    
    /**
     * Find large unmatched payments (for priority handling).
     * NEW: Added for large payment handling.
     */
    @Query("SELECT up FROM UnmatchedPayment up " +
           "WHERE up.status = 'PENDING' " +
           "AND up.amount >= :threshold " +
           "ORDER BY up.amount DESC")
    List<UnmatchedPayment> findLargePendingPayments(@Param("threshold") BigDecimal threshold);

    // ========================================================================
    // DATE QUERIES
    // ========================================================================

    /**
     * Find by payment date range.
     */
    @Query("SELECT up FROM UnmatchedPayment up WHERE up.paymentDate BETWEEN :startDate AND :endDate ORDER BY up.paymentDate DESC")
    List<UnmatchedPayment> findByPaymentDateBetween(@Param("startDate") LocalDateTime startDate, 
                                                     @Param("endDate") LocalDateTime endDate);

    /**
     * Find old pending (for escalation).
     */
    @Query("SELECT up FROM UnmatchedPayment up WHERE up.status = 'PENDING' AND up.paymentDate < :cutoffDate ORDER BY up.paymentDate ASC")
    List<UnmatchedPayment> findOldPending(@Param("cutoffDate") LocalDateTime cutoffDate);
    
    /**
     * Find by created date range (for audit).
     * NEW: Added for audit queries.
     */
    @Query("SELECT up FROM UnmatchedPayment up " +
           "WHERE up.createdAt BETWEEN :startDate AND :endDate " +
           "ORDER BY up.createdAt DESC")
    List<UnmatchedPayment> findByCreatedDateBetween(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    // ========================================================================
    // ESCALATION QUERIES
    // ========================================================================

    /**
     * Find escalated payments.
     */
    @Query("SELECT up FROM UnmatchedPayment up WHERE up.status = 'ESCALATED' ORDER BY up.escalatedAt DESC")
    List<UnmatchedPayment> findEscalated();
    
    /**
     * Find escalated payments with pagination.
     * NEW: Added for large datasets.
     */
    @Query("SELECT up FROM UnmatchedPayment up WHERE up.status = 'ESCALATED' ORDER BY up.escalatedAt DESC")
    Page<UnmatchedPayment> findEscalated(Pageable pageable);

    /**
     * Find escalated to specific user.
     */
    List<UnmatchedPayment> findByEscalatedTo(String escalatedTo);
    
    /**
     * Count escalated.
     * NEW: Added for dashboard statistics.
     */
    @Query("SELECT COUNT(up) FROM UnmatchedPayment up WHERE up.status = 'ESCALATED'")
    long countEscalated();
    
    /**
     * Count escalated by user.
     * NEW: Added for workload tracking.
     */
    @Query("SELECT up.escalatedTo, COUNT(up) FROM UnmatchedPayment up " +
           "WHERE up.status = 'ESCALATED' " +
           "GROUP BY up.escalatedTo")
    List<Object[]> countEscalatedByUser();

    // ========================================================================
    // MATCH ATTEMPT QUERIES
    // ========================================================================

    /**
     * Find payments with multiple failed match attempts.
     */
    @Query("SELECT up FROM UnmatchedPayment up WHERE up.status = 'PENDING' AND up.matchAttempts >= :minAttempts ORDER BY up.matchAttempts DESC")
    List<UnmatchedPayment> findWithMultipleMatchAttempts(@Param("minAttempts") Integer minAttempts);
    
    /**
     * Find payments with low match confidence.
     * NEW: Added for manual review prioritization.
     */
    @Query("SELECT up FROM UnmatchedPayment up " +
           "WHERE up.status = 'PENDING' " +
           "AND up.bestMatchConfidence IS NOT NULL " +
           "AND up.bestMatchConfidence < :threshold " +
           "ORDER BY up.bestMatchConfidence ASC")
    List<UnmatchedPayment> findLowConfidenceMatches(@Param("threshold") Integer threshold);

    // ========================================================================
    // RESOLUTION QUERIES
    // ========================================================================

    /**
     * Find recently resolved.
     */
    @Query("SELECT up FROM UnmatchedPayment up WHERE up.resolvedAt IS NOT NULL ORDER BY up.resolvedAt DESC")
    Page<UnmatchedPayment> findRecentlyResolved(Pageable pageable);

    /**
     * Find resolved by user.
     */
    List<UnmatchedPayment> findByResolvedBy(String resolvedBy);
    
    /**
     * Find resolved in date range.
     * NEW: Added for audit queries.
     */
    @Query("SELECT up FROM UnmatchedPayment up " +
           "WHERE up.resolvedAt BETWEEN :startDate AND :endDate " +
           "ORDER BY up.resolvedAt DESC")
    List<UnmatchedPayment> findResolvedInDateRange(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);
    
    /**
     * Count resolved by resolution type.
     * NEW: Added for resolution statistics.
     */
    @Query("SELECT up.resolutionType, COUNT(up) FROM UnmatchedPayment up " +
           "WHERE up.resolvedAt IS NOT NULL " +
           "GROUP BY up.resolutionType")
    List<Object[]> countByResolutionType();
    
    /**
     * Count resolved today.
     * NEW: Added for dashboard statistics.
     */
    @Query("SELECT COUNT(up) FROM UnmatchedPayment up " +
           "WHERE up.resolvedAt >= :startOfDay")
    long countResolvedToday(@Param("startOfDay") LocalDateTime startOfDay);

    // ========================================================================
    // PAYER QUERIES (NEW)
    // ========================================================================

    /**
     * Find by payer name (partial match).
     * NEW: Added for payer search.
     */
    @Query("SELECT up FROM UnmatchedPayment up " +
           "WHERE LOWER(up.payerName) LIKE LOWER(CONCAT('%', :payerName, '%')) " +
           "ORDER BY up.paymentDate DESC")
    List<UnmatchedPayment> findByPayerNameContaining(@Param("payerName") String payerName);
    
    /**
     * Find by payer account.
     * NEW: Added for payer search.
     */
    List<UnmatchedPayment> findByPayerAccount(String payerAccount);
    
    /**
     * Find repeat payers (same payer with multiple unmatched).
     * NEW: Added for pattern detection.
     */
    @Query("SELECT up.payerName, COUNT(up) as cnt, SUM(up.amount) as total " +
           "FROM UnmatchedPayment up " +
           "WHERE up.status = 'PENDING' " +
           "AND up.payerName IS NOT NULL " +
           "GROUP BY up.payerName " +
           "HAVING COUNT(up) > 1 " +
           "ORDER BY COUNT(up) DESC")
    List<Object[]> findRepeatPayers();

    // ========================================================================
    // BATCH UPDATE OPERATIONS (NEW)
    // ========================================================================

    /**
     * Bulk update priority for aged payments.
     * NEW: Added for batch processing.
     */
    @Modifying
    @Query("UPDATE UnmatchedPayment up " +
           "SET up.priority = :newPriority " +
           "WHERE up.status = 'PENDING' " +
           "AND up.paymentDate < :cutoffDate " +
           "AND up.priority = :currentPriority")
    int bulkUpdatePriorityForAgedPayments(
            @Param("cutoffDate") LocalDateTime cutoffDate,
            @Param("currentPriority") UnmatchedPriority currentPriority,
            @Param("newPriority") UnmatchedPriority newPriority);
    
    /**
     * Bulk escalate aged payments.
     * NEW: Added for batch processing.
     */
    @Modifying
    @Query("UPDATE UnmatchedPayment up " +
           "SET up.status = 'ESCALATED', " +
           "    up.escalatedAt = :now, " +
           "    up.escalatedTo = :escalateTo, " +
           "    up.escalationReason = :reason " +
           "WHERE up.status = 'PENDING' " +
           "AND up.paymentDate < :cutoffDate")
    int bulkEscalateAgedPayments(
            @Param("cutoffDate") LocalDateTime cutoffDate,
            @Param("escalateTo") String escalateTo,
            @Param("reason") String reason,
            @Param("now") LocalDateTime now);

    // ========================================================================
    // DASHBOARD STATISTICS (NEW) - SIMPLIFIED FOR PORTABILITY
    // ========================================================================

    /**
     * Get summary statistics for dashboard.
     * Returns: [totalPending, totalAmount]
     * Note: Average days calculation should be done in service layer for portability.
     * NEW: Added for dashboard.
     */
    @Query("SELECT COUNT(up), COALESCE(SUM(up.amount), 0) " +
           "FROM UnmatchedPayment up " +
           "WHERE up.status = 'PENDING'")
    List<Object[]> getDashboardStats();
    
    /**
     * Get oldest pending payment date (for average age calculation in service).
     * NEW: Added for dashboard statistics.
     */
    @Query("SELECT MIN(up.paymentDate) FROM UnmatchedPayment up WHERE up.status = 'PENDING'")
    LocalDateTime getOldestPendingPaymentDate();
    
    /**
     * Count pending in date ranges for aging buckets.
     * Call multiple times with different date ranges.
     * NEW: Added for aging distribution.
     */
    @Query("SELECT COUNT(up), COALESCE(SUM(up.amount), 0) " +
           "FROM UnmatchedPayment up " +
           "WHERE up.status = 'PENDING' " +
           "AND up.paymentDate >= :fromDate " +
           "AND up.paymentDate < :toDate")
    List<Object[]> countPendingInDateRange(
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate);
    
    /**
     * Count pending before date (for "older than X" bucket).
     * NEW: Added for aging distribution.
     */
    @Query("SELECT COUNT(up), COALESCE(SUM(up.amount), 0) " +
           "FROM UnmatchedPayment up " +
           "WHERE up.status = 'PENDING' " +
           "AND up.paymentDate < :beforeDate")
    List<Object[]> countPendingBeforeDate(@Param("beforeDate") LocalDateTime beforeDate);

    /**
     * Get daily counts for trend analysis.
     * Note: Groups by truncated date - service layer handles date extraction.
     * NEW: Added for trend analysis.
     */
    @Query("SELECT up.paymentDate, COUNT(up), SUM(up.amount) " +
           "FROM UnmatchedPayment up " +
           "WHERE up.paymentDate >= :startDate " +
           "GROUP BY up.paymentDate " +
           "ORDER BY up.paymentDate DESC")
    List<Object[]> getDailyTrend(@Param("startDate") LocalDateTime startDate);
}