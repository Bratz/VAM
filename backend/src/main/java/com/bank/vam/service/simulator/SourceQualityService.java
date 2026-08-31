package com.bank.vam.service.simulator;

import com.bank.vam.dto.simulator.SourceQualityDto;
import com.bank.vam.repository.simulator.ShadowSyncLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Aggregates trailing-90d source-quality (miss_rate / avg_lag_hours) from
 * {@code shadow_sync_log} for the Phase-4 Source-quality score line.
 * Read-only reference data; no live-table writes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceQualityService {

    private static final int WINDOW_DAYS = 90;

    private final ShadowSyncLogRepository syncLogRepository;

    @Transactional(readOnly = true)
    public List<SourceQualityDto> getSourceQuality(Collection<java.util.UUID> physicalAccountIds) {
        if (physicalAccountIds == null || physicalAccountIds.isEmpty()) {
            return List.of();
        }
        LocalDateTime since = LocalDateTime.now().minusDays(WINDOW_DAYS);
        return syncLogRepository.aggregate(physicalAccountIds, since).stream()
                .map(r -> SourceQualityDto.builder()
                        .physicalAccountId(r.getPhysId())
                        .missRate(r.getMissRate() != null ? r.getMissRate() : 0.0)
                        .avgLagHours(r.getAvgLagHours() != null ? r.getAvgLagHours() : 0.0)
                        .samples(r.getSamples() != null ? r.getSamples() : 0L)
                        .build())
                .toList();
    }
}
