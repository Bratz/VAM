package com.bank.vam.config;

import com.bank.vam.service.treasury.IhbUnifiedService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Gives every IHB participant the IC Payable VA that onboarding would have created.
 *
 * <p>IC Payable VAs are made only by {@code createIhbCurrentAccount}, alongside the IC Receivable.
 * A participant onboarded before that step existed keeps the receivable and never gets the payable,
 * and nothing re-runs onboarding for an existing participant — so the COBO direction has no account
 * tracking Treasury's obligation to that subsidiary. One of the two live participants was in
 * exactly that state.
 *
 * <p>A runner rather than a Flyway migration because the fix is not a row: the account needs the
 * right parent, owner, {@code mirrorsVaId} and a hierarchy node, all of which
 * {@code ensureIcPayableVa} already works out. SQL would have to reproduce that and would drift
 * from it. Left in place rather than made one-shot because the underlying method is idempotent, so
 * this is a no-op once satisfied and also repairs any participant that lands in the same state
 * later.
 *
 * <p>Ordered after the data initializers so legal entities and accounts are present.
 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class IcPayableBackfillRunner implements ApplicationRunner {

    private final IhbUnifiedService ihbUnifiedService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = ihbUnifiedService.backfillMissingIcPayableVas();
            if (created > 0) {
                log.info("IC Payable backfill created {} account(s) at startup", created);
            }
        } catch (Exception e) {
            // A backfill must never stop the application from starting.
            log.error("IC Payable backfill failed; continuing startup", e);
        }
    }
}
