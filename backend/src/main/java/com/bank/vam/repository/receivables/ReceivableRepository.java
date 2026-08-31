package com.bank.vam.repository.receivables;

import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.receivables.Receivable.ReceivableStatus;
import com.bank.vam.entity.receivables.Receivable.ReceivableType;
import com.bank.vam.entity.receivables.Receivable.CollectionChannel;
import com.bank.vam.entity.receivables.Receivable.CollectionRoute;
import com.bank.vam.entity.receivables.Receivable.CoboRequestStatus;
import com.bank.vam.entity.receivables.Receivable.NettingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
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
 * Repository for Receivable entity - Phase 3 Complete with COBO/IC/Netting queries.
 * 
 * PHASE 3 ENHANCEMENTS:
 * - Entity context queries (owning entity)
 * - Party integration queries (customer party)
 * - Intercompany queries
 * - COBO workflow queries
 * - Netting integration queries
 * - Enhanced filters with all Phase 3 fields
 * 
 * BACKWARD COMPATIBLE:
 * - All existing queries preserved
 * - New queries added for Phase 3 functionality
 * 
 * Integrates with:
 * - VIBAN module for payment routing lookup
 * - Hierarchy module for aggregated reporting
 * - Party module for customer details
 * - Netting module for cycle participation
 */
@Repository
public interface ReceivableRepository extends JpaRepository<Receivable, UUID>, JpaSpecificationExecutor<Receivable> {

    // ========================================================================
    // BASIC LOOKUPS (Preserved)
    // ========================================================================

    Optional<Receivable> findByReceivableNumber(String receivableNumber);
    
    Optional<Receivable> findByExternalReference(String externalReference);
    
    Optional<Receivable> findByPrimaryVibanId(UUID vibanId);
    
    Optional<Receivable> findByViban(String viban);
    
    // ========================================================================
    // CORPORATE QUERIES (Preserved)
    // ========================================================================

    Page<Receivable> findByCorporateId(UUID corporateId, Pageable pageable);
    
    List<Receivable> findByCorporateId(UUID corporateId);
    
    List<Receivable> findByCorporateIdAndStatus(UUID corporateId, ReceivableStatus status);
    
    long countByCorporateId(UUID corporateId);
    
    long countByCorporateIdAndStatus(UUID corporateId, ReceivableStatus status);

    // ========================================================================
    // PHASE 3: ENTITY CONTEXT QUERIES
    // ========================================================================

    /**
     * Find receivables by owning entity (legal entity that issued the invoice).
     */
    Page<Receivable> findByOwningEntityId(UUID owningEntityId, Pageable pageable);
    
    List<Receivable> findByOwningEntityId(UUID owningEntityId);
    
    List<Receivable> findByOwningEntityIdAndStatus(UUID owningEntityId, ReceivableStatus status);
    
    /**
     * Find receivables by owning entity code.
     */
    List<Receivable> findByOwningEntityCode(String owningEntityCode);
    
    /**
     * Count receivables by owning entity.
     */
    long countByOwningEntityId(UUID owningEntityId);
    
    /**
     * Sum outstanding by owning entity.
     */
    @Query("SELECT COALESCE(SUM(r.outstandingAmount), 0) FROM Receivable r " +
           "WHERE r.owningEntityId = :entityId AND r.status IN ('OPEN', 'PARTIAL', 'OVERDUE')")
    BigDecimal sumOutstandingByOwningEntity(@Param("entityId") UUID owningEntityId);
    
    /**
     * Find receivables by corporate and owning entity.
     */
    @Query("SELECT r FROM Receivable r WHERE r.corporateId = :corporateId " +
           "AND r.owningEntityId = :owningEntityId ORDER BY r.createdAt DESC")
    Page<Receivable> findByCorporateIdAndOwningEntityId(
            @Param("corporateId") UUID corporateId,
            @Param("owningEntityId") UUID owningEntityId,
            Pageable pageable);
    
    /**
     * Sum outstanding grouped by owning entity.
     */
    @Query("SELECT r.owningEntityId, r.owningEntityCode, r.owningEntityName, " +
           "COALESCE(SUM(r.outstandingAmount), 0), COUNT(r) " +
           "FROM Receivable r WHERE r.corporateId = :corporateId " +
           "AND r.status IN ('OPEN', 'PARTIAL', 'OVERDUE') " +
           "GROUP BY r.owningEntityId, r.owningEntityCode, r.owningEntityName")
    List<Object[]> sumOutstandingGroupedByOwningEntity(@Param("corporateId") UUID corporateId);

    // ========================================================================
    // PHASE 3: CUSTOMER PARTY QUERIES
    // ========================================================================

    /**
     * Find receivables by customer party ID.
     */
    List<Receivable> findByCustomerPartyId(UUID customerPartyId);
    
    Page<Receivable> findByCustomerPartyId(UUID customerPartyId, Pageable pageable);
    
    /**
     * Find receivables by customer party and status.
     */
    List<Receivable> findByCustomerPartyIdAndStatus(UUID customerPartyId, ReceivableStatus status);
    
    /**
     * Sum outstanding by customer party.
     */
    @Query("SELECT COALESCE(SUM(r.outstandingAmount), 0) FROM Receivable r " +
           "WHERE r.customerPartyId = :partyId AND r.status IN ('OPEN', 'PARTIAL', 'OVERDUE')")
    BigDecimal sumOutstandingByCustomerParty(@Param("partyId") UUID customerPartyId);
    
    /**
     * Count receivables by customer party.
     */
    long countByCustomerPartyId(UUID customerPartyId);

    // ========================================================================
    // PHASE 3: INTERCOMPANY QUERIES
    // ========================================================================

    /**
     * Find all intercompany receivables.
     */
    @Query("SELECT r FROM Receivable r WHERE r.isIntercompany = true ORDER BY r.createdAt DESC")
    List<Receivable> findIntercompanyReceivables();
    
    @Query("SELECT r FROM Receivable r WHERE r.isIntercompany = true")
    Page<Receivable> findIntercompanyReceivables(Pageable pageable);
    
