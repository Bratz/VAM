package com.bank.vam.repository.payables;

import com.bank.vam.entity.payables.Payable;
import com.bank.vam.entity.payables.Payable.*;
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
 * PayableRepository - Phase 2 Enhanced with POBO, Intercompany & Netting Queries
 * 
 * Provides data access for:
 * - Standard payable CRUD operations
 * - Entity-scoped queries (by owning entity)
 * - POBO workflow queries
 * - Intercompany payable queries
 * - Netting integration queries
 * - Statistics and reporting
 */
@Repository
public interface PayableRepository extends JpaRepository<Payable, UUID> {

    // ========================================================================
    // BASIC LOOKUPS
    // ========================================================================
    
    Optional<Payable> findByPayableNumber(String payableNumber);
    
    Optional<Payable> findByInvoiceNumberAndVendorId(String invoiceNumber, UUID vendorId);
    
    boolean existsByPayableNumber(String payableNumber);
    
    /**
     * Simple findAll with pagination - no filtering.
     * Use as fallback when other queries fail.
     */
    Page<Payable> findAll(Pageable pageable);
    
    // ========================================================================
    // CORPORATE & PROGRAM SCOPED QUERIES
    // ========================================================================
    
    List<Payable> findByCorporateIdOrderByCreatedAtDesc(UUID corporateId);
    
    Page<Payable> findByCorporateId(UUID corporateId, Pageable pageable);
    
    List<Payable> findByCorporateIdAndStatus(UUID corporateId, PayableStatus status);
    
    Page<Payable> findByCorporateIdAndStatusIn(UUID corporateId, List<PayableStatus> statuses, Pageable pageable);
    
    List<Payable> findByProgramId(UUID programId);
    
    // ========================================================================
    // PHASE 2: ENTITY CONTEXT QUERIES
    // ========================================================================
    
    /**
     * Find all payables owned by a specific legal entity.
     */
    List<Payable> findByOwningEntityIdOrderByCreatedAtDesc(UUID owningEntityId);
    
    Page<Payable> findByOwningEntityId(UUID owningEntityId, Pageable pageable);
    
    /**
     * Find payables by owning entity and status.
     */
    List<Payable> findByOwningEntityIdAndStatus(UUID owningEntityId, PayableStatus status);
    
    Page<Payable> findByOwningEntityIdAndStatusIn(UUID owningEntityId, List<PayableStatus> statuses, Pageable pageable);
    
    /**
     * Find payables by owning entity code (for when ID is not available).
     */
    List<Payable> findByOwningEntityCode(String owningEntityCode);
    
    /**
     * Count payables by owning entity.
     */
    long countByOwningEntityId(UUID owningEntityId);
    
    /**
     * Sum outstanding amount by owning entity.
     */
    @Query("SELECT COALESCE(SUM(p.outstandingAmount), 0) FROM Payable p WHERE p.owningEntityId = :entityId AND p.status NOT IN ('PAID', 'CANCELLED', 'NETTED')")
    BigDecimal sumOutstandingByOwningEntity(@Param("entityId") UUID owningEntityId);
    
    // ========================================================================
    // PHASE 2: PARTY INTEGRATION QUERIES
    // ========================================================================
    
    /**
     * Find payables for a specific party (vendor).
     */
    List<Payable> findByPartyIdOrderByCreatedAtDesc(UUID partyId);
    
    Page<Payable> findByPartyId(UUID partyId, Pageable pageable);
    
    /**
     * Find payables by party and status.
     */
    List<Payable> findByPartyIdAndStatus(UUID partyId, PayableStatus status);
    
    /**
     * Find payables by owning entity and party.
     */
    List<Payable> findByOwningEntityIdAndPartyId(UUID owningEntityId, UUID partyId);
    
