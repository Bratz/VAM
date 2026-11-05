package com.bank.vam.fx.deal;

import com.bank.vam.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.bank.vam.fx.deal.DealFixtures.CORP;
import static com.bank.vam.fx.deal.DealFixtures.bd;
import static com.bank.vam.fx.deal.DealFixtures.deal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FxSettlementServiceTest {

    private static final LocalDate DUE = LocalDate.of(2027, 2, 9);

    private final FxDealRepository deals = mock(FxDealRepository.class);
    private final FxSettlementRepository settlements = mock(FxSettlementRepository.class);
    private final FxDealService dealService = mock(FxDealService.class);
    private final HeldCash cash = mock(HeldCash.class);
    private final FxEvents log = mock(FxEvents.class);
    private final FxSettlementService service = new FxSettlementService(deals, settlements, dealService, cash, log);

    private final List<FxSettlement> saved = new ArrayList<>();

    @BeforeEach
    void wiring() {
        // The real service moves the status; the mock must do the same or completion logic cannot be tested.
        doAnswer(inv -> {
            FxDeal d = inv.getArgument(0);
            d.setStatus(inv.getArgument(1));
            return null;
        }).when(dealService).transition(any(), any(), anyString());
        when(settlements.save(any(FxSettlement.class))).thenAnswer(inv -> {
            FxSettlement s = inv.getArgument(0);
            // Identity, not equals(): the entity's equals is id-based, so unsaved rows would all look the same.
            if (saved.stream().noneMatch(x -> x == s)) {
                saved.add(s);
            }
            return s;
        });
        when(settlements.findByDealIdOrderByCreatedAtAsc(any())).thenAnswer(inv -> saved);
        when(settlements.findByDealIdAndKindAndStatus(any(), eq(SettlementKind.DRAWDOWN), eq(SettlementStatus.PENDING)))
                .thenAnswer(inv -> saved.stream().filter(s -> s.getKind() == SettlementKind.DRAWDOWN
                        && s.getStatus() == SettlementStatus.PENDING).toList());
        when(settlements.existsByDealIdAndStatus(any(), eq(SettlementStatus.PENDING)))
                .thenAnswer(inv -> saved.stream().anyMatch(s -> s.getStatus() == SettlementStatus.PENDING));
    }

    private FxDeal due(DealStatus status) {
        FxDeal d = deal(Side.BUY, Instrument.FORWARD, status, DUE);
        when(deals.findByStatusIn(any())).thenReturn(List.of(d));
        return d;
    }

    @Test
    void aConfirmedDealSettlesOnItsValueDateWhenTheCashIsThere() {
        FxDeal d = due(DealStatus.ACTIVE);
        when(cash.of(CORP, "USD")).thenReturn(bd("200000"));

        FxSettlementService.RunResult r = service.run(DUE, null);

        assertThat(r.settled()).isEqualTo(1);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getStatus()).isEqualTo(SettlementStatus.SETTLED);
        assertThat(saved.get(0).getSellAmount()).isEqualByComparingTo("110000");
        assertThat(d.getStatus()).isEqualTo(DealStatus.SETTLED);
        assertThat(d.remainingBase()).isEqualByComparingTo("0");
        assertThat(d.getSettledAt()).isNotNull();
    }

    @Test
    void aDealNotYetDueIsLeftAlone() {
        FxDeal d = due(DealStatus.ACTIVE);

        FxSettlementService.RunResult r = service.run(DUE.minusDays(1), null);

        assertThat(r.settled() + r.failed() + r.blocked()).isZero();
        assertThat(d.getStatus()).isEqualTo(DealStatus.ACTIVE);
    }

    @Test
    void anUnconfirmedOrDisputedDealIsBlockedAndNothingIsRecorded() {
        FxDeal d = due(DealStatus.ACTIVE);
        d.setConfirmationStatus(ConfirmationStatus.ISSUED);

        FxSettlementService.RunResult issued = service.run(DUE, null);
        d.setConfirmationStatus(ConfirmationStatus.DISPUTED);
        FxSettlementService.RunResult disputed = service.run(DUE, null);

        assertThat(issued.blocked()).isEqualTo(1);
        assertThat(issued.notes().get(0)).contains("waiting for the client to confirm");
        assertThat(disputed.blocked()).isEqualTo(1);
        assertThat(saved).isEmpty();
        assertThat(d.getStatus()).isEqualTo(DealStatus.ACTIVE);
        verify(cash, never()).of(any(), anyString());
    }

    @Test
    void aShortfallFailsTheDealAndTheNextRunSettlesItOnceFunded() {
        FxDeal d = due(DealStatus.ACTIVE);
        when(cash.of(CORP, "USD")).thenReturn(bd("50000"));

        FxSettlementService.RunResult first = service.run(DUE, null);

        assertThat(first.failed()).isEqualTo(1);
        assertThat(first.notes().get(0)).contains("Not enough USD").contains("needs 110000.00").contains("holds 50000");
        assertThat(d.getStatus()).isEqualTo(DealStatus.SETTLEMENT_FAILED);
        assertThat(saved).extracting(FxSettlement::getStatus).containsExactly(SettlementStatus.FAILED);

        // Still short: it stays failed and does not pile up another failure row.
        service.run(DUE, null);
        assertThat(saved).hasSize(1);

        when(cash.of(CORP, "USD")).thenReturn(bd("500000"));
        FxSettlementService.RunResult retry = service.run(DUE, null);

        assertThat(retry.settled()).isEqualTo(1);
        assertThat(d.getStatus()).isEqualTo(DealStatus.SETTLED);
        assertThat(saved).extracting(FxSettlement::getStatus).containsExactly(SettlementStatus.FAILED, SettlementStatus.SETTLED);
    }

    @Test
    void twoDealsInOneRunShareTheCashSoTheSecondCannotSpendItAgain() {
        FxDeal first = deal(Side.BUY, Instrument.FORWARD, DealStatus.ACTIVE, DUE);
        FxDeal second = deal(Side.BUY, Instrument.FORWARD, DealStatus.ACTIVE, DUE);
        second.setCreatedAt(first.getCreatedAt().plusSeconds(1));
        when(deals.findByStatusIn(any())).thenReturn(List.of(first, second));
        when(cash.of(CORP, "USD")).thenReturn(bd("150000"));

        FxSettlementService.RunResult r = service.run(DUE, null);

        assertThat(r.settled()).isEqualTo(1);
        assertThat(r.failed()).isEqualTo(1);
        assertThat(first.getStatus()).isEqualTo(DealStatus.SETTLED);
        assertThat(second.getStatus()).isEqualTo(DealStatus.SETTLEMENT_FAILED);
    }

    @Test
    void aWindowAtItsLastDateSettlesOnlyWhatWasNotDrawn() {
        FxDeal d = deal(Side.BUY, Instrument.WINDOW, DealStatus.PARTIALLY_DRAWN, DUE);
        d.setDrawnAmount(bd("40000"));
        when(deals.findByStatusIn(any())).thenReturn(List.of(d));
        when(cash.of(CORP, "USD")).thenReturn(bd("1000000"));

        service.run(DUE, null);

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getKind()).isEqualTo(SettlementKind.MATURITY);
        assertThat(saved.get(0).getBuyAmount()).isEqualByComparingTo("60000");
        assertThat(saved.get(0).getSellAmount()).isEqualByComparingTo("66000");
        assertThat(d.getStatus()).isEqualTo(DealStatus.SETTLED);
    }

    // ---- drawdowns ----

    private FxDeal window() {
        FxDeal d = deal(Side.BUY, Instrument.WINDOW, DealStatus.ACTIVE, LocalDate.now().plusDays(60));
        d.setWindowStart(LocalDate.now().plusDays(10));
        when(deals.findById(d.getId())).thenReturn(Optional.of(d));
        return d;
    }

    private static LocalDate weekday(LocalDate from) {
        LocalDate d = from;
        while (d.getDayOfWeek().getValue() > 5) {
            d = d.plusDays(1);
        }
        return d;
    }

    @Test
    void aDrawdownIsScheduledProportionallyAndMovesTheDealToPartiallyDrawn() {
        FxDeal d = window();

        FxSettlement s = service.drawdown(d.getId(), bd("40000"), weekday(LocalDate.now().plusDays(20)));

        assertThat(s.getKind()).isEqualTo(SettlementKind.DRAWDOWN);
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(s.getBuyAmount()).isEqualByComparingTo("40000.00");
        assertThat(s.getSellAmount()).isEqualByComparingTo("44000.00");
        assertThat(d.getDrawnAmount()).isEqualByComparingTo("40000");
        assertThat(d.getStatus()).isEqualTo(DealStatus.PARTIALLY_DRAWN);
    }

    @Test
    void theLastDrawdownTakesExactlyWhatIsLeftOfEachLegSoNoRoundingIsLost() {
        FxDeal d = window();
        d.setBuyAmount(bd("100.00"));
        d.setSellAmount(bd("110.00"));
        LocalDate day = weekday(LocalDate.now().plusDays(20));

        service.drawdown(d.getId(), bd("33.33"), day);
        service.drawdown(d.getId(), bd("33.33"), day);
        FxSettlement last = service.drawdown(d.getId(), bd("33.34"), day);

        BigDecimal buy = saved.stream().map(FxSettlement::getBuyAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sell = saved.stream().map(FxSettlement::getSellAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(buy).isEqualByComparingTo("100.00");
        assertThat(sell).isEqualByComparingTo("110.00");
        assertThat(last.getBuyAmount()).isEqualByComparingTo(bd("100.00").subtract(bd("33.33").multiply(bd("2"))));
    }

    @Test
    void aDrawdownIsRefusedUnlessItIsAWindowConfirmedInsideTheWindowAndWithinWhatIsLeft() {
        FxDeal d = window();
        LocalDate ok = weekday(LocalDate.now().plusDays(20));

        assertThatThrownBy(() -> service.drawdown(d.getId(), bd("150000"), ok)).hasMessageContaining("between 0.01 and 100000");
        assertThatThrownBy(() -> service.drawdown(d.getId(), bd("1000"), LocalDate.now().plusDays(3))).hasMessageContaining("inside the window");
        assertThatThrownBy(() -> service.drawdown(d.getId(), bd("1000"), LocalDate.now().plusDays(90))).hasMessageContaining("inside the window");

        d.setConfirmationStatus(ConfirmationStatus.ISSUED);
        assertThatThrownBy(() -> service.drawdown(d.getId(), bd("1000"), ok)).hasMessageContaining("Confirm the deal");

        FxDeal forward = deal(Side.BUY, Instrument.FORWARD, DealStatus.ACTIVE, LocalDate.now().plusDays(60));
        when(deals.findById(forward.getId())).thenReturn(Optional.of(forward));
        assertThatThrownBy(() -> service.drawdown(forward.getId(), bd("1000"), ok))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Only a window forward");

        ArgumentCaptor<FxSettlement> none = ArgumentCaptor.forClass(FxSettlement.class);
        verify(settlements, never()).save(none.capture());
    }
}
