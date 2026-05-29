package com.bank.vam.service.treasury;

import com.bank.vam.controller.treasury.ShadowAccountController.*;
import com.bank.vam.entity.PhysicalAccount;
import com.bank.vam.entity.Program;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.*;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.PhysicalAccountRepository;
import com.bank.vam.repository.ProgramRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ShadowAccountService - Business logic for Shadow Account (PHYSICAL_MIRROR) management.
 * 
 * ═══════════════════════════════════════════════════════════════════════════════════════
 * ARCHITECTURE (v4.5.2) - Per Currency Mirror Propagation Document
 * ═══════════════════════════════════════════════════════════════════════════════════════
 * 
 * FIX v4.5.2: Added programId to all created VAs
 * 
 * Shadow Accounts (PHYSICAL_MIRROR) are attached to AGGREGATION nodes, not ROOT.
 * They represent bank balances (liquidity) and do NOT contribute to aggregatedBalance.
 * 
 * HIERARCHY PATTERN:
 * 
 * ROOT (baseCurrency: EUR)
 * │
 * ├── CURRENCY_MIRROR: M-ROOT-EUR ← Created during hierarchy initialization
 * ├── CURRENCY_MIRROR: M-ROOT-USD ← Created when USD is introduced
 * │
 * ├── EXCEPTION: EXCEPT-EUR
 * ├── EXCEPTION: EXCEPT-USD
 * │
 * └── AGGREGATION: AGG-OPERATIONS
 *     │
 *     ├── CURRENCY_MIRROR: M-OPS-EUR ← Created by HierarchyVaService
 *     ├── CURRENCY_MIRROR: M-OPS-USD ← Created by HierarchyVaService
 *     │
 *     ├── PHYSICAL_MIRROR: SHADOW-EUR-001 ← Created by THIS service
 *     ├── PHYSICAL_MIRROR: SHADOW-USD-001 ← Created by THIS service
 *     │
 *     ├── TRANSACTION: VA-PAYABLES-EUR
 *     ├── SETTLEMENT: SETTLE-EUR ← Created by SettlementVaService
 *     └── TRANSACTION: VA-USD-OPS
 * 
 * ═══════════════════════════════════════════════════════════════════════════════════════
 * OWNERSHIP MODEL (v4.3)
 * ═══════════════════════════════════════════════════════════════════════════════════════
 * 
 * - Shadow Account: Owned by Legal Entity (set via linkToEntity or auto from PhysicalAccount)
 * - Exception VA: INHERITS ownership AND physicalAccountId from Shadow that triggered creation
 * - Currency Mirror: Created by HierarchyVaService, inherits ownership
 * - Settlement VA: Created by SettlementVaService, inherits from sibling
 * 
 * ═══════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS SERVICE CREATES
 * ═══════════════════════════════════════════════════════════════════════════════════════
 * 
 * When createShadowAccount() is called:
 * 1. Creates PHYSICAL_MIRROR (Shadow Account) under the parent AGGREGATION
 * 2. Calls HierarchyVaService.ensureCurrencyInfrastructure() to create:
 *    - Currency Mirror at AGGREGATION level (if not exists)
 *    - Currency Mirror at ROOT level (if new currency)
 *    - Exception VA at ROOT level (if new currency)
 * 
 * @since v4.5.2
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShadowAccountService {

    private final VirtualAccountRepository vaRepository;
    private final PhysicalAccountRepository paRepository;
    private final ProgramRepository programRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final HierarchyVaService hierarchyVaService;
    
    // ========================================================================
    // CONFIGURATION
    // ========================================================================
    
    /** If true, throw exception when ownership chain is broken */
    private static final boolean STRICT_OWNERSHIP_VALIDATION = false;

    // ========================================================================
    // CREATE OPERATIONS
    // ========================================================================

    /**
     * Create a Shadow Account for a Physical Account.
     * 
     * FIX v4.5.2: Now accepts programId and stamps it on all created VAs.
     * 
     * @param physicalAccountId Physical Account to link
     * @param parentVaId Parent VA (should be AGGREGATION, not ROOT)
     * @param corporateId Corporate ID
     * @param programId Program ID (REQUIRED - for proper scoping)
     * @return Created Shadow Account
     */
    @Transactional
    public VirtualAccount createShadowAccount(UUID physicalAccountId, UUID parentVaId, 
                                               UUID corporateId, UUID programId) {
        log.info("════════════════════════════════════════════════════════════════════════════════");
        log.info("CREATING SHADOW ACCOUNT (PHYSICAL_MIRROR)");
        log.info("════════════════════════════════════════════════════════════════════════════════");
        
        // 1. Get physical account
        PhysicalAccount pa = paRepository.findById(physicalAccountId)
            .orElseThrow(() -> new ResourceNotFoundException("Physical account not found: " + physicalAccountId));

        log.info("Physical Account: {} ({}) - {}", pa.getAccountNumber(), pa.getAccountName(), pa.getCurrencyCode());

        // 2. Check if shadow already exists
        Optional<VirtualAccount> existing = vaRepository.findByLinkedPhysicalAccountId(physicalAccountId);
        if (existing.isPresent()) {
            throw new BusinessException("Shadow account already exists for physical account: " + pa.getAccountNumber());
        }

        // 3. Validate and get parent
        VirtualAccount parentVa;
        final UUID effectiveParentId;
        UUID effectiveProgramId = programId;
        
        if (parentVaId != null) {
            parentVa = vaRepository.findById(parentVaId)
                .orElseThrow(() -> new ResourceNotFoundException("Parent VA not found: " + parentVaId));
            effectiveParentId = parentVaId;
            
            // Use parent's programId if not provided
            if (effectiveProgramId == null) {
                effectiveProgramId = parentVa.getProgramId();
                log.info("Using parent's programId: {}", effectiveProgramId);
            }
            
            // Validate parent is AGGREGATION (recommended) or ROOT
            if (parentVa.getAccountCategory() != AccountCategory.AGGREGATION &&
                parentVa.getAccountCategory() != AccountCategory.ROOT) {
                log.warn("Shadow Account parent should be AGGREGATION, not {}. Consider restructuring.", 
                    parentVa.getAccountCategory());
            }
            
            log.info("Parent: {} ({})", parentVa.getVaNumber(), parentVa.getAccountCategory());
        } else {
            // Default to ROOT if no parent specified
            parentVa = vaRepository.findRootAccount(corporateId)
                .orElseThrow(() -> new BusinessException("No ROOT VA found. Initialize hierarchy first."));
            effectiveParentId = parentVa.getId();
            
            // Use ROOT's programId if not provided
            if (effectiveProgramId == null) {
                effectiveProgramId = parentVa.getProgramId();
                log.info("Using ROOT's programId: {}", effectiveProgramId);
            }
            
            log.info("No parent specified, defaulting to ROOT: {}", parentVa.getVaNumber());
        }

        // 4. Validate programId
        if (effectiveProgramId == null) {
            throw new BusinessException("programId is REQUIRED for Shadow Account creation. " +
                "Provide it explicitly or ensure parent VA has programId set.");
        }

        // 5. Determine hierarchy position
        String hierarchyPath = parentVa.getHierarchyPathVa() + "/SHADOW-" + pa.getCurrencyCode() + "-" + 
                       pa.getAccountNumber().substring(Math.max(0, pa.getAccountNumber().length() - 4));
        int hierarchyLevel = parentVa.getHierarchyLevel() + 1;

        // 6. Try to get Legal Entity ownership from Physical Account
        UUID owningEntityId = null;
        String owningEntityCode = null;
        
        if (pa.getLegalEntityId() != null) {
            LegalEntity entity = legalEntityRepository.findById(pa.getLegalEntityId()).orElse(null);
            if (entity != null) {
                owningEntityId = entity.getId();
                owningEntityCode = entity.getEntityCode();
                log.info("Auto-inheriting ownership from Physical Account's Legal Entity: {}", owningEntityCode);
            }
        }

        // 7. Create shadow account
        VirtualAccount shadow = VirtualAccount.builder()
            .vaNumber(generateShadowVaNumber(pa))
            .vaName("Shadow: " + (pa.getAccountName() != null ? pa.getAccountName() : pa.getAccountNumber()))
            .corporateId(corporateId)
            .programId(effectiveProgramId)  // FIX v4.5.2: Set programId
            .physicalAccountId(physicalAccountId)
            .currencyCode(pa.getCurrencyCode())
            .accountType(AccountType.REAL)
            .accountCategory(AccountCategory.PHYSICAL_MIRROR)
            .parentAccountId(effectiveParentId)
            .hierarchyLevel(hierarchyLevel)
            .hierarchyPathVa(hierarchyPath)
            .linkedPhysicalAccountId(physicalAccountId)
            .bankBalance(pa.getCurrentBalance() != null ? pa.getCurrentBalance() : BigDecimal.ZERO)
            .bankAvailableBalance(pa.getAvailableBalance())
            .bankBalanceAt(LocalDateTime.now())
            .bankAccountNumber(pa.getAccountNumber())
            .bankIban(pa.getIban())
            .bankSwift(pa.getBankCode())
            .bankName(pa.getBankName())
            .balanceDataSource(mapDataSource(pa))
            // OWNERSHIP - Auto-inherit from Physical Account if available
            .owningEntityId(owningEntityId)
            .owningEntityCode(owningEntityCode)
            // Balances - Shadow doesn't have operational balance, only bank balance
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .aggregatedBalance(BigDecimal.ZERO)
            .status(VaStatus.ACTIVE)
            .build();

        shadow = vaRepository.save(shadow);

        // 8. Update physical account with shadow reference
        pa.setShadowVaId(shadow.getId());
        paRepository.save(pa);

        log.info("✓ Created Shadow Account {} for physical account {} ({}) [programId: {}]", 
            shadow.getVaNumber(), pa.getAccountNumber(), pa.getCurrencyCode(), effectiveProgramId);

        // 9. Validate ownership
        validateOwnership(shadow, "Shadow Account");

        // ─────────────────────────────────────────────────────────────────────────────
        // 10. DELEGATE TO HierarchyVaService for Currency Infrastructure
        // This will create:
        // - Currency Mirror at AGGREGATION level (if not exists)
        // - Currency Mirror at ROOT level (if new currency)
        // - Exception VA at ROOT level (if new currency)
        // ─────────────────────────────────────────────────────────────────────────────
        try {
            List<VirtualAccount> createdInfra = hierarchyVaService.ensureCurrencyInfrastructure(shadow);
            log.info("✓ HierarchyVaService created {} infrastructure VAs for currency {}", 
                createdInfra.size(), pa.getCurrencyCode());
            
            for (VirtualAccount va : createdInfra) {
                log.info("  → {} ({}) [programId: {}]", va.getVaNumber(), va.getAccountCategory(), va.getProgramId());
            }
        } catch (Exception e) {
            log.error("Failed to create currency infrastructure for shadow {}: {}", 
                shadow.getVaNumber(), e.getMessage());
            // Don't fail Shadow creation if infrastructure creation fails
        }

        log.info("════════════════════════════════════════════════════════════════════════════════");
        
        return shadow;
    }

    /**
     * Backward compatible overload - derives programId from parent.
     */
    @Transactional
    public VirtualAccount createShadowAccount(UUID physicalAccountId, UUID parentVaId, UUID corporateId) {
        return createShadowAccount(physicalAccountId, parentVaId, corporateId, null);
    }

    /**
     * Create Shadow Account with explicit program reference.
     * 
     * This is the preferred method when you have a program context.
     */
    @Transactional
    public VirtualAccount createShadowAccountForProgram(UUID physicalAccountId, UUID programId) {
        Program program = programRepository.findById(programId)
            .orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));
        
        UUID corporateId = program.getCorporateId();
        
        // Find or create an AGGREGATION under ROOT for this program
        VirtualAccount parentVa = findOrCreateDefaultAggregation(program);
        
        return createShadowAccount(physicalAccountId, parentVa.getId(), corporateId, programId);
    }

    /**
     * Find or create a default AGGREGATION node for shadow attachment.
     */
    private VirtualAccount findOrCreateDefaultAggregation(Program program) {
        UUID corporateId = program.getCorporateId();
        
        // Look for existing AGGREGATION under ROOT
        VirtualAccount root = vaRepository.findRootAccount(corporateId).orElse(null);
        if (root == null) {
            throw new BusinessException("ROOT VA not found. Initialize hierarchy first for program: " + 
                program.getProgramCode());
        }
        
        // Check for existing AGGREGATION children
        List<VirtualAccount> aggregations = vaRepository.findByParentAccountIdAndAccountCategory(
            root.getId(), AccountCategory.AGGREGATION);
        
        if (!aggregations.isEmpty()) {
            // Return first aggregation (or could select by some criteria)
            return aggregations.get(0);
        }
        
        // Create default AGGREGATION
        log.info("Creating default AGGREGATION for program {} under ROOT", program.getProgramCode());
        
        return hierarchyVaService.createAggregationVa(
            "Operations",
            "OPERATIONS",
            root.getId(),
            corporateId,
            program.getId(),
            program.getCurrencyCode(),
            null, // owningEntityId
            null  // owningEntityCode
        );
    }

    // ========================================================================
    // ATTACH TO HIERARCHY (for already-created shadows)
    // ========================================================================

    /**
     * Attach an existing Shadow Account to a different parent in the hierarchy.
     * 
     * Use case: Moving a Shadow from ROOT to a specific AGGREGATION node.
     */
    @Transactional
    public VirtualAccount attachToParent(UUID shadowVaId, UUID newParentVaId) {
        VirtualAccount shadow = getShadowAccount(shadowVaId);
        
        VirtualAccount newParent = vaRepository.findById(newParentVaId)
            .orElseThrow(() -> new ResourceNotFoundException("Parent VA not found: " + newParentVaId));
        
        // Validate parent type
        if (newParent.getAccountCategory() != AccountCategory.AGGREGATION &&
            newParent.getAccountCategory() != AccountCategory.ROOT) {
            throw new BusinessException("Shadow can only be attached to AGGREGATION or ROOT, not " + 
                newParent.getAccountCategory());
        }
        
        // Validate same program
        if (shadow.getProgramId() != null && newParent.getProgramId() != null &&
            !shadow.getProgramId().equals(newParent.getProgramId())) {
            throw new BusinessException("Cannot move Shadow to a different program's hierarchy");
        }
        
        UUID oldParentId = shadow.getParentAccountId();
        
        // Update hierarchy info
        String newHierarchyPath = newParent.getHierarchyPathVa() + "/" + 
            shadow.getVaNumber().replace("SHADOW-", "S-");
        int newLevel = newParent.getHierarchyLevel() + 1;
        
        shadow.setParentAccountId(newParentVaId);
        shadow.setHierarchyPathVa(newHierarchyPath);
        shadow.setHierarchyLevel(newLevel);
        
        // Inherit programId from parent if not set
        if (shadow.getProgramId() == null && newParent.getProgramId() != null) {
            shadow.setProgramId(newParent.getProgramId());
        }
        
        shadow = vaRepository.save(shadow);
        
        log.info("Attached Shadow {} from parent {} to new parent {} (level: {})", 
            shadow.getVaNumber(), oldParentId, newParentVaId, newLevel);
        
        // Trigger currency infrastructure if new parent has different base currency
        try {
            hierarchyVaService.ensureCurrencyInfrastructure(shadow);
        } catch (Exception e) {
            log.warn("Currency infrastructure check failed: {}", e.getMessage());
        }
        
        return shadow;
    }

    /**
     * Detach Shadow from hierarchy (move back to ROOT).
     */
    @Transactional
    public VirtualAccount detachToRoot(UUID shadowVaId) {
        VirtualAccount shadow = getShadowAccount(shadowVaId);
        
        VirtualAccount root = vaRepository.findRootAccount(shadow.getCorporateId())
            .orElseThrow(() -> new BusinessException("ROOT not found"));
        
        return attachToParent(shadowVaId, root.getId());
    }

    // ========================================================================
    // OWNERSHIP VALIDATION
    // ========================================================================

    /**
     * Validate that a VA has proper ownership set.
     */
    private void validateOwnership(VirtualAccount va, String vaType) {
        if (va.getCorporateId() == null) {
            String msg = String.format("%s %s has no corporateId - this is a critical error", 
                vaType, va.getVaNumber());
            log.error(msg);
            throw new BusinessException(msg);
        }
        
        // FIX v4.5.2: Also validate programId
        if (va.getProgramId() == null) {
            log.warn("{} {} has no programId - this may cause filtering/query issues", 
                vaType, va.getVaNumber());
        }
        
        if (va.getOwningEntityId() == null) {
            String msg = String.format("%s %s has no owningEntityId - ownership chain may be incomplete", 
                vaType, va.getVaNumber());
            if (STRICT_OWNERSHIP_VALIDATION) {
                log.error(msg);
                throw new BusinessException(msg);
            } else {
                log.warn(msg);
            }
        } else {
            log.debug("{} {} ownership validated: corporateId={}, programId={}, owningEntityId={}", 
                vaType, va.getVaNumber(), va.getCorporateId(), va.getProgramId(), va.getOwningEntityId());
        }
    }

    /**
     * Validate entire ownership chain from a VA up to ROOT.
     */
    public List<String> validateFullOwnershipChain(UUID vaId) {
        List<String> issues = new ArrayList<>();
        VirtualAccount current = vaRepository.findById(vaId).orElse(null);
        
        if (current == null) {
            issues.add("VA not found: " + vaId);
            return issues;
        }
        
        while (current != null) {
            if (current.getCorporateId() == null) {
                issues.add(String.format("%s (%s) has no corporateId", 
                    current.getVaNumber(), current.getAccountCategory()));
            }
            
            // FIX v4.5.2: Check programId
            if (current.getProgramId() == null) {
                issues.add(String.format("%s (%s) has no programId", 
                    current.getVaNumber(), current.getAccountCategory()));
            }
            
            if (current.getOwningEntityId() == null && 
                current.getAccountCategory() != AccountCategory.ROOT &&
                current.getAccountCategory() != AccountCategory.AGGREGATION) {
                issues.add(String.format("%s (%s) has no owningEntityId", 
                    current.getVaNumber(), current.getAccountCategory()));
            }
            
            if (current.getParentAccountId() != null) {
                current = vaRepository.findById(current.getParentAccountId()).orElse(null);
            } else {
                current = null;
            }
        }
        
        return issues;
    }

    // ========================================================================
    // LINK TO ENTITY (with cascade to children)
    // ========================================================================

    /**
     * Link shadow account to legal entity.
     * Also updates Exception VA if it exists.
     */
    @Transactional
    public void linkToEntity(UUID shadowVaId, UUID legalEntityId) {
        VirtualAccount shadow = vaRepository.findById(shadowVaId)
            .orElseThrow(() -> new ResourceNotFoundException("Shadow account not found: " + shadowVaId));
        
        if (shadow.getAccountCategory() != AccountCategory.PHYSICAL_MIRROR) {
            throw new BusinessException("Account is not a shadow account: " + shadow.getVaNumber());
        }
        
        LegalEntity entity = legalEntityRepository.findById(legalEntityId)
            .orElseThrow(() -> new ResourceNotFoundException("Legal entity not found: " + legalEntityId));
        
        // 1. Update Shadow
        shadow.setOwningEntityId(legalEntityId);
        shadow.setOwningEntityCode(entity.getEntityCode());
        vaRepository.save(shadow);
        log.info("Linked shadow {} to entity {} ({})", 
            shadow.getVaNumber(), entity.getEntityCode(), entity.getEntityName());
        
        // 2. Update Exception VA for this currency (if exists and has no ownership)
        List<VirtualAccount> exceptionVas = vaRepository.findByCorporateIdAndAccountCategory(
            shadow.getCorporateId(), AccountCategory.EXCEPTION);
        
        exceptionVas.stream()
            .filter(va -> shadow.getCurrencyCode().equals(va.getCurrencyCode()))
            .filter(va -> va.getOwningEntityId() == null)
            .forEach(exceptionVa -> {
                exceptionVa.setOwningEntityId(legalEntityId);
                exceptionVa.setOwningEntityCode(entity.getEntityCode());
                vaRepository.save(exceptionVa);
                log.info("  → Also updated Exception VA {} ownership", exceptionVa.getVaNumber());
            });
        
        // 3. Update Currency Mirrors for this currency (if exists and has no ownership)
        List<VirtualAccount> currencyMirrors = vaRepository.findByCorporateIdAndAccountCategory(
            shadow.getCorporateId(), AccountCategory.CURRENCY_MIRROR);
        
        currencyMirrors.stream()
            .filter(va -> shadow.getCurrencyCode().equals(va.getCurrencyCode()))
            .filter(va -> va.getOwningEntityId() == null)
            .forEach(mirror -> {
                mirror.setOwningEntityId(legalEntityId);
                mirror.setOwningEntityCode(entity.getEntityCode());
                vaRepository.save(mirror);
                log.info("  → Also updated Currency Mirror {} ownership", mirror.getVaNumber());
            });
    }

    // ========================================================================
    // BULK OPERATIONS
    // ========================================================================

    /**
     * Bulk create shadow accounts for all physical accounts of a legal entity.
     * 
     * FIX v4.5.2: Now passes programId through the chain.
     */
    @Transactional
    public BulkCreateResultDto bulkCreateShadowAccounts(UUID legalEntityId, UUID corporateId, 
                                                         UUID parentVaId, UUID programId) {
        log.info("Bulk creating shadow accounts for entity: {} (programId: {})", legalEntityId, programId);
        
        List<PhysicalAccount> physicalAccounts = paRepository.findByLegalEntityId(legalEntityId);
        
        int created = 0;
        int skipped = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        List<ShadowAccountDto> createdAccounts = new ArrayList<>();
        
        for (PhysicalAccount pa : physicalAccounts) {
            try {
                Optional<VirtualAccount> existing = vaRepository.findByLinkedPhysicalAccountId(pa.getId());
                if (existing.isPresent()) {
                    skipped++;
                    continue;
                }
                
                VirtualAccount shadow = createShadowAccount(pa.getId(), parentVaId, corporateId, programId);
                
                // Link to entity (this will cascade to children)
                linkToEntity(shadow.getId(), legalEntityId);
                
                createdAccounts.add(ShadowAccountDto.builder()
                    .id(shadow.getId())
                    .vaNumber(shadow.getVaNumber())
                    .currencyCode(shadow.getCurrencyCode())
                    .bankBalance(shadow.getBankBalance())
                    .owningEntityId(shadow.getOwningEntityId())
                    .owningEntityCode(shadow.getOwningEntityCode())
                    .build());
                created++;
                
            } catch (Exception e) {
                failed++;
                errors.add(pa.getAccountNumber() + ": " + e.getMessage());
                log.error("Failed to create shadow for {}: {}", pa.getAccountNumber(), e.getMessage());
            }
        }
        
        return BulkCreateResultDto.builder()
            .total(physicalAccounts.size())
            .created(created)
            .skipped(skipped)
            .failed(failed)
            .errors(errors)
            .createdAccounts(createdAccounts)
            .build();
    }

    /**
     * Backward compatible overload.
     */
    @Transactional
    public BulkCreateResultDto bulkCreateShadowAccounts(UUID legalEntityId, UUID corporateId, UUID parentVaId) {
        return bulkCreateShadowAccounts(legalEntityId, corporateId, parentVaId, null);
    }

    // ========================================================================
    // READ OPERATIONS
    // ========================================================================

    public VirtualAccount getShadowAccount(UUID shadowVaId) {
        VirtualAccount va = vaRepository.findById(shadowVaId)
            .orElseThrow(() -> new ResourceNotFoundException("Shadow account not found: " + shadowVaId));
        
        if (va.getAccountCategory() != AccountCategory.PHYSICAL_MIRROR) {
            throw new BusinessException("Account is not a shadow account: " + va.getVaNumber());
        }
        
        return va;
    }

    public List<VirtualAccount> getShadowAccounts(UUID corporateId) {
        return vaRepository.findByCorporateIdAndAccountCategory(corporateId, AccountCategory.PHYSICAL_MIRROR);
    }

    public List<VirtualAccount> getShadowAccountsByProgram(UUID programId) {
        return vaRepository.findByProgramIdAndAccountCategory(programId, AccountCategory.PHYSICAL_MIRROR);
    }

    public List<VirtualAccount> getShadowAccountsByEntity(UUID legalEntityId) {
        return vaRepository.findByOwningEntityIdAndAccountCategory(legalEntityId, AccountCategory.PHYSICAL_MIRROR);
    }

    public List<CurrencyGroupDto> getShadowAccountsGroupedByCurrency(UUID corporateId) {
        List<VirtualAccount> shadows = getShadowAccounts(corporateId);
        
        Map<String, List<VirtualAccount>> byCurrency = shadows.stream()
            .collect(Collectors.groupingBy(VirtualAccount::getCurrencyCode));
        
        return byCurrency.entrySet().stream()
            .map(entry -> {
                List<VirtualAccount> accounts = entry.getValue();
                BigDecimal totalBalance = accounts.stream()
                    .map(va -> va.getBankBalance() != null ? va.getBankBalance() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                
                return CurrencyGroupDto.builder()
                    .currencyCode(entry.getKey())
                    .accountCount(accounts.size())
                    .totalBalance(totalBalance)
                    .accounts(accounts.stream()
                        .map(this::toShadowDto)
                        .collect(Collectors.toList()))
                    .build();
            })
            .sorted(Comparator.comparing(CurrencyGroupDto::getCurrencyCode))
            .collect(Collectors.toList());
    }

    public List<VirtualAccount> getShadowAccountsByCurrency(UUID corporateId, String currency) {
        return vaRepository.findShadowAccountsByCurrency(corporateId, currency);
    }

    public Optional<VirtualAccount> getShadowForPhysicalAccount(UUID physicalAccountId) {
        return vaRepository.findByLinkedPhysicalAccountId(physicalAccountId);
    }

    public VirtualAccount getShadowByPhysicalAccount(UUID physicalAccountId) {
        return vaRepository.findByLinkedPhysicalAccountId(physicalAccountId).orElse(null);
    }

    public List<VirtualAccount> getChildVirtualAccounts(UUID shadowVaId) {
        VirtualAccount shadow = getShadowAccount(shadowVaId);
        return vaRepository.findByParentAccountId(shadow.getId());
    }

    public List<VirtualAccount> getChildVas(UUID shadowVaId) {
        return getChildVirtualAccounts(shadowVaId);
    }

    public long getChildCount(UUID shadowVaId) {
        return vaRepository.countByParentAccountId(shadowVaId);
    }

    public ShadowAccountStatsDto getShadowAccountStats(UUID shadowVaId) {
        VirtualAccount shadow = getShadowAccount(shadowVaId);
        
        List<VirtualAccount> children = vaRepository.findByParentAccountId(shadowVaId);
        
        BigDecimal bankBalance = shadow.getBankBalance() != null ? shadow.getBankBalance() : BigDecimal.ZERO;
        BigDecimal totalChildBalance = children.stream()
            .map(va -> va.getCurrentBalance() != null ? va.getCurrentBalance() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        long activeCount = children.stream()
            .filter(va -> va.getStatus() == VaStatus.ACTIVE)
            .count();
        
        BigDecimal available = bankBalance.subtract(totalChildBalance);
        
        return ShadowAccountStatsDto.builder()
            .id(shadow.getId())
            .vaNumber(shadow.getVaNumber())
            .currencyCode(shadow.getCurrencyCode())
            .bankBalance(bankBalance)
            .totalChildBalance(totalChildBalance)
            .availableForAllocation(available.max(BigDecimal.ZERO))
            .childCount(children.size())
            .activeChildCount((int) activeCount)
            .lastSyncAt(shadow.getBankBalanceAt())
            .build();
    }

    // ========================================================================
    // UPDATE OPERATIONS
    // ========================================================================

    @Transactional
    public void updateShadowBalance(UUID shadowVaId, BigDecimal currentBalance, 
                                     BigDecimal availableBalance, String source) {
        VirtualAccount shadow = vaRepository.findById(shadowVaId)
            .orElseThrow(() -> new ResourceNotFoundException("Shadow account not found: " + shadowVaId));

        if (shadow.getAccountCategory() != AccountCategory.PHYSICAL_MIRROR) {
            throw new BusinessException("Account is not a shadow account: " + shadow.getVaNumber());
        }

        BigDecimal previousBalance = shadow.getBankBalance() != null ? shadow.getBankBalance() : BigDecimal.ZERO;
        BigDecimal delta = currentBalance.subtract(previousBalance);

        shadow.setBankBalance(currentBalance);
        shadow.setBankAvailableBalance(availableBalance);
        shadow.setBankBalanceAt(LocalDateTime.now());
        
        if (source != null) {
            try {
                shadow.setBalanceDataSource(BalanceDataSource.valueOf(source));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown data source: {}", source);
            }
        }
        
        vaRepository.save(shadow);

        // NOTE: Shadow balance changes don't propagate to aggregatedBalance
        // because Shadow Accounts represent liquidity, not operational balance
        log.info("Updated shadow {} balance: {} -> {} (delta: {})", 
            shadow.getVaNumber(), previousBalance, currentBalance, delta);
    }

    @Transactional
    public VirtualAccount syncFromCbs(UUID shadowVaId) {
        VirtualAccount shadow = getShadowAccount(shadowVaId);
        
        if (shadow.getLinkedPhysicalAccountId() == null) {
            throw new BusinessException("Shadow account has no linked physical account");
        }
        
        PhysicalAccount pa = paRepository.findById(shadow.getLinkedPhysicalAccountId())
            .orElseThrow(() -> new ResourceNotFoundException("Physical account not found"));
        
        shadow.setBankBalance(pa.getCurrentBalance());
        shadow.setBankAvailableBalance(pa.getAvailableBalance());
        shadow.setBankBalanceAt(LocalDateTime.now());
        shadow.setBalanceDataSource(mapDataSource(pa));
        
        return vaRepository.save(shadow);
    }

    @Transactional
    public SyncResultDto syncAllFromCbs(UUID corporateId) {
        List<VirtualAccount> shadows = getShadowAccounts(corporateId);
        
        int synced = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        
        for (VirtualAccount shadow : shadows) {
            try {
                syncFromCbs(shadow.getId());
                synced++;
            } catch (Exception e) {
                failed++;
                errors.add(shadow.getVaNumber() + ": " + e.getMessage());
                log.error("Failed to sync shadow {}: {}", shadow.getVaNumber(), e.getMessage());
            }
        }
        
        return SyncResultDto.builder()
            .total(shadows.size())
            .synced(synced)
            .failed(failed)
            .syncedAt(LocalDateTime.now())
            .errors(errors)
            .build();
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private String generateShadowVaNumber(PhysicalAccount pa) {
        String accountSuffix = pa.getAccountNumber();
        if (accountSuffix.length() > 8) {
            accountSuffix = accountSuffix.substring(accountSuffix.length() - 8);
        }
        // Defensive: when the account number tail starts with a separator
        // (e.g. account number "PA-AED-002" → tail "-AED-002"), naive concat
        // produces a double dash like "SHADOW-AED--AED-002". Strip the leading
        // dash so the identifier is clean.
        accountSuffix = accountSuffix.replaceFirst("^[-_]+", "");
        return "SHADOW-" + pa.getCurrencyCode() + "-" + accountSuffix;
    }

    private BalanceDataSource mapDataSource(PhysicalAccount pa) {
        if (pa.getDataSource() == null) {
            return BalanceDataSource.MANUAL;
        }
        
        String source = pa.getDataSource().name();
        return switch (source) {
            case "CORE_BANKING", "INTERNAL_API" -> BalanceDataSource.CORE_BANKING;
            case "SWIFT_MT940", "SWIFT_CAMT053" -> BalanceDataSource.SWIFT_MT940;
            case "SWIFT_MT942", "SWIFT_CAMT052" -> BalanceDataSource.SWIFT_MT942;
            case "OPEN_BANKING_PSD2", "OPEN_BANKING_UAE", "OPEN_BANKING_UK", "OPEN_BANKING_KSA" -> BalanceDataSource.OPEN_BANKING;
            case "HOST_TO_HOST", "SFTP_IMPORT" -> BalanceDataSource.HOST_TO_HOST;
            case "PLAID", "YODLEE", "TARABUT", "LEAN", "SALT_EDGE" -> BalanceDataSource.API;
            case "MANUAL_ENTRY" -> BalanceDataSource.MANUAL;
            default -> BalanceDataSource.MANUAL;
        };
    }

    private ShadowAccountDto toShadowDto(VirtualAccount va) {
        return ShadowAccountDto.builder()
            .id(va.getId())
            .vaNumber(va.getVaNumber())
            .vaName(va.getVaName())
            .currencyCode(va.getCurrencyCode())
            .bankBalance(va.getBankBalance())
            .bankAvailableBalance(va.getBankAvailableBalance())
            .linkedPhysicalAccountId(va.getLinkedPhysicalAccountId())
            .physicalAccountNumber(va.getBankAccountNumber())
            .bankName(va.getBankName())
            .bankIban(va.getBankIban())
            .bankSwift(va.getBankSwift())
            .owningEntityId(va.getOwningEntityId())
            .owningEntityCode(va.getOwningEntityCode())
            .corporateId(va.getCorporateId())
            .programId(va.getProgramId())  // FIX v4.5.2: Include programId in DTO
            .parentAccountId(va.getParentAccountId())
            .hierarchyLevel(va.getHierarchyLevel())
            .hierarchyPathVa(va.getHierarchyPathVa())
            .status(va.getStatus() != null ? va.getStatus().name() : "ACTIVE")
            .bankBalanceAt(va.getBankBalanceAt())
            .dataSource(va.getBalanceDataSource() != null ? va.getBalanceDataSource().name() : null)
            .childCount((int) getChildCount(va.getId()))
            .createdAt(va.getCreatedAt())
            .updatedAt(va.getUpdatedAt())
            .build();
    }
}