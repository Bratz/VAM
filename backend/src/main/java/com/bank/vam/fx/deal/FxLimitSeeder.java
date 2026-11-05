package com.bank.vam.fx.deal;

import com.bank.vam.entity.Corporate;
import com.bank.vam.repository.CorporateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Gives every existing corporate the default FX credit limit at startup. A runner rather than a Flyway
 * migration for the same reason as the hedge policy: the table is created by Hibernate after Flyway
 * has run, and the limit's currency is derived from the corporate's accounts. Idempotent; a corporate
 * created later is covered on first use by {@link FxLimitService#limitFor}.
 */
@Slf4j
@Component
@Order(111)
@RequiredArgsConstructor
public class FxLimitSeeder implements ApplicationRunner {

    private final CorporateRepository corporates;
    private final FxLimitService limits;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = 0;
            for (Corporate c : corporates.findAll()) {
                if (limits.ensureDefault(c.getId())) {
                    created++;
                }
            }
            if (created > 0) {
                log.info("FX credit limits: seeded {} default limits", created);
            }
        } catch (Exception e) {
            // Seeding must never stop the application from starting.
            log.error("FX credit limit seeding failed; continuing startup", e);
        }
    }
}
