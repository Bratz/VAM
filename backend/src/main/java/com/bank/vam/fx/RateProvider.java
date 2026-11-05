package com.bank.vam.fx;

import java.util.List;
import java.util.Optional;

/** Where live mid rates come from. The mock is the only implementation until a real feed exists. */
public interface RateProvider {

    List<SpotTick> spots();

    /** The pair as quoted ("EUR/USD"), or its inverse ("USD/EUR") computed from it. */
    Optional<SpotTick> spot(String pair);
}
