package com.bank.vam.service;

import com.bank.vam.dto.VirtualAccountDto;
import com.bank.vam.entity.Corporate;
import com.bank.vam.entity.PhysicalAccount;
import com.bank.vam.entity.Program;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.hierarchy.HierarchyLevelConfig;
import com.bank.vam.entity.hierarchy.HierarchyNode;
import com.bank.vam.entity.hierarchy.HierarchyNode.HierarchyNodeType;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.CorporateRepository;
import com.bank.vam.repository.PhysicalAccountRepository;
import com.bank.vam.repository.ProgramRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.HierarchyLevelConfigRepository;
import com.bank.vam.repository.hierarchy.HierarchyNodeRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.credit.InterestConfigurationRepository;
import com.bank.vam.repository.treasury.SweepRuleRepository;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.credit.InterestConfiguration;
import com.bank.vam.entity.treasury.SweepRule;
import com.bank.vam.entity.treasury.SweepRuleSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bank.vam.service.treasury.HierarchyVaService;
import com.bank.vam.service.treasury.SettlementVaService;
import com.bank.vam.service.credit.InterestConfigAttachmentService;
import org.springframework.beans.factory.annotation.Autowired;


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Virtual Account Service - Enhanced with full feature support and dynamic hierarchy.
 * 
 * Features:
 * - Program inheritance for limits and configuration
 * - DYNAMIC HIERARCHY CREATION (NEW) - Auto-creates L1-L7 nodes as VAs are created
 * - Limit management (spending, topup)
 * - KYC verification and tier management
 * - MCC restrictions
 * - Status management with reasons
 * - Comprehensive response mapping
 * 
 * Backward compatible: All existing methods continue to work.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VirtualAccountService {

    private final VirtualAccountRepository virtualAccountRepository;
    private final ProgramRepository programRepository;
    private final CorporateRepository corporateRepository;
    private final PhysicalAccountRepository physicalAccountRepository;
    private final HierarchyNodeRepository hierarchyNodeRepository;
    private final HierarchyLevelConfigRepository hierarchyLevelConfigRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final InterestConfigurationRepository interestConfigRepository;
    private final SweepRuleRepository sweepRuleRepository;
    private final ObjectMapper objectMapper;

    private SettlementVaService settlementVaService;
    private final HierarchyVaService hierarchyVaService;
    private final com.bank.vam.config.MarketProfileProperties marketProfile;
    
    // ========================================================================
    // NEW: Interest Config Attachment Service (Phase 1)
    // ========================================================================
    private InterestConfigAttachmentService interestConfigAttachmentService;

    @Autowired(required = false)
    public void setSettlementVaService(SettlementVaService settlementVaService) {
        this.settlementVaService = settlementVaService;
        log.info("SettlementVaService injected");
    }

    @Autowired(required = false)
    public void setInterestConfigAttachmentService(InterestConfigAttachmentService interestConfigAttachmentService) {
        this.interestConfigAttachmentService = interestConfigAttachmentService;
        log.info("InterestConfigAttachmentService injected");
    }
    // ========================================================================
    // READ OPERATIONS (existing - enhanced)
    // ========================================================================

    @Transactional(readOnly = true)
    public VirtualAccount getById(UUID id) {
        return virtualAccountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Virtual account not found: " + id));
    }

    @Transactional(readOnly = true)
    public VirtualAccountDto.Response getByIdWithDetails(UUID id) {
        VirtualAccount va = getById(id);
        return toResponse(va);
    }

    @Transactional(readOnly = true)
    public VirtualAccount getByVaNumber(String vaNumber) {
        return virtualAccountRepository.findByVaNumber(vaNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Virtual account not found: " + vaNumber));
    }

    @Transactional(readOnly = true)
    public VirtualAccount getByViban(String viban) {
        return virtualAccountRepository.findByViban(viban)
                .orElseThrow(() -> new ResourceNotFoundException("Virtual account not found for VIBAN: " + viban));
    }

    @Transactional(readOnly = true)
    public List<VirtualAccount> getByCorporateId(UUID corporateId) {
        return virtualAccountRepository.findByCorporateId(corporateId);
    }

    @Transactional(readOnly = true)
    public Page<VirtualAccount> getByCorporateId(UUID corporateId, Pageable pageable) {
        return virtualAccountRepository.findByCorporateId(corporateId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<VirtualAccount> getByProgramId(UUID programId, Pageable pageable) {
        return virtualAccountRepository.findByProgramId(programId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<VirtualAccount> search(String query, Pageable pageable) {
        return virtualAccountRepository.search(query, pageable);
    }

    @Transactional(readOnly = true)
    public Page<VirtualAccount> getByHierarchyNode(UUID nodeId, boolean includeDescendants, Pageable pageable) {
        if (includeDescendants) {
            HierarchyNode node = hierarchyNodeRepository.findById(nodeId)
                    .orElseThrow(() -> new ResourceNotFoundException("Hierarchy node not found: " + nodeId));
            String pathPrefix = node.getMaterializedPath() + "%";
            return virtualAccountRepository.findByHierarchyPathLike(pathPrefix, pageable);
        }
        return virtualAccountRepository.findByHierarchyNodeId(nodeId, pageable);
    }

    @Transactional(readOnly = true)
    public List<VirtualAccount> getByStatus(VirtualAccount.VaStatus status) {
        return virtualAccountRepository.findByStatus(status);
    }

    @Transactional(readOnly = true)
    public Page<VirtualAccount> getByWalletType(String walletType, Pageable pageable) {
        return virtualAccountRepository.findByWalletType(walletType, pageable);
    }

    // ========================================================================
    // CREATE WITH PROGRAM INHERITANCE (existing - backward compatible)
    // ========================================================================

    @Transactional
    public VirtualAccount create(VirtualAccountDto.CreateRequest request) {
        log.info("Creating virtual account: {}", request.getVaName());

        // ====================================================================
        // NEW v4.4: Check if this is a parent-based hierarchy creation
        // ====================================================================
        if (request.hasParentReference()) {
            log.info("Detected parent reference - using createWithParentNode flow");
            return createWithParentNode(request);
        }

        // ====================================================================
        // EXISTING: Legacy creation flow (unchanged)
        // ====================================================================
        
        // Validate references
        validateReferences(request);

        // Get program for defaults
        Program program = null;
        if (request.getProgramId() != null) {
            program = programRepository.findById(request.getProgramId())
                    .orElseThrow(() -> new ResourceNotFoundException("Program not found: " + request.getProgramId()));

            // Foreign-currency VAs are supported: currency mirrors roll them up
            // to the program's base currency. The old hard equality check here
            // contradicted that feature (and the parent-node flow never had it).
            if (request.getCurrencyCode() != null && !program.getCurrencyCode().equals(request.getCurrencyCode())) {
                log.info("Creating foreign-currency VA ({}) under {} program {}",
                    request.getCurrencyCode(), program.getCurrencyCode(), program.getProgramCode());
            }

            // Validate program can issue new VAs
            if (!program.canIssueWallet()) {
                throw new BusinessException("Program cannot issue new accounts. Status: " + program.getStatus());
            }

            // Validate max VA count
            if (program.getMaxVirtualAccounts() != null && 
                program.getCurrentVaCount() != null &&
                program.getCurrentVaCount() >= program.getMaxVirtualAccounts()) {
                throw new BusinessException("Program has reached maximum VA count: " + program.getMaxVirtualAccounts());
            }
        }

        // Generate VA number
        String vaNumber = generateVaNumber(request.getVaPrefix(), program);

        // Build VA with inheritance
        VirtualAccount va = buildVirtualAccount(request, program, vaNumber);

        // Save
        va = virtualAccountRepository.save(va);

        if (va.getParentAccountId() != null && isOperationalVa(va)) {
            try {
                hierarchyVaService.ensureCurrencyInfrastructure(va);
            } catch (Exception e) {
                log.warn("Currency infrastructure creation failed: {}", e.getMessage());
            }
        }
        
        // Increment program VA count
        if (program != null) {
            program.incrementVaCount();
            programRepository.save(program);
        }

        log.info("Virtual account created: {} (ID: {})", va.getVaNumber(), va.getId());
        return va;
    }


    // ========================================================================
    // ADD: New createWithParentNode() method - Add after create() method
    // ========================================================================

    /**
     * Create a VA as a child of a parent node (NEW v4.4 METHOD).
     * 
     * This is for direct parent-child creation from the UI where the user
     * selects a parent node and creates a child VA directly.
     * 
     * Flow:
     * 1. Resolve parent from parentNodeId (could be HierarchyNode ID or VA ID)
     * 2. Use parent's programId for consistency
     * 3. Create HierarchyNode as child of parent
     * 4. Create VirtualAccount linked to the new node
     * 5. Update parent's child count
     * 
     * @param request CreateRequest with parentNodeId set
     * @return Created VirtualAccount
     */
// ========================================================================
    // FIXED: createWithParentNode() method
    // ========================================================================
    // 
    // PROBLEM: Database constraint requires VIRTUAL_ACCOUNT nodes to have virtualAccountId.
    // OLD CODE: Creates node first (with nodeType=VIRTUAL_ACCOUNT), then VA.
    // RESULT: Node save fails because virtualAccountId is null.
    //
    // SOLUTION: Create VA first, then create node with virtualAccountId already set.
    // ========================================================================

// ========================================================================
    // FIXED: createWithParentNode() method
    // ========================================================================
    // 
    // PROBLEM: Database constraint requires VIRTUAL_ACCOUNT nodes to have virtualAccountId.
    // OLD CODE: Creates node first (with nodeType=VIRTUAL_ACCOUNT), then VA.
    // RESULT: Node save fails because virtualAccountId is null.
    //
    // SOLUTION: Create VA first, then create node with virtualAccountId already set.
    // ========================================================================

    /**
     * Create a VA as a child of a parent node (NEW v4.4 METHOD - FIXED).
     * 
     * This is for direct parent-child creation from the UI where the user
     * selects a parent node and creates a child VA directly.
     * 
     * Flow (FIXED ORDER):
     * 1. Resolve parent from parentNodeId (could be HierarchyNode ID or VA ID)
     * 2. Use parent's programId for consistency
     * 3. Create VirtualAccount FIRST (to get the VA ID)
     * 4. Create HierarchyNode with virtualAccountId already set
     * 5. Update VA with hierarchyNodeId
     * 6. Update parent's child count
     * 
     * @param request CreateRequest with parentNodeId set
     * @return Created VirtualAccount
     */
    @Transactional
    public VirtualAccount createWithParentNode(VirtualAccountDto.CreateRequest request) {
        UUID parentId = request.getEffectiveParentId();
        log.info("Creating VA with parent reference: {} under parent {}", request.getVaName(), parentId);

        // STEP 1: Resolve parent (could be node ID or VA ID)
        HierarchyNode parentNode = resolveParentNode(parentId);
        if (parentNode == null) {
            throw new ResourceNotFoundException("Parent not found: " + parentId + 
                " (tried both hierarchy_nodes and virtual_accounts tables)");
        }

        // STEP 2: Get program from parent (ensures consistency)
        UUID actualProgramId = parentNode.getProgramId();
        Program program = programRepository.findById(actualProgramId)
                .orElseThrow(() -> new ResourceNotFoundException("Program not found: " + actualProgramId));

        // Log if request programId differs from parent's
        if (request.getProgramId() != null && !request.getProgramId().equals(actualProgramId)) {
            log.info("Using parent's programId {} instead of request programId {}",
                actualProgramId, request.getProgramId());
        }

        // Issuance guards — previously enforced only on the legacy (dimension-less)
        // flow, which let tree-based creation bypass the program's status gate and
        // VA cap. All creation paths converge on this method, so enforce here.
        if (!program.canIssueWallet()) {
            throw new BusinessException("Program cannot issue new accounts. Status: " + program.getStatus());
        }
        if (program.getMaxVirtualAccounts() != null &&
            program.getCurrentVaCount() != null &&
            program.getCurrentVaCount() >= program.getMaxVirtualAccounts()) {
            throw new BusinessException("Program has reached maximum VA count: " + program.getMaxVirtualAccounts());
        }

        // STEP 2b: Resolve physicalAccountId (optional but recommended)
        // Priority: 1) From program, 2) From parent VA, 3) Log warning and proceed
        // NOTE (v5.3.0): Physical account is now optional to allow hierarchy setup without CBS integration.
        // VAs without physical account cannot process actual payments until one is linked.
        UUID physicalAccountId = program.getPhysicalAccountId();

        if (physicalAccountId == null) {
            // Try to get from parent VA
            UUID parentVaIdForPhysical = parentNode.getVirtualAccountId();
            if (parentVaIdForPhysical != null) {
                VirtualAccount parentVa = virtualAccountRepository.findById(parentVaIdForPhysical).orElse(null);
                if (parentVa != null && parentVa.getPhysicalAccountId() != null) {
                    physicalAccountId = parentVa.getPhysicalAccountId();
                    log.info("Inherited physicalAccountId {} from parent VA {}",
                        physicalAccountId, parentVa.getVaNumber());
                }
            }
        }

        // Log warning if no physical account, but proceed (v5.3.0: made optional)
        if (physicalAccountId == null) {
            log.warn("⚠️ Creating VA without physical account for program '{}'. " +
                "This VA will NOT be able to process actual payments until a physical account is linked. " +
                "Consider linking a physical account to the program via Program Configuration.",
                program.getProgramCode());
        }

        // STEP 3: Calculate hierarchy level (using configurable max depth)
        int maxDepth = program.getMaxHierarchyDepth() != null
            ? program.getMaxHierarchyDepth()
            : HierarchyNode.DEFAULT_MAX_DEPTH;
        int newLevel = parentNode.getLevelNumber() + 1;
        if (newLevel > maxDepth) {
            throw new BusinessException("Cannot create VA beyond hierarchy level " + maxDepth +
                ". Current parent is at level " + parentNode.getLevelNumber() +
                ". Consider increasing program's maxHierarchyDepth if deeper hierarchy is needed.");
        }

        // STEP 4: Determine currency (inherit from parent if not specified)
        String currencyCode = request.getCurrencyCode() != null 
            ? request.getCurrencyCode() 
            : parentNode.getCurrencyCode();

        // STEP 5: Determine account category
        VirtualAccount.AccountCategory category = VirtualAccount.AccountCategory.TRANSACTION;
        if (request.getAccountCategory() != null) {
            try {
                category = VirtualAccount.AccountCategory.valueOf(request.getAccountCategory().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid account category: {}, defaulting to TRANSACTION", request.getAccountCategory());
            }
        }

        // STEP 6: Determine account type
        VirtualAccount.AccountType accountType = VirtualAccount.AccountType.VIRTUAL;
        if (request.getAccountType() != null) {
            try {
                accountType = VirtualAccount.AccountType.valueOf(request.getAccountType().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid account type: {}, defaulting to VIRTUAL", request.getAccountType());
            }
        }

        // STEP 7: Generate VA number
        String vaNumber = generateParentBasedVaNumber(program, category, newLevel, request.getVaPrefix());

        // STEP 8: Build hierarchy path (pre-calculate for VA)
        String nodeCode = generateNodeCodeForParentBased(vaNumber, category);
        String materializedPath = parentNode.getMaterializedPath() + "/" + nodeCode;

        // STEP 9: Get parent VA ID for linking
        UUID parentVaId = getParentVaIdFromNode(parentNode);

        // ====================================================================
        // STEP 10: Create VirtualAccount FIRST (FIXED ORDER)
        // We create VA first to get its ID, then create the node with the ID
        // ====================================================================

        // Determine base currency - CRITICAL for Currency Mirror creation
        // Base currency comes from: request > program configuration
        String resolvedBaseCurrency = request.getBaseCurrency() != null
            ? request.getBaseCurrency()
            : program.getCurrencyCode();

        log.debug("VA Creation - Currency resolution: vaCurrency={}, programCurrency={}, requestBaseCurrency={}, resolvedBaseCurrency={}",
            currencyCode, program.getCurrencyCode(), request.getBaseCurrency(), resolvedBaseCurrency);

        // If VA currency differs from program base currency, this is a foreign currency VA
        if (!currencyCode.equalsIgnoreCase(resolvedBaseCurrency)) {
            log.info("Foreign currency VA detected: {} (base: {}) - Currency Mirror will be created",
                currencyCode, resolvedBaseCurrency);
        }

        VirtualAccount va = VirtualAccount.builder()
                .vaNumber(vaNumber)
                .viban(request.getViban())
                .vaName(request.getVaName())
                .programId(actualProgramId)
                .corporateId(program.getCorporateId())
                .physicalAccountId(physicalAccountId)  // Use resolved value (from program or parent VA)
                .currencyCode(currencyCode)
                // Base currency should be the PROGRAM's currency, not the VA's currency
                // This is critical for Currency Mirror creation to work correctly
                .baseCurrency(resolvedBaseCurrency)
                // Hierarchy fields - hierarchyNodeId will be set after node creation
                .hierarchyPath(materializedPath)
                .hierarchyLevel(newLevel - 1) // parentNode.getLevelNumber() is 1-indexed, va.hierarchyLevel is 0-indexed
                .parentAccountId(parentVaId)
                // Account classification
                .accountType(accountType)
                .accountCategory(category)
                // Ownership (inherit from parent if not specified)
                .owningEntityId(request.getOwningEntityId())
                .owningEntityCode(request.getOwningEntityCode())
                // Balances
                .currentBalance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .heldBalance(BigDecimal.ZERO)
                .aggregatedBalance(BigDecimal.ZERO)
                .status(VirtualAccount.VaStatus.ACTIVE)
                // Counters
                .transactionCount(0)
                .topupCount(0)
                .withdrawalCount(0)
                .dailyUsed(BigDecimal.ZERO)
                .weeklyUsed(BigDecimal.ZERO)
                .monthlyUsed(BigDecimal.ZERO)
                .annualUsed(BigDecimal.ZERO)
                .dailyTopupUsed(BigDecimal.ZERO)
                .monthlyTopupUsed(BigDecimal.ZERO)
                .lastLimitResetDate(LocalDate.now())
                .lastActivityDate(LocalDate.now())
                // Other fields
                .externalReference(request.getExternalReference())
                .metadata(request.getMetadata())
                .kycVerified(false)
                .kycLevel(0)
                .build();

        // Apply program defaults
        applyProgramDefaultsToVa(va, program);

        // Then the request's own values, which take precedence. Without this the
        // flow accepted holderPartyId, walletType, limits and the stored-value
        // fields on CreateRequest and silently discarded every one of them.
        applyRequestOverridesToVa(va, request);

        // Placement is derived from the parent here, so re-assert it: the request
        // may carry its own hierarchyPath, and in this flow the parent decides.
        va.setHierarchyPath(materializedPath);

        // Inherit ownership from parent if not specified
        if (va.getOwningEntityId() == null) {
            inheritOwnershipFromParent(va, parentNode);
        }

        // Backfill the denormalized entity code when the caller supplied only
        // the id — keeps dimension-flow VAs shaped like tree-flow VAs.
        if (va.getOwningEntityId() != null &&
            (va.getOwningEntityCode() == null || va.getOwningEntityCode().isBlank())) {
            LegalEntity owner = legalEntityRepository.findById(va.getOwningEntityId()).orElse(null);
            if (owner != null) {
                va.setOwningEntityCode(owner.getEntityCode());
            }
        }

        // Save VA first to get its ID. saveAndFlush (not save): when the caller
        // has pending hierarchy_nodes inserts in the same transaction (the
        // dimension flow's chain), hibernate.order_inserts would otherwise batch
        // the VA-linked leaf node together with them BEFORE the VA row exists,
        // violating fk_node_virtual_account.
        va = virtualAccountRepository.saveAndFlush(va);
        log.debug("Created virtual account: {} (ID: {})", va.getVaNumber(), va.getId());

        // ====================================================================
        // STEP 11: Now create hierarchy node WITH virtualAccountId set
        // This satisfies the DB constraint for VIRTUAL_ACCOUNT nodes
        // ====================================================================
        HierarchyNodeType nodeType = mapCategoryToNodeType(category);
        boolean isLeaf = (nodeType == HierarchyNodeType.VIRTUAL_ACCOUNT);

        HierarchyNode node = HierarchyNode.builder()
                .programId(actualProgramId)
                .parentId(parentNode.getId())
                .levelNumber(newLevel)
                .nodeCode(nodeCode)
                .nodeName(request.getVaName())
                .nodeType(nodeType)
                .currencyCode(currencyCode)
                .dimensionValue(request.getAccountPurpose() != null ? request.getAccountPurpose() : category.name())
                .materializedPath(materializedPath)
                .isLeaf(isLeaf)
                .childCount(0)
                .status("ACTIVE")
                .aggregatedBalance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                // KEY FIX: Set virtualAccountId during creation, not after
                .virtualAccountId(va.getId())
                .build();

        node = hierarchyNodeRepository.save(node);
        log.debug("Created hierarchy node: {} at path: {} with VA link: {}", 
            node.getNodeCode(), node.getMaterializedPath(), va.getId());

        // STEP 12: Update VA with hierarchyNodeId (bidirectional link)
        va.setHierarchyNodeId(node.getId());
        va = virtualAccountRepository.save(va);

        // STEP 13: the parent's child_count and is_leaf are maintained by the
        // trg_hierarchy_nodes_child_count trigger, which already fired on the
        // insert above. Incrementing here as well read a pre-insert snapshot and
        // wrote back a value that only happened to match the trigger's; under
        // concurrent creation both threads write parent+1 and one insert is lost,
        // silently, since @Version is disabled on BaseEntity.

        // STEP 14: Increment program VA count
        program.incrementVaCount();
        programRepository.save(program);

        // STEP 15: Create currency infrastructure if operational VA (Currency Mirrors)
        // This creates M-{currency} nodes up the hierarchy for foreign currency VAs
        if (isOperationalVa(va)) {
            try {
                log.info("Creating currency infrastructure for VA {} currency {} (base: {})",
                    va.getVaNumber(), va.getCurrencyCode(), va.getBaseCurrency());
                List<VirtualAccount> created = hierarchyVaService.ensureCurrencyInfrastructure(va);
                if (created != null && !created.isEmpty()) {
                    log.info("✓ Created {} Currency Mirror(s) for VA {}: {}",
                        created.size(), va.getVaNumber(),
                        created.stream().map(VirtualAccount::getVaNumber).toList());
                }
            } catch (Exception e) {
                log.error("Currency infrastructure creation failed for VA {}: {} - {}",
                    va.getVaNumber(), e.getClass().getSimpleName(), e.getMessage(), e);
            }
        }

        // ====================================================================
        // STEP 16: AUTO-CREATE SETTLEMENT VA AS SIBLING (per design)
        // Settlement VA is required for double-entry bookkeeping
        // ====================================================================
        if (settlementVaService != null && isOperationalVa(va)) {
            try {
                settlementVaService.ensureSettlementVaForSibling(va);
                log.info("✓ Settlement VA ensured for {}", va.getVaNumber());
            } catch (Exception e) {
                log.warn("Failed to auto-create Settlement VA for {}: {}", va.getVaNumber(), e.getMessage());
                // Don't fail the main transaction - Settlement VA creation is optional
            }
        }

        //===================================================================
        // STEP 17: Attach Interest Config  if applicable (per program config)
        //===================================================================
        // After VA is saved and before return:
        if (interestConfigAttachmentService != null && isOperationalVa(va)) {
            try {
                va = interestConfigAttachmentService.autoAttachInterestConfigs(va);
                log.info("✓ Interest configs attached to VA {}: external={}, internal={}", 
                    va.getVaNumber(), va.getExternalInterestConfigId(), va.getInternalInterestConfigId());
            } catch (Exception e) {
                log.warn("Failed to auto-attach interest configs for VA {}: {}", 
                    va.getVaNumber(), e.getMessage());
            }
        }

        log.info("✓ Created VA {} with hierarchy node at level {} under parent {} (program: {})", 
            va.getVaNumber(), newLevel, parentNode.getNodeCode(), program.getProgramCode());

        return va;
    }


    // ========================================================================
    // ADD: Helper methods for parent-based creation
    // Add these at the end of the class, before the closing brace
    // Check if they already exist - if so, skip adding them
    // ========================================================================

    /**
     * Resolve parent from either HierarchyNode ID or VirtualAccount ID.
     * This allows the frontend to pass either type of ID.
     */
    private HierarchyNode resolveParentNode(UUID id) {
        if (id == null) {
            return null;
        }

        // First try hierarchy_nodes table
        Optional<HierarchyNode> nodeOpt = hierarchyNodeRepository.findById(id);
        if (nodeOpt.isPresent()) {
            log.debug("Found parent as HierarchyNode: {}", id);
            return nodeOpt.get();
        }

        // Then try virtual_accounts table
        Optional<VirtualAccount> vaOpt = virtualAccountRepository.findById(id);
        if (vaOpt.isPresent()) {
            VirtualAccount va = vaOpt.get();
            log.debug("Found parent as VirtualAccount: {}", id);

            // Get the VA's hierarchy node
            if (va.getHierarchyNodeId() != null) {
                Optional<HierarchyNode> nodeFromVa = hierarchyNodeRepository.findById(va.getHierarchyNodeId());
                if (nodeFromVa.isPresent()) {
                    log.debug("Found associated hierarchy node: {}", va.getHierarchyNodeId());
                    return nodeFromVa.get();
                }
            }

            // Try finding node by VA ID
            Optional<HierarchyNode> nodeByVaId = hierarchyNodeRepository.findByVirtualAccountId(id);
            if (nodeByVaId.isPresent()) {
                log.debug("Found hierarchy node by virtualAccountId: {}", id);
                return nodeByVaId.get();
            }
        }

        log.warn("Could not resolve parent node for ID: {}", id);
        return null;
    }

    /**
     * Get parent VA ID from hierarchy node.
     */
    private UUID getParentVaIdFromNode(HierarchyNode parentNode) {
        // Walk UP the chain: pure dimension nodes (built by ensureHierarchyPath)
        // carry no VA, but parent_account_id must still connect — VA-table-based
        // tree views (Balance Hierarchy) traverse it. Link to the nearest
        // ancestor that has a VA (ultimately the program ROOT VA).
        HierarchyNode current = parentNode;
        int guard = 0;
        while (current != null && guard++ < 20) {
            if (current.getVirtualAccountId() != null) {
                return current.getVirtualAccountId();
            }
            Optional<VirtualAccount> linked = virtualAccountRepository.findByHierarchyNodeId(current.getId());
            if (linked.isPresent()) {
                return linked.get().getId();
            }
            current = current.getParentId() != null
                ? hierarchyNodeRepository.findById(current.getParentId()).orElse(null)
                : null;
        }
        return null;
    }

    /**
     * Generate VA number for parent-based creation.
     */
    private String generateParentBasedVaNumber(Program program, VirtualAccount.AccountCategory category, 
                                                int level, String prefix) {
        String programPrefix = program.getVaPrefix() != null ? program.getVaPrefix() : program.getProgramCode();
        if (programPrefix.length() > 6) {
            programPrefix = programPrefix.substring(0, 6);
        }
        
        String categoryPrefix = getCategoryPrefixForVa(category);
        String userPrefix = prefix != null && !prefix.isEmpty() 
            ? "-" + prefix.toUpperCase().replaceAll("[^A-Z0-9]", "").substring(0, Math.min(prefix.length(), 4))
            : "";
        
        long timestamp = System.currentTimeMillis() % 100000;
        return String.format("%s-%s%s-L%d-%05d", 
            programPrefix.toUpperCase(), categoryPrefix, userPrefix, level, timestamp);
    }

    /**
     * Get prefix based on account category.
     */
    private String getCategoryPrefixForVa(VirtualAccount.AccountCategory category) {
        if (category == null) return "VA";
        return switch (category) {
            case ROOT -> "ROOT";
            case AGGREGATION -> "AGG";
            case TRANSACTION -> "TXN";
            case COLLECTION -> "COL";
            case DISBURSEMENT -> "DIS";
            case SETTLEMENT -> "SET";
            case EXCEPTION -> "EXC";
            case CURRENCY_MIRROR -> "MIR";
            case INTERCOMPANY -> "IHB";
            case ESCROW -> "ESC";
            default -> "VA";
        };
    }

    /**
     * Generate node code from VA number and category.
     */
    private String generateNodeCodeForParentBased(String vaNumber, VirtualAccount.AccountCategory category) {
        String suffix = vaNumber.length() > 8 
            ? vaNumber.substring(vaNumber.length() - 8) 
            : vaNumber;
        return getCategoryPrefixForVa(category) + "-" + suffix;
    }

    /**
     * Map account category to hierarchy node type.
     */
    private HierarchyNodeType mapCategoryToNodeType(VirtualAccount.AccountCategory category) {
        if (category == null) return HierarchyNodeType.VIRTUAL_ACCOUNT;
        return switch (category) {
            case ROOT -> HierarchyNodeType.MASTER;
            case AGGREGATION, CURRENCY_MIRROR -> HierarchyNodeType.CONSOLIDATION;
            default -> HierarchyNodeType.VIRTUAL_ACCOUNT; // Leaf nodes
        };
    }

    // ========================================================================
    // NEW: CREATE WITH DYNAMIC HIERARCHY (Production Flow)
    // ========================================================================

    /**
     * CONVERGED dimension-based creation (backs POST /virtual-accounts/with-dimensions).
     *
     * <p>Resolves/creates the intermediate hierarchy-node chain from the given
     * dimension values ({@link #ensureHierarchyPath} — validates required
     * dimensions against the program's level configs, normalizes values,
     * reuses existing nodes), then delegates the leaf VA to
     * {@link #createWithParentNode}, the single write path. Both the
     * dimension-driven UI flow and the hierarchy-tree flow therefore produce
     * identical records: VA + linked hierarchy node, program VA-count,
     * currency mirrors, and settlement-VA sibling.
     */
    @Transactional
    public VirtualAccount createWithDimensions(VirtualAccountDto.CreateRequest request,
                                               Map<String, String> hierarchyDimensions) {
        Program program = programRepository.findById(request.getProgramId())
            .orElseThrow(() -> new ResourceNotFoundException("Program not found: " + request.getProgramId()));
        List<HierarchyLevelConfig> levelConfigs =
            hierarchyLevelConfigRepository.findByProgramIdOrderByLevelNumberAsc(program.getId());
        if (levelConfigs.isEmpty()) {
            throw new BusinessException("No hierarchy levels configured. Apply a template first.");
        }

        // The VIRTUAL_ACCOUNT level is the leaf being created by this call, not
        // a dimension — exclude it so ensureHierarchyPath builds (and requires
        // dimensions for) only the aggregation chain above the VA.
        List<HierarchyLevelConfig> aggregationLevels = levelConfigs.stream()
            .filter(lc -> !"VIRTUAL_ACCOUNT".equalsIgnoreCase(lc.getDimensionType()))
            .toList();
        if (aggregationLevels.isEmpty()) {
            throw new BusinessException("Program hierarchy has no aggregation levels above the VA level.");
        }

        HierarchyNode leafParent = ensureHierarchyPath(program, aggregationLevels, hierarchyDimensions);

        request.setParentNodeId(leafParent.getId());
        return createWithParentNode(request);
    }

    /**
     * Create a Virtual Account with dynamic hierarchy path creation.
     * This is the PRODUCTION method - hierarchy nodes are created on-demand.
     * 
     * @param request Contains program, hierarchy dimensions, and VA details
     * @return Created VA with hierarchy node linked
     * 
     * Example request for IHB Program:
     * {
     *   "programId": "...",
     *   "currency": "AED",
     *   "hierarchyDimensions": {
     *     "L1": "AED",           // Currency
     *     "L2": "NORTH",         // Region
     *     "L3": "DUBAI",         // State/City
     *     "L4": "ENTITY-001",    // Entity
     *     "L5": "PAYABLES",      // Account Type
     *     "L6": "SUPPLIER"       // Sub-category
     *   },
     *   "vaName": "ABC Supplier Payables",
     *   "externalReference": "SUP-12345"
     * }
     */
    @Transactional
    public VirtualAccount createWithHierarchy(VirtualAccountDto.CreateWithHierarchyRequest request) {
        log.info("Creating VA with dynamic hierarchy for program: {}", request.getProgramId());

        Program program = programRepository.findById(request.getProgramId())
            .orElseThrow(() -> new ResourceNotFoundException("Program not found: " + request.getProgramId()));

        // Get level configurations for the program
        List<HierarchyLevelConfig> levelConfigs = 
            hierarchyLevelConfigRepository.findByProgramIdOrderByLevelNumberAsc(program.getId());
        
        if (levelConfigs.isEmpty()) {
            throw new BusinessException("No hierarchy levels configured. Apply a template first.");
        }

        // Validate program can issue new VAs
        if (!program.canIssueWallet()) {
            throw new BusinessException("Program cannot issue new accounts. Status: " + program.getStatus());
        }

        // Build/find hierarchy path dynamically - THIS IS THE KEY PRODUCTION LOGIC
        HierarchyNode leafNode = ensureHierarchyPath(program, levelConfigs, request.getHierarchyDimensions());

        // Generate VA number with hierarchy encoding
        String vaNumber = generateVaNumberWithHierarchy(program, leafNode);

        // Determine currency
        String currency = request.getCurrency() != null ? request.getCurrency() : program.getCurrencyCode();

        // Create the Virtual Account
        VirtualAccount va = VirtualAccount.builder()
            .vaNumber(vaNumber)
            .vaName(request.getVaName())
            .programId(program.getId())
            .corporateId(program.getCorporateId())
            .physicalAccountId(program.getPhysicalAccountId())
            .currencyCode(currency)
            .status(VirtualAccount.VaStatus.ACTIVE)
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .heldBalance(BigDecimal.ZERO)
            .hierarchyNodeId(leafNode.getId())
            .hierarchyPath(leafNode.getMaterializedPath())
            .externalReference(request.getExternalReference())
            .metadata(request.getMetadata())
            .kycVerified(false)
            .kycLevel(0)
            .transactionCount(0)
            .topupCount(0)
            .withdrawalCount(0)
            .dailyUsed(BigDecimal.ZERO)
            .weeklyUsed(BigDecimal.ZERO)
            .monthlyUsed(BigDecimal.ZERO)
            .annualUsed(BigDecimal.ZERO)
            .dailyTopupUsed(BigDecimal.ZERO)
            .monthlyTopupUsed(BigDecimal.ZERO)
            .lastLimitResetDate(LocalDate.now())
            .lastActivityDate(LocalDate.now())
            // .parent_account_id(request.getParentAccountId())
            .build();

        // Apply program defaults
        applyProgramDefaultsToVa(va, program);

        // CreateWithHierarchyRequest is a different DTO from CreateRequest, so it
        // cannot use applyRequestOverridesToVa; it carries only these two.
        if (request.getWalletType() != null) va.setWalletType(request.getWalletType());
        if (request.getKycLevel() != null) {
            va.setKycLevel(request.getKycLevel());
            va.setKycVerified(request.getKycLevel() > 0);
        }

        // ====================================================================
        // OWNERSHIP INHERITANCE (v4.3) - Inherit from parent VA
        // ====================================================================
        inheritOwnershipFromParent(va, leafNode);
        // ====================================================================

        va = virtualAccountRepository.save(va);

        // Link the leaf node to the VA
        leafNode.setVirtualAccountId(va.getId());
        leafNode.setIsLeaf(true);
        hierarchyNodeRepository.save(leafNode);

        // Update program VA count
        program.incrementVaCount();
        programRepository.save(program);

        // ====================================================================
        // AUTO-CREATE SETTLEMENT VA AS SIBLING (v4.3)
        // ====================================================================
        if (settlementVaService != null) {
            try {
                settlementVaService.ensureSettlementVaForSibling(va);
            } catch (Exception e) {
                log.warn("Failed to auto-create Settlement VA for {}: {}", va.getVaNumber(), e.getMessage());
                // Don't fail the main transaction - Settlement VA creation is optional
            }
        }
        // ====================================================================

        // Validate ownership chain
        List<String> ownershipIssues = validateOwnershipChain(va);
        if (!ownershipIssues.isEmpty()) {
            log.warn("Ownership chain issues for VA {}: {}", va.getVaNumber(), ownershipIssues);
        }

        log.info("Created VA {} at hierarchy path {} for program {} (owned by: {})", 
            va.getVaNumber(), leafNode.getMaterializedPath(), program.getProgramCode(),
            va.getOwningEntityCode() != null ? va.getOwningEntityCode() : "NONE");

        if (isOperationalVa(va)) {
        try {
            hierarchyVaService.ensureCurrencyInfrastructure(va);
        } catch (Exception e) {
            log.warn("Currency infrastructure creation failed: {}", e.getMessage());
        }
    }
        return va;
    }

    /**
     * Ensure the hierarchy path exists, creating missing nodes dynamically.
     * This is the core of the dynamic hierarchy creation.
     * 
     * PRODUCTION FLOW:
     * 1. For each level L1 → L7 (or max level):
     *    - Check if node with (parent, code) exists → REUSE
     *    - If not exists → CREATE
     * 2. Return the leaf node (last level)
     * 
     * @param program The program
     * @param levelConfigs Level configurations (L1-L7 schema)
     * @param dimensions Map of level -> dimension value (e.g., L1->AED, L2->NORTH)
     * @return The leaf node (L7 or last level)
     */
    private HierarchyNode ensureHierarchyPath(Program program, 
                                               List<HierarchyLevelConfig> levelConfigs,
                                               Map<String, String> dimensions) {
        
        HierarchyNode currentNode = null;
        String currentPath = "/" + program.getProgramCode();
        
        for (HierarchyLevelConfig levelConfig : levelConfigs) {
            int level = levelConfig.getLevelNumber();
            String levelKey = "L" + level;
            String dimensionValue = dimensions.get(levelKey);
            
            // Also try lowercase key
            if (dimensionValue == null) {
                dimensionValue = dimensions.get("l" + level);
            }
            
            // Also try level name as key
            if (dimensionValue == null) {
                dimensionValue = dimensions.get(levelConfig.getLevelName());
            }
            
            if (dimensionValue == null || dimensionValue.isEmpty()) {
                // For non-required levels, we can skip or use a default
                if (Boolean.TRUE.equals(levelConfig.getIsRequired())) {
                    throw new BusinessException("Missing required hierarchy dimension: " + 
                        levelConfig.getLevelName() + " (L" + level + ")");
                }
                // Skip optional empty levels
                continue;
            }

            // Normalize dimension value
            dimensionValue = dimensionValue.trim().toUpperCase().replace(" ", "-");

            // Build node code (unique within parent)
            String nodeCode = buildNodeCode(currentNode, dimensionValue, level);

            // Try to find existing node
            Optional<HierarchyNode> existingNode = findNodeByParentAndCode(
                program.getId(),
                currentNode != null ? currentNode.getId() : null,
                nodeCode,
                dimensionValue,
                level
            );

            if (existingNode.isPresent()) {
                // Node exists, traverse to it
                currentNode = existingNode.get();
                currentPath = currentNode.getMaterializedPath();
                log.debug("Reusing existing node: {} at level {}", nodeCode, level);
            } else {
                // Node doesn't exist, CREATE IT
                currentPath = currentPath + "/" + dimensionValue;
                
                HierarchyNode newNode = HierarchyNode.builder()
                    .programId(program.getId())
                    .parentId(currentNode != null ? currentNode.getId() : null)
                    .levelNumber(level)
                    .nodeCode(nodeCode)
                    .nodeName(buildNodeName(levelConfig, dimensionValue))
                    .nodeType(determineNodeType(levelConfig, level))
                    .currencyCode(program.getCurrencyCode())
                    .dimensionValue(dimensionValue)
                    .materializedPath(currentPath)
                    .isLeaf(level == levelConfigs.size())
                    .childCount(0)
                    .status("ACTIVE")
                    .aggregatedBalance(BigDecimal.ZERO)
                    .availableBalance(BigDecimal.ZERO)
                    .build();

                newNode = hierarchyNodeRepository.save(newNode);

                // The parent's child_count and is_leaf are maintained by the
                // trg_hierarchy_nodes_child_count trigger on the insert above.
                if (currentNode == null) {
                    // This is L1 node, update program root if needed
                    if (program.getRootHierarchyNodeId() == null) {
                        program.setRootHierarchyNodeId(newNode.getId());
                        programRepository.save(program);
                    }
                }

                log.info("CREATED hierarchy node: {} ({}) at L{} path {}", 
                    nodeCode, levelConfig.getLevelName(), level, currentPath);

                currentNode = newNode;
            }
        }

        if (currentNode == null) {
            throw new BusinessException("Failed to create hierarchy path - no dimensions provided");
        }

        return currentNode;
    }

    /**
     * Find a node by parent and code.
     */
    private Optional<HierarchyNode> findNodeByParentAndCode(UUID programId, UUID parentId,
                                                            String nodeCode, String dimensionValue, int level) {
        if (parentId == null) {
            // Looking for the root node (L1). A program has exactly ONE root —
            // if one exists under a different code (e.g. a wizard-built 'ROOT'
            // vs this builder's '<DIM>-MASTER'), reuse it rather than forking a
            // second root, which breaks every findRootNode-style lookup.
            List<HierarchyNode> rootLevel =
                hierarchyNodeRepository.findByProgramIdAndLevelNumber(programId, level);
            return rootLevel.stream()
                .filter(n -> n.getNodeCode().equals(nodeCode))
                .findFirst()
                .or(() -> rootLevel.stream().findFirst());
        }
        // Within a parent, the dimension value IS the node's identity — code
        // match first (fast path for chains this builder created), then fall
        // back to dimension-value match so selecting an existing branch built
        // by another tool (hierarchy wizard, older schemes) reuses it instead
        // of forking a parallel node with a different code.
        return hierarchyNodeRepository.findByParentIdAndNodeCode(parentId, nodeCode)
            .or(() -> hierarchyNodeRepository.findByParentIdOrderByDisplayOrderAsc(parentId).stream()
                .filter(n -> dimensionValue.equalsIgnoreCase(n.getDimensionValue()))
                .findFirst());
    }


    
    
    /**
     * Build unique node code.
     */
    private String buildNodeCode(HierarchyNode parent, String dimensionValue, int level) {
        if (parent == null) {
            return dimensionValue + "-MASTER";
        }
        // Truncate parent code if too long to avoid exceeding column limits
        String parentPrefix = parent.getNodeCode();
        if (parentPrefix.length() > 50) {
            parentPrefix = parentPrefix.substring(0, 50);
        }
        return parentPrefix + "-" + dimensionValue;
    }

    /**
     * Build human-readable node name.
     */
    private String buildNodeName(HierarchyLevelConfig levelConfig, String dimensionValue) {
        // Format nicely: "Dubai (City)" or "North Region"
        String formatted = dimensionValue.replace("-", " ");
        // Title case
        formatted = Arrays.stream(formatted.split(" "))
            .map(word -> word.isEmpty() ? word : 
                Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase())
            .collect(Collectors.joining(" "));
        
        return formatted + " (" + levelConfig.getLevelName() + ")";
    }

    /**
     * Determine node type based on level.
     */
    /**
     * Node type comes from the level's configured dimension type, not its
     * position: the old position-based rule (`level == maxLevel` ⇒
     * VIRTUAL_ACCOUNT) mistyped the deepest aggregation node whenever the
     * chain being built stops above the VA level — and VIRTUAL_ACCOUNT nodes
     * without a linked VA violate the entity's save-time guard.
     */
    private HierarchyNodeType determineNodeType(HierarchyLevelConfig levelConfig, int level) {
        if ("VIRTUAL_ACCOUNT".equalsIgnoreCase(levelConfig.getDimensionType())) {
            return HierarchyNodeType.VIRTUAL_ACCOUNT;
        }
        if (level == 1) return HierarchyNodeType.MASTER;
        return HierarchyNodeType.CONSOLIDATION;
    }

    /**
     * Generate unique VA number with hierarchy encoding.
     */
    private String generateVaNumberWithHierarchy(Program program, HierarchyNode leafNode) {
        String prefix = program.getVaPrefix() != null ? program.getVaPrefix() : program.getProgramCode();
        if (prefix.length() > 8) prefix = prefix.substring(0, 8);
        
        // Get sequence number
        long count = virtualAccountRepository.countByProgramId(program.getId());
        
        // Encode hierarchy level in VA number
        String levelCode = String.format("L%d", leafNode.getLevelNumber());
        String sequence = String.format("%06d", count + 1);
        
        return String.format("%s-%s-%s-%s", 
            prefix.toUpperCase(),
            leafNode.getCurrencyCode(),
            levelCode,
            sequence
        );
    }

    /**
     * Apply program defaults to VA.
     */
    private void applyProgramDefaultsToVa(VirtualAccount va, Program program) {
        if (program.getDefaultPerTransactionLimit() != null) {
            va.setPerTransactionLimit(program.getDefaultPerTransactionLimit());
        }
        if (program.getDefaultDailyLimit() != null) {
            va.setDailyLimit(program.getDefaultDailyLimit());
        }
        if (program.getDefaultWeeklyLimit() != null) {
            va.setWeeklyLimit(program.getDefaultWeeklyLimit());
        }
        if (program.getDefaultMonthlyLimit() != null) {
            va.setMonthlyLimit(program.getDefaultMonthlyLimit());
        }
        if (program.getDefaultMaxBalance() != null) {
            va.setMaxBalance(program.getDefaultMaxBalance());
        }
        if (program.getDefaultWalletType() != null) {
            va.setWalletType(program.getDefaultWalletType());
        }
    }

    // ========================================================================
    // OWNERSHIP INHERITANCE METHODS (v4.3)
    // ========================================================================

    /**
     * Inherit ownership (owningEntityId, owningEntityCode) from parent VA.
     * 
     * OWNERSHIP CHAIN:
     * Shadow (PHYSICAL_MIRROR) → Currency Mirror → Transactional VA
     * 
     * Each level inherits ownership from its parent.
     * 
     * @param va The VA being created
     * @param leafNode The hierarchy node for this VA
     */
    private void inheritOwnershipFromParent(VirtualAccount va, HierarchyNode leafNode) {
        // First, try to get parent VA directly from the VA's parentAccountId
        if (va.getParentAccountId() != null) {
            virtualAccountRepository.findById(va.getParentAccountId()).ifPresent(parentVa -> {
                if (parentVa.getOwningEntityId() != null && va.getOwningEntityId() == null) {
                    va.setOwningEntityId(parentVa.getOwningEntityId());
                    va.setOwningEntityCode(parentVa.getOwningEntityCode());
                    log.debug("Inherited ownership from parent VA {}: entityId={}, entityCode={}", 
                        parentVa.getVaNumber(), parentVa.getOwningEntityId(), parentVa.getOwningEntityCode());
                }
                // Points, miles and tokens are a property of the sub-tree you
                // hang under, not of the program: this used to be forced to
                // POINTS for every account of a LOYALTY-typed program, which
                // meant a points balance needed a program of its own. Inheriting
                // it puts value type on the same footing as currency and
                // ownership, and the create request still overrides it.
                if (parentVa.getValueType() != null && va.getValueType() == null) {
                    va.setValueType(parentVa.getValueType());
                    log.debug("Inherited value type {} from parent VA {}",
                        parentVa.getValueType(), parentVa.getVaNumber());
                }
            });
        }
        
        // If still no ownership, try via hierarchy node's parent
        if (va.getOwningEntityId() == null && leafNode != null && leafNode.getParentId() != null) {
            findOwnershipFromHierarchy(va, leafNode.getParentId());
        }
        
        // Warn if ownership chain is broken
        if (va.getOwningEntityId() == null) {
            log.warn("VA {} could not inherit ownership - no parent with owningEntityId found", va.getVaNumber());
        }
    }

    /**
     * Recursively search up the hierarchy to find a VA with ownership.
     */
    private void findOwnershipFromHierarchy(VirtualAccount va, UUID parentNodeId) {
        if (parentNodeId == null || va.getOwningEntityId() != null) {
            return;
        }

        hierarchyNodeRepository.findById(parentNodeId).ifPresent(parentNode -> {
            // Find VA linked to parent node
            if (parentNode.getVirtualAccountId() != null) {
                virtualAccountRepository.findById(parentNode.getVirtualAccountId()).ifPresent(parentVa -> {
                    if (parentVa.getOwningEntityId() != null) {
                        va.setOwningEntityId(parentVa.getOwningEntityId());
                        va.setOwningEntityCode(parentVa.getOwningEntityCode());
                        log.debug("Inherited ownership from hierarchy parent VA {}: entityId={}, entityCode={}", 
                            parentVa.getVaNumber(), parentVa.getOwningEntityId(), parentVa.getOwningEntityCode());
                    }
                });
            }
            
            // If still no ownership, continue up the hierarchy
            if (va.getOwningEntityId() == null && parentNode.getParentId() != null) {
                findOwnershipFromHierarchy(va, parentNode.getParentId());
            }
        });
    }

    /**
     * Validate ownership chain for a VA.
     * Can be called after VA creation to verify integrity.
     * 
     * @param va The VA to validate
     * @return List of validation issues (empty if valid)
     */
    public List<String> validateOwnershipChain(VirtualAccount va) {
        List<String> issues = new ArrayList<>();
        
        if (va.getCorporateId() == null) {
            issues.add("Missing corporateId");
        }
        
        // System VAs (ROOT, AGGREGATION) don't need entity ownership
        VirtualAccount.AccountCategory category = va.getAccountCategory();
        if (category != null && 
            category != VirtualAccount.AccountCategory.ROOT &&
            category != VirtualAccount.AccountCategory.AGGREGATION) {
            
            if (va.getOwningEntityId() == null) {
                issues.add("Missing owningEntityId");
            }
            
            // Check parent has ownership
            if (va.getParentAccountId() != null) {
                virtualAccountRepository.findById(va.getParentAccountId()).ifPresent(parent -> {
                    if (parent.getOwningEntityId() != null && va.getOwningEntityId() != null) {
                        if (!parent.getOwningEntityId().equals(va.getOwningEntityId())) {
                            issues.add(String.format("Ownership mismatch: VA owned by %s, parent owned by %s",
                                va.getOwningEntityCode(), parent.getOwningEntityCode()));
                        }
                    }
                });
            }
        }
        
        return issues;
    }

    /**
     * Validate entire ownership chain from a VA up to ROOT.
     * 
     * @param vaId The VA ID to start from
     * @return List of all issues found in the chain
     */
    public List<String> validateFullOwnershipChain(UUID vaId) {
        List<String> issues = new ArrayList<>();
        VirtualAccount current = virtualAccountRepository.findById(vaId).orElse(null);
        
        if (current == null) {
            issues.add("VA not found: " + vaId);
            return issues;
        }
        
        Set<UUID> visited = new HashSet<>();
        
        while (current != null && !visited.contains(current.getId())) {
            visited.add(current.getId());
            
            if (current.getCorporateId() == null) {
                issues.add(String.format("%s (%s) has no corporateId", 
                    current.getVaNumber(), current.getAccountCategory()));
            }
            
            VirtualAccount.AccountCategory category = current.getAccountCategory();
            if (current.getOwningEntityId() == null && 
                category != null &&
                category != VirtualAccount.AccountCategory.ROOT &&
                category != VirtualAccount.AccountCategory.AGGREGATION) {
                issues.add(String.format("%s (%s) has no owningEntityId", 
                    current.getVaNumber(), category));
            }
            
            // Move to parent
            if (current.getParentAccountId() != null) {
                current = virtualAccountRepository.findById(current.getParentAccountId()).orElse(null);
            } else {
                current = null;
            }
        }
        
        return issues;
    }

    /**
     * Update ownership of a VA and optionally cascade to children.
     * 
     * @param vaId The VA to update
     * @param owningEntityId The new owning entity ID
     * @param owningEntityCode The new owning entity code
     * @param cascade If true, update all child VAs as well
     */
    @Transactional
    public void updateOwnership(UUID vaId, UUID owningEntityId, String owningEntityCode, boolean cascade) {
        VirtualAccount va = getById(vaId);
        
        va.setOwningEntityId(owningEntityId);
        va.setOwningEntityCode(owningEntityCode);
        virtualAccountRepository.save(va);
        
        log.info("Updated ownership of VA {} to entity {}", va.getVaNumber(), owningEntityCode);
        
        if (cascade) {
            // Update all direct children
            List<VirtualAccount> children = virtualAccountRepository.findByParentAccountId(vaId);
            for (VirtualAccount child : children) {
                if (child.getOwningEntityId() == null || 
                    child.getOwningEntityId().equals(va.getOwningEntityId())) {
                    // Only update if child has no ownership or same ownership
                    updateOwnership(child.getId(), owningEntityId, owningEntityCode, true);
                }
            }
        }
    }
    // ========================================================================
    // CONVENIENCE METHODS FOR PROGRAM-SPECIFIC VA CREATION
    // ========================================================================

    /**
     * Create VA for a customer in Collection Program.
     * Derives hierarchy from customer attributes.
     */
    @Transactional
    public VirtualAccount createCollectionVa(UUID programId, VirtualAccountDto.CollectionVaRequest request) {
        Map<String, String> dimensions = new LinkedHashMap<>();
        dimensions.put("L1", request.getCurrency());
        dimensions.put("L2", request.getChannel());      // INVOICE, ECOMMERCE, POS
        dimensions.put("L3", request.getPlatform());     // AMAZON, NOON, etc.
        dimensions.put("L4", request.getSegment());      // SME, ENTERPRISE, etc.
        dimensions.put("L5", request.getCustomerId());   // Customer identifier
        dimensions.put("L6", request.getAccountType());  // RECEIVABLES, REFUNDS, etc.

        return createWithHierarchy(VirtualAccountDto.CreateWithHierarchyRequest.builder()
            .programId(programId)
            .currency(request.getCurrency())
            .hierarchyDimensions(dimensions)
            .vaName(request.getCustomerName() + " - " + request.getAccountType())
            .externalReference(request.getCustomerId())
            .build());
    }

    /**
     * Create VA for IHB (In-House Bank) Program.
     * Derives hierarchy from entity and account type.
     */
    @Transactional
    public VirtualAccount createIhbVa(UUID programId, VirtualAccountDto.IhbVaRequest request) {
        Map<String, String> dimensions = new LinkedHashMap<>();
        dimensions.put("L1", request.getCurrency());
        dimensions.put("L2", request.getRegion());       // NORTH, SOUTH, etc.
        dimensions.put("L3", request.getState());        // State/Emirate
        dimensions.put("L4", request.getCity());         // City
        dimensions.put("L5", request.getEntityCode());   // Legal entity
        dimensions.put("L6", request.getAccountType());  // PAYABLES, RECEIVABLES, etc.

        return createWithHierarchy(VirtualAccountDto.CreateWithHierarchyRequest.builder()
            .programId(programId)
            .currency(request.getCurrency())
            .hierarchyDimensions(dimensions)
            .vaName(request.getEntityName() + " - " + request.getAccountType())
            .externalReference(request.getEntityCode())
            .build());
    }

    // IHB Current Account creation (createIhbCurrentAccount and its ~11
    // support methods, ~1265 lines) moved to IhbUnifiedService 2026-09-15 —
    // this domain logic belongs there, not in a generic VA service. See the
    // VAM Context Ledger artifact.


    /**
     * Create VA for Wallet Program.
     * Derives hierarchy from customer KYC and type.
     */
    @Transactional
    public VirtualAccount createWalletVa(UUID programId, VirtualAccountDto.WalletVaRequest request) {
        Map<String, String> dimensions = new LinkedHashMap<>();
        dimensions.put("L1", request.getCurrency());
        dimensions.put("L2", request.getUserType());     // CONSUMER, MERCHANT, AGENT
        dimensions.put("L3", request.getRegion());       // Region
        dimensions.put("L4", request.getKycTier());      // TIER_1, TIER_2, TIER_3
        if (request.getGroupCode() != null) {
            dimensions.put("L5", request.getGroupCode()); // Optional grouping
        }
        dimensions.put("L6", request.getWalletType());   // MAIN, SAVINGS, etc.

        VirtualAccount va = createWithHierarchy(VirtualAccountDto.CreateWithHierarchyRequest.builder()
            .programId(programId)
            .currency(request.getCurrency())
            .hierarchyDimensions(dimensions)
            .vaName(request.getCustomerName() + " Wallet")
            .externalReference(request.getCustomerId())
            .walletType(request.getWalletType())
            .kycLevel(request.getKycTier() != null ? parseKycTier(request.getKycTier()) : 0)
            .build());

        // Set holder party if provided
        if (request.getHolderPartyId() != null) {
            va.setHolderPartyId(request.getHolderPartyId());
            virtualAccountRepository.save(va);
        }

        return va;
    }

    /**
     * Create VA for Escrow Program.
     */
    @Transactional
    public VirtualAccount createEscrowVa(UUID programId, VirtualAccountDto.EscrowVaRequest request) {
        Map<String, String> dimensions = new LinkedHashMap<>();
        dimensions.put("L1", request.getCurrency());
        dimensions.put("L2", request.getEscrowType());    // REAL_ESTATE, TRADE, etc.
        dimensions.put("L3", request.getTransactionId()); // Transaction reference
        dimensions.put("L4", request.getPartyRole());     // BUYER, SELLER, AGENT

        return createWithHierarchy(VirtualAccountDto.CreateWithHierarchyRequest.builder()
            .programId(programId)
            .currency(request.getCurrency())
            .hierarchyDimensions(dimensions)
            .vaName(request.getPartyName() + " - " + request.getPartyRole() + " Escrow")
            .externalReference(request.getTransactionId())
            .build());
    }

    /**
     * Create VA for VIBAN Program.
     */
    @Transactional
    public VirtualAccount createVibanVa(UUID programId, VirtualAccountDto.VibanVaRequest request) {
        Map<String, String> dimensions = new LinkedHashMap<>();
        dimensions.put("L1", request.getCurrency());
        dimensions.put("L2", request.getPoolId());        // VIBAN Pool
        dimensions.put("L3", request.getCustomerType());  // CORPORATE, SME, RETAIL
        dimensions.put("L4", request.getCustomerId());    // Customer

        VirtualAccount va = createWithHierarchy(VirtualAccountDto.CreateWithHierarchyRequest.builder()
            .programId(programId)
            .currency(request.getCurrency())
            .hierarchyDimensions(dimensions)
            .vaName(request.getCustomerName() + " VIBAN Account")
            .externalReference(request.getCustomerId())
            .build());

        // Assign VIBAN if provided
        if (request.getViban() != null) {
            va.setViban(request.getViban());
            virtualAccountRepository.save(va);
        }

        return va;
    }

    /**
     * Create VA for Payables Program.
     */
    @Transactional
    public VirtualAccount createPayablesVa(UUID programId, VirtualAccountDto.PayablesVaRequest request) {
        Map<String, String> dimensions = new LinkedHashMap<>();
        dimensions.put("L1", request.getCurrency());
        dimensions.put("L2", request.getPaymentType());   // SUPPLIER, PAYROLL, TAX
        dimensions.put("L3", request.getEntityCode());    // Legal entity
        dimensions.put("L4", request.getCostCenter());    // Cost center
        dimensions.put("L5", request.getBeneficiaryType());// DOMESTIC, INTERNATIONAL

        return createWithHierarchy(VirtualAccountDto.CreateWithHierarchyRequest.builder()
            .programId(programId)
            .currency(request.getCurrency())
            .hierarchyDimensions(dimensions)
            .vaName(request.getEntityName() + " - " + request.getPaymentType())
            .externalReference(request.getEntityCode())
            .build());
    }

    private int parseKycTier(String tier) {
        if (tier == null) return 0;
        return switch (tier.toUpperCase()) {
            case "TIER_1", "TIER1", "1" -> 1;
            case "TIER_2", "TIER2", "2" -> 2;
            case "TIER_3", "TIER3", "3" -> 3;
            default -> 0;
        };
    }

    // ========================================================================
    // EXISTING METHODS (unchanged for backward compatibility)
    // ========================================================================

    private void validateReferences(VirtualAccountDto.CreateRequest request) {
        // Skip validation if using parent-based creation (IDs will come from program)
        if (request.hasParentReference()) {
            log.debug("Skipping reference validation for parent-based creation");
            return;
        }
        
        // Validate corporate exists
        if (request.getCorporateId() != null && !corporateRepository.existsById(request.getCorporateId())) {
            throw new ResourceNotFoundException("Corporate not found: " + request.getCorporateId());
        }

        // Validate physical account exists
        if (request.getPhysicalAccountId() != null && !physicalAccountRepository.existsById(request.getPhysicalAccountId())) {
            throw new ResourceNotFoundException("Physical account not found: " + request.getPhysicalAccountId());
        }

        // One physical account backs at most one program's real balance. Same-program
        // reuse (ordinary pooling sub-ledgers of one account) is fine and untouched —
        // ShadowAccountService.createShadowAccount() already guards against a second
        // PHYSICAL_MIRROR of the same account. What that guard can't see is a plain
        // leaf VA in a DIFFERENT program independently pointing at an account another
        // program already mirrors: BalanceStructureService's corporate-wide rollup sums
        // every leaf VA it finds with no dedup by physicalAccountId, so that leaf's
        // balance would double-count real cash the mirror already reports in full.
        if (request.getPhysicalAccountId() != null) {
            virtualAccountRepository.findByLinkedPhysicalAccountId(request.getPhysicalAccountId())
                .filter(mirror -> !Objects.equals(mirror.getProgramId(), request.getProgramId()))
                .ifPresent(mirror -> {
                    throw new BusinessException(
                        "Physical account " + request.getPhysicalAccountId() + " is already mirrored by "
                        + mirror.getVaNumber() + " under a different program. Represent cross-program "
                        + "access to that cash as an inter-program claim, not a second direct reference.");
                });
        }

        // Validate hierarchy node if provided
        if (request.getHierarchyNodeId() != null) {
            if (!hierarchyNodeRepository.existsById(request.getHierarchyNodeId())) {
                throw new ResourceNotFoundException("Hierarchy node not found: " + request.getHierarchyNodeId());
            }
        }

        // Validate VIBAN uniqueness if provided
        if (request.getViban() != null && !request.getViban().isEmpty()) {
            if (virtualAccountRepository.existsByViban(request.getViban())) {
                throw new BusinessException("VIBAN already exists: " + request.getViban());
            }
        }
    }

    private VirtualAccount buildVirtualAccount(
            VirtualAccountDto.CreateRequest request,
            Program program,
            String vaNumber) {

        VirtualAccount.VirtualAccountBuilder builder = VirtualAccount.builder()
                .vaNumber(vaNumber)
                .viban(request.getViban())
                .vaName(request.getVaName())
                .programId(request.getProgramId())
                .corporateId(request.getCorporateId())
                .physicalAccountId(request.getPhysicalAccountId())
                .currencyCode(request.getCurrencyCode())
                .currentBalance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .heldBalance(BigDecimal.ZERO)
                .status(VirtualAccount.VaStatus.ACTIVE)
                .externalReference(request.getExternalReference())
                .metadata(request.getMetadata())
                .kycVerified(false)
                .kycLevel(0)
                .transactionCount(0)
                .topupCount(0)
                .withdrawalCount(0)
                .dailyUsed(BigDecimal.ZERO)
                .weeklyUsed(BigDecimal.ZERO)
                .monthlyUsed(BigDecimal.ZERO)
                .annualUsed(BigDecimal.ZERO)
                .dailyTopupUsed(BigDecimal.ZERO)
                .monthlyTopupUsed(BigDecimal.ZERO)
                .lastLimitResetDate(LocalDate.now())
                .lastActivityDate(LocalDate.now());

        // Apply program defaults if inheritance enabled
        if (program != null && Boolean.TRUE.equals(request.getInheritProgramDefaults())) {
            applyProgramDefaults(builder, program);
        }

        // Override with request-specific values (always takes precedence)
        applyRequestOverrides(builder, request);

        return builder.build();
    }

    private void applyProgramDefaults(VirtualAccount.VirtualAccountBuilder builder, Program program) {
        log.debug("Applying program defaults from: {}", program.getProgramCode());

        // Spending Limits
        if (program.getDefaultPerTransactionLimit() != null) {
            builder.perTransactionLimit(program.getDefaultPerTransactionLimit());
        }
        if (program.getDefaultDailyLimit() != null) {
            builder.dailyLimit(program.getDefaultDailyLimit());
        }
        if (program.getDefaultWeeklyLimit() != null) {
            builder.weeklyLimit(program.getDefaultWeeklyLimit());
        }
        if (program.getDefaultMonthlyLimit() != null) {
            builder.monthlyLimit(program.getDefaultMonthlyLimit());
        }
        if (program.getDefaultYearlyLimit() != null) {
            builder.annualLimit(program.getDefaultYearlyLimit());
        }
        if (program.getDefaultMaxBalance() != null) {
            builder.maxBalance(program.getDefaultMaxBalance());
        }

        // Topup Limits
        if (program.getDefaultDailyTopupLimit() != null) {
            builder.dailyTopupLimit(program.getDefaultDailyTopupLimit());
        }
        if (program.getDefaultMonthlyTopupLimit() != null) {
            builder.monthlyTopupLimit(program.getDefaultMonthlyTopupLimit());
        }

        // Wallet Configuration
        if (program.getDefaultWalletType() != null) {
            builder.walletType(program.getDefaultWalletType());
        }

        // KYC Requirements
        if (Boolean.TRUE.equals(program.getKycRequired())) {
            builder.kycLevel(0);
            builder.kycVerified(false);
        }
        if (program.getMinKycLevel() != null) {
            builder.kycLevel(program.getMinKycLevel());
        }

        // Expiry Configuration
        if (program.getWalletExpiryDays() != null) {
            builder.expiresAt(LocalDate.now().plusDays(program.getWalletExpiryDays()));
        }

    }

    /**
     * Setter twin of {@link #applyRequestOverrides}, for flows that have already
     * built the account before the request's optional fields are applied.
     *
     * createWithParentNode previously dropped every field this carries —
     * holderPartyId, walletType, the limits, the stored-value attributes — so a
     * caller supplying them got an account without them and no error. It runs
     * after applyProgramDefaultsToVa so the request wins over program defaults.
     *
     * Callers must re-assert anything they derive themselves (createWithParentNode
     * re-sets hierarchyPath), since the request may carry its own value.
     */
    private void applyRequestOverridesToVa(VirtualAccount va,
                                        VirtualAccountDto.CreateRequest request) {
        
        // Hierarchy
        if (request.getHierarchyNodeId() != null) {
            va.setHierarchyNodeId(request.getHierarchyNodeId());
            hierarchyNodeRepository.findById(request.getHierarchyNodeId())
                    .ifPresent(node -> va.setHierarchyPath(node.getMaterializedPath()));
        }
        if (request.getCollectionChannel() != null) {
            try {
                va.setCollectionChannel(
                        VirtualAccount.CollectionChannel.valueOf(request.getCollectionChannel().toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid collection channel: {}", request.getCollectionChannel());
            }
        }

        // Wallet Configuration
        if (request.getWalletType() != null) {
            va.setWalletType(request.getWalletType());
        }
        if (request.getKycLevel() != null) {
            va.setKycLevel(request.getKycLevel());
            va.setKycVerified(request.getKycLevel() > 0);
        }
        if (request.getExpiresAt() != null) {
            va.setExpiresAt(request.getExpiresAt());
        }
        if (request.getHolderPartyId() != null) {
            va.setHolderPartyId(request.getHolderPartyId());
        }

        // Spending Limits (override program defaults)
        if (request.getPerTransactionLimit() != null) {
            va.setPerTransactionLimit(request.getPerTransactionLimit());
        }
        if (request.getDailyLimit() != null) {
            va.setDailyLimit(request.getDailyLimit());
        }
        if (request.getWeeklyLimit() != null) {
            va.setWeeklyLimit(request.getWeeklyLimit());
        }
        if (request.getMonthlyLimit() != null) {
            va.setMonthlyLimit(request.getMonthlyLimit());
        }
        if (request.getAnnualLimit() != null) {
            va.setAnnualLimit(request.getAnnualLimit());
        }
        if (request.getMaxBalance() != null) {
            va.setMaxBalance(request.getMaxBalance());
        }

        // Topup Limits
        if (request.getDailyTopupLimit() != null) {
            va.setDailyTopupLimit(request.getDailyTopupLimit());
        }
        if (request.getMonthlyTopupLimit() != null) {
            va.setMonthlyTopupLimit(request.getMonthlyTopupLimit());
        }

        // Value Type
        if (request.getValueType() != null) {
            try {
                va.setValueType(VirtualAccount.ValueType.valueOf(request.getValueType().toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid value type: {}", request.getValueType());
            }
        }
        if (request.getPointsToCurrencyRate() != null) {
            va.setPointsToCurrencyRate(request.getPointsToCurrencyRate());
        }
        if (request.getLoyaltyTier() != null) {
            try {
                va.setLoyaltyTier(VirtualAccount.LoyaltyTier.valueOf(request.getLoyaltyTier().toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid loyalty tier: {}", request.getLoyaltyTier());
            }
        }
        if (request.getLoyaltyProgramId() != null) {
            va.setLoyaltyProgramId(request.getLoyaltyProgramId());
        }

        // Card Program
        if (request.getCardProgramType() != null) {
            try {
                va.setCardProgramType(
                        VirtualAccount.CardProgramType.valueOf(request.getCardProgramType().toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid card program type: {}", request.getCardProgramType());
            }
        }
        if (request.getLinkedCardId() != null) {
            va.setLinkedCardId(request.getLinkedCardId());
        }
        if (request.getBudgetOwnerId() != null) {
            va.setBudgetOwnerId(request.getBudgetOwnerId());
        }
        if (request.getCostCenter() != null) {
            va.setCostCenter(request.getCostCenter());
        }
        if (request.getDepartment() != null) {
            va.setDepartment(request.getDepartment());
        }

        // MCC Restrictions
        if (request.getMccWhitelist() != null) {
            va.setMccWhitelist(request.getMccWhitelist());
        }
        if (request.getMccBlacklist() != null) {
            va.setMccBlacklist(request.getMccBlacklist());
        }
        if (request.getMerchantWhitelist() != null) {
            va.setMerchantWhitelist(request.getMerchantWhitelist());
        }
        if (request.getCountryWhitelist() != null) {
            va.setCountryWhitelist(request.getCountryWhitelist());
        }

        // Expiry Configuration
        if (request.getBalanceExpiryDate() != null) {
            va.setBalanceExpiryDate(request.getBalanceExpiryDate());
        }
        if (request.getExpiryAction() != null) {
            try {
                va.setExpiryAction(
                        VirtualAccount.ExpiryAction.valueOf(request.getExpiryAction().toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid expiry action: {}", request.getExpiryAction());
            }
        }
    }

    private void applyRequestOverrides(VirtualAccount.VirtualAccountBuilder builder,
                                        VirtualAccountDto.CreateRequest request) {
        
        // Hierarchy
        if (request.getHierarchyNodeId() != null) {
            builder.hierarchyNodeId(request.getHierarchyNodeId());
            hierarchyNodeRepository.findById(request.getHierarchyNodeId())
                    .ifPresent(node -> builder.hierarchyPath(node.getMaterializedPath()));
        }
        if (request.getCollectionChannel() != null) {
            try {
                builder.collectionChannel(
                        VirtualAccount.CollectionChannel.valueOf(request.getCollectionChannel().toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid collection channel: {}", request.getCollectionChannel());
            }
        }

        // Wallet Configuration
        if (request.getWalletType() != null) {
            builder.walletType(request.getWalletType());
        }
        if (request.getKycLevel() != null) {
            builder.kycLevel(request.getKycLevel());
            builder.kycVerified(request.getKycLevel() > 0);
        }
        if (request.getExpiresAt() != null) {
            builder.expiresAt(request.getExpiresAt());
        }
        if (request.getHolderPartyId() != null) {
            builder.holderPartyId(request.getHolderPartyId());
        }

        // Spending Limits (override program defaults)
        if (request.getPerTransactionLimit() != null) {
            builder.perTransactionLimit(request.getPerTransactionLimit());
        }
        if (request.getDailyLimit() != null) {
            builder.dailyLimit(request.getDailyLimit());
        }
        if (request.getWeeklyLimit() != null) {
            builder.weeklyLimit(request.getWeeklyLimit());
        }
        if (request.getMonthlyLimit() != null) {
            builder.monthlyLimit(request.getMonthlyLimit());
        }
        if (request.getAnnualLimit() != null) {
            builder.annualLimit(request.getAnnualLimit());
        }
        if (request.getMaxBalance() != null) {
            builder.maxBalance(request.getMaxBalance());
        }

        // Topup Limits
        if (request.getDailyTopupLimit() != null) {
            builder.dailyTopupLimit(request.getDailyTopupLimit());
        }
        if (request.getMonthlyTopupLimit() != null) {
            builder.monthlyTopupLimit(request.getMonthlyTopupLimit());
        }

        // Value Type
        if (request.getValueType() != null) {
            try {
                builder.valueType(VirtualAccount.ValueType.valueOf(request.getValueType().toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid value type: {}", request.getValueType());
            }
        }
        if (request.getPointsToCurrencyRate() != null) {
            builder.pointsToCurrencyRate(request.getPointsToCurrencyRate());
        }
        if (request.getLoyaltyTier() != null) {
            try {
                builder.loyaltyTier(VirtualAccount.LoyaltyTier.valueOf(request.getLoyaltyTier().toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid loyalty tier: {}", request.getLoyaltyTier());
            }
        }
        if (request.getLoyaltyProgramId() != null) {
            builder.loyaltyProgramId(request.getLoyaltyProgramId());
        }

        // Card Program
        if (request.getCardProgramType() != null) {
            try {
                builder.cardProgramType(
                        VirtualAccount.CardProgramType.valueOf(request.getCardProgramType().toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid card program type: {}", request.getCardProgramType());
            }
        }
        if (request.getLinkedCardId() != null) {
            builder.linkedCardId(request.getLinkedCardId());
        }
        if (request.getBudgetOwnerId() != null) {
            builder.budgetOwnerId(request.getBudgetOwnerId());
        }
        if (request.getCostCenter() != null) {
            builder.costCenter(request.getCostCenter());
        }
        if (request.getDepartment() != null) {
            builder.department(request.getDepartment());
        }

        // MCC Restrictions
        if (request.getMccWhitelist() != null) {
            builder.mccWhitelist(request.getMccWhitelist());
        }
        if (request.getMccBlacklist() != null) {
            builder.mccBlacklist(request.getMccBlacklist());
        }
        if (request.getMerchantWhitelist() != null) {
            builder.merchantWhitelist(request.getMerchantWhitelist());
        }
        if (request.getCountryWhitelist() != null) {
            builder.countryWhitelist(request.getCountryWhitelist());
        }

        // Expiry Configuration
        if (request.getBalanceExpiryDate() != null) {
            builder.balanceExpiryDate(request.getBalanceExpiryDate());
        }
        if (request.getExpiryAction() != null) {
            try {
                builder.expiryAction(
                        VirtualAccount.ExpiryAction.valueOf(request.getExpiryAction().toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid expiry action: {}", request.getExpiryAction());
            }
        }
    }

    private String generateVaNumber(String prefix, Program program) {
        String effectivePrefix = prefix;
        if (effectivePrefix == null && program != null && program.getVaPrefix() != null) {
            effectivePrefix = program.getVaPrefix();
        }
        if (effectivePrefix == null) {
            effectivePrefix = "VA";
        }

        long count = virtualAccountRepository.count();
        String timestamp = String.valueOf(System.currentTimeMillis() % 10000);
        String sequence = String.format("%06d", count + 1);
        
        return effectivePrefix + timestamp + sequence;
    }

    // ========================================================================
    // UPDATE OPERATIONS (existing - unchanged)
    // ========================================================================

    @Transactional
    public VirtualAccount update(UUID id, VirtualAccountDto.UpdateRequest request) {
        log.info("Updating virtual account: {}", id);
        VirtualAccount va = getById(id);

        if (request.getVaName() != null) {
            va.setVaName(request.getVaName());
            // Keep the linked hierarchy_nodes row's name in sync — the two
            // are dual-written at creation (HierarchyService.createAggregation())
            // but nothing propagated a rename until now, letting an
            // AGGREGATION VA's name drift from its node's name (the node is
            // what the "pick a parent" tree picker UI displays).
            if (va.getHierarchyNodeId() != null) {
                hierarchyNodeRepository.findById(va.getHierarchyNodeId()).ifPresent(node -> {
                    node.setNodeName(request.getVaName());
                    hierarchyNodeRepository.save(node);
                });
            }
        }
        if (request.getExternalReference() != null) {
            va.setExternalReference(request.getExternalReference());
        }
        if (request.getMetadata() != null) {
            va.setMetadata(request.getMetadata());
        }
        if (request.getHierarchyNodeId() != null) {
            va.setHierarchyNodeId(request.getHierarchyNodeId());
            hierarchyNodeRepository.findById(request.getHierarchyNodeId())
                    .ifPresent(node -> va.setHierarchyPath(node.getMaterializedPath()));
        }
        if (request.getCollectionChannel() != null) {
            try {
                va.setCollectionChannel(
                        VirtualAccount.CollectionChannel.valueOf(request.getCollectionChannel().toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid collection channel: {}", request.getCollectionChannel());
            }
        }
        if (request.getWalletType() != null) {
            va.setWalletType(request.getWalletType());
        }
        if (request.getExpiresAt() != null) {
            va.setExpiresAt(request.getExpiresAt());
        }
        if (request.getHolderPartyId() != null) {
            va.setHolderPartyId(request.getHolderPartyId());
        }
        if (request.getCardProgramType() != null) {
            try {
                va.setCardProgramType(
                        VirtualAccount.CardProgramType.valueOf(request.getCardProgramType().toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid card program type: {}", request.getCardProgramType());
            }
        }
        if (request.getLinkedCardId() != null) {
            va.setLinkedCardId(request.getLinkedCardId());
        }
        if (request.getBudgetOwnerId() != null) {
            va.setBudgetOwnerId(request.getBudgetOwnerId());
        }
        if (request.getCostCenter() != null) {
            va.setCostCenter(request.getCostCenter());
        }
        if (request.getDepartment() != null) {
            va.setDepartment(request.getDepartment());
        }
        if (request.getLoyaltyTier() != null) {
            try {
                va.setLoyaltyTier(VirtualAccount.LoyaltyTier.valueOf(request.getLoyaltyTier().toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid loyalty tier: {}", request.getLoyaltyTier());
            }
        }
        if (request.getLoyaltyProgramId() != null) {
            va.setLoyaltyProgramId(request.getLoyaltyProgramId());
        }
        if (request.getBalanceExpiryDate() != null) {
            va.setBalanceExpiryDate(request.getBalanceExpiryDate());
        }
        if (request.getExpiryAction() != null) {
            try {
                va.setExpiryAction(VirtualAccount.ExpiryAction.valueOf(request.getExpiryAction().toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid expiry action: {}", request.getExpiryAction());
            }
        }

        // ENHANCED: Handle owning entity assignment
        if (request.getOwningEntityId() != null) {
            va.setOwningEntityId(request.getOwningEntityId());
            log.info("Updated owningEntityId of VA {} to {}", va.getVaNumber(), request.getOwningEntityId());
        }
        if (request.getOwningEntityCode() != null) {
            va.setOwningEntityCode(request.getOwningEntityCode());
            log.info("Updated owningEntityCode of VA {} to {}", va.getVaNumber(), request.getOwningEntityCode());
        }

        return virtualAccountRepository.save(va);
    }

    // ========================================================================
    // LIMITS MANAGEMENT (existing - unchanged)
    // ========================================================================

    @Transactional
    public VirtualAccount updateLimits(UUID id, VirtualAccountDto.LimitsUpdateRequest request) {
        log.info("Updating limits for VA: {}", id);

        VirtualAccount va = getById(id);

        if (request.getPerTransactionLimit() != null) {
            va.setPerTransactionLimit(request.getPerTransactionLimit());
        }
        if (request.getDailyLimit() != null) {
            va.setDailyLimit(request.getDailyLimit());
        }
        if (request.getWeeklyLimit() != null) {
            va.setWeeklyLimit(request.getWeeklyLimit());
        }
        if (request.getMonthlyLimit() != null) {
            va.setMonthlyLimit(request.getMonthlyLimit());
        }
        if (request.getAnnualLimit() != null) {
            va.setAnnualLimit(request.getAnnualLimit());
        }
        if (request.getMaxBalance() != null) {
            va.setMaxBalance(request.getMaxBalance());
        }
        if (request.getDailyTopupLimit() != null) {
            va.setDailyTopupLimit(request.getDailyTopupLimit());
        }
        if (request.getMonthlyTopupLimit() != null) {
            va.setMonthlyTopupLimit(request.getMonthlyTopupLimit());
        }
        if (Boolean.TRUE.equals(request.getResetUsage())) {
            va.setDailyUsed(BigDecimal.ZERO);
            va.setWeeklyUsed(BigDecimal.ZERO);
            va.setMonthlyUsed(BigDecimal.ZERO);
            va.setAnnualUsed(BigDecimal.ZERO);
            va.setDailyTopupUsed(BigDecimal.ZERO);
            va.setMonthlyTopupUsed(BigDecimal.ZERO);
            va.setLastLimitResetDate(LocalDate.now());
        }

        log.info("Limits updated for VA: {}", va.getVaNumber());
        return virtualAccountRepository.save(va);
    }

    @Transactional
    public VirtualAccount resetDailyLimits(UUID id) {
        VirtualAccount va = getById(id);
        va.resetDailyLimits();
        return virtualAccountRepository.save(va);
    }

    @Transactional
    public VirtualAccount resetWeeklyLimits(UUID id) {
        VirtualAccount va = getById(id);
        va.resetWeeklyLimits();
        return virtualAccountRepository.save(va);
    }

    @Transactional
    public VirtualAccount resetMonthlyLimits(UUID id) {
        VirtualAccount va = getById(id);
        va.resetMonthlyLimits();
        return virtualAccountRepository.save(va);
    }

    @Transactional
    public VirtualAccount resetAnnualLimits(UUID id) {
        VirtualAccount va = getById(id);
        va.resetAnnualLimits();
        return virtualAccountRepository.save(va);
    }

    // ------------------------------------------------------------------
    // Periodic limit resets, across every account.
    //
    // The bulk queries these call have existed on the repository all along
    // but had no caller: the only resets were the per-account REST endpoints
    // above, so dailyUsed/weeklyUsed/monthlyUsed/annualUsed accumulated for
    // the life of the account unless an operator reset each one by hand. A
    // "daily" limit that never resets is a lifetime limit. ScheduledJobService
    // now drives these on the period each one names.
    // ------------------------------------------------------------------

    /** Zero the daily spend and topup counters on accounts not yet reset today. */
    @Transactional
    public int resetAllDailyLimits() {
        return virtualAccountRepository.resetDailyLimits(LocalDate.now());
    }

    @Transactional
    public int resetAllWeeklyLimits() {
        return virtualAccountRepository.resetWeeklyLimits();
    }

    @Transactional
    public int resetAllMonthlyLimits() {
        return virtualAccountRepository.resetMonthlyLimits();
    }

    @Transactional
    public int resetAllAnnualLimits() {
        return virtualAccountRepository.resetAnnualLimits();
    }

    // ========================================================================
    // KYC MANAGEMENT (existing - unchanged)
    // ========================================================================

    @Transactional
    public VirtualAccount updateKyc(UUID id, VirtualAccountDto.KycUpdateRequest request) {
        log.info("Updating KYC for VA: {} to level {}", id, request.getKycLevel());

        VirtualAccount va = getById(id);

        if (request.getKycLevel() < 0 || request.getKycLevel() > 3) {
            throw new BusinessException("Invalid KYC level. Must be between 0 and 3.");
        }

        va.setKycVerified(request.getKycLevel() > 0);
        va.setKycLevel(request.getKycLevel());
        va.setKycVerifiedAt(LocalDateTime.now());

        if (request.getKycExpiryDate() != null) {
            va.setKycExpiryDate(request.getKycExpiryDate());
        } else {
            va.setKycExpiryDate(LocalDate.now().plusYears(1));
        }

        applyKycTierLimits(va, request.getKycLevel());

        log.info("KYC updated for VA: {} - Level: {}, Expiry: {}", 
                va.getVaNumber(), va.getKycLevel(), va.getKycExpiryDate());
        return virtualAccountRepository.save(va);
    }

    @Transactional
    public VirtualAccount verifyKyc(UUID id, int level) {
        VirtualAccountDto.KycUpdateRequest request = VirtualAccountDto.KycUpdateRequest.builder()
                .kycLevel(level)
                .build();
        return updateKyc(id, request);
    }

    private void applyKycTierLimits(VirtualAccount va, Integer level) {
        Program program = null;
        if (va.getProgramId() != null) {
            program = programRepository.findById(va.getProgramId()).orElse(null);
        }

        BigDecimal maxBalance;
        BigDecimal dailyLimit;

        switch (level) {
            case 0:
                maxBalance = new BigDecimal("1000");
                dailyLimit = new BigDecimal("500");
                break;
            case 1:
                maxBalance = new BigDecimal("10000");
                dailyLimit = new BigDecimal("5000");
                break;
            case 2:
                maxBalance = new BigDecimal("100000");
                dailyLimit = new BigDecimal("50000");
                break;
            case 3:
                maxBalance = new BigDecimal("1000000");
                dailyLimit = new BigDecimal("500000");
                break;
            default:
                return;
        }

        if (program != null) {
            if (program.getDefaultMaxBalance() != null &&
                    program.getDefaultMaxBalance().compareTo(maxBalance) > 0) {
                maxBalance = program.getDefaultMaxBalance();
            }
            if (program.getDefaultDailyLimit() != null &&
                    program.getDefaultDailyLimit().compareTo(dailyLimit) > 0) {
                dailyLimit = program.getDefaultDailyLimit();
            }
        }

        if (va.getMaxBalance() == null || va.getMaxBalance().compareTo(maxBalance) < 0) {
            va.setMaxBalance(maxBalance);
        }
        if (va.getDailyLimit() == null || va.getDailyLimit().compareTo(dailyLimit) < 0) {
            va.setDailyLimit(dailyLimit);
        }
    }

    // ========================================================================
    // MCC RESTRICTIONS MANAGEMENT (existing - unchanged)
    // ========================================================================

    @Transactional
    public VirtualAccount updateMccRestrictions(UUID id, VirtualAccountDto.MccRestrictionsRequest request) {
        log.info("Updating MCC restrictions for VA: {}", id);

        VirtualAccount va = getById(id);

        try {
            if (Boolean.TRUE.equals(request.getReplaceAll())) {
                va.setMccWhitelist(request.getMccWhitelist() != null ?
                        objectMapper.writeValueAsString(request.getMccWhitelist()) : null);
                va.setMccBlacklist(request.getMccBlacklist() != null ?
                        objectMapper.writeValueAsString(request.getMccBlacklist()) : null);
                va.setMerchantWhitelist(request.getMerchantWhitelist() != null ?
                        objectMapper.writeValueAsString(request.getMerchantWhitelist()) : null);
                va.setCountryWhitelist(request.getCountryWhitelist() != null ?
                        objectMapper.writeValueAsString(request.getCountryWhitelist()) : null);
            } else {
                if (request.getMccWhitelist() != null) {
                    List<String> existing = parseMccList(va.getMccWhitelist());
                    existing.addAll(request.getMccWhitelist());
                    va.setMccWhitelist(objectMapper.writeValueAsString(existing.stream().distinct().collect(Collectors.toList())));
                }
                if (request.getMccBlacklist() != null) {
                    List<String> existing = parseMccList(va.getMccBlacklist());
                    existing.addAll(request.getMccBlacklist());
                    va.setMccBlacklist(objectMapper.writeValueAsString(existing.stream().distinct().collect(Collectors.toList())));
                }
                if (request.getMerchantWhitelist() != null) {
                    List<String> existing = parseMccList(va.getMerchantWhitelist());
                    existing.addAll(request.getMerchantWhitelist());
                    va.setMerchantWhitelist(objectMapper.writeValueAsString(existing.stream().distinct().collect(Collectors.toList())));
                }
                if (request.getCountryWhitelist() != null) {
                    List<String> existing = parseMccList(va.getCountryWhitelist());
                    existing.addAll(request.getCountryWhitelist());
                    va.setCountryWhitelist(objectMapper.writeValueAsString(existing.stream().distinct().collect(Collectors.toList())));
                }
            }
        } catch (JsonProcessingException e) {
            throw new BusinessException("Invalid MCC restriction format: " + e.getMessage());
        }

        log.info("MCC restrictions updated for VA: {}", va.getVaNumber());
        return virtualAccountRepository.save(va);
    }

    private List<String> parseMccList(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }

    // ========================================================================
    // STATUS MANAGEMENT (existing - unchanged)
    // ========================================================================

    @Transactional
    public VirtualAccount updateStatus(UUID id, VirtualAccount.VaStatus status) {
        return updateStatus(id, status, null);
    }

    @Transactional
    public VirtualAccount updateStatus(UUID id, VirtualAccount.VaStatus status, String reason) {
        log.info("Updating status for VA: {} to {}", id, status);

        VirtualAccount va = getById(id);
        VirtualAccount.VaStatus previousStatus = va.getStatus();
        
        va.setStatus(status);

        if (status == VirtualAccount.VaStatus.SUSPENDED) {
            va.setSuspensionReason(reason);
            va.setBlockReason(null);
        } else if (status == VirtualAccount.VaStatus.BLOCKED) {
            va.setBlockReason(reason);
            va.setSuspensionReason(null);
        } else if (status == VirtualAccount.VaStatus.ACTIVE) {
            va.setSuspensionReason(null);
            va.setBlockReason(null);
            if (previousStatus == VirtualAccount.VaStatus.PENDING_ACTIVATION) {
                va.setActivatedAt(LocalDateTime.now());
            }
        }

        log.info("Status updated for VA: {} from {} to {}", va.getVaNumber(), previousStatus, status);
        return virtualAccountRepository.save(va);
    }

    @Transactional
    public VirtualAccount suspend(UUID id, String reason) {
        return updateStatus(id, VirtualAccount.VaStatus.SUSPENDED, reason);
    }

    @Transactional
    public VirtualAccount block(UUID id, String reason) {
        return updateStatus(id, VirtualAccount.VaStatus.BLOCKED, reason);
    }

    @Transactional
    public VirtualAccount reactivate(UUID id) {
        return updateStatus(id, VirtualAccount.VaStatus.ACTIVE, null);
    }

    @Transactional
    public VirtualAccount close(UUID id) {
        VirtualAccount va = getById(id);

        if (va.getCurrentBalance().compareTo(BigDecimal.ZERO) != 0) {
            throw new BusinessException("Cannot close account with non-zero balance: " + va.getCurrentBalance());
        }

        // FIX v5.4.0: Cleanup Currency Mirrors if this was the last VA of its currency
        try {
            hierarchyVaService.cleanupCurrencyMirrorsOnClose(va);
        } catch (Exception e) {
            log.warn("Currency mirror cleanup failed for VA {}: {}", va.getVaNumber(), e.getMessage());
            // Non-fatal - continue with closing the VA
        }

        return updateStatus(id, VirtualAccount.VaStatus.CLOSED, "Account closed");
    }

    // ========================================================================
    // PHASE 1: PUBLISH/UNPUBLISH METHODS (Published VA = Has VIBAN)
    // ========================================================================

    /**
     * Publish a VA - Assign a VIBAN to make it externally visible.
     * Published VAs can receive external payments via camt.054 notifications.
     *
     * @param id VA ID
     * @param viban Optional pre-assigned VIBAN. If null, one will be generated.
     * @param publishedBy User/system publishing the VA
     * @return Updated VA
     */
    @Transactional
    public VirtualAccount publish(UUID id, String viban, String publishedBy) {
        log.info("Publishing VA: {} with VIBAN: {}", id, viban != null ? viban : "AUTO-GENERATE");

        VirtualAccount va = getById(id);

        // Validate can be published
        if (!va.canBePublished()) {
            if (va.isPublished()) {
                throw new BusinessException("VA is already published with VIBAN: " + va.getViban());
            }
            if (va.getStatus() != VirtualAccount.VaStatus.ACTIVE) {
                throw new BusinessException("Cannot publish inactive VA. Current status: " + va.getStatus());
            }
            throw new BusinessException("VA cannot be published. Category: " + va.getAccountCategory());
        }

        // Generate VIBAN if not provided
        String assignedViban = viban;
        if (assignedViban == null || assignedViban.isEmpty()) {
            assignedViban = generateViban(va);
        }

        // Validate VIBAN uniqueness
        if (virtualAccountRepository.findByViban(assignedViban).isPresent()) {
            throw new BusinessException("VIBAN already in use: " + assignedViban);
        }

        // Publish the VA
        va.publish(assignedViban, publishedBy);

        log.info("VA {} published with VIBAN: {}", va.getVaNumber(), assignedViban);
        return virtualAccountRepository.save(va);
    }

    /**
     * Unpublish a VA - Remove external visibility but keep VIBAN for audit.
     * Unpublished VAs will not receive external payments.
     *
     * @param id VA ID
     * @param reason Reason for unpublishing
     * @return Updated VA
     */
    @Transactional
    public VirtualAccount unpublish(UUID id, String reason) {
        log.info("Unpublishing VA: {} - Reason: {}", id, reason);

        VirtualAccount va = getById(id);

        if (va.isUnpublished()) {
            throw new BusinessException("VA is already unpublished");
        }

        va.unpublish(reason);

        log.info("VA {} unpublished. VIBAN {} retained for audit.", va.getVaNumber(), va.getViban());
        return virtualAccountRepository.save(va);
    }

    /**
     * Suspend a published VA's publish status temporarily.
     *
     * @param id VA ID
     * @param reason Reason for suspension
     * @return Updated VA
     */
    @Transactional
    public VirtualAccount suspendPublish(UUID id, String reason) {
        log.info("Suspending publish status for VA: {} - Reason: {}", id, reason);

        VirtualAccount va = getById(id);

        if (!va.isPublished()) {
            throw new BusinessException("Can only suspend published VAs. Current status: " + va.getPublishStatus());
        }

        va.suspendPublish(reason);

        log.info("VA {} publish status suspended", va.getVaNumber());
        return virtualAccountRepository.save(va);
    }

    /**
     * Re-publish a suspended VA.
     *
     * @param id VA ID
     * @param publishedBy User/system re-publishing
     * @return Updated VA
     */
    @Transactional
    public VirtualAccount republish(UUID id, String publishedBy) {
        log.info("Re-publishing VA: {}", id);

        VirtualAccount va = getById(id);

        if (!va.isPublishSuspended()) {
            throw new BusinessException("Can only republish suspended VAs. Current status: " + va.getPublishStatus());
        }

        va.republish(publishedBy);

        log.info("VA {} re-published with existing VIBAN: {}", va.getVaNumber(), va.getViban());
        return virtualAccountRepository.save(va);
    }

    /**
     * Generate a VIBAN for a VA based on program configuration.
     * Format: {COUNTRY}{CHECK}{BANK}{PROGRAM}{SEQUENCE}
     * Example: AE021234COLL00000001
     */
    private String generateViban(VirtualAccount va) {
        // Get program for VIBAN prefix
        String programCode = "XXXX";
        String bankCode = "1234"; // Bank identifier
        String countryCode = marketProfile.getActiveIbanCountryCode();

        if (va.getProgramId() != null) {
            Program program = programRepository.findById(va.getProgramId()).orElse(null);
            if (program != null) {
                programCode = program.getProgramCode();
                if (programCode.length() > 4) {
                    programCode = programCode.substring(0, 4);
                }
            }
        }

        // Get sequence number
        long count = virtualAccountRepository.countByProgramId(va.getProgramId());
        String sequence = String.format("%08d", count + 1);

        // Generate basic VIBAN (without proper check digit calculation)
        // In production, implement proper ISO 13616 check digit calculation
        String vibanBase = countryCode + "00" + bankCode + programCode.toUpperCase() + sequence;

        // Calculate check digits (simplified - in production use proper IBAN algorithm)
        int checkDigits = 98 - (int)(calculateVibanCheckDigit(vibanBase) % 97);

        return String.format("%s%02d%s%s%s",
            countryCode,
            checkDigits,
            bankCode,
            programCode.toUpperCase(),
            sequence);
    }

    /**
     * Calculate VIBAN check digit (simplified).
     * In production, implement full ISO 7064 Mod 97-10 algorithm.
     */
    private long calculateVibanCheckDigit(String vibanBase) {
        // Move country code and check digits to end
        String rearranged = vibanBase.substring(4) + vibanBase.substring(0, 4);

        // Replace letters with numbers (A=10, B=11, ..., Z=35)
        StringBuilder numeric = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            if (Character.isLetter(c)) {
                numeric.append(Character.toUpperCase(c) - 'A' + 10);
            } else {
                numeric.append(c);
            }
        }

        // Calculate modulo 97 (handle large numbers by processing in chunks)
        long remainder = 0;
        for (int i = 0; i < numeric.length(); i += 9) {
            String chunk = remainder + numeric.substring(i, Math.min(i + 9, numeric.length()));
            remainder = Long.parseLong(chunk) % 97;
        }

        return remainder;
    }

    /**
     * Get publish status information for a VA.
     */
    @Transactional(readOnly = true)
    public VirtualAccountDto.PublishStatusInfo getPublishStatus(UUID id) {
        VirtualAccount va = getById(id);

        return VirtualAccountDto.PublishStatusInfo.builder()
            .id(va.getId())
            .vaNumber(va.getVaNumber())
            .viban(va.getViban())
            .publishStatus(va.getPublishStatus() != null ? va.getPublishStatus().name() : "UNPUBLISHED")
            .isPublished(va.isPublished())
            .canBePublished(va.canBePublished())
            .canReceiveExternalPayments(va.canReceiveExternalPayments())
            .publishedAt(va.getPublishedAt())
            .publishedBy(va.getPublishedBy())
            .unpublishedAt(va.getUnpublishedAt())
            .unpublishReason(va.getUnpublishReason())
            .build();
    }

    // ========================================================================
    // HIERARCHY MANAGEMENT (existing - unchanged)
    // ========================================================================

    @Transactional
    public VirtualAccount updateHierarchy(UUID id, UUID hierarchyNodeId) {
        log.info("Updating hierarchy for VA: {} to node: {}", id, hierarchyNodeId);

        VirtualAccount va = getById(id);

        HierarchyNode node = hierarchyNodeRepository.findById(hierarchyNodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Hierarchy node not found: " + hierarchyNodeId));

        va.setHierarchyNodeId(hierarchyNodeId);
        va.setHierarchyPath(node.getMaterializedPath());

        log.info("Hierarchy updated for VA: {} - Path: {}", va.getVaNumber(), va.getHierarchyPath());
        return virtualAccountRepository.save(va);
    }

    // ========================================================================
    // BALANCE OPERATIONS (existing - unchanged)
    // ========================================================================

    @Transactional
    public void updateBalance(UUID id, BigDecimal newBalance, BigDecimal newAvailableBalance) {
        VirtualAccount va = getById(id);
        va.setCurrentBalance(newBalance);
        va.setAvailableBalance(newAvailableBalance);
        va.setLastActivityDate(LocalDate.now());
        virtualAccountRepository.save(va);
    }

    // credit/debit were removed 2026-09-15 — they mutated currentBalance/
    // availableBalance directly with no Transaction record, no active-status
    // check, and no funds-availability check, unlike every other balance
    // mutation path in this codebase. VirtualAccountController now calls
    // TransactionService.credit/debit instead, which creates a real ledger
    // row. See the VAM Context Ledger artifact.

    // ========================================================================
    // PROGRAM TYPE CONFIGURATION (existing - unchanged)
    // ========================================================================



    // ========================================================================
    // STATISTICS (existing - unchanged)
    // ========================================================================

    @Transactional(readOnly = true)
    public VirtualAccountDto.VaStats getStats(UUID corporateId, UUID programId) {
        List<VirtualAccount> accounts;
        
        if (programId != null) {
            accounts = virtualAccountRepository.findByProgramId(programId);
        } else if (corporateId != null) {
            accounts = virtualAccountRepository.findByCorporateId(corporateId);
        } else {
            accounts = virtualAccountRepository.findAll();
        }

        Map<String, Long> countByStatus = accounts.stream()
                .collect(Collectors.groupingBy(
                        va -> va.getStatus().name(),
                        Collectors.counting()));

        Map<String, Long> countByWalletType = accounts.stream()
                .filter(va -> va.getWalletType() != null)
                .collect(Collectors.groupingBy(
                        VirtualAccount::getWalletType,
                        Collectors.counting()));

        Map<String, Long> countByKycLevel = accounts.stream()
                .collect(Collectors.groupingBy(
                        va -> "Level " + (va.getKycLevel() != null ? va.getKycLevel() : 0),
                        Collectors.counting()));

        BigDecimal totalBalance = accounts.stream()
                .map(VirtualAccount::getCurrentBalance)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal availableBalance = accounts.stream()
                .map(VirtualAccount::getAvailableBalance)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal heldBalance = accounts.stream()
                .map(VirtualAccount::getHeldBalance)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        LocalDate today = LocalDate.now();
        long activeLastDay = accounts.stream()
                .filter(va -> va.getLastActivityDate() != null && 
                        va.getLastActivityDate().isEqual(today))
                .count();

        long activeLastWeek = accounts.stream()
                .filter(va -> va.getLastActivityDate() != null && 
                        va.getLastActivityDate().isAfter(today.minusWeeks(1)))
                .count();

        long activeLastMonth = accounts.stream()
                .filter(va -> va.getLastActivityDate() != null && 
                        va.getLastActivityDate().isAfter(today.minusMonths(1)))
                .count();

        long kycExpiringCount = accounts.stream()
                .filter(va -> va.getKycExpiryDate() != null &&
                        va.getKycExpiryDate().isBefore(today.plusDays(30)) &&
                        va.getKycExpiryDate().isAfter(today))
                .count();

        return VirtualAccountDto.VaStats.builder()
                .totalAccounts(accounts.size())
                .activeAccounts(countByStatus.getOrDefault("ACTIVE", 0L))
                .inactiveAccounts(countByStatus.getOrDefault("INACTIVE", 0L))
                .suspendedAccounts(countByStatus.getOrDefault("SUSPENDED", 0L))
                .blockedAccounts(countByStatus.getOrDefault("BLOCKED", 0L))
                .expiredAccounts(countByStatus.getOrDefault("EXPIRED", 0L))
                .pendingActivationAccounts(countByStatus.getOrDefault("PENDING_ACTIVATION", 0L))
                .totalBalance(totalBalance)
                .availableBalance(availableBalance)
                .heldBalance(heldBalance)
                .countByWalletType(countByWalletType)
                .countByStatus(countByStatus)
                .countByKycLevel(countByKycLevel)
                .kycVerifiedCount(accounts.stream().filter(va -> Boolean.TRUE.equals(va.getKycVerified())).count())
                .kycPendingCount(accounts.stream().filter(va -> !Boolean.TRUE.equals(va.getKycVerified())).count())
                .kycExpiringCount(kycExpiringCount)
                .activeLastDay(activeLastDay)
                .activeLastWeek(activeLastWeek)
                .activeLastMonth(activeLastMonth)
                .build();
    }

    // ========================================================================
    // RESPONSE MAPPER (existing - unchanged)
    // ========================================================================

    public VirtualAccountDto.Response toResponse(VirtualAccount va) {
        String programName = null;
        if (va.getProgramId() != null) {
            programName = programRepository.findById(va.getProgramId())
                .map(Program::getProgramName).orElse(null);
        }

        String corporateName = null;
        if (va.getCorporateId() != null) {
            Optional<Corporate> corporate = corporateRepository.findById(va.getCorporateId());
            if (corporate.isPresent()) {
                corporateName = corporate.get().getLegalName();
            }
        }

        String physicalAccountNumber = null;
        if (va.getPhysicalAccountId() != null) {
            Optional<PhysicalAccount> physicalAccount = physicalAccountRepository.findById(va.getPhysicalAccountId());
            if (physicalAccount.isPresent()) {
                physicalAccountNumber = physicalAccount.get().getAccountNumber();
            }
        }

        String hierarchyNodeName = null;
        Integer hierarchyLevel = null;
        if (va.getHierarchyNodeId() != null) {
            Optional<HierarchyNode> node = hierarchyNodeRepository.findById(va.getHierarchyNodeId());
            if (node.isPresent()) {
                hierarchyNodeName = node.get().getNodeName();
                hierarchyLevel = node.get().getLevelNumber();
            }
        }

        boolean isExpired = va.getExpiresAt() != null && LocalDate.now().isAfter(va.getExpiresAt());
        Integer daysUntilExpiry = null;
        if (va.getExpiresAt() != null && !isExpired) {
            daysUntilExpiry = (int) java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), va.getExpiresAt());
        }

        boolean balanceExpired = va.getBalanceExpiryDate() != null && 
                LocalDate.now().isAfter(va.getBalanceExpiryDate());
        Integer daysUntilBalanceExpiry = null;
        if (va.getBalanceExpiryDate() != null && !balanceExpired) {
            daysUntilBalanceExpiry = (int) java.time.temporal.ChronoUnit.DAYS.between(
                    LocalDate.now(), va.getBalanceExpiryDate());
        }

        return VirtualAccountDto.Response.builder()
                .id(va.getId())
                .vaNumber(va.getVaNumber())
                .viban(va.getViban())
                .vaName(va.getVaName())
                .programId(va.getProgramId())
                .programName(programName)
                .corporateId(va.getCorporateId())
                .corporateName(corporateName)
                .physicalAccountId(va.getPhysicalAccountId())
                .physicalAccountNumber(physicalAccountNumber)
                .currencyCode(va.getCurrencyCode())
                .currentBalance(va.getCurrentBalance())
                .availableBalance(va.getAvailableBalance())
                .heldBalance(va.getHeldBalance())
                .status(va.getStatus().name())
                .statusLabel(getStatusLabel(va.getStatus()))
                .statusVariant(getStatusVariant(va.getStatus()))
                .externalReference(va.getExternalReference())
                .kycVerified(va.getKycVerified())
                .metadata(va.getMetadata())
                .createdAt(va.getCreatedAt())
                .updatedAt(va.getUpdatedAt())
                .hierarchyNodeId(va.getHierarchyNodeId())
                .hierarchyNodeName(hierarchyNodeName)
                .hierarchyPath(va.getHierarchyPath())
                .hierarchyLevel(hierarchyLevel)
                .collectionChannel(va.getCollectionChannel() != null ? va.getCollectionChannel().name() : null)
                .walletType(va.getWalletType())
                .walletTypeLabel(getWalletTypeLabel(va.getWalletType()))
                .kycLevel(va.getKycLevel())
                .kycLevelLabel(getKycLevelLabel(va.getKycLevel()))
                .kycExpiryDate(va.getKycExpiryDate())
                .kycVerifiedAt(va.getKycVerifiedAt())
                .expiresAt(va.getExpiresAt())
                .isExpired(isExpired)
                .daysUntilExpiry(daysUntilExpiry)
                .suspensionReason(va.getSuspensionReason())
                .blockReason(va.getBlockReason())
                .holderPartyId(va.getHolderPartyId())
                .limits(buildLimitsInfo(va))
                .limitsUsage(buildLimitsUsage(va))
                .valueType(va.getValueType() != null ? va.getValueType().name() : "FIAT")
                .valueTypeLabel(getValueTypeLabel(va.getValueType()))
                .pointsToCurrencyRate(va.getPointsToCurrencyRate())
                .pointsBalance(va.getPointsBalance())
                .pendingPoints(va.getPendingPoints())
                .lifetimePoints(va.getLifetimePoints())
                .loyaltyTier(va.getLoyaltyTier() != null ? va.getLoyaltyTier().name() : null)
                .loyaltyTierLabel(getLoyaltyTierLabel(va.getLoyaltyTier()))
                .loyaltyProgramId(va.getLoyaltyProgramId())
                .cardProgramType(va.getCardProgramType() != null ? va.getCardProgramType().name() : null)
                .cardProgramTypeLabel(getCardProgramTypeLabel(va.getCardProgramType()))
                .linkedCardId(va.getLinkedCardId())
                .budgetOwnerId(va.getBudgetOwnerId())
                .costCenter(va.getCostCenter())
                .department(va.getDepartment())
                .mccRestrictions(buildMccRestrictions(va))
                .hasMccRestrictions(hasMccRestrictions(va))
                .balanceExpiryDate(va.getBalanceExpiryDate())
                .expiryAction(va.getExpiryAction() != null ? va.getExpiryAction().name() : null)
                .expiryActionLabel(getExpiryActionLabel(va.getExpiryAction()))
                .balanceExpired(balanceExpired)
                .daysUntilBalanceExpiry(daysUntilBalanceExpiry)
                .transactionCount(va.getTransactionCount())
                .topupCount(va.getTopupCount())
                .withdrawalCount(va.getWithdrawalCount())
                .lastTransactionAt(va.getLastTransactionAt())
                .lastTopupAt(va.getLastTopupAt())
                .lastWithdrawalAt(va.getLastWithdrawalAt())
                .lastActivityDate(va.getLastActivityDate())
                .activatedAt(va.getActivatedAt())
                .owningEntityId(va.getOwningEntityId())
                .owningEntityCode(va.getOwningEntityCode())
                .accountCategory(va.getAccountCategory() != null ? va.getAccountCategory().name() : null)
                .accountType(va.getAccountType() != null ? va.getAccountType().name() : null)
                .parentAccountId(va.getParentAccountId())
                // Aggregated balances (for hierarchy nodes)
                .aggregatedBalance(va.getAggregatedBalance())
                .aggregatedBalanceBase(va.getAggregatedBalanceBase())
                .mirrorBalance(va.getMirrorBalance())
                .balanceInBase(va.getBalanceInBase())
                .baseCurrency(va.getBaseCurrency())
                // Publish status (Phase 1)
                .publishStatus(va.getPublishStatus() != null ? va.getPublishStatus().name() : "UNPUBLISHED")
                .isPublished(va.isPublished())
                .canBePublished(va.canBePublished())
                .canReceiveExternalPayments(va.canReceiveExternalPayments())
                .publishedAt(va.getPublishedAt())
                .publishedBy(va.getPublishedBy())
                // IHB (In-House Bank) Participation
                .ihbParticipant(va.getIhbParticipant())
                .ihbEnabledAt(va.getIhbEnabledAt())
                .ihbEnabledBy(va.getIhbEnabledBy())
                .creditLimit(va.getEffectiveCreditLimit())
                .availableCreditLimit(va.getCreditLimitAvailable())
                .currentExposure(va.getCreditLimitUtilized())
                .accruedCreditInterest(va.getAccruedCreditInterest())
                .accruedDebitInterest(va.getAccruedDebitInterest())
                .netAccruedInterest(va.getNetAccruedInterest())
                .effectiveCreditRate(va.getEffectiveCreditRate())
                .effectiveDebitRate(va.getEffectiveDebitRate())
                .internalInterestConfigId(va.getInternalInterestConfigId())
                .externalInterestConfigId(va.getExternalInterestConfigId())
                .ihbPositionType(va.getIhbPositionType())
                .ihbSweepEnabled(va.getIhbSweepEnabled())
                .targetCashBalance(va.getTargetCashBalance())
                .ihbSweepFrequency(va.getIhbSweepFrequency())
                .treasuryPoolVaId(va.getTreasuryPoolVaId())
                .build();
    }

    public VirtualAccountDto.Summary toSummary(VirtualAccount va) {
        return VirtualAccountDto.Summary.builder()
                .id(va.getId())
                .vaNumber(va.getVaNumber())
                .viban(va.getViban())
                .vaName(va.getVaName())
                .currencyCode(va.getCurrencyCode())
                .currentBalance(va.getCurrentBalance())
                .availableBalance(va.getAvailableBalance())
                .status(va.getStatus().name())
                .statusVariant(getStatusVariant(va.getStatus()))
                .walletType(va.getWalletType())
                .kycVerified(va.getKycVerified())
                .kycLevel(va.getKycLevel())
                .hierarchyPath(va.getHierarchyPath())
                .createdAt(va.getCreatedAt())
                .owningEntityId(va.getOwningEntityId())
                .owningEntityCode(va.getOwningEntityCode())
                .build();
    }

    private VirtualAccountDto.LimitsInfo buildLimitsInfo(VirtualAccount va) {
        boolean hasSpendingLimits = va.getPerTransactionLimit() != null || va.getDailyLimit() != null ||
                va.getWeeklyLimit() != null || va.getMonthlyLimit() != null || va.getAnnualLimit() != null;
        boolean hasTopupLimits = va.getDailyTopupLimit() != null || va.getMonthlyTopupLimit() != null;

        return VirtualAccountDto.LimitsInfo.builder()
                .perTransactionLimit(va.getPerTransactionLimit())
                .dailyLimit(va.getDailyLimit())
                .weeklyLimit(va.getWeeklyLimit())
                .monthlyLimit(va.getMonthlyLimit())
                .annualLimit(va.getAnnualLimit())
                .maxBalance(va.getMaxBalance())
                .dailyTopupLimit(va.getDailyTopupLimit())
                .monthlyTopupLimit(va.getMonthlyTopupLimit())
                .hasSpendingLimits(hasSpendingLimits)
                .hasTopupLimits(hasTopupLimits)
                .build();
    }

    private VirtualAccountDto.LimitsUsage buildLimitsUsage(VirtualAccount va) {
        BigDecimal dailyRemaining = calculateRemaining(va.getDailyLimit(), va.getDailyUsed());
        BigDecimal weeklyRemaining = calculateRemaining(va.getWeeklyLimit(), va.getWeeklyUsed());
        BigDecimal monthlyRemaining = calculateRemaining(va.getMonthlyLimit(), va.getMonthlyUsed());
        BigDecimal annualRemaining = calculateRemaining(va.getAnnualLimit(), va.getAnnualUsed());
        BigDecimal dailyTopupRemaining = calculateRemaining(va.getDailyTopupLimit(), va.getDailyTopupUsed());
        BigDecimal monthlyTopupRemaining = calculateRemaining(va.getMonthlyTopupLimit(), va.getMonthlyTopupUsed());

        Double dailyUsagePercent = calculateUsagePercent(va.getDailyUsed(), va.getDailyLimit());
        Double weeklyUsagePercent = calculateUsagePercent(va.getWeeklyUsed(), va.getWeeklyLimit());
        Double monthlyUsagePercent = calculateUsagePercent(va.getMonthlyUsed(), va.getMonthlyLimit());
        Double annualUsagePercent = calculateUsagePercent(va.getAnnualUsed(), va.getAnnualLimit());
        Double dailyTopupUsagePercent = calculateUsagePercent(va.getDailyTopupUsed(), va.getDailyTopupLimit());
        Double monthlyTopupUsagePercent = calculateUsagePercent(va.getMonthlyTopupUsed(), va.getMonthlyTopupLimit());

        return VirtualAccountDto.LimitsUsage.builder()
                .dailyUsed(va.getDailyUsed())
                .weeklyUsed(va.getWeeklyUsed())
                .monthlyUsed(va.getMonthlyUsed())
                .annualUsed(va.getAnnualUsed())
                .dailyTopupUsed(va.getDailyTopupUsed())
                .monthlyTopupUsed(va.getMonthlyTopupUsed())
                .lastResetDate(va.getLastLimitResetDate())
                .dailyRemaining(dailyRemaining)
                .weeklyRemaining(weeklyRemaining)
                .monthlyRemaining(monthlyRemaining)
                .annualRemaining(annualRemaining)
                .dailyTopupRemaining(dailyTopupRemaining)
                .monthlyTopupRemaining(monthlyTopupRemaining)
                .dailyUsagePercent(dailyUsagePercent)
                .weeklyUsagePercent(weeklyUsagePercent)
                .monthlyUsagePercent(monthlyUsagePercent)
                .annualUsagePercent(annualUsagePercent)
                .dailyTopupUsagePercent(dailyTopupUsagePercent)
                .monthlyTopupUsagePercent(monthlyTopupUsagePercent)
                .dailyLimitWarning(dailyUsagePercent != null && dailyUsagePercent >= 75)
                .weeklyLimitWarning(weeklyUsagePercent != null && weeklyUsagePercent >= 75)
                .monthlyLimitWarning(monthlyUsagePercent != null && monthlyUsagePercent >= 75)
                .dailyLimitExceeded(dailyUsagePercent != null && dailyUsagePercent >= 100)
                .weeklyLimitExceeded(weeklyUsagePercent != null && weeklyUsagePercent >= 100)
                .monthlyLimitExceeded(monthlyUsagePercent != null && monthlyUsagePercent >= 100)
                .build();
    }

    private BigDecimal calculateRemaining(BigDecimal limit, BigDecimal used) {
        if (limit == null) return null;
        BigDecimal usedAmount = used != null ? used : BigDecimal.ZERO;
        BigDecimal remaining = limit.subtract(usedAmount);
        return remaining.max(BigDecimal.ZERO);
    }

    private Double calculateUsagePercent(BigDecimal used, BigDecimal limit) {
        if (limit == null || limit.compareTo(BigDecimal.ZERO) == 0) return null;
        if (used == null) return 0.0;
        return used.divide(limit, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100")).doubleValue();
    }

    private VirtualAccountDto.MccRestrictions buildMccRestrictions(VirtualAccount va) {
        List<String> mccWhitelist = parseMccList(va.getMccWhitelist());
        List<String> mccBlacklist = parseMccList(va.getMccBlacklist());
        List<String> merchantWhitelist = parseMccList(va.getMerchantWhitelist());
        List<String> countryWhitelist = parseMccList(va.getCountryWhitelist());

        return VirtualAccountDto.MccRestrictions.builder()
                .mccWhitelist(mccWhitelist.isEmpty() ? null : mccWhitelist)
                .mccBlacklist(mccBlacklist.isEmpty() ? null : mccBlacklist)
                .merchantWhitelist(merchantWhitelist.isEmpty() ? null : merchantWhitelist)
                .countryWhitelist(countryWhitelist.isEmpty() ? null : countryWhitelist)
                .mccWhitelistCount(mccWhitelist.size())
                .mccBlacklistCount(mccBlacklist.size())
                .merchantWhitelistCount(merchantWhitelist.size())
                .countryWhitelistCount(countryWhitelist.size())
                .build();
    }

    private boolean hasMccRestrictions(VirtualAccount va) {
        return (va.getMccWhitelist() != null && !va.getMccWhitelist().isEmpty()) ||
                (va.getMccBlacklist() != null && !va.getMccBlacklist().isEmpty()) ||
                (va.getMerchantWhitelist() != null && !va.getMerchantWhitelist().isEmpty()) ||
                (va.getCountryWhitelist() != null && !va.getCountryWhitelist().isEmpty());
    }

    // ========================================================================
    // LABEL HELPERS (existing - unchanged)
    // ========================================================================

    private String getStatusLabel(VirtualAccount.VaStatus status) {
        if (status == null) return "Unknown";
        return switch (status) {
            case ACTIVE -> "Active";
            case INACTIVE -> "Inactive";
            case SUSPENDED -> "Suspended";
            case CLOSED -> "Closed";
            case BLOCKED -> "Blocked";
            case PENDING_ACTIVATION -> "Pending Activation";
            case EXPIRED -> "Expired";
        };
    }

    private String getStatusVariant(VirtualAccount.VaStatus status) {
        if (status == null) return "neutral";
        return switch (status) {
            case ACTIVE -> "success";
            case INACTIVE -> "neutral";
            case SUSPENDED -> "warning";
            case CLOSED -> "neutral";
            case BLOCKED -> "danger";
            case PENDING_ACTIVATION -> "info";
            case EXPIRED -> "danger";
        };
    }

    private String getWalletTypeLabel(String walletType) {
        if (walletType == null) return null;
        return switch (walletType.toUpperCase()) {
            case "CONSUMER" -> "Consumer Wallet";
            case "EMPLOYEE" -> "Employee Wallet";
            case "MERCHANT" -> "Merchant Wallet";
            case "AGENT" -> "Agent Wallet";
            case "CORPORATE" -> "Corporate Wallet";
            case "GIFT" -> "Gift Wallet";
            default -> walletType;
        };
    }

    private String getKycLevelLabel(Integer level) {
        if (level == null) return "Unverified";
        return switch (level) {
            case 0 -> "Unverified";
            case 1 -> "Basic";
            case 2 -> "Enhanced";
            case 3 -> "Full";
            default -> "Level " + level;
        };
    }

    private String getValueTypeLabel(VirtualAccount.ValueType valueType) {
        if (valueType == null) return "Fiat Currency";
        return switch (valueType) {
            case FIAT -> "Fiat Currency";
            case POINTS -> "Loyalty Points";
            case MILES -> "Air Miles";
            case TOKENS -> "Digital Tokens";
            case CRYPTO -> "Cryptocurrency";
        };
    }

    private String getLoyaltyTierLabel(VirtualAccount.LoyaltyTier tier) {
        if (tier == null) return null;
        return switch (tier) {
            case PLATINUM -> "Platinum";
            case GOLD -> "Gold";
            case SILVER -> "Silver";
            case BLUE -> "Blue";
            case BASIC -> "Basic";
        };
    }

    private String getCardProgramTypeLabel(VirtualAccount.CardProgramType type) {
        if (type == null) return null;
        return switch (type) {
            case TRAVEL -> "Travel & Entertainment";
            case PROCUREMENT -> "Procurement";
            case FLEET -> "Fleet/Fuel";
            case VIRTUAL -> "Virtual Card";
            case EXPENSE -> "Employee Expense";
            case PETTY_CASH -> "Petty Cash";
        };
    }

    private String getExpiryActionLabel(VirtualAccount.ExpiryAction action) {
        if (action == null) return null;
        return switch (action) {
            case ZERO_BALANCE -> "Zero Balance";
            case FORFEIT -> "Forfeit to Program";
            case TRANSFER -> "Transfer to Another Account";
            case EXTEND -> "Auto-Extend";
            case NOTIFY -> "Notify Only";
        };
    }
 // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Check if VA is an operational/transactional VA.
     * Operational VAs are the ones that hold actual transaction balances.
     */
    private boolean isOperationalVa(VirtualAccount va) {
        if (va == null || va.getAccountCategory() == null) {
            return false;
        }
        VirtualAccount.AccountCategory cat = va.getAccountCategory();
        return cat == VirtualAccount.AccountCategory.TRANSACTION ||
               cat == VirtualAccount.AccountCategory.COLLECTION ||
               cat == VirtualAccount.AccountCategory.DISBURSEMENT;
    }

}