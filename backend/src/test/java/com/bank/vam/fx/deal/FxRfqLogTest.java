package com.bank.vam.fx.deal;

import com.bank.vam.config.FxDealProperties;
import com.bank.vam.fx.ForwardPricer;
import com.bank.vam.fx.deal.FxDealModels.RfqSummary;
import com.bank.vam.repository.CorporateRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FxRfqLogTest {

    private static final UUID CORP = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 5, 12, 0, 0);

    private static FxQuoteRequest quote(QuoteStatus status, boolean priced, long secondsToAccept) {
        FxQuoteRequest q = new FxQuoteRequest();
        q.setId(UUID.randomUUID());
        q.setCorporateId(CORP);
        q.setPair("EUR/USD");
        q.setStatus(status);
        if (priced) {
            q.setClientRate(new BigDecimal("1.10"));
            q.setQuotedAt(NOW);
            q.setValidUntil(NOW.plusSeconds(30));
        }
        if (status == QuoteStatus.ACCEPTED) {
            q.setAcceptedAt(NOW.plusSeconds(secondsToAccept));
        }
        return q;
    }

    @Test
    void theSummaryCountsEachOutcomeAndMeasuresTheHitRateOnlyOverSettledOnes() {
        RfqSummary s = RfqSummary.of(List.of(
                quote(QuoteStatus.ACCEPTED, true, 12), quote(QuoteStatus.ACCEPTED, true, 8),
                quote(QuoteStatus.EXPIRED, true, 0), quote(QuoteStatus.REJECTED, false, 0),
                quote(QuoteStatus.CANCELLED, true, 0), quote(QuoteStatus.QUOTED, true, 0)));

        assertThat(s.requests()).isEqualTo(6);
        assertThat(s.priced()).isEqualTo(5);
        assertThat(s.accepted()).isEqualTo(2);
        assertThat(s.expired()).isEqualTo(1);
        assertThat(s.declined()).isEqualTo(1);
        assertThat(s.withdrawn()).isEqualTo(1);
        assertThat(s.open()).isEqualTo(1);
        // 2 accepted of 4 settled (accepted, expired, withdrawn); the open price and the decline are not counted against it.
        assertThat(s.hitRate()).isEqualByComparingTo("0.5");
        assertThat(s.avgSecondsToAccept()).isEqualTo(10L);
    }

    @Test
    void withNothingSettledThereIsNoHitRateRatherThanAMisleadingZero() {
        RfqSummary onlyOpenAndDeclined = RfqSummary.of(List.of(quote(QuoteStatus.QUOTED, true, 0), quote(QuoteStatus.REJECTED, false, 0)));
        RfqSummary empty = RfqSummary.of(List.of());

        assertThat(onlyOpenAndDeclined.hitRate()).isNull();
        assertThat(onlyOpenAndDeclined.avgSecondsToAccept()).isNull();
        assertThat(empty.requests()).isZero();
        assertThat(empty.hitRate()).isNull();
    }

    @Test
    void searchingTurnsLapsedPricesIntoExpiredSoTheLogNeverShowsAFirmPriceThatIsNot() {
        FxQuoteRequestRepository repo = mock(FxQuoteRequestRepository.class);
        FxQuoteService service = new FxQuoteService(repo, mock(CorporateRepository.class), mock(SimulatedDealer.class),
                mock(FxLimitService.class), mock(FxDealService.class), mock(FxDealRepository.class), mock(FxEvents.class),
                new FxDealProperties(), mock(ForwardPricer.class), mock(FxSettlementAccounts.class));
        FxQuoteRequest lapsed = quote(QuoteStatus.QUOTED, true, 0);
        lapsed.setValidUntil(LocalDateTime.now().minusSeconds(5));
        FxQuoteRequest firm = quote(QuoteStatus.QUOTED, true, 0);
        firm.setValidUntil(LocalDateTime.now().plusSeconds(20));
        FxQuoteRequest done = quote(QuoteStatus.ACCEPTED, true, 5);
        when(repo.findByCorporateIdAndCreatedAtBetweenOrderByCreatedAtDesc(any(), any(), any())).thenReturn(List.of(firm, lapsed, done));

        List<FxQuoteRequest> found = service.search(CORP, NOW.minusDays(30), NOW);

        assertThat(found).hasSize(3);
        assertThat(lapsed.getStatus()).isEqualTo(QuoteStatus.EXPIRED);
        assertThat(lapsed.getStatusReason()).contains("firm until");
        assertThat(firm.getStatus()).isEqualTo(QuoteStatus.QUOTED);
        assertThat(done.getStatus()).isEqualTo(QuoteStatus.ACCEPTED);
    }
}