    /**
     * Find intercompany receivables by corporate.
     */
    @Query("SELECT r FROM Receivable r WHERE r.corporateId = :corporateId AND r.isIntercompany = true " +
           "ORDER BY r.createdAt DESC")
    List<Receivable> findIntercompanyReceivablesByCorporate(@Param("corporateId") UUID corporateId);
    
    @Query("SELECT r FROM Receivable r WHERE r.corporateId = :corporateId AND r.isIntercompany = true")
    Page<Receivable> findIntercompanyReceivablesByCorporate(
            @Param("corporateId") UUID corporateId, Pageable pageable);
    
    /**
     * Find intercompany receivables by counterparty entity.
     */
    List<Receivable> findByIntercompanyEntityId(UUID intercompanyEntityId);
    
    Page<Receivable> findByIntercompanyEntityId(UUID intercompanyEntityId, Pageable pageable);
    
    /**
     * Find intercompany receivables by owning entity.
     */
    @Query("SELECT r FROM Receivable r WHERE r.owningEntityId = :owningEntityId " +
           "AND r.isIntercompany = true ORDER BY r.createdAt DESC")
    List<Receivable> findIntercompanyByOwningEntity(@Param("owningEntityId") UUID owningEntityId);
    
    /**
     * Find intercompany receivables by owning and counterparty entities.
     * (All receivables from entity A to entity B)
     */
    @Query("SELECT r FROM Receivable r WHERE r.owningEntityId = :owningEntityId " +
           "AND r.intercompanyEntityId = :counterpartyEntityId AND r.isIntercompany = true " +
           "ORDER BY r.createdAt DESC")
    List<Receivable> findIntercompanyReceivablesBetweenEntities(
            @Param("owningEntityId") UUID owningEntityId,
            @Param("counterpartyEntityId") UUID counterpartyEntityId);
    
    /**
     * Sum intercompany receivables by entity pair.
     */
    @Query("SELECT COALESCE(SUM(r.outstandingAmount), 0) FROM Receivable r " +
           "WHERE r.owningEntityId = :owningEntityId AND r.intercompanyEntityId = :counterpartyEntityId " +
           "AND r.isIntercompany = true AND r.status IN ('OPEN', 'PARTIAL', 'OVERDUE')")
    BigDecimal sumIntercompanyOutstandingBetweenEntities(
            @Param("owningEntityId") UUID owningEntityId,
            @Param("counterpartyEntityId") UUID counterpartyEntityId);
    
    /**
     * Count intercompany receivables by corporate.
     */
    @Query("SELECT COUNT(r) FROM Receivable r WHERE r.corporateId = :corporateId AND r.isIntercompany = true")
    long countIntercompanyByCorporate(@Param("corporateId") UUID corporateId);
    
    /**
     * Sum all intercompany outstanding by corporate.
     */
    @Query("SELECT COALESCE(SUM(r.outstandingAmount), 0) FROM Receivable r " +
           "WHERE r.corporateId = :corporateId AND r.isIntercompany = true " +
           "AND r.status IN ('OPEN', 'PARTIAL', 'OVERDUE')")
    BigDecimal sumIntercompanyOutstandingByCorporate(@Param("corporateId") UUID corporateId);
    
    /**
     * Get intercompany balance summary - receivables from each counterparty.
     */
    @Query("SELECT r.intercompanyEntityId, r.intercompanyEntityCode, r.intercompanyEntityName, " +
           "COALESCE(SUM(r.outstandingAmount), 0), COUNT(r) " +
           "FROM Receivable r WHERE r.corporateId = :corporateId " +
           "AND r.isIntercompany = true AND r.status IN ('OPEN', 'PARTIAL', 'OVERDUE') " +
           "GROUP BY r.intercompanyEntityId, r.intercompanyEntityCode, r.intercompanyEntityName")
    List<Object[]> getIntercompanyBalanceSummary(@Param("corporateId") UUID corporateId);
    
    /**
     * Find by counterparty payable ID (cross-reference).
     */
    Optional<Receivable> findByCounterpartyPayableId(UUID counterpartyPayableId);

    // ========================================================================
    // PHASE 3: COBO QUERIES
    // ========================================================================

    /**
     * Find COBO receivables.
     */
    @Query("SELECT r FROM Receivable r WHERE r.isCobo = true ORDER BY r.createdAt DESC")
    List<Receivable> findCoboReceivables();
    
    @Query("SELECT r FROM Receivable r WHERE r.isCobo = true")
    Page<Receivable> findCoboReceivables(Pageable pageable);
    
    /**
     * Find COBO receivables by corporate.
     */
    @Query("SELECT r FROM Receivable r WHERE r.corporateId = :corporateId AND r.isCobo = true " +
           "ORDER BY r.createdAt DESC")
    List<Receivable> findCoboByCorporateList(@Param("corporateId") UUID corporateId);
    
    @Query("SELECT r FROM Receivable r WHERE r.corporateId = :corporateId AND r.isCobo = true")
    Page<Receivable> findCoboByCorporate(@Param("corporateId") UUID corporateId, Pageable pageable);
    
    /**
     * Find receivables pending COBO approval.
     */
    @Query("SELECT r FROM Receivable r WHERE r.coboRequestStatus = 'PENDING_TREASURY_APPROVAL' " +
           "ORDER BY r.coboRequestedAt ASC")
    List<Receivable> findPendingCoboApproval();
    
    @Query("SELECT r FROM Receivable r WHERE r.corporateId = :corporateId " +
           "AND r.coboRequestStatus = 'PENDING_TREASURY_APPROVAL' ORDER BY r.coboRequestedAt ASC")
    List<Receivable> findPendingCoboApprovalByCorporate(@Param("corporateId") UUID corporateId);
    
    /**
     * Find receivables by COBO request status.
     */
    List<Receivable> findByCoboRequestStatus(CoboRequestStatus status);
    
    Page<Receivable> findByCoboRequestStatus(CoboRequestStatus status, Pageable pageable);
    