    /**
     * Sum outstanding amount by party.
     */
    @Query("SELECT COALESCE(SUM(p.outstandingAmount), 0) FROM Payable p WHERE p.partyId = :partyId AND p.status NOT IN ('PAID', 'CANCELLED', 'NETTED')")
    BigDecimal sumOutstandingByParty(@Param("partyId") UUID partyId);
    
    // ========================================================================
    // PHASE 2: PAYMENT ROUTE QUERIES
    // ========================================================================
    
    /**
     * Find payables by payment route.
     */
    List<Payable> findByPaymentRoute(PaymentRoute paymentRoute);
    
    Page<Payable> findByPaymentRoute(PaymentRoute paymentRoute, Pageable pageable);
    
    /**
     * Find payables routed through a specific paying entity (POBO treasury).
     */
    List<Payable> findByPaymentViaEntityId(UUID paymentViaEntityId);
    
    /**
     * Find payables by owning entity and payment route.
     */
    List<Payable> findByOwningEntityIdAndPaymentRoute(UUID owningEntityId, PaymentRoute paymentRoute);
    
    /**
     * Find direct payables that could be converted to POBO.
     */
    @Query("SELECT p FROM Payable p WHERE p.owningEntityId = :entityId AND p.paymentRoute = 'DIRECT' AND p.status = 'APPROVED' AND (p.poboRequestStatus IS NULL OR p.poboRequestStatus = 'NOT_REQUESTED')")
    List<Payable> findPoboEligibleByOwningEntity(@Param("entityId") UUID owningEntityId);
    
    // ========================================================================
    // PHASE 2: POBO WORKFLOW QUERIES
    // ========================================================================
    
    /**
     * Find payables by POBO request status.
     */
    List<Payable> findByPoboRequestStatus(PoboRequestStatus status);
    
    Page<Payable> findByPoboRequestStatus(PoboRequestStatus status, Pageable pageable);
    
    /**
     * Find POBO requests pending treasury approval.
     */
    @Query("SELECT p FROM Payable p WHERE p.poboRequestStatus = 'PENDING_TREASURY_APPROVAL' AND p.status = 'PENDING_POBO' ORDER BY p.poboRequestedAt ASC")
    List<Payable> findPoboPendingTreasuryApproval();
    
    Page<Payable> findByPoboRequestStatusAndStatus(PoboRequestStatus poboStatus, PayableStatus status, Pageable pageable);
    
    /**
     * Find POBO requests pending treasury approval for a specific paying entity.
     */
    @Query("SELECT p FROM Payable p WHERE p.paymentViaEntityId = :treasuryEntityId AND p.poboRequestStatus = 'PENDING_TREASURY_APPROVAL' ORDER BY p.poboRequestedAt ASC")
    List<Payable> findPoboPendingForTreasury(@Param("treasuryEntityId") UUID treasuryEntityId);
    
    /**
     * Find POBO requests by owning entity.
     */
    @Query("SELECT p FROM Payable p WHERE p.owningEntityId = :entityId AND p.paymentRoute = 'POBO' ORDER BY p.poboRequestedAt DESC")
    List<Payable> findPoboRequestsByOwningEntity(@Param("entityId") UUID owningEntityId);
    
    /**
     * Find executed POBO payments.
     */
    @Query("SELECT p FROM Payable p WHERE p.poboRequestStatus = 'EXECUTED' ORDER BY p.poboActionedAt DESC")
    Page<Payable> findPoboExecuted(Pageable pageable);
    
    /**
     * Find POBO payments by IHB loan ID.
     */
    Optional<Payable> findByPoboIhbLoanId(UUID ihbLoanId);
    
    /**
     * Find POBO payments by recharge ID.
     */
    Optional<Payable> findByPoboRechargeId(UUID rechargeId);
    
    /**
     * Count POBO requests by status for an owning entity.
     */
    long countByOwningEntityIdAndPoboRequestStatus(UUID owningEntityId, PoboRequestStatus status);
    
