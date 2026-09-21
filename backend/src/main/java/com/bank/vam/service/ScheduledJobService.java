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
    private final VirtualAccountService virtualAccountService;
    private final com.bank.vam.repository.ProgramRepository programRepository;
    private final com.bank.vam.service.hierarchy.HierarchyService hierarchyService;

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
     * Accrue interest on every IHB Current Account daily at midnight, using
     * each account's own effectiveCreditRate/effectiveDebitRate. Cash-
     * concentration sweeps (SweepService) and POBO/COBO both move these same
     * accounts, so this is the one interest engine that covers all of them —
     * previously implemented but only reachable via a manual POST endpoint,
     * never actually scheduled.
     */
    @Scheduled(cron = "0 0 0 * * *") // Midnight
    @Async("taskExecutor")
    public void calculateDailyIhbCurrentAccountInterest() {
        log.info("Starting daily IHB Current Account interest calculation at {}", LocalDateTime.now());
        try {
            var results = ihbUnifiedService.calculateDailyInterestForCurrentAccounts();
            log.info("Daily IHB Current Account interest calculation completed: {} accounts", results.size());
        } catch (Exception e) {
            log.error("Daily IHB Current Account interest calculation failed", e);
        }
    }

    /**
     * Post the interest accrued above onto each account's actual balance,
     * shortly after it's calculated.
     */
    @Scheduled(cron = "0 30 0 * * *") // 12:30 AM
    @Async("taskExecutor")
    public void postIhbCurrentAccountInterest() {
        log.info("Starting IHB Current Account interest posting at {}", LocalDateTime.now());
        try {
            var results = ihbUnifiedService.postInterestForCurrentAccounts();
            log.info("IHB Current Account interest posting completed: {} accounts", results.size());
        } catch (Exception e) {
            log.error("IHB Current Account interest posting failed", e);
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
     * Give every program that still lacks a hierarchy its root, once.
     *
     * Program creation now always bootstraps a tree, but programs created before
     * that -- with hierarchyEnabled off, through the wallet route, or where the old
     * bootstrap failed silently -- have no root, and a program without one cannot
     * take a parent-node account. Each program is its own transaction, so one
     * failure is logged and the rest still run.
     *
     * ponytail: runs on every startup; after the first successful pass the query
     * returns nothing. Move to a migration-style one-shot if startup cost matters.
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void bootstrapMissingHierarchies() {
        for (var program : programRepository.findByRootHierarchyNodeIdIsNull()) {
            try {
                hierarchyService.bootstrap(program);
                log.info("Bootstrapped hierarchy for existing program {}", program.getProgramCode());
            } catch (Exception e) {
                log.error("Could not bootstrap hierarchy for program {}: {}", program.getProgramCode(), e.getMessage());
            }
        }
    }

    /**
     * Roll the spend and topup usage counters over at the start of each period.
     *
     * The bulk reset queries have been on VirtualAccountRepository all along
     * with no caller — the only resets were per-account REST endpoints, so the
     * counters accumulated for the life of an account and a "daily" limit
     * behaved as a lifetime one. Same shape as the IHB interest job above:
     * implemented, reachable by hand, never actually scheduled.
     *
     * Each job runs a few minutes past its boundary so it cannot race a
     * transaction posted at exactly midnight.
     */
    @Scheduled(cron = "0 5 0 * * *") // 00:05 daily
    @Async("taskExecutor")
    public void resetDailyLimits() {
        runLimitReset("daily", virtualAccountService::resetAllDailyLimits);
    }

    @Scheduled(cron = "0 10 0 * * MON") // 00:10 Monday
    @Async("taskExecutor")
    public void resetWeeklyLimits() {
        runLimitReset("weekly", virtualAccountService::resetAllWeeklyLimits);
    }

    @Scheduled(cron = "0 15 0 1 * *") // 00:15 on the 1st
    @Async("taskExecutor")
    public void resetMonthlyLimits() {
        runLimitReset("monthly", virtualAccountService::resetAllMonthlyLimits);
    }

    @Scheduled(cron = "0 20 0 1 1 *") // 00:20 on 1 January
    @Async("taskExecutor")
    public void resetAnnualLimits() {
        runLimitReset("annual", virtualAccountService::resetAllAnnualLimits);
    }

    private void runLimitReset(String period, java.util.function.IntSupplier reset) {
        try {
            int affected = reset.getAsInt();
            log.info("Reset {} usage counters on {} accounts", period, affected);
        } catch (Exception e) {
            log.error("Failed to reset {} usage counters", period, e);
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
