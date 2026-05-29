package com.bank.vam.repository.treasury;

import com.bank.vam.entity.treasury.PaymentRequest;
import com.bank.vam.entity.treasury.PaymentRequest.PaymentRequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for PaymentRequest entities.
 *
 * Provides queries for:
 * - Payment request lifecycle management
 * - Status tracking and reporting
 * - Batch payment processing
 */
@Repository
public interface PaymentRequestRepository extends JpaRepository<PaymentRequest, UUID> {

    // ========================================================================
    // BASIC LOOKUPS
    // ========================================================================

    Optional<PaymentRequest> findByRequestNumber(String requestNumber);

    Optional<PaymentRequest> findByPayableId(UUID payableId);

    Optional<PaymentRequest> findByTransactionId(UUID transactionId);

    Optional<PaymentRequest> findByPain001MessageId(String pain001MessageId);

    // ========================================================================
    // CORPORATE / ENTITY QUERIES
    // ========================================================================

    Page<PaymentRequest> findByCorporateId(UUID corporateId, Pageable pageable);

    Page<PaymentRequest> findByOwningEntityId(UUID owningEntityId, Pageable pageable);

    List<PaymentRequest> findByCorporateIdAndStatus(UUID corporateId, PaymentRequestStatus status);

    List<PaymentRequest> findByOwningEntityIdAndStatus(UUID owningEntityId, PaymentRequestStatus status);

    // ========================================================================
    // STATUS QUERIES
    // ========================================================================

    List<PaymentRequest> findByStatus(PaymentRequestStatus status);

    Page<PaymentRequest> findByStatus(PaymentRequestStatus status, Pageable pageable);

    @Query("SELECT pr FROM PaymentRequest pr WHERE pr.status IN :statuses ORDER BY pr.createdAt DESC")
    List<PaymentRequest> findByStatusIn(@Param("statuses") List<PaymentRequestStatus> statuses);

    // Pending requests awaiting processing
    @Query("SELECT pr FROM PaymentRequest pr WHERE pr.status = 'PENDING' ORDER BY pr.priority DESC, pr.createdAt ASC")
    List<PaymentRequest> findPendingRequests();

    // Submitted requests awaiting confirmation
    @Query("SELECT pr FROM PaymentRequest pr WHERE pr.status = 'SUBMITTED' ORDER BY pr.createdAt ASC")
    List<PaymentRequest> findSubmittedRequests();

    // Failed requests for retry
    @Query("SELECT pr FROM PaymentRequest pr WHERE pr.status = 'FAILED' AND (pr.retryCount IS NULL OR pr.retryCount < 3) ORDER BY pr.createdAt ASC")
    List<PaymentRequest> findFailedRequestsForRetry();

    // ========================================================================
    // DATE QUERIES
    // ========================================================================

    Page<PaymentRequest> findByRequestedDate(LocalDate requestedDate, Pageable pageable);

    Page<PaymentRequest> findByValueDate(LocalDate valueDate, Pageable pageable);

    @Query("SELECT pr FROM PaymentRequest pr WHERE pr.createdAt >= :startDate AND pr.createdAt < :endDate")
    Page<PaymentRequest> findByDateRange(
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate,
        Pageable pageable
    );

    @Query("SELECT pr FROM PaymentRequest pr WHERE pr.valueDate = :valueDate AND pr.status = 'PENDING'")
    List<PaymentRequest> findPendingByValueDate(@Param("valueDate") LocalDate valueDate);

    // ========================================================================
    // BATCH QUERIES
    // ========================================================================

    List<PaymentRequest> findByBatchId(UUID batchId);

    List<PaymentRequest> findByBatchReference(String batchReference);

    @Query("SELECT pr FROM PaymentRequest pr WHERE pr.batchId = :batchId ORDER BY pr.createdAt ASC")
    List<PaymentRequest> findByBatchIdOrdered(@Param("batchId") UUID batchId);

    // ========================================================================
    // POBO QUERIES
    // ========================================================================

    @Query("SELECT pr FROM PaymentRequest pr WHERE pr.isPobo = TRUE AND pr.payingEntityId = :payingEntityId")
    List<PaymentRequest> findPoboRequestsByPayingEntity(@Param("payingEntityId") UUID payingEntityId);

    @Query("SELECT pr FROM PaymentRequest pr WHERE pr.isPobo = TRUE AND pr.owningEntityId = :behalfEntityId")
    List<PaymentRequest> findPoboRequestsOnBehalfOf(@Param("behalfEntityId") UUID behalfEntityId);

    // ========================================================================
    // STATISTICS
    // ========================================================================

    long countByStatus(PaymentRequestStatus status);

    long countByCorporateIdAndStatus(UUID corporateId, PaymentRequestStatus status);

    @Query("SELECT SUM(pr.amount) FROM PaymentRequest pr WHERE pr.corporateId = :corporateId AND pr.status = :status")
    java.math.BigDecimal sumAmountByCorporateIdAndStatus(
        @Param("corporateId") UUID corporateId,
        @Param("status") PaymentRequestStatus status
    );

    @Query("SELECT SUM(pr.amount) FROM PaymentRequest pr WHERE pr.status = 'COMPLETED' AND pr.completedAt >= :since")
    java.math.BigDecimal sumCompletedAmountSince(@Param("since") LocalDateTime since);

    @Query("SELECT COUNT(pr) FROM PaymentRequest pr WHERE pr.status = 'COMPLETED' AND pr.completedAt >= :since")
    long countCompletedSince(@Param("since") LocalDateTime since);

    // ========================================================================
    // SEARCH
    // ========================================================================

    @Query("SELECT pr FROM PaymentRequest pr WHERE pr.corporateId = :corporateId " +
           "AND (:status IS NULL OR pr.status = :status) " +
           "AND (:searchTerm IS NULL OR :searchTerm = '' OR " +
           "    LOWER(pr.requestNumber) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "    LOWER(pr.payableNumber) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "    LOWER(pr.beneficiaryName) LIKE LOWER(CONCAT('%', :searchTerm, '%')))")
    Page<PaymentRequest> search(
        @Param("corporateId") UUID corporateId,
        @Param("status") PaymentRequestStatus status,
        @Param("searchTerm") String searchTerm,
        Pageable pageable
    );
}
