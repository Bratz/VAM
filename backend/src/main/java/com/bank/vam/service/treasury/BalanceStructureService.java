package com.bank.vam.service.treasury;

import com.bank.vam.dto.treasury.BalanceStructureDto;
import com.bank.vam.dto.treasury.BalanceStructureDto.*;
import com.bank.vam.entity.Corporate;
import com.bank.vam.entity.PhysicalAccount;
import com.bank.vam.entity.Program;
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
    private final ProgramRepository programRepository;
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
    private final com.bank.vam.config.HomeBankProperties homeBank;

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

        // Build hierarchy
        HierarchyNode root = buildHierarchyFromAccounts(corporate, accounts, poolMemberMap, sweepRuleMap,
                                                        nettingMap, reportingCurrency);
        root.setUnconvertedCurrencies(unconvertedCurrencies(accounts, reportingCurrency));
        return root;
    }

    /**
     * Currencies in these accounts with no FX rate to the reporting currency. Their balances are
     * left out of every converted figure (never counted 1:1), and the page names them.
     */
    private List<String> unconvertedCurrencies(List<VirtualAccount> accounts, String reportingCurrency) {
        String target = reportingTarget(reportingCurrency);
        // Only accounts whose own balance counts in the total (not containers, mirrors or shadows)
        // and actually hold money -- anything else was never going to be converted.
        return accounts.stream()
            .filter(va -> va.getAccountCategory() == null || !NOT_OWN_MONEY.contains(va.getAccountCategory()))
            .filter(va -> va.getCurrentBalance() != null && va.getCurrentBalance().signum() != 0)
            .map(VirtualAccount::getCurrencyCode)
            .filter(Objects::nonNull)
            .filter(c -> !c.equalsIgnoreCase(target))
            .distinct()
            .filter(c -> !fxRateService.hasRate(c, target))
            .sorted()
            .toList();
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
            .unassignedBankBalance(Optional.ofNullable(hierarchy.getUnassignedBankBalance()).orElse(BigDecimal.ZERO))
            .netPosition(hierarchy.getNetPosition())
            .totalIntercompanyReceivable(hierarchy.getIntercompanyReceivable())
            .totalIntercompanyPayable(hierarchy.getIntercompanyPayable())
            .netIntercompanyPosition(hierarchy.getIntercompanyReceivable()
                .subtract(hierarchy.getIntercompanyPayable()))
            // From the notional pools these accounts are in: null when none (the page shows "-").
            .poolRate(stats.poolRate())
            .monthlyInterestAllocation(stats.poolInterest)
            .unconvertedCurrencies(hierarchy.getUnconvertedCurrencies())
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
     * The real bank account behind a program: its main backing account, else the first of its own
     * bank-account shadows. Never another program's or "the corporate's first" account -- this used
     * to return the corporate's first physical account in database order, whatever the program, with
     * a bank name and BIC made up from the branch code (every account showed as Emirates NBD).
     */
    @Transactional(readOnly = true)
    public PhysicalAccountInfo getPhysicalAccount(UUID corporateId, UUID programId) {
        if (programId == null) return notConfigured("Select a program to see its bank account");
        Program program = programRepository.findById(programId).orElse(null);
        if (program == null) return notConfigured("Program not found");

        UUID accountId = program.getPhysicalAccountId();
        if (accountId == null) {
            accountId = virtualAccountRepository
                .findByProgramIdAndAccountCategory(programId, VirtualAccount.AccountCategory.PHYSICAL_MIRROR).stream()
                .filter(s -> s.getLinkedPhysicalAccountId() != null)
                .min(Comparator.comparing(VirtualAccount::getVaNumber, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(VirtualAccount::getLinkedPhysicalAccountId)
                .orElse(null);
        }
        if (accountId == null) return notConfigured("No bank account linked to this program");
        PhysicalAccount pa = physicalAccountRepository.findById(accountId).orElse(null);
        if (pa == null) return notConfigured("No bank account linked to this program");

        PhysicalAccountInfo info = PhysicalAccountInfo.builder()
            .id(pa.getId())
            .bankName(pa.getBankName())
            .bankBic(pa.getBankCode())
            .accountNumber(pa.getAccountNumber())
            .iban(pa.getIban())
            .accountName(pa.getAccountName())
            .balance(pa.getCurrentBalance())
            .availableBalance(pa.getAvailableBalance())
            .currency(pa.getCurrencyCode())
            .accountType(pa.getAccountType() != null ? pa.getAccountType().name() : "CURRENT")
            .status(pa.getStatus() != null ? pa.getStatus().name() : "ACTIVE")
            .build();
        // Booked on an account whose shadow belongs to another program: say so (its payments are refused).
        virtualAccountRepository.findByLinkedPhysicalAccountId(pa.getId())
            .filter(s -> s.getProgramId() != null && !programId.equals(s.getProgramId()))
            .flatMap(s -> programRepository.findById(s.getProgramId()))
            .ifPresent(holder -> {
                info.setHeldByProgramCode(holder.getProgramCode());
                info.setHeldByProgramName(holder.getProgramName());
            });
        return info;
    }

    private static PhysicalAccountInfo notConfigured(String message) {
        return PhysicalAccountInfo.builder()
            .accountName(message)
            .accountNumber("")
            .bankName("")
            .balance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .status("NOT_CONFIGURED")
            .build();
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
        String target = reportingTarget(reportingCurrency);
        if (currency.equalsIgnoreCase(target)) return amount.setScale(2, RoundingMode.HALF_UP);
        try {
            return fxRateService.convert(amount, currency, target).setScale(2, RoundingMode.HALF_UP);
        } catch (com.bank.vam.exception.BusinessException noRate) {
            return BigDecimal.ZERO; // left out, and listed in unconvertedCurrencies
        }
    }

    private static final java.util.Set<VirtualAccount.AccountCategory> NOT_OWN_MONEY = java.util.EnumSet.of(
        VirtualAccount.AccountCategory.ROOT, VirtualAccount.AccountCategory.AGGREGATION,
        VirtualAccount.AccountCategory.CURRENCY_MIRROR, VirtualAccount.AccountCategory.PHYSICAL_MIRROR);

    private String reportingTarget(String reportingCurrency) {
        return (reportingCurrency != null && !reportingCurrency.isBlank())
            ? reportingCurrency : marketProfile.getDefaultCurrency();
    }

    /** Rate for display next to a currency mirror; null when there is none. */
    private BigDecimal rateOrNull(String from, String reportingCurrency) {
        if (from == null) return null;
        try {
            return fxRateService.getRate(from, reportingTarget(reportingCurrency));
        } catch (com.bank.vam.exception.BusinessException noRate) {
            return null;
        }
    }

    /**
     * Dashboard "Position breakdown" — firm-wide total per corporate.
     * Sums each corporate's balances per-currency first (sumBalanceByCorporateGroupedByCurrency),
     * then FX-converts each bucket via convertToReporting — same FX-honest
     * pattern as the hierarchy tree above, never adds raw cross-currency amounts.
     */
    @Transactional(readOnly = true)
    public List<BalanceBreakdownItem> getBalanceByCorporate(String reportingCurrency) {
        return corporateRepository.findAll().stream()
            .map(c -> BalanceBreakdownItem.builder()
                .id(c.getId())
                .name(c.getLegalName())
                .balance(sumConverted(virtualAccountRepository.sumBalanceByCorporateGroupedByCurrency(c.getId()), reportingCurrency))
                .build())
            .filter(item -> item.getBalance().signum() > 0)
            .sorted(Comparator.comparing(BalanceBreakdownItem::getBalance).reversed())
            .toList();
    }

    /**
     * Dashboard "Position breakdown" — total per program, optionally scoped
     * to one corporate (narrows the view when a corporate is selected on
     * the dashboard; firm-wide across all programs otherwise).
     */
    @Transactional(readOnly = true)
    public List<BalanceBreakdownItem> getBalanceByProgram(UUID corporateId, String reportingCurrency) {
        List<com.bank.vam.entity.Program> programs = corporateId != null
            ? programRepository.findByCorporateId(corporateId)
            : programRepository.findAll();
        return programs.stream()
            .map(p -> BalanceBreakdownItem.builder()
                .id(p.getId())
                .name(p.getProgramName())
                .balance(sumConverted(virtualAccountRepository.sumBalanceByProgramGroupedByCurrency(p.getId()), reportingCurrency))
                .build())
            .filter(item -> item.getBalance().signum() > 0)
            .sorted(Comparator.comparing(BalanceBreakdownItem::getBalance).reversed())
            .toList();
    }

    private BigDecimal sumConverted(List<Object[]> currencyBuckets, String reportingCurrency) {
        BigDecimal total = BigDecimal.ZERO;
        for (Object[] row : currencyBuckets) {
            total = total.add(convertToReporting((BigDecimal) row[1], (String) row[0], reportingCurrency));
        }
        return total;
    }

    private HierarchyNode buildHierarchyFromAccounts(Corporate corporate, List<VirtualAccount> accounts,
                                                     Map<UUID, PoolMember> poolMemberMap,
                                                     Map<UUID, SweepRule> sweepRuleMap,
                                                     Map<UUID, List<NettingEntry>> nettingMap,
                                                     String reportingCurrency) {
        // Build hierarchical tree from flat list using parent relationships
        List<HierarchyNode> children = buildHierarchyTree(
            accounts, poolMemberMap, sweepRuleMap, nettingMap, reportingCurrency);

        // Recompute every node's consolidatedBalance as a true post-order
        // rollup before anything reads it — see recomputeRollup() for why
        // this can't just trust what buildNodeFromVA() set.
        // Home-bank accounts no program has picked yet: shadows with no program and no parent, at the
        // top of the tree. Shadows are not in the total (below), so this is reported beside it; read
        // before the rollup, while a shadow node still carries its own (converted) bank balance.
        Set<String> unassignedIds = accounts.stream()
            .filter(va -> va.getAccountCategory() == VirtualAccount.AccountCategory.PHYSICAL_MIRROR
                && va.getProgramId() == null && va.getParentAccountId() == null
                && homeBank.matches(va.getBankSwift()))
            .map(va -> va.getId().toString())
            .collect(Collectors.toSet());
        BigDecimal unassignedBankBalance = children.stream()
            .filter(n -> unassignedIds.contains(n.getId()))
            .map(HierarchyNode::getConsolidatedBalance)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        children.forEach(this::recomputeRollup);

        // DEBUG: Log children balances
        log.info("ROOT has {} direct children", children.size());
        for (HierarchyNode child : children) {
            log.info("  Child '{}' ({}) consolidatedBalance: {}, children: {}",
                     child.getName(), child.getAccountCategory(),
                     child.getConsolidatedBalance(),
                     child.getChildren() != null ? child.getChildren().size() : 0);
        }

        BigDecimal totalBalance = children.stream()
            .filter(n -> n.getAccountCategory() != AccountCategory.CURRENCY_MIRROR)
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
            .unassignedBankBalance(unassignedBankBalance)
            .intercompanyReceivable(totalICReceivable)
            .intercompanyPayable(totalICPayable)
            .netPosition(totalBalance)   // IC mirrors are already in the total (payables subtracted)
            .participatesInPooling(false)
            .participatesInNetting(false)
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
                                                   String reportingCurrency) {
        // Map all accounts by ID
        Map<UUID, VirtualAccount> accountMap = accounts.stream()
            .collect(Collectors.toMap(VirtualAccount::getId, va -> va));
        
        // Map to store built nodes
        Map<UUID, HierarchyNode> nodeMap = new HashMap<>();
        
        // Build all nodes first
        for (VirtualAccount va : accounts) {
            HierarchyNode node = buildNodeFromVA(va, poolMemberMap, sweepRuleMap, 
                                                  nettingMap, reportingCurrency);
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

    /**
     * Post-order rollup, replacing whatever buildNodeFromVA() set from the
     * cached aggregatedBalance column. ROOT/AGGREGATION nodes contribute
     * ZERO of their own — their cached value is itself an old partial
     * rollup (HierarchyVaService.recalculateAggregationBalance() only sums
     * CURRENCY_MIRROR/AGGREGATION children, never TRANSACTION) — treating
     * it as "this level's own money" and adding a fresh children-sum on
     * top double-counts real money once per hierarchy level (confirmed
     * live: a single AED 2790 leaf balance compounded to over 4x its value
     * across a 3-level EMEA REGION > UK > LONDON OPS chain). localBalance
     * is zeroed the same way for the same reason — it's not a real
     * standalone figure for these categories either, just the same cached
     * artifact. CURRENCY_MIRROR children are excluded from every parent's
     * sum entirely (mirrors, not real money).
     */
    private BigDecimal recomputeRollup(HierarchyNode node) {
        BigDecimal childrenSum = BigDecimal.ZERO;
        if (node.getChildren() != null) {
            for (HierarchyNode child : node.getChildren()) {
                BigDecimal childTotal = recomputeRollup(child);
                if (child.getAccountCategory() == AccountCategory.CURRENCY_MIRROR) {
                    continue; // restates a branch's total, not a disjoint slice of it
                }
                // An IC mirror is Treasury's view of a subsidiary's own IHB position -- the same
                // money seen from the other side. Counting it (either sign) cancelled the real
                // movement: a POBO payment left the total unchanged although the cash had gone.
                // It stays on its row and in the intercompany figures below, not in the total.
                if (isIntercompanyMirror(child)) {
                    continue;
                }
                childrenSum = childrenSum.add(childTotal);
            }
        }
        boolean isContainer = node.getAccountCategory() == AccountCategory.ROOT
            || node.getAccountCategory() == AccountCategory.AGGREGATION;
        // A shadow (bank-mirror) account's own figure is the bank's balance of the real account that
        // backs these virtual accounts -- counting it too added the same cash twice. It stays on the
        // row (localBalance) but not in any total; accounts under a shadow still count.
        boolean isBankMirror = node.getAccountCategory() == AccountCategory.PHYSICAL_MIRROR;
        BigDecimal ownBalance = (isContainer || isBankMirror) ? BigDecimal.ZERO
            : (node.getConsolidatedBalance() != null ? node.getConsolidatedBalance() : BigDecimal.ZERO);
        BigDecimal total = ownBalance.add(childrenSum);
        node.setConsolidatedBalance(total);
        if (isContainer) {
            node.setLocalBalance(BigDecimal.ZERO);
        }
        // IC receivable/payable roll up for reporting only: they are no longer part of the total,
        // so the net position (money plus what the group owes itself) is the total itself.
        if (node.getChildren() != null) {
            BigDecimal icReceivable = node.getIntercompanyReceivable() != null ? node.getIntercompanyReceivable() : BigDecimal.ZERO;
            BigDecimal icPayable = node.getIntercompanyPayable() != null ? node.getIntercompanyPayable() : BigDecimal.ZERO;
            for (HierarchyNode child : node.getChildren()) {
                if (child.getAccountCategory() == AccountCategory.CURRENCY_MIRROR) continue;
                if (child.getIntercompanyReceivable() != null) icReceivable = icReceivable.add(child.getIntercompanyReceivable());
                if (child.getIntercompanyPayable() != null) icPayable = icPayable.add(child.getIntercompanyPayable());
            }
            node.setIntercompanyReceivable(icReceivable);
            node.setIntercompanyPayable(icPayable);
        }
        node.setNetPosition(total);
        return total;
    }

    /** Treasury's IC receivable/payable: the other side of a subsidiary's own position, not extra money. */
    private static boolean isIntercompanyMirror(HierarchyNode node) {
        return "IC_RECEIVABLE".equals(node.getMirrorAccountType())
            || "IC_PAYABLE".equals(node.getMirrorAccountType());
    }

    private HierarchyNode buildNodeFromVA(VirtualAccount va,
                                          Map<UUID, PoolMember> poolMemberMap,
                                          Map<UUID, SweepRule> sweepRuleMap,
                                          Map<UUID, List<NettingEntry>> nettingMap,
                                          String reportingCurrency) {
        PoolMember poolMember = poolMemberMap.get(va.getId());
        SweepRule sweepRule = sweepRuleMap.get(va.getId());
        List<NettingEntry> nettingEntries = va.getOwningEntityId() != null
            ? nettingMap.getOrDefault(va.getOwningEntityId(), List.of()) : List.of();

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
            .mirrorAccountType(va.getMirrorAccountType() != null ? va.getMirrorAccountType().name() : null)
            .level(level)
            // Use balanceCurrency for display - AGGREGATION nodes show balance in baseCurrency
            .currencyCode(balanceCurrency)
            // Balances - use effectiveBalance which returns aggregatedBalance for AGGREGATION nodes
            .localBalance(effectiveBalance)
            .consolidatedBalance(consolidated)
            .availableBalance(va.getAvailableBalance())
            // An IC mirror IS the intercompany position (Treasury's receivable/payable to a subsidiary).
            .intercompanyReceivable(va.getMirrorAccountType() == VirtualAccount.MirrorAccountType.IC_RECEIVABLE ? consolidated : BigDecimal.ZERO)
            .intercompanyPayable(va.getMirrorAccountType() == VirtualAccount.MirrorAccountType.IC_PAYABLE ? consolidated : BigDecimal.ZERO)
            .netPosition(consolidated)
            // Participation flags -- from real pool members, active sweep rules and open netting entries
            .participatesInPooling(poolMember != null)
            .participatesInNetting(!nettingEntries.isEmpty())
            .participatesInSweep(sweepRule != null)
            .sweepTarget(sweepRule != null && sweepRule.getTargetAccountId() != null ? sweepRule.getTargetAccountId().toString() : null)
            .interestRate(poolMember != null ? poolMember.getPool().getInterestRate() : null)
            .interestAllocation(poolMember != null && poolMember.getInterestAllocation() != null
                ? convertToReporting(poolMember.getInterestAllocation(), poolMember.getPool().getPoolCurrency(), reportingCurrency) : null)
            // NEW: Additional metadata for special VAs
            .primaryViban(va.getViban())
            .baseCurrency(va.getBaseCurrency())
            // For currency mirrors - use actual mirrorBalance and balanceInBase from VA
            .mirrorBalance(specialType == SpecialVaType.CURRENCY_MIRROR ? va.getMirrorBalance() : null)
            .balanceInBase(specialType == SpecialVaType.CURRENCY_MIRROR ? va.getBalanceInBase() : null)
            // Rate into the currency this view reports in (the stored va.fxRate targets the mirror's
            // own base currency, which the page then labelled as the reporting currency).
            .fxRate(specialType == SpecialVaType.CURRENCY_MIRROR ? rateOrNull(va.getCurrencyCode(), reportingCurrency) : null)
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
        UUID corporateId = va.getCorporateId();
        BigDecimal converted = convertToReporting(currentBalance, va.getCurrencyCode(), reportingCurrency);
        BigDecimal icReceivable = va.getMirrorAccountType() == VirtualAccount.MirrorAccountType.IC_RECEIVABLE ? converted : BigDecimal.ZERO;
        BigDecimal icPayable = va.getMirrorAccountType() == VirtualAccount.MirrorAccountType.IC_PAYABLE ? converted : BigDecimal.ZERO;

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
            .consolidatedBalance(converted)
            .availableBalance(availableBalance)
            .holdAmount(currentBalance.subtract(availableBalance))
            .intercompanyReceivable(icReceivable)
            .intercompanyPayable(icPayable)
            .netIntercompanyPosition(icReceivable.subtract(icPayable))
            .participatesInPooling(poolMemberRepository.findByAccountId(va.getId()).stream()
                .anyMatch(m -> m.getStatus() == PoolMember.MemberStatus.ACTIVE && m.getPool() != null
                    && m.getPool().getStatus() == NotionalPool.PoolStatus.ACTIVE))
            .participatesInNetting(va.getOwningEntityId() != null
                && !nettingEntryRepository.findOpenForEntities(List.of(va.getOwningEntityId())).isEmpty())
            .participatesInSweep(corporateId != null && getSweepRuleMap(corporateId).containsKey(va.getId()))
            .externalReference(va.getExternalReference())
            // NEW: Special VA specific fields
            .coveredVaCount(specialType == SpecialVaType.SETTLEMENT ? 0 : null)
            .pendingExceptions(specialType == SpecialVaType.EXCEPTION ? 0 : null)
            .mirrorBalance(specialType == SpecialVaType.CURRENCY_MIRROR ? currentBalance : null)
            .fxRate(specialType == SpecialVaType.CURRENCY_MIRROR ? rateOrNull(va.getCurrencyCode(), reportingCurrency) : null)
            .build();
    }

    /** Account id -> its membership in one of the corporate's active notional pools. */
    private Map<UUID, PoolMember> getPoolMemberMap(UUID corporateId) {
        Map<UUID, PoolMember> map = new HashMap<>();
        for (PoolMember m : poolMemberRepository.findActiveByCorporate(corporateId)) {
            if (m.getAccountId() != null) map.putIfAbsent(m.getAccountId(), m);
        }
        return map;
    }

    /** Account id -> the active sweep rule it is swept from (a source) or into (the target). */
    private Map<UUID, SweepRule> getSweepRuleMap(UUID corporateId) {
        Map<UUID, SweepRule> map = new HashMap<>();
        // Rules without a corporate (older IHB rules) are included; the map is only read for this
        // hierarchy's own accounts, so another corporate's rule never matches.
        for (SweepRule rule : sweepRuleRepository.findByCorporateIdOrUnset(corporateId)) {
            if (rule.getStatus() != SweepRule.SweepStatus.ACTIVE) continue;
            if (rule.getSourceAccounts() != null) {
                rule.getSourceAccounts().stream().map(SweepRuleSource::getAccountId)
                    .filter(Objects::nonNull).forEach(id -> map.putIfAbsent(id, rule));
            }
            if (rule.getTargetAccountId() != null) map.putIfAbsent(rule.getTargetAccountId(), rule);
        }
        return map;
    }

    /** Legal entity id -> its open (pending/included) netting entries, as payer or payee. */
    private Map<UUID, List<NettingEntry>> getNettingEntryMap(UUID corporateId) {
        List<UUID> entityIds = legalEntityRepository.findByCorporateIdOrderByHierarchyPath(corporateId).stream()
            .map(LegalEntity::getId).toList();
        Map<UUID, List<NettingEntry>> map = new HashMap<>();
        if (entityIds.isEmpty()) return map;
        for (NettingEntry e : nettingEntryRepository.findOpenForEntities(entityIds)) {
            for (UUID id : new UUID[]{e.getPayerEntityId(), e.getPayeeEntityId()}) {
                if (id != null && entityIds.contains(id)) map.computeIfAbsent(id, k -> new ArrayList<>()).add(e);
            }
        }
        return map;
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
        
        if (node.isParticipatesInPooling()) {
            stats.poolParticipants++;
            if (node.getInterestRate() != null) {
                BigDecimal weight = node.getConsolidatedBalance() != null ? node.getConsolidatedBalance().abs() : BigDecimal.ZERO;
                stats.rateWeighted = stats.rateWeighted.add(node.getInterestRate().multiply(weight));
                stats.rateWeight = stats.rateWeight.add(weight);
                stats.anyRate = node.getInterestRate();
            }
            if (node.getInterestAllocation() != null) stats.poolInterest = stats.poolInterest.add(node.getInterestAllocation());
        }
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
        BigDecimal rateWeighted = BigDecimal.ZERO;
        BigDecimal rateWeight = BigDecimal.ZERO;
        BigDecimal anyRate;
        BigDecimal poolInterest = BigDecimal.ZERO;

        /** Balance-weighted rate of the pools these accounts are in; null when they're in none. */
        BigDecimal poolRate() {
            if (rateWeight.signum() == 0) return anyRate;
            return rateWeighted.divide(rateWeight, 4, RoundingMode.HALF_UP);
        }
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
                    .ihbCurrency(entity.getEffectiveIhbCurrency())
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