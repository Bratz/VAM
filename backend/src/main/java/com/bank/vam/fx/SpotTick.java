package com.bank.vam.fx;

import java.math.BigDecimal;
import java.time.Instant;

/** An interbank mid rate. Mid only: the bank's spread is negotiated per deal, not published. */
public record SpotTick(String pair, BigDecimal mid, BigDecimal changeBps, Instant asOf, String source) {

    public String base() {
        return pair.substring(0, 3);
    }

    public String quote() {
        return pair.substring(4, 7);
    }
}
