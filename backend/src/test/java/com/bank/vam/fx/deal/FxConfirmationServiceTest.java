package com.bank.vam.fx.deal;

import com.bank.vam.config.FxDealProperties;
import com.bank.vam.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static com.bank.vam.fx.deal.DealFixtures.deal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FxConfirmationServiceTest {

    private final FxDealRepository deals = mock(FxDealRepository.class);
    private final FxDealService dealService = mock(FxDealService.class);
    private final FxEvents log = mock(FxEvents.class);
    private final FxConfirmationService service = new FxConfirmationService(deals, dealService, log, new FxDealProperties());

    private FxDeal issued() {
        FxDeal d = deal(Side.BUY, Instrument.FORWARD, DealStatus.ACTIVE, LocalDate.of(2027, 2, 9));
        d.setConfirmationStatus(ConfirmationStatus.ISSUED);
        d.setConfirmationIssuedAt(LocalDateTime.now());
        when(deals.findById(d.getId())).thenReturn(Optional.of(d));
        return d;
    }

    @Test
    void affirmingRecordsWhoAndWhenAndTwiceIsHarmless() {
        FxDeal d = issued();

        service.affirm(d.getId());
        LocalDateTime first = d.getConfirmedAt();
        service.affirm(d.getId());

        assertThat(d.getConfirmationStatus()).isEqualTo(ConfirmationStatus.AFFIRMED);
        assertThat(first).isNotNull();
        assertThat(d.getConfirmedAt()).isEqualTo(first);
        assertThat(d.getConfirmedBy()).isNotBlank();
        verify(log).note(any(), eq("CONFIRMED_BY_CLIENT"), anyString());
    }

    @Test
    void aDisputedConfirmationCannotBeAffirmedUntilTheBankReissuesIt() {
        FxDeal d = issued();
        service.dispute(d.getId(), "Rate is 1.10 but we agreed 1.0950");

        assertThat(d.getConfirmationStatus()).isEqualTo(ConfirmationStatus.DISPUTED);
        assertThat(d.getDisputeReason()).contains("1.0950");
        assertThatThrownBy(() -> service.affirm(d.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("DISPUTED");
    }

    @Test
    void aDisputeNeedsAReasonAndAnIssuedConfirmation() {
        FxDeal d = issued();

        assertThatThrownBy(() -> service.dispute(d.getId(), "  ")).isInstanceOf(BusinessException.class).hasMessageContaining("what does not match");
        assertThatThrownBy(() -> service.dispute(d.getId(), "x".repeat(301))).isInstanceOf(BusinessException.class).hasMessageContaining("300");

        service.affirm(d.getId());
        assertThatThrownBy(() -> service.dispute(d.getId(), "too late")).isInstanceOf(BusinessException.class).hasMessageContaining("AFFIRMED");
    }

    @Test
    void theBankReissuesAfterADisputeAndTheClientCanThenAffirm() {
        FxDeal d = issued();
        service.dispute(d.getId(), "wrong value date");

        service.resolve(d.getId(), FxConfirmationService.Resolution.REISSUE, "Value date corrected");

        assertThat(d.getConfirmationStatus()).isEqualTo(ConfirmationStatus.ISSUED);
        assertThat(d.getDisputeReason()).isNull();
        verify(log).note(any(), eq("REISSUED_BY_BANK"), anyString());
        service.affirm(d.getId());
        assertThat(d.getConfirmationStatus()).isEqualTo(ConfirmationStatus.AFFIRMED);
    }

    @Test
    void theBankCanCancelADisputedDeal() {
        FxDeal d = issued();
        service.dispute(d.getId(), "we never did this trade");

        service.resolve(d.getId(), FxConfirmationService.Resolution.CANCEL, "Booked in error");

        verify(dealService).transition(eq(d), eq(DealStatus.CANCELLED), anyString());
    }

    @Test
    void onlyADisputedConfirmationCanBeResolved() {
        FxDeal d = issued();

        assertThatThrownBy(() -> service.resolve(d.getId(), FxConfirmationService.Resolution.CANCEL, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ISSUED");
        verify(dealService, never()).transition(any(), any(), anyString());
    }

    @Test
    void anUnansweredConfirmationIsOverdueAfterTheAllowedHours() {
        FxDeal d = issued();
        assertThat(service.overdue(d)).isFalse();

        d.setConfirmationIssuedAt(LocalDateTime.now().minusHours(25));
        assertThat(service.overdue(d)).isTrue();

        service.affirm(d.getId());
        assertThat(service.overdue(d)).isFalse();
    }
}
