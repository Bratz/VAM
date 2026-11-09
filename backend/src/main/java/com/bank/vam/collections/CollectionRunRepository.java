package com.bank.vam.collections;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CollectionRunRepository extends JpaRepository<CollectionRun, UUID> {

    List<CollectionRun> findByCorporateIdOrderByCreatedAtDesc(UUID corporateId);
}
