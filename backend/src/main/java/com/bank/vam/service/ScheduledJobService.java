package com.bank.vam.service;

import com.bank.vam.dto.treasury.IhbDto;
import com.bank.vam.dto.treasury.SweepRuleDto;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.treasury.SweepRule;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.service.treasury.IhbUnifiedService;
import com.bank.vam.service.treasury.SweepService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Scheduled jobs for automated treasury operations.
 *
 * Jobs include:
 * - Real-time sweeps (every 5 minutes) — REAL_TIME-frequency rules only
 * - Daily sweeps (6 PM) — DAILY-frequency rules only
 * - Weekly sweeps (Monday 6 PM) — WEEKLY-frequency rules only
 * - Monthly sweeps (1st, 6 PM) — MONTHLY-frequency rules only
 * - Daily IHB interest accrual (midnight)
 * - Daily deficit funding check (6 AM)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledJobService {

    private final SweepService sweepService;
    private final IhbUnifiedService ihbUnifiedService;
    private final LegalEntityRepository legalEntityRepository;

    /**
     * Execute REAL_TIME-frequency sweeps every 5 minutes.
     *
     * Was previously passing an unfiltered {@code RunSweepsRequest()} here —
     * despite the method's name and the (stale, aspirational) comment that
     * used to sit here, {@link SweepRuleDto.RunSweepsRequest} had no
     * frequency field at all, so {@code resolveRulesForRun} fell through to
     * "every active rule" regardless of its configured frequency. A rule
     * configured DAILY was therefore actually swept ~289x/day (288 times
     * from this job alone, plus once more from {@link #executeDailySweeps}),
     * which is what inflated cumulative "Total Swept" figures into the
     * billions after weeks of uptime. Now scoped to REAL_TIME only.
     */
    @Scheduled(fixedRate = 300000) // 5 minutes
    @Async("sweepExecutor")
    public void executeRealTimeSweeps() {
        log.info("Starting real-time sweep execution at {}", LocalDateTime.now());
        try {
            SweepRuleDto.RunSweepsRequest request = new SweepRuleDto.RunSweepsRequest();
            request.setFrequency(SweepRule.SweepFrequency.REAL_TIME);
            SweepRuleDto.RunSweepsResponse response = sweepService.runSweeps(request);
            log.info("Real-time sweep completed: {} success, {} failed",
                    response.getSuccessCount(), response.getFailedCount());
        } catch (Exception e) {
            log.error("Real-time sweep execution failed", e);
        }
    }

    /**
     * Execute DAILY-frequency sweeps at 6 PM. See {@link #executeRealTimeSweeps}
     * for why this is now scoped to a single frequency instead of every rule.
     */
    @Scheduled(cron = "0 0 18 * * *") // 6 PM daily
    @Async("sweepExecutor")
    public void executeDailySweeps() {
        log.info("Starting daily sweep execution at {}", LocalDateTime.now());
        try {
            SweepRuleDto.RunSweepsRequest request = new SweepRuleDto.RunSweepsRequest();
            request.setFrequency(SweepRule.SweepFrequency.DAILY);
            SweepRuleDto.RunSweepsResponse response = sweepService.runSweeps(request);
            log.info("Daily sweep completed: {} success, {} failed, total swept: {}",
                    response.getSuccessCount(), response.getFailedCount(), response.getTotalSwept());
        } catch (Exception e) {
            log.error("Daily sweep execution failed", e);
        }
    }

    /**
     * Execute WEEKLY-frequency sweeps Monday at 6 PM. Previously missing
     * entirely — WEEKLY/MONTHLY rules still ran (via the unfiltered daily and
     * every-5-minutes jobs above), just on the wrong cadence. Now each
     * {@link SweepRule.SweepFrequency} has exactly one job that runs it.
     */
    @Scheduled(cron = "0 0 18 * * MON")
    @Async("sweepExecutor")
    public void executeWeeklySweeps() {
        log.info("Starting weekly sweep execution at {}", LocalDateTime.now());
        try {
            SweepRuleDto.RunSweepsRequest request = new SweepRuleDto.RunSweepsRequest();
            request.setFrequency(SweepRule.SweepFrequency.WEEKLY);
            SweepRuleDto.RunSweepsResponse response = sweepService.runSweeps(request);
            log.info("Weekly sweep completed: {} success, {} failed, total swept: {}",
                    response.getSuccessCount(), response.getFailedCount(), response.getTotalSwept());
        } catch (Exception e) {
            log.error("Weekly sweep execution failed", e);
        }
    }

    /**
     * Execute MONTHLY-frequency sweeps on the 1st at 6 PM. See
     * {@link #executeWeeklySweeps} — same gap, same fix.
     */
    @Scheduled(cron = "0 0 18 1 * *")
    @Async("sweepExecutor")
    public void executeMonthlySweeps() {
        log.info("Starting monthly sweep execution at {}", LocalDateTime.now());
        try {
            SweepRuleDto.RunSweepsRequest request = new SweepRuleDto.RunSweepsRequest();
            request.setFrequency(SweepRule.SweepFrequency.MONTHLY);
            SweepRuleDto.RunSweepsResponse response = sweepService.runSweeps(request);
            log.info("Monthly sweep completed: {} success, {} failed, total swept: {}",
                    response.getSuccessCount(), response.getFailedCount(), response.getTotalSwept());
        } catch (Exception e) {
            log.error("Monthly sweep execution failed", e);
        }
    }

    /**
     * Calculate IHB interest daily at midnight.
     * Runs for ALL corporates with IHB-enabled entities.
     */
    @Scheduled(cron = "0 0 0 * * *") // Midnight
    @Async("taskExecutor")
    public void calculateDailyInterest() {
        log.info("Starting daily IHB interest calculation at {}", LocalDateTime.now());
        try {
            // Find all distinct corporate IDs with IHB-enabled entities
            List<LegalEntity> ihbEntities = legalEntityRepository.findByIhbEnabledTrue();
            Set<UUID> corporateIds = ihbEntities.stream()
                    .map(LegalEntity::getCorporateId)
                    .filter(id -> id != null)
                    .collect(Collectors.toSet());

            int totalLoans = 0;
            int totalDeposits = 0;
            BigDecimal totalLoanInterest = BigDecimal.ZERO;
            BigDecimal totalDepositInterest = BigDecimal.ZERO;

            for (UUID corporateId : corporateIds) {
                try {
                    IhbDto.CalculateInterestResponse response = ihbUnifiedService.calculateDailyInterest(corporateId);
                    totalLoans += response.getLoansProcessed();
                    totalDeposits += response.getDepositsProcessed();
                    totalLoanInterest = totalLoanInterest.add(
                            response.getTotalLoanInterest() != null ? response.getTotalLoanInterest() : BigDecimal.ZERO);
                    totalDepositInterest = totalDepositInterest.add(
                            response.getTotalDepositInterest() != null ? response.getTotalDepositInterest() : BigDecimal.ZERO);
                    log.debug("Interest calculated for corporate {}: {} loans, {} deposits",
                            corporateId, response.getLoansProcessed(), response.getDepositsProcessed());
                } catch (Exception e) {
                    log.error("Interest calculation failed for corporate {}: {}", corporateId, e.getMessage());
                }
            }

            log.info("Daily IHB interest calculation completed: {} corporates, {} loans ({}), {} deposits ({})",
                    corporateIds.size(), totalLoans, totalLoanInterest, totalDeposits, totalDepositInterest);

        } catch (Exception e) {
            log.error("Daily interest calculation failed", e);
        }
    }

    /**
     * Run deficit funding check daily at 6 AM.
     * Provides IHB loans to accounts below target balance.
     */
    @Scheduled(cron = "0 0 6 * * *") // 6 AM daily
    @Async("taskExecutor")
    public void runDeficitFunding() {
        log.info("Starting daily deficit funding check at {}", LocalDateTime.now());
        try {
            SweepRuleDto.DeficitFundingRequest request = new SweepRuleDto.DeficitFundingRequest();
            SweepRuleDto.DeficitFundingResponse response = sweepService.runDeficitFunding(request);
            log.info("Deficit funding completed: {} funded, {} skipped, total: {}",
                    response.getFundedCount(), response.getSkippedCount(), response.getTotalFunded());
        } catch (Exception e) {
            log.error("Deficit funding failed", e);
        }
    }

    /**
     * Health check for BaNCS integration every minute
     */
    @Scheduled(fixedRate = 60000) // 1 minute
    public void checkBancsHealth() {
        // Implemented in BancsClient, just log status here
        log.debug("BaNCS health check scheduled task running");
    }
}
