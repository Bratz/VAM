package com.bank.vam.repository.treasury;

import com.bank.vam.entity.treasury.IhbDeposit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IhbDepositRepository extends JpaRepository<IhbDeposit, UUID> {
    Optional<IhbDeposit> findByDepositReference(String depositReference);
    List<IhbDeposit> findByStatus(IhbDeposit.DepositStatus status);
    
    @Query("SELECT d FROM IhbDeposit d WHERE d.depositorEntity.id = :entityId")
    List<IhbDeposit> findByDepositorId(@Param("entityId") UUID entityId);
    
    @Query("SELECT COALESCE(SUM(d.currentBalance), 0) FROM IhbDeposit d WHERE d.status = 'ACTIVE'")
    java.math.BigDecimal sumActiveDeposits();

    // ============================================================================
// PATCH: IhbDepositRepository.java - Add LegalEntity Query Methods
// ============================================================================
// 
// Add these methods to the existing IhbDepositRepository
//
// LOCATION: backend/src/main/java/com/bank/vam/repository/treasury/IhbDepositRepository.java
// ============================================================================

    // ========================================================================
    // LEGAL ENTITY QUERY METHODS (NEW - Phase 2 Unification)
    // ========================================================================

    /**
     * Find deposits by corporate ID.
     */
    List<IhbDeposit> findByCorporateId(UUID corporateId);

    /**
     * Find deposits by corporate ID and status.
     */
    List<IhbDeposit> findByCorporateIdAndStatus(UUID corporateId, IhbDeposit.DepositStatus status);

    /**
     * Find deposits by depositor legal entity ID.
     */
    List<IhbDeposit> findByDepositorLegalEntityId(UUID depositorLegalEntityId);

    /**
     * Find deposits by depositor legal entity ID and status.
     */
    List<IhbDeposit> findByDepositorLegalEntityIdAndStatus(UUID depositorLegalEntityId, IhbDeposit.DepositStatus status);

    /**
     * Find deposits by treasury legal entity ID.
     */
    List<IhbDeposit> findByTreasuryLegalEntityId(UUID treasuryLegalEntityId);

    /**
     * Sum active deposits for a corporate.
     */
    @Query("SELECT COALESCE(SUM(d.currentBalance), 0) FROM IhbDeposit d " +
           "WHERE d.corporateId = :corporateId AND d.status = 'ACTIVE'")
    BigDecimal sumActiveDepositsByCorporate(@Param("corporateId") UUID corporateId);

    /**
     * Sum accrued interest for deposits in a corporate.
     */
    @Query("SELECT COALESCE(SUM(d.accruedInterest), 0) FROM IhbDeposit d " +
           "WHERE d.corporateId = :corporateId AND d.status = 'ACTIVE'")
    BigDecimal sumAccruedInterestByCorporate(@Param("corporateId") UUID corporateId);

    /**
     * Find deposits maturing within a date range.
     */
    @Query("SELECT d FROM IhbDeposit d WHERE d.corporateId = :corporateId " +
           "AND d.status = 'ACTIVE' AND d.maturityDate <= :maturityDate")
    List<IhbDeposit> findDepositsMaturing(@Param("corporateId") UUID corporateId,
                                           @Param("maturityDate") LocalDate maturityDate);

    /**
     * Count active deposits for a corporate.
     */
    long countByCorporateIdAndStatus(UUID corporateId, IhbDeposit.DepositStatus status);

    // ========================================================================
    // SETTLEMENT SERVICE QUERIES (Option B Architecture)
    // ========================================================================

    /**
     * Find SETTLED deposits maturing on or before the given date.
     * Used by IhbSettlementService for EOD maturity processing.
     */
    @Query("SELECT d FROM IhbDeposit d WHERE d.corporateId = :corporateId " +
           "AND d.status = 'SETTLED' AND d.maturityDate <= :maturityDate")
    List<IhbDeposit> findMaturingDeposits(@Param("corporateId") UUID corporateId,
                                           @Param("maturityDate") LocalDate maturityDate);

    /**
     * Find all COMMITTED deposits (awaiting settlement).
     */
    @Query("SELECT d FROM IhbDeposit d WHERE d.status = 'COMMITTED'")
    List<IhbDeposit> findAllCommitted();

    /**
     * Find COMMITTED deposits by corporate.
     */
    @Query("SELECT d FROM IhbDeposit d WHERE d.corporateId = :corporateId AND d.status = 'COMMITTED'")
    List<IhbDeposit> findCommittedByCorporate(@Param("corporateId") UUID corporateId);

    /**
     * Find APPROVED deposits (manual, awaiting settlement).
     */
    @Query("SELECT d FROM IhbDeposit d WHERE d.corporateId = :corporateId AND d.status = 'APPROVED'")
    List<IhbDeposit> findApprovedByCorporate(@Param("corporateId") UUID corporateId);

    /**
     * Find PENDING deposits (manual, awaiting approval).
     */
    @Query("SELECT d FROM IhbDeposit d WHERE d.corporateId = :corporateId AND d.status = 'PENDING'")
    List<IhbDeposit> findPendingByCorporate(@Param("corporateId") UUID corporateId);

    /**
     * Sum committed deposit amounts for a corporate.
     */
    @Query("SELECT COALESCE(SUM(d.currentBalance), 0) FROM IhbDeposit d " +
           "WHERE d.corporateId = :corporateId AND d.status = 'COMMITTED'")
    BigDecimal sumCommittedDepositsByCorporate(@Param("corporateId") UUID corporateId);

    /**
     * Find deposits by sweep execution reference.
     */
    Optional<IhbDeposit> findBySweepExecutionReference(String sweepExecutionReference);

}

