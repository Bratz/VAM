package com.bank.vam.repository.tax;

import com.bank.vam.entity.tax.WithholdingTaxTreaty;
import com.bank.vam.entity.tax.WithholdingTaxTreaty.IncomeType;
import com.bank.vam.entity.tax.WithholdingTaxTreaty.TreatyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for WithholdingTaxTreaty operations.
 *
 * Primary use case: IHB interest WHT lookup between entity jurisdictions.
 */
@Repository
public interface WithholdingTaxTreatyRepository extends JpaRepository<WithholdingTaxTreaty, UUID> {

    // ========================================================================
    // BASIC FINDERS
    // ========================================================================

    Optional<WithholdingTaxTreaty> findByTreatyCode(String treatyCode);

    List<WithholdingTaxTreaty> findByPayerJurisdictionCode(String payerJurisdictionCode);

    List<WithholdingTaxTreaty> findByRecipientJurisdictionCode(String recipientJurisdictionCode);

    List<WithholdingTaxTreaty> findByIncomeType(IncomeType incomeType);

    List<WithholdingTaxTreaty> findByStatus(TreatyStatus status);

    // ========================================================================
    // ACTIVE TREATY LOOKUPS
    // ========================================================================

    /**
     * Find active treaty between two jurisdictions for a specific income type.
     * This is the primary lookup for IHB interest WHT.
     */
    @Query("""
        SELECT t FROM WithholdingTaxTreaty t
        WHERE t.payerJurisdictionCode = :payerJurisdiction
        AND t.recipientJurisdictionCode = :recipientJurisdiction
        AND t.incomeType = :incomeType
        AND t.status = 'ACTIVE'
        AND (t.effectiveFrom IS NULL OR t.effectiveFrom <= :today)
        AND (t.effectiveTo IS NULL OR t.effectiveTo >= :today)
        ORDER BY t.effectiveFrom DESC
        """)
    Optional<WithholdingTaxTreaty> findActiveTreaty(
        @Param("payerJurisdiction") String payerJurisdiction,
        @Param("recipientJurisdiction") String recipientJurisdiction,
        @Param("incomeType") IncomeType incomeType,
        @Param("today") LocalDate today);

    /**
     * Find active interest treaty between two jurisdictions.
     * Convenience method for IHB.
     */
    default Optional<WithholdingTaxTreaty> findActiveInterestTreaty(
            String payerJurisdiction, String recipientJurisdiction) {
        return findActiveTreaty(payerJurisdiction, recipientJurisdiction,
            IncomeType.INTEREST, LocalDate.now());
    }

    /**
     * Find all active treaties for a payer jurisdiction.
     */
    @Query("""
        SELECT t FROM WithholdingTaxTreaty t
        WHERE t.payerJurisdictionCode = :jurisdiction
        AND t.status = 'ACTIVE'
        AND (t.effectiveFrom IS NULL OR t.effectiveFrom <= CURRENT_DATE)
        AND (t.effectiveTo IS NULL OR t.effectiveTo >= CURRENT_DATE)
        ORDER BY t.recipientJurisdictionCode, t.incomeType
        """)
    List<WithholdingTaxTreaty> findActiveTreatiesByPayer(@Param("jurisdiction") String jurisdiction);

    /**
     * Find all active treaties for a recipient jurisdiction.
     */
    @Query("""
        SELECT t FROM WithholdingTaxTreaty t
        WHERE t.recipientJurisdictionCode = :jurisdiction
        AND t.status = 'ACTIVE'
        AND (t.effectiveFrom IS NULL OR t.effectiveFrom <= CURRENT_DATE)
        AND (t.effectiveTo IS NULL OR t.effectiveTo >= CURRENT_DATE)
        ORDER BY t.payerJurisdictionCode, t.incomeType
        """)
    List<WithholdingTaxTreaty> findActiveTreatiesByRecipient(@Param("jurisdiction") String jurisdiction);

    /**
     * Find all active interest treaties.
     */
    @Query("""
        SELECT t FROM WithholdingTaxTreaty t
        WHERE t.incomeType = 'INTEREST'
        AND t.status = 'ACTIVE'
        AND (t.effectiveFrom IS NULL OR t.effectiveFrom <= CURRENT_DATE)
        AND (t.effectiveTo IS NULL OR t.effectiveTo >= CURRENT_DATE)
        ORDER BY t.payerJurisdictionCode, t.recipientJurisdictionCode
        """)
    List<WithholdingTaxTreaty> findAllActiveInterestTreaties();

    // ========================================================================
    // RATE LOOKUPS
    // ========================================================================

    /**
     * Check if WHT applies between two jurisdictions for interest.
     * Returns true if there's a treaty with rate > 0.
     */
    @Query("""
        SELECT CASE WHEN COUNT(t) > 0 THEN true ELSE false END
        FROM WithholdingTaxTreaty t
        WHERE t.payerJurisdictionCode = :payerJurisdiction
        AND t.recipientJurisdictionCode = :recipientJurisdiction
        AND t.incomeType = 'INTEREST'
        AND t.status = 'ACTIVE'
        AND t.treatyRate > 0
        AND (t.effectiveFrom IS NULL OR t.effectiveFrom <= CURRENT_DATE)
        AND (t.effectiveTo IS NULL OR t.effectiveTo >= CURRENT_DATE)
        """)
    boolean hasWithholdingTax(
        @Param("payerJurisdiction") String payerJurisdiction,
        @Param("recipientJurisdiction") String recipientJurisdiction);

    /**
     * Get distinct payer jurisdictions with treaties.
     */
    @Query("SELECT DISTINCT t.payerJurisdictionCode FROM WithholdingTaxTreaty t WHERE t.status = 'ACTIVE'")
    List<String> findDistinctPayerJurisdictions();

    /**
     * Get distinct recipient jurisdictions with treaties.
     */
    @Query("SELECT DISTINCT t.recipientJurisdictionCode FROM WithholdingTaxTreaty t WHERE t.status = 'ACTIVE'")
    List<String> findDistinctRecipientJurisdictions();

    // ========================================================================
    // EXISTENCE CHECKS
    // ========================================================================

    boolean existsByTreatyCode(String treatyCode);

    boolean existsByPayerJurisdictionCodeAndRecipientJurisdictionCodeAndIncomeType(
        String payerJurisdiction, String recipientJurisdiction, IncomeType incomeType);
}
