package com.bank.vam.repository.treasury;

import com.bank.vam.entity.treasury.NettingCycle;
import com.bank.vam.entity.treasury.NettingCycle.CycleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NettingCycleRepository extends JpaRepository<NettingCycle, UUID> {
    
    Optional<NettingCycle> findByCycleReference(String cycleReference);
    
    @Query("SELECT c FROM NettingCycle c LEFT JOIN FETCH c.entries LEFT JOIN FETCH c.settlements WHERE c.id = :id")
    Optional<NettingCycle> findByIdWithEntries(@Param("id") UUID id);
    
    @Query("SELECT c FROM NettingCycle c LEFT JOIN FETCH c.entries LEFT JOIN FETCH c.settlements WHERE c.id = :id")
    Optional<NettingCycle> findByIdWithSettlements(@Param("id") UUID id);
    
    /**
     * Count cycles by status using enum type
     */
    long countByStatus(CycleStatus status);
    
    /**
     * Find cycles by status using enum type
     */
    List<NettingCycle> findByStatus(CycleStatus status);
    
    /**
     * Sum total savings from settled cycles
     */
    @Query("SELECT COALESCE(SUM(nc.savingsAmount), 0) FROM NettingCycle nc WHERE nc.status = 'SETTLED'")
    BigDecimal sumTotalSavings();
    
    /**
     * Count pending approval cycles
     */
    @Query("SELECT COUNT(c) FROM NettingCycle c WHERE c.status = 'PENDING_APPROVAL'")
    long countPendingApproval();
    
    /**
     * Find cycles by status ordered by creation date
     */
    List<NettingCycle> findByStatusOrderByCreatedAtDesc(CycleStatus status);
}