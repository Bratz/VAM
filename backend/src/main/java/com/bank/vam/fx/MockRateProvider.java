package com.bank.vam.fx;

import com.bank.vam.config.FxMarketProperties;
import com.bank.vam.service.treasury.FxRateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/**
 * Simulated live market: a mean-reverting random walk per pair, anchored on the mid already in
 * {@code fx_rates} so it starts where the rest of the platform thinks the market is.
 *
 * <p>The ticks live in memory on purpose and are never written back to {@code fx_rates}: that table
 * is unique per pair/day/type/source and feeds balance aggregation, netting and the mirrors, so a
 * write per tick would both violate the key and move every consolidated balance with it.
 *
 * <p>There is no scheduler. The walk advances by the time elapsed whenever it is read, so it runs
 * with {@code VAM_SCHEDULING_ENABLED=false} and every viewer sees one shared series.
 */
@Slf4j
@Component
public class MockRateProvider implements RateProvider {

    /** Every rate from here is simulated; screens show this as a "Simulated" badge. */
    public static final String SOURCE = "SIMULATED";
    /** How often a configured pair with no stored mid is looked up again. */
    static final long ANCHOR_RETRY_MS = 60_000;

    private final FxMarketProperties props;
    private final Function<String, Optional<BigDecimal>> anchorLookup;
    private final LongSupplier nowMillis;
    private final RandomGenerator rng;

    private final Map<String, State> walks = new LinkedHashMap<>();
    private long lastTickMillis;
    private long lastAnchorMillis;
    private boolean started;

    @Autowired
    public MockRateProvider(FxMarketProperties props, FxRateService fxRateService) {
        this(props, pair -> dbMid(fxRateService, pair), System::currentTimeMillis, RandomGenerator.getDefault());
    }

    MockRateProvider(FxMarketProperties props, Function<String, Optional<BigDecimal>> anchorLookup,
                     LongSupplier nowMillis, RandomGenerator rng) {
        this.props = props;
        this.anchorLookup = anchorLookup;
        this.nowMillis = nowMillis;
        this.rng = rng;
    }

    @Override
    public synchronized List<SpotTick> spots() {
        advance();
        Instant asOf = Instant.ofEpochMilli(lastTickMillis);
        List<SpotTick> out = new ArrayList<>();
        for (State s : walks.values()) {
            out.add(tick(s.pair, s.mid(), s.anchor, asOf));
        }
        for (String cross : props.getCrosses()) {
            if (!quoted(cross.substring(0, 3)) || !quoted(cross.substring(4, 7))) {
                continue; // a leg has no stored rate, so the cross has none either
            }
            double now = usdPer(cross.substring(0, 3), false) / usdPer(cross.substring(4, 7), false);
            double open = usdPer(cross.substring(0, 3), true) / usdPer(cross.substring(4, 7), true);
            out.add(tick(cross, now, open, asOf));
        }
        return out;
    }

    @Override
    public synchronized Optional<SpotTick> spot(String pair) {
        List<SpotTick> all = spots();
        Optional<SpotTick> direct = all.stream().filter(t -> t.pair().equals(pair)).findFirst();
        if (direct.isPresent()) {
            return direct;
        }
        String inverse = pair.substring(4, 7) + "/" + pair.substring(0, 3);
        return all.stream().filter(t -> t.pair().equals(inverse)).findFirst().map(MockRateProvider::invert);
    }

    // ponytail: one lock around the whole walk; fine for a demo, shard per pair only if a real feed replaces this.
    private void advance() {
        long now = nowMillis.getAsLong();
        if (!started) {
            started = true;
            lastTickMillis = now;
            lastAnchorMillis = now - ANCHOR_RETRY_MS;
        }
        long due = (now - lastTickMillis) / props.getTickIntervalMs();
        if (due > 0) {
            long steps = Math.min(due, props.getMaxCatchUpSteps());
            lastTickMillis = due > steps ? now : lastTickMillis + due * props.getTickIntervalMs();
            for (long i = 0; i < steps; i++) {
                for (State s : walks.values()) {
                    s.dev = s.dev * (1 - props.getMeanReversion()) + s.volBps / 1e4 * rng.nextGaussian();
                }
            }
        }
        // After the steps, so a pair that has just got a stored rate starts exactly at it.
        if (now - lastAnchorMillis >= ANCHOR_RETRY_MS) {
            anchorMissing(now);
        }
    }

    /**
     * Starts a walk for each configured pair whose mid is now stored. A pair without one is left off the board
     * (no rate, rather than a made-up one) and looked up again after {@link #ANCHOR_RETRY_MS}.
     */
    private void anchorMissing(long now) {
        lastAnchorMillis = now;
        for (FxMarketProperties.Walk w : props.getWalks()) {
            if (!walks.containsKey(w.getPair())) {
                anchorLookup.apply(w.getPair()).ifPresent(mid ->
                        walks.put(w.getPair(), new State(w.getPair(), mid.doubleValue(), w.getVolBps())));
            }
        }
    }

    private boolean quoted(String ccy) {
        return ccy.equals("USD") || walks.containsKey(ccy + "/USD") || walks.containsKey("USD/" + ccy);
    }

    /** USD per one unit of {@code ccy}, from whichever walk quotes it against USD. */
    private double usdPer(String ccy, boolean atOpen) {
        if (ccy.equals("USD")) {
            return 1;
        }
        State direct = walks.get(ccy + "/USD");
        if (direct != null) {
            return atOpen ? direct.anchor : direct.mid();
        }
        State inverse = walks.get("USD/" + ccy);
        if (inverse != null) {
            return 1 / (atOpen ? inverse.anchor : inverse.mid());
        }
        throw new IllegalStateException("No USD walk for " + ccy + "; a cross needs both legs quoted against USD");
    }

    private static SpotTick tick(String pair, double mid, double open, Instant asOf) {
        return new SpotTick(pair, round(mid), BigDecimal.valueOf((mid / open - 1) * 1e4).setScale(2, RoundingMode.HALF_UP),
                asOf, SOURCE);
    }

    private static SpotTick invert(SpotTick t) {
        double mid = 1 / t.mid().doubleValue();
        double change = (1 / (1 + t.changeBps().doubleValue() / 1e4) - 1) * 1e4;
        return new SpotTick(t.quote() + "/" + t.base(), round(mid),
                BigDecimal.valueOf(change).setScale(2, RoundingMode.HALF_UP), t.asOf(), t.source());
    }

    private static BigDecimal round(double mid) {
        return BigDecimal.valueOf(mid).setScale(mid >= 50 ? 3 : 5, RoundingMode.HALF_UP);
    }

    private static Optional<BigDecimal> dbMid(FxRateService fxRateService, String pair) {
        try {
            return Optional.of(fxRateService.getRate(pair.substring(0, 3), pair.substring(4, 7)));
        } catch (RuntimeException e) {
            log.debug("No stored mid for {}, so it has no simulated rate: {}", pair, e.getMessage());
            return Optional.empty();
        }
    }

    private static final class State {
        final String pair;
        /** The session open: the walk reverts here, and the board's change is measured against it. */
        final double anchor;
        final double volBps;
        double dev;

        State(String pair, double anchor, double volBps) {
            this.pair = pair;
            this.anchor = anchor;
            this.volBps = volBps;
        }

        double mid() {
            return anchor * (1 + dev);
        }
    }
}