    @Query("SELECT r FROM Receivable r WHERE r.corporateId = :corporateId " +
           "AND r.coboRequestStatus = :status ORDER BY r.createdAt DESC")
    List<Receivable> findByCoboRequestStatusAndCorporate(
            @Param("corporateId") UUID corporateId,
            @Param("status") CoboRequestStatus status);
    
    /**
     * Find receivables by COBO collector entity.
     */
    List<Receivable> findByCoboCollectorEntityId(UUID collectorEntityId);
    
    Page<Receivable> findByCoboCollectorEntityId(UUID collectorEntityId, Pageable pageable);
    
    /**
     * Find COBO receivables collected on behalf of a specific entity.
     */
    @Query("SELECT r FROM Receivable r WHERE r.owningEntityId = :owningEntityId AND r.isCobo = true " +
           "ORDER BY r.createdAt DESC")
    List<Receivable> findCoboByOwningEntity(@Param("owningEntityId") UUID owningEntityId);
    
    /**
     * Sum COBO receivables pending collection.
     */
    @Query("SELECT COALESCE(SUM(r.outstandingAmount), 0) FROM Receivable r " +
           "WHERE r.coboRequestStatus = 'APPROVED' AND r.status IN ('OPEN', 'PARTIAL', 'COBO_APPROVED')")
    BigDecimal sumPendingCoboCollection();
    
    @Query("SELECT COALESCE(SUM(r.outstandingAmount), 0) FROM Receivable r " +
           "WHERE r.corporateId = :corporateId AND r.coboRequestStatus = 'APPROVED' " +
           "AND r.status IN ('OPEN', 'PARTIAL', 'COBO_APPROVED')")
    BigDecimal sumPendingCoboCollectionByCorporate(@Param("corporateId") UUID corporateId);
    
    /**
     * Sum COBO collected by collector entity.
     */
    @Query("SELECT COALESCE(SUM(r.paidAmount), 0) FROM Receivable r " +
           "WHERE r.coboCollectorEntityId = :collectorEntityId AND r.coboRequestStatus = 'COLLECTED'")
    BigDecimal sumCoboCollectedByCollector(@Param("collectorEntityId") UUID collectorEntityId);
    
    /**
     * Count COBO by status for corporate.
     */
    @Query("SELECT r.coboRequestStatus, COUNT(r), COALESCE(SUM(r.outstandingAmount), 0) " +
           "FROM Receivable r WHERE r.corporateId = :corporateId AND r.isCobo = true " +
           "GROUP BY r.coboRequestStatus")
    List<Object[]> countCoboByStatusForCorporate(@Param("corporateId") UUID corporateId);
    
    /**
     * Find COBO by recharge ID.
     */
    Optional<Receivable> findByCoboRechargeId(UUID coboRechargeId);
    
    /**
     * Find COBO by IHB deposit ID.
     */
    Optional<Receivable> findByCoboIhbDepositId(UUID coboIhbDepositId);

    // ========================================================================
    // PHASE 3: NETTING QUERIES
    // ========================================================================

    /**
     * Find netting-eligible receivables.
     */
    @Query("SELECT r FROM Receivable r WHERE r.nettingEligible = true " +
           "AND r.nettingStatus = 'NOT_INCLUDED' AND r.status IN ('OPEN', 'PARTIAL') " +
           "ORDER BY r.createdAt DESC")
    List<Receivable> findNettingEligibleReceivables();
    
    @Query("SELECT r FROM Receivable r WHERE r.corporateId = :corporateId " +
           "AND r.nettingEligible = true AND r.nettingStatus = 'NOT_INCLUDED' " +
           "AND r.status IN ('OPEN', 'PARTIAL') ORDER BY r.dueDate ASC")
    List<Receivable> findNettingEligibleByCorporate(@Param("corporateId") UUID corporateId);
    
    /**
     * Find netting-eligible intercompany receivables by entity pair.
     * Used when populating netting cycles.
     */
    @Query("SELECT r FROM Receivable r WHERE r.owningEntityId = :owningEntityId " +
           "AND r.intercompanyEntityId = :counterpartyEntityId " +
           "AND r.isIntercompany = true AND r.nettingEligible = true " +
           "AND r.nettingStatus = 'NOT_INCLUDED' AND r.status IN ('OPEN', 'PARTIAL') " +
           "ORDER BY r.dueDate ASC")
    List<Receivable> findNettingEligibleIntercompanyByEntityPair(
            @Param("owningEntityId") UUID owningEntityId,
            @Param("counterpartyEntityId") UUID counterpartyEntityId);
    
    /**
     * Find netting-eligible by owning entity.
     */
//     @Query("SELECT r FROM Receivable r WHERE r.owningEntityId = :owningEntityId " +
//            "AND r.nettingEligible = true AND r.nettingStatus = 'NOT_INCLUDED' " +
//            "AND r.status IN ('OPEN', 'PARTIAL') ORDER BY r.dueDate ASC")
//     List<Receivable> findNettingEligibleByOwningEntity(@Param("owningEntityId") UUID owningEntityId);
    
    /**
     * Find receivables in a netting cycle.
     */
//     List<Receivable> findByNettingCycleId(UUID nettingCycleId);
    
    @Query("SELECT r FROM Receivable r WHERE r.nettingCycleId = :cycleId ORDER BY r.createdAt ASC")
    List<Receivable> findByNettingCycleIdOrdered(@Param("cycleId") UUID nettingCycleId);
    
    /**
     * Find receivables by netting status.
     */
    List<Receivable> findByNettingStatus(NettingStatus nettingStatus);
    
    Page<Receivable> findByNettingStatus(NettingStatus nettingStatus, Pageable pageable);
    
    @Query("SELECT r FROM Receivable r WHERE r.corporateId = :corporateId " +
           "AND r.nettingStatus = :status ORDER BY r.createdAt DESC")
    List<Receivable> findByNettingStatusAndCorporate(
            @Param("corporateId") UUID corporateId,
            @Param("status") NettingStatus status);
    
