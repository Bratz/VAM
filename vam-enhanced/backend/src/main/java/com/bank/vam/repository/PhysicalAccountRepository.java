package com.bank.vam.repository;

import com.bank.vam.entity.PhysicalAccount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PhysicalAccountRepository extends JpaRepository<PhysicalAccount, UUID> {

    Optional<PhysicalAccount> findByAccountNumber(String accountNumber);

    Optional<PhysicalAccount> findByIban(String iban);

    List<PhysicalAccount> findByCorporateId(UUID corporateId);

    Page<PhysicalAccount> findByCorporateId(UUID corporateId, Pageable pageable);

    List<PhysicalAccount> findByCorporateIdAndStatus(UUID corporateId, PhysicalAccount.AccountStatus status);

    boolean existsByAccountNumber(String accountNumber);
}
