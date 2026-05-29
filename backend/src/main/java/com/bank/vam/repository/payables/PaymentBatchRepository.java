package com.bank.vam.repository.payables;

import com.bank.vam.entity.payables.PaymentBatch;
import com.bank.vam.entity.payables.PaymentBatch.BatchStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for PaymentBatch entity.
 */
@Repository
public interface PaymentBatchRepository extends JpaRepository<PaymentBatch, UUID> {

    // ========================================================================
    // BASIC LOOKUPS
    // ========================================================================

    Optional<PaymentBatch> findByBatchReference(String batchReference);

    // ========================================================================
    // CORPORATE QUERIES
    // ========================================================================

    Page<PaymentBatch> findByCorporateId(UUID corporateId, Pageable pageable);
    
    List<PaymentBatch> findByCorporateId(UUID corporateId);
    
    List<PaymentBatch> findByCorporateIdAndStatus(UUID corporateId, BatchStatus status);
    
    long countByCorporateIdAndStatus(UUID corporateId, BatchStatus status);

    // ========================================================================
    // STATUS QUERIES
    // ========================================================================

    List<PaymentBatch> findByStatus(BatchStatus status);
    
    List<PaymentBatch> findByStatusIn(List<BatchStatus> statuses);
    
    @Query("SELECT b FROM PaymentBatch b WHERE b.status = 'PENDING_APPROVAL' ORDER BY b.createdAt ASC")
    List<PaymentBatch> findPendingApproval();
    
    @Query("SELECT b FROM PaymentBatch b WHERE b.status = 'APPROVED' ORDER BY b.scheduledDate ASC")
    List<PaymentBatch> findApproved();

    // ========================================================================
    // SCHEDULED QUERIES
    // ========================================================================

    @Query("SELECT b FROM PaymentBatch b WHERE b.scheduledDate = :date AND b.status = 'APPROVED'")
    List<PaymentBatch> findScheduledForDate(@Param("date") LocalDate date);

    @Query("SELECT b FROM PaymentBatch b WHERE b.scheduledDate <= :date AND b.status = 'APPROVED' ORDER BY b.scheduledDate ASC")
    List<PaymentBatch> findReadyForExecution(@Param("date") LocalDate date);

    // ========================================================================
    // AMOUNT QUERIES
    // ========================================================================

    @Query("SELECT COALESCE(SUM(b.totalAmount), 0) FROM PaymentBatch b WHERE b.corporateId = :corporateId AND b.status = 'PENDING_APPROVAL'")
    BigDecimal sumPendingApprovalByCorporate(@Param("corporateId") UUID corporateId);

    @Query("SELECT COALESCE(SUM(b.totalAmount), 0) FROM PaymentBatch b WHERE b.corporateId = :corporateId AND b.status IN ('APPROVED', 'SCHEDULED')")
    BigDecimal sumScheduledByCorporate(@Param("corporateId") UUID corporateId);

    // ========================================================================
    // UPDATE QUERIES
    // ========================================================================

    @Modifying
    @Query("UPDATE PaymentBatch b SET b.status = :status, b.updatedAt = CURRENT_TIMESTAMP WHERE b.id = :id")
    void updateStatus(@Param("id") UUID id, @Param("status") BatchStatus status);

    @Modifying
    @Query("UPDATE PaymentBatch b SET b.status = 'APPROVED', b.approvedBy = :approvedBy, b.approvedAt = :approvedAt, b.updatedAt = CURRENT_TIMESTAMP WHERE b.id = :id")
    void approve(@Param("id") UUID id, @Param("approvedBy") String approvedBy, @Param("approvedAt") LocalDateTime approvedAt);

    @Modifying
    @Query("UPDATE PaymentBatch b SET b.paymentCount = b.paymentCount + 1, b.totalAmount = b.totalAmount + :amount, b.updatedAt = CURRENT_TIMESTAMP WHERE b.id = :id")
    void addPayment(@Param("id") UUID id, @Param("amount") BigDecimal amount);

    @Modifying
    @Query("UPDATE PaymentBatch b SET b.successCount = b.successCount + 1, b.pendingCount = b.pendingCount - 1, b.updatedAt = CURRENT_TIMESTAMP WHERE b.id = :id")
    void recordSuccess(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE PaymentBatch b SET b.failedCount = b.failedCount + 1, b.pendingCount = b.pendingCount - 1, b.updatedAt = CURRENT_TIMESTAMP WHERE b.id = :id")
    void recordFailure(@Param("id") UUID id);

    // ========================================================================
    // SEARCH
    // ========================================================================

    @Query("SELECT b FROM PaymentBatch b WHERE " +
           "LOWER(b.batchReference) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(b.batchName) LIKE LOWER(CONCAT('%', :query, '%'))")
    Page<PaymentBatch> search(@Param("query") String query, Pageable pageable);

    // ========================================================================
    // STATISTICS
    // ========================================================================

    @Query("SELECT b.status, COUNT(b) FROM PaymentBatch b WHERE b.corporateId = :corporateId GROUP BY b.status")
    List<Object[]> countByStatusForCorporate(@Param("corporateId") UUID corporateId);

    @Query("SELECT COUNT(b) FROM PaymentBatch b WHERE b.corporateId = :corporateId AND b.createdAt >= :since")
    long countRecentBatches(@Param("corporateId") UUID corporateId, @Param("since") LocalDateTime since);
}