    /**
     * Find receivables pending netting settlement.
     */
    @Query("SELECT r FROM Receivable r WHERE r.nettingStatus = 'INCLUDED' " +
           "AND r.status = 'PENDING_NETTING' ORDER BY r.nettingCycleRef, r.createdAt")
    List<Receivable> findPendingNettingSettlement();
    
    @Query("SELECT r FROM Receivable r WHERE r.corporateId = :corporateId " +
           "AND r.nettingStatus = 'INCLUDED' AND r.status = 'PENDING_NETTING' " +
           "ORDER BY r.nettingCycleRef, r.createdAt")
    List<Receivable> findPendingNettingSettlementByCorporate(@Param("corporateId") UUID corporateId);
    
    /**
     * Sum receivables in netting cycle.
     */
    @Query("SELECT COALESCE(SUM(r.outstandingAmount), 0) FROM Receivable r " +
           "WHERE r.nettingCycleId = :cycleId AND r.nettingStatus = 'INCLUDED'")
    BigDecimal sumReceivablesInNettingCycle(@Param("cycleId") UUID nettingCycleId);
    
    /**
     * Sum netting-eligible receivables by owning entity.
     */
    @Query("SELECT COALESCE(SUM(r.outstandingAmount), 0) FROM Receivable r " +
           "WHERE r.owningEntityId = :entityId AND r.nettingEligible = true " +
           "AND r.nettingStatus = 'NOT_INCLUDED' AND r.status IN ('OPEN', 'PARTIAL')")
    BigDecimal sumNettingEligibleByOwningEntity(@Param("entityId") UUID owningEntityId);
    
    /**
     * Sum netting-eligible receivables by corporate.
     */
    @Query("SELECT COALESCE(SUM(r.outstandingAmount), 0) FROM Receivable r " +
           "WHERE r.corporateId = :corporateId AND r.nettingEligible = true " +
           "AND r.nettingStatus = 'NOT_INCLUDED' AND r.status IN ('OPEN', 'PARTIAL')")
    BigDecimal sumNettingEligibleByCorporate(@Param("corporateId") UUID corporateId);
    
    /**
     * Sum settled via netting by corporate.
     */
    @Query("SELECT COALESCE(SUM(r.paidAmount), 0) FROM Receivable r " +
           "WHERE r.corporateId = :corporateId AND r.nettingStatus = 'SETTLED'")
    BigDecimal sumNettingSettledByCorporate(@Param("corporateId") UUID corporateId);
    
    /**
     * Count receivables in netting cycle.
     */
    @Query("SELECT COUNT(r) FROM Receivable r WHERE r.nettingCycleId = :cycleId")
    long countByNettingCycleId(@Param("cycleId") UUID nettingCycleId);
    
    /**
     * Count netting by status for corporate.
     */
    @Query("SELECT r.nettingStatus, COUNT(r), COALESCE(SUM(r.outstandingAmount), 0) " +
           "FROM Receivable r WHERE r.corporateId = :corporateId AND r.nettingEligible = true " +
           "GROUP BY r.nettingStatus")
    List<Object[]> countNettingByStatusForCorporate(@Param("corporateId") UUID corporateId);
    
    /**
     * Find by netting entry ID.
     */
    Optional<Receivable> findByNettingEntryId(UUID nettingEntryId);

    // ========================================================================
    // PHASE 3: COLLECTION ROUTE QUERIES
    // ========================================================================

    /**
     * Find receivables by collection route.
     */
    List<Receivable> findByCollectionRoute(CollectionRoute collectionRoute);
    
    Page<Receivable> findByCollectionRoute(CollectionRoute collectionRoute, Pageable pageable);
    
    /**
     * Find receivables by corporate and collection route.
     */
    @Query("SELECT r FROM Receivable r WHERE r.corporateId = :corporateId " +
           "AND r.collectionRoute = :route ORDER BY r.createdAt DESC")
    List<Receivable> findByCorporateIdAndCollectionRouteList(
            @Param("corporateId") UUID corporateId,
            @Param("route") CollectionRoute collectionRoute);
    
    @Query("SELECT r FROM Receivable r WHERE r.corporateId = :corporateId " +
           "AND r.collectionRoute = :route")
    Page<Receivable> findByCorporateIdAndCollectionRoute(
            @Param("corporateId") UUID corporateId,
            @Param("route") CollectionRoute collectionRoute,
            Pageable pageable);
    
    /**
     * Sum outstanding by collection route.
     */
    @Query("SELECT r.collectionRoute, COUNT(r), COALESCE(SUM(r.outstandingAmount), 0) " +
           "FROM Receivable r WHERE r.corporateId = :corporateId " +
           "AND r.status IN ('OPEN', 'PARTIAL', 'OVERDUE') " +
           "GROUP BY r.collectionRoute")
    List<Object[]> sumByCollectionRouteForCorporate(@Param("corporateId") UUID corporateId);

    // ========================================================================
    // PROGRAM QUERIES (Preserved)
    // ========================================================================

    Page<Receivable> findByProgramId(UUID programId, Pageable pageable);
    
    List<Receivable> findByProgramId(UUID programId);
    
    List<Receivable> findByProgramIdAndStatus(UUID programId, ReceivableStatus status);

    // ========================================================================
    // VIRTUAL ACCOUNT QUERIES (Preserved)
    // ========================================================================

    List<Receivable> findByVirtualAccountId(UUID virtualAccountId);
    
    List<Receivable> findByVirtualAccountIdAndStatus(UUID virtualAccountId, ReceivableStatus status);

    // ========================================================================
    // CUSTOMER QUERIES (Preserved)
    // ========================================================================

    List<Receivable> findByCustomerId(UUID customerId);
    
    List<Receivable> findByCustomerIdAndStatus(UUID customerId, ReceivableStatus status);
    
    @Query("SELECT r FROM Receivable r WHERE r.customerName LIKE CONCAT('%', :name, '%')")
    List<Receivable> findByCustomerNameContaining(@Param("name") String name);

    // ========================================================================
    // STATUS QUERIES (Preserved)
    // ========================================================================

    Page<Receivable> findByStatus(ReceivableStatus status, Pageable pageable);
    