    /**
     * Sum POBO amounts pending treasury approval.
     */
    @Query("SELECT COALESCE(SUM(p.netAmount), 0) FROM Payable p WHERE p.poboRequestStatus = 'PENDING_TREASURY_APPROVAL'")
    BigDecimal sumPoboPendingApprovalAmount();
    
    /**
     * Sum POBO amounts by owning entity pending treasury approval.
     */
    @Query("SELECT COALESCE(SUM(p.netAmount), 0) FROM Payable p WHERE p.owningEntityId = :entityId AND p.poboRequestStatus = 'PENDING_TREASURY_APPROVAL'")
    BigDecimal sumPoboPendingByOwningEntity(@Param("entityId") UUID owningEntityId);
    
    // ========================================================================
    // PHASE 2: INTERCOMPANY QUERIES
    // ========================================================================
    
    /**
     * Find all intercompany payables.
     */
    List<Payable> findByIsIntercompanyTrueOrderByCreatedAtDesc();
    
    Page<Payable> findByIsIntercompanyTrue(Pageable pageable);
    
    /**
     * Find intercompany payables by owning entity.
     */
    List<Payable> findByOwningEntityIdAndIsIntercompanyTrue(UUID owningEntityId);
    
    /**
     * Find intercompany payables by counterparty entity.
     */
    List<Payable> findByCounterpartyEntityId(UUID counterpartyEntityId);
    
    Page<Payable> findByCounterpartyEntityId(UUID counterpartyEntityId, Pageable pageable);
    
    /**
     * Find intercompany payables between two specific entities.
     */
    @Query("SELECT p FROM Payable p WHERE p.owningEntityId = :fromEntityId AND p.counterpartyEntityId = :toEntityId AND p.isIntercompany = true ORDER BY p.createdAt DESC")
    List<Payable> findIntercompanyBetweenEntities(@Param("fromEntityId") UUID fromEntityId, @Param("toEntityId") UUID toEntityId);
    
    /**
     * Find intercompany payables without matching receivable.
     */
    @Query("SELECT p FROM Payable p WHERE p.isIntercompany = true AND p.counterpartyReceivableId IS NULL AND p.status NOT IN ('CANCELLED', 'PAID', 'NETTED')")
    List<Payable> findUnmatchedIntercompanyPayables();
    
    /**
     * Sum intercompany outstanding by counterparty.
     */
    @Query("SELECT COALESCE(SUM(p.outstandingAmount), 0) FROM Payable p WHERE p.isIntercompany = true AND p.counterpartyEntityId = :entityId AND p.status NOT IN ('PAID', 'CANCELLED', 'NETTED')")
    BigDecimal sumIntercompanyOutstandingByCounterparty(@Param("entityId") UUID counterpartyEntityId);
    
    /**
     * Sum intercompany exposure from owning entity to counterparty.
     */
    @Query("SELECT COALESCE(SUM(p.outstandingAmount), 0) FROM Payable p WHERE p.owningEntityId = :fromEntityId AND p.counterpartyEntityId = :toEntityId AND p.isIntercompany = true AND p.status NOT IN ('PAID', 'CANCELLED', 'NETTED')")
    BigDecimal sumIntercompanyExposure(@Param("fromEntityId") UUID fromEntityId, @Param("toEntityId") UUID toEntityId);
    
    // ========================================================================
    // PHASE 2: NETTING INTEGRATION QUERIES
    // ========================================================================
    
    /**
     * Find netting-eligible payables.
     */
    List<Payable> findByNettingEligibleTrueOrderByDueDateAsc();
    
    /**
     * Find netting-eligible payables not yet in a cycle.
     */
    @Query("SELECT p FROM Payable p WHERE p.nettingEligible = true AND p.nettingCycleId IS NULL AND p.nettingStatus = 'NOT_INCLUDED' AND p.status IN ('APPROVED', 'POBO_APPROVED') ORDER BY p.dueDate ASC")
    List<Payable> findNettingEligibleNotInCycle();
    
