package com.bank.vam.fx.deal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * End of day for FX deals: mark every open deal, refresh its credit-limit charge, and settle whatever is due.
 * Like every scheduled job here it only runs when {@code vam.scheduling.enabled} is on.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class FxEodScheduler {

    private final FxMtmService mtm;
    private final FxSettlementService settlement;

    @Scheduled(cron = "${vam.fx.deal.eod-cron:0 0 18 * * *}")
    void endOfDay() {
        try {
            int marked = mtm.snapshot(LocalDate.now(), null);
            FxSettlementService.RunResult r = settlement.run(null, null);
            log.info("FX end of day: {} deals marked; settled {}, failed {}, blocked {}", marked, r.settled(), r.failed(), r.blocked());
        } catch (Exception e) {
            log.error("FX end of day failed", e);
        }
    }
}
