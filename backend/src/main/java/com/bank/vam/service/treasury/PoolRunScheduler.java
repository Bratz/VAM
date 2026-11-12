package com.bank.vam.service.treasury;

import com.bank.vam.dto.treasury.NotionalPoolDto;
import com.bank.vam.entity.treasury.NotionalPool;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.treasury.NotionalPoolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * After end of day, one run per ACTIVE pool for yesterday (spec R10, D5); on the 1st, last month's posting. Suspended and closed pools are skipped; a
 * missed date is caught up by the manual run. Like every scheduled job here it only runs when
 * {@code vam.scheduling.enabled} is on. Runs after midnight so yesterday's last balance refresh is its snapshot.
 * Also the catch-up over a long date range (building history after a re-seed, D10).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PoolRunScheduler {

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

    /**
     * Every ACTIVE pool run for {@code from}..{@code to} (default yesterday), in requests of at most
     * {@link PoolRunService#MAX_RANGE_DAYS} days, each its own transaction; dates already run come back unchanged. A
     * pool that fails is reported with what it had run and the rest go on.
     */
    public List<NotionalPoolDto.CatchUp> catchUp(LocalDate from, LocalDate to) {
        if (from == null) throw new BusinessException("from is required");
        if (to == null) to = LocalDate.now().minusDays(1);
        if (to.isBefore(from)) throw new BusinessException("to (" + to + ") is before from (" + from + ")");
        if (!to.isBefore(LocalDate.now())) {
            throw new BusinessException("Value dates must be before today: today's end-of-day balances are not final");
        }
        List<NotionalPoolDto.CatchUp> out = new ArrayList<>();
        for (NotionalPool pool : pools.findByStatus(NotionalPool.PoolStatus.ACTIVE)) {
            int n = 0;
            try {
                for (LocalDate start = from; !start.isAfter(to); start = start.plusDays(PoolRunService.MAX_RANGE_DAYS)) {
                    LocalDate end = start.plusDays(PoolRunService.MAX_RANGE_DAYS - 1);
                    n += runs.runRange(pool.getId(), start, end.isAfter(to) ? to : end).size();
                }
                out.add(new NotionalPoolDto.CatchUp(pool.getPoolReference(), n, null));
            } catch (Exception e) {
                log.error("Pool {} catch-up {}..{} failed after {} runs", pool.getPoolReference(), from, to, n, e);
                out.add(new NotionalPoolDto.CatchUp(pool.getPoolReference(), n, e.getMessage()));
            }
        }
        return out;
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