    /**
     * Find netting-eligible payables by owning entity.
     */
//     @Query("SELECT p FROM Payable p WHERE p.owningEntityId = :entityId AND p.nettingEligible = true AND p.nettingCycleId IS NULL AND p.status IN ('APPROVED', 'POBO_APPROVED') ORDER BY p.dueDate ASC")
//     List<Payable> findNettingEligibleByOwningEntity(@Param("entityId") UUID owningEntityId);
    
    /**
     * Find payables by netting cycle.
     */
    List<Payable> findByNettingCycleIdOrderByOwningEntityCode(UUID nettingCycleId);
    
    /**
     * Find payables by netting cycle and status.
     */
    List<Payable> findByNettingCycleIdAndNettingStatus(UUID nettingCycleId, NettingStatus nettingStatus);
    
    /**
     * Find payables pending netting settlement.
     */
    @Query("SELECT p FROM Payable p WHERE p.nettingCycleId = :cycleId AND p.nettingStatus = 'INCLUDED' AND p.status = 'PENDING_NETTING'")
    List<Payable> findPendingNettingByCycle(@Param("cycleId") UUID nettingCycleId);
    
    /**
     * Count payables in a netting cycle.
     */
    long countByNettingCycleId(UUID nettingCycleId);
    
    /**
     * Sum payable amounts in a netting cycle.
     */
    @Query("SELECT COALESCE(SUM(p.netAmount), 0) FROM Payable p WHERE p.nettingCycleId = :cycleId AND p.nettingStatus IN ('INCLUDED', 'PENDING')")
    BigDecimal sumNettingCyclePayables(@Param("cycleId") UUID nettingCycleId);
    
    /**
     * Sum payable amounts in netting cycle by owning entity.
     */
    @Query("SELECT COALESCE(SUM(p.netAmount), 0) FROM Payable p WHERE p.nettingCycleId = :cycleId AND p.owningEntityId = :entityId AND p.nettingStatus IN ('INCLUDED', 'PENDING')")
    BigDecimal sumNettingCyclePayablesByEntity(@Param("cycleId") UUID nettingCycleId, @Param("entityId") UUID owningEntityId);
    
    // ========================================================================
    // BULK UPDATE OPERATIONS
    // ========================================================================
    
    /**
     * Bulk update netting status for payables in a cycle.
     */
    @Modifying
    @Query("UPDATE Payable p SET p.nettingStatus = :status WHERE p.nettingCycleId = :cycleId")
    int updateNettingStatusByCycle(@Param("cycleId") UUID nettingCycleId, @Param("status") NettingStatus status);
    
    /**
     * Bulk mark payables as settled via netting.
     */
    @Modifying
    @Query("UPDATE Payable p SET p.nettingStatus = 'SETTLED', p.nettingSettlementRef = :settlementRef, p.nettingSettledAt = :settledAt, p.status = 'NETTED', p.paymentStatus = 'PAID', p.paidAmount = p.netAmount, p.outstandingAmount = 0 WHERE p.nettingCycleId = :cycleId AND p.nettingStatus = 'INCLUDED'")
    int markNettingSettled(@Param("cycleId") UUID nettingCycleId, @Param("settlementRef") String settlementRef, @Param("settledAt") LocalDateTime settledAt);
    
    /**
     * Remove payables from a cancelled netting cycle.
     */
    @Modifying
    @Query("UPDATE Payable p SET p.nettingCycleId = NULL, p.nettingCycleRef = NULL, p.nettingEntryId = NULL, p.nettingStatus = 'NOT_INCLUDED', p.status = 'APPROVED' WHERE p.nettingCycleId = :cycleId AND p.nettingStatus IN ('INCLUDED', 'PENDING')")
    int removeFromNettingCycle(@Param("cycleId") UUID nettingCycleId);
    
    // ========================================================================
    // VENDOR/LEGACY QUERIES (Backward Compatible)
    // ========================================================================
    
