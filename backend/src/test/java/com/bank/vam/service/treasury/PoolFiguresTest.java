package com.bank.vam.service.treasury;

import com.bank.vam.dto.treasury.NotionalPoolDto;
import com.bank.vam.entity.treasury.NotionalPool;
import com.bank.vam.entity.treasury.PoolInterestCalculation;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.treasury.NotionalPoolRepository;
import com.bank.vam.repository.treasury.PoolInterestCalculationRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Notional pooling phase 5 (R9, R15, D10): a pool's figures come from its runs, not stored counters — last run,
 * month-to-date pooled interest and benefit, year-to-date benefit, each pool in its own currency; the catch-up runs
 * every active pool over a long range in requests the run service accepts.
 */
class PoolFiguresTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 11, 11);

    @Test
    void figuresFromRuns_mtdIsTheMonthOfAsOf_ytdItsYear_lastRunIsTheLatest() {
        List<PoolInterestCalculation> runs = List.of(
                run(null, "2025-12-31", "900", "10.00", "1.00"),   // last year: in neither sum
                run(null, "2026-10-31", "1000", "20.00", "2.00"),  // this year, last month: YTD only
                run(null, "2026-11-01", "1100", "30.00", "3.00"),
                run(null, "2026-11-10", "1200", "-40.00", "4.00"), // the last run: a debit day
                run(null, "2026-11-12", "1300", "50.00", "5.00")); // after asOf (not possible today): ignored

        NotionalPoolDto.Figures f = PoolRunService.figures(runs, AS_OF);

        assertThat(f.asOf()).isEqualTo(AS_OF);
        assertThat(f.lastRunDate()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(f.lastRunNet()).isEqualByComparingTo("1200");
        assertThat(f.lastRunPooledInterest()).isEqualByComparingTo("-40.00");
        assertThat(f.mtdPooledInterest()).isEqualByComparingTo("-10.00");
        assertThat(f.mtdBenefit()).isEqualByComparingTo("7.00");
        assertThat(f.ytdBenefit()).isEqualByComparingTo("9.00");
    }

    @Test
    void onTheFirstOfAMonth_theMonthToDateIsTheMonthJustEnded() {
        NotionalPoolDto.Figures f = PoolRunService.figures(List.of(run(null, "2026-10-31", "1", "20.00", "2.00")),
                LocalDate.of(2026, 10, 31));

        assertThat(f.mtdBenefit()).isEqualByComparingTo("2.00");
    }

    @Test
    void aPoolWithoutRunsHasNoLastRunAndZeroSums() {
        NotionalPoolDto.Figures f = PoolRunService.figures(List.of(), AS_OF);

        assertThat(f.lastRunDate()).isNull();
        assertThat(f.lastRunNet()).isNull();
        assertThat(f.mtdBenefit()).isZero();
        assertThat(f.ytdBenefit()).isZero();
    }

    @Test
    void eachPoolKeepsItsOwnFigures_currenciesAreNeverAdded() {
        PoolInterestCalculationRepository repo = mock(PoolInterestCalculationRepository.class);
        NotionalPool gbp = pool("GBP"), usd = pool("USD"), none = pool("EUR");
        LocalDate d = PoolRunService.figuresAsOf();
        when(repo.findByPoolIdInAndStatusNotAndValueDateBetween(any(), eq(PoolInterestCalculation.CalculationStatus.SUPERSEDED), any(), any()))
                .thenReturn(List.of(run(gbp, d.toString(), "100", "1.00", "0.50"), run(usd, d.toString(), "200", "2.00", "0.70"),
                        run(gbp, d.toString(), "100", "1.00", "0.25")));
        PoolRunService service = new PoolRunService(mock(NotionalPoolRepository.class), repo, null, null);

        Map<UUID, NotionalPoolDto.Figures> f = service.figures(List.of(gbp.getId(), usd.getId(), none.getId()));

        assertThat(f.get(gbp.getId()).ytdBenefit()).isEqualByComparingTo("0.75");
        assertThat(f.get(usd.getId()).ytdBenefit()).isEqualByComparingTo("0.70");
        assertThat(f.get(none.getId()).lastRunDate()).isNull();
    }

    // ---- catch-up ----

    @Test
    void catchUpRunsEveryActivePoolInChunksTheRunServiceAccepts() {
        NotionalPoolRepository pools = mock(NotionalPoolRepository.class);
        PoolRunService runs = mock(PoolRunService.class);
        NotionalPool a = pool("AED"), b = pool("GBP");
        when(pools.findByStatus(NotionalPool.PoolStatus.ACTIVE)).thenReturn(List.of(a, b));
        when(runs.runRange(any(), any(), any())).thenReturn(List.of());
        when(runs.runRange(eq(b.getId()), any(), any())).thenThrow(new BusinessException("no config"));
        PoolRunScheduler scheduler = new PoolRunScheduler(pools, runs, mock(PoolPostingService.class));
        LocalDate to = LocalDate.now().minusDays(1), from = to.minusDays(199); // 200 days = 92 + 92 + 16

        List<NotionalPoolDto.CatchUp> out = scheduler.catchUp(from, to);

        verify(runs).runRange(a.getId(), from, from.plusDays(91));
        verify(runs).runRange(a.getId(), from.plusDays(92), from.plusDays(183));
        verify(runs).runRange(a.getId(), from.plusDays(184), to);
        verify(runs, times(3)).runRange(eq(a.getId()), any(), any());
        assertThat(out).extracting(NotionalPoolDto.CatchUp::poolReference).containsExactly(a.getPoolReference(), b.getPoolReference());
        assertThat(out.get(0).error()).isNull();
        assertThat(out.get(1).error()).isEqualTo("no config");
    }

    @Test
    void catchUpRefusesTodayAndAnInvertedRange() {
        PoolRunScheduler scheduler = new PoolRunScheduler(mock(NotionalPoolRepository.class), mock(PoolRunService.class),
                mock(PoolPostingService.class));
        LocalDate today = LocalDate.now();

        assertThatThrownBy(() -> scheduler.catchUp(today.minusDays(3), today)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> scheduler.catchUp(today.minusDays(1), today.minusDays(2))).isInstanceOf(BusinessException.class);
    }

    private static NotionalPool pool(String ccy) {
        NotionalPool p = new NotionalPool();
        p.setId(UUID.randomUUID());
        p.setPoolReference("NP-" + ccy);
        p.setPoolCurrency(ccy);
        return p;
    }

    private static PoolInterestCalculation run(NotionalPool pool, String date, String net, String pooled, String benefit) {
        PoolInterestCalculation r = new PoolInterestCalculation();
        r.setPool(pool);
        r.setValueDate(LocalDate.parse(date));
        r.setPoolNet(new BigDecimal(net));
        r.setPooledInterest(new BigDecimal(pooled));
        r.setBenefit(new BigDecimal(benefit));
        return r;
    }
}
