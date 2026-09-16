package com.bank.vam.repository.fileingest;

import com.bank.vam.entity.fileingest.ReconciliationResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReconciliationResultRepository extends JpaRepository<ReconciliationResult, UUID> {

    Optional<ReconciliationResult> findByIngestJobId(UUID ingestJobId);
}
