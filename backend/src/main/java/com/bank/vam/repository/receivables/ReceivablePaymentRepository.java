package com.bank.vam.repository.receivables;

import com.bank.vam.entity.receivables.ReceivablePayment;
import com.bank.vam.entity.receivables.ReceivablePayment.MatchType;
import com.bank.vam.entity.receivables.ReceivablePayment.PaymentMatchStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for ReceivablePayment entity.
 */
@Repository
public interface ReceivablePaymentRepository extends JpaRepository<ReceivablePayment, UUID> {

    // ========================================================================
    // BASIC LOOKUPS
    // ========================================================================

    List<ReceivablePayment> findByReceivableId(UUID receivableId);
    
    List<ReceivablePayment> findByReceivableIdOrderByPaymentDateDesc(UUID receivableId);
    
    Optional<ReceivablePayment> findByTransactionId(UUID transactionId);
    
    List<ReceivablePayment> findByVibanId(UUID vibanId);
    
    Optional<ReceivablePayment> findByPaymentReference(String paymentReference);

    // ========================================================================
    // STATUS QUERIES
    // ========================================================================

    List<ReceivablePayment> findByStatus(PaymentMatchStatus status);
    
    List<ReceivablePayment> findByReceivableIdAndStatus(UUID receivableId, PaymentMatchStatus status);
    
    long countByReceivableIdAndStatus(UUID receivableId, PaymentMatchStatus status);

    // ========================================================================
    // MATCH TYPE QUERIES
    // ========================================================================

    List<ReceivablePayment> findByMatchType(MatchType matchType);
    
    long countByMatchType(MatchType matchType);
    
    @Query("SELECT rp.matchType, COUNT(rp), COALESCE(SUM(rp.appliedAmount), 0) " +
           "FROM ReceivablePayment rp GROUP BY rp.matchType")
    List<Object[]> countByMatchTypeGrouped();

    // ========================================================================
    // AMOUNT QUERIES
    // ========================================================================

    /**
     * Sum applied amount for a receivable.
     */
    @Query("SELECT COALESCE(SUM(rp.appliedAmount), 0) FROM ReceivablePayment rp WHERE rp.receivableId = :receivableId AND rp.status = 'APPLIED'")
    BigDecimal sumAppliedAmountByReceivable(@Param("receivableId") UUID receivableId);

    /**
     * Sum unapplied amount for a receivable.
     */
    @Query("SELECT COALESCE(SUM(rp.unappliedAmount), 0) FROM ReceivablePayment rp WHERE rp.receivableId = :receivableId AND rp.status = 'APPLIED'")
    BigDecimal sumUnappliedAmountByReceivable(@Param("receivableId") UUID receivableId);

    // ========================================================================
    // DATE QUERIES
    // ========================================================================

    /**
     * Find payments within date range.
     */
    @Query("SELECT rp FROM ReceivablePayment rp WHERE rp.paymentDate BETWEEN :startDate AND :endDate ORDER BY rp.paymentDate DESC")
    List<ReceivablePayment> findByPaymentDateBetween(@Param("startDate") LocalDateTime startDate, 
                                                      @Param("endDate") LocalDateTime endDate);

    /**
     * Find recent payments.
     */
    @Query("SELECT rp FROM ReceivablePayment rp ORDER BY rp.paymentDate DESC")
    Page<ReceivablePayment> findRecentPayments(Pageable pageable);

    // ========================================================================
    // RECONCILIATION QUERIES
    // ========================================================================

    /**
     * Find auto-matched payments with high confidence.
     */
    @Query("SELECT rp FROM ReceivablePayment rp WHERE rp.matchType = 'AUTO' AND rp.matchConfidence >= :minConfidence ORDER BY rp.matchedAt DESC")
    List<ReceivablePayment> findHighConfidenceAutoMatches(@Param("minConfidence") Integer minConfidence);

    /**
     * Find payments matched via VIBAN.
     */
    @Query("SELECT rp FROM ReceivablePayment rp WHERE rp.vibanId IS NOT NULL AND rp.matchType = 'AUTO' ORDER BY rp.matchedAt DESC")
    List<ReceivablePayment> findVibanAutoMatches();

    /**
     * Count auto-matched vs manual.
     */
    @Query("SELECT CASE WHEN rp.matchType = 'AUTO' THEN 'AUTO' ELSE 'MANUAL' END as type, COUNT(rp) " +
           "FROM ReceivablePayment rp WHERE rp.status = 'APPLIED' GROUP BY CASE WHEN rp.matchType = 'AUTO' THEN 'AUTO' ELSE 'MANUAL' END")
    List<Object[]> countAutoVsManual();

    // ========================================================================
    // REVERSAL QUERIES
    // ========================================================================

    /**
     * Find reversed payments.
     */
    List<ReceivablePayment> findByStatusOrderByReversedAtDesc(PaymentMatchStatus status);

    /**
     * Find payments that can be reversed.
     */
    @Query("SELECT rp FROM ReceivablePayment rp WHERE rp.status = 'APPLIED' AND rp.reversedAt IS NULL")
    List<ReceivablePayment> findReversible();
}