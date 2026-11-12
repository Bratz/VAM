package com.bank.vam.service.treasury;

import com.bank.vam.dto.treasury.NotionalPoolDto;
import com.bank.vam.entity.credit.InterestConfiguration;
import com.bank.vam.entity.treasury.*;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.credit.InterestConfigurationRepository;
import com.bank.vam.repository.treasury.NotionalPoolRepository;
import com.bank.vam.repository.treasury.PoolInterestCalculationRepository;
import com.bank.vam.repository.treasury.ShadowBalanceSnapshotRepository;
import com.bank.vam.service.credit.InterestConfigurationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Notional pooling phase 3 (spec R6, R8–R11, R13, acceptance 2, 3, 7): daily runs on end-of-day snapshots, pooled vs
 * standalone interest, pro-rata allocation with the residue to the header, one stored run per pool and date.
 */
class PoolRunServiceTest {

    private static final LocalDate D = LocalDate.now().minusDays(10);
    private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID(), C = UUID.randomUUID(), H = UUID.randomUUID();

    private final NotionalPoolRepository pools = mock(NotionalPoolRepository.class);
    private final PoolInterestCalculationRepository runs = mock(PoolInterestCalculationRepository.class);
    private final ShadowBalanceSnapshotRepository snapshots = mock(ShadowBalanceSnapshotRepository.class);
    private final InterestConfigurationRepository configs = mock(InterestConfigurationRepository.class);
    private final Map<LocalDate, PoolInterestCalculation> stored = new HashMap<>();
    private final List<ShadowBalanceSnapshot> snaps = new ArrayList<>();
    private final NotionalPool pool = new NotionalPool();
    private final InterestConfiguration config = new InterestConfiguration();
    private PoolRunService service;

    @BeforeEach
    void setUp() {
        service = new PoolRunService(pools, runs, snapshots, new InterestConfigurationService(configs));
        pool.setId(UUID.randomUUID());
        pool.setPoolReference("NP-0099");
        pool.setHeaderAccountId(A);
        pool.setInterestConfigurationId(UUID.randomUUID());
        config.setId(pool.getInterestConfigurationId());
        rates("3", "6");
        config.setDayCountConvention("ACT/365");
        when(configs.findById(config.getId())).thenReturn(Optional.of(config));
        when(pools.findByIdWithMembers(pool.getId())).thenReturn(Optional.of(pool));
        when(runs.findByPoolIdAndValueDateAndStatusNot(eqId(), any(), any())).thenAnswer(i -> Optional.ofNullable(stored.get(i.getArgument(1))));
        when(runs.save(any())).thenAnswer(i -> {
            PoolInterestCalculation r = i.getArgument(0);
            r.setId(UUID.randomUUID());
            stored.put(r.getValueDate(), r);
            return r;
        });
        when(snapshots.findFirstByShadowVaIdAndAsOfLessThanEqualOrderByAsOfDesc(any(), any())).thenAnswer(i -> snaps.stream()
                .filter(s -> s.getShadowVaId().equals(i.getArgument(0)) && !s.getAsOf().isAfter(i.getArgument(1)))
                .max(Comparator.comparing(ShadowBalanceSnapshot::getAsOf)));
    }

    @Test
    void acceptance3_pooledOnTheNet_standalonePerMember_benefit_allToTheCreditMember() {
        member(A, null);
        member(B, null);
        snap(A, D, "1000000");
        snap(B, D, "-400000");

        NotionalPoolDto.Run run = runOne();

        BigDecimal pooled = daily("600000", "3");
        BigDecimal standalone = daily("1000000", "3").subtract(daily("400000", "6"));
        assertThat(run.poolNet()).isEqualByComparingTo("600000");
        assertThat(run.pooledInterest()).isEqualByComparingTo(pooled).isEqualByComparingTo("49.32");
        assertThat(run.standaloneInterest()).isEqualByComparingTo(standalone).isEqualByComparingTo("16.44");
        assertThat(run.benefit()).isEqualByComparingTo(pooled.subtract(standalone)).isEqualByComparingTo("32.88");
        assertThat(line(run, A).allocatedInterest()).isEqualByComparingTo("49.32");
        assertThat(line(run, B).allocatedInterest()).isEqualByComparingTo("0");
        assertThat(line(run, B).standaloneInterest()).isEqualByComparingTo("-65.75");
        assertThat(run.interestRate()).isEqualByComparingTo("3");
        assertThat(run.creditRate()).isEqualByComparingTo("3");
        assertThat(run.debitRate()).isEqualByComparingTo("6");
        assertThat(run.dayCount()).isEqualTo("ACT/365");
        assertThat(run.status()).isEqualTo("CALCULATED");
    }

