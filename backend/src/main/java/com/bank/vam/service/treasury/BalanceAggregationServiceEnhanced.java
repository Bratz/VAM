package com.bank.vam.service.treasury;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.*;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.VirtualAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * BalanceAggregationServiceEnhanced - Real-time balance rollup service.
 *
 * =============================================================================
 * VERSION HISTORY:
 * v4.4.0 - Corrected for sibling-based Currency Mirrors
 * v5.6.0 - Removed Currency Mirror update at ancestor levels in propagateToAncestors
 * v5.6.1 - Currency-wise propagation: Mirrors aggregate nested mirrors, not AGGREGATIONs
 * v5.7.0 - Option C: Trigger async full recalc after each transaction
 * =============================================================================
 *
 * CORRECTED FOR SIBLING-BASED CURRENCY MIRRORS (v4.4.0)
 * =============================================================================
 * 
 * KEY CONCEPT: Currency Mirrors are SIBLINGS of Transaction VAs, NOT parents!
 * 
 * HIERARCHY STRUCTURE:
 * ROOT (baseCurrency: EUR)
 * ├── MIRROR-EUR ← Sibling, summarizes all EUR VAs under ROOT
 * ├── MIRROR-USD ← Sibling, summarizes all USD VAs under ROOT
 * ├── SHADOW-EUR ← Sibling
 * │
 * └── AGGREGATION-OPERATIONS
 *     ├── MIRROR-EUR ← Sibling, summarizes EUR VAs under OPERATIONS
 *     ├── MIRROR-USD ← Sibling, summarizes USD VAs under OPERATIONS
 *     ├── VA-EUR-001 ← Transaction VA (sibling of mirrors)
 *     ├── VA-EUR-002 ← Transaction VA (sibling of mirrors)
 *     └── VA-USD-001 ← Transaction VA (sibling of mirrors)
 * 
 * AGGREGATION LOGIC:
 * 1. CURRENCY_MIRROR.mirrorBalance = SUM of same-currency SIBLING Transaction VAs
 * 2. CURRENCY_MIRROR.balanceInBase = mirrorBalance × fxRate
 * 3. AGGREGATION.aggregatedBalance = SUM of CURRENCY_MIRROR.balanceInBase values
 * 4. ROOT.aggregatedBalance = SUM of all children's converted balances
 * 
 * =============================================================================
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceAggregationServiceEnhanced {

    private final VirtualAccountRepository vaRepository;
    private final FxRateService fxRateService;

    /**
     * Credit limit service for updating utilization when balance changes.
     * Utilization = amount of credit being used (negative balance draws on credit).
     */
    @Lazy
    @Autowired
    private com.bank.vam.service.credit.CreditLimitService creditLimitService;

    /**
     * Self-injection for calling @Async methods from within the same class.
     * Spring's proxy mechanism requires calling through the proxy for @Async to work.
     */
    @Lazy
    @Autowired
    private BalanceAggregationServiceEnhanced self;

    // ========================================================================
    // REAL-TIME BALANCE PROPAGATION
    // ========================================================================

    /**
     * Propagate balance change up the VA hierarchy in real-time.
     * Called immediately after a transaction on a VA.
     *
     * FLOW (v5.7.0 - Option C: Full Recalc on Transaction):
     * 1. Update the VA's Currency Mirror (sibling) at same level
     * 2. Propagate to parent AGGREGATION/ROOT (update aggregatedBalance)
     * 3. NEW: Trigger asynchronous full recalculation to update ALL Currency Mirrors
     *    at ALL levels (ensures M1 EUR at ROOT includes M3 EUR from nested AGGREGATION)
     *
     * WHY ASYNC FULL RECALC?
     * - Real-time propagation only updates immediate sibling mirror and AGGREGATION totals
     * - It does NOT update ancestor Currency Mirrors (currency-wise propagation)
     * - Full recalc ensures all Currency Mirrors at all levels reflect correct totals
     * - Running async prevents blocking the transaction response
     *
     * @param vaId The VA that had a balance change
     * @param balanceDelta The amount of change (positive for credit, negative for debit)
     */
    @Transactional
    public void propagateBalanceChange(UUID vaId, BigDecimal balanceDelta) {
        if (balanceDelta == null || balanceDelta.compareTo(BigDecimal.ZERO) == 0) {
            log.debug("Skipping propagation: delta is null or zero");
            return;
        }

        VirtualAccount va = vaRepository.findById(vaId).orElse(null);
        if (va == null) {
            log.warn("VA not found for balance propagation: {}", vaId);
            return;
        }

        log.info("Balance propagation starting for VA {} ({}): delta={}, parentId={}",
            va.getVaNumber(), va.getAccountCategory(), balanceDelta, va.getParentAccountId());

        String currency = va.getCurrencyCode();
        UUID parentId = va.getParentAccountId();

        // Step 1: Update Currency Mirror at VA's level (sibling)
        if (parentId != null) {
            updateCurrencyMirrorAtLevel(parentId, currency, balanceDelta);
        } else {
            log.info("VA {} has no parent - skipping currency mirror update", va.getVaNumber());
        }

        // Step 2: Propagate up through parent chain (updates AGGREGATION/ROOT aggregatedBalance)
        propagateToAncestors(va, balanceDelta);

        // ================================================================
        // Step 3 (v5.7.0): Trigger async full recalculation
        // ================================================================
        // This ensures ALL Currency Mirrors at ALL levels are updated correctly
        // with currency-wise propagation (M3 EUR + M5 EUR → M1 EUR at ROOT)
        //
        // Without this, only the immediate sibling mirror is updated in real-time,
        // and ancestor Currency Mirrors would have stale values until scheduled aggregation.
        UUID corporateId = va.getCorporateId();
        if (corporateId != null) {
            log.info("Triggering async full recalculation for corporate {} after transaction on VA {}",
                corporateId, va.getVaNumber());
            self.fullRefreshAfterTransaction(corporateId, vaId);
        }

        // ================================================================
        // Step 4 (v5.8.0): Update Credit Limit Utilization
        // ================================================================
        // Credit utilization is the amount of credit being used (negative balance).
        // When balance goes more negative, utilization increases.
        // When balance goes less negative (repayment), utilization decreases.
        // This propagates up the credit limit hierarchy (Entity → Regional → Group).
        updateCreditUtilization(va, balanceDelta);
    }

    /**
     * Update credit limit utilization based on balance change.
     *
     * Credit Utilization Logic:
     * - Utilization = amount of overdraft = max(0, -balance)
     * - When balance goes MORE negative → utilization INCREASES
     * - When balance goes LESS negative → utilization DECREASES
     *
     * This method calculates the utilization delta and propagates it through
     * the credit limit hierarchy (Entity Limit → Regional Treasury → Group Limit).
     *
     * @param va The Virtual Account with the balance change
     * @param balanceDelta The balance change (negative = debit, positive = credit)
     */
    private void updateCreditUtilization(VirtualAccount va, BigDecimal balanceDelta) {
        // Only process if VA has a credit limit
        UUID internalLimitId = va.getInternalLimitId();
        if (internalLimitId == null) {
            // Also check entity-level limit
            UUID owningEntityId = va.getOwningEntityId();
            if (owningEntityId == null) {
                return; // No credit limit tracking for this VA
            }
            // Entity-level limit will be handled separately
            updateEntityCreditUtilization(owningEntityId, va.getCurrencyCode(), balanceDelta, va.getCurrentBalance());
            return;
        }

        // Calculate utilization change based on balance change
        BigDecimal currentBalance = va.getCurrentBalance() != null ? va.getCurrentBalance() : BigDecimal.ZERO;
        BigDecimal previousBalance = currentBalance.subtract(balanceDelta); // Balance before this change

        // Utilization = max(0, -balance) = amount of overdraft
        BigDecimal previousUtilization = previousBalance.compareTo(BigDecimal.ZERO) < 0
            ? previousBalance.abs() : BigDecimal.ZERO;
        BigDecimal currentUtilization = currentBalance.compareTo(BigDecimal.ZERO) < 0
            ? currentBalance.abs() : BigDecimal.ZERO;

        BigDecimal utilizationDelta = currentUtilization.subtract(previousUtilization);

        if (utilizationDelta.compareTo(BigDecimal.ZERO) == 0) {
            return; // No change in credit utilization
        }

        log.info("Credit utilization change for VA {}: delta={} (balance {} -> {}, utilization {} -> {})",
            va.getVaNumber(), utilizationDelta, previousBalance, currentBalance,
            previousUtilization, currentUtilization);

        // Record utilization on VA's limit (propagates up hierarchy)
        try {
            creditLimitService.recordUtilization(internalLimitId, utilizationDelta,
                "BALANCE_PROPAGATION:" + va.getVaNumber());
        } catch (Exception e) {
            log.error("Failed to update credit utilization for VA {}: {}", va.getVaNumber(), e.getMessage());
        }
    }

    /**
     * Update entity-level credit utilization.
     * Called when the VA doesn't have its own limit but belongs to an entity with a limit.
     *
     * @param entityId The owning entity ID
     * @param currency The currency code
     * @param balanceDelta The balance change
     * @param newBalance The new balance after the change
     */
    private void updateEntityCreditUtilization(UUID entityId, String currency,
                                                BigDecimal balanceDelta, BigDecimal newBalance) {
        if (entityId == null || currency == null) {
            return;
        }

        try {
            // Calculate utilization delta at entity level
            // Note: This is approximate - full entity utilization would sum all VA balances
            BigDecimal previousBalance = newBalance != null ? newBalance.subtract(balanceDelta) : BigDecimal.ZERO;
            BigDecimal currentBalance = newBalance != null ? newBalance : BigDecimal.ZERO;

            BigDecimal previousUtilization = previousBalance.compareTo(BigDecimal.ZERO) < 0
                ? previousBalance.abs() : BigDecimal.ZERO;
            BigDecimal currentUtilization = currentBalance.compareTo(BigDecimal.ZERO) < 0
                ? currentBalance.abs() : BigDecimal.ZERO;

            BigDecimal utilizationDelta = currentUtilization.subtract(previousUtilization);

            if (utilizationDelta.compareTo(BigDecimal.ZERO) != 0) {
                log.info("Entity credit utilization change for entity {} in {}: delta={}",
                    entityId, currency, utilizationDelta);
                creditLimitService.recordEntityUtilization(entityId, currency, utilizationDelta,
                    "ENTITY_BALANCE_CHANGE");
            }
        } catch (Exception e) {
            log.debug("No credit limit found for entity {} in currency {}: {}",
                entityId, currency, e.getMessage());
        }
    }

    /**
     * Update the Currency Mirror for a specific currency at a specific level.
     * 
     * The Currency Mirror is a SIBLING - it has the same parentId as the Transaction VAs.
     * 
     * @param parentId The common parent of the Currency Mirror and Transaction VAs
     * @param currency The currency to update
     * @param delta The balance change to apply
     */
    private void updateCurrencyMirrorAtLevel(UUID parentId, String currency, BigDecimal delta) {
        // Find the Currency Mirror sibling for this currency
        Optional<VirtualAccount> mirrorOpt = vaRepository.findByParentAccountIdAndAccountCategory(
                parentId, AccountCategory.CURRENCY_MIRROR)
            .stream()
            .filter(m -> currency.equals(m.getCurrencyCode()))
            .findFirst();

        if (mirrorOpt.isEmpty()) {
            log.debug("No Currency Mirror for {} at parent {} - will be created on next aggregation", 
                currency, parentId);
            return;
        }

        VirtualAccount mirror = mirrorOpt.get();
        
        // Update mirror balance
        BigDecimal currentMirrorBalance = mirror.getMirrorBalance() != null ? 
            mirror.getMirrorBalance() : BigDecimal.ZERO;
        BigDecimal newMirrorBalance = currentMirrorBalance.add(delta);
        mirror.setMirrorBalance(newMirrorBalance);

        // Update balance in base currency
        String baseCurrency = mirror.getBaseCurrency();
        if (baseCurrency != null && !baseCurrency.equals(currency)) {
            BigDecimal fxRate = fxRateService.getRate(currency, baseCurrency);
            mirror.setFxRate(fxRate);
            mirror.setFxRateAt(LocalDateTime.now());
            mirror.setBalanceInBase(newMirrorBalance.multiply(fxRate).setScale(4, RoundingMode.HALF_UP));
        } else {
            mirror.setBalanceInBase(newMirrorBalance);
        }

        vaRepository.save(mirror);
        
        log.debug("Updated CURRENCY_MIRROR {} at parent level: {} {} (delta: {})", 
            mirror.getVaNumber(), newMirrorBalance, currency, delta);
    }

    /**
     * Propagate balance delta to all ancestors (AGGREGATION and ROOT nodes).
     */
    private void propagateToAncestors(VirtualAccount va, BigDecimal delta) {
        UUID currentParentId = va.getParentAccountId();
        String sourceCurrency = va.getCurrencyCode();
        LocalDateTime now = LocalDateTime.now();
        int level = 0;
        Set<UUID> visited = new HashSet<>();

        if (currentParentId == null) {
            log.info("VA {} has no parent - cannot propagate to ancestors", va.getVaNumber());
            return;
        }

        log.info("Starting ancestor propagation from VA {} with parentId={}", va.getVaNumber(), currentParentId);

        while (currentParentId != null && level < 10 && !visited.contains(currentParentId)) {
            visited.add(currentParentId);
            level++;

            VirtualAccount parent = vaRepository.findById(currentParentId).orElse(null);
            if (parent == null) {
                log.warn("Parent VA not found: {}", currentParentId);
                break;
            }

            log.info("Processing ancestor L{}: {} (category={})", level, parent.getVaNumber(), parent.getAccountCategory());

            // Only update AGGREGATION or ROOT nodes
            if (parent.getAccountCategory() == AccountCategory.AGGREGATION ||
                parent.getAccountCategory() == AccountCategory.ROOT) {

                // Convert delta to parent's base currency
                String parentBaseCurrency = parent.getBaseCurrency() != null ?
                    parent.getBaseCurrency() : parent.getCurrencyCode();

                BigDecimal convertedDelta = delta;
                if (!sourceCurrency.equals(parentBaseCurrency)) {
                    convertedDelta = fxRateService.convert(delta, sourceCurrency, parentBaseCurrency);
                }

                // Update aggregated balance
                BigDecimal currentAggBalance = parent.getAggregatedBalance() != null ?
                    parent.getAggregatedBalance() : BigDecimal.ZERO;
                BigDecimal newAggBalance = currentAggBalance.add(convertedDelta);
                parent.setAggregatedBalance(newAggBalance);
                parent.setAggregatedBalanceBase(newAggBalance);

                vaRepository.save(parent);

                log.info("Updated {} aggregatedBalance: {} -> {} (delta: {} {})",
                    parent.getVaNumber(), currentAggBalance, newAggBalance, convertedDelta, parentBaseCurrency);
            } else {
                log.info("Skipping {} (category={}) - not AGGREGATION or ROOT", parent.getVaNumber(), parent.getAccountCategory());
            }

            // ================================================================
            // FIX v5.6.0: REMOVED Currency Mirror update at ancestor levels
            // ================================================================
            // Previously, we updated Currency Mirrors at each ancestor level.
            // This was WRONG because Currency Mirrors should ONLY reflect their
            // direct sibling Transaction VAs, not descendants from lower levels.
            //
            // The Currency Mirror at the VA's immediate level is already updated
            // in propagateBalanceChange() Step 1 (before calling this method).
            //
            // Ancestor Currency Mirrors should remain unchanged by descendant
            // transaction changes - they only reflect siblings at their own level.

            // Move up to next parent
            currentParentId = parent.getParentAccountId();
        }

        log.info("Ancestor propagation complete. Processed {} levels", level);
    }

    // ========================================================================
    // SCHEDULED AGGREGATION (CORRECTED FOR SIBLING MODEL)
    // ========================================================================

    /**
     * Scheduled job to recalculate aggregated balances.
     * Runs every 5 minutes by default.
     */
    @Scheduled(fixedRateString = "${vam.aggregation.interval-ms:300000}")
    @Transactional
    public void scheduledAggregation() {
        log.info("Starting scheduled balance aggregation...");

        // Find all ROOT VAs (more efficient query)
        List<VirtualAccount> rootVas = vaRepository.findByAccountCategory(AccountCategory.ROOT);

        if (rootVas.isEmpty()) {
            log.info("No ROOT VAs found - skipping aggregation");
            return;
        }

        log.info("Found {} ROOT VAs to aggregate", rootVas.size());

        int totalAggregated = 0;
        for (VirtualAccount rootVa : rootVas) {
            UUID corporateId = rootVa.getCorporateId();
            if (corporateId == null) {
                log.warn("ROOT VA {} has no corporateId - skipping", rootVa.getVaNumber());
                continue;
            }

            try {
                log.debug("Aggregating for corporate {} (ROOT VA: {})", corporateId, rootVa.getVaNumber());
                int count = aggregateForCorporate(corporateId);
                totalAggregated += count;
            } catch (Exception e) {
                log.error("Error aggregating balances for corporate {}: {}", corporateId, e.getMessage(), e);
            }
        }

        log.info("Completed scheduled aggregation: {} VAs aggregated across {} corporates",
            totalAggregated, rootVas.size());
    }

    /**
     * Aggregate balances for all VAs in a corporate hierarchy.
     * 
     * ORDER OF OPERATIONS (Bottom-Up):
     * 1. First, recalculate all CURRENCY_MIRRORs (sum of same-currency siblings)
     * 2. Then, recalculate AGGREGATION nodes (sum of Currency Mirror balanceInBase)
     * 3. Finally, recalculate ROOT (sum of all children)
     */
    @Transactional
    public int aggregateForCorporate(UUID corporateId) {
        log.debug("Aggregating balances for corporate: {}", corporateId);

        List<VirtualAccount> allVas = vaRepository.findByCorporateId(corporateId);

        if (allVas.isEmpty()) {
            log.warn("No VAs found for corporate {} - nothing to aggregate", corporateId);
            return 0;
        }

        // Log VA distribution by category
        Map<AccountCategory, Long> categoryCount = allVas.stream()
            .filter(va -> va.getAccountCategory() != null)
            .collect(Collectors.groupingBy(VirtualAccount::getAccountCategory, Collectors.counting()));
        log.info("Corporate {} has {} VAs: {}", corporateId, allVas.size(), categoryCount);

        // Sort by hierarchy level descending (deepest first for bottom-up)
        allVas.sort((a, b) -> {
            int levelA = a.getHierarchyLevel() != null ? a.getHierarchyLevel() : 0;
            int levelB = b.getHierarchyLevel() != null ? b.getHierarchyLevel() : 0;
            return Integer.compare(levelB, levelA);
        });

        LocalDateTime now = LocalDateTime.now();
        int aggregated = 0;

        // ================================================================
        // STEP 1: Recalculate all CURRENCY_MIRRORs (deepest first)
        // Currency Mirror = SUM of same-currency SIBLING Transaction VAs
        // ================================================================
        for (VirtualAccount va : allVas) {
            if (va.getAccountCategory() == AccountCategory.CURRENCY_MIRROR) {
                recalculateCurrencyMirror(va, now);
                aggregated++;
            }
        }

        // ================================================================
        // STEP 2: Recalculate AGGREGATION nodes (deepest first)
        // Aggregation = SUM of Currency Mirror balanceInBase values
        // ================================================================
        for (VirtualAccount va : allVas) {
            if (va.getAccountCategory() == AccountCategory.AGGREGATION) {
                recalculateAggregationNode(va, now);
                aggregated++;
            }
        }

        // ================================================================
        // STEP 3: Recalculate ROOT
        // ================================================================
        for (VirtualAccount va : allVas) {
            if (va.getAccountCategory() == AccountCategory.ROOT) {
                recalculateAggregationNode(va, now);
                aggregated++;
            }
        }

        log.info("Aggregated {} hierarchy VAs for corporate {}", aggregated, corporateId);
        return aggregated;
    }

    /**
     * Recalculate a CURRENCY_MIRROR's balance.
     * 
     * CORRECTED LOGIC:
     * mirrorBalance = SUM of all same-currency SIBLING Transaction VAs
     * (Siblings = VAs with the same parentAccountId as this mirror)
     * 
     * @param mirror The Currency Mirror VA to recalculate
     * @param timestamp Current timestamp
     */
    private void recalculateCurrencyMirror(VirtualAccount mirror, LocalDateTime timestamp) {
        UUID parentId = mirror.getParentAccountId();
        String currency = mirror.getCurrencyCode();

        if (parentId == null) {
            log.warn("Currency Mirror {} has no parent - cannot aggregate siblings", mirror.getVaNumber());
            return;
        }

        // ================================================================
        // KEY FIX: Find SIBLING Transaction VAs (same parent, same currency)
        // ================================================================
        List<VirtualAccount> siblings = vaRepository.findByParentAccountId(parentId);

        // Filter operational VAs with same currency
        List<VirtualAccount> operationalSiblings = siblings.stream()
            .filter(this::isOperationalVa)  // TRANSACTION, COLLECTION, DISBURSEMENT
            .filter(va -> currency.equals(va.getCurrencyCode()))  // Same currency
            .toList();

        // Sum their balances
        BigDecimal mirrorBalance = operationalSiblings.stream()
            .map(va -> va.getCurrentBalance() != null ? va.getCurrentBalance() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Log what we're summing
        if (log.isDebugEnabled()) {
            log.debug("Mirror {} ({}) aggregating {} operational siblings: {}",
                mirror.getVaNumber(), currency, operationalSiblings.size(),
                operationalSiblings.stream()
                    .map(va -> va.getVaNumber() + "=" + va.getCurrentBalance())
                    .collect(Collectors.joining(", ")));
        }

        // ================================================================
        // v5.6.1: CURRENCY-WISE PROPAGATION - Aggregate nested Currency Mirrors
        // ================================================================
        // Currency Mirrors propagate CURRENCY-WISE up the hierarchy:
        // - M3 EUR (under A1) + M5 EUR (under A2) → M1 EUR (under ROOT)
        // - M4 USD (under A1) + M6 USD (under A2) → M2 USD (under ROOT)
        //
        // This is different from Aggregation nodes which sum balanceInBase (FX converted).
        // Currency Mirrors maintain the original currency amounts for reporting.
        //
        // To avoid double counting:
        // - Currency Mirrors: sum sibling TXN VAs + nested AGGREGATION's same-currency mirrors
        // - Aggregation/ROOT: ONLY sum direct Currency Mirror balanceInBase (NOT nested AGGREGATIONs)
        BigDecimal nestedMirrorBalance = siblings.stream()
            .filter(va -> va.getAccountCategory() == AccountCategory.AGGREGATION)
            .flatMap(agg -> {
                // Get Currency Mirrors under this nested aggregation
                return vaRepository.findByParentAccountIdAndAccountCategory(
                    agg.getId(), AccountCategory.CURRENCY_MIRROR).stream();
            })
            .filter(m -> currency.equals(m.getCurrencyCode()))
            .map(m -> m.getMirrorBalance() != null ? m.getMirrorBalance() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        mirrorBalance = mirrorBalance.add(nestedMirrorBalance);

        if (nestedMirrorBalance.compareTo(BigDecimal.ZERO) > 0) {
            log.debug("Mirror {} added {} from nested AGGREGATION mirrors",
                mirror.getVaNumber(), nestedMirrorBalance);
        }

        // Update mirror balance
        mirror.setMirrorBalance(mirrorBalance);

        // Convert to base currency
        String baseCurrency = mirror.getBaseCurrency();
        if (baseCurrency != null && !baseCurrency.equals(currency)) {
            BigDecimal fxRate = fxRateService.getRate(currency, baseCurrency);
            mirror.setFxRate(fxRate);
            mirror.setFxRateAt(timestamp);
            mirror.setBalanceInBase(mirrorBalance.multiply(fxRate).setScale(4, RoundingMode.HALF_UP));
        } else {
            mirror.setFxRate(BigDecimal.ONE);
            mirror.setBalanceInBase(mirrorBalance);
        }

        vaRepository.save(mirror);

        log.info("Recalculated CURRENCY_MIRROR {}: mirrorBalance={} {}, balanceInBase={} (from {} operational siblings)",
            mirror.getVaNumber(), mirrorBalance, currency, mirror.getBalanceInBase(), operationalSiblings.size());
    }

    /**
     * Recalculate an AGGREGATION or ROOT node's balance.
     *
     * v5.6.1: CORRECTED LOGIC for Currency-Wise Propagation:
     * aggregatedBalance = SUM of direct Currency Mirror's balanceInBase values ONLY
     *
     * IMPORTANT: Do NOT add nested AGGREGATION's aggregatedBalanceBase because:
     * - Currency Mirrors already aggregate nested Currency Mirrors (currency-wise)
     * - Adding nested AGGREGATION totals would cause DOUBLE COUNTING
     *
     * Flow (per the architecture diagram):
     * - M3 EUR (100) + M5 EUR (150) → M1 EUR (250) [currency-wise propagation]
     * - M4 USD (50) + M6 USD (75) → M2 USD (125) [currency-wise propagation]
     * - ROOT = M1.balanceInBase + M2.balanceInBase = 250 + (125 * FX) [base currency]
     *
     * @param aggregation The AGGREGATION or ROOT VA to recalculate
     * @param timestamp Current timestamp
     */
    private void recalculateAggregationNode(VirtualAccount aggregation, LocalDateTime timestamp) {
        UUID aggregationId = aggregation.getId();
        String baseCurrency = aggregation.getBaseCurrency() != null ? aggregation.getBaseCurrency() : aggregation.getCurrencyCode();

        // Get all direct children
        List<VirtualAccount> children = vaRepository.findByParentAccountId(aggregationId);

        // ================================================================
        // Sum Currency Mirror balanceInBase values (already FX converted)
        // Currency Mirrors already contain aggregated balances from nested levels
        // ================================================================
        List<VirtualAccount> mirrors = children.stream()
            .filter(c -> c.getAccountCategory() == AccountCategory.CURRENCY_MIRROR)
            .toList();

        BigDecimal mirrorTotal = mirrors.stream()
            .map(m -> m.getBalanceInBase() != null ? m.getBalanceInBase() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // ================================================================
        // v5.6.1: REMOVED nested AGGREGATION summation
        // Currency Mirrors already include nested balances via currency-wise propagation
        // Adding nested AGGREGATION totals would cause double counting
        // ================================================================
        List<VirtualAccount> nestedAggs = children.stream()
            .filter(c -> c.getAccountCategory() == AccountCategory.AGGREGATION)
            .toList();

        // ================================================================
        // FALLBACK: If no Currency Mirrors, sum nested AGGREGATIONs or operational VAs
        // This handles hierarchies without Currency Mirror layer
        // ================================================================
        List<VirtualAccount> operationalChildren = children.stream()
            .filter(this::isOperationalVa)
            .toList();

        BigDecimal fallbackTotal = BigDecimal.ZERO;
        if (mirrors.isEmpty()) {
            if (!nestedAggs.isEmpty()) {
                // No mirrors at this level - sum nested AGGREGATION balances as fallback
                fallbackTotal = nestedAggs.stream()
                    .map(a -> a.getAggregatedBalanceBase() != null ? a.getAggregatedBalanceBase() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                log.debug("No mirrors found - summed {} nested AGGREGATIONs: {}",
                    nestedAggs.size(), fallbackTotal);
            } else if (!operationalChildren.isEmpty()) {
                // No mirrors, no nested aggs - sum operational VAs directly with FX conversion
                for (VirtualAccount opVa : operationalChildren) {
                    BigDecimal balance = opVa.getCurrentBalance() != null ? opVa.getCurrentBalance() : BigDecimal.ZERO;
                    String vaCurrency = opVa.getCurrencyCode();

                    // Convert to base currency if needed
                    if (baseCurrency != null && vaCurrency != null && !baseCurrency.equals(vaCurrency)) {
                        BigDecimal fxRate = fxRateService.getRate(vaCurrency, baseCurrency);
                        balance = balance.multiply(fxRate).setScale(4, RoundingMode.HALF_UP);
                    }
                    fallbackTotal = fallbackTotal.add(balance);
                }
                log.debug("No mirrors found - summed {} operational VAs directly: {}",
                    operationalChildren.size(), fallbackTotal);
            }
        }

        BigDecimal totalBalance = mirrorTotal.add(fallbackTotal);

        // Update aggregation balance
        aggregation.setAggregatedBalance(totalBalance);
        aggregation.setAggregatedBalanceBase(totalBalance);

        vaRepository.save(aggregation);

        log.info("Recalculated {} {}: aggregatedBalance={} {} (mirrors: {}={}, nested aggs: {}, fallback: {})",
            aggregation.getAccountCategory(), aggregation.getVaNumber(),
            totalBalance, baseCurrency,
            mirrors.size(), mirrorTotal,
            nestedAggs.size(), fallbackTotal);
    }

    /**
     * Check if VA is an operational/transactional VA.
     */
    private boolean isOperationalVa(VirtualAccount va) {
        AccountCategory cat = va.getAccountCategory();
        return cat == AccountCategory.TRANSACTION ||
               cat == AccountCategory.COLLECTION ||
               cat == AccountCategory.DISBURSEMENT;
    }

    // ========================================================================
    // ON-DEMAND AGGREGATION
    // ========================================================================

    /**
     * Force refresh balance for a specific VA and its ancestors.
     */
    @Transactional
    public VirtualAccount refreshVaBalance(UUID vaId) {
        VirtualAccount va = vaRepository.findById(vaId)
            .orElseThrow(() -> new ResourceNotFoundException("VA not found: " + vaId));

        LocalDateTime now = LocalDateTime.now();

        // If this is a Currency Mirror, recalculate it
        if (va.getAccountCategory() == AccountCategory.CURRENCY_MIRROR) {
            recalculateCurrencyMirror(va, now);
        }
        // If this is an Aggregation or Root, recalculate it
        else if (va.getAccountCategory() == AccountCategory.AGGREGATION ||
                 va.getAccountCategory() == AccountCategory.ROOT) {
            recalculateAggregationNode(va, now);
        }

        // Refresh ancestors
        refreshAncestors(va);

        return vaRepository.findById(vaId).orElse(va);
    }

    /**
     * Refresh all ancestor balances.
     */
    private void refreshAncestors(VirtualAccount va) {
        UUID parentId = va.getParentAccountId();
        LocalDateTime now = LocalDateTime.now();
        Set<UUID> visited = new HashSet<>();

        while (parentId != null && !visited.contains(parentId)) {
            visited.add(parentId);
            
            VirtualAccount parent = vaRepository.findById(parentId).orElse(null);
            if (parent == null) break;

            // Recalculate based on category
            if (parent.getAccountCategory() == AccountCategory.CURRENCY_MIRROR) {
                recalculateCurrencyMirror(parent, now);
            } else if (parent.getAccountCategory() == AccountCategory.AGGREGATION ||
                       parent.getAccountCategory() == AccountCategory.ROOT) {
                recalculateAggregationNode(parent, now);
            }

            parentId = parent.getParentAccountId();
        }
    }

    /**
     * Full refresh for entire corporate.
     */
    @Async
    @Transactional
    public void fullRefresh(UUID corporateId) {
        log.info("Starting full balance refresh for corporate: {}", corporateId);
        aggregateForCorporate(corporateId);
        log.info("Completed full balance refresh for corporate: {}", corporateId);
    }

    /**
     * Async full refresh triggered after a transaction.
     *
     * v5.7.0: Option C - Trigger full recalc on transaction
     *
     * This method is called asynchronously after propagateBalanceChange() completes
     * the immediate mirror update and ancestor propagation. It ensures that ALL
     * Currency Mirrors at ALL levels are correctly updated with currency-wise propagation.
     *
     * Example scenario:
     * - Transaction on VA-EUR-001 under AGGREGATION-A1
     * - Immediate propagation updates M3 EUR (under A1) and AGGREGATION totals
     * - This async method then updates M1 EUR (under ROOT) to include M3 EUR balance
     *
     * @param corporateId Corporate ID for the full refresh
     * @param triggeringVaId The VA that triggered this refresh (for logging)
     */
    @Async
    @Transactional
    public void fullRefreshAfterTransaction(UUID corporateId, UUID triggeringVaId) {
        log.info("[ASYNC] Starting post-transaction full refresh for corporate {} (triggered by VA {})",
            corporateId, triggeringVaId);

        try {
            int aggregated = aggregateForCorporate(corporateId);
            log.info("[ASYNC] Completed post-transaction full refresh for corporate {}: {} VAs aggregated",
                corporateId, aggregated);
        } catch (Exception e) {
            log.error("[ASYNC] Error during post-transaction full refresh for corporate {}: {}",
                corporateId, e.getMessage(), e);
        }
    }

    // ========================================================================
    // CURRENCY MIRROR MANAGEMENT (CORRECTED)
    // ========================================================================

    /**
     * Update all Currency Mirrors for a corporate.
     * 
     * CORRECTED: Currency Mirrors aggregate SIBLING Transaction VAs, not children.
     */
    @Transactional
    public void updateCurrencyMirrors(UUID corporateId) {
        List<VirtualAccount> mirrors = vaRepository.findByCorporateId(corporateId).stream()
            .filter(va -> va.getAccountCategory() == AccountCategory.CURRENCY_MIRROR)
            .toList();

        LocalDateTime now = LocalDateTime.now();

        for (VirtualAccount mirror : mirrors) {
            recalculateCurrencyMirror(mirror, now);
        }

        log.info("Updated {} Currency Mirrors for corporate {}", mirrors.size(), corporateId);
    }

    // ========================================================================
    // MULTI-CURRENCY AGGREGATION
    // ========================================================================

    /**
     * Get multi-currency position for a corporate.
     * NOTE: For multi-program corporates, use getMultiCurrencyPositionByProgram instead.
     */
    @Transactional(readOnly = true)
    public MultiCurrencyPosition getMultiCurrencyPosition(UUID corporateId, String baseCurrency) {
        // Get all Currency Mirrors at ROOT level
        Optional<VirtualAccount> rootOpt = vaRepository.findRootAccount(corporateId);

        if (rootOpt.isEmpty()) {
            log.warn("No ROOT found for corporate {}", corporateId);
            return new MultiCurrencyPosition();
        }

        VirtualAccount root = rootOpt.get();
        List<VirtualAccount> rootMirrors = vaRepository.findByParentAccountIdAndAccountCategory(
            root.getId(), AccountCategory.CURRENCY_MIRROR);

        MultiCurrencyPosition position = new MultiCurrencyPosition();
        position.setCorporateId(corporateId);
        position.setBaseCurrency(baseCurrency);
        position.setCurrencyBreakdown(new ArrayList<>());

        BigDecimal totalInBase = BigDecimal.ZERO;

        for (VirtualAccount mirror : rootMirrors) {
            CurrencyPosition cp = new CurrencyPosition();
            cp.setCurrency(mirror.getCurrencyCode());
            cp.setOriginalBalance(mirror.getMirrorBalance() != null ? mirror.getMirrorBalance() : BigDecimal.ZERO);
            cp.setFxRate(mirror.getFxRate() != null ? mirror.getFxRate() : BigDecimal.ONE);
            cp.setFxRateAt(mirror.getFxRateAt());
            cp.setConvertedBalance(mirror.getBalanceInBase() != null ? mirror.getBalanceInBase() : BigDecimal.ZERO);

            position.getCurrencyBreakdown().add(cp);
            totalInBase = totalInBase.add(cp.getConvertedBalance());
        }

        position.setTotalInBase(totalInBase);
        return position;
    }

    /**
     * Get multi-currency position for a specific program.
     * This is the preferred method for multi-program corporates.
     *
     * @param programId Program ID
     * @param baseCurrency Base currency for conversion (usually program's base currency)
     * @return Multi-currency position with breakdown by currency
     */
    @Transactional(readOnly = true)
    public MultiCurrencyPosition getMultiCurrencyPositionByProgram(UUID programId, String baseCurrency) {
        // Get ROOT VA for this program
        List<VirtualAccount> rootVas = vaRepository.findByProgramIdAndAccountCategory(programId, AccountCategory.ROOT);

        if (rootVas.isEmpty()) {
            log.warn("No ROOT VA found for program {}", programId);
            return new MultiCurrencyPosition();
        }

        VirtualAccount root = rootVas.get(0);

        // Get Currency Mirrors under ROOT for this program
        List<VirtualAccount> rootMirrors = vaRepository.findByParentAccountIdAndAccountCategory(
            root.getId(), AccountCategory.CURRENCY_MIRROR)
            .stream()
            .filter(m -> programId.equals(m.getProgramId()))  // Filter by programId for safety
            .collect(Collectors.toList());

        MultiCurrencyPosition position = new MultiCurrencyPosition();
        position.setCorporateId(root.getCorporateId());
        position.setProgramId(programId);
        position.setBaseCurrency(baseCurrency != null ? baseCurrency : root.getCurrencyCode());
        position.setCurrencyBreakdown(new ArrayList<>());

        BigDecimal totalInBase = BigDecimal.ZERO;

        for (VirtualAccount mirror : rootMirrors) {
            CurrencyPosition cp = new CurrencyPosition();
            cp.setCurrency(mirror.getCurrencyCode());
            cp.setOriginalBalance(mirror.getMirrorBalance() != null ? mirror.getMirrorBalance() : BigDecimal.ZERO);
            cp.setFxRate(mirror.getFxRate() != null ? mirror.getFxRate() : BigDecimal.ONE);
            cp.setFxRateAt(mirror.getFxRateAt());
            cp.setConvertedBalance(mirror.getBalanceInBase() != null ? mirror.getBalanceInBase() : BigDecimal.ZERO);

            position.getCurrencyBreakdown().add(cp);
            totalInBase = totalInBase.add(cp.getConvertedBalance());
        }

        // Sort by currency code for consistent display
        position.getCurrencyBreakdown().sort(Comparator.comparing(CurrencyPosition::getCurrency));

        position.setTotalInBase(totalInBase);

        log.debug("Multi-currency position for program {}: {} currencies, total {} {}",
            programId, position.getCurrencyBreakdown().size(), totalInBase, baseCurrency);

        return position;
    }

    // ========================================================================
    // BALANCE QUERIES
    // ========================================================================

    /**
     * Get total balance for a corporate (from ROOT node).
     */
    @Transactional(readOnly = true)
    public BigDecimal getTotalBalance(UUID corporateId) {
        return vaRepository.findRootAccount(corporateId)
            .map(va -> va.getAggregatedBalanceBase() != null ? va.getAggregatedBalanceBase() : va.getAggregatedBalance())
            .orElse(BigDecimal.ZERO);
    }

    /**
     * Get balance for a specific VA.
     */
    @Transactional(readOnly = true)
    public BigDecimal getVaBalance(UUID vaId) {
        VirtualAccount va = vaRepository.findById(vaId)
            .orElseThrow(() -> new ResourceNotFoundException("VA not found: " + vaId));

        return switch (va.getAccountCategory()) {
            case ROOT, AGGREGATION -> va.getAggregatedBalance() != null ? va.getAggregatedBalance() : BigDecimal.ZERO;
            case CURRENCY_MIRROR -> va.getMirrorBalance() != null ? va.getMirrorBalance() : BigDecimal.ZERO;
            case PHYSICAL_MIRROR -> va.getBankBalance() != null ? va.getBankBalance() : BigDecimal.ZERO;
            default -> va.getCurrentBalance() != null ? va.getCurrentBalance() : BigDecimal.ZERO;
        };
    }

    /**
     * Get balance breakdown by currency for a corporate.
     */
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> getBalanceByCurrency(UUID corporateId) {
        return vaRepository.findByCorporateId(corporateId).stream()
            .filter(va -> va.getAccountCategory() == AccountCategory.CURRENCY_MIRROR)
            .filter(va -> va.getParentAccountId() != null)
            .collect(Collectors.groupingBy(
                VirtualAccount::getCurrencyCode,
                Collectors.reducing(
                    BigDecimal.ZERO,
                    va -> va.getMirrorBalance() != null ? va.getMirrorBalance() : BigDecimal.ZERO,
                    BigDecimal::add
                )
            ));
    }

    /**
     * Get subtree balance (sum of all leaf VAs under a node).
     */
    @Transactional(readOnly = true)
    public BigDecimal getSubtreeBalance(UUID vaId, String targetCurrency) {
        VirtualAccount va = vaRepository.findById(vaId)
            .orElseThrow(() -> new ResourceNotFoundException("VA not found: " + vaId));

        // For AGGREGATION/ROOT, use the aggregatedBalance (already calculated)
        if (va.getAccountCategory() == AccountCategory.ROOT ||
            va.getAccountCategory() == AccountCategory.AGGREGATION) {
            
            BigDecimal balance = va.getAggregatedBalanceBase() != null ? 
                va.getAggregatedBalanceBase() : BigDecimal.ZERO;
            
            String baseCurrency = va.getBaseCurrency() != null ? va.getBaseCurrency() : va.getCurrencyCode();
            if (!baseCurrency.equals(targetCurrency)) {
                balance = fxRateService.convert(balance, baseCurrency, targetCurrency);
            }
            return balance;
        }

        // For other VAs, return direct balance converted
        BigDecimal balance = va.getCurrentBalance() != null ? va.getCurrentBalance() : BigDecimal.ZERO;
        if (!va.getCurrencyCode().equals(targetCurrency)) {
            balance = fxRateService.convert(balance, va.getCurrencyCode(), targetCurrency);
        }
        return balance;
    }

    /**
     * Get balance by hierarchy level for a corporate.
     */
    @Transactional(readOnly = true)
    public Map<Integer, BigDecimal> getBalanceByLevel(UUID corporateId, String currency) {
        Map<Integer, BigDecimal> balanceByLevel = new TreeMap<>();

        List<VirtualAccount> vas = vaRepository.findByCorporateId(corporateId);
        
        for (VirtualAccount va : vas) {
            int level = va.getHierarchyLevel() != null ? va.getHierarchyLevel() : 0;
            BigDecimal balance = va.getCurrentBalance() != null ? va.getCurrentBalance() : BigDecimal.ZERO;
            
            if (!va.getCurrencyCode().equals(currency)) {
                balance = fxRateService.convert(balance, va.getCurrencyCode(), currency);
            }
            
            balanceByLevel.merge(level, balance, BigDecimal::add);
        }

        return balanceByLevel;
    }

    // ========================================================================
    // DTOs
    // ========================================================================

    @lombok.Data
    public static class MultiCurrencyPosition {
        private UUID corporateId;
        private UUID programId;  // Added for program-specific queries
        private String baseCurrency;
        private BigDecimal totalInBase = BigDecimal.ZERO;
        private List<CurrencyPosition> currencyBreakdown = new ArrayList<>();
    }

    @lombok.Data
    public static class CurrencyPosition {
        private String currency;
        private BigDecimal originalBalance;
        private BigDecimal fxRate;
        private LocalDateTime fxRateAt;
        private BigDecimal convertedBalance;
    }
}