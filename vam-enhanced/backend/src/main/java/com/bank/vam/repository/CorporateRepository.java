package com.bank.vam.repository;

import com.bank.vam.entity.Corporate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CorporateRepository extends JpaRepository<Corporate, UUID> {

    Optional<Corporate> findByCorporateId(String corporateId);

    boolean existsByCorporateId(String corporateId);

    Page<Corporate> findByStatus(Corporate.CorporateStatus status, Pageable pageable);

    @Query("SELECT c FROM Corporate c WHERE " +
           "LOWER(c.legalName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(c.tradeName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(c.corporateId) LIKE LOWER(CONCAT('%', :search, '%'))")
    Page<Corporate> search(String search, Pageable pageable);
}
