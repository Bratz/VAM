package com.bank.vam.fx.exposure;

import com.bank.vam.entity.Corporate;
import com.bank.vam.repository.CorporateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Gives every existing corporate the default hedge policy at startup. A runner rather than a Flyway
 * migration because {@code fx_policy_band} is an entity table: Hibernate creates it after Flyway has
 * run, so a migration seeding it would fail on a fresh database. Idempotent, and corporates created
 * later are covered on first use by {@link HedgePolicyService#bandsFor}.
 */
@Slf4j
@Component
@Order(110)
@RequiredArgsConstructor
public class FxPolicySeeder implements ApplicationRunner {

    private final CorporateRepository corporates;
    private final HedgePolicyService policy;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = 0;
            for (Corporate c : corporates.findAll()) {
                created += policy.ensureDefaults(c.getId());
            }
            if (created > 0) {
                log.info("FX hedge policy: seeded {} default bands", created);
            }
        } catch (Exception e) {
            // Seeding must never stop the application from starting.
            log.error("FX hedge policy seeding failed; continuing startup", e);
        }
    }
}
