package com.bank.vam.service;

import com.bank.vam.dto.ProgramDto.*;
import com.bank.vam.entity.Corporate;
import com.bank.vam.entity.PhysicalAccount;
import com.bank.vam.entity.Program;
import com.bank.vam.entity.Program.ProgramStatus;
import com.bank.vam.entity.Program.VibanGenerationStrategy;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.CorporateRepository;
import com.bank.vam.repository.PhysicalAccountRepository;
import com.bank.vam.repository.ProgramRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.HierarchyNodeRepository;
import com.bank.vam.repository.viban.VibanPoolRepository;
import com.bank.vam.entity.viban.VibanPool;
import com.bank.vam.service.hierarchy.HierarchyService;
import com.bank.vam.dto.hierarchy.HierarchyDto.InitializeHierarchyRequest;
import com.bank.vam.dto.hierarchy.HierarchyDto.InitializationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service for Program management.
 * Aligned with unified_programs schema including:
 * - 7-level hierarchy support
 * - VIBAN pool settings
 * - Balance aggregation settings
 * - Additional program types (Loyalty, Gift Card, Corporate Card, Mobile Money)
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ProgramService {

    private final ProgramRepository programRepository;
    private final CorporateRepository corporateRepository;
    private final PhysicalAccountRepository physicalAccountRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final HierarchyNodeRepository hierarchyNodeRepository;
    private final VibanPoolRepository vibanPoolRepository;
    private final HierarchyService hierarchyService;
    private final com.bank.vam.service.audit.AuditLogService auditLogService;
    private final com.bank.vam.repository.audit.AuditLogRepository auditLogRepository;
    private final com.bank.vam.service.treasury.ShadowAccountService shadowAccountService;
    private final com.bank.vam.service.treasury.FxRateService fxRateService;
    private final com.bank.vam.config.MarketProfileProperties marketProfile;

    // ========================================================================
    // CRUD OPERATIONS
    // ========================================================================

    /**
     * Get all programs with filtering, pagination, and stats
     */
    @Transactional(readOnly = true)
    public ProgramListResponse getAllPrograms(UUID corporateId, ProgramSearchRequest request) {
        log.debug("Getting all programs with request: {}", request);

        Sort sort = Sort.by(
            "desc".equalsIgnoreCase(request.getSortOrder()) ? Sort.Direction.DESC : Sort.Direction.ASC,
            request.getSortBy() != null ? request.getSortBy() : "createdAt"
        );

        Pageable pageable = PageRequest.of(
            request.getPage() != null ? request.getPage() : 0,
            request.getPageSize() != null ? request.getPageSize() : 20,
            sort
        );

        Page<Program> programPage;
        if (corporateId != null) {
            programPage = programRepository.findByCorporateIdWithFilters(
                corporateId, request.getQuery(), request.getStatus(), pageable
            );
        } else {
            programPage = programRepository.findAllWithFilters(
                request.getQuery(), request.getStatus(), pageable
            );
        }

        List<ProgramResponse> programs = programPage.getContent().stream()
            .map(this::toProgramResponse)
            .collect(Collectors.toList());

        ProgramStatsResponse stats = getStats(corporateId);

        return ProgramListResponse.builder()
            .programs(programs)
            .totalCount(programPage.getTotalElements())
            .page(programPage.getNumber())
            .pageSize(programPage.getSize())
            .stats(stats)
            .build();
    }

    /**
     * Get program by ID
     */
    @Transactional(readOnly = true)
    public ProgramResponse getProgram(UUID programId) {
        log.debug("Getting program by ID: {}", programId);
        Program program = findProgramOrThrow(programId);
        return toProgramResponse(program);
    }

    /**
     * Get program detail with related entities
     */
    @Transactional(readOnly = true)
    public ProgramDetailResponse getProgramDetail(UUID programId) {
        log.debug("Getting program detail: {}", programId);
        
        Program program = findProgramOrThrow(programId);
        ProgramResponse programResponse = toProgramResponse(program);

        // Get corporate info
        CorporateInfo corporateInfo = null;
        if (program.getCorporateId() != null) {
            Corporate corp = corporateRepository.findById(program.getCorporateId()).orElse(null);
            if (corp != null) {
                corporateInfo = CorporateInfo.builder()
                    .id(corp.getId())
                    .corporateId(corp.getCorporateId())
                    .legalName(corp.getLegalName())
                    .tradeName(corp.getTradeName())
                    .status(corp.getStatus().name())
                    .build();
            }
        }

        // Get physical account info
        PhysicalAccountInfo physicalAccountInfo = null;
        if (program.getPhysicalAccountId() != null) {
            PhysicalAccount pa = physicalAccountRepository.findById(program.getPhysicalAccountId()).orElse(null);
            if (pa != null) {
                physicalAccountInfo = PhysicalAccountInfo.builder()
                    .id(pa.getId())
                    .accountNumber(pa.getAccountNumber())
                    .accountName(pa.getAccountName())
                    .bankName(pa.getBankName())
                    .currencyCode(pa.getCurrencyCode())
                    .currentBalance(pa.getCurrentBalance())
                    .status(pa.getStatus().name())
                    .build();
                Optional<VirtualAccount> shadow = virtualAccountRepository.findByLinkedPhysicalAccountId(pa.getId());
                if (shadow.isEmpty()) {
                    physicalAccountInfo.setNoShadow(true);
                } else if (!programId.equals(shadow.get().getProgramId()) && shadow.get().getProgramId() != null) {
                    Program holder = programRepository.findById(shadow.get().getProgramId()).orElse(null);
                    physicalAccountInfo.setHeldByProgramCode(holder != null ? holder.getProgramCode() : null);
                    physicalAccountInfo.setHeldByProgramName(holder != null ? holder.getProgramName() : "another program");
                }
            }
        }

        // Get hierarchy info
        HierarchyInfo hierarchyInfo = null;
        if (program.getRootHierarchyNodeId() != null) {
            long totalNodes = hierarchyNodeRepository.countByProgramId(programId);
            long leafNodes = hierarchyNodeRepository.countActiveLeafNodes(programId);
            String rootNodeName = null;
            if (program.getRootHierarchyNodeId() != null) {
                rootNodeName = hierarchyNodeRepository.findById(program.getRootHierarchyNodeId())
                    .map(n -> n.getNodeName())
                    .orElse(null);
            }
            hierarchyInfo = HierarchyInfo.builder()
                .enabled(true)
                .depth(program.getHierarchyDepth())
                .template(program.getDefaultHierarchyTemplate())
                .rootNodeId(program.getRootHierarchyNodeId())
                .rootNodeName(rootNodeName)
                .totalNodes((int) totalNodes)
                .leafNodes((int) leafNodes)
                .build();
        }

        // Get recent virtual accounts
        List<VirtualAccountSummary> recentVAs = virtualAccountRepository
            .findByProgramId(programId, PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")))
            .getContent().stream()
            .map(va -> VirtualAccountSummary.builder()
                .id(va.getId())
                .vaNumber(va.getVaNumber())
                .viban(va.getViban())
                .vaName(va.getVaName())
                .currentBalance(va.getCurrentBalance())
                .status(va.getStatus().name())
                .createdAt(va.getCreatedAt())
                .build())
            .collect(Collectors.toList());

        ProgramUsageStats usageStats = calculateUsageStats(programId);

        // What happened to the program, newest first, from the audit log. Programs from before
        // their changes were recorded get their creation added so the list is never empty.
        List<ActivityLogEntry> activityLog = new ArrayList<>(auditLogRepository
            .findByEntityTypeAndEntityIdOrderByCreatedAtDesc(AUDIT_ENTITY, programId, PageRequest.of(0, 50))
            .map(a -> ActivityLogEntry.builder()
                .action(a.getSummary())
                .user(a.getActor() == null ? "System" : "anonymousUser".equals(a.getActor()) ? "Not signed in" : a.getActor())
                .timestamp(a.getCreatedAt())
                .type(activityType(a))
                .details(detailsOf(a))
                .build())
            .getContent());
        if (activityLog.stream().noneMatch(e -> e.getAction() != null && e.getAction().startsWith("Program created"))) {
            activityLog.add(ActivityLogEntry.builder()
                .action("Program created")
                .user(program.getCreatedBy() != null ? program.getCreatedBy() : "System")
                .timestamp(program.getCreatedAt())
                .type("success")
                .build());
        }

        // Get VIBAN pool info
        VibanPoolInfo vibanPoolInfo = null;
        if (program.getDefaultVibanPoolId() != null) {
            VibanPool pool = vibanPoolRepository.findById(program.getDefaultVibanPoolId()).orElse(null);
            if (pool != null) {
                vibanPoolInfo = VibanPoolInfo.builder()
                    .poolId(pool.getId())
                    .poolName(pool.getPoolName())
                    .generationStrategy(program.getVibanGenerationStrategy() != null
                        ? program.getVibanGenerationStrategy().name() : null)
                    .prefix(pool.getPrefix())
                    .totalVibans(pool.getPoolSize())
                    .availableVibans(pool.getAvailableCount())
                    .assignedVibans(pool.getAssignedCount())
                    .build();
            }
        }

        return ProgramDetailResponse.builder()
            .program(programResponse)
            .corporate(corporateInfo)
            .physicalAccount(physicalAccountInfo)
            .hierarchy(hierarchyInfo)
            .vibanPool(vibanPoolInfo)
            .recentVirtualAccounts(recentVAs)
            .usageStats(usageStats)
            .activityLog(activityLog)
            .build();
    }

    /**
     * Create a new program
     */
    public ProgramResponse createProgram(UUID corporateId, CreateProgramRequest request) {
        log.info("Creating program: {}", request.getProgramCode());

        validateProgramCode(request.getProgramCode());
        // Validate unique program code
        if (programRepository.existsByProgramCode(request.getProgramCode())) {
            throw new BusinessException("Program code already exists: " + request.getProgramCode());
        }

        // Validate corporate exists
        UUID effectiveCorporateId = request.getCorporateId() != null ? request.getCorporateId() : corporateId;
        if (effectiveCorporateId == null) {
            throw new BusinessException("Corporate ID is required");
        }
        if (!corporateRepository.existsById(effectiveCorporateId)) {
            throw new ResourceNotFoundException("Corporate not found: " + effectiveCorporateId);
        }

        // Validate physical account exists (if provided - physical account is optional)
        if (request.getPhysicalAccountId() != null && !physicalAccountRepository.existsById(request.getPhysicalAccountId())) {
            throw new ResourceNotFoundException("Physical account not found: " + request.getPhysicalAccountId());
        }

        Program program = Program.builder()
            // Core
            .programCode(request.getProgramCode())
            .programName(request.getProgramName())
            .description(request.getDescription())
            .corporateId(effectiveCorporateId)
            .physicalAccountId(shadowAccountService.backingAccountOf(request.getShadowAccountIds(), request.getPhysicalAccountId()))
            .currencyCode(request.getCurrencyCode())
            // VA Config
            .vaPrefix(request.getVaPrefix())
            .vaFormat(request.getVaFormat())
            .maxVirtualAccounts(request.getMaxVirtualAccounts())
            .currentVaCount(0)
            // Settlement
            // Feature Flags - Core
            // Hierarchy
            .hierarchyDepth(request.getHierarchyDepth() != null ? request.getHierarchyDepth() : 7)
            .defaultHierarchyTemplate(request.getDefaultHierarchyTemplate())
            // VIBAN
            .defaultVibanPoolId(request.getDefaultVibanPoolId())
            .vibanGenerationStrategy(request.getVibanGenerationStrategy() != null 
                ? VibanGenerationStrategy.valueOf(request.getVibanGenerationStrategy()) 
                : VibanGenerationStrategy.SEQUENTIAL)
            .vibanPrefix(request.getVibanPrefix())
            .vibanBankCode(request.getVibanBankCode())
            // Balance Aggregation
            // Additional Program Types
            // Wallet Config
            .defaultWalletType(request.getDefaultWalletType())
            .defaultPerTransactionLimit(request.getDefaultPerTransactionLimit())
            .defaultDailyLimit(request.getDefaultDailyLimit())
            .defaultWeeklyLimit(request.getDefaultWeeklyLimit())
            .defaultMonthlyLimit(request.getDefaultMonthlyLimit())
            .defaultYearlyLimit(request.getDefaultYearlyLimit())
            .defaultMaxBalance(request.getDefaultMaxBalance())
            .defaultDailyTopupLimit(request.getDefaultDailyTopupLimit())
            .defaultMonthlyTopupLimit(request.getDefaultMonthlyTopupLimit())
            // Wallet Settings
            .minTopup(request.getMinTopup())
            .maxTopup(request.getMaxTopup())
            .walletExpiryDays(request.getWalletExpiryDays())
            // KYC
            .kycRequired(request.getKycRequired() != null ? request.getKycRequired() : false)
            .minKycLevel(request.getMinKycLevel())
            .autoKyc(request.getAutoKyc())
            // Wallet Features
            .allowTopup(request.getAllowTopup() != null ? request.getAllowTopup() : true)
            .allowWithdrawal(request.getAllowWithdrawal() != null ? request.getAllowWithdrawal() : true)
            .allowTransfer(request.getAllowTransfer() != null ? request.getAllowTransfer() : true)
            // Fees
            .issuanceFee(request.getIssuanceFee())
            .monthlyFee(request.getMonthlyFee())
            .topupFeePercent(request.getTopupFeePercent())
            .topupFeeFlat(request.getTopupFeeFlat())
            .withdrawalFeePercent(request.getWithdrawalFeePercent())
            .withdrawalFeeFlat(request.getWithdrawalFeeFlat())
            .transferFeePercent(request.getTransferFeePercent())
            .transferFeeFlat(request.getTransferFeeFlat())
            // Branding
            // Status & Dates
            .status(ProgramStatus.ACTIVE)
            .effectiveFrom(request.getEffectiveFrom())
            .effectiveTo(request.getEffectiveTo())
            .build();

        validateValues(program);
        program = programRepository.save(program);
        log.info("Program created successfully: {} - hierarchyDepth: {}, template: {}",
            program.getId(), program.getHierarchyDepth(), program.getDefaultHierarchyTemplate());

        hierarchyService.bootstrap(program);
        audit(program, "PROGRAM_CREATED", "Program created", null);
        // After bootstrap: the shadows hang under the program's own hierarchy.
        if (request.getShadowAccountIds() != null) {
            shadowAccountService.setProgramShadows(program, request.getShadowAccountIds());
        }

        return toProgramResponse(program);
    }

    /**
     * Update program
     */
    public ProgramResponse updateProgram(UUID programId, UpdateProgramRequest request) {
        log.info("Updating program: {}", programId);

        Program program = findProgramOrThrow(programId);
        if (program.getStatus() == ProgramStatus.CLOSED) {
            throw new BusinessException("Program " + program.getProgramCode() + " is closed and can't be changed");
        }
        Map<String, Object> before = settingsOf(program);
        Set<UUID> shadowsBefore = shadowIdsOf(programId);

        // Update fields if provided
        if (request.getProgramName() != null) program.setProgramName(request.getProgramName());
        if (request.getDescription() != null) program.setDescription(request.getDescription());
        if (request.getVaPrefix() != null) program.setVaPrefix(request.getVaPrefix());
        if (request.getVaFormat() != null) program.setVaFormat(request.getVaFormat());
        if (request.getMaxVirtualAccounts() != null) program.setMaxVirtualAccounts(request.getMaxVirtualAccounts());

        // Feature Flags

        // Hierarchy
        if (request.getHierarchyDepth() != null) program.setHierarchyDepth(request.getHierarchyDepth());
        if (request.getDefaultHierarchyTemplate() != null) program.setDefaultHierarchyTemplate(request.getDefaultHierarchyTemplate());

        // VIBAN
        if (request.getDefaultVibanPoolId() != null) program.setDefaultVibanPoolId(request.getDefaultVibanPoolId());
        if (request.getVibanGenerationStrategy() != null) {
            program.setVibanGenerationStrategy(VibanGenerationStrategy.valueOf(request.getVibanGenerationStrategy()));
        }
        if (request.getVibanPrefix() != null) program.setVibanPrefix(request.getVibanPrefix());
        if (request.getVibanBankCode() != null) program.setVibanBankCode(request.getVibanBankCode());

        // Balance Aggregation

        // Wallet Config
        if (request.getDefaultWalletType() != null) program.setDefaultWalletType(request.getDefaultWalletType());
        if (request.getDefaultPerTransactionLimit() != null) program.setDefaultPerTransactionLimit(request.getDefaultPerTransactionLimit());
        if (request.getDefaultDailyLimit() != null) program.setDefaultDailyLimit(request.getDefaultDailyLimit());
        if (request.getDefaultWeeklyLimit() != null) program.setDefaultWeeklyLimit(request.getDefaultWeeklyLimit());
        if (request.getDefaultMonthlyLimit() != null) program.setDefaultMonthlyLimit(request.getDefaultMonthlyLimit());
        if (request.getDefaultYearlyLimit() != null) program.setDefaultYearlyLimit(request.getDefaultYearlyLimit());
        if (request.getDefaultMaxBalance() != null) program.setDefaultMaxBalance(request.getDefaultMaxBalance());

        // KYC
        if (request.getKycRequired() != null) program.setKycRequired(request.getKycRequired());
        if (request.getMinKycLevel() != null) program.setMinKycLevel(request.getMinKycLevel());
        if (request.getAutoKyc() != null) program.setAutoKyc(request.getAutoKyc());

        // Wallet Features
        if (request.getAllowTopup() != null) program.setAllowTopup(request.getAllowTopup());
        if (request.getAllowWithdrawal() != null) program.setAllowWithdrawal(request.getAllowWithdrawal());
        if (request.getAllowTransfer() != null) program.setAllowTransfer(request.getAllowTransfer());

        // Status & Dates
        if (request.getStatus() != null) {
            ProgramStatus newStatus = ProgramStatus.valueOf(request.getStatus());
            validateStatusTransition(program.getStatus(), newStatus);
            program.setStatus(newStatus);
        }
        if (request.getEffectiveFrom() != null) program.setEffectiveFrom(request.getEffectiveFrom());
        if (request.getEffectiveTo() != null) program.setEffectiveTo(request.getEffectiveTo());

        if (request.getShadowAccountIds() != null) {
            program.setPhysicalAccountId(shadowAccountService.setProgramShadows(program, request.getShadowAccountIds()));
        }

        validateValues(program);
        program = programRepository.save(program);
        log.info("Program updated successfully: {}", programId);
        List<String> changed = changedSettings(before, settingsOf(program));
        boolean bankAccountsChanged = !shadowsBefore.equals(shadowIdsOf(programId));
        if (bankAccountsChanged) changed.add("bank accounts");
        if (!changed.isEmpty()) {
            audit(program, "PROGRAM_UPDATED", "Settings updated", "Changed: " + String.join(", ", changed));
        }

        return toProgramResponse(program);
    }

    /**
     * Update program status
     */
    public ProgramResponse updateProgramStatus(UUID programId, UpdateProgramStatusRequest request) {
        log.info("Updating program status: {} -> {}", programId, request.getStatus());

        Program program = findProgramOrThrow(programId);
        ProgramStatus newStatus = ProgramStatus.valueOf(request.getStatus());

        validateStatusTransition(program.getStatus(), newStatus);

        ProgramStatus oldStatus = program.getStatus();
        program.setStatus(newStatus);
        program = programRepository.save(program);

        log.info("Program status updated successfully: {} -> {}", programId, newStatus);
        audit(program, "PROGRAM_STATUS_CHANGED", "Status changed to " + label(newStatus),
            "From " + label(oldStatus) + (request.getReason() != null ? ". Reason: " + request.getReason() : ""));
        return toProgramResponse(program);
    }

    /**
     * Delete (deactivate) program
     */
    /**
     * Close a program for good (a closed program cannot be reopened). Only its customer accounts
     * block this: its own scaffolding (root, mirrors, exception, settlement) is always active, so
     * counting every active account made closing -- then called delete -- impossible for any
     * program. Its bank accounts are released for other programs.
     */
    public ProgramResponse closeProgram(UUID programId) {
        Program program = findProgramOrThrow(programId);
        if (program.getStatus() == ProgramStatus.CLOSED) {
            throw new BusinessException("Program " + program.getProgramCode() + " is already closed");
        }
        long customerAccounts = virtualAccountRepository.findByProgramId(programId).stream()
            .filter(va -> va.getStatus() == VirtualAccount.VaStatus.ACTIVE)
            .filter(va -> !com.bank.vam.service.treasury.ShadowAccountService.STRUCTURAL.contains(va.getAccountCategory()))
            .count();
        if (customerAccounts > 0) {
            throw new BusinessException("Close or move the program's " + customerAccounts
                + " active customer accounts before closing it");
        }
        int kept = shadowAccountService.releaseProgramShadows(program);
        ProgramStatus old = program.getStatus();
        program.setStatus(ProgramStatus.CLOSED);
        program = programRepository.save(program);
        log.info("Program closed: {}", programId);
        audit(program, "PROGRAM_STATUS_CHANGED", "Program closed",
            "From " + label(old) + (kept > 0 ? "; " + kept + " bank account(s) with accounts under them stay with it" : "; bank accounts released"));
        return toProgramResponse(program);
    }

    /**
     * Clone program
     */
    public ProgramResponse cloneProgram(UUID programId, CloneProgramRequest request) {
        log.info("Cloning program: {}", programId);

        Program source = findProgramOrThrow(programId);

        validateProgramCode(request.getNewProgramCode());
        // Validate unique program code
        if (programRepository.existsByProgramCode(request.getNewProgramCode())) {
            throw new BusinessException("Program code already exists: " + request.getNewProgramCode());
        }

        Program cloned = Program.builder()
            // New identifiers
            .programCode(request.getNewProgramCode())
            .programName(request.getNewProgramName())
            .corporateId(request.getTargetCorporateId() != null ? request.getTargetCorporateId() : source.getCorporateId())
            // Not the source's bank account: that one belongs to the source program, so the clone's
            // accounts on it would be refused and its payments would find no bank account. The
            // clone picks its own in the program's setup.
            .physicalAccountId(request.getTargetPhysicalAccountId())
            // Copy all other fields
            .description(source.getDescription())
            .currencyCode(source.getCurrencyCode())
            .vaPrefix(source.getVaPrefix())
            .vaFormat(source.getVaFormat())
            .maxVirtualAccounts(source.getMaxVirtualAccounts())
            .currentVaCount(0)
            // Feature Flags
            // Hierarchy (don't copy root node - will need to create new one)
            .hierarchyDepth(source.getHierarchyDepth())
            .defaultHierarchyTemplate(source.getDefaultHierarchyTemplate())
            // VIBAN
            .vibanGenerationStrategy(source.getVibanGenerationStrategy())
            .vibanPrefix(source.getVibanPrefix())
            .vibanBankCode(source.getVibanBankCode())
            // Balance Aggregation
            // Wallet Config
            .defaultWalletType(source.getDefaultWalletType())
            .defaultPerTransactionLimit(source.getDefaultPerTransactionLimit())
            .defaultDailyLimit(source.getDefaultDailyLimit())
            .defaultWeeklyLimit(source.getDefaultWeeklyLimit())
            .defaultMonthlyLimit(source.getDefaultMonthlyLimit())
            .defaultYearlyLimit(source.getDefaultYearlyLimit())
            .defaultMaxBalance(source.getDefaultMaxBalance())
            .defaultDailyTopupLimit(source.getDefaultDailyTopupLimit())
            .defaultMonthlyTopupLimit(source.getDefaultMonthlyTopupLimit())
            // Wallet Settings
            .minTopup(source.getMinTopup())
            .maxTopup(source.getMaxTopup())
            .walletExpiryDays(source.getWalletExpiryDays())
            // KYC
            .kycRequired(source.getKycRequired())
            .minKycLevel(source.getMinKycLevel())
            .autoKyc(source.getAutoKyc())
            // Wallet Features
            .allowTopup(source.getAllowTopup())
            .allowWithdrawal(source.getAllowWithdrawal())
            .allowTransfer(source.getAllowTransfer())
            // Fees
            .issuanceFee(source.getIssuanceFee())
            .monthlyFee(source.getMonthlyFee())
            .topupFeePercent(source.getTopupFeePercent())
            .topupFeeFlat(source.getTopupFeeFlat())
            .withdrawalFeePercent(source.getWithdrawalFeePercent())
            .withdrawalFeeFlat(source.getWithdrawalFeeFlat())
            .transferFeePercent(source.getTransferFeePercent())
            .transferFeeFlat(source.getTransferFeeFlat())
            // Branding
            // Status
            .status(ProgramStatus.PENDING_APPROVAL)
            .build();

        cloned = programRepository.save(cloned);
        log.info("Program cloned successfully: {} -> {}", programId, cloned.getId());

        // Every program has a hierarchy; the root node itself is never copied.
        hierarchyService.bootstrap(cloned);
        audit(cloned, "PROGRAM_CREATED", "Program created as a copy of " + source.getProgramCode(), null);

        return toProgramResponse(cloned);
    }

    // ========================================================================
    // STATISTICS
    // ========================================================================

    @Transactional(readOnly = true)
    public ProgramStatsResponse getStats(UUID corporateId) {
        log.debug("Getting program stats for corporate: {}", corporateId);

        List<Program> programs;
        if (corporateId != null) {
            programs = programRepository.findByCorporateId(corporateId);
        } else {
            programs = programRepository.findAll();
        }

        long totalPrograms = programs.size();
        long activePrograms = programs.stream().filter(p -> p.getStatus() == ProgramStatus.ACTIVE).count();
        long inactivePrograms = programs.stream().filter(p -> p.getStatus() == ProgramStatus.INACTIVE).count();
        long suspendedPrograms = programs.stream().filter(p -> p.getStatus() == ProgramStatus.SUSPENDED).count();
        long pendingPrograms = programs.stream().filter(p -> p.getStatus() == ProgramStatus.PENDING_APPROVAL).count();
        long totalVirtualAccounts = programs.stream()
            .mapToLong(p -> virtualAccountRepository.countByProgramId(p.getId()))
            .sum();

        // Programs hold balances in different currencies: group by currency, then convert each group to
        // the market reporting currency. Adding raw amounts across currencies produced a meaningless total.
        java.util.Map<String, BigDecimal> balancesByCurrency = new java.util.TreeMap<>();
        for (Program p : programs) {
            BigDecimal b = programBalance(p);
            if (b != null && p.getCurrencyCode() != null) balancesByCurrency.merge(p.getCurrencyCode(), b, BigDecimal::add);
        }
        String reportingCurrency = marketProfile.getDefaultCurrency();
        List<String> excludedCurrencies = new java.util.ArrayList<>();
        BigDecimal totalBalance = BigDecimal.ZERO;
        for (java.util.Map.Entry<String, BigDecimal> e : balancesByCurrency.entrySet()) {
            try {
                totalBalance = totalBalance.add(fxRateService.convert(e.getValue(), e.getKey(), reportingCurrency));
            } catch (RuntimeException ex) {
                log.warn("No FX rate {} -> {} for program stats; excluding", e.getKey(), reportingCurrency);
                excludedCurrencies.add(e.getKey());
            }
        }

        return ProgramStatsResponse.builder()
            .totalPrograms(totalPrograms)
            .activePrograms(activePrograms)
            .inactivePrograms(inactivePrograms)
            .suspendedPrograms(suspendedPrograms)
            .pendingPrograms(pendingPrograms)
            .totalVirtualAccounts(totalVirtualAccounts)
            .totalBalance(totalBalance)
            .reportingCurrency(reportingCurrency)
            .balancesByCurrency(balancesByCurrency)
            .excludedCurrencies(excludedCurrencies)
            .build();
    }

    // ========================================================================
    // HIERARCHY AUTO-INITIALIZATION
    // ========================================================================

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private Program findProgramOrThrow(UUID programId) {
        return programRepository.findById(programId)
            .orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));
    }

    /**
     * Convert Program entity to ProgramResponse DTO.
     * Maps ALL fields from unified_programs schema.
     */
    private ProgramResponse toProgramResponse(Program program) {
        // Get corporate name
        String corporateName = null;
        if (program.getCorporateId() != null) {
            corporateName = corporateRepository.findById(program.getCorporateId())
                .map(Corporate::getLegalName)
                .orElse(null);
        }

        // Get physical account number
        String physicalAccountNumber = null;
        if (program.getPhysicalAccountId() != null) {
            physicalAccountNumber = physicalAccountRepository.findById(program.getPhysicalAccountId())
                .map(PhysicalAccount::getAccountNumber)
                .orElse(null);
        }

        // Count virtual accounts
        int vaCount = (int) virtualAccountRepository.countByProgramId(program.getId());
        int activeVaCount = (int) virtualAccountRepository.countByProgramIdAndStatus(program.getId(), VirtualAccount.VaStatus.ACTIVE);

        // Sum balance
        BigDecimal totalBalance = programBalance(program);

        // Status info
        String statusLabel = program.getStatus().name();
        String statusVariant = switch (program.getStatus()) {
            case ACTIVE -> "success";
            case INACTIVE -> "neutral";
            case SUSPENDED -> "warning";
            case PENDING_APPROVAL -> "info";
            case CLOSED -> "error";
        };

        return ProgramResponse.builder()
            // Core
            .id(program.getId())
            .programCode(program.getProgramCode())
            .programName(program.getProgramName())
            .description(program.getDescription())
            // Relationships
            .corporateId(program.getCorporateId())
            .corporateName(corporateName)
            .physicalAccountId(program.getPhysicalAccountId())
            .physicalAccountNumber(physicalAccountNumber)
            .currencyCode(program.getCurrencyCode())
            // VA Config
            .vaPrefix(program.getVaPrefix())
            .vaFormat(program.getVaFormat())
            .maxVirtualAccounts(program.getMaxVirtualAccounts())
            .currentVaCount(program.getCurrentVaCount())
            // Settlement
            // Feature Flags - Core
            // ================================================================
            // HIERARCHY SUPPORT
            // ================================================================
            .hierarchyDepth(program.getHierarchyDepth())
            .maxHierarchyDepth(program.getMaxHierarchyDepth())
            .defaultHierarchyTemplate(program.getDefaultHierarchyTemplate())
            .rootHierarchyNodeId(program.getRootHierarchyNodeId())
            // ================================================================
            // VIBAN POOL SETTINGS
            // ================================================================
            .defaultVibanPoolId(program.getDefaultVibanPoolId())
            .vibanGenerationStrategy(program.getVibanGenerationStrategy() != null 
                ? program.getVibanGenerationStrategy().name() : null)
            .vibanPrefix(program.getVibanPrefix())
            .vibanBankCode(program.getVibanBankCode())
            // ================================================================
            // BALANCE AGGREGATION
            // ================================================================
            // ================================================================
            // ADDITIONAL PROGRAM TYPE FLAGS
            // ================================================================
            // ================================================================
            // WALLET CONFIGURATION
            // ================================================================
            .defaultWalletType(program.getDefaultWalletType())
            .defaultPerTransactionLimit(program.getDefaultPerTransactionLimit())
            .defaultDailyLimit(program.getDefaultDailyLimit())
            .defaultWeeklyLimit(program.getDefaultWeeklyLimit())
            .defaultMonthlyLimit(program.getDefaultMonthlyLimit())
            .defaultYearlyLimit(program.getDefaultYearlyLimit())
            .defaultMaxBalance(program.getDefaultMaxBalance())
            .defaultDailyTopupLimit(program.getDefaultDailyTopupLimit())
            .defaultMonthlyTopupLimit(program.getDefaultMonthlyTopupLimit())
            .minTopup(program.getMinTopup())
            .maxTopup(program.getMaxTopup())
            .walletExpiryDays(program.getWalletExpiryDays())
            // KYC
            .kycRequired(program.getKycRequired())
            .minKycLevel(program.getMinKycLevel())
            .autoKyc(program.getAutoKyc())
            // Wallet Features
            .allowTopup(program.getAllowTopup())
            .allowWithdrawal(program.getAllowWithdrawal())
            .allowTransfer(program.getAllowTransfer())
            // Fees
            .issuanceFee(program.getIssuanceFee())
            .monthlyFee(program.getMonthlyFee())
            .topupFeePercent(program.getTopupFeePercent())
            .topupFeeFlat(program.getTopupFeeFlat())
            .withdrawalFeePercent(program.getWithdrawalFeePercent())
            .withdrawalFeeFlat(program.getWithdrawalFeeFlat())
            .transferFeePercent(program.getTransferFeePercent())
            .transferFeeFlat(program.getTransferFeeFlat())
            // Branding
            // Status
            .status(program.getStatus().name())
            .statusLabel(statusLabel)
            .statusVariant(statusVariant)
            // Effective Dates
            .effectiveFrom(program.getEffectiveFrom())
            .effectiveTo(program.getEffectiveTo())
            // Statistics
            .virtualAccountCount(vaCount)
            .activeVirtualAccountCount(activeVaCount)
            .totalBalance(totalBalance != null ? totalBalance : BigDecimal.ZERO)
            // Metadata
            .createdAt(program.getCreatedAt())
            .updatedAt(program.getUpdatedAt())
            .createdBy(program.getCreatedBy())
            .updatedBy(program.getUpdatedBy())
            .version(program.getVersion())
            .build();
    }

    private void validateStatusTransition(ProgramStatus current, ProgramStatus target) {
        switch (current) {
            case PENDING_APPROVAL:
                if (target != ProgramStatus.ACTIVE && target != ProgramStatus.INACTIVE) {
                    throw new BusinessException("Invalid status transition from PENDING_APPROVAL to " + target);
                }
                break;
            case ACTIVE:
                if (target != ProgramStatus.SUSPENDED && target != ProgramStatus.INACTIVE && target != ProgramStatus.CLOSED) {
                    throw new BusinessException("Invalid status transition from ACTIVE to " + target);
                }
                break;
            case SUSPENDED:
                if (target != ProgramStatus.ACTIVE && target != ProgramStatus.INACTIVE && target != ProgramStatus.CLOSED) {
                    throw new BusinessException("Invalid status transition from SUSPENDED to " + target);
                }
                break;
            case INACTIVE:
                if (target != ProgramStatus.PENDING_APPROVAL && target != ProgramStatus.ACTIVE) {
                    throw new BusinessException("Inactive programs must go through approval to be reactivated");
                }
                break;
            case CLOSED:
                throw new BusinessException("Closed programs cannot be reopened");
        }
    }

    private ProgramUsageStats calculateUsageStats(UUID programId) {
        return ProgramUsageStats.builder()
            .totalTransactions(0)
            .todayTransactions(0)
            .totalVolume(BigDecimal.ZERO)
            .todayVolume(BigDecimal.ZERO)
            .averageBalance(BigDecimal.ZERO)
            .lastTransactionAt(null)
            .build();
    }

    // ========================================================================
    // ACTIVITY (audit log)
    // ========================================================================

    private static final String AUDIT_ENTITY = "Program";

    private void audit(Program program, String eventType, String summary, String details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("programCode", program.getProgramCode());
        if (details != null) payload.put("details", details);
        auditLogService.record(eventType, AUDIT_ENTITY, program.getId(), program.getCorporateId(), summary, payload);
    }

    private static String label(ProgramStatus status) {
        if (status == null) return "none";
        String s = status.name().replace('_', ' ').toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String activityType(com.bank.vam.entity.audit.AuditLog a) {
        String summary = a.getSummary() == null ? "" : a.getSummary();
        if (summary.contains("Suspended") || summary.contains("deleted")) return "warning";
        return "PROGRAM_UPDATED".equals(a.getEventType()) ? "info" : "success";
    }

    private String detailsOf(com.bank.vam.entity.audit.AuditLog a) {
        if (a.getPayload() == null) return null;
        try {
            Object d = new com.fasterxml.jackson.databind.ObjectMapper().readTree(a.getPayload()).path("details").asText(null);
            return (String) d;
        } catch (Exception e) {
            return null;
        }
    }

    /** Record something done to a program elsewhere (e.g. its wallet fees) in its activity. */
    public void recordActivity(UUID programId, String eventType, String summary) {
        programRepository.findById(programId).ifPresent(p -> audit(p, eventType, summary, null));
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper SETTINGS_MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

    /** The program's own settings as name -> value (no counts, balances or timestamps). */
    private Map<String, Object> settingsOf(Program program) {
        Map<String, Object> all = SETTINGS_MAPPER.convertValue(toProgramResponse(program),
            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        all.keySet().removeIf(k -> k.endsWith("Count") || k.contains("Balance") || k.endsWith("At")
            || k.endsWith("Name") && !k.equals("programName") || k.equals("id") || k.equals("stats"));
        return all;
    }

    private static List<String> changedSettings(Map<String, Object> before, Map<String, Object> after) {
        List<String> changed = new ArrayList<>();
        for (String key : after.keySet()) {
            if (!Objects.equals(before.get(key), after.get(key))) {
                changed.add(key.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase());
            }
        }
        return changed;
    }

    private Set<UUID> shadowIdsOf(UUID programId) {
        return shadowAccountService.getShadowAccountsByProgram(programId).stream()
            .map(VirtualAccount::getId).collect(Collectors.toSet());
    }

    /** Codes are identifiers: capitals, digits, dash and underscore, starting with a letter or digit. */
    private static void validateProgramCode(String code) {
        if (code == null || !code.matches("[A-Z0-9][A-Z0-9_-]{0,49}")) {
            throw new BusinessException("Program code must be 1-50 capital letters, digits, '-' or '_' (e.g. COLL-001)");
        }
    }

    /**
     * Limits and fees are amounts: none negative, at least one account allowed, and each spend
     * limit no larger than the next longer period's (per transaction <= daily <= weekly <=
     * monthly <= yearly, where set). The form checks the same, but the API is the rule.
     */
    private static void validateValues(Program p) {
        if (p.getMaxVirtualAccounts() != null && p.getMaxVirtualAccounts() < 1) {
            throw new BusinessException("Max virtual accounts must be at least 1 (leave it blank for no limit)");
        }
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        amounts.put("per-transaction limit", p.getDefaultPerTransactionLimit());
        amounts.put("daily limit", p.getDefaultDailyLimit());
        amounts.put("weekly limit", p.getDefaultWeeklyLimit());
        amounts.put("monthly limit", p.getDefaultMonthlyLimit());
        amounts.put("yearly limit", p.getDefaultYearlyLimit());
        amounts.put("maximum balance", p.getDefaultMaxBalance());
        amounts.put("minimum top-up", p.getMinTopup());
        amounts.put("maximum top-up", p.getMaxTopup());
        amounts.put("daily top-up limit", p.getDefaultDailyTopupLimit());
        amounts.put("monthly top-up limit", p.getDefaultMonthlyTopupLimit());
        amounts.put("issuance fee", p.getIssuanceFee());
        amounts.put("monthly fee", p.getMonthlyFee());
        amounts.put("top-up fee %", p.getTopupFeePercent());
        amounts.put("top-up fee", p.getTopupFeeFlat());
        amounts.put("withdrawal fee %", p.getWithdrawalFeePercent());
        amounts.put("withdrawal fee", p.getWithdrawalFeeFlat());
        amounts.put("transfer fee %", p.getTransferFeePercent());
        amounts.put("transfer fee", p.getTransferFeeFlat());
        amounts.forEach((name, v) -> {
            if (v != null && v.signum() < 0) throw new BusinessException("The " + name + " can't be negative");
        });
        String[] periods = {"per-transaction limit", "daily limit", "weekly limit", "monthly limit", "yearly limit"};
        String prevName = null;
        BigDecimal prev = null;
        for (String name : periods) {
            BigDecimal v = amounts.get(name);
            if (v == null) continue;
            if (prev != null && prev.compareTo(v) > 0) {
                throw new BusinessException("The " + prevName + " can't be more than the " + name);
            }
            prev = v;
            prevName = name;
        }
        orderedPair(amounts, "minimum top-up", "maximum top-up");
        orderedPair(amounts, "daily top-up limit", "monthly top-up limit");
    }

    private static void orderedPair(Map<String, BigDecimal> amounts, String low, String high) {
        BigDecimal a = amounts.get(low), b = amounts.get(high);
        if (a != null && b != null && a.compareTo(b) > 0) {
            throw new BusinessException("The " + low + " can't be more than the " + high);
        }
    }

    /** Structural accounts: containers hold no money of their own; mirrors restate money held elsewhere. */
    private static final java.util.EnumSet<VirtualAccount.AccountCategory> NOT_MONEY = java.util.EnumSet.of(
        VirtualAccount.AccountCategory.ROOT, VirtualAccount.AccountCategory.AGGREGATION,
        VirtualAccount.AccountCategory.CURRENCY_MIRROR,
        VirtualAccount.AccountCategory.PHYSICAL_MIRROR, VirtualAccount.AccountCategory.EXTERNAL_MIRROR);

    /**
     * Money held in the program's accounts, in the program's currency. Same rule as the balance
     * hierarchy's rollup (BalanceStructureService.recomputeRollup): no containers, no currency
     * mirrors, IC payables negative -- except that bank-mirror (shadow) accounts are left out too,
     * as their balance is the bank's, not money in the program's accounts.
     *
     * It used to be a raw SUM(currentBalance) over every account: mirrors counted on top of what
     * they restate, stray balances on root accounts included, and currencies added unconverted.
     */
    private BigDecimal programBalance(Program program) {
        BigDecimal total = BigDecimal.ZERO;
        for (Object[] row : virtualAccountRepository.sumMoneyByProgramGroupedByCurrency(
                program.getId(), NOT_MONEY, VirtualAccount.MirrorAccountType.IC_PAYABLE)) {
            String currency = (String) row[0];
            BigDecimal amount = (BigDecimal) row[1];
            if (currency == null || amount == null || amount.signum() == 0) continue;
            if (currency.equals(program.getCurrencyCode())) { total = total.add(amount); continue; }
            try {
                total = total.add(fxRateService.convert(amount, currency, program.getCurrencyCode()));
            } catch (com.bank.vam.exception.BusinessException noRate) {
                // Left out, as the balance hierarchy does -- never counted 1:1.
                log.warn("No FX rate {} -> {} for program {}; excluded from its balance", currency, program.getCurrencyCode(), program.getProgramCode());
            }
        }
        return total;
    }
}
