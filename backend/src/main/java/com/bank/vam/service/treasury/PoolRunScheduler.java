package com.bank.vam.service.treasury;

import com.bank.vam.entity.treasury.NotionalPool;
import com.bank.vam.repository.treasury.NotionalPoolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * After end of day, one run per ACTIVE pool for yesterday (spec R10, D5); on the 1st, last month's posting. Suspended and closed pools are skipped; a
 * missed date is caught up by the manual run. Like every scheduled job here it only runs when
 * {@code vam.scheduling.enabled} is on. Runs after midnight so yesterday's last balance refresh is its snapshot.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class PoolRunScheduler {

    private final NotionalPoolRepository pools;
    private final PoolRunService runs;
    private final PoolPostingService postings;

    @Scheduled(cron = "${vam.pooling.run-cron:0 45 0 * * *}")
    void runYesterday() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        for (NotionalPool pool : pools.findByStatus(NotionalPool.PoolStatus.ACTIVE)) {
            try {
                runs.runRange(pool.getId(), yesterday, yesterday);
            } catch (Exception e) {
                log.error("Pool {} run for {} failed", pool.getPoolReference(), yesterday, e);
            }
        }
    }

    /** On the 1st, after the last day's run: the previous month posted for ACTIVE and SUSPENDED pools (D11, R11a). */
    @Scheduled(cron = "${vam.pooling.post-cron:0 15 1 1 * *}")
    void postLastMonth() {
        YearMonth month = YearMonth.now().minusMonths(1);
        for (NotionalPool.PoolStatus status : List.of(NotionalPool.PoolStatus.ACTIVE, NotionalPool.PoolStatus.SUSPENDED)) {
            for (NotionalPool pool : pools.findByStatus(status)) {
                try {
                    postings.postMonth(pool.getId(), month);
                } catch (Exception e) {
                    log.error("Pool {} posting for {} failed", pool.getPoolReference(), month, e);
                }
            }
        }
    }
}
