package com.bank.vam.repository;

import com.bank.vam.entity.Beneficiary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BeneficiaryRepository extends JpaRepository<Beneficiary, UUID> {

    List<Beneficiary> findByCorporateId(UUID corporateId);

    Page<Beneficiary> findByCorporateId(UUID corporateId, Pageable pageable);

    List<Beneficiary> findByCorporateIdAndStatus(UUID corporateId, Beneficiary.BeneficiaryStatus status);

    Optional<Beneficiary> findByCorporateIdAndAccountNumber(UUID corporateId, String accountNumber);

    Optional<Beneficiary> findByCorporateIdAndIban(UUID corporateId, String iban);

    boolean existsByCorporateIdAndAccountNumber(UUID corporateId, String accountNumber);
}
