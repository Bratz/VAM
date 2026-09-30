package com.bank.vam.repository.fileingest;

import com.bank.vam.entity.fileingest.RowStatus;
import com.bank.vam.entity.fileingest.StagedTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface StagedTransactionRepository extends JpaRepository<StagedTransaction, UUID> {

    List<StagedTransaction> findByIngestJobId(UUID ingestJobId);

    List<StagedTransaction> findByIngestJobIdAndStatus(UUID ingestJobId, RowStatus status);

    List<StagedTransaction> findByIngestJobIdOrderBySourceRowNumberAsc(UUID ingestJobId);

    Page<StagedTransaction> findByIngestJobIdOrderBySourceRowNumberAsc(UUID ingestJobId, Pageable pageable);

    /**
     * Row counts per status for several jobs at once. The uploads list shows up to 100 jobs and
     * needs counts for each, so this is one grouped query rather than 100 round trips.
     * Returns {@code [ingestJobId, status, count]} triples; jobs with no rows are simply absent.
     */
    @Query("SELECT s.ingestJobId, s.status, COUNT(s) FROM StagedTransaction s "
            + "WHERE s.ingestJobId IN :jobIds GROUP BY s.ingestJobId, s.status")
    List<Object[]> countByStatusForJobs(@Param("jobIds") Collection<UUID> jobIds);
}
