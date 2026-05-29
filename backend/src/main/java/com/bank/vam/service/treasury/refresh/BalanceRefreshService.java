package com.bank.vam.service.treasury.refresh;

import com.bank.vam.config.MultiBankProperties;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.entity.VirtualAccount.BalanceDataSource;
import com.bank.vam.entity.VirtualAccount.BalanceRefreshStatus;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.service.treasury.refresh.ShadowBalanceAdapter.RefreshResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates balance refreshes for PHYSICAL_MIRROR shadow accounts.
 *
 * Looks up the right {@link ShadowBalanceAdapter} by {@link BalanceDataSource},
 * applies the result to the shadow, and stamps {@code lastBalanceRefreshAt} +
 * {@code lastBalanceRefreshStatus}. Falls back to {@link StubAdapter} when no
 * dedicated adapter is registered for the source (v1: everything except
 * CORE_BANKING).
 *
 * Scheduled job {@link #refreshAllStaleShadows()} runs every
 * {@code vam.multi-bank.refresh-cadence-minutes} and refreshes any shadow whose
 * freshness threshold has been exceeded.
 */
@Slf4j
@Service
public class BalanceRefreshService {

    private final VirtualAccountRepository vaRepository;
    private final MultiBankProperties multiBankProperties;
    private final StubAdapter stub;
    private final Map<BalanceDataSource, ShadowBalanceAdapter> adapters =
            new EnumMap<>(BalanceDataSource.class);

    public BalanceRefreshService(VirtualAccountRepository vaRepository,
                                 MultiBankProperties multiBankProperties,
                                 List<ShadowBalanceAdapter> dedicatedAdapters,
                                 StubAdapter stub) {
        this.vaRepository = vaRepository;
        this.multiBankProperties = multiBankProperties;
        this.stub = stub;
        for (ShadowBalanceAdapter a : dedicatedAdapters) {
            // Only index real adapters; the stub is the fallback (skip self-registration).
            if (a != stub) {
                adapters.put(a.source(), a);
            }
        }
        log.info("BalanceRefreshService initialized with adapters for: {}", adapters.keySet());
    }

    /**
     * Refresh a single shadow regardless of freshness state.
     */
    @Transactional
    public BalanceRefreshStatus refresh(UUID shadowVaId) {
        VirtualAccount shadow = vaRepository.findById(shadowVaId)
                .orElseThrow(() -> new ResourceNotFoundException("Shadow VA not found: " + shadowVaId));
        return refresh(shadow);
    }

    /**
     * Refresh only if last refresh is older than the shadow's freshness threshold.
     * Returns the resulting status (or NEVER if no refresh attempt was made because the
     * shadow is still fresh).
     */
    @Transactional
    public BalanceRefreshStatus refreshIfStale(UUID shadowVaId) {
        VirtualAccount shadow = vaRepository.findById(shadowVaId)
                .orElseThrow(() -> new ResourceNotFoundException("Shadow VA not found: " + shadowVaId));
        if (!isStale(shadow)) {
            return shadow.getLastBalanceRefreshStatus() != null
                    ? shadow.getLastBalanceRefreshStatus()
                    : BalanceRefreshStatus.NEVER;
        }
        return refresh(shadow);
    }

    /**
     * Scheduled refresh of all stale PHYSICAL_MIRROR shadows.
     * Cadence comes from {@code vam.multi-bank.refresh-cadence-minutes} (default 15).
     */
    @Scheduled(fixedRateString = "#{${vam.multi-bank.refresh-cadence-minutes:15} * 60 * 1000}")
    @Async("taskExecutor")
    public void refreshAllStaleShadows() {
        List<VirtualAccount> shadows = vaRepository.findByAccountCategory(AccountCategory.PHYSICAL_MIRROR);
        int attempted = 0;
        int succeeded = 0;
        int failed = 0;
        int skipped = 0;
        for (VirtualAccount shadow : shadows) {
            if (!isStale(shadow)) {
                skipped++;
                continue;
            }
            attempted++;
            try {
                BalanceRefreshStatus result = refresh(shadow);
                if (result == BalanceRefreshStatus.SUCCESS) {
                    succeeded++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                failed++;
                log.error("Unhandled refresh error for shadow {}: {}", shadow.getVaNumber(), e.getMessage(), e);
            }
        }
        log.info("Scheduled shadow refresh: attempted={}, succeeded={}, failed={}, skipped={}",
                attempted, succeeded, failed, skipped);
    }

    // ============================================================================
    // INTERNAL
    // ============================================================================

    private BalanceRefreshStatus refresh(VirtualAccount shadow) {
        if (shadow.getAccountCategory() != AccountCategory.PHYSICAL_MIRROR) {
            log.debug("Skipping refresh for non-mirror VA {} ({})",
                    shadow.getVaNumber(), shadow.getAccountCategory());
            return null;
        }

        ShadowBalanceAdapter adapter = resolveAdapter(shadow.getBalanceDataSource());
        RefreshResult result;
        try {
            result = adapter.fetch(shadow);
        } catch (Exception e) {
            log.error("Adapter {} threw for shadow {}: {}",
                    adapter.getClass().getSimpleName(), shadow.getVaNumber(), e.getMessage(), e);
            result = RefreshResult.fail("Adapter threw: " + e.getMessage());
        }

        shadow.setLastBalanceRefreshAt(LocalDateTime.now());
        if (result.success()) {
            if (result.bankBalance() != null) {
                shadow.setBankBalance(result.bankBalance());
            }
            if (result.bankAvailableBalance() != null) {
                shadow.setBankAvailableBalance(result.bankAvailableBalance());
            }
            if (result.asOf() != null) {
                shadow.setBankBalanceAt(result.asOf());
            }
            shadow.setLastBalanceRefreshStatus(BalanceRefreshStatus.SUCCESS);
        } else {
            shadow.setLastBalanceRefreshStatus(BalanceRefreshStatus.FAILED);
            log.warn("Refresh failed for shadow {} ({}): {}",
                    shadow.getVaNumber(), shadow.getBalanceDataSource(), result.errorMessage());
        }
        vaRepository.save(shadow);
        return shadow.getLastBalanceRefreshStatus();
    }

    private ShadowBalanceAdapter resolveAdapter(BalanceDataSource source) {
        if (source == null) {
            return stub;
        }
        return adapters.getOrDefault(source, stub);
    }

    private boolean isStale(VirtualAccount shadow) {
        if (shadow.getLastBalanceRefreshAt() == null) {
            return true;
        }
        int threshold = shadow.getFreshnessThresholdMinutes() != null
                ? shadow.getFreshnessThresholdMinutes()
                : multiBankProperties.getDefaultFreshnessThresholdMinutes();
        return Duration.between(shadow.getLastBalanceRefreshAt(), LocalDateTime.now())
                .toMinutes() >= threshold;
    }
}
