package com.bank.vam.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The single place scheduling is switched on — and the kill switch for it.
 *
 * <p>Set {@code vam.scheduling.enabled=false} (env: {@code VAM_SCHEDULING_ENABLED=false})
 * and no {@code @Scheduled} method anywhere in the app runs: the condition keeps Spring's
 * scheduling post-processor from being registered at all, so the annotations are simply
 * never seen. Nothing else changes — every job's endpoint/service method stays callable
 * by hand.
 *
 * <p>Defaults to on. Turn it off on a deployment where the treasury automation should sit
 * still (a read-only demo, a resource-starved VM, a debugging session where recurring
 * sweeps keep changing the data under you). Be aware of what stops: sweeps stop moving
 * money, IHB interest stops accruing, usage limits stop resetting, shadow balances stop
 * refreshing and VIBAN cooling periods never release.
 *
 * <p>{@code @EnableScheduling} used to be declared twice (on {@code AsyncConfig} and on
 * the application class itself), which is harmless but meant there was no single place to
 * gate. Both were removed in favour of this class.
 */
@Configuration
@ConditionalOnProperty(name = "vam.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class SchedulingConfig {
}
