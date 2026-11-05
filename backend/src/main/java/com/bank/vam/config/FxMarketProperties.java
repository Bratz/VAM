package com.bank.vam.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Corporate-FX market simulation, bound from {@code vam.fx.market.*}. Everything here is demo
 * data: the rates are illustrative, not a market view (see tasks/fx-corporate-design.md).
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "vam.fx.market")
public class FxMarketProperties {

    /** One simulated tick per this many ms; a read after a gap catches up, capped by maxCatchUpSteps. */
    private long tickIntervalMs = 1000;
    private int maxCatchUpSteps = 60;

    /** Pull of the walk back towards its anchor per tick, so a long-running demo never drifts away. */
    private double meanReversion = 0.02;

    private int spotLagDays = 2;
    private int maxForwardDays = 365;

    /** Annual money-market rates in percent. Only the differential matters to forward points. */
    private Map<String, BigDecimal> interestRates = Map.of(
            "USD", new BigDecimal("4.00"), "EUR", new BigDecimal("2.00"),
            "GBP", new BigDecimal("3.75"), "AED", new BigDecimal("3.90"),
            "SAR", new BigDecimal("5.00"), "INR", new BigDecimal("6.00"),
            "CHF", new BigDecimal("0.25"), "JPY", new BigDecimal("0.50"),
            "SGD", new BigDecimal("2.50"));

    /** Money-market day count: these accrue on 365, everything else on 360. */
    private Set<String> actual365Currencies = Set.of("GBP", "SGD", "INR");

    /** Independent random walks; every one must involve USD so crosses can be triangulated. */
    private List<Walk> walks = List.of(
            walk("EUR/USD", "1.0926", 0.6), walk("GBP/USD", "1.2665", 0.6),
            walk("USD/JPY", "149.50", 0.6), walk("USD/CHF", "0.8800", 0.5),
            walk("USD/INR", "83.20", 0.15), walk("USD/SGD", "1.3400", 0.3),
            walk("USD/AED", "3.6725", 0), walk("USD/SAR", "3.7500", 0));

    /** Derived through USD from the walks, so the board is always triangular-consistent. */
    private List<String> crosses = List.of("EUR/GBP", "EUR/AED", "GBP/AED", "EUR/JPY");

    @Getter
    @Setter
    public static class Walk {
        private String pair;
        /** Used only when fx_rates has no rate for the pair. */
        private BigDecimal fallbackMid;
        /** One-tick volatility, in basis points of the rate. Zero pins the pair (AED, SAR). */
        private double volBps;
    }

    private static Walk walk(String pair, String fallbackMid, double volBps) {
        Walk w = new Walk();
        w.setPair(pair);
        w.setFallbackMid(new BigDecimal(fallbackMid));
        w.setVolBps(volBps);
        return w;
    }
}
