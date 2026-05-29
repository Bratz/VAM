package com.bank.vam.service;

import com.bank.vam.dto.ProgramDto.*;
import com.bank.vam.entity.Corporate;
import com.bank.vam.entity.PhysicalAccount;
import com.bank.vam.entity.Program;
import com.bank.vam.entity.Program.ProgramStatus;
import com.bank.vam.entity.Program.ProgramType;
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
import java.util.List;
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
                corporateId, request.getQuery(), request.getProgramType(), 
                request.getStatus(), pageable
            );
        } else {
            programPage = programRepository.findAllWithFilters(
                request.getQuery(), request.getProgramType(), 
                request.getStatus(), pageable
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
            }
        }

        // Get hierarchy info
        HierarchyInfo hierarchyInfo = null;
        if (Boolean.TRUE.equals(program.getHierarchyEnabled())) {
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

        List<ActivityLogEntry> activityLog = List.of(
            ActivityLogEntry.builder()
                .action("Program created")
                .user(program.getCreatedBy() != null ? program.getCreatedBy() : "System")
                .timestamp(program.getCreatedAt())
                .type("success")
                .details("Program " + program.getProgramCode() + " was created")
                .build()
        );

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
            .programType(ProgramType.valueOf(request.getProgramType()))
            .description(request.getDescription())
            .corporateId(effectiveCorporateId)
            .physicalAccountId(request.getPhysicalAccountId())
            .currencyCode(request.getCurrencyCode())
            // VA Config
            .vaPrefix(request.getVaPrefix())
            .vaFormat(request.getVaFormat())
            .maxVirtualAccounts(request.getMaxVirtualAccounts())
            .currentVaCount(0)
            .autoReconciliation(request.getAutoReconciliation() != null ? request.getAutoReconciliation() : true)
            // Settlement
            .settlementFrequency(request.getSettlementFrequency())
            .settlementTime(request.getSettlementTime())
            .minBalanceThreshold(request.getMinBalanceThreshold())
            // Feature Flags - Core
            .vibanEnabled(request.getVibanEnabled() != null ? request.getVibanEnabled() : false)
            .walletEnabled(request.getWalletEnabled() != null ? request.getWalletEnabled() : false)
            .escrowEnabled(request.getEscrowEnabled() != null ? request.getEscrowEnabled() : false)
            .ihbEnabled(request.getIhbEnabled() != null ? request.getIhbEnabled() : false)
            // Hierarchy
            .hierarchyEnabled(request.getHierarchyEnabled() != null ? request.getHierarchyEnabled() : false)
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
            .balanceAggregationIntervalMinutes(request.getBalanceAggregationIntervalMinutes() != null 
                ? request.getBalanceAggregationIntervalMinutes() : 5)
            .realtimeBalancePropagation(request.getRealtimeBalancePropagation() != null 
                ? request.getRealtimeBalancePropagation() : true)
            // Additional Program Types
            .loyaltyEnabled(request.getLoyaltyEnabled() != null ? request.getLoyaltyEnabled() : false)
            .giftCardEnabled(request.getGiftCardEnabled() != null ? request.getGiftCardEnabled() : false)
            .corporateCardEnabled(request.getCorporateCardEnabled() != null ? request.getCorporateCardEnabled() : false)
            .mobileMoneyEnabled(request.getMobileMoneyEnabled() != null ? request.getMobileMoneyEnabled() : false)
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
            .minWithdrawal(request.getMinWithdrawal())
            .maxWithdrawal(request.getMaxWithdrawal())
            .walletExpiryDays(request.getWalletExpiryDays())
            .inactiveExpiryDays(request.getInactiveExpiryDays())
            // KYC
            .kycRequired(request.getKycRequired() != null ? request.getKycRequired() : false)
            .minKycLevel(request.getMinKycLevel())
            .autoKyc(request.getAutoKyc())
            .kycValidityDays(request.getKycValidityDays())
            // Wallet Features
            .allowTopup(request.getAllowTopup() != null ? request.getAllowTopup() : true)
            .allowWithdrawal(request.getAllowWithdrawal() != null ? request.getAllowWithdrawal() : true)
            .allowTransfer(request.getAllowTransfer() != null ? request.getAllowTransfer() : true)
            .allowPayment(request.getAllowPayment() != null ? request.getAllowPayment() : true)
            .allowBulkOperations(request.getAllowBulkOperations() != null ? request.getAllowBulkOperations() : false)
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
            .brandName(request.getBrandName())
            .brandLogoUrl(request.getBrandLogoUrl())
            // Status & Dates
            .status(ProgramStatus.ACTIVE)
            .effectiveFrom(request.getEffectiveFrom())
            .effectiveTo(request.getEffectiveTo())
            .build();

        program = programRepository.save(program);
        log.info("Program created successfully: {} - hierarchyEnabled: {}, hierarchyDepth: {}, template: {}",
            program.getId(), program.getHierarchyEnabled(), program.getHierarchyDepth(), program.getDefaultHierarchyTemplate());

        // Auto-initialize hierarchy if hierarchyEnabled is true
        if (Boolean.TRUE.equals(program.getHierarchyEnabled())) {
            log.info("Starting auto-initialization for program {} with depth {}", program.getProgramCode(), program.getHierarchyDepth());
            autoInitializeHierarchy(program);
        }

        return toProgramResponse(program);
    }

    /**
     * Update program
     */
    public ProgramResponse updateProgram(UUID programId, UpdateProgramRequest request) {
        log.info("Updating program: {}", programId);

        Program program = findProgramOrThrow(programId);

        // Update fields if provided
        if (request.getProgramName() != null) program.setProgramName(request.getProgramName());
        if (request.getDescription() != null) program.setDescription(request.getDescription());
        if (request.getVaPrefix() != null) program.setVaPrefix(request.getVaPrefix());
        if (request.getVaFormat() != null) program.setVaFormat(request.getVaFormat());
        if (request.getMaxVirtualAccounts() != null) program.setMaxVirtualAccounts(request.getMaxVirtualAccounts());
        if (request.getAutoReconciliation() != null) program.setAutoReconciliation(request.getAutoReconciliation());
        if (request.getSettlementFrequency() != null) program.setSettlementFrequency(request.getSettlementFrequency());
        if (request.getSettlementTime() != null) program.setSettlementTime(request.getSettlementTime());
        if (request.getMinBalanceThreshold() != null) program.setMinBalanceThreshold(request.getMinBalanceThreshold());

        // Feature Flags
        if (request.getVibanEnabled() != null) program.setVibanEnabled(request.getVibanEnabled());
        if (request.getWalletEnabled() != null) program.setWalletEnabled(request.getWalletEnabled());
        if (request.getEscrowEnabled() != null) program.setEscrowEnabled(request.getEscrowEnabled());
        if (request.getIhbEnabled() != null) program.setIhbEnabled(request.getIhbEnabled());
        if (request.getLoyaltyEnabled() != null) program.setLoyaltyEnabled(request.getLoyaltyEnabled());
        if (request.getGiftCardEnabled() != null) program.setGiftCardEnabled(request.getGiftCardEnabled());
        if (request.getCorporateCardEnabled() != null) program.setCorporateCardEnabled(request.getCorporateCardEnabled());
        if (request.getMobileMoneyEnabled() != null) program.setMobileMoneyEnabled(request.getMobileMoneyEnabled());

        // Hierarchy
        if (request.getHierarchyEnabled() != null) program.setHierarchyEnabled(request.getHierarchyEnabled());
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
        if (request.getBalanceAggregationIntervalMinutes() != null) {
            program.setBalanceAggregationIntervalMinutes(request.getBalanceAggregationIntervalMinutes());
        }
        if (request.getRealtimeBalancePropagation() != null) {
            program.setRealtimeBalancePropagation(request.getRealtimeBalancePropagation());
        }

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
        if (request.getKycValidityDays() != null) program.setKycValidityDays(request.getKycValidityDays());

        // Wallet Features
        if (request.getAllowTopup() != null) program.setAllowTopup(request.getAllowTopup());
        if (request.getAllowWithdrawal() != null) program.setAllowWithdrawal(request.getAllowWithdrawal());
        if (request.getAllowTransfer() != null) program.setAllowTransfer(request.getAllowTransfer());
        if (request.getAllowPayment() != null) program.setAllowPayment(request.getAllowPayment());
        if (request.getAllowBulkOperations() != null) program.setAllowBulkOperations(request.getAllowBulkOperations());

        // Status & Dates
        if (request.getStatus() != null) {
            ProgramStatus newStatus = ProgramStatus.valueOf(request.getStatus());
            validateStatusTransition(program.getStatus(), newStatus);
            program.setStatus(newStatus);
        }
        if (request.getEffectiveFrom() != null) program.setEffectiveFrom(request.getEffectiveFrom());
        if (request.getEffectiveTo() != null) program.setEffectiveTo(request.getEffectiveTo());

        program = programRepository.save(program);
        log.info("Program updated successfully: {}", programId);

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

        program.setStatus(newStatus);
        program = programRepository.save(program);

        log.info("Program status updated successfully: {} -> {}", programId, newStatus);
        return toProgramResponse(program);
    }

    /**
     * Delete (deactivate) program
     */
    public void deleteProgram(UUID programId) {
        log.info("Deactivating program: {}", programId);

        Program program = findProgramOrThrow(programId);

        // Check if program has active virtual accounts
        long activeVaCount = virtualAccountRepository.countByProgramIdAndStatus(programId, VirtualAccount.VaStatus.ACTIVE);
        if (activeVaCount > 0) {
            throw new BusinessException("Cannot delete program with " + activeVaCount + " active virtual accounts");
        }

        program.setStatus(ProgramStatus.INACTIVE);
        programRepository.save(program);

        log.info("Program deactivated successfully: {}", programId);
    }

    /**
     * Clone program
     */
    public ProgramResponse cloneProgram(UUID programId, CloneProgramRequest request) {
        log.info("Cloning program: {}", programId);

        Program source = findProgramOrThrow(programId);

        // Validate unique program code
        if (programRepository.existsByProgramCode(request.getNewProgramCode())) {
            throw new BusinessException("Program code already exists: " + request.getNewProgramCode());
        }

        Program cloned = Program.builder()
            // New identifiers
            .programCode(request.getNewProgramCode())
            .programName(request.getNewProgramName())
            .corporateId(request.getTargetCorporateId() != null ? request.getTargetCorporateId() : source.getCorporateId())
            .physicalAccountId(request.getTargetPhysicalAccountId() != null ? request.getTargetPhysicalAccountId() : source.getPhysicalAccountId())
            // Copy all other fields
            .programType(source.getProgramType())
            .description(source.getDescription())
            .currencyCode(source.getCurrencyCode())
            .vaPrefix(source.getVaPrefix())
            .vaFormat(source.getVaFormat())
            .maxVirtualAccounts(source.getMaxVirtualAccounts())
            .currentVaCount(0)
            .autoReconciliation(source.getAutoReconciliation())
            .settlementFrequency(source.getSettlementFrequency())
            .settlementTime(source.getSettlementTime())
            .minBalanceThreshold(source.getMinBalanceThreshold())
            // Feature Flags
            .vibanEnabled(source.getVibanEnabled())
            .walletEnabled(source.getWalletEnabled())
            .escrowEnabled(source.getEscrowEnabled())
            .ihbEnabled(source.getIhbEnabled())
            .loyaltyEnabled(source.getLoyaltyEnabled())
            .giftCardEnabled(source.getGiftCardEnabled())
            .corporateCardEnabled(source.getCorporateCardEnabled())
            .mobileMoneyEnabled(source.getMobileMoneyEnabled())
            // Hierarchy (don't copy root node - will need to create new one)
            .hierarchyEnabled(source.getHierarchyEnabled())
            .hierarchyDepth(source.getHierarchyDepth())
            .defaultHierarchyTemplate(source.getDefaultHierarchyTemplate())
            // VIBAN
            .vibanGenerationStrategy(source.getVibanGenerationStrategy())
            .vibanPrefix(source.getVibanPrefix())
            .vibanBankCode(source.getVibanBankCode())
            // Balance Aggregation
            .balanceAggregationIntervalMinutes(source.getBalanceAggregationIntervalMinutes())
            .realtimeBalancePropagation(source.getRealtimeBalancePropagation())
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
            .minWithdrawal(source.getMinWithdrawal())
            .maxWithdrawal(source.getMaxWithdrawal())
            .walletExpiryDays(source.getWalletExpiryDays())
            .inactiveExpiryDays(source.getInactiveExpiryDays())
            // KYC
            .kycRequired(source.getKycRequired())
            .minKycLevel(source.getMinKycLevel())
            .autoKyc(source.getAutoKyc())
            .kycValidityDays(source.getKycValidityDays())
            // Wallet Features
            .allowTopup(source.getAllowTopup())
            .allowWithdrawal(source.getAllowWithdrawal())
            .allowTransfer(source.getAllowTransfer())
            .allowPayment(source.getAllowPayment())
            .allowBulkOperations(source.getAllowBulkOperations())
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
            .brandName(source.getBrandName())
            .brandLogoUrl(source.getBrandLogoUrl())
            // Status
            .status(ProgramStatus.PENDING_APPROVAL)
            .build();

        cloned = programRepository.save(cloned);
        log.info("Program cloned successfully: {} -> {}", programId, cloned.getId());

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

        // By Type
        long collectionPrograms = programs.stream().filter(p -> p.getProgramType() == ProgramType.COLLECTION).count();
        long vibanPrograms = programs.stream().filter(p -> p.getProgramType() == ProgramType.VIBAN).count();
        long escrowPrograms = programs.stream().filter(p -> p.getProgramType() == ProgramType.ESCROW).count();
        long walletPrograms = programs.stream().filter(p -> p.getProgramType() == ProgramType.WALLET).count();
        long ihbPrograms = programs.stream().filter(p -> p.getProgramType() == ProgramType.IHB).count();
        long payablesPrograms = programs.stream().filter(p -> p.getProgramType() == ProgramType.PAYABLES).count();
        long loyaltyPrograms = programs.stream().filter(p -> p.getProgramType() == ProgramType.LOYALTY).count();
        long giftCardPrograms = programs.stream().filter(p -> p.getProgramType() == ProgramType.GIFT_CARD).count();
        long corporateCardPrograms = programs.stream().filter(p -> p.getProgramType() == ProgramType.CORPORATE_CARD).count();
        long mobileMoneyPrograms = programs.stream().filter(p -> p.getProgramType() == ProgramType.MOBILE_MONEY).count();

        // By Feature
        long hierarchyEnabledPrograms = programs.stream().filter(p -> Boolean.TRUE.equals(p.getHierarchyEnabled())).count();
        long vibanEnabledPrograms = programs.stream().filter(p -> Boolean.TRUE.equals(p.getVibanEnabled())).count();

        // Totals
        long totalVirtualAccounts = programs.stream()
            .mapToLong(p -> virtualAccountRepository.countByProgramId(p.getId()))
            .sum();

        BigDecimal totalBalance = programs.stream()
            .map(p -> virtualAccountRepository.sumBalanceByProgramId(p.getId()))
            .filter(b -> b != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return ProgramStatsResponse.builder()
            .totalPrograms(totalPrograms)
            .activePrograms(activePrograms)
            .inactivePrograms(inactivePrograms)
            .suspendedPrograms(suspendedPrograms)
            .pendingPrograms(pendingPrograms)
            .collectionPrograms(collectionPrograms)
            .vibanPrograms(vibanPrograms)
            .escrowPrograms(escrowPrograms)
            .walletPrograms(walletPrograms)
            .ihbPrograms(ihbPrograms)
            .payablesPrograms(payablesPrograms)
            .loyaltyPrograms(loyaltyPrograms)
            .giftCardPrograms(giftCardPrograms)
            .corporateCardPrograms(corporateCardPrograms)
            .mobileMoneyPrograms(mobileMoneyPrograms)
            .hierarchyEnabledPrograms(hierarchyEnabledPrograms)
            .vibanEnabledPrograms(vibanEnabledPrograms)
            .totalVirtualAccounts(totalVirtualAccounts)
            .totalBalance(totalBalance)
            .build();
    }

    // ========================================================================
    // HIERARCHY AUTO-INITIALIZATION
    // ========================================================================

    /**
     * Automatically initialize hierarchy when program is created with hierarchyEnabled=true.
     * Creates ROOT node and ROOT VA based on program configuration.
     * Also applies level configs if template is specified.
     */
    private void autoInitializeHierarchy(Program program) {
        log.info("Auto-initializing hierarchy for program: {} ({})", program.getProgramCode(), program.getId());

        try {
            // Build initialization request
            InitializeHierarchyRequest initRequest = InitializeHierarchyRequest.builder()
                .rootName(program.getProgramName() + " - Group Treasury")
                .rootCode("ROOT")
                .baseCurrency(program.getCurrencyCode())
                .createExceptionVa(true)  // Always create exception VA for proper routing
                .createCurrencyMirror(true)  // Create currency mirror for multi-currency support
                .templateType(program.getDefaultHierarchyTemplate())  // Use selected template if any
                .build();

            // Initialize the hierarchy
            InitializationResponse response = hierarchyService.initializeHierarchyWithResponse(
                program.getId(),
                initRequest
            );

            if (response.isSuccess()) {
                log.info("✓ Hierarchy auto-initialized for program {} - ROOT node: {}, ROOT VA: {}",
                    program.getProgramCode(), response.getRootNodeId(), response.getRootVaId());
                // Note: Template is already applied inside initializeHierarchyWithResponse()
                // No need to call applyTemplate() again here
            } else {
                log.warn("Hierarchy auto-initialization returned non-success for program {}: {} - {}",
                    program.getProgramCode(), response.getStatus(), response.getMessage());
            }
        } catch (Exception e) {
            log.error("Failed to auto-initialize hierarchy for program {}: {}",
                program.getProgramCode(), e.getMessage(), e);
            // Don't throw - program is created, hierarchy can be initialized manually later
        }
    }

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
        BigDecimal totalBalance = virtualAccountRepository.sumBalanceByProgramId(program.getId());

        // Status info
        String statusLabel = program.getStatus().name();
        String statusVariant = switch (program.getStatus()) {
            case ACTIVE -> "success";
            case INACTIVE -> "neutral";
            case SUSPENDED -> "warning";
            case PENDING_APPROVAL -> "info";
            case CLOSED -> "error";
        };

        // Program type label
        String programTypeLabel = program.getProgramType().name();

        return ProgramResponse.builder()
            // Core
            .id(program.getId())
            .programCode(program.getProgramCode())
            .programName(program.getProgramName())
            .programType(program.getProgramType().name())
            .programTypeLabel(programTypeLabel)
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
            .autoReconciliation(program.getAutoReconciliation())
            // Settlement
            .settlementFrequency(program.getSettlementFrequency())
            .settlementTime(program.getSettlementTime())
            .minBalanceThreshold(program.getMinBalanceThreshold())
            // Feature Flags - Core
            .vibanEnabled(program.getVibanEnabled())
            .walletEnabled(program.getWalletEnabled())
            .escrowEnabled(program.getEscrowEnabled())
            .ihbEnabled(program.getIhbEnabled())
            // ================================================================
            // HIERARCHY SUPPORT
            // ================================================================
            .hierarchyEnabled(program.getHierarchyEnabled())
            .hierarchyDepth(program.getHierarchyDepth())
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
            .balanceAggregationIntervalMinutes(program.getBalanceAggregationIntervalMinutes())
            .realtimeBalancePropagation(program.getRealtimeBalancePropagation())
            // ================================================================
            // ADDITIONAL PROGRAM TYPE FLAGS
            // ================================================================
            .loyaltyEnabled(program.getLoyaltyEnabled())
            .giftCardEnabled(program.getGiftCardEnabled())
            .corporateCardEnabled(program.getCorporateCardEnabled())
            .mobileMoneyEnabled(program.getMobileMoneyEnabled())
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
            .minWithdrawal(program.getMinWithdrawal())
            .maxWithdrawal(program.getMaxWithdrawal())
            .walletExpiryDays(program.getWalletExpiryDays())
            .inactiveExpiryDays(program.getInactiveExpiryDays())
            // KYC
            .kycRequired(program.getKycRequired())
            .minKycLevel(program.getMinKycLevel())
            .autoKyc(program.getAutoKyc())
            .kycValidityDays(program.getKycValidityDays())
            // Wallet Features
            .allowTopup(program.getAllowTopup())
            .allowWithdrawal(program.getAllowWithdrawal())
            .allowTransfer(program.getAllowTransfer())
            .allowPayment(program.getAllowPayment())
            .allowBulkOperations(program.getAllowBulkOperations())
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
            .brandName(program.getBrandName())
            .brandLogoUrl(program.getBrandLogoUrl())
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
}