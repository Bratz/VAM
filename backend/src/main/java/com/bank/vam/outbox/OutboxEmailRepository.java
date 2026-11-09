package com.bank.vam.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OutboxEmailRepository extends JpaRepository<OutboxEmail, UUID> {

    List<OutboxEmail> findTop200ByCorporateIdOrderByCreatedAtDesc(UUID corporateId);

    List<OutboxEmail> findByRelatedIdOrderByCreatedAtDesc(UUID relatedId);
}