    /** Live, POOL-HEL-USD showed a benefit of −0.01: three members in credit at one rate, standalone summed rounded. */
    @Test
    void allMembersInCreditAtOneRate_noBenefit_notARoundingCent() {
        rates("5.1", "7.1");
        config.setDayCountConvention("ACT/360");
        member(A, null);
        member(B, null);
        member(C, null);
        snap(A, D, "15280000");
        snap(B, D, "6045000");
        snap(C, D, "4012000");

        NotionalPoolDto.Run run = runOne();

        assertThat(run.pooledInterest()).isEqualByComparingTo("3589.41");
        assertThat(run.benefit()).isEqualByComparingTo("0");
    }

    @Test
    void negativeNet_debitInterestSharedByTheDebitMembersProRata() {
        member(A, null);
        member(B, null);
        member(C, null);
        snap(A, D, "100000");
        snap(B, D, "-300000");
        snap(C, D, "-200000");

        NotionalPoolDto.Run run = runOne();

        assertThat(run.pooledInterest()).isEqualByComparingTo("-65.75"); // 400,000 × 6% / 365
        assertThat(run.interestRate()).isEqualByComparingTo("6");
        assertThat(line(run, B).allocatedInterest()).isEqualByComparingTo("-39.45");
        assertThat(line(run, C).allocatedInterest()).isEqualByComparingTo("-26.30");
        assertThat(line(run, A).allocatedInterest()).isEqualByComparingTo("0");
    }

    @Test
    void residueToTheHeader_evenWithNoShareOfItsOwn() {
        rates("3.1", "6");
        pool.setHeaderAccountId(H);
        for (UUID id : List.of(A, B, C, H)) member(id, null);
        snap(A, D, "100000");
        snap(B, D, "100000");
        snap(C, D, "100000");
        snap(H, D, "0");

        NotionalPoolDto.Run run = runOne();

        assertThat(run.pooledInterest()).isEqualByComparingTo("25.48"); // 300,000 × 3.1% / 365 = 25.4795
        assertThat(line(run, A).allocatedInterest()).isEqualByComparingTo("8.49");
        assertThat(run.residue()).isEqualByComparingTo("0.01");
        assertThat(line(run, H).allocatedInterest()).isEqualByComparingTo("0.01");
        assertThat(run.lines().stream().map(NotionalPoolDto.RunLine::allocatedInterest).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(run.pooledInterest());
    }

    @Test
    void missingSnapshot_earlierOneUsedOrZero_flagged() {
        member(A, null);
        member(B, null);
        snap(A, D.minusDays(3), "500000");
        snap(A, D.plusDays(1), "999999"); // after the value date: never used

        NotionalPoolDto.Run run = runOne();

        assertThat(line(run, A).balance()).isEqualByComparingTo("500000");
        assertThat(line(run, A).balanceAsOf()).isEqualTo(D.minusDays(3));
        assertThat(line(run, A).snapshotMissing()).isTrue();
        assertThat(line(run, B).balance()).isEqualByComparingTo("0");
        assertThat(line(run, B).balanceAsOf()).isNull();
        assertThat(line(run, B).snapshotMissing()).isTrue();
        assertThat(run.snapshotsMissing()).isEqualTo(2);
    }

    @Test
    void rerun_returnsTheStoredRunUnchanged_evenAfterARateEdit() {
        member(A, null);
        snap(A, D, "1000000");
        NotionalPoolDto.Run first = runOne();

        rates("5", "8"); // configs are edited in place
        snap(B, D, "1"); // irrelevant data change
        NotionalPoolDto.Run again = service.runRange(pool.getId(), D.minusDays(1), D).get(1);

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.pooledInterest()).isEqualByComparingTo(first.pooledInterest()).isEqualByComparingTo("82.19");
        assertThat(again.creditRate()).isEqualByComparingTo("3");
        verify(runs, times(2)).save(any()); // D once, D-1 once
    }

