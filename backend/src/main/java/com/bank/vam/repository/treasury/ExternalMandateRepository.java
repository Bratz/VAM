package com.bank.vam.repository.treasury;

import com.bank.vam.entity.treasury.ExternalMandate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ExternalMandateRepository extends JpaRepository<ExternalMandate, UUID> {

    Optional<ExternalMandate> findByMandateReference(String mandateReference);

    List<ExternalMandate> findByShadowVaIdOrderByCreatedAtDesc(UUID shadowVaId);

    @Query("SELECT m FROM ExternalMandate m WHERE m.shadowVaId IN "
            + "(SELECT v.id FROM VirtualAccount v WHERE v.corporateId = :corporateId) ORDER BY m.createdAt DESC")
    List<ExternalMandate> findByCorporateId(@Param("corporateId") UUID corporateId);
}
