package com.bank.vam.fx.deal;

import com.bank.vam.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.bank.vam.fx.deal.DealFixtures.CORP;
import static com.bank.vam.fx.deal.DealFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FxReportServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 11, 1);
    private static final LocalDate TO = LocalDate.of(2026, 11, 30);

    private final FxDealRepository deals = mock(FxDealRepository.class);
    private final FxSettlementRepository settlements = mock(FxSettlementRepository.class);
    private final FxMtmRepository marks = mock(FxMtmRepository.class);
    private final FxMtmService mtm = mock(FxMtmService.class);
    private final FxDealService dealService = mock(FxDealService.class);
    private final FxReportService reports = new FxReportService(deals, settlements, marks, mtm, dealService);

    private FxDeal deal(String ref, LocalDate traded, DealStatus status) {
        FxDeal d = DealFixtures.deal(Side.BUY, Instrument.FORWARD, status, LocalDate.of(2027, 2, 9));
        d.setDealRef(ref);
        d.setTradeDate(traded);
        d.setEntityName("Treasury, Ltd");
        return d;
    }

    @Test
    void theDealHistoryHoldsDealsTradedInThePeriodInTradeOrder() {
        when(deals.findByCorporateIdOrderByCreatedAtDesc(CORP)).thenReturn(List.of(
                deal("FXD-B", LocalDate.of(2026, 11, 20), DealStatus.ACTIVE), deal("FXD-A", LocalDate.of(2026, 11, 2), DealStatus.SETTLED),
                deal("FXD-OLD", LocalDate.of(2026, 10, 31), DealStatus.ACTIVE)));

        FxReportService.Table t = reports.table("deals", CORP, FROM, TO);

        assertThat(t.rows()).extracting(r -> r.get(0)).containsExactly("FXD-A", "FXD-B");
        assertThat(t.rows().get(0)).contains("EUR 100,000.00", "USD 110,000.00", "1.1", "Settled");
        assertThat(t.columns()).noneMatch(c -> c.toLowerCase().contains("mid") || c.toLowerCase().contains("margin"));
    }

    @Test
    void csvQuotesCellsWithCommasAndThePdfIsALandscapeTable() {
        when(deals.findByCorporateIdOrderByCreatedAtDesc(CORP)).thenReturn(List.of(deal("FXD-A", LocalDate.of(2026, 11, 2), DealStatus.ACTIVE)));
        FxReportService.Table t = reports.table("deals", CORP, FROM, TO);

        assertThat(reports.csv(t)).startsWith("Deal,Entity,Trade date").contains("FXD-A,\"Treasury, Ltd\",2026-11-02");
        String pdf = new String(reports.pdf(t, CORP, FROM, TO), StandardCharsets.ISO_8859_1);
        assertThat(pdf).contains("/MediaBox [0 0 842 595]").contains("FX deal history").contains("FXD-A");
    }

    @Test
    void theMtmStatementCountsDealsOpenAtTheDateAndTotalsTheirWorth() {
        FxDeal open = deal("FXD-OPEN", LocalDate.of(2026, 10, 1), DealStatus.ACTIVE);
        FxDeal tradedAfter = deal("FXD-NEW", LocalDate.of(2026, 11, 2), DealStatus.ACTIVE);
        FxDeal settledLater = deal("FXD-LATER", LocalDate.of(2026, 10, 1), DealStatus.SETTLED);
        settledLater.setSettledAt(LocalDateTime.of(2026, 11, 15, 10, 0));
        FxDeal settledBefore = deal("FXD-GONE", LocalDate.of(2026, 10, 1), DealStatus.SETTLED);
        settledBefore.setSettledAt(LocalDateTime.of(2026, 10, 20, 10, 0));
        when(deals.findByCorporateIdOrderByCreatedAtDesc(CORP)).thenReturn(List.of(open, settledLater, settledBefore, tradedAfter));
        FxMtm m = new FxMtm();
        m.setAsOf(LocalDate.of(2026, 10, 30));
        m.setRateUsed(bd("1.12"));
        m.setMtmQuote(bd("2000.00"));
        m.setMtmHome(bd("7346.00"));
        m.setHomeCurrency("AED");
        when(marks.findByDealIdOrderByAsOfAsc(any())).thenReturn(List.of(m));
        when(mtm.mark(any())).thenReturn(Optional.empty());

        FxReportService.Table t = reports.table("mtm", CORP, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        assertThat(t.rows()).extracting(r -> r.get(0)).containsExactly("FXD-OPEN", "FXD-LATER", "Total");
        assertThat(t.rows().get(2).get(9)).isEqualTo("+14,692.00 AED");
        assertThat(t.subtitle()).contains("As of 2026-10-31");
    }

    @Test
    void anUnknownReportOrABackwardsPeriodIsRefused() {
        assertThatThrownBy(() -> reports.table("pnl", CORP, FROM, TO)).isInstanceOf(BusinessException.class).hasMessageContaining("Unknown report");
        assertThatThrownBy(() -> reports.table("deals", CORP, TO, FROM)).hasMessageContaining("start is on or before its end");
    }
}
