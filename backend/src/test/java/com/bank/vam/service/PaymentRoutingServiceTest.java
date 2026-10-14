package com.bank.vam.service;

import com.bank.vam.client.PaymentRoutingClient;
import com.bank.vam.client.PaymentRoutingClient.Asked;
import com.bank.vam.client.PaymentRoutingClient.Option;
import com.bank.vam.client.PaymentRoutingClient.Quote;
import com.bank.vam.config.PaymentRoutingProperties;
import com.bank.vam.dto.PaymentRoutingDto.OptionsRequest;
import com.bank.vam.dto.PaymentRoutingDto.OptionsResponse;
import com.bank.vam.dto.PaymentRoutingDto.SelectRequest;
import com.bank.vam.dto.PaymentRoutingDto.SelectResponse;
import com.bank.vam.entity.PaymentRouteSelection;
import com.bank.vam.repository.PaymentRouteSelectionRepository;
import com.bank.vam.service.audit.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The re-quote at confirm, and the currency ceiling.
 *
 * <p>A quote is a read at one moment and uc05 keeps nothing, so the only thing standing between a
 * corporate and a charge they were never shown is this comparison. The asymmetry is the point:
 * worse news goes back and asks, better news is recorded and waved through -- nobody should be
 * interrupted to be told their payment got cheaper.
 */
class PaymentRoutingServiceTest {

