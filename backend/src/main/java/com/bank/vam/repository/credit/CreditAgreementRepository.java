package com.bank.vam.repository.credit;

import com.bank.vam.entity.credit.CreditAgreement;
import com.bank.vam.entity.credit.CreditAgreement.AgreementStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for CreditAgreement operations.
 */
@Repository
public interface CreditAgreementRepository extends JpaRepository<CreditAgreement, UUID> {

    Optional<CreditAgreement> findByExternalReference(String externalReference);

    List<CreditAgreement> findByCorporateIdOrderByAgreementName(UUID corporateId);

    List<CreditAgreement> findByCorporateIdAndStatus(UUID corporateId, AgreementStatus status);

    List<CreditAgreement> findByBankCodeAndStatus(String bankCode, AgreementStatus status);

    @Query("SELECT ca FROM CreditAgreement ca WHERE ca.corporateId = :corporateId AND ca.status = 'ACTIVE' ORDER BY ca.agreementName")
    List<CreditAgreement> findActiveByCorporate(@Param("corporateId") UUID corporateId);

    @Query("""
        SELECT ca FROM CreditAgreement ca 
        WHERE ca.status = 'ACTIVE' 
        AND ca.expiryDate IS NOT NULL 
        AND ca.expiryDate <= :expiryDate
        ORDER BY ca.expiryDate
        """)
    List<CreditAgreement> findExpiringSoon(@Param("expiryDate") LocalDate expiryDate);

    @Query("SELECT COALESCE(SUM(ca.totalLimit), 0) FROM CreditAgreement ca WHERE ca.corporateId = :corporateId AND ca.status = 'ACTIVE'")
    BigDecimal getTotalLimitByCorporate(@Param("corporateId") UUID corporateId);

    @Query("SELECT COALESCE(SUM(ca.totalUtilized), 0) FROM CreditAgreement ca WHERE ca.corporateId = :corporateId AND ca.status = 'ACTIVE'")
    BigDecimal getTotalUtilizedByCorporate(@Param("corporateId") UUID corporateId);

    @Modifying
    @Query("""
        UPDATE CreditAgreement ca 
        SET ca.totalUtilized = :utilized, 
            ca.availableLimit = ca.totalLimit - :utilized,
            ca.updatedAt = CURRENT_TIMESTAMP 
        WHERE ca.id = :id
        """)
    int updateUtilization(@Param("id") UUID id, @Param("utilized") BigDecimal utilized);

    @Modifying
    @Query("UPDATE CreditAgreement ca SET ca.status = :status, ca.updatedAt = CURRENT_TIMESTAMP WHERE ca.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("status") AgreementStatus status);

    boolean existsByExternalReference(String externalReference);

    long countByCorporateIdAndStatus(UUID corporateId, AgreementStatus status);

    @Query("SELECT DISTINCT ca.bankCode FROM CreditAgreement ca WHERE ca.corporateId = :corporateId AND ca.bankCode IS NOT NULL")
    List<String> findDistinctBanks(@Param("corporateId") UUID corporateId);
}