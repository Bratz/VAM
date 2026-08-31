package com.bank.vam.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Multi-bank liquidity feature configuration. Bound from {@code vam.multi-bank.*}.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "vam.multi-bank")
public class MultiBankProperties {

    /** Default freshness threshold (minutes) for shadow balance staleness. */
    private int defaultFreshnessThresholdMinutes = 60;

    /** Scheduled refresh cadence (minutes) for shadow balances. */
    private int refreshCadenceMinutes = 15;
}