    List<Receivable> findByStatus(ReceivableStatus status);
    
    List<Receivable> findByStatusIn(List<ReceivableStatus> statuses);
    
    @Query("SELECT r FROM Receivable r WHERE r.status IN ('OPEN', 'PARTIAL') ORDER BY r.dueDate ASC")
    List<Receivable> findOpenReceivables();
    
    @Query("SELECT r FROM Receivable r WHERE r.status = 'OVERDUE' ORDER BY r.dueDate ASC")
    List<Receivable> findOverdueReceivables();

    // ========================================================================
    // TYPE QUERIES (Preserved)
    // ========================================================================

    List<Receivable> findByReceivableType(ReceivableType type);
    
    List<Receivable> findByReceivableTypeAndStatus(ReceivableType type, ReceivableStatus status);
    
    Page<Receivable> findByReceivableType(ReceivableType type, Pageable pageable);

    // ========================================================================
    // COLLECTION CHANNEL QUERIES (Preserved)
    // ========================================================================

    List<Receivable> findByCollectionChannel(CollectionChannel channel);
    
    List<Receivable> findByCollectionChannelAndStatus(CollectionChannel channel, ReceivableStatus status);

    // ========================================================================
    // VIBAN INTEGRATION QUERIES (Preserved)
    // ========================================================================

    @Query("SELECT r FROM Receivable r WHERE r.primaryVibanId = :vibanId AND r.status IN ('OPEN', 'PARTIAL')")
    Optional<Receivable> findActiveByVibanId(@Param("vibanId") UUID vibanId);

    @Query("SELECT r FROM Receivable r WHERE r.viban = :viban AND r.status IN ('OPEN', 'PARTIAL')")
    Optional<Receivable> findActiveByViban(@Param("viban") String viban);

    @Query("SELECT r FROM Receivable r WHERE r.primaryVibanId IS NULL AND r.viban IS NULL AND r.status IN ('OPEN', 'PARTIAL')")
    List<Receivable> findWithoutViban();

    // ========================================================================
    // HIERARCHY QUERIES (Preserved)
    // ========================================================================

    List<Receivable> findByHierarchyNodeId(UUID hierarchyNodeId);

    @Query("SELECT r FROM Receivable r WHERE r.hierarchyPath LIKE CONCAT(:pathPrefix, '%')")
    List<Receivable> findByHierarchyPathPrefix(@Param("pathPrefix") String pathPrefix);

    @Query("SELECT COALESCE(SUM(r.outstandingAmount), 0) FROM Receivable r WHERE r.hierarchyNodeId = :nodeId AND r.status IN ('OPEN', 'PARTIAL', 'OVERDUE')")
    BigDecimal sumOutstandingByHierarchyNode(@Param("nodeId") UUID nodeId);

    @Query("SELECT COALESCE(SUM(r.outstandingAmount), 0) FROM Receivable r WHERE r.hierarchyPath LIKE CONCAT(:pathPrefix, '%') AND r.status IN ('OPEN', 'PARTIAL', 'OVERDUE')")
    BigDecimal sumOutstandingByHierarchyPath(@Param("pathPrefix") String pathPrefix);

    @Query("SELECT r.status, COUNT(r) FROM Receivable r WHERE r.hierarchyNodeId = :nodeId GROUP BY r.status")
    List<Object[]> countByHierarchyNodeGroupByStatus(@Param("nodeId") UUID nodeId);

    // ========================================================================
    // DUE DATE QUERIES (Preserved)
    // ========================================================================

    List<Receivable> findByDueDate(LocalDate dueDate);

