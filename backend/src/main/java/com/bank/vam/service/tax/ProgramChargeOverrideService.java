package com.bank.vam.service.tax;

import com.bank.vam.dto.tax.ProgramChargeOverrideDto.*;
import com.bank.vam.entity.Program;
import com.bank.vam.entity.tax.ChargeConfiguration;
import com.bank.vam.entity.tax.ProgramChargeOverride;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.ProgramRepository;
import com.bank.vam.repository.tax.ChargeConfigurationRepository;
import com.bank.vam.repository.tax.ProgramChargeOverrideRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Program Charge Override Service - Manages program-specific fee configurations.
 * 
 * Features:
 * - CRUD for program charge overrides
 * - Wallet fee configuration (convergence with ChargeConfiguration)
 * - Fee calculation with override support
 * - Bulk operations for program setup
 * - COMPLETE WAIVER SUPPORT for all 6 wallet fee types
 * 
 * Wallet Charge Codes:
 * - WALLET_TOPUP: Topup fee (percent + flat)
 * - WALLET_WITHDRAWAL: Withdrawal fee (percent + flat)
 * - WALLET_TRANSFER: Transfer fee (percent + flat)
 * - WALLET_ISSUANCE: One-time issuance fee
 * - WALLET_MONTHLY: Monthly maintenance fee
 * - WALLET_INACTIVITY: Inactivity fee
 * 
 * VAM Compliance:
 * - Transparent fee disclosure
 * - Audit trail for fee changes
 * - Tiered pricing support via base ChargeConfiguration
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProgramChargeOverrideService {

    private final ProgramChargeOverrideRepository overrideRepository;
    private final ChargeConfigurationRepository chargeConfigRepository;
    private final ProgramRepository programRepository;

    // Standard wallet charge codes
    public static final String WALLET_TOPUP = "WALLET_TOPUP";
    public static final String WALLET_WITHDRAWAL = "WALLET_WITHDRAWAL";
    public static final String WALLET_TRANSFER = "WALLET_TRANSFER";
    public static final String WALLET_ISSUANCE = "WALLET_ISSUANCE";
    public static final String WALLET_MONTHLY = "WALLET_MONTHLY";
    public static final String WALLET_INACTIVITY = "WALLET_INACTIVITY";

    private static final List<String> WALLET_CHARGE_CODES = Arrays.asList(
        WALLET_TOPUP, WALLET_WITHDRAWAL, WALLET_TRANSFER, 
        WALLET_ISSUANCE, WALLET_MONTHLY, WALLET_INACTIVITY
    );

    // ========================================================================
    // CRUD OPERATIONS
    // ========================================================================

    @Transactional
    public OverrideResponse createOverride(CreateOverrideRequest request) {
        // Validate program exists
        Program program = programRepository.findById(request.getProgramId())
            .orElseThrow(() -> new ResourceNotFoundException("Program not found: " + request.getProgramId()));

        // Validate charge code exists
        ChargeConfiguration baseConfig = chargeConfigRepository.findActiveByCode(request.getChargeCode())
            .orElseThrow(() -> new ResourceNotFoundException("Charge configuration not found: " + request.getChargeCode()));

        // Check for existing override
        if (overrideRepository.existsActiveByProgramIdAndChargeCode(
                request.getProgramId(), request.getChargeCode())) {
            throw new BusinessException("Active override already exists for program " + 
                program.getProgramCode() + " and charge " + request.getChargeCode());
        }

        ProgramChargeOverride override = ProgramChargeOverride.builder()
            .programId(request.getProgramId())
            .chargeCode(request.getChargeCode())
            .chargeConfiguration(baseConfig)
            .overridePercentage(request.getOverridePercentage())
            .overrideFixed(request.getOverrideFixed())
            .overrideMinimum(request.getOverrideMinimum())
            .overrideMaximum(request.getOverrideMaximum())
            .isWaived(request.getIsWaived() != null ? request.getIsWaived() : false)
            .waiverReason(request.getWaiverReason())
            .waiverApprovedBy(request.getWaiverApprovedBy())
            .effectiveFrom(request.getEffectiveFrom() != null ? request.getEffectiveFrom() : LocalDate.now())
            .effectiveTo(request.getEffectiveTo())
            .notes(request.getNotes())
            .build();

        override = overrideRepository.save(override);
        log.info("Created charge override for program {} charge {}", program.getProgramCode(), request.getChargeCode());

        return toOverrideResponse(override, program, baseConfig);
    }

    @Transactional
    public OverrideResponse updateOverride(UUID overrideId, UpdateOverrideRequest request) {
        ProgramChargeOverride override = overrideRepository.findById(overrideId)
            .orElseThrow(() -> new ResourceNotFoundException("Override not found: " + overrideId));

        Program program = programRepository.findById(override.getProgramId())
            .orElseThrow(() -> new ResourceNotFoundException("Program not found"));

        ChargeConfiguration baseConfig = chargeConfigRepository.findActiveByCode(override.getChargeCode())
            .orElse(null);

        if (request.getOverridePercentage() != null) {
            override.setOverridePercentage(request.getOverridePercentage());
        }
        if (request.getOverrideFixed() != null) {
            override.setOverrideFixed(request.getOverrideFixed());
        }
        if (request.getOverrideMinimum() != null) {
            override.setOverrideMinimum(request.getOverrideMinimum());
        }
        if (request.getOverrideMaximum() != null) {
            override.setOverrideMaximum(request.getOverrideMaximum());
        }
        if (request.getIsWaived() != null) {
            override.setIsWaived(request.getIsWaived());
        }
        if (request.getWaiverReason() != null) {
            override.setWaiverReason(request.getWaiverReason());
        }
        if (request.getWaiverApprovedBy() != null) {
            override.setWaiverApprovedBy(request.getWaiverApprovedBy());
        }
        if (request.getEffectiveFrom() != null) {
            override.setEffectiveFrom(request.getEffectiveFrom());
        }
        if (request.getEffectiveTo() != null) {
            override.setEffectiveTo(request.getEffectiveTo());
        }
        if (request.getNotes() != null) {
            override.setNotes(request.getNotes());
        }
        if (request.getStatus() != null) {
            override.setStatus(request.getStatus());
        }

        override = overrideRepository.save(override);
        log.info("Updated charge override {}", overrideId);

        return toOverrideResponse(override, program, baseConfig);
    }

    @Transactional
    public void deleteOverride(UUID overrideId) {
        ProgramChargeOverride override = overrideRepository.findById(overrideId)
            .orElseThrow(() -> new ResourceNotFoundException("Override not found: " + overrideId));
        
        overrideRepository.delete(override);
        log.info("Deleted charge override {}", overrideId);
    }

    @Transactional(readOnly = true)
    public OverrideResponse getOverride(UUID overrideId) {
        ProgramChargeOverride override = overrideRepository.findById(overrideId)
            .orElseThrow(() -> new ResourceNotFoundException("Override not found: " + overrideId));

        Program program = programRepository.findById(override.getProgramId()).orElse(null);
        ChargeConfiguration baseConfig = chargeConfigRepository.findActiveByCode(override.getChargeCode()).orElse(null);

        return toOverrideResponse(override, program, baseConfig);
    }

    // ========================================================================
    // PROGRAM CHARGES VIEW
    // ========================================================================

    @Transactional(readOnly = true)
    public ProgramChargesResponse getProgramCharges(UUID programId) {
        Program program = programRepository.findById(programId)
            .orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));

        // Get all charge configs
        List<ChargeConfiguration> configs = chargeConfigRepository.findAllActive();
        
        // Get overrides for this program
        Map<String, ProgramChargeOverride> overrideMap = overrideRepository.findActiveByProgramId(programId)
            .stream()
            .collect(Collectors.toMap(ProgramChargeOverride::getChargeCode, o -> o));

        List<ChargeWithOverride> charges = configs.stream()
            .map(config -> toChargeWithOverride(config, overrideMap.get(config.getChargeCode())))
            .collect(Collectors.toList());

        int overriddenCount = (int) charges.stream().filter(c -> c.isHasOverride() && !Boolean.TRUE.equals(c.getIsWaived())).count();
        int waivedCount = (int) charges.stream().filter(c -> Boolean.TRUE.equals(c.getIsWaived())).count();

        return ProgramChargesResponse.builder()
            .programId(programId)
            .programCode(program.getProgramCode())
            .programName(program.getProgramName())
            .charges(charges)
            .totalCharges(charges.size())
            .overriddenCharges(overriddenCount)
            .waivedCharges(waivedCount)
            .build();
    }

    // ========================================================================
    // WALLET-SPECIFIC OPERATIONS
    // ========================================================================

    /**
     * Get wallet charges for a program.
     */
    @Transactional(readOnly = true)
    public WalletChargesResponse getWalletCharges(UUID programId) {
        Program program = programRepository.findById(programId)
            .orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));

        // Get wallet overrides
        Map<String, ProgramChargeOverride> overrideMap = overrideRepository
            .findStandardWalletOverridesByProgramId(programId)
            .stream()
            .collect(Collectors.toMap(ProgramChargeOverride::getChargeCode, o -> o));

        return WalletChargesResponse.builder()
            .programId(programId)
            .programCode(program.getProgramCode())
            .topup(getChargeDetail(WALLET_TOPUP, overrideMap.get(WALLET_TOPUP)))
            .withdrawal(getChargeDetail(WALLET_WITHDRAWAL, overrideMap.get(WALLET_WITHDRAWAL)))
            .transfer(getChargeDetail(WALLET_TRANSFER, overrideMap.get(WALLET_TRANSFER)))
            .issuance(getChargeDetail(WALLET_ISSUANCE, overrideMap.get(WALLET_ISSUANCE)))
            .monthly(getChargeDetail(WALLET_MONTHLY, overrideMap.get(WALLET_MONTHLY)))
            .inactivity(getChargeDetail(WALLET_INACTIVITY, overrideMap.get(WALLET_INACTIVITY)))
            .build();
    }

    /**
     * Set wallet charges for a program (bulk update).
     * 
     * UPDATED: Now handles ALL waivers for all 6 fee types:
     * - Transaction fees: topup, withdrawal, transfer
     * - Fixed fees: issuance, monthly, inactivity
     */
    @Transactional
    public WalletChargesResponse setWalletCharges(WalletChargesRequest request) {
        UUID programId = request.getProgramId();
        
        Program program = programRepository.findById(programId)
            .orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));

        // ===== TRANSACTION FEES =====
        
        // Topup fee - update if any value provided OR if waiver status is explicitly set
        if (request.getTopupFeePercent() != null || request.getTopupFeeFlat() != null || request.getWaiveTopup() != null) {
            upsertOverride(programId, WALLET_TOPUP, 
                request.getTopupFeePercent(), request.getTopupFeeFlat(),
                Boolean.TRUE.equals(request.getWaiveTopup()));
        }

        // Withdrawal fee
        if (request.getWithdrawalFeePercent() != null || request.getWithdrawalFeeFlat() != null || request.getWaiveWithdrawal() != null) {
            upsertOverride(programId, WALLET_WITHDRAWAL,
                request.getWithdrawalFeePercent(), request.getWithdrawalFeeFlat(),
                Boolean.TRUE.equals(request.getWaiveWithdrawal()));
        }

        // Transfer fee
        if (request.getTransferFeePercent() != null || request.getTransferFeeFlat() != null || request.getWaiveTransfer() != null) {
            upsertOverride(programId, WALLET_TRANSFER,
                request.getTransferFeePercent(), request.getTransferFeeFlat(),
                Boolean.TRUE.equals(request.getWaiveTransfer()));
        }

        // ===== FIXED FEES =====

        // Issuance fee - now handles waiver
        if (request.getIssuanceFee() != null || request.getWaiveIssuance() != null) {
            upsertOverride(programId, WALLET_ISSUANCE, 
                null, request.getIssuanceFee(), 
                Boolean.TRUE.equals(request.getWaiveIssuance()));
        }

        // Monthly fee - now handles waiver
        if (request.getMonthlyFee() != null || request.getWaiveMonthly() != null) {
            upsertOverride(programId, WALLET_MONTHLY, 
                null, request.getMonthlyFee(), 
                Boolean.TRUE.equals(request.getWaiveMonthly()));
        }

        // Inactivity fee - NEW: now supported
        if (request.getInactivityFee() != null || request.getWaiveInactivity() != null) {
            upsertOverride(programId, WALLET_INACTIVITY, 
                null, request.getInactivityFee(), 
                Boolean.TRUE.equals(request.getWaiveInactivity()));
        }

        log.info("Updated wallet charges for program {} - waivers: topup={}, withdrawal={}, transfer={}, issuance={}, monthly={}, inactivity={}",
            program.getProgramCode(),
            request.getWaiveTopup(),
            request.getWaiveWithdrawal(),
            request.getWaiveTransfer(),
            request.getWaiveIssuance(),
            request.getWaiveMonthly(),
            request.getWaiveInactivity());

        return getWalletCharges(programId);
    }

    /**
     * Migrate legacy wallet fees from Program to ProgramChargeOverride.
     * Call this during program creation or upgrade.
     */
    @Transactional
    public void migrateFromProgramFees(UUID programId) {
        Program program = programRepository.findById(programId)
            .orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));

        if (!program.isWalletProgram()) {
            log.debug("Program {} is not a wallet program, skipping fee migration", program.getProgramCode());
            return;
        }

        // Migrate topup fee
        if (hasValue(program.getTopupFeePercent()) || hasValue(program.getTopupFeeFlat())) {
            upsertOverride(programId, WALLET_TOPUP, 
                program.getTopupFeePercent(), program.getTopupFeeFlat(), false);
        }

        // Migrate withdrawal fee
        if (hasValue(program.getWithdrawalFeePercent()) || hasValue(program.getWithdrawalFeeFlat())) {
            upsertOverride(programId, WALLET_WITHDRAWAL,
                program.getWithdrawalFeePercent(), program.getWithdrawalFeeFlat(), false);
        }

        // Migrate transfer fee
        if (hasValue(program.getTransferFeePercent()) || hasValue(program.getTransferFeeFlat())) {
            upsertOverride(programId, WALLET_TRANSFER,
                program.getTransferFeePercent(), program.getTransferFeeFlat(), false);
        }

        // Migrate issuance fee
        if (hasValue(program.getIssuanceFee())) {
            upsertOverride(programId, WALLET_ISSUANCE, null, program.getIssuanceFee(), false);
        }

        // Migrate monthly fee
        if (hasValue(program.getMonthlyFee())) {
            upsertOverride(programId, WALLET_MONTHLY, null, program.getMonthlyFee(), false);
        }

        log.info("Migrated wallet fees from program {} to charge overrides", program.getProgramCode());
    }

    // ========================================================================
    // FEE CALCULATION
    // ========================================================================

    /**
     * Calculate charge for a program, applying any overrides.
     */
    @Transactional(readOnly = true)
    public CalculateChargeResponse calculateCharge(CalculateChargeRequest request) {
        ChargeConfiguration baseConfig = chargeConfigRepository.findActiveByCode(request.getChargeCode())
            .orElseThrow(() -> new ResourceNotFoundException("Charge not found: " + request.getChargeCode()));

        Optional<ProgramChargeOverride> override = overrideRepository
            .findActiveByProgramIdAndChargeCode(request.getProgramId(), request.getChargeCode());

        BigDecimal charge;
        boolean wasOverridden = false;
        boolean wasWaived = false;
        String source = "BASE";
        BigDecimal percentageUsed = baseConfig.getPercentageRate();
        BigDecimal fixedUsed = baseConfig.getFixedAmount();

        if (override.isPresent()) {
            ProgramChargeOverride o = override.get();
            
            if (Boolean.TRUE.equals(o.getIsWaived())) {
                charge = BigDecimal.ZERO;
                wasWaived = true;
                source = "WAIVED";
            } else {
                charge = o.calculateCharge(request.getAmount(), baseConfig);
                wasOverridden = o.hasOverrides();
                source = wasOverridden ? "OVERRIDE" : "BASE";
                if (o.getOverridePercentage() != null) percentageUsed = o.getOverridePercentage();
                if (o.getOverrideFixed() != null) fixedUsed = o.getOverrideFixed();
            }
        } else {
            // Check base config waiver rules
            if (baseConfig.shouldWaive(request.getAmount(), 
                    Boolean.TRUE.equals(request.getIsVip()),
                    Boolean.TRUE.equals(request.getIsBulk()))) {
                charge = BigDecimal.ZERO;
                wasWaived = true;
                source = "WAIVED";
            } else {
                charge = baseConfig.calculateCharge(request.getAmount());
            }
        }

        return CalculateChargeResponse.builder()
            .chargeCode(request.getChargeCode())
            .chargeName(baseConfig.getChargeName())
            .baseAmount(request.getAmount())
            .calculatedCharge(wasWaived ? BigDecimal.ZERO : charge)
            .waivedAmount(wasWaived ? charge : BigDecimal.ZERO)
            .finalCharge(charge)
            .currencyCode(baseConfig.getCurrencyCode())
            .percentageUsed(percentageUsed)
            .fixedUsed(fixedUsed)
            .source(source)
            .wasOverridden(wasOverridden)
            .wasWaived(wasWaived)
            .build();
    }

    /**
     * Calculate all wallet fees for given amounts.
     */
    @Transactional(readOnly = true)
    public CalculateWalletFeesResponse calculateWalletFees(CalculateWalletFeesRequest request) {
        CalculateChargeResponse topupFee = null;
        CalculateChargeResponse withdrawalFee = null;
        CalculateChargeResponse transferFee = null;
        BigDecimal totalFees = BigDecimal.ZERO;

        if (request.getTopupAmount() != null && request.getTopupAmount().compareTo(BigDecimal.ZERO) > 0) {
            topupFee = calculateCharge(CalculateChargeRequest.builder()
                .programId(request.getProgramId())
                .chargeCode(WALLET_TOPUP)
                .amount(request.getTopupAmount())
                .isVip(request.getIsVip())
                .build());
            totalFees = totalFees.add(topupFee.getFinalCharge());
        }

        if (request.getWithdrawalAmount() != null && request.getWithdrawalAmount().compareTo(BigDecimal.ZERO) > 0) {
            withdrawalFee = calculateCharge(CalculateChargeRequest.builder()
                .programId(request.getProgramId())
                .chargeCode(WALLET_WITHDRAWAL)
                .amount(request.getWithdrawalAmount())
                .isVip(request.getIsVip())
                .build());
            totalFees = totalFees.add(withdrawalFee.getFinalCharge());
        }

        if (request.getTransferAmount() != null && request.getTransferAmount().compareTo(BigDecimal.ZERO) > 0) {
            transferFee = calculateCharge(CalculateChargeRequest.builder()
                .programId(request.getProgramId())
                .chargeCode(WALLET_TRANSFER)
                .amount(request.getTransferAmount())
                .isVip(request.getIsVip())
                .build());
            totalFees = totalFees.add(transferFee.getFinalCharge());
        }

        return CalculateWalletFeesResponse.builder()
            .programId(request.getProgramId())
            .topupFee(topupFee)
            .withdrawalFee(withdrawalFee)
            .transferFee(transferFee)
            .totalFees(totalFees)
            .currencyCode("AED")
            .build();
    }

    // ========================================================================
    // CHARGE CONFIG INITIALIZATION
    // ========================================================================

    /**
     * Initialize wallet charge configurations if they don't exist.
     */
    @Transactional
    public void initializeWalletChargeConfigs() {
        createChargeConfigIfNotExists(WALLET_TOPUP, "Wallet Topup Fee", 
            ChargeConfiguration.ChargeType.TRANSACTION_FEE, new BigDecimal("1.5"), BigDecimal.ZERO);
        
        createChargeConfigIfNotExists(WALLET_WITHDRAWAL, "Wallet Withdrawal Fee",
            ChargeConfiguration.ChargeType.TRANSACTION_FEE, new BigDecimal("2.0"), new BigDecimal("5.00"));
        
        createChargeConfigIfNotExists(WALLET_TRANSFER, "Wallet Transfer Fee",
            ChargeConfiguration.ChargeType.TRANSACTION_FEE, new BigDecimal("0.5"), new BigDecimal("1.00"));
        
        createChargeConfigIfNotExists(WALLET_ISSUANCE, "Wallet Issuance Fee",
            ChargeConfiguration.ChargeType.SERVICE_FEE, null, new BigDecimal("10.00"));
        
        createChargeConfigIfNotExists(WALLET_MONTHLY, "Wallet Monthly Fee",
            ChargeConfiguration.ChargeType.MAINTENANCE_FEE, null, new BigDecimal("5.00"));
        
        createChargeConfigIfNotExists(WALLET_INACTIVITY, "Wallet Inactivity Fee",
            ChargeConfiguration.ChargeType.MAINTENANCE_FEE, null, new BigDecimal("10.00"));

        log.info("Wallet charge configurations initialized");
    }

    // ========================================================================
    // PRIVATE HELPER METHODS
    // ========================================================================

    private void createChargeConfigIfNotExists(String chargeCode, String chargeName,
                                                ChargeConfiguration.ChargeType type,
                                                BigDecimal percentageRate, BigDecimal fixedAmount) {
        if (chargeConfigRepository.findByChargeCode(chargeCode).isEmpty()) {
            ChargeConfiguration config = ChargeConfiguration.builder()
                .chargeCode(chargeCode)
                .chargeName(chargeName)
                .chargeType(type)
                .chargeCategory(percentageRate != null ? 
                    ChargeConfiguration.ChargeCategory.PERCENTAGE : 
                    ChargeConfiguration.ChargeCategory.FIXED)
                .percentageRate(percentageRate != null ? percentageRate : BigDecimal.ZERO)
                .fixedAmount(fixedAmount != null ? fixedAmount : BigDecimal.ZERO)
                .minimumCharge(new BigDecimal("1.00"))
                .currencyCode("AED")
                .build();
            chargeConfigRepository.save(config);
            log.debug("Created charge configuration: {}", chargeCode);
        }
    }

    /**
     * Upsert (create or update) a charge override.
     * Now properly handles waiver-only updates.
     */
    private void upsertOverride(UUID programId, String chargeCode, 
                                BigDecimal percentage, BigDecimal fixed, boolean waived) {
        Optional<ProgramChargeOverride> existing = overrideRepository
            .findByProgramIdAndChargeCode(programId, chargeCode);

        ProgramChargeOverride override;
        if (existing.isPresent()) {
            override = existing.get();
            // Only update percentage/fixed if provided (non-null)
            // This allows waiver-only updates without clearing fee overrides
            if (percentage != null) {
                override.setOverridePercentage(percentage);
            }
            if (fixed != null) {
                override.setOverrideFixed(fixed);
            }
            // Always update waiver status
            override.setIsWaived(waived);
            override.setStatus(ProgramChargeOverride.OverrideStatus.ACTIVE);
        } else {
            override = ProgramChargeOverride.builder()
                .programId(programId)
                .chargeCode(chargeCode)
                .overridePercentage(percentage)
                .overrideFixed(fixed)
                .isWaived(waived)
                .build();
        }

        overrideRepository.save(override);
        log.debug("Upserted override for {} - waived: {}", chargeCode, waived);
    }

    private ChargeDetail getChargeDetail(String chargeCode, ProgramChargeOverride override) {
        ChargeConfiguration baseConfig = chargeConfigRepository.findActiveByCode(chargeCode)
            .orElse(null);

        if (baseConfig == null) {
            return ChargeDetail.builder()
                .chargeCode(chargeCode)
                .chargeName("Not Configured")
                .percentage(BigDecimal.ZERO)
                .fixed(BigDecimal.ZERO)
                .isWaived(false)
                .hasOverride(false)
                .source("NONE")
                .build();
        }

        if (override != null && override.isActive()) {
            boolean hasValueOverride = override.getOverridePercentage() != null || override.getOverrideFixed() != null;
            return ChargeDetail.builder()
                .chargeCode(chargeCode)
                .chargeName(baseConfig.getChargeName())
                .percentage(override.getOverridePercentage() != null ? 
                    override.getOverridePercentage() : baseConfig.getPercentageRate())
                .fixed(override.getOverrideFixed() != null ? 
                    override.getOverrideFixed() : baseConfig.getFixedAmount())
                .minimum(override.getOverrideMinimum() != null ?
                    override.getOverrideMinimum() : baseConfig.getMinimumCharge())
                .maximum(override.getOverrideMaximum() != null ?
                    override.getOverrideMaximum() : baseConfig.getMaximumCharge())
                .isWaived(Boolean.TRUE.equals(override.getIsWaived()))
                .hasOverride(hasValueOverride || Boolean.TRUE.equals(override.getIsWaived()))
                .source(hasValueOverride || Boolean.TRUE.equals(override.getIsWaived()) ? "OVERRIDE" : "BASE")
                .build();
        }

        return ChargeDetail.builder()
            .chargeCode(chargeCode)
            .chargeName(baseConfig.getChargeName())
            .percentage(baseConfig.getPercentageRate())
            .fixed(baseConfig.getFixedAmount())
            .minimum(baseConfig.getMinimumCharge())
            .maximum(baseConfig.getMaximumCharge())
            .isWaived(false)
            .hasOverride(false)
            .source("BASE")
            .build();
    }

    private boolean hasValue(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private OverrideResponse toOverrideResponse(ProgramChargeOverride override, 
                                                 Program program, 
                                                 ChargeConfiguration baseConfig) {
        return OverrideResponse.builder()
            .id(override.getId())
            .programId(override.getProgramId())
            .programCode(program != null ? program.getProgramCode() : null)
            .programName(program != null ? program.getProgramName() : null)
            .chargeCode(override.getChargeCode())
            .chargeName(baseConfig != null ? baseConfig.getChargeName() : null)
            .chargeType(baseConfig != null ? baseConfig.getChargeType() : null)
            .chargeCategory(baseConfig != null ? baseConfig.getChargeCategory() : null)
            .basePercentage(baseConfig != null ? baseConfig.getPercentageRate() : null)
            .baseFixed(baseConfig != null ? baseConfig.getFixedAmount() : null)
            .baseMinimum(baseConfig != null ? baseConfig.getMinimumCharge() : null)
            .baseMaximum(baseConfig != null ? baseConfig.getMaximumCharge() : null)
            .overridePercentage(override.getOverridePercentage())
            .overrideFixed(override.getOverrideFixed())
            .overrideMinimum(override.getOverrideMinimum())
            .overrideMaximum(override.getOverrideMaximum())
            .effectivePercentage(override.getOverridePercentage() != null ? 
                override.getOverridePercentage() : 
                (baseConfig != null ? baseConfig.getPercentageRate() : null))
            .effectiveFixed(override.getOverrideFixed() != null ?
                override.getOverrideFixed() :
                (baseConfig != null ? baseConfig.getFixedAmount() : null))
            .effectiveMinimum(override.getOverrideMinimum() != null ?
                override.getOverrideMinimum() :
                (baseConfig != null ? baseConfig.getMinimumCharge() : null))
            .effectiveMaximum(override.getOverrideMaximum() != null ?
                override.getOverrideMaximum() :
                (baseConfig != null ? baseConfig.getMaximumCharge() : null))
            .isWaived(override.getIsWaived())
            .waiverReason(override.getWaiverReason())
            .waiverApprovedBy(override.getWaiverApprovedBy())
            .effectiveFrom(override.getEffectiveFrom())
            .effectiveTo(override.getEffectiveTo())
            .status(override.getStatus().name())
            .notes(override.getNotes())
            .createdAt(override.getCreatedAt())
            .updatedAt(override.getUpdatedAt())
            .build();
    }

    private ChargeWithOverride toChargeWithOverride(ChargeConfiguration config, 
                                                     ProgramChargeOverride override) {
        boolean hasOverride = override != null && override.isActive();

        return ChargeWithOverride.builder()
            .chargeCode(config.getChargeCode())
            .chargeName(config.getChargeName())
            .chargeType(config.getChargeType())
            .chargeCategory(config.getChargeCategory())
            .basePercentage(config.getPercentageRate())
            .baseFixed(config.getFixedAmount())
            .baseMinimum(config.getMinimumCharge())
            .baseMaximum(config.getMaximumCharge())
            .overrideId(hasOverride ? override.getId() : null)
            .overridePercentage(hasOverride ? override.getOverridePercentage() : null)
            .overrideFixed(hasOverride ? override.getOverrideFixed() : null)
            .overrideMinimum(hasOverride ? override.getOverrideMinimum() : null)
            .overrideMaximum(hasOverride ? override.getOverrideMaximum() : null)
            .isWaived(hasOverride ? override.getIsWaived() : false)
            .effectivePercentage(hasOverride && override.getOverridePercentage() != null ?
                override.getOverridePercentage() : config.getPercentageRate())
            .effectiveFixed(hasOverride && override.getOverrideFixed() != null ?
                override.getOverrideFixed() : config.getFixedAmount())
            .effectiveMinimum(hasOverride && override.getOverrideMinimum() != null ?
                override.getOverrideMinimum() : config.getMinimumCharge())
            .effectiveMaximum(hasOverride && override.getOverrideMaximum() != null ?
                override.getOverrideMaximum() : config.getMaximumCharge())
            .hasOverride(hasOverride)
            .isActive(config.isActive())
            .build();
    }
}