    @Test
    void onlyMembersInThePoolOnTheDate() {
        member(A, null);
        member(B, null).setJoinedDate(D.plusDays(1));
        member(C, null).setLeftDate(D);
        member(H, null).setLeftDate(D.plusDays(1));
        for (UUID id : List.of(A, B, C, H)) snap(id, D, "1000");

        NotionalPoolDto.Run run = runOne();

        assertThat(run.lines()).extracting(NotionalPoolDto.RunLine::accountId).containsExactlyInAnyOrder(A, H);
        assertThat(run.poolNet()).isEqualByComparingTo("2000");
    }

    @Test
    void dateWithNoMember_hasNoRun() {
        member(A, null).setJoinedDate(D);
        snap(A, D, "1000");

        assertThat(service.runRange(pool.getId(), D.minusDays(2), D)).extracting(NotionalPoolDto.Run::valueDate).containsExactly(D);
    }

    @Test
    void breaches_memberDebitLimitAndPoolFloor() {
        pool.setPoolFloor(new BigDecimal("100000"));
        member(A, new BigDecimal("500000"));
        member(B, new BigDecimal("300000"));
        snap(A, D, "100000");
        snap(B, D, "-400000");

        NotionalPoolDto.Run run = runOne();

        assertThat(line(run, B).debitLimitBreach()).isTrue();
        assertThat(line(run, A).debitLimitBreach()).isFalse();
        assertThat(run.limitBreaches()).isEqualTo(1);
        assertThat(run.floorBreach()).isTrue(); // net −300,000 < −100,000
    }

    @Test
    void refusesTodayAndOverlongRanges() {
        assertThatThrownBy(() -> service.runRange(pool.getId(), LocalDate.now(), null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.runRange(pool.getId(), D.minusDays(100), D)).isInstanceOf(BusinessException.class);
        verify(runs, never()).save(any());
    }

    // ---- helpers ----

    private NotionalPoolDto.Run runOne() {
        List<NotionalPoolDto.Run> out = service.runRange(pool.getId(), D, D);
        assertThat(out).hasSize(1);
        return out.get(0);
    }

    private void rates(String credit, String debit) {
        config.setCreditBaseRate(new BigDecimal(credit));
        config.setDebitBaseRate(new BigDecimal(debit));
        config.calculateEffectiveRates();
    }

    private PoolMember member(UUID accountId, BigDecimal debitLimit) {
        PoolMember m = new PoolMember();
        m.setPool(pool);
        m.setAccountId(accountId);
        m.setAccountNumber("ACC-" + accountId.toString().substring(0, 4));
        m.setDebitLimit(debitLimit);
        m.setJoinedDate(D.minusYears(1));
        pool.getMembers().add(m);
        return m;
    }

    private void snap(UUID accountId, LocalDate asOf, String balance) {
        snaps.add(ShadowBalanceSnapshot.builder().shadowVaId(accountId).asOf(asOf).bankBalance(new BigDecimal(balance)).build());
    }

    private static NotionalPoolDto.RunLine line(NotionalPoolDto.Run run, UUID accountId) {
        return run.lines().stream().filter(l -> l.accountId().equals(accountId)).findFirst().orElseThrow();
    }

    /** balance × rate% / 365, to the cent. */
    private static BigDecimal daily(String balance, String rate) {
        return new BigDecimal(balance).multiply(new BigDecimal(rate))
                .divide(new BigDecimal("36500"), 2, RoundingMode.HALF_UP);
    }

    private UUID eqId() {
        return org.mockito.ArgumentMatchers.eq(pool.getId());
    }
}
