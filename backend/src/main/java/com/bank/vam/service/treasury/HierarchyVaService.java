package com.bank.vam.service.treasury;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.*;
import com.bank.vam.entity.hierarchy.HierarchyNode;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.HierarchyNodeRepository;
import lombok.Builder;
import lombok.Data;
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
 * HierarchyVaService - Creates and manages structural VAs in the hierarchy.
 * 
 * ═══════════════════════════════════════════════════════════════════════════════════════
 * ARCHITECTURE (v4.6.0) - Per Currency Mirror Propagation Document
 * ═══════════════════════════════════════════════════════════════════════════════════════
 * 
 * FIX v4.6.0: Fixed ROOT duplication bug - properly handles multiple ROOTs
 * FIX v4.5.2: Added programId to all created VAs (Currency Mirror, Exception, Aggregation, ROOT)
 * 
 * ROOT (baseCurrency: EUR, aggregatedBalance: sum of Currency Mirrors' balanceInBase)
 * │
 * ├── CURRENCY_MIRROR: M-ROOT-USD ← Created ONLY when USD (foreign) currency is introduced
 * │                                  (NO mirror for EUR since it matches baseCurrency)
 * │
 * ├── EXCEPTION: EXCEPT-USD       ← Created for foreign currencies only
 * │
 * └── AGGREGATION: AGG-OPERATIONS (baseCurrency: EUR)
 *     │
 *     ├── CURRENCY_MIRROR: M-OPS-USD  ← Only for foreign currency USD
 *     │                                  (NO mirror for EUR - same as base)
 *     │
 *     ├── PHYSICAL_MIRROR: SHADOW-EUR-001 ← Bank balance (liquidity)
 *     ├── PHYSICAL_MIRROR: SHADOW-USD-001
 *     │
 *     ├── TRANSACTION: VA-PAYABLES-EUR  ← No mirror needed (same currency as parent)
 *     ├── TRANSACTION: VA-RECEIVABLES-EUR
 *     ├── SETTLEMENT: SETTLE-EUR
 *     │
 *     └── TRANSACTION: VA-USD-OPS       ← Triggers USD Currency Mirror creation
 * 
 * @since v4.6.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HierarchyVaService {

    private final VirtualAccountRepository vaRepository;
    private final HierarchyNodeRepository hierarchyNodeRepository;
    private final FxRateService fxRateService;
    private final BalanceAggregationServiceEnhanced aggregationService;

    // Configuration
    private static final boolean AUTO_CREATE_EXCEPTION_FOR_NEW_CURRENCY = true;

    // ════════════════════════════════════════════════════════════════════════════════
    // HIERARCHY INITIALIZATION
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Initialize corporate hierarchy with ROOT VA and base Currency Mirror.
     * 
     * FIX v4.6.0: Now properly handles existing ROOT accounts:
     * - If ROOT exists with same baseCurrency → return existing
     * - If ROOT exists with different baseCurrency → convert to AGGREGATION, create new ROOT
     * - If multiple ROOTs exist → consolidate them (fix bug state)
     * 
     * @param corporateId Corporate ID
     * @param baseCurrency Base currency (e.g., EUR, USD, AED)
     * @param programId Program ID (REQUIRED - all VAs must belong to a program)
     * @param forceRecreate If true, converts existing ROOT to AGGREGATION and creates new ROOT
     * @return InitializationResult with ROOT VA and created infrastructure
     */
    @Transactional
    public InitializationResult initializeHierarchy(
            UUID corporateId, 
            String baseCurrency, 
            UUID programId,
            boolean forceRecreate) {
        
        log.info("╔════════════════════════════════════════════════════════════════════════════════╗");
        log.info("║ INITIALIZING CORPORATE HIERARCHY                                              ║");
        log.info("║ Corporate: {} | Base Currency: {} | Program: {}  ║", corporateId, baseCurrency, programId);
        log.info("║ Force Recreate: {}                                                           ║", forceRecreate);
        log.info("╚════════════════════════════════════════════════════════════════════════════════╝");

        if (programId == null) {
            throw new BusinessException("programId is REQUIRED for hierarchy initialization");
        }

        // FIX v4.6.0: Find ALL existing ROOT accounts for this corporate
        List<VirtualAccount> existingRoots = vaRepository.findByCorporateIdAndAccountCategory(
            corporateId, AccountCategory.ROOT);

        // CASE 1: Multiple ROOTs exist (BUG STATE) - consolidate them
        if (existingRoots.size() > 1) {
            log.warn("MULTIPLE ROOT ACCOUNTS DETECTED ({}): Consolidating...", existingRoots.size());
            return handleMultipleRoots(existingRoots, corporateId, baseCurrency, programId);
        }

        // CASE 2: Single ROOT exists
        if (existingRoots.size() == 1) {
            VirtualAccount existingRoot = existingRoots.get(0);
            String existingBaseCurrency = existingRoot.getBaseCurrency() != null 
                ? existingRoot.getBaseCurrency() 
                : existingRoot.getCurrencyCode();

            // CASE 2A: Same base currency and not forcing recreate - return existing
            if (!forceRecreate && baseCurrency.equalsIgnoreCase(existingBaseCurrency)) {
                log.info("ROOT already exists with same base currency ({}): {}", 
                    baseCurrency, existingRoot.getVaNumber());
                
                return InitializationResult.builder()
                    .rootVa(existingRoot)
                    .createdVas(Collections.emptyList())
                    .alreadyExists(true)
                    .message("Hierarchy already initialized with base currency " + baseCurrency)
                    .build();
            }

            // CASE 2B: Different base currency OR force recreate - convert old ROOT to AGGREGATION
            log.warn("Converting existing ROOT {} ({}) to AGGREGATION to create new ROOT ({})", 
                existingRoot.getVaNumber(), existingBaseCurrency, baseCurrency);
            
            return convertRootAndCreateNew(existingRoot, corporateId, baseCurrency, programId);
        }

        // CASE 3: No ROOT exists - create fresh hierarchy
        log.info("No existing ROOT found - creating fresh hierarchy");
        return createFreshHierarchy(corporateId, baseCurrency, programId);
    }

    /**
     * Backward compatible overload - defaults to NOT forcing recreate.
     */
    @Transactional
    public InitializationResult initializeHierarchy(UUID corporateId, String baseCurrency, UUID programId) {
        return initializeHierarchy(corporateId, baseCurrency, programId, false);
    }

    /**
     * Backward compatible overload - extracts programId from existing VAs if possible.
     */
    @Transactional
    public InitializationResult initializeHierarchy(UUID corporateId, String baseCurrency) {
        // Try to find programId from existing VAs for this corporate
        UUID programId = findProgramIdForCorporate(corporateId);
        if (programId == null) {
            throw new BusinessException(
                "Cannot initialize hierarchy without programId. " +
                "No existing VAs found for corporate " + corporateId + " to derive programId from. " +
                "Please use initializeHierarchy(corporateId, baseCurrency, programId) instead.");
        }
        return initializeHierarchy(corporateId, baseCurrency, programId, false);
    }

    /**
     * Handle the bug case where multiple ROOT accounts exist.
     * Strategy: Keep the oldest ROOT, convert others to AGGREGATION.
     */
    @Transactional
    private InitializationResult handleMultipleRoots(
            List<VirtualAccount> roots, 
            UUID corporateId, 
            String baseCurrency,
            UUID programId) {
        
        log.warn("════════════════════════════════════════════════════════════════════════════════");
        log.warn("FIXING MULTIPLE ROOT ACCOUNTS BUG");
        log.warn("Found {} ROOT accounts - this should never happen!", roots.size());
        log.warn("════════════════════════════════════════════════════════════════════════════════");

        // Sort by created date - keep the oldest
        roots.sort(Comparator.comparing(VirtualAccount::getCreatedAt));
        
        VirtualAccount primaryRoot = roots.get(0);
        List<VirtualAccount> duplicateRoots = roots.subList(1, roots.size());
        
        log.info("Keeping PRIMARY ROOT: {} (created: {})", 
            primaryRoot.getVaNumber(), primaryRoot.getCreatedAt());

        List<VirtualAccount> convertedAggregations = new ArrayList<>();

        // Convert duplicate ROOTs to AGGREGATION under primary ROOT
        for (VirtualAccount duplicateRoot : duplicateRoots) {
            log.warn("Converting DUPLICATE ROOT {} to AGGREGATION under primary ROOT", 
                duplicateRoot.getVaNumber());
            
            // Convert to AGGREGATION
            duplicateRoot.setAccountCategory(AccountCategory.AGGREGATION);
            duplicateRoot.setVaNumber("AGG-" + duplicateRoot.getVaNumber().replace("ROOT-", "LEGACY-"));
            duplicateRoot.setVaName(duplicateRoot.getVaName() + " (converted from ROOT)");
            duplicateRoot.setParentAccountId(primaryRoot.getId());
            duplicateRoot.setHierarchyLevel(1);
            duplicateRoot.setHierarchyPathVa("/ROOT/" + duplicateRoot.getVaNumber());
            
            // Update all children to point to new parent path
            List<VirtualAccount> children = vaRepository.findByParentAccountId(duplicateRoot.getId());
            for (VirtualAccount child : children) {
                updateHierarchyPath(child, duplicateRoot.getHierarchyPathVa());
            }
            
            VirtualAccount converted = vaRepository.save(duplicateRoot);
            convertedAggregations.add(converted);
            
            log.info("✓ Converted {} to AGGREGATION: {}", 
                duplicateRoot.getVaNumber(), converted.getVaNumber());
        }

        // Check if primary ROOT needs base currency update
        String existingBaseCurrency = primaryRoot.getBaseCurrency() != null 
            ? primaryRoot.getBaseCurrency() 
            : primaryRoot.getCurrencyCode();
        
        if (!baseCurrency.equalsIgnoreCase(existingBaseCurrency)) {
            log.warn("Updating PRIMARY ROOT base currency from {} to {}", 
                existingBaseCurrency, baseCurrency);
            
            primaryRoot.setBaseCurrency(baseCurrency);
            primaryRoot.setCurrencyCode(baseCurrency);
            primaryRoot = vaRepository.save(primaryRoot);
        }

        log.warn("════════════════════════════════════════════════════════════════════════════════");
        log.warn("MULTIPLE ROOT FIX COMPLETE");
        log.warn("Primary ROOT: {} | Converted to AGGREGATION: {}", 
            primaryRoot.getVaNumber(), convertedAggregations.size());
        log.warn("════════════════════════════════════════════════════════════════════════════════");

        return InitializationResult.builder()
            .rootVa(primaryRoot)
            .createdVas(convertedAggregations)
            .alreadyExists(true)
            .message(String.format("Fixed multiple ROOT bug - converted %d duplicates to AGGREGATION", 
                convertedAggregations.size()))
            .build();
    }

    /**
     * Convert existing ROOT to AGGREGATION and create new ROOT above it.
     */
    @Transactional
    private InitializationResult convertRootAndCreateNew(
            VirtualAccount oldRoot,
            UUID corporateId,
            String newBaseCurrency,
            UUID programId) {
        
        log.info("════════════════════════════════════════════════════════════════════════════════");
        log.info("CONVERTING EXISTING ROOT TO AGGREGATION");
        log.info("Old ROOT: {} (baseCurrency: {})", oldRoot.getVaNumber(), oldRoot.getBaseCurrency());
        log.info("New ROOT baseCurrency: {}", newBaseCurrency);
        log.info("════════════════════════════════════════════════════════════════════════════════");

        List<VirtualAccount> createdVas = new ArrayList<>();

        // 1. Create NEW ROOT with new base currency
        VirtualAccount newRoot = createRootVaInternal(corporateId, newBaseCurrency, programId);
        createdVas.add(newRoot);
        log.info("✓ Created NEW ROOT: {} with baseCurrency {}", newRoot.getVaNumber(), newBaseCurrency);

        // 2. Convert OLD ROOT to AGGREGATION
        String oldRootNumber = oldRoot.getVaNumber();
        String oldBaseCurrency = oldRoot.getBaseCurrency() != null 
            ? oldRoot.getBaseCurrency() 
            : oldRoot.getCurrencyCode();
        
        oldRoot.setAccountCategory(AccountCategory.AGGREGATION);
        oldRoot.setVaNumber("AGG-" + oldRootNumber.replace("ROOT-", "LEGACY-"));
        oldRoot.setVaName("Legacy Root (" + oldBaseCurrency + ") - converted to AGGREGATION");
        oldRoot.setParentAccountId(newRoot.getId());
        oldRoot.setHierarchyLevel(1);
        oldRoot.setHierarchyPathVa("/ROOT/AGG-LEGACY");
        oldRoot.setBaseCurrency(oldBaseCurrency); // Keep its original base currency as AGGREGATION
        
        VirtualAccount convertedAggregation = vaRepository.save(oldRoot);
        createdVas.add(convertedAggregation);
        log.info("✓ Converted old ROOT {} to AGGREGATION: {}", oldRootNumber, convertedAggregation.getVaNumber());

        // 3. Update hierarchy paths for all descendants of converted ROOT
        List<VirtualAccount> descendants = findAllDescendants(convertedAggregation.getId());
        for (VirtualAccount descendant : descendants) {
            updateHierarchyPath(descendant, convertedAggregation.getHierarchyPathVa());
        }
        log.info("✓ Updated hierarchy paths for {} descendants", descendants.size());

        // v5.5.0: NO automatic Currency Mirror creation during conversion
        // Currency Mirrors will be created/updated when Transaction VAs trigger propagation
        log.info("Note: Currency Mirrors will be created/updated based on existing Transaction VAs");

        log.info("════════════════════════════════════════════════════════════════════════════════");
        log.info("ROOT CONVERSION COMPLETE");
        log.info("New hierarchy: {} → {}", newRoot.getVaNumber(), convertedAggregation.getVaNumber());
        log.info("════════════════════════════════════════════════════════════════════════════════");

        return InitializationResult.builder()
            .rootVa(newRoot)
            .baseCurrencyMirror(null)  // v5.5.0: No automatic mirror creation
            .createdVas(createdVas)
            .alreadyExists(false)
            .message("Converted existing ROOT to AGGREGATION and created new ROOT with base currency " + newBaseCurrency)
            .build();
    }

    /**
     * Create fresh hierarchy (no existing ROOT).
     *
     * v5.5.0: Removed automatic base currency mirror creation.
     * Currency Mirrors are now ONLY created when Transaction VAs are created.
     */
    @Transactional
    private InitializationResult createFreshHierarchy(
            UUID corporateId,
            String baseCurrency,
            UUID programId) {

        List<VirtualAccount> createdVas = new ArrayList<>();

        // 1. Create ROOT VA with programId
        VirtualAccount root = createRootVaInternal(corporateId, baseCurrency, programId);
        createdVas.add(root);
        log.info("✓ Created ROOT: {} (baseCurrency: {}, programId: {})",
            root.getVaNumber(), baseCurrency, programId);

        // v5.5.0: NO automatic Currency Mirror creation at initialization
        // Currency Mirrors will be created when Transaction VAs are created
        log.info("Note: Currency Mirrors will be created when Transaction VAs are added");

        return InitializationResult.builder()
            .rootVa(root)
            .baseCurrencyMirror(null)  // No mirror created at initialization
            .createdVas(createdVas)
            .alreadyExists(false)
            .message("Hierarchy initialized successfully (Currency Mirrors created on demand)")
            .build();
    }

    /**
     * Find all descendants of a VA recursively.
     */
    @Transactional(readOnly = true)
    private List<VirtualAccount> findAllDescendants(UUID parentId) {
        List<VirtualAccount> allDescendants = new ArrayList<>();
        List<VirtualAccount> directChildren = vaRepository.findByParentAccountId(parentId);
        
        for (VirtualAccount child : directChildren) {
            allDescendants.add(child);
            allDescendants.addAll(findAllDescendants(child.getId()));
        }
        
        return allDescendants;
    }

    /**
     * Update hierarchy path for a VA based on new parent path.
     */
    @Transactional
    private void updateHierarchyPath(VirtualAccount va, String newParentPath) {
        String oldPath = va.getHierarchyPathVa();
        String newPath = newParentPath + "/" + va.getVaNumber();
        
        va.setHierarchyPathVa(newPath);
        va.setHierarchyLevel((va.getHierarchyLevel() != null ? va.getHierarchyLevel() : 0) + 1);
        
        vaRepository.save(va);
        
        log.debug("Updated path for {}: {} → {}", va.getVaNumber(), oldPath, newPath);
        
        // Recursively update children
        List<VirtualAccount> children = vaRepository.findByParentAccountId(va.getId());
        for (VirtualAccount child : children) {
            updateHierarchyPath(child, newPath);
        }
    }

    /**
     * Ensure ROOT VA exists for a corporate. Creates if not exists.
     */
    @Transactional
    public VirtualAccount ensureRootVa(UUID corporateId, String baseCurrency, UUID programId) {
        Optional<VirtualAccount> existingRoot = vaRepository.findRootAccount(corporateId);
        if (existingRoot.isPresent()) {
            return existingRoot.get();
        }
        return createRootVaInternal(corporateId, baseCurrency, programId);
    }

    /**
     * Backward compatible overload.
     */
    @Transactional
    public VirtualAccount ensureRootVa(UUID corporateId, String baseCurrency) {
        UUID programId = findProgramIdForCorporate(corporateId);
        if (programId == null) {
            throw new BusinessException("Cannot create ROOT VA without programId");
        }
        return ensureRootVa(corporateId, baseCurrency, programId);
    }

    private VirtualAccount createRootVaInternal(UUID corporateId, String baseCurrency, UUID programId) {
        log.info("Creating ROOT VA for corporate {} with base currency {} (programId: {})", 
            corporateId, baseCurrency, programId);

        VirtualAccount root = VirtualAccount.builder()
            .vaNumber("ROOT-" + corporateId.toString().substring(0, 8).toUpperCase())
            .vaName("Corporate Root Account")
            .corporateId(corporateId)
            .programId(programId)
            .currencyCode(baseCurrency)
            .baseCurrency(baseCurrency)
            .physicalAccountId(null)
            .accountType(AccountType.VIRTUAL)
            .accountCategory(AccountCategory.ROOT)
            .parentAccountId(null)
            .hierarchyLevel(0)
            .hierarchyPathVa("/ROOT")
            .aggregatedBalance(BigDecimal.ZERO)
            .aggregatedBalanceBase(BigDecimal.ZERO)
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .status(VaStatus.ACTIVE)
            .build();

        root = vaRepository.save(root);
        log.info("✓ Created ROOT VA {} (programId: {})", root.getVaNumber(), programId);

        return root;
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // CURRENCY INFRASTRUCTURE - Called by ShadowAccountService and VirtualAccountService
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Ensure all necessary Currency Mirrors exist when a new VA is created.
     * 
     * FIX v4.5.2: Now extracts programId from newVa and passes it to all created infrastructure.
     * 
     * @param newVa The newly created VA (Shadow or Transaction)
     * @return List of all VAs created (mirrors, exception) - empty if same currency
     */
    /**
     * Ensure all necessary Currency Mirrors exist when a new VA is created.
     *
     * v5.5.0: Currency Mirrors are now created for ALL currencies when Transaction VAs are created,
     * INCLUDING the base currency. This ensures:
     * - Currency Mirrors only exist when there are actual Transaction VAs for that currency
     * - Mirrors are created on demand, not at hierarchy initialization
     *
     * @param newVa The newly created VA (Transaction, Collection, or Disbursement)
     * @return List of all VAs created (mirrors, exception)
     */
    @Transactional
    public List<VirtualAccount> ensureCurrencyInfrastructure(VirtualAccount newVa) {
        log.info("Ensuring currency infrastructure for new VA {} ({}) category={}",
            newVa.getVaNumber(), newVa.getCurrencyCode(), newVa.getAccountCategory());

        List<VirtualAccount> created = new ArrayList<>();
        String vaCurrency = newVa.getCurrencyCode();
        UUID corporateId = newVa.getCorporateId();
        UUID programId = newVa.getProgramId();

        // Validate programId
        if (programId == null) {
            log.warn("Source VA {} has no programId - attempting to derive from hierarchy", newVa.getVaNumber());
            programId = findProgramIdFromHierarchy(newVa);
            if (programId == null) {
                throw new BusinessException("Cannot create currency infrastructure: programId is null for VA " +
                    newVa.getVaNumber());
            }
        }

        // Get base currency for reference (used for FX calculations, not for skipping)
        String baseCurrency = newVa.getBaseCurrency();
        if (baseCurrency == null || baseCurrency.isEmpty()) {
            log.debug("VA {} has no baseCurrency set - deriving from hierarchy", newVa.getVaNumber());
            baseCurrency = findBaseCurrencyFromParent(newVa);
        } else {
            log.debug("VA {} has baseCurrency={} already set", newVa.getVaNumber(), baseCurrency);
        }

        // v5.5.0: ALWAYS create Currency Mirrors for any currency (including base currency)
        // This ensures mirrors exist only when there are actual Transaction VAs
        log.info("VA {} with currency {} - creating Currency Mirrors (base: {}, programId: {})",
            newVa.getVaNumber(), vaCurrency, baseCurrency, programId);

        // 1. Propagate Currency Mirrors up the entire hierarchy (for ALL currencies now)
        List<VirtualAccount> mirrors = propagateCurrencyMirrorsUp(newVa, programId);
        created.addAll(mirrors);

        // 2. Create Exception VA at ROOT level only for foreign currencies (if enabled)
        if (AUTO_CREATE_EXCEPTION_FOR_NEW_CURRENCY &&
            baseCurrency != null && !baseCurrency.equalsIgnoreCase(vaCurrency)) {
            try {
                VirtualAccount exception = ensureExceptionVaAtRoot(vaCurrency, corporateId, programId, newVa);
                if (exception != null) {
                    created.add(exception);
                }
            } catch (Exception e) {
                log.warn("Failed to create Exception VA for currency {}: {}", vaCurrency, e.getMessage());
            }
        }

        log.info("Currency infrastructure complete for {} - created {} VAs for currency {}",
            newVa.getVaNumber(), created.size(), vaCurrency);

        return created;
    }

    /**
     * Find programId by traversing up the parent hierarchy.
     */
    private UUID findProgramIdFromHierarchy(VirtualAccount va) {
        UUID currentParentId = va.getParentAccountId();
        Set<UUID> visited = new HashSet<>();
        
        while (currentParentId != null && !visited.contains(currentParentId)) {
            visited.add(currentParentId);
            
            VirtualAccount parent = vaRepository.findById(currentParentId).orElse(null);
            if (parent == null) break;
            
            if (parent.getProgramId() != null) {
                log.info("Found programId {} from parent VA {}", parent.getProgramId(), parent.getVaNumber());
                return parent.getProgramId();
            }
            
            currentParentId = parent.getParentAccountId();
        }
        
        return null;
    }

    /**
     * Find programId from any existing VA for a corporate.
     */
    private UUID findProgramIdForCorporate(UUID corporateId) {
        List<VirtualAccount> vas = vaRepository.findByCorporateId(corporateId);
        return vas.stream()
            .map(VirtualAccount::getProgramId)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    /**
     * Find base currency by traversing up the parent hierarchy.
     *
     * FIX v5.3.0: Now also traverses via HierarchyNode when parentAccountId is null.
     * This handles the case where a VA is created via hierarchy nodes without
     * parentAccountId being set.
     */
    private String findBaseCurrencyFromParent(VirtualAccount va) {
        UUID currentParentId = va.getParentAccountId();
        Set<UUID> visited = new HashSet<>();

        // If no parentAccountId, try to find via hierarchy node first
        if (currentParentId == null && va.getHierarchyNodeId() != null) {
            log.debug("VA {} has no parentAccountId - finding base currency via hierarchy node {}",
                va.getVaNumber(), va.getHierarchyNodeId());
            String baseCurrency = findBaseCurrencyViaHierarchyNode(va.getHierarchyNodeId());
            if (baseCurrency != null) {
                return baseCurrency;
            }
        }

        // Traverse via parentAccountId chain
        while (currentParentId != null && !visited.contains(currentParentId)) {
            visited.add(currentParentId);

            VirtualAccount parent = vaRepository.findById(currentParentId).orElse(null);
            if (parent == null) break;

            if (parent.getBaseCurrency() != null) {
                return parent.getBaseCurrency();
            }

            if (parent.getAccountCategory() == AccountCategory.AGGREGATION ||
                parent.getAccountCategory() == AccountCategory.ROOT) {
                return parent.getCurrencyCode();
            }

            currentParentId = parent.getParentAccountId();
        }

        // Last resort: try to find from program
        if (va.getProgramId() != null) {
            String baseCurrency = findBaseCurrencyFromProgram(va.getProgramId());
            if (baseCurrency != null) {
                log.debug("Found base currency {} from program {}", baseCurrency, va.getProgramId());
                return baseCurrency;
            }
        }

        return null;
    }

    /**
     * Find base currency by traversing up the HierarchyNode tree.
     */
    private String findBaseCurrencyViaHierarchyNode(UUID hierarchyNodeId) {
        HierarchyNode currentNode = hierarchyNodeRepository.findById(hierarchyNodeId).orElse(null);
        if (currentNode == null) {
            return null;
        }

        // First check the program's root for base currency
        UUID programId = currentNode.getProgramId();
        if (programId != null) {
            // Try to find ROOT VA for this program - it defines the base currency
            List<VirtualAccount> rootVas = vaRepository.findByProgramIdAndAccountCategory(programId, AccountCategory.ROOT);
            if (!rootVas.isEmpty()) {
                VirtualAccount rootVa = rootVas.get(0);
                if (rootVa.getBaseCurrency() != null) {
                    log.debug("Found base currency {} from ROOT VA {} for program {}",
                        rootVa.getBaseCurrency(), rootVa.getVaNumber(), programId);
                    return rootVa.getBaseCurrency();
                }
                if (rootVa.getCurrencyCode() != null) {
                    log.debug("Found base currency {} (from currencyCode) from ROOT VA {} for program {}",
                        rootVa.getCurrencyCode(), rootVa.getVaNumber(), programId);
                    return rootVa.getCurrencyCode();
                }
            }
        }

        // Traverse up hierarchy nodes looking for one with a linked VA that has baseCurrency
        UUID parentNodeId = currentNode.getParentId();
        Set<UUID> visited = new HashSet<>();

        while (parentNodeId != null && !visited.contains(parentNodeId)) {
            visited.add(parentNodeId);

            HierarchyNode parentNode = hierarchyNodeRepository.findById(parentNodeId).orElse(null);
            if (parentNode == null) break;

            // Check if this node has a linked VA with baseCurrency
            if (parentNode.getVirtualAccountId() != null) {
                VirtualAccount parentVa = vaRepository.findById(parentNode.getVirtualAccountId()).orElse(null);
                if (parentVa != null) {
                    if (parentVa.getBaseCurrency() != null) {
                        log.debug("Found base currency {} from parent VA {} via hierarchy node",
                            parentVa.getBaseCurrency(), parentVa.getVaNumber());
                        return parentVa.getBaseCurrency();
                    }
                    if ((parentVa.getAccountCategory() == AccountCategory.AGGREGATION ||
                         parentVa.getAccountCategory() == AccountCategory.ROOT) &&
                        parentVa.getCurrencyCode() != null) {
                        log.debug("Found base currency {} (from currencyCode) from parent {} VA {} via hierarchy node",
                            parentVa.getCurrencyCode(), parentVa.getAccountCategory(), parentVa.getVaNumber());
                        return parentVa.getCurrencyCode();
                    }
                }
            }

            parentNodeId = parentNode.getParentId();
        }

        return null;
    }

    /**
     * Find base currency from program's ROOT VA.
     */
    private String findBaseCurrencyFromProgram(UUID programId) {
        List<VirtualAccount> rootVas = vaRepository.findByProgramIdAndAccountCategory(programId, AccountCategory.ROOT);
        if (!rootVas.isEmpty()) {
            VirtualAccount rootVa = rootVas.get(0);
            return rootVa.getBaseCurrency() != null ? rootVa.getBaseCurrency() : rootVa.getCurrencyCode();
        }
        return null;
    }

    /**
     * Public method to ensure Currency Mirror exists at a specific level.
     *
     * v5.5.0: Updated to create mirrors for ALL currencies including base currency.
     */
    @Transactional
    public VirtualAccount ensureCurrencyMirrorAtLevel(UUID parentVaId, String currency,
                                                       UUID corporateId, VirtualAccount sourceVa) {
        VirtualAccount parent = vaRepository.findById(parentVaId)
            .orElseThrow(() -> new ResourceNotFoundException("Parent VA not found: " + parentVaId));

        String baseCurrency = parent.getBaseCurrency() != null ?
            parent.getBaseCurrency() : parent.getCurrencyCode();

        // v5.5.0: REMOVED the base currency skip logic
        // Now create mirrors for ALL currencies, including base currency

        if (checkCurrencyMirrorExists(parentVaId, currency)) {
            return vaRepository.findByParentAccountIdAndAccountCategory(parentVaId, AccountCategory.CURRENCY_MIRROR)
                .stream()
                .filter(m -> currency.equals(m.getCurrencyCode()))
                .findFirst()
                .orElseThrow();
        }

        // Get programId from source or parent
        UUID programId = sourceVa != null && sourceVa.getProgramId() != null
            ? sourceVa.getProgramId()
            : parent.getProgramId();

        return createCurrencyMirrorInternal(parentVaId, currency, baseCurrency, corporateId, programId, sourceVa);
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // CURRENCY MIRROR PROPAGATION (Internal)
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Propagate Currency Mirrors up the hierarchy from a new VA to ROOT.
     *
     * v5.5.0: Now creates mirrors for ALL currencies, including the base currency.
     * This ensures Currency Mirrors only exist when there are actual Transaction VAs.
     *
     * FIX v4.5.2: Now passes programId to all created mirrors.
     */
    @Transactional
    public List<VirtualAccount> propagateCurrencyMirrorsUp(VirtualAccount newVa, UUID programId) {
        List<VirtualAccount> createdMirrors = new ArrayList<>();
        String currency = newVa.getCurrencyCode();
        UUID corporateId = newVa.getCorporateId();

        UUID currentParentId = newVa.getParentAccountId();
        Set<UUID> visited = new HashSet<>();

        log.info("Starting Currency Mirror propagation for VA {} (currency: {}, parentAccountId: {})",
            newVa.getVaNumber(), currency, currentParentId);

        // If no parentAccountId, try to find via hierarchy node
        if (currentParentId == null && newVa.getHierarchyNodeId() != null) {
            log.info("VA {} has no parentAccountId but has hierarchyNodeId {} - attempting to find parent via hierarchy",
                newVa.getVaNumber(), newVa.getHierarchyNodeId());
            currentParentId = findParentVaIdViaHierarchyNode(newVa.getHierarchyNodeId());
            log.info("Found parentVaId via hierarchy: {}", currentParentId);
        }

        if (currentParentId == null) {
            log.warn("Cannot propagate Currency Mirrors for VA {}: no parent VA found (parentAccountId and hierarchyNodeId both lead to null)",
                newVa.getVaNumber());
            return createdMirrors;
        }

        while (currentParentId != null && !visited.contains(currentParentId)) {
            visited.add(currentParentId);

            VirtualAccount parent = vaRepository.findById(currentParentId).orElse(null);
            if (parent == null) {
                log.warn("Parent VA not found: {}", currentParentId);
                break;
            }

            log.debug("Checking parent VA: {} (category: {}, baseCurrency: {})",
                parent.getVaNumber(), parent.getAccountCategory(), parent.getBaseCurrency());

            if (parent.getAccountCategory() == AccountCategory.AGGREGATION ||
                parent.getAccountCategory() == AccountCategory.ROOT) {

                String parentBaseCurrency = parent.getBaseCurrency() != null ?
                    parent.getBaseCurrency() : parent.getCurrencyCode();

                // v5.5.0: REMOVED the base currency skip logic
                // Now create mirrors for ALL currencies, including base currency
                // This ensures mirrors only exist when Transaction VAs exist

                // Use parent's programId if available, otherwise use the passed programId
                UUID effectiveProgramId = parent.getProgramId() != null ? parent.getProgramId() : programId;

                if (!checkCurrencyMirrorExists(currentParentId, currency, effectiveProgramId)) {

                    VirtualAccount mirror = createCurrencyMirrorInternal(
                        currentParentId, currency, parentBaseCurrency, corporateId, effectiveProgramId, newVa);

                    createdMirrors.add(mirror);

                    log.info("✓ Auto-created CURRENCY_MIRROR {} at {} level under {} for currency {} (programId: {})",
                        mirror.getVaNumber(),
                        parent.getAccountCategory(),
                        parent.getVaNumber(),
                        currency,
                        effectiveProgramId);
                } else {
                    // v5.5.2: Currency Mirror exists - refresh its balance to include the new Transaction VA
                    log.debug("Currency Mirror for {} already exists under {} - refreshing balance", currency, parent.getVaNumber());
                    try {
                        VirtualAccount existingMirror = vaRepository.findByParentAccountIdAndAccountCategory(
                            currentParentId, AccountCategory.CURRENCY_MIRROR)
                            .stream()
                            .filter(m -> currency.equals(m.getCurrencyCode()))
                            .findFirst()
                            .orElse(null);
                        if (existingMirror != null) {
                            aggregationService.refreshVaBalance(existingMirror.getId());
                            log.info("Refreshed existing CURRENCY_MIRROR {} balance for new {} VA",
                                existingMirror.getVaNumber(), currency);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to refresh existing Currency Mirror balance: {}", e.getMessage());
                    }
                }
            }

            currentParentId = parent.getParentAccountId();
        }

        if (createdMirrors.isEmpty()) {
            log.info("Currency Mirrors for {} already exist in hierarchy - balances refreshed", currency);
        } else {
            log.info("Propagated {} Currency Mirrors for currency {} up the hierarchy",
                createdMirrors.size(), currency);
        }

        return createdMirrors;
    }

    /**
     * Backward compatible overload.
     */
    @Transactional
    public List<VirtualAccount> propagateCurrencyMirrorsUp(VirtualAccount newVa) {
        UUID programId = newVa.getProgramId();
        if (programId == null) {
            programId = findProgramIdFromHierarchy(newVa);
        }
        return propagateCurrencyMirrorsUp(newVa, programId);
    }

    /**
     * Check if a Currency Mirror exists for a specific currency under a parent.
     *
     * @param parentId Parent VA ID
     * @param currency Currency code to check
     * @return true if Currency Mirror exists
     */
    public boolean checkCurrencyMirrorExists(UUID parentId, String currency) {
        List<VirtualAccount> mirrors = vaRepository.findByParentAccountIdAndAccountCategory(
            parentId, AccountCategory.CURRENCY_MIRROR);

        return mirrors.stream()
            .anyMatch(m -> currency.equals(m.getCurrencyCode()));
    }

    /**
     * Check if a Currency Mirror exists for a specific currency under a parent within a program.
     * This is the safer version that also validates programId.
     *
     * @param parentId Parent VA ID
     * @param currency Currency code to check
     * @param programId Program ID to filter by
     * @return true if Currency Mirror exists for this program
     */
    public boolean checkCurrencyMirrorExists(UUID parentId, String currency, UUID programId) {
        List<VirtualAccount> mirrors = vaRepository.findByParentAccountIdAndAccountCategory(
            parentId, AccountCategory.CURRENCY_MIRROR);

        return mirrors.stream()
            .filter(m -> programId == null || programId.equals(m.getProgramId()))
            .anyMatch(m -> currency.equals(m.getCurrencyCode()));
    }

    /**
     * Create Currency Mirror (internal method).
     * 
     * FIX v4.5.2: Now accepts and sets programId.
     */
    private VirtualAccount createCurrencyMirrorInternal(
            UUID parentVaId,
            String currency,
            String baseCurrency,
            UUID corporateId,
            UUID programId,
            VirtualAccount sourceVa) {

        VirtualAccount parent = vaRepository.findById(parentVaId)
            .orElseThrow(() -> new ResourceNotFoundException("Parent VA not found: " + parentVaId));

        if (parent.getAccountCategory() != AccountCategory.ROOT && 
            parent.getAccountCategory() != AccountCategory.AGGREGATION) {
            throw new BusinessException(
                "Currency Mirror can only be created under ROOT or AGGREGATION, not " + 
                parent.getAccountCategory());
        }

        // Use parent's programId if provided one is null
        UUID effectiveProgramId = programId != null ? programId : parent.getProgramId();
        if (effectiveProgramId == null) {
            log.warn("Creating Currency Mirror without programId - this may cause issues");
        }

        // Get FX rate
        BigDecimal fxRate = BigDecimal.ONE;
        if (!currency.equals(baseCurrency)) {
            try {
                fxRate = fxRateService.getRate(currency, baseCurrency);
            } catch (Exception e) {
                log.warn("Could not get FX rate {}/{}: {}", currency, baseCurrency, e.getMessage());
            }
        }

        // Generate VA number based on parent level
        String levelPrefix = parent.getAccountCategory() == AccountCategory.ROOT ? "ROOT" :
            parent.getVaNumber().replace("AGG-", "");
        String vaNumber = "M-" + levelPrefix + "-" + currency;

        // Calculate hierarchy
        String hierarchyPath = parent.getHierarchyPathVa() != null 
            ? parent.getHierarchyPathVa() + "/M-" + currency
            : "/M-" + currency;
        int level = parent.getHierarchyLevel() != null ? parent.getHierarchyLevel() + 1 : 1;

        UUID physicalAccountId = null;
        if (sourceVa != null && sourceVa.getPhysicalAccountId() != null) {
            physicalAccountId = sourceVa.getPhysicalAccountId();
        }

        VirtualAccount mirror = VirtualAccount.builder()
            .vaNumber(vaNumber)
            .vaName(currency + " Currency Mirror at " + parent.getVaNumber())
            .corporateId(corporateId)
            .programId(effectiveProgramId)
            .currencyCode(currency)
            .baseCurrency(baseCurrency)
            .physicalAccountId(physicalAccountId)
            .accountType(AccountType.VIRTUAL)
            .accountCategory(AccountCategory.CURRENCY_MIRROR)
            .parentAccountId(parentVaId)
            .hierarchyLevel(level)
            .hierarchyPathVa(hierarchyPath)
            // Mirror-specific fields
            .mirrorBalance(BigDecimal.ZERO)
            .fxRate(fxRate)
            .fxRateAt(LocalDateTime.now())
            .fxRateSource("SYSTEM")
            .balanceInBase(BigDecimal.ZERO)
            // Ownership
            .owningEntityId(sourceVa != null ? sourceVa.getOwningEntityId() : parent.getOwningEntityId())
            .owningEntityCode(sourceVa != null ? sourceVa.getOwningEntityCode() : parent.getOwningEntityCode())
            // Standard fields
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .aggregatedBalance(BigDecimal.ZERO)
            .status(VaStatus.ACTIVE)
            .build();

        mirror = vaRepository.save(mirror);

        log.info("Created CURRENCY_MIRROR {} for currency {} under {} (level: {}, programId: {})",
            mirror.getVaNumber(), currency, parent.getVaNumber(), level, effectiveProgramId);

        // v5.5.2: Immediately recalculate the mirror balance by summing sibling Transaction VAs
        try {
            mirror = aggregationService.refreshVaBalance(mirror.getId());
            log.info("Initialized CURRENCY_MIRROR {} balance: mirrorBalance={}, balanceInBase={}",
                mirror.getVaNumber(), mirror.getMirrorBalance(), mirror.getBalanceInBase());
        } catch (Exception e) {
            log.warn("Failed to initialize CURRENCY_MIRROR balance for {}: {}",
                mirror.getVaNumber(), e.getMessage());
        }

        return mirror;
    }

    /**
     * Create Currency Mirror - Public API (backward compatibility).
     */
    @Transactional
    public VirtualAccount createCurrencyMirror(
            UUID parentVaId,
            String currency,
            String baseCurrency,
            UUID corporateId,
            VirtualAccount sourceVa) {

        if (checkCurrencyMirrorExists(parentVaId, currency)) {
            log.debug("Currency Mirror for {} already exists under parent", currency);
            return vaRepository.findByParentAccountIdAndAccountCategory(parentVaId, AccountCategory.CURRENCY_MIRROR)
                .stream()
                .filter(m -> currency.equals(m.getCurrencyCode()))
                .findFirst()
                .orElseThrow();
        }

        // Get programId from source or parent
        UUID programId = null;
        if (sourceVa != null && sourceVa.getProgramId() != null) {
            programId = sourceVa.getProgramId();
        } else {
            VirtualAccount parent = vaRepository.findById(parentVaId).orElse(null);
            if (parent != null) {
                programId = parent.getProgramId();
            }
        }

        return createCurrencyMirrorInternal(parentVaId, currency, baseCurrency, corporateId, programId, sourceVa);
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // EXCEPTION VA
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Ensure Exception VA exists at ROOT level for a currency.
     * 
     * FIX v4.5.2: Now accepts and sets programId.
     */
    @Transactional
    public VirtualAccount ensureExceptionVaAtRoot(
            String currency, 
            UUID corporateId,
            UUID programId,
            VirtualAccount sourceVa) {

        Optional<VirtualAccount> rootOpt = vaRepository.findRootAccount(corporateId);
        if (rootOpt.isEmpty()) {
            log.warn("No ROOT VA found for corporate {} - cannot create Exception VA", corporateId);
            return null;
        }

        VirtualAccount root = rootOpt.get();
        
        String baseCurrency = root.getBaseCurrency() != null ? root.getBaseCurrency() : root.getCurrencyCode();
        if (baseCurrency.equalsIgnoreCase(currency)) {
            log.debug("Currency {} matches ROOT base currency - no Exception VA needed", currency);
            return null;
        }

        // Check if Exception VA exists for this currency
        List<VirtualAccount> exceptions = vaRepository.findByCorporateIdAndAccountCategory(
            corporateId, AccountCategory.EXCEPTION);

        boolean exists = exceptions.stream()
            .anyMatch(e -> currency.equals(e.getCurrencyCode()));

        if (exists) {
            log.debug("Exception VA for {} already exists", currency);
            return null;
        }

        // Use root's programId if provided one is null
        UUID effectiveProgramId = programId != null ? programId : root.getProgramId();
        if (effectiveProgramId == null && sourceVa != null) {
            effectiveProgramId = sourceVa.getProgramId();
        }

        UUID physicalAccountId = null;
        if (sourceVa != null && sourceVa.getPhysicalAccountId() != null) {
            physicalAccountId = sourceVa.getPhysicalAccountId();
        }

        String vaNumber = "EXCEPT-" + currency;
        String hierarchyPath = root.getHierarchyPathVa() != null 
            ? root.getHierarchyPathVa() + "/" + vaNumber
            : "/" + vaNumber;
        int level = root.getHierarchyLevel() != null ? root.getHierarchyLevel() + 1 : 1;

        VirtualAccount exception = VirtualAccount.builder()
            .vaNumber(vaNumber)
            .vaName(currency + " Exception Account")
            .corporateId(corporateId)
            .programId(effectiveProgramId)
            .currencyCode(currency)
            .physicalAccountId(physicalAccountId)
            .accountType(AccountType.VIRTUAL)
            .accountCategory(AccountCategory.EXCEPTION)
            .specialType(VaSpecialType.EXCEPTION)
            .parentAccountId(root.getId())
            .hierarchyLevel(level)
            .hierarchyPathVa(hierarchyPath)
            .owningEntityId(sourceVa != null ? sourceVa.getOwningEntityId() : root.getOwningEntityId())
            .owningEntityCode(sourceVa != null ? sourceVa.getOwningEntityCode() : root.getOwningEntityCode())
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .status(VaStatus.ACTIVE)
            .build();

        exception = vaRepository.save(exception);

        log.info("✓ Auto-created EXCEPTION VA {} for foreign currency {} at ROOT level (programId: {})", 
            exception.getVaNumber(), currency, effectiveProgramId);

        return exception;
    }

    /**
     * Backward compatible overload.
     */
    @Transactional
    public VirtualAccount ensureExceptionVaAtRoot(
            String currency, 
            UUID corporateId,
            VirtualAccount sourceVa) {
        UUID programId = sourceVa != null ? sourceVa.getProgramId() : null;
        return ensureExceptionVaAtRoot(currency, corporateId, programId, sourceVa);
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // AGGREGATION VA
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Create an AGGREGATION VA under a parent.
     * 
     * FIX v4.5.2: Now accepts and sets programId.
     */
    @Transactional
    public VirtualAccount createAggregationVa(
            String name, 
            String code,
            UUID parentVaId,
            UUID corporateId,
            UUID programId,
            String baseCurrency,
            UUID owningEntityId,
            String owningEntityCode) {

        VirtualAccount parent = vaRepository.findById(parentVaId)
            .orElseThrow(() -> new ResourceNotFoundException("Parent VA not found: " + parentVaId));

        if (parent.getAccountCategory() != AccountCategory.ROOT && 
            parent.getAccountCategory() != AccountCategory.AGGREGATION) {
            throw new BusinessException(
                "AGGREGATION parent must be ROOT or AGGREGATION, not " + parent.getAccountCategory());
        }

        // Use parent's programId if provided one is null
        UUID effectiveProgramId = programId != null ? programId : parent.getProgramId();

        String vaNumber = "AGG-" + code.toUpperCase();
        String hierarchyPath = parent.getHierarchyPathVa() != null 
            ? parent.getHierarchyPathVa() + "/" + code.toUpperCase()
            : "/" + code.toUpperCase();
        int level = parent.getHierarchyLevel() != null ? parent.getHierarchyLevel() + 1 : 1;

        VirtualAccount aggregation = VirtualAccount.builder()
            .vaNumber(vaNumber)
            .vaName(name)
            .corporateId(corporateId)
            .programId(effectiveProgramId)
            .currencyCode(baseCurrency)
            .baseCurrency(baseCurrency)
            .physicalAccountId(null)
            .accountType(AccountType.VIRTUAL)
            .accountCategory(AccountCategory.AGGREGATION)
            .parentAccountId(parentVaId)
            .hierarchyLevel(level)
            .hierarchyPathVa(hierarchyPath)
            .owningEntityId(owningEntityId)
            .owningEntityCode(owningEntityCode)
            .aggregatedBalance(BigDecimal.ZERO)
            .aggregatedBalanceBase(BigDecimal.ZERO)
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .status(VaStatus.ACTIVE)
            .build();

        aggregation = vaRepository.save(aggregation);
        log.info("✓ Created AGGREGATION VA {} at level {} (programId: {})", 
            aggregation.getVaNumber(), level, effectiveProgramId);

        return aggregation;
    }

    /**
     * Backward compatible overload.
     */
    @Transactional
    public VirtualAccount createAggregationVa(
            String name, 
            String code,
            UUID parentVaId,
            UUID corporateId,
            String baseCurrency,
            UUID owningEntityId,
            String owningEntityCode) {
        return createAggregationVa(name, code, parentVaId, corporateId, null, baseCurrency, 
            owningEntityId, owningEntityCode);
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // BALANCE RECALCULATION - Per Currency Mirror Propagation Document
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Recalculate Currency Mirror at AGGREGATION level.
     */
    @Transactional
    public void recalculateAggregationMirror(UUID mirrorVaId) {
        VirtualAccount mirror = vaRepository.findById(mirrorVaId)
            .orElseThrow(() -> new ResourceNotFoundException("Mirror VA not found: " + mirrorVaId));

        if (mirror.getAccountCategory() != AccountCategory.CURRENCY_MIRROR) {
            throw new BusinessException("VA is not a Currency Mirror: " + mirror.getVaNumber());
        }

        UUID parentId = mirror.getParentAccountId();
        String currency = mirror.getCurrencyCode();
        String baseCurrency = mirror.getBaseCurrency();

        List<VirtualAccount> siblings = vaRepository.findByParentAccountId(parentId);

        BigDecimal mirrorBalance = siblings.stream()
            .filter(this::isOperationalVa)
            .filter(va -> currency.equals(va.getCurrencyCode()))
            .map(va -> va.getCurrentBalance() != null ? va.getCurrentBalance() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal fxRate = BigDecimal.ONE;
        if (!currency.equals(baseCurrency)) {
            fxRate = fxRateService.getRate(currency, baseCurrency);
        }

        BigDecimal balanceInBase = mirrorBalance.multiply(fxRate).setScale(4, RoundingMode.HALF_UP);

        mirror.setMirrorBalance(mirrorBalance);
        mirror.setFxRate(fxRate);
        mirror.setFxRateAt(LocalDateTime.now());
        mirror.setBalanceInBase(balanceInBase);
        mirror.setCurrentBalance(mirrorBalance);
        mirror.setAvailableBalance(mirrorBalance);

        vaRepository.save(mirror);

        log.debug("Recalculated AGGREGATION Mirror {}: {} {} = {} base", 
            mirror.getVaNumber(), mirrorBalance, currency, balanceInBase);
    }

    /**
     * Recalculate Currency Mirror at ROOT level.
     */
    @Transactional
    public void recalculateRootMirror(UUID mirrorVaId) {
        VirtualAccount mirror = vaRepository.findById(mirrorVaId)
            .orElseThrow(() -> new ResourceNotFoundException("Mirror VA not found: " + mirrorVaId));

        if (mirror.getAccountCategory() != AccountCategory.CURRENCY_MIRROR) {
            throw new BusinessException("VA is not a Currency Mirror: " + mirror.getVaNumber());
        }

        UUID rootId = mirror.getParentAccountId();
        String currency = mirror.getCurrencyCode();
        String baseCurrency = mirror.getBaseCurrency();

        List<VirtualAccount> rootChildren = vaRepository.findByParentAccountId(rootId);

        BigDecimal mirrorBalance = BigDecimal.ZERO;

        for (VirtualAccount child : rootChildren) {
            if (child.getAccountCategory() == AccountCategory.AGGREGATION) {
                List<VirtualAccount> aggMirrors = vaRepository.findByParentAccountIdAndAccountCategory(
                    child.getId(), AccountCategory.CURRENCY_MIRROR);

                Optional<VirtualAccount> currencyMirror = aggMirrors.stream()
                    .filter(m -> currency.equals(m.getCurrencyCode()))
                    .findFirst();

                if (currencyMirror.isPresent() && currencyMirror.get().getMirrorBalance() != null) {
                    mirrorBalance = mirrorBalance.add(currencyMirror.get().getMirrorBalance());
                }
            }
        }

        BigDecimal fxRate = BigDecimal.ONE;
        if (!currency.equals(baseCurrency)) {
            fxRate = fxRateService.getRate(currency, baseCurrency);
        }

        BigDecimal balanceInBase = mirrorBalance.multiply(fxRate).setScale(4, RoundingMode.HALF_UP);

        mirror.setMirrorBalance(mirrorBalance);
        mirror.setFxRate(fxRate);
        mirror.setFxRateAt(LocalDateTime.now());
        mirror.setBalanceInBase(balanceInBase);
        mirror.setCurrentBalance(mirrorBalance);
        mirror.setAvailableBalance(mirrorBalance);

        vaRepository.save(mirror);

        log.debug("Recalculated ROOT Mirror {}: {} {} = {} base", 
            mirror.getVaNumber(), mirrorBalance, currency, balanceInBase);
    }

    /**
     * Recalculate Currency Mirror balance (auto-detects level).
     */
    @Transactional
    public void recalculateCurrencyMirror(UUID mirrorVaId) {
        VirtualAccount mirror = vaRepository.findById(mirrorVaId)
            .orElseThrow(() -> new ResourceNotFoundException("Mirror VA not found: " + mirrorVaId));

        if (mirror.getAccountCategory() != AccountCategory.CURRENCY_MIRROR) {
            throw new BusinessException("VA is not a Currency Mirror: " + mirror.getVaNumber());
        }

        VirtualAccount parent = vaRepository.findById(mirror.getParentAccountId()).orElse(null);
        
        if (parent != null && parent.getAccountCategory() == AccountCategory.ROOT) {
            recalculateRootMirror(mirrorVaId);
        } else {
            recalculateAggregationMirror(mirrorVaId);
        }
    }

    /**
     * Recalculate AGGREGATION or ROOT aggregatedBalance.
     */
    @Transactional
    public void recalculateAggregationBalance(UUID aggregationVaId) {
        VirtualAccount aggregation = vaRepository.findById(aggregationVaId)
            .orElseThrow(() -> new ResourceNotFoundException("VA not found: " + aggregationVaId));

        List<VirtualAccount> children = vaRepository.findByParentAccountId(aggregationVaId);

        BigDecimal totalBase = children.stream()
            .filter(c -> c.getAccountCategory() == AccountCategory.CURRENCY_MIRROR)
            .map(m -> m.getBalanceInBase() != null ? m.getBalanceInBase() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal nestedBase = children.stream()
            .filter(c -> c.getAccountCategory() == AccountCategory.AGGREGATION)
            .map(a -> a.getAggregatedBalanceBase() != null ? a.getAggregatedBalanceBase() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        totalBase = totalBase.add(nestedBase);

        aggregation.setAggregatedBalance(totalBase);
        aggregation.setAggregatedBalanceBase(totalBase);

        vaRepository.save(aggregation);

        log.debug("Recalculated {} {}: {} base", 
            aggregation.getAccountCategory(), aggregation.getVaNumber(), totalBase);
    }

    /**
     * Full hierarchy recalculation (bottom-up).
     */
    @Transactional
    public void recalculateFullHierarchy(UUID corporateId) {
        log.info("════════════════════════════════════════════════════════════════════════════════");
        log.info("FULL HIERARCHY RECALCULATION (bottom-up)");
        log.info("════════════════════════════════════════════════════════════════════════════════");

        List<VirtualAccount> allVas = vaRepository.findByCorporateId(corporateId);

        VirtualAccount root = vaRepository.findRootAccount(corporateId)
            .orElseThrow(() -> new BusinessException("ROOT not found for corporate: " + corporateId));

        log.info("Step 1: Recalculating AGGREGATION Currency Mirrors...");
        allVas.stream()
            .filter(va -> va.getAccountCategory() == AccountCategory.CURRENCY_MIRROR)
            .filter(va -> !va.getParentAccountId().equals(root.getId()))
            .sorted(Comparator.comparingInt(va -> -(va.getHierarchyLevel() != null ? va.getHierarchyLevel() : 0)))
            .forEach(mirror -> {
                try {
                    recalculateAggregationMirror(mirror.getId());
                } catch (Exception e) {
                    log.error("Failed to recalculate mirror {}: {}", mirror.getVaNumber(), e.getMessage());
                }
            });

        log.info("Step 2: Recalculating AGGREGATION aggregatedBalances...");
        allVas.stream()
            .filter(va -> va.getAccountCategory() == AccountCategory.AGGREGATION)
            .sorted(Comparator.comparingInt(va -> -(va.getHierarchyLevel() != null ? va.getHierarchyLevel() : 0)))
            .forEach(agg -> {
                try {
                    recalculateAggregationBalance(agg.getId());
                } catch (Exception e) {
                    log.error("Failed to recalculate aggregation {}: {}", agg.getVaNumber(), e.getMessage());
                }
            });

        log.info("Step 3: Recalculating ROOT Currency Mirrors...");
        allVas.stream()
            .filter(va -> va.getAccountCategory() == AccountCategory.CURRENCY_MIRROR)
            .filter(va -> va.getParentAccountId().equals(root.getId()))
            .forEach(mirror -> {
                try {
                    recalculateRootMirror(mirror.getId());
                } catch (Exception e) {
                    log.error("Failed to recalculate ROOT mirror {}: {}", mirror.getVaNumber(), e.getMessage());
                }
            });

        log.info("Step 4: Recalculating ROOT aggregatedBalance...");
        recalculateAggregationBalance(root.getId());

        log.info("════════════════════════════════════════════════════════════════════════════════");
        log.info("HIERARCHY RECALCULATION COMPLETE");
        log.info("════════════════════════════════════════════════════════════════════════════════");
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ════════════════════════════════════════════════════════════════════════════════

    private boolean isOperationalVa(VirtualAccount va) {
        AccountCategory cat = va.getAccountCategory();
        return cat == AccountCategory.TRANSACTION ||
               cat == AccountCategory.COLLECTION ||
               cat == AccountCategory.DISBURSEMENT;
    }

    /**
     * Find parent VA ID by traversing the hierarchy node structure.
     *
     * This is used when a VA has a hierarchyNodeId but no parentAccountId.
     * We traverse up the HierarchyNode tree to find the nearest ancestor
     * that has a linked VirtualAccount.
     *
     * @param hierarchyNodeId The hierarchy node ID of the source VA
     * @return The parent VA ID, or null if not found
     */
    private UUID findParentVaIdViaHierarchyNode(UUID hierarchyNodeId) {
        if (hierarchyNodeId == null) {
            return null;
        }

        // Get the current node
        HierarchyNode currentNode = hierarchyNodeRepository.findById(hierarchyNodeId).orElse(null);
        if (currentNode == null) {
            log.warn("HierarchyNode not found: {}", hierarchyNodeId);
            return null;
        }

        log.debug("Finding parent VA via hierarchy node: {} (code: {}, parentId: {})",
            hierarchyNodeId, currentNode.getNodeCode(), currentNode.getParentId());

        // Traverse up the hierarchy to find a parent node with a linked VA
        UUID parentNodeId = currentNode.getParentId();
        Set<UUID> visited = new HashSet<>();

        while (parentNodeId != null && !visited.contains(parentNodeId)) {
            visited.add(parentNodeId);

            HierarchyNode parentNode = hierarchyNodeRepository.findById(parentNodeId).orElse(null);
            if (parentNode == null) {
                log.warn("Parent HierarchyNode not found: {}", parentNodeId);
                break;
            }

            log.debug("Checking parent node: {} (code: {}, type: {}, virtualAccountId: {})",
                parentNodeId, parentNode.getNodeCode(), parentNode.getNodeType(), parentNode.getVirtualAccountId());

            // If this parent node has a linked VA, return its ID
            if (parentNode.getVirtualAccountId() != null) {
                log.info("Found parent VA {} via hierarchy node {} ({})",
                    parentNode.getVirtualAccountId(), parentNode.getId(), parentNode.getNodeCode());
                return parentNode.getVirtualAccountId();
            }

            // Continue traversing up
            parentNodeId = parentNode.getParentId();
        }

        // If no parent VA found via hierarchy, try to find the ROOT VA for this program
        UUID programId = currentNode.getProgramId();
        if (programId != null) {
            log.debug("No parent VA found via hierarchy - looking for ROOT node for program {}", programId);

            // Find the root (MASTER) node for this program
            Optional<HierarchyNode> rootNode = hierarchyNodeRepository.findRootNode(programId);
            if (rootNode.isPresent() && rootNode.get().getVirtualAccountId() != null) {
                log.info("Found ROOT VA {} via program {}",
                    rootNode.get().getVirtualAccountId(), programId);
                return rootNode.get().getVirtualAccountId();
            }

            // If root node doesn't have VA, find ROOT VA directly from VirtualAccount
            List<VirtualAccount> rootVas = vaRepository.findByProgramIdAndAccountCategory(programId, AccountCategory.ROOT);
            if (!rootVas.isEmpty()) {
                log.info("Found ROOT VA {} for program {} via VA repository",
                    rootVas.get(0).getId(), programId);
                return rootVas.get(0).getId();
            }
        }

        log.warn("Could not find parent VA via hierarchy node {} - no parent with linked VA found", hierarchyNodeId);
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // CURRENCY MIRROR CLEANUP - Called when a VA is closed/deleted
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Cleanup Currency Mirrors when a VA is closed or deleted.
     *
     * This method checks if the closed VA was the last operational VA of its currency
     * at each hierarchy level. If so, it removes the corresponding Currency Mirror
     * and propagates the cleanup up to ROOT.
     *
     * Flow:
     * 1. Check if there are other active operational VAs with the same currency under the same parent
     * 2. If not, delete the Currency Mirror at that level
     * 3. Propagate cleanup up to parent levels
     *
     * @param closedVa The VA being closed/deleted
     * @return List of Currency Mirrors that were removed
     */
    @Transactional
    /**
     * v5.5.0: Updated to handle ALL currencies including base currency.
     * Since mirrors are now created for all currencies, cleanup should also handle all.
     */
    public List<VirtualAccount> cleanupCurrencyMirrorsOnClose(VirtualAccount closedVa) {
        log.info("Checking currency mirror cleanup for closed VA {} (currency: {})",
            closedVa.getVaNumber(), closedVa.getCurrencyCode());

        List<VirtualAccount> removedMirrors = new ArrayList<>();

        // Only process operational VAs (Transaction, Collection, Disbursement)
        if (!isOperationalVa(closedVa)) {
            log.debug("VA {} is not an operational VA (category: {}) - no cleanup needed",
                closedVa.getVaNumber(), closedVa.getAccountCategory());
            return removedMirrors;
        }

        String currency = closedVa.getCurrencyCode();
        UUID corporateId = closedVa.getCorporateId();

        if (currency == null || corporateId == null) {
            log.warn("VA {} has null currency or corporateId - cannot cleanup", closedVa.getVaNumber());
            return removedMirrors;
        }

        // v5.5.0: REMOVED the base currency skip logic
        // Since mirrors are now created for ALL currencies (including base currency),
        // cleanup should also handle all currencies

        // Propagate cleanup up the hierarchy
        UUID currentParentId = closedVa.getParentAccountId();

        // If no parentAccountId, try via hierarchy node
        if (currentParentId == null && closedVa.getHierarchyNodeId() != null) {
            currentParentId = findParentVaIdViaHierarchyNode(closedVa.getHierarchyNodeId());
        }

        if (currentParentId == null) {
            log.warn("Cannot propagate cleanup for VA {} - no parent found", closedVa.getVaNumber());
            return removedMirrors;
        }

        Set<UUID> visited = new HashSet<>();

        while (currentParentId != null && !visited.contains(currentParentId)) {
            visited.add(currentParentId);

            VirtualAccount parent = vaRepository.findById(currentParentId).orElse(null);
            if (parent == null) {
                log.warn("Parent VA not found: {}", currentParentId);
                break;
            }

            // Only process AGGREGATION and ROOT levels
            if (parent.getAccountCategory() == AccountCategory.AGGREGATION ||
                parent.getAccountCategory() == AccountCategory.ROOT) {

                // Check if there are still active operational VAs with this currency under this parent
                boolean hasActiveVasWithCurrency = hasActiveOperationalVasWithCurrency(
                    parent.getId(), currency, closedVa.getId());

                if (!hasActiveVasWithCurrency) {
                    // No more active VAs with this currency - remove the mirror
                    VirtualAccount removedMirror = removeCurrencyMirrorIfExists(
                        parent.getId(), currency);

                    if (removedMirror != null) {
                        removedMirrors.add(removedMirror);
                        log.info("✓ Removed CURRENCY_MIRROR {} at {} level (no more active {} VAs)",
                            removedMirror.getVaNumber(), parent.getAccountCategory(), currency);
                    }
                } else {
                    log.debug("Active {} VAs still exist under {} - keeping Currency Mirror",
                        currency, parent.getVaNumber());
                    // If there are still VAs at this level, no need to propagate further
                    break;
                }
            }

            currentParentId = parent.getParentAccountId();
        }

        if (removedMirrors.isEmpty()) {
            log.info("No Currency Mirrors needed cleanup for closed VA {}", closedVa.getVaNumber());
        } else {
            log.info("Cleaned up {} Currency Mirrors after closing VA {}",
                removedMirrors.size(), closedVa.getVaNumber());
        }

        return removedMirrors;
    }

    /**
     * Check if there are any active operational VAs with a specific currency under a parent.
     * This recursively checks children (including nested AGGREGATION levels).
     *
     * @param parentId Parent VA ID (AGGREGATION or ROOT)
     * @param currency Currency code to check
     * @param excludeVaId VA ID to exclude (the VA being closed)
     * @return true if there are active operational VAs with this currency
     */
    private boolean hasActiveOperationalVasWithCurrency(UUID parentId, String currency, UUID excludeVaId) {
        List<VirtualAccount> children = vaRepository.findByParentAccountId(parentId);

        for (VirtualAccount child : children) {
            // Skip the VA being closed
            if (excludeVaId != null && excludeVaId.equals(child.getId())) {
                continue;
            }

            // Check if this is an active operational VA with matching currency
            if (isOperationalVa(child) &&
                child.getStatus() == VaStatus.ACTIVE &&
                currency.equals(child.getCurrencyCode())) {
                return true;
            }

            // Recursively check nested AGGREGATION nodes
            if (child.getAccountCategory() == AccountCategory.AGGREGATION) {
                if (hasActiveOperationalVasWithCurrency(child.getId(), currency, excludeVaId)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Remove a Currency Mirror for a specific currency under a parent if it exists.
     *
     * @param parentId Parent VA ID (AGGREGATION or ROOT)
     * @param currency Currency code
     * @return The removed mirror, or null if not found
     */
    @Transactional
    public VirtualAccount removeCurrencyMirrorIfExists(UUID parentId, String currency) {
        List<VirtualAccount> mirrors = vaRepository.findByParentAccountIdAndAccountCategory(
            parentId, AccountCategory.CURRENCY_MIRROR);

        Optional<VirtualAccount> mirrorOpt = mirrors.stream()
            .filter(m -> currency.equals(m.getCurrencyCode()))
            .findFirst();

        if (mirrorOpt.isEmpty()) {
            return null;
        }

        VirtualAccount mirror = mirrorOpt.get();

        // Validate mirror can be removed (should have zero balance)
        BigDecimal mirrorBalance = mirror.getMirrorBalance() != null ? mirror.getMirrorBalance() : BigDecimal.ZERO;
        if (mirrorBalance.compareTo(BigDecimal.ZERO) != 0) {
            log.warn("Cannot remove Currency Mirror {} - has non-zero balance: {}",
                mirror.getVaNumber(), mirrorBalance);
            return null;
        }

        // Mark as CLOSED instead of deleting (for audit trail)
        mirror.setStatus(VaStatus.CLOSED);
        mirror = vaRepository.save(mirror);

        log.info("Closed CURRENCY_MIRROR {} (currency: {}) under parent {}",
            mirror.getVaNumber(), currency, parentId);

        return mirror;
    }

    /**
     * Force cleanup of all unused Currency Mirrors for a corporate.
     * This is a maintenance method to clean up any orphaned mirrors.
     *
     * @param corporateId Corporate ID
     * @return Number of mirrors cleaned up
     */
    @Transactional
    public int cleanupUnusedCurrencyMirrors(UUID corporateId) {
        log.info("Starting cleanup of unused Currency Mirrors for corporate: {}", corporateId);

        List<VirtualAccount> allMirrors = vaRepository.findByCorporateIdAndAccountCategory(
            corporateId, AccountCategory.CURRENCY_MIRROR);

        int cleanedUp = 0;

        for (VirtualAccount mirror : allMirrors) {
            // Skip already closed mirrors
            if (mirror.getStatus() == VaStatus.CLOSED) {
                continue;
            }

            String currency = mirror.getCurrencyCode();
            UUID parentId = mirror.getParentAccountId();

            if (parentId == null) {
                log.warn("Mirror {} has no parent - skipping", mirror.getVaNumber());
                continue;
            }

            // Check if there are any active operational VAs with this currency
            boolean hasActiveVas = hasActiveOperationalVasWithCurrency(parentId, currency, null);

            if (!hasActiveVas) {
                // No active VAs - can close this mirror if balance is zero
                VirtualAccount removed = removeCurrencyMirrorIfExists(parentId, currency);
                if (removed != null) {
                    cleanedUp++;
                }
            }
        }

        log.info("Cleanup complete - closed {} unused Currency Mirrors for corporate {}",
            cleanedUp, corporateId);

        return cleanedUp;
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // QUERY METHODS
    // ════════════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<VirtualAccount> getCurrencyMirrors(UUID corporateId, String currency) {
        return vaRepository.findByCorporateIdAndAccountCategory(corporateId, AccountCategory.CURRENCY_MIRROR)
            .stream()
            .filter(m -> currency.equals(m.getCurrencyCode()))
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Set<String> getAllCurrencies(UUID corporateId) {
        return vaRepository.findByCorporateId(corporateId).stream()
            .map(VirtualAccount::getCurrencyCode)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    }

    @Transactional(readOnly = true)
    public HierarchyTreeNode getHierarchyTree(UUID corporateId) {
        VirtualAccount root = vaRepository.findRootAccount(corporateId)
            .orElseThrow(() -> new ResourceNotFoundException("ROOT not found for corporate: " + corporateId));
        return buildTreeNode(root);
    }

    private HierarchyTreeNode buildTreeNode(VirtualAccount va) {
        List<VirtualAccount> children = vaRepository.findByParentAccountId(va.getId());

        List<HierarchyTreeNode> childNodes = children.stream()
            .map(this::buildTreeNode)
            .collect(Collectors.toList());

        return new HierarchyTreeNode(
            va.getId(),
            va.getVaNumber(),
            va.getVaName(),
            va.getAccountCategory(),
            va.getCurrencyCode(),
            getEffectiveBalance(va),
            va.getHierarchyLevel(),
            childNodes
        );
    }

    private BigDecimal getEffectiveBalance(VirtualAccount va) {
        return switch (va.getAccountCategory()) {
            case ROOT, AGGREGATION -> va.getAggregatedBalanceBase();
            case CURRENCY_MIRROR -> va.getMirrorBalance();
            case PHYSICAL_MIRROR -> va.getBankBalance();
            default -> va.getCurrentBalance();
        };
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // RESULT DTOs
    // ════════════════════════════════════════════════════════════════════════════════

    @Data
    @Builder
    public static class InitializationResult {
        private VirtualAccount rootVa;
        private VirtualAccount baseCurrencyMirror;
        private List<VirtualAccount> createdVas;
        private boolean alreadyExists;
        private String message;
    }

    public record HierarchyTreeNode(
        UUID id,
        String vaNumber,
        String vaName,
        AccountCategory category,
        String currency,
        BigDecimal balance,
        Integer level,
        List<HierarchyTreeNode> children
    ) {}
}