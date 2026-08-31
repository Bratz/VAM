package com.bank.vam.service.treasury;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.*;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.VirtualAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * SettlementVaService - Creates Settlement VAs at the appropriate hierarchy level.
 * 
 * FIX v4.5.2: Added programId to all created Settlement VAs.
 * 
 * OWNERSHIP MODEL (v4.3):
 * Settlement VAs INHERIT ownership from their parent VA (Currency Mirror) or sibling VA.
 * This ensures the ownership chain is maintained throughout the hierarchy.
 * 
 * HIERARCHY PATTERN:
 * ROOT (Level 1)
 * ├── SHADOW-GBP-XXXX (Level 2) - PHYSICAL_MIRROR [owningEntityId = Legal Entity]
 * │   └── MIRROR-GBP-XXXX (Level 3) - CURRENCY_MIRROR [inherits from Shadow]
 * │       ├── VA-COLLECTIONS-001 (Level 4) - TRANSACTION [inherits from Mirror]
 * │       ├── VA-DISBURSEMENT-002 (Level 4) - TRANSACTION [inherits from Mirror]
 * │       └── SETTLE-GBP-XXXX (Level 4) - SETTLEMENT [inherits from Mirror/sibling]
 * └── EXCEPT-GBP (Level 2) - EXCEPTION [inherits from Shadow]
 * 
 * USAGE:
 * Called by VirtualAccountService when creating transactional VAs.
 * Settlement VA is created as a sibling at the same level.
 * 
 * @since v4.5.2
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementVaService {

    private final VirtualAccountRepository vaRepository;
    
    /** If true, throw exception when ownership is missing */
    private static final boolean STRICT_OWNERSHIP_VALIDATION = false;

    /**
     * Ensure a Settlement VA exists as sibling of the given transactional VA.
     * Called automatically when a transactional VA is created.
     * 
     * FIX v4.5.2: Now inherits programId from sibling VA.
     * 
     * @param transactionalVa The newly created transactional VA
     * @return The Settlement VA (existing or newly created)
     */
    @Transactional
    public VirtualAccount ensureSettlementVaForSibling(VirtualAccount transactionalVa) {
        // Validate this is an operational VA
        if (!isOperationalVa(transactionalVa)) {
            log.debug("Skipping Settlement VA creation - not an operational VA: {}", 
                transactionalVa.getAccountCategory());
            return null;
        }

        UUID parentId = transactionalVa.getParentAccountId();
        String currency = transactionalVa.getCurrencyCode();
        UUID corporateId = transactionalVa.getCorporateId();
        UUID programId = transactionalVa.getProgramId();  // FIX v4.5.2: Get programId
        
        if (parentId == null) {
            log.warn("Cannot create Settlement VA - transactional VA has no parent: {}", 
                transactionalVa.getVaNumber());
            return null;
        }

        // Check if Settlement VA already exists under this parent for this currency
        Optional<VirtualAccount> existingSettlement = findSettlementVaUnderParent(parentId, currency);
        
        if (existingSettlement.isPresent()) {
            log.debug("Settlement VA already exists under parent {}: {}", 
                parentId, existingSettlement.get().getVaNumber());
            return existingSettlement.get();
        }

        // Create new Settlement VA as sibling (inherits ownership and programId)
        return createSettlementVaAsSibling(transactionalVa, parentId, currency, corporateId, programId);
    }

    /**
     * Create a Settlement VA at a specific level under a parent.
     * 
     * FIX v4.5.2: Now accepts and sets programId.
     * 
     * @param parentVaId The parent VA (typically Currency Mirror)
     * @param currency The currency code
     * @param corporateId The corporate ID
     * @param programId The program ID
     * @return Created Settlement VA
     */
    @Transactional
    public VirtualAccount createSettlementVaUnderParent(UUID parentVaId, String currency, 
                                                         UUID corporateId, UUID programId) {
        // Get parent VA
        VirtualAccount parentVa = vaRepository.findById(parentVaId)
            .orElseThrow(() -> new BusinessException("Parent VA not found: " + parentVaId));

        // Check if already exists
        Optional<VirtualAccount> existing = findSettlementVaUnderParent(parentVaId, currency);
        if (existing.isPresent()) {
            log.info("Settlement VA already exists under parent {}: {}", 
                parentVaId, existing.get().getVaNumber());
            return existing.get();
        }

        // Use parent's programId if provided one is null
        UUID effectiveProgramId = programId != null ? programId : parentVa.getProgramId();
        if (effectiveProgramId == null) {
            effectiveProgramId = findProgramIdFromHierarchy(parentVa);
            if (effectiveProgramId == null) {
                log.warn("Creating Settlement VA without programId - this may cause issues");
            }
        }

        // Generate unique VA number
        String vaNumber = generateSettlementVaNumber(currency, parentVa);
        
        // Calculate hierarchy
        int hierarchyLevel = parentVa.getHierarchyLevel() + 1;
        String hierarchyPath = parentVa.getHierarchyPathVa() + "/" + vaNumber;

        VirtualAccount settlementVa = VirtualAccount.builder()
            // Core Identity
            .vaNumber(vaNumber)
            .vaName(currency + " Settlement Account")
            .corporateId(corporateId)
            .programId(effectiveProgramId)  // FIX v4.5.2: Set programId
            .currencyCode(currency)
            // Physical account (for NOT NULL constraint)
            .physicalAccountId(parentVa.getPhysicalAccountId())
            // Classification
            .accountType(AccountType.VIRTUAL)
            .accountCategory(AccountCategory.SETTLEMENT)
            .specialType(VaSpecialType.SETTLEMENT)
            // Hierarchy
            .parentAccountId(parentVaId)
            .hierarchyLevel(hierarchyLevel)
            .hierarchyPathVa(hierarchyPath)
            // OWNERSHIP - Inherit from parent VA
            .owningEntityId(parentVa.getOwningEntityId())
            .owningEntityCode(parentVa.getOwningEntityCode())
            // Balances
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .aggregatedBalance(BigDecimal.ZERO)
            // Status
            .status(VaStatus.ACTIVE)
            .build();

        settlementVa = vaRepository.save(settlementVa);
        
        // Validate ownership
        validateOwnership(settlementVa, parentVa);
        
        log.info("✓ Created Settlement VA {} at Level {} under parent {} for currency {} (owned by: {}, programId: {})", 
            settlementVa.getVaNumber(), 
            hierarchyLevel,
            parentVa.getVaNumber(),
            currency,
            settlementVa.getOwningEntityCode(),
            effectiveProgramId);

        return settlementVa;
    }

    /**
     * Backward compatible overload.
     */
    @Transactional
    public VirtualAccount createSettlementVaUnderParent(UUID parentVaId, String currency, UUID corporateId) {
        return createSettlementVaUnderParent(parentVaId, currency, corporateId, null);
    }

    /**
     * Find Settlement VA under a specific parent for a currency.
     *
     * FIX v5.1.0: Now filters for ACTIVE status only and considers ALL naming patterns
     * (SETTLE-xxx, SETTLEMENT-xxx, etc.) to prevent duplicate creation.
     */
    public Optional<VirtualAccount> findSettlementVaUnderParent(UUID parentVaId, String currency) {
        List<VirtualAccount> settlements = vaRepository.findByParentAccountIdAndAccountCategory(
            parentVaId, AccountCategory.SETTLEMENT);

        return settlements.stream()
            .filter(va -> currency.equals(va.getCurrencyCode()))
            .filter(va -> va.getStatus() == VaStatus.ACTIVE)  // Only consider ACTIVE
            .findFirst();
    }

    /**
     * Find all Settlement VAs for a corporate.
     */
    public List<VirtualAccount> findAllSettlementVas(UUID corporateId) {
        return vaRepository.findByCorporateIdAndAccountCategory(corporateId, AccountCategory.SETTLEMENT);
    }

    /**
     * Get or create Settlement VA for a specific parent and currency.
     */
    @Transactional
    public VirtualAccount getOrCreateSettlementVa(UUID parentVaId, String currency, 
                                                   UUID corporateId, UUID programId) {
        return findSettlementVaUnderParent(parentVaId, currency)
            .orElseGet(() -> createSettlementVaUnderParent(parentVaId, currency, corporateId, programId));
    }

    /**
     * Backward compatible overload.
     */
    @Transactional
    public VirtualAccount getOrCreateSettlementVa(UUID parentVaId, String currency, UUID corporateId) {
        return getOrCreateSettlementVa(parentVaId, currency, corporateId, null);
    }

    /**
     * Update ownership of Settlement VA to match its parent.
     */
    @Transactional
    public void updateOwnershipFromParent(UUID settlementVaId) {
        VirtualAccount settlementVa = vaRepository.findById(settlementVaId)
            .orElseThrow(() -> new BusinessException("Settlement VA not found: " + settlementVaId));
        
        if (settlementVa.getParentAccountId() == null) {
            log.warn("Settlement VA {} has no parent, cannot update ownership", settlementVa.getVaNumber());
            return;
        }
        
        VirtualAccount parentVa = vaRepository.findById(settlementVa.getParentAccountId())
            .orElseThrow(() -> new BusinessException("Parent VA not found: " + settlementVa.getParentAccountId()));
        
        if (parentVa.getOwningEntityId() != null) {
            settlementVa.setOwningEntityId(parentVa.getOwningEntityId());
            settlementVa.setOwningEntityCode(parentVa.getOwningEntityCode());
            vaRepository.save(settlementVa);
            log.info("Updated Settlement VA {} ownership to match parent {} (entity: {})", 
                settlementVa.getVaNumber(), parentVa.getVaNumber(), parentVa.getOwningEntityCode());
        }
        
        // FIX v4.5.2: Also update programId if missing
        if (settlementVa.getProgramId() == null && parentVa.getProgramId() != null) {
            settlementVa.setProgramId(parentVa.getProgramId());
            vaRepository.save(settlementVa);
            log.info("Updated Settlement VA {} programId from parent {} (programId: {})", 
                settlementVa.getVaNumber(), parentVa.getVaNumber(), parentVa.getProgramId());
        }
    }

    // ========================================================================
    // PRIVATE HELPERS
    // ========================================================================

    /**
     * Create Settlement VA as sibling of a transactional VA.
     * 
     * FIX v4.5.2: Now accepts and sets programId.
     */
    private VirtualAccount createSettlementVaAsSibling(
            VirtualAccount siblingVa, 
            UUID parentId, 
            String currency, 
            UUID corporateId,
            UUID programId) {
        
        // Get parent for hierarchy info and ownership
        VirtualAccount parentVa = vaRepository.findById(parentId).orElse(null);
        
        // Determine effective programId
        UUID effectiveProgramId = programId;
        if (effectiveProgramId == null && parentVa != null) {
            effectiveProgramId = parentVa.getProgramId();
        }
        if (effectiveProgramId == null && siblingVa != null) {
            effectiveProgramId = siblingVa.getProgramId();
        }
        if (effectiveProgramId == null) {
            effectiveProgramId = findProgramIdFromHierarchy(siblingVa);
            if (effectiveProgramId == null) {
                log.warn("Creating Settlement VA without programId - this may cause issues");
            }
        }
        
        // Generate unique VA number
        String vaNumber = generateSettlementVaNumber(currency, parentVa);
        
        // Calculate hierarchy - same level as sibling
        int hierarchyLevel = siblingVa.getHierarchyLevel();
        String hierarchyPath;
        if (parentVa != null && parentVa.getHierarchyPathVa() != null) {
            hierarchyPath = parentVa.getHierarchyPathVa() + "/" + vaNumber;
        } else {
            hierarchyPath = "/ROOT/" + vaNumber;
        }

        // Determine ownership source: prefer parent, fallback to sibling
        UUID owningEntityId = null;
        String owningEntityCode = null;
        
        if (parentVa != null && parentVa.getOwningEntityId() != null) {
            owningEntityId = parentVa.getOwningEntityId();
            owningEntityCode = parentVa.getOwningEntityCode();
            log.debug("Settlement VA inheriting ownership from parent: {}", owningEntityCode);
        } else if (siblingVa.getOwningEntityId() != null) {
            owningEntityId = siblingVa.getOwningEntityId();
            owningEntityCode = siblingVa.getOwningEntityCode();
            log.debug("Settlement VA inheriting ownership from sibling: {}", owningEntityCode);
        }

        VirtualAccount settlementVa = VirtualAccount.builder()
            // Core Identity
            .vaNumber(vaNumber)
            .vaName(currency + " Settlement Account")
            .corporateId(corporateId)
            .programId(effectiveProgramId)  // FIX v4.5.2: Set programId
            .currencyCode(currency)
            // Physical account (for NOT NULL constraint)
            .physicalAccountId(siblingVa.getPhysicalAccountId())
            // Classification
            .accountType(AccountType.VIRTUAL)
            .accountCategory(AccountCategory.SETTLEMENT)
            .specialType(VaSpecialType.SETTLEMENT)
            // Hierarchy - Same parent as sibling transactional VA
            .parentAccountId(parentId)
            .hierarchyLevel(hierarchyLevel)
            .hierarchyPathVa(hierarchyPath)
            // OWNERSHIP - Inherit from parent or sibling
            .owningEntityId(owningEntityId)
            .owningEntityCode(owningEntityCode)
            // Balances
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .aggregatedBalance(BigDecimal.ZERO)
            // Status
            .status(VaStatus.ACTIVE)
            .build();

        settlementVa = vaRepository.save(settlementVa);
        
        // Validate ownership
        validateOwnership(settlementVa, parentVa != null ? parentVa : siblingVa);
        
        log.info("✓ Created Settlement VA {} at Level {} as sibling of {} for currency {} (owned by: {}, programId: {})", 
            settlementVa.getVaNumber(), 
            hierarchyLevel,
            siblingVa.getVaNumber(),
            currency,
            settlementVa.getOwningEntityCode() != null ? settlementVa.getOwningEntityCode() : "NONE",
            effectiveProgramId);

        return settlementVa;
    }

    /**
     * Find programId by traversing up the parent hierarchy.
     */
    private UUID findProgramIdFromHierarchy(VirtualAccount va) {
        if (va == null) return null;
        
        UUID currentParentId = va.getParentAccountId();
        Set<UUID> visited = new HashSet<>();
        
        while (currentParentId != null && !visited.contains(currentParentId)) {
            visited.add(currentParentId);
            
            VirtualAccount parent = vaRepository.findById(currentParentId).orElse(null);
            if (parent == null) break;
            
            if (parent.getProgramId() != null) {
                log.debug("Found programId {} from parent VA {}", parent.getProgramId(), parent.getVaNumber());
                return parent.getProgramId();
            }
            
            currentParentId = parent.getParentAccountId();
        }
        
        return null;
    }

    /**
     * Generate unique Settlement VA number.
     */
    private String generateSettlementVaNumber(String currency, VirtualAccount parentVa) {
        String suffix;
        if (parentVa != null) {
            String parentNumber = parentVa.getVaNumber();
            if (parentNumber.contains("-")) {
                String[] parts = parentNumber.split("-");
                suffix = parts[parts.length - 1];
                if (suffix.length() > 6) {
                    suffix = suffix.substring(suffix.length() - 6);
                }
            } else {
                suffix = parentNumber.substring(Math.max(0, parentNumber.length() - 6));
            }
        } else {
            suffix = String.valueOf(System.currentTimeMillis() % 1000000);
        }
        
        return "SETTLE-" + currency + "-" + suffix;
    }

    /**
     * Check if VA is an operational/transactional VA.
     */
    private boolean isOperationalVa(VirtualAccount va) {
        AccountCategory category = va.getAccountCategory();
        return category == AccountCategory.TRANSACTION ||
               category == AccountCategory.COLLECTION ||
               category == AccountCategory.DISBURSEMENT;
    }

    /**
     * Validate ownership of Settlement VA.
     */
    private void validateOwnership(VirtualAccount settlementVa, VirtualAccount sourceVa) {
        if (settlementVa.getCorporateId() == null) {
            String msg = String.format("Settlement VA %s has no corporateId - critical error", 
                settlementVa.getVaNumber());
            log.error(msg);
            throw new BusinessException(msg);
        }
        
        // FIX v4.5.2: Also validate programId
        if (settlementVa.getProgramId() == null) {
            log.warn("Settlement VA {} has no programId - this may cause filtering/query issues", 
                settlementVa.getVaNumber());
        }
        
        if (settlementVa.getOwningEntityId() == null) {
            String msg = String.format("Settlement VA %s has no owningEntityId - ownership chain incomplete", 
                settlementVa.getVaNumber());
            if (STRICT_OWNERSHIP_VALIDATION) {
                log.error(msg);
                throw new BusinessException(msg);
            } else {
                log.warn(msg);
            }
        }
        
        if (sourceVa != null && sourceVa.getOwningEntityId() != null && settlementVa.getOwningEntityId() != null) {
            if (!sourceVa.getOwningEntityId().equals(settlementVa.getOwningEntityId())) {
                log.warn("Settlement VA {} has different ownership ({}) than source {} ({})", 
                    settlementVa.getVaNumber(), settlementVa.getOwningEntityCode(),
                    sourceVa.getVaNumber(), sourceVa.getOwningEntityCode());
            }
        }
        
        if (sourceVa != null && sourceVa.getOwningEntityId() != null && settlementVa.getOwningEntityId() == null) {
            log.warn("Settlement VA {} did not inherit ownership from source {} (entity: {})", 
                settlementVa.getVaNumber(), sourceVa.getVaNumber(), sourceVa.getOwningEntityCode());
        }
        
        log.debug("Settlement VA {} validated: corporateId={}, programId={}, owningEntityId={}", 
            settlementVa.getVaNumber(), 
            settlementVa.getCorporateId(), 
            settlementVa.getProgramId(),
            settlementVa.getOwningEntityId());
    }
}