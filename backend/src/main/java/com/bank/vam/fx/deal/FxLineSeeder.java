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
 * Gives every corporate without one an FX line for the demo (see {@link FxLimitService#ensureLine}): an
 * FX_TREASURY_LINE facility under its agreement with the home bank, the line's external limit, and the whole line
 * allocated to its treasury centre or holding entity. A runner rather than a Flyway migration because the line's
 * currency and entity come from data Hibernate-managed tables hold. Idempotent. Corporates created later get no
 * line until the bank sets one up: without one they cannot deal.
 */
@Slf4j
@Component
@Order(111)
@RequiredArgsConstructor
public class FxLineSeeder implements ApplicationRunner {

    private final CorporateRepository corporates;
    private final FxLimitService limits;

    @Override
    public void run(ApplicationArguments args) {
        int created = 0;
        for (Corporate c : corporates.findAll()) {
            try {
                if (limits.ensureLine(c.getId())) {
                    created++;
                }
            } catch (Exception e) {
                // One corporate's data must not stop the others, nor the application from starting.
                log.error("FX line seeding failed for corporate {}; continuing", c.getId(), e);
            }
        }
        if (created > 0) {
            log.info("FX lines: seeded {} FX facilities with their lines and entity sub-limits", created);
        }
    }
}
