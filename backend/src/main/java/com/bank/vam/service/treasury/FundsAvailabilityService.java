package com.bank.vam.service.treasury;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.*;
import com.bank.vam.entity.credit.CreditLimit;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.credit.CreditLimitRepository;
import com.bank.vam.service.credit.CreditLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * FundsAvailabilityService - ⭐ THE CRITICAL SERVICE ⭐
 * 
 * UNIFIED ARCHITECTURE v4.2:
 * Full hierarchy-aware funds availability check for debits.
 * 
 * The Two Questions:
 * 1. BALANCE HIERARCHY: Do you HAVE the funds?
 * 2. LIMIT HIERARCHY: Are you ALLOWED to spend?
 * 
 * Check Flow for Debit Request:
 * ┌───────────────────────────────────────────────────────────────┐
 * │                 FundsAvailabilityService                      │
 * │                                                               │
 * │  FOR EACH LEVEL IN HIERARCHY (VA → ROOT):                    │
 * │                                                               │
 * │  Level 0: UK-PAYABLES (VA)                                   │
 * │    Balance: €200K + Internal Limit: €500K = €700K ✓          │
 * │                                                               │
 * │  Level 1: MIRROR-EUR                                         │
 * │    Balance: €18M + Limit: €0 = €18M ✓                        │
 * │                                                               │
 * │  Level 2: SHADOW-EUR                                         │
 * │    Bank: €20M + External Limit: €30M = €50M ✓                │
 * │                                                               │
 * │  Level 3: ROOT                                               │
 * │    Aggregated: €45.2M + Master: €50M = €95.2M ✓              │
 * │                                                               │
 * │  ALL LEVELS PASS → DEBIT APPROVED                            │
 * └───────────────────────────────────────────────────────────────┘
 * 
 * Features:
 * - Full hierarchy traversal (VA to ROOT)
 * - Balance + Credit Limit combination
 * - FX conversion at each level
 * - Detailed rejection reasons
 * - Limit utilization tracking
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundsAvailabilityService {

    private final VirtualAccountRepository vaRepository;
    private final CreditLimitRepository limitRepository;
    private final CreditLimitService creditLimitService;
    private final FxRateService fxRateService;

    // Maximum hierarchy depth to prevent infinite loops
    private static final int MAX_HIERARCHY_DEPTH = 10;

    // ========================================================================
    // MAIN FUNDS CHECK
    // ========================================================================

    /**
     * Check if funds are available for a debit at all hierarchy levels.
     * This is THE critical check that must pass before any debit.
     * 
     * @param vaId Source VA for debit
     * @param amount Amount to debit
     * @param currency Currency of debit
     * @return FundsCheckResult with approval status and details
     */
    @Transactional(readOnly = true)
    public FundsCheckResult checkFundsAvailability(UUID vaId, BigDecimal amount, String currency) {
        log.info("Checking funds availability: VA={}, Amount={} {}", vaId, amount, currency);

        FundsCheckResult result = new FundsCheckResult();
        result.setVaId(vaId);
        result.setRequestedAmount(amount);
        result.setRequestedCurrency(currency);
        result.setCheckedAt(LocalDateTime.now());
        result.setLevelResults(new ArrayList<>());

        // Get the source VA
        VirtualAccount va = vaRepository.findById(vaId).orElse(null);
        if (va == null) {
            result.setApproved(false);
            result.setRejectionReason("Virtual account not found: " + vaId);
            result.setRejectionLevel(-1);
            return result;
        }

        // Check if VA is active
        if (va.getStatus() != VaStatus.ACTIVE) {
            result.setApproved(false);
            result.setRejectionReason("Virtual account is not active: " + va.getStatus());
            result.setRejectionLevel(0);
            return result;
        }

        // Start the hierarchy check
        int level = 0;
        VirtualAccount current = va;
        BigDecimal amountInCurrentCurrency = amount;
        String currentCurrency = currency;

        // IHB Current Accounts: Only check at level 0 (the account itself with Treasury credit limit)
        // IHB accounts are funded by Treasury's credit facility, NOT by parent hierarchy balances.
        // The parent aggregation VA is structural only - it doesn't hold funds for IHB operations.
        boolean isIhbAccount = Boolean.TRUE.equals(va.getIhbParticipant());
        if (isIhbAccount) {
            log.info("IHB Current Account detected: {} - checking only at account level (no hierarchy traversal)",
                va.getVaNumber());
        }

        while (current != null && level < MAX_HIERARCHY_DEPTH) {
            // Check funds at this level
            LevelCheckResult levelResult = checkLevelFunds(current, amountInCurrentCurrency, currentCurrency, level);
            result.getLevelResults().add(levelResult);

            if (!levelResult.isApproved()) {
                // Failed at this level
                result.setApproved(false);
                result.setRejectionLevel(level);
                result.setRejectionReason(levelResult.getRejectionReason());
                result.setRejectionVaId(current.getId());
                result.setRejectionVaNumber(current.getVaNumber());

                log.warn("Funds check REJECTED at level {}: {} - {}",
                    level, current.getVaNumber(), levelResult.getRejectionReason());
                return result;
            }

            // IHB Current Accounts: Stop after level 0 - no hierarchy traversal needed
            // IHB accounts have their own credit limit from Treasury, independent of parent VA balance
            if (isIhbAccount && level == 0) {
                log.info("IHB Current Account {} approved at level 0 with credit limit - skipping parent hierarchy check",
                    va.getVaNumber());
                break;
            }

            // Move to parent
            if (current.getParentAccountId() == null) {
                // Reached ROOT or no parent
                break;
            }

            VirtualAccount parent = vaRepository.findById(current.getParentAccountId()).orElse(null);
            if (parent == null) {
                break;
            }

            // Convert amount to parent's currency if different
            if (!current.getCurrencyCode().equals(parent.getCurrencyCode())) {
                amountInCurrentCurrency = fxRateService.convert(
                    amountInCurrentCurrency,
                    current.getCurrencyCode(),
                    parent.getCurrencyCode()
                );
                currentCurrency = parent.getCurrencyCode();
            }

            current = parent;
            level++;
        }

        // All levels passed
        result.setApproved(true);
        result.setRejectionLevel(-1);
        
        log.info("Funds check APPROVED: VA={}, Amount={} {}, Levels checked={}", 
            vaId, amount, currency, result.getLevelResults().size());
        
        return result;
    }

    /**
     * Check funds at a single hierarchy level.
     */
    private LevelCheckResult checkLevelFunds(VirtualAccount va, BigDecimal amount, 
                                              String requestedCurrency, int level) {
        LevelCheckResult result = new LevelCheckResult();
        result.setLevel(level);
        result.setVaId(va.getId());
        result.setVaNumber(va.getVaNumber());
        result.setVaCurrency(va.getCurrencyCode());
        result.setAccountCategory(va.getAccountCategory());
        
        // Convert amount to VA's currency if different
        BigDecimal amountInVaCurrency = amount;
        if (!requestedCurrency.equals(va.getCurrencyCode())) {
            amountInVaCurrency = fxRateService.convert(amount, requestedCurrency, va.getCurrencyCode());
        }
        result.setRequestedAmountInVaCurrency(amountInVaCurrency);

        // Get balance based on account type
        BigDecimal balance = getEffectiveBalance(va);
        result.setBalance(balance);

        // Get credit limits
        BigDecimal externalLimit = getExternalLimitAvailable(va);
        BigDecimal internalLimit = getInternalLimitAvailable(va);
        result.setExternalLimitAvailable(externalLimit);
        result.setInternalLimitAvailable(internalLimit);

        // Calculate total available
        BigDecimal totalAvailable = balance.add(externalLimit).add(internalLimit);
        result.setTotalAvailable(totalAvailable);

        // Check sufficiency
        if (totalAvailable.compareTo(amountInVaCurrency) >= 0) {
            result.setApproved(true);
            
            // Determine limit usage
            if (balance.compareTo(amountInVaCurrency) >= 0) {
                result.setLimitUsageRequired(BigDecimal.ZERO);
            } else {
                result.setLimitUsageRequired(amountInVaCurrency.subtract(balance));
            }
        } else {
            result.setApproved(false);
            result.setRejectionReason(String.format(
                "Insufficient funds at %s: Available=%s, Required=%s",
                va.getVaNumber(), totalAvailable, amountInVaCurrency
            ));
            result.setShortfall(amountInVaCurrency.subtract(totalAvailable));
        }

        return result;
    }

    /**
     * Get effective balance for a VA based on its type.
     */
    private BigDecimal getEffectiveBalance(VirtualAccount va) {
        // Physical mirrors use bank balance
        if (va.isPhysicalMirror()) {
            return va.getBankBalance() != null ? va.getBankBalance() : BigDecimal.ZERO;
        }
        
        // Currency mirrors use mirror balance
        if (va.isCurrencyMirror()) {
            return va.getMirrorBalance() != null ? va.getMirrorBalance() : BigDecimal.ZERO;
        }
        
        // Aggregation/ROOT nodes use aggregated balance
        if (va.isAggregationNode() || va.isRootNode()) {
            return va.getAggregatedBalance() != null ? va.getAggregatedBalance() : BigDecimal.ZERO;
        }
        
        // Regular VAs use current balance
        return va.getCurrentBalance() != null ? va.getCurrentBalance() : BigDecimal.ZERO;
    }

    /**
     * Get available external credit limit for a VA.
     */
    private BigDecimal getExternalLimitAvailable(VirtualAccount va) {
        if (va.getExternalLimitId() == null) {
            return BigDecimal.ZERO;
        }
        
        CreditLimit limit = limitRepository.findById(va.getExternalLimitId()).orElse(null);
        if (limit == null || !limit.isActive()) {
            return BigDecimal.ZERO;
        }
        
        return limit.getAvailableAmount() != null ? limit.getAvailableAmount() : BigDecimal.ZERO;
    }

    /**
     * Get available internal credit limit for a VA.
     *
     * For IHB Current Accounts (ihbParticipant=true), the credit limit is stored
     * directly on the VA (creditLimitAvailable/effectiveCreditLimit) rather than
     * in a separate CreditLimit entity. This is the IHB overdraft facility from Treasury.
     *
     * For regular VAs, the credit limit comes from a linked CreditLimit entity.
     */
    private BigDecimal getInternalLimitAvailable(VirtualAccount va) {
        // IHB Current Accounts: Use VA's built-in credit limit (from Treasury)
        // This is the IHB overdraft facility - subsidiaries can go negative up to this limit
        log.info("getInternalLimitAvailable: VA={}, ihbParticipant={}, creditLimitAvailable={}, effectiveCreditLimit={}",
            va.getVaNumber(), va.getIhbParticipant(), va.getCreditLimitAvailable(), va.getEffectiveCreditLimit());

        if (Boolean.TRUE.equals(va.getIhbParticipant())) {
            BigDecimal ihbCreditLimit = va.getCreditLimitAvailable();
            if (ihbCreditLimit == null || ihbCreditLimit.compareTo(BigDecimal.ZERO) == 0) {
                ihbCreditLimit = va.getEffectiveCreditLimit();
                log.info("IHB Account {}: creditLimitAvailable was null/zero, using effectiveCreditLimit={}",
                    va.getVaNumber(), ihbCreditLimit);
            }
            if (ihbCreditLimit != null && ihbCreditLimit.compareTo(BigDecimal.ZERO) > 0) {
                log.info("IHB Current Account {} returning credit limit: {}", va.getVaNumber(), ihbCreditLimit);
                return ihbCreditLimit;
            }
            log.warn("IHB Account {} has NO credit limit! creditLimitAvailable={}, effectiveCreditLimit={}",
                va.getVaNumber(), va.getCreditLimitAvailable(), va.getEffectiveCreditLimit());
        }

        // Regular VAs: Check linked CreditLimit entity
        if (va.getInternalLimitId() == null) {
            return BigDecimal.ZERO;
        }

        CreditLimit limit = limitRepository.findById(va.getInternalLimitId()).orElse(null);
        if (limit == null || !limit.isActive()) {
            return BigDecimal.ZERO;
        }

        return limit.getAvailableAmount() != null ? limit.getAvailableAmount() : BigDecimal.ZERO;
    }

    // ========================================================================
    // LIMIT UTILIZATION
    // ========================================================================

    /**
     * Update limit utilization after a successful debit.
     * Called by TransactionService after debit is approved and executed.
     *
     * For IHB Current Accounts: Updates VA-level creditLimitUtilized/creditLimitAvailable directly.
     * For Regular VAs: Updates linked CreditLimit entities.
     *
     * @param vaId VA that was debited
     * @param amount Amount debited
     */
    @Transactional
    public void updateLimitUtilization(UUID vaId, BigDecimal amount) {
        VirtualAccount va = vaRepository.findById(vaId)
            .orElseThrow(() -> new ResourceNotFoundException("VA not found: " + vaId));

        BigDecimal balance = va.getCurrentBalance() != null ? va.getCurrentBalance() : BigDecimal.ZERO;

        // If balance went negative, we used credit limit
        if (balance.compareTo(BigDecimal.ZERO) < 0) {
            BigDecimal overdraftUsed = balance.abs();

            // IHB Current Accounts: Update VA-level credit limit fields directly
            if (Boolean.TRUE.equals(va.getIhbParticipant())) {
                BigDecimal effectiveLimit = va.getEffectiveCreditLimit() != null
                    ? va.getEffectiveCreditLimit() : BigDecimal.ZERO;
                BigDecimal currentUtilized = va.getCreditLimitUtilized() != null
                    ? va.getCreditLimitUtilized() : BigDecimal.ZERO;

                // Calculate new utilization (capped at effective limit)
                BigDecimal newUtilized = currentUtilized.add(overdraftUsed).min(effectiveLimit);
                BigDecimal newAvailable = effectiveLimit.subtract(newUtilized);

                va.setCreditLimitUtilized(newUtilized);
                va.setCreditLimitAvailable(newAvailable);
                vaRepository.save(va);

                log.info("Updated IHB limit utilization for {}: Utilized={}, Available={}, EffectiveLimit={}",
                    va.getVaNumber(), newUtilized, newAvailable, effectiveLimit);
                return;
            }

            // Regular VAs: Use CreditLimit entities
            BigDecimal internalUsed = BigDecimal.ZERO;
            BigDecimal externalUsed = BigDecimal.ZERO;

            if (va.getInternalLimitId() != null) {
                CreditLimit intLimit = limitRepository.findById(va.getInternalLimitId()).orElse(null);
                if (intLimit != null && intLimit.isActive()) {
                    BigDecimal available = intLimit.getAvailableAmount();
                    internalUsed = overdraftUsed.min(available);
                    if (internalUsed.compareTo(BigDecimal.ZERO) > 0) {
                        creditLimitService.utilizeLimit(va.getInternalLimitId(), internalUsed);
                        overdraftUsed = overdraftUsed.subtract(internalUsed);
                    }
                }
            }

            if (overdraftUsed.compareTo(BigDecimal.ZERO) > 0 && va.getExternalLimitId() != null) {
                CreditLimit extLimit = limitRepository.findById(va.getExternalLimitId()).orElse(null);
                if (extLimit != null && extLimit.isActive()) {
                    externalUsed = overdraftUsed;
                    creditLimitService.utilizeLimit(va.getExternalLimitId(), externalUsed);
                }
            }

            // Update VA's utilized amount
            va.setCreditLimitUtilized(internalUsed.add(externalUsed));
            va.recalculateCreditAvailable();
            vaRepository.save(va);

            log.info("Updated limit utilization for {}: Internal={}, External={}",
                va.getVaNumber(), internalUsed, externalUsed);
        }
    }

    /**
     * Release limit utilization after a credit.
     *
     * For IHB Current Accounts: Updates VA-level creditLimitUtilized/creditLimitAvailable directly.
     * For Regular VAs: Updates linked CreditLimit entities.
     *
     * @param vaId VA that was credited
     * @param amount Amount credited
     */
    @Transactional
    public void releaseLimitUtilization(UUID vaId, BigDecimal amount) {
        VirtualAccount va = vaRepository.findById(vaId)
            .orElseThrow(() -> new ResourceNotFoundException("VA not found: " + vaId));

        BigDecimal utilized = va.getCreditLimitUtilized();
        if (utilized == null || utilized.compareTo(BigDecimal.ZERO) == 0) {
            return; // Nothing to release
        }

        BigDecimal toRelease = amount.min(utilized);

        // IHB Current Accounts: Update VA-level credit limit fields directly
        if (Boolean.TRUE.equals(va.getIhbParticipant())) {
            BigDecimal effectiveLimit = va.getEffectiveCreditLimit() != null
                ? va.getEffectiveCreditLimit() : BigDecimal.ZERO;
            BigDecimal newUtilized = utilized.subtract(toRelease).max(BigDecimal.ZERO);
            BigDecimal newAvailable = effectiveLimit.subtract(newUtilized);

            va.setCreditLimitUtilized(newUtilized);
            va.setCreditLimitAvailable(newAvailable);
            vaRepository.save(va);

            log.info("Released IHB limit for {}: Released={}, NewUtilized={}, NewAvailable={}",
                va.getVaNumber(), toRelease, newUtilized, newAvailable);
            return;
        }

        // Regular VAs: Release from external first (LIFO), then internal
        BigDecimal remaining = toRelease;

        if (va.getExternalLimitId() != null && remaining.compareTo(BigDecimal.ZERO) > 0) {
            CreditLimit extLimit = limitRepository.findById(va.getExternalLimitId()).orElse(null);
            if (extLimit != null) {
                BigDecimal extUtilized = extLimit.getUtilizedAmount();
                BigDecimal extRelease = remaining.min(extUtilized);
                if (extRelease.compareTo(BigDecimal.ZERO) > 0) {
                    creditLimitService.releaseLimit(va.getExternalLimitId(), extRelease);
                    remaining = remaining.subtract(extRelease);
                }
            }
        }

        if (va.getInternalLimitId() != null && remaining.compareTo(BigDecimal.ZERO) > 0) {
            CreditLimit intLimit = limitRepository.findById(va.getInternalLimitId()).orElse(null);
            if (intLimit != null) {
                BigDecimal intRelease = remaining.min(intLimit.getUtilizedAmount());
                if (intRelease.compareTo(BigDecimal.ZERO) > 0) {
                    creditLimitService.releaseLimit(va.getInternalLimitId(), intRelease);
                }
            }
        }

        // Update VA
        va.setCreditLimitUtilized(utilized.subtract(toRelease));
        va.recalculateCreditAvailable();
        vaRepository.save(va);

        log.info("Released limit utilization for {}: {}", va.getVaNumber(), toRelease);
    }

    // ========================================================================
    // QUICK CHECKS
    // ========================================================================

    /**
     * Quick balance check (single level, no hierarchy).
     */
    @Transactional(readOnly = true)
    public boolean hasSimpleBalance(UUID vaId, BigDecimal amount) {
        VirtualAccount va = vaRepository.findById(vaId).orElse(null);
        if (va == null) return false;
        
        BigDecimal available = va.getAvailableBalance() != null ? va.getAvailableBalance() : BigDecimal.ZERO;
        return available.compareTo(amount) >= 0;
    }

    /**
     * Quick check with limit (single level).
     */
    @Transactional(readOnly = true)
    public boolean hasFundsWithLimit(UUID vaId, BigDecimal amount) {
        VirtualAccount va = vaRepository.findById(vaId).orElse(null);
        if (va == null) return false;
        
        BigDecimal totalAvailable = va.getTotalAvailableWithLimit();
        return totalAvailable.compareTo(amount) >= 0;
    }

    /**
     * Get total available funds for a VA (balance + limits).
     */
    @Transactional(readOnly = true)
    public BigDecimal getTotalAvailableFunds(UUID vaId) {
        VirtualAccount va = vaRepository.findById(vaId).orElse(null);
        if (va == null) return BigDecimal.ZERO;
        
        BigDecimal balance = getEffectiveBalance(va);
        BigDecimal externalLimit = getExternalLimitAvailable(va);
        BigDecimal internalLimit = getInternalLimitAvailable(va);
        
        return balance.add(externalLimit).add(internalLimit);
    }

    // ========================================================================
    // RESULT CLASSES
    // ========================================================================

    /**
     * Result of a full funds availability check.
     */
    @lombok.Data
    public static class FundsCheckResult {
        private UUID vaId;
        private BigDecimal requestedAmount;
        private String requestedCurrency;
        private LocalDateTime checkedAt;
        
        private boolean approved;
        private int rejectionLevel = -1;
        private String rejectionReason;
        private UUID rejectionVaId;
        private String rejectionVaNumber;
        
        private List<LevelCheckResult> levelResults;
    }

    /**
     * Result of a single level check.
     */
    @lombok.Data
    public static class LevelCheckResult {
        private int level;
        private UUID vaId;
        private String vaNumber;
        private String vaCurrency;
        private AccountCategory accountCategory;
        
        private BigDecimal requestedAmountInVaCurrency;
        private BigDecimal balance;
        private BigDecimal externalLimitAvailable;
        private BigDecimal internalLimitAvailable;
        private BigDecimal totalAvailable;
        
        private boolean approved;
        private String rejectionReason;
        private BigDecimal shortfall;
        private BigDecimal limitUsageRequired;
    }
}