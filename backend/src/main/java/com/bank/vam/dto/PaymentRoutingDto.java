package com.bank.vam.dto;

import com.bank.vam.client.PaymentRoutingClient.Quote;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Requests and answers for the routing step that sits between capturing a payment and submitting it. */
public class PaymentRoutingDto {

    /**
     * A payment intent, as much of it as routing needs.
     *
     * <p>Everything here except {@code byWhen} is already collected by the payment screens --
     * {@code TransactionDto.PaymentRequest} carries beneficiary country, charge bearer and purpose
     * code today and throws all three away on the routing question, because nothing asked it. The
     * deadline is the one genuinely new field: without it there is no "on time" to be on.
     */
    public record OptionsRequest(UUID fromVaId,
                                 String currency,
                                 BigDecimal amount,
                                 LocalDateTime byWhen,
                                 /** Alpha-2. Present means cross-border; absent means domestic. */
                                 String beneficiaryCountry,
                                 String chargeBearer,
                                 String purposeCode,
                                 boolean reachCard,
                                 boolean reachToken) {}

    /**
     * The quote, plus why VAM did not ask when it did not.
     *
     * <p>{@code quotable} false is not an error and not an outage: it is a currency uc05 does not
     * quote. The screen says so and falls back to choosing a channel by hand.
     */
    public record OptionsResponse(boolean quotable, String note, Quote quote) {

        public static OptionsResponse notQuotable(String note) {
            return new OptionsResponse(false, note, null);
        }

        public static OptionsResponse of(Quote quote) {
            return new OptionsResponse(true, null, quote);
        }
    }

    /**
     * The chosen route, with the figures the corporate was shown.
     *
     * <p>The quoted charge and arrival are what the screen displayed, sent back so the service can
     * tell whether the world moved. They are evidence of what was shown, never the figures VAM
     * records as true -- those come from the re-quote.
     */
    public record SelectRequest(OptionsRequest ask,
                                String route,
                                BigDecimal quotedCharge,
                                LocalDateTime quotedArrival) {}

    /**
     * Accepted, with the selection recorded; or refused, with the current options to choose from again.
     */
    public record SelectResponse(boolean accepted,
                                 UUID selectionId,
                                 /** The chosen route's kind, which becomes PaymentRequest.channel. */
                                 String channel,
                                 /** What moved since the quote, whether or not it was enough to refuse. */
                                 String requoteNote,
                                 /** Present only when refused: the options as they stand now. */
                                 Quote quote) {}
}
