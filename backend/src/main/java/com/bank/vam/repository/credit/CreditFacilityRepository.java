package com.bank.vam.repository.credit;

import com.bank.vam.entity.credit.CreditFacility;
import com.bank.vam.entity.credit.CreditFacility.FacilityStatus;
import com.bank.vam.entity.credit.CreditFacility.FacilityType;
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
 * Repository for CreditFacility operations.
 */
@Repository
public interface CreditFacilityRepository extends JpaRepository<CreditFacility, UUID> {

    Optional<CreditFacility> findByExternalReference(String externalReference);

    List<CreditFacility> findByCreditAgreementIdOrderByFacilityName(UUID agreementId);

    List<CreditFacility> findByCorporateIdOrderByFacilityName(UUID corporateId);

    List<CreditFacility> findByCorporateIdAndStatus(UUID corporateId, FacilityStatus status);

    List<CreditFacility> findByCorporateIdAndFacilityType(UUID corporateId, FacilityType type);

    Optional<CreditFacility> findByPhysicalAccountId(UUID physicalAccountId);

    @Query("SELECT cf FROM CreditFacility cf WHERE cf.corporateId = :corporateId AND cf.status = 'ACTIVE' ORDER BY cf.facilityName")
    List<CreditFacility> findActiveByCorporate(@Param("corporateId") UUID corporateId);

    @Query("""
        SELECT cf FROM CreditFacility cf 
        WHERE cf.creditAgreementId = :agreementId 
        AND cf.status = 'ACTIVE'
        ORDER BY cf.facilityName
        """)
    List<CreditFacility> findActiveByAgreement(@Param("agreementId") UUID agreementId);

    @Query("""
        SELECT cf FROM CreditFacility cf 
        WHERE cf.corporateId = :corporateId 
        AND cf.facilityType = 'OVERDRAFT' 
        AND cf.status = 'ACTIVE'
        """)
    List<CreditFacility> findActiveOverdrafts(@Param("corporateId") UUID corporateId);

    @Query("""
        SELECT cf FROM CreditFacility cf 
        WHERE cf.status = 'ACTIVE' 
        AND cf.expiryDate IS NOT NULL 
        AND cf.expiryDate <= :expiryDate
        ORDER BY cf.expiryDate
        """)
    List<CreditFacility> findExpiringSoon(@Param("expiryDate") LocalDate expiryDate);

    @Query("""
        SELECT cf FROM CreditFacility cf 
        WHERE cf.corporateId = :corporateId 
        AND cf.status = 'ACTIVE'
        AND cf.sanctionedLimit > 0
        AND (cf.currentOutstanding / cf.sanctionedLimit * 100) >= 80
        ORDER BY (cf.currentOutstanding / cf.sanctionedLimit) DESC
        """)
    List<CreditFacility> findHighUtilizationFacilities(@Param("corporateId") UUID corporateId);

    @Query("SELECT COALESCE(SUM(cf.sanctionedLimit), 0) FROM CreditFacility cf WHERE cf.creditAgreementId = :agreementId AND cf.status = 'ACTIVE'")
    BigDecimal getTotalSanctionedByAgreement(@Param("agreementId") UUID agreementId);

    @Query("SELECT COALESCE(SUM(cf.currentOutstanding), 0) FROM CreditFacility cf WHERE cf.creditAgreementId = :agreementId AND cf.status = 'ACTIVE'")
    BigDecimal getTotalOutstandingByAgreement(@Param("agreementId") UUID agreementId);

    @Modifying
    @Query("""
        UPDATE CreditFacility cf 
        SET cf.currentOutstanding = :outstanding, 
            cf.availableLimit = COALESCE(cf.drawingPower, cf.sanctionedLimit) - :outstanding,
            cf.updatedAt = CURRENT_TIMESTAMP 
        WHERE cf.id = :id
        """)
    int updateOutstanding(@Param("id") UUID id, @Param("outstanding") BigDecimal outstanding);

    @Modifying
    @Query("UPDATE CreditFacility cf SET cf.status = :status, cf.updatedAt = CURRENT_TIMESTAMP WHERE cf.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("status") FacilityStatus status);

    @Modifying
    @Query("""
        UPDATE CreditFacility cf 
        SET cf.drawingPower = :drawingPower,
            cf.availableLimit = :drawingPower - COALESCE(cf.currentOutstanding, 0),
            cf.updatedAt = CURRENT_TIMESTAMP 
        WHERE cf.id = :id
        """)
    int updateDrawingPower(@Param("id") UUID id, @Param("drawingPower") BigDecimal drawingPower);

    boolean existsByExternalReference(String externalReference);

    long countByCreditAgreementIdAndStatus(UUID agreementId, FacilityStatus status);

    @Query("SELECT DISTINCT cf.facilityType FROM CreditFacility cf WHERE cf.corporateId = :corporateId AND cf.status = 'ACTIVE'")
    List<FacilityType> findDistinctFacilityTypes(@Param("corporateId") UUID corporateId);
}