    List<Payable> findByVendorIdOrderByCreatedAtDesc(UUID vendorId);
    
    Page<Payable> findByVendorId(UUID vendorId, Pageable pageable);
    
    List<Payable> findByVendorIdAndStatus(UUID vendorId, PayableStatus status);
    
    // ========================================================================
    // SEARCH & FILTER QUERIES
    // ========================================================================
    
    /**
     * Search payables with multiple filters.
     * Note: Removed LOWER() to avoid PostgreSQL bytea conversion issues with Hibernate 6.
     * Search is now case-sensitive but more reliable.
     */
    @Query("SELECT p FROM Payable p WHERE p.corporateId = :corporateId " +
           "AND (:owningEntityId IS NULL OR p.owningEntityId = :owningEntityId) " +
           "AND (:status IS NULL OR p.status = :status) " +
           "AND (:paymentRoute IS NULL OR p.paymentRoute = :paymentRoute) " +
           "AND (:isIntercompany IS NULL OR p.isIntercompany = :isIntercompany) " +
           "AND (:nettingEligible IS NULL OR p.nettingEligible = :nettingEligible) " +
           "AND (:searchTerm IS NULL OR :searchTerm = '' " +
           "     OR p.payableNumber LIKE CONCAT('%', :searchTerm, '%') " +
           "     OR p.invoiceNumber LIKE CONCAT('%', :searchTerm, '%') " +
           "     OR p.vendorName LIKE CONCAT('%', :searchTerm, '%'))")
    Page<Payable> searchPayables(
        @Param("corporateId") UUID corporateId,
        @Param("owningEntityId") UUID owningEntityId,
        @Param("status") PayableStatus status,
        @Param("paymentRoute") PaymentRoute paymentRoute,
        @Param("isIntercompany") Boolean isIntercompany,
        @Param("nettingEligible") Boolean nettingEligible,
        @Param("searchTerm") String searchTerm,
        Pageable pageable
    );
    
    // ========================================================================
    // REPORTING & STATISTICS
    // ========================================================================
    
    /**
     * Get payables statistics by corporate.
     * Note: Uses simple counts - complex CASE expressions with enums can fail in Hibernate 6.
     */
    @Query("SELECT new map(" +
           "COUNT(p) as totalCount, " +
           "COALESCE(SUM(p.netAmount), 0) as totalAmount, " +
           "COALESCE(SUM(p.outstandingAmount), 0) as outstandingAmount) " +
           "FROM Payable p WHERE p.corporateId = :corporateId")
    Object getPayablesStatsByCorporate(@Param("corporateId") UUID corporateId);
    
    /**
     * Get payables statistics by owning entity.
     * Note: Uses simple counts - complex CASE expressions with enums can fail in Hibernate 6.
     */
    @Query("SELECT new map(" +
           "COUNT(p) as totalCount, " +
           "COALESCE(SUM(p.netAmount), 0) as totalAmount, " +
           "COALESCE(SUM(p.outstandingAmount), 0) as outstandingAmount) " +
           "FROM Payable p WHERE p.owningEntityId = :entityId")
    Object getPayablesStatsByOwningEntity(@Param("entityId") UUID owningEntityId);
    
    /**
     * Get overdue payables by owning entity.
     */
    @Query("SELECT p FROM Payable p WHERE p.owningEntityId = :entityId AND p.dueDate < :today AND p.status NOT IN ('PAID', 'CANCELLED', 'NETTED') ORDER BY p.dueDate ASC")
    List<Payable> findOverdueByOwningEntity(@Param("entityId") UUID owningEntityId, @Param("today") LocalDate today);
    
