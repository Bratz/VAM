package com.bank.vam.fx;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * An indicative interbank forward: spot plus points from the interest differential. {@code tenor}
 * is the standard label (null when the caller supplied an exact value date). Not a dealable price;
 * the bank's spread is added when a quote is requested.
 */
public record ForwardQuote(
        String pair,
        String tenor,
        LocalDate spotDate,
        LocalDate valueDate,
        int days,
        BigDecimal spotMid,
        BigDecimal forwardPoints,
        BigDecimal forwardMid,
        BigDecimal baseRatePct,
        BigDecimal quoteRatePct,
        String source) {
}
