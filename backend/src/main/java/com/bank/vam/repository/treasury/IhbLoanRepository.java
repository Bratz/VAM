package com.bank.vam.repository.treasury;

import com.bank.vam.entity.treasury.IhbLoan;
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
public interface IhbLoanRepository extends JpaRepository<IhbLoan, UUID> {
    Optional<IhbLoan> findByLoanReference(String loanReference);
    List<IhbLoan> findByStatus(IhbLoan.LoanStatus status);
    
    @Query("SELECT l FROM IhbLoan l WHERE l.lenderEntity.id = :entityId")
    List<IhbLoan> findByLenderId(@Param("entityId") UUID entityId);
    
    @Query("SELECT l FROM IhbLoan l WHERE l.borrowerEntity.id = :entityId")
    List<IhbLoan> findByBorrowerId(@Param("entityId") UUID entityId);
    
    @Query("SELECT COALESCE(SUM(l.outstandingAmount), 0) FROM IhbLoan l WHERE l.status = 'ACTIVE'")
    java.math.BigDecimal sumOutstandingLoans();

    // ============================================================================
// PATCH: IhbLoanRepository.java - Add LegalEntity Query Methods
// ============================================================================
// 
// Add these methods to the existing IhbLoanRepository
//
// LOCATION: backend/src/main/java/com/bank/vam/repository/treasury/IhbLoanRepository.java
// ============================================================================

    // ========================================================================
    // LEGAL ENTITY QUERY METHODS (NEW - Phase 2 Unification)
    // ========================================================================

    /**
     * Find loans by corporate ID.
     */
    List<IhbLoan> findByCorporateId(UUID corporateId);

    /**
     * Find loans by corporate ID and status.
     */
    List<IhbLoan> findByCorporateIdAndStatus(UUID corporateId, IhbLoan.LoanStatus status);

    /**
     * Find loans by lender legal entity ID.
     */
    List<IhbLoan> findByLenderLegalEntityId(UUID lenderLegalEntityId);

    /**
     * Find loans by lender legal entity ID and status.
     */
    List<IhbLoan> findByLenderLegalEntityIdAndStatus(UUID lenderLegalEntityId, IhbLoan.LoanStatus status);

    /**
     * Find loans by borrower legal entity ID.
     */
    List<IhbLoan> findByBorrowerLegalEntityId(UUID borrowerLegalEntityId);

    /**
     * Find loans by borrower legal entity ID and status.
     */
    List<IhbLoan> findByBorrowerLegalEntityIdAndStatus(UUID borrowerLegalEntityId, IhbLoan.LoanStatus status);

    /**
     * Sum outstanding loans for a corporate.
     */
    @Query("SELECT COALESCE(SUM(l.outstandingAmount), 0) FROM IhbLoan l " +
           "WHERE l.corporateId = :corporateId AND l.status = 'ACTIVE'")
    BigDecimal sumOutstandingLoansByCorporate(@Param("corporateId") UUID corporateId);

    /**
     * Sum accrued interest for a corporate.
     */
    @Query("SELECT COALESCE(SUM(l.accruedInterest), 0) FROM IhbLoan l " +
           "WHERE l.corporateId = :corporateId AND l.status = 'ACTIVE'")
    BigDecimal sumAccruedInterestByCorporate(@Param("corporateId") UUID corporateId);

    /**
     * Find loans maturing within N days.
     */
    @Query("SELECT l FROM IhbLoan l WHERE l.corporateId = :corporateId " +
           "AND l.status = 'ACTIVE' AND l.maturityDate <= :maturityDate")
    List<IhbLoan> findLoansMaturing(@Param("corporateId") UUID corporateId,
                                     @Param("maturityDate") LocalDate maturityDate);

    /**
     * Count active loans for a corporate.
     */
    long countByCorporateIdAndStatus(UUID corporateId, IhbLoan.LoanStatus status);

    // ========================================================================
    // SETTLEMENT SERVICE QUERIES (Option B Architecture)
    // ========================================================================

    /**
     * Find SETTLED loans maturing on or before the given date.
     * Used by IhbSettlementService for EOD maturity processing.
     */
    @Query("SELECT l FROM IhbLoan l WHERE l.corporateId = :corporateId " +
           "AND l.status = 'SETTLED' AND l.maturityDate <= :maturityDate")
    List<IhbLoan> findMaturingLoans(@Param("corporateId") UUID corporateId,
                                     @Param("maturityDate") LocalDate maturityDate);

    /**
     * Find all COMMITTED loans (awaiting settlement).
     */
    @Query("SELECT l FROM IhbLoan l WHERE l.status = 'COMMITTED'")
    List<IhbLoan> findAllCommitted();

    /**
     * Find COMMITTED loans by corporate.
     */
    @Query("SELECT l FROM IhbLoan l WHERE l.corporateId = :corporateId AND l.status = 'COMMITTED'")
    List<IhbLoan> findCommittedByCorporate(@Param("corporateId") UUID corporateId);

    /**
     * Find APPROVED loans (manual, awaiting settlement).
     */
    @Query("SELECT l FROM IhbLoan l WHERE l.corporateId = :corporateId AND l.status = 'APPROVED'")
    List<IhbLoan> findApprovedByCorporate(@Param("corporateId") UUID corporateId);

    /**
     * Find PENDING loans (manual, awaiting approval).
     */
    @Query("SELECT l FROM IhbLoan l WHERE l.corporateId = :corporateId AND l.status = 'PENDING'")
    List<IhbLoan> findPendingByCorporate(@Param("corporateId") UUID corporateId);

    /**
     * Sum committed loan amounts for a corporate.
     */
    @Query("SELECT COALESCE(SUM(l.outstandingAmount), 0) FROM IhbLoan l " +
           "WHERE l.corporateId = :corporateId AND l.status = 'COMMITTED'")
    BigDecimal sumCommittedLoansByCorporate(@Param("corporateId") UUID corporateId);

    /**
     * Find loans by sweep execution reference.
     */
    Optional<IhbLoan> findBySweepExecutionReference(String sweepExecutionReference);
}

