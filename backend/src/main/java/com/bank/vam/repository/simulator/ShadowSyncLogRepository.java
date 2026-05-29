package com.bank.vam.repository.simulator;

import com.bank.vam.entity.simulator.ShadowSyncLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ShadowSyncLogRepository
        extends JpaRepository<ShadowSyncLog, UUID> {

    /** Interface projection — Spring binds by aliased select names. */
    interface SourceQualityRow {
        UUID getPhysId();
        Double getMissRate();
        Double getAvgLagHours();
        Long getSamples();
    }

    /**
     * Trailing-window aggregate per physical account:
     *  miss_rate  = non-SUCCESS / total
     *  avg_lag_hr = mean(lag_minutes over SUCCESS) / 60
     */
    @Query("""
            SELECT s.physicalAccountId AS physId,
                   AVG(CASE WHEN s.syncStatus <> 'SUCCESS' THEN 1.0 ELSE 0.0 END) AS missRate,
                   COALESCE(AVG(CASE WHEN s.lagMinutes IS NOT NULL THEN s.lagMinutes END), 0) / 60.0 AS avgLagHours,
                   COUNT(s) AS samples
            FROM ShadowSyncLog s
            WHERE s.physicalAccountId IN :ids
              AND s.expectedSyncAt >= :since
            GROUP BY s.physicalAccountId
            """)
    List<SourceQualityRow> aggregate(
            @Param("ids") Collection<UUID> ids,
            @Param("since") LocalDateTime since);
}
