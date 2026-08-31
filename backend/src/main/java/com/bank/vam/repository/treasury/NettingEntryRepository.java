package com.bank.vam.repository.treasury;

import com.bank.vam.entity.treasury.NettingEntry;
import com.bank.vam.entity.treasury.NettingEntry.EntryStatus;
import com.bank.vam.entity.treasury.NettingEntry.FlowDirection;
import com.bank.vam.entity.treasury.NettingEntry.SourceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * NettingEntryRepository - Phase 4 Enhanced for Unified Netting Engine
 */
@Repository
public interface NettingEntryRepository extends JpaRepository<NettingEntry, UUID> {

    // ========================================================================
    // BASIC QUERIES
    // ========================================================================
    
    List<NettingEntry> findByCycleId(UUID cycleId);
    
    List<NettingEntry> findByCycleIdAndStatus(UUID cycleId, EntryStatus status);
    
    Optional<NettingEntry> findByEntryReference(String entryReference);
    
    // ========================================================================
    // PHASE 4: FLOW DIRECTION QUERIES
    // ========================================================================
    
    /**
     * Find all payable entries in a cycle.
     */
    @Query("SELECT e FROM NettingEntry e WHERE e.cycle.id = :cycleId AND e.flowDirection = 'PAYABLE'")
    List<NettingEntry> findPayableEntries(@Param("cycleId") UUID cycleId);
    
    /**
     * Find all receivable entries in a cycle.
     */
    @Query("SELECT e FROM NettingEntry e WHERE e.cycle.id = :cycleId AND e.flowDirection = 'RECEIVABLE'")
    List<NettingEntry> findReceivableEntries(@Param("cycleId") UUID cycleId);
    
    /**
     * Count entries by flow direction.
     */
    @Query("SELECT COUNT(e) FROM NettingEntry e WHERE e.cycle.id = :cycleId AND e.flowDirection = :direction")
    long countByFlowDirection(@Param("cycleId") UUID cycleId, @Param("direction") FlowDirection direction);
    
    /**
     * Sum base amount by flow direction.
     */
    @Query("SELECT COALESCE(SUM(e.baseAmount), 0) FROM NettingEntry e WHERE e.cycle.id = :cycleId AND e.flowDirection = :direction AND e.status != 'EXCLUDED'")
    BigDecimal sumByFlowDirection(@Param("cycleId") UUID cycleId, @Param("direction") FlowDirection direction);
    
    // ========================================================================
    // PHASE 4: SOURCE DOCUMENT QUERIES
    // ========================================================================
    
    /**
     * Find entry by source payable.
     */
    Optional<NettingEntry> findByPayableId(UUID payableId);
    
    /**
     * Find entry by source receivable.
     */
    Optional<NettingEntry> findByReceivableId(UUID receivableId);
    
    /**
     * Find entry by intercompany recharge.
     */
    Optional<NettingEntry> findByIntercompanyRechargeId(UUID rechargeId);
    
    /**
     * Find entry by IHB transaction.
     */
    Optional<NettingEntry> findByIhbTransactionId(UUID ihbTransactionId);
    
    /**
     * Find all entries from payables in a cycle.
     */
    @Query("SELECT e FROM NettingEntry e WHERE e.cycle.id = :cycleId AND e.payableId IS NOT NULL")
    List<NettingEntry> findEntriesFromPayables(@Param("cycleId") UUID cycleId);
    
    /**
     * Find all entries from receivables in a cycle.
     */
    @Query("SELECT e FROM NettingEntry e WHERE e.cycle.id = :cycleId AND e.receivableId IS NOT NULL")
    List<NettingEntry> findEntriesFromReceivables(@Param("cycleId") UUID cycleId);
    
    // ========================================================================
    // PHASE 4: SOURCE TYPE QUERIES
    // ========================================================================
    
    /**
     * Find entries by source type.
     */
    List<NettingEntry> findByCycleIdAndSourceType(UUID cycleId, SourceType sourceType);
    
    /**
     * Count intercompany entries (both payable and receivable).
     */
    @Query("""
        SELECT COUNT(e) FROM NettingEntry e 
        WHERE e.cycle.id = :cycleId 
        AND e.sourceType IN ('INTERCOMPANY_PAYABLE', 'INTERCOMPANY_RECEIVABLE')
        """)
    long countIntercompanyEntries(@Param("cycleId") UUID cycleId);
    
    /**
     * Count POBO/COBO recharge entries.
     */
    @Query("""
        SELECT COUNT(e) FROM NettingEntry e 
        WHERE e.cycle.id = :cycleId 
        AND e.sourceType IN ('POBO_RECHARGE', 'COBO_COLLECTION')
        """)
    long countRechargeEntries(@Param("cycleId") UUID cycleId);
    
    // ========================================================================
    // PHASE 4: ENTITY QUERIES
    // ========================================================================
    
    /**
     * Find entries where entity is payer.
     */
    @Query("SELECT e FROM NettingEntry e WHERE e.cycle.id = :cycleId AND e.payerEntityId = :entityId")
    List<NettingEntry> findByPayerEntity(@Param("cycleId") UUID cycleId, @Param("entityId") UUID entityId);
    
