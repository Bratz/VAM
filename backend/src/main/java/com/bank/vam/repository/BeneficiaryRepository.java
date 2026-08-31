package com.bank.vam.repository;

import com.bank.vam.entity.Beneficiary;
import com.bank.vam.entity.Beneficiary.BeneficiaryStatus;
import com.bank.vam.entity.Beneficiary.ValidationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BeneficiaryRepository extends JpaRepository<Beneficiary, UUID> {
    
    Page<Beneficiary> findByCorporateId(UUID corporateId, Pageable pageable);
    
    Optional<Beneficiary> findByCorporateIdAndBeneficiaryName(UUID corporateId, String beneficiaryName);
    
    long countByStatus(BeneficiaryStatus status);
    
    @Query("SELECT COUNT(b) FROM Beneficiary b WHERE b.status = :status")
    long countByStatus(@Param("status") String status);
    
    long countByValidationStatus(ValidationStatus validationStatus);
    
    long countByCorporateId(UUID corporateId);
}