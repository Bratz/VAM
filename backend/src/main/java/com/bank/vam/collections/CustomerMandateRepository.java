package com.bank.vam.collections;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CustomerMandateRepository extends JpaRepository<CustomerMandate, UUID> {

    List<CustomerMandate> findByCorporateIdOrderByCreatedAtDesc(UUID corporateId);

    List<CustomerMandate> findByCorporateIdAndCustomerPartyIdIn(UUID corporateId, Collection<UUID> partyIds);

    boolean existsByCorporateIdAndMandateReferenceIgnoreCase(UUID corporateId, String mandateReference);

    java.util.Optional<CustomerMandate> findByRequestId(String requestId);

    List<CustomerMandate> findByMandateReferenceIgnoreCase(String mandateReference);
}