    /**
     * Get due soon payables (within N days).
     */
    @Query("SELECT p FROM Payable p WHERE p.owningEntityId = :entityId AND p.dueDate BETWEEN :today AND :dueBefore AND p.status NOT IN ('PAID', 'CANCELLED', 'NETTED') ORDER BY p.dueDate ASC")
    List<Payable> findDueSoonByOwningEntity(@Param("entityId") UUID owningEntityId, @Param("today") LocalDate today, @Param("dueBefore") LocalDate dueBefore);
    
    /**
     * Get aging summary by corporate.
     * Note: Uses parameterized dates instead of date arithmetic for Hibernate 6 compatibility.
     * Call with: today, today.minusDays(30), today.minusDays(60), today.minusDays(90)
     */
    @Query("SELECT new map(" +
           "SUM(CASE WHEN p.dueDate >= :today THEN p.outstandingAmount ELSE 0 END) as current, " +
           "SUM(CASE WHEN p.dueDate < :today AND p.dueDate >= :minus30 THEN p.outstandingAmount ELSE 0 END) as overdue1to30, " +
           "SUM(CASE WHEN p.dueDate < :minus30 AND p.dueDate >= :minus60 THEN p.outstandingAmount ELSE 0 END) as overdue31to60, " +
           "SUM(CASE WHEN p.dueDate < :minus60 AND p.dueDate >= :minus90 THEN p.outstandingAmount ELSE 0 END) as overdue61to90, " +
           "SUM(CASE WHEN p.dueDate < :minus90 THEN p.outstandingAmount ELSE 0 END) as overdue90Plus) " +
           "FROM Payable p WHERE p.corporateId = :corporateId AND p.status NOT IN ('PAID', 'CANCELLED', 'NETTED')")
    Object getAgingSummaryByCorporate(
        @Param("corporateId") UUID corporateId,
        @Param("today") LocalDate today,
        @Param("minus30") LocalDate minus30,
        @Param("minus60") LocalDate minus60,
        @Param("minus90") LocalDate minus90
    );

    // ============================================================================
// ADD THESE METHODS TO EXISTING PayableRepository.java
// Location: backend/src/main/java/com/bank/vam/repository/payables/PayableRepository.java
// ============================================================================

// Add these imports if not present:
// import com.bank.vam.entity.payables.Payable.NettingStatus;
// import com.bank.vam.entity.payables.Payable.PayableStatus;

    // ========================================================================
    // PHASE 4: NETTING QUERIES
    // ========================================================================
    
    /**
     * Find all payables eligible for netting that are not yet in a cycle.
     * 
     * Criteria:
     * - Intercompany = true OR nettingEligible = true
     * - Status = APPROVED or POBO_APPROVED
     * - nettingCycleId is null
     * - nettingStatus = NOT_INCLUDED
     */
    @Query("""
        SELECT p FROM Payable p 
        WHERE p.corporateId = :corporateId
        AND (p.isIntercompany = true OR p.nettingEligible = true)
        AND p.status IN ('APPROVED', 'POBO_APPROVED')
        AND p.nettingCycleId IS NULL
        AND p.nettingStatus = 'NOT_INCLUDED'
        ORDER BY p.dueDate ASC
        """)
    List<Payable> findNettingEligiblePayables(@Param("corporateId") UUID corporateId);
    
    /**
     * Find intercompany payables for a specific counterparty entity.
     */
    @Query("""
        SELECT p FROM Payable p 
        WHERE p.corporateId = :corporateId
        AND p.isIntercompany = true
        AND p.counterpartyEntityId = :counterpartyEntityId
        AND p.status IN ('APPROVED', 'POBO_APPROVED')
        AND p.nettingCycleId IS NULL
        ORDER BY p.dueDate ASC
        """)
    List<Payable> findIntercompanyPayablesForCounterparty(
        @Param("corporateId") UUID corporateId,
        @Param("counterpartyEntityId") UUID counterpartyEntityId
    );
    
    /**
     * Find payables in a specific netting cycle.
     */
    @Query("SELECT p FROM Payable p WHERE p.nettingCycleId = :cycleId")
    List<Payable> findByNettingCycleId(@Param("cycleId") UUID cycleId);
    
