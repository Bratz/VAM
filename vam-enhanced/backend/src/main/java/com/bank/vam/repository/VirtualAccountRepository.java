package com.bank.vam.repository;

import com.bank.vam.entity.VirtualAccount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VirtualAccountRepository extends JpaRepository<VirtualAccount, UUID> {

    Optional<VirtualAccount> findByVaNumber(String vaNumber);

    Optional<VirtualAccount> findByViban(String viban);

    List<VirtualAccount> findByCorporateId(UUID corporateId);

    Page<VirtualAccount> findByCorporateId(UUID corporateId, Pageable pageable);

    List<VirtualAccount> findByPhysicalAccountId(UUID physicalAccountId);

    Page<VirtualAccount> findByProgramId(UUID programId, Pageable pageable);

    List<VirtualAccount> findByCorporateIdAndStatus(UUID corporateId, VirtualAccount.VaStatus status);

    boolean existsByVaNumber(String vaNumber);

    boolean existsByViban(String viban);

    @Query("SELECT COUNT(v) FROM VirtualAccount v WHERE v.programId = :programId")
    long countByProgramId(UUID programId);

    @Query("SELECT SUM(v.currentBalance) FROM VirtualAccount v WHERE v.physicalAccountId = :physicalAccountId AND v.status = 'ACTIVE'")
    BigDecimal sumBalanceByPhysicalAccountId(UUID physicalAccountId);

    @Query("SELECT v FROM VirtualAccount v WHERE " +
           "LOWER(v.vaNumber) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(v.vaName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(v.viban) LIKE LOWER(CONCAT('%', :search, '%'))")
    Page<VirtualAccount> search(String search, Pageable pageable);
}