    /**
     * Find entries where entity is payee.
     */
    @Query("SELECT e FROM NettingEntry e WHERE e.cycle.id = :cycleId AND e.payeeEntityId = :entityId")
    List<NettingEntry> findByPayeeEntity(@Param("cycleId") UUID cycleId, @Param("entityId") UUID entityId);
    
    /**
     * Find entries involving an entity (as payer or payee).
     */
    @Query("""
        SELECT e FROM NettingEntry e 
        WHERE e.cycle.id = :cycleId 
        AND (e.payerEntityId = :entityId OR e.payeeEntityId = :entityId)
        """)
    List<NettingEntry> findByInvolvingEntity(@Param("cycleId") UUID cycleId, @Param("entityId") UUID entityId);
    
    /**
     * Sum payable amount for entity in cycle.
     */
    @Query("""
        SELECT COALESCE(SUM(e.baseAmount), 0) FROM NettingEntry e 
        WHERE e.cycle.id = :cycleId 
        AND e.payerEntityId = :entityId 
        AND e.status != 'EXCLUDED'
        """)
    BigDecimal sumPayableForEntity(@Param("cycleId") UUID cycleId, @Param("entityId") UUID entityId);
    
    /**
     * Sum receivable amount for entity in cycle.
     */
    @Query("""
        SELECT COALESCE(SUM(e.baseAmount), 0) FROM NettingEntry e 
        WHERE e.cycle.id = :cycleId 
        AND e.payeeEntityId = :entityId 
        AND e.status != 'EXCLUDED'
        """)
    BigDecimal sumReceivableForEntity(@Param("cycleId") UUID cycleId, @Param("entityId") UUID entityId);
    
    // ========================================================================
    // PHASE 4: BILATERAL QUERIES
    // ========================================================================
    
    /**
     * Find entries between two specific entities.
     */
    @Query("""
        SELECT e FROM NettingEntry e 
        WHERE e.cycle.id = :cycleId 
        AND e.payerEntityId = :payerEntityId 
        AND e.payeeEntityId = :payeeEntityId
        """)
    List<NettingEntry> findBilateralEntries(
        @Param("cycleId") UUID cycleId,
        @Param("payerEntityId") UUID payerEntityId,
        @Param("payeeEntityId") UUID payeeEntityId
    );
    
    /**
     * Sum bilateral amount between two entities.
     */
    @Query("""
        SELECT COALESCE(SUM(e.baseAmount), 0) FROM NettingEntry e 
        WHERE e.cycle.id = :cycleId 
        AND e.payerEntityId = :payerEntityId 
        AND e.payeeEntityId = :payeeEntityId
        AND e.status != 'EXCLUDED'
        """)
    BigDecimal sumBilateralAmount(
        @Param("cycleId") UUID cycleId,
        @Param("payerEntityId") UUID payerEntityId,
        @Param("payeeEntityId") UUID payeeEntityId
    );
    
    // ========================================================================
    // PHASE 4: STATUS UPDATES
    // ========================================================================
    
    /**
     * Mark all entries in cycle as included.
     */
    @Modifying
    @Query("UPDATE NettingEntry e SET e.status = 'INCLUDED', e.updatedAt = CURRENT_TIMESTAMP WHERE e.cycle.id = :cycleId AND e.status = 'PENDING'")
    int includeAllPendingEntries(@Param("cycleId") UUID cycleId);
    
    /**
     * Mark all entries in cycle as settled.
     */
    @Modifying
    @Query("UPDATE NettingEntry e SET e.status = 'SETTLED', e.updatedAt = CURRENT_TIMESTAMP WHERE e.cycle.id = :cycleId AND e.status = 'INCLUDED'")
    int settleAllIncludedEntries(@Param("cycleId") UUID cycleId);
    
    /**
     * Exclude entry from netting.
     */
    @Modifying
    @Query("UPDATE NettingEntry e SET e.status = 'EXCLUDED', e.updatedAt = CURRENT_TIMESTAMP WHERE e.id = :entryId")
    int excludeEntry(@Param("entryId") UUID entryId);
    
    // ========================================================================
    // AGGREGATION QUERIES
    // ========================================================================
    
    /**
     * Get distinct payer entity IDs in cycle.
     */
    @Query("SELECT DISTINCT e.payerEntityId FROM NettingEntry e WHERE e.cycle.id = :cycleId")
    List<UUID> findDistinctPayerEntities(@Param("cycleId") UUID cycleId);
    
    /**
     * Get distinct payee entity IDs in cycle.
     */
    @Query("SELECT DISTINCT e.payeeEntityId FROM NettingEntry e WHERE e.cycle.id = :cycleId")
    List<UUID> findDistinctPayeeEntities(@Param("cycleId") UUID cycleId);
    
    /**
     * Get all distinct entity IDs in cycle (payers and payees).
     */
    @Query("""
        SELECT DISTINCT entityId FROM (
            SELECT e.payerEntityId as entityId FROM NettingEntry e WHERE e.cycle.id = :cycleId
            UNION
            SELECT e.payeeEntityId as entityId FROM NettingEntry e WHERE e.cycle.id = :cycleId
        )
        """)
    List<UUID> findAllParticipantEntities(@Param("cycleId") UUID cycleId);
}