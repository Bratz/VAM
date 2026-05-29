package com.bank.vam.repository.treasury;

import com.bank.vam.entity.treasury.ExternalMandate;
import com.bank.vam.entity.treasury.ExternalMandate.MandateStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ExternalMandateRepository extends JpaRepository<ExternalMandate, UUID> {

    Optional<ExternalMandate> findByMandateReference(String mandateReference);

    List<ExternalMandate> findByShadowVaId(UUID shadowVaId);

    List<ExternalMandate> findByShadowVaIdAndStatus(UUID shadowVaId, MandateStatus status);
}