    /**
     * Find payables by owning entity that are netting-eligible.
     */
    @Query("""
        SELECT p FROM Payable p 
        WHERE p.owningEntityId = :entityId
        AND (p.isIntercompany = true OR p.nettingEligible = true)
        AND p.status IN ('APPROVED', 'POBO_APPROVED')
        AND p.nettingCycleId IS NULL
        ORDER BY p.dueDate ASC
        """)
    List<Payable> findNettingEligibleByOwningEntity(@Param("entityId") UUID entityId);
    
    /**
     * Count netting-eligible payables for corporate.
     */
    @Query("""
        SELECT COUNT(p) FROM Payable p 
        WHERE p.corporateId = :corporateId
        AND (p.isIntercompany = true OR p.nettingEligible = true)
        AND p.status IN ('APPROVED', 'POBO_APPROVED')
        AND p.nettingCycleId IS NULL
        """)
    long countNettingEligiblePayables(@Param("corporateId") UUID corporateId);
    
    /**
     * Sum of netting-eligible payable amounts for corporate.
     */
    @Query("""
        SELECT COALESCE(SUM(p.netAmount), 0) FROM Payable p 
        WHERE p.corporateId = :corporateId
        AND (p.isIntercompany = true OR p.nettingEligible = true)
        AND p.status IN ('APPROVED', 'POBO_APPROVED')
        AND p.nettingCycleId IS NULL
        """)
    BigDecimal sumNettingEligiblePayables(@Param("corporateId") UUID corporateId);
    
    /**
     * Find payables pending netting settlement.
     */
    @Query("""
        SELECT p FROM Payable p 
        WHERE p.nettingCycleId = :cycleId
        AND p.nettingStatus = 'INCLUDED'
        """)
    List<Payable> findPendingNettingSettlement(@Param("cycleId") UUID cycleId);

    // ========================================================================
    // FORECASTING — AGING ENGINE (Sprint 1, T5)
    // ========================================================================

    /**
     * Page of APPROVED/SCHEDULED payables for the aging forecast engine.
     *
     * <p>Window is the engine horizon padded ±30 days so the DPO shift can pull
     * pre-horizon due dates into the window (and shift post-horizon ones back
     * via the {@code horizonEnd} clip).
     */
    @Query("""
        SELECT p FROM Payable p
        WHERE p.owningEntityId IN :entityIds
          AND p.status IN ('APPROVED', 'SCHEDULED')
          AND p.dueDate IS NOT NULL
          AND p.dueDate BETWEEN :from AND :to
        """)
    Page<Payable> findForAgingForecast(
        @Param("entityIds") java.util.Collection<UUID> entityIds,
        @Param("from") LocalDate from,
        @Param("to") LocalDate to,
        Pageable pageable
    );

    /**
     * Corporate-wide DPO (avg actual-paid − due, in days) over payables whose
     * latest completed execution fell on or after {@code since}.
     *
     * <p>Returns {@code null} when no paid payables exist in the window —
     * callers floor the missing/negative result to 0.
     */
    @Query(value = """
        SELECT AVG(CAST((latest.paid_at::date - p.due_date) AS DOUBLE PRECISION))
        FROM payables p
        JOIN (
            SELECT payable_id, MAX(execution_date) AS paid_at
            FROM payment_executions
            WHERE status = 'COMPLETED' AND payable_id IS NOT NULL
            GROUP BY payable_id
        ) latest ON latest.payable_id = p.id
        WHERE p.status = 'PAID'
          AND p.owning_entity_id IN (:entityIds)
          AND p.due_date IS NOT NULL
          AND latest.paid_at >= :since
        """, nativeQuery = true)
    Double computeCorporateDpoDays(
        @Param("entityIds") java.util.Collection<UUID> entityIds,
        @Param("since") LocalDateTime since
    );

}