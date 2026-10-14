package com.bank.vam.client;

import com.bank.vam.client.PaymentRoutingClient.Quote;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire between VAM and uc05.
 *
 * <p>The payload below is uc05's, built field by field from the return statement of its
 * {@code router/quote.py} -- snake_case names, naive local date-times, the nested {@code why} terms.
 * This is the seam where a quiet mistake costs the most: a name that does not match binds to null,
 * and a null charge reads as a free payment rather than as a bug. A rename on either side fails
 * here instead of showing someone a wrong number.
 *
 * <p>Honest about what it proves: it checks these records against uc05's source as read, not
 * against a running uc05. The first call to a live instance is still the real test.
 */
class PaymentRoutingClientBindingTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private static final String UC05_QUOTE = """
            {
              "asked": {
                "currency": "USD",
                "amount": 250000.0,
                "destination": "SG",
                "at": "2026-10-14T09:30:00",
                "by_when": "2026-10-15T17:00:00",
                "charge_bearer": "SHA"
              },
              "options": [
                {
                  "rank": 1,
                  "labels": ["cheapest", "surest"],
                  "route": "correspondent_usd_sg",
                  "name": "Correspondent via SCB Singapore",
                  "kind": "correspondent",
                  "charge": 42.5,
                  "parts": 2,
                  "send": "2026-10-14T10:00:00",
                  "arrival": "2026-10-15T11:30:00",
                  "minutes_to_spare": 330,
                  "expected_deduction": 18.0,
                  "on_time": 0.9412,
                  "on_time_margin": 0.0233,
                  "failure": 0.0071,
                  "expected_total": 60.5,
                  "proven": true,
                  "why": {
                    "on_time": {
                      "prior": 0.91,
                      "estimate": 0.9412,
                      "margin": 0.0233,
                      "top": [["corridor_sg", 0.0182], ["amount_band_high", -0.0041]],
                      "rest": 0.0012
                    },
                    "deduction": {
                      "prior": 22.0, "estimate": 18.0, "margin": 4.1,
                      "top": [["charge_bearer_sha", -3.2]], "rest": 0.8
                    },
                    "fail": {
                      "prior": 0.009, "estimate": 0.0071, "margin": 0.004,
                      "top": [], "rest": 0.0
                    }
                  }
                }
              ],
              "none": null,
              "considered": 4,
              "excluded": [
                {
                  "route": "instant_usd",
                  "name": "RTP",
                  "reason": "above the scheme limit for this amount"
                }
              ],
              "floor": 0.9,
              "sheet_version": 7,
              "destination_known": true
            }
            """;

    @Test
    void uc05sOwnPayloadBindsWithNothingLeftNull() throws Exception {
        Quote quote = mapper.readValue(UC05_QUOTE, Quote.class);

        assertThat(quote.asked().currency()).isEqualTo("USD");
        assertThat(quote.asked().destination()).isEqualTo("SG");
        // naive local times: uc05 keeps the route data in local time and sends no offset
        assertThat(quote.asked().byWhen()).isEqualTo(LocalDateTime.of(2026, 10, 15, 17, 0));
        assertThat(quote.considered()).isEqualTo(4);
        assertThat(quote.sheetVersion()).isEqualTo(7);
        assertThat(quote.destinationKnown()).isTrue();
        assertThat(quote.none()).isNull();
        assertThat(quote.floor()).isEqualTo(0.9);

        assertThat(quote.hasOptions()).isTrue();
        var option = quote.options().get(0);
        assertThat(option.route()).isEqualTo("correspondent_usd_sg");
        assertThat(option.kind()).isEqualTo("correspondent");
        assertThat(option.labels()).containsExactly("cheapest", "surest");
        // the four figures a screen shows, and the ones a null would silently falsify
        assertThat(option.charge()).isEqualByComparingTo("42.5");
        assertThat(option.expectedTotal()).isEqualByComparingTo("60.5");
        assertThat(option.expectedDeduction()).isEqualByComparingTo("18.0");
        assertThat(option.onTime()).isEqualTo(0.9412);
        assertThat(option.onTimeMargin()).isEqualTo(0.0233);
        assertThat(option.minutesToSpare()).isEqualTo(330);
        assertThat(option.arrival()).isEqualTo(LocalDateTime.of(2026, 10, 15, 11, 30));
        assertThat(option.proven()).isTrue();
        assertThat(option.why()).containsKeys("on_time", "deduction", "fail");

        assertThat(quote.excluded()).hasSize(1);
        assertThat(quote.excluded().get(0).reason()).contains("scheme limit");

        // the lookup select() relies on to find the chosen route in a fresh quote
        assertThat(quote.option("correspondent_usd_sg")).isNotNull();
        assertThat(quote.option("rtgs_usd")).isNull();
    }

    @Test
    void aDomesticQuoteWithNoOptionsStillBinds() throws Exception {
        // uc05 sends destination and destination_known as null for a domestic payment, and `none`
        // carries its reason when it offered nothing. All three must survive, because the screen
        // shows that reason instead of an empty list with no explanation.
        String json = """
                {"asked": {"currency": "GBP", "amount": 1000.0, "destination": null,
                           "at": "2026-10-14T09:30:00", "by_when": "2026-10-14T10:00:00",
                           "charge_bearer": "OUR"},
                 "options": [], "none": "no eligible route", "considered": 0, "excluded": [],
                 "floor": 0.9, "sheet_version": 7, "destination_known": null}
                """;

        Quote quote = mapper.readValue(json, Quote.class);

        assertThat(quote.hasOptions()).isFalse();
        assertThat(quote.none()).isEqualTo("no eligible route");
        assertThat(quote.asked().destination()).isNull();
        assertThat(quote.destinationKnown()).isNull();
    }

    @Test
    void anUnknownFieldFromUc05DoesNotBreakTheBinding() throws Exception {
        // uc05 is a live project; it must be able to add a field without taking VAM down.
        String json = """
                {"asked": null, "options": [], "none": null, "considered": 0, "excluded": [],
                 "floor": 0.9, "sheet_version": 7, "destination_known": null,
                 "something_uc05_added_later": {"nested": [1, 2, 3]}}
                """;

        assertThat(mapper.readValue(json, Quote.class).sheetVersion()).isEqualTo(7);
    }
}