    private final PaymentRoutingClient client = mock(PaymentRoutingClient.class);
    private final PaymentRouteSelectionRepository selections = mock(PaymentRouteSelectionRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final PaymentRoutingProperties props = new PaymentRoutingProperties();

    private PaymentRoutingService service;

    private static final UUID VA = UUID.randomUUID();
    private static final LocalDateTime QUOTED_ARRIVAL = LocalDateTime.of(2026, 10, 15, 14, 0);
    private static final LocalDateTime BY_WHEN = LocalDateTime.of(2026, 10, 15, 17, 0);

    @BeforeEach
    void setUp() {
        props.setBaseUrl("http://uc05/api/v1");
        service = new PaymentRoutingService(client, props, selections, audit);
        when(selections.save(any())).thenAnswer(i -> {
            PaymentRouteSelection s = i.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });
    }

    // ------------------------------------------------------------------ helpers

    private Option option(String route, BigDecimal charge, LocalDateTime arrival) {
        return new Option(1, List.of("cheapest"), route, "Fedwire Funds", "rtgs",
                charge, 1, arrival.minusHours(1), arrival, 180,
                BigDecimal.ZERO, 0.97, 0.01, 0.01, charge, true, Map.of());
    }

    private Quote quoteOf(Option... options) {
        return new Quote(new Asked("USD", BigDecimal.valueOf(50000), null,
                        LocalDateTime.of(2026, 10, 15, 9, 0), BY_WHEN, "SHA"),
                List.of(options), null, options.length, List.of(), 0.9, 7, null);
    }

    private OptionsRequest ask() {
        return new OptionsRequest(VA, "USD", BigDecimal.valueOf(50000), BY_WHEN,
                null, "SHA", "SUPP", false, false);
    }

    private SelectRequest selecting(String route, BigDecimal shownCharge) {
        return new SelectRequest(ask(), route, shownCharge, QUOTED_ARRIVAL);
    }

    // ---------------------------------------------------------- currency ceiling

    @Test
    void aedIsRefusedWithoutAskingUc05() {
        OptionsRequest aed = new OptionsRequest(VA, "AED", BigDecimal.TEN,
                LocalDateTime.now().plusHours(4), null, "SHA", null, false, false);

        OptionsResponse response = service.options(aed);

        assertThat(response.quotable()).isFalse();
        assertThat(response.note()).contains("AED").contains("USD, EUR, GBP");
        // uc05 would 422 on the Literal; there is nothing to gain from the round trip
        verify(client, never()).quote(any());
    }

    @Test
    void aDeadlineInThePastIsRefusedLocally() {
        OptionsRequest past = new OptionsRequest(VA, "USD", BigDecimal.TEN,
                LocalDateTime.now().minusHours(1), null, null, null, false, false);

        assertThat(service.options(past).quotable()).isFalse();
        verify(client, never()).quote(any());
    }

    @Test
    void routingTurnedOffLeavesThePaymentPossible() {
        props.setBaseUrl("");

        OptionsResponse response = service.options(ask());

        assertThat(response.quotable()).isFalse();
        assertThat(response.quote()).isNull();
        verify(client, never()).quote(any());
    }

    // ------------------------------------------------------------- confirm: pass

    @Test
    void anUnchangedQuoteIsConfirmedAndRecorded() {
        when(client.quote(any())).thenReturn(quoteOf(option("rtgs_usd", BigDecimal.valueOf(25), QUOTED_ARRIVAL)));

        SelectResponse response = service.select(selecting("rtgs_usd", BigDecimal.valueOf(25)));

        assertThat(response.accepted()).isTrue();
        assertThat(response.channel()).isEqualTo("rtgs");   // becomes PaymentRequest.channel
        assertThat(response.selectionId()).isNotNull();
        assertThat(response.requoteNote()).isNull();
    }

    @Test
    void aCheaperRequoteIsAcceptedAndTheMoveIsKept() {
        when(client.quote(any())).thenReturn(quoteOf(option("rtgs_usd", BigDecimal.valueOf(18), QUOTED_ARRIVAL)));

        SelectResponse response = service.select(selecting("rtgs_usd", BigDecimal.valueOf(25)));

        assertThat(response.accepted()).isTrue();
        // recorded, because "it cost less than you were shown" still gets asked about
        assertThat(response.requoteNote()).contains("25").contains("18");
    }

    @Test
    void aChargeRiseInsideToleranceDoesNotInterrupt() {
        // 25.00 -> 26.00 is 4%, under the 5% tolerance
        when(client.quote(any())).thenReturn(quoteOf(option("rtgs_usd", BigDecimal.valueOf(26), QUOTED_ARRIVAL)));

        SelectResponse response = service.select(selecting("rtgs_usd", BigDecimal.valueOf(25)));

        assertThat(response.accepted()).isTrue();
        assertThat(response.requoteNote()).contains("charge");
    }

    // ------------------------------------------------------------ confirm: refuse

    @Test
    void aChargeRiseBeyondToleranceGoesBackAndAsks() {
        when(client.quote(any())).thenReturn(quoteOf(option("rtgs_usd", BigDecimal.valueOf(40), QUOTED_ARRIVAL)));

        SelectResponse response = service.select(selecting("rtgs_usd", BigDecimal.valueOf(25)));

        assertThat(response.accepted()).isFalse();
        assertThat(response.requoteNote()).contains("40");
        assertThat(response.quote()).isNotNull();   // the screen needs something to offer instead
        verify(selections, never()).save(any());
    }

    @Test
    void aWithdrawnRouteGoesBackAndAsks() {
        when(client.quote(any())).thenReturn(quoteOf(option("instant_usd", BigDecimal.valueOf(5), QUOTED_ARRIVAL)));

        SelectResponse response = service.select(selecting("rtgs_usd", BigDecimal.valueOf(25)));

        assertThat(response.accepted()).isFalse();
        assertThat(response.requoteNote()).contains("no longer being offered");
        verify(selections, never()).save(any());
    }

    @Test
    void anArrivalPastTheDeadlineIsRefusedWhateverTheTolerance() {
        // only 30 minutes later than quoted, which tolerance would wave through -- but it now lands
        // after by_when, and that is a different answer rather than drift
        props.setArrivalToleranceMinutes(600);
        when(client.quote(any())).thenReturn(
                quoteOf(option("rtgs_usd", BigDecimal.valueOf(25), BY_WHEN.plusMinutes(30))));

        SelectResponse response = service.select(selecting("rtgs_usd", BigDecimal.valueOf(25)));

        assertThat(response.accepted()).isFalse();
        assertThat(response.requoteNote()).contains("deadline");
        verify(selections, never()).save(any());
    }

    @Test
    void anArrivalLaterBeyondToleranceGoesBackAndAsks() {
        when(client.quote(any())).thenReturn(
                quoteOf(option("rtgs_usd", BigDecimal.valueOf(25), QUOTED_ARRIVAL.plusMinutes(90))));

        SelectResponse response = service.select(selecting("rtgs_usd", BigDecimal.valueOf(25)));

        assertThat(response.accepted()).isFalse();
        assertThat(response.requoteNote()).contains("90 minutes later");
    }

    @Test
    void anEarlierArrivalIsNeverAReasonToInterrupt() {
        when(client.quote(any())).thenReturn(
                quoteOf(option("rtgs_usd", BigDecimal.valueOf(25), QUOTED_ARRIVAL.minusHours(2))));

        assertThat(service.select(selecting("rtgs_usd", BigDecimal.valueOf(25))).accepted()).isTrue();
    }

    // --------------------------------------------------------------- the evidence

    @Test
    void theRecordedFiguresAreTheRequotedOnesNotWhatTheScreenClaimed() {
        when(client.quote(any())).thenReturn(quoteOf(option("rtgs_usd", BigDecimal.valueOf(26), QUOTED_ARRIVAL)));

        service.select(selecting("rtgs_usd", BigDecimal.valueOf(25)));

        org.mockito.ArgumentCaptor<PaymentRouteSelection> captor =
                org.mockito.ArgumentCaptor.forClass(PaymentRouteSelection.class);
        verify(selections).save(captor.capture());
        PaymentRouteSelection saved = captor.getValue();

        // what they were shown, kept as evidence
        assertThat(saved.getQuotedCharge()).isEqualByComparingTo("25");
        // what VAM treats as true: the re-quote, so a client cannot talk VAM into a false figure
        assertThat(saved.getConfirmedCharge()).isEqualByComparingTo("26");
        assertThat(saved.getSheetVersion()).isEqualTo(7);
        assertThat(saved.getProven()).isTrue();
        assertThat(saved.getLabels()).isEqualTo("cheapest");
        assertThat(saved.getRouteKind()).isEqualTo("rtgs");
    }
}
