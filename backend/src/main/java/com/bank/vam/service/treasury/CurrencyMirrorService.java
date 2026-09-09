package com.bank.vam.service.treasury;

import com.bank.vam.entity.Program;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.*;
import com.bank.vam.entity.hierarchy.HierarchyNode;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.treasury.FxRate;
import com.bank.vam.entity.treasury.FxRate.RateType;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.ProgramRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.HierarchyNodeRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CurrencyMirrorService - Manages Currency Mirror VAs attached to Consolidation nodes.
 * 
 * UNIFIED ARCHITECTURE v4.3:
 * Currency Mirror VAs are AGGREGATION accounts that:
 * 1. Attach to existing HierarchyNode (CONSOLIDATION type) via hierarchyNodeId
 * 2. Aggregate all same-currency VAs under that node
 * 3. Apply FX conversion to base currency
 * 4. Form a self-referencing tree via parentAccountId
 * 
 * KEY RELATIONSHIPS:
 * - HierarchyNode (CONSOLIDATION) 1 → N VirtualAccount (CURRENCY_MIRROR)
 * - VirtualAccount (CURRENCY_MIRROR) 1 → N VirtualAccount (TRANSACTION)
 * - VirtualAccount (CURRENCY_MIRROR) 1 → N VirtualAccount (CURRENCY_MIRROR) [parent-child mirrors]
 * 
 * BALANCE FLOW:
 * Transaction VA balance changes → propagates up parentAccountId chain →
 * updates mirrorBalance at each level → FX converts to balanceInBase
 * 
 * AUTO-CREATION TRIGGERS:
 * 1. createWithHierarchy() - when creating transaction VA with new currency
 * 2. initializeMirrorsForEntity() - when linking Legal Entity to program
 * 3. Manual creation via API for pre-provisioning
 * 
 * FIXED v5.1.0:
 * - Updated RateType.SPOT -> RateType.MID to match database constraints
 * - RateType enum now only supports: BID, ASK, MID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CurrencyMirrorService {

    private final VirtualAccountRepository vaRepository;
    private final HierarchyNodeRepository nodeRepository;
    private final ProgramRepository programRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final FxRateService fxRateService;

    // Default FX rate when rate service is unavailable
    private static final BigDecimal DEFAULT_FX_RATE = BigDecimal.ONE;

    // ========================================================================
    // INNER CLASSES (DTOs for API responses)
    // ========================================================================

    /**
     * Currency breakdown information for reporting.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurrencyBreakdown {
        private String currency;
        private String baseCurrency;
        private BigDecimal originalBalance;
        private BigDecimal fxRate;
        private LocalDateTime fxRateAt;
        private BigDecimal convertedBalance;
        private UUID mirrorVaId;
        private String mirrorVaNumber;
        private Integer level;  // v5.7.1: Hierarchy level for level-based breakdown
    }

    // ========================================================================
    // CORE: CREATE CURRENCY MIRROR (Controller API)
    // ========================================================================

    /**
     * Create a currency mirror under a parent VA.
     * Called by CurrencyMirrorController.createCurrencyMirror().
     * 
     * @param parentVaId The parent VA ID (can be another mirror or ROOT)
     * @param currency The currency code
     * @param baseCurrency The base currency for FX conversion
     * @param corporateId The corporate ID
     * @return The created Currency Mirror VA
     */
    @Transactional
    public VirtualAccount createCurrencyMirror(UUID parentVaId, String currency, 
                                                String baseCurrency, UUID corporateId) {
        log.info("Creating Currency Mirror for {} (base: {}) under parent {} for corporate {}",
                 currency, baseCurrency, parentVaId, corporateId);

        // Get parent VA
        VirtualAccount parentVa = vaRepository.findById(parentVaId)
            .orElseThrow(() -> new ResourceNotFoundException("Parent VA not found: " + parentVaId));

        // Validate parent is valid for Currency Mirror attachment
        if (parentVa.getAccountCategory() != AccountCategory.CURRENCY_MIRROR 
            && parentVa.getAccountCategory() != AccountCategory.ROOT
            && parentVa.getAccountCategory() != AccountCategory.AGGREGATION) {
            throw new BusinessException("Parent must be a CURRENCY_MIRROR, ROOT, or AGGREGATION account");
        }

        // Check if mirror already exists
        Optional<VirtualAccount> existing = findMirrorByParentAndCurrency(parentVaId, currency);
        if (existing.isPresent()) {
            log.info("Currency Mirror already exists: {}", existing.get().getVaNumber());
            return existing.get();
        }

        // Get program from parent
        Program program = null;
        if (parentVa.getProgramId() != null) {
            program = programRepository.findById(parentVa.getProgramId()).orElse(null);
        }

        // Generate mirror code
        String mirrorCode = generateMirrorCode(currency, parentVa);

        // Calculate FX rate
        BigDecimal fxRate = calculateFxRate(currency, baseCurrency);

        // Create Currency Mirror VA
        VirtualAccount mirrorVa = VirtualAccount.builder()
            // Core identification
            .vaNumber(mirrorCode)
            .vaName(currency + " Mirror")
            .programId(parentVa.getProgramId())
            .corporateId(corporateId)
            .physicalAccountId(parentVa.getPhysicalAccountId())
            .currencyCode(currency)
            
            // Account classification
            .accountType(AccountType.VIRTUAL)
            .accountCategory(AccountCategory.CURRENCY_MIRROR)
            
            // Hierarchy linkage
            .hierarchyNodeId(parentVa.getHierarchyNodeId())
            .hierarchyPath(parentVa.getHierarchyPath())
            .hierarchyPathVa(buildMirrorHierarchyPath(parentVa, currency))
            .hierarchyLevel(parentVa.getHierarchyLevel() != null ? parentVa.getHierarchyLevel() + 1 : 1)
            
            // Parent mirror linkage (self-referencing VA tree)
            .parentAccountId(parentVaId)
            
            // Multi-currency support
            .baseCurrency(baseCurrency)
            .mirrorBalance(BigDecimal.ZERO)
            .fxRate(fxRate)
            .fxRateAt(LocalDateTime.now())
            .fxRateSource("SYSTEM")
            .balanceInBase(BigDecimal.ZERO)
            
            // Standard balance fields
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .heldBalance(BigDecimal.ZERO)
            
            // Status
            .status(VaStatus.ACTIVE)
            
            // Initialize counters
            .transactionCount(0)
            .build();

        mirrorVa = vaRepository.save(mirrorVa);

        log.info("Created Currency Mirror VA: {} for currency {} under parent {}", 
            mirrorVa.getVaNumber(), currency, parentVa.getVaNumber());

        return mirrorVa;
    }

    // ========================================================================
    // CORE: ENSURE CURRENCY MIRROR EXISTS (for createWithHierarchy flow)
    // ========================================================================

    /**
     * Ensure a Currency Mirror VA exists for a given hierarchy node and currency.
     * Creates the mirror if it doesn't exist, and ensures the parent chain exists.
     * 
     * This is the CORE method called by VirtualAccountService.createWithHierarchy().
     * 
     * @param nodeId The HierarchyNode (CONSOLIDATION) to attach the mirror to
     * @param currency The currency code (e.g., "EUR", "USD")
     * @param program The program for defaults
     * @return The existing or newly created Currency Mirror VA
     */
    @Transactional
    public VirtualAccount ensureCurrencyMirrorVa(UUID nodeId, String currency, Program program) {
        log.debug("Ensuring Currency Mirror VA for node {} currency {}", nodeId, currency);

        HierarchyNode node = nodeRepository.findById(nodeId)
            .orElseThrow(() -> new ResourceNotFoundException("Hierarchy node not found: " + nodeId));

        // Check if mirror already exists for this node + currency
        Optional<VirtualAccount> existing = vaRepository
            .findByHierarchyNodeIdAndAccountCategoryAndCurrencyCode(
                nodeId, AccountCategory.CURRENCY_MIRROR, currency);

        if (existing.isPresent()) {
            log.debug("Currency Mirror already exists: {}", existing.get().getVaNumber());
            return existing.get();
        }

        // Ensure parent currency mirror exists (recursive up the tree)
        VirtualAccount parentMirror = null;
        if (node.getParentId() != null) {
            parentMirror = ensureCurrencyMirrorVa(node.getParentId(), currency, program);
        }

        // Create Currency Mirror VA
        VirtualAccount mirrorVa = createCurrencyMirrorVaForNode(node, currency, parentMirror, program);

        // If this is ROOT level (no parent mirror), ensure Exception account exists
        if (parentMirror == null) {
            ensureExceptionVa(mirrorVa, currency, program);
        }

        return mirrorVa;
    }

    /**
     * Ensure Currency Mirror chain exists from leaf node up to ROOT.
     * Called when creating a new transaction VA.
     * 
     * This is the entry point from VirtualAccountService.createWithHierarchy().
     * 
     * @param leafNodeId The leaf node where VA is being created
     * @param currency The currency of the new VA
     * @param program The program
     * @return The leaf-level Currency Mirror VA (immediate parent for the new VA)
     */
    @Transactional
    public VirtualAccount ensureCurrencyMirrorChain(UUID leafNodeId, String currency, Program program) {
        log.info("Ensuring Currency Mirror chain for node {} currency {}", leafNodeId, currency);
        return ensureCurrencyMirrorVa(leafNodeId, currency, program);
    }

    // ========================================================================
    // CREATE CURRENCY MIRROR VA (for hierarchy nodes)
    // ========================================================================

    /**
     * Create a new Currency Mirror VA attached to a hierarchy node.
     */
    private VirtualAccount createCurrencyMirrorVaForNode(HierarchyNode node, String currency, 
                                                          VirtualAccount parentMirror, Program program) {
        
        String mirrorCode = generateMirrorCodeForNode(node, currency);
        String mirrorName = generateMirrorName(node, currency);
        String hierarchyPath = buildMirrorHierarchyPathForNode(node, currency);

        // Determine FX rate
        BigDecimal fxRate = calculateFxRate(currency, program.getCurrencyCode());

        VirtualAccount mirrorVa = VirtualAccount.builder()
            // Core identification
            .vaNumber(mirrorCode)
            .vaName(mirrorName)
            .programId(program.getId())
            .corporateId(program.getCorporateId())
            .physicalAccountId(program.getPhysicalAccountId())
            .currencyCode(currency)
            
            // Account classification
            .accountType(AccountType.VIRTUAL)
            .accountCategory(AccountCategory.CURRENCY_MIRROR)
            
            // Hierarchy linkage - attached to consolidation node
            .hierarchyNodeId(node.getId())
            .hierarchyPath(node.getMaterializedPath())
            .hierarchyPathVa(hierarchyPath)
            // node.getLevelNumber() is HierarchyNode's own 1-indexed scheme
            // (ROOT node = 1); the VA's hierarchyLevel is 0-indexed (ROOT
            // VA = 0) — the same mismatch fixed in HierarchyService's ROOT
            // creation. Copying levelNumber directly here left every mirror
            // in this createWithHierarchy chain one level too deep,
            // including the ROOT-level one (parentMirror == null below),
            // which isMirrorAtLevel() would already treat as unreachable
            // by level filtering since it has no parent — but a wrong
            // stored value is still wrong for anything that reads it directly.
            .hierarchyLevel(node.getLevelNumber() != null ? node.getLevelNumber() - 1 : 0)
            
            // Parent mirror linkage (self-referencing VA tree)
            .parentAccountId(parentMirror != null ? parentMirror.getId() : null)
            
            // Multi-currency support
            .baseCurrency(program.getCurrencyCode())
            .mirrorBalance(BigDecimal.ZERO)
            .fxRate(fxRate)
            .fxRateAt(LocalDateTime.now())
            .fxRateSource("SYSTEM")
            .balanceInBase(BigDecimal.ZERO)
            
            // Standard balance fields (for compatibility with existing queries)
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .heldBalance(BigDecimal.ZERO)
            
            // Status
            .status(VaStatus.ACTIVE)
            
            // Initialize counters
            .transactionCount(0)
            .build();

        mirrorVa = vaRepository.save(mirrorVa);

        log.info("Created Currency Mirror VA: {} for node {} (level {}) currency {}", 
            mirrorVa.getVaNumber(), node.getNodeCode(), node.getLevelNumber(), currency);

        return mirrorVa;
    }

    // ========================================================================
    // CREATE EXCEPTION VA
    // ========================================================================

    /**
     * Ensure Exception VA exists under the ROOT-level Currency Mirror.
     * Exception VAs capture unmatched transactions, interest credits, bank charges, etc.
     */
    @Transactional
    public VirtualAccount ensureExceptionVa(VirtualAccount rootMirror, String currency, Program program) {
        // Check if Exception VA already exists for this program and currency
        Optional<VirtualAccount> existing = vaRepository
            .findExceptionVaByCurrency(program.getId(), currency);

        if (existing.isPresent()) {
            return existing.get();
        }

        String exceptionCode = "EXCEPTION-" + currency;

        VirtualAccount exceptionVa = VirtualAccount.builder()
            .vaNumber(exceptionCode)
            .vaName(currency + " Exception Account")
            .programId(program.getId())
            .corporateId(program.getCorporateId())
            .physicalAccountId(program.getPhysicalAccountId())
            .currencyCode(currency)
            .accountType(AccountType.VIRTUAL)
            .accountCategory(AccountCategory.EXCEPTION)
            .parentAccountId(rootMirror.getId())
            .specialType(VaSpecialType.EXCEPTION)
            .baseCurrency(program.getCurrencyCode())
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .status(VaStatus.ACTIVE)
            .transactionCount(0)
            .build();

        exceptionVa = vaRepository.save(exceptionVa);

        log.info("Created Exception VA: {} under root mirror {}", exceptionCode, rootMirror.getVaNumber());

        return exceptionVa;
    }

    /**
     * Create Settlement VA (on-demand, not auto-created).
     * Settlement VAs are used for contra-entries in double-entry bookkeeping.
     */
    @Transactional
    public VirtualAccount createSettlementVa(VirtualAccount parentMirror, String currency, Program program) {
        // Check if Settlement VA already exists
        Optional<VirtualAccount> existing = vaRepository
            .findSettlementVaByCurrency(program.getId(), currency);

        if (existing.isPresent()) {
            return existing.get();
        }

        String settlementCode = "SETTLEMENT-" + currency;

        VirtualAccount settlementVa = VirtualAccount.builder()
            .vaNumber(settlementCode)
            .vaName(currency + " Settlement Account")
            .programId(program.getId())
            .corporateId(program.getCorporateId())
            .physicalAccountId(program.getPhysicalAccountId())
            .currencyCode(currency)
            .accountType(AccountType.VIRTUAL)
            .accountCategory(AccountCategory.SETTLEMENT)
            .parentAccountId(parentMirror.getId())
            .specialType(VaSpecialType.SETTLEMENT)
            .baseCurrency(program.getCurrencyCode())
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .status(VaStatus.ACTIVE)
            .transactionCount(0)
            .build();

        settlementVa = vaRepository.save(settlementVa);

        log.info("Created Settlement VA: {} under mirror {}", settlementCode, parentMirror.getVaNumber());

        return settlementVa;
    }

    // ========================================================================
    // BALANCE PROPAGATION
    // ========================================================================

    /**
     * Propagate balance change up the Currency Mirror chain.
     * Called after a transaction VA balance changes.
     * 
     * This walks up the parentAccountId chain and updates mirrorBalance
     * at each Currency Mirror level.
     * 
     * @param vaId The transaction VA whose balance changed
     * @param delta The balance change amount (positive for credit, negative for debit)
     */
    @Transactional
    public void propagateBalanceChange(UUID vaId, BigDecimal delta) {
        if (delta == null || delta.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }

        VirtualAccount va = vaRepository.findById(vaId)
            .orElseThrow(() -> new ResourceNotFoundException("VA not found: " + vaId));

        log.debug("Propagating balance change {} for VA {}", delta, va.getVaNumber());

        // Walk up the parent chain
        UUID parentId = va.getParentAccountId();
        while (parentId != null) {
            VirtualAccount parent = vaRepository.findById(parentId).orElse(null);
            if (parent == null) break;

            if (parent.isCurrencyMirror()) {
                // Update mirror balance
                BigDecimal currentMirror = parent.getMirrorBalance() != null 
                    ? parent.getMirrorBalance() : BigDecimal.ZERO;
                BigDecimal newMirrorBalance = currentMirror.add(delta);
                parent.setMirrorBalance(newMirrorBalance);
                
                // Recalculate balance in base currency
                if (parent.getFxRate() != null) {
                    BigDecimal balanceInBase = newMirrorBalance.multiply(parent.getFxRate())
                        .setScale(4, RoundingMode.HALF_UP);
                    parent.setBalanceInBase(balanceInBase);
                }
                
                // Also update standard balance fields for compatibility
                parent.setCurrentBalance(newMirrorBalance);
                parent.setAvailableBalance(newMirrorBalance);
                
                vaRepository.save(parent);
                
                log.debug("Updated mirror {} balance to {} (base: {})", 
                    parent.getVaNumber(), newMirrorBalance, parent.getBalanceInBase());
            }

            parentId = parent.getParentAccountId();
        }
    }

    /**
     * Recalculate mirror balance using CURRENCY-WISE propagation.
     *
     * v5.6.1: Currency Mirrors aggregate:
     * 1. Same-currency sibling Transaction VAs (under same parent)
     * 2. Same-currency Currency Mirrors from nested AGGREGATION nodes
     *
     * This enables currency-wise roll-up across the hierarchy:
     * - M3 EUR (under A1) + M5 EUR (under A2) → M1 EUR (under ROOT)
     *
     * Returns the updated mirror VA.
     */
    @Transactional
    public VirtualAccount recalculateMirrorBalance(UUID mirrorVaId) {
        VirtualAccount mirror = vaRepository.findById(mirrorVaId)
            .orElseThrow(() -> new ResourceNotFoundException("Mirror VA not found: " + mirrorVaId));

        if (!mirror.isCurrencyMirror()) {
            throw new BusinessException("VA is not a Currency Mirror: " + mirrorVaId);
        }

        UUID parentId = mirror.getParentAccountId();
        String currency = mirror.getCurrencyCode();

        if (parentId == null) {
            log.warn("Currency Mirror {} has no parent - cannot calculate sibling balance", mirror.getVaNumber());
            return mirror;
        }

        // Get all siblings (same parent)
        List<VirtualAccount> siblings = vaRepository.findByParentAccountId(parentId);

        // ================================================================
        // Step 1: Sum sibling Transaction VAs (same currency)
        // ================================================================
        List<VirtualAccount> operationalSiblings = siblings.stream()
            .filter(va -> isOperationalVa(va))
            .filter(va -> currency.equals(va.getCurrencyCode()))
            .toList();

        BigDecimal siblingBalance = operationalSiblings.stream()
            .map(va -> va.getCurrentBalance() != null ? va.getCurrentBalance() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // ================================================================
        // Step 2: Sum nested AGGREGATION's same-currency Currency Mirrors
        // (Currency-wise propagation up the hierarchy)
        // ================================================================
        BigDecimal nestedMirrorBalance = siblings.stream()
            .filter(va -> va.getAccountCategory() == AccountCategory.AGGREGATION)
            .flatMap(agg -> vaRepository.findByParentAccountIdAndAccountCategory(
                agg.getId(), AccountCategory.CURRENCY_MIRROR).stream())
            .filter(m -> currency.equals(m.getCurrencyCode()))
            .map(m -> m.getMirrorBalance() != null ? m.getMirrorBalance() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalBalance = siblingBalance.add(nestedMirrorBalance);

        mirror.setMirrorBalance(totalBalance);

        // Recalculate base currency balance
        String baseCurrency = mirror.getBaseCurrency();
        if (baseCurrency != null && !baseCurrency.equals(currency)) {
            BigDecimal fxRate = calculateFxRate(currency, baseCurrency);
            mirror.setFxRate(fxRate);
            mirror.setFxRateAt(LocalDateTime.now());
            BigDecimal balanceInBase = totalBalance.multiply(fxRate)
                .setScale(4, RoundingMode.HALF_UP);
            mirror.setBalanceInBase(balanceInBase);
        } else {
            mirror.setFxRate(BigDecimal.ONE);
            mirror.setBalanceInBase(totalBalance);
        }

        // Update standard balance fields
        mirror.setCurrentBalance(totalBalance);
        mirror.setAvailableBalance(totalBalance);

        mirror = vaRepository.save(mirror);

        log.info("Recalculated mirror {} balance: {} {} (siblings: {}, nested mirrors: {})",
            mirror.getVaNumber(), totalBalance, currency,
            siblingBalance, nestedMirrorBalance);

        return mirror;
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

    /**
     * Recalculate all mirror balances for a program (batch reconciliation).
     * Processes mirrors from deepest level to root to ensure correct aggregation.
     */
    @Transactional
    public int recalculateAllMirrors(UUID programId) {
        log.info("Recalculating all Currency Mirrors for program {}", programId);

        // Get all mirrors ordered by level (deepest first for correct aggregation)
        List<VirtualAccount> mirrors = vaRepository.findCurrencyMirrorsByProgram(programId);
        
        // Sort by level descending (leaf mirrors first)
        mirrors.sort((a, b) -> Integer.compare(
            b.getHierarchyLevel() != null ? b.getHierarchyLevel() : 0,
            a.getHierarchyLevel() != null ? a.getHierarchyLevel() : 0
        ));

        int count = 0;
        for (VirtualAccount mirror : mirrors) {
            recalculateMirrorBalance(mirror.getId());
            count++;
        }

        log.info("Recalculated {} Currency Mirrors for program {}", count, programId);
        return count;
    }

    // ========================================================================
    // FX RATE MANAGEMENT
    // ========================================================================

    /**
     * Update FX rate for a Currency Mirror.
     */
    @Transactional
    public void updateFxRate(UUID mirrorVaId, BigDecimal newRate, String source) {
        VirtualAccount mirror = vaRepository.findById(mirrorVaId)
            .orElseThrow(() -> new ResourceNotFoundException("Mirror VA not found: " + mirrorVaId));

        if (!mirror.isCurrencyMirror()) {
            throw new BusinessException("VA is not a Currency Mirror: " + mirrorVaId);
        }

        mirror.updateFxRate(newRate, source);
        vaRepository.save(mirror);

        log.info("Updated FX rate for {} to {} (source: {})", 
            mirror.getVaNumber(), newRate, source);
    }

    /**
     * Update FX rates for all Currency Mirrors of a currency in a program.
     */
    @Transactional
    public int updateFxRatesForCurrency(UUID programId, String currency, BigDecimal rate, String source) {
        List<VirtualAccount> mirrors = vaRepository.findCurrencyMirrorsByProgramAndCurrency(programId, currency);
        
        for (VirtualAccount mirror : mirrors) {
            mirror.updateFxRate(rate, source);
            vaRepository.save(mirror);
        }

        log.info("Updated FX rate to {} for {} {} mirrors in program {}", 
            rate, mirrors.size(), currency, programId);

        return mirrors.size();
    }

    // ========================================================================
    // QUERY METHODS (Required by Controller)
    // ========================================================================

    /**
     * Get all Currency Mirrors for a corporate.
     * Required by CurrencyMirrorController.getMirrorsByCorporate().
     */
    @Transactional(readOnly = true)
    public List<VirtualAccount> getMirrorsByCorporate(UUID corporateId) {
        return vaRepository.findByCorporateId(corporateId).stream()
            .filter(va -> va.getAccountCategory() == AccountCategory.CURRENCY_MIRROR)
            .collect(Collectors.toList());
    }

    /**
     * Get currency breakdown for a corporate.
     * Required by CurrencyMirrorController.getCurrencyBreakdown().
     *
     * Returns a map of currency -> CurrencyBreakdown with aggregated balances.
     *
     * ROOT-level mirrors only (level 0), summed across every program the
     * corporate has. Mirrors below ROOT already roll up into it, so summing
     * every level together — as this used to do — double counts the same
     * money (see buildCurrencyBreakdown's own double-counting warning). A
     * corporate can span multiple programs, each with its own ROOT mirror
     * per currency, which is why this still sums (unlike the single-program
     * getCurrencyBreakdownByProgram below, where ROOT already is the total).
     */
    @Transactional(readOnly = true)
    public Map<String, CurrencyBreakdown> getCurrencyBreakdown(UUID corporateId) {
        List<VirtualAccount> mirrors = getMirrorsByCorporate(corporateId);
        List<VirtualAccount> rootLevelMirrors = mirrors.stream()
            .filter(m -> isMirrorAtLevel(m, 0))
            .collect(Collectors.toList());
        return buildCurrencyBreakdown(rootLevelMirrors);
    }

    /**
     * Get currency breakdown for a specific program.
     * This is the preferred method for multi-program corporates as it provides
     * accurate currency breakdown scoped to a single program.
     *
     * Required by CurrencyMirrorController.getCurrencyBreakdownByProgram().
     *
     * @param programId The program ID
     * @return Map of currency -> CurrencyBreakdown with aggregated balances
     */
    @Transactional(readOnly = true)
    public Map<String, CurrencyBreakdown> getCurrencyBreakdownByProgram(UUID programId) {
        // Delegate to the level-aware method at ROOT (0) — a single program
        // has exactly one hierarchy, so ROOT already is the program's total.
        // This used to sum every level's mirrors together (double counting,
        // the same bug getCurrencyBreakdownByProgramAndLevel's own default
        // already avoids).
        return getCurrencyBreakdownByProgramAndLevel(programId, 0);
    }

    /**
     * Corporate-wide breakdown re-projected into an arbitrary target base
     * currency. {@code null}/blank falls back to each mirror's own
     * configured base (existing behaviour) — see {@link #reprojectToBase}.
     */
    @Transactional(readOnly = true)
    public Map<String, CurrencyBreakdown> getCurrencyBreakdown(UUID corporateId, String targetBaseCurrency) {
        Map<String, CurrencyBreakdown> breakdown = getCurrencyBreakdown(corporateId);
        return reprojectToBase(breakdown, targetBaseCurrency);
    }

    /**
     * Program breakdown re-projected into an arbitrary target base currency.
     * See {@link #reprojectToBase}.
     */
    @Transactional(readOnly = true)
    public Map<String, CurrencyBreakdown> getCurrencyBreakdownByProgram(UUID programId, String targetBaseCurrency) {
        Map<String, CurrencyBreakdown> breakdown = getCurrencyBreakdownByProgram(programId);
        return reprojectToBase(breakdown, targetBaseCurrency);
    }

    /**
     * Program breakdown at a specific level, re-projected into an arbitrary
     * target base currency. See {@link #reprojectToBase}.
     */
    @Transactional(readOnly = true)
    public Map<String, CurrencyBreakdown> getCurrencyBreakdownByProgramAndLevel(UUID programId, Integer level, String targetBaseCurrency) {
        Map<String, CurrencyBreakdown> breakdown = getCurrencyBreakdownByProgramAndLevel(programId, level);
        return reprojectToBase(breakdown, targetBaseCurrency);
    }

    /**
     * Re-project a breakdown map's converted figures into an arbitrary
     * target base currency. {@code originalBalance} (native to each
     * currency) never changes; {@code convertedBalance}/{@code fxRate}/
     * {@code baseCurrency} get recomputed against the requested currency
     * instead of whatever base each mirror happens to be configured with.
     * Without this, picking a different "Base" currency in the UI only
     * relabelled the mirror's native conversion instead of actually
     * converting — e.g. showing "1 AED = 1.0000 GBP" (the real AED→AED
     * rate) just because the label said GBP.
     *
     * {@code null}/blank target returns the breakdown unchanged.
     */
    private Map<String, CurrencyBreakdown> reprojectToBase(Map<String, CurrencyBreakdown> breakdown, String targetBaseCurrency) {
        if (targetBaseCurrency == null || targetBaseCurrency.isBlank()) {
            return breakdown;
        }
        String target = targetBaseCurrency.toUpperCase();
        Map<String, CurrencyBreakdown> result = new HashMap<>();
        for (Map.Entry<String, CurrencyBreakdown> entry : breakdown.entrySet()) {
            CurrencyBreakdown cb = entry.getValue();
            BigDecimal original = cb.getOriginalBalance() != null ? cb.getOriginalBalance() : BigDecimal.ZERO;
            BigDecimal rate = calculateFxRate(cb.getCurrency(), target);
            BigDecimal converted = original.multiply(rate).setScale(4, RoundingMode.HALF_UP);
            result.put(entry.getKey(), CurrencyBreakdown.builder()
                .currency(cb.getCurrency())
                .baseCurrency(target)
                .originalBalance(original)
                .fxRate(rate)
                .fxRateAt(LocalDateTime.now())
                .convertedBalance(converted)
                .mirrorVaId(cb.getMirrorVaId())
                .mirrorVaNumber(cb.getMirrorVaNumber())
                .level(cb.getLevel())
                .build());
        }
        return result;
    }

    /**
     * Get currency breakdown for a specific program at a specific hierarchy level.
     *
     * v5.7.1: Level-based breakdown to avoid double counting.
     *
     * IMPORTANT: Currency Mirrors at higher levels (e.g., ROOT) already include
     * balances from lower-level mirrors via currency-wise propagation.
     * To avoid double counting, use level=0 (ROOT) for the total breakdown,
     * or specify a specific level to see only that level's mirrors.
     *
     * Level meanings:
     * - level=0: ROOT level mirrors only (recommended - shows total)
     * - level=1: First AGGREGATION level mirrors
     * - level=2+: Deeper AGGREGATION levels
     * - level=null: All levels (may cause double counting - for debugging only)
     *
     * @param programId The program ID
     * @param level The hierarchy level (0=ROOT, 1+=AGGREGATION levels), null for all
     * @return Map of currency -> CurrencyBreakdown with balances at that level
     */
    @Transactional(readOnly = true)
    public Map<String, CurrencyBreakdown> getCurrencyBreakdownByProgramAndLevel(UUID programId, Integer level) {
        log.info("Getting currency breakdown for program {} at level {}", programId, level);

        List<VirtualAccount> mirrors = getMirrorsForProgram(programId);
        log.info("Found {} total mirrors for program {}", mirrors.size(), programId);

        if (level == null) {
            // Return all mirrors (may double count - useful for debugging)
            log.warn("Getting breakdown without level filter - may cause double counting");
            return buildCurrencyBreakdown(mirrors);
        }

        // Filter mirrors by their parent's hierarchy level
        List<VirtualAccount> filteredMirrors = mirrors.stream()
            .filter(mirror -> isMirrorAtLevel(mirror, level))
            .collect(java.util.stream.Collectors.toList());

        log.info("Filtered {} mirrors at level {} from {} total mirrors",
            filteredMirrors.size(), level, mirrors.size());

        return buildCurrencyBreakdownWithLevel(filteredMirrors, level);
    }

    /**
     * Get currency breakdown for mirrors that are direct children of a specific node.
     *
     * v5.7.2: Node-specific breakdown to avoid showing mirrors from sibling AGGREGATION nodes.
     *
     * This is the preferred method when the user clicks on a specific AGGREGATION node
     * in the tree view. It returns only the Currency Mirrors that are direct children
     * of that specific node, not all mirrors at the same level.
     *
     * Use case:
     * - User clicks on AGGREGATION-A1 (level 1)
     * - Returns only M-A1-EUR, M-A1-USD (children of A1)
     * - Does NOT return M-A2-EUR (child of sibling A2, also at level 1)
     *
     * @param parentNodeId The ID of the parent node (AGGREGATION or ROOT)
     * @return Map of currency -> CurrencyBreakdown for mirrors under this specific node
     */
    @Transactional(readOnly = true)
    public Map<String, CurrencyBreakdown> getCurrencyBreakdownByParentNode(UUID parentNodeId) {
        log.info("Getting currency breakdown for mirrors under parent node {}", parentNodeId);

        // Get the parent node to determine its level for the response
        VirtualAccount parentNode = vaRepository.findById(parentNodeId).orElse(null);
        Integer parentLevel = 0;
        if (parentNode != null) {
            parentLevel = parentNode.getHierarchyLevel();
            if (parentLevel == null) {
                parentLevel = (parentNode.getAccountCategory() == AccountCategory.ROOT) ? 0 : 1;
            }
        }

        // Find all Currency Mirrors that are direct children of this node
        List<VirtualAccount> mirrors = vaRepository.findByParentAccountIdAndAccountCategory(
            parentNodeId, AccountCategory.CURRENCY_MIRROR);

        log.info("Found {} mirrors directly under node {}", mirrors.size(), parentNodeId);

        return buildCurrencyBreakdownWithLevel(mirrors, parentLevel);
    }

    /**
     * Get all hierarchy levels that have Currency Mirrors for a program.
     * Useful for populating level selector in UI.
     *
     * @param programId The program ID
     * @return List of levels (sorted), with level descriptions
     */
    @Transactional(readOnly = true)
    public List<LevelInfo> getAvailableLevels(UUID programId) {
        List<VirtualAccount> mirrors = getMirrorsForProgram(programId);

        Map<Integer, String> levelNames = new HashMap<>();

        for (VirtualAccount mirror : mirrors) {
            UUID parentId = mirror.getParentAccountId();
            if (parentId == null) continue;

            VirtualAccount parent = vaRepository.findById(parentId).orElse(null);
            if (parent == null) continue;

            // ROOT is always level 0 regardless of the stored hierarchyLevel
            // (inconsistently seeded across programs — see isMirrorAtLevel).
            Integer parentLevel = parent.getAccountCategory() == AccountCategory.ROOT
                ? 0
                : parent.getHierarchyLevel();
            if (parentLevel == null) continue;

            if (!levelNames.containsKey(parentLevel)) {
                String levelName = parent.getAccountCategory() == AccountCategory.ROOT
                    ? "ROOT (Total)"
                    : "Level " + parentLevel + " - " + parent.getVaNumber();
                levelNames.put(parentLevel, levelName);
            }
        }

        return levelNames.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> new LevelInfo(e.getKey(), e.getValue()))
            .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Level info DTO for UI.
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class LevelInfo {
        private Integer level;
        private String name;
    }

    /**
     * Get all Currency Mirrors for a specific program.
     * Alias method for controller compatibility.
     *
     * @param programId The program ID
     * @return List of Currency Mirror VAs
     */
    @Transactional(readOnly = true)
    public List<VirtualAccount> getMirrorsByProgram(UUID programId) {
        return getMirrorsForProgram(programId);
    }

    /**
     * True when `mirror`'s parent sits at the given hierarchy level (a ROOT
     * parent with a null hierarchyLevel counts as level 0). Shared by every
     * breakdown path that needs "this mirror's own level" — ROOT accounts
     * don't always have hierarchyLevel populated, so the null-defaulting
     * logic has to be applied consistently everywhere this is checked.
     */
    private boolean isMirrorAtLevel(VirtualAccount mirror, int level) {
        UUID parentId = mirror.getParentAccountId();
        if (parentId == null) {
            log.debug("Mirror {} has no parent - skipping", mirror.getVaNumber());
            return false;
        }
        VirtualAccount parent = vaRepository.findById(parentId).orElse(null);
        if (parent == null) {
            log.debug("Parent {} not found for mirror {}", parentId, mirror.getVaNumber());
            return false;
        }
        // ROOT is always level 0 by definition, regardless of what's stored in
        // hierarchyLevel — confirmed via live data that this column is
        // inconsistently seeded across programs (one corporate's two program
        // ROOTs had hierarchyLevel 0 and 1 respectively), which silently
        // dropped an entire program's mirrors from "ROOT-only" corporate
        // totals instead of just failing to filter out double-counting.
        Integer parentLevel = parent.getAccountCategory() == AccountCategory.ROOT
            ? 0
            : parent.getHierarchyLevel();
        return parentLevel != null && parentLevel == level;
    }

    /**
     * Build currency breakdown from a list of mirror VAs.
     * Extracted to avoid duplication between corporate and program-based methods.
     *
     * WARNING: If mirrors from multiple levels are passed, this will SUM them,
     * which may cause double counting. Use buildCurrencyBreakdownWithLevel() for level-specific.
     */
    private Map<String, CurrencyBreakdown> buildCurrencyBreakdown(List<VirtualAccount> mirrors) {
        // Group by currency and sum balances
        Map<String, CurrencyBreakdown> breakdown = new HashMap<>();

        for (VirtualAccount mirror : mirrors) {
            String currency = mirror.getCurrencyCode();

            if (breakdown.containsKey(currency)) {
                // Add to existing breakdown
                CurrencyBreakdown existing = breakdown.get(currency);
                BigDecimal newOriginal = existing.getOriginalBalance()
                    .add(mirror.getMirrorBalance() != null ? mirror.getMirrorBalance() : BigDecimal.ZERO);
                BigDecimal newConverted = existing.getConvertedBalance()
                    .add(mirror.getBalanceInBase() != null ? mirror.getBalanceInBase() : BigDecimal.ZERO);
                existing.setOriginalBalance(newOriginal);
                existing.setConvertedBalance(newConverted);
            } else {
                // Create new breakdown entry (use the first/ROOT mirror for this currency)
                breakdown.put(currency, CurrencyBreakdown.builder()
                    .currency(currency)
                    .baseCurrency(mirror.getBaseCurrency())
                    .originalBalance(mirror.getMirrorBalance() != null ? mirror.getMirrorBalance() : BigDecimal.ZERO)
                    .fxRate(mirror.getFxRate())
                    .fxRateAt(mirror.getFxRateAt())
                    .convertedBalance(mirror.getBalanceInBase() != null ? mirror.getBalanceInBase() : BigDecimal.ZERO)
                    .mirrorVaId(mirror.getId())
                    .mirrorVaNumber(mirror.getVaNumber())
                    .build());
            }
        }

        return breakdown;
    }

    /**
     * Build currency breakdown for a specific level.
     * Unlike buildCurrencyBreakdown(), this does NOT sum across currencies
     * because all mirrors are already at the same level.
     */
    private Map<String, CurrencyBreakdown> buildCurrencyBreakdownWithLevel(List<VirtualAccount> mirrors, Integer level) {
        Map<String, CurrencyBreakdown> breakdown = new HashMap<>();

        for (VirtualAccount mirror : mirrors) {
            String currency = mirror.getCurrencyCode();

            // For same-level, each currency should have only ONE mirror
            // If duplicates exist (shouldn't happen), take the first one
            if (!breakdown.containsKey(currency)) {
                breakdown.put(currency, CurrencyBreakdown.builder()
                    .currency(currency)
                    .baseCurrency(mirror.getBaseCurrency())
                    .originalBalance(mirror.getMirrorBalance() != null ? mirror.getMirrorBalance() : BigDecimal.ZERO)
                    .fxRate(mirror.getFxRate())
                    .fxRateAt(mirror.getFxRateAt())
                    .convertedBalance(mirror.getBalanceInBase() != null ? mirror.getBalanceInBase() : BigDecimal.ZERO)
                    .mirrorVaId(mirror.getId())
                    .mirrorVaNumber(mirror.getVaNumber())
                    .level(level)
                    .build());
            } else {
                log.warn("Duplicate currency mirror {} at level {} - should not happen", currency, level);
            }
        }

        return breakdown;
    }

    /**
     * Get all Currency Mirrors for a hierarchy node.
     */
    @Transactional(readOnly = true)
    public List<VirtualAccount> getMirrorsForNode(UUID nodeId) {
        return vaRepository.findByHierarchyNodeIdAndAccountCategory(nodeId, AccountCategory.CURRENCY_MIRROR);
    }

    /**
     * Get Currency Mirror for a specific node and currency.
     */
    @Transactional(readOnly = true)
    public Optional<VirtualAccount> getMirrorForNodeAndCurrency(UUID nodeId, String currency) {
        return vaRepository.findByHierarchyNodeIdAndAccountCategoryAndCurrencyCode(
            nodeId, AccountCategory.CURRENCY_MIRROR, currency);
    }

    /**
     * Get all Currency Mirrors for a program.
     */
    @Transactional(readOnly = true)
    public List<VirtualAccount> getMirrorsForProgram(UUID programId) {
        return vaRepository.findCurrencyMirrorsByProgram(programId);
    }

    /**
     * Get ROOT-level Currency Mirror for a currency.
     */
    @Transactional(readOnly = true)
    public Optional<VirtualAccount> getRootMirror(UUID programId, String currency) {
        return vaRepository.findRootCurrencyMirror(programId, currency);
    }

    /**
     * Get distinct currencies that have Currency Mirrors in a program.
     */
    @Transactional(readOnly = true)
    public List<String> getDistinctCurrencies(UUID programId) {
        return vaRepository.findDistinctCurrenciesInMirrors(programId);
    }

    /**
     * Get children of a Currency Mirror (can be other mirrors or transaction VAs).
     */
    @Transactional(readOnly = true)
    public List<VirtualAccount> getMirrorChildren(UUID mirrorVaId) {
        return vaRepository.findByParentAccountId(mirrorVaId);
    }

    /**
     * Get only transaction VAs under a Currency Mirror (not child mirrors).
     */
    @Transactional(readOnly = true)
    public List<VirtualAccount> getTransactionVasUnderMirror(UUID mirrorVaId) {
        return vaRepository.findByParentAccountIdAndAccountCategory(mirrorVaId, AccountCategory.TRANSACTION);
    }

    /**
     * Get Exception VA for a program and currency.
     */
    @Transactional(readOnly = true)
    public Optional<VirtualAccount> getExceptionVa(UUID programId, String currency) {
        return vaRepository.findExceptionVaByCurrency(programId, currency);
    }

    /**
     * Get Settlement VA for a program and currency.
     */
    @Transactional(readOnly = true)
    public Optional<VirtualAccount> getSettlementVa(UUID programId, String currency) {
        return vaRepository.findSettlementVaByCurrency(programId, currency);
    }

    /**
     * Find mirror by parent and currency.
     */
    @Transactional(readOnly = true)
    public Optional<VirtualAccount> findMirrorByParentAndCurrency(UUID parentVaId, String currency) {
        return vaRepository.findByParentAccountId(parentVaId).stream()
            .filter(va -> va.getAccountCategory() == AccountCategory.CURRENCY_MIRROR)
            .filter(va -> currency.equalsIgnoreCase(va.getCurrencyCode()))
            .findFirst();
    }

    // ========================================================================
    // LEGAL ENTITY INTEGRATION
    // ========================================================================

    /**
     * Initialize Currency Mirrors for a Legal Entity when linked to a program.
     * Creates mirrors for the entity's functional currency and program's base currency.
     * 
     * @param entityId The Legal Entity ID
     * @param programId The Program ID to link to
     */
    @Transactional
    public void initializeMirrorsForEntity(UUID entityId, UUID programId) {
        log.info("Initializing Currency Mirrors for entity {} in program {}", entityId, programId);

        LegalEntity entity = legalEntityRepository.findById(entityId)
            .orElseThrow(() -> new ResourceNotFoundException("Entity not found: " + entityId));

        Program program = programRepository.findById(programId)
            .orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));

        // Entity must have a hierarchy node
        if (entity.getHierarchyNodeId() == null) {
            throw new BusinessException("Entity " + entity.getEntityCode() + 
                " has no hierarchy node. Link entity to hierarchy first.");
        }

        // Get currencies to initialize
        Set<String> currencies = new HashSet<>();
        currencies.add(entity.getFunctionalCurrency());
        currencies.add(program.getCurrencyCode());
        
        // TODO: Parse operating currencies from entity.operatingCurrencies JSON

        // Create Currency Mirrors for each currency
        for (String currency : currencies) {
            ensureCurrencyMirrorVa(entity.getHierarchyNodeId(), currency, program);
        }

        // Mark entity as initialized
        entity.setCurrencyMirrorsInitialized(true);
        entity.setProgramId(programId);
        legalEntityRepository.save(entity);

        log.info("Initialized {} Currency Mirrors for entity {}", currencies.size(), entity.getEntityCode());
    }

    /**
     * Link a Legal Entity to a hierarchy node and initialize Currency Mirrors.
     * 
     * NOTE: HierarchyNode does not have a legalEntityId field in current implementation.
     * The linkage is stored on LegalEntity.hierarchyNodeId instead (reverse direction).
     * 
     * @param entityId The Legal Entity ID
     * @param nodeId The HierarchyNode ID to link to
     * @param programId The Program ID
     */
    @Transactional
    public void linkEntityToHierarchy(UUID entityId, UUID nodeId, UUID programId) {
        log.info("Linking entity {} to hierarchy node {} in program {}", entityId, nodeId, programId);

        LegalEntity entity = legalEntityRepository.findById(entityId)
            .orElseThrow(() -> new ResourceNotFoundException("Entity not found: " + entityId));

        HierarchyNode node = nodeRepository.findById(nodeId)
            .orElseThrow(() -> new ResourceNotFoundException("Hierarchy node not found: " + nodeId));

        // Link entity to node (entity side)
        entity.setHierarchyNodeId(nodeId);
        entity.setProgramId(programId);
        legalEntityRepository.save(entity);

        // Note: HierarchyNode.legalEntityId field doesn't exist in current schema.
        // If you need bidirectional linkage, add the field to HierarchyNode entity first.
        // The migration V4.3.1 includes this column, but entity needs to be updated.
        // For now, we only store the link on the LegalEntity side.

        // Initialize Currency Mirrors
        initializeMirrorsForEntity(entityId, programId);

        log.info("Linked entity {} to node {} and initialized Currency Mirrors", 
            entity.getEntityCode(), node.getNodeCode());
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Generate mirror code for controller API.
     */
    private String generateMirrorCode(String currency, VirtualAccount parentVa) {
        return "M-" + currency + "-" + System.currentTimeMillis() % 100000;
    }

    /**
     * Generate mirror code for hierarchy node.
     */
    private String generateMirrorCodeForNode(HierarchyNode node, String currency) {
        String nodeCode = node.getNodeCode();
        // Truncate if too long
        if (nodeCode.length() > 30) {
            nodeCode = nodeCode.substring(0, 30);
        }
        return "M-" + currency + "-" + nodeCode;
    }

    /**
     * Generate mirror name for display.
     */
    private String generateMirrorName(HierarchyNode node, String currency) {
        return currency + " Mirror (" + node.getNodeName() + ")";
    }

    /**
     * Build hierarchy path for the mirror VA.
     */
    private String buildMirrorHierarchyPath(VirtualAccount parentVa, String currency) {
        String parentPath = parentVa.getHierarchyPathVa() != null 
            ? parentVa.getHierarchyPathVa() 
            : parentVa.getHierarchyPath();
        return parentPath + "/M-" + currency;
    }

    /**
     * Build hierarchy path for node-based mirror.
     */
    private String buildMirrorHierarchyPathForNode(HierarchyNode node, String currency) {
        String nodePath = node.getMaterializedPath();
        return nodePath + "/M-" + currency;
    }

    /**
     * Calculate FX rate between two currencies using FxRateService.
     * 
     * INTEGRATION WITH FxRateService:
     * 1. Attempts to get MID rate from FxRateService (MID is the spot equivalent)
     * 2. FxRateService handles: cache lookup, DB lookup, inverse calculation, multi-hop via pivot currencies
     * 3. Falls back to DEFAULT_FX_RATE (1.0) only if FxRateService returns 1.0 (indicating failure)
     * 
     * @param fromCurrency The source currency (e.g., "EUR")
     * @param toCurrency The target/base currency (e.g., "AED")
     * @return The exchange rate to multiply by
     */
    private BigDecimal calculateFxRate(String fromCurrency, String toCurrency) {
        // Same currency - no conversion needed
        if (fromCurrency == null || toCurrency == null) {
            log.warn("Currency is null, using default rate 1.0");
            return DEFAULT_FX_RATE;
        }
        
        if (fromCurrency.equalsIgnoreCase(toCurrency)) {
            return BigDecimal.ONE;
        }

        try {
            // Get rate from FxRateService (uses MID rate by default - the spot equivalent)
            BigDecimal rate = fxRateService.getRate(fromCurrency.toUpperCase(), toCurrency.toUpperCase());
            
            if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("Invalid FX rate {} for {}/{}, using default 1.0", rate, fromCurrency, toCurrency);
                return DEFAULT_FX_RATE;
            }
            
            log.debug("FX rate for {}/{}: {}", fromCurrency, toCurrency, rate);
            return rate;
            
        } catch (Exception e) {
            log.error("Error fetching FX rate for {}/{}: {}", fromCurrency, toCurrency, e.getMessage());
            return DEFAULT_FX_RATE;
        }
    }

    /**
     * Calculate FX rate with specific rate type (BID, ASK, MID).
     * 
     * NOTE: RateType was updated in v5.1.0 to match database constraints:
     * - BID: Bank buying rate
     * - ASK: Bank selling rate
     * - MID: Mid-market rate (used as spot/default)
     * 
     * @param fromCurrency The source currency
     * @param toCurrency The target/base currency
     * @param rateType The type of rate to use (BID, ASK, or MID)
     * @return The exchange rate
     */
    private BigDecimal calculateFxRate(String fromCurrency, String toCurrency, RateType rateType) {
        if (fromCurrency == null || toCurrency == null) {
            return DEFAULT_FX_RATE;
        }
        
        if (fromCurrency.equalsIgnoreCase(toCurrency)) {
            return BigDecimal.ONE;
        }

        try {
            BigDecimal rate = fxRateService.getRate(
                fromCurrency.toUpperCase(), 
                toCurrency.toUpperCase(), 
                rateType
            );
            
            if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("Invalid FX rate {} for {}/{} ({}), using default 1.0", 
                    rate, fromCurrency, toCurrency, rateType);
                return DEFAULT_FX_RATE;
            }
            
            return rate;
            
        } catch (Exception e) {
            log.error("Error fetching FX rate for {}/{} ({}): {}", 
                fromCurrency, toCurrency, rateType, e.getMessage());
            return DEFAULT_FX_RATE;
        }
    }

    /**
     * Get FX rate with full metadata from FxRateService.
     * Useful when you need rate timestamp, source, bid/ask spread, etc.
     * 
     * NOTE: Uses MID rate type as the standard "spot" equivalent.
     * RateType enum was updated in v5.1.0 to match database constraints:
     * - BID: Bank buying rate
     * - ASK: Bank selling rate  
     * - MID: Mid-market rate (used as spot/default)
     * 
     * @param fromCurrency The source currency
     * @param toCurrency The target/base currency
     * @return Optional containing FxRate entity with full metadata
     */
    public Optional<FxRate> getFxRateWithMetadata(String fromCurrency, String toCurrency) {
        if (fromCurrency == null || toCurrency == null || fromCurrency.equalsIgnoreCase(toCurrency)) {
            return Optional.empty();
        }
        
        return fxRateService.getFxRate(
            fromCurrency.toUpperCase(), 
            toCurrency.toUpperCase(), 
            RateType.MID  // MID is the spot/default rate type
        );
    }

    /**
     * Convert amount from one currency to another using FxRateService.
     * 
     * @param amount The amount to convert
     * @param fromCurrency The source currency
     * @param toCurrency The target currency
     * @return Converted amount
     */
    public BigDecimal convertAmount(BigDecimal amount, String fromCurrency, String toCurrency) {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        
        if (fromCurrency == null || toCurrency == null || fromCurrency.equalsIgnoreCase(toCurrency)) {
            return amount;
        }
        
        return fxRateService.convert(amount, fromCurrency.toUpperCase(), toCurrency.toUpperCase());
    }

    /**
     * Refresh FX rates for all Currency Mirrors of a specific currency.
     * Called by scheduled job or manual refresh.
     * 
     * @param programId The program ID
     * @param currency The currency to refresh rates for
     * @return Number of mirrors updated
     */
    @Transactional
    public int refreshFxRatesForCurrency(UUID programId, String currency) {
        log.info("Refreshing FX rates for {} mirrors in program {}", currency, programId);
        
        Program program = programRepository.findById(programId)
            .orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));
        
        String baseCurrency = program.getCurrencyCode();
        
        // Get fresh rate from FxRateService
        BigDecimal freshRate = calculateFxRate(currency, baseCurrency);
        Optional<FxRate> fxRateOpt = getFxRateWithMetadata(currency, baseCurrency);
        
        String rateSource = fxRateOpt.map(r -> r.getRateSource().name()).orElse("SYSTEM");
        LocalDateTime rateAt = fxRateOpt.map(FxRate::getRateTimestamp).orElse(LocalDateTime.now());
        
        // Update all Currency Mirrors for this currency
        List<VirtualAccount> mirrors = vaRepository.findCurrencyMirrorsByProgramAndCurrency(programId, currency);
        
        int updated = 0;
        for (VirtualAccount mirror : mirrors) {
            mirror.setFxRate(freshRate);
            mirror.setFxRateAt(rateAt);
            mirror.setFxRateSource(rateSource);
            
            // Recalculate balance in base currency
            if (mirror.getMirrorBalance() != null) {
                BigDecimal balanceInBase = mirror.getMirrorBalance()
                    .multiply(freshRate)
                    .setScale(4, RoundingMode.HALF_UP);
                mirror.setBalanceInBase(balanceInBase);
            }
            
            vaRepository.save(mirror);
            updated++;
        }
        
        log.info("Updated FX rate to {} for {} {} mirrors in program {}", 
            freshRate, updated, currency, programId);
        
        return updated;
    }

    /**
     * Refresh FX rates for all Currency Mirrors in a program.
     * Called by scheduled job for EOD rate refresh.
     * 
     * @param programId The program ID
     * @return Map of currency -> number of mirrors updated
     */
    @Transactional
    public Map<String, Integer> refreshAllFxRates(UUID programId) {
        log.info("Refreshing all FX rates for program {}", programId);
        
        // Get distinct currencies used in Currency Mirrors
        List<String> currencies = vaRepository.findDistinctCurrenciesInMirrors(programId);
        
        Map<String, Integer> results = new HashMap<>();
        for (String currency : currencies) {
            int updated = refreshFxRatesForCurrency(programId, currency);
            results.put(currency, updated);
        }
        
        log.info("Refreshed FX rates for {} currencies in program {}", currencies.size(), programId);
        return results;
    }
}