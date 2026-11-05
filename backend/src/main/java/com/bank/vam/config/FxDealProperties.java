package com.bank.vam.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Corporate FX dealing, bound from {@code vam.fx.deal.*}. Spreads and credit factors are illustrative
 * demo numbers, not a pricing or risk policy (see tasks/fx-corporate-design.md).
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "vam.fx.deal")
public class FxDealProperties {

    /** How long a dealer price stays firm. */
    private int quoteValiditySeconds = 30;

    /** How long the corporate has to affirm a confirmation before it is flagged overdue. */
    private int confirmationDueHours = 24;

    private BigDecimal minAmount = new BigDecimal("1000");

    /** Credit limit given to a corporate with none, in its home currency. */
    private BigDecimal defaultLimit = new BigDecimal("20000000");

    /** Spread charged against the client, in basis points of the rate, before size and tenor add-ons. */
    private Map<String, BigDecimal> baseSpreadBps = Map.ofEntries(
            Map.entry("EUR/USD", new BigDecimal("3")), Map.entry("GBP/USD", new BigDecimal("4")),
            Map.entry("USD/JPY", new BigDecimal("4")), Map.entry("USD/CHF", new BigDecimal("5")),
            Map.entry("USD/INR", new BigDecimal("12")), Map.entry("USD/SGD", new BigDecimal("6")),
            Map.entry("USD/AED", new BigDecimal("2")), Map.entry("USD/SAR", new BigDecimal("2")),
            Map.entry("EUR/GBP", new BigDecimal("5")), Map.entry("EUR/AED", new BigDecimal("6")),
            Map.entry("GBP/AED", new BigDecimal("7")), Map.entry("EUR/JPY", new BigDecimal("7")));
    private BigDecimal defaultSpreadBps = new BigDecimal("8");

    /** Extra spread for a large ticket, in units of the pair's base currency. */
    private BigDecimal largeAmount = new BigDecimal("1000000");
    private BigDecimal largeAmountAddBps = new BigDecimal("1");
    private BigDecimal hugeAmount = new BigDecimal("5000000");
    private BigDecimal hugeAmountAddBps = new BigDecimal("3");

    /** Extra spread per 30 days to maturity: a longer forward is harder for the desk to cover. */
    private BigDecimal tenorAddBpsPerMonth = new BigDecimal("0.5");

    /**
     * Credit exposure charged on a deal, as a share of its notional, by days to maturity. A deal takes
     * the first row whose {@code maxDays} covers it; beyond the last row it takes the last.
     */
    private List<PfeBand> pfeBands = List.of(
            pfe(7, "0.01"), pfe(31, "0.03"), pfe(92, "0.06"), pfe(183, "0.10"), pfe(366, "0.15"));

    @Getter
    @Setter
    public static class PfeBand {
        private int maxDays;
        private BigDecimal factor;
    }

    private static PfeBand pfe(int maxDays, String factor) {
        PfeBand b = new PfeBand();
        b.setMaxDays(maxDays);
        b.setFactor(new BigDecimal(factor));
        return b;
    }
}
