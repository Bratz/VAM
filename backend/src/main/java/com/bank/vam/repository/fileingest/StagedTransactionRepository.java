package com.bank.vam.repository.fileingest;

import com.bank.vam.entity.fileingest.RowStatus;
import com.bank.vam.entity.fileingest.StagedTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StagedTransactionRepository extends JpaRepository<StagedTransaction, UUID> {

    List<StagedTransaction> findByIngestJobId(UUID ingestJobId);

    List<StagedTransaction> findByIngestJobIdAndStatus(UUID ingestJobId, RowStatus status);

    List<StagedTransaction> findByIngestJobIdOrderBySourceRowNumberAsc(UUID ingestJobId);
}