    @Query("SELECT r FROM Receivable r WHERE r.dueDate BETWEEN :startDate AND :endDate AND r.status IN ('OPEN', 'PARTIAL')")
    List<Receivable> findDueBetween(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT r FROM Receivable r WHERE r.dueDate < :today AND r.status IN ('OPEN', 'PARTIAL')")
    List<Receivable> findOverdue(@Param("today") LocalDate today);

    @Query("SELECT r FROM Receivable r WHERE r.dueDate BETWEEN :today AND :futureDate AND r.status IN ('OPEN', 'PARTIAL') ORDER BY r.dueDate ASC")
    List<Receivable> findDueSoon(@Param("today") LocalDate today, @Param("futureDate") LocalDate futureDate);

    // ========================================================================
    // AMOUNT QUERIES (Preserved)
    // ========================================================================

    @Query("SELECT COALESCE(SUM(r.outstandingAmount), 0) FROM Receivable r WHERE r.corporateId = :corporateId AND r.status IN ('OPEN', 'PARTIAL', 'OVERDUE')")
    BigDecimal sumOutstandingByCorporate(@Param("corporateId") UUID corporateId);

    @Query("SELECT COALESCE(SUM(r.netAmount), 0) FROM Receivable r WHERE r.corporateId = :corporateId AND r.status NOT IN ('CANCELLED', 'WRITTEN_OFF', 'DRAFT')")
    BigDecimal sumTotalByCorporate(@Param("corporateId") UUID corporateId);

    @Query("SELECT COALESCE(SUM(r.paidAmount), 0) FROM Receivable r WHERE r.corporateId = :corporateId")
    BigDecimal sumPaidByCorporate(@Param("corporateId") UUID corporateId);

    @Query("SELECT r.status, COALESCE(SUM(r.outstandingAmount), 0) FROM Receivable r WHERE r.corporateId = :corporateId GROUP BY r.status")
    List<Object[]> sumByStatusForCorporate(@Param("corporateId") UUID corporateId);

    // ========================================================================
    // E-COMMERCE QUERIES (Preserved)
    // ========================================================================

    List<Receivable> findByPlatform(String platform);

    Optional<Receivable> findByPlatformOrderId(String platformOrderId);

    @Query("SELECT r FROM Receivable r WHERE r.platform = :platform AND r.escrowStatus = :escrowStatus")
    List<Receivable> findByPlatformAndEscrowStatus(@Param("platform") String platform, 
                                                    @Param("escrowStatus") Receivable.EscrowStatus escrowStatus);

    @Query("SELECT r.platform, COUNT(r), COALESCE(SUM(r.netAmount), 0), COALESCE(SUM(r.paidAmount), 0) " +
           "FROM Receivable r WHERE r.receivableType = 'ORDER' AND r.corporateId = :corporateId " +
           "GROUP BY r.platform")
    List<Object[]> sumByPlatformForCorporate(@Param("corporateId") UUID corporateId);

    // ========================================================================
    // UPDATE QUERIES (Preserved + Enhanced)
    // ========================================================================

    @Modifying
    @Query("UPDATE Receivable r SET r.status = :status, r.updatedAt = CURRENT_TIMESTAMP WHERE r.id = :id")
    void updateStatus(@Param("id") UUID id, @Param("status") ReceivableStatus status);

    @Modifying
    @Query("UPDATE Receivable r SET r.paidAmount = r.paidAmount + :amount, r.updatedAt = CURRENT_TIMESTAMP WHERE r.id = :id")
    void incrementPaidAmount(@Param("id") UUID id, @Param("amount") BigDecimal amount);

    @Modifying
    @Query("UPDATE Receivable r SET r.primaryVibanId = :vibanId, r.viban = :viban, r.updatedAt = CURRENT_TIMESTAMP WHERE r.id = :id")
    void linkViban(@Param("id") UUID id, @Param("vibanId") UUID vibanId, @Param("viban") String viban);

    @Modifying
    @Query("UPDATE Receivable r SET r.hierarchyNodeId = :nodeId, r.hierarchyPath = :path, r.updatedAt = CURRENT_TIMESTAMP WHERE r.id = :id")
    void updateHierarchyContext(@Param("id") UUID id, @Param("nodeId") UUID nodeId, @Param("path") String path);

    @Modifying
    @Query("UPDATE Receivable r SET r.status = 'OVERDUE', r.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE r.dueDate < :today AND r.status = 'OPEN'")
    int markOverdue(@Param("today") LocalDate today);

    // ========================================================================
    // PHASE 3: NEW UPDATE QUERIES
    // ========================================================================

    /**
     * Update COBO request status.
     */
    @Modifying
    @Query("UPDATE Receivable r SET r.coboRequestStatus = :status, " +
           "r.coboActionedAt = :actionedAt, r.coboActionedBy = :actionedBy, " +
           "r.updatedAt = CURRENT_TIMESTAMP WHERE r.id = :id")
    void updateCoboStatus(@Param("id") UUID id, 
                          @Param("status") CoboRequestStatus status,
                          @Param("actionedAt") LocalDateTime actionedAt,
                          @Param("actionedBy") String actionedBy);

    /**
     * Update netting cycle assignment.
     */
    @Modifying
    @Query("UPDATE Receivable r SET r.nettingCycleId = :cycleId, r.nettingCycleRef = :cycleRef, " +
           "r.nettingEntryId = :entryId, r.nettingStatus = :status, " +
           "r.collectionRoute = 'NETTING', r.status = 'PENDING_NETTING', " +
           "r.updatedAt = CURRENT_TIMESTAMP WHERE r.id = :id")
    void assignToNettingCycle(@Param("id") UUID id,
                               @Param("cycleId") UUID nettingCycleId,
                               @Param("cycleRef") String nettingCycleRef,
                               @Param("entryId") UUID entryId,
                               @Param("status") NettingStatus nettingStatus);

    /**
     * Mark netting settled.
     */
    @Modifying
    @Query("UPDATE Receivable r SET r.nettingStatus = 'SETTLED', " +
           "r.nettingSettlementRef = :settlementRef, r.nettingSettledAt = :settledAt, " +
           "r.status = 'NETTED', r.paymentStatus = 'COMPLETE', " +
           "r.paidAmount = r.netAmount, r.outstandingAmount = 0, " +
           "r.updatedAt = CURRENT_TIMESTAMP WHERE r.id = :id")
    void markNettingSettled(@Param("id") UUID id,
                            @Param("settlementRef") String settlementRef,
                            @Param("settledAt") LocalDateTime settledAt);

    /**
     * Batch mark netting settled for a cycle.
     */
    @Modifying
    @Query("UPDATE Receivable r SET r.nettingStatus = 'SETTLED', " +
           "r.nettingSettlementRef = :settlementRef, r.nettingSettledAt = :settledAt, " +
           "r.status = 'NETTED', r.paymentStatus = 'COMPLETE', " +
           "r.paidAmount = r.netAmount, r.outstandingAmount = 0, " +
           "r.updatedAt = CURRENT_TIMESTAMP WHERE r.nettingCycleId = :cycleId")
    int markNettingSettledByCycle(@Param("cycleId") UUID nettingCycleId,
                                   @Param("settlementRef") String settlementRef,
                                   @Param("settledAt") LocalDateTime settledAt);

    /**
     * Remove from netting cycle.
     */
    @Modifying
    @Query("UPDATE Receivable r SET r.nettingCycleId = NULL, r.nettingCycleRef = NULL, " +
           "r.nettingEntryId = NULL, r.nettingStatus = 'NOT_INCLUDED', " +
           "r.collectionRoute = CASE WHEN r.isIntercompany = true THEN 'INTERCOMPANY' ELSE 'DIRECT' END, " +
           "r.status = 'OPEN', r.updatedAt = CURRENT_TIMESTAMP WHERE r.id = :id")
    void removeFromNettingCycle(@Param("id") UUID id);

    /**
     * Update entity context.
     */
    @Modifying
    @Query("UPDATE Receivable r SET r.owningEntityId = :entityId, " +
           "r.owningEntityCode = :entityCode, r.owningEntityName = :entityName, " +
           "r.updatedAt = CURRENT_TIMESTAMP WHERE r.id = :id")
    void updateOwningEntity(@Param("id") UUID id,
                            @Param("entityId") UUID owningEntityId,
                            @Param("entityCode") String owningEntityCode,
                            @Param("entityName") String owningEntityName);

    /**
     * Update customer party.
     */
    @Modifying
    @Query("UPDATE Receivable r SET r.customerPartyId = :partyId, " +
           "r.updatedAt = CURRENT_TIMESTAMP WHERE r.id = :id")
    void updateCustomerParty(@Param("id") UUID id, @Param("partyId") UUID customerPartyId);

    /**
     * Set intercompany details.
     */
    @Modifying
    @Query("UPDATE Receivable r SET r.isIntercompany = true, " +
           "r.intercompanyEntityId = :entityId, r.intercompanyEntityCode = :entityCode, " +
           "r.intercompanyEntityName = :entityName, r.nettingEligible = true, " +
           "r.collectionRoute = 'INTERCOMPANY', r.receivableType = 'INTERCOMPANY', " +
           "r.updatedAt = CURRENT_TIMESTAMP WHERE r.id = :id")
    void setIntercompany(@Param("id") UUID id,
                         @Param("entityId") UUID intercompanyEntityId,
                         @Param("entityCode") String intercompanyEntityCode,
                         @Param("entityName") String intercompanyEntityName);

    /**
     * Link counterparty payable.
     */
    @Modifying
    @Query("UPDATE Receivable r SET r.counterpartyPayableId = :payableId, " +
           "r.updatedAt = CURRENT_TIMESTAMP WHERE r.id = :id")
    void linkCounterpartyPayable(@Param("id") UUID id, @Param("payableId") UUID counterpartyPayableId);

    // ========================================================================
    // SEARCH & ADVANCED FILTERS (Phase 3 Enhanced)
    // ========================================================================

    /**
     * Full-text search.
     */
    @Query("SELECT r FROM Receivable r WHERE " +
           "r.receivableNumber LIKE CONCAT('%', :query, '%') OR " +
           "r.customerName LIKE CONCAT('%', :query, '%') OR " +
           "r.externalReference LIKE CONCAT('%', :query, '%') OR " +
           "r.owningEntityCode LIKE CONCAT('%', :query, '%') OR " +
           "r.intercompanyEntityCode LIKE CONCAT('%', :query, '%') OR " +
           "r.viban LIKE CONCAT('%', :query, '%')")
    Page<Receivable> search(@Param("query") String query, Pageable pageable);

    /**
     * Advanced filter with all Phase 3 fields.
     */
    @Query("SELECT r FROM Receivable r WHERE " +
           "(:corporateId IS NULL OR r.corporateId = :corporateId) AND " +
           "(:owningEntityId IS NULL OR r.owningEntityId = :owningEntityId) AND " +
           "(:customerPartyId IS NULL OR r.customerPartyId = :customerPartyId) AND " +
           "(:status IS NULL OR r.status = :status) AND " +
           "(:type IS NULL OR r.receivableType = :type) AND " +
           "(:channel IS NULL OR r.collectionChannel = :channel) AND " +
           "(:collectionRoute IS NULL OR r.collectionRoute = :collectionRoute) AND " +
           "(:isIntercompany IS NULL OR r.isIntercompany = :isIntercompany) AND " +
           "(:intercompanyEntityId IS NULL OR r.intercompanyEntityId = :intercompanyEntityId) AND " +
           "(:isCobo IS NULL OR r.isCobo = :isCobo) AND " +
           "(:coboRequestStatus IS NULL OR r.coboRequestStatus = :coboRequestStatus) AND " +
           "(:nettingEligible IS NULL OR r.nettingEligible = :nettingEligible) AND " +
           "(:nettingStatus IS NULL OR r.nettingStatus = :nettingStatus) AND " +
           "(:nettingCycleId IS NULL OR r.nettingCycleId = :nettingCycleId) AND " +
           "(:fromDate IS NULL OR r.issueDate >= :fromDate) AND " +
           "(:toDate IS NULL OR r.issueDate <= :toDate)")
    Page<Receivable> findWithFilters(
            @Param("corporateId") UUID corporateId,
            @Param("owningEntityId") UUID owningEntityId,
            @Param("customerPartyId") UUID customerPartyId,
            @Param("status") ReceivableStatus status,
            @Param("type") ReceivableType type,
            @Param("channel") CollectionChannel channel,
            @Param("collectionRoute") CollectionRoute collectionRoute,
            @Param("isIntercompany") Boolean isIntercompany,
            @Param("intercompanyEntityId") UUID intercompanyEntityId,
            @Param("isCobo") Boolean isCobo,
            @Param("coboRequestStatus") CoboRequestStatus coboRequestStatus,
            @Param("nettingEligible") Boolean nettingEligible,
            @Param("nettingStatus") NettingStatus nettingStatus,
            @Param("nettingCycleId") UUID nettingCycleId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            Pageable pageable);

    /**
     * Simplified filter (most common use case).
     */
    @Query("SELECT r FROM Receivable r WHERE " +
           "(:corporateId IS NULL OR r.corporateId = :corporateId) AND " +
           "(:owningEntityId IS NULL OR r.owningEntityId = :owningEntityId) AND " +
           "(:status IS NULL OR r.status = :status) AND " +
           "(:collectionRoute IS NULL OR r.collectionRoute = :collectionRoute)")
    Page<Receivable> findWithSimpleFilters(
            @Param("corporateId") UUID corporateId,
            @Param("owningEntityId") UUID owningEntityId,
            @Param("status") ReceivableStatus status,
            @Param("collectionRoute") CollectionRoute collectionRoute,
            Pageable pageable);


            // ============================================================================
// ADD THESE METHODS TO EXISTING ReceivableRepository.java
// Location: backend/src/main/java/com/bank/vam/repository/receivables/ReceivableRepository.java
// ============================================================================

// Add these imports if not present:
// import com.bank.vam.entity.receivables.Receivable.NettingStatus;
// import com.bank.vam.entity.receivables.Receivable.ReceivableStatus;

    // ========================================================================
    // PHASE 4: NETTING QUERIES
    // ========================================================================
    
    /**
     * Find all receivables eligible for netting that are not yet in a cycle.
     * 
     * Criteria:
     * - Intercompany = true OR nettingEligible = true
     * - Status = OPEN or COBO_APPROVED
     * - nettingCycleId is null
     * - nettingStatus = NOT_INCLUDED
     */
    @Query("""
        SELECT r FROM Receivable r 
        WHERE r.corporateId = :corporateId
        AND (r.isIntercompany = true OR r.nettingEligible = true)
        AND r.status IN ('OPEN', 'COBO_APPROVED')
        AND r.nettingCycleId IS NULL
        AND r.nettingStatus = 'NOT_INCLUDED'
        ORDER BY r.dueDate ASC
        """)
    List<Receivable> findNettingEligibleReceivables(@Param("corporateId") UUID corporateId);
    
    /**
     * Find intercompany receivables for a specific counterparty entity.
     */
    @Query("""
        SELECT r FROM Receivable r 
        WHERE r.corporateId = :corporateId
        AND r.isIntercompany = true
        AND r.intercompanyEntityId = :counterpartyEntityId
        AND r.status IN ('OPEN', 'COBO_APPROVED')
        AND r.nettingCycleId IS NULL
        ORDER BY r.dueDate ASC
        """)
    List<Receivable> findIntercompanyReceivablesForCounterparty(
        @Param("corporateId") UUID corporateId,
        @Param("counterpartyEntityId") UUID counterpartyEntityId
    );
    
    /**
     * Find receivables in a specific netting cycle.
     */
    @Query("SELECT r FROM Receivable r WHERE r.nettingCycleId = :cycleId")
    List<Receivable> findByNettingCycleId(@Param("cycleId") UUID cycleId);
    
    /**
     * Find receivables by owning entity that are netting-eligible.
     */
    @Query("""
        SELECT r FROM Receivable r 
        WHERE r.owningEntityId = :entityId
        AND (r.isIntercompany = true OR r.nettingEligible = true)
        AND r.status IN ('OPEN', 'COBO_APPROVED')
        AND r.nettingCycleId IS NULL
        ORDER BY r.dueDate ASC
        """)
    List<Receivable> findNettingEligibleByOwningEntity(@Param("entityId") UUID entityId);
    
    /**
     * Count netting-eligible receivables for corporate.
     */
    @Query("""
        SELECT COUNT(r) FROM Receivable r 
        WHERE r.corporateId = :corporateId
        AND (r.isIntercompany = true OR r.nettingEligible = true)
        AND r.status IN ('OPEN', 'COBO_APPROVED')
        AND r.nettingCycleId IS NULL
        """)
    long countNettingEligibleReceivables(@Param("corporateId") UUID corporateId);
    
    /**
     * Sum of netting-eligible receivable amounts for corporate.
     */
    @Query("""
        SELECT COALESCE(SUM(r.netAmount), 0) FROM Receivable r 
        WHERE r.corporateId = :corporateId
        AND (r.isIntercompany = true OR r.nettingEligible = true)
        AND r.status IN ('OPEN', 'COBO_APPROVED')
        AND r.nettingCycleId IS NULL
        """)
    BigDecimal sumNettingEligibleReceivables(@Param("corporateId") UUID corporateId);
    
    /**
     * Find receivables pending netting settlement.
     */
    @Query("""
        SELECT r FROM Receivable r 
        WHERE r.nettingCycleId = :cycleId
        AND r.nettingStatus = 'INCLUDED'
        """)
    List<Receivable> findPendingNettingSettlement(@Param("cycleId") UUID cycleId);
    
    /**
     * Find intercompany receivables by owning and counterparty entity pair.
     * Used for bilateral netting position calculation.
     */
    @Query("""
        SELECT r FROM Receivable r 
        WHERE r.corporateId = :corporateId
        AND r.owningEntityId = :owningEntityId
        AND r.intercompanyEntityId = :counterpartyEntityId
        AND r.isIntercompany = true
        AND r.status IN ('OPEN', 'COBO_APPROVED')
        ORDER BY r.dueDate ASC
        """)
    List<Receivable> findByEntityPair(
        @Param("corporateId") UUID corporateId,
        @Param("owningEntityId") UUID owningEntityId,
        @Param("counterpartyEntityId") UUID counterpartyEntityId
    );

    // ========================================================================
    // FORECASTING — AGING ENGINE (Sprint 1, T5)
    // ========================================================================

    /**
     * Page of OPEN/PARTIAL receivables for the aging forecast engine.
     *
     * <p>Window is the engine horizon padded ±30 days so the DSO shift can pull
     * pre-horizon due dates into the window (and shift post-horizon ones back
     * via the {@code horizonEnd} clip).
     */
    @Query("""
        SELECT r FROM Receivable r
        WHERE r.owningEntityId IN :entityIds
          AND r.status IN ('OPEN', 'PARTIAL')
          AND r.dueDate IS NOT NULL
          AND r.dueDate BETWEEN :from AND :to
        """)
    Page<Receivable> findForAgingForecast(
        @Param("entityIds") java.util.Collection<UUID> entityIds,
        @Param("from") LocalDate from,
        @Param("to") LocalDate to,
        Pageable pageable
    );

    /**
     * Corporate-wide DSO (avg actual-paid − due, in days) over receivables
     * whose latest applied payment fell on or after {@code since}.
     *
     * <p>Returns {@code null} when no paid receivables exist in the window —
     * callers floor the missing/negative result to 0.
     */
    @Query(value = """
        SELECT AVG(CAST((latest.paid_at::date - r.due_date) AS DOUBLE PRECISION))
        FROM receivables r
        JOIN (
            SELECT receivable_id, MAX(payment_date) AS paid_at
            FROM receivable_payments
            WHERE status = 'APPLIED'
            GROUP BY receivable_id
        ) latest ON latest.receivable_id = r.id
        WHERE r.status = 'PAID'
          AND r.owning_entity_id IN (:entityIds)
          AND r.due_date IS NOT NULL
          AND latest.paid_at >= :since
        """, nativeQuery = true)
    Double computeCorporateDsoDays(
        @Param("entityIds") java.util.Collection<UUID> entityIds,
        @Param("since") LocalDateTime since
    );

       }