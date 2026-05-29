package com.bank.vam.service;

import com.bank.vam.dto.ProgramTypeConfigDto;
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

            // Validate currency match (only if currencyCode is provided)
            if (request.getCurrencyCode() != null && !program.getCurrencyCode().equals(request.getCurrencyCode())) {
                throw new BusinessException("Currency must match program currency: " + program.getCurrencyCode());
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
                .hierarchyLevel(newLevel)
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

        // Inherit ownership from parent if not specified
        if (va.getOwningEntityId() == null) {
            inheritOwnershipFromParent(va, parentNode);
        }

        // Save VA first to get its ID
        va = virtualAccountRepository.save(va);
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

        // STEP 13: Update parent node
        parentNode.setChildCount(parentNode.getChildCount() != null ? parentNode.getChildCount() + 1 : 1);
        parentNode.setIsLeaf(false);
        hierarchyNodeRepository.save(parentNode);

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
        if (parentNode.getVirtualAccountId() != null) {
            return parentNode.getVirtualAccountId();
        }
        // Try to find VA linked to this node
        return virtualAccountRepository.findByHierarchyNodeId(parentNode.getId())
                .map(VirtualAccount::getId)
                .orElse(null);
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

        // Validate hierarchy is enabled
        if (!Boolean.TRUE.equals(program.getHierarchyEnabled())) {
            throw new BusinessException("Hierarchy not enabled for program. Enable hierarchy first.");
        }

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

// Apply request overrides
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
                    .nodeType(determineNodeType(level, levelConfigs.size()))
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

                // Update parent's child count
                if (currentNode != null) {
                    currentNode.setChildCount(currentNode.getChildCount() + 1);
                    currentNode.setIsLeaf(false);
                    hierarchyNodeRepository.save(currentNode);
                } else {
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
                                                            String nodeCode, int level) {
        if (parentId == null) {
            // Looking for root node (L1)
            return hierarchyNodeRepository.findByProgramIdAndLevelNumber(programId, level)
                .stream()
                .filter(n -> n.getNodeCode().equals(nodeCode))
                .findFirst();
        }
        return hierarchyNodeRepository.findByParentIdAndNodeCode(parentId, nodeCode);
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
    private HierarchyNodeType determineNodeType(int level, int maxLevel) {
        if (level == 1) return HierarchyNodeType.MASTER;
        if (level >= maxLevel) return HierarchyNodeType.VIRTUAL_ACCOUNT;
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

    // ========================================================================
    // IHB CURRENT ACCOUNT CREATION
    // ========================================================================

    /**
     * Create IHB Current Account for a participant entity.
     *
     * This creates an INTERCOMPANY VA that functions as the participant's
     * current account at the In-House Bank (Treasury Center).
     *
     * Features:
     * - Running balance (can go negative for overdraft)
     * - Credit interest on positive balance
     * - Debit interest on negative balance
     * - Overdraft limit from entity's IHB credit limit
     *
     * KEY DESIGN: Uses TRANSACTION category with ihbParticipant=true.
     * This allows any operational VA to participate in IHB interest schemes,
     * rather than requiring a separate INTERCOMPANY category.
     *
     * @param request IHB Current Account creation request
     * @return Created VirtualAccount with accountCategory=TRANSACTION and ihbParticipant=true
     */
    @Transactional
    public VirtualAccount createIhbCurrentAccount(VirtualAccountDto.IhbCurrentAccountRequest request) {
        log.info("Creating IHB Current Account for entity: {}, parentNodeId={}, programId={}",
            request.getParticipantEntityId(), request.getParentNodeId(), request.getProgramId());

        // 1. Validate participant entity is IHB-enabled
        LegalEntity participant = legalEntityRepository.findById(request.getParticipantEntityId())
            .orElseThrow(() -> new ResourceNotFoundException(
                "Participant entity not found: " + request.getParticipantEntityId()));

        if (!participant.isIhbEnabled()) {
            throw new BusinessException("Entity is not IHB-enabled: " + participant.getEntityCode());
        }

        // 2. Resolve or validate IHB Program
        // IHB is a Program Type, so IHB accounts MUST belong to an IHB program
        UUID ihbProgramId = resolveIhbProgram(participant.getCorporateId(), request.getProgramId());

        // 3. Get Treasury Center for this corporate
        LegalEntity treasury = legalEntityRepository
            .findByCorporateIdAndCanLendTrue(participant.getCorporateId())
            .stream()
            .findFirst()
            .orElseThrow(() -> new BusinessException(
                "No Treasury Center found for corporate: " + participant.getCorporateId()));

        if (!treasury.canLend()) {
            throw new BusinessException("Treasury Center cannot lend: " + treasury.getEntityCode());
        }

        // 5. Multiple IHB accounts per entity/currency are allowed
        // Use case: Entity may have separate IHB accounts for Operating, Payroll, Treasury Reserve, etc.
        // Each account participates independently in interest schemes.
        // Entity's total IHB position = sum of all their IHB-enabled VAs.
        long existingCount = virtualAccountRepository
            .countByOwningEntityIdAndIhbParticipantTrueAndCurrencyCode(
                participant.getId(),
                request.getCurrencyCode());

        // 6. Resolve parent hierarchy node and VA
        // ============================================================================
        // IHB CONCEPTUAL MODEL:
        // - Treasury Center IS the In-House Bank (offers accounts to subsidiaries)
        // - Subsidiary is the CUSTOMER (has account AT the Treasury)
        // - IHB Current Account should automatically appear under Treasury's hierarchy
        //
        // HIERARCHY RESOLUTION:
        // 1. If parentNodeId provided: Use the explicitly selected parent (backward compatibility)
        // 2. If NOT provided: Auto-discover Treasury's aggregation VA for the given currency
        //    The IHB Current Account is placed under Treasury because:
        //    - Treasury is the "bank" offering IHB accounts
        //    - Subsidiaries are "customers" with accounts at this internal bank
        // ============================================================================
        HierarchyNode parentHierarchyNode = null;
        VirtualAccount parentVa = null;

        if (request.getParentNodeId() != null) {
            // EXPLICIT PARENT: User selected a specific parent node
            // Frontend sends VA ID (not hierarchy node ID) because balance structure API returns VA IDs
            parentHierarchyNode = hierarchyNodeRepository.findByVirtualAccountId(request.getParentNodeId())
                .orElseGet(() -> hierarchyNodeRepository.findById(request.getParentNodeId()).orElse(null));

            if (parentHierarchyNode != null && parentHierarchyNode.getVirtualAccountId() != null) {
                parentVa = virtualAccountRepository.findById(parentHierarchyNode.getVirtualAccountId()).orElse(null);
                log.info("Found parent hierarchy node: {} with VA: {}",
                    parentHierarchyNode.getNodeCode(), parentVa != null ? parentVa.getVaNumber() : "N/A");
            }

            // Fallback: If no hierarchy node found, try to find the VA directly by the provided ID
            if (parentVa == null) {
                parentVa = virtualAccountRepository.findById(request.getParentNodeId()).orElse(null);
                if (parentVa != null) {
                    log.info("Found parent VA directly (no hierarchy node): {}", parentVa.getVaNumber());
                }
            }
        }

        // AUTO-DISCOVERY: If no explicit parent, place under Treasury's hierarchy
        // Since Treasury IS the In-House Bank, IHB Current Accounts belong under Treasury
        if (parentVa == null) {
            log.info("No explicit parent provided. Auto-discovering Treasury {} hierarchy for currency {}",
                treasury.getEntityCode(), request.getCurrencyCode());

            // Find Treasury's aggregation/root VA for this currency
            List<VirtualAccount> treasuryVas = virtualAccountRepository.findByOwningEntityIdAndCurrencyCode(
                treasury.getId(), request.getCurrencyCode());

            // Prefer AGGREGATION, then ROOT, then any VA with a hierarchy node
            parentVa = treasuryVas.stream()
                .filter(va -> va.getAccountCategory() == VirtualAccount.AccountCategory.AGGREGATION)
                .findFirst()
                .orElseGet(() -> treasuryVas.stream()
                    .filter(va -> va.getAccountCategory() == VirtualAccount.AccountCategory.ROOT)
                    .findFirst()
                    .orElseGet(() -> treasuryVas.stream()
                        .filter(va -> va.getHierarchyNodeId() != null)
                        .findFirst()
                        .orElse(null)));

            if (parentVa != null) {
                // Try to find the hierarchy node for this Treasury VA
                if (parentVa.getHierarchyNodeId() != null) {
                    parentHierarchyNode = hierarchyNodeRepository.findById(parentVa.getHierarchyNodeId()).orElse(null);
                } else {
                    parentHierarchyNode = hierarchyNodeRepository.findByVirtualAccountId(parentVa.getId()).orElse(null);
                }

                log.info("Auto-discovered Treasury parent: VA={}, hierarchyNode={}",
                    parentVa.getVaNumber(),
                    parentHierarchyNode != null ? parentHierarchyNode.getNodeCode() : "N/A");
            } else {
                log.warn("No suitable Treasury VA found for currency {} to auto-place IHB Current Account. " +
                    "The account will be created without hierarchy linking.", request.getCurrencyCode());
            }
        }

        // 7. Ensure Treasury has a Settlement VA for this currency (required for IHB settlement)
        // ============================================================================
        // The Treasury Settlement VA is the counterparty for all IHB transactions:
        // - Deposits: Subsidiary → Treasury Settlement VA
        // - Loans: Treasury Settlement VA → Subsidiary
        // - Interest Settlement: Posted via Treasury Settlement VA
        // If Treasury doesn't have one for this currency, auto-create it.
        // ============================================================================
        VirtualAccount treasurySettlementVa = ensureTreasurySettlementVa(
            treasury, request.getCurrencyCode(), ihbProgramId, parentVa, parentHierarchyNode);

        // 8. IHB Current Accounts are NOTIONAL/VIRTUAL - no physical account needed
        // ============================================================================
        // IHB (In-House Bank) Flow:
        // 1. Subsidiary deposits surplus cash → IHB Current Account balance increases (credit)
        // 2. Subsidiary borrows from Treasury → IHB Current Account balance decreases (debit)
        // 3. Daily interest accrues based on balance and configured rates
        // 4. Monthly interest posting settles accrued amounts
        //
        // This is purely internal bookkeeping between Treasury and subsidiaries.
        // No physical bank account involvement - physicalAccountId is intentionally NULL.
        // ============================================================================

        // 9. Get or create Interest Configuration for participant
        InterestConfiguration interestConfig = getOrCreateIhbInterestConfig(
            participant, treasury, request);

        // 10. Determine credit limit
        BigDecimal creditLimit = request.getCreditLimit() != null
            ? request.getCreditLimit()
            : (participant.getIhbCreditLimit() != null ? participant.getIhbCreditLimit() : BigDecimal.ZERO);

        // 11. Generate IHB account number
        String accountNumber = generateIhbCurrentAccountNumber(participant, request.getCurrencyCode());

        // 12. Determine VA name
        // If user provides a name, use it. Otherwise generate with sequence number for uniqueness.
        String vaName;
        if (request.getVaName() != null && !request.getVaName().isBlank()) {
            vaName = request.getVaName();
        } else if (existingCount > 0) {
            // Add sequence number for additional accounts
            vaName = "IHB Current - " + participant.getEntityName() + " (" + (existingCount + 1) + ")";
        } else {
            vaName = "IHB Current - " + participant.getEntityName();
        }

        // 13. Build the VA
        // KEY: TRANSACTION category with ihbParticipant=true, always linked to IHB program
        // NOTE: physicalAccountId is intentionally NULL - IHB accounts are notional/virtual
        VirtualAccount va = VirtualAccount.builder()
            // Identity
            .vaNumber(accountNumber)
            .vaName(vaName)
            .corporateId(participant.getCorporateId())
            .programId(ihbProgramId)  // Always set to resolved IHB program
            // physicalAccountId intentionally not set - IHB is notional, no physical bank account
            .currencyCode(request.getCurrencyCode())

            // Account Classification - KEY: TRANSACTION with IHB flag
            .accountCategory(VirtualAccount.AccountCategory.TRANSACTION)
            .accountType(VirtualAccount.AccountType.VIRTUAL)

            // IHB PARTICIPATION FLAG - The key change!
            .ihbParticipant(true)
            .ihbEnabledAt(LocalDateTime.now())
            .ihbEnabledBy("SYSTEM")

            // Ownership & Hierarchy
            .owningEntityId(participant.getId())
            .owningEntityCode(participant.getEntityCode())

            // VA Hierarchy: Link to Treasury's aggregation VA
            // parentVa is auto-discovered from Treasury's hierarchy if not explicitly selected
            // This places the IHB Current Account under Treasury (the In-House Bank)
            .parentAccountId(parentVa != null ? parentVa.getId() : null)

            // Treasury Settlement VA: Omnibus Settlement VA for all IHB transactions
            // This is the counterparty for 4-leg accounting: IHB Current Account → Treasury Settlement VA → Shadow VA
            // Using treasuryPoolVaId field to store this reference for transaction routing
            .treasuryPoolVaId(treasurySettlementVa != null ? treasurySettlementVa.getId() : null)

            // Balances - Start at zero
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(creditLimit)
            .heldBalance(BigDecimal.ZERO)

            // Interest Configuration
            .internalInterestConfigId(interestConfig.getId())
            .effectiveCreditRate(interestConfig.getEffectiveCreditRate())
            .effectiveDebitRate(interestConfig.getEffectiveDebitRate())
            .penaltyRate(interestConfig.getPenaltyRate())

            // Credit/Overdraft Limit
            .effectiveCreditLimit(creditLimit)
            .creditLimitAvailable(creditLimit)
            .creditLimitUtilized(BigDecimal.ZERO)

            // Interest Accrual - Start at zero
            .accruedCreditInterest(BigDecimal.ZERO)
            .accruedDebitInterest(BigDecimal.ZERO)

            // IHB Sweep Configuration (from request or defaults)
            .ihbSweepEnabled(Boolean.TRUE.equals(request.getIhbSweepEnabled()))
            .targetCashBalance(request.getTargetCashBalance() != null
                ? request.getTargetCashBalance() : BigDecimal.ZERO)
            .ihbSweepFrequency(request.getIhbSweepFrequency() != null
                ? request.getIhbSweepFrequency() : "DAILY")

            // Status
            .status(VirtualAccount.VaStatus.ACTIVE)

            // Metadata
            .externalReference(participant.getEntityCode() + "-IHB-" + request.getCurrencyCode())
            .build();

        va = virtualAccountRepository.save(va);
        log.info("Created IHB Current Account: {} for entity {} (ihbParticipant=true) -> Treasury Settlement VA: {}",
            va.getVaNumber(), participant.getEntityCode(),
            treasurySettlementVa != null ? treasurySettlementVa.getVaNumber() : "N/A");

        // 14. Create hierarchy node if parent hierarchy node was resolved earlier
        // This enables the IHB Current Account to appear in the Treasury Hierarchy view
        // IMPORTANT: Use parent node's programId (not IHB programId) so the VA appears in the correct hierarchy
        if (parentHierarchyNode != null) {
            log.info("Creating hierarchy node for IHB Current Account under parent: {} (program: {})",
                parentHierarchyNode.getNodeCode(), parentHierarchyNode.getProgramId());

            // Calculate hierarchy level
            int newLevel = parentHierarchyNode.getLevelNumber() + 1;

            // Generate unique node code
            String nodeCode = "IHB-" + participant.getEntityCode() + "-" + request.getCurrencyCode();
            // Check if node code already exists under this parent, add suffix if needed
            long existingNodeCount = hierarchyNodeRepository.countByParentIdAndNodeCodeStartingWith(
                parentHierarchyNode.getId(), nodeCode);
            if (existingNodeCount > 0) {
                nodeCode = nodeCode + "-" + (existingNodeCount + 1);
            }
            String materializedPath = parentHierarchyNode.getMaterializedPath() + "/" + nodeCode;

            // Create hierarchy node - USE PARENT'S PROGRAM ID
            HierarchyNode node = HierarchyNode.builder()
                .programId(parentHierarchyNode.getProgramId())  // Use parent's program, not IHB program
                .parentId(parentHierarchyNode.getId())
                .levelNumber(newLevel)
                .nodeCode(nodeCode)
                .nodeName(va.getVaName())
                .nodeType(HierarchyNodeType.VIRTUAL_ACCOUNT)
                .currencyCode(request.getCurrencyCode())
                .dimensionValue("IHB_CURRENT_ACCOUNT")
                .materializedPath(materializedPath)
                .isLeaf(true)
                .childCount(0)
                .status("ACTIVE")
                .aggregatedBalance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .virtualAccountId(va.getId())
                .build();

            node = hierarchyNodeRepository.save(node);
            log.debug("Created hierarchy node for IHB Current Account: {} at path: {} (program: {})",
                node.getNodeCode(), node.getMaterializedPath(), node.getProgramId());

            // Update VA with hierarchy node ID, path, and PARENT'S PROGRAM
            va.setHierarchyNodeId(node.getId());
            va.setHierarchyPath(materializedPath);
            va.setHierarchyLevel(newLevel);
            va.setProgramId(parentHierarchyNode.getProgramId());  // Update VA to use parent's program
            va = virtualAccountRepository.save(va);

            // Update parent hierarchy node child count
            parentHierarchyNode.setChildCount(parentHierarchyNode.getChildCount() != null ? parentHierarchyNode.getChildCount() + 1 : 1);
            parentHierarchyNode.setIsLeaf(false);
            hierarchyNodeRepository.save(parentHierarchyNode);

            log.info("✓ Linked IHB Current Account {} to hierarchy at level {} under {} (program: {})",
                va.getVaNumber(), newLevel, parentHierarchyNode.getNodeCode(), parentHierarchyNode.getProgramId());
        } else if (parentVa != null) {
            // Parent VA found but no hierarchy node - still set parentAccountId for VA hierarchy
            log.info("Parent VA {} found but no hierarchy node. IHB Current Account will be linked via parentAccountId.",
                parentVa.getVaNumber());
        } else {
            log.warn("Could not resolve parent hierarchy for IHB Current Account. " +
                "Ensure Treasury {} has an aggregation VA for currency {} with a hierarchy node.",
                treasury.getEntityCode(), request.getCurrencyCode());
        }

        // 15. Track participant's primary IHB Current Account
        // NOTE: This stores the IHB Current Account ID (not a Settlement VA) on the participant entity.
        // The actual Settlement VA for IHB transactions is Treasury's Settlement VA stored in va.treasuryPoolVaId.
        // This field is used to quickly find the participant's IHB position for reporting.
        // Only set for the FIRST IHB account created (existingCount == 0).
        if (participant.getSettlementVaId() == null && existingCount == 0) {
            participant.setSettlementVaId(va.getId());
            legalEntityRepository.save(participant);
            log.info("Set {} as primary IHB account for entity {}", va.getVaNumber(), participant.getEntityCode());
        }

        // 16. Create Cash Concentration Sweep Rule if sweep is enabled
        // Sweep target is Treasury's Settlement VA (ensured in step 7)
        if (Boolean.TRUE.equals(request.getIhbSweepEnabled()) && treasurySettlementVa != null) {
            createIhbSweepRule(va, participant, treasury, treasurySettlementVa, request);
        } else if (Boolean.TRUE.equals(request.getIhbSweepEnabled())) {
            log.warn("Sweep enabled but no Treasury Settlement VA found for IHB Current Account {} - sweep rule not created",
                va.getVaNumber());
        }

        // =====================================================================
        // 17. MIRROR ACCOUNT MODEL (Option A - 6-leg POBO)
        // Create IHB Settlement VA and IC Receivable VA for proper intercompany accounting
        // =====================================================================

        // Set mirror account type on IHB Current Account
        va.setMirrorAccountType(VirtualAccount.MirrorAccountType.IHB_CURRENT);
        va = virtualAccountRepository.save(va);

        // 17a. Create IHB Settlement VA (sibling to IHB Current Account)
        // This is the per-subsidiary routing point for POBO payments
        VirtualAccount ihbSettlementVa = ensureIhbSettlementVa(va, participant, ihbProgramId, parentHierarchyNode);
        log.info("IHB Settlement VA: {} linked to IHB Current Account {}",
            ihbSettlementVa.getVaNumber(), va.getVaNumber());

        // 17b. Create IC Receivable VA at Treasury (tracks Treasury's claim on subsidiary)
        // This enables proper intercompany accounting and reconciliation
        if (treasurySettlementVa != null) {
            VirtualAccount icReceivableVa = ensureIcReceivableVa(va, participant, treasury, treasurySettlementVa, ihbProgramId);
            log.info("IC Receivable VA: {} at Treasury for subsidiary {}",
                icReceivableVa.getVaNumber(), participant.getEntityCode());

            // Mark Treasury Settlement VA as TREASURY_SETTLEMENT type if not already set
            if (treasurySettlementVa.getMirrorAccountType() == null ||
                treasurySettlementVa.getMirrorAccountType() == VirtualAccount.MirrorAccountType.NONE) {
                treasurySettlementVa.setMirrorAccountType(VirtualAccount.MirrorAccountType.TREASURY_SETTLEMENT);
                virtualAccountRepository.save(treasurySettlementVa);
            }
        } else {
            log.warn("Treasury Settlement VA not available - IC Receivable VA not created for {}. " +
                "6-leg POBO will fall back to 4-leg flow.", va.getVaNumber());
        }

        log.info("✓ IHB Current Account {} configured for Mirror Account Model (6-leg POBO ready: {})",
            va.getVaNumber(), va.isConfiguredFor6LegPobo());

        return va;
    }

    /**
     * Create Cash Concentration Sweep Rule for IHB Current Account.
     * Links the IHB participant account to Treasury's Settlement VA for automated sweeping.
     *
     * @param va The IHB current account (source)
     * @param participant The participant entity
     * @param treasury The treasury center entity
     * @param treasurySettlementVa The treasury's settlement VA (target)
     * @param request The creation request with sweep configuration
     */
    private void createIhbSweepRule(
            VirtualAccount va,
            LegalEntity participant,
            LegalEntity treasury,
            VirtualAccount treasurySettlementVa,
            VirtualAccountDto.IhbCurrentAccountRequest request) {

        BigDecimal targetBalance = request.getTargetCashBalance() != null ?
            request.getTargetCashBalance() : BigDecimal.ZERO;

        // Generate unique rule reference: IHB-{entityCode}-{currency}-{seq}
        String ruleRef = "IHB-" + participant.getEntityCode() + "-" + va.getCurrencyCode();
        if (ruleRef.length() > 20) {
            // Truncate and add hash for uniqueness
            String hash = String.valueOf(Math.abs(ruleRef.hashCode()) % 10000);
            ruleRef = ruleRef.substring(0, 15) + hash;
        }

        // Check if rule already exists for this account
        boolean ruleExists = sweepRuleRepository.existsByRuleReference(ruleRef);
        if (ruleExists) {
            log.info("Sweep rule {} already exists for IHB account {}", ruleRef, va.getVaNumber());
            return;
        }

        // Create the sweep rule
        SweepRule rule = new SweepRule();
        rule.setRuleReference(ruleRef);
        rule.setRuleName("IHB Sweep: " + participant.getEntityName() + " → Treasury (" + va.getCurrencyCode() + ")");
        rule.setSweepType(targetBalance.compareTo(BigDecimal.ZERO) == 0 ?
            SweepRule.SweepType.ZERO_BALANCE : SweepRule.SweepType.TARGET_BALANCE);
        rule.setTargetAmount(targetBalance);
        rule.setTargetAccountId(treasurySettlementVa.getId());
        rule.setTargetAccountNumber(treasurySettlementVa.getVaNumber());
        rule.setTargetEntityCode(treasury.getEntityCode());
        rule.setFrequency(mapSweepFrequency(request.getIhbSweepFrequency()));
        rule.setPriority(10);  // Default priority
        rule.setStatus(SweepRule.SweepStatus.ACTIVE);
        rule.setCurrencyCode(va.getCurrencyCode());

        // Add source account
        List<SweepRuleSource> sources = new ArrayList<>();
        SweepRuleSource source = new SweepRuleSource();
        source.setRule(rule);
        source.setAccountId(va.getId());
        source.setAccountNumber(va.getVaNumber());
        source.setEntityCode(participant.getEntityCode());
        source.setEntityName(participant.getEntityName());
        source.setCurrencyCode(va.getCurrencyCode());
        sources.add(source);
        rule.setSourceAccounts(sources);

        sweepRuleRepository.save(rule);
        log.info("Created IHB Cash Concentration Sweep Rule: {} for account {} → Treasury {}",
            ruleRef, va.getVaNumber(), treasurySettlementVa.getVaNumber());
    }

    // ========================================================================
    // IHB MIRROR ACCOUNT MODEL (Option A - 6-leg POBO)
    // ========================================================================

    /**
     * Ensure IHB Settlement VA exists for the IHB Current Account.
     *
     * IHB Settlement VA is a sibling to the IHB Current Account that:
     * - Acts as the per-subsidiary routing/settlement point
     * - Receives funds from IHB Current Account for POBO payments
     * - Routes funds to Treasury Settlement VA
     *
     * 6-leg POBO flow:
     * IHB Current Account → IHB Settlement VA → Treasury Settlement VA → IC Receivable → Shadow VA → CBS
     *
     * @param ihbCurrentAccount The IHB Current Account to create settlement VA for
     * @param participant The subsidiary legal entity
     * @param ihbProgramId The IHB program ID
     * @param parentHierarchyNode The parent hierarchy node (same as IHB Current Account's parent)
     * @return The IHB Settlement VA
     */
    @Transactional
    public VirtualAccount ensureIhbSettlementVa(
            VirtualAccount ihbCurrentAccount,
            LegalEntity participant,
            UUID ihbProgramId,
            HierarchyNode parentHierarchyNode) {

        // 1. Check if IHB Settlement VA already exists
        if (ihbCurrentAccount.getIhbSettlementVaId() != null) {
            Optional<VirtualAccount> existing = virtualAccountRepository.findById(ihbCurrentAccount.getIhbSettlementVaId());
            if (existing.isPresent()) {
                log.debug("IHB Settlement VA already exists: {}", existing.get().getVaNumber());
                return existing.get();
            }
        }

        // 2. Look for existing IHB Settlement VA by naming convention
        String expectedVaNumber = "IHBS-" + participant.getEntityCode() + "-" + ihbCurrentAccount.getCurrencyCode();
        Optional<VirtualAccount> existingByNumber = virtualAccountRepository.findByVaNumber(expectedVaNumber);
        if (existingByNumber.isPresent()) {
            // Link it to the IHB Current Account
            ihbCurrentAccount.setIhbSettlementVaId(existingByNumber.get().getId());
            virtualAccountRepository.save(ihbCurrentAccount);
            log.debug("Found existing IHB Settlement VA: {}", existingByNumber.get().getVaNumber());
            return existingByNumber.get();
        }

        // 3. Create new IHB Settlement VA
        log.info("Creating IHB Settlement VA for {} in {}", participant.getEntityCode(), ihbCurrentAccount.getCurrencyCode());

        String currency = ihbCurrentAccount.getCurrencyCode();
        String vaNumber = expectedVaNumber;
        String vaName = "IHB Settlement - " + participant.getEntityName() + " " + currency;

        VirtualAccount settlementVa = VirtualAccount.builder()
            .vaNumber(vaNumber)
            .vaName(vaName)
            .corporateId(participant.getCorporateId())
            .programId(parentHierarchyNode != null ? parentHierarchyNode.getProgramId() : ihbProgramId)
            .physicalAccountId(ihbCurrentAccount.getPhysicalAccountId())
            .currencyCode(currency)
            .accountCategory(VirtualAccount.AccountCategory.TRANSACTION)
            .accountType(VirtualAccount.AccountType.VIRTUAL)
            .mirrorAccountType(VirtualAccount.MirrorAccountType.IHB_SETTLEMENT)
            .owningEntityId(participant.getId())
            .owningEntityCode(participant.getEntityCode())
            .parentAccountId(ihbCurrentAccount.getParentAccountId())  // Same parent as IHB Current Account
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .status(VirtualAccount.VaStatus.ACTIVE)
            .externalReference("IHB-SETTLEMENT-" + participant.getEntityCode() + "-" + currency)
            .build();

        settlementVa = virtualAccountRepository.save(settlementVa);

        // 4. Create hierarchy node if parent hierarchy exists
        if (parentHierarchyNode != null) {
            String nodeCode = "IHBS-" + participant.getEntityCode() + "-" + currency;
            String materializedPath = parentHierarchyNode.getMaterializedPath() + "/" + nodeCode;
            int newLevel = parentHierarchyNode.getLevelNumber() + 1;

            HierarchyNode node = HierarchyNode.builder()
                .programId(parentHierarchyNode.getProgramId())
                .parentId(parentHierarchyNode.getId())
                .levelNumber(newLevel)
                .nodeCode(nodeCode)
                .nodeName(vaName)
                .nodeType(HierarchyNodeType.VIRTUAL_ACCOUNT)
                .currencyCode(currency)
                .dimensionValue("IHB_SETTLEMENT")
                .materializedPath(materializedPath)
                .isLeaf(true)
                .childCount(0)
                .status("ACTIVE")
                .aggregatedBalance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .virtualAccountId(settlementVa.getId())
                .build();

            node = hierarchyNodeRepository.save(node);

            settlementVa.setHierarchyNodeId(node.getId());
            settlementVa.setHierarchyPath(materializedPath);
            settlementVa.setHierarchyLevel(newLevel);
            settlementVa = virtualAccountRepository.save(settlementVa);
        }

        // 5. Link IHB Settlement VA to the IHB Current Account
        ihbCurrentAccount.setIhbSettlementVaId(settlementVa.getId());
        virtualAccountRepository.save(ihbCurrentAccount);

        log.info("Created IHB Settlement VA: {} for {} (linked to IHB Current Account {})",
            settlementVa.getVaNumber(), participant.getEntityCode(), ihbCurrentAccount.getVaNumber());

        return settlementVa;
    }

    /**
     * Ensure IC Receivable VA exists at Treasury for the subsidiary.
     *
     * IC Receivable VA is Treasury's intercompany receivable that:
     * - Tracks Treasury's financial claim on the subsidiary
     * - Mirrors the subsidiary's IHB Current Account balance (opposite sign)
     * - Enables proper intercompany accounting and reconciliation
     *
     * When subsidiary uses POBO: IC Receivable balance INCREASES (Treasury is owed more)
     * When subsidiary funds IHB: IC Receivable balance DECREASES (Treasury is owed less)
     *
     * @param ihbCurrentAccount The subsidiary's IHB Current Account
     * @param participant The subsidiary legal entity
     * @param treasury The treasury center entity
     * @param treasurySettlementVa Treasury's settlement VA (for linking)
     * @param ihbProgramId The IHB program ID
     * @return The IC Receivable VA at Treasury
     */
    @Transactional
    public VirtualAccount ensureIcReceivableVa(
            VirtualAccount ihbCurrentAccount,
            LegalEntity participant,
            LegalEntity treasury,
            VirtualAccount treasurySettlementVa,
            UUID ihbProgramId) {

        // 1. Check if IC Receivable VA already exists
        if (ihbCurrentAccount.getIcReceivableVaId() != null) {
            Optional<VirtualAccount> existing = virtualAccountRepository.findById(ihbCurrentAccount.getIcReceivableVaId());
            if (existing.isPresent()) {
                log.debug("IC Receivable VA already exists: {}", existing.get().getVaNumber());
                return existing.get();
            }
        }

        String currency = ihbCurrentAccount.getCurrencyCode();

        // 2. Look for existing IC Receivable VA by naming convention
        String expectedVaNumber = "ICR-" + treasury.getEntityCode() + "-" + participant.getEntityCode() + "-" + currency;
        Optional<VirtualAccount> existingByNumber = virtualAccountRepository.findByVaNumber(expectedVaNumber);
        if (existingByNumber.isPresent()) {
            // Link it to the IHB Current Account
            ihbCurrentAccount.setIcReceivableVaId(existingByNumber.get().getId());
            virtualAccountRepository.save(ihbCurrentAccount);
            log.debug("Found existing IC Receivable VA: {}", existingByNumber.get().getVaNumber());
            return existingByNumber.get();
        }

        // 3. Create new IC Receivable VA at Treasury
        log.info("Creating IC Receivable VA at {} for subsidiary {} in {}",
            treasury.getEntityCode(), participant.getEntityCode(), currency);

        String vaNumber = expectedVaNumber;
        String vaName = "IC Receivable - " + participant.getEntityName() + " " + currency;

        // Get Treasury's hierarchy node for placement
        HierarchyNode treasuryHierarchyNode = null;
        if (treasurySettlementVa.getHierarchyNodeId() != null) {
            treasuryHierarchyNode = hierarchyNodeRepository.findById(treasurySettlementVa.getHierarchyNodeId()).orElse(null);
        }

        VirtualAccount icReceivableVa = VirtualAccount.builder()
            .vaNumber(vaNumber)
            .vaName(vaName)
            .corporateId(treasury.getCorporateId())
            .programId(treasuryHierarchyNode != null ? treasuryHierarchyNode.getProgramId() : ihbProgramId)
            .physicalAccountId(treasurySettlementVa.getPhysicalAccountId())
            .currencyCode(currency)
            .accountCategory(VirtualAccount.AccountCategory.INTERCOMPANY)
            .accountType(VirtualAccount.AccountType.VIRTUAL)
            .mirrorAccountType(VirtualAccount.MirrorAccountType.IC_RECEIVABLE)
            .mirrorsVaId(ihbCurrentAccount.getId())  // Points to the subsidiary's IHB Current Account
            .owningEntityId(treasury.getId())
            .owningEntityCode(treasury.getEntityCode())
            .parentAccountId(treasurySettlementVa.getId())  // Child of Treasury Settlement VA
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .status(VirtualAccount.VaStatus.ACTIVE)
            .externalReference("IC-RECEIVABLE-" + participant.getEntityCode() + "-" + currency)
            .build();

        icReceivableVa = virtualAccountRepository.save(icReceivableVa);

        // 4. Create hierarchy node under Treasury if hierarchy exists
        if (treasuryHierarchyNode != null) {
            String nodeCode = "ICR-" + participant.getEntityCode() + "-" + currency;
            String materializedPath = treasuryHierarchyNode.getMaterializedPath() + "/" + nodeCode;
            int newLevel = treasuryHierarchyNode.getLevelNumber() + 1;

            HierarchyNode node = HierarchyNode.builder()
                .programId(treasuryHierarchyNode.getProgramId())
                .parentId(treasuryHierarchyNode.getId())
                .levelNumber(newLevel)
                .nodeCode(nodeCode)
                .nodeName(vaName)
                .nodeType(HierarchyNodeType.VIRTUAL_ACCOUNT)
                .currencyCode(currency)
                .dimensionValue("IC_RECEIVABLE")
                .materializedPath(materializedPath)
                .isLeaf(true)
                .childCount(0)
                .status("ACTIVE")
                .aggregatedBalance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .virtualAccountId(icReceivableVa.getId())
                .build();

            node = hierarchyNodeRepository.save(node);

            icReceivableVa.setHierarchyNodeId(node.getId());
            icReceivableVa.setHierarchyPath(materializedPath);
            icReceivableVa.setHierarchyLevel(newLevel);
            icReceivableVa = virtualAccountRepository.save(icReceivableVa);
        }

        // 5. Link IC Receivable VA to the IHB Current Account
        ihbCurrentAccount.setIcReceivableVaId(icReceivableVa.getId());
        virtualAccountRepository.save(ihbCurrentAccount);

        log.info("Created IC Receivable VA: {} at Treasury {} for subsidiary {} (linked to IHB Current Account {})",
            icReceivableVa.getVaNumber(), treasury.getEntityCode(), participant.getEntityCode(), ihbCurrentAccount.getVaNumber());

        return icReceivableVa;
    }

    /**
     * Ensure Treasury has a Settlement VA for IHB operations.
     *
     * This is the counterparty account for all IHB transactions:
     * - Deposits: Subsidiary → Treasury Settlement VA
     * - Loans: Treasury Settlement VA → Subsidiary
     * - Interest Settlement: Posted via Treasury Settlement VA
     *
     * Unlike getOrCreateTreasurySettlementVa, this method:
     * 1. Uses the provided parent hierarchy information (not TSETT- orphan)
     * 2. Creates Settlement VA as sibling of other Treasury VAs
     * 3. Properly links to hierarchy for display in Treasury Structure
     *
     * @param treasury The treasury center entity
     * @param currency The currency code
     * @param ihbProgramId The IHB program ID
     * @param parentVa The parent aggregation VA (Treasury's hierarchy root)
     * @param parentHierarchyNode The parent hierarchy node (optional, for linking)
     * @return Treasury's Settlement VA for the specified currency
     */
    private VirtualAccount ensureTreasurySettlementVa(
            LegalEntity treasury,
            String currency,
            UUID ihbProgramId,
            VirtualAccount parentVa,
            HierarchyNode parentHierarchyNode) {

        // 1. Check if treasury already has a Settlement VA for this currency
        if (treasury.getSettlementVaId() != null) {
            Optional<VirtualAccount> configuredVa = virtualAccountRepository.findById(treasury.getSettlementVaId());
            if (configuredVa.isPresent() && configuredVa.get().getCurrencyCode().equals(currency)) {
                log.debug("Treasury {} already has Settlement VA: {}", treasury.getEntityCode(), configuredVa.get().getVaNumber());
                return configuredVa.get();
            }
        }

        // 2. Look for existing Settlement/Transaction VA owned by Treasury for this currency
        List<VirtualAccount> treasuryVas = virtualAccountRepository.findByOwningEntityIdAndCurrencyCode(
            treasury.getId(), currency);

        // Prefer existing Settlement VA (TRANSACTION category, owned by treasury, used for settlement)
        Optional<VirtualAccount> existingSettlement = treasuryVas.stream()
            .filter(va -> va.getAccountCategory() == VirtualAccount.AccountCategory.TRANSACTION
                       && va.getVaName() != null && va.getVaName().contains("Settlement"))
            .findFirst();

        if (existingSettlement.isPresent()) {
            VirtualAccount settlement = existingSettlement.get();
            log.debug("Found existing Treasury Settlement VA: {}", settlement.getVaNumber());
            // Update treasury's settlementVaId if not set
            if (treasury.getSettlementVaId() == null) {
                treasury.setSettlementVaId(settlement.getId());
                legalEntityRepository.save(treasury);
            }
            return settlement;
        }

        // 3. If parentVa IS the Treasury's VA, use it as the settlement target
        if (parentVa != null && treasury.getId().equals(parentVa.getOwningEntityId())) {
            log.debug("Using parent VA {} as Treasury Settlement VA", parentVa.getVaNumber());
            if (treasury.getSettlementVaId() == null) {
                treasury.setSettlementVaId(parentVa.getId());
                legalEntityRepository.save(treasury);
            }
            return parentVa;
        }

        // 4. No existing Settlement VA - create one under Treasury's hierarchy
        log.info("Creating Treasury Settlement VA for {} in {} under hierarchy",
            treasury.getEntityCode(), currency);

        String vaNumber = "SETT-" + treasury.getEntityCode() + "-" + currency;
        String vaName = "Settlement - " + treasury.getEntityName() + " " + currency;

        VirtualAccount settlementVa = VirtualAccount.builder()
            .vaNumber(vaNumber)
            .vaName(vaName)
            .corporateId(treasury.getCorporateId())
            .programId(parentHierarchyNode != null ? parentHierarchyNode.getProgramId() : ihbProgramId)
            .currencyCode(currency)
            .accountCategory(VirtualAccount.AccountCategory.TRANSACTION)  // Real transactions happen
            .accountType(VirtualAccount.AccountType.VIRTUAL)
            .owningEntityId(treasury.getId())
            .owningEntityCode(treasury.getEntityCode())
            .parentAccountId(parentVa != null ? parentVa.getId() : null)  // Sibling of IHB accounts
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .status(VirtualAccount.VaStatus.ACTIVE)
            .externalReference("TREASURY-SETTLEMENT-" + currency)
            .build();

        settlementVa = virtualAccountRepository.save(settlementVa);

        // 5. Create hierarchy node if parent hierarchy exists
        if (parentHierarchyNode != null) {
            String nodeCode = "SETT-" + treasury.getEntityCode() + "-" + currency;
            String materializedPath = parentHierarchyNode.getMaterializedPath() + "/" + nodeCode;
            int newLevel = parentHierarchyNode.getLevelNumber() + 1;

            HierarchyNode node = HierarchyNode.builder()
                .programId(parentHierarchyNode.getProgramId())
                .parentId(parentHierarchyNode.getId())
                .levelNumber(newLevel)
                .nodeCode(nodeCode)
                .nodeName(vaName)
                .nodeType(HierarchyNodeType.VIRTUAL_ACCOUNT)
                .currencyCode(currency)
                .dimensionValue("SETTLEMENT")
                .materializedPath(materializedPath)
                .isLeaf(true)
                .childCount(0)
                .status("ACTIVE")
                .aggregatedBalance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .virtualAccountId(settlementVa.getId())
                .build();

            node = hierarchyNodeRepository.save(node);

            settlementVa.setHierarchyNodeId(node.getId());
            settlementVa.setHierarchyPath(materializedPath);
            settlementVa.setHierarchyLevel(newLevel);
            settlementVa = virtualAccountRepository.save(settlementVa);

            log.info("Created Treasury Settlement VA {} with hierarchy node at {}",
                settlementVa.getVaNumber(), materializedPath);
        }

        // 6. Update treasury's settlementVaId
        treasury.setSettlementVaId(settlementVa.getId());
        legalEntityRepository.save(treasury);

        log.info("Created Treasury Settlement VA: {} for {} (category=TRANSACTION)",
            settlementVa.getVaNumber(), treasury.getEntityCode());

        return settlementVa;
    }

    /**
     * Map sweep frequency string to SweepRule.SweepFrequency enum.
     */
    private SweepRule.SweepFrequency mapSweepFrequency(String frequency) {
        if (frequency == null) return SweepRule.SweepFrequency.DAILY;
        try {
            return SweepRule.SweepFrequency.valueOf(frequency.toUpperCase());
        } catch (IllegalArgumentException e) {
            return SweepRule.SweepFrequency.DAILY;
        }
    }

    /**
     * Get or create Treasury Settlement VA for IHB operations.
     *
     * Strategy:
     * 1. If treasury entity has settlementVaId set, use that VA
     * 2. Look for existing IHB participant VA for treasury in this currency
     * 3. Look for any active TRANSACTION VA for treasury in this currency
     * 4. Create new Settlement VA if none found
     *
     * The Settlement VA serves as:
     * - Parent account for IHB Current Accounts (via parentAccountId)
     * - Target for sweep rules (receives swept funds)
     * - Source/target for loan disbursements and repayments
     * - Source/target for deposit placements and withdrawals
     *
     * IMPORTANT: This is a TRANSACTION VA, not AGGREGATION because:
     * - Real fund movements happen (loans, deposits, sweeps)
     * - Balance is real from actual transactions, not computed from children
     * - Only structural-only VAs (ROOT) should be AGGREGATION
     *
     * For multi-level treasury (Regional → Global), regional treasury's Settlement VA
     * should have parentAccountId pointing to global treasury's Settlement VA.
     *
     * @param treasury The treasury center entity
     * @param currency The currency code
     * @param ihbProgramId The IHB program ID for this settlement VA
     * @return Treasury's Settlement VA for the specified currency
     */
    private VirtualAccount getOrCreateTreasurySettlementVa(LegalEntity treasury, String currency, UUID ihbProgramId) {
        // 1. Check if treasury has a configured settlement VA in this currency
        if (treasury.getSettlementVaId() != null) {
            Optional<VirtualAccount> configuredVa = virtualAccountRepository.findById(treasury.getSettlementVaId());
            if (configuredVa.isPresent() && configuredVa.get().getCurrencyCode().equals(currency)) {
                log.debug("Using treasury's configured settlement VA: {}", configuredVa.get().getVaNumber());
                return configuredVa.get();
            }
        }

        // 2. Look for existing IHB-enabled VA for treasury in this currency
        Optional<VirtualAccount> ihbVa = virtualAccountRepository
            .findByOwningEntityIdAndIhbParticipantTrueAndCurrencyCode(treasury.getId(), currency);
        if (ihbVa.isPresent()) {
            log.debug("Using treasury's existing IHB VA: {}", ihbVa.get().getVaNumber());
            return ihbVa.get();
        }

        // 3. Look for any active TRANSACTION VA for treasury in this currency
        List<VirtualAccount> existingVas = virtualAccountRepository
            .findByOwningEntityIdAndCurrencyCodeAndAccountCategory(
                treasury.getId(), currency, VirtualAccount.AccountCategory.TRANSACTION);
        if (!existingVas.isEmpty()) {
            VirtualAccount existing = existingVas.get(0);
            log.debug("Using treasury's existing TRANSACTION VA: {}", existing.getVaNumber());
            // Update entity's settlementVaId if not set
            if (treasury.getSettlementVaId() == null) {
                treasury.setSettlementVaId(existing.getId());
                legalEntityRepository.save(treasury);
            }
            return existing;
        }

        // 4. Create new Settlement VA for treasury
        log.info("Creating Treasury Settlement VA for {} in {}", treasury.getEntityCode(), currency);

        UUID physicalAccountId = resolvePhysicalAccountForTreasury(treasury, currency);

        // Resolve parent VA for multi-level treasury hierarchy
        UUID parentVaId = resolveParentTreasuryVa(treasury, currency);

        // NOTE: Treasury Settlement VA is TRANSACTION category, NOT AGGREGATION because:
        // - Real fund movements happen: loan disbursements, repayments, deposits, withdrawals
        // - It's the target for sweep rules (receives swept funds)
        // - Balance is real, not computed from children
        // - Only structural-only VAs (ROOT) should be AGGREGATION
        VirtualAccount settlementVa = VirtualAccount.builder()
            .vaNumber("TSETT-" + treasury.getEntityCode() + "-" + currency)
            .vaName("Treasury Settlement - " + treasury.getEntityName() + " " + currency)
            .corporateId(treasury.getCorporateId())
            .programId(ihbProgramId)  // Belongs to IHB program
            .physicalAccountId(physicalAccountId)
            .currencyCode(currency)
            .accountCategory(VirtualAccount.AccountCategory.TRANSACTION)  // Real transactions happen
            .accountType(VirtualAccount.AccountType.VIRTUAL)
            .owningEntityId(treasury.getId())
            .owningEntityCode(treasury.getEntityCode())
            .parentAccountId(parentVaId)  // Link to parent treasury if exists
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .status(VirtualAccount.VaStatus.ACTIVE)
            .externalReference("TREASURY-SETTLEMENT-" + currency)
            .build();

        VirtualAccount saved = virtualAccountRepository.save(settlementVa);

        // Update treasury's settlementVaId
        treasury.setSettlementVaId(saved.getId());
        legalEntityRepository.save(treasury);

        log.info("Created Treasury Settlement VA: {} for {} (category=AGGREGATION)",
            saved.getVaNumber(), treasury.getEntityCode());

        return saved;
    }

    /**
     * Resolve parent treasury VA for multi-level treasury hierarchy.
     *
     * If this treasury has a parent entity that is also a treasury center,
     * return that parent treasury's settlement VA.
     *
     * Example:
     * - EMEA Treasury (regional) → parent = Global Treasury
     * - EMEA's Settlement VA.parentAccountId = Global's Settlement VA
     *
     * @param treasury The treasury center entity
     * @param currency The currency code
     * @return Parent treasury's settlement VA ID, or null if this is top-level treasury
     */
    private UUID resolveParentTreasuryVa(LegalEntity treasury, String currency) {
        // Check if treasury has a parent entity
        if (treasury.getParentEntityId() == null) {
            return null;  // Top-level treasury
        }

        // Find parent entity
        Optional<LegalEntity> parentOpt = legalEntityRepository.findById(treasury.getParentEntityId());
        if (parentOpt.isEmpty()) {
            return null;
        }

        LegalEntity parent = parentOpt.get();

        // Check if parent is also a treasury center
        if (!parent.canLend()) {
            return null;  // Parent is not a treasury
        }

        // Find parent treasury's settlement VA in this currency
        // Note: Recursive call, but limited by hierarchy depth
        if (parent.getSettlementVaId() != null) {
            Optional<VirtualAccount> parentVa = virtualAccountRepository.findById(parent.getSettlementVaId());
            if (parentVa.isPresent() && parentVa.get().getCurrencyCode().equals(currency)) {
                log.debug("Found parent treasury VA: {} for {}", parentVa.get().getVaNumber(), treasury.getEntityCode());
                return parentVa.get().getId();
            }
        }

        // Look for parent's VA in this currency
        List<VirtualAccount> parentVas = virtualAccountRepository
            .findByOwningEntityIdAndCurrencyCodeAndAccountCategory(
                parent.getId(), currency, VirtualAccount.AccountCategory.AGGREGATION);
        if (!parentVas.isEmpty()) {
            return parentVas.get(0).getId();
        }

        // Also check TRANSACTION category
        parentVas = virtualAccountRepository
            .findByOwningEntityIdAndCurrencyCodeAndAccountCategory(
                parent.getId(), currency, VirtualAccount.AccountCategory.TRANSACTION);
        if (!parentVas.isEmpty()) {
            return parentVas.get(0).getId();
        }

        return null;
    }

    /**
     * Resolve IHB Program for the corporate.
     *
     * IHB is a Program Type - all IHB Current Accounts MUST belong to an IHB program.
     *
     * Logic:
     * 1. If programId is provided, validate it is an active IHB program for this corporate
     * 2. If not provided, auto-resolve the corporate's active IHB program
     * 3. Throw error if no IHB program exists
     *
     * @param corporateId The corporate ID
     * @param requestedProgramId Optional program ID from request
     * @return Resolved IHB program ID (never null)
     */
    private UUID resolveIhbProgram(UUID corporateId, UUID requestedProgramId) {
        if (requestedProgramId != null) {
            // Validate the provided program is an active IHB program for this corporate
            Program program = programRepository.findById(requestedProgramId)
                .orElseThrow(() -> new ResourceNotFoundException(
                    "Program not found: " + requestedProgramId));

            if (!program.getCorporateId().equals(corporateId)) {
                throw new BusinessException("Program " + program.getProgramCode() +
                    " does not belong to this corporate");
            }

            if (program.getProgramType() != Program.ProgramType.IHB) {
                throw new BusinessException("Program " + program.getProgramCode() +
                    " is not an IHB program (type: " + program.getProgramType() + "). " +
                    "IHB Current Accounts must belong to an IHB program.");
            }

            if (program.getStatus() != Program.ProgramStatus.ACTIVE) {
                throw new BusinessException("Program " + program.getProgramCode() +
                    " is not active (status: " + program.getStatus() + ")");
            }

            log.debug("Using provided IHB program: {} ({})", program.getProgramCode(), program.getId());
            return program.getId();
        }

        // Auto-resolve: Find the corporate's active IHB program
        List<Program> ihbPrograms = programRepository.findActiveIhbProgramsByCorporate(corporateId);

        if (ihbPrograms.isEmpty()) {
            throw new BusinessException(
                "No active IHB program found for corporate " + corporateId + ". " +
                "Please create an IHB program first, or provide a programId.");
        }

        // Use the first (oldest) IHB program if multiple exist
        Program ihbProgram = ihbPrograms.get(0);
        if (ihbPrograms.size() > 1) {
            log.warn("Corporate {} has {} IHB programs. Using first: {} ({})",
                corporateId, ihbPrograms.size(), ihbProgram.getProgramCode(), ihbProgram.getId());
        }

        log.info("Auto-resolved IHB program for corporate {}: {} ({})",
            corporateId, ihbProgram.getProgramCode(), ihbProgram.getId());
        return ihbProgram.getId();
    }

    /**
     * Resolve physical account for treasury center.
     */
    private UUID resolvePhysicalAccountForTreasury(LegalEntity treasury, String currency) {
        // Try to find physical account for treasury in this currency
        return physicalAccountRepository
            .findWithFilters(treasury.getCorporateId(), null, currency,
                PhysicalAccount.AccountStatus.ACTIVE, null, null)
            .stream()
            .findFirst()
            .map(PhysicalAccount::getId)
            .orElseThrow(() -> new BusinessException(
                "No active physical account found for corporate " +
                treasury.getCorporateId() + " in currency " + currency));
    }

    /**
     * Get or create Interest Configuration for IHB participant.
     */
    private InterestConfiguration getOrCreateIhbInterestConfig(
            LegalEntity participant,
            LegalEntity treasury,
            VirtualAccountDto.IhbCurrentAccountRequest request) {

        // Check if participant has specific config
        Optional<InterestConfiguration> participantConfig = interestConfigRepository
            .findByTargetIdAndCurrencyCodeAndConfigType(
                participant.getId(),
                request.getCurrencyCode(),
                InterestConfiguration.ConfigType.INTERNAL);

        if (participantConfig.isPresent()) {
            return participantConfig.get();
        }

        // Fall back to Treasury's default IHB config
        if (treasury.getIhbInterestConfigId() != null) {
            Optional<InterestConfiguration> treasuryConfig = interestConfigRepository
                .findById(treasury.getIhbInterestConfigId());
            if (treasuryConfig.isPresent()) {
                return treasuryConfig.get();
            }
        }

        // Create default config
        BigDecimal creditRate = request.getCreditRate() != null
            ? request.getCreditRate() : new BigDecimal("2.50");
        BigDecimal debitRate = request.getDebitRate() != null
            ? request.getDebitRate() : new BigDecimal("5.00");
        BigDecimal penaltyRate = request.getPenaltyRate() != null
            ? request.getPenaltyRate() : new BigDecimal("7.00");

        InterestConfiguration config = InterestConfiguration.builder()
            .corporateId(participant.getCorporateId())
            .configName("IHB Rates - " + participant.getEntityCode())
            .configType(InterestConfiguration.ConfigType.INTERNAL)
            .targetId(participant.getId())
            .targetType(InterestConfiguration.TargetType.LEGAL_ENTITY)
            .currencyCode(request.getCurrencyCode())
            .effectiveCreditRate(creditRate)
            .effectiveDebitRate(debitRate)
            .penaltyRate(penaltyRate)
            .dayCountConvention("ACT/360")
            .compoundingFrequency(InterestConfiguration.CompoundingFrequency.DAILY)
            .postingFrequency(InterestConfiguration.PostingFrequency.MONTHLY)
            .status(InterestConfiguration.ConfigStatus.ACTIVE)
            .effectiveFrom(LocalDate.now())
            .build();

        return interestConfigRepository.save(config);
    }

    /**
     * Generate IHB current account number.
     */
    private String generateIhbCurrentAccountNumber(LegalEntity entity, String currency) {
        long sequence = virtualAccountRepository.countByAccountCategory(
            VirtualAccount.AccountCategory.INTERCOMPANY) + 1;
        return String.format("IHB-%s-%s-%04d", entity.getEntityCode(), currency, sequence);
    }

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

        // Value Type (for loyalty programs)
        if (program.getProgramType() == Program.ProgramType.LOYALTY) {
            builder.valueType(VirtualAccount.ValueType.POINTS);
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

    @Transactional
    public VirtualAccount credit(UUID id, BigDecimal amount) {
        VirtualAccount va = getById(id);
        va.credit(amount);
        return virtualAccountRepository.save(va);
    }

    @Transactional
    public VirtualAccount debit(UUID id, BigDecimal amount) {
        VirtualAccount va = getById(id);
        
        if (!va.hasSufficientBalance(amount)) {
            throw new BusinessException("Insufficient balance. Available: " + va.getAvailableBalance());
        }
        
        va.debit(amount);
        va.recordSpending(amount);
        return virtualAccountRepository.save(va);
    }

    // ========================================================================
    // PROGRAM TYPE CONFIGURATION (existing - unchanged)
    // ========================================================================

    public ProgramTypeConfigDto getProgramTypeConfig(String programType) {
        return ProgramTypeConfigDto.forType(programType);
    }

    public List<ProgramTypeConfigDto> getAllProgramTypeConfigs() {
        return ProgramTypeConfigDto.getAllConfigs();
    }

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
        String programType = null;
        if (va.getProgramId() != null) {
            Optional<Program> program = programRepository.findById(va.getProgramId());
            if (program.isPresent()) {
                programName = program.get().getProgramName();
                programType = program.get().getProgramType().name();
            }
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
                .programType(programType)
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