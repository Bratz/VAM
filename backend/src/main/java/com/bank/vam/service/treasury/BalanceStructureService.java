package com.bank.vam.service.treasury;

import com.bank.vam.dto.treasury.BalanceStructureDto;
import com.bank.vam.dto.treasury.BalanceStructureDto.*;
import com.bank.vam.entity.Corporate;
import com.bank.vam.entity.PhysicalAccount;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.treasury.*;
import com.bank.vam.repository.*;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.treasury.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceStructureService {

    private final VirtualAccountRepository virtualAccountRepository;
    private final PhysicalAccountRepository physicalAccountRepository;
    private final CorporateRepository corporateRepository;
    private final IhbLoanRepository ihbLoanRepository;
    private final IhbEntityRepository ihbEntityRepository;
    private final NotionalPoolRepository notionalPoolRepository;
    private final PoolMemberRepository poolMemberRepository;
    private final SweepRuleRepository sweepRuleRepository;
    private final NettingEntryRepository nettingEntryRepository;
    // ENHANCED: For entity name lookup
    private final com.bank.vam.repository.hierarchy.LegalEntityRepository legalEntityRepository;
    // MP3: FX conversions go through FxRateService (cached, multi-hop, with fallbacks).
    private final FxRateService fxRateService;
    // MP3: active market profile drives the default base currency for roll-ups.
    private final com.bank.vam.config.MarketProfileProperties marketProfile;

    /**
     * Build complete hierarchy tree for a corporate (backward compatible)
     */
    @Transactional(readOnly = true)
    public HierarchyNode getHierarchy(UUID corporateId, String reportingCurrency) {
        return getHierarchy(corporateId, null, reportingCurrency);
    }

    /**
     * Build complete hierarchy tree for a corporate, optionally filtered by program
     */
    @Transactional(readOnly = true)
    public HierarchyNode getHierarchy(UUID corporateId, UUID programId, String reportingCurrency) {
        log.info("Building balance structure hierarchy for corporate: {}, programId: {}, reporting currency: {}",
                 corporateId, programId, reportingCurrency);

        // Get corporate info
        Corporate corporate = corporateRepository.findById(corporateId)
            .orElseGet(() -> createDefaultCorporate(corporateId));

        // Get virtual accounts - filter by programId if provided
        List<VirtualAccount> accounts;
        if (programId != null) {
            accounts = virtualAccountRepository.findByProgramId(programId);
            log.info("Found {} virtual accounts for programId: {}", accounts.size(), programId);
        } else {
            accounts = virtualAccountRepository.findByCorporateId(corporateId);
            log.info("Found {} virtual accounts for corporateId: {}", accounts.size(), corporateId);
        }

        if (accounts.isEmpty()) {
            // Return empty hierarchy instead of demo data
            log.info("No virtual accounts found, returning empty hierarchy");
            return HierarchyNode.builder()
                .id(corporateId.toString())
                .name(corporate.getLegalName())
                .type(NodeType.GROUP)
                .specialType(SpecialVaType.REGULAR)
                .accountCategory(AccountCategory.ROOT)
                .level(0)
                .currencyCode(reportingCurrency)
                .localBalance(BigDecimal.ZERO)
                .consolidatedBalance(BigDecimal.ZERO)
                .intercompanyReceivable(BigDecimal.ZERO)
                .intercompanyPayable(BigDecimal.ZERO)
                .netPosition(BigDecimal.ZERO)
                .participatesInPooling(false)
                .participatesInNetting(false)
                .participatesInSweep(false)
                .children(Collections.emptyList())
                .build();
        }

        // Get participation data
        Map<UUID, PoolMember> poolMemberMap = getPoolMemberMap(corporateId);
        Map<UUID, SweepRule> sweepRuleMap = getSweepRuleMap(corporateId);
        Map<UUID, List<NettingEntry>> nettingMap = getNettingEntryMap(corporateId);
        Map<String, IntercompanyPositions> icPositions = getIntercompanyPositions(corporateId);

        // Build hierarchy
        return buildHierarchyFromAccounts(corporate, accounts, poolMemberMap, sweepRuleMap,
                                          nettingMap, icPositions, reportingCurrency);
    }

    /**
     * Get summary statistics (backward compatible - corporate level)
     */
    @Transactional(readOnly = true)
    public BalanceSummary getSummary(UUID corporateId, String reportingCurrency) {
        return getSummary(corporateId, null, reportingCurrency);
    }

    /**
     * Get summary statistics, optionally filtered by program
     */
    @Transactional(readOnly = true)
    public BalanceSummary getSummary(UUID corporateId, UUID programId, String reportingCurrency) {
        HierarchyNode hierarchy = getHierarchy(corporateId, programId, reportingCurrency);
        
        // Calculate stats from hierarchy
        SummaryStats stats = calculateStats(hierarchy);
        
        return BalanceSummary.builder()
            .consolidatedBalance(hierarchy.getConsolidatedBalance())
            .netPosition(hierarchy.getNetPosition())
            .totalIntercompanyReceivable(hierarchy.getIntercompanyReceivable())
            .totalIntercompanyPayable(hierarchy.getIntercompanyPayable())
            .netIntercompanyPosition(hierarchy.getIntercompanyReceivable()
                .subtract(hierarchy.getIntercompanyPayable()))
            .poolRate(hierarchy.getInterestRate() != null ? hierarchy.getInterestRate() : new BigDecimal("3.75"))
            .monthlyInterestAllocation(hierarchy.getInterestAllocation() != null ? 
                hierarchy.getInterestAllocation() : BigDecimal.ZERO)
            .reportingCurrency(reportingCurrency)
            .totalEntities(stats.entityCount)
            .totalVirtualAccounts(stats.vaCount)
            .poolParticipants(stats.poolParticipants)
            .sweepParticipants(stats.sweepParticipants)
            .nettingParticipants(stats.nettingParticipants)
            // NEW: Special VA counts
            .settlementVaCount(stats.settlementVaCount)
            .exceptionVaCount(stats.exceptionVaCount)
            .currencyMirrorCount(stats.currencyMirrorCount)
            .currencies(stats.currencies)
            .build();
    }

    /**
     * Get physical (real) bank account info
     */
    @Transactional(readOnly = true)
    public PhysicalAccountInfo getPhysicalAccount(UUID corporateId) {
        // Use pageable query and get first result
        Page<PhysicalAccount> accountsPage = physicalAccountRepository.findByCorporateId(
            corporateId, PageRequest.of(0, 1));
        List<PhysicalAccount> accounts = accountsPage.getContent();
        
        if (accounts.isEmpty()) {
            // Return empty info instead of demo data
            return PhysicalAccountInfo.builder()
                .id(null)
                .bankName("Not Configured")
                .bankBic("")
                .accountNumber("")
                .iban("")
                .accountName("No physical account linked")
                .balance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .currency("AED")
                .accountType("CURRENT")
                .status("NOT_CONFIGURED")
                .build();
        }
        
        PhysicalAccount pa = accounts.get(0);
        // PhysicalAccount entity doesn't have bankName/bankBic - derive from branch or use defaults
        String bankName = deriveBankName(pa.getBranchCode());
        String bankBic = deriveBankBic(pa.getBranchCode());
        
        return PhysicalAccountInfo.builder()
            .id(pa.getId())
            .bankName(bankName)
            .bankBic(bankBic)
            .accountNumber(pa.getAccountNumber())
            .iban(pa.getIban())
            .accountName(pa.getAccountName())
            .balance(pa.getCurrentBalance())
            .availableBalance(pa.getAvailableBalance())
            .currency(pa.getCurrencyCode())
            .accountType(pa.getAccountType() != null ? pa.getAccountType().name() : "CURRENT")
            .status(pa.getStatus() != null ? pa.getStatus().name() : "ACTIVE")
            .build();
    }
    
    /**
     * Derive bank name from branch code (placeholder - in production, lookup from reference data)
     */
    private String deriveBankName(String branchCode) {
        if (branchCode == null) return "Emirates NBD";
        // Map branch codes to bank names
        return switch (branchCode.substring(0, Math.min(3, branchCode.length()))) {
            case "ENB" -> "Emirates NBD";
            case "FAB" -> "First Abu Dhabi Bank";
            case "DIB" -> "Dubai Islamic Bank";
            case "ADC" -> "Abu Dhabi Commercial Bank";
            default -> "Emirates NBD";
        };
    }
    
    /**
     * Derive bank BIC from branch code (placeholder - in production, lookup from reference data)
     */
    private String deriveBankBic(String branchCode) {
        if (branchCode == null) return "EABOROAEDXXX";
        return switch (branchCode.substring(0, Math.min(3, branchCode.length()))) {
            case "ENB" -> "EABOROAEDXXX";
            case "FAB" -> "FABIAEDIXXXX";
            case "DIB" -> "DUIBAEDIXXXX";
            case "ADC" -> "ADCBAEDIXXXX";
            default -> "EABOROAEDXXX";
        };
    }

    /**
     * Get detailed info for a specific node
     */
    @Transactional(readOnly = true)
    public NodeDetail getNodeDetail(String nodeId, String reportingCurrency) {
        // Try to find as virtual account first
        try {
            UUID vaId = UUID.fromString(nodeId);
            Optional<VirtualAccount> vaOpt = virtualAccountRepository.findById(vaId);
            if (vaOpt.isPresent()) {
                return buildNodeDetailFromVA(vaOpt.get(), reportingCurrency);
            }
        } catch (IllegalArgumentException e) {
            // Not a UUID, might be a synthetic node ID
            log.warn("Invalid node ID format (not a UUID): {}", nodeId);
        }
        
        // Return empty node detail instead of demo data
        return NodeDetail.builder()
            .id(nodeId)
            .name("Node not found")
            .accountNumber("")
            .type(NodeType.VIRTUAL_ACCOUNT)
            .specialType(SpecialVaType.REGULAR)
            .accountCategory(AccountCategory.TRANSACTION)
            .currencyCode("AED")
            .localBalance(BigDecimal.ZERO)
            .consolidatedBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .holdAmount(BigDecimal.ZERO)
            .intercompanyReceivable(BigDecimal.ZERO)
            .intercompanyPayable(BigDecimal.ZERO)
            .netIntercompanyPosition(BigDecimal.ZERO)
            .participatesInPooling(false)
            .participatesInNetting(false)
            .participatesInSweep(false)
            .build();
    }

    /**
     * Update participation flags for a node
     */
    @Transactional
    public NodeDetail updateParticipation(String nodeId, UpdateParticipationRequest request) {
        log.info("Updating participation for node: {}", nodeId);
        
        try {
            UUID vaId = UUID.fromString(nodeId);
            VirtualAccount va = virtualAccountRepository.findById(vaId)
                .orElseThrow(() -> new RuntimeException("Virtual account not found: " + nodeId));
            
            // Update pool membership
            if (request.getParticipatesInPooling() != null) {
                updatePoolMembership(va, request.getParticipatesInPooling(), request.getPoolReference());
            }
            
            // Update sweep participation
            if (request.getParticipatesInSweep() != null) {
                updateSweepParticipation(va, request.getParticipatesInSweep(), 
                                         request.getSweepRuleReference(), request.getSweepTarget());
            }
            
            return getNodeDetail(nodeId, "AED");
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid node ID: " + nodeId);
        }
    }

    // ========================================================================
    // SPECIAL TYPE / ACCOUNT CATEGORY MAPPING METHODS
    // ========================================================================

    /**
     * Map VirtualAccount.VaSpecialType to DTO SpecialVaType
     *
     * FIX v5.5.2: Check isCurrencyMirror() FIRST because VaSpecialType enum
     * doesn't have CURRENCY_MIRROR - it only exists in AccountCategory.
     */
    private SpecialVaType mapSpecialType(VirtualAccount va) {
        // CRITICAL: Check Currency Mirror FIRST - VaSpecialType doesn't have CURRENCY_MIRROR
        if (va.isCurrencyMirror()) {
            return SpecialVaType.CURRENCY_MIRROR;
        }

        if (va.getSpecialType() == null) {
            // Infer from accountCategory if specialType not set
            if (va.getAccountCategory() != null) {
                return switch (va.getAccountCategory()) {
                    case SETTLEMENT -> SpecialVaType.SETTLEMENT;
                    case EXCEPTION -> SpecialVaType.EXCEPTION;
                    default -> SpecialVaType.REGULAR;
                };
            }
            return SpecialVaType.REGULAR;
        }
        return switch (va.getSpecialType()) {
            case SETTLEMENT -> SpecialVaType.SETTLEMENT;
            case EXCEPTION -> SpecialVaType.EXCEPTION;
            default -> SpecialVaType.REGULAR;
        };
    }

    /**
     * Map VirtualAccount.AccountCategory to DTO AccountCategory
     */
    private AccountCategory mapAccountCategory(VirtualAccount va) {
        if (va.getAccountCategory() == null) {
            // Infer from specialType if category not set
            if (va.getSpecialType() != null) {
                return switch (va.getSpecialType()) {
                    case SETTLEMENT -> AccountCategory.SETTLEMENT;
                    case EXCEPTION -> AccountCategory.EXCEPTION;
                    default -> AccountCategory.TRANSACTION;
                };
            }
            return AccountCategory.TRANSACTION;
        }
        return switch (va.getAccountCategory()) {
            case ROOT -> AccountCategory.ROOT;
            case AGGREGATION -> AccountCategory.AGGREGATION;
            case CURRENCY_MIRROR -> AccountCategory.CURRENCY_MIRROR;
            case PHYSICAL_MIRROR -> AccountCategory.PHYSICAL_MIRROR;
            case SETTLEMENT -> AccountCategory.SETTLEMENT;
            case EXCEPTION -> AccountCategory.EXCEPTION;
            case COLLECTION -> AccountCategory.COLLECTION;
            case DISBURSEMENT -> AccountCategory.DISBURSEMENT;
            case INTERCOMPANY -> AccountCategory.INTERCOMPANY;
            default -> AccountCategory.TRANSACTION;
        };
    }

    /**
     * Map to appropriate NodeType based on account category
     */
    private NodeType mapNodeType(VirtualAccount va, AccountCategory category) {
        // Special types get specific node types
        if (category == AccountCategory.PHYSICAL_MIRROR) {
            return NodeType.SHADOW_ACCOUNT;
        }
        if (category == AccountCategory.ROOT) {
            return NodeType.GROUP;
        }
        if (category == AccountCategory.AGGREGATION) {
            return NodeType.ENTITY;
        }
        return NodeType.VIRTUAL_ACCOUNT;
    }

    // ========================================================================
    // PRIVATE HELPER METHODS - REMOVED DEMO DATA BUILDERS
    // ========================================================================

    /**
     * Convert an amount to the active market's base currency via {@link FxRateService}.
     * The helper keeps its legacy name for caller compatibility; the actual base
     * is whatever the active {@code MarketProfile} defines (AED for UAE, GBP for UK, etc.).
     */
    /**
     * Convert an amount into the caller-requested reporting currency. Falls
     * back to the market profile's default currency when the caller passed
     * none — previously conversion ALWAYS targeted the profile default, so
     * a UK-profile backend answered a `reportingCurrency=AED` request with
     * GBP figures that the frontend then labelled "AED".
     */
    private BigDecimal convertToReporting(BigDecimal amount, String currency, String reportingCurrency) {
        if (amount == null) return BigDecimal.ZERO;
        if (currency == null) return amount.setScale(2, RoundingMode.HALF_UP);
        String target = (reportingCurrency != null && !reportingCurrency.isBlank())
            ? reportingCurrency
            : marketProfile.getDefaultCurrency();
        if (currency.equalsIgnoreCase(target)) return amount.setScale(2, RoundingMode.HALF_UP);
        return fxRateService.convert(amount, currency, target).setScale(2, RoundingMode.HALF_UP);
    }

    private HierarchyNode buildHierarchyFromAccounts(Corporate corporate, List<VirtualAccount> accounts,
                                                     Map<UUID, PoolMember> poolMemberMap,
                                                     Map<UUID, SweepRule> sweepRuleMap,
                                                     Map<UUID, List<NettingEntry>> nettingMap,
                                                     Map<String, IntercompanyPositions> icPositions,
                                                     String reportingCurrency) {
        // Build hierarchical tree from flat list using parent relationships
        List<HierarchyNode> children = buildHierarchyTree(
            accounts, poolMemberMap, sweepRuleMap, nettingMap, icPositions, reportingCurrency);

        // DEBUG: Log children balances
        log.info("ROOT has {} direct children", children.size());
        for (HierarchyNode child : children) {
            log.info("  Child '{}' ({}) consolidatedBalance: {}, children: {}",
                     child.getName(), child.getAccountCategory(),
                     child.getConsolidatedBalance(),
                     child.getChildren() != null ? child.getChildren().size() : 0);
        }

        BigDecimal totalBalance = children.stream()
            .map(HierarchyNode::getConsolidatedBalance)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        log.info("ROOT totalBalance (sum of direct children): {}", totalBalance);

        BigDecimal totalICReceivable = children.stream()
            .map(n -> n.getIntercompanyReceivable() != null ? n.getIntercompanyReceivable() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalICPayable = children.stream()
            .map(n -> n.getIntercompanyPayable() != null ? n.getIntercompanyPayable() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return HierarchyNode.builder()
            .id(corporate.getId().toString())
            .name(corporate.getLegalName())
            .type(NodeType.GROUP)
            .specialType(SpecialVaType.REGULAR)
            .accountCategory(AccountCategory.ROOT)
            .level(0)
            .currencyCode(reportingCurrency)
            .localBalance(BigDecimal.ZERO)
            .consolidatedBalance(totalBalance)
            .intercompanyReceivable(totalICReceivable)
            .intercompanyPayable(totalICPayable)
            .netPosition(totalBalance.add(totalICReceivable).subtract(totalICPayable))
            .participatesInPooling(true)
            .participatesInNetting(true)
            .participatesInSweep(false)
            .children(children)
            .build();
    }

    /**
     * Build hierarchical tree from flat list of accounts using parentAccountId
     */
    private List<HierarchyNode> buildHierarchyTree(List<VirtualAccount> accounts,
                                                   Map<UUID, PoolMember> poolMemberMap,
                                                   Map<UUID, SweepRule> sweepRuleMap,
                                                   Map<UUID, List<NettingEntry>> nettingMap,
                                                   Map<String, IntercompanyPositions> icPositions,
                                                   String reportingCurrency) {
        // Map all accounts by ID
        Map<UUID, VirtualAccount> accountMap = accounts.stream()
            .collect(Collectors.toMap(VirtualAccount::getId, va -> va));
        
        // Map to store built nodes
        Map<UUID, HierarchyNode> nodeMap = new HashMap<>();
        
        // Build all nodes first
        for (VirtualAccount va : accounts) {
            HierarchyNode node = buildNodeFromVA(va, poolMemberMap, sweepRuleMap, 
                                                  nettingMap, icPositions, reportingCurrency);
            nodeMap.put(va.getId(), node);
        }
        
        // Build parent-child relationships
        List<HierarchyNode> roots = new ArrayList<>();
        for (VirtualAccount va : accounts) {
            HierarchyNode node = nodeMap.get(va.getId());
            UUID parentId = va.getParentAccountId();
            
            if (parentId == null || !nodeMap.containsKey(parentId)) {
                // This is a root node
                roots.add(node);
            } else {
                // Add as child to parent
                HierarchyNode parent = nodeMap.get(parentId);
                if (parent.getChildren() == null) {
                    parent.setChildren(new ArrayList<>());
                }
                parent.getChildren().add(node);
            }
        }
        
        // Sort roots by level, then by name
        roots.sort(Comparator
            .comparingInt(HierarchyNode::getLevel)
            .thenComparing(HierarchyNode::getName));
        
        return roots;
    }

    private HierarchyNode buildNodeFromVA(VirtualAccount va,
                                          Map<UUID, PoolMember> poolMemberMap,
                                          Map<UUID, SweepRule> sweepRuleMap,
                                          Map<UUID, List<NettingEntry>> nettingMap,
                                          Map<String, IntercompanyPositions> icPositions,
                                          String reportingCurrency) {
        PoolMember poolMember = poolMemberMap.get(va.getId());
        SweepRule sweepRule = sweepRuleMap.get(va.getId());
        List<NettingEntry> nettingEntries = nettingMap.getOrDefault(va.getId(), List.of());
        IntercompanyPositions icPos = icPositions.getOrDefault(va.getVaNumber(), new IntercompanyPositions());

        // Use getEffectiveBalance() which returns:
        // - aggregatedBalance for ROOT/AGGREGATION nodes (sum of children, stored in baseCurrency)
        // - mirrorBalance for CURRENCY_MIRROR nodes (stored in currencyCode)
        // - currentBalance for regular transaction VAs (stored in currencyCode)
        BigDecimal effectiveBalance = va.getEffectiveBalance();

        // CRITICAL: For AGGREGATION/ROOT, aggregatedBalance is stored in baseCurrency, not currencyCode
        // For CURRENCY_MIRROR, mirrorBalance is in currencyCode, but balanceInBase is already converted
        // FIX v5.5.1: Check isCurrencyMirror() FIRST because isAggregationNode() includes CURRENCY_MIRROR
        String balanceCurrency;
        if (va.isCurrencyMirror()) {
            // CURRENCY_MIRROR: mirrorBalance is in currencyCode (e.g., GBP, USD)
            balanceCurrency = va.getCurrencyCode();
        } else if (va.isAggregationNode()) {
            // AGGREGATION/ROOT: aggregatedBalance is in baseCurrency
            balanceCurrency = va.getBaseCurrency() != null ? va.getBaseCurrency() : va.getCurrencyCode();
        } else {
            // Regular VAs: currentBalance is in currencyCode
            balanceCurrency = va.getCurrencyCode();
        }
        BigDecimal consolidated = convertToReporting(effectiveBalance, balanceCurrency, reportingCurrency);

        // DEBUG: Log balance for troubleshooting
        if (effectiveBalance.compareTo(BigDecimal.ZERO) != 0) {
            log.info("VA {} ({}, {}) effectiveBalance: {} {} (baseCurrency: {}) -> {} AED",
                     va.getVaNumber(), va.getVaName(), va.getAccountCategory(),
                     effectiveBalance, balanceCurrency, va.getBaseCurrency(), consolidated);
        }

        // Map special type and account category from entity
        SpecialVaType specialType = mapSpecialType(va);
        AccountCategory accountCategory = mapAccountCategory(va);
        NodeType nodeType = mapNodeType(va, accountCategory);
        
        // Determine hierarchy level
        int level = 1;
        if (va.getHierarchyLevel() != null) {
            level = va.getHierarchyLevel();
        } else if (accountCategory == AccountCategory.ROOT) {
            level = 0;
        } else if (accountCategory == AccountCategory.CURRENCY_MIRROR || 
                   accountCategory == AccountCategory.EXCEPTION) {
            // Currency Mirrors and Exception VAs are at ROOT level (siblings)
            level = 0;  // Or 1, depending on your ROOT level convention
        } else if (accountCategory == AccountCategory.AGGREGATION) {
            level = 1;
        } else {
            level = 2; // Transaction level VAs
        }


        return HierarchyNode.builder()
            .id(va.getId().toString())
            .name(va.getVaName())
            .accountNumber(va.getVaNumber())
            .type(nodeType)
            // NEW: Special Type and Account Category for icon differentiation
            .specialType(specialType)
            .accountCategory(accountCategory)
            .level(level)
            // Use balanceCurrency for display - AGGREGATION nodes show balance in baseCurrency
            .currencyCode(balanceCurrency)
            // Balances - use effectiveBalance which returns aggregatedBalance for AGGREGATION nodes
            .localBalance(effectiveBalance)
            .consolidatedBalance(consolidated)
            .availableBalance(va.getAvailableBalance())
            .intercompanyReceivable(icPos.receivable)
            .intercompanyPayable(icPos.payable)
            .netPosition(consolidated.add(icPos.receivable).subtract(icPos.payable))
            // Participation flags
            .participatesInPooling(poolMember != null)
            .participatesInNetting(!nettingEntries.isEmpty())
            .participatesInSweep(sweepRule != null)
            .sweepTarget(sweepRule != null ? sweepRule.getTargetAccountId().toString() : null)
            .interestRate(poolMember != null ? new BigDecimal("3.75") : null)
            .interestAllocation(poolMember != null ? poolMember.getInterestAllocation() : null)
            // NEW: Additional metadata for special VAs
            .primaryViban(va.getViban())
            .baseCurrency(va.getBaseCurrency())
            // For currency mirrors - use actual mirrorBalance and balanceInBase from VA
            .mirrorBalance(specialType == SpecialVaType.CURRENCY_MIRROR ? va.getMirrorBalance() : null)
            .balanceInBase(specialType == SpecialVaType.CURRENCY_MIRROR ? va.getBalanceInBase() : null)
            .fxRate(specialType == SpecialVaType.CURRENCY_MIRROR ?
                    (va.getFxRate() != null ? va.getFxRate() : fxRateService.getRate(va.getCurrencyCode(), marketProfile.getDefaultCurrency())) : null)
            .fxRateAt(specialType == SpecialVaType.CURRENCY_MIRROR ?
                      (va.getFxRateAt() != null ? va.getFxRateAt().toString() : java.time.LocalDateTime.now().toString()) : null)
            // Placeholder counts - in production, query actual counts
            .coveredVaCount(specialType == SpecialVaType.SETTLEMENT ? 0 : null)
            .pendingExceptions(specialType == SpecialVaType.EXCEPTION ? 0 : null)
            // ENHANCED: Owning entity information with full lookup
            .owningEntityId(va.getOwningEntityId() != null ? va.getOwningEntityId().toString() : null)
            .owningEntityCode(getEntityCode(va))
            .owningEntityName(lookupEntityName(va.getOwningEntityId()))
            .owningEntityType(lookupEntityType(va.getOwningEntityId()))
            // ENHANCED: IHB summary from owning entity
            .ihb(buildIhbSummary(va.getOwningEntityId(), va))
            .build();
    }

    private NodeDetail buildNodeDetailFromVA(VirtualAccount va, String reportingCurrency) {
        BigDecimal currentBalance = va.getCurrentBalance() != null ? va.getCurrentBalance() : BigDecimal.ZERO;
        BigDecimal availableBalance = va.getAvailableBalance() != null ? va.getAvailableBalance() : BigDecimal.ZERO;
        
        // Map special type and account category
        SpecialVaType specialType = mapSpecialType(va);
        AccountCategory accountCategory = mapAccountCategory(va);
        NodeType nodeType = mapNodeType(va, accountCategory);
        
        return NodeDetail.builder()
            .id(va.getId().toString())
            .name(va.getVaName())
            .accountNumber(va.getVaNumber())
            .type(nodeType)
            // NEW: Special type info
            .specialType(specialType)
            .accountCategory(accountCategory)
            .currencyCode(va.getCurrencyCode())
            .localBalance(currentBalance)
            .consolidatedBalance(convertToReporting(currentBalance, va.getCurrencyCode(), reportingCurrency))
            .availableBalance(availableBalance)
            .holdAmount(currentBalance.subtract(availableBalance))
            .intercompanyReceivable(BigDecimal.ZERO)
            .intercompanyPayable(BigDecimal.ZERO)
            .netIntercompanyPosition(BigDecimal.ZERO)
            .participatesInPooling(false)  // TODO: Query pool membership
            .participatesInNetting(false)  // TODO: Query netting entries
            .participatesInSweep(Boolean.TRUE.equals(va.getIhbSweepEnabled()))  // Check VA sweep flag
            .externalReference(va.getExternalReference())
            // NEW: Special VA specific fields
            .coveredVaCount(specialType == SpecialVaType.SETTLEMENT ? 0 : null)
            .pendingExceptions(specialType == SpecialVaType.EXCEPTION ? 0 : null)
            .mirrorBalance(specialType == SpecialVaType.CURRENCY_MIRROR ? currentBalance : null)
            .fxRate(specialType == SpecialVaType.CURRENCY_MIRROR ?
                    fxRateService.getRate(va.getCurrencyCode(), marketProfile.getDefaultCurrency()) : null)
            .build();
    }

    private Map<UUID, PoolMember> getPoolMemberMap(UUID corporateId) {
        // In real implementation, fetch from repository
        return new HashMap<>();
    }

    private Map<UUID, SweepRule> getSweepRuleMap(UUID corporateId) {
        return new HashMap<>();
    }

    private Map<UUID, List<NettingEntry>> getNettingEntryMap(UUID corporateId) {
        return new HashMap<>();
    }

    private Map<String, IntercompanyPositions> getIntercompanyPositions(UUID corporateId) {
        return new HashMap<>();
    }

    private void updatePoolMembership(VirtualAccount va, boolean participate, String poolReference) {
        log.info("Updating pool membership for VA {}: participate={}, pool={}", 
                 va.getVaNumber(), participate, poolReference);
        // Implementation would update pool_members table
    }

    private void updateSweepParticipation(VirtualAccount va, boolean participate, 
                                          String ruleReference, String target) {
        log.info("Updating sweep participation for VA {}: participate={}, rule={}, target={}",
                 va.getVaNumber(), participate, ruleReference, target);
        // Implementation would update sweep_rule_sources table
    }

    private Corporate createDefaultCorporate(UUID corporateId) {
        Corporate c = new Corporate();
        c.setId(corporateId != null ? corporateId : UUID.randomUUID());
        c.setLegalName("Corporate");
        c.setCorporateId("CORP-001");
        return c;
    }

    private SummaryStats calculateStats(HierarchyNode node) {
        SummaryStats stats = new SummaryStats();
        stats.currencies = new ArrayList<>();
        collectStats(node, stats);
        return stats;
    }

    private void collectStats(HierarchyNode node, SummaryStats stats) {
        if (node.getType() == NodeType.ENTITY || node.getType() == NodeType.REGION) {
            stats.entityCount++;
        } else if (node.getType() == NodeType.VIRTUAL_ACCOUNT) {
            stats.vaCount++;
        }
        
        if (node.isParticipatesInPooling()) stats.poolParticipants++;
        if (node.isParticipatesInSweep()) stats.sweepParticipants++;
        if (node.isParticipatesInNetting()) stats.nettingParticipants++;
        
        // Count special VA types
        if (node.getSpecialType() != null) {
            switch (node.getSpecialType()) {
                case SETTLEMENT -> stats.settlementVaCount++;
                case EXCEPTION -> stats.exceptionVaCount++;
                case CURRENCY_MIRROR -> stats.currencyMirrorCount++;
                default -> {}
            }
        }
        
        if (node.getCurrencyCode() != null && !stats.currencies.contains(node.getCurrencyCode())) {
            stats.currencies.add(node.getCurrencyCode());
        }
        
        if (node.getChildren() != null) {
            node.getChildren().forEach(child -> collectStats(child, stats));
        }
    }

    private static class SummaryStats {
        int entityCount = 0;
        int vaCount = 0;
        int poolParticipants = 0;
        int sweepParticipants = 0;
        int nettingParticipants = 0;
        int settlementVaCount = 0;
        int exceptionVaCount = 0;
        int currencyMirrorCount = 0;
        List<String> currencies = new ArrayList<>();
    }

    private static class IntercompanyPositions {
        BigDecimal receivable = BigDecimal.ZERO;
        BigDecimal payable = BigDecimal.ZERO;
    }

    // ========================================================================
    // ENHANCED: Helper methods for entity lookup
    // ========================================================================

    /**
     * Get entity code - from VA first, then lookup if needed
     */
    private String getEntityCode(VirtualAccount va) {
        // First try the stored code on VA
        if (va.getOwningEntityCode() != null && !va.getOwningEntityCode().isEmpty()) {
            return va.getOwningEntityCode();
        }
        // Fallback to lookup
        if (va.getOwningEntityId() == null) return null;
        try {
            return legalEntityRepository.findById(va.getOwningEntityId())
                .map(com.bank.vam.entity.hierarchy.LegalEntity::getEntityCode)
                .orElse(null);
        } catch (Exception e) {
            log.warn("Failed to lookup entity code for {}: {}", va.getOwningEntityId(), e.getMessage());
            return null;
        }
    }

    /**
     * Lookup entity name by ID
     */
    private String lookupEntityName(UUID entityId) {
        if (entityId == null) return null;
        try {
            return legalEntityRepository.findById(entityId)
                .map(com.bank.vam.entity.hierarchy.LegalEntity::getEntityName)
                .orElse(null);
        } catch (Exception e) {
            log.warn("Failed to lookup entity name for {}: {}", entityId, e.getMessage());
            return null;
        }
    }

    /**
     * Lookup entity type by ID
     */
    private String lookupEntityType(UUID entityId) {
        if (entityId == null) return null;
        try {
            return legalEntityRepository.findById(entityId)
                .map(e -> e.getEntityType() != null ? e.getEntityType().name() : null)
                .orElse(null);
        } catch (Exception e) {
            log.warn("Failed to lookup entity type for {}: {}", entityId, e.getMessage());
            return null;
        }
    }

    /**
     * ENHANCED: Build IHB summary from owning legal entity.
     */
    private IhbSummary buildIhbSummary(UUID entityId, VirtualAccount va) {
        if (entityId == null) return null;
        
        try {
            return legalEntityRepository.findById(entityId)
                .filter(LegalEntity::isIhbEnabled)
                .map(entity -> IhbSummary.builder()
                    .enabled(true)
                    .isTreasuryCenter(Boolean.TRUE.equals(entity.getCanLend()))
                    .canLend(Boolean.TRUE.equals(entity.getCanLend()))
                    .canBorrow(Boolean.TRUE.equals(entity.getCanBorrow()))
                    .creditLimit(entity.getIhbCreditLimit())
                    .currentExposure(entity.getIhbCurrentExposure())
                    .availableLimit(entity.getIhbAvailableLimit())
                    .utilizationPercent(calculateUtilization(entity.getIhbCurrentExposure(), entity.getIhbCreditLimit()))
                    .targetCashBalance(va.getTargetCashBalance())
                    .sweepEnabled(Boolean.TRUE.equals(va.getIhbSweepEnabled()))
                    .sweepFrequency(va.getIhbSweepFrequency())
                    .build())
                .orElse(null);
        } catch (Exception e) {
            log.warn("Failed to build IHB summary for entity {}: {}", entityId, e.getMessage());
            return null;
        }
    }

    /**
     * Calculate utilization percentage.
     */
    private BigDecimal calculateUtilization(BigDecimal exposure, BigDecimal limit) {
        if (limit == null || limit.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        if (exposure == null) return BigDecimal.ZERO;
        return exposure.multiply(new BigDecimal("100"))
            .divide(limit, 2, java.math.RoundingMode.HALF_UP);
    }
}