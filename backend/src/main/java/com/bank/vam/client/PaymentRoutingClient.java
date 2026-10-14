package com.bank.vam.client;

import com.bank.vam.config.PaymentRoutingProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Reads route options from uc05's {@code GET /routing/quote}.
 *
 * <p>The records below mirror {@code router/quote.py} field for field, under the names uc05 emits.
 * Deliberately no translation into a VAM vocabulary: the labels, the route kinds and the
 * explainability are uc05's own, and a second set of names for the same things is how two services
 * drift apart. {@code why} stays an untyped map because VAM only forwards it to the screen -- there
 * is nothing to gain from modelling a structure nothing here computes on.
 *
 * <p>Unknown properties are ignored so uc05 can add a field without breaking this.
 *
 * <p>One class, no interface. There is a single implementation and nothing to mock: when uc05 is
 * unreachable the circuit breaker's fallback returns a quote with no options and a reason, and the
 * payment screens fall back to choosing a channel by hand -- which is exactly what they did before
 * this feature existed.
 */
@Slf4j
@Component
public class PaymentRoutingClient {

    private final RestTemplate restTemplate;
    private final PaymentRoutingProperties props;

    public PaymentRoutingClient(RestTemplateBuilder builder, PaymentRoutingProperties props) {
        this.props = props;
        // Its own timeout rather than the shared RestTemplate bean, whose timeout is bancs.api.timeout
        // -- tuning the core-banking client should not silently change how long a screen waits for a quote.
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .setReadTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .build();
    }

    // ------------------------------------------------------------------ the ask

    /** What uc05 needs to quote one payment. {@code destination} present means cross-border. */
    public record Ask(String currency,
                      BigDecimal amount,
                      LocalDateTime byWhen,
                      String destination,
                      String chargeBearer,
                      String purpose,
                      boolean reachCard,
                      boolean reachToken) {}

    // --------------------------------------------------------------- the answer

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Asked(String currency, BigDecimal amount, String destination,
                        LocalDateTime at, LocalDateTime byWhen, String chargeBearer) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Option(Integer rank,
                         List<String> labels,
                         String route,
                         String name,
                         String kind,
                         BigDecimal charge,
                         Integer parts,
                         LocalDateTime send,
                         LocalDateTime arrival,
                         Integer minutesToSpare,
                         BigDecimal expectedDeduction,
                         Double onTime,
                         Double onTimeMargin,
                         Double failure,
                         BigDecimal expectedTotal,
                         Boolean proven,
                         Map<String, Object> why) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Excluded(String route, String name, String reason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Quote(Asked asked,
                        List<Option> options,
                        /** uc05's reason when it offered nothing; null when it offered something. */
                        String none,
                        Integer considered,
                        List<Excluded> excluded,
                        Double floor,
                        Integer sheetVersion,
                        /** null for a domestic payment; false when the learner never saw this corridor. */
                        Boolean destinationKnown) {

        public boolean hasOptions() {
            return options != null && !options.isEmpty();
        }

        public Option option(String route) {
            return options == null ? null
                    : options.stream().filter(o -> route != null && route.equals(o.route())).findFirst().orElse(null);
        }
    }

    // ------------------------------------------------------------------- the call

    @CircuitBreaker(name = "uc05Routing", fallbackMethod = "unavailable")
    public Quote quote(Ask ask) {
        String url = UriComponentsBuilder.fromHttpUrl(props.getBaseUrl() + "/routing/quote")
                .queryParam("currency", ask.currency())
                .queryParam("amount", ask.amount())
                // uc05 keeps local times and rejects an offset, so send it naive
                .queryParam("by_when", DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(ask.byWhen()))
                .queryParamIfPresent("destination", java.util.Optional.ofNullable(ask.destination()))
                .queryParamIfPresent("charge_bearer", java.util.Optional.ofNullable(ask.chargeBearer()))
                .queryParamIfPresent("purpose", java.util.Optional.ofNullable(ask.purpose()))
                .queryParam("reach_card", ask.reachCard())
                .queryParam("reach_token", ask.reachToken())
                .toUriString();

        Quote quote = restTemplate.getForObject(url, Quote.class);
        log.debug("uc05 quoted {} option(s) for {} {} to {}",
                quote != null && quote.options() != null ? quote.options().size() : 0,
                ask.amount(), ask.currency(), ask.destination() == null ? "domestic" : ask.destination());
        return quote;
    }

    /**
     * No options and a reason, rather than an exception. A quote is a convenience ahead of a payment;
     * uc05 being down must leave the payment possible, not block it.
     */
    @SuppressWarnings("unused")   // resilience4j resolves it by name
    private Quote unavailable(Ask ask, Throwable t) {
        log.warn("uc05 routing unavailable, no options offered: {}", t.toString());
        return new Quote(null, List.of(),
                "Routing options are unavailable right now. Choose a channel to continue.",
                0, List.of(), null, null, null);
    }
}
