package com.bank.vam.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The uc05 routing service, bound from {@code vam.routing.*}.
 *
 * <p>Off unless {@code base-url} is set, so an environment without uc05 behaves exactly as it did
 * before this feature existed: the payment screens fall back to choosing a channel by hand.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "vam.routing")
public class PaymentRoutingProperties {

    /** Base URL of the uc05 API, e.g. {@code https://host/api/v1}. Empty disables routing. */
    private String baseUrl = "";

    /** Connect and read timeout. A quote is a pre-submit convenience; it must never hold a screen. */
    private int timeoutMs = 3000;

    /**
     * Currencies uc05 can quote. This is not a VAM preference -- {@code /routing/quote} declares
     * {@code currency: Literal["USD","EUR","GBP"]} and rejects anything else with a 422. It is a
     * property rather than a constant so the day uc05 adds AED or ILS, this widens by configuration
     * instead of by a release.
     */
    private List<String> currencies = List.of("USD", "EUR", "GBP");

    /**
     * How far the re-quote at confirm may move before the corporate is asked again, as a fraction of
     * the quoted charge. A quote is taken at one moment and the uc05 author is explicit that it does
     * not know what the desk has already sent today, so some drift is normal and some is a different
     * answer.
     */
    private double chargeTolerance = 0.05;

    /** How much later the re-quoted arrival may be before the corporate is asked again. */
    private int arrivalToleranceMinutes = 15;

    public boolean isEnabled() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    public boolean quotable(String currency) {
        return currency != null && currencies.stream().anyMatch(currency::equalsIgnoreCase);
    }
}
